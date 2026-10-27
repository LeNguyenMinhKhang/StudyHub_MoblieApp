## ADDED Requirements

### Requirement: Device-local account isolation
The application SHALL store committed avatars in account-scoped private no-backup storage, retain them across logout for the same account, and never publish a local file path to Supabase. Another user's public profile SHALL not consult that user's locally retained avatar from a previous session.

#### Scenario: Switch accounts
- **WHEN** account A saves an avatar and account B subsequently signs in
- **THEN** B's own avatar and views of A do not reuse A's private local override

#### Scenario: Restart on the same device
- **WHEN** the app restarts and the same account signs in
- **THEN** its committed local avatar is available unless app data was cleared

#### Scenario: Different device
- **WHEN** a profile with a local-only avatar is viewed on another device
- **THEN** the existing remote avatar or placeholder is shown without implying the local change was uploaded

### Requirement: Safe image selection and preview
The app SHALL support image selection, cancellation and preview; stream-limit input to 10 MB, validate decodability, bound decode allocation, correct orientation and save a re-encoded image with a maximum 1024-pixel edge off the main thread.

#### Scenario: Cancel selection
- **WHEN** the picker is cancelled
- **THEN** the previous draft and committed avatar remain unchanged

#### Scenario: Invalid or oversized image
- **WHEN** selected input exceeds the byte limit or cannot be safely decoded
- **THEN** a structured image error appears without replacing the current avatar or blocking the UI

### Requirement: Durable avatar replacement and removal
The system SHALL stage replacements, commit the new reference durably before deleting the old file, recover interrupted commits to a complete image, and clean abandoned unreferenced drafts. Selection modes SHALL distinguish remote/default, local and hidden-on-device. Removal SHALL not delete the gallery source or modify a remote avatar.

#### Scenario: Storage failure
- **WHEN** replacement cannot be committed due to storage failure
- **THEN** the old avatar remains usable and a retryable draft is retained where possible

#### Scenario: Hide current avatar
- **WHEN** the owner saves removal of the displayed avatar
- **THEN** Profile and drawer show a placeholder on that device without resurrecting an old remote URL

#### Scenario: Interrupted commit
- **WHEN** the app terminates during avatar replacement and restarts
- **THEN** recovery selects a complete committed image and does not reference a partially written file

### Requirement: Explicit partial-save behavior
Text-only saves SHALL use remote storage; avatar-only saves SHALL not require a profile update. Combined saves SHALL stage the image, save remote text, then commit the image, reporting each outcome accurately.

#### Scenario: Remote save fails
- **WHEN** a combined save fails before text is committed
- **THEN** the committed avatar remains unchanged and edits remain available for retry

#### Scenario: Avatar fails after text succeeds
- **WHEN** remote text saves but local avatar commit fails
- **THEN** UI and drawer receive the saved text, UI reports partial success, and retry targets the remaining avatar work

#### Scenario: Avatar-only update
- **WHEN** only the avatar is changed and saved
- **THEN** the local commit refreshes Profile and drawer without a remote profile write
