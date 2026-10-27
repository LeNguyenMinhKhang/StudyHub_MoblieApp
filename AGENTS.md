# StudyHub instructions for Codex

Read and follow the project rules in `.codex/rules/PROJECT.md` for work in this repository. Load the relevant skill from `.codex/skills/` when its description matches the task. The Android frontend and Java/Spring backend skills are project-specific; the Java/Spring skill does not apply to the current Kotlin Supabase module.

For the team's AI-assisted development and OpenSpec workflow, follow [AI_DEVELOPMENT_WORKFLOW.md](AI_DEVELOPMENT_WORKFLOW.md).

## OpenSpec skills

For OpenSpec requests, load the matching skill from `.agents/skills/openspec-<action>/SKILL.md`: `explore`, `propose`, `apply`, `update`, `sync`, or `archive`. Natural-language invocation is supported; do not depend on a particular chat menu or legacy `/opsx:*` command. `propose` also handles completing missing planning artifacts for an existing change.

The maintained sources are `.agent/skills/openspec-*/SKILL.md` and `.agent/openspec-context.md`. Run `python .agent/scripts/sync_openspec_skills.py` after editing them; run the same command with `--check` to verify copies in `.agents/skills`, `.github/skills`, `.claude/skills`, and `.codex/skills`, plus the legacy `.agent/workflows` entrypoints. Do not reinitialize OpenSpec when loading skills.
