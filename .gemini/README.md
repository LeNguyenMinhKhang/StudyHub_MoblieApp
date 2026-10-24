# Gemini and Android Studio setup

This directory combines the project configuration from `.agent`, `.claude`, and `.codex`. Identical skills are merged once; conflicting source content stops synchronization instead of silently overwriting a version. Kotlin/Android and conditional Java/Spring guidance are retained.

## Locations

| Location | Purpose |
| --- | --- |
| `GEMINI.md` at repository root | Gemini entrypoint importing project and Android Studio rules |
| `.gemini/rules/` | Project rules and runtime guidance |
| `.gemini/skills/` | 9 shared project skills, 6 OpenSpec skills, 8 migrated workflow skills |
| `.android-studio/skills/` | Discoverable Studio copies with tools/commands adapted for the IDE |
| `.gemini/commands/opsx/` | 6 generated Gemini CLI commands, not Android Studio slash commands |
| `.gemini/agents/` | 4 specialist role references, not automatically registered subagents |
| `.gemini/workflows/` | 8 workflows adapted from `.agent/workflows/` |
| `.gemini/reference/` | Original agent/workflow/Maestro files for comparison, not active rules |
| `.gemini/skill-sources.json` | Source paths and SHA-256 hashes for merged shared files |

OpenSpec files were generated with installed CLI 1.6.0's core profile in a temporary directory. Existing `openspec/` configuration, changes and specs were preserved. The old Maestro template references unavailable agents, scripts and web tooling; active rules use the actual StudyHub modules and available skills. Original `.agent`, `.claude` and `.codex` files are unchanged.

## Use in Android Studio

For Android Studio Quail 1 or newer, open StudyHub at the repository root, start a new Agent conversation, type `@`, and choose a skill. If the list is stale, reopen the project or restart the IDE. These are refresh steps, not a guarantee that every Studio version supports the same discovery behavior.

- `openspec-propose`: create a proposal, design, specs and tasks.
- `openspec-apply-change`: implement a reviewed change.
- `openspec-archive-change`: archive completed, reviewed work.
- `openspec-explore`, `openspec-update-change`, `openspec-sync-specs`: investigate, revise requirements, synchronize specs.
- `studyhub-brainstorm`, `studyhub-create`, `studyhub-debug`, `studyhub-plan`, `studyhub-test`: migrated general workflows.
- `studyhub-openspec-proposal`, `studyhub-openspec-apply`, `studyhub-openspec-archive`: OpenSpec workflows with the team's summary and review conventions.

Example: "Use openspec-propose to plan notifications. Follow GEMINI.md and AI_DEVELOPMENT_WORKFLOW.md."

If a skill is not listed, explicitly ask the agent to read `.android-studio/skills/openspec-propose/SKILL.md` and follow it. For Studio versions before Quail, check that version's documented skill locations; this setup targets `.android-studio/skills/` rather than duplicating skills into every legacy location.

## Keep copies synchronized

```powershell
python .gemini/scripts/sync_skills.py
python .gemini/scripts/sync_skills.py --check
```

The script merges every file under the three source skill trees and preserves references/scripts. It adds missing skill frontmatter using the folder name and introductory description; nonstandard metadata is placed under `metadata`. Studio copies omit CLI-specific tool allowlists, use `openspec.cmd` on Windows, and refer to skills through `@`. Gemini CLI originals retain their command format.

Edit all matching shared source copies when changing a shared skill; consult `skill-sources.json`. Edit Gemini-only skills, rules, profiles and workflows in `.gemini/`. After `openspec update`, run the sync commands again. Extra destination files are preserved to avoid losing user work; removed/renamed source skills need manual review. `--check` verifies expected generated files but does not delete extra files.

## Official references

- [Android Studio skills and discovery locations](https://developer.android.com/studio/gemini/skills)
- [Android Studio agent instruction files and imports](https://developer.android.com/studio/gemini/agent-files)

Filesystem validation cannot prove that a running Android Studio session has reloaded its skill menu. Verify that in the IDE after refreshing.
