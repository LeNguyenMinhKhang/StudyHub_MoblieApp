#!/usr/bin/env python3
"""Run Java/Kotlin project tests through the repository's build wrapper."""
import json
import os
import subprocess
import sys
from pathlib import Path


def find_command(project: Path):
    if (project / "gradlew").exists():
        wrapper = "gradlew.bat" if os.name == "nt" and (project / "gradlew.bat").exists() else "./gradlew"
        return [wrapper, "test"], "Gradle"
    if (project / "mvnw").exists():
        wrapper = "mvnw.cmd" if os.name == "nt" and (project / "mvnw.cmd").exists() else "./mvnw"
        return [wrapper, "test"], "Maven"
    if (project.joinpath("pom.xml").exists()):
        return ["mvn", "test"], "Maven"
    if list(project.glob("*.gradle")) or list(project.glob("*.gradle.kts")):
        return ["gradle", "test"], "Gradle"
    return None, None


def main():
    project = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    command, build_tool = find_command(project)
    if not command:
        print(json.dumps({"script": "test_runner", "project": str(project), "passed": True,
                          "message": "No Gradle or Maven project detected"}, indent=2))
        return 0
    try:
        result = subprocess.run(command, cwd=project, text=True, timeout=900)
        passed = result.returncode == 0
        print(json.dumps({"script": "test_runner", "project": str(project),
                          "build_tool": build_tool, "command": command,
                          "passed": passed, "exit_code": result.returncode}, indent=2))
        return result.returncode
    except (OSError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"script": "test_runner", "project": str(project),
                          "build_tool": build_tool, "passed": False, "error": str(error)}, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
