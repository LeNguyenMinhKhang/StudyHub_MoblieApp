"""Smoke migration against a schema-only clone of the local Supabase test stack."""
from pathlib import Path
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[3]
CONTAINER = "supabase_db_studyhub-notification-tests"
DATABASE = "studyhub_groups_test_" + uuid.uuid4().hex


def command(*args, data=None):
    result = subprocess.run(["docker", "exec", "-i", CONTAINER, *args], input=data,
                            text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout


def sql(statement, database=DATABASE):
    return command("psql", "-X", "-U", "supabase_admin", "-d", database, "-v", "ON_ERROR_STOP=1", "-qAt", data=statement)


if __name__ == "__main__":
    created = False
    try:
        # Fixed local Docker target only; no URL/host/credentials are accepted.
        dump = command("pg_dump", "-U", "supabase_admin", "-d", "postgres", "--schema-only", "--no-owner",
                       "--exclude-extension=pg_cron", "--exclude-schema=cron")
        sql(f'create database "{DATABASE}" template template0;', "postgres")
        created = True
        sql(dump)
        sql((ROOT / "supabase/migrations/20261113100000_study_groups.sql").read_text(encoding="utf-8"))
        sql("""
          begin;
          insert into auth.users(id,raw_user_meta_data) values
            ('00000000-0000-0000-0000-000000000001','{"username":"sg_owner","display_name":"Group Owner"}'),
            ('00000000-0000-0000-0000-000000000002','{"username":"sg_invitee","display_name":"Group Invitee"}');
          insert into public.subjects(id,name) values('00000000-0000-0000-0000-000000000101','Group subject');
          insert into public.friendships(requester_id,addressee_id,status) values
            ('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','accepted');
          create temp table baseline_counts as select
            (select count(*) from public.notifications) as notifications,
            (select count(*) from public.conversations) as conversations;
          set local request.jwt.claim.sub='00000000-0000-0000-0000-000000000001';
          set local role authenticated;
          select public.create_study_group('00000000-0000-0000-0000-000000000102',
            '00000000-0000-0000-0000-000000000101','Group smoke',null)->>'id' as gid \gset
          select public.invite_study_group_member(:'gid','00000000-0000-0000-0000-000000000002')->>'id' as iid \gset
          set local request.jwt.claim.sub='00000000-0000-0000-0000-000000000002';
          select public.respond_study_group_invitation(:'iid','accept');
          reset role;
          do $$ begin
            if (select count(*) from public.notifications) <> (select notifications from baseline_counts)
               or (select count(*) from public.conversations) <> (select conversations from baseline_counts) then
              raise exception 'Group operation changed chat/notification counts';
            end if;
          end $$;
          rollback;
        """)
        snapshot = (ROOT / "supabase/schema.sql").read_text(encoding="utf-8-sig").split("-- Study groups: additive snapshot", 1)[1]
        snapshot = "-- Study groups: additive snapshot" + snapshot
        sql(snapshot)  # Existing schema must be a harmless no-op.
        sql("drop table public.study_group_invitations, public.study_group_members, public.study_groups cascade;")
        # Drop feature routines before validating the fresh snapshot path.
        sql("""do $$ declare f record; begin
          for f in select p.oid::regprocedure s from pg_proc p join pg_namespace n on n.oid=p.pronamespace
            where (n.nspname='studyhub_private' and p.proname like 'group\\_%' escape '\\')
               or (n.nspname='public' and p.proname like '%study_group%') loop
            execute format('drop function if exists %s cascade',f.s);
          end loop; end $$;""")
        sql(snapshot)
        print("PASS full local Supabase schema clone: migration, auth roles, create/invite/accept, no chat/notification side effects, snapshot fresh/reapply")
    finally:
        if created:
            # Exact generated database only; source postgres DB is never modified.
            sql(f'drop database "{DATABASE}" with (force);', "postgres")
