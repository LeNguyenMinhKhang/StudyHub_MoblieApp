---
description: Archive a completed and reviewed StudyHub OpenSpec change.
---

# OpenSpec archive

Follow `.gemini/skills/openspec-archive-change/SKILL.md` and `AI_DEVELOPMENT_WORKFLOW.md`.

1. Resolve the intended change ID from the request or conversation; ask only when ambiguous.
2. Verify implementation review, completed tasks, spec/behavior agreement and actual verification. Do not proceed merely because a generated skill permits archiving with warnings.
3. Ensure the change summary.md accurately records implementation and verification before moving it.
4. Use `openspec.cmd archive <id>` when ready, retaining an explicitly selected store flag where applicable. Inspect validation errors before continuing.
5. Verify the archived directory, preserved summary and resulting canonical specs. Run `openspec.cmd validate --all --strict --no-interactive` and report the result.
