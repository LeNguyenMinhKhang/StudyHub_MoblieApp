-- Local-only shim so supabase/schema.sql and migrations can run on a plain,
-- disposable PostgreSQL 15 container. Never run this against a Supabase project.
--
--   docker run -d --name studyhub-search-test -e POSTGRES_PASSWORD=localonly \
--     -e POSTGRES_DB=studyhub postgres:15-alpine
--   docker exec -i studyhub-search-test psql -U postgres -d studyhub -v ON_ERROR_STOP=1 < supabase/tests/local_supabase_shim.sql
--   docker exec -i studyhub-search-test psql -U postgres -d studyhub < supabase/schema.sql
--   docker exec -i studyhub-search-test psql -U postgres -d studyhub -v ON_ERROR_STOP=1 < supabase/migrations/20261106_unified_search.sql
--   docker exec -i studyhub-search-test psql -U postgres -d studyhub -v ON_ERROR_STOP=1 < supabase/tests/unified_search_test.sql
--   docker rm -f studyhub-search-test

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'anon') then create role anon nologin; end if;
    if not exists (select 1 from pg_roles where rolname = 'authenticated') then create role authenticated nologin; end if;
    if not exists (select 1 from pg_roles where rolname = 'service_role') then create role service_role nologin bypassrls; end if;
end;
$$;

create schema if not exists extensions;
grant usage on schema extensions to anon, authenticated, service_role;

create schema if not exists auth;
grant usage on schema auth to anon, authenticated, service_role;
create table if not exists auth.users (
    id uuid primary key,
    email text,
    raw_user_meta_data jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

-- Supabase reads the caller from request.jwt.claims.
create or replace function auth.uid() returns uuid language sql stable as $$
    select nullif(nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'sub', '')::uuid
$$;
create or replace function auth.role() returns text language sql stable as $$
    select coalesce(nullif(current_setting('request.jwt.claims', true), '')::jsonb ->> 'role', 'anon')
$$;
grant execute on function auth.uid(), auth.role() to anon, authenticated, service_role;

create schema if not exists storage;
grant usage on schema storage to anon, authenticated, service_role;
create table if not exists storage.buckets (
    id text primary key,
    name text not null,
    public boolean default false,
    file_size_limit bigint,
    allowed_mime_types text[]
);
create table if not exists storage.objects (
    id uuid primary key default gen_random_uuid(),
    bucket_id text references storage.buckets(id),
    name text,
    owner uuid,
    metadata jsonb,
    created_at timestamptz not null default now()
);
alter table storage.objects enable row level security;

do $$
begin
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        create publication supabase_realtime;
    end if;
end;
$$;

-- Mirror Supabase's default privileges for objects created in public.
grant usage on schema public to anon, authenticated, service_role;
alter default privileges in schema public grant all on tables to anon, authenticated, service_role;
alter default privileges in schema public grant all on sequences to anon, authenticated, service_role;
alter default privileges in schema public grant execute on functions to anon, authenticated, service_role;
