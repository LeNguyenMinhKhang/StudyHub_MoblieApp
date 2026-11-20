-- Optional "follow the guide" navigation for group calls (task 9.6).
-- Depends on 20261113100000_study_groups.sql, 20261117090000_group_documents.sql and
-- 20261118090000_group_calls.sql.
--
-- Guidance is navigation only: it points followers at a document version/page. It never grants
-- or removes the right to annotate, and removing it never touches documents or annotations.
-- One guide per open call, held under a short lease and fenced by a generation, so a stale
-- guide (expired, replaced, removed) cannot move followers.
--
-- Lock order: study_groups (share) → study_group_calls (update) → guidance row. Guidance is a
-- leaf: cleanup triggers only delete guidance rows inside the transaction that already holds the
-- group/call locks, so no new lock-order cycle is introduced.
--
-- SQLSTATEs (in addition to the G00xx, D000x and C000x codes):
--   D0007 guidance_taken (another guide holds a live lease)
--   D0008 guidance_lost  (caller is not the current guide or its generation is stale)
begin;

create table public.group_document_guidance (
    call_id uuid primary key references public.study_group_calls(id) on delete cascade,
    group_id uuid not null references public.study_groups(id) on delete cascade,
    guide_id uuid not null references public.profiles(id) on delete cascade,
    version_id uuid not null references public.group_document_versions(id) on delete restrict,
    page_index integer not null check (page_index between 0 and 99),
    -- Bumped when the guide changes; every update must present the generation it claimed.
    generation bigint not null default 1,
    -- Bumped on every move so followers can ignore out-of-order reads.
    sequence bigint not null default 1,
    lease_expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index group_document_guidance_guide on public.group_document_guidance(group_id, guide_id);

alter table public.group_document_guidance enable row level security;
revoke all on public.group_document_guidance from public, anon, authenticated, service_role;

create function studyhub_private.guidance_lease() returns interval
language sql immutable set search_path = '' as $$ select interval '30 seconds' $$;

-- ---------------------------------------------------------------------------
-- 1. Cleanup hooks (same transaction as the triggering change)
-- ---------------------------------------------------------------------------
-- Member removed/left: drop only that member's guidance.
create function studyhub_private.guidance_member_left() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    delete from public.group_document_guidance where group_id = old.group_id and guide_id = old.user_id;
    return null;
end;
$$;
create trigger group_document_guidance_member_left after delete on public.study_group_members
for each row execute function studyhub_private.guidance_member_left();

create function studyhub_private.guidance_group_archived() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    delete from public.group_document_guidance where group_id = new.id;
    return null;
end;
$$;
create trigger group_document_guidance_group_archived after update of archived_at on public.study_groups
for each row when (old.archived_at is null and new.archived_at is not null)
execute function studyhub_private.guidance_group_archived();

create function studyhub_private.guidance_account_banned() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    delete from public.group_document_guidance where guide_id = new.id;
    return null;
end;
$$;
create trigger group_document_guidance_account_banned after update of is_banned on public.profiles
for each row when (new.is_banned and not old.is_banned)
execute function studyhub_private.guidance_account_banned();

-- Call no longer open (ending/ended/failed): guidance goes away, nothing else does.
create function studyhub_private.guidance_call_closed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    delete from public.group_document_guidance where call_id = new.id;
    return null;
end;
$$;
create trigger group_document_guidance_call_closed after update of status on public.study_group_calls
for each row when (new.status in ('ending', 'ended', 'failed') and old.status is distinct from new.status)
execute function studyhub_private.guidance_call_closed();

-- The guide's own admission stopped being live (left, revoked, expired) and no other live
-- admission of that user exists in the call: release the guide role.
create function studyhub_private.guidance_admission_closed() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    if not exists(select 1 from public.study_group_call_admissions
                  where call_id = new.call_id and user_id = new.user_id and status in ('reserved', 'connected')) then
        delete from public.group_document_guidance where call_id = new.call_id and guide_id = new.user_id;
    end if;
    return null;
end;
$$;
create trigger group_document_guidance_admission_closed after update of status on public.study_group_call_admissions
for each row when (new.status in ('left', 'revoked', 'expired') and old.status in ('reserved', 'connected'))
execute function studyhub_private.guidance_admission_closed();

-- ---------------------------------------------------------------------------
-- 2. Access helpers
-- ---------------------------------------------------------------------------
-- Member of the call's group with the call lock held. Outsiders and unknown calls get C0003.
create function studyhub_private.guidance_call(p_call_id uuid, p_actor uuid, p_lock boolean default false)
returns public.study_group_calls language plpgsql security definer set search_path = '' as $$
declare c public.study_group_calls; gid uuid;
begin
    select group_id into gid from public.study_group_calls where id = p_call_id;
    if gid is null then raise exception 'call unavailable' using errcode = 'C0003'; end if;
    perform studyhub_private.document_member(gid, p_actor, 'C0003');
    if p_lock then select * into c from public.study_group_calls where id = p_call_id for update;
    else select * into c from public.study_group_calls where id = p_call_id; end if;
    return c;
end;
$$;

-- The guide must be a live participant of an open call and may only point at a live version
-- of an unarchived document in the same group.
create function studyhub_private.guidance_target(p_call public.study_group_calls, p_actor uuid, p_version_id uuid, p_page integer)
returns public.group_document_versions language plpgsql security definer set search_path = '' as $$
declare v public.group_document_versions; d public.group_documents;
begin
    if p_call.status = 'ending' then raise exception 'call ending' using errcode = 'C0004'; end if;
    if p_call.status not in ('starting', 'active') then raise exception 'call unavailable' using errcode = 'C0003'; end if;
    if not exists(select 1 from public.study_group_call_admissions
                  where call_id = p_call.id and user_id = p_actor and status in ('reserved', 'connected')) then
        raise exception 'call unavailable' using errcode = 'C0003';
    end if;
    select * into v from public.group_document_versions where id = p_version_id;
    if v.id is null then raise exception 'document unavailable' using errcode = 'D0001'; end if;
    select * into d from public.group_documents where id = v.document_id;
    if d.group_id <> p_call.group_id or d.archived_at is not null then
        raise exception 'document unavailable' using errcode = 'D0001';
    end if;
    if p_page is null or p_page < 0 or p_page >= v.page_count then
        raise exception 'invalid page' using errcode = 'G0001';
    end if;
    return v;
end;
$$;

create function studyhub_private.guidance_projection(p_guidance public.group_document_guidance, p_actor uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'call_id', p_guidance.call_id,
        'guide', studyhub_private.document_peer(p_guidance.guide_id),
        'document_id', v.document_id, 'version_id', p_guidance.version_id, 'page_index', p_guidance.page_index,
        'generation', p_guidance.generation, 'sequence', p_guidance.sequence,
        'lease_expires_at', p_guidance.lease_expires_at, 'is_me', p_guidance.guide_id = p_actor)
    from public.group_document_versions v where v.id = p_guidance.version_id
$$;

create function studyhub_private.guidance_publish(p_group_id uuid, p_call_id uuid) returns void
language sql security definer set search_path = '' as $$
    select studyhub_private.document_publish(p_group_id, jsonb_build_object('scope', 'guidance', 'call_id', p_call_id))
$$;

-- ---------------------------------------------------------------------------
-- 3. Public RPCs (authenticated; actor always from the session)
-- ---------------------------------------------------------------------------
-- Becomes (or stays) the guide. An unexpired lease held by someone else is not stolen.
create function public.claim_group_document_guidance(p_call_id uuid, p_version_id uuid, p_page_index integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c public.study_group_calls; gd public.group_document_guidance;
begin
    c := studyhub_private.guidance_call(p_call_id, actor, true);
    perform studyhub_private.guidance_target(c, actor, p_version_id, p_page_index);
    select * into gd from public.group_document_guidance where call_id = c.id for update;
    if gd.call_id is null then
        insert into public.group_document_guidance(call_id, group_id, guide_id, version_id, page_index, lease_expires_at)
        values (c.id, c.group_id, actor, p_version_id, p_page_index, now() + studyhub_private.guidance_lease())
        returning * into gd;
    elsif gd.guide_id = actor then
        update public.group_document_guidance set version_id = p_version_id, page_index = p_page_index,
            sequence = sequence + 1, lease_expires_at = now() + studyhub_private.guidance_lease(), updated_at = now()
        where call_id = c.id returning * into gd;
    elsif gd.lease_expires_at > now() then
        raise exception 'guidance taken' using errcode = 'D0007';
    else
        update public.group_document_guidance set guide_id = actor, version_id = p_version_id, page_index = p_page_index,
            generation = generation + 1, sequence = sequence + 1,
            lease_expires_at = now() + studyhub_private.guidance_lease(), updated_at = now()
        where call_id = c.id returning * into gd;
    end if;
    perform studyhub_private.guidance_publish(c.group_id, c.id);
    return jsonb_build_object('guidance', studyhub_private.guidance_projection(gd, actor));
end;
$$;

-- Moves followers and renews the lease. Only the current guide, with its claimed generation.
create function public.update_group_document_guidance(p_call_id uuid, p_generation bigint, p_version_id uuid, p_page_index integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c public.study_group_calls; gd public.group_document_guidance; moved boolean;
begin
    c := studyhub_private.guidance_call(p_call_id, actor, true);
    perform studyhub_private.guidance_target(c, actor, p_version_id, p_page_index);
    select * into gd from public.group_document_guidance where call_id = c.id for update;
    if gd.call_id is null or gd.guide_id <> actor or gd.generation is distinct from p_generation then
        raise exception 'guidance lost' using errcode = 'D0008';
    end if;
    -- A heartbeat on the same page renews the lease without moving or notifying followers.
    moved := (gd.version_id, gd.page_index) is distinct from (p_version_id, p_page_index);
    update public.group_document_guidance
    set version_id = p_version_id, page_index = p_page_index, lease_expires_at = now() + studyhub_private.guidance_lease(),
        sequence = sequence + case when moved then 1 else 0 end, updated_at = now()
    where call_id = c.id returning * into gd;
    if moved then perform studyhub_private.guidance_publish(c.group_id, c.id); end if;
    return jsonb_build_object('guidance', studyhub_private.guidance_projection(gd, actor));
end;
$$;

-- Idempotent: a stale or already-released caller gets released=false, never an error, and
-- releasing never needs call/group membership (a removed guide is also cleaned by triggers).
create function public.release_group_document_guidance(p_call_id uuid, p_generation bigint default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); released public.group_document_guidance;
begin
    delete from public.group_document_guidance
    where call_id = p_call_id and guide_id = actor and (p_generation is null or generation = p_generation)
    returning * into released;
    if released.call_id is not null then
        perform studyhub_private.guidance_publish(released.group_id, released.call_id);
    end if;
    return jsonb_build_object('released', released.call_id is not null);
end;
$$;

-- What followers read. An expired lease reads as "no guide" without mutating anything.
create function public.get_group_document_guidance(p_call_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c public.study_group_calls; gd public.group_document_guidance;
begin
    c := studyhub_private.guidance_call(p_call_id, actor);
    if c.status not in ('starting', 'active') then return jsonb_build_object('guidance', null); end if;
    select * into gd from public.group_document_guidance where call_id = c.id and lease_expires_at > now();
    return jsonb_build_object('guidance', case when gd.call_id is null then null
        else studyhub_private.guidance_projection(gd, actor) end);
end;
$$;

-- ---------------------------------------------------------------------------
-- 4. Grants: RPCs to authenticated, helpers to nobody.
-- ---------------------------------------------------------------------------
do $$ declare f record;
begin
    for f in select p.oid::regprocedure as signature, n.nspname, p.proname from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where (n.nspname = 'studyhub_private' and p.proname like 'guidance\_%' escape '\')
           or (n.nspname = 'public' and p.proname = any(array[
               'claim_group_document_guidance','update_group_document_guidance',
               'release_group_document_guidance','get_group_document_guidance'])) loop
        execute format('revoke all on function %s from public, anon, authenticated, service_role', f.signature);
        if f.nspname = 'public' then execute format('grant execute on function %s to authenticated', f.signature); end if;
    end loop;
end $$;

notify pgrst, 'reload schema';
commit;
