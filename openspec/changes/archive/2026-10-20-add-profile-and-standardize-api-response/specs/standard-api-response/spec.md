## ADDED Requirements

### Requirement: Unified repository results
All authentication and profile repository operations SHALL return a typed ApiResponse success or failure with a per-operation traceId. Loading SHALL remain UI state. Successful no-payload operations SHALL use Unit. The contract SHALL NOT change Supabase wire responses.

#### Scenario: Successful profile read
- **WHEN** a profile read succeeds
- **THEN** the consumer receives typed profile data and a traceId with no error payload

#### Scenario: Failed operation
- **WHEN** a repository operation fails
- **THEN** the consumer receives a structured error and traceId without Throwable or raw provider messages

### Requirement: Central error definitions
The system SHALL define immutable ErrorType enum entries containing stable code, safe user message, technical log template, severity and retryability. Per-occurrence field errors and diagnostics SHALL be separate from enum instances.

#### Scenario: Concurrent failures of the same type
- **WHEN** two operations encounter the same error type concurrently
- **THEN** they share the error definition but retain distinct trace IDs and occurrence metadata without overwriting each other

#### Scenario: Username conflict
- **WHEN** the provider reports a recognized username uniqueness violation
- **THEN** the mapper returns USERNAME_TAKEN with a username field error rather than an email-registration error

### Requirement: Safe centralized exception handling
The repository execution boundary SHALL map recognized structured exceptions, use a safe fallback for unknown failures and preserve coroutine cancellation.

#### Scenario: Operation cancellation
- **WHEN** a coroutine operation is cancelled
- **THEN** cancellation propagates without a failure response, retry prompt or error log

#### Scenario: Unknown provider failure
- **WHEN** a provider returns an unrecognized exception containing technical details
- **THEN** the UI receives UNKNOWN_ERROR and a safe message without those raw details

### Requirement: Sanitized correlated diagnostics
The system SHALL log handled failures once at the owning boundary with traceId, allowlisted operation name, error code and severity. Logs SHALL exclude passwords, tokens, profile content, raw request/response bodies and unsanitized exception text.

#### Scenario: Sensitive error details
- **WHEN** an exception contains a token or personal payload
- **THEN** logging removes those values while retaining safe correlation information

### Requirement: Auth consumers honor operation outcomes
Auth UI SHALL distinguish signed-out state from transient refresh failure and SHALL honor failed sign-out responses. All Resource consumers SHALL migrate before Resource is removed.

#### Scenario: Sign-out failure
- **WHEN** sign-out fails and the session has not been invalidated
- **THEN** UI reports the failure and does not claim successful logout

#### Scenario: Session profile refresh loses network
- **WHEN** an existing session cannot refresh its profile due to a network failure
- **THEN** UI offers recovery without treating the network failure itself as proof that authentication is invalid
