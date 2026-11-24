"""Child-process network gate. CPython hooks do not intercept libpq, so gate it separately."""
import os
import socket
import sys
from pathlib import Path

EVENTS = {
    "socket.connect", "socket.bind", "socket.sendto", "socket.getaddrinfo",
    "socket.gethostbyname", "socket.gethostbyaddr", "socket.getnameinfo",
}
PG_SOCKET = Path("/tmp/yuldash-taxi-cross-version-pg")
PG_ROLE_DB = "taxi_cross_version_qa"


def deny(event):
    with open(os.environ["QA_DENIED"], "a", encoding="ascii") as stream:
        stream.write(event + "\n")
    raise PermissionError("QA denies this connection")


def before_ip(event, args):
    if event in EVENTS and (not args or getattr(args[0], "family", None) != socket.AF_UNIX):
        deny(event)


def install():
    import psycopg2
    from psycopg2.extensions import parse_dsn

    connect = psycopg2.connect

    def guarded_connect(dsn=None, **kwargs):
        expected = dict(
            dbname=PG_ROLE_DB, user=PG_ROLE_DB, host=str(PG_SOCKET),
            port="5432", connect_timeout="5",
        )
        try:
            options = parse_dsn(psycopg2.extensions.make_dsn(dsn, **kwargs))
        except Exception:
            deny("libpq.invalid_dsn")
        if os.environ["QA_DB"] != "postgresql" or options != expected:
            deny("libpq.unapproved_dsn")
        node = PG_SOCKET / ".s.PGSQL.5432"
        st = node.lstat()
        if PG_SOCKET.resolve(strict=True) != PG_SOCKET or not node.is_socket() or node.is_symlink():
            deny("libpq.invalid_socket")
        if os.environ["QA_SOCKET_ID"] != f"{st.st_dev}:{st.st_ino}":
            deny("libpq.replaced_socket")
        return connect(**expected)

    psycopg2.connect = guarded_connect


if "QA_DENIED" in os.environ:
    sys.addaudithook(before_ip)
    # sitecustomize import exceptions otherwise only warn and allow the child to run.
    try:
        install()
    except BaseException:
        os._exit(92)
    os.environ["QA_GUARD_INSTALLED"] = "1"
