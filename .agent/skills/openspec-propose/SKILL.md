---
name: openspec-propose
description: Create an OpenSpec change and its proposal, design, specs, and tasks until ready for implementation. Also use to complete missing planning artifacts in a named existing change.
---

# OpenSpec Propose

Read [project context](references/project-context.md) before starting. Recognize OpenSpec proposal, opsx-propose, and requests to continue creating missing artifacts.

1. Understand the requested outcome and inspect related code and existing specs. Ask for the goal if none is supplied. Use a descriptive kebab-case change name.
2. Run `openspec.cmd list --json`. For a new change, run `openspec.cmd new change "<name>"`. If it already exists, use it only when the user's intent identifies that change; clarify conflicting intent instead of overwriting it.
3. Run `openspec.cmd status --change "<name>" --json`. Read `artifacts`, dependencies, `applyRequires`, and the returned path/scope context.
4. For each ready artifact, run `openspec.cmd instructions <artifact-id> --change "<name>" --json`. Read completed dependencies; follow the returned template, instructions, context, and rules. Context and rules constrain the output; do not copy them as boilerplate.
5. Write to the resolved output location. If it is a glob pattern, choose concrete capability files within that location rather than writing a literal glob. Preserve existing approved artifacts; use `openspec-update` when revisions are needed.
6. Recheck status after each artifact and continue in dependency order until every `applyRequires` artifact is done. If blocked by missing information, explain the precise decision needed.
7. For spec-driven changes, use requirements with concrete WHEN/THEN scenarios, record architectural decisions where needed, and create actionable tasks with appropriate validation. Use meaningful tests for business/data logic and build or smoke checks for UI/configuration.
8. Run `openspec.cmd validate "<name>" --strict --no-interactive`, resolve issues, and report the change, artifact paths, validation result, and readiness for review.

This workflow creates planning artifacts only. Apply requires the user's implementation intent and the project review requirements; do not automatically begin coding after proposal generation.
