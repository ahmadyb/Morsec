#!/usr/bin/env bash
# Runs the API 23 connected tests and captures everything the diagnostic step
# needs to name a failure category.
#
# WHY THIS IS A FILE AND NOT AN INLINE script: BLOCK
# reactivecircus/android-emulator-runner@v2 passes its `script` input through
# parseScript, which splits on newlines and executes EVERY LINE as a separate
# `sh -c`, aborting the loop at the first non-zero exit (lib/script-parser.js and
# lib/main.js). A multi-line inline script is therefore not a script at all:
# `set +e` applies to nothing, variables do not survive to the next line, and an
# `if` header with no matching `fi` on the same line is a syntax error that fails
# the step. That is exactly what happened in runs 38365708870, 38368188165 and
# 38370664983: Gradle printed BUILD SUCCESSFUL with 4 of 4 tests passing, and the
# step still failed, because the `if` line never parsed.
#
# The workflow therefore invokes this file as one single-line command.

set +e
set -u

mkdir -p ci-logs

# Device state before anything runs, so a boot or ADB problem is attributable.
adb devices -l > ci-logs/60-api23-device-before.log 2>&1
adb shell getprop ro.build.version.sdk > ci-logs/60-api23-api-level.log 2>&1

./gradlew --no-daemon --stacktrace --console=plain \
  :transport-lan:connectedDebugAndroidTest > ci-logs/60-api23-connected.log 2>&1
status=$?

# Written immediately: the value must survive even if this script is killed
# afterwards, which is the whole reason the diagnostic step exists.
printf '%s\n' "$status" > ci-logs/60-api23-exit-status.txt
echo "connectedDebugAndroidTest exit status: $status"
tail -n 150 ci-logs/60-api23-connected.log

if [ "$status" -ne 0 ]; then
  adb devices -l > ci-logs/61-api23-device-after.log 2>&1
  # Bounded tail. .logcat rather than .log so the generic ci-logs/*.log failure
  # emitter cannot republish it unredacted; only the diagnostic step reads it.
  adb logcat -d -t 400 > ci-logs/61-api23-logcat-tail.logcat 2>&1
fi

# The last act of the script. Its absence while the console log exists is what
# tells the diagnostic step the script was terminated mid-flight.
printf 'completed %s\n' "$status" > ci-logs/60-api23-script-completed.txt

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "status=$status" >> "$GITHUB_OUTPUT"
fi

exit "$status"
