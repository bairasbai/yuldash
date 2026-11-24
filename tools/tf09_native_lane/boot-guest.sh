#!/usr/bin/env bash
# The same loopback-only namespace owns the emulator, adb server, fixture and test driver.
set +x
set -euo pipefail
umask 077
# Normalize unexpected boot exits; 86 is emitted only by the controlled test verdict branch.
test_failure=false
finish() {
    local status=$?
    trap - EXIT
    if [[ ${pid:-} ]]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" || true
        adb kill-server || true
    fi
    if $test_failure; then exit 86; fi
    if [[ $status != 0 ]]; then exit 1; fi
}
trap finish EXIT
[[ $# -ge 3 ]]
host_ns=$1
expected_uid=$2
mode=$3
shift 3
[[ $(id -u) == "$expected_uid" && $expected_uid -gt 0 && $(readlink /proc/self/ns/net) != "$host_ns" ]]
awk '/^Cap(Eff|Prm|Amb|Bnd):/ { if ($2 !~ /^0+$/) exit 1; ++caps }
    /^NoNewPrivs:/ { if ($2 != 1) exit 1; ++nnp } END {if (caps != 4 || nnp != 1) exit 1}' /proc/self/status
[[ $(ip -o link show | awk -F': ' '{print $2}') == lo ]]
[[ $(ip -o link show lo) == *UP* ]]
[[ -z $(ip -4 route show table main) && -z $(ip -6 route show table main) ]]
python3 -I -B - <<'PY'
import socket
with socket.socket() as server:
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    with socket.create_connection(server.getsockname(), timeout=1):
        with server.accept()[0]:
            pass
PY
if [[ $mode == probe ]]; then exit 0; fi
[[ $mode == run && $# == 4 ]]
product=$1
lane=$2
debug=$3
tests=$4
image="$ANDROID_HOME/system-images/android-30/default/x86_64"
avd="$ANDROID_AVD_HOME/tf09.avd"
mkdir "$avd"
cat >"$ANDROID_AVD_HOME/tf09.ini" <<EOF
avd.ini.encoding=UTF-8
path=$avd
target=android-30
EOF
cat >"$avd/config.ini" <<EOF
AvdId=tf09
avd.ini.encoding=UTF-8
abi.type=x86_64
image.sysdir.1=$image/
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
disk.dataPartition.size=3G
EOF
emulator="$ANDROID_HOME/emulator/emulator"
accel=off
if [[ -r /dev/kvm && -w /dev/kvm ]] && timeout 10 "$emulator" -accel-check; then accel=on; fi
adb start-server
"$emulator" -avd tf09 -sysdir "$image" -port 5554 -no-window -no-snapshot -noaudio -no-boot-anim \
    -gpu swiftshader_indirect -camera-back none -accel "$accel" >"$TMPDIR/emulator.out" 2>&1 &
pid=$!
for ((i=0; i<360; i++)); do
    kill -0 "$pid"
    [[ $(timeout 5 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]] && break
    sleep 1
done
[[ $(timeout 5 adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r') == 1 ]]
cd "$product"
# All test evidence stays private; no process may publish raw logs or credentials here.
verdict=$(bash "$lane/run-guest.sh" "$debug" "$tests") || { test_failure=true; exit 1; }
[[ $verdict == 'TF09 Android: joint 4/4, visual 8/8' ]] || { test_failure=true; exit 1; }
