## ADDED Requirements

### Requirement: Create a subject-linked study group atomically
The system SHALL allow an authenticated, non-banned user to create a study group with a trimmed name of 1–100 Unicode characters, an optional description of at most 1,000 Unicode characters, and exactly one existing active subject. The creator SHALL become the sole initial member and owner in the same transaction. Group names SHALL NOT require global uniqueness. A failed creation MUST leave neither an orphan group nor a membership.

#### Scenario: Create a valid group
- **WHEN** an eligible user submits valid group information and an active subject
- **THEN** the system creates one group and its owner membership and returns the group detail
- **AND** its member count is one and it appears in the creator's group list

#### Scenario: Reject invalid input or account
- **WHEN** a user submits an empty or overlong name, an overlong description, a missing/inactive subject, or uses an unauthenticated or banned account
- **THEN** the system rejects the request with a safe domain error and creates no group or membership

#### Scenario: Roll back a partial failure
- **WHEN** owner membership insertion fails after group insertion
- **THEN** the entire creation transaction rolls back

### Requirement: Safe creation retries
The system SHALL accept a client-generated request ID scoped to the creator and immutable normalized creation payload. Retrying the same key and payload SHALL return the same active group only if the caller remains a member. Reusing the key with a different payload SHALL fail with a conflict. A key for an archived group or a group the creator has left MUST NOT create a replacement group or disclose private group data.

#### Scenario: Response lost after successful creation
- **WHEN** the client retries after a lost response, including after form restoration, with the original key and payload
- **THEN** the system returns the original group and stores exactly one group and one owner membership

#### Scenario: Concurrent duplicate creation
- **WHEN** two requests use the same creator, key and payload concurrently
- **THEN** both successful responses identify the same group

#### Scenario: Conflicting reuse
- **WHEN** a request reuses a creation key with changed input or the original group has been archived
- **THEN** the system returns conflict or unavailable respectively without creating a new group

#### Scenario: Retry after later group changes
- **WHEN** the original payload is retried after the group metadata changed or its subject became inactive
- **THEN** the system matches the immutable creation payload and returns the same group if the creator remains a member
- **AND** if the creator has lost membership it returns unavailable without private details or a replacement group

### Requirement: Private and bounded group browsing
The system SHALL expose active group details and group-list entries only to current, non-banned members, except for the invitation preview defined by study-group-membership. Group summaries SHALL include group ID, name, subject, caller role, and the count of actual members. Lists SHALL use stable keyset pagination with a default of 20 and a maximum of 50 items, ordered by creation time and ID descending.

#### Scenario: Browse own groups
- **WHEN** a member opens or refreshes the group list
- **THEN** only active groups they currently belong to appear with correct counts and a next cursor when needed
- **AND** pending invitees are excluded from member counts

#### Scenario: Deny access to another group
- **WHEN** an outsider, former member, unauthenticated user or banned user requests private group data
- **THEN** the server returns no private group data even if the caller knows the group ID

#### Scenario: Page a stable dataset
- **WHEN** the user loads consecutive pages while the underlying list is unchanged
- **THEN** entries are not skipped or duplicated, limits are enforced and malformed cursors are rejected

### Requirement: Owner edits and fixed subject association
Only the current owner SHALL update a group's name and description subject to creation validation rules. The subject association SHALL remain fixed after creation. Deactivating a subject SHALL NOT delete or hide existing groups or prevent their membership management. Referenced subjects MUST NOT be physically deleted through a cascading group deletion.

#### Scenario: Update valid group information
- **WHEN** the current owner saves a valid name and description
- **THEN** subsequent reads return the new values and preserve the subject and membership

#### Scenario: Reject unauthorized field changes
- **WHEN** a non-owner edits group metadata or a caller attempts to change subject, owner or creator through the metadata operation
- **THEN** the operation is rejected without changing those fields

#### Scenario: Subject becomes inactive
- **WHEN** an existing group's subject is deactivated
- **THEN** the group remains accessible to members and invitations and membership operations remain available
- **AND** the subject is unavailable for new group creation

### Requirement: Archive a group and revoke pending access
Only the current owner SHALL archive an active group after explicit UI confirmation, including a group with other members. Archiving SHALL atomically revoke pending invitations and make the group unavailable through all user-facing group reads and mutations. Retained database history SHALL NOT grant archived-group access.

#### Scenario: Archive a populated group
- **WHEN** the owner confirms dissolution
- **THEN** the group is archived and all pending invitations are revoked in one transaction
- **AND** subsequent requests omit it from group and invitation lists and deny private detail access

#### Scenario: Accept races with archive
- **WHEN** an invitation response and archive execute concurrently
- **THEN** an archive committed first prevents acceptance; an acceptance committed first is followed by archive revoking group access
- **AND** no accepted response can make an archived group accessible

### Requirement: Group UI handles refresh and session changes
The Android group screens SHALL provide loading, empty, content and recoverable error states. They SHALL refresh on entry/resume, manual refresh and successful mutations; preserve input and creation keys after failures; and prevent duplicate in-flight submissions. The system SHALL clear private state on logout/account switch and discard results from obsolete sessions. The first version SHALL NOT claim immediate Realtime screen revocation.

#### Scenario: Network failure and retry
- **WHEN** creation or list loading fails due to connectivity
- **THEN** the UI shows a safe retry action, retains form values and the creation key, and avoids treating failure as an empty successful list

#### Scenario: Membership revoked while screen is open
- **WHEN** the next refresh or mutation reports that the caller no longer has access
- **THEN** the UI clears private group details and returns to the group list with an understandable message

#### Scenario: Account switch during a request
- **WHEN** the active account changes before a previous account's request completes
- **THEN** the previous response does not populate the new account's group screens

### Requirement: Isolate study groups from existing features
Group creation and membership operations SHALL NOT create text conversations, alter direct-chat contracts or add notification-center events. Invitations and ongoing group calls SHALL be discoverable within the group tab. LiveKit dependencies SHALL be scoped to the new calling integration and pinned to compatible versions. Existing direct messaging and notification behavior SHALL remain compatible.

#### Scenario: Create and join without chat side effects
- **WHEN** a group is created and an invitee joins
- **THEN** the resulting records belong to the study-group feature and no conversation or notification-center event is created by those operations

### Requirement: Start and join one live call per group
Any authenticated, non-banned current group member SHALL start or join a voice/video call in an active group. Each group SHALL have at most one starting, active or ending call. A call SHALL have a unique provider room identity that is not reused by later calls. The initial demo SHALL allow at most six admitted/connected users per call without limiting group membership to six. Pending invitees and outsiders MUST NOT obtain a room token.

#### Scenario: Start or join a group call
- **WHEN** a current member chooses voice or video calling
- **THEN** the system creates or returns the group's current joinable call after server authorization
- **AND** voice starts with camera disabled and video capture requires the user's camera permission and action

#### Scenario: Concurrent starts or lost response
- **WHEN** members start calls concurrently or retry an already submitted start request
- **THEN** the system returns the same current session and does not create parallel rooms

#### Scenario: Full room or ineligible caller
- **WHEN** the call has six admitted/connected users or the caller lacks active membership/account eligibility
- **THEN** the server refuses a new admission with a safe capacity/access error without granting media access

#### Scenario: Call banner refresh
- **WHEN** a member views group details while another member starts or ends a call
- **THEN** an authorized refresh performed at most every ten seconds while that screen is active updates the call banner when the server is reachable
- **AND** no background incoming-call ringing is implied

### Requirement: Voice and video controls reflect actual media state
Connected users SHALL mute/unmute their own microphone, enable/disable their camera, switch supported cameras, select available audio routes and leave the call. Remote participant views SHALL reflect published media. Mic and camera permissions SHALL be requested independently. Denied camera permission SHALL allow audio participation; denied mic permission SHALL allow listening/viewing with mic off. The app MUST NOT auto-enable previously denied capture.

#### Scenario: Participant toggles camera and microphone
- **WHEN** a connected user changes a media control with the needed permissions
- **THEN** the local UI and remote media reflect the resulting publish/mute state and failures are reported without claiming success

#### Scenario: Camera permission denied
- **WHEN** a user declines camera permission
- **THEN** they can remain in the call using permitted microphone/audio reception and retry camera permission only through a user action

#### Scenario: Audio route changes
- **WHEN** a supported headset connects/disconnects or the user chooses speaker/earpiece
- **THEN** the call uses the available selected/fallback route and continues without creating a second session

### Requirement: End and reconcile call sessions
The current group owner or the call initiator who remains a member SHALL end a call for everyone after confirmation. Others SHALL only leave their own call participation. Ending SHALL deny new admissions before media shutdown and SHALL remain pending until provider cleanup is confirmed. Provider failures MUST NOT be reported as successful termination. Empty calls SHALL become eligible for cleanup after sixty seconds with reconciliation at most every thirty seconds when infrastructure is healthy. Duplicate or delayed webhooks MUST NOT reopen ended calls or affect a newer room generation.

#### Scenario: Initiator leaves while others remain
- **WHEN** the initiator leaves call participation without ending the call
- **THEN** other participants remain connected and the current group owner retains end-for-all authority

#### Scenario: End the call for all
- **WHEN** an authorized controller confirms end-for-all
- **THEN** the call becomes ending, new joins are refused, existing participants and issued admissions are revoked and the room is closed
- **AND** it is marked ended only after provider confirmation; cached tokens cannot recreate the ended call

#### Scenario: Provider unavailable during cleanup
- **WHEN** ending or access-revocation work fails at the provider
- **THEN** durable jobs retry and the UI reports pending cleanup rather than completed shutdown
- **AND** the affected session refuses new joins and media-control changes until reconciled

#### Scenario: Duplicate or out-of-order provider event
- **WHEN** the server receives a duplicate webhook or a stale room event
- **THEN** signature verification, deduplication and room-version checks preserve the current authoritative call state

### Requirement: Preserve voice and video sessions across Android lifecycle events
An ongoing call SHALL retain its session when the user opens the in-app PDF editor or rotates the device. The app SHALL use appropriately declared foreground service permissions for an ongoing microphone session when backgrounded, pause background camera publishing, and provide leave/return controls. Final disconnect, logout or account switch SHALL release media resources. Process death MUST NOT silently restart microphone or camera. Screen capture and MediaProjection SHALL NOT be required by the PDF feature.

#### Scenario: Annotate while talking
- **WHEN** a connected user opens a group PDF and writes a note
- **THEN** permitted audio/video continue without creating a second call and document tools remain usable

#### Scenario: Rotate reconnect or switch account
- **WHEN** the UI rotates or a temporary network interruption occurs
- **THEN** it reattaches to the session or shows reconnecting without duplicate tracks
- **AND** leaving, logging out or switching accounts disconnects the old session before displaying the next account

### Requirement: Private group PDF library with validated uploads
Current eligible members SHALL upload and read PDFs in a private group library independently of meeting sessions. Files SHALL become visible only after server-verified validation and finalization with a current permission check. The initial limits SHALL be 20 MiB and 100 pages per file; corrupt, encrypted, non-PDF or over-limit inputs SHALL be rejected. Retrying the same upload request SHALL NOT create duplicate ready documents. The uploader or current group owner SHALL manage new versions and archive a document.

#### Scenario: Share a valid PDF
- **WHEN** a member uploads a valid in-limit PDF and finalization succeeds
- **THEN** the group's members can open it from the document list during or outside a call
- **AND** source bytes are stored privately with an immutable object key and content hash

#### Scenario: Upload fails or membership is revoked
- **WHEN** upload/validation fails or the uploader loses membership before finalization
- **THEN** no ready document is exposed, unsafe finalization is rejected and expired pending/orphan data is cleaned up

#### Scenario: Reject unsupported file
- **WHEN** a file is corrupt, encrypted, not actually PDF or above the size/page limits
- **THEN** the application reports the supported limits without publishing it or executing embedded content

### Requirement: Bind annotations to immutable document versions
Every annotation SHALL reference an exact document version and page. Uploading a new version MUST NOT overwrite source bytes or move existing annotations to different pages. Previous versions and saved annotations SHALL remain accessible to authorized members through version history while the document is active. Archiving a document SHALL deny future reads, edits and new exports without ending unrelated calls.

#### Scenario: Replace the shared document
- **WHEN** an authorized uploader or owner adds a new version
- **THEN** viewers of the old version retain their version context and see that a newer version is available
- **AND** the new version starts with its own annotation set

#### Scenario: Reopen after the meeting
- **WHEN** members end a call and later reopen the same document version
- **THEN** all committed notes and marks remain, including those authored by members who have since left

### Requirement: Draw highlight and create editable text notes
Authorized members SHALL create ink strokes, translucent rectangular highlights and anchored text notes. Ink SHALL support at most 4,096 points per stroke, an operation payload SHALL be at most 64 KiB, a note SHALL contain at most 2,000 Unicode characters, and a version SHALL have at most 10,000 active annotations. Input validation SHALL reject invalid page indices, style values, out-of-bounds coordinates and version mismatches. The first version SHALL allow editing/deleting annotation objects without modifying original PDF text/layout; highlight SHALL work by selecting a region without OCR.

#### Scenario: Multiple annotation tools
- **WHEN** members draw a stroke, highlight a region and save a text note
- **THEN** each becomes a distinct attributed annotation visible at its intended page position

#### Scenario: Edit erase or undo
- **WHEN** an authorized author or owner edits/deletes an annotation, or an author undoes their own unconflicted operation
- **THEN** the server applies a version-checked mutation and all synchronized views reflect it
- **AND** undo does not overwrite a later edit by another user

#### Scenario: Geometry remains aligned
- **WHEN** the same version is opened at different zoom levels, device sizes or orientations
- **THEN** the marks stay aligned with the same page content using canonical page coordinates

### Requirement: Durable and concurrent annotation mutations
The server SHALL commit an annotation mutation, its idempotent operation result and an ordered document revision event in one transaction. Different annotation IDs SHALL support concurrent edits independently. Mutations of the same annotation SHALL use expected row versions and report conflicts without silently overwriting accepted changes. Deletes SHALL retain tombstones sufficient to prevent stale retries from resurrecting data. Successful retries SHALL be deduplicated only after current authorization is checked.

#### Scenario: Different members draw together
- **WHEN** two members submit different stroke IDs at the same time
- **THEN** both strokes are saved and neither replaces the other

#### Scenario: Concurrent note changes
- **WHEN** two authorized users edit the same note using the same expected version
- **THEN** only one mutation succeeds and the other receives a conflict while preserving its local draft

#### Scenario: Response lost after commit
- **WHEN** the client retries a committed operation with the same operation ID and payload
- **THEN** it receives the recorded result without duplicate marks
- **AND** reuse with different content is rejected as a conflict

#### Scenario: Stale delete update or undo
- **WHEN** a stale operation targets an annotation that has since changed or been deleted
- **THEN** the server rejects the conflicting operation without restoring or overwriting the current state

### Requirement: Realtime updates with complete catch-up
Committed annotations SHALL synchronize between online authorized viewers, targeting two seconds on healthy infrastructure, with a revision-check fallback every five seconds while the viewer is active. Sync SHALL occur when a stroke/highlight completes or a note is saved, without requiring per-pointer-motion streaming. Initial loading and reconnect SHALL use a consistent snapshot plus ordered deltas; missing history SHALL trigger a fresh snapshot. Repeated, missed or reordered delivery MUST NOT cause permanent data loss or duplication. Realtime messages MUST NOT be the sole persistence mechanism.

#### Scenario: Online annotation update
- **WHEN** a member completes and successfully saves a stroke, highlight or note
- **THEN** other online viewers receive it and the author sees Saved only after the server acknowledges persistence

#### Scenario: Join late or reconnect
- **WHEN** a viewer joins after annotations exist or reconnects after missing events
- **THEN** snapshot/delta catch-up reconstructs the latest committed state without missing or duplicate marks

#### Scenario: Changes occur during snapshot paging
- **WHEN** others edit while the client downloads a paginated snapshot
- **THEN** all snapshot pages share a fixed revision and newer changes are applied afterward in revision order

### Requirement: Honest offline pending and session isolation
Offline edits SHALL remain visibly pending in app-private storage scoped to the account and document version. Reconnection SHALL revalidate permission and replay the same operation IDs. Conflict SHALL preserve the user's draft; revoked access SHALL stop replay. Account switch/logout SHALL prevent old drafts, file caches and responses from appearing under a new account. User-initiated disposal of unsaved work SHALL be explained before it is discarded.

#### Scenario: Connection lost during editing
- **WHEN** a user edits without a working connection
- **THEN** the editor labels the changes Unsynced and queues them without claiming they are saved on the server

#### Scenario: Permission revoked before replay
- **WHEN** a user reconnects after being removed from the group
- **THEN** the server rejects queued edits and the client locks the editor and clears inaccessible private state according to session cleanup rules

### Requirement: Optional guided reading without exclusive editing
Members SHALL read and zoom independently by default. During a call a single eligible guide SHALL be able to select a shared document version/page for users who explicitly enable Follow guide. Followers SHALL be able to return to independent reading. Guiding SHALL NOT grant exclusive annotation rights or force other users' zoom. Guidance SHALL end when the guide loses eligibility or the call ends, while documents and saved annotations persist.

#### Scenario: Follow or read freely
- **WHEN** the guide changes page
- **THEN** opted-in followers navigate to that document version/page and independent readers remain where they were
- **AND** all eligible users can continue annotating their selected pages

### Requirement: Export a consistent annotated PDF copy
An authorized reader SHALL export an independent PDF copy of an exact source version/hash and committed annotation revision. A server snapshot SHALL fix export content across pages even when live edits continue. Pending/conflicted local changes SHALL require synchronization or explicit selection to export committed data only. Source files MUST NOT be overwritten. Export SHALL use bounded resources with progress, cancellation and cleanup; permission SHALL be rechecked before publishing the completed file.

#### Scenario: Export while collaborators edit
- **WHEN** a user exports while others add or change annotations
- **THEN** the file contains one consistent committed snapshot identified by version/revision, not a mixture of revisions

#### Scenario: Export contains full text notes
- **WHEN** ink, highlights and long Unicode text notes are exported
- **THEN** marks retain correct page placement and transparency, numbered note pins link to an appendix with complete wrapped text, page reference and author
- **AND** the PDF opens in an external viewer without requiring StudyHub

#### Scenario: Export with unsynced work
- **WHEN** the user has pending or conflicting local annotations
- **THEN** the UI offers sync first or explicit committed-only export and never silently labels unsaved marks as included

#### Scenario: Export canceled or permission lost
- **WHEN** export fails, is canceled or the user loses document access before completion
- **THEN** temporary/partial output is cleaned up and no completed export is reported

#### Scenario: Flat PDF limitations
- **WHEN** the user chooses annotated export
- **THEN** the UI identifies it as a flattened visual copy; preservation of searchable original text, editable annotation objects, interactive forms or digital signatures is not promised
- **AND** the original PDF and editable StudyHub annotations remain unchanged

### Requirement: Demo quota failures and integrated verification
The app SHALL handle LiveKit capacity, quota and connectivity failures with safe retryable/status messages and SHALL NOT upgrade paid plans or enable recording automatically. PDF Storage/Realtime usage SHALL be tracked separately from LiveKit media usage. Functional acceptance SHALL include real provider media between at least three test devices/accounts; fake-provider and build results alone MUST NOT establish successful calls, collaborative editing or export.

#### Scenario: Free quota exhausted
- **WHEN** the provider rejects room creation or connection because the free allowance is exhausted
- **THEN** the UI explains the unavailable calling service, resolves stale reservations and does not corrupt group membership or leave a false active call

#### Scenario: Verify real media and collaborative documents
- **WHEN** the feature is evaluated with configured test infrastructure
- **THEN** separate receiving devices demonstrate audible speech, visible camera frames, shared PDF annotations received across devices, persistence after the call, externally readable export and access revocation
- **AND** unavailable provider credentials or devices are reported as an unverified acceptance layer
