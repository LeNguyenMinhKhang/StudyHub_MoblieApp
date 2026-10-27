# Indexing Supabase Postgres

Indexes can turn a large scan into a selective lookup, but consume storage and add write cost. Add them for measured or clearly expected query patterns, then confirm with `EXPLAIN (ANALYZE, BUFFERS)` on representative data.

## Index common access paths

- Index foreign-key columns used in joins, authorization policies, and deletes. PostgreSQL does not automatically index the referencing side of every foreign key.
- Index columns used in frequent filters, joins, and ordering. Use composite indexes that match the actual predicate/order sequence.
- Put equality-filtered columns before range/order columns when the workload supports that order; verify with the query plan.
- Use partial indexes when a stable predicate selects a small, frequently queried subset.
- Use GIN for queried JSONB containment/array or full-text patterns; use GiST or specialized vector indexes only for matching operators and workload.
- Avoid duplicate and low-value indexes. Check existing indexes before adding another.

```sql
-- Query: WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?
create index study_groups_owner_created_idx
    on public.study_groups (owner_id, created_at desc);
```

For RLS, index columns used to match the authenticated subject (for example `owner_id`) and membership joins. Index policy helper tables as well as the protected table. Do not index every column “just in case.”

For Supabase, use Query Performance/Database Advisors and PostgreSQL plans; keep index creation in reviewed migrations. Large production indexes may need `CREATE INDEX CONCURRENTLY`, which cannot run inside a transaction block—plan the migration accordingly.
