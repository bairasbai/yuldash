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
        if [[ -s ${state:-} ]]; then
            jq '{schedule, schedule_http, orders, order_status, activate} + (if has("draft_admission") then {draft_admission} else {} end)' "$state" > "$results/$case_name-failure-state.json" || true
        fi
        # Draft runs already capture their own screenshots. The frame files below
        # belong only to the consent instrumentation suite.
        if [[ ${TAXI_DRAFT_ONLY:-false} != true ]]; then
            for frame in quote-review interrupted cold-entry resolved roundtrip-resolved third-selected scheduled-list scheduled-active scheduled-form; do
                if timeout -k 1s 3s adb shell run-as "$pkg" test -f "$device_dir/$frame-failed.png"; then
                    timeout -k 1s 5s adb exec-out run-as "$pkg" cat "$device_dir/$frame-failed.png" > "$results/$case_name-$frame-failed.png" || true
                fi
            done
        fi
        timeout -k 1s 5s adb shell dumpsys window | grep -m2 -E 'mCurrentFocus=|mFocusedApp=' > "$results/$case_name-foreground.txt" || true
    fi
    if [[ -n "$phase_pid" ]]; then kill "$phase_pid" 2>/dev/null || true; fi
    if [[ -n "$fixture_pid" ]]; then
        kill "$fixture_pid" 2>/dev/null || true
        if ! timeout 5s tail --pid="$fixture_pid" -f /dev/null; then
            kill -KILL "$fixture_pid" 2>/dev/null || true
        fi
        wait "$fixture_pid" 2>/dev/null || true
    fi
    timeout -k 1s 5s adb reverse --remove tcp:8000 >/dev/null 2>&1 || true
    rm -rf -- "$private"
}
trap cleanup EXIT
export PYTHONUNBUFFERED=1

[[ $(adb shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
if adb shell pm list packages | tr -d '\r' | grep -Fxq "package:$pkg"; then
    echo 'This acceptance run requires a fresh emulator without an existing installation.' >&2
    exit 1
fi
# adbd may close the root request while it is restarting. Require a live root
# shell before setting up the isolated network or installing the test APK.
for attempt in 1 2 3; do
    timeout 10s adb root || true
    if timeout 15s adb wait-for-device &&
            device_uid=$(timeout 5s adb shell id -u | tr -d '\r') &&
            [[ $device_uid == 0 ]]; then
        break
    fi
    if [[ $attempt == 3 ]]; then
        echo 'Unable to verify a root shell on the test emulator.' >&2
        exit 1
    fi
    sleep 1
done
adb shell svc wifi disable
adb shell svc data disable
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
candidate_apk=android/app/build/outputs/apk/debug/app-debug.apk
baseline_apk=${TAXI_CONSENT_BASELINE_APK:-}
if [[ -n $baseline_apk ]]; then
    baseline_hash=$(sha256sum "$baseline_apk" | cut -d' ' -f1)
    candidate_hash=$(sha256sum "$candidate_apk" | cut -d' ' -f1)
    [[ $baseline_hash != "$candidate_hash" ]]
fi
adb install -t "${baseline_apk:-$candidate_apk}"
if [[ ${TAXI_DRAFT_ONLY:-false} == true ]]; then
    observer=com.yuldash.qa.taxidraft
    observer_apks=(android/taxiDraftObserver/build/outputs/apk/debug/*.apk)
    [[ ${#observer_apks[@]} == 1 ]]
    adb install -t "${observer_apks[0]}"
    adb shell pm list instrumentation | tr -d '\r' | grep -F " (target=$observer)" >/dev/null
else
    adb install -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
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

force_stop_phase() {
    adb shell am force-stop "$pkg"
    wait "$phase_pid" || true
    phase_pid=
    [[ -z $(adb shell pidof "$pkg" | tr -d '\r') ]]
    adb shell input keyevent KEYCODE_WAKEUP
    adb shell wm dismiss-keyguard
}

installed_apk_hash() {
    local installed
    installed=$(adb shell pm path "$pkg" | tr -d '\r' | sed -n 's/^package:\(.*\/base\.apk\)$/\1/p')
    [[ -n $installed ]] || return 1
    adb shell sha256sum "$installed" | cut -d' ' -f1
}

if [[ ${TAXI_DRAFT_ONLY:-false} == true ]]; then
    [[ -z $baseline_apk ]]
    cases=(restore restore-ba delayed relogin alternate)
    if [[ ${TAXI_DRAFT_VARIANT:-candidate} == baseline ]]; then cases=(baseline); fi
    for scenario in "${cases[@]}"; do
        case_name="draft-$scenario"
        adb shell pm clear "$pkg" | grep -q Success
        adb shell run-as "$pkg" mkdir -p "$device_dir"
        session="$private/$case_name-session.json"
        state="$private/$case_name-state.json"
        fixture_case=draft
        if [[ $scenario == delayed ]]; then fixture_case=draft-delayed; fi
        python tools/taxi_consent_emulator/fixture.py --case "$fixture_case" --session-file "$session" --state-file "$state" \
            >"$private/$case_name-server.log" 2>&1 &
        fixture_pid=$!
        for ((i=0; i<90; i++)); do
            [[ -s $session ]] && break
            kill -0 "$fixture_pid" || { cat "$private/$case_name-server.log" >&2; exit 1; }
            sleep 1
        done
        [[ -s $session ]]
        python tools/taxi_consent_emulator/draft_task.py "$scenario" "$session" "$state" "$results"
        stop_fixture
    done
    exit 0
fi

# Run the upgrade first so the previous APK is the only app that has touched this fresh installation.
for case_name in selection-ba-dark committed closed roundtrip selection-ru-light selection-ru-dark selection-ba-light scheduled-ru-light late-ru-dark late-ba-light unavailable-ba-dark; do
    adb shell pm clear "$pkg" | grep -q Success
    adb shell run-as "$pkg" mkdir -p "$device_dir"
    session="$private/$case_name-session.json"
    state="$private/$case_name-state.json"
    python tools/taxi_consent_emulator/fixture.py --case "${case_name%%-*}" --session-file "$session" --state-file "$state" \
        >"$private/$case_name-server.log" 2>&1 &
    fixture_pid=$!
    for ((i=0; i<90; i++)); do
        [[ -s "$session" ]] && break
        kill -0 "$fixture_pid" || { cat "$private/$case_name-server.log" >&2; exit 1; }
        sleep 1
    done
    [[ -s "$session" ]]
    adb shell -T "run-as $pkg sh -c 'umask 077; cat > $device_dir/session.json'" < "$session"
    if [[ $case_name == scheduled-* || $case_name == late-* || $case_name == unavailable-* ]]; then
        IFS=- read -r _ language theme <<< "$case_name"
        dark=false
        if [[ $theme == dark ]]; then dark=true; fi
        start_phase createsAndActivatesScheduledRoundTrip "$language" "$dark" 0
        wait_device_file scheduled.ready
        trip='.schedule == 1 and .legacy == 0 and .confirm == 0 and .orders == 1 and .confirmed_round_trip == true and .confirmed_return_wait_min == 120'
        route='.route == .scheduled_route and .route.stops == ["QA Stop A","QA Stop B"] and .route.km == 180 and .route.minutes == 90 and .route.fare > 0'
        check_state created "$trip and $route and .activate == 0 and .order_status == \"scheduled\""
        continue_after_check scheduled
        if [[ $case_name == late-* ]]; then
            wait_device_file response-pending.ready
            # Let the device leave only once the live FastAPI activation committed behind the bridge.
            for ((i=0; i<100; i++)); do
                if jq -e '.activate == 1 and .order_status == "offered"' "$state" >/dev/null; then break; fi
                sleep 0.1
            done
            check_state pending "$trip and $route and .activate == 1 and .order_status == \"offered\" and .released == false"
            continue_after_check response-pending
            wait_device_file left.ready
            touch "${state%.json}.release"
            continue_after_check left
        fi
        finish_phase
        expected_status=offered
        if [[ $case_name == unavailable-* ]]; then expected_status=expired; fi
        check_state final "$trip and $route and .activate == 1 and .order_status == \"$expected_status\""
        collect_frame scheduled-list
        if [[ $case_name != late-* ]]; then
            check_state opened ".card >= 1 and .last_card_status == \"$expected_status\""
            [[ $(adb shell run-as "$pkg" cat "$device_dir/scheduled-active-order-id" | tr -d '\r') == "$(jq -r .order_id "$state")" ]]
            collect_frame scheduled-active
        else
            check_state returned '.released == true'
        fi
        stop_fixture
        echo "Passed scheduled round trip ($case_name)."
        continue
    fi
    if [[ $case_name == selection-* ]]; then
        IFS=- read -r _ language theme <<< "$case_name"
        dark=false
        if [[ $theme == dark ]]; then dark=true; fi
        start_phase seedThreeIssuedAttemptsAndWaitForRestart "$language" "$dark" 0
        wait_device_file force-stop.ready
        restart=force-stop
        if [[ $case_name == selection-ba-dark && -n $baseline_apk ]]; then
            restart=apk-update
            [[ $(installed_apk_hash) == "$baseline_hash" ]]
            adb install -r -t "$candidate_apk"
            wait "$phase_pid" || true
            phase_pid=
            [[ $(installed_apk_hash) == "$candidate_hash" ]]
            adb shell pm list packages -U "$pkg" | tr -d '\r' | grep -Fx "package:$pkg uid:$app_uid" >/dev/null
            jq -n --arg old "$baseline_hash" --arg new "$candidate_hash" \
                '{before_apk_sha256:$old, after_apk_sha256:$new, retained_uid:true}' > "$results/$case_name-upgrade.json"
        else
            force_stop_phase
        fi
        start_phase coldEntryChecksAndClosesOnlyThirdAttempt "$language" "$dark" 0
        untouched='.orders == 0 and .quote == 0 and .confirm == 0 and .card == 0 and .legacy == 0'
        issued='.states_by_slot == ["issued","issued","issued"]'
        wait_device_file selected.ready
        check_state selected "$untouched and $issued and .result == 0 and .close == 0"
        continue_after_check selected
        wait_device_file cancel-close.ready
        check_state dismissed "$untouched and $issued and .result == 1 and .addressed_slots.result == [3] and .close == 0"
        continue_after_check cancel-close
        finish_phase
        check_state final "$untouched and .states_by_slot == [\"issued\",\"issued\",\"closed\"] and .result == 1 and .close == 1 and .addressed_slots == {result:[3],close:[3]}"
        collect_frame third-selected
        stop_fixture
        echo "Passed third-attempt selection ($language/$theme, $restart)."
        continue
    fi
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
    force_stop_phase
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
