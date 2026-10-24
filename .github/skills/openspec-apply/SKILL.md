---
name: openspec-apply
description: Implement the tasks of an approved OpenSpec change, track completion, and verify the resulting behavior. Use for OpenSpec apply or opsx-apply requests.
---

# OpenSpec Apply

Read [project context](references/project-context.md) before starting.

1. Resolve the change from the request or unambiguous conversation context. Otherwise run `openspec.cmd list --json` and ask the user to choose when ambiguous. Announce the selected change.
2. Run `openspec.cmd status --change "<name>" --json`, then `openspec.cmd instructions apply --change "<name>" --json`.
3. Read every file in `contextFiles`, the schema, progress, task list, dynamic instructions, and action constraints. If blocked by missing artifacts, explain what is missing and use `openspec-propose` only if the user requests completing the plan. If all tasks are done, report that and identify any outstanding verification.
4. Confirm that the intended scope is approved under the project workflow using the current conversation and artifacts. Do not repeatedly ask for approval already given.
5. Implement pending tasks in dependency order, using the relevant Android or Kotlin Supabase skill. Keep each edit within the approved scope. If a design decision becomes invalid, explain the impact and use `openspec-update` for the needed planning revision.
6. Validate meaningful behavior and error cases. For logic/data contracts, use focused tests where feasible; for UI/configuration, use relevant build, preview, or smoke checks. Run the repository's actual Gradle checks, not Node.js test commands.
7. Mark tasks complete only after their work and acceptance checks are satisfied. Keep unfinished or blocked work visible. Resolve diagnosable failures within scope; ask only when a real decision or external blocker prevents progress.
8. Review the diff against the spec and report changed files, completed/remaining tasks, checks actually run, and limitations. Produce implementation documentation when required by the change. Suggest `openspec-archive` when ready; do not archive automatically.
