# /test - Test Generation and Adaptive TDD

Use the repository's configured test framework and build commands. For Gradle projects, inspect the wrapper and module configuration before running tests.

## TDD cycle

1. **Red:** write a focused test for the requirement or scenario and confirm it fails for the expected reason.
2. **Green:** implement the smallest change that makes the test pass.
3. **Refactor:** improve the code while keeping the relevant tests green.

Apply focused unit tests to business logic and integration tests to persistence, security, and HTTP boundaries. Keep UI verification appropriate to the frontend framework.

## Test checklist

- Cover success, validation, boundary, and failure cases.
- Prefer behavior assertions over implementation details.
- Use Arrange, Act, Assert to make test intent clear.
- Mock external boundaries in unit tests; use integration tests for real framework and database behavior.
- Follow existing JUnit, Kotest, MockK, and Gradle conventions in the repository.
