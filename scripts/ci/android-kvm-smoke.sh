#!/usr/bin/env bash
# One disposable, permission-isolated fixture. Not production/device acceptance.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
TEST='com.yuldash.app.TaxiDriverCancelledInstrumentedTest#freePassengerCancel_showsNoCompensationAndRecoveryAction'
RUNNER='com.yuldash.app.StorageAuditRunner'
APP='com.yuldash.app'
OUT="$RUNNER_TEMP/android-kvm-smoke"
mkdir -p "$OUT"
case "${1:-}" in
build)
  # Never read/write the project's secret-bearing local.properties.
  test ! -e android/local.properties
  test ! -e android/app/google-services.json
  for variant in debug androidTest; do
    file="android/app/src/$variant/AndroidManifest.xml"
    test ! -e "$file"
    mkdir -p "$(dirname "$file")"
    cat > "$file" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
</manifest>
XML
  done
  cd android
  bash ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
    -PstorageAuditRunner="$RUNNER" --no-daemon --max-workers=2 --stacktrace
  cd "$ROOT"
  AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt2 -type f | sort -V | tail -1)"
  test -x "$AAPT"
  for apk in android/app/build/outputs/apk/debug/app-debug.apk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
    test -s "$apk"
    permissions="$("$AAPT" dump permissions "$apk")"
    if grep -q 'android.permission.INTERNET' <<<"$permissions"; then
      echo 'OFFLINE_PERMISSION_CHECK=FAIL'; exit 1
    fi
    manifest="$("$AAPT" dump xmltree "$apk" --file AndroidManifest.xml)"
    if grep -q 'sharedUserId' <<<"$manifest"; then
      echo 'UNEXPECTED_SHARED_UID'; exit 1
    fi
    if [[ "$apk" == *androidTest* ]]; then
      grep -Fq "$RUNNER" <<<"$manifest"
    fi
    sha256sum "$apk"
  done
  echo 'OFFLINE_PERMISSION_CHECK=PASS (both APKs; no INTERNET; no shared UID)'
  ;;
test)
  ADB=(adb -s emulator-5554)
  test "$("${ADB[@]}" shell getprop sys.boot_completed | tr -d '\r')" = 1
  echo "BOOT=PASS ABI=$("${ADB[@]}" shell getprop ro.product.cpu.abi | tr -d '\r') API=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
  # No application is launched before its kernel-enforced permission boundary is checked.
  "${ADB[@]}" install -t android/app/build/outputs/apk/debug/app-debug.apk
  "${ADB[@]}" install -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
  for pkg in "$APP" "$APP.test"; do
    dump="$("${ADB[@]}" shell dumpsys package "$pkg")"
    if grep -q 'android.permission.INTERNET' <<<"$dump"; then
      echo 'INSTALLED_PERMISSION_CHECK=FAIL'; exit 1
    fi
  done
  echo 'INSTALLED_PERMISSION_CHECK=PASS'
  "${ADB[@]}" logcat -c
  timeout 180 "${ADB[@]}" shell am instrument -w -r -e class "$TEST" \
    "$APP.test/$RUNNER" > "$OUT/instrumentation.txt" 2>&1
  python3 - "$OUT/instrumentation.txt" <<'PY'
import re, sys
s = open(sys.argv[1]).read().replace('\r\n', '\n')
expected_class = 'com.yuldash.app.TaxiDriverCancelledInstrumentedTest'
expected_test = 'freePassengerCancel_showsNoCompensationAndRecoveryAction'
blocks = re.findall(r'((?:INSTRUMENTATION_STATUS:.*\n)+)INSTRUMENTATION_STATUS_CODE: (-?\d+)', s)
finished = []
for body, code in blocks:
    fields = dict(re.findall(r'INSTRUMENTATION_STATUS: ([^=]+)=(.*)', body))
    if code == '0':
        finished.append((fields.get('class'), fields.get('test'), fields.get('numtests')))
assert finished == [(expected_class, expected_test, '1')], 'Expected exactly one successful named test'
assert re.search(r'OK \(1 test\)', s), 'Missing runner success'
assert 'INSTRUMENTATION_CODE: -1' in s, 'Missing successful instrumentation finish'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -(1|2|3|4)\b|INSTRUMENTATION_FAILED|FAILURES!!!', s), 'Failure/skip detected'
print('INSTRUMENTATION=PASS tests=1 failures=0 skipped=0')
print('TEST=' + expected_class + '#' + expected_test)
PY
  echo 'SMOKE=PASS (offline fixture only; no real provider/device acceptance)'
  ;;
*) exit 2 ;;
esac

