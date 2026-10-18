# /create - Create an application or feature

$ARGUMENTS

## Workflow

1. Clarify the goal, users, core behavior, constraints, and existing project structure.
2. Inspect the repository and identify its languages, frameworks, build system, and architecture.
3. Propose a concise plan with affected modules, API/data changes, and verification steps.
4. Follow the project's established Java/Kotlin and frontend conventions; do not assume a framework that is not present.
5. Implement in small steps, adding tests appropriate to the behavior and boundaries.
6. Run relevant project checks using the existing Gradle or Maven wrapper and report the commands and results.

For a new application, discuss the stack and deployment needs before creating framework scaffolding. For an existing application, preserve its current architecture unless the request requires a change.
