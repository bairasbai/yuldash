#!/usr/bin/env bash
# Disposable local PostgreSQL only. Never accepts an existing DATABASE_URL.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${YULDASH_TEST_PG_BIN:?Set the directory of installed trusted PostgreSQL binaries}"
python="${YULDASH_TEST_PYTHON:-python}"
for command in initdb pg_ctl; do
  [[ -x "$YULDASH_TEST_PG_BIN/$command" ]] || { echo "NOT RUN: missing $command" >&2; exit 77; }
done
# No inherited remote DB/service, libpq options, or HTTP proxy configuration.
while IFS='=' read -r name _; do
  case "$name" in PG*|DATABASE_URL|HTTP_PROXY|HTTPS_PROXY|ALL_PROXY|http_proxy|https_proxy|all_proxy) unset "$name";; esac
done < <(env)
umask 077
runtime="$(mktemp -d /tmp/yuldash-revision-pg.XXXXXXXX)"
started=0
cleanup() {
  if (( started )) || [[ -f "$runtime/data/postmaster.pid" ]]; then
    if ! "$YULDASH_TEST_PG_BIN/pg_ctl" -D "$runtime/data" -m immediate -t 10 -w stop >/dev/null; then
      echo "Cleanup failed: temporary cluster retained at $runtime" >&2
      trap - EXIT
      exit 1
    fi
  fi
  rm -rf -- "$runtime"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$runtime/tmp"
"$python" - "$runtime/password" <<'PYSECRET'
import secrets, sys
from pathlib import Path
Path(sys.argv[1]).write_text(secrets.token_hex(32))
PYSECRET
port="$("$python" -c "import socket; s=socket.socket(); s.bind(('127.0.0.1',0)); print(s.getsockname()[1]); s.close()")"
export TZ=UTC PGPASSFILE=/dev/null PGSERVICEFILE=/dev/null
"$YULDASH_TEST_PG_BIN/initdb" -D "$runtime/data" -U yuldash_test --pwfile="$runtime/password" --auth-local=reject --auth-host=scram-sha-256 --encoding=UTF8 --no-locale > "$runtime/initdb.log"
# Unix sockets are unavailable in this executor. Bind only loopback with a fresh password.
if ! "$YULDASH_TEST_PG_BIN/pg_ctl" -D "$runtime/data" -l "$runtime/postgres.log" -o "-h 127.0.0.1 -p $port -c unix_socket_directories='' -c statement_timeout=10000 -c lock_timeout=4000" -t 10 -w start >/dev/null; then
  cat "$runtime/postgres.log" >&2
  exit 1
fi
started=1
"$python" - "$runtime" "$port" <<'PYBOOT'
import sys
from pathlib import Path
import psycopg2
root = Path(sys.argv[1])
# Explicit loopback and fresh credentials; no URL or service inherited.
connection = psycopg2.connect(host="127.0.0.1", port=int(sys.argv[2]), password=(root / "password").read_text(), user="yuldash_test", dbname="postgres")
connection.autocommit = True
try:
    with connection.cursor() as cursor:
        cursor.execute("SHOW data_directory")
        if Path(cursor.fetchone()[0]).resolve() != root / "data":
            raise RuntimeError("Refusing unknown cluster")
        cursor.execute("SELECT inet_server_addr()")
        if str(cursor.fetchone()[0]) != "127.0.0.1":
            raise RuntimeError("Only loopback is permitted")
        cursor.execute("CREATE DATABASE yuldash_revision_fixture")
finally:
    connection.close()
PYBOOT
password="$(cat "$runtime/password")"
export DATABASE_URL="postgresql+psycopg2://yuldash_test:$password@127.0.0.1:$port/yuldash_revision_fixture"
unset password
export YULDASH_TEST_PG_DATA="$runtime/data" TMPDIR="$runtime/tmp"
"$python" -m pytest tests/test_taxi_search_revision_postgres.py tests/test_taxi_expiry_cleanup_postgres.py tests/test_taxi_accept_cleanup_postgres.py tests/test_taxi_tried_lifecycle_postgres.py tests/test_taxi_offer_dispatch_postgres.py tests/test_taxi_tried_rollback_postgres.py tests/test_taxi_tried_history_schema_postgres.py tests/test_taxi_tried_history_gc_postgres.py tests/test_taxi_tried_history_write_postgres.py tests/test_taxi_tried_history_init_postgres.py tests/test_taxi_tried_history_reader_postgres.py tests/test_taxi_offer_publication_postgres.py tests/test_taxi_driver_reservation_protocol_postgres.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
