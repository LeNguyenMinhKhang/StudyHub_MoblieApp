---
description: Scaffold a new OpenSpec change proposal with Adaptive TDD and validate strictly.
---

<!-- OPENSPEC:START -->
**Guardrails**
- Favor straightforward, minimal implementations first and add complexity only when it is requested or clearly required.
- Keep changes tightly scoped to the requested outcome.
- Refer to `openspec/AGENTS.md` (located inside the `openspec/` directory—run `ls openspec` or `openspec update` if you don't see it) if you need additional OpenSpec conventions or clarifications.
- Identify any vague or ambiguous details and ask the necessary follow-up questions before editing files.
- Do not write any code during the proposal stage. Only create design documents (proposal.md, tasks.md, design.md, and spec deltas). Implementation happens in the apply stage after approval.
- Apply **Adaptive TDD**: Ensure spec scenarios translate directly into testable contracts and structure tasks using the Red-Green-Refactor sequence for core business logic and APIs.

**Steps**
1. Review `openspec/project.md`, run `openspec list` and `openspec list --specs`, and inspect related code or docs (e.g., via `rg`/`ls`) to ground the proposal in current behaviour; note any gaps that require clarification.
2. Choose a unique verb-led `change-id` and scaffold `proposal.md`, `tasks.md`, and `design.md` (when needed) under `openspec/changes/<id>/`.
3. Map the change into concrete capabilities or requirements, breaking multi-scope efforts into distinct spec deltas with clear relationships and sequencing.
4. Capture architectural reasoning in `design.md` when the solution spans multiple systems, introduces new patterns, or demands trade-off discussion before committing to specs. Include a **Testing Strategy** section defining test doubles, mock boundaries, and unit vs integration scope.
5. Draft spec deltas in `changes/<id>/specs/<capability>/spec.md` (one folder per capability) using `## ADDED|MODIFIED|REMOVED Requirements` with at least one `#### Scenario:` per requirement. Write scenarios as clear, automated test specifications with **WHEN** and **THEN** assertions.
6. Draft `tasks.md` using **Adaptive TDD** sequencing:
   - For backend services, APIs, data access, calculations, auth:
     - `- [ ] 1.1 (Red) Write unit/integration tests for [Scenario/Requirement]`
     - `- [ ] 1.2 (Green) Implement minimal code to satisfy tests`
     - `- [ ] 1.3 (Refactor) Clean up, optimize, and verify full test suite`
   - For presentation, UI, styling, and static configurations:
     - `- [ ] 2.1 Implement [Component/Config] and verify via component/smoke checks`
7. Validate with `openspec validate <id> --strict --no-interactive` and resolve every issue before sharing the proposal.

**Reference**
- Use `openspec show <id> --json --deltas-only` or `openspec show <spec> --type spec` to inspect details when validation fails.
- Search existing requirements with `rg -n "Requirement:|Scenario:" openspec/specs` before writing new ones.
- Explore the codebase with `rg <keyword>`, `ls`, or direct file reads so proposals align with current implementation realities.
<!-- OPENSPEC:END -->