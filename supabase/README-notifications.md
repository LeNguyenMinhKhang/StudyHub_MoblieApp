# Notification center & realtime delivery

Change: `notification-center-and-realtime`. In-app channel only (no FCM/APNs/SMS/email).

## Status (2026-11-06)

| Area | State |
| --- | --- |
| SQL migrations `20261106120000_notification_center.sql`, `20261106120100_notification_delivery.sql` | Implemented; verified on a **local disposable** Supabase stack |
| `supabase/schema.sql` | Section 24 mirrors both migrations (applies cleanly with `psql -1`) |
| Kotlin backend + Android UI | Implemented; unit tests pass; `:app-user:assembleDebug` builds |
| Hosted database | **Not migrated.** No hosted mutation was performed by this change |
| Scheduler | Verified locally only. Hosted `pg_cron` 1.6.4 is available but **not installed** |
| Two-device smoke / offline / burst tests (tasks 6.2, 6.3) | **Not performed** |

## Architecture

```text
send/accept/cancel friend RPC, likes/comments INSERT/DELETE
  -> same transaction: eligibility + preferences (per-recipient lock) + inbox row
     -> statement trigger: one delivery job per affected recipient
pg_cron (5 s) -> studyhub_private.dispatch_notifications()
  -> advisory lock, <=100 due jobs, 3 s budget, coalesce per recipient, 5 s/recipient interval
  -> publish_notification(): realtime.send(...) + confirm exactly one matching
     realtime.messages row for a fresh dispatch_id, in the same subtransaction
  -> published | retry (5/10/20/40 s + <=20% jitter) | dead_letter after 5 attempts
Android: private channel notifications:<uid> -> "re-read" -> list_my_notifications_v2 RPC
```

- Inbox rows carry `source_kind`, `source_id`, `event_key = <type>:<source_id>` (partial unique
  index per recipient), `template_version`, `opened_at`. Legacy rows keep NULL source data; no
  link is guessed from actor/recipient pairs. Legacy friend-request cleanup (accept/decline/cancel
  matching actor+recipient with `source_id IS NULL`) is preserved.
- Broadcast payload is only `{schema_version, dispatch_id}` (plus the `id` that `realtime.send`
  adds). Content and counts always come from RPCs that re-check ban/block/post visibility.
- Post visibility mirrors `PostTypeUtils.parse` (`<category>:<public|friends|private>`); an actor
  who cannot see a post produces no notification for it.
- **BREAKING:** `anon`/`authenticated` lose all privileges on `public.notifications` and its
  client policies are dropped. Repo consumers (backend/app-user/app-admin) use RPCs only.

## Evidence

### Local disposable stack

Supabase CLI stack `studyhub-notification-tests` (images: postgres 17.11.0.006, realtime v2.147.1),
db container `supabase_db_studyhub-notification-tests`. Base built from `supabase/schema.sql`
(`psql -1`), then migrations in version order (`20261027` before `20261027150000`; the two that
fail on top of the snapshot only duplicate snapshot objects).

- Local `realtime.send(jsonb,text,text,boolean)` definition hash `77afe4a67a0330f080cb562cec0898bd`
  — **identical** to the hosted definition recorded in preflight, including the
  `EXCEPTION WHEN OTHERS THEN RAISE WARNING` that swallows INSERT failures.
- `realtime.messages` columns used by the adapter (`topic, extension, payload, event, private`)
  exist locally. The function owner `postgres` reads `realtime.messages` through direct grants
  **and** membership in `pg_read_all_data`, so revoking the direct grant alone does not remove
  access; read denial was therefore tested by reassigning the adapter to an unprivileged role.
- `pg_cron` 1.6.4 accepted `'5 seconds'`; two runs succeeded within 12 s and a queued job was
  published 9.6 s after enqueue (includes waiting for the first tick). `disable_scheduler.sql`
  removed both jobs; a job enqueued afterwards stayed `pending` (inbox unaffected).
- Kotlin Realtime 3.1.4 sources: `channel(id) { isPrivate = true }`, `broadcastFlow<T>(event)`,
  `status: StateFlow`, `subscribe(blockUntilSubscribed)`, `removeChannel`; the session access
  token is sent on join and refreshed on auth changes. Compiles in `:app-user:assembleDebug`.
  A device joining the private channel against a live project has **not** been exercised.

### SQL test suite — `supabase/tests/notifications/`

```bash
bash supabase/tests/notifications/run_local.sh            # refuses non supabase_db_* containers
```

Result on 2026-11-06: `PASS 01_center`, `PASS 02_delivery`, `PASS 03_concurrency` (exit 0; the
runner was also checked to exit non-zero on a failing assertion). Coverage:

- `01_center`: like/comment/reply/edit/delete producers, self/blocked/banned/private/friends-only
  suppression, idempotent retries, unlike/re-like incarnations, friendship send/accept/decline/
  cancel with source ids and legacy cleanup, preferences (defaults, validation, opt-out without
  failing the business action, no backfill, friendship opt-out), 45 equal-timestamp keyset
  pagination, filters, global unread count, cursor/filter validation, legacy RPC item shape,
  open/first-open idempotency, mark-all ≠ open, banned/blocked visibility, post deletion, and
  denied direct table/helper access for authenticated and anon.
- `02_delivery`: inbox+job atomic rollback, one job per recipient per statement, grouped confirmed
  publication, minimal payload, rate-limit deferral, swallowed send failure (WARNING only) → retry
  with fresh dispatch ids → dead-letter at 5 → alarm → operator requeue → published without inbox
  change, mismatched event/private flag, duplicate record, pre-existing correlation id, worker read
  denial, rollback after confirmation, 100-job batch bound, banned recipient skip, ban/block/post
  invalidations, recipient deletion cleanup, no inbox recreation, bounded retention, Realtime
  policies (own topic only, no cross-user/anon/banned receive, no client publish).
- `03_concurrency` (dblink, committed rows cleaned up): opt-out vs like in both orders (serialized
  by the recipient lock), concurrent duplicate producers → one row, mark-all vs concurrent arrival
  (arrival stays unread), overlapping workers (`{"locked": true}`).

### Gradle

`gradlew.bat :backend:testDebugUnitTest :app-user:testDebugUnitTest :app-user:assembleDebug --continue`

- `:app-user:testDebugUnitTest` — pass (31 tests, incl. 13 `NotificationViewModelTest`,
  5 `NotificationSettingsViewModelTest`).
- `:app-user:assembleDebug` — success.
- `:backend:testDebugUnitTest` — 31 tests pass (incl. 10 `NotificationRepositoryTest`).
  `SafeApiCallTest.unknown failures remain safe and separate per invocation` previously failed
  (also on `HEAD`, since `042b7ce` passed raw exception text to the UI as `detailMessage`);
  fixed on 2026-11-07 by no longer copying exception text into `ApiError`.

## Cross-change note (`add-friend-requests-and-friends-list`)

Its `friend-requests` requirement says a new pending request / acceptance SHALL create a
notification. With this change, a recipient who disabled the `friendship` category gets **no**
notification while the request/acceptance still commits. Reconcile that requirement with
`notification-preferences` when syncing/archiving the two changes; this change does not edit the
other change's artifacts. Transactional creation, repeat-send silence and cancel removal are kept.

## Runbook

All operator commands run as `postgres` (SQL editor / CLI), never from the app.

| Check | Query | Alarm |
| --- | --- | --- |
| Health | `select * from studyhub_private.notification_health;` | `alarm = true` (dead_letter > 0 or oldest_due_seconds > 60) |
| Scheduler | `select jobname, schedule, active from cron.job where jobname like 'studyhub-notifications%';` | missing/inactive after rollout |
| Recent runs | `select status, return_message, start_time from cron.job_run_details d join cron.job j using (jobid) where j.jobname = 'studyhub-notifications-dispatch' order by start_time desc limit 20;` | repeated `failed` |
| Failures | `select safe_error_code, count(*) from studyhub_private.notification_delivery_jobs where status in ('retry','dead_letter') group by 1;` | any `dead_letter` |

- Requeue after fixing the cause: `select studyhub_private.requeue_notification_jobs();`
  (all dead letters, max 100 per call) or `requeue_notification_jobs(array['<job id>'::uuid])`.
  Requeue never touches inbox rows; each retry gets a fresh `dispatch_id`.
- `broadcast_unconfirmed`: Realtime did not store exactly one matching message (swallowed send
  error, schema/partition change). `permission_denied` / `realtime_schema_*`: the adapter cannot
  read Realtime — fix the role/schema; do **not** widen client access.
- Manual run (testing): `select studyhub_private.dispatch_notifications();`
- Retention: hourly `cleanup_notification_jobs()` deletes ≤1000 `published`/`skipped` jobs older
  than 7 days. Pending/retry/dead-letter are never deleted. Inbox rows are never aged out.
- No external alert integration exists; health must be polled or wired up separately.

## Deployment order (authorized target only)

1. Confirm no consumer outside this repo reads/writes `public.notifications` directly (not yet
   verified — the migration revokes that access).
2. Back up / note current function definitions (`supabase/notifications/preflight.sql`, read-only).
3. Apply `20261106120000_notification_center.sql`, then `20261106120100_notification_delivery.sql`.
4. Run `supabase/tests/notifications` against a disposable copy, never the hosted project.
5. Ship the Android build (it falls back to the legacy list RPC if v2 is missing, "all" view only).
6. Install/enable `pg_cron`, then run `supabase/notifications/enable_scheduler.sql`; watch health.
7. Two-account smoke test (task 6.2) and the 10 s foreground convergence target.

### Hotfix 2026-11-07: `permission denied for function search_norm`

The first versions of both notification migrations ended with a schema-wide
`revoke all on all functions in schema studyhub_private ...`, which also removed the
EXECUTE grant unified search gives `authenticated` on `search_norm`/`search_file_norm`.
Those functions back expression indexes on `posts`, `profiles`, `subjects` and
`post_files`, so every app insert/update on those tables failed. The migrations now revoke
only their own helpers; databases that already ran the old versions need
`20261107090000_restore_search_norm_grants.sql`. Reproduced and verified locally (insert
fails after the old revoke, succeeds after the hotfix and after re-running the fixed
migrations); `01_center` asserts the grant survives.

## Rollback (non-destructive)

1. `supabase/notifications/disable_scheduler.sql` — stops dispatch; jobs and inbox are retained;
   apps keep working through RPC refresh on foreground/pull-to-refresh.
2. Producer/policy problems: ship a reviewed compensating migration (e.g. restore previous
   function bodies). Do not restore direct client grants on `notifications` and do not delete
   backlog.

## Remaining blockers / limitations

- Hosted rollout, hosted `pg_cron` install and hosted Realtime private-channel behaviour unverified.
- External (non-repo) consumers of `public.notifications` unverified (task 1.1).
- Two-device smoke, offline/reconnect, worker restart under load, process recreation (6.2, 6.3).
- Realtime authorization was exercised by emulating the Realtime server's checks in SQL
  (`role`, `request.jwt.claims`, `realtime.topic`), not through a live websocket.
- Collector-before-join on Android relies on coroutine ordering (`UNDISPATCHED` + `yield`); the
  subscribe-then-snapshot re-read covers any gap, but it has not been observed on a device.
