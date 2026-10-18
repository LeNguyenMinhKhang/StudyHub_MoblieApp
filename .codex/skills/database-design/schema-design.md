# Supabase Postgres Schema Design

Model invariants in PostgreSQL so every access path observes the same data rules.

## Tables and columns

- Use a schema with deliberate exposure. Keep internal tables and helper functions outside API-exposed schemas when possible.
- Use `uuid` for IDs that must be generated across clients or services; use identity columns where sequential local identifiers are appropriate.
- Use `timestamptz` for instants. Store timestamps in UTC and format them at the application boundary.
- Prefer appropriate native types and `NOT NULL`, `CHECK`, `UNIQUE`, and foreign-key constraints over application-only validation.
- Make delete behavior explicit (`RESTRICT`, `CASCADE`, or `SET NULL`) based on ownership and retention rules.
- Add `created_at`/`updated_at` only where lifecycle tracking is useful; define how `updated_at` is maintained.
- Avoid storing relational data in JSONB. Use JSONB for genuinely flexible documents that are queried as documents.

## Example

```sql
create table public.study_groups (
    id uuid primary key default gen_random_uuid(),
    owner_id uuid not null references auth.users(id) on delete cascade,
    name text not null check (length(trim(name)) between 1 and 120),
    created_at timestamptz not null default now()
);

alter table public.study_groups enable row level security;
create index study_groups_owner_id_idx on public.study_groups (owner_id);
```

The table is not safe for API exposure until grants and policies are defined and tested. See [security-rls.md](security-rls.md).

## Spring mapping

Map only the columns needed by the domain model. Keep database entities separate from API DTOs. If schema changes are made by Supabase migrations, configure Hibernate to validate rather than create or update production tables.
