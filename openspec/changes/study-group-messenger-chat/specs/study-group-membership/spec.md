## ADDED Requirements

### Requirement: Any member invites accepted friends
Any current, non-banned member of an active study group SHALL be able to invite users who are non-banned and are the member's own accepted friends at sending time. This replaces the owner-only invitation rule of the `study-groups-and-member-invitations` change. Invitations SHALL still require the invitee to accept before membership is created, and the inviter SHALL be recorded as historical information without granting management rights. The system MUST reject self-invitations, invitations to existing members, and invitations from outsiders, former members or banned accounts. A group/invitee pair SHALL have at most one pending invitation; when any member invites someone who already has a pending invitation, the system SHALL return that pending invitation's ID and status without creating another.

#### Scenario: Ordinary member invites a friend
- **WHEN** a non-owner member invites an accepted friend who is not a member of the group
- **THEN** a pending invitation is created with that member as inviter, and the friend sees it in their incoming invitations

#### Scenario: Member invites someone who is not their friend
- **WHEN** a member invites a user who is an accepted friend of the owner but not of that member
- **THEN** the request is rejected with the existing "friendship required" error

#### Scenario: Two members invite the same person
- **WHEN** a second member invites a user who already has a pending invitation to the group
- **THEN** the existing pending invitation is returned and no duplicate is created

#### Scenario: Outsider or former member invites
- **WHEN** a user who is not a current member, or whose account is banned, tries to invite someone to the group
- **THEN** the request is rejected with the existing "group unavailable" or "account unavailable" error

#### Scenario: Invitee accepts a member's invitation
- **WHEN** the invitee accepts an invitation sent by an ordinary member
- **THEN** the invitee becomes an ordinary member and can open the group chat

### Requirement: Management actions remain owner-only
The system SHALL keep removing members, transferring ownership, editing the group name and description, listing and revoking pending invitations, and archiving the group restricted to the current owner. Any member SHALL be able to leave, except that the owner MUST transfer ownership first.

#### Scenario: Member tries to remove someone
- **WHEN** a non-owner member calls the remove-member operation
- **THEN** the request is rejected with the existing "owner required" error

#### Scenario: Member tries to read pending invitations
- **WHEN** a non-owner member requests the group's pending invitation list or revokes an invitation
- **THEN** the request is rejected and no invitation details are returned

#### Scenario: Owner revokes a member's invitation
- **WHEN** the owner revokes a pending invitation that an ordinary member sent
- **THEN** the invitation becomes revoked and disappears from the invitee's incoming list

### Requirement: Add-member entry for every member
The Group info screen SHALL show an "Thêm thành viên" icon row to every member. It SHALL open the member's own friends list with an invite action, hiding users already known to be members; after the invite action, a friend whose invitation already exists SHALL leave the invite list as an already-invited person instead of showing an error.

#### Scenario: Ordinary member opens add-member
- **WHEN** a non-owner member taps "Thêm thành viên"
- **THEN** their accepted friends who are not members are listed with an invite action

#### Scenario: Friend already invited by someone else
- **WHEN** the member taps invite for a friend who already has a pending invitation from another member
- **THEN** the server returns the existing invitation, no error is displayed, and the friend leaves the invite list as an already-invited person
