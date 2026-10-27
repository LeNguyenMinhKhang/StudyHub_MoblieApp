# StudyHub OpenSpec context

Read `AGENTS.md`, `.codex/rules/PROJECT.md`, and `AI_DEVELOPMENT_WORKFLOW.md` from the repository root. This context is installed alongside every skill at `references/project-context.md`.

- The Android modules use Kotlin. `backend` is a Kotlin library for Supabase access, not a Java/Spring or Node.js service. Choose the relevant module skill and actual Gradle validation for implementation.
- Run OpenSpec from the repository root. On Windows/PowerShell use `openspec.cmd` to avoid the PowerShell shim execution-policy problem; on other platforms use `openspec`. Check `--version` and command `--help` when capabilities differ.
- Preserve the existing OpenSpec configuration, active changes, archives, and canonical specs. Loading or repairing skills does not require reinitializing OpenSpec.
- Start with the nearest OpenSpec root. If the user explicitly selects a standalone store, discover it with `openspec.cmd store list --json` and pass `--store <id>` only on commands whose help supports it. Keep the selected store consistent throughout the operation.
- Resolve scope and paths from CLI output (`planningHome`, `changeRoot`, `artifactPaths`, `actionContext`, and `contextFiles`) rather than inventing them. Respect action constraints. Existing glob artifacts have concrete `existingOutputPaths`; a glob itself is not a writable file path.
- Use the tools available in the current agent. If a specialized question, task, or skill tool is unavailable, ask in chat, track progress normally, or read the sibling skill directly. Do not assume tools named AskUserQuestion, TodoWrite, Task, or Skill exist.
- Natural-language requests such as "use openspec-explore" are sufficient. `/opsx:*`, `/prompts:opsx-*`, `$openspec-*`, and slash skill menus are interface-specific conveniences, not required CLI commands. Use the skill name when suggesting a next action.
- Recognize the six workflows: `openspec-explore`, `openspec-propose`, `openspec-apply`, `openspec-update`, `openspec-sync`, `openspec-archive`. Legacy proposal/new/continue requests map to `openspec-propose` for creating or completing planning artifacts; `openspec-sync-specs` maps to `openspec-sync`.
- Preserve the user's approved scope across turns. Ask for missing decisions or approval only when actually needed. Proposal/explore/update do not silently authorize application-code changes. Sync/archive require the user's corresponding intent.
- Report only checks actually performed. File validation does not prove the current IDE menu has loaded a skill. Reply in the user's language.
