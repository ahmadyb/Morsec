#!/usr/bin/env bash
# Runs the API 23 connected tests under a hard process bound and captures everything the
# diagnostic step needs to name a failure category.
#
# WHY THIS IS A FILE AND NOT AN INLINE script: BLOCK
# reactivecircus/android-emulator-runner@v2 passes its `script` input through parseScript, which
# splits on newlines and executes EVERY LINE as a separate `sh -c`, aborting the loop at the first
# non-zero exit (lib/script-parser.js and lib/main.js). A multi-line inline script is therefore not
# a script at all: `set +e` applies to nothing, variables do not survive to the next line, and an
# `if` header with no matching `fi` on the same line is a syntax error that fails the step. That is
# what happened in runs 38365708870, 38368188165 and 38370664983: Gradle printed BUILD SUCCESSFUL
# with 4 of 4 tests passing, and the step still failed.
#
# WHY A HARD PROCESS BOUND
# Run 38460137506 was cancelled after hanging at this step. A JUnit timeout inside the JVM is not a
# sufficient answer on its own, because interrupting a thread blocked in a native or socket call may
# not terminate it, and a hang below the JVM cannot be bounded from inside it at all. So Gradle runs
# under GNU timeout: TERM first, KILL after a bounded grace period. A timeout exits 124 (or 137 if
# KILL was needed) and that status is preserved and reported -- it is never converted into success.
#
# The bound sits comfortably above the normal runtime for these tests and far below the workflow
# job timeout, so a hang becomes a diagnosed failure instead of a cancelled run.

set +e
set -u

mkdir -p ci-logs

# Device state before anything runs, so a boot or ADB problem is attributable.
adb devices -l > ci-logs/60-api23-device-before.log 2>&1
adb shell getprop ro.build.version.sdk > ci-logs/60-api23-api-level.log 2>&1

# The A3 phase markers travel by logcat, because instrumentation stdout does not reach the
# Gradle console and API 23 does not support an additional test output directory.
#
# WHY A CONTINUOUS CAPTURE AND NOT A SINGLE DUMP
# A one-shot `adb logcat -d` after the run reported "last phase marker: none" on run 38593221201
# even though all five tests passed and every marker had been logged. API 23 keeps a small logcat
# ring buffer on a chatty emulator, so markers written minutes earlier had already rotated out by
# the time anything read them. Streaming the tag into the file from before Gradle starts means the
# evidence is on disk the moment it is produced, and survives a hang, a kill and a disconnect.
#
# One tag only, so no unbounded logcat is collected: fixed phase names and bounded counts, never a
# peer or session identifier, address, certificate, proof or key.
A3_TAG="${A3_PHASE_TAG:-MorsecA3Phase}"
# The J-PAKE feasibility harness reports bounded phase timings under its own tag.
JPAKE_TAG="${JPAKE_PHASE_TAG:-MorsecJpakePhase}"
: > ci-logs/62-api23-phases.log
adb logcat -c > /dev/null 2>&1
adb logcat -v time -s "$A3_TAG:I" "$JPAKE_TAG:I" >> ci-logs/62-api23-phases.log 2>&1 &
A3_LOGCAT_PID=$!

timeout --kill-after="${TIMEOUT_GRACE_SECONDS}s" "${TIMEOUT_BOUND_SECONDS}s" \
  ./gradlew --no-daemon --stacktrace --console=plain \
  :transport-lan:connectedDebugAndroidTest > ci-logs/60-api23-connected.log 2>&1
status=$?

# Written immediately: the value must survive even if this script is killed afterwards, which is
# the whole reason the diagnostic step exists.
printf '%s\n' "$status" > ci-logs/60-api23-exit-status.txt
if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
  printf 'timed out with status %s after %ss (kill-after %ss)\n' \
    "$status" "$TIMEOUT_BOUND_SECONDS" "$TIMEOUT_GRACE_SECONDS" > ci-logs/60-api23-timed-out.txt
fi
echo "connectedDebugAndroidTest exit status: $status"
tail -n 150 ci-logs/60-api23-connected.log

# The A3 phase markers travel by logcat, because instrumentation stdout does not reach the
# Gradle console and API 23 does not support an additional test output directory. Captured on
# every outcome -- success, failure and timeout alike -- and before any cleanup, while the
# emulator is still reachable. One tag only, so no unbounded logcat is collected.
# Stop the stream first, then dump what the buffer still holds as a fallback in case the stream
# died early -- for example on an ADB disconnect mid-run.
kill "$A3_LOGCAT_PID" > /dev/null 2>&1
wait "$A3_LOGCAT_PID" > /dev/null 2>&1
adb logcat -d -s "${A3_PHASE_TAG:-MorsecA3Phase}:I" "${JPAKE_PHASE_TAG:-MorsecJpakePhase}:I" >> ci-logs/62-api23-phases.log 2>&1

if [ "$status" -ne 0 ]; then
  adb devices -l > ci-logs/61-api23-device-after.log 2>&1
  # Bounded tail. .logcat rather than .log so the generic ci-logs/*.log failure emitter cannot
  # republish it unredacted; only the diagnostic step reads it.
  adb logcat -d -t 400 > ci-logs/61-api23-logcat-tail.logcat 2>&1
fi

# The last act of the script. Its absence while the console log exists is what tells the diagnostic
# step that the script was terminated mid-flight rather than exiting.
printf 'completed %s\n' "$status" > ci-logs/60-api23-script-completed.txt

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "status=$status" >> "$GITHUB_OUTPUT"
fi

exit "$status"
