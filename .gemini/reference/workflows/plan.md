---
description: Create project plan using project-planner agent with Adaptive TDD task breakdown. No code writing - only plan file generation.
---

# /plan - Project Planning Mode (Adaptive TDD)

$ARGUMENTS

---

## 🔴 CRITICAL RULES

1. **NO CODE WRITING** - This command creates plan file only
2. **Use project-planner agent** - NOT Claude Code's native Plan subagent
3. **Socratic Gate** - Ask clarifying questions before planning
4. **Adaptive TDD Task Structure** - Structure tasks using Red-Green-Refactor for core logic/APIs
5. **Dynamic Naming** - Plan file named based on task

---

## Task

Use the `project-planner` agent with this context:

```
CONTEXT:
- User Request: $ARGUMENTS
- Mode: PLANNING ONLY (no code)
- Output: docs/PLAN-{task-slug}.md (dynamic naming)

NAMING RULES:
1. Extract 2-3 key words from request
2. Lowercase, hyphen-separated
3. Max 30 characters
4. Example: "e-commerce cart" → PLAN-ecommerce-cart.md

RULES:
1. Follow project-planner.md Phase -1 (Context Check)
2. Follow project-planner.md Phase 0 (Socratic Gate)
3. Formulate Testing Strategy upfront:
   - Identify modules requiring Strict TDD (APIs, Services, Auth, Calculations, DB logic)
   - Identify modules requiring Adaptive Verification (UI components, styling, static configs)
4. Structure Task Breakdown with Adaptive TDD:
   - For logic/APIs/services:
     - [ ] X.1 (Red) Write failing unit/integration tests for [Feature] (define contracts & assertions)
     - [ ] X.2 (Green) Implement minimal code to satisfy tests
     - [ ] X.3 (Refactor) Clean code, optimize, verify test suite passes
   - For UI/configs:
     - [ ] Y.1 Scaffold component/config & verify with smoke/component tests
5. DO NOT write any code files
6. REPORT the exact file name created
```

---

## Expected Output Structure (`docs/PLAN-{task-slug}.md`)

| Section | Content |
|---------|---------|
| **1. Overview & Scope** | Problem statement, goals, non-goals, architecture overview |
| **2. Testing Strategy (Adaptive TDD)** | Frameworks, mock boundaries, test doubles, coverage targets |
| **3. Test Scenarios (Given/When/Then)** | Critical acceptance criteria & edge cases mapped to test suites |
| **4. Task Breakdown (Red-Green-Refactor)** | Sequenced tasks with explicit Red / Green / Refactor subtasks |
| **5. Verification Checklist** | Automated test commands (`npm test`), manual checks, performance gates |

---

## After Planning

Tell user:
```
[OK] Plan created: docs/PLAN-{slug}.md (with Adaptive TDD task breakdown)

Next steps:
- Review the plan and test strategy
- Run `/create` or begin implementation following the Red-Green-Refactor cycle
- Or modify plan manually
```

---

## Naming Examples

| Request | Plan File |
|---------|-----------|
| `/plan e-commerce site with cart` | `docs/PLAN-ecommerce-cart.md` |
| `/plan mobile app for fitness` | `docs/PLAN-fitness-app.md` |
| `/plan add dark mode feature` | `docs/PLAN-dark-mode.md` |
| `/plan fix authentication bug` | `docs/PLAN-auth-fix.md` |
| `/plan SaaS dashboard` | `docs/PLAN-saas-dashboard.md` |

---

## Usage

```
/plan e-commerce site with cart
/plan mobile app for fitness tracking
/plan SaaS dashboard with analytics
```
