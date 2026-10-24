---
name: kotlin-supabase-backend
description: Use when changing StudyHub's Kotlin `backend` module, Supabase Auth/PostgREST access, shared data models, remote data sources, repositories, serialization, or coroutine-based data flows.
---

# Kotlin Supabase Data Layer

## Project fit

- StudyHub's `backend` is a Kotlin Android library that calls Supabase. It is not a Spring Boot server and does not use JPA/Hibernate.
- Follow the current `core/data/model`, `core/data/remote`, and `core/data/repository` structure and the existing `Resource` result convention. Do not add server/controller/service layers unless the architecture changes intentionally.
- Inspect the exact Supabase Kotlin SDK, Ktor, and kotlinx.serialization versions from the version catalog before using APIs; examples from other SDK releases may not compile here.

## Kotlin and asynchronous work

- Prefer immutable models and constructor-injected dependencies. Use nullable types to represent genuinely absent data and validate untrusted remote data at the boundary; avoid `!!` as normal control flow.
- Keep suspend work in structured coroutine scopes, preserve cancellation, and avoid `GlobalScope`, `runBlocking` on UI paths, and blocking network calls.
- Map expected failures into the established result/error model. Do not swallow exceptions, convert cancellation into an ordinary error, or leak implementation details to UI.
- Keep extensions small and unsurprising. Do not hide a network call, authorization decision, or mutation in a property getter or generic extension.

## Supabase and data contracts

- Use the existing Supabase client and SDK APIs. Keep queries in remote data sources and expose task-oriented operations through repositories.
- Treat request parameters, database rows, auth responses, and storage metadata as external inputs. Validate required values and map serialization/database naming explicitly according to nearby model conventions.
- Keep query results bounded. For lists, preserve an explicit order and use pagination where a result can grow; select only needed relations/columns when the SDK supports the existing API pattern.
- Keep model changes aligned with the Postgres schema, serialization names, and existing table conventions. Describe schema or RLS changes separately; client code alone cannot enforce authorization.
- Supabase anon keys are public client configuration. Never include service-role keys, database passwords, or other privileged credentials in app code, resources, Gradle files, or logs.
- RLS is the authorization boundary for client access. Check that a feature's data access remains compatible with expected policies, and do not claim a UI check protects a row.
- Do not introduce JPA entities, Spring repositories, server-side JWT signing, or transactions unless the target module actually adopts those technologies.

## Testing and verification

- Follow dependencies and test layout already configured in the affected module. Write focused tests for mapping, repository decisions, and failure handling when the behavior can be tested locally.
- Keep external Supabase integration tests isolated from unit tests and never target production data. Use test credentials or fakes only when the project already provides them.
- Run the narrow relevant Gradle task and report its exact result. If local credentials or network access are required and unavailable, state that limitation instead of claiming end-to-end verification.

## Source material

This skill adapts applicable Kotlin idiom, null-safety, structured-concurrency, boundary, query-shape, security, and test-selection practices reviewed in [Kotlin Agent Skills](https://github.com/Kotlin/kotlin-agent-skills), [Spring Boot Skills](https://github.com/rrezartprebreza/spring-boot-skills), [GitHub Awesome Copilot's Kotlin/Spring skill](https://github.com/github/awesome-copilot/tree/main/skills/kotlin-springboot), and [Amplicode Spring Skills](https://github.com/Amplicode/spring-skills). Spring/JPA-specific instructions are excluded from this Supabase skill.
