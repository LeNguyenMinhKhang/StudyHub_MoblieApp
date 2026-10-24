---
name: java-spring-backend
description: Use when implementing or reviewing a Java or Kotlin backend service built with Spring Boot, including REST APIs, transactions, persistence, security, or tests. Do not apply to StudyHub's Kotlin Supabase data module or Android app code.
---

Read `.gemini/rules/PROJECT.md` and `.gemini/rules/ANDROID_STUDIO.md` before applying this skill. Use the actual tools available in Android Studio. Read supporting files beside this SKILL.md unless a repository-relative path is explicit.


# Spring Boot Backend (Java or Kotlin)

## Applicability

- First inspect the target module's build files, source language, framework version, persistence stack, security setup, and tests. Apply these rules only to a Java/Kotlin Spring service or where the requested design explicitly targets Spring.
- StudyHub currently has no Java/Spring backend. Its `backend` module is Kotlin Android library code that calls Supabase; use the Kotlin project conventions for it instead.
- Follow the versions and patterns already declared by the target project. Do not assume Spring Boot 4, Spring Security 7, Java 21, or upgrade dependencies without a task requirement. Framework APIs and testing annotations differ by Spring Boot generation.

## Architecture

- Keep HTTP concerns at the web boundary, business rules in the established service/use-case or domain layer, and persistence behind repositories or focused data-access components. Respect the existing module/package architecture instead of forcing a new one.
- Use constructor injection and immutable dependencies (`private val` in Kotlin). Avoid field injection and service-locator patterns.
- Define request/response DTOs at API boundaries; do not expose persistence entities as public API contracts.
- Keep request and response models distinct when their validation or data exposure needs differ. Use Kotlin `data class` for DTOs where appropriate, not automatically for persistence entities.
- Keep controllers/adapters free of business workflows and repositories free of business policy. Centralize error mapping using the service's established contract.
- Keep methods cohesive and avoid adding abstraction layers without a concrete need.

## API and validation

- Validate external input at the boundary with the project's established validation mechanism. Return consistent, documented error responses through the existing exception handling convention.
- Use accurate HTTP status codes and preserve established response envelopes. Do not invent a second error or response format within an existing service.
- Keep controllers thin. Avoid database calls, business workflows, or authorization policy hidden in controller methods.

## Persistence and transactions

- Use parameterized queries or the project's ORM; never build SQL by concatenating untrusted input.
- Define transaction boundaries around coherent business operations at the existing application/service boundary. Inspect propagation and rollback behavior before changing it; do not assume an annotation takes effect on self-invocation through a Spring proxy.
- A local database transaction does not make remote HTTP/Supabase/provider calls atomic. Avoid holding transactions open across slow network calls and use explicit consistency patterns when required.
- Use migrations when the repository already uses a migration tool. Do not silently edit production schemas from application startup code.
- Consider pagination and query shape for collection endpoints; avoid unbounded reads and N+1 access patterns.
- If the project uses JPA/Hibernate, keep entities separate from transport DTOs, initialize collection fields, avoid including mutable fields or lazy relations in `equals`/`hashCode`/`toString`, and select fetch plans based on observed query needs. Never use `data class` as a default JPA entity pattern.
- Apply JPA-specific plugin, proxy, identifier, locking, and migration advice only after confirming the persistence provider and versions in the project.

## Security and configuration

- Enforce authorization on the server for every protected operation; authentication alone does not establish permission to access a resource.
- Store credentials and environment-specific values in the established secret/configuration system. Never commit secrets or return internal exception details to clients.
- Distinguish unauthenticated (401) from authenticated-but-forbidden (403) responses, consistent with the API contract. Never log passwords, bearer tokens, signing keys, or sensitive claims.
- Use least-privilege access and the project's established CSRF/CORS/session or token configuration. Do not disable CSRF, weaken authorization, or introduce permissive defaults without verifying the client model and threat context.
- Derive JWT/OAuth2 configuration and claim/role mapping from the actual identity provider. Do not invent a first-party token system when the project delegates auth to an identity provider.

## Testing and changes

- Follow the project's existing test stack and supported framework version. Prefer fast unit tests for isolated business rules, focused web/persistence slices when the framework supports them, and integration tests for database/security wiring where valuable.
- Test observable behavior and important failure cases, not framework internals. Use real database containers only when already supported or explicitly requested; do not prescribe JUnit 5, MockK, Testcontainers, or a particular Spring test annotation when the project uses something else.
- Report the exact test/build commands and results. Do not infer success from code inspection or claim an integration test ran when its external services were unavailable.
- Keep changes aligned with the declared Java and Spring versions. Avoid APIs or dependencies not supported by the project.
- Update API or configuration documentation when behavior changes; do not add unrelated scaffolding.

## Source material

These project-adapted rules synthesize guidance from [Kotlin Agent Skills](https://github.com/Kotlin/kotlin-agent-skills), [Spring Boot Skills](https://github.com/rrezartprebreza/spring-boot-skills), [GitHub Awesome Copilot's Kotlin/Spring skill](https://github.com/github/awesome-copilot/tree/main/skills/kotlin-springboot), and [Amplicode Spring Skills](https://github.com/Amplicode/spring-skills). Version-specific examples and IDE/MCP-only procedures are not treated as universal rules.
