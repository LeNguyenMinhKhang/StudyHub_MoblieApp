# Supabase Migration Safety

Store schema changes in version-controlled SQL migrations, normally under `supabase/migrations/` when using the Supabase CLI. Apply the same ordered migrations to local, preview, and production environments. Never make an undocumented dashboard-only change that the repository cannot reproduce.

## Migration workflow

1. Inspect the current schema and all dependent Spring/JPA and Kotlin API code.
2. Add a timestamped migration with the smallest safe DDL change.
3. Include grants, RLS policies, indexes, functions, and triggers with the schema change they secure or support.
4. Apply locally and test both allowed and denied access paths.
5. Check compatibility with currently deployed application versions and existing data.
6. Deploy in an expand/migrate/contract sequence for breaking changes.

## Safe rollout

- Add nullable columns or columns with safe defaults first; backfill in bounded batches; add stricter constraints after old rows are valid.
- For renames, add the new column, dual-read/write if required, backfill, switch clients, then remove the old column in a later release.
- For destructive changes, ship application code that stops depending on the object before dropping it.
- Add large indexes using a non-blocking strategy when supported; `CREATE INDEX CONCURRENTLY` cannot run inside a transaction.
- Take backups and validate restore procedures before high-risk data migrations.
- Do not rely on ORM auto-DDL in production. Keep Hibernate schema mode at validation/none according to project convention.

Use Supabase CLI migration status and database tests in CI where configured. Run database and RLS tests against a disposable local/test database, not production.
