# Supabase Database Architecture

Supabase provides managed PostgreSQL plus optional Data API, Auth, Storage, Realtime, and database extensions. The application still relies on PostgreSQL constraints, SQL, and transaction semantics.

## Access path by application layer

| Caller | Recommended path | Authorization boundary |
|---|---|---|
| Android/Kotlin user client | Supabase Data API with publishable key and the signed-in user's access token | PostgreSQL grants plus RLS policies |
| Java Spring service | JDBC/JPA to Postgres using a server-only, least-privilege database role | Spring verifies identity and enforces application authorization; database grants constrain the role |
| Schema migrations/admin | Supabase CLI or direct connection from trusted deployment tooling | Restricted CI/operator credentials; never bundled with the app |

The publishable/anon key identifies the project and is not a secret. It does not authorize unrestricted data access: RLS and grants must define what clients may do. Secret/service-role keys are privileged and bypass RLS; keep them out of mobile apps, logs, and client-delivered configuration.

## Connection mode

- Long-running Spring service with IPv6 or IPv4 add-on: use the direct connection, or a suitable dedicated pooler on supported plans.
- Persistent service on IPv4-only networks: use the shared session pooler.
- Short-lived/serverless compute: use transaction pooling only after confirming driver compatibility and disabling prepared statements where required.
- Schema migrations and native Postgres tools: use a direct connection when the environment can reach it.

Take host, port, username, and mode from Supabase Dashboard **Connect**. Do not construct a pooler hostname manually.

## Selection rule

Use Supabase Postgres as the system of record unless a requirement calls for a different store. Keep relational data relational; add JSONB or extensions only for concrete query patterns. See [connection-management.md](connection-management.md) before production deployment.
