# Supabase Grants, Auth, and Row-Level Security

Treat RLS as a database authorization layer for requests that reach Supabase through its Data API with a user JWT. RLS does not replace SQL grants, application authorization, or tests.

## Secure every exposed table

1. Enable RLS for tables in exposed schemas.
2. Grant each role only the SQL operations it needs. RLS policies do not revoke broad grants.
3. Define policies per operation (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) and choose `USING` and `WITH CHECK` deliberately.
4. Index columns referenced by policies and membership checks.
5. Write database tests for both permitted and denied rows and operations; run `supabase test db` when configured.

```sql
alter table public.study_groups enable row level security;

create policy "members read own groups"
    on public.study_groups for select
    to authenticated
    using ((select auth.uid()) = owner_id);

create policy "owners create groups"
    on public.study_groups for insert
    to authenticated
    with check ((select auth.uid()) = owner_id);
```

Use `(select auth.uid())` for a request-constant helper in policy predicates, and index `owner_id`. Test behavior under the actual roles and JWT context, not as a database owner that bypasses RLS.

## Java Spring and direct JDBC

A normal JDBC connection does not automatically receive the end user's Supabase JWT, so `auth.uid()` may be null or not represent the API caller. Do not treat a policy as protecting a Spring request unless the identity context is explicitly and safely propagated for each transaction and verified with pooled-connection tests.

For Spring, verify the bearer token and authorize the operation in the service. Connect using a dedicated least-privilege Postgres role, not a superuser/owner or `service_role`. If the architecture needs database-enforced per-user RLS through Spring, design transaction-scoped claim propagation, prevent claim leakage across pooled connections, and test every allow/deny case before relying on it.

## Secret keys and views

Never put a Supabase secret/service-role key or database password in Android code. Service-role access bypasses RLS. Review views and `SECURITY DEFINER` functions for privilege escalation; set a safe `search_path`, grant `EXECUTE` narrowly, and avoid exposing internal schemas.

Reference: [Supabase Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security).
