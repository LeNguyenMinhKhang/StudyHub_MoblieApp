## 1. Baseline and schema evidence

- [x] 1.1 Re-read project rules and relevant Kotlin/backend/testing skills; record current worktree and baseline app-user/app-admin build results without modifying unrelated work.
- [x] 1.2 Inspect development profiles schema, Auth triggers, grants/RLS and existing client insert flow; record only schema evidence and aggregate invalid/collision counts, not personal records.
- [x] 1.3 Resolve authenticated public visibility, legacy username remediation and old-client provisioning compatibility from design open questions before dependent SQL changes.
- [x] 1.4 Inspect resolved image, orientation, lifecycle and test dependencies; add only necessary catalog/module entries without toolchain upgrades.

## 2. Shared operation and error contract

- [x] 2.1 Add immutable ErrorType enum with stable codes, safe Vietnamese messages, log templates, severity and retryability covering Auth, profile, network and local image failures.
- [x] 2.2 Add ApiResponse and ApiError with typed success, Unit support, field errors and per-operation trace IDs; keep loading and exceptions outside public response payloads.
- [x] 2.3 Implement ExceptionMapper against the installed Supabase/Ktor APIs with structured-code precedence and safe unknown-error fallback.
- [x] 2.4 Implement SafeApiCall with cancellation propagation and one logging owner per operation; inject logger/clock/trace generation as needed for focused tests.
- [x] 2.5 Implement ErrorLogger with allowlisted context and sanitized diagnostics; verify no sensitive input or raw provider message reaches configured log sinks.
- [ ] 2.6 Add unit tests for error mapping, concurrent same-type errors, cancellation, field errors, safe fallback, log redaction and exactly-once logging.

## 3. Migrate authentication consumers

- [x] 3.1 Convert AuthRepository methods to ApiResponse and explicit session/registration outcomes; preserve missing-profile versus signed-out distinction.
- [x] 3.2 Update AuthViewModel and sign-in/sign-up/reset UI consumers; handle pending email confirmation without treating it as authentication or failure.
- [x] 3.3 Honor sign-out failure and preserve valid-session presentation on transient refresh failure; remove raw localizedMessage rendering and configuration advice from user errors.
- [ ] 3.4 Search both apps and backend for Resource consumers, migrate them, remove the old contract and compile consumers together.
- [ ] 3.5 Add Auth state tests for confirmation-required, no-session, missing-profile, refresh network error and failed sign-out outcomes.

## 4. Profile integrity and provisioning

- [x] 4.1 Prepare version-controlled SQL migrations from verified schema for Auth-linked required identity, non-blank names, case-insensitive username uniqueness and database timestamp defaults; document legacy remediation gates.
- [x] 4.2 Implement or adapt the transactional Auth-user profile trigger with validated metadata, database-owned administrative defaults and hardened privileges/search path.
- [x] 4.3 Implement reviewed own-write permissions and minimal authenticated public projection; ensure direct API attempts cannot update administrative fields or another owner.
- [x] 4.4 Remove Android's direct profile insertion in coordination with the tested trigger/client compatibility strategy; load the provisioned profile only when a session is available.
- [ ] 4.5 Verify development registration with confirmation enabled/disabled, duplicate/case-colliding usernames, invalid metadata and trigger failure; verify no duplicate or newly orphaned records.
- [ ] 4.6 Run allow/deny database checks for owner, other-user and unauthenticated reads/writes and protected-column mutation; record migration/rollback and rollout prerequisites without production execution.

## 5. Profile data access

- [x] 5.1 Add PublicProfile and UpdateProfileRequest; preserve existing Profile model compatibility and explicitly serialize cleared bio as null.
- [x] 5.2 Add ProfileRemoteDataSource for own/public ID reads, exact case-insensitive username lookup and restricted updates with returned-row verification.
- [x] 5.3 Add ProfileRepository using ApiResponse, session-derived update ownership and shared read access from authentication code.
- [x] 5.4 Add shared validation for new/changed usernames, trimmed names and bios; support unchanged legacy usernames according to the reviewed migration policy.
- [ ] 5.5 Test missing/denied rows, zero-row updates, duplicate usernames, cleared bio and restricted update payloads with fakes and focused development integration checks.

## 6. Local avatar data lifecycle

- [x] 6.1 Implement LocalAvatarDataSource and AvatarRepository with per-account no-backup storage, generated filenames and persisted remote/local/hidden selection modes.
- [x] 6.2 Implement capped streaming, image validation, bounded decoding, orientation handling and 1024-pixel re-encoding outside the main thread.
- [x] 6.3 Implement staged replacement, durable reference commit, interruption recovery, removal and bounded orphan cleanup without touching source-gallery files or active drafts.
- [x] 6.4 Implement per-account commit serialization and observable avatar revisions; retain same-account committed images across logout and isolate all public-profile reads.
- [ ] 6.5 Test cancelled selection, corrupt/oversized/rotated images, storage exhaustion, interrupted commit, restart persistence, backup exclusion and account isolation on an appropriate device or emulator.

## 7. Profile screens and editing

- [x] 7.1 Replace placeholder Profile with ProfileFragment, ProfileViewModel and ProfileUiState; render own/public data and loading, unavailable, error/retry states using ViewBinding.
- [x] 7.2 Build fragment_profile.xml with existing theme resources, accessible avatar and empty-bio/date handling; keep social statistics outside scope.
- [x] 7.3 Add exact-username lookup dialog, input/loading/not-found/error handling and navigation by matched stable user ID.
- [x] 7.4 Add EditProfileFragment/ViewModel/UiState and XML form with field errors, dirty tracking, duplicate-save prevention and draft restoration.
- [x] 7.5 Connect the system image picker, preview, replace and hide actions; handle missing draft files after recreation without losing text edits.
- [x] 7.6 Implement text-only, avatar-only and combined saves with explicit partial outcomes, updated text baseline and retry of only unfinished work.
- [ ] 7.7 Add focused ViewModel tests for validation, draft preservation, partial save/retry, stale completions and account changes during requests.

## 8. Home integration and navigation

- [x] 8.1 Separate root and detail child-fragment navigation in Home; implement Back/drawer ordering and discard checks for all editor exits.
- [x] 8.2 Add account-checked profile snapshot updates to AuthViewModel without session revalidation; propagate avatar revisions to Profile and drawer.
- [x] 8.3 Update home.xml image rendering/tint, shared avatar loader behavior and new localized strings; verify URL/local/placeholder behavior without stale image reuse.
- [ ] 8.4 Verify rotation/process recreation, detail Back, root history clearing, unsaved drawer navigation and logout/account-switch flows on device.

## 9. Acceptance and handoff

- [ ] 9.1 Run configured backend/app-user unit tests and relevant instrumentation tests; run :app-user:assembleDebug and :app-admin:assembleDebug to verify shared-contract consumers.
- [ ] 9.2 Execute and record the four capability specs' scenarios, including direct database denials, two-account isolation and network/filesystem partial failures; explicitly record unavailable environments.
- [x] 9.3 Review changed code and migrations for unexpected dependencies, raw errors, sensitive logs, duplicated response contracts and out-of-scope edits.
- [x] 9.4 Document migration/client rollout and rollback prerequisites, device-only avatar behavior, actual validation results and remaining limitations; keep unfinished tasks unchecked.
