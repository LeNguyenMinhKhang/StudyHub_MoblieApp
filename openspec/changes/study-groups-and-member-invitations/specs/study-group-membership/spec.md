## ADDED Requirements

### Requirement: Maintain one owner and a private member directory
Every active group SHALL have exactly one owner who is a current member. Ownership SHALL have one authoritative source. Current non-banned members SHALL list members using only user ID, public display name/avatar, role and join time, with stable pagination ordered by join time and user ID ascending, default 20 and maximum 50. Pending invitees SHALL NOT appear as members. The owner SHALL NOT be deleted through ordinary leave/remove operations or cascading profile deletion.

#### Scenario: Member views the directory
- **WHEN** a current member requests the member list
- **THEN** the server returns only that group's members and permitted profile fields with exactly one owner
- **AND** stable consecutive pages contain no missing or duplicated rows when the dataset is unchanged

#### Scenario: Invitee requests the directory
- **WHEN** a pending invitee who is not a member requests the member list
- **THEN** the server denies access without returning member data

### Requirement: Owners invite accepted friends
Only the current owner SHALL issue invitations to non-banned existing users who are their accepted friends at sending time. The system MUST reject self-invitations and invitations to existing members. A group/invitee pair SHALL have at most one pending invitation; repeated sends while pending SHALL return that invitation. Following decline or revocation, a new eligible invitation SHALL use a new ID. Invitations SHALL remain pending until accepted, declined, revoked or the group is archived; they SHALL NOT expire automatically in this version.

#### Scenario: Send an eligible invitation
- **WHEN** an owner invites an accepted friend who is not a member
- **THEN** one pending invitation is created and the member count remains unchanged

#### Scenario: Reject ineligible sender or target
- **WHEN** a non-owner sends an invitation, or an owner targets themselves, a current member, a non-friend, a nonexistent user or a banned user
- **THEN** no invitation is created and a safe domain error is returned

#### Scenario: Concurrent invitation sends
- **WHEN** eligible duplicate invitation requests execute concurrently for the same group and invitee
- **THEN** only one pending invitation exists and successful responses refer to it

#### Scenario: Friendship removed after sending
- **WHEN** the inviter and invitee cease being friends after a valid invitation was issued
- **THEN** that pending invitation remains actionable; friendship is not checked again at acceptance
- **AND** any new invitation requires friendship eligibility at its own sending time

### Requirement: Limit invitation visibility
An eligible invitee SHALL list only their pending invitations for active groups, with preview limited to invitation ID/state, group ID/name, subject, description, and inviter's public name/avatar. The current owner SHALL list the group's pending invitations and revoke them. Other members and outsiders MUST NOT read the invitation directory. Invitation lists SHALL use creation-time/ID descending keyset pagination with default 20, maximum 50 and validated cursors.

#### Scenario: Read incoming invitation preview
- **WHEN** the recipient opens incoming invitations
- **THEN** they receive enough information to accept or decline without obtaining the member directory, other invitations or private profile fields

#### Scenario: Protect invitation data from guessed IDs
- **WHEN** another user queries or responds to an invitation intended for someone else
- **THEN** the system returns no invitation details and makes no change

### Requirement: Respond to invitations atomically and safely
Only the intended authenticated, non-banned recipient SHALL accept or decline a pending invitation to an active group. Acceptance SHALL atomically insert exactly one membership and resolve the invitation as accepted. Decline SHALL resolve it without adding membership. Repeating acceptance SHALL return success only while that accepted invitation's recipient is still a current member; an old accepted invitation MUST NOT restore removed membership. Repeating decline in declined state SHALL be harmless. Incompatible terminal-state actions SHALL fail without mutation.

#### Scenario: Accept an invitation
- **WHEN** the recipient accepts a pending invitation
- **THEN** the invitation becomes accepted and exactly one membership is committed
- **AND** the group appears in their group list and the invitation disappears from pending lists

#### Scenario: Decline an invitation
- **WHEN** the recipient declines a pending invitation
- **THEN** the invitation becomes declined, no membership is created and the invitation leaves pending lists

#### Scenario: Accept from two devices
- **WHEN** two acceptance requests for one invitation execute concurrently or retry after a lost response
- **THEN** one membership exists and compatible responses refer to the same resulting membership

#### Scenario: Membership insertion fails
- **WHEN** acceptance cannot insert the membership
- **THEN** the invitation remains pending and no partial membership is committed

#### Scenario: Reuse an old accepted invitation
- **WHEN** a former member tries to accept an invitation previously accepted before they left or were removed
- **THEN** the system returns unavailable and does not recreate their membership

### Requirement: Revoke invitations with deterministic race outcomes
Only the current owner SHALL revoke a pending invitation. Repeating revocation on a revoked invitation SHALL be harmless. Accept and revoke operations SHALL serialize against the same group/invitation state. Revoking an already accepted invitation MUST NOT remove a member.

#### Scenario: Revocation wins
- **WHEN** revocation commits before a concurrent acceptance
- **THEN** acceptance fails and no membership is created

#### Scenario: Acceptance wins
- **WHEN** acceptance commits before a concurrent revocation
- **THEN** revocation reports a state conflict and membership remains until an authorized leave/remove action

#### Scenario: Reinvite after refusal or revocation
- **WHEN** the owner sends a new eligible invitation after the old one was declined or revoked
- **THEN** a new pending invitation with a distinct ID is created and the old terminal invitation remains unusable

### Requirement: Leave or remove membership
A current non-owner member SHALL be able to leave an active group. Only the current owner SHALL remove another non-owner member. Removal SHALL immediately revoke access for new server reads after commit. Neither operation SHALL reactivate previous invitations. An owner trying to leave SHALL be instructed to transfer ownership or archive the group.

#### Scenario: Member leaves or is removed
- **WHEN** an authorized leave/remove operation commits
- **THEN** the member count decreases and subsequent group-detail/member-list requests from that user are denied
- **AND** future rejoining requires a new valid invitation

#### Scenario: Prevent ownerless groups
- **WHEN** an owner attempts ordinary leave, self-removal or removal through another caller's request
- **THEN** the system rejects the operation and retains owner membership

### Requirement: Transfer ownership atomically
Only the current owner SHALL transfer ownership to a different existing, non-banned member after UI confirmation. Transfer SHALL atomically give ownership to the target and leave the previous owner as an ordinary member. Pending invitations SHALL remain valid under the new owner's control; inviter identity SHALL be historical and SHALL NOT confer current management rights.

#### Scenario: Successful transfer
- **WHEN** the owner transfers to an eligible current member
- **THEN** exactly one owner exists, both memberships remain and the previous owner can subsequently leave
- **AND** only the new owner can revoke pending invitations or invite new users

#### Scenario: Reject an invalid successor
- **WHEN** a caller selects themselves, a non-member, a banned account or a pending invitee as successor
- **THEN** ownership is unchanged and a safe error is returned

#### Scenario: Transfer races with leaving or removal
- **WHEN** transfer and a leave/remove operation affecting the successor run concurrently
- **THEN** either removal commits first and transfer fails, or transfer commits first and ordinary owner removal fails
- **AND** no committed active group has an owner outside its membership

### Requirement: Server-enforced authorization and account eligibility
All group operations SHALL derive the actor from the authenticated session and deny unauthenticated or banned actors. App roles MUST NOT directly insert, update, delete or select the new tables; public RPCs SHALL explicitly enforce actor, ownership, membership, group state and minimal data projection. Anonymous users MUST NOT execute group RPCs; internal helpers MUST NOT be public APIs. A banned owner SHALL NOT be automatically replaced; other eligible members SHALL retain their ordinary read/leave rights.

#### Scenario: Bypass the UI
- **WHEN** a user attempts direct table access, forged actor identity, self-membership insertion or ownership escalation through API calls
- **THEN** authorization denies the request without exposing private rows or changing membership/ownership

#### Scenario: Account is banned
- **WHEN** a banned account invokes group reads or mutations
- **THEN** access is denied regardless of stored owner/member status
- **AND** the system does not silently transfer their ownership to another user

#### Scenario: Current owner replaces historical inviter
- **WHEN** ownership has changed and the original inviter attempts to revoke an invitation
- **THEN** the old inviter is denied management rights and the current owner can manage that pending invitation

### Requirement: Membership governs media admission and revocation
Call tokens SHALL be generated only by trusted server code after verifying the authenticated user's current account, membership, call state and admission generation. Tokens SHALL bind a single opaque user identity and room, have a short explicit TTL, and omit administrative/recording permissions. LiveKit secrets MUST NOT exist in Android binaries, client configuration, logs or public database reads. Pending membership invitations MUST NOT grant calling privileges.

#### Scenario: Request a token with forged identity or room
- **WHEN** a caller modifies a token request to name another user, group or provider room
- **THEN** the server derives identity from verified authentication and refuses any unauthorized admission

#### Scenario: Former member or banned user obtains an old token
- **WHEN** membership is removed, the user leaves the group, the account is banned or the group is archived
- **THEN** new token issuance is denied immediately after DB commit and durable media-revocation work is enqueued in that same transaction
- **AND** the provider is instructed to disconnect the identity and revoke cached tokens, targeting completion within ten seconds when provider and worker are healthy

#### Scenario: Token issuance races with removal
- **WHEN** a token issuance is in progress while membership is removed
- **THEN** admission version checks and reconciliation prevent the stale authorization from persisting as a valid session
- **AND** any already issued/late token is covered by provider revocation and cannot restore membership or long-lived media access

#### Scenario: Retry or delayed revoke after rejoining
- **WHEN** the user legitimately rejoins with a new membership generation while an old revocation job is retried
- **THEN** server coordination fences stale admissions and verifies the current generation before issuing a new usable session
- **AND** a delayed callback cannot silently mark an old membership as authorized

#### Scenario: Ownership changes during a call
- **WHEN** group ownership transfers while a call is active
- **THEN** end-for-all owner authority follows the current owner, while the initiator retains that authority only if still a member
- **AND** historical ownership alone does not authorize future media-control operations

### Requirement: Membership protects documents annotations and export
Only current non-banned members of an active group SHALL read its active documents, source files, versions, annotation snapshots and deltas or create annotations. Authors SHALL edit/delete their own annotations and the current group owner SHALL manage all annotations. Only a document's uploader or the current group owner SHALL add versions/archive it. All checks SHALL be enforced at server/Storage boundaries, not only UI. Document access SHALL be independent of participation in a call and its capacity limit.

#### Scenario: Access private document by guessed identifier
- **WHEN** an outsider, pending invitee, former member or banned account requests source bytes, annotation data, an export snapshot or a mutation
- **THEN** the server denies access even if the caller knows the object key/document ID or previously subscribed to a channel

#### Scenario: Modify another author's note
- **WHEN** an ordinary member attempts to update/delete another member's annotation
- **THEN** the server denies the mutation
- **AND** the current owner can perform the version-checked management operation with actor attribution

#### Scenario: Group membership changes during collaboration
- **WHEN** a member leaves/is removed or the group/document is archived
- **THEN** subsequent file/data reads, writes, snapshot fetches and finalization are denied, including queued offline operations
- **AND** the realtime channel epoch is rotated as needed so obsolete subscriptions receive no new document content

#### Scenario: Preserve others' work and acknowledge download limits
- **WHEN** an annotation author leaves the group
- **THEN** their committed annotations remain for remaining members
- **AND** access revocation does not claim to erase PDF copies previously exported outside the app

#### Scenario: Read without joining the call
- **WHEN** an eligible group member opens its PDF while not in the meeting or while media capacity is full
- **THEN** document reading and annotation permissions still follow group membership
