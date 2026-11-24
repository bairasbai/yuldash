"""Metadata-only capability probe for one disposable Actions runner."""
import json
import os
from pathlib import Path
import re
import secrets
import socket
import stat
import subprocess
import sys

PRODUCT = "402b4cd4efb6255070345ab6f8649264d0769448"
IP = "/usr/sbin/ip"
SUDO = ["/usr/bin/sudo", "-n", "--"]
VERSION = r"[0-9]{1,3}(?:\.[0-9]{1,8}){1,3}"
CLEAN = {"LC_ALL": "C", "PATH": "/usr/local/bin:/usr/bin:/bin"}


def command(argv, seconds=5):
    try:
        run = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             env=CLEAN, timeout=seconds, check=False)
        return (run.returncode, run.stdout.decode("ascii", errors="ignore")[:8192])
    except (OSError, subprocess.TimeoutExpired):
        return (125, "")


def version(argv, pattern):
    rc, text = command(argv)
    match = re.search(pattern, text, re.MULTILINE) if rc == 0 else None
    return match[1] if match and re.fullmatch(VERSION, match[1]) else "unavailable"


def worker(original_inode, expected_uid):
    if (os.getuid() != expected_uid or os.geteuid() != expected_uid or expected_uid < 1 or
            os.getgroups() or os.stat("/proc/self/ns/net").st_ino == original_inode):
        return False
    status = Path("/proc/self/status").read_text()
    if (any(not re.search(rf"^{key}:[ \t]+0+$", status, re.M)
            for key in ("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb")) or
            not re.search(r"^NoNewPrivs:[ \t]+1$", status, re.M)):
        return False
    for args, kind in [(["-j", "link", "show"], "link"),
                       (["-j", "-4", "route", "show", "table", "all"], "route"),
                       (["-j", "-6", "route", "show", "table", "all"], "route")]:
        rc, raw = command([IP, *args])
        if rc:
            return False
        items = json.loads(raw)
        if not isinstance(items, list):
            return False
        if kind == "link":
            if len(items) != 1 or items[0]["ifname"] != "lo" or "UP" not in items[0]["flags"]:
                return False
        elif any(item.get("dev") != "lo" or item.get("gateway") or item.get("dst") == "default"
                 for item in items):
            return False
    with socket.socket() as listener:
        listener.settimeout(2)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        with socket.create_connection(listener.getsockname(), timeout=2) as client:
            connection, _ = listener.accept()
            with connection:
                connection.settimeout(2)
                client.sendall(b"x")
                return connection.recv(1) == b"x"


def isolated_loopback():
    if any(not os.access(path, os.X_OK) for path in (IP, SUDO[0], "/usr/bin/setpriv", "/usr/bin/timeout")):
        return False
    name = "tf09-probe-" + secrets.token_hex(8)
    if command([*SUDO, IP, "netns", "add", name])[0]:
        return False
    clean = False
    proved = False
    try:
        if command([*SUDO, IP, "-n", name, "link", "set", "dev", "lo", "up"])[0] == 0:
            inode = os.stat("/proc/self/ns/net").st_ino
            rc, out = command(["/usr/bin/timeout", "--kill-after=2s", "12s", *SUDO, IP, "netns", "exec", name,
                              "/usr/bin/setpriv", "--reuid", str(os.getuid()), "--regid", str(os.getgid()),
                              "--clear-groups", "--bounding-set=-all", "--inh-caps=-all",
                              "--ambient-caps=-all", "--no-new-privs", "/usr/bin/python3", "-I", "-B", str(Path(__file__).resolve()),
                              "--worker", str(inode), str(os.getuid())], seconds=16)
            proved = rc == 0 and out == "worker-ok\n"
    finally:
        rc, pids = command([*SUDO, IP, "netns", "pids", name])
        removed = command([*SUDO, IP, "netns", "delete", name])[0] == 0
        listed, listing = command([*SUDO, IP, "netns", "list"])
        clean = (rc == 0 and not pids.strip() and removed and listed == 0 and
                 not any(line.split() and line.split()[0] == name for line in listing.splitlines()))
    return proved and clean


def kvm_open(flags):
    try:
        if not stat.S_ISCHR(os.lstat("/dev/kvm").st_mode):
            return False
        fd = os.open("/dev/kvm", flags | os.O_NOFOLLOW | os.O_CLOEXEC)
        os.close(fd)
        return True
    except OSError:
        return False


def main():
    if (os.environ.get("TF09_CAPABILITY_VERIFIED") != PRODUCT or
            os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Linux" or
            os.environ.get("RUNNER_ARCH") != "X64" or os.geteuid() == 0):
        print("::error title=TF09 host probe::preflight=refused")
        return 125
    chrome = version(["/usr/bin/google-chrome", "--version"], rf"^Google Chrome ({VERSION})$")
    chromium = version(["/usr/bin/chromium", "--version"], rf"^Chromium ({VERSION})(?: .*)?$")
    node = version(["node", "--version"], rf"^v({VERSION})$")
    python = version(["/usr/bin/python3", "--version"], rf"^Python ({VERSION})$")
    sdk_root = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    sdk = Path(sdk_root) if sdk_root and sdk_root.startswith("/") else None
    # No start-server, sdkmanager, package install, emulator boot, API or browser launch.
    emulator = (version([str(sdk / "emulator/emulator"), "-version"],
                        rf"^Android emulator version ({VERSION})(?: .*)?$") if sdk else "unavailable")
    adb = (version([str(sdk / "platform-tools/adb"), "version"],
                   rf"^Version ({VERSION})(?:-[0-9]+)?$") if sdk else "unavailable")
    api30 = bool(sdk and (sdk / "platforms/android-30/android.jar").is_file())
    image30 = bool(sdk and (sdk / "system-images/android-30/default/x86_64/system.img").is_file())
    ns = isolated_loopback()
    kr, kw, krw = (kvm_open(f) for f in (os.O_RDONLY, os.O_WRONLY, os.O_RDWR))
    data = {"chrome": chrome, "chromium": chromium, "node": node, "python": python, "sdk_available": bool(sdk and sdk.is_dir()),
            "emulator": emulator, "adb": adb, "api30_present": api30, "aosp30_x64_present": image30,
            "isolated_loopback_worker_and_cleanup": ns, "kvm_open_read": kr, "kvm_open_write": kw, "kvm_open_readwrite": krw}
    print("::notice title=TF09 host capabilities (no browser/emulator run)::" + json.dumps(data, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--worker":
        success = worker(int(sys.argv[2]), int(sys.argv[3]))
        if success:
            print("worker-ok")
        raise SystemExit(0 if success else 1)
    raise SystemExit(main())
