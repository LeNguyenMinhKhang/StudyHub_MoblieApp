# Data Access Patterns

Shape SQL around the endpoint or use case and fetch data in bounded round trips.

- Replace ORM N+1 queries with a projection, batch query, or deliberate fetch plan. Avoid fetching whole object graphs by default.
- Use parameterized queries and bind variables; never construct SQL from untrusted strings.
- Fetch only required columns and paginate on the server.
- Use keyset/cursor pagination for large, changing result sets; use a deterministic indexed order and include a unique tie-breaker.
- Use offset pagination only when page numbers and small result sets make its cost acceptable.
- Batch inserts/updates where it reduces round trips, but use bounded batch sizes and preserve transaction semantics.
- Prefer `INSERT ... ON CONFLICT` for an atomic upsert when its conflict behavior matches the business rule.
- Avoid returning unbounded result sets to Kotlin clients; define limits and stable sorting.

For user-scoped Kotlin access through Supabase Data API, ensure pagination and filters work with RLS and that the policy does not inadvertently hide valid rows. For Spring JPA, inspect generated SQL and use DTO projections for read-heavy views.
