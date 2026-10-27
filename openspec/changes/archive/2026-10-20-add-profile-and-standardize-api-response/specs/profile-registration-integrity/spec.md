## ADDED Requirements

### Requirement: Required profile identity
Profiles SHALL have a non-null Auth-linked id and non-null, non-blank username and display_name. Newly selected usernames SHALL be unique case-insensitively. Creation timestamps and administrative defaults SHALL be database-owned.

#### Scenario: Invalid required fields
- **WHEN** registration or profile update supplies null or blank required values
- **THEN** the operation is rejected with structured validation feedback and no invalid profile is committed

#### Scenario: Case-insensitive collision
- **WHEN** a new username differs from an existing username only by letter case
- **THEN** it is rejected as already taken

### Requirement: Atomic profile provisioning
Registration SHALL provision exactly one profile linked to the new Auth account with validated username/display name, null initial bio/avatar, role user, is_banned false and a database-generated timestamp, including when email confirmation is enabled.

#### Scenario: Confirmation required
- **WHEN** valid registration creates an account without an authenticated client session because email confirmation is required
- **THEN** exactly one profile exists and UI displays confirmation-required success without entering authenticated Home

#### Scenario: Immediate authentication
- **WHEN** valid registration returns an authenticated session
- **THEN** the application loads the already-provisioned profile without inserting a duplicate and enters the authenticated flow

#### Scenario: Provisioning fails
- **WHEN** profile provisioning fails during account creation
- **THEN** registration does not report success or commit a newly orphaned account

### Requirement: Protected profile writes
Database authorization SHALL prevent users from updating another user's profile or changing administrative fields, even through direct API requests. Public profile access SHALL expose only approved public fields to authenticated readers.

#### Scenario: Cross-account update
- **WHEN** user A sends a direct update targeting user B
- **THEN** the database denies the mutation and B's profile remains unchanged

#### Scenario: Privilege escalation attempt
- **WHEN** an ordinary user attempts to change role or is_banned on their own row
- **THEN** the database rejects the protected-field mutation

#### Scenario: Public projection
- **WHEN** an authenticated user reads another accessible profile
- **THEN** only approved public profile fields are returned through that read interface

### Requirement: Safe integrity migration
Constraint/provisioning rollout SHALL audit existing rows, triggers and older-client insert behavior before enforcement. Invalid legacy identities SHALL NOT be silently replaced or deleted.

#### Scenario: Legacy collision discovered
- **WHEN** migration preflight finds null identities or case-insensitive username collisions
- **THEN** enforcement is blocked pending explicit remediation while unaffected implementation work can continue
