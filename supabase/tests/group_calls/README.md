# LiveKit compatibility spike

Task 6.1 remains incomplete until audio routing, foreground microphone/camera
behavior and Cloud token revocation are verified with test infrastructure.

Opt-in Android compile, using the app's unchanged minSdk 24 / targetSdk 37:

```powershell
.\gradlew.bat --no-configuration-cache -I supabase/tests/group_calls/android-sdk.init.gradle :app-user:assembleDebug
```

The probe pins LiveKit Android 2.30.0 and adds JitPack only for its pinned audioswitch
transitive module. It removes the library's screen-capture service and media-projection
permission from the merged probe manifest. This probe is not part of a normal app build
and does not connect to a room or start capture. Rebuild normally afterward to restore
the usual APK. Successful compilation is not proof of media or revocation behavior.

The server contract probe pins `livekit-server-sdk@2.19.3` in `server-sdk/package.json`
and its lockfile. Run the offline contract checks with:

```powershell
Push-Location supabase/tests/group_calls/server-sdk
npm.cmd ci --ignore-scripts --no-audit --no-fund
npm.cmd test
Pop-Location
```

On 2026-11-14 all three checks passed: signed five-minute participant JWT grants
restricted to microphone/camera, explicit cutoff serialized as Unix seconds with
room-admin authorization, and a provider failure propagated as a failure. Requests
are intercepted locally and use public test fixtures, never configured credentials.
These checks do not prove that Cloud enforces the grants or revokes tokens.

Before server integration, validate
`removeParticipant(room, identity, { revokeTokenTs })` against the Cloud test project:
the cutoff is Unix **seconds**, bounded to 60 seconds of server time. Omission permits
a token-refresh window; DeleteRoom alone is insufficient. Cover cached and refreshed
tokens, late issuance, ended-room recreation, retries and membership generations.
Cloud documents a ten-minute retention period for the revocation cutoff. The real
test must include the refreshed-token horizon and cleanup after that window; do not
treat one successful disconnect or DeleteRoom response as final revocation evidence.

Sources inspected 2026-11-14:
- https://github.com/livekit/client-sdk-android/tree/v2.30.0
- https://docs.livekit.io/reference/server-sdk-js/types/RemoveParticipantOptions.html
- https://docs.livekit.io/intro/basics/rooms-participants-tracks/participants/#setting-an-explicit-revocation-cutoff

No LiveKit configuration was found in repository configuration or process environment.
Use a server-side test configuration; never put the API secret in Android or commit it.
The user confirmed on 2026-11-14 that no LiveKit test project is available. ADB also
reported no connected devices. Cloud revocation, audio routing and foreground capture
remain blocked/unverified; task 6.1 is intentionally unchecked.

## Cloud revocation probe (2026-11-17)

`cloud-probe/` pins `livekit-server-sdk@2.19.3` and `@livekit/rtc-node@1.2.1` (lockfile
included) and connects real participants to the LiveKit Cloud test project. Credentials are
read only from the process environment (kept outside the repository); never commit them.

```powershell
Push-Location supabase/tests/group_calls/cloud-probe
npm.cmd ci --ignore-scripts --no-audit --no-fund
# set LIVEKIT_URL / LIVEKIT_API_KEY / LIVEKIT_API_SECRET for this process only
npm.cmd test
Pop-Location
```

Each run creates fresh `probe-<uuid>` rooms and deletes them afterwards. Results of the
first run on 2026-11-17 (3/3 tests passed):

| Check | Observed |
| --- | --- |
| Screen-share publish with a microphone/camera-only grant | Publish never completed (10 s); server saw only the microphone source |
| RemoveParticipant with `revokeTokenTs` | Disconnected with PARTICIPANT_REMOVED in 476 ms |
| Reconnect with the cached pre-cutoff token | Rejected: `401 Unauthorized - invalid token: revoked` |
| New token issued after the cutoff | Accepted: DB admission must stop issuance after removal |
| RemoveParticipant for an identity that holds a token but never joined | Accepted; that token was later rejected as revoked |
| Cached token of a non-revoked identity after DeleteRoom | Accepted, so DeleteRoom alone is insufficient |

Consequences for 6.2–6.5: end-room and membership loss must call RemoveParticipant with a
cutoff for **every admitted identity** (connected or not) before DeleteRoom, and admission
must be refused in the database before any new token is signed.

Still unverified: the refreshed-token horizon/ten-minute cutoff retention (the probe cannot
read SDK-refreshed tokens), provider failure/retry behaviour, and all Android-side checks
(audio routing, foreground microphone/camera service). No device is connected, so task 6.1
stays unchecked.

## Call server (tasks 6.2–6.6, 2026-11-17)

Implementation:
- `supabase/migrations/20261118090000_group_calls.sql`: calls, request keys, admissions (opaque
  identity, membership generation, 60 s reservation, capacity 6), durable `revoke`/`end_room` jobs
  and webhook deduplication. Membership removal, leave, ban and archive revoke admissions and
  enqueue jobs **in the same transaction**. No client access to tables; `service_*` functions run
  only as `service_role`; members read the call through `get_study_group_call`.
- `supabase/functions/group-call` (start_or_join / leave / end), `group-call-webhook`,
  `group-call-worker`; handlers in `_shared/group_calls.ts`; LiveKit adapter in
  `_shared/livekit_provider.ts` (`livekit-server-sdk@2.19.3`). Token: one room and identity,
  microphone/camera only, 5 min TTL, returned only after a post-signing membership re-check.
- Kotlin `GroupCallRepository` / `GroupCallRemoteDataSource` / `GroupCall` DTOs. The token is
  kept in memory only, and `toString()` redacts it.

Deployment (not done; needs an operator with project access):
1. Apply the migration (it is also mirrored as a guarded block at the end of `schema.sql`).
2. Function secrets: `LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`,
   `GROUP_CALL_WORKER_SECRET` (≥ 32 random characters). Never put them in Android or the repository.
3. Deploy `group-call` with JWT verification; deploy `group-call-webhook` and `group-call-worker`
   with `--no-verify-jwt` (signature / shared secret are checked in code).
4. In LiveKit Cloud, set the webhook URL to `<project>/functions/v1/group-call-webhook`.
5. Post-commit wake: enable `pg_net` and store Vault secrets `group_call_worker_url`
   (worker URL) and `group_call_worker_secret`. Without them `call_wake()` is a no-op.
6. Fallback scheduler every 5 s (pg_cron ≥ 1.5 supports second intervals), for example:
   `select cron.schedule('group-call-worker', '5 seconds', $$select net.http_post(...)$$);`
   using the same URL/secret from Vault.

Verification (this session):
- `python supabase/tests/group_calls/run_local.py`: 35 tests OK (14 group + 12 document + 9 call):
  admission/projection/grants, capacity 6 and reservation expiry, concurrent start (two DB
  sessions), removal/admission races in both commit orders, generation fencing after rejoin,
  ban/archive/transfer authority, ending blocks joins, job lease/backoff/partial completion,
  presence/empty-room cleanup, webhook duplicate/stale/no-reopen.
- `npm.cmd test` in `edge/`: 12 passed (fake DB/provider: forged JWT/body fields, provider failures
  release admissions and never return tokens, confirm failure discards the token, end drains
  revoke + delete before confirming, signed webhooks only, worker secret). The adapter is checked
  with the real SDK offline (token grants/TTL, webhook signature over the exact body).
- `cloud-probe` (`npm.cmd test` with credentials): 6 passed on LiveKit Cloud, including the
  production adapter and `drainJobs`. The end_room job revoked a connected identity and a
  never-joined cached token, then deleted the room; neither token could rejoin and the room was
  not recreated. The revoke job disconnected in 104 ms. RemoveParticipant with a cutoff is
  accepted for rooms that do not exist; DeleteRoom of a missing room returns 404 (treated as done).

Not verified: hosted deployment, real webhook delivery to a deployed endpoint, and the
pg_net wake/5 s scheduler latency for removals made through PostgREST (task 6.5 stays open
for that measurement). Android media (6.1, 7.x) needs a device.

## Android calls (tasks 7.1–7.4, 2026-11-17)

LiveKit Android `2.30.0` is now a regular `app-user` dependency (`libs.livekit.android`).
JitPack is enabled in `settings.gradle.kts` only for `com.github.davidliu:audioswitch`. The opt-in
`android-sdk.init.gradle` spike is therefore obsolete; it is kept only as history.
The merged debug manifest was checked: RECORD_AUDIO, CAMERA, MODIFY_AUDIO_SETTINGS,
BLUETOOTH_CONNECT (and BLUETOOTH ≤ API 30), FOREGROUND_SERVICE(_MICROPHONE) and
POST_NOTIFICATIONS are declared. There is no FOREGROUND_SERVICE_MEDIA_PROJECTION and no
ScreenCaptureService, and `CallService` has `foregroundServiceType="microphone"`.

Not verified on a device (none connected): actual capture/publish, the Android 14+
foreground-service start, audio routing with real headsets/Bluetooth, renderer
lifecycle, rotation and process-death behaviour. These belong to 6.1, 7.5 and 8.4.
