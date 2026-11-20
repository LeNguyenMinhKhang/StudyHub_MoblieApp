# Implementation evidence

## Baseline and contract review (2026-11-13)

The apply request selects the existing design defaults: one fixed subject per group,
owner-only invitations to accepted friends, no invitation expiry, six call participants,
author/owner annotation management, 20 MiB / 100 pages, flattened export and optional
follow-guide. Calls and persistent collaborative PDFs remain in scope.

Local review:
- `SubjectRepository.getActiveSubjects()` delegates to the `is_active=true` query.
- `FriendshipRepository.list(FRIENDS)` supports descending keyset pages and safe errors.
- `Home.openDetail` / `closeDetail` use child fragments; the group entrypoint is a placeholder.
- `ApiResponse` represents completed outcomes; loading belongs in UI state.
  `SafeApiCall` preserves cancellation. New SQLSTATEs must map by code, not SQL messages.
- `app-admin/MainActivity.kt` is a placeholder. No profile/subject deletion implementation
  exists there. Subject DELETE is allowed by the local admin RLS policy; profile deletion
  can cascade from `auth.users`. New RESTRICT references intentionally reject physical
  deletion of referenced profiles/subjects, including retained archived history. Use account
  banning or subject deactivation instead; there is no automatic ownership succession.
- The pre-existing `supabase/schema.sql` diff adds 35 lines of post/comment cleanup.
  It is unrelated and must remain intact. The snapshot mentions an absent
  `20261109090000_post_delete_cascade` migration; no repair is included in this change.
- Kotlin/Supabase 3.1.4, Ktor 3.1.1, serialization 1.8.0; JUnit 4.13.2 is available.
  Android minSdk 24 / compileSdk and targetSdk 37; AGP 9.3.0 / Gradle 9.6.0.
- Hosted schema, LiveKit, devices and PDF export have not been verified.

## Group RPC wire contract

All functions return JSONB. Actor comes only from `auth.uid()`. Public arguments use
`p_` prefixes. UUID arguments are PostgreSQL `uuid`; names/actions are `text`.

| RPC | Arguments | Result |
| --- | --- | --- |
| create_study_group | p_request_id, p_subject_id, p_name, p_description=null | GroupDetail |
| get_study_group | p_group_id | GroupDetail |
| update_study_group | p_group_id, p_name, p_description=null | GroupDetail |
| list_my_study_groups | p_cursor=null, p_limit=20 | Page<GroupSummary> |
| list_study_group_members | p_group_id, p_cursor=null, p_limit=20 | Page<GroupMember> |
| list_my_study_group_invitations | p_cursor=null, p_limit=20 | Page<GroupInvitation> |
| list_study_group_invitations | p_group_id, p_cursor=null, p_limit=20 | Page<GroupInvitation> |
| invite_study_group_member | p_group_id, p_target_id | InvitationResult |
| respond_study_group_invitation | p_invitation_id, p_action=accept/decline | InvitationResult |
| revoke_study_group_invitation | p_invitation_id | InvitationResult |
| leave_study_group | p_group_id | MutationResult |
| remove_study_group_member | p_group_id, p_target_id | MutationResult |
| transfer_study_group_ownership | p_group_id, p_target_id | GroupDetail |
| archive_study_group | p_group_id | MutationResult |

- GroupSummary/Detail: `id`, `name`, nullable `description`, `subject` (`id`, `name`,
  `is_active`), `role` (`owner`/`member`), `member_count`, `created_at`, `updated_at`.
  No creation payload, request key, private profile or historical actor fields.
- GroupMember: `user_id`, `display_name`, nullable `avatar_url`, `role`, `joined_at`.
- GroupInvitation: `id`, `status`, `created_at`, `group_id`, `group_name`, nullable
  `description`, `subject`, `inviter` and `invitee` public projections (`id`,
  `display_name`, nullable `avatar_url`). Incoming preview omits `invitee` because
  the recipient is already known; owner lists include the invitee.
- InvitationResult: `id`, `group_id`, `status`. MutationResult: `group_id`, `status`.
- Page: `items`, nullable `next_cursor`. Cursor is JSONB with exactly `at` (finite
  timestamptz string), `id` (UUID string). Groups/invitations descend by created_at/id;
  members ascend by joined_at/user_id. Reject malformed cursors and limits outside
  1..50, default 20; fetch one extra row to determine the next cursor.
- Normalize name/description with surrounding whitespace removed; empty description
  becomes null. Count Unicode code points. Persist the normalized creation payload.
- SQLSTATEs: G0001 validation_failed, G0002 unauthenticated, G0003 account_unavailable,
  G0004 group_unavailable, G0005 forbidden, G0006 subject_unavailable, G0007 not_friends,
  G0008 already_member, G0009 invitation_unavailable, G0010 state_conflict,
  G0011 owner_transfer_required. Typed UUID/cursor conversion failures also map to
  validation. Outsider entity lookups use unavailable rather than disclosing existence.

Contract acceptance still requires real local SQL payloads and Kotlin decode tests.

## Android group completion (2026-11-14)

Continued from the existing 21/67 completed tasks. Tasks 5.1–5.7 are now complete,
bringing the change to 28/67. The existing SQL/data-layer implementation and unrelated
working-tree changes were preserved; this session did not deploy migrations or Edge
Functions, publish commits, or archive the change.

| Tasks | Implementation and evidence |
| --- | --- |
| 5.1 | `Group.kt` delegates to the lifecycle-aware browser in `GroupFragments.kt`; lists expose loading, empty, error/retry, refresh and deduplicated keyset pagination. |
| 5.2 | Create form retains draft and request ID through retry/recreation. Saved state is scoped to both account and login session; subject loading is retryable. |
| 5.3 | Detail shows subject/count/role, owner metadata edits and retained unsaved edits after failed save/refresh. Ownership loss resets the editing draft. |
| 5.4 | Member, pending and accepted-friend sections use separate operation errors. Friend refresh reconciles the first 50 known members/pending invitations; the invite RPC remains authoritative for full-group eligibility. All member pages lose owner controls immediately after a role downgrade, including when the next page fails. |
| 5.5 | Accept waits for server acknowledgement before opening a group; stale invitations reload without opening private detail. Duplicate operations are suppressed. |
| 5.6 | Leave/remove/transfer/archive have confirmation and result UI. Terminal access errors erase private state immediately, including failed mutations; `Home.closeGroupDetails()` asynchronously unwinds the whole detail stack to the group list. |
| 5.7 | 21 group ViewModel tests pass, covering restored drafts/request IDs, subject failure, list errors/pagination, duplicate accept, stale invitation, role changes, account/login changes, access loss, failed edits, leave/archive, and refreshed invitation eligibility. Source review checked localized strings, labelled controls, 48dp input/spinner targets, live status regions and ViewBinding/adapter/dialog cleanup. |

Verification performed in this session:

- `gradlew.bat :backend:testDebugUnitTest :app-user:testDebugUnitTest
  :backend:compileDebugKotlin :app-user:assembleDebug --console=plain`: **BUILD SUCCESSFUL**.
  App unit tests ran: 52 tests, zero failures/errors/skips, including 21 group tests.
  Backend compilation/tests were **UP-TO-DATE**; retained reports contain 38 passing
  tests including seven group repository tests. These backend tests were not rerun.
- Independent code review identified three issues (cached detail after access loss,
  stale owner actions on earlier pages, stale friend eligibility); fixes were reviewed
  again with no new blocker found. Focused tests cover the ViewModel regressions.
- Strict OpenSpec validation and `git diff --check` passed. New source/config files
  were also checked for whitespace errors separately because they are untracked.
- No connected Android device was available. Navigation stack behavior, TalkBack,
  rotation/process restoration and multi-account hosted flows were **not tested on
  devices**. Source review and JVM tests are not substitutes for those smoke checks.
- SQL was unchanged in this continuation and its earlier local checks were not rerun.

## LiveKit spike and stopping point (2026-11-14)

Added `supabase/tests/group_calls/server-sdk/` with an exact server SDK pin and lockfile
for `livekit-server-sdk@2.19.3`. `npm.cmd test` passed three offline contract checks:
five-minute signed media grants, explicit Unix-second removal cutoff serialization,
and provider errors remaining failures. HTTP is intercepted and credentials are public
test fixtures. This is a compatibility probe, not an implemented call service.

Task 6.1 remains incomplete. The user confirmed that no LiveKit test project exists;
ADB reported no connected devices. The existing spike requires Cloud cached/refreshed
token revocation and audio routing/foreground microphone/camera verification before
server integration. See `supabase/tests/group_calls/README.md` for versions, sources,
commands and the unverified cases. Calls, persistent PDF collaboration/export and the
remaining end-to-end handoff tasks are still pending (39 tasks). No hosted/provider/
device/PDF-export success is claimed.

## Apply preflight (2026-11-15)

Reselected this change and read the current status, apply instructions and all planning
artifacts. Progress remains 28/67; no additional task was marked complete.

- No LiveKit-named environment variables were present (values were not inspected).
- `adb devices` returned no connected devices.
- `npm.cmd test --prefix supabase/tests/group_calls/server-sdk`: all three offline
  contract tests passed again. This does not verify Cloud revocation or actual media.
- `openspec.cmd validate study-groups-and-member-invitations --strict --no-interactive`
  passed.

Task 6.1 is still blocked on the existing Cloud/device acceptance gate. Application
code, dependency pins and schema were not changed. Continuing requires test
infrastructure or an explicit planning revision that separates local implementation
from the deferred provider/device acceptance checks; no gate was silently removed.

## Documents and LiveKit Cloud probe (2026-11-17)

Reselected the change at 28/67. Earlier on 2026-11-17 the document migration, Edge
Functions, Kotlin data layer and the app's PDF building blocks had been added but not recorded.
This session verified that work, added the document UI and ran the LiveKit Cloud probe.
Progress is now 41/67. Nothing was deployed, committed or archived.

| Tasks | Implementation and evidence |
| --- | --- |
| 9.2–9.5 | `20261117090000_group_documents.sql`: private `group-documents` bucket and Storage/Realtime policies, upload reservation → server validation → finalize, CAS annotation mutations with op ledger, snapshots, ordered deltas, channel epochs. 26 tests in `supabase/tests/group_documents/run_local.py` passed (14 group regression + 12 document), using local stand-ins for `storage.objects`/`realtime.*`. |
| 9.3 | Edge Functions `group-document-finalize` and `group-document-cleanup` with `pdf-lib@1.17.1` (MIT) server validation; 11 Node tests passed (limits, 100/101 pages, near-20 MiB, encrypted/corrupt input, forged sessions, cleanup confirmation). |
| 9.7 | Backend document DTOs, repository and `DocumentSyncController`; decode tests use fixtures captured from local RPC results (`--write-fixtures`). |
| 10.1–10.4 | `DocumentLibraryFragment` (list, upload with resumable request ID, version history, new version, archive) and `DocumentViewerFragment` (off-main-thread page rendering, page navigation, zoom/rotate, pen/highlight/note/select, colors, delete, undo, save status, conflict choice, newer-version banner). Realtime invalidation plus 5 s foreground polling. Private cache/journal per account, cleared on account switch/logout and on access loss. Group detail opens the library. |
| 10.6–10.7 | `AndroidDocumentExporter`: committed export snapshot only; ~150 dpi page bitmaps capped at 4 MP; overlay through the shared `PageTransform`; numbered pins plus a note appendix paginated on line boundaries; streamed into `FlatPdfWriter`. Pending-work choice (sync first / saved only), progress and cancel, Storage Access Framework destination, permission recheck before publish, and removal of temporary/partial files on failure, cancel or revoke. The UI states the flat-PDF limitations. |
| 11.1–11.2 | Covered by the SQL suites above and the backend controller/repository tests (crash after commit before ACK, CAS draft retention, duplicate/reordered rows, snapshot paging during edits). |

Checks run in this session:
- `python supabase/tests/study_groups/run_local.py`: 14 tests OK.
- `python supabase/tests/group_documents/run_local.py`: 26 tests OK.
- `npm.cmd test` in `supabase/tests/group_documents/edge`: 11 passed.
- Gradle `:backend:testDebugUnitTest :app-user:testDebugUnitTest :backend:compileDebugKotlin :app-user:assembleDebug`: BUILD SUCCESSFUL. App: 57 tests, 0 failures, including 5 new `DocumentViewModelsTest` cases (upload request reuse, access loss/account switch cleanup, committed-only export, cancel/revoke never publishes, pin/appendix numbering). `kotlinx-serialization-json` (already in the catalog) was added to app-user **test scope only**.
- LiveKit Cloud probe: 3/3 passed against the user's test project. Results are in `supabase/tests/group_calls/README.md`; credentials were kept outside the repository.

Not verified / still open:
- No Android device: viewer gestures, rendering quality, SAF export, rotation and TalkBack
  were not exercised on a device. 11.4/11.5 (visual and near-limit export memory) and 11.6 remain open.
- 9.1 (Android PdfRenderer/PdfDocument spike on a device) and 9.6/10.5 (guidance and call
  controls, which depend on calls) remain open. 11.3 has only fake-signal controller tests; no hosted Realtime.
- `supabase/schema.sql` has not yet been synchronized with the document migration.
- Calls (6.x–8.x): Cloud revocation semantics are now verified, but 6.1 still needs device
  checks (audio routing, foreground service). The 6.2–6.6 server implementation has not been started.

## Call server 6.2–6.6 (2026-11-17)

The user asked to continue with 6.2–6.6. Progress went from 41/67 to 45/67 (6.2, 6.3, 6.4 and 6.6 done).
6.5 stays open for one hosted measurement. Nothing was deployed, committed or archived.

| Task | Implementation and evidence |
| --- | --- |
| 6.2 | `20261118090000_group_calls.sql`: one open call per group (partial unique index), request keys, admissions with opaque identity, membership generation, 60 s reservation and capacity 6, `revoke`/`end_room` jobs, webhook dedup. Lock order group → call → admission. Tables have no client grants; `service_*` functions are service_role only. The migration is mirrored into `schema.sql` as a guarded block, checked in a disposable DB (runs twice, grants correct). |
| 6.3 | `group-call` Edge Function: actor from verified JWT (body user/room fields ignored); admit → create room after commit → activate → sign token (one room/identity, mic/camera only, 5 min) → re-check → return. The member read is the RPC `get_study_group_call` (no secret is needed, so it is cheaper than an Edge endpoint for 10 s polling). SQL tests cover concurrent start, duplicate device session (C0002) and full room (C0001). |
| 6.4 | Worker with leased jobs, exponential backoff, partial-completion fencing, 30 s reconciliation, 60 s empty-room cleanup, stuck `starting` → failed; signed webhook handler. Ending refuses joins before provider shutdown, and a call is marked `ended` only after revoke + delete are confirmed. Stale or duplicate webhooks never reopen an ended call (SQL test). |
| 6.6 | `GroupCall` DTOs, `GroupCallRemoteDataSource`, `GroupCallRepository`, `CALL_*` error types and C0001–C0004 mapping. Decode tests use fixtures captured from local RPCs; join tokens are redacted from `toString()`, never logged, and discarded for an earlier session. |

Design adjustment: the planned `get-group-call` Edge endpoint became the authenticated RPC
`get_study_group_call`, since it needs no LiveKit secret; start/join, leave and end remain
server-side Edge actions. Cloud probing also showed that RemoveParticipant with a cutoff works for
absent identities and nonexistent rooms. end_room therefore revokes every admission identity
(connected or not) before DeleteRoom, without recreating the room.

Checks: SQL 35 tests OK (groups + documents + calls in one disposable DB); Edge 12 passed;
LiveKit Cloud 6 passed (production adapter + `drainJobs`: end_room revoked a connected identity
and a never-joined cached token, then deleted the room, and neither token could rejoin; revoke
disconnected in 104 ms); backend `GroupCallRepositoryTest` 5/5. Also fixed a pre-existing
startup race in the shared SQL runner (it now waits for the final postgres after initdb).

Open: 6.5 needs the hosted pg_net wake + 5 s scheduler latency for removals made via PostgREST;
6.1 and 7.x need a device; real webhook delivery and hosted grants are unverified.

## Android calls 7.1–7.4 (2026-11-17)

The user asked to continue with 7.1–7.4. Progress went from 45/67 to 49/67. Nothing was deployed, committed or archived.

| Task | Implementation and evidence |
| --- | --- |
| 7.1 | `livekit-android` 2.30.0 in the version catalog (JitPack restricted to audioswitch). The manifest declares microphone/camera/audio/Bluetooth/notification permissions, a `microphone` foreground service, and removes the SDK's MediaProjection permission and ScreenCaptureService. This was verified in the merged manifest. Permissions are requested independently at runtime. |
| 7.2 | `CallSessionManager` (process-wide, application context only): one call per app instance; rotation and opening the group PDF re-attach to the same session. Media is released on leave, server removal/room close, logout and account switch (before the next account is shown). Camera pauses in the background and resumes only if permission is still granted. The `CallService` notification offers return/leave; START_NOT_STICKY, and a restored screen after process death offers "Vào lại" instead of rejoining automatically. LiveKit sits behind `CallEngine` for testability; the join token is used once for `connect` and never stored. |
| 7.3 | `GroupCallFragment`/`GroupCallViewModel`/layouts: participant grid (TextureViewRenderer, renderers released with the view), mic/camera/switch camera/audio route, leave, and end-for-all with confirmation (owner/initiator only). Voice starts with the camera off. Denied camera keeps audio and denied microphone keeps listening; a retry happens only through a user tap, with a settings hint when permanently denied. Tokens now carry the member's public display name for tiles (SQL + Edge + adapter updated). |
| 7.4 | Group detail call banner: none/active (n/6, initiator)/full/ending/"you are in this call" states, start/join audio or video, and return to the call. Polls every 10 s only while STARTED; overlapping polls are coalesced; rapid taps cannot open two call screens; no background ringing. |

Checks: `:app-user:assembleDebug` BUILD SUCCESSFUL with LiveKit; 7 new JVM tests in
`CallSessionManagerTest` (single session/re-attach, denied permissions, background camera pause
and permission revocation, leave/removal/account switch releasing media and the service, server
and provider failures never connecting, end-for-all/reconnecting states, banner polling and
capacity). The call SQL suite (9) and Edge tests (12) were re-run after the display-name change,
and the LiveKit Cloud probe 6/6 (revoke job 351 ms).

Not verified: no Android device, so real capture, foreground-service behaviour on Android 14+,
audio routing, renderers, rotation/process death and multi-device media are untested (6.1, 7.5, 8.4).
