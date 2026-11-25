"""Regression tests for the CI evidence gate; no Android toolchain required."""
from __future__ import annotations

import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

import test_evidence as gate


class EvidenceGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def write(self, relative: str, text: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def source(self, module: str = "demo", methods: tuple[str, ...] = ("passes",), android: bool = False) -> Path:
        scope = "androidTest" if android else "test"
        functions = "\n".join(f"    @Test fun `{name}`() {{}}" for name in methods)
        return self.write(
            f"{module}/src/{scope}/kotlin/sample/FooTest.kt",
            f"package sample\nclass FooTest {{\n{functions}\n}}\n",
        )

    def report(self, module: str = "demo", android: bool = False, names: tuple[str, ...] = ("passes",)) -> Path:
        if android:
            relative = f"{module}/build/outputs/androidTest-results/connected/debug/TEST-device.xml"
        else:
            relative = f"{module}/build/test-results/testDebugUnitTest/TEST-sample.FooTest.xml"
        return self.write(relative, make_xml([("sample.FooTest", name) for name in names]))

    def check_jvm(self):
        with patch.object(gate, "JVM_REQUIRED_MODULES", frozenset({"demo"})), \
             patch.object(gate, "JVM_4R_FLOORS", {("demo", "sample.FooTest"): 1}):
            return gate.verify(self.root, "jvm")

    def check_api(self):
        with patch.object(gate, "API23_FLOORS", {("transport-lan", "sample.FooTest"): 1}):
            return gate.verify(self.root, "api23")

    def api_logs(self, api: str = "23", status: str = "0") -> None:
        self.write("ci-logs/60-api23-api-level.log", api + "\n")
        self.write("ci-logs/60-api23-exit-status.txt", status + "\n")

    def test_green_jvm_reports_every_source_method(self) -> None:
        self.source(methods=("passes", "another case"))
        self.report(names=("passes", "another case"))
        result, expected = self.check_jvm()
        self.assertEqual(2, result.tests)
        self.assertEqual(2, len(expected["demo"]))

    def test_parameterized_and_robolectric_suffixes_are_accepted(self) -> None:
        self.source()
        path = self.report()
        path.write_text(make_xml([("sample.FooTest[35]", "passes[35]")]), encoding="utf-8")
        self.assertEqual(1, self.check_jvm()[0].tests)

    def test_missing_xml_is_rejected(self) -> None:
        self.source()
        with self.assertRaisesRegex(gate.EvidenceError, "no test result"):
            self.check_jvm()

    def test_missing_required_module_is_rejected(self) -> None:
        self.source()
        self.report()
        with patch.object(gate, "JVM_REQUIRED_MODULES", frozenset({"demo", "other"})), \
             patch.object(gate, "JVM_4R_FLOORS", {}), \
             self.assertRaisesRegex(gate.EvidenceError, "other"):
            gate.verify(self.root, "jvm")

    def test_missing_source_method_is_rejected_even_when_totals_are_nonzero(self) -> None:
        self.source(methods=("passes", "missing"))
        self.report()
        with self.assertRaisesRegex(gate.EvidenceError, "missing from XML.*missing"):
            self.check_jvm()

    def test_required_class_floor_is_enforced(self) -> None:
        self.source()
        self.report()
        with patch.object(gate, "JVM_REQUIRED_MODULES", frozenset({"demo"})), \
             patch.object(gate, "JVM_4R_FLOORS", {("demo", "sample.FooTest"): 2}), \
             self.assertRaisesRegex(gate.EvidenceError, "expected at least 2"):
            gate.verify(self.root, "jvm")

    def test_malformed_xml_and_unexpected_root_fail(self) -> None:
        self.source()
        path = self.report()
        for xml in ("<testsuite", "<nothing/>"):
            with self.subTest(xml=xml):
                path.write_text(xml, encoding="utf-8")
                with self.assertRaises(gate.EvidenceError):
                    self.check_jvm()

    def test_missing_invalid_and_negative_counts_fail(self) -> None:
        self.source()
        path = self.report()
        for attribute, value in (("tests", None), ("tests", "-1"), ("tests", "NaN"), ("skipped", None)):
            with self.subTest(attribute=attribute, value=value):
                doc = ET.fromstring(make_xml([("sample.FooTest", "passes")]))
                if value is None:
                    doc.attrib.pop(attribute)
                else:
                    doc.set(attribute, value)
                path.write_bytes(ET.tostring(doc))
                with self.assertRaises(gate.EvidenceError):
                    self.check_jvm()

    def test_empty_and_inconsistent_suite_counts_fail(self) -> None:
        self.source()
        path = self.report()
        for declared in ("0", "2"):
            with self.subTest(declared=declared):
                doc = ET.fromstring(make_xml([("sample.FooTest", "passes")]))
                doc.set("tests", declared)
                path.write_bytes(ET.tostring(doc))
                with self.assertRaisesRegex(gate.EvidenceError, "test-case count"):
                    self.check_jvm()

    def test_failures_and_skips_fail_even_with_forged_zero_counts(self) -> None:
        self.source()
        path = self.report()
        for kind, attribute in (("failure", "failures"), ("error", "errors"), ("skipped", "skipped")):
            for declared in ("0", "1"):
                with self.subTest(kind=kind, declared=declared):
                    doc = ET.fromstring(make_xml([("sample.FooTest", "passes")]))
                    ET.SubElement(doc.find("testcase"), kind)
                    doc.set(attribute, declared)
                    path.write_bytes(ET.tostring(doc))
                    with self.assertRaises(gate.EvidenceError):
                        self.check_jvm()

    def test_non_running_status_fails(self) -> None:
        self.source()
        path = self.report()
        doc = ET.fromstring(make_xml([("sample.FooTest", "passes")]))
        doc.find("testcase").set("status", "notrun")
        path.write_bytes(ET.tostring(doc))
        with self.assertRaisesRegex(gate.EvidenceError, "non-running"):
            self.check_jvm()

    def test_missing_test_case_identity_fails(self) -> None:
        self.source()
        path = self.report()
        for attribute in ("classname", "name"):
            with self.subTest(attribute=attribute):
                doc = ET.fromstring(make_xml([("sample.FooTest", "passes")]))
                doc.find("testcase").attrib.pop(attribute)
                path.write_bytes(ET.tostring(doc))
                with self.assertRaisesRegex(gate.EvidenceError, "missing class or name"):
                    self.check_jvm()

    def test_suite_level_failure_is_not_mistaken_for_a_pass(self) -> None:
        self.source()
        path = self.report()
        doc = ET.fromstring(make_xml([("sample.FooTest", "passes")]))
        ET.SubElement(doc, "error")
        path.write_bytes(ET.tostring(doc))
        with self.assertRaisesRegex(gate.EvidenceError, "suite-level"):
            self.check_jvm()

    def test_aggregate_root_is_not_double_counted(self) -> None:
        self.source(methods=("passes", "another"))
        path = self.report()
        outer = ET.Element("testsuites", tests="2", failures="0", errors="0", skipped="0")
        outer.append(ET.fromstring(make_xml([("sample.FooTest", "passes")])))
        outer.append(ET.fromstring(make_xml([("sample.FooTest", "another")])))
        path.write_bytes(ET.tostring(outer))
        self.assertEqual(2, self.check_jvm()[0].tests)
        outer.set("tests", "3")
        path.write_bytes(ET.tostring(outer))
        with self.assertRaisesRegex(gate.EvidenceError, "aggregate tests"):
            self.check_jvm()

    def test_nested_and_empty_aggregate_suites_fail(self) -> None:
        self.source()
        path = self.report()
        for xml in ("<testsuites/>", "<testsuites><testsuites><testsuite/></testsuites></testsuites>"):
            with self.subTest(xml=xml):
                path.write_text(xml, encoding="utf-8")
                with self.assertRaises(gate.EvidenceError):
                    self.check_jvm()

    def test_unknown_kotlin_syntax_and_java_tests_are_not_silently_ignored(self) -> None:
        path = self.source()
        path.write_text("package sample\nclass FooTest {\n @Test val unknown = 1\n}\n", encoding="utf-8")
        with self.assertRaisesRegex(gate.EvidenceError, "unsupported @Test"):
            gate.inventory_sources(self.root, [self.root / "demo/src/test"])
        path.unlink()
        self.write("demo/src/test/java/sample/FooTest.java", "class FooTest {\n @Test public void test() {}\n}")
        with self.assertRaisesRegex(gate.EvidenceError, "unsupported @Test"):
            gate.inventory_sources(self.root, [self.root / "demo/src/test"])

    def test_duplicate_source_methods_are_rejected(self) -> None:
        self.source(methods=("passes", "passes"))
        with self.assertRaisesRegex(gate.EvidenceError, "duplicate test method"):
            gate.inventory_sources(self.root, [self.root / "demo/src/test"])

    def test_api23_green_requires_actual_device_status_and_results(self) -> None:
        self.source("transport-lan", android=True)
        self.report("transport-lan", android=True)
        self.api_logs()
        self.assertEqual(1, self.check_api()[0].tests)

    def test_api23_wrong_device_failed_status_and_missing_status_fail(self) -> None:
        self.source("transport-lan", android=True)
        self.report("transport-lan", android=True)
        for api, status in (("24", "0"), ("23", "1")):
            with self.subTest(api=api, status=status):
                self.api_logs(api, status)
                with self.assertRaisesRegex(gate.EvidenceError, "expected API 23"):
                    self.check_api()
        (self.root / "ci-logs/60-api23-exit-status.txt").unlink()
        with self.assertRaisesRegex(gate.EvidenceError, "missing"):
            self.check_api()

    def test_api23_missing_reports_fail_even_with_green_status(self) -> None:
        self.source("transport-lan", android=True)
        self.api_logs()
        with self.assertRaisesRegex(gate.EvidenceError, "no test result"):
            self.check_api()

    def test_api23_skips_and_missing_methods_fail(self) -> None:
        self.source("transport-lan", methods=("passes", "missing"), android=True)
        path = self.report("transport-lan", android=True)
        self.api_logs()
        with self.assertRaisesRegex(gate.EvidenceError, "missing from XML"):
            self.check_api()
        doc = ET.fromstring(make_xml([("sample.FooTest", "passes"), ("sample.FooTest", "missing")]))
        ET.SubElement(doc.find("testcase"), "skipped")
        doc.set("skipped", "1")
        path.write_bytes(ET.tostring(doc))
        with self.assertRaisesRegex(gate.EvidenceError, "skipped tests"):
            self.check_api()

    def test_real_source_inventory_covers_repaired_and_device_suites(self) -> None:
        root = gate.ROOT
        jvm = gate.inventory_sources(root, sorted(root.glob("*/src/test")))
        self.assertTrue(gate.JVM_REQUIRED_MODULES.issubset(jvm))
        for (module, classname), floor in gate.JVM_4R_FLOORS.items():
            self.assertGreaterEqual(sum(cls == classname for cls, _ in jvm[module]), floor)
        device = gate.inventory_sources(root, [root / "transport-lan/src/androidTest"])
        for (module, classname), floor in gate.API23_FLOORS.items():
            self.assertGreaterEqual(sum(cls == classname for cls, _ in device[module]), floor)
        self.assertEqual(74, sum(gate.API23_FLOORS.values()))


def make_xml(cases: list[tuple[str, str]]) -> str:
    suite = ET.Element("testsuite", name="sample", tests=str(len(cases)), failures="0", errors="0", skipped="0")
    for classname, name in cases:
        ET.SubElement(suite, "testcase", classname=classname, name=name)
    return ET.tostring(suite, encoding="unicode")


if __name__ == "__main__":
    unittest.main()
