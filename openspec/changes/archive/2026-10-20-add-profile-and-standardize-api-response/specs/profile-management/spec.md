## ADDED Requirements

### Requirement: Own and public profile viewing
The application SHALL display avatar, display name, username, bio and valid available creation date using a shared profile screen with loading, content, unavailable and retryable error states. Edit actions SHALL be available only for the current owner.

#### Scenario: Open own profile
- **WHEN** the user selects the drawer header
- **THEN** their profile is loaded with an edit action and no fabricated social statistics

#### Scenario: Open another profile
- **WHEN** an accessible different user ID is opened
- **THEN** public profile content appears without owner editing controls

#### Scenario: Missing or inaccessible profile
- **WHEN** the requested profile cannot be accessed
- **THEN** UI shows an appropriate unavailable state rather than stale content from a previous user

### Requirement: Reachable username lookup
The own-profile screen SHALL provide exact case-insensitive username lookup with loading, empty/invalid input, not-found and failure states and SHALL open a matched profile by stable user ID.

#### Scenario: Matching username
- **WHEN** the user submits an existing accessible username
- **THEN** the matching profile opens and Back returns to the originating screen

### Requirement: Validated profile editing
Owners SHALL edit display name, username and bio through a request restricted to those fields. New/changed usernames SHALL use 3-30 ASCII letters, digits or underscores; trimmed display names SHALL contain 1-80 characters; bios SHALL contain at most 500 characters. Empty bio SHALL explicitly clear existing bio. Unchanged legacy usernames SHALL not prevent unrelated edits pending approved remediation.

#### Scenario: Clear bio
- **WHEN** the owner removes all bio text and saves a valid form
- **THEN** stored bio becomes null and the profile displays its empty-bio state

#### Scenario: Conflict or validation failure
- **WHEN** saving fails due to a username collision or invalid field
- **THEN** field feedback appears and all draft values are preserved

#### Scenario: No update applied
- **WHEN** an update affects no authorized profile row
- **THEN** the repository returns failure instead of successful save

### Requirement: Draft and save lifecycle
The edit screen SHALL preserve drafts across rotation and restore supported draft state after recreation, prevent duplicate save submissions, and request discard confirmation when leaving dirty drafts, including drawer navigation.

#### Scenario: Leave unsaved changes
- **WHEN** the user presses Back or selects another drawer destination with unsaved edits
- **THEN** they can continue editing or explicitly discard before navigation occurs

#### Scenario: Recreated editor
- **WHEN** the editor is recreated with a saved draft
- **THEN** text is restored and a missing staged image produces a recoverable reselection state

### Requirement: Consistent shared presentation
Successful own-profile changes SHALL update Profile and drawer without reauthentication. Child-fragment history SHALL distinguish root and detail navigation. Stale account/request completions SHALL not overwrite current presentation.

#### Scenario: Updated display name
- **WHEN** an owner successfully saves a new display name
- **THEN** Profile and drawer show the same new value immediately

#### Scenario: Account changes while saving
- **WHEN** an operation for account A completes after account B becomes active
- **THEN** it does not update B's UI or local avatar selection

#### Scenario: Root navigation
- **WHEN** the user leaves a detail flow for a drawer root after resolving any draft
- **THEN** Back does not reopen the abandoned edit form
