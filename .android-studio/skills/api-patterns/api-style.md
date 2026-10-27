# API Style Selection

Choose the communication style based on consumers and data needs.

| Context | Suitable approach |
|---|---|
| Public API or multiple client platforms | REST with OpenAPI |
| Complex relationships and flexible client queries | GraphQL |
| Real-time or event-driven workflows | WebSocket or messaging with an event contract |
| Internal service-to-service communication | REST for simplicity or gRPC for typed RPC needs |

Before choosing, identify consumers, compatibility and caching needs, data complexity, and operational constraints. For Android/Kotlin clients, keep the API contract explicit through OpenAPI or a shared schema; do not couple clients to server implementation details.
