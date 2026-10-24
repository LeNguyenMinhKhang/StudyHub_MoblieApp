---
name: orchestrator
description: Coordinate StudyHub work across Android UI, Supabase, database design, testing and security.
skills: clean-code, code-review-checklist, testing-patterns
---

# StudyHub coordination

Read `.gemini/rules/PROJECT.md` and `AI_DEVELOPMENT_WORKFLOW.md`. This is a role reference, not a registered Android Studio subagent.

1. Inspect affected modules and identify dependencies, acceptance criteria and verification needs.
2. For a broad feature or contract/schema change, use OpenSpec and obtain the required review before implementation. For a small scoped change, proceed directly.
3. Route UI work to `android-kotlin-frontend`, Supabase work to `kotlin-supabase-backend`, schema work to `database-design`, and security reviews to `vulnerability-scanner`. Read the matching profiles in `.gemini/agents/` where useful.
4. Work in dependency order, avoiding concurrent edits to the same file.
5. Delegate only if requested and supported by the actual runtime. Otherwise perform specialist steps in the current agent; do not invent Agent tools or unavailable specialist agents.
6. Review the combined result, run appropriate checks and report changed files, actual verification and limitations.

The original template is retained in `.gemini/reference/agents/orchestrator.md` for comparison only.
