-- Task 27: post/comment reactions and Q&A best-answer RPCs.
-- Safe to apply after the notification-center and user-block migrations.
-- Existing likes are preserved and backfilled as the "like" reaction.
begin;

-- ---------------------------------------------------------------------------
-- 1. Post reactions: reuse likes so existing like rows and notification
--    INSERT/DELETE triggers remain compatible.
-- ---------------------------------------------------------------------------
alter table public.likes
  add column if not exists reaction_type text not null default 'like';

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conrelid = 'public.likes'::regclass
      and conname = 'likes_reaction_type_check'
  ) then
    alter table public.likes
      add constraint likes_reaction_type_check
      check (reaction_type in ('like', 'love', 'haha', 'sad', 'angry'));
  end if;
end
$$;

create index if not exists likes_post_reaction_idx
  on public.likes(post_id, reaction_type, created_at desc);

-- ---------------------------------------------------------------------------
-- 2. Comment reactions: separate from likes because a comment reaction must
--    not be confused with the post-like notification source.
-- ---------------------------------------------------------------------------
create table if not exists public.comment_reactions (
  id uuid primary key default gen_random_uuid(),
  comment_id uuid not null references public.comments(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  reaction_type text not null
    check (reaction_type in ('like', 'love', 'haha', 'sad', 'angry')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (comment_id, user_id)
);

create index if not exists comment_reactions_comment_type_idx
  on public.comment_reactions(comment_id, reaction_type, created_at desc);

alter table public.comment_reactions enable row level security;

-- All client access goes through visibility-aware RPCs. This avoids exposing
-- reactions belonging to posts that the current viewer cannot see.
drop policy if exists comment_reactions_select_all on public.comment_reactions;
drop policy if exists comment_reactions_select_visible on public.comment_reactions;
drop policy if exists comment_reactions_insert_own on public.comment_reactions;
drop policy if exists comment_reactions_update_own on public.comment_reactions;
drop policy if exists comment_reactions_delete_own on public.comment_reactions;
revoke all on public.comment_reactions from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 3. Repair historical best-answer state before enforcing one answer per post.
--    Prefer posts.best_comment_id when it points to a comment of that post;
--    otherwise retain the newest legacy flagged comment.
-- ---------------------------------------------------------------------------
update public.comments c
set is_best_answer = (
  c.id = coalesce(
    (
      select p.best_comment_id
      from public.posts p
      where p.id = c.post_id
        and p.best_comment_id = c.id
    ),
    (
      select c2.id
      from public.comments c2
      where c2.post_id = c.post_id
        and c2.is_best_answer
      order by c2.created_at desc, c2.id desc
      limit 1
    )
  )
)
where c.is_best_answer
   or exists (
     select 1 from public.posts p
     where p.id = c.post_id and p.best_comment_id = c.id
   );

-- Remove stale/cross-post pointers. This also avoids a dangling pointer when
-- legacy data was written without validating comment.post_id.
update public.posts p
set best_comment_id = null
where p.best_comment_id is not null
  and not exists (
    select 1 from public.comments c
    where c.id = p.best_comment_id
      and c.post_id = p.id
  );

-- Reconcile the pointer to the single retained flag.
update public.posts p
set best_comment_id = (
  select c.id
  from public.comments c
  where c.post_id = p.id and c.is_best_answer
  limit 1
)
where p.best_comment_id is distinct from (
  select c.id
  from public.comments c
  where c.post_id = p.id and c.is_best_answer
  limit 1
);

create unique index if not exists comments_one_best_answer_per_post_idx
  on public.comments(post_id)
  where is_best_answer;

-- ---------------------------------------------------------------------------
-- 4. Post reaction mutation and summary RPCs.
-- ---------------------------------------------------------------------------
create or replace function public.set_post_reaction(
  p_post_id uuid,
  p_reaction_type text
) returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_existing text;
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_post_id is null
     or (p_reaction_type is not null
         and p_reaction_type not in ('like', 'love', 'haha', 'sad', 'angry')) then
    raise exception 'invalid reaction input' using errcode = 'F0001';
  end if;
  if not exists (
    select 1 from public.profiles p
    where p.id = v_actor and not p.is_banned
  ) then
    raise exception 'account unavailable' using errcode = 'F0006';
  end if;
  if not studyhub_private.post_viewable(v_actor, p_post_id) then
    raise exception 'post unavailable' using errcode = 'F0004';
  end if;

  select l.reaction_type into v_existing
  from public.likes l
  where l.post_id = p_post_id and l.user_id = v_actor
  for update;

  if p_reaction_type is null then
    delete from public.likes
    where post_id = p_post_id and user_id = v_actor;
    return null;
  end if;

  insert into public.likes(post_id, user_id, reaction_type)
  values (p_post_id, v_actor, p_reaction_type)
  on conflict (post_id, user_id)
  do update set reaction_type = excluded.reaction_type
  returning reaction_type into v_existing;

  return v_existing;
end;
$$;

create or replace function public.get_post_reaction_summaries(
  p_post_ids uuid[]
) returns table (
  post_id uuid,
  total_count bigint,
  counts_by_type jsonb,
  current_user_reaction text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_post_ids is null or cardinality(p_post_ids) > 200 then
    raise exception 'invalid post id list' using errcode = 'F0001';
  end if;
  if exists (
    select 1 from unnest(p_post_ids) as requested(id)
    where not studyhub_private.post_viewable(v_actor, requested.id)
  ) then
    raise exception 'post unavailable' using errcode = 'F0004';
  end if;

  return query
  select requested.id,
         count(l.id)::bigint,
         jsonb_build_object(
           'like',  count(l.id) filter (where l.reaction_type = 'like'),
           'love',  count(l.id) filter (where l.reaction_type = 'love'),
           'haha',  count(l.id) filter (where l.reaction_type = 'haha'),
           'sad',   count(l.id) filter (where l.reaction_type = 'sad'),
           'angry', count(l.id) filter (where l.reaction_type = 'angry')
         ),
         max(l.reaction_type) filter (where l.user_id = v_actor)
  from (select distinct unnest(p_post_ids) as id) requested
  left join public.likes l on l.post_id = requested.id
  group by requested.id;
end;
$$;

create or replace function public.get_post_reactors(
  p_post_id uuid,
  p_reaction_type text default null,
  p_limit integer default 50,
  p_offset integer default 0
) returns table (
  id uuid,
  username text,
  display_name text,
  avatar_url text,
  bio text,
  created_at timestamptz,
  reaction_type text,
  reacted_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_post_id is null
     or (p_reaction_type is not null
         and p_reaction_type not in ('like', 'love', 'haha', 'sad', 'angry'))
     or p_limit is null or p_limit <= 0
     or p_offset is null or p_offset < 0 then
    raise exception 'invalid reactor query' using errcode = 'F0001';
  end if;
  if not studyhub_private.post_viewable(v_actor, p_post_id) then
    raise exception 'post unavailable' using errcode = 'F0004';
  end if;

  return query
  select p.id, p.username, p.display_name, p.avatar_url, p.bio, p.created_at,
         l.reaction_type, l.created_at
  from public.likes l
  join public.profiles p on p.id = l.user_id and not p.is_banned
  where l.post_id = p_post_id
    and (p_reaction_type is null or l.reaction_type = p_reaction_type)
  order by l.created_at desc, l.id
  limit least(p_limit, 100) offset p_offset;
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. Comment reaction mutation, summaries and reactor list.
-- ---------------------------------------------------------------------------
create or replace function public.set_comment_reaction(
  p_comment_id uuid,
  p_reaction_type text
) returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_post_id uuid;
  v_result text;
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_comment_id is null
     or (p_reaction_type is not null
         and p_reaction_type not in ('like', 'love', 'haha', 'sad', 'angry')) then
    raise exception 'invalid reaction input' using errcode = 'F0001';
  end if;
  if not exists (
    select 1 from public.profiles p
    where p.id = v_actor and not p.is_banned
  ) then
    raise exception 'account unavailable' using errcode = 'F0006';
  end if;

  select c.post_id into v_post_id
  from public.comments c
  where c.id = p_comment_id;

  if v_post_id is null or not studyhub_private.post_viewable(v_actor, v_post_id) then
    raise exception 'comment unavailable' using errcode = 'F0004';
  end if;

  if p_reaction_type is null then
    delete from public.comment_reactions
    where comment_id = p_comment_id and user_id = v_actor;
    return null;
  end if;

  insert into public.comment_reactions(comment_id, user_id, reaction_type, updated_at)
  values (p_comment_id, v_actor, p_reaction_type, now())
  on conflict (comment_id, user_id)
  do update set reaction_type = excluded.reaction_type, updated_at = now()
  returning reaction_type into v_result;

  return v_result;
end;
$$;

create or replace function public.get_comment_reaction_summaries(
  p_comment_ids uuid[]
) returns table (
  comment_id uuid,
  total_count bigint,
  counts_by_type jsonb,
  current_user_reaction text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_comment_ids is null or cardinality(p_comment_ids) > 500 then
    raise exception 'invalid comment id list' using errcode = 'F0001';
  end if;
  if exists (
    select 1
    from public.comments c
    where c.id = any(p_comment_ids)
      and not studyhub_private.post_viewable(v_actor, c.post_id)
  ) then
    raise exception 'comment unavailable' using errcode = 'F0004';
  end if;

  return query
  select requested.id,
         count(r.id)::bigint,
         jsonb_build_object(
           'like',  count(r.id) filter (where r.reaction_type = 'like'),
           'love',  count(r.id) filter (where r.reaction_type = 'love'),
           'haha',  count(r.id) filter (where r.reaction_type = 'haha'),
           'sad',   count(r.id) filter (where r.reaction_type = 'sad'),
           'angry', count(r.id) filter (where r.reaction_type = 'angry')
         ),
         max(r.reaction_type) filter (where r.user_id = v_actor)
  from (select distinct unnest(p_comment_ids) as id) requested
  left join public.comment_reactions r on r.comment_id = requested.id
  group by requested.id;
end;
$$;

create or replace function public.get_comment_reactors(
  p_comment_id uuid,
  p_reaction_type text default null,
  p_limit integer default 50,
  p_offset integer default 0
) returns table (
  id uuid,
  username text,
  display_name text,
  avatar_url text,
  bio text,
  created_at timestamptz,
  reaction_type text,
  reacted_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_post_id uuid;
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_comment_id is null
     or (p_reaction_type is not null
         and p_reaction_type not in ('like', 'love', 'haha', 'sad', 'angry'))
     or p_limit is null or p_limit <= 0
     or p_offset is null or p_offset < 0 then
    raise exception 'invalid reactor query' using errcode = 'F0001';
  end if;

  select c.post_id into v_post_id
  from public.comments c
  where c.id = p_comment_id;

  if v_post_id is null or not studyhub_private.post_viewable(v_actor, v_post_id) then
    raise exception 'comment unavailable' using errcode = 'F0004';
  end if;

  return query
  select p.id, p.username, p.display_name, p.avatar_url, p.bio, p.created_at,
         r.reaction_type, r.created_at
  from public.comment_reactions r
  join public.profiles p on p.id = r.user_id and not p.is_banned
  where r.comment_id = p_comment_id
    and (p_reaction_type is null or r.reaction_type = p_reaction_type)
  order by r.created_at desc, r.id
  limit least(p_limit, 100) offset p_offset;
end;
$$;

-- ---------------------------------------------------------------------------
-- 6. Atomic best-answer RPC. Direct client updates to is_best_answer are
--    removed; comment authors retain their own-content update policy.
-- ---------------------------------------------------------------------------
drop policy if exists "comments_update_best_answer_by_post_owner" on public.comments;

-- Prevent clients from changing is_best_answer directly. Comment authors may
-- still edit content; the SECURITY DEFINER RPC owns best-answer transitions.
revoke update on public.comments from public, anon, authenticated;
grant update (content) on public.comments to authenticated;

create or replace function public.set_best_answer(
  p_post_id uuid,
  p_comment_id uuid
) returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid := auth.uid();
  v_post_author uuid;
  v_old_best uuid;
  v_comment_author uuid;
  v_comment_post uuid;
  v_is_banned boolean;
  v_enabled boolean;
begin
  if v_actor is null then
    raise exception 'authentication required' using errcode = 'F0005';
  end if;
  if p_post_id is null then
    raise exception 'invalid post id' using errcode = 'F0001';
  end if;

  select p.author_id, p.best_comment_id
    into v_post_author, v_old_best
  from public.posts p
  where p.id = p_post_id
  for update;

  if v_post_author is null or v_post_author <> v_actor then
    raise exception 'only post owner can choose best answer' using errcode = '42501';
  end if;

  select pr.is_banned into v_is_banned
  from public.profiles pr where pr.id = v_actor;
  if coalesce(v_is_banned, true) then
    raise exception 'account unavailable' using errcode = 'F0006';
  end if;
  if not studyhub_private.post_viewable(v_actor, p_post_id) then
    raise exception 'post unavailable' using errcode = 'F0004';
  end if;

  if p_comment_id is not null then
    select c.post_id, c.user_id
      into v_comment_post, v_comment_author
    from public.comments c
    where c.id = p_comment_id
    for update;

    if v_comment_post is distinct from p_post_id then
      raise exception 'comment does not belong to post' using errcode = 'F0001';
    end if;
  end if;

  if v_old_best is not distinct from p_comment_id then
    return jsonb_build_object('post_id', p_post_id, 'best_comment_id', p_comment_id);
  end if;

  update public.comments
  set is_best_answer = false
  where post_id = p_post_id and is_best_answer;

  if p_comment_id is not null then
    update public.comments
    set is_best_answer = true
    where id = p_comment_id;

    update public.posts
    set best_comment_id = p_comment_id
    where id = p_post_id;

    -- Best-answer uses the existing comment preference category. The inbox
    -- outbox trigger automatically queues delivery for this inserted row.
    if v_comment_author is not null and v_comment_author <> v_actor
       and exists (
         select 1 from public.profiles pr
         where pr.id = v_actor and not pr.is_banned
       )
       and studyhub_private.friend_peer_available(v_actor, v_comment_author)
       and studyhub_private.lock_notification_recipient(v_comment_author) then
      select coalesce((
        select np.enabled
        from studyhub_private.notification_preferences np
        where np.user_id = v_comment_author
          and np.channel = 'in_app'
          and np.category = 'comment'
      ), true) into v_enabled;

      if v_enabled then
        insert into public.notifications(
          user_id, actor_id, type, post_id, source_kind, source_id, event_key, template_version
        )
        values (
          v_comment_author, v_actor, 'best_answer', p_post_id,
          'comment', p_comment_id, 'best_answer:' || p_comment_id::text, 1
        )
        on conflict (user_id, event_key) where event_key is not null do nothing;
      end if;
    end if;
  else
    update public.posts
    set best_comment_id = null
    where id = p_post_id;
  end if;

  return jsonb_build_object('post_id', p_post_id, 'best_comment_id', p_comment_id);
end;
$$;

-- ---------------------------------------------------------------------------
-- 7. RPC privileges.
-- ---------------------------------------------------------------------------
revoke all on function public.set_post_reaction(uuid, text) from public, anon, authenticated;
revoke all on function public.get_post_reaction_summaries(uuid[]) from public, anon, authenticated;
revoke all on function public.get_post_reactors(uuid, text, integer, integer) from public, anon, authenticated;
revoke all on function public.set_comment_reaction(uuid, text) from public, anon, authenticated;
revoke all on function public.get_comment_reaction_summaries(uuid[]) from public, anon, authenticated;
revoke all on function public.get_comment_reactors(uuid, text, integer, integer) from public, anon, authenticated;
revoke all on function public.set_best_answer(uuid, uuid) from public, anon, authenticated;

grant execute on function public.set_post_reaction(uuid, text) to authenticated;
grant execute on function public.get_post_reaction_summaries(uuid[]) to authenticated;
grant execute on function public.get_post_reactors(uuid, text, integer, integer) to authenticated;
grant execute on function public.set_comment_reaction(uuid, text) to authenticated;
grant execute on function public.get_comment_reaction_summaries(uuid[]) to authenticated;
grant execute on function public.get_comment_reactors(uuid, text, integer, integer) to authenticated;
grant execute on function public.set_best_answer(uuid, uuid) to authenticated;

commit;
