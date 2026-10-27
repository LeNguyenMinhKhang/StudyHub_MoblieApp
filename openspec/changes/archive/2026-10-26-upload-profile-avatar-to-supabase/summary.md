# Implementation Summary: Remote Profile Avatar on Supabase

## Overview & Completed Work
Implemented the `upload-profile-avatar-to-supabase` change proposal to transition profile avatars from local device storage to cloud-backed Supabase Storage with centralized URL references in `profiles.avatar_url`.

### Features & Capabilities Built
1. **Supabase Storage Bucket & RLS Migration** (`supabase/migrations/20261025_avatar_bucket_rpc_permissions.sql`):
   - Created the public bucket `avatars` with JPEG-only MIME type restriction and 10 MiB object size limit.
   - Storage RLS policies:
     - `INSERT`: Strictly owner-scoped with key prefix `{auth.uid()}/...` and immutable (no overwrite).
     - `SELECT`: Public read access for any client possessing the HTTPS URL.
     - `DELETE`: Restricted to object owners, conditionally blocked if the object is currently referenced by `profiles.avatar_url`.
   - RPC function `update_my_avatar(p_object_path text, p_expected_avatar_url text)`:
     - Atomically locks profile row (`FOR UPDATE`), checks user authentication and banned status.
     - Validates object path prefix and existence in the `avatars` bucket.
     - Performs optimistic concurrency control (OCC) using nullable expected avatar URL comparison (`IS NOT DISTINCT FROM`).
     - Supports both avatar replacement and removal (`p_object_path = null`).
     - Constructs trusted public URLs using server project URL configuration; rejects arbitrary client URLs.
   - Guard against bypass: Trigger `trg_guard_direct_avatar_url_update` prevents direct `UPDATE` of `avatar_url` on `profiles`, mandating the secure RPC path.

2. **Data Layer & Remote Storage Client**:
   - `AvatarRemoteDataSource`: Interacts with the `avatars` bucket via Supabase Storage SDK; uploads immutable keys with `image/jpeg` MIME type and performs best-effort unreferenced object deletion.
   - `ProfileRemoteDataSource`: Added `updateMyAvatar(objectPath, expectedAvatarUrl)` calling the Supabase `update_my_avatar` RPC.
   - `AvatarPendingStore`: Per-account atomic journal in `noBackupFilesDir` recording pending upload/publish/cleanup operations for crash resilience and timeout reconciliation.
   - `AvatarRepository`: Orchestrates the remote workflow:
     - `commitRemote(userId, draft, expectedAvatarUrl)`: Uploads new immutable key → saves pending journal → executes RPC publish → triggers best-effort cleanup of old avatar → clears pending record.
     - `removeRemote(userId, expectedAvatarUrl)`: Saves pending journal → executes RPC removal → cleans up old avatar object.
     - `reconcilePending(userId)`: Resolves uncertain outcomes after timeout/cancellation by inspecting current remote profile.
     - Safe error handling: Sanitized logging, rethrown coroutine cancellations, non-blocking cleanup failure.

3. **Domain & Error Types**:
   - Extended `Operation` with `AVATAR_UPLOAD` and `AVATAR_PUBLISH`.
   - Added `ErrorType` entries: `AVATAR_UPLOAD_FAILED`, `AVATAR_CONFLICT` (Postgres 40001 serialization error), `AVATAR_OUTCOME_UNKNOWN`.
   - Updated `ExceptionMapper` to map Postgres conflict code 40001 and I/O exceptions accordingly.

4. **UI & State Management**:
   - `EditProfileUiState`: Updated with `avatarAction` (`KEEP`, `REPLACE`, `REMOVE`), `avatarConflict`, `avatarOutcomeUnknown`, `partialSave`, and `canRetryAvatar`.
   - `EditProfileViewModel`:
     - Text-first saving: Text updates first; avatar is only committed if text succeeds.
     - Partial save handling: If text succeeds but avatar fails, state enters `partialSave` with retry capability.
     - `retryAvatar()`: Retries only the avatar step using current expected URL.
     - `useRetainedLocalAvatar(file)`: Allows user to explicitly select existing local device avatar as draft for upload without automatic migration.
     - Session safety: Verifies active session user ID before and after asynchronous operations to prevent cross-account leaks.
   - `AvatarImageLoader`: Committed displays use remote profile URL (or placeholder); local files used strictly for draft preview.
   - `EditProfileFragment` & `fragment_edit_profile.xml`:
     - Wired up `btnChangeAvatar`, `btnRemoveAvatar`, `btnRetryAvatar`, and error/warning notices (`tvEditError`, `tvEditNotice`).

## Key Decisions & Edge Cases Handled
- **Immutable Keys**: Each avatar upload generates a new `{userId}/{UUID}.jpg` key. This prevents caching bugs, ensures CDN freshness, and avoids overwriting active avatars.
- **Optimistic Concurrency Control (OCC)**: The `update_my_avatar` RPC compares `p_expected_avatar_url IS NOT DISTINCT FROM profiles.avatar_url`. Stale writes from other devices trigger `AVATAR_CONFLICT` requiring a fresh profile load.
- **Uncertain Outcomes**: Network timeouts during RPC do not delete the newly uploaded object or report false failures. An `AVATAR_OUTCOME_UNKNOWN` status is set and reconciliation confirms the outcome when the account resumes.
- **Cleanup Resilience**: Object deletion errors are logged but never fail a successful profile save.

## File Changes
### Created
- `supabase/migrations/20261025_avatar_bucket_rpc_permissions.sql`: SQL migration for bucket, RLS policies, RPC, and direct write guard.
- `backend/src/main/java/com/doan/studyhub/core/data/remote/AvatarRemoteDataSource.kt`: Remote data source for Supabase Storage `avatars` bucket.
- `backend/src/main/java/com/doan/studyhub/core/data/local/AvatarPendingStore.kt`: Local atomic file store for pending operation tracking.
- `backend/src/test/java/com/doan/studyhub/core/data/repository/AvatarRepositoryTest.kt`: Unit test suite covering all 12 repository scenarios.
- `app-user/src/test/java/com/doan/studyhub/user/ui/profile/EditProfileViewModelTest.kt`: Unit test suite covering 8 ViewModel state transitions.
- `openspec/changes/upload-profile-avatar-to-supabase/summary.md`: This implementation summary.

### Modified
- `backend/src/main/java/com/doan/studyhub/common/SafeApiCall.kt`: Added avatar operations to `Operation` enum.
- `backend/src/main/java/com/doan/studyhub/common/ErrorType.kt`: Added `AVATAR_UPLOAD_FAILED`, `AVATAR_CONFLICT`, and `AVATAR_OUTCOME_UNKNOWN`.
- `backend/src/main/java/com/doan/studyhub/common/ExceptionMapper.kt`: Mapped Postgres 40001 code and I/O exceptions for avatar operations.
- `backend/src/main/java/com/doan/studyhub/core/data/remote/ProfileRemoteDataSource.kt`: Added `updateMyAvatar` RPC call.
- `backend/src/main/java/com/doan/studyhub/core/data/repository/ProfileRepository.kt`: Made extensible with open methods and custom session provider for testing.
- `backend/src/main/java/com/doan/studyhub/core/data/repository/AvatarRepository.kt`: Full orchestration for remote avatar upload, RPC publish, cleanup, and reconciliation.
- `backend/build.gradle.kts`: Added `testOptions.unitTests.isReturnDefaultValues = true`.
- `app-user/build.gradle.kts`: Added `testOptions.unitTests.isReturnDefaultValues = true`.
- `app-user/src/main/java/com/doan/studyhub/user/ui/profile/EditProfileUiState.kt`: Added remote avatar fields, flags, and retry logic.
- `app-user/src/main/java/com/doan/studyhub/user/ui/profile/EditProfileViewModel.kt`: Implemented remote avatar coordination, text-first save, partial save retry, and retained avatar support.
- `app-user/src/main/java/com/doan/studyhub/user/ui/profile/EditProfileFragment.kt`: UI binding for retry, remove, and draft preview.
- `app-user/src/main/java/com/doan/studyhub/user/ui/profile/ProfileFragment.kt`: Updated avatar display to use remote URLs directly.
- `app-user/src/main/java/com/doan/studyhub/user/ui/profile/AvatarImageLoader.kt`: Switched committed avatar rendering to remote-first.
- `app-user/src/main/res/layout/fragment_edit_profile.xml`: Added `btnRetryAvatar`.
- `app-user/src/main/res/values/strings.xml`: Added avatar retry and outcome strings.

## Verification & Test Status
- **Backend Unit Tests**:
  - Command: `.\gradlew :backend:testDebugUnitTest`
  - Result: 21 tests completed, 0 failures (100% pass rate including `AvatarRepositoryTest`, `SafeApiCallTest`, `ProfileValidatorTest`).
- **App User Unit Tests**:
  - Command: `.\gradlew :app-user:testDebugUnitTest`
  - Result: 8 tests completed, 0 failures (100% pass rate covering `EditProfileViewModelTest`).
- **Build & Packaging**:
  - Command: `.\gradlew :backend:testDebugUnitTest :app-user:testDebugUnitTest :app-user:assembleDebug`
  - Result: `BUILD SUCCESSFUL` with all 71 actionable tasks executed or up-to-date.
- **OpenSpec Validation**:
  - Command: `openspec.cmd validate upload-profile-avatar-to-supabase --strict --no-interactive`
  - Result: `Change 'upload-profile-avatar-to-supabase' is valid`.
