#!/usr/bin/env python3
from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/test.yml"
SUPPORTED_LINES = ("3.3.8", "3.8.4")


def indentation(line: str) -> int:
    return len(line) - len(line.lstrip(" "))


def indented_block(text: str, header: str) -> str:
    lines = text.splitlines()
    start = lines.index(header)
    base = indentation(header)
    selected: list[str] = []
    for line in lines[start + 1 :]:
        if line.strip() and indentation(line) <= base:
            break
        selected.append(line)
    return "\n".join(selected)


def scalar(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def matrix_rows(workflow: str) -> list[tuple[str, str]]:
    test_job = indented_block(workflow, "  test:")
    matrix = indented_block(test_job, "      matrix:")
    include = indented_block(matrix, "        include:")
    entries: list[dict[str, str]] = []
    active: dict[str, str] | None = None
    for line in include.splitlines():
        item = re.fullmatch(r"\s*-\s+([a-z][a-z0-9-]*):\s*(\S.*?)\s*", line)
        field = re.fullmatch(r"\s+([a-z][a-z0-9-]*):\s*(\S.*?)\s*", line)
        if item:
            if active is not None:
                entries.append(active)
            active = {item.group(1): scalar(item.group(2))}
        elif field and active is not None:
            active[field.group(1)] = scalar(field.group(2))
        elif line.strip() and not line.lstrip().startswith("#"):
            raise AssertionError(f"unsupported matrix include line: {line}")
    if active is not None:
        entries.append(active)

    expected_keys = {"scala-version", "opposite-scala-version"}
    for entry in entries:
        if set(entry) != expected_keys:
            raise AssertionError(f"matrix row keys must be exactly {sorted(expected_keys)}: {entry}")
    return [
        (entry["scala-version"], entry["opposite-scala-version"])
        for entry in entries
    ]


def test_job_run_commands(workflow: str) -> list[str]:
    job = indented_block(workflow, "  test:")
    lines = job.splitlines()
    commands: list[str] = []
    for index, line in enumerate(lines):
        if not re.fullmatch(r"\s+run:\s*[|>]\s*", line):
            continue
        base = indentation(line)
        for command in lines[index + 1 :]:
            if command.strip() and indentation(command) <= base:
                break
            stripped = command.strip()
            if stripped and not stripped.startswith("#"):
                commands.append(stripped)
    return commands


class HostedCiMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_matrix_is_exact_and_bidirectional(self) -> None:
        test_job = indented_block(self.workflow, "  test:")
        self.assertEqual(
            matrix_rows(self.workflow),
            [("3.3.8", "3.8.4"), ("3.8.4", "3.3.8")],
        )
        self.assertIn("fail-fast: false", test_job)
        self.assertIn('name: Test (Scala ${{ matrix.scala-version }})', test_job)

    def test_matrix_parser_does_not_ignore_an_unquoted_reordered_extra_row(self) -> None:
        mutated = self.workflow.replace(
            "\n    steps:\n",
            "\n          - opposite-scala-version: 3.8.4\n"
            "            scala-version: 3.9.0\n"
            "\n    steps:\n",
        )

        self.assertEqual(
            matrix_rows(mutated),
            [("3.3.8", "3.8.4"), ("3.8.4", "3.3.8"), ("3.9.0", "3.8.4")],
        )

    def test_each_lane_prepares_the_opposite_artifact_then_runs_the_boundary(self) -> None:
        opposite = "${{ matrix.opposite-scala-version }}"
        selected = "${{ matrix.scala-version }}"
        commands = test_job_run_commands(self.workflow)
        self.assertIn(
            f"sbt -Dmacroparadise.exactScalaVersion={opposite} -batch '++{opposite}!' plugin/packageBin",
            commands,
        )
        self.assertIn(
            f"sbt -Dmacroparadise.exactScalaVersion={selected} -batch '++{selected}!' verifyPublicProductBoundary",
            commands,
        )
        build = (ROOT / "build.sbt").read_text(encoding="utf-8")
        self.assertIn('"test-hosted-ci-matrix.py"', build)

    def test_commands_in_comments_do_not_satisfy_the_selected_job(self) -> None:
        selected = "${{ matrix.scala-version }}"
        command = (
            f"sbt -Dmacroparadise.exactScalaVersion={selected} "
            f"-batch '++{selected}!' verifyPublicProductBoundary"
        )
        self.workflow = self.workflow.replace(command, "sbt -batch about")
        self.workflow += f"\n# {command}\n"

        with self.assertRaises(AssertionError):
            self.test_each_lane_prepares_the_opposite_artifact_then_runs_the_boundary()

    def test_jdk_sbt_and_ordinary_triggers_remain_exact(self) -> None:
        test_job = indented_block(self.workflow, "  test:")
        self.assertRegex(self.workflow, r"(?m)^  push:\n    branches:\n      - main$")
        self.assertRegex(self.workflow, r"(?m)^  pull_request:$")
        self.assertIn("uses: actions/setup-java@v5", test_job)
        self.assertIn('java-version: "25"', test_job)
        self.assertIn("uses: sbt/setup-sbt@v1", test_job)

    def test_workflow_has_no_publication_or_secret_surface(self) -> None:
        lowered = self.workflow.lower()
        for forbidden in (
            "publishlocal",
            "publishsigned",
            "centralportal",
            "gh release",
            "git tag",
            "credentials",
            "secrets.",
            "gpg",
            "signing",
        ):
            self.assertNotIn(forbidden, lowered)


if __name__ == "__main__":
    unittest.main(verbosity=2)
