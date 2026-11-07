## ADDED Requirements

### Requirement: Durable outbox and bounded dispatch
Every committed inbox mutation SHALL atomically persist a private in_app delivery job. A server-only worker SHALL process bounded due batches and publish minimal invalidation signals on the recipient's private channel. The inbox MUST remain readable while dispatch is unavailable. Job publication status MUST NOT be interpreted as device receipt or user read status.

#### Scenario: Worker unavailable
- **WHEN** notification data commits while the worker is stopped
- **THEN** inbox data and pending jobs remain durable and RPC reads return current authorized data
- **AND** restarting the worker processes due jobs without recreating inbox rows

#### Scenario: Limited dispatch
- **WHEN** more than 100 jobs are due or multiple scheduler executions overlap
- **THEN** one worker processes at most 100 claimed jobs per run, using a concurrency guard and a time bound
- **AND** jobs outside that batch remain eligible for later processing

### Requirement: Retry and failure recovery
The worker SHALL preserve failed work with an attempt count, safe error code and next-attempt time. It SHALL use bounded exponential backoff with jitter, defaulting to five total attempts, then retain dead-letter records for operator inspection/requeue. A process crash MUST NOT permanently strand claimed work. Reprocessing MUST NOT create duplicate inbox entries or change their read state.

#### Scenario: Temporary failure
- **WHEN** publication raises an error before the attempt commits
- **THEN** the job is retried after backoff without a tight retry loop or losing the inbox

#### Scenario: Publication failure is swallowed by the provider function
- **WHEN** realtime.send returns normally after converting an insert error to a warning and no matching Broadcast record exists
- **THEN** the worker treats the attempt as failed, records a safe error code and increments its attempt count
- **AND** claimed jobs enter retry or dead-letter according to the same attempt limit and are not marked published

#### Scenario: Exhaustion and replay
- **WHEN** five publication attempts fail
- **THEN** the job becomes a retained dead-letter visible to operators
- **AND** an authorized operator can explicitly requeue it without duplicating user notifications

#### Scenario: Worker crash
- **WHEN** the worker crashes before its transaction commits
- **THEN** publication/claim/status changes roll back together and the work remains available

### Requirement: Transactional publication confirmation
For each recipient dispatch attempt, the worker SHALL generate a fresh dispatch_id distinct from stable job/event IDs and include it in the Broadcast payload. It SHALL verify that no pre-existing Broadcast record uses that correlation ID and, after sending, confirm exactly one record with that dispatch_id, expected topic, event, schema version, private flag and broadcast extension. Sending, confirmation and publication status updates MUST occur in the same transaction. A missing, mismatched, duplicate or unreadable confirmation MUST fail the attempt. Only the group's claimed jobs SHALL become published after successful confirmation; last_dispatch_id SHALL identify the persisted attempt outcome. The adapter MUST remain server-only and MUST NOT change managed Supabase functions or grant app clients additional data access for verification.

#### Scenario: Confirmed success for a grouped dispatch
- **WHEN** one dispatch coalesces multiple claimed jobs for the same recipient and the expected Broadcast record is confirmed
- **THEN** only those jobs become published with the same dispatch correlation ID when the transaction commits
- **AND** jobs outside the group or inserted later remain unmodified

#### Scenario: Old or unrelated record does not prove publication
- **WHEN** an older record shares the topic or another recipient/attempt has a Broadcast record, but the exact expected confirmation is absent
- **THEN** that record cannot satisfy verification and the current attempt fails safely
- **AND** a retry generates a fresh dispatch_id rather than reusing the previous attempt's identifier

#### Scenario: Confirmation is mismatched or ambiguous
- **WHEN** records matching the correlation ID have the wrong topic/event/schema version/private flag/extension, or multiple exact matches exist
- **THEN** confirmation fails and the group's publication changes roll back before retry/dead-letter status is recorded

#### Scenario: Worker cannot inspect confirmation
- **WHEN** the worker role cannot read the required Realtime columns or the schema is incompatible
- **THEN** no job is reported as successfully published and compatibility verification fails
- **AND** clients are not given broader access as a workaround

#### Scenario: Rollback after confirmation
- **WHEN** a valid Broadcast record is confirmed but the transaction fails before commit
- **THEN** neither the record nor the published job status commits and the job remains eligible for another attempt

### Requirement: Private recipient channels
Realtime channel authorization SHALL allow an eligible authenticated user to receive only their own notification topic. Clients MUST NOT publish to these topics or execute worker/helper functions. Broadcast payloads SHALL contain only invalidation metadata; authorized RPCs SHALL supply notification content and counts.

#### Scenario: Forged subscription or publication
- **WHEN** A subscribes to B's topic, an anonymous caller subscribes, or a client attempts to broadcast
- **THEN** the operation is rejected by server authorization

#### Scenario: Minimal payload
- **WHEN** a notification is published
- **THEN** the signal contains no comment body, actor profile, credentials or unfiltered unread count

### Requirement: Reconnection and session reconciliation
The client SHALL attach its collector before subscription and fetch an authoritative snapshot after subscription succeeds, on reconnect and on foreground re-entry. Events during a fetch SHALL schedule a bounded follow-up fetch. It MUST NOT depend on replay of every Realtime event, event order or exactly-once delivery. Logout/account switch SHALL cancel old channels and discard previous-session state and responses.

#### Scenario: Missed and duplicate signals
- **WHEN** the client misses signals while offline and later reconnects or receives duplicate/out-of-order signals
- **THEN** a snapshot reconciles the current inbox and badge without duplicate items or arithmetic count drift

#### Scenario: Event during initial fetch
- **WHEN** an event arrives after subscription but while the initial snapshot is in flight
- **THEN** a subsequent fetch resolves the change instead of losing it in the subscription/fetch gap

#### Scenario: Account switch during fetch
- **WHEN** A logs out and C signs in while A's fetch or channel callback is pending
- **THEN** A's state is cleared and late responses cannot populate C's screen

### Requirement: Operational monitoring and retention
The system SHALL expose server-only backlog, retry, dead-letter, oldest-due age and publication latency metrics, plus a privacy-preserving first-open aggregate. A runbook SHALL define alert conditions, scheduler verification and controlled requeue. Published jobs SHALL be eligible for bounded cleanup after seven days; pending/retry jobs MUST NOT expire silently and unresolved dead-letter jobs MUST remain inspectable.

#### Scenario: Backlog alarm condition
- **WHEN** any dead-letter exists or the oldest due job is older than 60 seconds
- **THEN** operator health queries flag the condition and the runbook identifies the recovery path
- **AND** no external alert delivery is claimed without an explicitly configured integration

#### Scenario: Retention cleanup
- **WHEN** cleanup runs
- **THEN** only eligible completed job history is removed in bounded batches, leaving inbox, pending, retry and unresolved failure data intact

### Requirement: Verified soft realtime operation
The implementation SHALL verify the actual scheduler and private-channel support before enabling dispatch. Under an agreed normal-load two-account foreground test, the default five-second worker schedule SHALL target inbox/badge convergence within ten seconds. Backlog-related delay SHALL be observable; unsupported scheduler resolution MUST be reported rather than silently presented as equivalent behavior.

#### Scenario: Deployment prerequisite missing
- **WHEN** the target database lacks the required scheduler resolution or the installed SDK cannot join private channels
- **THEN** deployment records the blocker and preserves RPC-based inbox reads without claiming Realtime delivery is complete
