# StudyHub Codex Project Rules

## Project map

- `app-user` and `app-admin` are Android application modules written in Kotlin.
- `backend` is a Kotlin Android library that provides shared data access to Supabase. It is not a Java Spring Boot service.
- Build configuration is Gradle Kotlin DSL. Follow the versions and libraries already declared in the project.

## Working rules

- Read the relevant module and nearby code before changing it. Preserve established architecture and naming.
- For app UI changes, follow `.codex/skills/android-kotlin-frontend/SKILL.md` and the existing Compose, Fragment, XML, and ViewBinding patterns in the target module.
- For Supabase/data changes, use `.codex/skills/kotlin-supabase-backend/SKILL.md`; keep network and persistence access in `backend`, use the existing repository/data-source structure, and preserve server-side RLS assumptions.
- Use `.codex/skills/java-spring-backend/SKILL.md` only for a Java/Spring backend module. Do not apply Spring rules to the current Kotlin Supabase module.
- Load other skills from `.codex/skills/<skill-name>/SKILL.md` when their description matches the task. These are synchronized from `.agent/skills` and `.claude/skills`.
- Do not add dependencies, upgrade toolchains, or change public data/API contracts unless the task requires it.
- Keep secrets out of source control. Never treat client-side checks as authorization.
- Run only the verification needed for the requested change and report what was run and any failures.
