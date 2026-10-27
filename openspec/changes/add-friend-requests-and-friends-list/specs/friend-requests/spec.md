## ADDED Requirements

### Requirement: Discover a person and send a friend request
The system SHALL let an authenticated, non-banned user find an existing public profile by exact username using the current lookup contract, open that profile, and explicitly send a request to an eligible different user. The server MUST derive the requester from the authenticated session and create a pending relationship only when the pair has no relationship.

#### Scenario: Send from a public profile
- **WHEN** A finds eligible B by username, opens B's profile and selects Kết bạn
- **THEN** one pending relationship from A to B is persisted and A sees outgoing_pending with a cancel action
- **AND** B sees that request in incoming requests after loading or refreshing

#### Scenario: Lookup has no public match
- **WHEN** a username lookup has no eligible public result
- **THEN** the UI displays a generic unavailable result and sends no friend request

#### Scenario: Own profile opened by explicit identifier
- **WHEN** A opens a profile whose id equals A's session user id, including a lookup result
- **THEN** no friendship mutation action is displayed
- **AND** a direct attempt to send a request to self is rejected by the server

### Requirement: One relationship per unordered pair
The system MUST enforce at most one relationship for an unordered pair of distinct users at the database level. Sending when a relationship exists SHALL return its current viewer-relative state without duplicating it or implicitly accepting a reverse request.

#### Scenario: Repeated send in the same direction
- **WHEN** A sends to B while A's pending request to B already exists
- **THEN** the existing relationship id and outgoing state are returned without a new row

#### Scenario: Simultaneous reverse sends
- **WHEN** A sends to B while B sends to A concurrently and no relationship initially exists
- **THEN** at most one pending row exists after both operations
- **AND** the other user receives an incoming state and must explicitly accept to become friends

#### Scenario: Sending to an existing friend
- **WHEN** either party sends while their relationship is accepted
- **THEN** the server returns friends and does not change or duplicate the relationship

### Requirement: Incoming and outgoing request lists
The system SHALL expose separate incoming and outgoing pending lists scoped to the current actor, with the other user's public id, username, display name and avatar. It MUST exclude accepted rows and unavailable peers, return a bounded page in deterministic created_at-descending/id-descending order, and disclose no private profile fields.

#### Scenario: Direction is relative to the viewer
- **WHEN** A has sent to B and C has sent to A
- **THEN** A's outgoing list contains B and incoming list contains C
- **AND** neither list exposes pending relationships between unrelated users

#### Scenario: Pagination and invalid input
- **WHEN** a user loads requests with no page size or with a size greater than 50
- **THEN** the server returns at most 20 rows by default or at most 50 for the oversized request, with a next cursor only if more results exist
- **AND** a nonpositive size, invalid list kind or incomplete/malformed cursor is rejected

#### Scenario: Same timestamp across page boundaries
- **WHEN** multiple pending requests share created_at and the user loads subsequent pages without intervening changes
- **THEN** the id tie-breaker yields each eligible request once and the client does not duplicate rows

### Requirement: Accept or decline as the recipient
The system SHALL allow only the addressee to accept or decline a pending request. Accept MUST atomically change the existing row to accepted; decline MUST remove the pending row. Acceptance SHALL require both accounts to be eligible and no block in either direction.

#### Scenario: Accept a received request
- **WHEN** B accepts pending request A → B
- **THEN** its row becomes accepted, it leaves both pending lists, and each party sees the other in friends after refresh

#### Scenario: Decline and send again later
- **WHEN** B declines pending request A → B
- **THEN** the row is removed and no friendship is created
- **AND** a later explicit send between the eligible pair can create a new pending request with a new id

#### Scenario: Sender or unrelated user attempts acceptance
- **WHEN** A or unrelated C attempts to accept or decline A → B
- **THEN** the server rejects the operation and does not alter the row

#### Scenario: Repeat acceptance for the same accepted row
- **WHEN** B repeats acceptance for the same id that B already accepted
- **THEN** the server returns the accepted state without creating a second relationship

### Requirement: Cancel only a pending request owned by the sender
The system SHALL allow only the requester to cancel their pending request, removing its row. Cancellation MUST NOT remove an accepted friendship or affect a newer request created for the same pair.

#### Scenario: Cancel outgoing request
- **WHEN** A cancels pending request A → B
- **THEN** that row is removed and both pending lists omit it on refresh

#### Scenario: Cancellation loses a race with acceptance
- **WHEN** B accepts before A's cancellation obtains the row for mutation
- **THEN** A receives a stale-state failure and the accepted friendship remains intact

#### Scenario: Cancellation or decline wins before acceptance
- **WHEN** a valid cancel or decline removes the request before acceptance obtains the row
- **THEN** acceptance fails as unavailable/stale without recreating a row

#### Scenario: Old action after a new invitation
- **WHEN** a prior request was removed, a new request exists, and a user submits cancel/accept/decline with the old id
- **THEN** the action fails safely and the new request remains unchanged

### Requirement: Server authorization and unavailable peers
Every friendship RPC MUST require a valid session with a non-banned actor, enforce actor ownership and immutable relationship fields, and return only the caller's authorized data. Send and accept MUST reject deleted/banned peers and a block in either direction. State/list reads SHALL suppress unavailable peer relationships without exposing block direction. Direct table access by anon/authenticated MUST NOT bypass these checks.

#### Scenario: Anonymous or banned actor
- **WHEN** an anonymous caller or a banned authenticated actor invokes a friendship RPC
- **THEN** no relationship data or mutation is permitted

#### Scenario: Blocked or unavailable target
- **WHEN** A tries to send to or accept a request with B while B is deleted/banned or either party has blocked the other
- **THEN** no new or accepted relationship is produced and the UI receives a generic unavailable outcome

#### Scenario: Third party and forged direct writes
- **WHEN** C calls a mutation for A/B's relationship or a client directly inserts accepted rows, changes participant ids, updates status, deletes, or reads the friendships table
- **THEN** the unauthorized request is denied without exposing the relationship or changing data

#### Scenario: Pending relationship becomes unavailable
- **WHEN** an existing pending relationship's peer becomes banned or blocked and the user refreshes
- **THEN** it is omitted from the lists and its profile friendship state is unavailable
- **AND** an eligible actor can still perform their authorized decline/cancel cleanup using the existing id

#### Scenario: Block removed without deleting relationship
- **WHEN** a block is removed while the original relationship still exists and both accounts are eligible
- **THEN** the existing relationship becomes visible again on refresh with its original pending or accepted status

### Requirement: Recoverable invitation UI state
The UI SHALL represent initial loading, successful empty/content, list errors and per-row mutation progress separately. It MUST disable conflicting actions while a mutation is in progress, preserve visible data on recoverable errors, and reconcile ambiguous timeout outcomes by reading server state before permitting another explicit mutation.

#### Scenario: Action fails or times out
- **WHEN** an invitation action returns an error or times out without a confirmed response
- **THEN** the UI does not report success or automatically resend the mutation
- **AND** it presents an error and refresh/retry-read path; after reconciliation the current valid actions become available

#### Scenario: Relationship has not loaded
- **WHEN** the relationship section is loading or its read failed
- **THEN** the UI does not treat that state as no relationship or enable a send action based on it

#### Scenario: Session changes during request
- **WHEN** an invitation load or mutation started under A completes after logout or login as C
- **THEN** its result is discarded and no list/action state belonging to A is shown for C

### Requirement: Notify the recipient of friend request changes
The system SHALL create an in-app notification for the addressee when a new pending request is created, and for the requester when the request is accepted, in the same transaction as the relationship change. Repeated sends MUST NOT create additional notifications, and a cancelled request MUST remove its pending notification. Users SHALL read and mark only their own notifications through authenticated RPCs.

#### Scenario: Recipient sees a new request
- **WHEN** A sends a request to eligible B
- **THEN** B's notification list contains one unread friend_request from A and the unread count increases
- **AND** opening it leads to A's profile with accept and decline actions

#### Scenario: Requester learns of acceptance
- **WHEN** B accepts A's pending request
- **THEN** A receives one unread friend_accept from B and B's friend_request notification is marked read

#### Scenario: Request withdrawn
- **WHEN** A cancels a pending request to B
- **THEN** B no longer sees the friend_request notification from A

