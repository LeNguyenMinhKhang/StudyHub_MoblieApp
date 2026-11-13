## ADDED Requirements

### Requirement: Per-category recipient preferences
The system SHALL provide authenticated own-account preferences for in_app friendship, like and comment categories, defaulting to enabled. Server-side creation MUST honor settings committed before an interaction. Preference changes and notification production SHALL be serialized per recipient to define concurrent outcomes. Opt-out SHALL affect new notifications without deleting history; re-enabling MUST NOT backfill suppressed events.

#### Scenario: Disable a category
- **WHEN** B disables likes and A subsequently likes B's post
- **THEN** the like follows its business contract but no new like notification or corresponding create-delivery job is generated for B
- **AND** existing notifications remain readable

#### Scenario: Re-enable or race
- **WHEN** B re-enables a category or a setting update races with an interaction
- **THEN** old suppressed events are not replayed and concurrent behavior follows the serialized transaction order

#### Scenario: Unauthorized settings access
- **WHEN** another user or an anonymous caller tries to read or change B's preferences
- **THEN** the operation fails without exposing or modifying B's choices

### Requirement: Preferences UI and recovery
The Android settings screen SHALL show the three server-backed category switches with loading and save-error states. It MUST reconcile after an ambiguous save failure, avoid claiming unconfirmed settings are saved, and clear state after session change.

#### Scenario: Save fails
- **WHEN** the user toggles a category and persistence fails or times out
- **THEN** the UI reports the failure and offers reconciliation/retry while showing the last confirmed state

### Requirement: Versioned localized templates
Notifications SHALL carry a stable type/template version and structured source identifiers. Android SHALL render supported templates through localized resources and provide a safe generic fallback for unknown type/version or missing actor. It MUST NOT render untrusted source text as executable markup or require one remote lookup per list row.

#### Scenario: Unknown template or deleted actor
- **WHEN** a legacy or newer notification lacks a supported template version or its actor was deleted
- **THEN** the center renders a safe generic item and handles navigation according to available authorized source data

### Requirement: Recipient dispatch frequency control
The dispatcher SHALL coalesce due invalidation jobs per recipient and enforce a configurable minimum dispatch interval, initially five seconds. Throttling MUST defer or coalesce signals without dropping inbox events or marking jobs outside the claimed batch published. The client SHALL coalesce repeated refresh requests and never count invalidations as individual notifications.

#### Scenario: Interaction burst
- **WHEN** many likes/comments or read updates affect one recipient within the interval
- **THEN** all eligible inbox changes remain durable while dispatch sends at most one signal per configured interval
- **AND** pending work eventually triggers another signal or is covered by explicit RPC reconciliation
