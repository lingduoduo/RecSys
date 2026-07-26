#!/usr/bin/env python3
"""Summarize Surefire XML reports as versioned resilience evidence."""

import argparse
import json
import os
import platform
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, help="Evidence suite name")
    parser.add_argument("--reports", required=True, type=Path, help="Surefire report directory")
    parser.add_argument("--output", required=True, type=Path, help="Evidence JSON path")
    return parser.parse_args()


def numeric_attribute(element: ET.Element, name: str, number_type):
    raw = element.get(name, "0")
    try:
        value = number_type(raw)
    except (TypeError, ValueError) as error:
        raise ValueError(f"invalid {name}={raw!r}") from error
    if value < 0:
        raise ValueError(f"invalid negative {name}={raw!r}")
    return value


def suite_elements(root: ET.Element) -> list[ET.Element]:
    tag = root.tag.rsplit("}", 1)[-1]
    if tag == "testsuite":
        return [root]
    if tag == "testsuites":
        # Container totals duplicate their child suites, so consume children only.
        return [child for child in root if child.tag.rsplit("}", 1)[-1] == "testsuite"]
    return []


def summarize(reports: Path) -> dict[str, float | int]:
    totals: dict[str, float | int] = {
        "run": 0,
        "failed": 0,
        "errors": 0,
        "skipped": 0,
        "elapsedSeconds": 0.0,
    }
    valid_suites = 0
    problems: list[str] = []

    if not reports.is_dir():
        raise ValueError(f"report directory does not exist: {reports}")

    for report in sorted(reports.glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
            suites = suite_elements(root)
            if not suites:
                problems.append(f"{report.name}: no testsuite elements")
                continue
            for suite in suites:
                totals["run"] += numeric_attribute(suite, "tests", int)
                totals["failed"] += numeric_attribute(suite, "failures", int)
                totals["errors"] += numeric_attribute(suite, "errors", int)
                totals["skipped"] += numeric_attribute(suite, "skipped", int)
                totals["elapsedSeconds"] += numeric_attribute(suite, "time", float)
                valid_suites += 1
        except (ET.ParseError, OSError, ValueError) as error:
            problems.append(f"{report.name}: {error}")

    for problem in problems:
        print(f"warning: ignored invalid Surefire report: {problem}", file=sys.stderr)
    if valid_suites == 0:
        raise ValueError(f"no valid TEST-*.xml Surefire reports found in {reports}")

    totals["elapsedSeconds"] = round(float(totals["elapsedSeconds"]), 6)
    return totals


def main() -> int:
    args = parse_args()
    try:
        tests = summarize(args.reports)
        evidence = {
            "schemaVersion": 1,
            "suite": args.suite,
            "environment": {
                "gitSha": os.environ.get("GITHUB_SHA", ""),
                "javaVersion": os.environ.get("JAVA_VERSION", ""),
                "runnerOs": os.environ.get("RUNNER_OS") or platform.system(),
            },
            "tests": tests,
            "invariantsPassed": tests["failed"] == 0 and tests["errors"] == 0,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(evidence, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
