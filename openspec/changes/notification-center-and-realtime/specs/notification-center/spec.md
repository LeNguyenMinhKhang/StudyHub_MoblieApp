## ADDED Requirements

### Requirement: Transactional interaction notifications
The system SHALL persist eligible inbox notifications in the same transaction as friendship, like or comment changes, using server-derived actor and recipient identities. It MUST exclude self interactions, banned or blocked participants, inaccessible source posts and categories disabled by the recipient. A persistence failure MUST roll back the business transaction; deliberate preference suppression MUST NOT fail the business action.

#### Scenario: New interaction
- **WHEN** A likes or comments on eligible B's post and B has that category enabled
- **THEN** the business row, one inbox row for B and its delivery job commit atomically
- **AND** A does not receive a notification for the action

#### Scenario: Ineligible interaction or disabled category
- **WHEN** an interaction is self-directed, its recipient has opted out, or its actor/source fails notification eligibility
- **THEN** no notification is created
- **AND** the business action remains governed by its own authorization contract

#### Scenario: Notification persistence fails
- **WHEN** an eligible business transaction cannot persist its inbox or outbox record
- **THEN** the transaction does not report success or leave an unnotified committed interaction

### Requirement: Source identity and lifecycle
The system SHALL deduplicate inbox creation by recipient, event type and source incarnation. Friendship sends/acceptances SHALL preserve existing transition rules. Cancellation, unlike and comment/post deletion SHALL remove the corresponding notification; editing a comment SHALL NOT create another notification. Replies SHALL notify only the post owner in this version, excluding self actions. Legacy notifications without a provable source link MUST NOT receive guessed source IDs.

#### Scenario: Retry and concurrent events
- **WHEN** the same request, like or comment event is processed repeatedly or concurrently
- **THEN** at most one inbox row exists for that source event and recipient

#### Scenario: Request acceptance and withdrawal
- **WHEN** B accepts A's pending request
- **THEN** B's request notification is marked read and A receives at most one friend_accept if enabled
- **AND** if A instead cancels while pending, the matching request notification is removed

#### Scenario: Source removal and re-creation
- **WHEN** A unlikes and later likes B's post again
- **THEN** the old like notification is removed and the new like incarnation can create one new notification
- **AND** retrying old delivery jobs cannot recreate the removed notification

#### Scenario: Comment edits and replies
- **WHEN** A edits an existing comment or adds a reply to C on B's post
- **THEN** editing creates no new notification and the reply notifies eligible B once, without an additional notification to C

### Requirement: Authorized bounded inbox
Users SHALL list only their own visible notifications through authenticated RPCs. Direct table reads/writes MUST NOT bypass ownership, banned/block checks or source visibility. Lists SHALL support all/unread filters, descending created_at/id keyset pagination, default 20 and maximum 50 rows, with a globally scoped visible unread count independent of page/filter. Invalid filters and malformed cursors MUST be rejected.

#### Scenario: Privacy across access paths
- **WHEN** C or an anonymous caller reads/marks A's notification or attempts a direct insert/update/select
- **THEN** unauthorized access fails without exposing or changing A's notification

#### Scenario: Pagination ties and filtering
- **WHEN** at least 45 visible rows have equal timestamps and the user paginates without intervening changes
- **THEN** each eligible row is returned once with a deterministic id tie-breaker
- **AND** the unread filter affects rows but not the definition of the global unread count

#### Scenario: Visibility changes
- **WHEN** an actor becomes blocked/banned or the recipient loses permission to a source post
- **THEN** subsequent reads exclude those notifications from both rows and unread count
- **AND** affected active recipients receive an invalidation without private block or ban details

### Requirement: Read and open semantics
The system SHALL support marking one or all owned notifications read and recording first-open idempotently. Mark-all SHALL apply to unread rows visible to the server operation's snapshot, not future arrivals. UI mutation failures MUST reconcile server state and MUST NOT silently retain a false successful read state. Opening a notification SHALL navigate to its profile or post; unavailable targets SHALL produce a recoverable generic outcome.

#### Scenario: Read fails or races with refresh
- **WHEN** marking an item read fails or an older list response arrives after a read mutation
- **THEN** the UI reconciles with the server and older responses do not overwrite newer read state

#### Scenario: Mark-all and new arrival
- **WHEN** a notification commits after the mark-all operation's snapshot
- **THEN** that new notification remains unread
- **AND** mark-all does not count as opening each existing notification

#### Scenario: Repeated opening
- **WHEN** the owner opens the same notification repeatedly or retries a timed-out open call
- **THEN** first-open is recorded at most once and the item is marked read
- **AND** a deleted or unauthorized target does not expose cached private content

### Requirement: Recoverable notification center
The Android center SHALL provide all/unread selection, refresh, pagination, loading/empty/error states and mark-all. Existing rows SHALL survive recoverable failures. Home badges and the center MUST use one session-scoped state source. Reconciliation MUST prevent duplicate rows, stale deleted rows and unsafe reuse of a previous cursor after resetting a list.

#### Scenario: User remains on a different screen
- **WHEN** a new notification arrives while the user views another screen within Home
- **THEN** the badge updates and opening the center shows the corresponding current inbox

#### Scenario: Updates while paginating
- **WHEN** insertion, read or deletion events arrive while another page is loading
- **THEN** reconciliation produces a bounded consistent loaded window without duplicate IDs or resurrected rows
- **AND** if the loaded window exceeds the configured 200-row reconciliation limit, the UI exposes a refresh action instead of claiming it is fully current
