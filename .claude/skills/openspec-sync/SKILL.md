---
name: openspec-sync
description: Merge a selected OpenSpec change's delta specifications into canonical specs while keeping the change active. Use for opsx-sync or openspec-sync-specs requests.
---

# OpenSpec Sync

Read [project context](references/project-context.md) before starting.

1. Resolve the change from explicit user intent. Otherwise run `openspec.cmd list --json` and ask which change to synchronize. Do not choose an unrelated change merely because it is the newest.
2. Run `openspec.cmd status --change "<name>" --json`. Resolve delta files from `artifactPaths.specs.existingOutputPaths` and canonical specs from the resolved planning root. If there are no delta specs, report that without editing files.
3. Read each delta and its corresponding canonical spec. Summarize the intended additions, modifications, removals, and renames before applying them.
4. Merge by requirement and scenario identity:
   - ADDED: add absent requirements; reconcile existing ones without duplicating them.
   - MODIFIED: apply the intended changes while preserving unrelated requirements and scenarios. Resolve contradictory instructions rather than guessing.
   - REMOVED: remove only the explicitly identified requirement block.
   - RENAMED: apply FROM/TO mappings, accounting for an already-applied rename.
5. Create a missing capability spec with a meaningful Purpose and Requirements section when the delta introduces it. Preserve WHEN/THEN scenario structure.
6. Inspect the resulting diff and run `openspec.cmd validate --specs --strict --no-interactive`. Correct relevant validation errors and report any unrelated pre-existing failures separately.
7. Report affected capabilities, changes made, and verification. Leave the change active and its tasks untouched.

The merge should be idempotent: a repeat must not duplicate requirements/scenarios or remove unrelated content. This is direct document editing; do not invent a CLI sync command or require a separate subagent/tool.
