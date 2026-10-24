# Java Spring Persistence with Supabase

Use the persistence style already established by the Spring application. Supabase is PostgreSQL, so standard PostgreSQL JDBC, JPA, and Spring Data patterns apply.

| Approach | Use for | Guidance |
|---|---|---|
| Spring Data JPA / Hibernate | Aggregate-oriented domain writes and routine transactional workflows | Keep mappings explicit; control fetch plans; inspect generated SQL |
| JDBC / `JdbcTemplate` | Reporting, bulk operations, specialized SQL, or precise query control | Bind parameters; map rows explicitly; keep transaction boundaries visible |
| Supabase Data API | User-scoped mobile access with Supabase Auth and RLS | Use a supported Kotlin client or HTTPS API; do not expose database credentials |

## Spring rules

- Use constructor injection and Spring-managed transactions (`@Transactional`) at service/use-case boundaries.
- Prefer DTO projections for read-heavy endpoints; do not serialize JPA entities as API contracts.
- Avoid eager relationships by default. Load what each use case needs with projections, entity graphs, or explicit queries.
- Keep schema migrations authoritative. Do not let Hibernate silently mutate a production schema; use validation mode in deployed environments.
- Configure the Postgres driver and HikariCP from environment-based secrets, with TLS and bounded pool sizes.
- Use a dedicated database role for the service. Do not use the Supabase project owner account for normal application traffic.
- Do not assume Postgres RLS sees a Spring user's identity on a regular JDBC connection. Verify the JWT in Spring and enforce authorization there, or explicitly implement a safe transaction-scoped claim propagation design and test it under pooled connections.

## Kotlin rules

Use immutable data classes for DTOs, avoid nullable database fields unless the schema permits null, and make transaction behavior explicit. Coroutines do not make blocking JDBC non-blocking; use the project's established execution model.

For Supabase transaction pooler mode, consult the current JDBC guidance and use `prepareThreshold=0` if required by that mode. Prefer direct/session connections for a persistent Spring/HikariCP workload when supported by network and project plan.
