-- Apply to a development project first. Do not deploy until every supported client
-- uses profile RPCs and no supported client inserts a profile after Auth signup.
-- Preflight must report zero invalid names and case-insensitive collisions.

alter table public.profiles
    alter column username set not null,
    alter column display_name set not null,
    alter column created_at set not null,
    alter column role set default 'user',
    alter column is_banned set default false,
    alter column created_at set default now();

alter table public.profiles
    add constraint profiles_username_format check (username ~ '^[A-Za-z0-9_]{3,30}$'),
    add constraint profiles_display_name_format check (
        display_name = btrim(display_name)
        and char_length(display_name) between 1 and 80
    ),
    add constraint profiles_bio_length check (bio is null or char_length(bio) <= 500);

create unique index profiles_username_ci_key on public.profiles (lower(username));

create function public.create_profile_for_auth_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    new_username text := lower(btrim(new.raw_user_meta_data ->> 'username'));
    new_display_name text := btrim(new.raw_user_meta_data ->> 'display_name');
begin
    if new_username is null or new_username !~ '^[a-z0-9_]{3,30}$'
        or new_display_name is null or char_length(new_display_name) not between 1 and 80 then
        raise exception 'invalid profile registration metadata' using errcode = '23514';
    end if;

    insert into public.profiles (id, username, display_name, avatar_url, bio)
    values (new.id, new_username, new_display_name, null, null);
    return new;
end;
$$;

revoke all on function public.create_profile_for_auth_user() from public, anon, authenticated;

create trigger on_auth_user_created_create_profile
after insert on auth.users
for each row execute function public.create_profile_for_auth_user();

-- RPCs expose only the fields needed by each caller. Their definer privileges
-- bypass table RLS, so every function checks auth.uid() and constrains rows.
create function public.get_my_profile()
returns setof public.profiles
language sql
stable
security definer
set search_path = ''
as $$
    select p.* from public.profiles p
    where auth.uid() is not null and p.id = auth.uid()
    limit 1;
$$;

create function public.get_public_profile_by_id(p_id uuid)
returns table(id uuid, username text, display_name text, avatar_url text, bio text, created_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
    select p.id, p.username::text, p.display_name::text, p.avatar_url::text,
           p.bio::text, p.created_at
    from public.profiles p
    where auth.uid() is not null and p.id = p_id and not p.is_banned
    limit 1;
$$;

create function public.get_public_profile_by_username(p_username text)
returns table(id uuid, username text, display_name text, avatar_url text, bio text, created_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
    select p.id, p.username::text, p.display_name::text, p.avatar_url::text,
           p.bio::text, p.created_at
    from public.profiles p
    where auth.uid() is not null
      and lower(p.username) = lower(btrim(p_username))
      and not p.is_banned
    limit 1;
$$;

create function public.update_my_profile(p_username text, p_display_name text, p_bio text)
returns setof public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    cleaned_username text := lower(btrim(p_username));
    cleaned_display_name text := btrim(p_display_name);
    cleaned_bio text := nullif(btrim(p_bio), '');
begin
    if auth.uid() is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;
    if cleaned_username is null or (
        not exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and p.username = p_username
        ) and cleaned_username !~ '^[a-z0-9_]{3,30}$'
    )
        or cleaned_display_name is null or char_length(cleaned_display_name) not between 1 and 80
        or char_length(coalesce(cleaned_bio, '')) > 500 then
        raise exception 'invalid profile input' using errcode = '23514';
    end if;

    return query
    update public.profiles p
    set username = case when p.username = p_username then p.username else cleaned_username end,
        display_name = cleaned_display_name,
        bio = cleaned_bio
    where p.id = auth.uid() and not p.is_banned
    returning p.*;
end;
$$;

revoke all on public.profiles from public, anon, authenticated;
revoke all on function public.get_my_profile() from public, anon;
revoke all on function public.get_public_profile_by_id(uuid) from public, anon;
revoke all on function public.get_public_profile_by_username(text) from public, anon;
revoke all on function public.update_my_profile(text, text, text) from public, anon;
grant execute on function public.get_my_profile() to authenticated;
grant execute on function public.get_public_profile_by_id(uuid) to authenticated;
grant execute on function public.get_public_profile_by_username(text) to authenticated;
grant execute on function public.update_my_profile(text, text, text) to authenticated;

-- Keep RLS enabled as defense in depth. Direct client table access has no grants.
drop policy if exists profiles_insert_own on public.profiles;
drop policy if exists profiles_select_all on public.profiles;
drop policy if exists profiles_update_own on public.profiles;
