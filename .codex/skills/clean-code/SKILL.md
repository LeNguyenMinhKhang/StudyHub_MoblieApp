---
name: clean-code
description: Practical clean code guidance for Java and Kotlin. Keep changes readable, focused, and consistent with the project.
allowed-tools: Read, Write, Edit
version: 3.0
priority: CRITICAL
---

# Clean Code for Java and Kotlin

## Core principles

- **Single responsibility:** each class or function has one clear reason to change.
- **Keep it simple:** choose the smallest design that satisfies current requirements.
- **Avoid duplication:** extract shared behavior only when the duplication is real and stable.
- **Avoid speculative work:** do not add abstractions or features without a current use case.
- **Preserve local conventions:** follow the surrounding package, naming, and error-handling patterns.

## Naming

Use domain terms and names that explain intent. Prefer `activeUserCount`, `isEligible`, and `findAccountById` over abbreviations such as `n`, `flag`, or `getData`.

Use Java conventions for Java code (classes in `PascalCase`, methods and fields in `camelCase`, constants in `UPPER_SNAKE_CASE`). Use Kotlin conventions for Kotlin code (types in `PascalCase`, properties and functions in `camelCase`, immutable `val` by default).

## Functions and classes

- Keep functions focused; extract a helper when it clarifies a meaningful operation.
- Prefer guard clauses to deeply nested conditionals.
- Keep side effects visible and at clear boundaries.
- Depend on interfaces where substitution or testing benefits; do not create interfaces mechanically.
- Keep controllers and UI components thin. Put business rules in the appropriate domain or service layer.

## Immutability and nullability

Prefer immutable values. In Kotlin, use nullable types deliberately and handle them explicitly; avoid `!!` unless an invariant is guaranteed and documented. In Java, use the project's established null-handling approach and validate values at boundaries.

```kotlin
fun displayName(firstName: String?, lastName: String?): String =
    listOfNotNull(firstName, lastName)
        .joinToString(" ")
        .ifBlank { "Unknown" }
```

```java
public int remainingSeats(int capacity, int reservations) {
    if (capacity < 0 || reservations < 0) {
        throw new IllegalArgumentException("Capacity and reservations must be non-negative");
    }
    return capacity - reservations;
}
```

## Errors and boundaries

- Validate untrusted input at application boundaries.
- Use domain-specific exceptions or result types where they improve caller decisions.
- Do not swallow errors or expose internal details in client-facing responses.
- Keep database, network, and framework concerns behind suitable boundaries.

## Comments

Write comments to explain a non-obvious reason, constraint, or trade-off. Remove comments that merely repeat what the code says. Update comments when behavior changes.

## Tests

Test observable behavior and important edge cases. Keep tests deterministic, isolated from external services unless they are integration tests, and use the project's existing JUnit/Kotest/MockK conventions.

## Before finishing

- [ ] Names and responsibilities are clear.
- [ ] Existing architecture and conventions are respected.
- [ ] Nullability, errors, and side effects are handled explicitly.
- [ ] No unnecessary abstraction, duplication, or comments were introduced.
- [ ] Relevant tests cover the behavior changed.
