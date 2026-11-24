#!/usr/bin/env bash
# Called by an approved one-use hosted job. Provisioning may use network; the guest runtime may not.
set +x
set -euo pipefail
umask 077
export LC_ALL=C
exec 3>&1
exec >/dev/null 2>&1
phase=preflight
ns=''
work=''
cleanup() {
    local code=$?
    trap - EXIT
    if [[ -n $ns ]]; then
        # Only the named namespace created by this job. The guest worker is separately bounded.
        sudo -n ip netns delete "$ns" || { if [[ $code == 0 ]]; then phase=unknown; fi; code=1; }
    fi
    if [[ -n $work ]]; then
        rm -rf -- "$work" || { if [[ $code == 0 ]]; then phase=unknown; fi; code=1; }
    fi
    if [[ $code == 0 ]]; then
        printf 'TF09 boot canary: isolated API30 guest booted; no APK or app tests\n' >&3
    else
        case "$phase" in
            preflight|provision|boot-entry|boot-isolation|avd-setup|adb-server|emulator-exit|adb-serial|android-timeout|guest-identity) ;;
            *) phase=unknown ;;
        esac
        printf '::error title=TF09 Android boot canary::%s\n' "$phase" >&3
        if [[ -n ${GITHUB_OUTPUT:-} ]]; then printf 'reported=true\n' >> "$GITHUB_OUTPUT" || true; fi
    fi
    exit "$code"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
[[ $# == 2 && $(id -u) -gt 0 && ${RUNNER_ENVIRONMENT:-} == github-hosted ]]
product=$1
lane=$2
cd "$product"
[[ $(git rev-parse HEAD) == 402b4cd4efb6255070345ab6f8649264d0769448 ]]
[[ $(git rev-parse 'HEAD^{tree}') == 0dc0d5e804001e87a950c644598fe6bd5e959693 ]]
git diff --quiet HEAD -- .
for cmd in ip sudo setpriv curl unzip sha256sum git python3; do command -v "$cmd"; done
uid=$(id -u)
gid=$(id -g)
host_namespace=$(readlink /proc/self/ns/net)
work=$(mktemp -d)
name="tf09-$(python3 -c 'import secrets; print(secrets.token_hex(8))')"
sudo -n ip netns add "$name"
ns=$name
sudo -n ip -n "$ns" link set lo up
# Only setup of isolation is privileged; the entire guest toolchain owns no host caps or sudo handles.
offline() {
    sudo -n ip netns exec "$ns" setpriv --reuid "$uid" --regid "$gid" --clear-groups         --bounding-set=-all --inh-caps=-all --ambient-caps=-all --no-new-privs         env -i PATH="$work/sdk/platform-tools:/usr/bin:/bin" LC_ALL=C HOME="$work/home" TMPDIR="$work/tmp"         ANDROID_HOME="$work/sdk" ANDROID_SDK_ROOT="$work/sdk" ANDROID_AVD_HOME="$work/avd"         /bin/bash "$lane/boot-guest.sh" "$host_namespace" "$uid" "$@"
}
mkdir -p "$work"/{home,tmp,avd,sdk,dl}
offline probe

phase=provision
download() {
    local url=$1 digest=$2 file=$3
    curl --silent --show-error --fail --location --proto '=https' --proto-redir '=https' --max-redirs 4         --connect-timeout 15 --max-time 450 -o "$file" "$url"
    printf '%s  %s\n' "$digest" "$file" | sha256sum --check --status
}
google=https://dl.google.com/android/repository
download "$google/emulator-linux_x64-7920983.zip" 5690099ab213a6265bc025d1d2218055e6b9d69414972c13cf2ef1c98a9c4565 "$work/dl/emulator.zip"
download "$google/platform-tools_r31.0.3-linux.zip" e6cb61b92b5669ed6fd9645fad836d8f888321cd3098b75588a54679c204b7dc "$work/dl/tools.zip"
download "$google/sys-img/android/x86_64-30_r10.zip" a97f08f286df90dd510abcfbf7a785f950b42775930f7cf00b1453c59a4ec6e5 "$work/dl/image.zip"
unzip -q "$work/dl/emulator.zip" -d "$work/sdk"
unzip -q "$work/dl/tools.zip" -d "$work/sdk"
image="$work/sdk/system-images/android-30/default"
mkdir -p "$image"
unzip -q "$work/dl/image.zip" -d "$image"
test -x "$work/sdk/emulator/emulator"
test -x "$work/sdk/platform-tools/adb"
test -f "$image/x86_64/system.img"
test -f "$image/x86_64/source.properties"
rm -r "$work/dl"

phase=boot-entry
# Capture stdout privately; the only optional mode records accepted are exact fixed literals.
set +e
marker=$(offline canary)
status=$?
set -e
case "$marker" in
    'TF09 boot canary: acceleration=on'|'TF09 boot canary: acceleration=off')
        printf '%s\n' "$marker" >&3 ;;
esac
case "$status" in
    0)
        [[ $marker == 'TF09 boot canary: acceleration=on' || $marker == 'TF09 boot canary: acceleration=off' ]] ;;
    86) phase=boot-isolation; exit 1 ;;
    87) phase=avd-setup; exit 1 ;;
    88) phase=adb-server; exit 1 ;;
    89) phase=emulator-exit; exit 1 ;;
    90) phase=adb-serial; exit 1 ;;
    91) phase=android-timeout; exit 1 ;;
    92) phase=guest-identity; exit 1 ;;
    *) exit 1 ;;
esac
