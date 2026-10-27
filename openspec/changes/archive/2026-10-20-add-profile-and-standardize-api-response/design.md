## Context

`app-user` and `app-admin` are Kotlin Android applications. `backend` is an Android library using Supabase Auth/PostgREST, not a server. Profile UI currently inflates a placeholder XML. Home replaces child fragments without a detail back stack and reads drawer text from activity-scoped AuthViewModel. AuthRepository repeats broad catches and string-based error parsing; sign-out UI ignores its result. Profile already uses non-null Kotlin Strings for id, username and displayName, but database constraints are not verified. No SQL migrations were found during investigation.

## Goals / Non-Goals

**Goals:** Consistent typed results and diagnostics; reliable registration/profile integrity; usable own/public profiles and editing; durable account-isolated local avatars; testable failure and navigation behavior.

**Non-Goals:** Spring controllers, changing Supabase wire formats, server avatar upload, full friends/feed/search features, camera/crop UI, toolchain upgrades, production deployment, and rewriting unrelated Auth behavior.

## Decisions

### 1. Typed results at repository boundaries

Replace Resource with sealed ApiResponse.Success<T> and Failure. Success carries data and traceId; Failure carries ApiError (ErrorType, fieldErrors) and traceId. Unit represents successful no-payload operations. Loading remains a UI-state property. Explicit session/registration outcomes distinguish signed-out, authenticated and confirmation-required states; a missing profile is distinct from no session.

ErrorType is an immutable enum with stable external code, safe Vietnamese default user message, technical log template, severity and retryable flag. Include validation, invalid credentials, email confirmation, unauthenticated, account banned, permission denied, username conflict, missing profile, network unavailable, timeout, rate limit, invalid image, storage full, avatar save and unknown error categories. Per-call metadata and Throwable never live on enum instances or UI response payloads.

ExceptionMapper prioritizes structured provider codes/types supported by installed SDK versions. SafeApiCall owns mapping/logging at the repository boundary and rethrows cancellation. Nested helpers do not independently log the same failure. ErrorLogger accepts allowlisted operation names, trace IDs, duration and sanitized diagnostics; neither raw request/response bodies nor raw exception messages/stack traces are automatically emitted. Diagnostic sinks must remove credentials, paths/URIs and personal payloads. A process-wide uncaught-exception hook cannot recover ordinary suspend calls and is not used for this contract.

Alternative: nullable data/error in a universal response bean permits invalid states. Keeping Resource alongside ApiResponse permanently would leave two contracts; migrate all consumers in one coherent slice instead.

### 2. Database-owned profile integrity

Inspect the connected development schema, triggers, grants and RLS before authoring migrations. Require id linked to auth.users, required trimmed names, database timestamp defaults and unique username semantics. Proposed new/changed usernames are 3-30 ASCII letters/digits/underscores; uniqueness and exact lookup are case-insensitive. Display names are 1-80 characters after trim and bios at most 500 characters. Audit existing values and case collisions before enforcement; never synthesize replacement identities. A legacy username may remain unchanged while other fields are edited, until an explicit remediation plan is approved. Null/blank legacy required fields block constraint rollout until remediated.

Use a narrowly scoped database trigger on Auth user creation to validate username/display-name metadata and insert exactly one profile atomically with database-owned role, ban flag and creation timestamp. Inspect/reuse an existing trigger rather than layering duplicates. Harden privileged function search_path/qualification and execution permissions. Android removes direct profile insertion once this flow is deployed. Trigger failure must fail registration consistently rather than silently leave a partial account. Both email-confirmation modes are tested.

RLS enforces own-row updates. Column grants or a constrained database write interface also protect administrative fields; own-row RLS alone does not protect role/is_banned. Proposed public-read policy allows authenticated users to read the approved public projection of non-banned profiles. Do not expose administrative columns through a broadly readable table just because UI selects fewer columns. Confirm a safe projection/view or equivalent permission design against actual schema before applying it.

Alternative: create profiles from the Android session after sign-up fails with confirmation-required flows and is vulnerable to interrupted clients. A server trigger makes provisioning transactional. Deployment compatibility with older clients remains a migration gate.

### 3. Focused Kotlin data and presentation layers

Add ProfileRemoteDataSource, ProfileRepository, PublicProfile and UpdateProfileRequest in backend. Updates include only username, displayName and explicit nullable bio; obtain the owner ID from the current session, not a caller-selected target. Verify returned updated data; zero updated rows are not success. Auth reads reuse the same profile read implementation.

Rename the UI Profile class to ProfileFragment to distinguish it from the model. Add ProfileViewModel/ProfileUiState, EditProfileFragment/EditProfileViewModel/EditProfileUiState and ProfileValidator. Use StateFlow and view-lifecycle-aware collection. Scope state to the screen and use saved state for draft text and draft-file references, never bitmap bundles. Missing staged files after process recreation produce a recoverable reselect state.

Use ProfileFragment for both owner and public views, and a small ProfileLookupDialogFragment for exact username entry. This gives a real entry point while Friend/Feed remain placeholders. Do not add social counts or inactive action buttons.

### 4. Account-scoped local avatars

LocalAvatarDataSource and AvatarRepository in backend own storage with application context only. Use the system photo picker, stream input with a 10 MB cap, inspect dimensions before bounded decoding, correct orientation, downsample to a maximum 1024-pixel edge and re-encode. Validate actual decodability, not only MIME/extension. Use generated file names under controlled account directories, not raw user input. Use app no-backup storage for committed avatars and durable staged drafts so device-only images do not enter automatic backup. Retain committed avatars across logout for the same account; never consult another account's local files when viewing its public profile. App-data clearing/uninstall removes local state; no cross-device restoration is promised.

Persist selection mode: remote/default, local image, or hidden-on-device. Removal changes the mode to hidden so an old remote URL does not unexpectedly reappear. Do not write local paths into avatar_url. Existing remote URLs remain usable through a lifecycle-safe image loader with placeholders and failure fallback. Verify dependency availability first; if needed add one narrowly scoped image loader and orientation support with versions compatible with the current catalog, without upgrading the toolchain.

Stage and verify the replacement before committing its reference; only delete the former file after the new reference is durable. Keep a small recovery record so interrupted commits resolve to a complete old or new image on next access; clean unreferenced drafts after bounded retention, excluding active drafts. Never remove the gallery source file.

### 5. Partial saves and concurrent account changes

Text-only edits call remote update; avatar-only edits use local storage without a remote write. Combined edits stage the image, update remote text, then commit the avatar. Remote failure preserves the old avatar and draft. If text succeeds and avatar commit fails, update the text baseline and shared profile immediately, keep the draft, report partial success and retry only the remaining avatar work. Do not claim atomicity across network and filesystem. Process restart reloads server text and the last committed avatar; abandoned drafts are recoverable or safely discarded.

Every operation captures the owning account and draft revision. Ignore stale completions after account changes or newer requests; serialise per-account avatar commits and prevent duplicate save taps. A save started for A must never update B's drawer.

### 6. Navigation and shared presentation

Keep Home.childFragmentManager, adding explicit root versus detail navigation. Opening edit/public profile pushes detail history; selecting a drawer root clears detail history. Route Back and drawer-root navigation through unsaved-draft handling. Close an open drawer first, then handle draft confirmation/detail pop. Persist/restore fragment state without duplicate initial navigation.

After a successful own-profile update, update AuthViewModel's profile snapshot only when IDs match; do not call checkSession merely to refresh presentation. Home and Profile observe the avatar source/revision so an image-only save refreshes both. Remove drawer image tint for actual photos. Sign-out failure is surfaced instead of unconditionally reporting success; transient session refresh failures do not fabricate an invalid session.

## Risks / Trade-offs

- Unverified schema and existing users -> inspect development database; block only dependent migrations until evidence and remediation are available.
- Older clients still insert profiles -> coordinate minimum client rollout or a tested compatibility window; never enable duplicate writers blindly.
- Public profile privacy -> approve minimal authenticated visibility and verify both row and column access with direct API attempts.
- Images are device-only -> label selection/removal accordingly and document remote-avatar fallback.
- Network/filesystem cannot commit together -> explicit partial outcome and retry, with interruption recovery tests.
- Shared backend API changes -> migrate every consumer and compile both Android apps.
- New dependencies might be needed for image/test support -> justify exact additions; preserve SDK/toolchain versions.

## Migration Plan

1. Record baseline builds, actual schema/policies, trigger inventory and invalid/colliding profile rows in a development environment without exporting sensitive records.
2. Implement and test common result/error infrastructure, then migrate Auth consumers together.
3. Prepare version-controlled SQL migrations and validate provisioning/authorization in development; review schema-sensitive choices before application. Coordinate trigger and Android direct-insert removal with legacy-client behavior.
4. Implement Profile repositories, UI, local avatars and Home synchronization in dependency order.
5. Run unit, device and database acceptance tests; inspect release logs and build app-user/app-admin. No production migration or deployment is part of artifact generation.
6. Rollback application changes as a coherent contract slice. Retain valid profiles and avatar files; do not delete user data to roll back. Restore previous provisioning only with a matching client strategy and tested SQL, not by simply dropping the trigger while new clients depend on it.

## Open Questions

- Actual database constraints, policies, existing triggers, grants and legacy-client population are unknown. Resolve during the first implementation task; final SQL and rollout timing depend on this evidence.
- Confirm the proposed authenticated-only public visibility and handling of legacy usernames against product/data ownership requirements before enforcing database policy.
- Select image-loading/orientation and coroutine test support only after inspecting resolved dependencies. No specific new dependency is assumed installed.
