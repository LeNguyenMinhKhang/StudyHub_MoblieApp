## ADDED Requirements

### Requirement: Safe selection and explicit upload
The application SHALL select and preview an image locally before explicit Save. It MUST stream-limit input to 10 MiB, reject unsafe or undecodable input, bound decode allocation, correct EXIF orientation, resize to a maximum 1024-pixel edge, and encode JPEG at quality 85 off the main thread. Encoded upload data MUST be at most 10 MiB and use image/jpeg. Cancelling selection or editing MUST NOT upload an image or change the committed profile.

#### Scenario: Valid image preview
- **WHEN** the owner selects a valid image larger than 1024 pixels on its longest edge
- **THEN** the app previews an orientation-corrected JPEG with its longest edge at most 1024 pixels and performs no upload until Save

#### Scenario: Invalid input
- **WHEN** selected content exceeds 10 MiB, cannot be decoded safely, or cannot be encoded within the upload limit
- **THEN** a structured image error is shown and the previous committed avatar remains unchanged

#### Scenario: Cancel image selection
- **WHEN** the owner cancels the picker
- **THEN** the previous draft and committed avatar remain unchanged and no network mutation occurs

### Requirement: Remote avatar publication
The system SHALL upload prepared avatar bytes to the public avatars bucket using a new account-scoped UUID JPEG key without overwriting an existing object. It SHALL publish the trusted HTTPS URL to profiles.avatar_url through an authenticated owner RPC only after upload succeeds, returning the updated profile. Local paths and content URIs MUST NOT be persisted to the remote profile. Avatar operations MUST NOT create post_files records or fall back to post buckets.

#### Scenario: Save replacement
- **WHEN** the owner saves a prepared avatar and both upload and profile publication succeed
- **THEN** the profile contains the new avatar URL, the app renders it as committed, and the previous managed object becomes eligible for cleanup

#### Scenario: Avatar-only save
- **WHEN** the owner changes only the avatar
- **THEN** the avatar RPC updates that field while username, display name, bio and administrative fields remain unchanged

### Requirement: Server enforced ownership
The system MUST enforce avatar write authorization on the server. Anonymous and banned users MUST NOT upload or publish avatars. An authenticated owner SHALL only upload or delete permitted objects under their own UID prefix; overwriting objects and deleting a currently referenced avatar MUST be rejected. The avatar RPC MUST derive identity from auth.uid(), validate object existence and the managed path, reject arbitrary external URLs, and prevent clients from bypassing validation with direct profile writes. Read access to published avatar bytes SHALL be public for anyone possessing the URL.

#### Scenario: Cross-account mutation
- **WHEN** account B attempts to upload or delete objects under account A's prefix or publish A's object as B's avatar
- **THEN** the server rejects the operation without changing either profile or A's object

#### Scenario: Unauthenticated or banned write
- **WHEN** an anonymous caller or banned account attempts avatar upload or publication
- **THEN** the server denies the write

#### Scenario: Validation bypass
- **WHEN** a client supplies an external URL, a nonexistent object path, protected fields, or a direct avatar column write bypassing the RPC
- **THEN** server authorization or validation rejects the mutation

#### Scenario: Read published avatar
- **WHEN** a viewer has the published avatar URL without an authenticated session
- **THEN** the image is readable without exposing any additional private profile fields

### Requirement: Failure recovery and cleanup
The application SHALL preserve the previous avatar until publication succeeds, retain a usable draft for retry where possible, and distinguish confirmed failure from an unknown network outcome. It MUST reconcile an uncertain RPC outcome before retrying publication or deleting the newly uploaded object. Cleanup SHALL be best effort, restricted to managed unreferenced keys, recorded per account for later retry, and MUST NOT turn a successful profile publication into a reported save failure. Gallery sources and external avatar URLs MUST NOT be deleted.

#### Scenario: Upload fails
- **WHEN** upload fails before publication
- **THEN** the existing profile URL remains unchanged and the owner can retry from the retained draft

#### Scenario: Publication is rejected
- **WHEN** the upload succeeds but the server definitively rejects publication
- **THEN** the previous profile remains committed, the new unreferenced object is scheduled for cleanup, and the UI reports the failure accurately

#### Scenario: Lost publication response
- **WHEN** a timeout or cancellation occurs after sending the publication RPC
- **THEN** the app does not blindly delete the new object or report success; it reconciles the same owner's remote profile and preserves an unresolved operation until the outcome can be determined

#### Scenario: Cleanup fails or app restarts
- **WHEN** an unreferenced-object cleanup fails or the process stops during an operation
- **THEN** the committed profile remains authoritative and recorded pending work is reconciled or retried only under the matching owner's session

### Requirement: Account-wide removal
The system SHALL remove an avatar by setting profiles.avatar_url to null through the owner RPC before attempting object cleanup. A successful removal SHALL render the default placeholder on fresh profile reads across devices. Removal MUST NOT be represented solely by a device-local hidden flag. The system does not guarantee revocation of previously downloaded copies or immediate expiry of external caches.

#### Scenario: Remove existing avatar
- **WHEN** the owner saves avatar removal and the RPC succeeds
- **THEN** the current app and fresh profile reads show the placeholder, with the old managed object scheduled for cleanup

#### Scenario: Removal fails
- **WHEN** the removal RPC definitively fails
- **THEN** the previous remote avatar remains committed and the app offers retry without claiming removal succeeded

### Requirement: Accurate combined saves and visible state
Combined text/avatar saves SHALL save text first and attempt the avatar mutation only after text succeeds. If avatar work fails after text succeeds, the UI SHALL report partial success, propagate the saved text, retain remaining avatar work and retry only that work. Successful avatar changes SHALL propagate the updated profile to the own profile and drawer without a new session login check. Public profile and post-author views SHALL use the new URL on their next data refresh; realtime synchronization is not required.

#### Scenario: Text failure
- **WHEN** a combined save fails during the text operation
- **THEN** no avatar is published and the user's text and avatar drafts remain available

#### Scenario: Partial success and retry
- **WHEN** text succeeds but avatar upload or publication fails
- **THEN** the UI and drawer show saved text, the editor reports partial success, and retry performs the remaining avatar work without repeating the successful text save

#### Scenario: Cross-device display
- **WHEN** avatar publication succeeds and another device or another user reloads that profile or its post-author data
- **THEN** the returned profile URL references the newly committed avatar

### Requirement: Concurrent edits and session isolation
Avatar mutation MUST compare the expected prior nullable avatar URL atomically with the server state. A stale replacement or removal SHALL fail with a refreshable conflict instead of silently overwriting a newer avatar. Asynchronous results, drafts and cleanup records MUST remain scoped to the initiating account. Cancellation MUST retain coroutine cancellation semantics.

#### Scenario: Stale device save
- **WHEN** device A has published a new avatar and device B saves with the previous expected URL
- **THEN** B receives a conflict, A's avatar is preserved, and B refreshes before explicitly retrying

#### Scenario: Session changes during upload
- **WHEN** account A starts saving and account B signs in before completion
- **THEN** A's result is not applied to B's UI or profile and A's pending cleanup is not executed with B's credentials

### Requirement: Explicit transition from local avatars
Committed avatar display in the new version SHALL use the remote profile URL or placeholder without legacy LOCAL or HIDDEN overrides. Existing private local avatars MUST NOT be automatically uploaded. The owner SHALL be able to explicitly select their retained local image as an editor draft and save it through the remote workflow. Another account MUST NOT access that retained private image. The historical archived specification SHALL remain unchanged; this capability supersedes its device-local committed-avatar behavior for the new version.

#### Scenario: Upgrade with local avatar or hidden flag
- **WHEN** the upgraded app loads an account with legacy local avatar state
- **THEN** normal profile display uses server state and no private image is uploaded automatically

#### Scenario: Owner elects to upload retained image
- **WHEN** the owner chooses the retained device image in the editor and presses Save
- **THEN** that image follows the same validation, upload, publication and error handling as a newly selected image

#### Scenario: Different account after upgrade
- **WHEN** account B signs in on a device retaining A's old local avatar
- **THEN** B cannot select or render A's private local avatar through the migration option
