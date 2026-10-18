# Advanced Postgres Features in Supabase

Use specialized features when an explicit access pattern needs them and the team can operate them.

- **JSONB:** Keep stable relational fields as typed columns. Add GIN or expression indexes only for queried JSON paths/operators.
- **Full-text search:** Use `tsvector`/text search configuration and matching indexes for language-aware search; don't substitute broad `ILIKE` scans for large datasets.
- **pgvector:** Choose vector dimensions, distance operator, and HNSW/IVFFlat index based on recall, latency, and dataset size. Measure with representative queries.
- **Extensions:** Verify availability and operational implications in Supabase before adding an extension; keep enablement in a reproducible migration where supported.
- **Partitions:** Partition only when table size and retention/query patterns justify the added operational complexity.
- **Database functions and triggers:** Keep them deterministic and narrowly privileged. For `SECURITY DEFINER`, set `search_path` safely and restrict execution grants.
- **Queues and scheduled jobs:** Define idempotency, retry policy, visibility/ownership, and retention before implementation.

Benchmark advanced features against simpler indexed SQL first. Review RLS and grants for every new table, function, or API-exposed view.
