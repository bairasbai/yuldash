#!/usr/bin/env bash
# The same loopback-only namespace owns only the emulator and adb server.
set +x
set -euo pipefail
umask 077
# Emit only reserved statuses from this reviewed wrapper.
phase=86
finish() {
    local status=$?
    trap - EXIT
    if [[ ${pid:-} ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" || true; fi
    if [[ ${adb_started:-} ]]; then timeout 10 adb kill-server >/dev/null 2>&1 || true; fi
    if [[ $status == 0 ]]; then exit 0; fi
    exit "$phase"
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
[[ $mode == canary && $# == 0 ]]
phase=87
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
if [[ -r /dev/kvm && -w /dev/kvm ]] && timeout 10 "$emulator" -accel-check >/dev/null 2>&1; then
    accel=on
else
    # Officially documented software CPU mode; exact archived binary behavior is what this canary tests.
    export ANDROID_I_WANT_MY_TCG=yes
fi
printf 'TF09 boot canary: acceleration=%s\n' "$accel"
phase=88
timeout 15 adb start-server >/dev/null 2>&1
adb_started=1
phase=89
"$emulator" -avd tf09 -sysdir "$image" -port 5554 -no-window -no-snapshot -noaudio -no-boot-anim \
    -gpu swiftshader_indirect -camera-back none -accel "$accel" >"$TMPDIR/emulator.out" 2>&1 &
pid=$!
deadline=$((SECONDS+600))
attached=false
while ((SECONDS < deadline)); do
    if ! kill -0 "$pid"; then phase=89; exit 1; fi
    state=$(timeout 5 adb -s emulator-5554 get-state 2>/dev/null || true)
    if [[ $state == device ]]; then
        attached=true
        if [[ $(timeout 5 adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true) == 1 ]]; then
            phase=92
            [[ $(timeout 5 adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r') == 30 ]]
            [[ $(timeout 5 adb -s emulator-5554 shell getprop ro.product.cpu.abi | tr -d '\r') == x86_64 ]]
            [[ $(timeout 5 adb -s emulator-5554 shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]]
            exit 0
        fi
    fi
    sleep 2
done
if $attached; then phase=91; else phase=90; fi
exit 1
