# Supabase Connection Management for Spring

Supabase has database connections and pooler connections; Spring/HikariCP adds an application-side pool as well. Tune both as one system so replicas cannot exceed the database connection budget.

## Choose a connection mode

| Deployment | Starting choice |
|---|---|
| Persistent Spring service with IPv6/direct access | Direct connection |
| Persistent service on IPv4-only network | Shared session pooler |
| Short-lived or highly bursty/serverless compute | Transaction pooler, after checking driver limitations |
| Schema migration/native database tooling | Direct connection when network access permits |

Use the exact connection string shown in Supabase Dashboard → **Connect**. Direct and pooler usernames/ports differ.

## HikariCP

- Set a bounded `maximumPoolSize` based on replicas, worker concurrency, and Supabase's connection limit. The fleet total is approximately `replicas × maximumPoolSize`, plus migration, monitoring, and administrative connections.
- Set connection and acquisition timeouts. Fail fast rather than accumulating unbounded request threads.
- Keep minimum idle modest; don't reserve a large idle pool on every replica without need.
- Enable TLS according to the Supabase connection settings and validate certificates in production.
- Store credentials in the deployment secret manager, never in Git or mobile resources.
- Watch active, idle, pending, and timeout metrics together with Supabase connection usage.

## Transaction pooler and JDBC

Transaction pooling can route each transaction to a different server connection. Session state, temporary tables, and session-level advisory locks cannot be assumed to persist between transactions. Supabase documents JDBC `prepareThreshold=0` for transaction mode; verify this and other current limitations before enabling it. HikariCP and server-side pool limits must be sized together.

Keep database connections scoped to the application lifecycle. Never create a new Hikari pool per request. Use short transactions and release connections promptly.

Reference: [Supabase database connection modes](https://supabase.com/docs/guides/database/connecting-to-postgres).
