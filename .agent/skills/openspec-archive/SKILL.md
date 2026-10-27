---
name: openspec-archive
description: Archive a completed, reviewed OpenSpec change and reconcile its canonical specifications. Use for OpenSpec archive or opsx-archive requests.
---

# OpenSpec Archive

Read [project context](references/project-context.md) before starting.

1. Identify the change explicitly from the request or a previously confirmed choice. Otherwise run `openspec.cmd list --json` and ask which change to archive. Do not guess.
2. Run `openspec.cmd status --change "<name>" --json` and read its artifacts and task checklist. Confirm implementation/review and required verification are complete under the project workflow. Report unfinished work instead of archiving an incomplete change.
3. Check any implementation summary or handoff required by the change. Preserve those files. Never claim tests or review happened without evidence.
4. Read the delta specs and canonical specs from the resolved root. Summarize their differences. Determine whether they still need applying, were already fully synchronized, or contain no spec changes. Resolve conflicts before proceeding.
5. Run `openspec.cmd validate "<name>" --strict --no-interactive`. Inspect `openspec.cmd archive --help` if the installed CLI differs from these instructions.
6. For a completed change the user asked to archive, run `openspec.cmd archive "<name>" --yes`. Let the CLI validate, update specs, and move the complete change with its metadata. Do not manually move files around failed validation.
7. Use `--skip-specs` only when there are no spec changes, the user explicitly requested skipping them, or every delta effect has been verified as already present in canonical specs. Never silently discard unsynchronized deltas or bypass validation with `--no-validate`.
8. If the destination already exists, report the collision without overwriting it. If archiving fails, inspect the actual state before any retry; do not assume the operation was atomic.
9. Verify the archived location, preserved metadata/docs, active change list, and updated canonical specs. Run `openspec.cmd validate --specs --strict --no-interactive` when specs exist. Report the archive path, synchronization status, checks, and limitations.

If a semantic merge is needed before archiving, follow the sibling `openspec-sync` skill directly, then reassess sync state. Do not apply deltas twice.
