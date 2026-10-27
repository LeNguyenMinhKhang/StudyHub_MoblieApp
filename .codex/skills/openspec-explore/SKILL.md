---
name: openspec-explore
description: Explore an idea, investigate a problem, or compare designs with OpenSpec before implementation. Use for OpenSpec explore or opsx-explore requests.
---

# OpenSpec Explore

Read [project context](references/project-context.md) before starting.

Explore the user's topic using existing code, requirements, and evidence. If no topic is supplied, ask what they want to explore. Do not create a change just to start a discussion.

1. Run `openspec.cmd list --json`. If a change is relevant, run `openspec.cmd status --change "<name>" --json` and read its existing artifacts using the returned paths.
2. Investigate the affected modules and current behavior. Separate verified facts from assumptions and unresolved questions.
3. Compare useful alternatives, explain tradeoffs, and identify constraints and acceptance criteria. Use diagrams only when they clarify the discussion.
4. Provide a file-specific plan when the user is considering implementation. Ask only for decisions that materially affect the result.
5. Capture decisions in planning artifacts only when requested. When ready, suggest `openspec-propose` to create or complete a proposal.

Explore does not authorize implementation. Stay read-only unless the user asks to save planning artifacts or explicitly switches to an implementation task. Do not force a proposal, fixed questionnaire, or conclusion.
