-- Private group PDF library, immutable versions and collaborative annotations.
-- Depends on 20261113100000_study_groups.sql. Guidance (follow-guide) depends on group
-- calls and is intentionally not part of this migration.
--
-- SQLSTATEs (in addition to the G00xx group codes):
--   D0001 document_unavailable   D0002 annotation_conflict   D0003 operation_conflict
--   D0004 annotation_limit       D0005 upload_unavailable    D0006 snapshot_expired
begin;

create schema if not exists studyhub_policy;
revoke all on schema studyhub_policy from public, anon, authenticated;
grant usage on schema studyhub_policy to authenticated;

-- ---------------------------------------------------------------------------
-- 1. Tables
-- ---------------------------------------------------------------------------
create table public.group_documents (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references public.study_groups(id) on delete restrict,
    title text not null check (char_length(title) between 1 and 200),
    uploaded_by uuid not null references public.profiles(id) on delete restrict,
    current_version_id uuid not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz,
    archived_by uuid references public.profiles(id) on delete restrict,
    check ((archived_at is null) = (archived_by is null))
);
create table public.group_document_uploads (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references public.study_groups(id) on delete restrict,
    document_id uuid references public.group_documents(id) on delete restrict,
    actor_id uuid not null references public.profiles(id) on delete restrict,
    request_id uuid not null,
    title text check (char_length(title) between 1 and 200),
    declared_size bigint not null check (declared_size between 1 and 20971520),
    object_key text not null unique,
    status text not null default 'pending' check (status in ('pending','ready','failed','expired')),
    failure_reason text,
    version_id uuid,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz,
    object_removed_at timestamptz,
    unique (actor_id, request_id),
    check ((document_id is null) = (title is not null)),
    check ((status = 'ready') = (version_id is not null)),
    check ((status = 'pending') = (resolved_at is null))
);
create table public.group_document_versions (
    id uuid primary key,
    document_id uuid not null references public.group_documents(id) on delete restrict deferrable initially deferred,
    version_number integer not null check (version_number > 0),
    object_key text not null unique,
    content_hash text not null check (content_hash ~ '^[0-9a-f]{64}$'),
    size_bytes bigint not null check (size_bytes between 1 and 20971520),
    page_count integer not null check (page_count between 1 and 100),
    pages jsonb not null check (jsonb_typeof(pages) = 'array'),
    uploaded_by uuid not null references public.profiles(id) on delete restrict,
    revision bigint not null default 0 check (revision >= 0),
    active_annotations integer not null default 0 check (active_annotations between 0 and 10000),
    created_at timestamptz not null default now(),
    unique (document_id, version_number),
    unique (document_id, id),
    check (jsonb_array_length(pages) = page_count)
);
-- The current version must belong to the same document; checked at commit.
alter table public.group_documents add constraint group_documents_current_version
    foreign key (id, current_version_id) references public.group_document_versions(document_id, id)
    deferrable initially deferred;
alter table public.group_document_uploads add constraint group_document_uploads_version
    foreign key (version_id) references public.group_document_versions(id) deferrable initially deferred;

create table public.group_document_annotations (
    id uuid primary key,
    version_id uuid not null references public.group_document_versions(id) on delete restrict,
    page_index integer not null check (page_index between 0 and 99),
    kind text not null check (kind in ('ink','highlight','note')),
    data jsonb not null,
    author_id uuid not null references public.profiles(id) on delete restrict,
    row_version integer not null default 1 check (row_version > 0),
    revision bigint not null check (revision > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    updated_by uuid not null references public.profiles(id) on delete restrict,
    deleted_at timestamptz
);
create unique index group_document_annotation_revision on public.group_document_annotations(version_id, revision);

-- Append-only per-version history. Each revision changes exactly one annotation and
-- stores its full state, so a snapshot at revision R is reconstructible after later edits.
create table public.group_document_events (
    version_id uuid not null references public.group_document_versions(id) on delete restrict,
    revision bigint not null check (revision > 0),
    annotation_id uuid not null references public.group_document_annotations(id) on delete restrict,
    action text not null check (action in ('create','update','delete','restore')),
    actor_id uuid not null references public.profiles(id) on delete restrict,
    state jsonb not null,
    created_at timestamptz not null default now(),
    primary key (version_id, revision)
);
create index group_document_event_annotation on public.group_document_events(version_id, annotation_id, revision desc);

create table public.group_document_operations (
    actor_id uuid not null references public.profiles(id) on delete restrict,
    op_id uuid not null,
    version_id uuid not null references public.group_document_versions(id) on delete restrict,
    fingerprint text not null,
    result jsonb not null,
    created_at timestamptz not null default now(),
    primary key (actor_id, op_id)
);
create table public.group_document_snapshots (
    id uuid primary key default gen_random_uuid(),
    version_id uuid not null references public.group_document_versions(id) on delete restrict,
    revision bigint not null,
    requested_by uuid not null references public.profiles(id) on delete restrict,
    purpose text not null check (purpose in ('view','export')),
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);
create index group_document_snapshot_owner on public.group_document_snapshots(requested_by, expires_at);
create table public.group_document_channels (
    group_id uuid primary key references public.study_groups(id) on delete restrict,
    epoch uuid not null default gen_random_uuid(),
    rotated_at timestamptz not null default now()
);

create index group_document_page on public.group_documents(group_id, created_at desc, id desc) where archived_at is null;
create index group_document_version_page on public.group_document_versions(document_id, created_at desc, id desc);
create index group_document_upload_pending on public.group_document_uploads(actor_id) where status = 'pending';
create index group_document_upload_cleanup on public.group_document_uploads(expires_at)
    where object_removed_at is null and status <> 'ready';

alter table public.group_documents enable row level security;
alter table public.group_document_uploads enable row level security;
alter table public.group_document_versions enable row level security;
alter table public.group_document_annotations enable row level security;
alter table public.group_document_events enable row level security;
alter table public.group_document_operations enable row level security;
alter table public.group_document_snapshots enable row level security;
alter table public.group_document_channels enable row level security;
revoke all on public.group_documents, public.group_document_uploads, public.group_document_versions,
    public.group_document_annotations, public.group_document_events, public.group_document_operations,
    public.group_document_snapshots, public.group_document_channels from public, anon, authenticated, service_role;

-- Source metadata of a version is immutable; only counters move.
create function studyhub_private.document_version_immutable() returns trigger
language plpgsql set search_path = '' as $$
begin
    if (new.id, new.document_id, new.version_number, new.object_key, new.content_hash, new.size_bytes,
        new.page_count, new.pages, new.uploaded_by, new.created_at)
       is distinct from (old.id, old.document_id, old.version_number, old.object_key, old.content_hash,
        old.size_bytes, old.page_count, old.pages, old.uploaded_by, old.created_at) then
        raise exception 'immutable version' using errcode = 'G0010';
    end if;
    if new.revision < old.revision then raise exception 'revision regression' using errcode = 'G0010'; end if;
    return new;
end;
$$;
create trigger group_document_version_immutable before update on public.group_document_versions
for each row execute function studyhub_private.document_version_immutable();
create function studyhub_private.document_append_only() returns trigger
language plpgsql set search_path = '' as $$
begin
    raise exception 'append-only' using errcode = 'G0010';
end;
$$;
create trigger group_document_event_append_only before update or delete on public.group_document_events
for each row execute function studyhub_private.document_append_only();

-- ---------------------------------------------------------------------------
-- 2. Channel epochs: obsolete subscriptions stop receiving after membership loss.
-- ---------------------------------------------------------------------------
create function studyhub_private.document_rotate_epoch(p_group_id uuid) returns void
language sql security definer set search_path = '' as $$
    update public.group_document_channels set epoch = gen_random_uuid(), rotated_at = now() where group_id = p_group_id;
$$;
create function studyhub_private.document_member_left() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    perform studyhub_private.document_rotate_epoch(old.group_id);
    return null;
end;
$$;
create trigger group_document_member_left after delete on public.study_group_members
for each row execute function studyhub_private.document_member_left();
create function studyhub_private.document_group_archived() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    perform studyhub_private.document_rotate_epoch(new.id);
    return null;
end;
$$;
create trigger group_document_group_archived after update of archived_at on public.study_groups
for each row when (old.archived_at is null and new.archived_at is not null)
execute function studyhub_private.document_group_archived();
create function studyhub_private.document_account_banned() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
    update public.group_document_channels c set epoch = gen_random_uuid(), rotated_at = now()
    where c.group_id in (select m.group_id from public.study_group_members m where m.user_id = new.id);
    return null;
end;
$$;
create trigger group_document_account_banned after update of is_banned on public.profiles
for each row when (new.is_banned and not old.is_banned)
execute function studyhub_private.document_account_banned();

-- Invalidation only: no annotation text, geometry, object key or URL.
create function studyhub_private.document_publish(p_group_id uuid, p_payload jsonb) returns void
language plpgsql security definer set search_path = '' as $$
declare topic_epoch uuid;
begin
    select epoch into topic_epoch from public.group_document_channels where group_id = p_group_id;
    if topic_epoch is not null then
        perform realtime.send(p_payload || jsonb_build_object('schema_version', 1),
            'documents_changed', 'group-docs:' || topic_epoch::text, true);
    end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. Access helpers. Lock order everywhere: group (share) → document → version → annotation.
--    Membership mutations lock the group FOR UPDATE, so a document write either commits
--    before a removal or observes it.
-- ---------------------------------------------------------------------------
create function studyhub_private.document_member(p_group_id uuid, p_actor uuid, p_unavailable text default 'G0004')
returns public.study_groups language plpgsql security definer set search_path = '' as $$
declare g public.study_groups;
begin
    select * into g from public.study_groups where id = p_group_id for share;
    if g.id is null or g.archived_at is not null or not exists(
        select 1 from public.study_group_members where group_id = g.id and user_id = p_actor
    ) then raise exception 'unavailable' using errcode = p_unavailable; end if;
    -- Explicit actor (service callers have no end-user JWT); never impersonate via claims.
    if not exists(select 1 from public.profiles where id = p_actor and not is_banned) then
        raise exception 'account unavailable' using errcode = 'G0003';
    end if;
    return g;
end;
$$;

-- Returns the document after checking group membership; outsiders get D0001.
create function studyhub_private.document_access(p_document_id uuid, p_actor uuid, p_lock boolean default false)
returns public.group_documents language plpgsql security definer set search_path = '' as $$
declare d public.group_documents; gid uuid;
begin
    select group_id into gid from public.group_documents where id = p_document_id;
    if gid is null then raise exception 'document unavailable' using errcode = 'D0001'; end if;
    perform studyhub_private.document_member(gid, p_actor, 'D0001');
    if p_lock then select * into d from public.group_documents where id = p_document_id for update;
    else select * into d from public.group_documents where id = p_document_id; end if;
    if d.archived_at is not null then raise exception 'document unavailable' using errcode = 'D0001'; end if;
    return d;
end;
$$;

create function studyhub_private.document_version_access(p_version_id uuid, p_actor uuid, p_lock boolean default false)
returns public.group_document_versions language plpgsql security definer set search_path = '' as $$
declare v public.group_document_versions; did uuid;
begin
    select document_id into did from public.group_document_versions where id = p_version_id;
    if did is null then raise exception 'document unavailable' using errcode = 'D0001'; end if;
    perform studyhub_private.document_access(did, p_actor);
    if p_lock then select * into v from public.group_document_versions where id = p_version_id for update;
    else select * into v from public.group_document_versions where id = p_version_id; end if;
    return v;
end;
$$;

create function studyhub_private.document_can_manage(p_document public.group_documents, p_actor uuid) returns boolean
language sql stable security definer set search_path = '' as $$
    select p_document.uploaded_by = p_actor
        or exists(select 1 from public.study_groups g where g.id = p_document.group_id and g.owner_id = p_actor);
$$;

create function studyhub_private.document_peer(p_id uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', p.id, 'display_name', p.display_name, 'avatar_url', p.avatar_url)
    from public.profiles p where p.id = p_id;
$$;

create function studyhub_private.document_version_summary(p_version_id uuid, p_actor uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', v.id, 'document_id', v.document_id, 'version_number', v.version_number,
        'page_count', v.page_count, 'size_bytes', v.size_bytes,
        'uploaded_by', studyhub_private.document_peer(v.uploaded_by),
        'is_current', d.current_version_id = v.id, 'created_at', v.created_at)
    from public.group_document_versions v join public.group_documents d on d.id = v.document_id
    where v.id = p_version_id;
$$;

create function studyhub_private.document_projection(p_document_id uuid, p_actor uuid) returns jsonb
language sql stable security definer set search_path = '' as $$
    select jsonb_build_object('id', d.id, 'group_id', d.group_id, 'title', d.title,
        'uploaded_by', studyhub_private.document_peer(d.uploaded_by),
        'can_manage', studyhub_private.document_can_manage(d, p_actor),
        'current_version', studyhub_private.document_version_summary(d.current_version_id, p_actor),
        'created_at', d.created_at, 'updated_at', d.updated_at)
    from public.group_documents d where d.id = p_document_id;
$$;

create function studyhub_private.document_upload_projection(p_upload public.group_document_uploads) returns jsonb
language sql stable set search_path = '' as $$
    select jsonb_build_object('id', p_upload.id, 'group_id', p_upload.group_id, 'document_id',
        coalesce(p_upload.document_id, (select v.document_id from public.group_document_versions v where v.id = p_upload.version_id)),
        'version_id', p_upload.version_id, 'bucket', 'group-documents', 'object_key', p_upload.object_key,
        'status', case when p_upload.status = 'pending' and p_upload.expires_at <= now() then 'expired' else p_upload.status end,
        'failure_reason', p_upload.failure_reason, 'expires_at', p_upload.expires_at);
$$;

-- Annotation state as stored in events; author name is resolved on read.
create function studyhub_private.document_annotation_state(p_annotation public.group_document_annotations) returns jsonb
language sql immutable set search_path = '' as $$
    select jsonb_build_object('id', p_annotation.id, 'version_id', p_annotation.version_id,
        'page_index', p_annotation.page_index, 'kind', p_annotation.kind,
        'data', case when p_annotation.deleted_at is null then p_annotation.data end,
        'author_id', p_annotation.author_id, 'row_version', p_annotation.row_version,
        'revision', p_annotation.revision, 'deleted', p_annotation.deleted_at is not null,
        'created_at', p_annotation.created_at, 'updated_at', p_annotation.updated_at);
$$;
create function studyhub_private.document_annotation_view(p_state jsonb) returns jsonb
language sql stable security definer set search_path = '' as $$
    select (p_state - 'author_id') || jsonb_build_object('author', jsonb_build_object('id', p.id, 'display_name', p.display_name))
    from public.profiles p where p.id = (p_state->>'author_id')::uuid;
$$;

-- ---------------------------------------------------------------------------
-- 4. Annotation payload validation (geometry/style/text/size whitelist)
-- ---------------------------------------------------------------------------
create function studyhub_private.document_point_ok(p jsonb) returns boolean
language sql immutable set search_path = '' as $$
    select jsonb_typeof(p) = 'array' and jsonb_array_length(p) = 2
        and jsonb_typeof(p->0) = 'number' and jsonb_typeof(p->1) = 'number'
        and (p->>0)::numeric between 0 and 1 and (p->>1)::numeric between 0 and 1;
$$;
create function studyhub_private.document_validate_annotation(p_kind text, p_data jsonb) returns void
language plpgsql immutable set search_path = '' as $$
declare text_value text; r jsonb;
begin
    if p_data is null or jsonb_typeof(p_data) <> 'object' or octet_length(p_data::text) > 65536 then
        raise exception 'invalid annotation' using errcode = 'G0001';
    end if;
    if p_kind = 'ink' then
        if (p_data - 'points' - 'color' - 'width') <> '{}'::jsonb
           or jsonb_typeof(p_data->'points') is distinct from 'array'
           or jsonb_array_length(p_data->'points') not between 1 and 4096
           or p_data->>'color' is null or p_data->>'color' not in ('#111827','#DC2626','#2563EB','#16A34A','#F59E0B','#9333EA')
           or jsonb_typeof(p_data->'width') is distinct from 'number'
           or (p_data->>'width')::numeric not between 0.001 and 0.05
           or exists(select 1 from jsonb_array_elements(p_data->'points') p where not studyhub_private.document_point_ok(p)) then
            raise exception 'invalid ink' using errcode = 'G0001';
        end if;
    elsif p_kind = 'highlight' then
        r := p_data->'rect';
        if (p_data - 'rect' - 'color') <> '{}'::jsonb
           or jsonb_typeof(r) is distinct from 'array' or jsonb_array_length(r) <> 4
           or p_data->>'color' is null or p_data->>'color' not in ('#FDE047','#86EFAC','#F9A8D4','#93C5FD')
           or not studyhub_private.document_point_ok(jsonb_build_array(r->0, r->1))
           or not studyhub_private.document_point_ok(jsonb_build_array(r->2, r->3))
           or (r->>0)::numeric >= (r->>2)::numeric or (r->>1)::numeric >= (r->>3)::numeric then
            raise exception 'invalid highlight' using errcode = 'G0001';
        end if;
    elsif p_kind = 'note' then
        if (p_data - 'anchor' - 'text' - 'color') <> '{}'::jsonb
           or not studyhub_private.document_point_ok(p_data->'anchor')
           or jsonb_typeof(p_data->'text') is distinct from 'string'
           or p_data->>'color' is null or p_data->>'color' not in ('#FDE047','#86EFAC','#F9A8D4','#93C5FD') then
            raise exception 'invalid note' using errcode = 'G0001';
        end if;
        text_value := p_data->>'text';
        if text_value <> studyhub_private.group_text(text_value) or char_length(text_value) not between 1 and 2000 then
            raise exception 'invalid note text' using errcode = 'G0001';
        end if;
    else
        raise exception 'invalid kind' using errcode = 'G0001';
    end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- 5. Upload reservation → private object → server validation → finalize
-- ---------------------------------------------------------------------------
create function public.reserve_group_document_upload(p_request_id uuid, p_group_id uuid, p_title text,
    p_size_bytes bigint, p_document_id uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); u public.group_document_uploads; d public.group_documents;
    clean_title text := nullif(studyhub_private.group_text(p_title), ''); new_id uuid := gen_random_uuid();
begin
    if p_request_id is null or p_group_id is null or p_size_bytes is null or p_size_bytes not between 1 and 20971520
       or (p_document_id is null and (clean_title is null or char_length(clean_title) > 200))
       or (p_document_id is not null and clean_title is not null) then
        raise exception 'invalid upload' using errcode = 'G0001';
    end if;
    perform studyhub_private.document_member(p_group_id, actor);
    select * into u from public.group_document_uploads where actor_id = actor and request_id = p_request_id for update;
    if u.id is not null then
        if (u.group_id, u.document_id, u.title, u.declared_size)
           is distinct from (p_group_id, p_document_id, clean_title, p_size_bytes) then
            raise exception 'request reused' using errcode = 'G0010';
        end if;
        return studyhub_private.document_upload_projection(u);
    end if;
    if p_document_id is not null then
        d := studyhub_private.document_access(p_document_id, actor, true);
        if d.group_id <> p_group_id then raise exception 'document unavailable' using errcode = 'D0001'; end if;
        if not studyhub_private.document_can_manage(d, actor) then raise exception 'forbidden' using errcode = 'G0005'; end if;
    end if;
    if (select count(*) from public.group_document_uploads
        where actor_id = actor and status = 'pending' and expires_at > now()) >= 5 then
        raise exception 'too many pending uploads' using errcode = 'G0010';
    end if;
    insert into public.group_document_uploads(id, group_id, document_id, actor_id, request_id, title,
        declared_size, object_key, expires_at)
    values (new_id, p_group_id, p_document_id, actor, p_request_id, clean_title, p_size_bytes,
        p_group_id::text || '/' || new_id::text || '.pdf', now() + interval '30 minutes')
    returning * into u;
    return studyhub_private.document_upload_projection(u);
end;
$$;

create function public.get_group_document_upload(p_upload_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); u public.group_document_uploads;
begin
    select * into u from public.group_document_uploads where id = p_upload_id and actor_id = actor;
    if u.id is null then raise exception 'upload unavailable' using errcode = 'D0005'; end if;
    perform studyhub_private.document_member(u.group_id, actor, 'D0005');
    return studyhub_private.document_upload_projection(u);
end;
$$;

-- Server-only (Edge Function with service role): authorize before downloading bytes.
create function public.service_prepare_group_document_validation(p_upload_id uuid, p_actor uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare u public.group_document_uploads;
begin
    select * into u from public.group_document_uploads where id = p_upload_id and actor_id = p_actor;
    if u.id is null then raise exception 'upload unavailable' using errcode = 'D0005'; end if;
    if not exists(select 1 from public.profiles where id = p_actor and not is_banned) then
        raise exception 'account unavailable' using errcode = 'G0003';
    end if;
    if not exists(select 1 from public.study_groups g join public.study_group_members m on m.group_id = g.id
                  where g.id = u.group_id and g.archived_at is null and m.user_id = p_actor) then
        raise exception 'upload unavailable' using errcode = 'D0005';
    end if;
    if u.status = 'pending' and u.expires_at <= now() then raise exception 'upload expired' using errcode = 'D0005'; end if;
    return studyhub_private.document_upload_projection(u);
end;
$$;

create function public.service_finalize_group_document_upload(p_upload_id uuid, p_actor uuid,
    p_content_hash text, p_size_bytes bigint, p_pages jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare u public.group_document_uploads; gid uuid; d public.group_documents; next_number integer; page jsonb;
begin
    select group_id into gid from public.group_document_uploads where id = p_upload_id and actor_id = p_actor;
    if gid is null then raise exception 'upload unavailable' using errcode = 'D0005'; end if;
    -- Recheck permission under the group lock: a concurrent removal commits first or waits.
    perform studyhub_private.document_member(gid, p_actor, 'D0005');
    select * into u from public.group_document_uploads where id = p_upload_id for update;
    if u.status = 'ready' then return studyhub_private.document_upload_projection(u); end if;
    if u.status <> 'pending' or u.expires_at <= now() then raise exception 'upload unavailable' using errcode = 'D0005'; end if;
    if p_content_hash is null or p_content_hash !~ '^[0-9a-f]{64}$' or p_size_bytes is null
       or p_size_bytes not between 1 and 20971520 or p_pages is null or jsonb_typeof(p_pages) <> 'array'
       or jsonb_array_length(p_pages) not between 1 and 100 then
        raise exception 'invalid metadata' using errcode = 'G0001';
    end if;
    for page in select * from jsonb_array_elements(p_pages) loop
        if jsonb_typeof(page) <> 'object' or (page - 'w' - 'h') <> '{}'::jsonb
           or jsonb_typeof(page->'w') is distinct from 'number' or jsonb_typeof(page->'h') is distinct from 'number'
           or (page->>'w')::numeric not between 3 and 14400 or (page->>'h')::numeric not between 3 and 14400 then
            raise exception 'invalid page metadata' using errcode = 'G0001';
        end if;
    end loop;
    if u.document_id is null then
        insert into public.group_documents(id, group_id, title, uploaded_by, current_version_id)
        values (gen_random_uuid(), u.group_id, u.title, p_actor, u.id) returning * into d;
        next_number := 1;
    else
        select * into d from public.group_documents where id = u.document_id for update;
        if d.archived_at is not null or not studyhub_private.document_can_manage(d, p_actor) then
            raise exception 'upload unavailable' using errcode = 'D0005';
        end if;
        select coalesce(max(version_number), 0) + 1 into next_number from public.group_document_versions where document_id = d.id;
        update public.group_documents set current_version_id = u.id, updated_at = now() where id = d.id;
    end if;
    insert into public.group_document_versions(id, document_id, version_number, object_key, content_hash,
        size_bytes, page_count, pages, uploaded_by)
    values (u.id, d.id, next_number, u.object_key, p_content_hash, p_size_bytes, jsonb_array_length(p_pages), p_pages, p_actor);
    update public.group_document_uploads set status = 'ready', version_id = u.id, resolved_at = now()
    where id = u.id returning * into u;
    perform studyhub_private.document_publish(u.group_id, jsonb_build_object('scope', 'library'));
    return studyhub_private.document_upload_projection(u);
end;
$$;

create function public.service_fail_group_document_upload(p_upload_id uuid, p_actor uuid, p_reason text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare u public.group_document_uploads;
begin
    if p_reason is null or p_reason not in ('empty','too_large','not_pdf','encrypted','corrupt','no_pages',
        'too_many_pages','invalid_page_size','missing_object') then
        raise exception 'invalid reason' using errcode = 'G0001';
    end if;
    update public.group_document_uploads set status = 'failed', failure_reason = p_reason, resolved_at = now()
    where id = p_upload_id and actor_id = p_actor and status = 'pending' returning * into u;
    if u.id is null then
        select * into u from public.group_document_uploads where id = p_upload_id and actor_id = p_actor;
        if u.id is null then raise exception 'upload unavailable' using errcode = 'D0005'; end if;
    end if;
    return studyhub_private.document_upload_projection(u);
end;
$$;

-- Cleanup worker: claim expired/failed objects, delete them through the Storage API,
-- then confirm. Ready objects are never returned.
create function public.service_claim_group_document_cleanup(p_limit integer default 100)
returns table(upload_id uuid, object_key text) language plpgsql security definer set search_path = '' as $$
begin
    update public.group_document_uploads set status = 'expired', resolved_at = now()
    where status = 'pending' and expires_at <= now();
    return query select u.id, u.object_key from public.group_document_uploads u
        where u.status in ('failed','expired') and u.object_removed_at is null
        order by u.expires_at limit greatest(1, least(coalesce(p_limit, 100), 500));
end;
$$;
create function public.service_confirm_group_document_cleanup(p_upload_ids uuid[]) returns integer
language plpgsql security definer set search_path = '' as $$
declare n integer;
begin
    update public.group_document_uploads set object_removed_at = now()
    where id = any(p_upload_ids) and status in ('failed','expired') and object_removed_at is null;
    get diagnostics n = row_count;
    delete from public.group_document_snapshots where expires_at < now() - interval '1 day';
    return n;
end;
$$;

-- ---------------------------------------------------------------------------
-- 6. Library reads and management
-- ---------------------------------------------------------------------------
create function public.list_group_documents(p_group_id uuid, p_cursor jsonb default null, p_limit integer default 20)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c record; r record; items jsonb := '[]'; next_cursor jsonb; n integer := 0;
begin
    select * into c from studyhub_private.group_cursor(p_cursor, p_limit);
    perform studyhub_private.document_member(p_group_id, actor);
    for r in select d.id, d.created_at from public.group_documents d
        where d.group_id = p_group_id and d.archived_at is null
          and (c.at is null or (d.created_at, d.id) < (c.at, c.id))
        order by d.created_at desc, d.id desc limit p_limit + 1 loop
        n := n + 1;
        if n > p_limit then return jsonb_build_object('items', items, 'next_cursor', next_cursor); end if;
        items := items || jsonb_build_array(studyhub_private.document_projection(r.id, actor));
        next_cursor := jsonb_build_object('at', r.created_at, 'id', r.id);
    end loop;
    return jsonb_build_object('items', items, 'next_cursor', null);
end;
$$;

create function public.get_group_document(p_document_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor();
begin
    perform studyhub_private.document_access(p_document_id, actor);
    return studyhub_private.document_projection(p_document_id, actor);
end;
$$;

create function public.list_group_document_versions(p_document_id uuid, p_cursor jsonb default null, p_limit integer default 20)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); c record; r record; items jsonb := '[]'; next_cursor jsonb; n integer := 0;
begin
    select * into c from studyhub_private.group_cursor(p_cursor, p_limit);
    perform studyhub_private.document_access(p_document_id, actor);
    for r in select v.id, v.created_at from public.group_document_versions v
        where v.document_id = p_document_id and (c.at is null or (v.created_at, v.id) < (c.at, c.id))
        order by v.created_at desc, v.id desc limit p_limit + 1 loop
        n := n + 1;
        if n > p_limit then return jsonb_build_object('items', items, 'next_cursor', next_cursor); end if;
        items := items || jsonb_build_array(studyhub_private.document_version_summary(r.id, actor));
        next_cursor := jsonb_build_object('at', r.created_at, 'id', r.id);
    end loop;
    return jsonb_build_object('items', items, 'next_cursor', null);
end;
$$;

create function public.get_group_document_version(p_version_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); v public.group_document_versions; d public.group_documents;
begin
    v := studyhub_private.document_version_access(p_version_id, actor);
    select * into d from public.group_documents where id = v.document_id;
    return studyhub_private.document_version_summary(v.id, actor) || jsonb_build_object(
        'group_id', d.group_id, 'title', d.title, 'bucket', 'group-documents', 'object_key', v.object_key,
        'content_hash', v.content_hash, 'pages', v.pages, 'revision', v.revision,
        'active_annotations', v.active_annotations, 'current_version_id', d.current_version_id,
        'can_manage', studyhub_private.document_can_manage(d, actor));
end;
$$;

create function public.archive_group_document(p_document_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); d public.group_documents;
begin
    d := studyhub_private.document_access(p_document_id, actor, true);
    if not studyhub_private.document_can_manage(d, actor) then raise exception 'forbidden' using errcode = 'G0005'; end if;
    update public.group_documents set archived_at = now(), archived_by = actor, updated_at = now() where id = d.id;
    perform studyhub_private.document_publish(d.group_id, jsonb_build_object('scope', 'library'));
    return jsonb_build_object('document_id', d.id, 'status', 'archived');
end;
$$;

-- ---------------------------------------------------------------------------
-- 7. Annotation mutations: CAS row_version, idempotent op ledger, ordered revisions
-- ---------------------------------------------------------------------------
create function public.mutate_group_document_annotation(p_op_id uuid, p_version_id uuid, p_annotation_id uuid,
    p_action text, p_expected_row_version integer default null, p_page_index integer default null,
    p_kind text default null, p_data jsonb default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); v public.group_document_versions; d public.group_documents;
    a public.group_document_annotations; prior public.group_document_operations; fp text; rev bigint;
    owner_id uuid; result jsonb; delta integer := 0;
begin
    if p_op_id is null or p_version_id is null or p_annotation_id is null
       or p_action is null or p_action not in ('create','update','delete','restore') then
        raise exception 'invalid mutation' using errcode = 'G0001';
    end if;
    fp := md5(jsonb_build_object('version', p_version_id, 'annotation', p_annotation_id, 'action', p_action,
        'expected', p_expected_row_version, 'page', p_page_index, 'kind', p_kind, 'data', p_data)::text);
    -- Authorization is rechecked for every call, including retries of committed operations.
    v := studyhub_private.document_version_access(p_version_id, actor, true);
    select * into d from public.group_documents where id = v.document_id;
    select g.owner_id into owner_id from public.study_groups g where g.id = d.group_id;
    select * into prior from public.group_document_operations where actor_id = actor and op_id = p_op_id;
    if prior.op_id is not null then
        if prior.fingerprint <> fp then raise exception 'operation id reused' using errcode = 'D0003'; end if;
        if p_action <> 'create' and actor <> owner_id
           and not exists(select 1 from public.group_document_annotations x where x.id = p_annotation_id and x.author_id = actor) then
            raise exception 'forbidden' using errcode = 'G0005';
        end if;
        return prior.result;
    end if;
    rev := v.revision + 1;
    if p_action = 'create' then
        if p_expected_row_version is not null or p_page_index is null or p_kind is null then
            raise exception 'invalid create' using errcode = 'G0001';
        end if;
        if p_page_index not between 0 and v.page_count - 1 then raise exception 'invalid page' using errcode = 'G0001'; end if;
        perform studyhub_private.document_validate_annotation(p_kind, p_data);
        if exists(select 1 from public.group_document_annotations where id = p_annotation_id) then
            raise exception 'annotation exists' using errcode = 'D0002';
        end if;
        if v.active_annotations >= 10000 then raise exception 'annotation limit' using errcode = 'D0004'; end if;
        insert into public.group_document_annotations(id, version_id, page_index, kind, data, author_id, revision, updated_by)
        values (p_annotation_id, v.id, p_page_index, p_kind, p_data, actor, rev, actor) returning * into a;
        delta := 1;
    else
        if p_expected_row_version is null then raise exception 'expected version required' using errcode = 'G0001'; end if;
        select * into a from public.group_document_annotations where id = p_annotation_id and version_id = v.id for update;
        if a.id is null then raise exception 'annotation unavailable' using errcode = 'D0001'; end if;
        if a.author_id <> actor and actor <> owner_id then raise exception 'forbidden' using errcode = 'G0005'; end if;
        if a.row_version <> p_expected_row_version then raise exception 'stale annotation' using errcode = 'D0002'; end if;
        if p_action = 'update' then
            if a.deleted_at is not null then raise exception 'annotation deleted' using errcode = 'D0002'; end if;
            if (p_kind is not null and p_kind <> a.kind) or (p_page_index is not null and p_page_index <> a.page_index) then
                raise exception 'immutable kind/page' using errcode = 'G0001';
            end if;
            perform studyhub_private.document_validate_annotation(a.kind, p_data);
            update public.group_document_annotations set data = p_data, row_version = row_version + 1, revision = rev,
                updated_at = now(), updated_by = actor where id = a.id returning * into a;
        elsif p_action = 'delete' then
            if a.deleted_at is not null then raise exception 'annotation deleted' using errcode = 'D0002'; end if;
            if p_kind is not null or p_page_index is not null or p_data is not null then
                raise exception 'invalid delete' using errcode = 'G0001';
            end if;
            update public.group_document_annotations set deleted_at = now(), row_version = row_version + 1, revision = rev,
                updated_at = now(), updated_by = actor where id = a.id returning * into a;
            delta := -1;
        else
            if a.deleted_at is null then raise exception 'annotation active' using errcode = 'D0002'; end if;
            if p_kind is not null or p_page_index is not null or p_data is not null then
                raise exception 'invalid restore' using errcode = 'G0001';
            end if;
            if v.active_annotations >= 10000 then raise exception 'annotation limit' using errcode = 'D0004'; end if;
            update public.group_document_annotations set deleted_at = null, row_version = row_version + 1, revision = rev,
                updated_at = now(), updated_by = actor where id = a.id returning * into a;
            delta := 1;
        end if;
    end if;
    update public.group_document_versions set revision = rev, active_annotations = active_annotations + delta where id = v.id;
    insert into public.group_document_events(version_id, revision, annotation_id, action, actor_id, state)
    values (v.id, rev, a.id, p_action, actor, studyhub_private.document_annotation_state(a));
    result := jsonb_build_object('op_id', p_op_id, 'action', p_action, 'revision', rev,
        'annotation', studyhub_private.document_annotation_view(studyhub_private.document_annotation_state(a)));
    insert into public.group_document_operations(actor_id, op_id, version_id, fingerprint, result)
    values (actor, p_op_id, v.id, fp, result);
    perform studyhub_private.document_publish(d.group_id,
        jsonb_build_object('scope', 'annotations', 'version_id', v.id, 'revision', rev));
    return result;
end;
$$;

-- ---------------------------------------------------------------------------
-- 8. Consistent snapshots, ordered deltas, revision polling and channel topics
-- ---------------------------------------------------------------------------
create function public.create_group_document_snapshot(p_version_id uuid, p_purpose text default 'view')
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); v public.group_document_versions; s public.group_document_snapshots;
begin
    if p_purpose is null or p_purpose not in ('view','export') then raise exception 'invalid purpose' using errcode = 'G0001'; end if;
    v := studyhub_private.document_version_access(p_version_id, actor);
    delete from public.group_document_snapshots where requested_by = actor and expires_at < now();
    insert into public.group_document_snapshots(version_id, revision, requested_by, purpose, expires_at)
    values (v.id, v.revision, actor, p_purpose, now() + interval '15 minutes') returning * into s;
    return jsonb_build_object('id', s.id, 'version_id', v.id, 'revision', s.revision, 'content_hash', v.content_hash,
        'purpose', s.purpose, 'expires_at', s.expires_at);
end;
$$;

create function public.list_group_document_snapshot_annotations(p_snapshot_id uuid, p_after uuid default null,
    p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); s public.group_document_snapshots; items jsonb; last_id uuid; n integer;
begin
    if p_limit is null or p_limit not between 1 and 500 then raise exception 'invalid limit' using errcode = 'G0001'; end if;
    select * into s from public.group_document_snapshots where id = p_snapshot_id and requested_by = actor;
    if s.id is null then raise exception 'snapshot unavailable' using errcode = 'D0006'; end if;
    perform studyhub_private.document_version_access(s.version_id, actor);
    if s.expires_at <= now() then raise exception 'snapshot expired' using errcode = 'D0006'; end if;
    with latest as (
        select distinct on (e.annotation_id) e.annotation_id, e.state
        from public.group_document_events e
        where e.version_id = s.version_id and e.revision <= s.revision
          and (p_after is null or e.annotation_id > p_after)
        order by e.annotation_id, e.revision desc
    ), page as (
        select l.annotation_id, l.state from latest l where not (l.state->>'deleted')::boolean
        order by l.annotation_id limit p_limit + 1
    ), numbered as (select p.*, row_number() over (order by p.annotation_id) as rn from page p)
    select coalesce(jsonb_agg(studyhub_private.document_annotation_view(x.state) order by x.annotation_id)
                    filter (where x.rn <= p_limit), '[]'::jsonb),
           max(x.annotation_id::text) filter (where x.rn = p_limit)::uuid, count(*)
    into items, last_id, n from numbered x;
    return jsonb_build_object('snapshot_id', s.id, 'version_id', s.version_id, 'revision', s.revision,
        'items', items, 'next_after', case when n > p_limit then last_id end);
end;
$$;

create function public.list_group_document_changes(p_version_id uuid, p_since bigint, p_limit integer default 200)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); v public.group_document_versions; items jsonb; last_rev bigint; n integer;
begin
    if p_since is null or p_since < 0 or p_limit is null or p_limit not between 1 and 500 then
        raise exception 'invalid range' using errcode = 'G0001';
    end if;
    v := studyhub_private.document_version_access(p_version_id, actor);
    if p_since > v.revision then
        return jsonb_build_object('version_id', v.id, 'revision', v.revision, 'items', '[]'::jsonb,
            'next_since', null, 'reset_required', true);
    end if;
    with page as (
        select a.revision, studyhub_private.document_annotation_state(a) as state from public.group_document_annotations a
        where a.version_id = v.id and a.revision > p_since order by a.revision limit p_limit + 1
    ), numbered as (select p.*, row_number() over (order by p.revision) as rn from page p)
    select coalesce(jsonb_agg(studyhub_private.document_annotation_view(x.state) order by x.revision)
                    filter (where x.rn <= p_limit), '[]'::jsonb),
           max(x.revision) filter (where x.rn = p_limit), count(*)
    into items, last_rev, n from numbered x;
    return jsonb_build_object('version_id', v.id, 'revision', v.revision, 'items', items,
        'next_since', case when n > p_limit then last_rev end, 'reset_required', false);
end;
$$;

create function public.get_group_document_revision(p_version_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); v public.group_document_versions; current_id uuid;
begin
    v := studyhub_private.document_version_access(p_version_id, actor);
    select current_version_id into current_id from public.group_documents where id = v.document_id;
    return jsonb_build_object('version_id', v.id, 'revision', v.revision, 'current_version_id', current_id);
end;
$$;

create function public.get_group_document_channel(p_group_id uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare actor uuid := studyhub_private.group_actor(); e uuid;
begin
    perform studyhub_private.document_member(p_group_id, actor);
    insert into public.group_document_channels(group_id) values (p_group_id) on conflict (group_id) do nothing;
    select epoch into e from public.group_document_channels where group_id = p_group_id;
    return jsonb_build_object('group_id', p_group_id, 'topic', 'group-docs:' || e::text);
end;
$$;

-- ---------------------------------------------------------------------------
-- 9. Storage and Realtime boundaries (policy helpers expose booleans only)
-- ---------------------------------------------------------------------------
create function studyhub_policy.can_upload_group_document(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists(
        select 1 from public.group_document_uploads u
        join public.study_groups g on g.id = u.group_id and g.archived_at is null
        join public.study_group_members m on m.group_id = g.id and m.user_id = u.actor_id
        join public.profiles p on p.id = u.actor_id and not p.is_banned
        where u.object_key = p_name and u.actor_id = (select auth.uid())
          and u.status = 'pending' and u.expires_at > now());
$$;
create function studyhub_policy.can_read_group_document(p_name text) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists(
        select 1 from public.group_document_versions v
        join public.group_documents d on d.id = v.document_id and d.archived_at is null
        join public.study_groups g on g.id = d.group_id and g.archived_at is null
        join public.study_group_members m on m.group_id = g.id and m.user_id = (select auth.uid())
        join public.profiles p on p.id = m.user_id and not p.is_banned
        where v.object_key = p_name);
$$;
create function studyhub_policy.can_receive_group_documents(p_topic text) returns boolean
language sql stable security definer set search_path = '' as $$
    select exists(
        select 1 from public.group_document_channels c
        join public.study_groups g on g.id = c.group_id and g.archived_at is null
        join public.study_group_members m on m.group_id = g.id and m.user_id = (select auth.uid())
        join public.profiles p on p.id = m.user_id and not p.is_banned
        where p_topic = 'group-docs:' || c.epoch::text);
$$;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('group-documents', 'group-documents', false, 20971520, array['application/pdf'])
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;
-- Insert only into an unexpired reservation; no UPDATE/DELETE policy, so ready objects
-- cannot be overwritten (no upsert) or removed by clients.
drop policy if exists group_documents_insert on storage.objects;
create policy group_documents_insert on storage.objects for insert to authenticated
    with check (bucket_id = 'group-documents' and studyhub_policy.can_upload_group_document(name));
drop policy if exists group_documents_select on storage.objects;
create policy group_documents_select on storage.objects for select to authenticated
    using (bucket_id = 'group-documents' and studyhub_policy.can_read_group_document(name));
-- Restrictive guard so a future permissive policy cannot expose group documents.
drop policy if exists group_documents_select_guard on storage.objects;
create policy group_documents_select_guard on storage.objects as restrictive for select to authenticated
    using (bucket_id <> 'group-documents' or studyhub_policy.can_read_group_document(name));
drop policy if exists group_documents_insert_guard on storage.objects;
create policy group_documents_insert_guard on storage.objects as restrictive for insert to authenticated
    with check (bucket_id <> 'group-documents' or studyhub_policy.can_upload_group_document(name));

drop policy if exists group_documents_receive on realtime.messages;
create policy group_documents_receive on realtime.messages for select to authenticated
    using (extension = 'broadcast' and studyhub_policy.can_receive_group_documents(realtime.topic()));
drop policy if exists group_documents_receive_guard on realtime.messages;
create policy group_documents_receive_guard on realtime.messages as restrictive for select to authenticated
    using (coalesce(realtime.topic(), '') not like 'group-docs:%'
        or (extension = 'broadcast' and studyhub_policy.can_receive_group_documents(realtime.topic())));
drop policy if exists group_documents_no_client_send on realtime.messages;
create policy group_documents_no_client_send on realtime.messages as restrictive for insert to authenticated
    with check (coalesce(realtime.topic(), topic, '') not like 'group-docs:%' and topic not like 'group-docs:%');

-- ---------------------------------------------------------------------------
-- 10. Grants: public RPCs to authenticated, workers to service_role, helpers to nobody.
-- ---------------------------------------------------------------------------
do $$ declare f record;
begin
    for f in select p.oid::regprocedure as signature, n.nspname, p.proname from pg_proc p
        join pg_namespace n on n.oid = p.pronamespace
        where (n.nspname = 'studyhub_private' and (p.proname like 'document\_%' escape '\'
                or p.proname like '%group\_document%' escape '\'))
           or n.nspname = 'studyhub_policy'
           or (n.nspname = 'public' and p.proname = any(array[
               'reserve_group_document_upload','get_group_document_upload','list_group_documents',
               'get_group_document','list_group_document_versions','get_group_document_version',
               'archive_group_document','mutate_group_document_annotation','create_group_document_snapshot',
               'list_group_document_snapshot_annotations','list_group_document_changes',
               'get_group_document_revision','get_group_document_channel']))
           or (n.nspname = 'public' and p.proname like 'service\_%group\_document%' escape '\') loop
        execute format('revoke all on function %s from public, anon, authenticated, service_role', f.signature);
        if f.nspname = 'public' and f.proname like 'service\_%' escape '\' then
            -- Edge Functions only: they pass the JWT-verified actor explicitly.
            execute format('grant execute on function %s to service_role', f.signature);
        elsif f.nspname in ('public', 'studyhub_policy') then
            execute format('grant execute on function %s to authenticated', f.signature);
        end if;
    end loop;
end $$;

notify pgrst, 'reload schema';
commit;
