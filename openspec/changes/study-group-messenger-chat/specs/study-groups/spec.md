## ADDED Requirements

### Requirement: Subject is optional when creating a study group
The system SHALL allow an authenticated, non-banned user to create a study group with or without a subject. This replaces the "exactly one existing active subject" rule of the `study-groups-and-member-invitations` change; name, description, ownership, atomicity and idempotent retry rules stay as defined there. When a subject is supplied it MUST be an existing active subject, and the subject SHALL remain immutable after creation. A retry with the same request ID SHALL be treated as the same request only when the subject choice, including "no subject", is unchanged.

#### Scenario: Create a group without a subject
- **WHEN** an eligible user submits a valid name and leaves the subject as "Không gắn môn học"
- **THEN** the system creates the group with no subject, the creator becomes owner, and the group appears in their group list

#### Scenario: Create a group with an active subject
- **WHEN** an eligible user submits a valid name and an active subject
- **THEN** the system creates the group linked to that subject, as before

#### Scenario: Supplied subject is inactive or unknown
- **WHEN** a user supplies a subject that is inactive or does not exist
- **THEN** the system rejects creation with the existing "subject unavailable" error and creates nothing

#### Scenario: Retry changes the subject choice
- **WHEN** a request is retried with the same request ID but a different subject choice, such as adding a subject that was omitted the first time
- **THEN** the system rejects the retry with the existing "request conflict" error

#### Scenario: Subjects cannot be loaded
- **WHEN** the subject list fails to load or no subject is active
- **THEN** the user can still create a group without a subject

### Requirement: Groups without a subject display consistently
Every place that shows group information, including the group list, incoming invitations, the group chat header, Group info and the Chat tab, SHALL render a group without a subject without errors and without placeholder text such as "null". Group and invitation responses SHALL return `subject` as null for such groups.

#### Scenario: Group list row
- **WHEN** the user's group list contains a group without a subject
- **THEN** the row shows the group name and role, and omits the subject line

#### Scenario: Incoming invitation preview
- **WHEN** a user receives an invitation to a group without a subject
- **THEN** the preview shows the group name, description and inviter, and omits the subject

#### Scenario: Group info summary
- **WHEN** a member opens Group info of a group without a subject
- **THEN** the summary shows the member count and role only

#### Scenario: Existing groups keep their subject
- **WHEN** the migration runs on a database with groups that already have subjects
- **THEN** those groups keep their subjects and display them as before
