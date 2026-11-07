-- Hotfix: 20261106120000/120100 originally ended with
--   revoke all on all functions in schema studyhub_private from public, anon, authenticated;
-- which also removed the EXECUTE grant that unified search (20261106_unified_search)
-- gives authenticated on its normalizers. posts/profiles/subjects/post_files have
-- expression indexes calling these functions with the writer's privileges, so every
-- insert/update by an app user failed with
--   permission denied for function search_norm
-- Restores exactly the grants unified search defines. Safe to re-run; skips
-- databases where unified search is not installed.
begin;

do $$
begin
  if to_regprocedure('studyhub_private.search_norm(text)') is not null then
    grant execute on function studyhub_private.search_norm(text) to authenticated, service_role;
  end if;
  if to_regprocedure('studyhub_private.search_file_norm(text)') is not null then
    grant execute on function studyhub_private.search_file_norm(text) to authenticated, service_role;
  end if;
end $$;

notify pgrst, 'reload schema';
commit;
