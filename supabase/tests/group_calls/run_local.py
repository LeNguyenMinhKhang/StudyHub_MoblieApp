"""Disposable PostgreSQL tests for group calls (tasks 6.2/6.5/8.1/8.2). No DB URL is accepted.

Builds on the group-documents runner (fresh labelled postgres:17-alpine container, no network,
local Storage/Realtime stand-ins), applies 20261118090000_group_calls.sql and runs the group,
document and call suites together so the new triggers are regression-tested with both.
LiveKit is not contacted here; provider behaviour is covered by the Edge tests and cloud-probe.
"""
from pathlib import Path
import importlib.util
import json
import sys
import unittest
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "group_documents"))
sys.path.insert(0, str(HERE.parent / "study_groups"))
import run_local as base  # noqa: E402  (study_groups runner, first on the path)
docs = importlib.util.spec_from_file_location("docs_runner", HERE.parent / "group_documents" / "run_local.py")
docs_runner = importlib.util.module_from_spec(docs)
docs.loader.exec_module(docs_runner)

OWNER, MEMBER, INVITEE, OUTSIDER, BANNED = base.OWNER, base.MEMBER, base.INVITEE, base.OUTSIDER, base.BANNED
EXTRA = [str(uuid.UUID(int=i)) for i in range(21, 27)]
sql, value = base.sql, base.value


def setup():
    docs_runner.setup()
    sql((base.ROOT / "supabase/migrations/20261118090000_group_calls.sql").read_text(encoding="utf-8-sig"))
    sql((base.ROOT / "supabase/migrations/20261119090000_group_document_guidance.sql").read_text(encoding="utf-8-sig"))
    for index, actor in enumerate(EXTRA):
        sql(f"insert into auth.users values('{actor}'); insert into public.profiles(id,username,display_name,is_banned) "
            f"values('{actor}','caller_{index}','Caller {index}',false);")


def service(statement):
    return json.loads(sql("set role service_role; " + statement).stdout.strip().splitlines()[-1])


def scalar(statement):
    return sql(statement).stdout.strip().splitlines()[-1]


def clear_calls():
    sql("begin; set session_replication_role = replica; "
        "delete from public.group_document_guidance; "
        "delete from public.study_group_call_jobs; delete from public.study_group_call_webhook_events; "
        "delete from public.study_group_call_requests; delete from public.study_group_call_admissions; "
        "delete from public.study_group_calls; delete from public.study_group_member_generations; "
        "set session_replication_role = origin; commit;")


class CallsTest(unittest.TestCase):
    create, friends, invite, join = (base.GroupsTest.create, base.GroupsTest.friends,
                                     base.GroupsTest.invite, base.GroupsTest.join)
    fail_sql, race = base.GroupsTest.fail_sql, base.GroupsTest.race

    def setUp(self):
        clear_calls()
        docs_runner.clear_documents()
        base.GroupsTest.setUp(self)
        sql("update public.profiles set is_banned=false where id = any(array[" + ",".join(f"'{u}'::uuid" for u in EXTRA) + "]);")
        self.gid = self.create()["id"]
        self.join(self.gid, MEMBER)

    # --- helpers -------------------------------------------------------------------
    def admit_sql(self, actor=OWNER, request=None, session=None, mode="audio", group=None):
        return (f"select public.service_admit_group_call('{actor}','{group or self.gid}','{request or uuid.uuid4()}',"
                f"'{mode}','{session or uuid.uuid4()}');")

    def admit(self, actor=OWNER, **kwargs):
        return service(self.admit_sql(actor, **kwargs))

    def fail_service(self, statement, code):
        result = sql("set role service_role; " + statement, check=False)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(code, result.stderr)

    def activate(self, call_id):
        return service(f"select public.service_activate_group_call('{call_id}','RM_test');")

    def confirm_sql(self, admission, actor):
        return f"select public.service_confirm_group_call_admission('{admission}','{actor}',now() + interval '5 minutes');"

    def start(self, actor=OWNER, session=None):
        """Full happy path of the Edge start-or-join flow without the provider."""
        result = self.admit(actor, session=session)
        if result["admission"]["needs_room"]:
            self.activate(result["call"]["id"])
        confirmed = service(self.confirm_sql(result["admission"]["id"], actor))
        return result, confirmed

    def member(self, user):
        self.join(self.gid, user)

    def jobs(self, kind=None, status="pending"):
        where = f"status='{status}'" + (f" and kind='{kind}'" if kind else "")
        return int(scalar(f"select count(*) from public.study_group_call_jobs where {where};"))

    def admission(self, admission_id):
        return scalar(f"select status from public.study_group_call_admissions where id='{admission_id}';")

    def event(self, kind, room, identity=None, at="now()", event_id=None, sid="RM_test"):
        ident = f"'{identity}'" if identity else "null"
        return service(f"select public.service_record_group_call_event('{event_id or uuid.uuid4()}','{kind}','{room}','{sid}',"
                       f"{ident},{at});")

    # --- 6.2 admission, projection and grants -------------------------------------
    def test_admission_projection_retry_and_grants(self):
        request, session = str(uuid.uuid4()), str(uuid.uuid4())
        first = self.admit(OWNER, request=request, session=session, mode="video")
        call = first["call"]
        self.assertEqual((call["status"], call["initial_mode"], first["admission"]["needs_room"]), ("starting", "video", True))
        self.assertTrue(first["admission"]["room_name"].startswith("sh-"))
        self.assertEqual(first["admission"]["display_name"], "Test 0")
        self.assertEqual(set(call), {"id", "group_id", "status", "initial_mode", "started_by", "created_at", "activated_at",
                                     "ended_at", "participant_count", "capacity", "can_end", "cleanup_pending", "my_status"})
        self.assertNotIn("room", json.dumps(call))  # no room name/SID, identities or tokens in the projection
        self.assertEqual(self.activate(call["id"])["status"], "active")
        confirmed = service(self.confirm_sql(first["admission"]["id"], OWNER))["call"]
        self.assertEqual((confirmed["participant_count"], confirmed["capacity"], confirmed["can_end"], confirmed["my_status"]),
                         (1, 6, True, "reserved"))
        # Lost response: same request and device return the same call and identity.
        again = self.admit(OWNER, request=request, session=session)
        self.assertEqual((again["call"]["id"], again["admission"]["identity"]), (call["id"], first["admission"]["identity"]))
        self.assertFalse(again["admission"]["needs_room"])
        # Another device of the same user is refused instead of taking over.
        self.fail_service(self.admit_sql(OWNER, session=str(uuid.uuid4())), "C0002")
        # Members see the call through the plain RPC; others do not.
        seen = value(f"select public.get_study_group_call('{self.gid}');", MEMBER)["call"]
        self.assertEqual((seen["id"], seen["can_end"], seen["my_status"]), (call["id"], False, None))
        self.fail_sql(f"select public.get_study_group_call('{self.gid}');", "G0004", OUTSIDER)
        # Pending invitee, outsider and forged/invalid input never get an admission.
        self.friends(OWNER, INVITEE); self.invite(self.gid, INVITEE)
        for actor in (INVITEE, OUTSIDER):
            self.fail_service(self.admit_sql(actor), "G0004")
        self.fail_service(self.admit_sql(MEMBER, mode="screen"), "G0001")
        self.fail_service(f"select public.service_admit_group_call(null,'{self.gid}','{uuid.uuid4()}','audio','{uuid.uuid4()}');", "G0002")
        # Clients cannot call service functions, read call tables or call helpers.
        for statement in (self.admit_sql(MEMBER), "select * from public.study_group_calls;",
                          "select * from public.study_group_call_admissions;", "select studyhub_private.call_capacity();",
                          f"select public.service_end_group_call('{OWNER}','{call['id']}');"):
            self.fail_sql(statement, "42501", MEMBER)
        self.fail_sql(f"select public.get_study_group_call('{self.gid}');", "42501", None, "anon")

    def test_capacity_and_reservation_expiry(self):
        for user in EXTRA:
            self.member(user)
        admitted = [self.start(OWNER)[0]] + [self.start(user)[0] for user in EXTRA[:5]]
        self.assertEqual(admitted[-1]["call"]["participant_count"], 6)
        self.fail_service(self.admit_sql(MEMBER), "C0001")
        # A reservation that never connected frees its seat and is revoked at the provider.
        stale = admitted[1]["admission"]["id"]
        sql(f"update public.study_group_call_admissions set reserved_until = now() - interval '1 second' where id='{stale}';")
        self.admit(MEMBER)
        self.assertEqual(self.admission(stale), "expired")
        self.assertEqual(self.jobs("revoke"), 1)
        # Group membership is not capped by call capacity.
        self.assertEqual(int(scalar(f"select count(*) from public.study_group_members where group_id='{self.gid}';")), 8)

    def test_concurrent_starts_share_one_call(self):
        first, second = self.admit_sql(OWNER), self.admit_sql(MEMBER)
        a = "reset role; set role service_role; " + first
        b = "reset role; set role service_role; " + second
        out_a, out_b = self.race(a, b, OWNER, MEMBER)
        call_a = json.loads(out_a.strip().splitlines()[-1])["call"]["id"]
        call_b = json.loads(out_b.strip().splitlines()[-1])["call"]["id"]
        self.assertEqual(call_a, call_b)
        self.assertEqual(scalar(f"select count(*) from public.study_group_calls where group_id='{self.gid}';"), "1")
        self.assertEqual(scalar("select count(distinct identity) from public.study_group_call_admissions;"), "2")

    # --- 6.5/8.2 revocation races and generations ----------------------------------
    def test_removal_revokes_and_generation_fences_old_admissions(self):
        self.start(OWNER)
        admitted, _ = self.start(MEMBER)
        aid = admitted["admission"]["id"]
        value(f"select public.remove_study_group_member('{self.gid}','{MEMBER}');")
        self.assertEqual(self.admission(aid), "revoked")
        self.assertEqual(self.jobs("revoke"), 1)
        self.fail_service(self.confirm_sql(aid, MEMBER), "G0004")
        # Legitimate rejoin bumps the generation: the old admission stays unusable.
        invitation = self.invite(self.gid, MEMBER)
        value(f"select public.respond_study_group_invitation('{invitation['id']}','accept');", MEMBER)
        self.fail_service(self.confirm_sql(aid, MEMBER), "C0003")
        fresh = self.admit(MEMBER)
        self.assertNotEqual(fresh["admission"]["identity"], admitted["admission"]["identity"])
        self.assertGreater(fresh["admission"]["generation"], admitted["admission"]["generation"])
        service(self.confirm_sql(fresh["admission"]["id"], MEMBER))
        # A stale admission from a previous generation cannot be confirmed even if left reserved.
        sql(f"update public.study_group_call_admissions set status='expired' where id='{fresh['admission']['id']}';")
        stale = self.admit(MEMBER)
        sql(f"update public.study_group_member_generations set generation = generation + 1 where user_id='{MEMBER}';")
        self.fail_service(self.confirm_sql(stale["admission"]["id"], MEMBER), "C0003")

    def test_admission_races_with_removal_both_orders(self):
        self.start(OWNER)
        for remove_first in (True, False):
            with self.subTest(remove_first=remove_first):
                if not remove_first:  # rejoin (friendship already exists)
                    invitation = self.invite(self.gid, MEMBER)
                    value(f"select public.respond_study_group_invitation('{invitation['id']}','accept');", MEMBER)
                admit = "reset role; set role service_role; " + self.admit_sql(MEMBER)
                remove = f"select public.remove_study_group_member('{self.gid}','{MEMBER}');"
                if remove_first:
                    self.race(remove, admit, OWNER, MEMBER, "G0004")
                    live = scalar(f"select count(*) from public.study_group_call_admissions where user_id='{MEMBER}';")
                    self.assertEqual(live, "0")
                else:
                    self.race(admit, remove, MEMBER, OWNER)
                    # Admitted before the removal committed → revoked by it in the same transaction.
                    self.assertEqual(scalar(f"select string_agg(status, ',') from public.study_group_call_admissions "
                                            f"where user_id='{MEMBER}';"), "revoked")
                    self.assertEqual(self.jobs("revoke"), 1)

    def test_ban_archive_and_transfer_authority(self):
        for user in EXTRA[:2]:
            self.member(user)
        admitted, _ = self.start(EXTRA[0])  # EXTRA[0] is the initiator
        call_id = admitted["call"]["id"]
        member_admission = self.start(MEMBER)[0]["admission"]["id"]
        # Ban revokes immediately and denies new admission.
        sql(f"update public.profiles set is_banned=true where id='{MEMBER}';")
        self.assertEqual(self.admission(member_admission), "revoked")
        self.fail_service(self.admit_sql(MEMBER), "G0003")
        # Other members only leave; initiator (still a member) and the current owner may end.
        self.fail_service(f"select public.service_end_group_call('{EXTRA[1]}','{call_id}');", "G0005")
        value(f"select public.transfer_study_group_ownership('{self.gid}','{EXTRA[1]}');")
        self.fail_service(f"select public.service_end_group_call('{OWNER}','{call_id}');", "G0005")  # historical owner
        value(f"select public.leave_study_group('{self.gid}');", EXTRA[0])
        self.fail_service(f"select public.service_end_group_call('{EXTRA[0]}','{call_id}');", "G0004")  # initiator left
        ended = service(f"select public.service_end_group_call('{EXTRA[1]}','{call_id}');")["call"]
        self.assertEqual((ended["status"], ended["cleanup_pending"], ended["can_end"]), ("ending", True, False))
        # Archive ends any open call atomically with the archive.
        clear_calls()
        call2 = self.start(OWNER)[0]["call"]["id"]
        value(f"select public.archive_study_group('{self.gid}');", EXTRA[1])
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call2}';"), "ending")
        self.assertEqual(self.jobs("end_room"), 1)

    # --- 8.2 token issuance races (the confirm step is the last gate before a token leaves the server) ---
    def reserved_member(self):
        """Call is active; MEMBER holds a reservation whose token has not been confirmed yet."""
        self.start(OWNER)
        return self.admit(MEMBER)["admission"]["id"]

    def svc(self, statement):
        return "reset role; set role service_role; " + statement

    def revoke_state(self, admission):
        return scalar("select status || ',' || (token_issued_at is not null)::text || ',' || "
                      "(select count(*) from public.study_group_call_jobs j where j.admission_id = a.id and j.kind = 'revoke' "
                      f"and j.status = 'pending') from public.study_group_call_admissions a where a.id = '{admission}';")

    def test_token_confirmation_races_with_ban_archive_and_removal(self):
        # Built after setUp() of each subtest, because every scenario has its own group.
        causes = {
            "ban": (lambda: f"reset role; update public.profiles set is_banned = true where id = '{MEMBER}';", "C0003"),
            "remove": (lambda: f"select public.remove_study_group_member('{self.gid}','{MEMBER}');", "G0004"),
            "archive": (lambda: f"select public.archive_study_group('{self.gid}');", "G0004"),
        }
        actor = OWNER
        for name, (build, refused) in causes.items():
            with self.subTest(order="confirm commits first", cause=name):
                self.setUp()
                action = build()
                admission = self.reserved_member()
                confirm = self.svc(self.confirm_sql(admission, MEMBER))
                self.race(confirm, action, MEMBER, actor)
                # The token was issued, and the later change revoked that exact admission with a durable
                # provider job; nothing is reported revoked until a worker confirms it.
                status, issued, pending = self.revoke_state(admission).split(",")
                # Archive revokes through the call-level end_room job (it covers every admitted identity).
                self.assertEqual((status, issued, pending), ("revoked", "true", "0" if name == "archive" else "1"))
                if name == "archive":
                    self.assertEqual(self.jobs("end_room"), 1)
                self.assertEqual(scalar(f"select revoke_confirmed_at is null from public.study_group_call_admissions where id='{admission}';"), "t")
            with self.subTest(order="change commits first", cause=name):
                self.setUp()
                action = build()
                admission = self.reserved_member()
                self.race(action, self.svc(self.confirm_sql(admission, MEMBER)), actor, MEMBER, refused)
                status, issued, pending = self.revoke_state(admission).split(",")
                self.assertEqual((status, issued), ("revoked", "false"))  # no token was ever recorded as issued
                self.assertEqual(pending, "0" if name == "archive" else "1")
                if name == "archive":
                    self.assertEqual(self.jobs("end_room"), 1)

    def test_revoke_retry_after_rejoin_targets_only_the_old_identity(self):
        self.start(OWNER)
        old = self.start(MEMBER)[0]["admission"]
        value(f"select public.remove_study_group_member('{self.gid}','{MEMBER}');")
        job = service("select public.service_claim_group_call_jobs(10);")[0]
        self.assertEqual((job["kind"], job["identities"]), ("revoke", [old["identity"]]))
        # Provider failure: the job stays pending (revocation is never reported done without confirmation).
        self.assertEqual(service(f"select public.service_fail_group_call_job({job['id']},'provider_unavailable');")["status"], "pending")
        self.assertEqual(scalar(f"select revoke_confirmed_at is null from public.study_group_call_admissions where id='{old['id']}';"), "t")
        # The member legitimately rejoins and gets a new identity while the old revoke is still pending.
        value(f"select public.respond_study_group_invitation('{self.invite(self.gid, MEMBER)['id']}','accept');", MEMBER)
        fresh = self.admit(MEMBER)["admission"]
        service(self.confirm_sql(fresh["id"], MEMBER))
        self.assertNotEqual(fresh["identity"], old["identity"])
        sql("update public.study_group_call_jobs set run_after = now(), leased_until = null;")
        retried = service("select public.service_claim_group_call_jobs(10);")
        self.assertEqual([(j["kind"], j["identities"]) for j in retried], [("revoke", [old["identity"]])])
        service(f"select public.service_complete_group_call_job({retried[0]['id']},array['{old['identity']}']::uuid[]);")
        # Only the old identity was confirmed revoked; the new admission is untouched and still usable.
        self.assertEqual(scalar(f"select revoke_confirmed_at is not null from public.study_group_call_admissions where id='{old['id']}';"), "t")
        self.assertEqual(scalar(f"select revoke_confirmed_at is null from public.study_group_call_admissions where id='{fresh['id']}';"), "t")
        self.assertEqual(self.admission(fresh["id"]), "reserved")
        self.assertEqual(self.jobs("revoke"), 0)

    def test_admission_races_with_ban_and_archive_never_yield_a_token(self):
        # Ban before and after admission. The admission check does not lock the profile, so an admission can
        # commit just after a ban; the confirm step (nothing is signed without it) refuses banned accounts too.
        self.start(OWNER)
        sql(f"update public.profiles set is_banned = true where id = '{MEMBER}';")
        self.fail_service(self.admit_sql(MEMBER), "G0003")
        sql(f"update public.profiles set is_banned = false where id = '{MEMBER}';")
        admission = self.admit(MEMBER)["admission"]["id"]
        sql(f"update public.profiles set is_banned = true where id = '{MEMBER}';")
        self.assertEqual(self.admission(admission), "revoked")
        self.fail_service(self.confirm_sql(admission, MEMBER), "G0003")
        self.assertEqual(self.revoke_state(admission).split(",")[1], "false")
        # Archive racing admission, both orders.
        self.setUp(); self.start(OWNER)
        archive = f"select public.archive_study_group('{self.gid}');"
        self.race(archive, self.svc(self.admit_sql(MEMBER)), OWNER, MEMBER, "G0004")
        self.assertEqual(scalar(f"select count(*) from public.study_group_call_admissions where user_id='{MEMBER}';"), "0")
        self.setUp(); self.start(OWNER)
        archive = f"select public.archive_study_group('{self.gid}');"
        self.race(self.svc(self.admit_sql(MEMBER)), archive, MEMBER, OWNER)
        self.assertEqual(scalar("select string_agg(status, ',') from public.study_group_calls;"), "ending")
        self.assertEqual(scalar(f"select status from public.study_group_call_admissions where user_id='{MEMBER}';"), "revoked")
        self.assertEqual(self.jobs("end_room"), 1)

    def test_end_for_all_races_with_ownership_transfer_in_both_orders(self):
        for transfer_first in (True, False):
            with self.subTest(transfer_first=transfer_first):
                self.setUp()
                self.member(EXTRA[0])
                self.member(EXTRA[1])
                call_id = self.start(EXTRA[0])[0]["call"]["id"]  # EXTRA[0] initiated the call
                transfer = f"select public.transfer_study_group_ownership('{self.gid}','{EXTRA[1]}');"
                end = self.svc(f"select public.service_end_group_call('{OWNER}','{call_id}');")
                if transfer_first:
                    # The previous owner is no longer allowed once the transfer committed.
                    self.race(transfer, end, OWNER, OWNER, "G0005")
                    self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "active")
                else:
                    # Ending committed first with the owner's authority: both succeed, the call is closing.
                    self.race(end, transfer, OWNER, OWNER)
                    self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "ending")
                self.assertEqual(scalar(f"select owner_id from public.study_groups where id='{self.gid}';"), EXTRA[1])

    # --- 6.4 ending, worker jobs and reconciliation ---------------------------------
    def test_end_blocks_joins_and_worker_confirms_before_ended(self):
        admitted, _ = self.start(OWNER)
        call_id, room = admitted["call"]["id"], admitted["admission"]["room_name"]
        member_admitted, _ = self.start(MEMBER)
        self.fail_service(f"select public.service_end_group_call('{MEMBER}','{call_id}');", "G0005")
        service(f"select public.service_end_group_call('{OWNER}','{call_id}');")
        self.fail_service(self.admit_sql(MEMBER), "C0004")
        self.fail_service(self.confirm_sql(member_admitted["admission"]["id"], MEMBER), "C0004")
        claimed = service("select public.service_claim_group_call_jobs(10);")
        self.assertEqual([(j["kind"], j["room_name"]) for j in claimed], [("end_room", room)])
        identities = claimed[0]["identities"]
        self.assertEqual(set(identities), {admitted["admission"]["identity"], member_admitted["admission"]["identity"]})
        self.assertEqual(service("select public.service_claim_group_call_jobs(10);"), [])  # leased
        # Provider failure: retried later, call stays ending (never reported as ended).
        failed = service(f"select public.service_fail_group_call_job({claimed[0]['id']},'provider_unavailable');")
        self.assertEqual(failed["status"], "pending")
        self.assertEqual(service("select public.service_claim_group_call_jobs(10);"), [])  # backoff
        sql("update public.study_group_call_jobs set run_after = now();")
        claimed = service("select public.service_claim_group_call_jobs(10);")
        partial = service(f"select public.service_complete_group_call_job({claimed[0]['id']},array['{identities[0]}']::uuid[]);")
        self.assertEqual(partial["status"], "pending")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "ending")
        sql("update public.study_group_call_jobs set run_after = now(), leased_until = null;")
        claimed = service("select public.service_claim_group_call_jobs(10);")
        done = service(f"select public.service_complete_group_call_job({claimed[0]['id']},"
                       f"array[{','.join(repr(i) for i in claimed[0]['identities'])}]::uuid[]);")
        self.assertEqual(done["status"], "done")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "ended")
        self.assertIsNone(value(f"select public.get_study_group_call('{self.gid}');")["call"])
        # A new call gets a new room; the ended request key does not restart anything.
        fresh = self.admit(OWNER)
        self.assertNotEqual(fresh["admission"]["room_name"], room)

    def test_leave_call_presence_and_empty_room_cleanup(self):
        admitted, _ = self.start(OWNER)
        call_id = admitted["call"]["id"]
        member = self.start(MEMBER)[0]["admission"]
        service(f"select public.service_record_group_call_presence('{call_id}',array['{admitted['admission']['identity']}',"
                f"'{member['identity']}']::uuid[]);")
        self.assertEqual(self.admission(member["id"]), "connected")
        # Initiator leaving does not end the call for others.
        service(f"select public.service_leave_group_call('{OWNER}','{call_id}');")
        self.assertEqual(self.admission(admitted["admission"]["id"]), "left")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "active")
        self.assertEqual(self.jobs("revoke"), 1)
        # Someone still present with a left (revoked) identity is disconnected again.
        sql("update public.study_group_call_jobs set status='done';")
        sql(f"update public.study_group_call_admissions set revoke_confirmed_at = now() where id='{admitted['admission']['id']}';")
        service(f"select public.service_record_group_call_presence('{call_id}',array['{admitted['admission']['identity']}',"
                f"'{member['identity']}']::uuid[]);")
        self.assertEqual(self.jobs("revoke"), 1)
        # Empty for 60 s → ending (reconciled, not instant).
        service(f"select public.service_leave_group_call('{MEMBER}','{call_id}');")
        service(f"select public.service_record_group_call_presence('{call_id}',array[]::uuid[]);")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "active")
        sql(f"update public.study_group_calls set empty_since = now() - interval '61 seconds' where id='{call_id}';")
        service(f"select public.service_record_group_call_presence('{call_id}',array[]::uuid[]);")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "ending")
        # Reconcile: due calls at most every 30 s; stuck 'starting' calls fail.
        clear_calls()
        stuck = self.admit(OWNER)["call"]["id"]
        sql(f"update public.study_group_calls set created_at = now() - interval '3 minutes' where id='{stuck}';")
        service("select public.service_reconcile_group_calls();")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{stuck}';"), "failed")
        active = self.start(OWNER)[0]["call"]["id"]
        self.assertEqual([c["id"] for c in service("select public.service_reconcile_group_calls();")], [active])
        self.assertEqual(service("select public.service_reconcile_group_calls();"), [])

    def test_webhooks_dedupe_stale_and_never_reopen(self):
        admitted, _ = self.start(OWNER)
        call_id, room, identity = admitted["call"]["id"], admitted["admission"]["room_name"], admitted["admission"]["identity"]
        event_id = str(uuid.uuid4())
        self.assertEqual(self.event("participant_joined", room, identity, event_id=event_id)["applied"], "connected")
        self.assertTrue(self.event("participant_joined", room, identity, event_id=event_id)["duplicate"])
        # Out-of-order: an older 'left' after a newer 'joined' is ignored.
        self.assertEqual(self.event("participant_left", room, identity, at="now() - interval '1 minute'")["applied"], "stale")
        self.assertEqual(self.admission(admitted["admission"]["id"]), "connected")
        self.assertEqual(self.event("participant_joined", "sh-unknown", identity)["applied"], "unknown_room")
        # Stale room_finished from an earlier room SID does not end the current call.
        self.assertEqual(self.event("room_finished", room, sid="RM_old")["applied"], "ignored")
        # End and complete; late events never reopen it.
        service(f"select public.service_end_group_call('{OWNER}','{call_id}');")
        job = service("select public.service_claim_group_call_jobs(10);")[0]
        service(f"select public.service_complete_group_call_job({job['id']},array['{identity}']::uuid[]);")
        self.assertEqual(self.event("room_started", room, sid="RM_new")["applied"], "reclose")
        self.assertEqual(self.event("participant_joined", room, identity)["applied"], "revoke")
        self.assertEqual(scalar(f"select status from public.study_group_calls where id='{call_id}';"), "ended")
        self.assertEqual(self.jobs("end_room"), 1)
        self.assertEqual(self.jobs("revoke"), 1)
        # room_finished for an active call starts ending (provider closed it).
        active = self.start(OWNER)[0]["admission"]["room_name"]
        self.assertEqual(self.event("room_finished", active)["applied"], "ending")


class GuidanceTest(unittest.TestCase):
    """Task 9.6: follow-the-guide navigation. Reuses the group, call and document fixtures."""
    setUp = CallsTest.setUp
    create, friends, invite, join = CallsTest.create, CallsTest.friends, CallsTest.invite, CallsTest.join
    fail_sql, race = CallsTest.fail_sql, CallsTest.race
    admit_sql, admit, fail_service, activate = CallsTest.admit_sql, CallsTest.admit, CallsTest.fail_service, CallsTest.activate
    confirm_sql, start, member, jobs, admission = (CallsTest.confirm_sql, CallsTest.start, CallsTest.member,
                                                   CallsTest.jobs, CallsTest.admission)
    reserve, put_object, finalize, upload, mutate = (docs_runner.DocumentsTest.reserve, docs_runner.DocumentsTest.put_object,
                                                     docs_runner.DocumentsTest.finalize, docs_runner.DocumentsTest.upload,
                                                     docs_runner.DocumentsTest.mutate)

    # --- helpers -------------------------------------------------------------------
    def ready(self, users=(OWNER, MEMBER)):
        """Open call with live admissions for [users] and one 2-page document."""
        call_id = None
        for user in users:
            call_id = self.start(user)[0]["call"]["id"]
        document = self.upload(OWNER)
        return call_id, document["version_id"], document["document_id"]

    def claim_sql(self, call, version, page=0):
        return f"select public.claim_group_document_guidance('{call}','{version}',{page});"

    def update_sql(self, call, generation, version, page):
        return f"select public.update_group_document_guidance('{call}',{generation},'{version}',{page});"

    def release_sql(self, call, generation="null"):
        return f"select public.release_group_document_guidance('{call}',{generation});"

    def get(self, call, actor=OWNER):
        return value(f"select public.get_group_document_guidance('{call}');", actor)["guidance"]

    def claim(self, call, version, page=0, actor=OWNER):
        return value(self.claim_sql(call, version, page), actor)["guidance"]

    def guidance_rows(self):
        return int(scalar("select count(*) from public.group_document_guidance;"))

    def document_rows(self):
        return (int(scalar("select count(*) from public.group_documents;")),
                int(scalar("select count(*) from public.group_document_versions;")),
                int(scalar("select count(*) from public.group_document_annotations;")))

    # --- claim / update / release / lease ------------------------------------------
    def test_claim_update_release_lease_and_generation(self):
        call, version, document = self.ready()
        self.assertIsNone(self.get(call, MEMBER))
        first = self.claim(call, version, 0, OWNER)
        self.assertEqual((first["generation"], first["sequence"], first["is_me"], first["page_index"], first["document_id"]),
                         (1, 1, True, 0, document))
        self.assertEqual(set(first), {"call_id", "guide", "document_id", "version_id", "page_index", "generation",
                                      "sequence", "lease_expires_at", "is_me"})
        self.assertEqual(set(first["guide"]), {"id", "display_name", "avatar_url"})
        seen = self.get(call, MEMBER)
        self.assertEqual((seen["is_me"], seen["guide"]["id"], seen["generation"]), (False, OWNER, 1))
        # A live lease held by someone else is not stolen and its holder cannot be impersonated.
        self.fail_sql(self.claim_sql(call, version, 1), "D0007", MEMBER)
        self.fail_sql(self.update_sql(call, 1, version, 1), "D0008", MEMBER)
        self.assertFalse(value(self.release_sql(call), MEMBER)["released"])
        self.assertEqual(self.get(call, OWNER)["page_index"], 0)
        # Moving followers bumps the sequence; a heartbeat on the same page only renews the lease.
        moved = value(self.update_sql(call, 1, version, 1), OWNER)["guidance"]
        self.assertEqual((moved["sequence"], moved["page_index"], moved["generation"]), (2, 1, 1))
        sql(f"update public.group_document_guidance set lease_expires_at = now() + interval '1 second' where call_id='{call}';")
        beat = value(self.update_sql(call, 1, version, 1), OWNER)["guidance"]
        self.assertEqual(beat["sequence"], 2)
        self.assertEqual(scalar(f"select lease_expires_at > now() + interval '20 seconds' from public.group_document_guidance where call_id='{call}';"), "t")
        # Wrong generation, bad page numbers and unknown versions are rejected.
        self.fail_sql(self.update_sql(call, 2, version, 1), "D0008", OWNER)
        for page in (2, -1):
            self.fail_sql(self.update_sql(call, 1, version, page), "G0001", OWNER)
            self.fail_sql(self.claim_sql(call, version, page), "G0001", OWNER)
        self.fail_sql(self.claim_sql(call, uuid.uuid4(), 0), "D0001", OWNER)
        # Same guide may re-claim (navigate) without changing generation.
        again = self.claim(call, version, 0, OWNER)
        self.assertEqual((again["generation"], again["sequence"], again["page_index"]), (1, 3, 0))
        # Expired lease: followers see no guide, another member takes over and fences the old guide.
        sql(f"update public.group_document_guidance set lease_expires_at = now() - interval '1 second' where call_id='{call}';")
        self.assertIsNone(self.get(call, MEMBER))
        taken = self.claim(call, version, 1, MEMBER)
        self.assertEqual((taken["generation"], taken["is_me"], taken["guide"]["id"]), (2, True, MEMBER))
        self.fail_sql(self.update_sql(call, 1, version, 0), "D0008", OWNER)
        self.assertFalse(value(self.release_sql(call, 1), OWNER)["released"])
        self.assertEqual(self.get(call, OWNER)["guide"]["id"], MEMBER)
        # Release is generation-fenced, idempotent and leaves everyone free to claim.
        self.assertFalse(value(self.release_sql(call, 1), MEMBER)["released"])
        self.assertTrue(value(self.release_sql(call, 2), MEMBER)["released"])
        self.assertFalse(value(self.release_sql(call, 2), MEMBER)["released"])
        self.assertIsNone(self.get(call, OWNER))
        self.assertEqual(self.claim(call, version, 0, OWNER)["generation"], 1)

    def test_guidance_does_not_gate_annotations(self):
        call, version, _ = self.ready()
        self.claim(call, version, 0, OWNER)
        for actor in (OWNER, MEMBER):  # the non-guide annotates while a guide exists, and vice versa
            result = value(self.mutate(version, actor=actor), actor)
            self.assertEqual(result["action"], "create")
        value(self.release_sql(call), OWNER)
        self.assertEqual(self.document_rows()[2], 2)

    # --- authorization ---------------------------------------------------------------
    def test_access_and_target_rules(self):
        call, version, document = self.ready((OWNER,))  # MEMBER is a member but not in the call
        self.fail_sql(self.claim_sql(call, version), "C0003", MEMBER)
        self.assertIsNone(self.get(call, MEMBER))  # reading is allowed for members, claiming needs a live admission
        self.friends(OWNER, INVITEE); self.invite(self.gid, INVITEE)
        for actor in (OUTSIDER, INVITEE):
            for statement in (self.claim_sql(call, version), self.update_sql(call, 1, version, 0),
                              f"select public.get_group_document_guidance('{call}');"):
                self.fail_sql(statement, "C0003", actor)
        self.fail_sql(self.claim_sql(call, version), "G0003", BANNED)
        self.fail_sql(self.claim_sql(uuid.uuid4(), version), "C0003", OWNER)
        self.fail_sql(self.claim_sql(call, version), "42501", None, "anon")
        # Direct table access and helpers are not part of the API.
        for statement in ("select * from public.group_document_guidance;", "delete from public.group_document_guidance;",
                          "select studyhub_private.guidance_lease();"):
            self.fail_sql(statement, "42501", MEMBER)
        # A version from another group (even a group the caller belongs to) is not a valid target.
        other_group = self.create()["id"]
        original_gid, self.gid = self.gid, other_group
        foreign = self.upload(OWNER, title="Other group")["version_id"]
        self.gid = original_gid
        self.fail_sql(self.claim_sql(call, foreign), "D0001", OWNER)
        # Archived documents cannot be guided to.
        value(f"select public.archive_group_document('{document}');")
        self.fail_sql(self.claim_sql(call, version), "D0001", OWNER)
        # An ending call accepts no guidance.
        fresh = self.upload(OWNER, title="Fresh")["version_id"]
        service(f"select public.service_end_group_call('{OWNER}','{call}');")
        self.fail_sql(self.claim_sql(call, fresh), "C0004", OWNER)
        self.assertIsNone(self.get(call, OWNER))

    # --- cleanup keeps documents and annotations ---------------------------------------
    def guided(self, guide=MEMBER):
        call, version, _ = self.ready()
        value(self.mutate(version, actor=OWNER), OWNER)
        value(self.mutate(version, actor=MEMBER), MEMBER)
        self.claim(call, version, 1, guide)
        self.assertEqual(self.guidance_rows(), 1)
        before = self.document_rows()
        self.assertEqual(before, (1, 1, 2))
        return call, version, before

    def test_guide_leaving_the_call_releases_only_guidance(self):
        call, version, before = self.guided()
        service(f"select public.service_leave_group_call('{MEMBER}','{call}');")
        self.assertEqual((self.guidance_rows(), self.document_rows()), (0, before))
        self.assertIsNone(self.get(call, OWNER))
        # Another participant leaving does not disturb the guide.
        self.claim(call, version, 0, OWNER)
        service(f"select public.service_leave_group_call('{MEMBER}','{call}');")
        self.assertEqual(self.guidance_rows(), 1)

    def test_guide_removed_banned_or_group_archived_releases_only_guidance(self):
        for how in ("remove", "leave", "ban", "archive"):
            with self.subTest(how=how):
                self.setUp()
                call, version, before = self.guided()
                if how == "remove":
                    value(f"select public.remove_study_group_member('{self.gid}','{MEMBER}');")
                elif how == "leave":
                    value(f"select public.leave_study_group('{self.gid}');", MEMBER)
                elif how == "ban":
                    sql(f"update public.profiles set is_banned=true where id='{MEMBER}';")
                else:
                    value(f"select public.archive_study_group('{self.gid}');")
                self.assertEqual(self.guidance_rows(), 0)
                self.assertEqual(self.document_rows(), before)

    def test_ending_the_call_releases_only_guidance(self):
        call, version, before = self.guided(OWNER)
        service(f"select public.service_end_group_call('{OWNER}','{call}');")
        self.assertEqual((self.guidance_rows(), self.document_rows()), (0, before))
        self.fail_sql(self.claim_sql(call, version), "C0004", OWNER)
        # Finish the shutdown; documents stay readable and annotatable outside any call.
        while jobs := service("select public.service_claim_group_call_jobs(10);"):
            for job in jobs:
                ids = ",".join(repr(i) for i in job["identities"])
                service(f"select public.service_complete_group_call_job({job['id']},array[{ids}]::uuid[]);")
        self.assertEqual(self.get(call, OWNER), None)
        self.assertEqual(value(self.mutate(version, actor=MEMBER), MEMBER)["action"], "create")
        self.assertEqual(self.document_rows()[2], 3)

    def test_removal_races_with_claim_in_both_orders(self):
        call, version, _ = self.ready()
        remove = f"select public.remove_study_group_member('{self.gid}','{MEMBER}');"
        # Removal commits first: the claim waits on the group lock, then is refused.
        self.race(remove, self.claim_sql(call, version), OWNER, MEMBER, "C0003")
        self.assertEqual(self.guidance_rows(), 0)
        # Claim commits first: the later removal deletes the new guidance in its own transaction.
        self.setUp()
        call, version, _ = self.ready()
        self.race(self.claim_sql(call, version), f"select public.remove_study_group_member('{self.gid}','{MEMBER}');", MEMBER, OWNER)
        self.assertEqual(self.guidance_rows(), 0)

    def test_concurrent_claims_yield_one_guide(self):
        call, version, _ = self.ready()
        self.race(self.claim_sql(call, version, 0), self.claim_sql(call, version, 1), OWNER, MEMBER, "D0007")
        self.assertEqual(scalar("select count(*) from public.group_document_guidance;"), "1")
        self.assertEqual(self.get(call, OWNER)["guide"]["id"], OWNER)


def write_payload_fixtures():
    """Captures actual RPC projections for Kotlin decode tests (no hand-written JSON)."""
    t = CallsTest("test_admission_projection_retry_and_grants")
    t.setUp()
    admitted, confirmed = t.start(OWNER)
    call_id = admitted["call"]["id"]
    payloads = {
        "get_active": value(f"select public.get_study_group_call('{t.gid}');", MEMBER),
        "confirm": confirmed,
        "leave": service(f"select public.service_leave_group_call('{OWNER}','{call_id}');"),
        "end": service(f"select public.service_end_group_call('{OWNER}','{call_id}');"),
    }
    # Drain every job (the leave revoke and the end_room) as the worker would.
    while jobs := service("select public.service_claim_group_call_jobs(10);"):
        for job in jobs:
            ids = ",".join(repr(i) for i in job["identities"])
            service(f"select public.service_complete_group_call_job({job['id']},array[{ids}]::uuid[]);")
    assert scalar(f"select status from public.study_group_calls where id='{call_id}';") == "ended"
    payloads["get_none"] = value(f"select public.get_study_group_call('{t.gid}');", MEMBER)
    target = base.ROOT / "backend/src/test/resources/group_calls/rpc_payloads.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payloads, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    clear_calls()
    # Guidance (task 9.6): real claim/update/get/release results for the Kotlin decode tests.
    g = GuidanceTest("test_claim_update_release_lease_and_generation")
    g.setUp()
    call, version, _ = g.ready()
    guidance = {
        "claim": value(g.claim_sql(call, version, 0), OWNER),
        "get_follower": value(f"select public.get_group_document_guidance('{call}');", MEMBER),
        "update": value(g.update_sql(call, 1, version, 1), OWNER),
        "release": value(g.release_sql(call, 1), OWNER),
    }
    guidance["get_none"] = value(f"select public.get_group_document_guidance('{call}');", MEMBER)
    guidance["release_again"] = value(g.release_sql(call, 1), OWNER)
    target = base.ROOT / "backend/src/test/resources/group_documents/guidance_payloads.json"
    target.write_text(json.dumps(guidance, ensure_ascii=False, indent=2) + chr(10), encoding="utf-8")
    clear_calls()


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-fixtures", action="store_true", help="Refresh Kotlin decode fixtures from local RPC results")
    parser.add_argument("--calls-only", action="store_true", help="Skip the group/document regression suites")
    parser.add_argument("--only", help="Run only test classes/methods matching this substring (e.g. Guidance)")
    args = parser.parse_args()
    try:
        setup()
        if args.write_fixtures:
            write_payload_fixtures()
        loader = unittest.defaultTestLoader
        suites = [] if args.calls_only else [loader.loadTestsFromTestCase(base.GroupsTest),
                                              loader.loadTestsFromTestCase(docs_runner.DocumentsTest)]
        suite = unittest.TestSuite(suites + [loader.loadTestsFromTestCase(CallsTest),
                                              loader.loadTestsFromTestCase(GuidanceTest)])
        if args.only:
            def flatten(tests):
                for item in tests:
                    yield from flatten(item) if isinstance(item, unittest.TestSuite) else [item]
            suite = unittest.TestSuite(t for t in flatten(suite) if args.only in t.id())
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        raise SystemExit(0 if result.wasSuccessful() else 1)
    finally:
        info = base.docker("inspect", "--format", '{{index .Config.Labels "studyhub.test"}}', base.CONTAINER, check=False)
        if info.returncode == 0 and info.stdout.strip() == "study-groups":
            base.docker("rm", "-f", base.CONTAINER)
