---
name: openspec-update
description: Revise existing OpenSpec planning artifacts and reconcile their requirements, design, and tasks without editing application code. Use for opsx-update or change-plan revision requests.
---

# OpenSpec Update

Read [project context](references/project-context.md) before starting. This is planning revision, not the CLI command `openspec.cmd update`, which updates integration files.

1. Resolve the change from explicit user intent. If not identified, run `openspec.cmd list --json` and present relevant active changes for selection.
2. Run `openspec.cmd status --change "<name>" --json`. Use artifact IDs from the active schema and concrete `artifactPaths.<id>.existingOutputPaths`. Do not assume a fixed set of artifact names or write to a glob pattern.
3. Read the affected artifacts and their related existing artifacts. For a specific revision, start there; for a coherence review, look for contradictions, gaps, and duplication across the plan.
4. Explain material revisions and their consequences. Apply revisions already authorized by the request; clarify changes beyond the approved intent. Before substantially rewriting an artifact, retrieve its rules and template with `openspec.cmd instructions <artifact-id> --change "<name>" --json`.
5. Reconcile implications in every direction: later task/spec edits can require changes to the proposal or design. Edit existing planning files only. If the change is already coherent, make no edits.
6. Run `openspec.cmd validate "<name>" --strict --no-interactive`. Report revisions, validation results, remaining gaps, and any mismatch with implemented code.

Do not create missing artifacts or new files under glob artifacts in this workflow; identify them for `openspec-propose`. Do not edit application code; implementation belongs to `openspec-apply`. If the request replaces the change's original intent, recommend a new proposal instead of silently repurposing it.
