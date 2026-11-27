#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 34
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
shopt -s nullglob
app=(android/app/build/outputs/apk/debug/*.apk)
test_apk=(android/app/build/outputs/apk/androidTest/debug/*.apk)
(( ${#app[@]} == 1 && ${#test_apk[@]} == 1 ))
adb install "${app[0]}"
adb install "${test_apk[0]}"
adb shell pm grant com.yuldash.app android.permission.POST_NOTIFICATIONS
result="$(mktemp)"
trap 'rm -f "$result"' EXIT
timeout 75 adb shell am instrument -w \
    -e taxiAlertTray isolated \
    -e class com.yuldash.app.TaxiAlertTrayInstrumentedTest \
    com.yuldash.app.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$result"
grep -Fxq "INSTRUMENTATION_STATUS: taxi_tray_result=verified" "$result"
grep -Eq '^OK \(1 test\)[[:space:]]*$' "$result"
! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|shortMsg=' "$result"
