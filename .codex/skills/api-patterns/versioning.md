# Versioning Strategies

Plan API evolution based on consumer compatibility needs.

| Strategy | Implementation | Trade-offs |
|---|---|---|
| URI | `/v1/users` | Clear and easy to cache |
| Header | `Accept-Version: 1` | Clean URLs, less visible |
| Query | `?version=1` | Easy to add, can clutter URLs |
| No explicit version | Evolve carefully | Suitable for internal APIs with coordinated consumers |

Public REST APIs should document compatibility expectations in OpenAPI. GraphQL APIs typically evolve through schema additions and deprecations rather than endpoint versions.
