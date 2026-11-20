-- Group voice/video calls on LiveKit Cloud: one open call per group, short-lived admissions,
-- durable provider control jobs and webhook deduplication. Depends on
-- 20261113100000_study_groups.sql. Postgres never talks to LiveKit inside a transaction:
-- Edge Functions perform provider calls after commit and report results back.
--
-- Lock order everywhere: study_groups → study_group_calls → study_group_call_admissions.
-- Membership mutations lock the group FOR UPDATE; admission/confirm take it FOR SHARE, so a
-- token is either admitted before a removal (and then revoked by it) or refused after it.
--
-- SQLSTATEs (in addition to the G00xx group codes):
--   C0001 call_full   C0002 call_in_use_elsewhere   C0003 call_unavailable   C0004 call_ending
begin;

-- ---------------------------------------------------------------------------
-- 1. Tables (no client access; see grants at the end)
-- ---------------------------------------------------------------------------
-- Bumped whenever a user joins or leaves a group, so a stale admission cannot be confirmed
-- after a remove → rejoin cycle.
create table public.study_group_member_generations (
    -- Groups are archived, never deleted by the app; cascade only keeps fixtures/admin cleanup simple.
    group_id uuid not null references public.study_groups(id) on delete cascade,
    user_id uuid not null references public.profiles(id) on delete restrict,
    generation bigint not null default 1,
    primary key (group_id, user_id)
);

create table public.study_group_calls (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references public.study_groups(id) on delete restrict,
    started_by uuid not null references public.profiles(id) on delete restrict,
    initial_mode text not null check (initial_mode in ('audio', 'video')),
    status text not null default 'starting' check (status in ('starting', 'active', 'ending', 'ended', 'failed')),
    -- Opaque, never reused: one provider room per call session.
    room_name text not null unique,
    room_sid text,
    version integer not null default 1,
    created_at timestamptz not null default now(),
    activated_at timestamptz,
    ending_at timestamptz,
    ended_at timestamptz,
    end_reason text,
    empty_since timestamptz,
    last_reconciled_at timestamptz,
    check ((status in ('ended', 'failed')) = (ended_at is not null))
);
create unique index study_group_open_call on public.study_group_calls(group_id) where status in ('starting', 'active', 'ending');
create index study_group_call_active on public.study_group_calls(status, last_reconciled_at) where status in ('starting', 'active');

-- Start/join request keys: a lost response retried with the same key returns the same call.
create table public.study_group_call_requests (
    actor_id uuid not null references public.profiles(id) on delete restrict,
    request_id uuid not null,
    call_id uuid not null references public.study_group_calls(id) on delete restrict,
    created_at timestamptz not null default now(),
    primary key (actor_id, request_id)
);

-- One admission = one opaque provider identity. Raw tokens are never stored.
create table public.study_group_call_admissions (
    id uuid primary key default gen_random_uuid(),
    call_id uuid not null references public.study_group_calls(id) on delete restrict,
    group_id uuid not null references public.study_groups(id) on delete restrict,
    user_id uuid not null references public.profiles(id) on delete restrict,
    identity uuid not null unique default gen_random_uuid(),
    client_session_id uuid not null,
    generation bigint not null,
    -- reserved: token may be issued, counted for capacity until reserved_until;
    -- connected: confirmed by provider webhook/reconciliation.
    status text not null default 'reserved' check (status in ('reserved', 'connected', 'left', 'revoked', 'expired')),
    reserved_until timestamptz not null,
    token_issued_at timestamptz,
    token_expires_at timestamptz,
    last_event_at timestamptz,
    revoke_requested_at timestamptz,
    revoke_confirmed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create unique index study_group_call_live_admission on public.study_group_call_admissions(call_id, user_id)
    where status in ('reserved', 'connected');
create index study_group_call_admission_user on public.study_group_call_admissions(user_id) where status in ('reserved', 'connected');

-- Durable provider work. revoke: RemoveParticipant with an explicit token cutoff for one
-- identity. end_room: revoke every identity of the call, then DeleteRoom, then mark ended.
create table public.study_group_call_jobs (
    id bigserial primary key,
    call_id uuid not null references public.study_group_calls(id) on delete restrict,
    kind text not null check (kind in ('revoke', 'end_room')),
    admission_id uuid references public.study_group_call_admissions(id) on delete restrict,
    status text not null default 'pending' check (status in ('pending', 'done')),
    attempts integer not null default 0,
    run_after timestamptz not null default now(),
    leased_until timestamptz,
    last_error text,
    created_at timestamptz not null default now(),
    completed_at timestamptz,
    check ((kind = 'revoke') = (admission_id is not null))
);
create index study_group_call_job_due on public.study_group_call_jobs(run_after) where status = 'pending';
create unique index study_group_call_job_revoke on public.study_group_call_jobs(admission_id) where status = 'pending' and kind = 'revoke';
create unique index study_group_call_job_end on public.study_group_call_jobs(call_id) where status = 'pending' and kind = 'end_room';

create table public.study_group_call_webhook_events (
    event_id text primary key,
    event text not null,
    room_name text,
    received_at timestamptz not null default now()
);

alter table public.study_group_member_generations enable row level security;
alter table public.study_group_calls enable row level security;
alter table public.study_group_call_requests enable row level security;
alter table public.study_group_call_admissions enable row level security;
alter table public.study_group_call_jobs enable row level security;
alter table public.study_group_call_webhook_events enable row level security;
revoke all on public.study_group_member_generations, public.study_group_calls, public.study_group_call_requests,
    public.study_group_call_admissions, public.study_group_call_jobs, public.study_group_call_webhook_events
    from public, anon, authenticated;
revoke all on sequence public.study_group_call_jobs_id_seq from public, anon, authenticated;

-- Existing members start at generation 1.
insert into public.study_group_member_generations(group_id, user_id) select group_id, user_id from public.study_group_members
on conflict do nothing;

-- ---------------------------------------------------------------------------
-- 2. Helpers
-- ---------------------------------------------------------------------------
create function studyhub_private.call_capacity() returns integer language sql immutable set search_path = '' as $$ select 6 $$;
create function studyhub_private.call_reservation() returns interval language sql immutable set search_path = '' as $$ select interval '60 seconds' $$;

-- Wakes the worker after commit when deployed: pg_net queues the request transactionally, so
-- it is only sent if the enqueuing transaction commits. Missing pg_net/Vault (local tests) or
-- any error is a no-op; the 5-second scheduler remains the fallback.
create function studyhub_private.call_wake() returns void
language plpgsql security definer set search_path = '' as $$
declare target text; secret text;
begin
    if to_regclass('vault.decrypted_secrets') is null or to_regprocedure('net.http_post(text,jsonb,jsonb,jsonb,integer)') is null then
        return;
    end if;
    execute $q$select max(decrypted_secret) filter (where name = 'group_call_worker_url'),
                      max(decrypted_secret) filter (where name = 'group_call_worker_secret')
               from vault.decrypted_secrets where name in ('group_call_worker_url', 'group_call_worker_secret')$q$
        into target, secret;
    if target is null or secret is null then return; end if;
    execute 'select net.http_post($1, ''{}''::jsonb, ''{}''::jsonb, jsonb_build_object(''authorization'', ''Bearer '' || $2, ''content-type'', ''application/json''), 5000)'
        using target, secret;
exception when others then
    return; -- waking is an optimization; never fail the membership/call transaction
end;
$$;

-- Explicit actor (service callers have no end-user JWT). Takes the group FOR SHARE.
create function studyhub_private.call_member(p_group_id uuid, p_actor uuid) returns public.study_groups
language plpgsql security definer set search_path = '' as $$
declare g public.study_groups;
begin
    if p_actor is null then raise exception 'session required' using errcode = 'G0002'; end if;
    select * into g from public.study_groups where id = p_group_id for share;
    if g.id is null or g.archived_at is not null or not exists(
        select 1 from public.study_group_members where group_id = g.id and user_id = p_actor
    ) then raise exception 'group unavailable' using errcode = 'G0004'; end if;
    if not exists(select 1 from public.profiles where id = p_actor and not is_banned) then
        raise exception 'account unavailable' using errcode = 'G0003';
    end if;
    return g;
end;
$$;

create function studyhub_private.call_generation(p_group_id uuid, p_user_id uuid) returns bigint
language sql stable security definer set search_path = '' as $$
    select coalesce((select generation from public.study_group_member_generations where group_id = p_group_id and user_id = p_user_id), 0)
$$;

-- Marks an admission revoked (or expired/left) and enqueues provider revocation, in the caller's transaction.
create function studyhub_private.call_revoke(p_admission_id uuid, p_status text default 'revoked') returns void
language plpgsql security definer set search_path = '' as $$
declare a public.study_group_call_admissions;
begin
    update public.study_group_call_admissions
    set status = case when status in ('reserved', 'connected') then p_status else status end,
        revoke_requested_at = coalesce(revoke_requested_at, now()), updated_at = now()
    where id = p_admission_id and revoke_confirmed_at is null
    returning * into a;
    if a.id is null then return; end if;
    insert into public.study_group_call_jobs(call_id, kind, admission_id) values (a.call_id, 'revoke', a.id)
    on conflict (admission_id) where status = 'pending' and kind = 'revoke' do nothing;
    perform studyhub_private.call_wake();
end;
$$;

-- ending: refuse new admissions before any provider shutdown; ended only after the worker
-- confirms revocation of every identity and room deletion.
create function studyhub_private.call_begin_end(p_call_id uuid, p_reason text) returns void
language plpgsql security definer set search_path = '' as $$
declare a record;
begin
    update public.study_group_calls set status = 'ending', ending_at = now(), end_reason = p_reason, version = version + 1
    where id = p_call_id and status in ('starting', 'active');
    if not found then return; end if;
    for a in select id from public.study_group_call_admissions where call_id = p_call_id and status in ('reserved', 'connected') loop
        update public.study_group_call_admissions set status = 'revoked', revoke_requested_at = coalesce(revoke_requested_at, now()),
            updated_at = now() where id = a.id;
    end loop;
    insert into public.study_group_call_jobs(call_id, kind) values (p_call_id, 'end_room')
    on conflict (call_id) where status = 'pending' and kind = 'end_room' do nothing;
    perform studyhub_private.call_wake();
end;
$$;

create function studyhub_private.call_live_count(p_call_id uuid) returns integer
language sql stable security definer set search_path = '' as $$
    select count(*)::integer from public.study_group_call_admissions
    where call_id = p_call_id and (status = 'connected' or (status = 'reserved' and reserved_until > now()))
$$;

-- Expired reservations never connected: free the seat and revoke any token issued for them.
create function studyhub_private.call_expire_reservations(p_call_id uuid) returns void
language plpgsql security definer set search_path = '' as $$
declare a record;
begin
    for a in select id from public.study_group_call_admissions
             where call_id = p_call_id and status = 'reserved' and reserved_until <= now() for update loop
        perform studyhub_private.call_revoke(a.id, 'expired');
    end loop;
end;
$$;

-- Minimal projection: no room name/SID, identities, tokens, or other users' admissions.
create function studyhub_private.call_projection(p_call_id uuid, p_actor uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object(
        'id', c.id, 'group_id', c.group_id, 'status', c.status, 'initial_mode', c.initial_mode,
        'started_by', jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_url', p.avatar_url),
        'created_at', c.created_at, 'activated_at', c.activated_at, 'ended_at', c.ended_at,
        'participant_count', studyhub_private.call_live_count(c.id),
        'capacity', studyhub_private.call_capacity(),
        'can_end', c.status in ('starting', 'active') and (g.owner_id = p_actor or c.started_by = p_actor),
        'cleanup_pending', c.status = 'ending',
        'my_status', (select a.status from public.study_group_call_admissions a
                      where a.call_id = c.id and a.user_id = p_actor and a.status in ('reserved', 'connected') limit 1))
    from public.study_group_calls c
    join public.study_groups g on g.id = c.group_id
    join public.profiles p on p.id = c.started_by
    where c.id = p_call_id
$$;

-- ---------------------------------------------------------------------------
-- 3. Membership, archive and ban hooks (same transaction as the triggering change)
-- ---------------------------------------------------------------------------
create function studyhub_private.call_member_joined() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    insert into public.study_group_member_generations(group_id, user_id) values (new.group_id, new.user_id)
    on conflict (group_id, user_id) do update set generation = public.study_group_member_generations.generation + 1;
    return null;
end;
$$;
create trigger study_group_call_member_joined after insert on public.study_group_members
for each row execute function studyhub_private.call_member_joined();

create function studyhub_private.call_member_left() returns trigger
language plpgsql security definer set search_path = '' as $$
declare a record;
begin
    update public.study_group_member_generations set generation = generation + 1
    where group_id = old.group_id and user_id = old.user_id;
    for a in select id from public.study_group_call_admissions
             where group_id = old.group_id and user_id = old.user_id and status in ('reserved', 'connected') loop
        perform studyhub_private.call_revoke(a.id);
    end loop;
    return null;
end;
$$;
create trigger study_group_call_member_left after delete on public.study_group_members
for each row execute function studyhub_private.call_member_left();

create function studyhub_private.call_group_archived() returns trigger
language plpgsql security definer set search_path = '' as $$
declare c record;
begin
    for c in select id from public.study_group_calls where group_id = new.id and status in ('starting', 'active') loop
        perform studyhub_private.call_begin_end(c.id, 'group_archived');
    end loop;
    return null;
end;
$$;
create trigger study_group_call_group_archived after update of archived_at on public.study_groups
for each row when (old.archived_at is null and new.archived_at is not null)
execute function studyhub_private.call_group_archived();

create function studyhub_private.call_account_banned() returns trigger
language plpgsql security definer set search_path = '' as $$
declare a record;
begin
    for a in select id from public.study_group_call_admissions where user_id = new.id and status in ('reserved', 'connected') loop
        perform studyhub_private.call_revoke(a.id);
    end loop;
    return null;
end;
$$;
create trigger study_group_call_account_banned after update of is_banned on public.profiles
for each row when (new.is_banned and not old.is_banned)
execute function studyhub_private.call_account_banned();

-- ---------------------------------------------------------------------------
-- 4. Member-facing read (no secrets involved, so a plain RPC)
-- ---------------------------------------------------------------------------
create function public.get_study_group_call(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); call_id uuid;
begin
    perform studyhub_private.group_access(p_group_id, actor);
    select id into call_id from public.study_group_calls where group_id = p_group_id and status in ('starting', 'active', 'ending');
    return jsonb_build_object('call', case when call_id is null then null else studyhub_private.call_projection(call_id, actor) end);
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. Admission flow for Edge Functions (service role; actor verified from the JWT)
--    admit → [createRoom + activate when starting] → sign token → confirm
-- ---------------------------------------------------------------------------
create function public.service_admit_group_call(p_actor uuid, p_group_id uuid, p_request_id uuid, p_mode text,
                                                p_client_session uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare g public.study_groups; c public.study_group_calls; a public.study_group_call_admissions; mapped uuid;
begin
    g := studyhub_private.call_member(p_group_id, p_actor);
    if p_request_id is null or p_client_session is null or p_mode is null or p_mode not in ('audio', 'video') then
        raise exception 'invalid request' using errcode = 'G0001';
    end if;
    select call_id into mapped from public.study_group_call_requests where actor_id = p_actor and request_id = p_request_id;
    if mapped is not null then
        select * into c from public.study_group_calls where id = mapped for update;
        if c.group_id <> g.id then raise exception 'invalid request' using errcode = 'G0001'; end if;
    else
        select * into c from public.study_group_calls where group_id = g.id and status in ('starting', 'active', 'ending') for update;
        if c.id is null then
            begin
                insert into public.study_group_calls(group_id, started_by, initial_mode, room_name)
                values (g.id, p_actor, p_mode, 'sh-' || gen_random_uuid()) returning * into c;
            exception when unique_violation then
                -- A concurrent start committed first: join that call instead of creating a parallel room.
                select * into c from public.study_group_calls where group_id = g.id and status in ('starting', 'active', 'ending') for update;
            end;
        end if;
        insert into public.study_group_call_requests(actor_id, request_id, call_id) values (p_actor, p_request_id, c.id);
    end if;
    if c.status = 'ending' then raise exception 'call ending' using errcode = 'C0004'; end if;
    if c.status not in ('starting', 'active') then raise exception 'call unavailable' using errcode = 'C0003'; end if;

    perform studyhub_private.call_expire_reservations(c.id);
    select * into a from public.study_group_call_admissions
    where call_id = c.id and user_id = p_actor and status in ('reserved', 'connected') for update;
    if a.id is not null then
        -- Same device retrying keeps its identity; another device must not take over silently.
        if a.client_session_id <> p_client_session then raise exception 'in use elsewhere' using errcode = 'C0002'; end if;
        update public.study_group_call_admissions
        set reserved_until = greatest(reserved_until, now() + studyhub_private.call_reservation()), updated_at = now()
        where id = a.id returning * into a;
    else
        if studyhub_private.call_live_count(c.id) >= studyhub_private.call_capacity() then
            raise exception 'call full' using errcode = 'C0001';
        end if;
        insert into public.study_group_call_admissions(call_id, group_id, user_id, client_session_id, generation, reserved_until)
        values (c.id, g.id, p_actor, p_client_session, studyhub_private.call_generation(g.id, p_actor),
                now() + studyhub_private.call_reservation())
        returning * into a;
    end if;
    return jsonb_build_object('call', studyhub_private.call_projection(c.id, p_actor),
        'admission', jsonb_build_object('id', a.id, 'identity', a.identity, 'generation', a.generation,
            'room_name', c.room_name, 'needs_room', c.status = 'starting',
            'display_name', (select display_name from public.profiles where id = p_actor)));
end;
$$;

-- After the provider confirmed the room exists. Never reopens an ending/ended call.
create function public.service_activate_group_call(p_call_id uuid, p_room_sid text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare g_id uuid; c public.study_group_calls;
begin
    select group_id into g_id from public.study_group_calls where id = p_call_id;
    if g_id is null then raise exception 'call unavailable' using errcode = 'C0003'; end if;
    perform 1 from public.study_groups where id = g_id for share;
    update public.study_group_calls
    set status = 'active', activated_at = coalesce(activated_at, now()), room_sid = coalesce(room_sid, p_room_sid),
        version = version + case when status = 'starting' then 1 else 0 end
    where id = p_call_id and status in ('starting', 'active')
    returning * into c;
    if c.id is null then
        select * into c from public.study_group_calls where id = p_call_id;
    end if;
    return jsonb_build_object('id', c.id, 'status', c.status);
end;
$$;

-- Releases an admission whose token was never handed out (provider failure before signing).
create function public.service_release_group_call_admission(p_admission_id uuid, p_actor uuid) returns void
language plpgsql security definer set search_path = '' as $$
begin
    update public.study_group_call_admissions set status = 'expired', updated_at = now()
    where id = p_admission_id and user_id = p_actor and status = 'reserved' and token_issued_at is null;
end;
$$;

-- Final re-check before a signed token leaves the server: still a member with the same
-- generation, admission still reserved/connected and call active.
create function public.service_confirm_group_call_admission(p_admission_id uuid, p_actor uuid, p_expires_at timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare a public.study_group_call_admissions; c public.study_group_calls;
begin
    select * into a from public.study_group_call_admissions where id = p_admission_id;
    if a.id is null or a.user_id <> p_actor then raise exception 'call unavailable' using errcode = 'C0003'; end if;
    perform studyhub_private.call_member(a.group_id, p_actor);
    select * into c from public.study_group_calls where id = a.call_id for share;
    select * into a from public.study_group_call_admissions where id = p_admission_id for update;
    if c.status = 'ending' then raise exception 'call ending' using errcode = 'C0004'; end if;
    if c.status <> 'active' or a.status not in ('reserved', 'connected')
       or (a.status = 'reserved' and a.reserved_until <= now())
       or a.generation <> studyhub_private.call_generation(a.group_id, p_actor) then
        raise exception 'admission unavailable' using errcode = 'C0003';
    end if;
    update public.study_group_call_admissions
    set token_issued_at = now(), token_expires_at = p_expires_at, updated_at = now() where id = a.id;
    return jsonb_build_object('call', studyhub_private.call_projection(c.id, p_actor));
end;
$$;

create function public.service_leave_group_call(p_actor uuid, p_call_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare g_id uuid; c public.study_group_calls; a record;
begin
    if p_actor is null then raise exception 'session required' using errcode = 'G0002'; end if;
    select group_id into g_id from public.study_group_calls where id = p_call_id;
    if g_id is null then raise exception 'call unavailable' using errcode = 'C0003'; end if;
    perform 1 from public.study_groups where id = g_id for share;
    select * into c from public.study_group_calls where id = p_call_id for update;
    -- Leaving never requires current membership: a removed user can always disconnect.
    for a in select id from public.study_group_call_admissions
             where call_id = c.id and user_id = p_actor and status in ('reserved', 'connected') loop
        perform studyhub_private.call_revoke(a.id, 'left');
    end loop;
    if c.status = 'active' and studyhub_private.call_live_count(c.id) = 0 then
        update public.study_group_calls set empty_since = coalesce(empty_since, now()) where id = c.id;
    end if;
    return jsonb_build_object('call_id', c.id, 'status', 'left');
end;
$$;

-- End for all: current owner, or the initiator while still a member.
create function public.service_end_group_call(p_actor uuid, p_call_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare g public.study_groups; c public.study_group_calls; g_id uuid;
begin
    select group_id into g_id from public.study_group_calls where id = p_call_id;
    if g_id is null then raise exception 'call unavailable' using errcode = 'C0003'; end if;
    g := studyhub_private.call_member(g_id, p_actor);
    select * into c from public.study_group_calls where id = p_call_id for update;
    if c.status in ('starting', 'active') then
        if g.owner_id <> p_actor and c.started_by <> p_actor then
            raise exception 'owner or initiator required' using errcode = 'G0005';
        end if;
        perform studyhub_private.call_begin_end(c.id, 'ended_by_user');
    end if;
    return jsonb_build_object('call', studyhub_private.call_projection(c.id, p_actor));
end;
$$;

-- ---------------------------------------------------------------------------
-- 6. Worker (scheduler/wake): leased jobs with backoff, reconciliation, webhooks
-- ---------------------------------------------------------------------------
create function public.service_claim_group_call_jobs(p_limit integer default 20) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare result jsonb;
begin
    with due as (
        select j.id from public.study_group_call_jobs j
        where j.status = 'pending' and j.run_after <= now() and (j.leased_until is null or j.leased_until < now())
        order by j.run_after, j.id limit greatest(1, least(coalesce(p_limit, 20), 100))
        for update skip locked
    ), claimed as (
        update public.study_group_call_jobs j set leased_until = now() + interval '30 seconds', attempts = j.attempts + 1
        from due where j.id = due.id returning j.*
    )
    select coalesce(jsonb_agg(jsonb_build_object(
        'id', cl.id, 'kind', cl.kind, 'call_id', cl.call_id, 'attempts', cl.attempts, 'room_name', c.room_name,
        'identities', case when cl.kind = 'revoke'
            then (select jsonb_agg(a.identity) from public.study_group_call_admissions a where a.id = cl.admission_id)
            else coalesce((select jsonb_agg(a.identity order by a.created_at) from public.study_group_call_admissions a
                           where a.call_id = cl.call_id and a.revoke_confirmed_at is null), '[]'::jsonb) end
    ) order by cl.id), '[]'::jsonb) into result
    from claimed cl join public.study_group_calls c on c.id = cl.call_id;
    return result;
end;
$$;

-- Provider confirmed: revocation of [p_identities] (and room deletion for end_room).
create function public.service_complete_group_call_job(p_job_id bigint, p_identities uuid[]) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare j public.study_group_call_jobs; remaining integer; g_id uuid;
begin
    select call_id into j.call_id from public.study_group_call_jobs where id = p_job_id;
    select group_id into g_id from public.study_group_calls where id = j.call_id;
    if g_id is null then raise exception 'job unavailable' using errcode = 'G0010'; end if;
    perform 1 from public.study_groups where id = g_id for share;
    perform 1 from public.study_group_calls where id = j.call_id for update;
    select * into j from public.study_group_call_jobs where id = p_job_id for update;
    if j.status = 'done' then return jsonb_build_object('id', j.id, 'status', 'done'); end if;
    update public.study_group_call_admissions set revoke_confirmed_at = coalesce(revoke_confirmed_at, now()), updated_at = now()
    where call_id = j.call_id and identity = any(coalesce(p_identities, '{}'));
    -- Revocations covered by this confirmation are complete too.
    update public.study_group_call_jobs r set status = 'done', completed_at = now(), leased_until = null
    from public.study_group_call_admissions a
    where r.status = 'pending' and r.kind = 'revoke' and r.admission_id = a.id and a.revoke_confirmed_at is not null
      and r.call_id = j.call_id;
    if j.kind = 'end_room' then
        select count(*) into remaining from public.study_group_call_admissions where call_id = j.call_id and revoke_confirmed_at is null;
        if remaining > 0 then
            -- An identity was admitted after the claim: run again instead of reporting ended.
            update public.study_group_call_jobs set leased_until = null, run_after = now() where id = j.id;
            return jsonb_build_object('id', j.id, 'status', 'pending');
        end if;
        update public.study_group_calls set status = 'ended', ended_at = now(), empty_since = null, version = version + 1
        where id = j.call_id and status = 'ending';
    end if;
    update public.study_group_call_jobs set status = 'done', completed_at = now(), leased_until = null, last_error = null
    where id = j.id;
    return jsonb_build_object('id', j.id, 'status', 'done');
end;
$$;

create function public.service_fail_group_call_job(p_job_id bigint, p_error text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare j public.study_group_call_jobs;
begin
    update public.study_group_call_jobs
    set leased_until = null, last_error = left(coalesce(p_error, 'provider_error'), 120),
        run_after = now() + least(interval '60 seconds', interval '1 second' * power(2, least(attempts, 6)))
    where id = p_job_id and status = 'pending' returning * into j;
    return jsonb_build_object('id', p_job_id, 'status', coalesce(j.status, 'done'), 'run_after', j.run_after);
end;
$$;

-- Calls due for reconciliation (at most every 30 s each). Also fails calls stuck in starting
-- and expires stale reservations so their seats/tokens are released.
create function public.service_reconcile_group_calls(p_limit integer default 50) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare c record; result jsonb := '[]'::jsonb;
begin
    for c in select id, group_id from public.study_group_calls
             where status = 'starting' and created_at < now() - interval '2 minutes' loop
        perform 1 from public.study_groups where id = c.group_id for share;
        update public.study_group_calls set status = 'failed', ended_at = now(), end_reason = 'provider_create_failed', version = version + 1
        where id = c.id and status = 'starting';
        perform studyhub_private.call_revoke(a.id, 'expired') from public.study_group_call_admissions a
        where a.call_id = c.id and a.status in ('reserved', 'connected');
    end loop;
    for c in select id, group_id from public.study_group_calls
             where status = 'active' and (last_reconciled_at is null or last_reconciled_at < now() - interval '30 seconds')
             order by last_reconciled_at nulls first limit greatest(1, least(coalesce(p_limit, 50), 200)) loop
        perform 1 from public.study_groups where id = c.group_id for share;
        perform 1 from public.study_group_calls where id = c.id for update skip locked;
        if not found then continue; end if;
        perform studyhub_private.call_expire_reservations(c.id);
        update public.study_group_calls set last_reconciled_at = now() where id = c.id;
        result := result || (select jsonb_build_object('id', id, 'room_name', room_name) from public.study_group_calls where id = c.id);
    end loop;
    delete from public.study_group_call_webhook_events where received_at < now() - interval '1 day';
    return result;
end;
$$;

-- Provider view of a room (p_identities connected now, null when the room does not exist).
create function public.service_record_group_call_presence(p_call_id uuid, p_identities uuid[]) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare c public.study_group_calls; g_id uuid; a record; live integer;
begin
    select group_id into g_id from public.study_group_calls where id = p_call_id;
    if g_id is null then return jsonb_build_object('status', 'unknown'); end if;
    perform 1 from public.study_groups where id = g_id for share;
    select * into c from public.study_group_calls where id = p_call_id for update;
    if c.status <> 'active' then return jsonb_build_object('status', c.status); end if;
    -- Present but no longer entitled (revoked, expired or left): disconnect and revoke again.
    for a in select x.id, x.status from public.study_group_call_admissions x
             where x.call_id = c.id and x.identity = any(coalesce(p_identities, '{}')) loop
        if a.status in ('reserved', 'connected') then
            update public.study_group_call_admissions set status = 'connected', updated_at = now() where id = a.id;
        else
            update public.study_group_call_admissions set revoke_confirmed_at = null where id = a.id;
            perform studyhub_private.call_revoke(a.id);
        end if;
    end loop;
    update public.study_group_call_admissions set status = 'left', updated_at = now()
    where call_id = c.id and status = 'connected' and not (identity = any(coalesce(p_identities, '{}')));
    live := studyhub_private.call_live_count(c.id);
    if live > 0 then
        update public.study_group_calls set empty_since = null where id = c.id;
    elsif c.empty_since is null then
        update public.study_group_calls set empty_since = now() where id = c.id;
    elsif c.empty_since <= now() - interval '60 seconds' then
        perform studyhub_private.call_begin_end(c.id, 'empty');
    end if;
    return jsonb_build_object('status', (select status from public.study_group_calls where id = c.id), 'live', live);
end;
$$;

-- Signed webhook, already verified by the Edge Function. Deduplicated by event ID; stale
-- events (older than the last applied one, or for an earlier room SID) never reopen a call.
create function public.service_record_group_call_event(p_event_id text, p_event text, p_room_name text, p_room_sid text,
                                                      p_identity uuid, p_created_at timestamptz) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare c public.study_group_calls; g_id uuid; a public.study_group_call_admissions; applied text := 'ignored';
begin
    if p_event_id is null or p_event is null then raise exception 'invalid event' using errcode = 'G0001'; end if;
    insert into public.study_group_call_webhook_events(event_id, event, room_name) values (p_event_id, p_event, p_room_name)
    on conflict (event_id) do nothing;
    if not found then return jsonb_build_object('duplicate', true, 'applied', 'none'); end if;
    select group_id into g_id from public.study_group_calls where room_name = p_room_name;
    if g_id is null then return jsonb_build_object('duplicate', false, 'applied', 'unknown_room'); end if;
    perform 1 from public.study_groups where id = g_id for share;
    select * into c from public.study_group_calls where room_name = p_room_name for update;

    if p_event = 'room_started' then
        if c.status in ('starting', 'active') and (c.room_sid is null or c.room_sid = p_room_sid) then
            update public.study_group_calls set status = 'active', activated_at = coalesce(activated_at, now()),
                room_sid = coalesce(room_sid, p_room_sid), version = version + case when status = 'starting' then 1 else 0 end
            where id = c.id;
            applied := 'activated';
        elsif c.status in ('ending', 'ended', 'failed') then
            -- Something recreated a closed room: close it again; state stays closed.
            insert into public.study_group_call_jobs(call_id, kind) values (c.id, 'end_room')
            on conflict (call_id) where status = 'pending' and kind = 'end_room' do nothing;
            perform studyhub_private.call_wake();
            applied := 'reclose';
        end if;
    elsif p_event = 'room_finished' then
        if c.status = 'active' and (c.room_sid is null or p_room_sid is null or c.room_sid = p_room_sid) then
            perform studyhub_private.call_begin_end(c.id, 'provider_finished');
            applied := 'ending';
        end if;
    elsif p_event in ('participant_joined', 'participant_left') and p_identity is not null then
        select * into a from public.study_group_call_admissions where call_id = c.id and identity = p_identity for update;
        if a.id is null then return jsonb_build_object('duplicate', false, 'applied', 'unknown_identity'); end if;
        if a.last_event_at is not null and p_created_at is not null and p_created_at < a.last_event_at then
            return jsonb_build_object('duplicate', false, 'applied', 'stale');
        end if;
        update public.study_group_call_admissions set last_event_at = coalesce(p_created_at, now()) where id = a.id;
        if p_event = 'participant_joined' then
            if c.status = 'active' and a.status in ('reserved', 'connected')
               and a.generation = studyhub_private.call_generation(c.group_id, a.user_id)
               and exists(select 1 from public.profiles where id = a.user_id and not is_banned) then
                update public.study_group_call_admissions set status = 'connected', updated_at = now() where id = a.id;
                update public.study_group_calls set empty_since = null where id = c.id;
                applied := 'connected';
            else
                -- Late join with a revoked/expired/stale token, or into a closing call.
                update public.study_group_call_admissions set revoke_confirmed_at = null where id = a.id;
                perform studyhub_private.call_revoke(a.id);
                applied := 'revoke';
            end if;
        else
            if a.status = 'connected' then
                update public.study_group_call_admissions set status = 'left', updated_at = now() where id = a.id;
                applied := 'left';
            end if;
            if c.status = 'active' and studyhub_private.call_live_count(c.id) = 0 then
                update public.study_group_calls set empty_since = coalesce(empty_since, now()) where id = c.id;
            end if;
        end if;
    end if;
    return jsonb_build_object('duplicate', false, 'applied', applied);
end;
$$;

-- ---------------------------------------------------------------------------
-- 7. Grants: member read to authenticated, service_* to service_role, helpers to nobody.
-- ---------------------------------------------------------------------------
do $$ declare f record;
begin
    for f in select p.oid::regprocedure as signature, n.nspname, p.proname from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where (n.nspname = 'studyhub_private' and p.proname like 'call\_%' escape '\')
           or (n.nspname = 'public' and (p.proname = 'get_study_group_call' or p.proname like 'service\_%group\_call%' escape '\')) loop
        execute format('revoke all on function %s from public, anon, authenticated, service_role', f.signature);
        if f.nspname = 'public' and f.proname like 'service\_%' escape '\' then
            execute format('grant execute on function %s to service_role', f.signature);
        elsif f.nspname = 'public' then
            execute format('grant execute on function %s to authenticated', f.signature);
        end if;
    end loop;
end $$;

notify pgrst, 'reload schema';
commit;
