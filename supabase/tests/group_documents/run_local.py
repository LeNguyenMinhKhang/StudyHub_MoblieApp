"""Disposable PostgreSQL tests for group documents/annotations. No DB URL is accepted.

Reuses the study-groups runner (fresh labelled postgres:17-alpine container, no network),
then adds minimal local stand-ins for Supabase `storage.objects`, `realtime.messages`,
`realtime.send()`/`realtime.topic()` and the `service_role` role. Those stand-ins verify
our policies and RPC contracts on PostgreSQL only; hosted Storage/Realtime are not tested.
"""
from pathlib import Path
import json
import subprocess
import sys
import unittest
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "study_groups"))
import run_local as base  # noqa: E402

OWNER, MEMBER, INVITEE, OUTSIDER, BANNED = base.OWNER, base.MEMBER, base.INVITEE, base.OUTSIDER, base.BANNED
sql, value = base.sql, base.value
HASH = "a" * 64
PAGES = json.dumps([{"w": 612, "h": 792}, {"w": 842, "h": 595}])

STUBS = """
create role service_role;
grant usage on schema public to service_role;
create schema storage;
create table storage.buckets(id text primary key, name text, public boolean, file_size_limit bigint, allowed_mime_types text[]);
create table storage.objects(id uuid primary key default gen_random_uuid(), bucket_id text references storage.buckets(id),
    name text not null, owner uuid default auth.uid(), created_at timestamptz default now(), unique(bucket_id, name));
alter table storage.objects enable row level security;
grant usage on schema storage to authenticated, anon;
grant select, insert, update, delete on storage.objects to authenticated, anon;
create schema realtime;
create table realtime.messages(id bigserial primary key, topic text not null, extension text not null, payload jsonb,
    event text, private boolean, inserted_at timestamptz default now());
alter table realtime.messages enable row level security;
grant usage on schema realtime to authenticated, anon;
grant select, insert on realtime.messages to authenticated;
grant usage on sequence realtime.messages_id_seq to authenticated;
create function realtime.topic() returns text language sql stable as $$ select nullif(current_setting('realtime.topic', true), '') $$;
grant execute on function realtime.topic() to authenticated, anon;
create function realtime.send(payload jsonb, event text, topic text, private boolean default true) returns void
language sql security definer as $$ insert into realtime.messages(topic, extension, payload, event, private)
    values (topic, 'broadcast', payload, event, private) $$;
revoke all on function realtime.send(jsonb, text, text, boolean) from public;
"""


def setup():
    base.setup()
    sql(STUBS)
    sql((base.ROOT / "supabase/migrations/20261117090000_group_documents.sql").read_text(encoding="utf-8-sig"))


def service(statement):
    return json.loads(sql("set role service_role; " + statement).stdout.strip().splitlines()[-1])


def clear_documents():
    sql("begin; set session_replication_role = replica; "
        "delete from public.group_document_operations; delete from public.group_document_events; "
        "delete from public.group_document_snapshots; delete from public.group_document_annotations; "
        "delete from public.group_document_uploads; "
        "delete from public.group_document_versions; delete from public.group_documents; "
        "delete from public.group_document_channels; "
        "delete from storage.objects; delete from realtime.messages; "
        "set session_replication_role = origin; commit;")


class DocumentsTest(unittest.TestCase):
    # Group fixtures from the study-groups runner (they call each other through self).
    create, friends, invite, join = (base.GroupsTest.create, base.GroupsTest.friends,
                                     base.GroupsTest.invite, base.GroupsTest.join)
    fail_sql, race = base.GroupsTest.fail_sql, base.GroupsTest.race

    def setUp(self):
        clear_documents()
        base.GroupsTest.setUp(self)
        self.gid = self.create()["id"]
        self.join(self.gid, MEMBER)

    # --- helpers -------------------------------------------------------------------
    def reserve(self, actor=OWNER, title="Giáo trình 📘", document=None, request=None, size=1000):
        doc = f"'{document}'" if document else "null"
        title_sql = f"'{title}'" if title is not None else "null"
        return value(f"select public.reserve_group_document_upload('{request or uuid.uuid4()}','{self.gid}',"
                     f"{title_sql},{size},{doc});", actor)

    def put_object(self, key, actor=OWNER):
        return sql(f"insert into storage.objects(bucket_id,name) values('group-documents','{key}');", actor, check=False)

    def finalize(self, upload, actor=OWNER, pages=PAGES):
        return service(f"select public.service_finalize_group_document_upload('{upload}','{actor}','{HASH}',1000,'{pages}');")

    def upload(self, actor=OWNER, document=None, title="Giáo trình 📘"):
        u = self.reserve(actor, None if document else title, document)
        self.assertEqual(self.put_object(u["object_key"], actor).returncode, 0)
        service(f"select public.service_prepare_group_document_validation('{u['id']}','{actor}');")
        return self.finalize(u["id"], actor)

    def mutate(self, version, action="create", annotation=None, expected=None, page=0, kind="ink", data=None,
               actor=OWNER, op=None):
        data = data if data is not None else {"points": [[0.1, 0.1], [0.5, 0.5]], "color": "#DC2626", "width": 0.004}
        args = [f"'{op or uuid.uuid4()}'", f"'{version}'", f"'{annotation or uuid.uuid4()}'", f"'{action}'",
                "null" if expected is None else str(expected),
                "null" if action not in ("create",) and page == 0 else ("null" if page is None else str(page)),
                "null" if action != "create" else f"'{kind}'",
                "null" if action in ("delete", "restore") else "'" + json.dumps(data, ensure_ascii=False).replace("'", "''") + "'::jsonb"]
        return f"select public.mutate_group_document_annotation({','.join(args)});"

    def visible(self, key, actor):
        return sql(f"select count(*) from storage.objects where bucket_id='group-documents' and name='{key}';",
                   actor).stdout.strip().splitlines()[-1]

    # --- 9.2/9.3/11.1 uploads, storage and library ---------------------------------
    def test_upload_finalize_and_private_storage(self):
        u = self.reserve(MEMBER)
        self.assertEqual(u["status"], "pending")
        self.assertTrue(u["object_key"].startswith(self.gid + "/"))
        # Object key is reserved for the reserving member only; no arbitrary or foreign keys.
        for actor in (OWNER, OUTSIDER, BANNED, INVITEE):
            self.assertNotEqual(self.put_object(u["object_key"], actor).returncode, 0)
        self.assertNotEqual(self.put_object(f"{self.gid}/{uuid.uuid4()}.pdf", MEMBER).returncode, 0)
        self.assertEqual(self.put_object(u["object_key"], MEMBER).returncode, 0)
        self.assertEqual(self.visible(u["object_key"], MEMBER), "0")  # pending objects are unreadable
        ready = self.finalize(u["id"], MEMBER)
        self.assertEqual(ready["status"], "ready")
        self.assertEqual(self.finalize(u["id"], MEMBER)["version_id"], ready["version_id"])  # retry: no duplicate
        self.assertEqual(sql("select count(*) from public.group_document_versions;").stdout.strip(), "1")
        docs = value(f"select public.list_group_documents('{self.gid}');")["items"]
        self.assertEqual([d["title"] for d in docs], ["Giáo trình 📘"])
        self.assertEqual(docs[0]["can_manage"], True)  # owner manages every document
        self.assertEqual(value(f"select public.get_group_document('{docs[0]['id']}');", MEMBER)["can_manage"], True)
        version = value(f"select public.get_group_document_version('{ready['version_id']}');", MEMBER)
        self.assertEqual((version["page_count"], version["pages"][1], version["content_hash"]), (2, {"w": 842, "h": 595}, HASH))
        self.assertEqual(set(docs[0]) - {"id", "group_id", "title", "uploaded_by", "can_manage", "current_version",
                                         "created_at", "updated_at"}, set())
        for actor, expected in ((OWNER, "1"), (MEMBER, "1"), (OUTSIDER, "0"), (INVITEE, "0"), (BANNED, "0")):
            self.assertEqual(self.visible(u["object_key"], actor), expected, actor)
        # No overwrite or deletion by clients; anon sees nothing.
        sql(f"update storage.objects set name = name || 'x' where name='{u['object_key']}';", OWNER)
        sql(f"delete from storage.objects where name='{u['object_key']}';", OWNER)
        self.assertEqual(sql("select count(*) from storage.objects;").stdout.strip(), "1")
        self.assertNotEqual(self.put_object(u["object_key"], MEMBER).returncode, 0)
        for actor in (OUTSIDER, INVITEE, BANNED):
            for statement in (f"select public.get_group_document('{docs[0]['id']}');",
                              f"select public.get_group_document_version('{ready['version_id']}');",
                              f"select public.create_group_document_snapshot('{ready['version_id']}');",
                              self.mutate(ready["version_id"], actor=actor)):
                self.fail_sql(statement, "G0003" if actor == BANNED else "D0001", actor)
            self.fail_sql(f"select public.list_group_documents('{self.gid}');", "G0003" if actor == BANNED else "G0004", actor)

    def test_reservation_idempotency_validation_and_limits(self):
        key = str(uuid.uuid4())
        first = self.reserve(request=key)
        self.assertEqual(self.reserve(request=key)["id"], first["id"])
        self.fail_sql(f"select public.reserve_group_document_upload('{key}','{self.gid}','Other',1000,null);", "G0010")
        for title, size in (("", 10), ("x" * 201, 10), ("ok", 0), ("ok", 20 * 1024 * 1024 + 1)):
            self.fail_sql(f"select public.reserve_group_document_upload('{uuid.uuid4()}','{self.gid}','{title}',{size},null);", "G0001")
        self.assertEqual(self.reserve(title="📘" * 200)["status"], "pending")
        for _ in range(3):
            self.reserve()
        self.fail_sql(f"select public.reserve_group_document_upload('{uuid.uuid4()}','{self.gid}','x',10,null);", "G0010")
        self.fail_sql(f"select public.reserve_group_document_upload('{uuid.uuid4()}','{self.gid}','x',10,null);", "G0004", OUTSIDER)
        bad_pages = (json.dumps([]), json.dumps([{"w": 1, "h": 2}]), json.dumps([{"w": 600, "h": 800, "x": 1}]),
                     json.dumps([{"w": 600, "h": 800}] * 101))
        for pages in bad_pages:
            with self.subTest(pages=pages[:30]):
                result = sql(f"set role service_role; select public.service_finalize_group_document_upload("
                             f"'{first['id']}','{OWNER}','{HASH}',1000,'{pages}');", check=False)
                self.assertIn("G0001", result.stderr)
        # Ready retry after the response was lost returns the same version.
        self.put_object(first["object_key"])
        ready = self.finalize(first["id"])
        again = self.reserve(request=key)
        self.assertEqual((again["status"], again["version_id"]), ("ready", ready["version_id"]))

    def test_failed_expired_revoked_uploads_and_cleanup(self):
        failed = self.reserve(MEMBER)
        self.put_object(failed["object_key"], MEMBER)
        result = service(f"select public.service_fail_group_document_upload('{failed['id']}','{MEMBER}','encrypted');")
        self.assertEqual((result["status"], result["failure_reason"]), ("failed", "encrypted"))
        res = sql(f"set role service_role; select public.service_finalize_group_document_upload('{failed['id']}','{MEMBER}','{HASH}',1,'{PAGES}');", check=False)
        self.assertIn("D0005", res.stderr)
        expired = self.reserve(MEMBER)
        sql(f"update public.group_document_uploads set expires_at = now() - interval '1 second' where id='{expired['id']}';")
        self.assertNotEqual(self.put_object(expired["object_key"], MEMBER).returncode, 0)
        self.assertEqual(value(f"select public.get_group_document_upload('{expired['id']}');", MEMBER)["status"], "expired")
        res = sql(f"set role service_role; select public.service_prepare_group_document_validation('{expired['id']}','{MEMBER}');", check=False)
        self.assertIn("D0005", res.stderr)
        # Uploader loses membership before finalization: no ready document is exposed.
        revoked = self.reserve(MEMBER)
        self.put_object(revoked["object_key"], MEMBER)
        value(f"select public.remove_study_group_member('{self.gid}','{MEMBER}');")
        res = sql(f"set role service_role; select public.service_finalize_group_document_upload('{revoked['id']}','{MEMBER}','{HASH}',1,'{PAGES}');", check=False)
        self.assertIn("D0005", res.stderr)
        self.assertEqual(value(f"select public.list_group_documents('{self.gid}');")["items"], [])
        claimed = sql("set role service_role; select upload_id from public.service_claim_group_document_cleanup(10);").stdout.split()
        self.assertEqual(set(claimed), {failed["id"], expired["id"]})
        ids = "array['" + "','".join(claimed) + "']::uuid[]"
        self.assertEqual(sql(f"set role service_role; select public.service_confirm_group_document_cleanup({ids});").stdout.strip(), "2")
        self.assertEqual(sql("set role service_role; select count(*) from public.service_claim_group_document_cleanup(10);").stdout.strip(), "0")

    def test_grants_and_direct_table_access(self):
        tables = ("group_documents", "group_document_uploads", "group_document_versions", "group_document_annotations",
                  "group_document_events", "group_document_operations", "group_document_snapshots", "group_document_channels")
        for table in tables:
            for role in ("anon", "authenticated", "service_role"):
                self.fail_sql(f"select * from public.{table};", "42501", actor=None, role=role)
                self.fail_sql(f"delete from public.{table};", "42501", actor=None, role=role)
        for fn in ("service_finalize_group_document_upload(null,null,null,null,null)",
                   "service_prepare_group_document_validation(null,null)",
                   "service_fail_group_document_upload(null,null,null)", "service_claim_group_document_cleanup(1)",
                   "service_confirm_group_document_cleanup(null)"):
            self.fail_sql(f"select public.{fn};", "42501", MEMBER)
            self.fail_sql(f"select public.{fn};", "42501", actor=None, role="anon")
        self.fail_sql(f"select public.list_group_documents('{self.gid}');", "42501", actor=None, role="anon")
        self.fail_sql("select studyhub_private.document_publish(null, null);", "42501")
        self.fail_sql(f"select public.mutate_group_document_annotation('{uuid.uuid4()}','{uuid.uuid4()}','{uuid.uuid4()}','create',p_actor=>'{OWNER}');", "42883")

    def test_versions_management_and_archive(self):
        first = self.upload(MEMBER)
        doc = first["document_id"]
        # Only uploader or current owner may add versions/archive; others are forbidden.
        self.join(self.gid, INVITEE)
        self.fail_sql(f"select public.reserve_group_document_upload('{uuid.uuid4()}','{self.gid}',null,10,'{doc}');", "G0005", INVITEE)
        self.fail_sql(f"select public.archive_group_document('{doc}');", "G0005", INVITEE)
        self.fail_sql(f"select public.reserve_group_document_upload('{uuid.uuid4()}','{self.gid}','title',10,'{doc}');", "G0001", MEMBER)
        v1 = first["version_id"]
        value(self.mutate(v1), MEMBER)
        v2 = self.upload(OWNER, document=doc)["version_id"]
        versions = value(f"select public.list_group_document_versions('{doc}');", INVITEE)["items"]
        self.assertEqual([(v["version_number"], v["is_current"]) for v in versions], [(2, True), (1, False)])
        old = value(f"select public.get_group_document_version('{v1}');", INVITEE)
        self.assertEqual((old["current_version_id"], old["revision"], old["active_annotations"]), (v2, 1, 1))
        self.assertEqual(value(f"select public.get_group_document_version('{v2}');")["active_annotations"], 0)
        self.fail_sql(f"update public.group_document_versions set content_hash='{'b' * 64}';", "G0010", actor=None)
        key = sql(f"select object_key from public.group_document_versions where id='{v1}';").stdout.strip()
        value(f"select public.archive_group_document('{doc}');", MEMBER)
        self.assertEqual(value(f"select public.list_group_documents('{self.gid}');")["items"], [])
        for statement in (f"select public.get_group_document_version('{v1}');", self.mutate(v2),
                          f"select public.create_group_document_snapshot('{v1}','export');",
                          f"select public.reserve_group_document_upload('{uuid.uuid4()}','{self.gid}',null,10,'{doc}');"):
            self.fail_sql(statement, "D0001")
        self.assertEqual(self.visible(key, OWNER), "0")
        self.assertEqual(sql("select count(*) from public.group_document_annotations;").stdout.strip(), "1")  # not deleted

    # --- 9.4/11.1 annotations ------------------------------------------------------
    def test_annotation_kinds_validation_and_limits(self):
        v = self.upload()["version_id"]
        prefix = "Ghi chú tiếng Việt 😀 "
        note_text = prefix + "đ" * (2000 - len(prefix))
        for kind, data in (("ink", None), ("highlight", {"rect": [0.1, 0.2, 0.4, 0.3], "color": "#FDE047"}),
                           ("note", {"anchor": [0.5, 0.5], "text": note_text, "color": "#93C5FD"})):
            result = value(self.mutate(v, kind=kind, data=data, page=1, actor=MEMBER), MEMBER)
            self.assertEqual((result["annotation"]["kind"], result["annotation"]["author"]["id"]), (kind, MEMBER))
        self.assertEqual(len(note_text), 2000)
        ink = {"points": [[0.1, 0.1]], "color": "#DC2626", "width": 0.004}
        invalid = [
            ("ink", {**ink, "points": [[0.1, 1.2]]}), ("ink", {**ink, "points": [[0.1, 0.1]] * 4097}),
            ("ink", {**ink, "color": "#000000"}), ("ink", {**ink, "width": 0.5}), ("ink", {**ink, "extra": 1}),
            ("ink", {**ink, "points": [[0.1]]}), ("ink", {**ink, "points": [["0.1", 0.2]]}),
            ("highlight", {"rect": [0.4, 0.2, 0.1, 0.3], "color": "#FDE047"}),
            ("highlight", {"rect": [0.1, 0.2, 0.4, 0.3], "color": "#DC2626"}),
            ("note", {"anchor": [0.5, 0.5], "text": "x" * 2001, "color": "#FDE047"}),
            ("note", {"anchor": [0.5, 0.5], "text": "   ", "color": "#FDE047"}),
            ("note", {"anchor": [0.5, 0.5], "text": " padded", "color": "#FDE047"}),
            ("stamp", {"anything": 1}),
            ("ink", {**ink, "points": [[0.123456789012, 0.123456789012]] * 4000}),  # > 64 KiB payload
        ]
        for kind, data in invalid:
            with self.subTest(kind=kind, keys=list(data)):
                self.fail_sql(self.mutate(v, kind=kind, data=data), "G0001")
        self.fail_sql(self.mutate(v, page=2), "G0001")
        self.fail_sql(self.mutate(v, page=-1), "G0001")
        self.assertEqual(value(self.mutate(v, data={**ink, "points": [[0.5, 0.5]] * 4096})).get("revision"), 4)
        sql(f"update public.group_document_versions set active_annotations = 10000 where id='{v}';")
        self.fail_sql(self.mutate(v), "D0004")

    def test_permissions_cas_tombstones_undo_and_op_ledger(self):
        v = self.upload()["version_id"]
        note = {"anchor": [0.2, 0.2], "text": "Ban đầu", "color": "#FDE047"}
        aid, op = str(uuid.uuid4()), str(uuid.uuid4())
        created = value(self.mutate(v, annotation=aid, kind="note", data=note, actor=MEMBER, op=op), MEMBER)
        self.assertEqual(created["annotation"]["row_version"], 1)
        # Lost response: same op/payload returns the recorded result without a duplicate.
        self.assertEqual(value(self.mutate(v, annotation=aid, kind="note", data=note, actor=MEMBER, op=op), MEMBER), created)
        self.fail_sql(self.mutate(v, annotation=aid, kind="note", data={**note, "text": "Khác"}, actor=MEMBER, op=op), "D0003", MEMBER)
        self.fail_sql(self.mutate(v, annotation=aid, kind="note", data=note, actor=OWNER), "D0002")  # id already used
        self.assertEqual(sql("select count(*) from public.group_document_events;").stdout.strip(), "1")
        # Ordinary members cannot modify another author's annotation; the owner can.
        self.join(self.gid, INVITEE)
        edit = {**note, "text": "Sửa"}
        self.fail_sql(self.mutate(v, "update", aid, 1, data=edit, actor=INVITEE), "G0005", INVITEE)
        self.fail_sql(self.mutate(v, "delete", aid, 1, actor=INVITEE), "G0005", INVITEE)
        owner_edit = value(self.mutate(v, "update", aid, 1, data={**note, "text": "Trưởng nhóm sửa"}))
        self.assertEqual(owner_edit["annotation"]["row_version"], 2)
        self.assertEqual(owner_edit["annotation"]["author"]["id"], MEMBER)  # attribution stays with author
        # Stale CAS (expected 1) is a conflict and does not overwrite the owner's edit.
        self.fail_sql(self.mutate(v, "update", aid, 1, data=edit, actor=MEMBER), "D0002", MEMBER)
        self.fail_sql(f"select public.mutate_group_document_annotation('{uuid.uuid4()}','{v}','{aid}','update',2,null,'ink',"
                      f"'{json.dumps(edit, ensure_ascii=False)}'::jsonb);", "G0001", MEMBER)
        self.fail_sql(f"select public.mutate_group_document_annotation('{uuid.uuid4()}','{v}','{aid}','update',2,1,null,"
                      f"'{json.dumps(edit, ensure_ascii=False)}'::jsonb);", "G0001", MEMBER)
        deleted = value(self.mutate(v, "delete", aid, 2, actor=MEMBER), MEMBER)
        self.assertEqual((deleted["annotation"]["deleted"], deleted["annotation"]["data"]), (True, None))
        # Stale update/delete after tombstone cannot resurrect or overwrite.
        self.fail_sql(self.mutate(v, "update", aid, 3, data=edit, actor=MEMBER), "D0002", MEMBER)
        self.fail_sql(self.mutate(v, "delete", aid, 3, actor=MEMBER), "D0002", MEMBER)
        self.fail_sql(self.mutate(v, "restore", aid, 2, actor=MEMBER), "D0002", MEMBER)
        restored = value(self.mutate(v, "restore", aid, 3, actor=MEMBER), MEMBER)  # undo of delete
        self.assertEqual((restored["annotation"]["deleted"], restored["annotation"]["data"]["text"]), (False, "Trưởng nhóm sửa"))
        self.fail_sql(self.mutate(v, "restore", aid, 4, actor=MEMBER), "D0002", MEMBER)
        # Committed annotations survive the author leaving; retries by the former member are denied.
        value(f"select public.leave_study_group('{self.gid}');", MEMBER)
        self.fail_sql(self.mutate(v, annotation=aid, kind="note", data=note, actor=MEMBER, op=op), "D0001", MEMBER)
        snap = value(f"select public.create_group_document_snapshot('{v}');", INVITEE)
        items = value(f"select public.list_group_document_snapshot_annotations('{snap['id']}');", INVITEE)["items"]
        self.assertEqual([(i["id"], i["author"]["id"]) for i in items], [(aid, MEMBER)])
        self.assertEqual(sql(f"select revision from public.group_document_versions where id='{v}';").stdout.strip(), "4")

    # --- 9.5/11.2/11.3 snapshots, deltas and realtime ---------------------------------
    def test_snapshot_paging_fixed_revision_and_deltas(self):
        v = self.upload()["version_id"]
        ids = sorted(str(uuid.uuid4()) for _ in range(5))
        for aid in ids:
            value(self.mutate(v, annotation=aid, kind="note", data={"anchor": [0.1, 0.1], "text": aid[:8], "color": "#FDE047"}))
        snap = value(f"select public.create_group_document_snapshot('{v}','export');")
        self.assertEqual(snap["revision"], 5)
        first = value(f"select public.list_group_document_snapshot_annotations('{snap['id']}',null,2);")
        # Concurrent edits after the snapshot: update, delete and create.
        value(self.mutate(v, "update", ids[3], 1, data={"anchor": [0.1, 0.1], "text": "changed", "color": "#FDE047"}))
        value(self.mutate(v, "delete", ids[4], 1))
        value(self.mutate(v, annotation=str(uuid.uuid4()), kind="note", data={"anchor": [0.1, 0.1], "text": "new", "color": "#FDE047"}))
        pages, after = [first], first["next_after"]
        while after:
            pages.append(value(f"select public.list_group_document_snapshot_annotations('{snap['id']}','{after}',2);"))
            after = pages[-1]["next_after"]
        items = [i for p in pages for i in p["items"]]
        self.assertEqual([i["id"] for i in items], ids)
        self.assertEqual([i["data"]["text"] for i in items], [aid[:8] for aid in ids])  # state at revision 5
        self.assertEqual({p["revision"] for p in pages}, {5})
        delta = value(f"select public.list_group_document_changes('{v}',5,2);")
        self.assertEqual((delta["revision"], [i["revision"] for i in delta["items"]], delta["next_since"]), (8, [6, 7], 7))
        rest = value(f"select public.list_group_document_changes('{v}',7,2);")
        self.assertEqual(([i["revision"] for i in rest["items"]], rest["next_since"]), ([8], None))
        self.assertEqual([i["deleted"] for i in delta["items"]], [False, True])
        self.assertTrue(value(f"select public.list_group_document_changes('{v}',99);")["reset_required"])
        self.fail_sql(f"select public.list_group_document_changes('{v}',-1);", "G0001")
        self.fail_sql(f"select public.list_group_document_snapshot_annotations('{snap['id']}');", "D0006", MEMBER)
        sql(f"update public.group_document_snapshots set expires_at = now() - interval '1 second';")
        self.fail_sql(f"select public.list_group_document_snapshot_annotations('{snap['id']}');", "D0006")
        self.assertEqual(value(f"select public.get_group_document_revision('{v}');", MEMBER)["revision"], 8)

    def test_realtime_invalidation_epochs_and_receive_policy(self):
        v = self.upload()["version_id"]
        topic = value(f"select public.get_group_document_channel('{self.gid}');", MEMBER)["topic"]
        self.assertEqual(value(f"select public.get_group_document_channel('{self.gid}');")["topic"], topic)
        value(self.mutate(v, kind="note", data={"anchor": [0.3, 0.3], "text": "bí mật", "color": "#FDE047"}))
        payload = sql("select payload::text from realtime.messages order by id desc limit 1;").stdout.strip()
        self.assertEqual(set(json.loads(payload)), {"scope", "version_id", "revision", "schema_version"})
        self.assertNotIn("bí mật", payload)
        self.assertNotIn("anchor", payload)

        def receive(actor, t):
            return sql(f"set realtime.topic = '{t}'; select count(*) from realtime.messages where topic = '{t}';", actor).stdout.strip().splitlines()[-1]
        self.assertEqual(receive(MEMBER, topic), "1")
        self.assertEqual(receive(OUTSIDER, topic), "0")
        self.assertNotEqual(sql(f"set realtime.topic = '{topic}'; insert into realtime.messages(topic,extension,payload) "
                                f"values('{topic}','broadcast','{{}}');", MEMBER, check=False).returncode, 0)
        value(f"select public.remove_study_group_member('{self.gid}','{MEMBER}');")
        new_topic = value(f"select public.get_group_document_channel('{self.gid}');")["topic"]
        self.assertNotEqual(new_topic, topic)
        value(self.mutate(v))
        self.assertEqual(receive(MEMBER, topic), "0")      # obsolete subscription: nothing readable
        self.assertEqual(receive(MEMBER, new_topic), "0")
        self.assertEqual(receive(OWNER, topic), "0")
        self.assertEqual(receive(OWNER, new_topic), "1")
        self.fail_sql(f"select public.get_group_document_channel('{self.gid}');", "G0004", MEMBER)
        # Ban rotates every channel of the banned member.
        self.join(self.gid, INVITEE)
        before = value(f"select public.get_group_document_channel('{self.gid}');")["topic"]
        sql(f"update public.profiles set is_banned = true where id='{INVITEE}';")
        self.assertNotEqual(value(f"select public.get_group_document_channel('{self.gid}');")["topic"], before)

    def test_document_access_is_independent_of_calls(self):
        # No call tables exist in this migration: reading/annotating needs only membership.
        v = self.upload(MEMBER)["version_id"]
        self.assertEqual(value(self.mutate(v, actor=MEMBER), MEMBER)["revision"], 1)

    # --- 11.2 concurrency (two sessions, coordinated commit order) -----------------
    def test_concurrent_writers_cas_duplicates_and_removal(self):
        v = self.upload()["version_id"]
        # Different annotation IDs from different members: both commit.
        a, b = str(uuid.uuid4()), str(uuid.uuid4())
        self.race(self.mutate(v, annotation=a), self.mutate(v, annotation=b, actor=MEMBER), OWNER, MEMBER)
        self.assertEqual(sql(f"select count(*) from public.group_document_annotations where id in ('{a}','{b}');").stdout.strip(), "2")
        # Same note, same expected version: exactly one update wins.
        note = {"anchor": [0.2, 0.2], "text": "x", "color": "#FDE047"}
        n = str(uuid.uuid4())
        value(self.mutate(v, annotation=n, kind="note", data=note, actor=MEMBER), MEMBER)
        self.race(self.mutate(v, "update", n, 1, data={**note, "text": "owner"}),
                  self.mutate(v, "update", n, 1, data={**note, "text": "member"}, actor=MEMBER), OWNER, MEMBER, "D0002")
        self.assertEqual(sql(f"select data->>'text' from public.group_document_annotations where id='{n}';").stdout.strip(), "owner")
        # Delete vs edit: the later stale operation conflicts, nothing is resurrected.
        self.race(self.mutate(v, "delete", n, 2), self.mutate(v, "update", n, 2, data=note, actor=MEMBER), OWNER, MEMBER, "D0002")
        self.assertEqual(sql(f"select deleted_at is not null from public.group_document_annotations where id='{n}';").stdout.strip(), "t")
        # Duplicate op from two devices: one row, identical results.
        op, c = str(uuid.uuid4()), str(uuid.uuid4())
        first, second = self.race(self.mutate(v, annotation=c, op=op, actor=MEMBER), self.mutate(v, annotation=c, op=op, actor=MEMBER), MEMBER, MEMBER)
        self.assertEqual(json.loads(first.strip().splitlines()[-1]), json.loads(second.strip().splitlines()[-1]))
        self.assertEqual(sql(f"select count(*) from public.group_document_events where annotation_id='{c}';").stdout.strip(), "1")
        # Removal commits first: the queued member write is rejected (rechecked under lock).
        before = sql(f"select revision from public.group_document_versions where id='{v}';").stdout.strip()
        self.race(f"select public.remove_study_group_member('{self.gid}','{MEMBER}');", self.mutate(v, actor=MEMBER),
                  OWNER, MEMBER, "D0001")
        self.assertEqual(sql(f"select revision from public.group_document_versions where id='{v}';").stdout.strip(), before)
        # Revisions are gap-free and each maps to one event.
        self.assertEqual(sql(f"select count(*) = max(revision) from public.group_document_events where version_id='{v}';").stdout.strip(), "t")

    def test_finalize_races_with_removal_both_orders(self):
        for remove_first in (True, False):
            with self.subTest(remove_first=remove_first):
                self.setUp()
                u = self.reserve(MEMBER)
                self.put_object(u["object_key"], MEMBER)
                finalize = (f"reset role; set role service_role; select public.service_finalize_group_document_upload("
                            f"'{u['id']}','{MEMBER}','{HASH}',1000,'{PAGES}');")
                remove = f"select public.remove_study_group_member('{self.gid}','{MEMBER}');"
                if remove_first:
                    self.race(remove, finalize, OWNER, MEMBER, "D0005")
                else:
                    self.race(finalize, remove, MEMBER, OWNER)
                count = sql("select count(*) from public.group_documents;").stdout.strip()
                self.assertEqual(count, "0" if remove_first else "1")
                self.assertEqual(sql(f"select count(*) from public.study_group_members where user_id='{MEMBER}';").stdout.strip(), "0")


def write_payload_fixtures():
    """Captures actual local RPC results for Kotlin decode tests (no hand-written JSON)."""
    t = DocumentsTest("test_document_access_is_independent_of_calls")
    t.setUp()
    reserved = t.reserve(MEMBER)
    t.put_object(reserved["object_key"], MEMBER)
    ready = t.finalize(reserved["id"], MEMBER)
    v, doc = ready["version_id"], ready["document_id"]
    note = {"anchor": [0.25, 0.5], "text": "Ghi chú 😀", "color": "#FDE047"}
    aid = str(uuid.uuid4())
    created = value(t.mutate(v, annotation=aid, kind="note", data=note, actor=MEMBER), MEMBER)
    ink = value(t.mutate(v, actor=MEMBER), MEMBER)
    highlight = value(t.mutate(v, kind="highlight", data={"rect": [0.1, 0.2, 0.4, 0.3], "color": "#86EFAC"}, actor=MEMBER), MEMBER)
    deleted = value(t.mutate(v, "delete", ink["annotation"]["id"], 1, actor=MEMBER), MEMBER)
    snapshot = value(f"select public.create_group_document_snapshot('{v}','export');", MEMBER)
    payloads = {
        "reserve": reserved, "ready": ready,
        "documents": value(f"select public.list_group_documents('{t.gid}');", MEMBER),
        "document": value(f"select public.get_group_document('{doc}');", MEMBER),
        "versions": value(f"select public.list_group_document_versions('{doc}');", MEMBER),
        "version": value(f"select public.get_group_document_version('{v}');", MEMBER),
        "created": created, "highlight": highlight, "deleted": deleted,
        "snapshot": snapshot,
        "snapshot_page": value(f"select public.list_group_document_snapshot_annotations('{snapshot['id']}',null,1);", MEMBER),
        "changes": value(f"select public.list_group_document_changes('{v}',0,2);", MEMBER),
        "revision": value(f"select public.get_group_document_revision('{v}');", MEMBER),
        "channel": value(f"select public.get_group_document_channel('{t.gid}');", MEMBER),
        "archive": value(f"select public.archive_group_document('{doc}');", MEMBER),
    }
    # PostgreSQL's own jsonb text size, to check the Kotlin 64 KiB estimator against the server.
    samples = [note, {"points": [[0.1234, 0.5], [1, 0], [0.0001, 0.9999]], "color": "#DC2626", "width": 0.004},
               {"rect": [0.1, 0.2, 0.4, 0.3], "color": "#86EFAC"},
               {"anchor": [0.5, 0.5], "text": "Dòng 1" + chr(10) + 'Dòng "2" ' + chr(92) + " tab" + chr(9) + " 👍🏽",
                "color": "#93C5FD"}]
    payloads["jsonb_sizes"] = [{"data": d, "bytes": int(sql("select octet_length('" + json.dumps(d, ensure_ascii=False).replace("'", "''")
                                                       + "'::jsonb::text);").stdout.strip())} for d in samples]
    target = base.ROOT / "backend/src/test/resources/group_documents/rpc_payloads.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payloads, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    clear_documents()


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-fixtures", action="store_true", help="Refresh Kotlin decode fixtures from local RPC results")
    args = parser.parse_args()
    try:
        setup()
        if args.write_fixtures:
            write_payload_fixtures()
        # Group regression suite first (its cleanup cannot delete groups referenced by documents),
        # proving the document triggers on members/groups/profiles keep group contracts intact.
        suite = unittest.TestSuite([unittest.defaultTestLoader.loadTestsFromTestCase(base.GroupsTest),
                                    unittest.defaultTestLoader.loadTestsFromTestCase(DocumentsTest)])
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        raise SystemExit(0 if result.wasSuccessful() else 1)
    finally:
        info = base.docker("inspect", "--format", '{{index .Config.Labels "studyhub.test"}}', base.CONTAINER, check=False)
        if info.returncode == 0 and info.stdout.strip() == "study-groups":
            base.docker("rm", "-f", base.CONTAINER)
