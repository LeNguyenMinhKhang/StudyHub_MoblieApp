"""Disposable PostgreSQL tests for study group chat, member invitations and optional subject.

No connection URL or existing container is accepted. See supabase/tests/study_groups/README.md.
The chat tables come from supabase/schema.sql; the chat/RLS functions come from the real migrations
that preceded the one under test, so regressions of direct chat are visible.
"""
from pathlib import Path
import json
import re
import subprocess
import sys
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[3]
IMAGE = "postgres:17-alpine"
LABEL = "studyhub.test=study-group-chat"
CONTAINER = "studyhub-groupchat-test-" + uuid.uuid4().hex[:12]
OWNER, MEMBER, INVITEE, OUTSIDER, BANNED, OTHER = [str(uuid.UUID(int=i)) for i in range(1, 7)]
SUBJECT = str(uuid.UUID(int=101))


def docker(*args, data=None, check=True):
    result = subprocess.run(["docker", *args], input=data, text=True, encoding="utf-8", capture_output=True)
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


def scalar(statement, actor=None):
    return sql(statement, actor).stdout.strip().splitlines()[-1]


def migration(name):
    return (ROOT / "supabase/migrations" / name).read_text(encoding="utf-8-sig")


def setup():
    docker("run", "-d", "--name", CONTAINER, "--label", LABEL, "--network", "none",
           "-e", "POSTGRES_HOST_AUTH_METHOD=trust", IMAGE)
    for _ in range(300):
        logs = docker("logs", CONTAINER, check=False)
        if "init process complete" in logs.stdout + logs.stderr and \
                docker("exec", CONTAINER, "pg_isready", "-U", "postgres", check=False).returncode == 0:
            break
        time.sleep(0.1)
    else:
        raise RuntimeError("Disposable PostgreSQL did not start")
    schema = (ROOT / "supabase/schema.sql").read_text(encoding="utf-8-sig")
    baseline = """
        create role anon; create role authenticated;
        create schema auth; create table auth.users(id uuid primary key);
        create function auth.uid() returns uuid language sql stable as
        $$ select nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
        grant usage on schema auth to authenticated, anon;
        grant execute on function auth.uid() to authenticated, anon;
        grant usage on schema public to authenticated, anon;
    """
    for table in ("profiles", "subjects", "friendships", "blocks", "conversations", "conversation_members", "messages"):
        match = re.search(r"create table if not exists public\." + table + r" \([\s\S]*?\n\);", schema)
        if not match:
            raise RuntimeError(f"Missing baseline definition: {table}")
        baseline += match.group(0)
    baseline += """
        alter table public.conversations enable row level security;
        alter table public.conversation_members enable row level security;
        alter table public.messages enable row level security;
        grant select, insert, update, delete on public.conversations, public.conversation_members, public.messages to authenticated;
        create schema if not exists studyhub_private;
        -- is_conversation_member exactly as the previous migration defined it.
        create function public.is_conversation_member(p_conversation_id uuid, p_user_id uuid)
        returns boolean language sql security definer set search_path = 'public','auth','pg_temp' stable as
        $$ select exists (select 1 from public.conversation_members where conversation_id = p_conversation_id and user_id = p_user_id); $$;
    """
    sql(baseline)
    sql(migration("20261027_friendship_rpc.sql"))
    sql(migration("20261113100000_study_groups.sql"))
    sql(migration("20261108130000_block_direct_chat.sql"))
    sql(migration("20261116_chat_status_and_documents.sql"))
    sql(migration("20261125100000_study_group_chat.sql"))
    for index, actor in enumerate((OWNER, MEMBER, INVITEE, OUTSIDER, BANNED, OTHER)):
        sql(f"insert into auth.users values('{actor}'); insert into public.profiles(id,username,display_name,is_banned) "
            f"values('{actor}','test_{index}','Test {index}',{str(actor == BANNED).lower()});")
    sql(f"insert into public.subjects(id,name) values('{SUBJECT}','Test subject');")


def write_payload_fixtures():
    """Real RPC results for the Kotlin decoding tests (backend/src/test/resources/group_chat)."""
    group = value(f"select public.create_study_group('{uuid.uuid4()}',null,'Nhóm chat 💬',null);")
    conversation = value(f"select public.get_or_create_study_group_conversation('{group['id']}');")
    message = value(f"select public.send_chat_message('{conversation['conversation_id']}','Xin chào 👋');")
    payloads = {
        "conversation": conversation,
        "message": message,
        "conversations": value("select coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb) from public.list_my_conversations() t;"),
    }
    target = ROOT / "backend/src/test/resources/group_chat/rpc_payloads.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payloads, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")


class ChatTest(unittest.TestCase):
    def setUp(self):
        sql("begin; delete from public.messages; delete from public.conversation_members; delete from public.conversations; "
            "delete from public.study_group_invitations; delete from public.study_group_members; "
            "delete from public.study_groups; delete from public.friendships; delete from public.blocks; "
            "update public.subjects set is_active=true; "
            f"update public.profiles set is_banned=(id='{BANNED}'); commit;")

    def fail_sql(self, statement, code, actor=OWNER):
        result = sql(statement, actor, check=False)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(code, result.stderr)

    def create(self, subject=SUBJECT, actor=OWNER, key=None):
        subject_sql = f"'{subject}'" if subject else "null"
        return value(f"select public.create_study_group('{key or uuid.uuid4()}',{subject_sql},'Nhóm chat',null);", actor)

    def friends(self, a, b):
        sql(f"insert into public.friendships(requester_id,addressee_id,status) values('{a}','{b}','accepted');")

    def join(self, gid, target=MEMBER, via=OWNER):
        self.friends(via, target)
        invitation = value(f"select public.invite_study_group_member('{gid}','{target}');", via)
        value(f"select public.respond_study_group_invitation('{invitation['id']}','accept');", target)

    def conversation(self, gid, actor=OWNER):
        return value(f"select public.get_or_create_study_group_conversation('{gid}');", actor)

    def send(self, cid, text, actor=OWNER):
        return value(f"select public.send_chat_message('{cid}','{text}');", actor)

    def visible(self, cid, actor):
        return int(scalar(f"select count(*) from public.messages where conversation_id='{cid}';", actor))

    def conversations(self, actor):
        return value("select coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb) from public.list_my_conversations() t;", actor)

    # --- conversation lifecycle -------------------------------------------------
    def test_get_or_create_is_idempotent_and_members_only(self):
        gid = self.create()["id"]
        first = self.conversation(gid)
        self.assertEqual(first["group_id"], gid)
        self.assertEqual(first["member_count"], 1)
        self.assertEqual(self.conversation(gid)["conversation_id"], first["conversation_id"])
        self.assertEqual(scalar(f"select count(*) from public.conversations where study_group_id='{gid}';"), "1")
        self.fail_sql(f"select public.get_or_create_study_group_conversation('{gid}');", "G0004", OUTSIDER)
        sql(f"select public.archive_study_group('{gid}');", OWNER)
        self.fail_sql(f"select public.get_or_create_study_group_conversation('{gid}');", "G0004", OWNER)

    def test_concurrent_first_requests_share_one_conversation(self):
        gid = self.create()["id"]
        self.join(gid)
        call = f"set request.jwt.claim.sub='%s'; set role authenticated; select public.get_or_create_study_group_conversation('{gid}');"
        procs = [subprocess.Popen(["docker", "exec", "-i", CONTAINER, "psql", "-X", "-U", "postgres", "-d", "postgres", "-qAt"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                 for _ in range(2)]
        for proc, actor in zip(procs, (OWNER, MEMBER)):
            proc.stdin.write(call % actor)
            proc.stdin.close()
        for proc in procs:
            proc.wait(timeout=60)
            err = proc.stderr.read()
            proc.stdout.close(); proc.stderr.close()
            self.assertEqual(proc.returncode, 0, err)
        self.assertEqual(scalar(f"select count(*) from public.conversations where study_group_id='{gid}';"), "1")

    # --- authorization ---------------------------------------------------------
    def test_membership_authorizes_read_and_send(self):
        gid = self.create()["id"]
        cid = self.conversation(gid)["conversation_id"]
        self.send(cid, "xin chào")
        self.join(gid)                       # joined later: still sees history
        self.assertEqual(self.visible(cid, MEMBER), 1)
        self.send(cid, "chào bạn", MEMBER)
        self.assertEqual(self.visible(cid, OWNER), 2)
        self.assertEqual(self.visible(cid, OUTSIDER), 0)
        self.fail_sql(f"select public.send_chat_message('{cid}','spam');", "42501", OUTSIDER)
        self.fail_sql(f"insert into public.messages(conversation_id,sender_id,content) values('{cid}','{OUTSIDER}','x');",
                      "row-level security", OUTSIDER)
        self.fail_sql(f"select public.send_chat_message('{cid}','   ');", "22023", OWNER)

    def test_leaving_removal_ban_and_archive_revoke_access(self):
        gid = self.create()["id"]
        cid = self.conversation(gid)["conversation_id"]
        self.join(gid)
        self.send(cid, "one", MEMBER)
        sql(f"select public.leave_study_group('{gid}');", MEMBER)
        self.assertEqual(self.visible(cid, MEMBER), 0)
        self.fail_sql(f"select public.send_chat_message('{cid}','x');", "42501", MEMBER)
        self.assertEqual(scalar(f"select count(*) from public.conversation_members where conversation_id='{cid}' and user_id='{MEMBER}';"), "0")

        self.join(gid, INVITEE)
        sql(f"select public.remove_study_group_member('{gid}','{INVITEE}');", OWNER)
        self.fail_sql(f"select public.send_chat_message('{cid}','x');", "42501", INVITEE)

        self.join(gid, OTHER)
        sql(f"update public.profiles set is_banned=true where id='{OTHER}';")
        self.fail_sql(f"select public.send_chat_message('{cid}','x');", "42501", OTHER)

        sql(f"select public.archive_study_group('{gid}');", OWNER)
        self.assertEqual(self.visible(cid, OWNER), 0)
        self.fail_sql(f"select public.send_chat_message('{cid}','x');", "42501", OWNER)
        self.assertEqual(self.conversations(OWNER), [])

    def test_creator_loses_conversation_row_after_leaving(self):
        gid = self.create()["id"]
        self.join(gid)
        cid = self.conversation(gid, MEMBER)["conversation_id"]   # MEMBER is created_by
        sql(f"select public.leave_study_group('{gid}');", MEMBER)
        self.assertEqual(scalar(f"select count(*) from public.conversations where id='{cid}';", MEMBER), "0")

    # --- conversation list and read state --------------------------------------
    def test_list_unread_and_read_state(self):
        gid = self.create()["id"]
        cid = self.conversation(gid)["conversation_id"]
        self.join(gid)
        self.assertEqual(self.conversations(MEMBER), [])           # no message yet
        self.send(cid, "hello")
        rows = self.conversations(MEMBER)
        self.assertEqual(len(rows), 1)
        self.assertEqual((rows[0]["study_group_id"], rows[0]["title"], rows[0]["type"]), (gid, "Nhóm chat", "group"))
        self.assertEqual(rows[0]["unread_count"], 1)
        self.assertEqual(self.conversations(OWNER)[0]["unread_count"], 0)
        sql(f"select public.mark_conversation_read('{cid}');", MEMBER)
        self.assertEqual(self.conversations(MEMBER)[0]["unread_count"], 0)
        # group messages never show read receipts
        self.assertEqual(scalar("select status from public.messages;"), "sent")
        sql(f"select public.leave_study_group('{gid}');", MEMBER)
        self.assertEqual(self.conversations(MEMBER), [])
        self.assertEqual(self.conversations(OUTSIDER), [])

    def test_group_rename_is_reflected_in_list(self):
        gid = self.create()["id"]
        cid = self.conversation(gid)["conversation_id"]
        self.send(cid, "hello")
        sql(f"select public.update_study_group('{gid}','Tên mới',null);", OWNER)
        self.assertEqual(self.conversations(OWNER)[0]["title"], "Tên mới")

    # --- direct chat regression ------------------------------------------------
    def test_direct_chat_behaviour_is_preserved(self):
        self.friends(OWNER, INVITEE)
        direct = value(f"select public.get_or_create_direct_conversation('{INVITEE}');", OWNER)
        cid = direct["conversation_id"]
        self.send(cid, "hi")
        self.assertEqual(scalar("select status from public.messages;"), "sent")
        sql(f"select public.mark_conversation_read('{cid}');", INVITEE)
        self.assertEqual(scalar("select status from public.messages;"), "read")
        rows = self.conversations(OWNER)
        self.assertEqual((len(rows), rows[0]["type"], rows[0]["study_group_id"]), (1, "direct", None))
        self.assertEqual(self.visible(cid, OUTSIDER), 0)
        sql(f"insert into public.blocks(blocker_id,blocked_id) values('{INVITEE}','{OWNER}');")
        self.fail_sql(f"select public.send_chat_message('{cid}','blocked?');", "42501", OWNER)
        self.assertEqual(self.conversations(OWNER), [])

    # --- invitations by any member ---------------------------------------------
    def test_any_member_invites_own_friends(self):
        gid = self.create()["id"]
        self.join(gid)
        self.friends(MEMBER, INVITEE)
        invitation = value(f"select public.invite_study_group_member('{gid}','{INVITEE}');", MEMBER)
        self.assertEqual(invitation["status"], "pending")
        self.assertEqual(sql(f"select inviter_id from public.study_group_invitations where id='{invitation['id']}';").stdout.strip(), MEMBER)
        self.fail_sql(f"select public.invite_study_group_member('{gid}','{OTHER}');", "G0007", MEMBER)      # not friends
        self.fail_sql(f"select public.invite_study_group_member('{gid}','{OWNER}');", "G0008", MEMBER)       # already member
        self.fail_sql(f"select public.invite_study_group_member('{gid}','{INVITEE}');", "G0004", OUTSIDER)   # outsider
        value(f"select public.respond_study_group_invitation('{invitation['id']}','accept');", INVITEE)
        self.assertEqual(value(f"select public.get_study_group('{gid}');", INVITEE)["role"], "member")
        cid = self.conversation(gid, INVITEE)["conversation_id"]
        self.assertTrue(cid)

    def test_duplicate_invitation_from_second_member_returns_existing(self):
        gid = self.create()["id"]
        self.join(gid)
        self.friends(OWNER, INVITEE)
        self.friends(MEMBER, INVITEE)
        first = value(f"select public.invite_study_group_member('{gid}','{INVITEE}');", OWNER)
        second = value(f"select public.invite_study_group_member('{gid}','{INVITEE}');", MEMBER)
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(scalar(f"select count(*) from public.study_group_invitations where invitee_id='{INVITEE}';"), "1")

    def test_management_remains_owner_only(self):
        gid = self.create()["id"]
        self.join(gid)
        self.join(gid, INVITEE)
        self.fail_sql(f"select public.remove_study_group_member('{gid}','{INVITEE}');", "G0005", MEMBER)
        self.fail_sql(f"select public.list_study_group_invitations('{gid}');", "G0005", MEMBER)
        self.fail_sql(f"select public.archive_study_group('{gid}');", "G0005", MEMBER)
        self.fail_sql(f"select public.transfer_study_group_ownership('{gid}','{INVITEE}');", "G0005", MEMBER)
        self.friends(MEMBER, OTHER)
        invitation = value(f"select public.invite_study_group_member('{gid}','{OTHER}');", MEMBER)
        self.fail_sql(f"select public.revoke_study_group_invitation('{invitation['id']}');", "G0005", MEMBER)
        revoked = value(f"select public.revoke_study_group_invitation('{invitation['id']}');", OWNER)
        self.assertEqual(revoked["status"], "revoked")

    # --- optional subject ------------------------------------------------------
    def test_subject_is_optional(self):
        key = str(uuid.uuid4())
        group = self.create(subject=None, key=key)
        self.assertIsNone(group["subject"])
        self.assertEqual(self.create(subject=None, key=key)["id"], group["id"])        # idempotent retry
        self.fail_sql(f"select public.create_study_group('{key}','{SUBJECT}','Nhóm chat',null);", "G0010")
        self.assertEqual(value("select public.list_my_study_groups();")["items"][0]["subject"], None)
        with_subject = self.create()
        self.assertEqual(with_subject["subject"]["id"], SUBJECT)
        sql("update public.subjects set is_active=false;")
        self.fail_sql(f"select public.create_study_group('{uuid.uuid4()}','{SUBJECT}','x',null);", "G0006")
        self.assertIsNone(self.create(subject=None)["subject"])                          # inactive subjects irrelevant
        self.fail_sql(f"select public.create_study_group('{uuid.uuid4()}','{uuid.uuid4()}','x',null);", "G0006")

    def test_invitation_preview_without_subject(self):
        gid = self.create(subject=None)["id"]
        self.friends(OWNER, INVITEE)
        value(f"select public.invite_study_group_member('{gid}','{INVITEE}');")
        incoming = value("select public.list_my_study_group_invitations();", INVITEE)["items"]
        self.assertEqual(len(incoming), 1)
        self.assertIsNone(incoming[0]["subject"])
        self.assertEqual(incoming[0]["group_name"], "Nhóm chat")

    def test_existing_group_keeps_subject(self):
        gid = self.create()["id"]
        self.assertEqual(value(f"select public.get_study_group('{gid}');")["subject"]["name"], "Test subject")


if __name__ == "__main__":
    try:
        setup()
        if "--write-fixtures" in sys.argv:
            sys.argv.remove("--write-fixtures")
            write_payload_fixtures()
            sql("begin; delete from public.messages; delete from public.conversation_members; delete from public.conversations; "
                "delete from public.study_group_invitations; delete from public.study_group_members; delete from public.study_groups; commit;")
        unittest.main(verbosity=2)
    finally:
        docker("rm", "-f", CONTAINER, check=False)
