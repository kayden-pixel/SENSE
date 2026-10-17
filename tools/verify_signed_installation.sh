#!/usr/bin/env bash
# Drive the exact signed consumer APK from a separate UI Automator process.
# No test code or test runtime is injected into the optimized application.
set -euo pipefail

reports=signed-installation-reports
mkdir -p "$reports"
device=(adb -s emulator-5554)
collect_diagnostics() {
  timeout 15 "${device[@]}" logcat -d -v threadtime \
    AndroidRuntime:E TestRunner:I AndroidJUnitRunner:I System.err:W '*:S' \
    > "$reports/android-test.log" 2>&1 || true
  timeout 15 "${device[@]}" pull \
    /sdcard/Android/data/app.sense.releasecheck/files/release-screen.png \
    "$reports/release-screen.png" > /dev/null 2>&1 || true
}
trap collect_diagnostics EXIT

test_apks=(release-check/build/outputs/apk/androidTest/debug/*.apk)
[[ ${#test_apks[@]} -eq 1 && -f "${test_apks[0]}" ]]
timeout 60 "${device[@]}" wait-for-device
timeout 120 "${device[@]}" install -r app/build/outputs/apk/release/app-release.apk
timeout 120 "${device[@]}" install -r release-check/build/outputs/apk/debug/release-check-debug.apk
timeout 120 "${device[@]}" install -r "${test_apks[0]}"
timeout 15 "${device[@]}" logcat -c

timeout 360 "${device[@]}" shell am instrument -w -r \
  -e class app.sense.releasecheck.ReleaseInstallationTest \
  app.sense.releasecheck.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$reports/instrumentation.txt"

# Android's am command can return exit code zero after a test failure. Require
# the actual runner result, the expected test, and a successful completion code.
grep -Fq 'INSTRUMENTATION_STATUS: test=freshInstallRegistersRestartsAndRecovers' "$reports/instrumentation.txt"
grep -Eq '^OK \(1 tests?\)' "$reports/instrumentation.txt"
grep -Fq 'INSTRUMENTATION_CODE: -1' "$reports/instrumentation.txt"
