---
description: Plan StudyHub work and verification before implementation.
---

# Project planning

Use the current request and conversation context. Read project rules, affected code and `AI_DEVELOPMENT_WORKFLOW.md`.

1. Identify scope, dependencies, constraints and acceptance criteria. Ask only about missing decisions that affect the plan.
2. For broad features, schema/API or architecture changes, use `.gemini/skills/openspec-propose/SKILL.md` and store artifacts in `openspec/changes/<id>/`.
3. For an explicitly requested standalone plan outside OpenSpec, write `docs/PLAN-<task-slug>.md` covering scope, design decisions, tasks, behavior scenarios and verification.
4. Plan focused tests for business logic, auth and data access; choose appropriate build, preview or smoke checks for UI/configuration. Use existing Gradle tasks.
5. Report the plan location and decisions requiring review. Planning alone does not authorize implementation.

Perform planning with the current agent; no separate project-planner tool or profile is required.
