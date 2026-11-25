#!/usr/bin/env python3
"""Fail closed on incomplete, failed, skipped or malformed test evidence.

This complements Gradle's exit status, which the workflow must still preserve.
JVM reports are checked for every current JUnit @Test method in the repository's
ordinary src/test Kotlin sources, including the Slice 4R reducer and Room tests.
The connected check covers the transport-lan API-23 task (not app androidTests).
A parameterized source method must appear at least once; this check does not
prove that every possible parameter value was exercised.

The source inventory is intentionally narrow and fail-closed: if a test source
uses syntax it cannot inventory, the checker must be extended rather than quietly
omitting that source. This is not a replacement for running the tests.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JVM_REQUIRED_MODULES = frozenset({
    "app", "core-model", "core-design", "core-data", "core-storage",
    "core-transfer", "transport-lan",
})
# Floors pin the repaired suites as well as discovering all the other source
# methods. Deliberate test removal requires review and an explicit floor update.
JVM_4R_FLOORS = {
    ("core-transfer", "app.morsecode.core.transfer.reducer.RejectionCoverageTest"): 25,
    ("core-transfer", "app.morsecode.core.transfer.reducer.TransferReducerContractRepairTest"): 19,
    ("core-transfer", "app.morsecode.core.transfer.reducer.TransferReducerInvariantTest"): 28,
    ("core-transfer", "app.morsecode.core.transfer.reducer.TransferReducerTransitionTest"): 68,
    ("core-transfer", "app.morsecode.core.transfer.reducer.TransferTransitionTableTest"): 10,
    ("core-transfer", "app.morsecode.core.transfer.LargeFileTest"): 32,
    ("core-transfer", "app.morsecode.core.transfer.resume.ResumeNegotiatorTest"): 29,
    ("core-transfer", "app.morsecode.core.transfer.persistence.TransferSnapshotStoreContractTest"): 18,
    ("core-data", "app.morsecode.core.data.db.RoomTransferSnapshotStoreTest"): 16,
}
API23_FLOORS = {
    ("transport-lan", "app.morsecode.transport.lan.ControlEstablishmentCapacityTest"): 1,
    ("transport-lan", "app.morsecode.transport.lan.security.ConscryptApi23CompatibilityTest"): 1,
    ("transport-lan", "app.morsecode.transport.lan.security.HostileSecureSessionApi23Test"): 11,
    ("transport-lan", "app.morsecode.transport.lan.security.LanMultiplexedRecordChannelApi23Test"): 7,
    ("transport-lan", "app.morsecode.transport.lan.security.SecureLanPairingCoordinatorApi23Test"): 9,
    ("transport-lan", "app.morsecode.transport.lan.security.jpake.EcJpakeFeasibilityApi23Test"): 21,
    ("transport-lan", "app.morsecode.transport.lan.security.jpake.EcJpakePerformanceApi23Test"): 3,
    ("transport-lan", "app.morsecode.transport.lan.security.jpake.ModpJpakeMeasurementApi23Test"): 8,
    ("transport-lan", "app.morsecode.transport.lan.security.jpake.ModpJpakeSessionApi23Test"): 12,
    ("transport-lan", "app.morsecode.transport.lan.security.pairing.PakeSessionApi23Test"): 1,
}

ANNOTATION = re.compile(r"^[ \t]*@(?:org\.junit\.)?Test\b", re.MULTILINE)
TEST_METHOD = re.compile(
    r"^[ \t]*@(?:org\.junit\.)?Test\b(?:\([^)]*\))?\s*"
    r"(?:(?:@(?!Test)\w+(?:\([^)]*\))?|(?:public|internal|private|open|suspend))\s+)*"
    r"fun\s+(`[^`]+`|\w+)\s*\(",
    re.MULTILINE,
)
TOP_LEVEL_CLASS = re.compile(
    r"^(?:(?:public|internal|private|abstract|open|data|sealed)\s+)*"
    r"(?:class|object)\s+(\w+)\b",
    re.MULTILINE,
)
PACKAGE = re.compile(r"^package\s+([A-Za-z_][\w.]*)\s*$", re.MULTILINE)


class EvidenceError(Exception):
    """The available evidence cannot support a pass."""


@dataclass
class Results:
    tests: int = 0
    files: int = 0
    # Module -> reported (class, method) pairs. Multiple variants/parameter
    # values may report a source method more than once.
    cases: dict[str, set[tuple[str, str]]] = field(default_factory=lambda: defaultdict(set))
    module_counts: dict[str, int] = field(default_factory=lambda: defaultdict(int))


def count_attr(element: ET.Element, name: str, path: Path) -> int:
    raw = element.get(name)
    if raw is None or not re.fullmatch(r"[0-9]+", raw):
        raise EvidenceError(f"{path}: missing or invalid {name} count")
    return int(raw)


def read_results(root: Path, paths: list[Path]) -> Results:
    if not paths:
        raise EvidenceError("no test result XML reports")
    result = Results()
    for path in sorted(paths):
        try:
            document = ET.parse(path).getroot()
            module = path.relative_to(root).parts[0]
        except (OSError, ET.ParseError, ValueError, IndexError) as exc:
            raise EvidenceError(f"{path}: unreadable or malformed XML: {exc}") from exc
        if document.tag == "testsuite":
            suites = [document]
        elif document.tag == "testsuites":
            suites = list(document.findall("testsuite"))
            if len(suites) != len(list(document.iter("testsuite"))):
                raise EvidenceError(f"{path}: nested test suites are unsupported")
        else:
            raise EvidenceError(f"{path}: unexpected XML root {document.tag!r}")
        if not suites:
            raise EvidenceError(f"{path}: no test suites")

        file_counts = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
        for suite in suites:
            counts = {name: count_attr(suite, name, path) for name in file_counts}
            cases = list(suite.findall("testcase"))
            if counts["tests"] == 0 or counts["tests"] != len(cases):
                raise EvidenceError(f"{path}: empty or inconsistent test-case count")
            if any(suite.findall(kind) for kind in ("failure", "error", "skipped")):
                raise EvidenceError(f"{path}: suite-level failure, error or skip")
            actual = {kind: 0 for kind in ("failure", "error", "skipped")}
            for case in cases:
                classname, name = case.get("classname"), case.get("name")
                if not classname or not name:
                    raise EvidenceError(f"{path}: test case missing class or name")
                status = (case.get("status") or "").lower()
                if status in {"notrun", "skipped", "ignored"}:
                    raise EvidenceError(f"{path}: non-running test case")
                for kind in actual:
                    actual[kind] += len(case.findall(kind))
                result.cases[module].add((classname, name))
            if (counts["failures"], counts["errors"], counts["skipped"]) != (
                actual["failure"], actual["error"], actual["skipped"]
            ):
                raise EvidenceError(f"{path}: failure/error/skip counts do not match cases")
            if counts["failures"] or counts["errors"] or counts["skipped"]:
                raise EvidenceError(f"{path}: failed, errored or skipped tests")
            for name, value in counts.items():
                file_counts[name] += value
        # Aggregate attributes are optional, but when present they must agree
        # with the child suites. Never add aggregate counts a second time.
        if document.tag == "testsuites":
            for name, value in file_counts.items():
                if document.get(name) is not None and count_attr(document, name, path) != value:
                    raise EvidenceError(f"{path}: aggregate {name} count disagrees with suites")
        result.files += 1
        result.tests += file_counts["tests"]
        result.module_counts[module] += file_counts["tests"]
    return result


def inventory_sources(root: Path, source_dirs: list[Path]) -> dict[str, set[tuple[str, str]]]:
    expected: dict[str, set[tuple[str, str]]] = defaultdict(set)
    for source_dir in sorted(source_dirs):
        if not source_dir.is_dir():
            continue
        for path in sorted(source_dir.rglob("*")):
            if path.suffix not in {".kt", ".java"} or not path.is_file():
                continue
            try:
                source = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError) as exc:
                raise EvidenceError(f"{path}: unreadable test source: {exc}") from exc
            annotations = list(ANNOTATION.finditer(source))
            if not annotations:
                continue
            methods = list(TEST_METHOD.finditer(source))
            if path.suffix != ".kt" or len(methods) != len(annotations):
                raise EvidenceError(f"{path}: unsupported @Test source syntax")
            package = PACKAGE.search(source)
            classes = list(TOP_LEVEL_CLASS.finditer(source))
            if package is None or not classes:
                raise EvidenceError(f"{path}: could not identify the test class")
            module = path.relative_to(root).parts[0]
            for method in methods:
                preceding = [entry for entry in classes if entry.start() < method.start()]
                if not preceding:
                    raise EvidenceError(f"{path}: test method has no top-level class")
                classname = package.group(1) + "." + preceding[-1].group(1)
                name = method.group(1).strip("`")
                key = (classname, name)
                if key in expected[module]:
                    raise EvidenceError(f"{path}: duplicate test method {classname}#{name}")
                expected[module].add(key)
    return expected


def reported_name(actual: str, source: str) -> bool:
    # JUnit parameterized and Robolectric runners may append a bracketed
    # parameter or SDK to a method or class name.
    return actual == source or (actual.startswith(source + "[") and actual.endswith("]"))


def require_source_coverage(
    results: Results,
    expected: dict[str, set[tuple[str, str]]],
    required_modules: frozenset[str],
) -> None:
    if not required_modules.issubset(expected):
        raise EvidenceError("required modules have no inventoried test sources: " + ", ".join(sorted(required_modules - expected.keys())))
    missing = []
    for module, methods in sorted(expected.items()):
        by_class: dict[str, list[str]] = defaultdict(list)
        for classname, name in results.cases.get(module, set()):
            by_class[classname].append(name)
        for classname, name in sorted(methods):
            if not any(
                reported_name(actual_class, classname) and any(reported_name(actual_name, name) for actual_name in names)
                for actual_class, names in by_class.items()
            ):
                missing.append(f"{module}: {classname}#{name}")
    if missing:
        detail = "; ".join(missing[:12])
        if len(missing) > 12:
            detail += f"; and {len(missing) - 12} more"
        raise EvidenceError(f"{len(missing)} source test method(s) missing from XML: {detail}")


def verify(root: Path, kind: str) -> tuple[Results, dict[str, set[tuple[str, str]]]]:
    if kind == "jvm":
        source_dirs = sorted(root.glob("*/src/test"))
        paths = sorted(root.glob("*/build/test-results/**/TEST-*.xml"))
        required = JVM_REQUIRED_MODULES
    elif kind == "api23":
        source_dirs = [root / "transport-lan/src/androidTest"]
        paths = sorted((root / "transport-lan/build/outputs/androidTest-results/connected").glob("**/TEST-*.xml"))
        required = frozenset({"transport-lan"})
        try:
            api = (root / "ci-logs/60-api23-api-level.log").read_text(encoding="utf-8").strip()
            exit_status = (root / "ci-logs/60-api23-exit-status.txt").read_text(encoding="utf-8").strip()
        except (OSError, UnicodeError) as exc:
            raise EvidenceError(f"API-23 device level or Gradle exit status missing: {exc}") from exc
        if api != "23" or exit_status != "0":
            raise EvidenceError(f"expected API 23 and connected Gradle status 0, got API {api!r}, status {exit_status!r}")
    else:
        raise EvidenceError(f"unknown evidence kind {kind!r}")

    expected = inventory_sources(root, source_dirs)
    floors = JVM_4R_FLOORS if kind == "jvm" else API23_FLOORS
    for (module, classname), floor in floors.items():
        count = sum(1 for actual_class, _ in expected.get(module, set()) if actual_class == classname)
        if count < floor:
            raise EvidenceError(f"{module}: {classname} has {count} source methods, expected at least {floor}")
    results = read_results(root, paths)
    require_source_coverage(results, expected, required)
    return results, expected


def main(argv: list[str]) -> int:
    if len(argv) != 2 or argv[1] not in {"jvm", "api23"}:
        print("usage: test_evidence.py {jvm|api23}", file=sys.stderr)
        return 2
    kind = argv[1]
    try:
        results, expected = verify(ROOT, kind)
    except EvidenceError as exc:
        message = str(exc).replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
        print(f"::error title={kind} test evidence::{message}")
        return 1
    method_count = sum(map(len, expected.values()))
    class_count = sum(len({classname for classname, _ in methods}) for methods in expected.values())
    modules = "; ".join(f"{module}={count}" for module, count in sorted(results.module_counts.items()))
    print(
        f"::notice title={kind} test evidence::{results.tests} tests, 0 failed, 0 skipped, "
        f"{results.files} XML reports; {class_count} source classes and {method_count} source methods "
        f"represented at least once; {modules}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
