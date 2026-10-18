#!/usr/bin/env python3
"""Name the category of an API 23 instrumentation failure.

WHY THIS EXISTS
---------------
Run 38302267476 failed at the API 23 step with a bare "The process '/usr/bin/sh'
failed with exit code 1" and no annotation at all. The instrumentation command
wrote no log into ci-logs/, so the existing JVM failure emitter (which reads
ci-logs/*.log) had nothing to publish, and from this environment the run log is
unreachable and artifacts cannot be downloaded. A red instrumentation step was
therefore undiagnosable, which is how a step failure goes unexplained for a
whole cycle.

WHAT IT DOES
------------
Parses the real connected-test result XML that the Android Gradle Plugin writes,
and emits one GitHub check-run annotation per failed test carrying the class,
the method, the failure message, a bounded stack excerpt and the device/API the
test ran on. When no XML exists it says so in its own distinct annotation and
names the infrastructure category instead: emulator boot, install/ADB,
instrumentation process crash, timeout, provider failure or runtime crash.

WHAT IT MUST NEVER DO
---------------------
* Convert a failed instrumentation run into a success. It exits 0 deliberately
  and only ever *adds* annotations: the instrumentation step's own non-zero exit
  status is what fails the job, and this script cannot and must not alter it.
* Mask the exit code of anything it calls.
* Print secrets, raw key material, full peer identifiers or unbounded logcat.
  Long hex runs are truncated to a recognising prefix and everything quoted from
  a log is line- and byte-bounded.
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

# ---------------------------------------------------------------------------
# Bounds. Every one of these is here so a pathological log cannot produce an
# annotation that the runner rejects or that buries the actual finding.
# ---------------------------------------------------------------------------
MAX_LOG_BYTES = 2_000_000        # per captured log read
MAX_EVIDENCE_LINES = 12          # per category-evidence excerpt
MAX_STACK_LINES = 16             # per failure stack excerpt
MAX_CHARS_PER_ANNOTATION = 1400
MAX_LINE_WIDTH = 220
MAX_ANNOTATIONS = 40             # hard cap so a catastrophic run cannot spam
# Annotations carrying a console tail get a larger budget. GitHub allows far
# more; the point of the cap is to keep the finding readable, and the tail is
# the finding, so it must not be the part that gets cut.
MAX_CHARS_WITH_TAIL = 6000
# Gradle task lines that report no work. They are the majority of the log and
# carry no information, so they are dropped before the tail is budgeted.
GRADLE_NOISE = re.compile(r"^> Task .*(UP-TO-DATE|NO-SOURCE|FROM-CACHE)$")

# Result paths the Android Gradle Plugin actually writes for connected tests.
# AGP places one directory per device under .../connected/, which is also where
# the device name in the annotation comes from.
XML_GLOBS = (
    "*/build/outputs/androidTest-results/connected/**/*.xml",
)
HTML_REPORT_GLOBS = (
    "*/build/reports/androidTests/connected/**/*.html",
)

DEVICE_LOGS = (
    "ci-logs/60-api23-device-before.log",
    "ci-logs/60-api23-api-level.log",
    "ci-logs/61-api23-device-after.log",
)
CONSOLE_LOG = "ci-logs/60-api23-connected.log"
# Deliberately not a .log extension: the generic ci-logs/*.log failure emitter
# would republish this verbatim and unredacted. Only this script reads it, and
# only through bounded().
LOGCAT_LOG = "ci-logs/61-api23-logcat-tail.logcat"
STATUS_FILE = "ci-logs/60-api23-exit-status.txt"
TIMEOUT_FILE = "ci-logs/60-api23-timed-out.txt"
# The dedicated logcat tag the A3 harness writes its phase markers to.
PHASE_LOG = "ci-logs/62-api23-phases.log"
# The marker that proves the harness ran to the end. A green run without it means the
# diagnostic channel is dead, which is worth failing the build over: a channel that
# silently reports nothing is indistinguishable from a test that never emitted anything.
TERMINAL_PHASE = "cleanup_completed"
# Phase markers the A3 harness prints to standard output. They are the only evidence
# available when a run hangs: no result XML, no stack, just the last stage that finished.
# Two shapes: a logcat line carrying the dedicated tag, and the plain console form.
# The tag form is what the harness actually emits -- `I/MorsecA3Phase( 1234): <phase>`.
PHASE_MARKER = re.compile(
    r"(?:MorsecA3Phase\s*\([^)]*\)\s*:\s*|\bA3PHASE\s+)(\S+)(.*)$"
)
MAX_PHASE_LINES = 20
# Written as the script's last act. Its absence while the console log exists
# means the instrumentation script was terminated mid-flight, which is a
# different failure from anything the log itself can report.
MARKER_FILE = "ci-logs/60-api23-script-completed.txt"

# Long hex runs are keys, transcript digests or peer instance identifiers. Keep
# enough to recognise which value was involved; never the value.
HEX_RUN = re.compile(r"\b[0-9A-Fa-f]{24,}\b")

ASSERTION_TYPES = re.compile(
    r"(AssertionFailedError|ComparisonFailure|AssertionError|junit\.framework\.Assert)", re.I
)

# Ordered: the first match wins, so the most specific infrastructure signals are
# tested before the generic runtime-crash patterns that would also match them.
INFRASTRUCTURE_CATEGORIES = (
    # An emulator that dies after the tests started is not a boot failure, and
    # calling it one would point the reader at boot configuration when the run
    # had already produced passing results.
    (
        "Emulator terminated during or after the run",
        re.compile(
            r"(The emulator process for AVD .* has terminated"
            r"|Emulator process .* terminated|emulator: .* terminated"
            r"|Emulator terminated)",
            re.I,
        ),
    ),
    (
        "Emulator boot failure",
        re.compile(
            r"(PANIC:|Cannot find AVD|emulator: ERROR|Waiting for emulator"
            r"|Timed out waiting for emulator|AVD .*not found|x86 emulation"
            r"|KVM is not installed|/dev/kvm|Failed to (start|boot) the emulator)",
            re.I,
        ),
    ),
    (
        "Install / ADB failure",
        re.compile(
            r"(INSTALL_FAILED|Installation did not succeed|Unable to install"
            r"|Failure \[INSTALL|adb: no devices|device offline|device not found"
            r"|no devices/emulators found|DeviceException|closed\b.*adb"
            r"|adb: device unauthorized)",
            re.I,
        ),
    ),
    (
        "Instrumentation process crash",
        re.compile(
            r"(INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed"
            r"|Target crashed|am_crash|Process .* has died|app process died"
            r"|Instrumentation run failed)",
            re.I,
        ),
    ),
    (
        "Conscrypt / TLS provider failure",
        re.compile(
            r"(Conscrypt|NoSuchProviderException|UnsatisfiedLinkError"
            r"|SSLHandshakeException|SSLException|no cipher suites in common"
            r"|NoSuchAlgorithmException|KeyStoreException|CertificateException"
            r"|Provider .* not found)",
            re.I,
        ),
    ),
    (
        "Test timeout",
        re.compile(
            r"(TimeoutException|Test timed out|Timed out after"
            r"|timed out waiting|deadline expired|SocketTimeoutException)",
            re.I,
        ),
    ),
    (
        "Kotlin/Android runtime crash",
        re.compile(
            r"(kotlin\.[A-Za-z]+Exception|NullPointerException|IllegalStateException"
            r"|ClassCastException|IndexOutOfBoundsException|ExceptionInInitializerError"
            r"|NoClassDefFoundError|OutOfMemoryError)",
            re.I,
        ),
    ),
    (
        "Gradle task failure",
        re.compile(r"(Execution failed for task|FAILURE: Build failed|What went wrong)", re.I),
    ),
)


def workflow_command(level: str, title: str, message: str) -> str:
    """Escape for GitHub workflow commands: %, CR and LF are special."""
    text = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    title = title.replace("%", "%25").replace("\r", " ").replace("\n", " ")
    return f"::{level} title={title}::{text}"


def redact(text: str) -> str:
    def substitute(match: re.Match) -> str:
        value = match.group(0)
        return f"{value[:8]}…[redacted {len(value)} hex chars]"

    return HEX_RUN.sub(substitute, text)


def bounded(text: str, max_lines: int, max_chars: int = MAX_CHARS_PER_ANNOTATION) -> str:
    """Truncate by line and by byte, then redact. Never returns the raw input."""
    out: list[str] = []
    used = 0
    for line in (text or "").splitlines():
        line = line.rstrip()[:MAX_LINE_WIDTH]
        if len(out) >= max_lines or used + len(line) > max_chars:
            out.append(f"…[excerpt truncated after {len(out)} lines]")
            break
        out.append(line)
        used += len(line) + 1
    return redact("\n".join(out)) or "[no message]"


JPAKE_PHASE = re.compile(r"(phase=[A-Za-z0-9_]+(?:\s+[a-z0-9_]+=-?[0-9]+)+)")
MAX_JPAKE_LINES = 16


def jpake_phase_lines():
    """Bounded per-phase J-PAKE timings captured under the feasibility harness tag.

    These are the output the feasibility exercise exists to produce, so they are surfaced
    on a green run rather than left only in an artifact. Fixed phase names and integers
    only, exactly as the harness logs them: no code, keying material or MAC tag.
    """
    out = []
    for line in (read_log(PHASE_LOG) + "\n" + read_log(CONSOLE_LOG)).splitlines():
        if "MorsecJpakePhase" not in line:
            continue
        found = JPAKE_PHASE.search(line)
        if found and found.group(1) not in out:
            out.append(found.group(1))
    return out[:MAX_JPAKE_LINES]


def phase_report():
    """The last A3PHASE marker and the bounded trail leading to it.

    Markers reach the host through the Gradle console, because an instrumentation
    process on API 23 cannot write into the runner workspace and its additional test
    output directory is not supported at that API level.
    """
    matches = []
    for line in (read_log(PHASE_LOG) + "\n" + read_log(CONSOLE_LOG)).splitlines():
        found = PHASE_MARKER.search(line)
        if found:
            matches.append(f"{found.group(1)}{found.group(2)}".strip())
    if not matches:
        return "none", [
            "no phase marker reached either the captured logcat tag or the console log"
        ]
    return matches[-1].split(" ", 1)[0], matches[-MAX_PHASE_LINES:]


def console_tail(max_lines: int = 20) -> str:
    """The end of the captured instrumentation console, bounded and redacted.

    Where the output *stops* is the finding when no category pattern matches: a
    log that ends mid-test is a killed run, a log that ends after BUILD
    SUCCESSFUL is a failure outside Gradle.
    """
    lines = [line.strip() for line in read_log(CONSOLE_LOG).splitlines() if line.strip()]
    kept = [line for line in lines if not GRADLE_NOISE.match(line)] or lines
    if not kept:
        return "[console log absent or empty]"
    dropped = len(lines) - len(kept)
    head = f"[{dropped} no-work task lines omitted]\n" if dropped else ""
    return head + "\n".join(kept[-max_lines:])


def read_log(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read(MAX_LOG_BYTES)
    except OSError:
        return ""


def collect_xml() -> list[str]:
    return sorted({p for pattern in XML_GLOBS for p in glob.glob(pattern, recursive=True)})


def device_of(xml_path: str) -> str:
    """AGP names the per-device directory under .../connected/ after the device."""
    parent = os.path.basename(os.path.dirname(xml_path))
    if parent and parent != "connected":
        return parent
    return "unknown-device"


def api_level() -> str:
    for path in DEVICE_LOGS:
        text = read_log(path).strip()
        if not text:
            continue
        match = re.search(r"\b(2[0-9]|3[0-9])\b", text)
        if match:
            return match.group(1)
    return "unknown"


def parse_results(xmls: list[str]):
    """Return (failures, tests_seen, parse_errors)."""
    failures: list[dict] = []
    tests_seen = 0
    parse_errors: list[str] = []

    for path in xmls:
        device = device_of(path)
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            parse_errors.append(f"{path}: {exc}")
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for suite in suites:
            try:
                tests_seen += int(suite.get("tests") or 0)
            except ValueError:
                pass
            for case in suite.iter("testcase"):
                for bad in list(case.iter("failure")) + list(case.iter("error")):
                    failures.append(
                        {
                            "class": case.get("classname") or suite.get("name") or "unknown-class",
                            "method": case.get("name") or "unknown-method",
                            "type": bad.get("type") or "unspecified",
                            "message": bad.get("message") or "",
                            "stack": (bad.text or ""),
                            "device": device,
                            "source": path,
                        }
                    )
    return failures, tests_seen, parse_errors


def classify_failure(failure: dict) -> str:
    blob = f"{failure['type']} {failure['message']}\n{failure['stack']}"
    if ASSERTION_TYPES.search(blob):
        return "Test assertion failure"
    for name, pattern in INFRASTRUCTURE_CATEGORIES:
        if pattern.search(blob):
            return name
    return "Unclassified test failure"


# A line ending in PASSED or SKIPPED is a test *name*, and test names in this
# module contain words like "Conscrypt" and "TLS". Classifying from one produced
# a false "Conscrypt / TLS provider failure" against a run whose tests all
# passed, so passing lines are excluded before any pattern is tried.
PASSING_LINE = re.compile(r"\b(PASSED|SKIPPED)\b")


def classify_logs(paths: list[str]):
    """Return (category, evidence source, evidence lines) from the logs, or None."""
    for path in paths:
        text = read_log(path)
        if not text:
            continue
        lines = [line for line in text.splitlines() if not PASSING_LINE.search(line)]
        for name, pattern in INFRASTRUCTURE_CATEGORIES:
            hits = [line.strip() for line in lines if pattern.search(line)]
            if hits:
                return name, os.path.basename(path), hits[:MAX_EVIDENCE_LINES]
    return None


def classify_outcome(terminated: bool, log_paths: list[str]):
    """The category, its evidence source and the evidence lines.

    A keyword found in the log names the *cause* and wins the title, because
    "the script was killed" is the symptom, not the reason. Termination is still
    reported alongside it. Only when the log holds no recognisable cause does
    termination itself become the category.
    """
    if read_log(TIMEOUT_FILE).strip():
        return (
            "Instrumentation command exceeded its hard process bound",
            os.path.basename(TIMEOUT_FILE),
            [
                read_log(TIMEOUT_FILE).strip(),
                "gradle was sent TERM and then KILL; the status was preserved, not treated as success",
            ],
        )
    classified = classify_logs(log_paths)
    if classified:
        name, source, hits = classified
        if terminated:
            hits = [
                f"the script was also killed mid-flight (no completion marker); "
                f"category taken from the log evidence: {name}"
            ] + hits
        return name, source, hits
    if terminated:
        return (
            "Instrumentation script terminated before completion",
            os.path.basename(CONSOLE_LOG),
            [
                "the script wrote a console log but never wrote its completion marker, "
                "so it was killed mid-flight instead of exiting",
                "keyword scan over the full log matched no known category",
            ],
        )
    return (
        "Unclassified infrastructure failure",
        os.path.basename(CONSOLE_LOG),
        [line.strip() for line in read_log(CONSOLE_LOG).splitlines() if line.strip()][
            -MAX_EVIDENCE_LINES:
        ],
    )


def main() -> int:
    xmls = collect_xml()
    reports = sorted({p for pattern in HTML_REPORT_GLOBS for p in glob.glob(pattern, recursive=True)})
    failures, tests_seen, parse_errors = parse_results(xmls)
    sdk = api_level()
    annotations_emitted = 0

    # The instrumentation step's own exit status, passed in by the workflow.
    # Without it this script cannot tell a green run from a red one, and a
    # diagnostic that warns on success trains its reader to ignore it.
    raw_status = os.environ.get("API23_EXIT_STATUS", "").strip()
    if not raw_status:
        # The instrumentation step writes its own status to a file as well. Step
        # outputs only survive if the emulator-runner action propagates
        # GITHUB_OUTPUT into its script; the file does not depend on that, and
        # an unknown status degrades to "unknown" rather than to a wrong answer.
        raw_status = read_log(STATUS_FILE).strip()
    status_known = raw_status.lstrip("-").isdigit()
    step_passed = status_known and raw_status == "0"
    status_label = raw_status if status_known else "unknown (API23_EXIT_STATUS not set)"

    def emit(level: str, title: str, message: str) -> None:
        nonlocal annotations_emitted
        if annotations_emitted >= MAX_ANNOTATIONS:
            return
        print(workflow_command(level, title, message))
        annotations_emitted += 1

    script_completed = bool(read_log(MARKER_FILE).strip())
    terminated = bool(read_log(CONSOLE_LOG)) and not script_completed

    if not xmls:
        # The distinct annotation the recovery contract asks for. No XML means
        # the instrumentation run never produced a result, which is never a
        # test failure and must not be reported as one.
        category, source, hits = classify_outcome(
            terminated, [CONSOLE_LOG, LOGCAT_LOG] + list(DEVICE_LOGS)
        )
        emit(
            "error" if not step_passed else "warning",
            "API 23 infrastructure failure — no instrumentation result XML produced",
            bounded(
                f"category: {category}\n"
                f"instrumentation exit status: {status_label}\n"
                f"sdk: {sdk}\n"
                f"result xml found: 0\n"
                f"evidence source: {source}\n"
                f"script ran to completion: {script_completed}\n"
                f"--- matching lines ---\n" + "\n".join(hits)
                + f"\n--- console tail ---\n{console_tail()}",
                MAX_EVIDENCE_LINES + 34,
                MAX_CHARS_WITH_TAIL,
            ),
        )
    else:
        for failure in failures:
            category = classify_failure(failure)
            excerpt = bounded(failure["stack"] or failure["message"], MAX_STACK_LINES)
            emit(
                "error",
                f"API 23 {category} — {failure['class']}#{failure['method']}",
                bounded(
                    f"category: {category}\n"
                    f"class: {failure['class']}\n"
                    f"method: {failure['method']}\n"
                    f"type: {failure['type']}\n"
                    f"device: {failure['device']} (API {sdk})\n"
                    f"result xml: {failure['source']}\n"
                    f"message: {failure['message']}\n"
                    # The stack excerpt comes before the phase trail on purpose. The A3
                    # trail alone runs to ~27 lines, and when it was emitted first it
                    # consumed the entire annotation budget, so a run of ten J-PAKE
                    # failures reported ten annotations with no exception in any of them.
                    # Evidence first, context second.
                    f"--- stack excerpt ---\n{excerpt}\n"
                    f"--- phase trail ---\n" + "\n".join(phase_report()[1]),
                    MAX_STACK_LINES + MAX_EVIDENCE_LINES + 12,
                ),
            )

    for error in parse_errors:
        emit(
            "error",
            "API 23 result XML could not be parsed",
            bounded(error, MAX_EVIDENCE_LINES),
        )

    if not failures and step_passed:
        # A green instrumentation step. The summary notice below is enough;
        # claiming a failure here would make the channel cry wolf on every
        # passing run and train the next reader to ignore it.
        #
        # The J-PAKE feasibility timings are the exception worth a notice: they are the
        # measured evidence the external review needs, and the phase log they live in is
        # not retrievable once the run ends.
        jpake = jpake_phase_lines()
        if jpake:
            emit(
                "notice",
                "J-PAKE API 23 phase timings (median and p90, microseconds)",
                bounded("\n".join(jpake), MAX_JPAKE_LINES),
            )
    elif xmls and not failures:
        # Results exist and every test passed, yet the job may still be red.
        # Say what the logs hold rather than letting the run look explained.
        category, source, hits = classify_outcome(terminated, [CONSOLE_LOG, LOGCAT_LOG])
        emit(
            "error",
            f"API 23 {category} — tests passed but the instrumentation step still failed",
            bounded(
                f"category: {category}\n"
                f"result xml: {len(xmls)} file(s), {tests_seen} test(s), 0 failure(s)\n"
                f"instrumentation exit status: {status_label}\n"
                f"script ran to completion: {script_completed}\n"
                f"last phase marker: {phase_report()[0]}\n"
                f"--- phase trail ---\n" + "\n".join(phase_report()[1]) + "\n"
                f"evidence source: {source}\n"
                f"--- matching lines ---\n" + "\n".join(hits)
                + f"\n--- console tail ---\n{console_tail()}",
                MAX_EVIDENCE_LINES + 34,
                MAX_CHARS_WITH_TAIL,
            ),
        )

    if step_passed and not failures:
        last_phase = phase_report()[0]
        if last_phase != TERMINAL_PHASE:
            emit(
                "error",
                f"API 23 phase channel is dead — expected terminal marker {TERMINAL_PHASE}",
                bounded(
                    f"instrumentation exit status: {status_label}\n"
                    f"tests: {tests_seen}, failures: 0\n"
                    f"last phase marker: {last_phase}\n"
                    f"phase log: {'present' if read_log(PHASE_LOG).strip() else 'absent'}\n"
                    "The instrumentation run passed, so the missing terminal marker means the "
                    "phase transport is broken rather than the test being incomplete. A channel "
                    "that reports nothing cannot diagnose the next hang, which is the only reason "
                    "it exists.",
                    MAX_EVIDENCE_LINES + 8,
                ),
            )
            return 1

    # Always emit one summary, on green runs too. That is what proves the
    # channel itself is alive: a green run that says "3 tests, 0 failures"
    # shows the diagnostic really read the results.
    emit(
        "notice",
        "API 23 diagnostics",
        bounded(
            f"instrumentation exit status: {status_label}\n"
            f"last phase marker: {phase_report()[0]}\n"
            f"phase log: {'present' if read_log(PHASE_LOG).strip() else 'absent'}\n"
            f"result xml: {len(xmls)} file(s)\n"
            f"html reports: {len(reports)}\n"
            f"tests: {tests_seen}\n"
            f"failures: {len(failures)}\n"
            f"parse errors: {len(parse_errors)}\n"
            f"sdk: {sdk}\n"
            f"console log: {'present' if read_log(CONSOLE_LOG) else 'absent'}\n"
            f"logcat tail: {'present' if read_log(LOGCAT_LOG) else 'absent'}",
            MAX_EVIDENCE_LINES,
        ),
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
