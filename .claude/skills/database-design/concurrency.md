# Transactions, Concurrency, and Locking

PostgreSQL transactions provide atomicity and isolation, but long transactions and conflicting writes can block application traffic.

- Put related database writes in one service-level transaction; keep remote API calls outside the transaction.
- Keep transactions short. Do not wait for user input or slow network I/O while holding row locks.
- Acquire locks in a consistent order across code paths to reduce deadlocks.
- Use optimistic locking (`@Version`) for entities where concurrent edits should be detected rather than silently overwritten.
- Use row-level locking (`SELECT ... FOR UPDATE`) only when an invariant requires serialized updates; ensure the predicate is indexed and lock scope is narrow.
- Handle serialization failures and deadlocks with bounded retries only when the whole operation is safe to retry and idempotent.
- Use unique constraints and atomic SQL updates to enforce invariants under concurrency; a prior read followed by an unguarded write is not sufficient.
- For Supabase transaction pooling, keep all required session state inside one explicit transaction and avoid session-level advisory locks.

Monitor lock waits and deadlocks. Diagnose the blocking transaction before raising timeouts or connection limits.
