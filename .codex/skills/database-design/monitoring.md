# Supabase Postgres Monitoring and Diagnostics

Measure the database and application as a connected system.

## Diagnose in order

1. Identify the affected endpoint/job, query, time window, and workload change.
2. Check Spring request latency, Hikari pending/active connections, timeouts, and retry counts.
3. Check Supabase Database/Query Performance views and connection usage.
4. Inspect the SQL plan with `EXPLAIN (ANALYZE, BUFFERS)` in a safe environment.
5. Check lock waits, deadlocks, row estimates, table/index growth, and RLS policy cost.
6. Capture a baseline and compare after one targeted change.

Enable and use `pg_stat_statements` where available. Track query latency and frequency, not just averages; inspect high total-time and high-call-count queries. Redact credentials, personal data, and JWTs from logs. Avoid running diagnostic queries that expose sensitive row contents.

Do not raise pool size blindly: pending requests may come from slow SQL, long transactions, or pooler limits. Use evidence to locate the bottleneck.
