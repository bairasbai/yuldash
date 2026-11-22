#!/usr/bin/env bash
# Run only on a fresh, expendable, rootable AVD. The test host never opens an external API.
set -euo pipefail
cd "$(dirname "$0")/../.."
pkg=com.yuldash.app
suite=com.yuldash.app.TaxiConsentRecoveryInstrumentedTest
runner=com.yuldash.app.test/com.yuldash.app.StorageAuditRunner
private=$(mktemp -d)
results=android/build/taxi-consent-emulator
device_dir=files/taxi-consent-qa
mkdir -p "$results"
fixture_pid= phase_pid=
cleanup() {
    local status=$?
    if [[ $status != 0 && -n ${case_name:-} ]]; then
        for frame in quote-review interrupted cold-entry resolved roundtrip-resolved; do
            if adb shell run-as "$pkg" test -f "$device_dir/$frame-failed.png"; then
                adb exec-out run-as "$pkg" cat "$device_dir/$frame-failed.png" > "$results/$case_name-$frame-failed.png" || true
            fi
        done
        adb shell dumpsys window | grep -m2 -E 'mCurrentFocus=|mFocusedApp=' > "$results/$case_name-foreground.txt" || true
    fi
    if [[ -n "$phase_pid" ]]; then kill "$phase_pid" 2>/dev/null || true; fi
    if [[ -n "$fixture_pid" ]]; then kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; fi
    adb reverse --remove tcp:8000 >/dev/null 2>&1 || true
    rm -rf -- "$private"
}
trap cleanup EXIT
export PYTHONUNBUFFERED=1

[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
if adb shell pm list packages | tr -d '\r' | grep -Fxq "package:$pkg"; then
    echo 'This acceptance run requires a fresh emulator without an existing installation.' >&2
    exit 1
fi
adb root
adb wait-for-device
[[ $(adb shell id -u | tr -d '\r') == 0 ]]
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb install -t android/app/build/outputs/apk/debug/app-debug.apk
adb install -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
app_uid=$(adb shell pm list packages -U "$pkg" | tr -d '\r' | awk -v p="package:$pkg" '$1==p {sub(/^uid:/,"",$2); print $2}')
[[ $app_uid =~ ^[0-9]+$ && $app_uid -ge 10000 ]]
adb shell iptables -N TAXI_QA
adb shell iptables -A TAXI_QA -o lo -d 127.0.0.1/32 -p tcp --dport 8000 -j RETURN
adb shell iptables -A TAXI_QA -j REJECT
adb shell iptables -I OUTPUT 1 -m owner --uid-owner "$app_uid" -j TAXI_QA
adb shell ip6tables -I OUTPUT 1 -m owner --uid-owner "$app_uid" -j REJECT
adb shell iptables -C OUTPUT -m owner --uid-owner "$app_uid" -j TAXI_QA
adb shell ip6tables -C OUTPUT -m owner --uid-owner "$app_uid" -j REJECT
adb reverse tcp:8000 tcp:8000

wait_device_file() {
    local name=$1
    for ((i=0; i<180; i++)); do
        if adb shell run-as "$pkg" test -f "$device_dir/$name"; then return; fi
        if ! kill -0 "$phase_pid" 2>/dev/null; then
            cat "$phase_log" >&2
            echo "Instrumentation ended before $name" >&2
            return 1
        fi
        sleep 1
    done
    echo "Timed out waiting for $name" >&2
    return 1
}
continue_after_check() {
    adb shell run-as "$pkg" touch "$device_dir/$1.continue"
}
start_phase() {
    local method=$1 language=$2 dark=$3 order_id=$4
    phase_log="$results/$case_name-$method.txt"
    adb shell am instrument -w -r -e class "$suite#$method" -e taxiConsentFixture true \
        -e case "$case_name" -e language "$language" -e dark "$dark" -e orderId "$order_id" \
        "$runner" >"$phase_log" 2>&1 &
    phase_pid=$!
}
check_state() {
    local point=$1 condition=$2
    jq -e "$condition" "$state" >/dev/null || {
        echo "SQL/HTTP mismatch at $case_name/$point" >&2
        cat "$state" >&2
        return 1
    }
    cp "$state" "$results/$case_name-$point.json"
}
finish_phase() {
    wait "$phase_pid"
    phase_pid=
    if ! grep -Eq '^OK \(1 test\)' "$phase_log"; then cat "$phase_log" >&2; exit 1; fi
}
collect_frame() {
    adb exec-out run-as "$pkg" cat "$device_dir/$1.png" > "$results/$case_name-$1.png"
    [[ -s "$results/$case_name-$1.png" ]]
}
stop_fixture() {
    kill "$fixture_pid"
    wait "$fixture_pid" 2>/dev/null || true
    fixture_pid=
}

for case_name in committed closed roundtrip; do
    adb shell pm clear "$pkg" | grep -q Success
    adb shell run-as "$pkg" mkdir -p "$device_dir"
    session="$private/$case_name-session.json"
    state="$private/$case_name-state.json"
    python tools/taxi_consent_emulator/fixture.py --case "$case_name" --session-file "$session" --state-file "$state" \
        >"$private/$case_name-server.log" 2>&1 &
    fixture_pid=$!
    for ((i=0; i<90; i++)); do
        [[ -s "$session" ]] && break
        kill -0 "$fixture_pid" || { cat "$private/$case_name-server.log" >&2; exit 1; }
        sleep 1
    done
    [[ -s "$session" ]]
    adb shell -T "run-as $pkg sh -c 'umask 077; cat > $device_dir/session.json'" < "$session"
    if [[ $case_name == roundtrip ]]; then
        start_phase roundTripRetainsWaitAndCreatesOrder ru false 0
        finish_phase
        check_state final '.quote == 2 and .confirm == 1 and .result == 0 and .close == 0 and .orders == 1 and .order_status == "offered" and .attempts == ["committed","issued"] and .legacy == 0 and .quoted_round_trips == [[false,0],[true,120]] and .confirmed_round_trip == true and .confirmed_return_wait_min == 120'
        [[ $(adb shell run-as "$pkg" cat "$device_dir/roundtrip-resolved-order-id" | tr -d '\r') == "$(jq -r .order_id "$state")" ]]
        collect_frame roundtrip-resolved
        stop_fixture
        echo 'Passed the round trip with a 120-minute return wait.'
        continue
    fi
    if [[ $case_name == committed ]]; then seed_lang=ru; cold_lang=ba; else seed_lang=ba; cold_lang=ru; fi
    start_phase confirmThenWaitForForceStop "$seed_lang" false 0
    wait_device_file review.ready
    check_state review '.quote == 1 and .confirm == 0 and .orders == 0 and .result == 0 and .close == 0 and .legacy == 0'
    continue_after_check review
    wait_device_file force-stop.ready
    if [[ $case_name == committed ]]; then expected_orders=1; else expected_orders=0; fi
    before_read=".quote == 1 and .confirm == 1 and .result == 0 and .close == 0 and .card == 0 and .legacy == 0 and .orders == $expected_orders"
    check_state interrupted "$before_read"
    order_id=$(jq -r '.order_id // 0' "$state")
    adb shell am force-stop "$pkg"
    wait "$phase_pid" || true
    phase_pid=
    [[ -z $(adb shell pidof "$pkg" | tr -d '\r') ]]
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
    start_phase coldEntryChecksOrClosesOriginalAttempt "$cold_lang" true "$order_id"
    wait_device_file cold.ready
    check_state cold "$before_read"
    continue_after_check cold
    if [[ $case_name == closed ]]; then
        wait_device_file cancel-close.ready
        check_state dismissed '.result == 1 and .close == 0 and .confirm == 1 and .orders == 0 and .legacy == 0'
        continue_after_check cancel-close
    fi
    finish_phase
    if [[ $case_name == committed ]]; then
        check_state final '.quote == 1 and .confirm == 1 and .result == 1 and .close == 0 and .card >= 1 and .orders == 1 and .order_status == "expired" and .last_card_status == "expired" and .attempts == ["committed"] and .legacy == 0'
    else
        check_state final '.quote == 1 and .confirm == 1 and .result == 1 and .close == 1 and .card == 0 and .orders == 0 and .order_status == null and .last_card_status == null and .attempts == ["closed"] and .late_status == 409 and .legacy == 0'
    fi
    for frame in quote-review interrupted cold-entry resolved; do
        collect_frame "$frame"
    done
    stop_fixture
    echo "Passed $case_name on fresh Android processes."
done
