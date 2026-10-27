---
description: Implement an approved OpenSpec proposal with Adaptive TDD execution, strict tracking, and dedicated implementation documentation.
---
<!-- OPENSPEC:START -->
**Guardrails**
- Favor straightforward, minimal implementations first and add complexity only when it is requested or clearly required.
- Keep changes tightly scoped to the requested outcome.
- Refer to `openspec/AGENTS.md` (located inside the `openspec/` directory—run `ls openspec` or `openspec update` if you don't see it) if you need additional OpenSpec conventions or clarifications.
- Apply **Adaptive TDD**: For core services and APIs, ensure tests are written and verified failing (Red) before implementing the passing code (Green), followed by refactoring (Refactor).

**Steps**
Track these steps as TODOs and complete them one by one.
1. Read `changes/<id>/proposal.md`, `design.md` (if present), and `tasks.md` to confirm scope, testing strategy, and acceptance criteria.
2. Work through tasks sequentially following the **Adaptive TDD cycle**:
   - **(Red Phase)**: Write unit/integration tests covering the spec scenarios. Run the test runner to verify they fail for the right reason.
   - **(Green Phase)**: Implement the minimal feature code required to make tests pass. Run the test runner to confirm success.
   - **(Refactor Phase)**: Refactor for clean code, remove duplication, and ensure adherence to coding standards without breaking any tests.
   - **(Adaptive Verification)**: For UI/configs, implement and verify against component/smoke test criteria.
3. Confirm completion before updating statuses—run the full project test suite (`npm test` / `npm run test:e2e`) to ensure zero regressions.
4. Create dedicated implementation doc (`changes/<id>/summary.md`) and synchronize specs:
   - Create a dedicated doc file `changes/<id>/summary.md` (recording all work completed by AI) with:
     - **Overview & Completed Work**: Details of what was built (features, services, endpoints, data models).
     - **Key Decisions & Edge Cases**: Architectural choices, design patterns applied, and edge cases handled.
     - **File Changes**: Comprehensive breakdown of created, modified, or deleted files with descriptions.
     - **Verification & Test Status**: Test suites run, commands used, and validation results.
   - Ensure the delta spec files under `changes/<id>/specs/<capability>/spec.md` accurately match the implemented behavior and scenarios so the specification stays accurate upon archiving.
5. Update the checklist after all work is done so each task in `changes/<id>/tasks.md` is marked `- [x]` and reflects reality.
6. Reference `openspec list` or `openspec show <item>` when additional context is required, and present a concise summary of changes and test results to the user.

**Reference**
- Use `openspec show <id> --json --deltas-only` if you need additional context from the proposal while implementing.
<!-- OPENSPEC:END -->

