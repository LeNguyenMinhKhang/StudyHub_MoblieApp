begin;

-- ============================================================
-- StudyHub - User Block Settings
-- 1. Block state
-- 2. Block / unblock user
-- 3. Remove friendship and pending request atomically
-- 4. Clean up related friend-request notifications
-- 5. List blocked users with cursor pagination
-- ============================================================

create schema if not exists studyhub_private;

-- ------------------------------------------------------------
-- 1. Snapshot trạng thái Block
-- ------------------------------------------------------------

create or replace function studyhub_private.block_snapshot(
    p_actor uuid,
    p_target uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_blocked_by_me boolean;
    v_blocked_me boolean;
begin
    if p_target is null or p_target = p_actor then
        raise exception 'invalid target' using errcode = 'F0001';
    end if;

    if not exists (
        select 1
        from public.profiles
        where id = p_target
    ) then
        raise exception 'target unavailable' using errcode = 'F0002';
    end if;

    select exists (
        select 1 from public.blocks
        where blocker_id = p_actor and blocked_id = p_target
    ) into v_blocked_by_me;

    select exists (
        select 1 from public.blocks
        where blocker_id = p_target and blocked_id = p_actor
    ) into v_blocked_me;

    return jsonb_build_object(
        'target_id', p_target,
        'blocked_by_me', v_blocked_by_me,
        'blocked_me', v_blocked_me,
        'is_blocked', v_blocked_by_me or v_blocked_me
    );
end;
$$;

-- ------------------------------------------------------------
-- 2. Lấy trạng thái Block
-- ------------------------------------------------------------

create or replace function public.get_block_state(
    p_target_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
begin
    return studyhub_private.block_snapshot(v_actor, p_target_id);
end;
$$;

-- ------------------------------------------------------------
-- 3. Block user
-- ------------------------------------------------------------

create or replace function public.block_user(
    p_target_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_relation public.friendships;
begin
    if p_target_id is null or p_target_id = v_actor then
        raise exception 'invalid target' using errcode = 'F0001';
    end if;

    if not exists (
        select 1
        from public.profiles
        where id = p_target_id
          and not is_banned
    ) then
        raise exception 'peer unavailable' using errcode = 'F0002';
    end if;

    -- Serialize các thao tác Block trên cùng một cặp người dùng.
    perform pg_advisory_xact_lock(
        hashtextextended(
            least(v_actor::text, p_target_id::text)
            || ':'
            || greatest(v_actor::text, p_target_id::text),
            0
        )
    );

    -- Ngăn thao tác Friendship đồng thời vượt qua thời điểm Block.
    lock table public.friendships in share row exclusive mode;

    -- Tạo Block. Nếu đã tồn tại thì không tạo bản ghi trùng.
    insert into public.blocks(blocker_id, blocked_id)
    values (v_actor, p_target_id)
    on conflict (blocker_id, blocked_id) do nothing;

    -- Xóa quan hệ bạn bè hoặc lời mời đang chờ giữa hai người.
    delete from public.friendships
    where least(requester_id, addressee_id)
              = least(v_actor, p_target_id)
      and greatest(requester_id, addressee_id)
              = greatest(v_actor, p_target_id)
    returning * into v_relation;

    -- Dọn thông báo lời mời kết bạn liên quan.
    if found then
        delete from public.notifications
        where type = 'friend_request'
          and user_id = v_relation.addressee_id
          and actor_id = v_relation.requester_id
          and (
              source_id = v_relation.id
              or source_id is null
          );
    end if;

    return studyhub_private.block_snapshot(
        v_actor,
        p_target_id
    );
end;
$$;

-- ------------------------------------------------------------
-- 4. Unblock user
-- ------------------------------------------------------------

create or replace function public.unblock_user(
    p_target_id uuid
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
begin
    if p_target_id is null or p_target_id = v_actor then
        raise exception 'invalid target' using errcode = 'F0001';
    end if;

    perform pg_advisory_xact_lock(
        hashtextextended(
            least(v_actor::text, p_target_id::text)
            || ':'
            || greatest(v_actor::text, p_target_id::text),
            0
        )
    );

    -- Chỉ xóa bản ghi do chính actor tạo.
    delete from public.blocks
    where blocker_id = v_actor
      and blocked_id = p_target_id;

    return studyhub_private.block_snapshot(
        v_actor,
        p_target_id
    );
end;
$$;

-- ------------------------------------------------------------
-- 5. Danh sách người tôi đã Block
-- ------------------------------------------------------------

create or replace function public.list_blocked_users(
    p_limit integer default 20,
    p_cursor_created_at timestamptz default null,
    p_cursor_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_actor uuid := studyhub_private.friend_actor();
    v_page_size integer;
    v_result jsonb;
begin
    if p_limit is null
       or p_limit <= 0
       or ((p_cursor_created_at is null) <> (p_cursor_id is null))
       or (
           p_cursor_created_at is not null
           and not isfinite(p_cursor_created_at)
       ) then
        raise exception 'invalid page input' using errcode = 'F0001';
    end if;

    v_page_size := least(p_limit, 50);

    with candidates as materialized (
        select
            b.id,
            b.created_at,
            jsonb_build_object(
                'id', p.id,
                'username', p.username,
                'display_name', p.display_name,
                'avatar_url', p.avatar_url
            ) as peer
        from public.blocks b
        join public.profiles p
            on p.id = b.blocked_id
        where b.blocker_id = v_actor
          and (
              p_cursor_id is null
              or (b.created_at, b.id)
                 < (p_cursor_created_at, p_cursor_id)
          )
        order by b.created_at desc, b.id desc
        limit v_page_size + 1
    ),
    page as (
        select *
        from candidates
        order by created_at desc, id desc
        limit v_page_size
    )
    select jsonb_build_object(
        'items',
        coalesce(
            (
                select jsonb_agg(
                    to_jsonb(page)
                    order by created_at desc, id desc
                )
                from page
            ),
            '[]'::jsonb
        ),
        'next_cursor',
        case
            when (select count(*) from candidates) > v_page_size
            then (
                select jsonb_build_object(
                    'created_at', created_at,
                    'id', id
                )
                from page
                order by created_at, id
                limit 1
            )
            else null
        end
    )
    into v_result;

    return v_result;
end;
$$;

-- ------------------------------------------------------------
-- 6. Chỉ sử dụng RPC, không cho client thao tác trực tiếp
-- ------------------------------------------------------------

revoke all on public.blocks
from public, anon, authenticated;

revoke all on function studyhub_private.block_snapshot(uuid, uuid)
from public, anon, authenticated;

revoke all on function public.get_block_state(uuid),
    public.block_user(uuid),
    public.unblock_user(uuid),
    public.list_blocked_users(integer, timestamptz, uuid)
from public, anon, authenticated;

grant execute on function public.get_block_state(uuid),
    public.block_user(uuid),
    public.unblock_user(uuid),
    public.list_blocked_users(integer, timestamptz, uuid)
to authenticated;

notify pgrst, 'reload schema';

commit;