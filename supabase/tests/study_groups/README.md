# Local group SQL tests

Run `python supabase/tests/study_groups/run_local.py` with Docker running.
The runner creates a fresh `postgres:17-alpine` container with no network and no
published ports, runs real SQL as authenticated/anon roles, then removes only its
own randomly named and labelled container. It accepts no DB URL or target container.
No production connection or credentials are used. Docker may need to pull the image.

The baseline uses the actual profiles/subjects/friendships/blocks CREATE TABLE
definitions from `supabase/schema.sql` plus the existing friendship RPC migration.
`auth.users` and `auth.uid()` are minimal local auth fixtures. This establishes
group contracts on PostgreSQL, not hosted Auth/Storage/Realtime or full snapshot
compatibility. Full-baseline, concurrency, Android, LiveKit and device acceptance
must be reported separately.
