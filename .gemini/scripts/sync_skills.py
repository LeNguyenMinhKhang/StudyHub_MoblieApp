"""Merge StudyHub skills and publish Android Studio copies; no third-party packages."""

import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GEMINI = ROOT / ".gemini"
STUDIO = ROOT / ".android-studio" / "skills"
SOURCES = (".agent", ".claude", ".codex")


def normalize(text):
    text = text.lstrip("\ufeff").replace("\r\n", "\n")
    for source in SOURCES:
        text = text.replace(source + "/skills/", ".gemini/skills/")
    # Preserve custom source metadata in the standard metadata mapping.
    if text.startswith("---\n"):
        header, body = text[4:].split("\n---", 1)
        extras = re.findall(r"^(version|priority|compatibility): (.+)$", header, re.M)
        header = re.sub(r"^(version|priority|compatibility): .+\n?", "", header, flags=re.M).rstrip()
        if extras:
            if not re.search(r"^metadata:\s*$", header, re.M):
                header += "\nmetadata:"
            # Existing metadata is last in the generated/source frontmatter.
            for key, value in extras:
                header += f"\n  {key}: {json.dumps(value)}"
        text = "---\n" + header + "\n---" + body
    return text


def studio_skill(text):
    text = normalize(text)
    # Tool names and slash commands are Gemini CLI conventions, not Studio APIs.
    text = re.sub(r"^allowed-tools:.*\n", "", text, flags=re.M)
    names = {
        "propose": "openspec-propose", "explore": "openspec-explore",
        "apply": "openspec-apply-change", "archive": "openspec-archive-change",
        "sync": "openspec-sync-specs", "update": "openspec-update-change",
        "new": "openspec-propose",
    }
    for command, skill in names.items():
        text = text.replace("/opsx:" + command, "@" + skill)
    text = text.replace("/opsx:continue", "the remaining-artifact procedure in .gemini/rules/ANDROID_STUDIO.md")
    text = text.replace("AskUserQuestion tool", "chat input to ask the user")
    text = text.replace("TodoWrite tool", "available task tracker (or a checklist in the conversation)")
    # Use the Windows executable shim, avoiding PowerShell execution-policy errors.
    text = re.sub(r"\bopenspec (?=(?:new|status|instructions|list|show|validate|archive|store|doctor|context|schema|config|update|init)\b)", "openspec.cmd ", text)
    if text.startswith("---\n"):
        header, body = text[4:].split("\n---", 1)
        note = (
            "\n\nRead `.gemini/rules/PROJECT.md` and `.gemini/rules/ANDROID_STUDIO.md` "
            "before applying this skill. Use the actual tools available in Android Studio. "
            "Read supporting files beside this SKILL.md unless a repository-relative path is explicit.\n"
        )
        text = "---\n" + header + "\n---" + note + body
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify expected files without writing")
    args = parser.parse_args()
    merged = {}
    origins = {}
    for source in SOURCES:
        for path in sorted((ROOT / source / "skills").rglob("*")):
            if not path.is_file() or "__pycache__" in path.parts:
                continue
            rel = path.relative_to(ROOT / source / "skills")
            content = path.read_bytes()
            if path.suffix == ".md":
                text = normalize(content.decode("utf-8-sig"))
                if rel.name == "SKILL.md" and not text.startswith("---\n"):
                    description = next(block.strip() for block in text.split("\n\n")
                                       if block.strip() and not block.startswith("#"))
                    text = f"---\nname: {rel.parent.name}\ndescription: {json.dumps(description)}\n---\n\n{text}"
                content = text.encode("utf-8")
            if rel in merged and merged[rel] != content:
                raise SystemExit(f"Conflicting source skill: {rel}; reconcile sources before syncing")
            merged[rel] = content
            origins.setdefault(rel.as_posix(), []).append(path.relative_to(ROOT).as_posix())

    expected = {GEMINI / "skills" / rel: data for rel, data in merged.items()}
    all_skills = {p.relative_to(GEMINI / "skills"): p.read_bytes()
                  for p in (GEMINI / "skills").rglob("*")
                  if p.is_file() and "__pycache__" not in p.parts}
    all_skills.update(merged)
    for rel, data in all_skills.items():
        if rel.name == "SKILL.md":
            data = studio_skill(data.decode("utf-8-sig")).encode("utf-8")
        expected[STUDIO / rel] = data
    manifest = {rel: {"sources": srcs, "sha256": hashlib.sha256(merged[Path(rel)]).hexdigest()}
                for rel, srcs in sorted(origins.items())}
    expected[GEMINI / "skill-sources.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    mismatches = []
    for path, data in expected.items():
        if path.exists() and path.read_bytes() == data:
            continue
        if args.check:
            mismatches.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    if mismatches:
        raise SystemExit("Out of sync:\n" + "\n".join(mismatches))
    count = sum(rel.name == "SKILL.md" for rel in all_skills)
    print(f"{'Verified' if args.check else 'Synced'} {len(merged)} shared files; {count} Android Studio skills.")
    print("Unmanaged destination files are preserved; removed source skills require manual review.")


if __name__ == "__main__":
    main()
