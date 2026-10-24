---
name: database-design
description: Supabase Postgres design, security, migrations, and performance for Java Spring backends and Kotlin clients. Use for schema, SQL, JPA/Hibernate, RLS, connection pooling, or database troubleshooting.
---

Read `.gemini/rules/PROJECT.md` and `.gemini/rules/ANDROID_STUDIO.md` before applying this skill. Use the actual tools available in Android Studio. Read supporting files beside this SKILL.md unless a repository-relative path is explicit.


# Supabase Postgres for Java Spring and Kotlin

Use this skill for every database change: schema and SQL, migrations, JPA/Hibernate mappings, indexes, RLS policies and tests, database functions, connection setup, or performance/security reviews.

## Architecture rules

- Treat Supabase as managed PostgreSQL. Prefer the existing project database and migration workflow.
- Keep schema, grants, RLS, and database functions in version-controlled SQL migrations under `supabase/migrations/` when Supabase CLI is configured.
- Kotlin mobile clients should use the Supabase Data API and user-scoped Auth token for direct client access. Enable RLS on every exposed table.
- Java Spring should keep privileged database access on the server. Verify user identity at the API boundary; a JDBC connection does not automatically carry the caller's Supabase JWT into PostgreSQL RLS. Do not assume `auth.uid()` identifies a Spring API caller unless the application deliberately and safely propagates verified claims within each transaction.
- Never ship database passwords, secret keys, or service-role credentials in Android code. Service-role credentials bypass RLS; use only for narrowly scoped trusted server operations.
- Choose one explicit authorization model per access path: user JWT through Supabase Data API with RLS, or backend authorization using a least-privilege Postgres role. Avoid accidentally treating a privileged JDBC role as RLS-protected.

## Read the relevant reference

| Topic | File |
|---|---|
| Supabase/Postgres architecture | `database-selection.md` |
| Spring Data JPA, JDBC, HikariCP | `orm-selection.md` |
| Tables, constraints, RLS-ready schema | `schema-design.md` |
| Index selection | `indexing.md` |
| Supabase CLI migration safety | `migrations.md` |
| Query plans and Hibernate access | `optimization.md` |
| Supavisor, pool sizing, JDBC settings | `connection-management.md` |
| RLS, grants, Auth and policy tests | `security-rls.md` |
| Transactions, locks and retries | `concurrency.md` |
| Batching and pagination | `data-access.md` |
| Monitoring and diagnostics | `monitoring.md` |
| JSONB, full text, pgvector | `advanced.md` |

## Supabase Postgres priority groups

| Priority | Category | Typical concern |
|---|---|---|
| Critical | Query performance | Missing indexes, inefficient plans, excessive scans |
| Critical | Connection management | Hikari pool sizing, Supavisor modes, connection exhaustion |
| Critical | Security and RLS | Grants, row policies, user identity and secret handling |
| High | Schema design | Types, constraints, relationships and indexes |
| Medium-high | Concurrency and locking | Transaction length, deadlocks, contention |
| Medium | Data access patterns | N+1, batching, pagination and bounded results |
| Low-medium | Monitoring and diagnostics | Query insights, plans, connection and lock metrics |
| Low | Advanced features | JSONB, search, pgvector, partitions and extensions |
## Priority when reviewing

1. Data exposure, grants and RLS correctness.
2. Query performance and connection exhaustion.
3. Schema and migration safety.
4. Transaction behavior, N+1 queries and access patterns.
5. Monitoring and specialized Postgres features.

Use the source Supabase Postgres skill for detailed rule discovery: [supabase/agent-skills](https://github.com/supabase/agent-skills/tree/main/skills/supabase-postgres-best-practices). Load its rule references when available; this project guide adapts its eight categories to Java Spring and Kotlin.

