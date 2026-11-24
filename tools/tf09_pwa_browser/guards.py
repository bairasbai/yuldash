"""Standard-library-only fail-closed preflight and exact Chromium canary parser."""
import errno
import fcntl
import os
import socket
import struct
from pathlib import Path

_REQUIRED = {"PID namespaces", "Network namespaces", "Seccomp-BPF sandbox"}
_POSITIVE = "You are adequately sandboxed."


def sandbox_verified(body, rows):
    lines = {line.strip() for line in body.splitlines()}
    if _POSITIVE not in lines or any("not adequately sandboxed" in s.lower() for s in lines):
        return False
    known = {}
    for row in rows:
        if len(row) == 2 and row[0] in _REQUIRED:
            if row[0] in known:
                return False
            known[row[0]] = row[1]
    return known == dict.fromkeys(_REQUIRED, "Yes")


def network_table_is_local(interfaces, ipv4, ipv6):
    found = {line.split(":", 1)[0].strip() for line in interfaces.splitlines()[2:] if ":" in line}
    return (found == {"lo"} and all(line.split()[0] == "lo" for line in ipv4.splitlines()[1:] if line.strip())
            and all(line.split()[-1] == "lo" for line in ipv6.splitlines() if line.strip()))


def require_isolation():
    if os.getuid() == 0 or os.geteuid() != os.getuid():
        raise RuntimeError("non-root runner required")
    if any(key.lower().endswith("_proxy") and value for key, value in os.environ.items()):
        raise RuntimeError("inherited proxy rejected")
    if not network_table_is_local(*(Path(f"/proc/net/{p}").read_text() for p in ("dev", "route", "ipv6_route"))):
        raise RuntimeError("non-loopback interface or route rejected")
    # Inspect lo, never modify it. UDP connect below is a route lookup: no payload is sent.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        flags = bytearray(struct.pack("16sH22x", b"lo", 0))
        fcntl.ioctl(probe.fileno(), 0x8913, flags)
        if not struct.unpack_from("H", flags, 16)[0] & 1:
            raise RuntimeError("isolated loopback is not ready")
    for family, target in ((socket.AF_INET, ("192.0.2.1", 9)), (socket.AF_INET6, ("2001:db8::1", 9))):
        with socket.socket(family, socket.SOCK_DGRAM) as probe:
            try:
                probe.connect(target)
            except OSError as exc:
                if exc.errno not in (errno.ENETUNREACH, errno.EHOSTUNREACH):
                    raise
            else:
                raise RuntimeError("external route unexpectedly available")


def worker_environment(private, backend, tools, browser):
    return {
        "PATH": "/usr/bin:/bin", "HOME": str(private), "TMPDIR": str(private),
        "LANG": "C.UTF-8", "TZ": "UTC", "TF09_BROWSER_ISOLATED": "1", "TF09_CHROMIUM": str(browser),
        "DATABASE_URL": f"sqlite:///{private / 'guard.sqlite3'}",
        "ENV": "dev", "SEED_DEMO": "false", "REDIS_URL": "",
        "PYTHONPATH": os.pathsep.join((str(tools), str(backend))),
        "PYTHONDONTWRITEBYTECODE": "1", "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1",
    }
