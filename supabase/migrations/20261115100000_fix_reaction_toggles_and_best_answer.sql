
begin;

-- =========================================================
-- 1. Toggle post reaction atomically
-- =========================================================
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
  v_locked_post uuid;
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
    select 1
    from public.profiles p
    where p.id = v_actor and not p.is_banned
  ) then
    raise exception 'account unavailable' using errcode = 'F0006';
  end if;

  if not studyhub_private.post_viewable(v_actor, p_post_id) then
    raise exception 'post unavailable' using errcode = 'F0004';
  end if;

  -- Serialize reaction changes for the same post.
  select p.id into v_locked_post
  from public.posts p
  where p.id = p_post_id
  for update;

  if v_locked_post is null then
    raise exception 'post unavailable' using errcode = 'F0004';
  end if;

  select l.reaction_type into v_existing
  from public.likes l
  where l.post_id = p_post_id
    and l.user_id = v_actor
  for update;

  -- Null means remove; selecting the same reaction also removes it.
  if p_reaction_type is null or v_existing = p_reaction_type then
    delete from public.likes
    where post_id = p_post_id
      and user_id = v_actor;

    return null;
  end if;

  insert into public.likes(post_id, user_id, reaction_type)
  values (p_post_id, v_actor, p_reaction_type)
  on conflict (post_id, user_id)
  do update
    set reaction_type = excluded.reaction_type
  returning reaction_type into v_existing;

  return v_existing;
end;
$$;


-- =========================================================
-- 2. Toggle comment reaction atomically
-- =========================================================
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
  v_locked_comment uuid;
  v_existing text;
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
    select 1
    from public.profiles p
    where p.id = v_actor and not p.is_banned
  ) then
    raise exception 'account unavailable' using errcode = 'F0006';
  end if;

  -- Lock the comment row to serialize reaction changes on this comment.
  select c.id, c.post_id
    into v_locked_comment, v_post_id
  from public.comments c
  where c.id = p_comment_id
  for update;

  if v_locked_comment is null
     or not studyhub_private.post_viewable(v_actor, v_post_id) then
    raise exception 'comment unavailable' using errcode = 'F0004';
  end if;

  select r.reaction_type into v_existing
  from public.comment_reactions r
  where r.comment_id = p_comment_id
    and r.user_id = v_actor
  for update;

  if p_reaction_type is null or v_existing = p_reaction_type then
    delete from public.comment_reactions
    where comment_id = p_comment_id
      and user_id = v_actor;

    return null;
  end if;

  insert into public.comment_reactions(
    comment_id,
    user_id,
    reaction_type,
    updated_at
  )
  values (
    p_comment_id,
    v_actor,
    p_reaction_type,
    now()
  )
  on conflict (comment_id, user_id)
  do update
    set reaction_type = excluded.reaction_type,
        updated_at = now()
  returning reaction_type into v_existing;

  return v_existing;
end;
$$;


-- =========================================================
-- 3. Toggle / replace / clear best answer
-- =========================================================
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
  v_new_best uuid;
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
    raise exception 'only post owner can choose best answer'
      using errcode = '42501';
  end if;

  select pr.is_banned into v_is_banned
  from public.profiles pr
  where pr.id = v_actor;

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
      raise exception 'comment does not belong to post'
        using errcode = 'F0001';
    end if;
  end if;

  -- Selecting the currently selected answer again means clear it.
  if p_comment_id is not null and v_old_best = p_comment_id then
    v_new_best := null;
  else
    v_new_best := p_comment_id;
  end if;

  -- Clear previous best-answer flags first.
  update public.comments
  set is_best_answer = false
  where post_id = p_post_id
    and is_best_answer;

  if v_new_best is not null then
    update public.comments
    set is_best_answer = true
    where id = v_new_best
      and post_id = p_post_id;
  end if;

  -- Keep the post pointer consistent with the comment flag.
  update public.posts
  set best_comment_id = v_new_best
  where id = p_post_id;

  -- Notify only when a new best answer is selected.
  -- Clearing the answer does not generate a notification.
  if v_new_best is not null
     and v_comment_author is not null
     and v_comment_author <> v_actor
     and exists (
       select 1
       from public.profiles pr
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
    ), true)
    into v_enabled;

    if v_enabled then
      insert into public.notifications(
        user_id,
        actor_id,
        type,
        post_id,
        source_kind,
        source_id,
        event_key,
        template_version
      )
      values (
        v_comment_author,
        v_actor,
        'best_answer',
        p_post_id,
        'comment',
        v_new_best,
        'best_answer:' || v_new_best::text,
        1
      )
      on conflict (user_id, event_key)
        where event_key is not null
      do nothing;
    end if;
  end if;

  return jsonb_build_object(
    'post_id', p_post_id,
    'best_comment_id', v_new_best
  );
end;
$$;


-- =========================================================
-- 4. Keep RPC permissions restricted to authenticated users
-- =========================================================
revoke all on function public.set_post_reaction(uuid, text)
  from public, anon, authenticated;

revoke all on function public.set_comment_reaction(uuid, text)
  from public, anon, authenticated;

revoke all on function public.set_best_answer(uuid, uuid)
  from public, anon, authenticated;

grant execute on function public.set_post_reaction(uuid, text)
  to authenticated;

grant execute on function public.set_comment_reaction(uuid, text)
  to authenticated;

grant execute on function public.set_best_answer(uuid, uuid)
  to authenticated;

commit;
