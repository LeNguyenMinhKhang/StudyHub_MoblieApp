# Implementation handoff (2026-10-19)

## Current state

- Kotlin `ApiResponse`/`ErrorType`, Auth migration, Profile read/edit, Home navigation, and device-local avatars are implemented in the worktree. The new SQL is **not applied** to Supabase. The Android client now expects the RPCs in `supabase/migrations/20261018_profile_integrity_and_rpc.sql`, so the app must not be released against the old schema.
- No older StudyHub Android build has been released. The product decision is authenticated-only public profile access with only id, username, display name, avatar URL, bio, and created date.
- The inspected connected schema had two profile rows, no invalid required values or case-insensitive collisions, and no existing Auth/profile trigger. Recheck these counts immediately before applying the migration to each environment. Do not auto-rename or delete legacy identities if a new collision appears.
- No build, unit test, device test, or database acceptance test has passed for the final code. The user will run build and tests later. An attempted isolated offline Gradle run stopped before compilation because its fresh cache lacked the Foojay plugin. An earlier baseline run also encountered a missing `jlink.exe` in a VSCode Red Hat JRE; this is a toolchain/cache issue, not evidence that the new code compiles.

## Development rollout

1. On an isolated development Supabase project, inspect `public.profiles` column types/defaults, grants, RLS policies, Auth triggers, and aggregate invalid/collision counts against the current migration. Confirm `role` and `is_banned` are compatible with the defaults and that `created_at` is `timestamptz`. Keep this inspection free of exported personal rows.
2. Apply the version-controlled SQL only to development. Validate registration with email confirmation enabled and disabled. Each new Auth user must have exactly one profile, and a rejected duplicate/invalid username must not leave an Auth account without a profile. The client must no longer insert `profiles` directly.
3. With authenticated JWTs for two test accounts and an anonymous request, check own-profile RPC, public lookup by ID and username, banned-row visibility, own update, cross-account denial, direct table denial, protected-column denial, and null bio clearing. Review the returned public JSON to ensure administrative fields are absent. Do not use a database-owner or service-role session as proof of client authorization.
4. Run backend unit tests, both Android debug assemblies, and device/emulator checks for rotation, process recreation, Back/drawer discard, account switching, image cancellation/invalid/oversized/oriented input, low storage, partial save/retry, local avatar persistence and public-profile isolation. Reconcile any failures before marking the corresponding OpenSpec tasks done.
5. Only then coordinate any later production migration and app release. Migration must precede the new app because the app calls the new RPCs. A backup and restore rehearsal are prerequisites for production schema changes; no production action is part of this change.

## Rollback and limits

- Rolling back the app requires a matching database/client provisioning strategy. Do not simply drop the Auth trigger while a client relying on it remains in use. Avoid deleting users, profiles or local image files to roll back.
- A safe SQL rollback needs a separately reviewed migration that restores previous grants/policies and direct-write behavior only after client compatibility is proven. The unique index and constraints may be retained if compatible; do not weaken them merely to mask failing data.
- Avatars are stored in each device's no-backup app directory. They are never uploaded, do not sync to another device, and disappear when app data is cleared or the app is uninstalled. A public viewer sees the existing remote avatar URL or placeholder, never another account's device-local override.
- Until the development SQL, builds and device checks pass, the change remains apply-in-progress and must not be archived or described as production-ready.
