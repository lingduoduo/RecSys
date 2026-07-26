#!/usr/bin/env python3
"""Summarize Surefire XML reports as versioned resilience evidence."""

import argparse
import json
import math
import os
import platform
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True, help="Evidence suite name")
    parser.add_argument("--reports", required=True, type=Path, help="Surefire report directory")
    parser.add_argument("--measurements", required=True, type=Path, help="Validated probe sidecar")
    parser.add_argument("--output", required=True, type=Path, help="Evidence JSON path")
    return parser.parse_args()


def numeric_attribute(element: ET.Element, name: str, number_type):
    raw = element.get(name)
    if raw is None:
        raise ValueError(f"missing required {name} attribute")
    try:
        value = number_type(raw)
    except (TypeError, ValueError) as error:
        raise ValueError(f"invalid {name}={raw!r}") from error
    if value < 0:
        raise ValueError(f"invalid negative {name}={raw!r}")
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError(f"{name} must be finite, got {raw!r}")
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
    if not reports.is_dir():
        raise ValueError(f"report directory does not exist: {reports}")

    for report in sorted(reports.glob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
            suites = suite_elements(root)
            if not suites:
                raise ValueError("no testsuite elements")
            for suite in suites:
                tests = numeric_attribute(suite, "tests", int)
                failures = numeric_attribute(suite, "failures", int)
                errors = numeric_attribute(suite, "errors", int)
                skipped = numeric_attribute(suite, "skipped", int)
                if failures + errors + skipped > tests:
                    raise ValueError("failures, errors, and skipped exceed tests")
                totals["run"] += tests
                totals["failed"] += failures
                totals["errors"] += errors
                totals["skipped"] += skipped
                totals["elapsedSeconds"] += numeric_attribute(suite, "time", float)
                valid_suites += 1
        except ET.ParseError as error:
            raise ValueError(f"malformed Surefire report {report.name}: {error}") from error
        except (OSError, ValueError) as error:
            raise ValueError(f"invalid Surefire report {report.name}: {error}") from error
    if valid_suites == 0:
        raise ValueError(f"no valid TEST-*.xml Surefire reports found in {reports}")
    if totals["run"] == 0:
        raise ValueError("Surefire reports must contain at least one test")

    totals["elapsedSeconds"] = round(float(totals["elapsedSeconds"]), 6)
    return totals


def required_number(value, path: str, *, integer: bool = False):
    expected = int if integer else (int, float)
    if isinstance(value, bool) or not isinstance(value, expected):
        raise ValueError(f"{path} must be {'an integer' if integer else 'a number'}")
    if not math.isfinite(float(value)) or value < 0:
        raise ValueError(f"{path} must be finite and non-negative")
    return value


def load_measurements(path: Path, suite: str) -> tuple[dict, dict, str, dict]:
    try:
        payload = json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=lambda value: (_ for _ in ()).throw(
                ValueError(f"non-finite JSON number {value}")),
        )
    except (OSError, json.JSONDecodeError, ValueError) as error:
        raise ValueError(f"invalid measurement sidecar {path}: {error}") from error
    if (not isinstance(payload, dict)
            or isinstance(payload.get("schemaVersion"), bool)
            or payload.get("schemaVersion") != 1):
        raise ValueError("measurement sidecar must have schemaVersion 1")
    if payload.get("suite") != suite:
        raise ValueError(f"measurement suite {payload.get('suite')!r} does not match {suite!r}")
    measurements = payload.get("measurements")
    invariants = payload.get("invariants")
    source = payload.get("source")
    applicability = payload.get("applicability")
    if (not isinstance(measurements, dict) or not isinstance(invariants, dict)
            or not isinstance(applicability, dict)
            or not isinstance(source, str) or not source.strip()):
        raise ValueError(
            "measurement sidecar requires nonempty source, applicability, "
            "measurements, and invariants")

    def measurement_object(name: str) -> dict:
        value = measurements.get(name)
        if not isinstance(value, dict):
            raise ValueError(f"measurements.{name} must be an object")
        return value

    performance = measurement_object("performance")
    required_number(performance.get("elapsedMillis"), "performance.elapsedMillis")
    if suite == "load":
        if (applicability.get("load") is not True
                or applicability.get("redisBoundary") is not False
                or applicability.get("redisBoundaryCoveredBy") != "docker"):
            raise ValueError("invalid load-suite applicability")
        concurrency = measurement_object("concurrency")
        offered = required_number(
            concurrency.get("offered"), "concurrency.offered", integer=True)
        accepted = required_number(
            concurrency.get("accepted"), "concurrency.accepted", integer=True)
        rejections = measurement_object("rejections")
        admission = required_number(
            rejections.get("admission"), "rejections.admission", integer=True)
        bulkhead = required_number(
            rejections.get("bulkhead"), "rejections.bulkhead", integer=True)
        if accepted > offered or accepted + admission != offered:
            raise ValueError("impossible concurrency/admission counter relationship")
        degradation = measurement_object("degradation")
        total = required_number(
            degradation.get("total"), "degradation.total", integer=True)
        degraded = required_number(
            degradation.get("degraded"), "degradation.degraded", integer=True)
        ratio = required_number(degradation.get("ratio"), "degradation.ratio")
        expected_ratio = 0.0 if total == 0 else degraded / total
        if (degraded > total or ratio > 1
                or not math.isclose(ratio, expected_ratio, abs_tol=1e-9)):
            raise ValueError("impossible degradation counters or ratio")
        timeout = measurement_object("timeoutRecovery")
        timeouts = required_number(
            timeout.get("timeouts"), "timeoutRecovery.timeouts", integer=True)
        if not isinstance(timeout.get("recovered"), bool):
            raise ValueError("timeoutRecovery.recovered must be boolean")
        drain = measurement_object("gracefulDrain")
        if not isinstance(drain.get("completed"), bool):
            raise ValueError("gracefulDrain.completed must be boolean")
        in_flight = required_number(
            drain.get("inFlightAfterDrain"),
            "gracefulDrain.inFlightAfterDrain",
            integer=True)
        if drain["completed"] and in_flight != 0:
            raise ValueError("completed graceful drain must have zero in-flight requests")
        required_invariants = {
            "admissionBounded", "bulkheadRejected", "degradationMeasured",
            "timeoutRecovered", "gracefulDrainCompleted",
        }
        computed_invariants = {
            "admissionBounded": accepted <= offered and accepted + admission == offered,
            "bulkheadRejected": bulkhead > 0,
            "degradationMeasured": total > 0,
            "timeoutRecovered": timeouts > 0 and timeout["recovered"],
            "gracefulDrainCompleted": drain["completed"] and in_flight == 0,
        }
    elif suite == "docker":
        if (applicability.get("load") is not False
                or applicability.get("loadCoveredBy") != "load"
                or applicability.get("redisBoundary") is not True):
            raise ValueError("invalid docker-suite applicability")
        redis = measurement_object("redisBoundary")
        limit = required_number(redis.get("limit"), "redisBoundary.limit", integer=True)
        initial = required_number(
            redis.get("initialAllowed"), "redisBoundary.initialAllowed", integer=True)
        attempted = required_number(
            redis.get("attempted"), "redisBoundary.attempted", integer=True)
        allowed = required_number(
            redis.get("allowed"), "redisBoundary.allowed", integer=True)
        rejected = required_number(
            redis.get("rejected"), "redisBoundary.rejected", integer=True)
        if allowed + rejected != attempted or initial > limit:
            raise ValueError("impossible Redis boundary counters")
        required_invariants = {"redisBoundaryEnforced"}
        computed_invariants = {
            "redisBoundaryEnforced":
                initial == limit and allowed <= 1 and rejected >= attempted - 1,
        }
    else:
        raise ValueError(f"unsupported evidence suite: {suite}")

    missing_invariants = sorted(required_invariants - invariants.keys())
    if missing_invariants:
        raise ValueError(f"missing required invariants: {', '.join(missing_invariants)}")
    unexpected_invariants = sorted(invariants.keys() - required_invariants)
    if unexpected_invariants:
        raise ValueError(f"unexpected suite invariants: {', '.join(unexpected_invariants)}")
    if any(not isinstance(value, bool) for value in invariants.values()):
        raise ValueError("all invariants must be named booleans")
    inconsistent = sorted(
        name for name, computed in computed_invariants.items()
        if invariants[name] is not computed
    )
    if inconsistent:
        raise ValueError(
            "measurement invariant failed or disagrees with measured values: "
            + ", ".join(inconsistent))
    return measurements, invariants, source, applicability


def actual_java_version() -> str:
    java_home = os.environ.get("JAVA_HOME")
    executable = str(Path(java_home, "bin", "java")) if java_home else "java"
    try:
        result = subprocess.run(
            [executable, "-version"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ValueError(f"cannot determine Java runtime version: {error}") from error
    first_line = (result.stderr or result.stdout).splitlines()
    if result.returncode != 0 or not first_line or not first_line[0].strip():
        raise ValueError("cannot determine nonempty Java runtime version")
    return first_line[0].strip()


def main() -> int:
    args = parse_args()
    try:
        tests = summarize(args.reports)
        measurements, invariants, source, applicability = load_measurements(
            args.measurements, args.suite)
        evidence = {
            "schemaVersion": 1,
            "suite": args.suite,
            "environment": {
                "gitSha": os.environ.get("GITHUB_SHA", ""),
                "javaVersion": actual_java_version(),
                "runnerOs": os.environ.get("RUNNER_OS") or platform.system(),
            },
            "tests": tests,
            "measurements": measurements,
            "invariants": invariants,
            "source": source,
            "applicability": applicability,
            "invariantsPassed": (
                tests["failed"] == 0
                and tests["errors"] == 0
                and all(invariants.values())
            ),
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(evidence, indent=2, sort_keys=True, allow_nan=False) + "\n",
            encoding="utf-8",
        )
        if not evidence["invariantsPassed"]:
            failed = sorted(name for name, passed in invariants.items() if not passed)
            detail = ", ".join(failed) if failed else "Surefire failures or errors"
            print(f"error: resilience invariant failed: {detail}", file=sys.stderr)
            return 1
    except (OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
