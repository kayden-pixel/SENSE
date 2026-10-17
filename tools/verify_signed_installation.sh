#!/usr/bin/env bash
# Run the same installation journey directly through AndroidJUnitRunner. The
# Gradle device controller can stall before it produces a result for release
# instrumentation; each device command here is bounded and publication still
# requires an explicit one-test success from the exact signed application.
set -euo pipefail

reports=signed-installation-reports
mkdir -p "$reports"
device=(adb -s emulator-5554)
collect_diagnostics() {
  timeout 15 "${device[@]}" logcat -d -v threadtime \
    AndroidRuntime:E TestRunner:I AndroidJUnitRunner:I System.err:W '*:S' \
    > "$reports/android-test.log" 2>&1 || true
}
trap collect_diagnostics EXIT

test_apks=(app/build/outputs/apk/androidTest/release/*.apk)
[[ ${#test_apks[@]} -eq 1 && -f "${test_apks[0]}" ]]
timeout 60 "${device[@]}" wait-for-device
timeout 120 "${device[@]}" install -r app/build/outputs/apk/release/app-release.apk
timeout 120 "${device[@]}" install -r "${test_apks[0]}"
"${device[@]}" logcat -c

timeout 360 "${device[@]}" shell am instrument -w -r \
  -e class app.sense.InstallationTest \
  app.sense.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$reports/instrumentation.txt"

# Android's am command can return exit code zero after a test failure. Require
# the actual runner result, the expected test, and a successful completion code.
grep -Fq 'INSTRUMENTATION_STATUS: test=freshInstallRegistersRestoresAndSignsInWithItsPrivateKey' "$reports/instrumentation.txt"
grep -Eq '^OK \(1 tests?\)' "$reports/instrumentation.txt"
grep -Fq 'INSTRUMENTATION_CODE: -1' "$reports/instrumentation.txt"
