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
        sudo -n ip netns delete "$ns" || code=1
    fi
    if [[ -n $work ]]; then rm -rf -- "$work"; fi
    if [[ $code == 0 ]]; then printf 'TF09 isolated Android: joint 4/4, visual 8/8\n' >&3
    else printf 'TF09 isolated Android: failed (%s)\n' "$phase" >&3; fi
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
[[ -z $(git ls-files --others --exclude-standard) ]]
for cmd in ip sudo setpriv curl unzip sha256sum git python3; do command -v "$cmd"; done
[[ -n ${JAVA_HOME_21_X64:-} && -x $JAVA_HOME_21_X64/bin/java && -f ${ANDROID_HOME:?}/platforms/android-36/android.jar ]]
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
mkdir -p "$work"/{home,tmp,avd,sdk,dl,gradle-home}
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
download https://services.gradle.org/distributions/gradle-8.13-bin.zip 20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78 "$work/dl/gradle.zip"
unzip -q "$work/dl/emulator.zip" -d "$work/sdk"
unzip -q "$work/dl/tools.zip" -d "$work/sdk"
image="$work/sdk/system-images/android-30/default"
mkdir -p "$image"
unzip -q "$work/dl/image.zip" -d "$image"
unzip -q "$work/dl/gradle.zip" -d "$work"
test -x "$work/sdk/emulator/emulator" && test -x "$work/sdk/platform-tools/adb"
test -f "$image/x86_64/system.img" && test -f "$image/x86_64/source.properties"
rm -r "$work/dl"

phase=compile
[[ $(grep '^distributionUrl=' android/gradle/wrapper/gradle-wrapper.properties) == 'distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip' ]]
(
    cd android
    env -i PATH=/usr/bin:/bin HOME="$work/home" LC_ALL=C \
        JAVA_HOME="$JAVA_HOME_21_X64" ANDROID_HOME="$ANDROID_HOME" ANDROID_SDK_ROOT="$ANDROID_HOME" \
        GRADLE_USER_HOME="$work/gradle-home" YULDASH_NATIVE_QA_PINNED_API=true \
        YULDASH_DEBUG_API_BASE_URL=http://127.0.0.1:8000 \
        "$work/gradle-8.13/bin/gradle" :app:assembleDebug :app:assembleDebugAndroidTest \
        -PstorageAuditRunner=com.yuldash.app.StorageAuditRunner --no-daemon
)
git diff --quiet HEAD -- .
debug=android/app/build/outputs/apk/debug/app-debug.apk
tests=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
debug_hash=$(sha256sum "$debug" | cut -d' ' -f1)
test_hash=$(sha256sum "$tests" | cut -d' ' -f1)
phase=guest
offline run "$product" "$lane" "$debug_hash" "$test_hash"
