## ADDED Requirements

### Requirement: Show the current user's bilateral friends
The system SHALL show accepted friendships involving the authenticated, non-banned viewer, regardless of whether the viewer was requester or addressee. Each row MUST show the other participant once with public id, username, display name and avatar or placeholder. Pending relationships, unavailable peers and unrelated users' relationships MUST be excluded; email, role and administration fields MUST NOT be returned.

#### Scenario: Friend on either side of the row
- **WHEN** A has accepted relationships A → B and C → A plus pending A → D
- **THEN** A's friends list shows B and C once each and omits D

#### Scenario: Unrelated relationship
- **WHEN** A loads friends while B and C have an accepted relationship not involving A
- **THEN** that relationship is not returned to A

#### Scenario: Unavailable friend and avatar fallback
- **WHEN** A refreshes with a banned or blocked friend and a different eligible friend without a valid avatar
- **THEN** the unavailable friend is omitted and the eligible friend displays an avatar placeholder
- **AND** no local avatar belonging to A is substituted for that friend

### Requirement: Stable bounded friend pagination
The system SHALL fetch pages sorted by relationship created_at descending and id descending, with a default page size of 20 and a server maximum of 50. It MUST use a cursor that includes both sort fields, reject invalid cursor/limit input, return a next cursor only when further rows exist, and load public identity in the page query rather than one lookup per friend.

#### Scenario: Multiple pages with equal timestamps
- **WHEN** A has at least 45 eligible friends, including rows with identical created_at, and loads all pages without intervening changes
- **THEN** all eligible friends are reachable exactly once in deterministic order and the final page has no next cursor

#### Scenario: Oversized or invalid request
- **WHEN** a caller asks for a size over 50, a nonpositive size, or an incomplete/malformed cursor
- **THEN** an oversized positive size is capped at 50 and nonpositive size or invalid cursor is rejected

#### Scenario: Mutation and refresh reset pagination
- **WHEN** A successfully accepts/removes a relationship or explicitly refreshes friends
- **THEN** the relevant list restarts from its first page and subsequent pages merge by relationship id without duplicates

### Requirement: Enter friends and navigate to a profile
The Android app SHALL expose Bạn bè, Lời mời nhận and Lời mời đã gửi from the existing Bạn bè & Tìm kiếm drawer destination. It SHALL support exact-username discovery and opening a listed peer's existing public profile through Home's detail navigation while retaining the return path to friends.

#### Scenario: Open friends from drawer
- **WHEN** the user selects Bạn bè & Tìm kiếm
- **THEN** a functional friends screen opens with the three list modes and a Tìm người action

#### Scenario: Open a listed friend and return
- **WHEN** the user selects a friend's row and then navigates back from that profile
- **THEN** the app opens the correct peer profile and returns to the friends screen, reloading the active list to reconcile changes

#### Scenario: Lookup entry points still work
- **WHEN** the user searches an exact username from either friends or the owner's profile
- **THEN** a successful lookup opens the matching public profile and existing profile lookup behavior remains usable

### Requirement: Relationship actions on profiles
The profile relationship section SHALL render self, none, outgoing_pending, incoming_pending, friends and unavailable according to server state relative to the current session. It MUST expose only actions valid for that state, reload when the profile becomes active again, and avoid reloading posts merely because friendship state changes.

#### Scenario: Action mapping
- **WHEN** an eligible other profile resolves to none, outgoing_pending, incoming_pending or friends
- **THEN** the UI respectively offers send, cancel, accept/decline, or confirmed removal

#### Scenario: Remote change while profile is inactive
- **WHEN** B accepts A's request on another device and A returns to B's profile
- **THEN** the relationship section reloads and shows friends once the read completes

#### Scenario: Self or unavailable profile
- **WHEN** the target is the current user or the server reports unavailable
- **THEN** the UI does not allow sending or accepting a friendship with that target

### Requirement: Remove an accepted friendship with confirmation
Either participant SHALL be able to remove their accepted friendship after an explicit confirmation. The server MUST check the relationship id, actor involvement and accepted status before removing that row. Removal SHALL not automatically create another request or remove messages/posts.

#### Scenario: Confirm removal
- **WHEN** A confirms removal of accepted A/B
- **THEN** the accepted row is deleted, each party omits the other on refresh, and a later deliberate send can start a new pending relationship

#### Scenario: Dismiss confirmation
- **WHEN** A dismisses the removal dialog
- **THEN** no mutation is sent and the friendship remains

#### Scenario: Wrong actor or pending relationship
- **WHEN** unrelated C attempts removal of A/B or either party invokes removal for a pending row
- **THEN** the server rejects the action and preserves the row

#### Scenario: Removal races or targets an obsolete id
- **WHEN** both parties remove concurrently or an old removal is submitted after a new request was created
- **THEN** at most the original accepted row is deleted and subsequent operations return stale/unavailable without affecting any newer row

#### Scenario: Cleanup of an unavailable peer
- **WHEN** non-banned A removes an accepted relationship by its known id after the peer becomes banned or blocked
- **THEN** the server permits cleanup if A is a participant without disclosing the peer's private status

### Requirement: List lifecycle and recoverable failures
The friends screen SHALL have distinct loading, empty, content and error states, explicit refresh, and load-more controls while further pages exist. Failed refresh or pagination MUST retain previously loaded content and provide a retry path. Data and pending actions MUST be scoped to the signed-in account, with old asynchronous results ignored after session changes.

#### Scenario: Empty success versus failure
- **WHEN** initial load succeeds with no friends or fails before any content is available
- **THEN** the UI respectively shows an empty-state invitation to find people or an error with retry, never equating an error with an empty list

#### Scenario: Failed next page
- **WHEN** loading another page fails after the first page succeeded
- **THEN** existing rows remain visible and retry resumes from the failed cursor without duplicating rows

#### Scenario: Failed removal and ambiguous timeout
- **WHEN** removal fails or times out without a confirmed response
- **THEN** the UI does not announce success, retains available content and reconciles by read/refresh before allowing another deliberate mutation

#### Scenario: Logout or account switch
- **WHEN** A signs out or switches to C while friends data is loaded or in flight
- **THEN** A's rows, cursors and action state are cleared and late results cannot repopulate C's screen

#### Scenario: View recreated
- **WHEN** the friends view is recreated during an in-flight load
- **THEN** state collection follows the new view lifecycle and does not update destroyed views or duplicate list entries
