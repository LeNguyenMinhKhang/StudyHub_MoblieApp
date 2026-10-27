"""Sync StudyHub's six OpenSpec skills and legacy workflow entrypoints.

Edit .agent/skills/openspec-*/SKILL.md and .agent/openspec-context.md,
then run this script. --check verifies generated copies without writing.
"""

import argparse
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ACTIONS = ("explore", "propose", "apply", "update", "sync", "archive")
DESTINATIONS = (".agents", ".github", ".claude", ".codex")


def expected_files():
    context = (ROOT / ".agent/openspec-context.md").read_bytes()
    for action in ACTIONS:
        name = f"openspec-{action}"
        source = ROOT / ".agent/skills" / name / "SKILL.md"
        content = source.read_bytes()
        yield source.parent / "references/project-context.md", context
        for destination in DESTINATIONS:
            target = ROOT / destination / "skills" / name
            yield target / "SKILL.md", content
            yield target / "references/project-context.md", context

        aliases = (action, "proposal") if action == "propose" else (action,)
        for alias in aliases:
            wrapper = (
                "---\n"
                f"description: Run the StudyHub {name} skill.\n"
                "---\n\n"
                f"Read and follow [the {name} skill](../skills/{name}/SKILL.md) "
                "and its project context. Treat the user's arguments as the skill input. "
                "This entrypoint uses the same workflow as the agent and Codex skill copies.\n"
            )
            yield ROOT / ".agent/workflows" / f"openspec-{alias}.md", wrapper.encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify without writing")
    args = parser.parse_args()
    mismatches = []
    count = 0
    for path, expected in expected_files():
        count += 1
        if path.is_file() and path.read_bytes() == expected:
            continue
        if args.check:
            mismatches.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(expected)
    if mismatches:
        print("Out-of-sync OpenSpec files:\n" + "\n".join(mismatches))
        return 1
    verb = "Verified" if args.check else "Synchronized"
    print(f"{verb} {count} generated files; {len(ACTIONS)} skills across 5 locations.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
