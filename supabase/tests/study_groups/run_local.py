"""Disposable PostgreSQL tests. No connection URL or existing container is accepted."""
from pathlib import Path
import json
import re
import subprocess
import time
import unittest
import uuid
import argparse

ROOT = Path(__file__).resolve().parents[3]
IMAGE = "postgres:17-alpine"
LABEL = "studyhub.test=study-groups"
CONTAINER = "studyhub-groups-test-" + uuid.uuid4().hex[:12]
OWNER, MEMBER, INVITEE, OUTSIDER, BANNED = [str(uuid.UUID(int=i)) for i in range(1, 6)]
SUBJECT = str(uuid.UUID(int=101))


def docker(*args, data=None, check=True):
    result = subprocess.run(["docker", *args], input=data, text=True, encoding="utf-8",
                            capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result


def sql(statement, actor=None, role=None, check=True):
    prefix = ""
    if actor is not None:
        prefix += f"set request.jwt.claim.sub = '{actor}'; "
    if role or actor:
        prefix += f"set role {role or 'authenticated'}; "
    result = docker("exec", "-i", CONTAINER, "psql", "-X", "-U", "postgres", "-d", "postgres",
                    "-v", "ON_ERROR_STOP=1", "-qAt", data="\\set VERBOSITY verbose\n" + prefix + statement, check=False)
    if check and result.returncode:
        raise AssertionError(result.stderr)
    return result


def value(statement, actor=OWNER):
    output = sql(statement, actor).stdout.strip().splitlines()
    return json.loads(output[-1])


def setup():
    docker("run", "-d", "--name", CONTAINER, "--label", LABEL, "--network", "none",
           "-e", "POSTGRES_HOST_AUTH_METHOD=trust", IMAGE)
    # The image runs a temporary server during initdb; wait for the final one, not that one.
    for _ in range(300):
        logs = docker("logs", CONTAINER, check=False)
        initialized = "init process complete" in logs.stdout + logs.stderr
        if initialized and docker("exec", CONTAINER, "pg_isready", "-U", "postgres", check=False).returncode == 0:
            break
        time.sleep(0.1)
    else:
        raise RuntimeError("Disposable PostgreSQL did not start")
    # Actual base table definitions from the checked-out snapshot, never a hosted DB.
    schema = (ROOT / "supabase/schema.sql").read_text(encoding="utf-8-sig")
    baseline = """
        create role anon; create role authenticated;
        create schema auth; create table auth.users(id uuid primary key);
        create function auth.uid() returns uuid language sql stable as
        $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
        grant usage on schema auth to authenticated, anon;
        grant execute on function auth.uid() to authenticated, anon;
    """
    for table in ("profiles", "subjects", "friendships", "blocks", "conversations", "conversation_members", "messages"):
        match = re.search(r"create table if not exists public\." + table + r" \([\s\S]*?\n\);", schema)
        if not match:
            raise RuntimeError(f"Missing baseline definition: {table}")
        baseline += match.group(0)
    # Chat tables and the previous membership helper: the study-group-chat migration redefines them.
    baseline += """
        alter table public.conversations enable row level security;
        alter table public.conversation_members enable row level security;
        alter table public.messages enable row level security;
        create function public.is_conversation_member(p_conversation_id uuid, p_user_id uuid)
        returns boolean language sql security definer set search_path = 'public','auth','pg_temp' stable as
        $$ select exists (select 1 from public.conversation_members where conversation_id = p_conversation_id and user_id = p_user_id); $$;
    """
    sql(baseline)
    sql((ROOT / "supabase/migrations/20261027_friendship_rpc.sql").read_text(encoding="utf-8-sig"))
    sql((ROOT / "supabase/migrations/20261113100000_study_groups.sql").read_text(encoding="utf-8-sig"))
    for name in ("20261108130000_block_direct_chat.sql", "20261116_chat_status_and_documents.sql",
                 "20261125100000_study_group_chat.sql"):
        sql((ROOT / "supabase/migrations" / name).read_text(encoding="utf-8-sig"))
    for index, actor in enumerate((OWNER, MEMBER, INVITEE, OUTSIDER, BANNED)):
        sql(f"insert into auth.users values('{actor}'); insert into public.profiles(id,username,display_name,is_banned) "
            f"values('{actor}','test_{index}','Test {index}',{str(actor == BANNED).lower()});")
    sql(f"insert into public.subjects(id,name) values('{SUBJECT}','Test subject');")


def write_payload_fixtures():
    group = value(f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','Nhóm 📚',null);")
    gid = group["id"]
    sql(f"insert into public.friendships(requester_id,addressee_id,status) values('{OWNER}','{INVITEE}','accepted');")
    invitation = value(f"select public.invite_study_group_member('{gid}','{INVITEE}');")
    payloads = {
        "detail": group,
        "groups": value("select public.list_my_study_groups();"),
        "members": value(f"select public.list_study_group_members('{gid}');"),
        "incoming": value("select public.list_my_study_group_invitations();", INVITEE),
        "outgoing": value(f"select public.list_study_group_invitations('{gid}');"),
        "invitation_result": invitation,
        "mutation_result": value(f"select public.archive_study_group('{gid}');"),
    }
    # Created last so the list payloads above keep their single group.
    payloads["detail_no_subject"] = value(f"select public.create_study_group('{uuid.uuid4()}',null,'Nhóm không môn',null);")
    target = ROOT / "backend/src/test/resources/study_groups/rpc_payloads.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payloads, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


class GroupsTest(unittest.TestCase):
    def setUp(self):
        sql("begin; delete from public.study_group_invitations; delete from public.study_group_members; "
            "delete from public.study_groups; delete from public.friendships; "
            "update public.subjects set is_active=true; "
            f"update public.profiles set is_banned=(id='{BANNED}'); commit;")

    def fail_sql(self, statement, code, actor=OWNER, role=None):
        result = sql(statement, actor, role, check=False)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(code, result.stderr)

    def create(self, key=None, name="Nhóm học 📚", actor=OWNER):
        key = key or str(uuid.uuid4())
        return value(f"select public.create_study_group('{key}','{SUBJECT}','{name}',null);", actor)

    def friends(self, a=OWNER, b=INVITEE):
        sql(f"insert into public.friendships(requester_id,addressee_id,status) values('{a}','{b}','accepted');")

    def invite(self, gid, target=INVITEE, actor=OWNER):
        return value(f"select public.invite_study_group_member('{gid}','{target}');", actor)

    def join(self, gid, target=MEMBER):
        self.friends(OWNER, target)
        invitation = self.invite(gid, target)
        value(f"select public.respond_study_group_invitation('{invitation['id']}','accept');", target)
        return invitation

    def test_create_normalization_unicode_and_validation(self):
        g = self.create(name="  Nhóm 📚  ")
        self.assertEqual(g["name"], "Nhóm 📚")
        self.assertEqual(g["member_count"], 1)
        self.assertEqual(g["role"], "owner")
        self.assertEqual(set(g), {"id", "name", "description", "subject", "role", "member_count", "created_at", "updated_at"})
        for name in ("", "   ", "x" * 101, "📚" * 101):
            self.fail_sql(f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','{name}',null);", "G0001")
        self.assertEqual(len(self.create(name="📚" * 100)["name"]), 100)
        self.fail_sql(f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','name',repeat('x',1001));", "G0001")
        self.fail_sql(f"select public.create_study_group(null,'{SUBJECT}','name',null);", "G0001")
        sql(f"update public.subjects set is_active=false where id='{SUBJECT}';")
        self.fail_sql(f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','name',null);", "G0006")

    def test_retry_metadata_subject_and_membership_changes(self):
        key = str(uuid.uuid4())
        g = self.create(key)
        gid = g["id"]
        value(f"select public.update_study_group('{gid}','Changed','Description');")
        sql("update public.subjects set is_active=false;")
        retried = self.create(key)
        self.assertEqual(retried["id"], gid)
        self.assertEqual(retried["name"], "Changed")
        self.fail_sql(f"select public.create_study_group('{key}','{SUBJECT}','Other',null);", "G0010")
        self.join(gid)
        value(f"select public.transfer_study_group_ownership('{gid}','{MEMBER}');")
        value(f"select public.leave_study_group('{gid}');")
        self.fail_sql(f"select public.create_study_group('{key}','{SUBJECT}','Nhóm học 📚',null);", "G0004")
        self.assertEqual(sql("select count(*) from public.study_groups;").stdout.strip(), "1")

    def test_atomic_create_and_fk_protection(self):
        sql("create function public.test_fail_member() returns trigger language plpgsql as $$ "
            "begin raise exception 'injected failure'; end $$; "
            "create trigger test_fail before insert on public.study_group_members for each row execute function public.test_fail_member();")
        try:
            self.fail_sql(f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','name',null);", "P0001")
            self.assertEqual(sql("select count(*) from public.study_groups;").stdout.strip(), "0")
        finally:
            sql("drop trigger test_fail on public.study_group_members; drop function public.test_fail_member();")
        g = self.create()
        for statement in (f"delete from public.subjects where id='{SUBJECT}';",
                          f"delete from public.profiles where id='{OWNER}';",
                          f"delete from auth.users where id='{OWNER}';",
                          f"delete from public.study_group_members where group_id='{g['id']}';"):
            self.fail_sql(statement, "23503", actor=None)
        self.fail_sql(f"update public.study_groups set creation_payload='{{}}' where id='{g['id']}';", "G0010", actor=None)

    def test_roles_projections_and_banned_accounts(self):
        gid = self.create()["id"]
        self.join(gid)
        for actor in (OUTSIDER, INVITEE):
            self.fail_sql(f"select public.get_study_group('{gid}');", "G0004", actor)
            self.fail_sql(f"select public.list_study_group_members('{gid}');", "G0004", actor)
        self.fail_sql(f"select public.get_study_group('{gid}');", "G0003", BANNED)
        self.fail_sql("select public.list_my_study_groups();", "G0002", actor="", role="authenticated")
        self.fail_sql("select public.list_my_study_groups();", "42501", actor=None, role="anon")
        for table in ("study_groups", "study_group_members", "study_group_invitations"):
            for role in ("anon", "authenticated"):
                self.fail_sql(f"select * from public.{table};", "42501", actor=None, role=role)
                self.fail_sql(f"delete from public.{table};", "42501", actor=None, role=role)
                self.fail_sql(f"insert into public.{table} default values;", "42501", actor=None, role=role)
        self.fail_sql("select studyhub_private.group_actor();", "42501")
        self.fail_sql(f"select public.get_study_group('{gid}',p_actor=>'{OWNER}');", "42883", OUTSIDER)
        self.fail_sql(f"select public.update_study_group('{gid}','bad',null);", "G0005", MEMBER)
        people = value(f"select public.list_study_group_members('{gid}');", MEMBER)["items"]
        self.assertEqual(sum(p["role"] == "owner" for p in people), 1)
        self.assertEqual(set(people[0]), {"user_id", "display_name", "avatar_url", "role", "joined_at"})
        sql(f"update public.profiles set is_banned=true where id='{OWNER}';")
        self.fail_sql(f"select public.get_study_group('{gid}');", "G0003")
        self.assertEqual(value(f"select public.get_study_group('{gid}');", MEMBER)["member_count"], 2)
        value(f"select public.leave_study_group('{gid}');", MEMBER)

    def test_pagination_and_invalid_cursors(self):
        # Same transaction gives identical timestamps, exercising UUID tie breakers.
        statements = [f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','Page {i}',null);" for i in range(55)]
        sql("begin; " + "".join(statements) + "commit;", OWNER)
        ids = []
        cursor = "null"
        while True:
            page = value(f"select public.list_my_study_groups({cursor},20);")
            ids.extend(x["id"] for x in page["items"])
            if page["next_cursor"] is None:
                break
            cursor = "'" + json.dumps(page["next_cursor"]) + "'::jsonb"
        self.assertEqual(len(ids), 55)
        self.assertEqual(len(set(ids)), 55)
        self.assertEqual(ids, sorted(ids, reverse=True))
        for cursor in ("'{}'", "'[]'", "'null'", "'42'", "'{\"at\":null,\"id\":null}'",
                       f"'{{\"at\":\"infinity\",\"id\":\"{OWNER}\"}}'",
                       f"'{{\"at\":\"bad\",\"id\":\"{OWNER}\"}}'",
                       f"'{{\"at\":\"2026-11-13T00:00:00Z\",\"id\":\"bad\"}}'"):
            self.fail_sql(f"select public.list_my_study_groups({cursor}::jsonb,20);", "G0001")
        for limit in ("0", "-1", "51", "null"):
            self.fail_sql(f"select public.list_my_study_groups(null,{limit});", "G0001")
        self.assertEqual(len(value("select public.list_my_study_groups();")["items"]), 20)

    def test_invitation_lifecycle_preview_and_reinvite(self):
        gid = self.create()["id"]
        self.friends()
        invitation = self.invite(gid)
        iid = invitation["id"]
        self.assertEqual(self.invite(gid)["id"], iid)
        self.assertEqual(value(f"select public.get_study_group('{gid}');")["member_count"], 1)
        incoming = value("select public.list_my_study_group_invitations();", INVITEE)["items"]
        self.assertEqual(len(incoming), 1)
        self.assertNotIn("invitee", incoming[0])
        self.assertEqual(set(incoming[0]["inviter"]), {"id", "display_name", "avatar_url"})
        self.fail_sql(f"select public.respond_study_group_invitation('{iid}','accept');", "G0009", OUTSIDER)
        self.fail_sql(f"select public.list_study_group_invitations('{gid}');", "G0004", INVITEE)
        value(f"select public.respond_study_group_invitation('{iid}','decline');", INVITEE)
        value(f"select public.respond_study_group_invitation('{iid}','decline');", INVITEE)
        self.fail_sql(f"select public.respond_study_group_invitation('{iid}','accept');", "G0010", INVITEE)
        newer = self.invite(gid)["id"]
        self.assertNotEqual(newer, iid)
        value(f"select public.revoke_study_group_invitation('{newer}');")
        value(f"select public.revoke_study_group_invitation('{newer}');")
        self.fail_sql(f"select public.respond_study_group_invitation('{newer}','accept');", "G0009", INVITEE)
        newest = self.invite(gid)["id"]
        sql("delete from public.friendships;")
        value(f"select public.respond_study_group_invitation('{newest}','accept');", INVITEE)
        value(f"select public.respond_study_group_invitation('{newest}','accept');", INVITEE)
        self.fail_sql(f"select public.revoke_study_group_invitation('{newest}');", "G0010")
        value(f"select public.leave_study_group('{gid}');", INVITEE)
        self.fail_sql(f"select public.respond_study_group_invitation('{newest}','accept');", "G0009", INVITEE)

    def test_member_and_invitation_pagination(self):
        gid = self.create()["id"]
        setup_rows = []
        for n in range(200, 255):
            uid = str(uuid.UUID(int=n))
            setup_rows.append(f"insert into auth.users values('{uid}') on conflict do nothing; "
                              f"insert into public.profiles(id,username,display_name) values('{uid}','page_{n}','Page {n}') on conflict do nothing; "
                              f"insert into public.study_group_members(group_id,user_id) values('{gid}','{uid}'); "
                              f"insert into public.study_group_invitations(group_id,inviter_id,invitee_id) values('{gid}','{OWNER}','{uid}');")
        sql("begin;" + "".join(setup_rows) + "commit;")
        def pages(function, prefix, actor, field):
            cursor, ids = "null", []
            while True:
                page = value(f"select public.{function}({prefix}{cursor},20);", actor)
                ids.extend(row[field] for row in page["items"])
                if page["next_cursor"] is None:
                    break
                cursor = "'" + json.dumps(page["next_cursor"]) + "'::jsonb"
            self.assertEqual(len(ids), len(set(ids)))
            return ids
        self.assertEqual(len(pages("list_study_group_members", f"'{gid}',", OWNER, "user_id")), 56)
        self.assertEqual(len(pages("list_study_group_invitations", f"'{gid}',", OWNER, "id")), 55)
        # Incoming lists span different groups, all with tied invitation timestamps.
        sql(f"begin; set local request.jwt.claim.sub='{OWNER}';" + "".join(
            f"insert into public.study_group_invitations(group_id,inviter_id,invitee_id) "
            f"select (public.create_study_group('{uuid.uuid4()}','{SUBJECT}','Incoming {n}',null)->>'id')::uuid,'{OWNER}','{INVITEE}';"
            for n in range(55)) + "commit;")
        self.assertEqual(len(pages("list_my_study_group_invitations", "", INVITEE, "id")), 55)
        for function, prefix in (("list_study_group_members", f"'{gid}',"), ("list_study_group_invitations", f"'{gid}',"), ("list_my_study_group_invitations", "")):
            self.fail_sql(f"select public.{function}({prefix}'{{}}',20);", "G0001")
            self.fail_sql(f"select public.{function}({prefix}null,51);", "G0001")

    def test_ineligible_targets_and_accept_rollback(self):
        gid = self.create()["id"]
        for target, code in ((OWNER,"G0001"), (BANNED,"G0003"), (str(uuid.uuid4()),"G0003"), (INVITEE,"G0007")):
            self.fail_sql(f"select public.invite_study_group_member('{gid}','{target}');", code)
        self.join(gid)
        self.fail_sql(f"select public.invite_study_group_member('{gid}','{MEMBER}');", "G0008")
        self.fail_sql(f"select public.invite_study_group_member('{gid}','{INVITEE}');", "G0007", MEMBER)  # any member may invite, but only own friends
        self.friends()
        iid = self.invite(gid)["id"]
        sql("create function public.test_fail_accept() returns trigger language plpgsql as $$ begin raise exception 'injected'; end $$; "
            "create trigger test_fail before insert on public.study_group_members for each row execute function public.test_fail_accept();")
        try:
            self.fail_sql(f"select public.respond_study_group_invitation('{iid}','accept');", "P0001", INVITEE)
            self.assertEqual(sql(f"select status from public.study_group_invitations where id='{iid}';").stdout.strip(), "pending")
        finally:
            sql("drop trigger test_fail on public.study_group_members; drop function public.test_fail_accept();")

    def test_transfer_remove_archive_and_historical_inviter(self):
        key = str(uuid.uuid4())
        gid = self.create(key)["id"]
        self.join(gid)
        self.friends()
        iid = self.invite(gid)["id"]
        self.fail_sql(f"select public.leave_study_group('{gid}');", "G0011")
        self.fail_sql(f"select public.remove_study_group_member('{gid}','{OWNER}');", "G0011")
        for target in (OWNER, INVITEE, BANNED, OUTSIDER):
            self.fail_sql(f"select public.transfer_study_group_ownership('{gid}','{target}');", "G0010")
        value(f"select public.transfer_study_group_ownership('{gid}','{MEMBER}');")
        self.fail_sql(f"select public.revoke_study_group_invitation('{iid}');", "G0005")
        self.assertEqual(len(value(f"select public.list_study_group_invitations('{gid}');", MEMBER)["items"]), 1)
        value(f"select public.remove_study_group_member('{gid}','{OWNER}');", MEMBER)
        self.fail_sql(f"select public.get_study_group('{gid}');", "G0004")
        value(f"select public.archive_study_group('{gid}');", MEMBER)
        self.assertEqual(sql(f"select status from public.study_group_invitations where id='{iid}';").stdout.strip(), "revoked")
        self.fail_sql(f"select public.get_study_group('{gid}');", "G0004", MEMBER)
        self.fail_sql(f"select public.respond_study_group_invitation('{iid}','accept');", "G0009", INVITEE)
        self.fail_sql(f"select public.create_study_group('{key}','{SUBJECT}','Nhóm học 📚',null);", "G0004")
        self.assertEqual(value("select public.list_my_study_groups();", MEMBER)["items"], [])

    def race(self, first, second, first_actor=OWNER, second_actor=OWNER, second_error=None):
        """Hold first's transaction open; observe second's DB lock wait before commit."""
        processes = []
        appname = "studyhub_race_" + uuid.uuid4().hex[:10]
        try:
            command = ["docker", "exec", "-i", CONTAINER, "psql", "-X", "-U", "postgres", "-d", "postgres",
                       "-v", "ON_ERROR_STOP=1", "-qAt"]
            a = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 text=True, encoding="utf-8", bufsize=1)
            processes.append(a)
            a.stdin.write(f"begin; set request.jwt.claim.sub='{first_actor}'; set role authenticated; {first}\n"
                          "\\echo FIRST_FINISHED\n")
            a.stdin.flush()
            # Read through the result; marker proves the mutation ran before B starts.
            lines = []
            while True:
                line = a.stdout.readline()
                if not line:
                    self.fail(a.stderr.read())
                if line.strip() == "FIRST_FINISHED":
                    break
                lines.append(line)
            b = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 text=True, encoding="utf-8")
            processes.append(b)
            b.stdin.write(f"\\set VERBOSITY verbose\nset application_name='{appname}'; "
                          f"set request.jwt.claim.sub='{second_actor}'; set role authenticated; {second}\n")
            b.stdin.close()
            for _ in range(50):
                wait = sql(f"select count(*) from pg_stat_activity where application_name='{appname}' and wait_event_type='Lock';").stdout.strip()
                if wait == "1":
                    break
                if b.poll() is not None:
                    self.fail("Second transaction did not wait: " + b.stdout.read() + b.stderr.read())
                time.sleep(0.02)
            else:
                self.fail("Second transaction never reached the expected lock")
            a.stdin.write("commit;\n")
            a.stdin.close()
            a.wait(timeout=10)
            b.wait(timeout=10)
            first_error = a.stderr.read()
            second_output, error = b.stdout.read(), b.stderr.read()
            self.assertEqual(a.returncode, 0, first_error)
            if second_error:
                self.assertNotEqual(b.returncode, 0)
                self.assertIn(second_error, error)
            else:
                self.assertEqual(b.returncode, 0, error)
            return "".join(lines), second_output
        finally:
            for process in processes:
                if process.poll() is None:
                    process.kill()
                for stream in (process.stdin, process.stdout, process.stderr):
                    if stream and not stream.closed:
                        stream.close()

    def test_concurrent_create_invite_accept(self):
        key = str(uuid.uuid4())
        call = f"select public.create_study_group('{key}','{SUBJECT}','Concurrent',null);"
        first, second = self.race(call, call)
        gid = json.loads(first)["id"]
        self.assertEqual(json.loads(second)["id"], gid)
        self.assertEqual(sql("select count(*) from public.study_groups;").stdout.strip(), "1")
        self.friends()
        call = f"select public.invite_study_group_member('{gid}','{INVITEE}');"
        first, second = self.race(call, call)
        iid = json.loads(first)["id"]
        self.assertEqual(json.loads(second)["id"], iid)
        self.assertEqual(sql("select count(*) from public.study_group_invitations;").stdout.strip(), "1")
        call = f"select public.respond_study_group_invitation('{iid}','accept');"
        self.race(call, call, INVITEE, INVITEE)
        self.assertEqual(value(f"select public.get_study_group('{gid}');")["member_count"], 2)

    def test_accept_revoke_both_commit_orders(self):
        for accept_first in (True, False):
            self.setUp()
            gid = self.create()["id"]
            self.friends()
            iid = self.invite(gid)["id"]
            accept = f"select public.respond_study_group_invitation('{iid}','accept');"
            revoke = f"select public.revoke_study_group_invitation('{iid}');"
            if accept_first:
                self.race(accept, revoke, INVITEE, OWNER, "G0010")
            else:
                self.race(revoke, accept, OWNER, INVITEE, "G0009")
            self.assertEqual(sql(f"select status from public.study_group_invitations where id='{iid}';").stdout.strip(),
                             "accepted" if accept_first else "revoked")
            self.assertEqual(value(f"select public.get_study_group('{gid}');")["member_count"], 2 if accept_first else 1)

    def test_accept_archive_both_commit_orders(self):
        for accept_first in (True, False):
            self.setUp()
            gid = self.create()["id"]
            self.friends()
            iid = self.invite(gid)["id"]
            accept = f"select public.respond_study_group_invitation('{iid}','accept');"
            archive = f"select public.archive_study_group('{gid}');"
            if accept_first:
                self.race(accept, archive, INVITEE, OWNER)
            else:
                self.race(archive, accept, OWNER, INVITEE, "G0009")
            self.fail_sql(f"select public.get_study_group('{gid}');", "G0004", INVITEE)
            self.assertEqual(sql("select count(*) from public.study_groups where archived_at is not null;").stdout.strip(), "1")

    def test_transfer_leave_remove_both_commit_orders(self):
        for remove in (True, False):
            for transfer_first in (True, False):
                self.setUp()
                gid = self.create()["id"]
                self.join(gid)
                transfer = f"select public.transfer_study_group_ownership('{gid}','{MEMBER}');"
                departure = (f"select public.remove_study_group_member('{gid}','{MEMBER}');" if remove
                             else f"select public.leave_study_group('{gid}');")
                departing_actor = OWNER if remove else MEMBER
                if transfer_first:
                    self.race(transfer, departure, OWNER, departing_actor, "G0005" if remove else "G0011")
                else:
                    self.race(departure, transfer, departing_actor, OWNER, "G0010")
                self.assertEqual(sql("select count(*) from public.study_groups g where not exists "
                                     "(select 1 from public.study_group_members m where m.group_id=g.id and m.user_id=g.owner_id);").stdout.strip(), "0")
                self.assertEqual(sql(f"select owner_id from public.study_groups where id='{gid}';").stdout.strip(), MEMBER if transfer_first else OWNER)

    def test_invite_unfriend_both_commit_orders(self):
        for invite_first in (True, False):
            self.setUp()
            gid = self.create()["id"]
            self.friends()
            fid = sql("select id from public.friendships;").stdout.strip()
            invite = f"select public.invite_study_group_member('{gid}','{INVITEE}');"
            unfriend = f"select public.remove_friendship('{fid}');"
            if invite_first:
                self.race(invite, unfriend)
            else:
                self.race(unfriend, invite, second_error="G0007")
            self.assertEqual(sql("select count(*) from public.friendships;").stdout.strip(), "0")
            self.assertEqual(sql("select count(*) from public.study_group_invitations;").stdout.strip(), "1" if invite_first else "0")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-fixtures", action="store_true", help="Refresh Kotlin fixtures from actual local RPC results")
    args = parser.parse_args()
    try:
        setup()
        if args.write_fixtures:
            write_payload_fixtures()
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(GroupsTest)
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        raise SystemExit(0 if result.wasSuccessful() else 1)
    finally:
        # Only remove the exact random container created here, with our test label.
        info = docker("inspect", "--format", '{{index .Config.Labels "studyhub.test"}}', CONTAINER, check=False)
        if info.returncode == 0 and info.stdout.strip() == "study-groups":
            docker("rm", "-f", CONTAINER)
