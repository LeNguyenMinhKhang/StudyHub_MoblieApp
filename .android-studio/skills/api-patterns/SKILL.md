---
name: api-patterns
description: "Choose an API style based on its consumers, deployment model, and operational needs."
---

Read `.gemini/rules/PROJECT.md` and `.gemini/rules/ANDROID_STUDIO.md` before applying this skill. Use the actual tools available in Android Studio. Read supporting files beside this SKILL.md unless a repository-relative path is explicit.


# API Patterns

Choose an API style based on its consumers, deployment model, and operational needs.

## Content map

| File | Description | When to read |
|---|---|---|
| `api-style.md` | REST, GraphQL, and event-driven API selection | Choosing an API style |
| `rest.md` | Resource naming, HTTP methods, and status codes | Designing REST APIs |
| `response.md` | Response envelopes, errors, and pagination | Defining response contracts |
| `graphql.md` | Schema design, use cases, and security | Considering GraphQL |
| `versioning.md` | URI, header, and query versioning | Planning API evolution |
| `auth.md` | JWT, OAuth, passkeys, and API keys | Selecting authentication |
| `rate-limiting.md` | Token bucket and sliding window approaches | Protecting APIs |
| `documentation.md` | OpenAPI/Swagger practices | Documenting APIs |
| `security-testing.md` | OWASP API risks and authorization tests | Security reviews |

## Design checklist

- Identify API consumers and compatibility requirements.
- Select REST, GraphQL, or event-driven communication for the use case.
- Define consistent success and error responses.
- Plan authentication, authorization, rate limits, and versioning.
- Document public contracts and use appropriate HTTP status codes.
- Validate input and avoid exposing internal errors.

## Validator

`scripts/api_validator.py` scans API source and OpenAPI specifications.
