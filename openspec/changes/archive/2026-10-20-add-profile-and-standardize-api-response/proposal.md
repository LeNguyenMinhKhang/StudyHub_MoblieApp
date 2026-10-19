## Why

StudyHub currently has a placeholder profile screen and repeated exception handling that can expose raw errors or report unsuccessful operations as successful. Users need complete profile viewing and editing, reliable account/profile creation, local avatars, and consistent Kotlin operation results and diagnostics.

## What Changes

- Introduce a shared `ApiResponse` success/failure contract, an immutable `ErrorType` enum containing stable codes, safe user messages, log templates, severity and retryability, plus centralized exception mapping and sanitized logging.
- **BREAKING**: Replace the internal `Resource` repository return contract and migrate every existing consumer together; this does not alter Supabase HTTP response formats.
- Enforce non-null, non-blank profile identifiers and names, unique usernames, database-owned timestamps and protected administrative fields.
- Make registration create exactly one profile even when email confirmation is required; represent confirmation-pending registration separately from authentication.
- Implement own/public profile views, exact-username lookup, editing with validation, navigation and drawer synchronization.
- Implement avatar selection, preview, replacement and removal in account-scoped device storage, including partial-save failures.
- Keep Kotlin, Fragment/XML/ViewBinding and the existing Supabase data-layer architecture.

## Capabilities

### New Capabilities

- `standard-api-response`: Typed operation results, centralized error definitions/mapping and correlated sanitized logs; migration of authentication consumers.
- `profile-registration-integrity`: Required profile fields, exactly-once registration provisioning, confirmation-pending outcomes and database authorization constraints.
- `profile-management`: Own/public profile viewing, username lookup, validated editing and consistent navigation/session presentation.
- `local-profile-avatar`: Account-scoped local avatar lifecycle, safe image processing and coordinated information/avatar saves.

### Modified Capabilities

None. No canonical capability specs exist in this repository yet.

## Impact

- `backend/common`, profile models, authentication and profile remote data sources/repositories, and new local avatar storage.
- `app-user` profile UI, authentication ViewModel, Home child-fragment navigation, drawer and Android resources; inspect and migrate any `app-admin` consumers of changed contracts.
- Version-controlled Supabase migrations for validated profile constraints, provisioning and grants/RLS after inspecting the actual schema. No production migration is executed by this proposal.
- Focused unit/device/database tests and only the test or image-support dependencies demonstrably needed; no toolchain upgrades.
- Avatars remain device-local and are not uploaded to Supabase Storage. Other devices show existing remote avatars or placeholders. Friends, posts, messaging, camera capture and cropping UI are outside scope.
