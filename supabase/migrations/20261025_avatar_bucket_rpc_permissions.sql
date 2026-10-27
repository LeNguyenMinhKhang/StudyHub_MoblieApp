-- StudyHub project URL below matches SUPABASE_URL in local.properties.
-- When applying to another project, update the function's v_project_url constant.
-- Run this entire file in the Supabase SQL Editor.
begin;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', true, 10485760, array['image/jpeg'])
on conflict (id) do update
    set public            = true,
        file_size_limit   = 10485760,
        allowed_mime_types = array['image/jpeg'];

drop policy if exists "avatars_public_read" on storage.objects;
create policy "avatars_public_read"
    on storage.objects for select
    using (bucket_id = 'avatars');

drop policy if exists "avatars_owner_insert" on storage.objects;
create policy "avatars_owner_insert"
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'avatars'
        and auth.uid() is not null
        and not exists (
            select 1 from public.profiles p
            where p.id = auth.uid() and p.is_banned
        )
        and (storage.foldername(name))[1] = auth.uid()::text
    );

drop policy if exists "avatars_owner_delete" on storage.objects;
create policy "avatars_owner_delete"
    on storage.objects for delete
    to authenticated
    using (
        bucket_id = 'avatars'
        and auth.uid() is not null
        and (storage.foldername(name))[1] = auth.uid()::text
        and not exists (
            select 1 from public.profiles p
            where p.avatar_url is not null
              and p.avatar_url like '%/avatars/' || name || '%'
        )
    );

create or replace function public.reject_direct_avatar_url_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
    if new.avatar_url is distinct from old.avatar_url
       and current_setting('app.allow_avatar_update', true) is distinct from 'on' then
        raise exception 'direct avatar_url update not allowed; use update_my_avatar RPC'
            using errcode = '42501';
    end if;
    return new;
end;
$$;

drop trigger if exists profiles_block_direct_avatar_update on public.profiles;
create trigger profiles_block_direct_avatar_update
    before update on public.profiles
    for each row
    when (new.avatar_url is distinct from old.avatar_url)
    execute function public.reject_direct_avatar_url_write();

create or replace function public.update_my_avatar(
    p_object_path text,
    p_expected_avatar_url text default null
)
returns setof public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid          uuid := auth.uid();
    v_profile      public.profiles%rowtype;
    v_new_url      text;
    v_project_url  constant text := 'https://igajdsukpotsplzjjqls.supabase.co';
begin
    if v_uid is null then
        raise exception 'not authenticated' using errcode = '42501';
    end if;

    select * into v_profile
    from public.profiles p
    where p.id = v_uid
    for update;

    if not found then
        raise exception 'profile not found' using errcode = 'P0002';
    end if;

    if v_profile.is_banned then
        raise exception 'account is banned' using errcode = '42501';
    end if;

    if p_expected_avatar_url is distinct from v_profile.avatar_url then
        raise exception 'avatar conflict: profile was updated by another session; refresh and retry'
            using errcode = '40001';
    end if;

    if p_object_path is null then
        v_new_url := null;
    else
        if not starts_with(p_object_path, v_uid::text || '/') then
            raise exception 'object path does not belong to caller'
                using errcode = '42501';
        end if;

        if not exists (
            select 1 from storage.objects so
            where so.bucket_id = 'avatars'
              and so.name = p_object_path
              and (so.metadata->>'mimetype' = 'image/jpeg' or so.metadata->>'contentType' = 'image/jpeg')
        ) then
            raise exception 'avatar object not found or not a JPEG in avatars bucket'
                using errcode = '42501';
        end if;

        v_new_url := v_project_url || '/storage/v1/object/public/avatars/' || p_object_path;
    end if;

    perform set_config('app.allow_avatar_update', 'on', true);

    update public.profiles p
    set avatar_url = v_new_url
    where p.id = v_uid
    returning p.* into v_profile;

    return next v_profile;
end;
$$;

revoke execute on function public.reject_direct_avatar_url_write()
    from public, anon, authenticated;
revoke execute on function public.update_my_avatar(text, text)
    from public, anon;
grant execute on function public.update_my_avatar(text, text) to authenticated;

commit;
