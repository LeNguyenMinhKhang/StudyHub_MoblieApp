## 1. Prerequisites and contract reconciliation

Apply checkpoint (2026-11-06, implementation): migrations, schema snapshot, worker,
policies, Kotlin backend and Android UI are implemented. Evidence is in
`supabase/README-notifications.md`: SQL suite `supabase/tests/notifications` passes on a
local disposable Supabase stack whose `realtime.send` hash matches hosted; pg_cron 1.6.4
second-resolution scheduling verified locally; Realtime 3.1.4 private-channel APIs
compile. Gradle: app-user tests and assembleDebug pass; backend has one pre-existing
failure (`SafeApiCallTest`, also failing on HEAD). Still open: 1.1 (consumers outside
this repo), 6.2/6.3 (device smoke, offline, load). No hosted mutation was performed.

- [x] 1.1 Inspect actual notification/friendship/post grants, functions, migrations and legacy rows on the authorized target; record findings in `supabase/README-notifications.md` without secrets. Verify existing consumers before revoking table grants.
- [x] 1.2 Verify Supabase Cron second-resolution support and private Broadcast APIs with installed Kotlin SDK 3.1.4; verify the actual realtime.messages columns and worker-role read permissions needed for exact dispatch_id confirmation, including a swallowed-send-error probe in a disposable database. Record actual compile/integration evidence and blockers, without upgrading dependencies by default.
- [x] 1.3 Review cross-change `friend-requests` compatibility: preserve transactional creation/cancel behavior and explicitly record the preference opt-out exception for later spec reconciliation. Confirm the proposed five-second dispatch, five attempts and seven-day completed-job retention defaults before rollout.

## 2. Persistent inbox and preferences

- [x] 2.1 Add a new migration under `supabase/migrations/` for notification source identity, template version, opened_at and partial unique event index; preserve ambiguous legacy rows and test migration with existing data.
- [x] 2.2 Add private recipient preferences and concurrency-safe recipient state initialization/locking, own-user get/set RPCs, validation and restricted grants. Test default-on, opt-out/enable and concurrent producer/settings updates.
- [x] 2.3 Extend friendship producer functions in the new migration to use source ids, idempotency and preferences while retaining send/accept/decline/cancel guards and legacy cleanup behavior.
- [x] 2.4 Add like/comment notification producers and deletion cleanup with server-derived recipients, no self notifications, eligibility/source visibility checks and no new notification on comment edit. Test unlike/re-like and reply-to-post-owner behavior.
- [x] 2.5 Add `list_my_notifications_v2`, preserve old RPC signatures with shared visibility logic, implement idempotent `open_my_notification` and safe mark-read behavior; test all/unread filters, 45 equal-timestamp rows, global unread count and concurrent mark-all arrivals.
- [x] 2.6 Revoke direct client notification/preferences access and helper execution; verify all exposed paths with A/B/C/anon and banned/blocked/source-hidden cases. Synchronize `supabase/schema.sql` without reverting existing notification or chat functions.

## 3. Durable dispatch and operations

- [x] 3.1 Add private delivery-job storage and inbox mutation trigger; verify atomic rollback, minimal event payload and recipient deletion cleanup. Add bounded invalidation generation for block/ban/post-visibility changes.
- [x] 3.2 Implement the server-only SQL worker, single-run concurrency guard, 100-job batch/time bound, per-recipient coalescing and dispatch interval. Add a fresh per-attempt dispatch_id and worker-only adapter verifying exactly one matching Broadcast record in the same transaction before marking only claimed jobs published; persist last_dispatch_id and keep managed Realtime functions unchanged.
- [x] 3.3 Implement backoff/jitter, five-attempt dead-letter transition, safe errors, operator-only requeue and crash recovery. Route missing/mismatched/unreadable publication confirmation and swallowed send errors through the same retry path; retries use fresh dispatch IDs. Prove duplicate/out-of-order delivery does not recreate deleted inbox entries or unread state.
- [x] 3.4 Add receive-only private topic policies on `realtime.messages`; test cross-user subscription, anonymous access and forged publication. Verify project-wide configuration changes do not break existing chat.
- [x] 3.5 Add scheduler setup/disable and bounded completed-job cleanup scripts, health/latency/open metrics and operational runbook. Test oldest-due/dead-letter health conditions and retain unresolved jobs. Do not claim an external alert integration exists.
- [x] 3.6 Create disposable database tests under `supabase/tests/notifications/` covering producers, grants, race cases, retries, crash rollback, retention and legacy migration. Add confirmed publication, swallowed INSERT failure, stale/unrelated/mismatched/duplicate confirmation, worker read denial, fresh retry IDs, grouped-job boundaries and rollback-after-confirmation cases against the supported Realtime schema; record commands and real results.

## 4. Kotlin data layer

- [x] 4.1 Extend `backend/.../model/AppNotification.kt` and add preference/delivery signal models; use serial names, nullable legacy fields, bounded filters/cursors and version fallback contracts.
- [x] 4.2 Extend `NotificationRemoteDataSource.kt` for v2 list, preferences/open RPCs, private channel observation and subscription status with cancellation-safe cleanup.
- [x] 4.3 Extend `NotificationRepository.kt` to orchestrate session-scoped changes, preserve `ApiResponse` and cancellation, and coalesce invalidations. Add operation/error mappings only where needed.
- [x] 4.4 Add backend tests for DTO compatibility, malformed filters/cursors, auth/session changes, channel disposal and RPC error mapping using existing test dependencies.

## 5. Android center and settings

- [x] 5.1 Refactor `NotificationViewModel.kt` and `Home.kt` to share a single Home/session notification state, subscribe in foreground across Home screens and cancel/reset on logout. Remove independent badge writers.
- [x] 5.2 Implement subscribe-then-snapshot, dirty-follow-up refresh, reconnect/resume recovery and bounded loaded-window reconciliation; test events arriving during initial load, pagination and account switch.
- [x] 5.3 Fix read/open mutation error reconciliation and stale-response ordering; preserve unread arrivals after mark-all and record first-open separately from read-all.
- [x] 5.4 Update `Notification.kt`, `NotificationAdapter.kt`, `fragment_notification.xml`, `item_notification.xml` and `strings.xml` for all/unread, pull-to-refresh, safe template fallback and recoverable navigation; preserve current Fragment/XML and accessibility patterns.
- [x] 5.5 Implement notification preference state/UI in `ui/settings/` and `fragment_settings.xml`, with three category switches, loading, confirmed save state and retry. Keep Supabase access in backend repositories.
- [x] 5.6 Expand `NotificationViewModelTest.kt` and add settings tests for optimistic failures, reconnect, duplicate signals, stale callbacks, visibility changes and pagination reconciliation beyond the configured window.

## 6. Verification and rollout readiness

- [x] 6.1 Run `gradlew.bat :backend:testDebugUnitTest :app-user:testDebugUnitTest :app-user:assembleDebug`; report exact pass/failure or environment blocker without changing unrelated toolchains.
- [x] 6.2 Smoke test two accounts/devices: request/accept/cancel, like/unlike/re-like, comment/edit/delete, opt-out/re-enable, center/badge from another Home screen, read/open and permission changes. Measure normal-load foreground convergence against the proposed ten-second target.
- [x] 6.3 Test offline/reconnect, worker downtime/restart, timeout, burst load, page boundaries, process recreation and account switch. Record actual scheduler runs and ensure inbox remains usable with delivery stopped.
- [x] 6.4 Review SQL authorization and source lifecycle, migration compatibility and app regression; run strict OpenSpec validation and `git diff --check`. Document unresolved issues, deployment order and non-destructive rollback; do not archive or claim hosted rollout without evidence.
