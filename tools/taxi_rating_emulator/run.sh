#!/usr/bin/env bash
# Disposable CI AVD only. No backend, proxy, API credentials, or real account.
set -euo pipefail

package=com.yuldash.app
test_package=com.yuldash.app.test
runner=$test_package/androidx.test.runner.AndroidJUnitRunner
classes=$package.TaxiDriverCompletedInstrumentedTest,$package.TaxiPostTripInstrumentedTest
: "${RUNNER_TEMP:?expected GitHub Actions runner temporary directory}"
: "${TF11_SUBJECT:?expected pinned checkout commit}"
actual_subject=$(git -C subject rev-parse HEAD)
[[ $actual_subject == "$TF11_SUBJECT" ]]

adb_call() { timeout -k 2s 15s adb "$@"; }
device_value() { adb_call shell "$@" | tr -d '\r'; }
guest_type=$(device_value getprop ro.kernel.qemu)
sdk=$(device_value getprop ro.build.version.sdk)
boot=$(device_value getprop sys.boot_completed)
[[ $guest_type == 1 && $sdk == 30 && $boot == 1 ]]
installed=$(device_value pm list packages)
if grep -Fxq -e "package:$package" -e "package:$test_package" <<< "$installed"; then
    echo "An existing test package makes the emulator non-disposable." >&2
    exit 1
fi

# An initial adbd-root restart can break the request. Require a live root shell.
adb_call root || true
adb_call wait-for-device
device_uid=$(device_value id -u)
[[ $device_uid == 0 ]]
adb_call shell svc wifi disable
adb_call shell svc data disable
adb_call shell settings put global airplane_mode_on 1
adb_call shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null
airplane_mode=$(device_value settings get global airplane_mode_on)
[[ $airplane_mode == 1 ]]
if ! reverse_list=$(adb_call reverse --list | tr -d '\r'); then
    echo "Could not verify absence of ADB reverse mappings." >&2
    exit 1
fi
if [[ -n $reverse_list ]]; then
    echo "The disposable emulator has unexpected ADB reverse mappings." >&2
    exit 1
fi

# APK install does not launch either stopped package. Verify both UIDs and reject all
# IPv4/IPv6 OUTPUT for each UID before the first instrumentation can launch the app.
apk=subject/android/app/build/outputs/apk
adb_call install -t "$apk/debug/app-debug.apk"
adb_call install -t "$apk/androidTest/debug/app-debug-androidTest.apk"
instrumentation=$(device_value pm list instrumentation)
grep -Fxq "instrumentation:$runner (target=$package)" <<< "$instrumentation"
for pkg in "$package" "$test_package"; do
    packages=$(device_value pm list packages -U "$pkg")
    uid=$(awk -v pkg="package:$pkg" '$1 == pkg && $2 ~ /^uid:[0-9]+$/ {
        print substr($2, 5)
    }' <<< "$packages")
    [[ $uid =~ ^[0-9]+$ && $uid -ge 10000 ]]
    for firewall in iptables ip6tables; do
        adb_call shell "$firewall" -I OUTPUT 1 -m owner --uid-owner "$uid" -j REJECT
        adb_call shell "$firewall" -C OUTPUT -m owner --uid-owner "$uid" -j REJECT
    done
done
adb_call shell svc power stayon true
adb_call shell input keyevent KEYCODE_WAKEUP
adb_call shell wm dismiss-keyguard

# Keep raw instrument output local and ephemeral. Only the fixed-method allowlist
# and sanitized status can leave the runner; app stdout/logcat is never uploaded.
raw=$(mktemp "$RUNNER_TEMP/tf11-instrument.XXXXXX")
trap 'rm -f -- "$raw"' EXIT
status=0
timeout -k 5s 180s adb shell am instrument -w -r -e class "$classes" "$runner" >"$raw" 2>&1 || status=$?
python3 qa/tools/taxi_rating_emulator/instrumentation_summary.py \
    "$raw" "$status" "$RUNNER_TEMP/taxi-tf11-summary.json"
