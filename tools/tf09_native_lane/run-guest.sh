#!/usr/bin/env bash
# Reviewed runner candidate. Prereqs: exact trusted tree/APKs, a sole fresh API30 AOSP guest,
# and explicitly approved detached CI. This file does not provision packages or boot an AVD.
set +x
set -euo pipefail
umask 077
export LC_ALL=C
exec 3>&1
exec >/dev/null 2>&1
phase=source
app=com.yuldash.app
test_app=com.yuldash.app.test
runner=$test_app/com.yuldash.app.StorageAuditRunner
fixture_pid=''
work=''
serial=''
forwarded=false
cleanup() {
    local code=$?
    trap - EXIT
    if [[ -n $fixture_pid ]]; then kill "$fixture_pid" || true; wait "$fixture_pid" || true; fi
    if $forwarded; then adb_at reverse --remove tcp:8000 || true; fi
    if [[ -n $work ]]; then rm -rf -- "$work"; fi
    # The runner owning the disposable VM destroys the guest; do not relax the guard.
    if [[ $code == 0 ]]; then printf 'TF09 Android: joint 4/4, visual 8/8\n' >&3
    else printf 'TF09 Android: failed (%s)\n' "$phase" >&3; fi
    exit "$code"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[[ $# == 2 ]]
[[ $(git rev-parse HEAD) == 402b4cd4efb6255070345ab6f8649264d0769448 ]]
[[ $(git rev-parse 'HEAD^{tree}') == 0dc0d5e804001e87a950c644598fe6bd5e959693 ]]
git diff --quiet HEAD -- .
debug=android/app/build/outputs/apk/debug/app-debug.apk
instrument=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ $1 =~ ^[a-f0-9]{64}$ && $2 =~ ^[a-f0-9]{64}$ ]]
printf '%s  %s\n%s  %s\n' "$1" "$debug" "$2" "$instrument" | sha256sum --check --status
phase=guest
unset ANDROID_SERIAL ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT
mapfile -t devices < <(timeout 15 adb devices | awk 'NR > 1 && NF {print $1 " " $2}')
[[ ${#devices[@]} == 1 && ${devices[0]} =~ ^(emulator-[0-9]+)\ device$ ]]
serial=${BASH_REMATCH[1]}
adb_at() { timeout 20 adb -s "$serial" "$@"; }
[[ $(adb_at shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
[[ $(adb_at shell getprop ro.build.version.sdk | tr -d '\r') == 30 ]]
[[ $(adb_at shell getprop ro.product.cpu.abi | tr -d '\r') == x86_64 ]]
installed=$(adb_at shell pm list packages | tr -d '\r')
! grep -Fxq "package:$app" <<< "$installed" || exit 1
! grep -Fxq "package:$test_app" <<< "$installed" || exit 1
adb_at root
adb_at wait-for-device
[[ $(adb_at shell id -u | tr -d '\r') == 0 ]]
adb_at shell svc wifi disable
adb_at shell svc data disable
adb_at shell settings put global airplane_mode_on 1
adb_at shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
[[ $(adb_at shell settings get global airplane_mode_on | tr -d '\r') == 1 ]]
adb_at shell svc power stayon true
adb_at shell input keyevent KEYCODE_WAKEUP
adb_at shell wm dismiss-keyguard
phase=guard
adb_at install -t "$debug"
adb_at install -t "$instrument"
adb_at shell iptables -N TF09_QA
adb_at shell iptables -A TF09_QA -o lo -d 127.0.0.1/32 -p tcp --dport 8000 -j RETURN
adb_at shell iptables -A TF09_QA -j REJECT
for pkg in "$app" "$test_app"; do
    uid=$(adb_at shell pm list packages -U "$pkg" | tr -d '\r' | awk -v p="package:$pkg" '$1==p {sub(/^uid:/,"",$2); print $2}')
    [[ $uid =~ ^[0-9]+$ && $uid -ge 10000 ]]
    adb_at shell iptables -I OUTPUT 1 -m owner --uid-owner "$uid" -j TF09_QA
    adb_at shell ip6tables -I OUTPUT 1 -m owner --uid-owner "$uid" -j REJECT
    adb_at shell iptables -C OUTPUT -m owner --uid-owner "$uid" -j TF09_QA
    adb_at shell ip6tables -C OUTPUT -m owner --uid-owner "$uid" -j REJECT
done
adb_at shell iptables -C TF09_QA -o lo -d 127.0.0.1/32 -p tcp --dport 8000 -j RETURN
adb_at shell iptables -C TF09_QA -j REJECT
adb_at reverse tcp:8000 tcp:8000
forwarded=true
phase=fixture
work=$(mktemp -d)
python3 -I -B tools/taxi_stop_joint_qa/fixture.py "$work" &
fixture_pid=$!
for ((i=0; i<100; i++)); do
    kill -0 "$fixture_pid"
    [[ -s "$work/driver.json" && -s "$work/passenger.json" ]] && break
    sleep .1
done
[[ -s "$work/driver.json" && -s "$work/passenger.json" ]]
result_is() { cmp -s "$work/result" <(printf '%s\n' "$1"); }
result_is pending
clear_app() { adb_at shell pm clear "$app" | tr -d '\r' | grep -Fxq Success; }
exact_success() {
    awk -v c="$1" -v m="$2" '
        {sub(/\r$/,"")}
        /^INSTRUMENTATION_STATUS: class=/ {actual_c=substr($0,length("INSTRUMENTATION_STATUS: class=")+1)}
        /^INSTRUMENTATION_STATUS: test=/ {actual_m=substr($0,length("INSTRUMENTATION_STATUS: test=")+1)}
        /^INSTRUMENTATION_STATUS_CODE:/ {
            if (actual_c != c || actual_m != m) bad=1
            if ($0 == "INSTRUMENTATION_STATUS_CODE: 1" && n == 0) n=1
            else if ($0 == "INSTRUMENTATION_STATUS_CODE: 0" && n == 1) n=2
            else bad=1
            actual_c=actual_m=""
        }
        /^OK \(1 test\)$/ {ok++}
        /^INSTRUMENTATION_CODE: -1$/ {terminal++}
        /^(FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|INSTRUMENTATION_RESULT: shortMsg=)/ {bad=1}
        END {exit !(n==2 && ok==1 && terminal==1 && !bad)}
    ' "$work/instrument.out"
}
run_one() {
    local class=$1 method=$2; shift 2
    timeout 110 adb -s "$serial" shell am instrument -w -r -e class "$class#$method" "$@" "$runner" >"$work/instrument.out" 2>&1
    exact_success "$class" "$method"
    rm -- "$work/instrument.out"
}
phase=joint
joint=com.yuldash.app.TaxiStopJointMountedTest
for pair in driver:driverSelectsSecondLiteralDuplicate passenger:passengerEditsOnlyOtherDuplicateDuringOffDrain \
    driver:driverDrainsSameWaitWithNewRouteRevision passenger:passengerReadsIdleAfterDriverDrains; do
    role=${pair%%:*}; method=${pair#*:}
    kill -0 "$fixture_pid"
    result_is pending
    clear_app
    adb_at shell run-as "$app" mkdir -p files/taxi-stop-joint
    adb_at shell -T 'run-as com.yuldash.app sh -c "umask 077; cat > files/taxi-stop-joint/session.json"' <"$work/$role.json"
    run_one "$joint" "$method" -e tf09JointFixture true -e role "$role"
done
result_is passed
kill "$fixture_pid"
wait "$fixture_pid" || true
fixture_pid=''
rm -- "$work/driver.json" "$work/passenger.json"
phase=visual
visual=com.yuldash.app.TaxiStopVisualMatrixTest
for role in driver passenger; do
    for language in ru ba; do
        for theme in light dark; do
            clear_app
            run_one "$visual" mountedStopVisualCell -e tf09VisualFixture true -e role "$role" -e language "$language" -e theme "$theme"
        done
    done
done
clear_app
