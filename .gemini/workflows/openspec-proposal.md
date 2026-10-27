---
description: Prepare a StudyHub OpenSpec proposal, design, specs and tasks for review.
---

# OpenSpec proposal

Follow `.gemini/skills/openspec-propose/SKILL.md`, `AI_DEVELOPMENT_WORKFLOW.md` and the existing `openspec/config.yaml`.

- Read affected code and canonical specs before proposing changes.
- Keep artifacts in the change directory resolved by OpenSpec; the local default is `openspec/changes/<id>/`.
- Include testable WHEN/THEN scenarios and focused verification tasks using existing Kotlin/Gradle conventions.
- Run `openspec.cmd validate <id> --strict --no-interactive` before handing off.
- Present the proposal for review. Do not implement application code during this workflow.
