---
description: Implement a reviewed StudyHub OpenSpec change and document verification.
---

# OpenSpec apply

Follow `.gemini/skills/openspec-apply-change/SKILL.md` and `AI_DEVELOPMENT_WORKFLOW.md`.

1. Identify the change, confirm review status from conversation or artifacts, and read proposal, design, specs and tasks.
2. Implement in dependency order, keeping Supabase access in `backend` and existing Android patterns in app modules.
3. Add focused tests for changed business behavior. Use existing Gradle tasks and appropriate UI/configuration checks; report commands actually run.
4. Mark only completed tasks as done and keep spec scenarios aligned with implementation.
5. Write `openspec/changes/<id>/summary.md` covering completed work, decisions, changed files, verification and limitations. For an explicitly selected external store, use the change directory returned by the CLI instead.
6. Review the diff and report results. Do not archive incomplete work.
