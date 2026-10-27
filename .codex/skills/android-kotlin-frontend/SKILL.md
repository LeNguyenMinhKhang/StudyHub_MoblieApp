---
name: android-kotlin-frontend
description: Use when building or changing StudyHub Android UI and application logic in app-user or app-admin, including Kotlin, Jetpack Compose, XML layouts, ViewBinding, navigation, ViewModels, coroutines, and UI state.
---

# Android Kotlin Frontend

## Project fit

- `app-user` and `app-admin` are Android application modules. The shared `backend` module is Kotlin data access for Supabase, not a Java Spring service.
- Follow the module's existing UI approach. `app-user` uses Compose and also has ViewBinding/XML and Fragment navigation; `app-admin` currently uses Compose. Do not migrate UI frameworks as part of a feature.
- Keep shared data and Supabase access in `:backend`; keep screen-specific presentation in the consuming app module.

## Kotlin and Android

- Prefer small, idiomatic Kotlin types, immutable values, null safety, and explicit types where they improve API clarity.
- Prefer `val`; use `var` only for state that must change. Keep nullable types explicit and handle platform or decoded data at the boundary instead of propagating unsafe `!!` assertions.
- Use extension functions when they make a focused operation clearer; avoid extensions that hide I/O, mutation, or broad framework behavior.
- Keep Android framework references out of reusable domain or data logic where practical. Do not retain Activity, Fragment, View, or Context references beyond their lifecycle.
- Use lifecycle-aware collection for Flow and observe UI state through the established ViewModel pattern. Tie coroutine work to an appropriate lifecycle scope; avoid `GlobalScope`.
- Keep coroutine work structured and cancellable. Do not block the main thread with `runBlocking`; do not launch detached work for a request that should stop with its owner.
- Represent loading, success, and failure explicitly. Do not swallow exceptions or expose raw stack traces to users.
- Preserve existing navigation patterns, resource conventions, accessibility labels, and localization approach. Avoid hard-coded user-facing strings when resources are used nearby.

## Compose and XML

- In Compose, keep composables focused, state-driven, and side-effect free. Hoist state to the lowest shared owner that needs it; use effects only for lifecycle-bound side effects.
- Keep a unidirectional flow: UI events go to the state owner, and rendering consumes state. Do not launch network work during composition or use effects as a substitute for event handling.
- Collect Flows with lifecycle-aware APIs where available. Make loading, empty, content, and error states explicit when the screen can reach them.
- For XML/ViewBinding screens, follow existing Fragment lifecycle cleanup and binding patterns. Do not access a binding after the view lifecycle ends.
- Keep UI rendering separate from network/database work. Handle empty, loading, error, and populated states when relevant.

## Gradle and Android toolchain

- Before build edits, inspect `settings.gradle.kts`, the affected module build file, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, and relevant `gradle.properties` entries.
- Reuse the project's version catalog, plugin aliases, BOMs, and module boundaries. Do not add duplicate version declarations or upgrade Gradle, AGP, Kotlin, SDK levels, or dependencies without a task requirement.
- Confirm a proposed plugin or DSL change against the actual AGP/Kotlin/Gradle versions and module type; a successful pattern for KMP may not apply to a plain Android module.
- The upstream Kotlin AGP 9 migration guidance targets KMP-to-Android migrations. StudyHub currently uses regular Android application/library modules; do not split or convert modules based on KMP-only instructions. Consult AGP 9 guidance only for a specifically relevant migration or compatibility issue.

## Data and security boundaries

- Call repositories or the existing backend API from ViewModels; do not query Supabase directly from a composable or view.
- Never embed service-role keys, passwords, or private credentials in source, resources, or client builds. Treat the Supabase anon key as a public client key and rely on server-side RLS for authorization.
- Validate user input at the UI boundary for helpful feedback, while preserving authoritative validation and authorization at the data/backend boundary.

## Change discipline

- Match existing package and naming conventions and make the smallest coherent change.
- Add or update focused tests when the change has testable state or business behavior, using the test libraries already configured in the module.
- Do not change Gradle/plugin/dependency versions unless the task requires it.

## Source material

These rules synthesize applicable Kotlin and Android guidance from [Kotlin Agent Skills](https://github.com/Kotlin/kotlin-agent-skills) and the Kotlin/Spring guidance in [GitHub Awesome Copilot](https://github.com/github/awesome-copilot/tree/main/skills/kotlin-springboot). Framework-specific JPA, KMP, and server rules are intentionally scoped to their matching skills or tasks.
