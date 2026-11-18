#!/usr/bin/env bash
# Disposable local PostgreSQL only. Never accepts an existing DATABASE_URL.
set -euo pipefail
# Only named fixed suites; reject arbitrary pytest arguments before any bootstrap.
(( $# <= 1 )) || { echo "Usage: $0 [regression|driver-slot-app-compat|generation-representation|generation-adapter|generation-migration-rehearsal|resume-composition|durable-writer-known-gaps|locked-revision-bump|entry-writer-locks|route-capture-locks|actual-schema-baseline|actual-schema-pg-type-rehearsal|actual-schema-pg-domain-rehearsal|actual-schema-close-observation|storage-check-contract|actual-orm-counter|matching-resume-known-gaps]" >&2; exit 64; }
mode="${1-regression}"
case "$mode" in regression|driver-slot-app-compat|generation-representation|generation-adapter|generation-migration-rehearsal|resume-composition|durable-writer-known-gaps|locked-revision-bump|entry-writer-locks|route-capture-locks|actual-schema-baseline|actual-schema-pg-type-rehearsal|actual-schema-pg-domain-rehearsal|actual-schema-close-observation|storage-check-contract|actual-orm-counter|matching-resume-known-gaps) ;; *) echo "Unknown test mode" >&2; exit 64;; esac
export YULDASH_TEST_PG_MODE="$mode"
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
import os, sys
from pathlib import Path
import psycopg2
root = Path(sys.argv[1])
databases = ["yuldash_revision_fixture"]
if os.environ.get("YULDASH_TEST_PG_MODE") in ("actual-schema-baseline", "actual-schema-pg-type-rehearsal", "actual-schema-pg-domain-rehearsal", "actual-schema-close-observation", "storage-check-contract", "actual-orm-counter"):
    databases += ["yuldash_schema_chain_fixture", "yuldash_schema_init_fixture"]
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
        for database in databases:
            # Names are fixed above, never supplied by a caller or DSN.
            cursor.execute('CREATE DATABASE "' + database + '"')
finally:
    connection.close()
# Verify every newly created fixed database BEFORE pytest imports any app code.
for database in databases:
    connection = psycopg2.connect(host="127.0.0.1", port=int(sys.argv[2]), password=(root / "password").read_text(), user="yuldash_test", dbname=database)
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT current_database(), inet_server_addr(), inet_server_port()")
            name, host, port = cursor.fetchone()
            if name != database or str(host) != "127.0.0.1" or port != int(sys.argv[2]):
                raise RuntimeError("Refusing unexpected test database")
            cursor.execute("SHOW data_directory")
            if Path(cursor.fetchone()[0]).resolve() != root / "data":
                raise RuntimeError("Refusing unknown database cluster")
    finally:
        connection.close()
PYBOOT
password="$(cat "$runtime/password")"
export DATABASE_URL="postgresql+psycopg2://yuldash_test:$password@127.0.0.1:$port/yuldash_revision_fixture"
unset password
export YULDASH_TEST_PG_DATA="$runtime/data" TMPDIR="$runtime/tmp"
if [[ "$mode" == "actual-orm-counter" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_actual_orm_counter.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "storage-check-contract" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_storage_check_postgres.py tests/test_taxi_storage_check_sqlite.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "actual-schema-close-observation" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_pg_close_observation.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "actual-schema-pg-domain-rehearsal" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_actual_schema_pg_domain_rehearsal.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "actual-schema-pg-type-rehearsal" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_actual_schema_pg_type_rehearsal.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "actual-schema-baseline" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_actual_schema_baseline.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "route-capture-locks" ]]; then
  "$python" -m pytest tests/test_taxi_route_capture_locks.py tests/test_taxi_locked_revision_bump.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "entry-writer-locks" ]]; then
  "$python" -m pytest tests/test_taxi_entry_writer_locks.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "locked-revision-bump" ]]; then
  "$python" -m pytest tests/test_taxi_locked_revision_bump.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "durable-writer-known-gaps" ]]; then
  "$python" -m pytest tests/test_taxi_delayed_tokenless_request_known_gaps.py tests/test_taxi_pending_writer_known_gaps.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "generation-migration-rehearsal" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_generation_migration_rehearsal.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "resume-composition" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_resume_transaction_composition.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "generation-adapter" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_generation_adapter_lab.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "generation-representation" ]]; then
  "$python" -m pytest --noconftest tests/test_taxi_generation_representation_spike.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "matching-resume-known-gaps" ]]; then
  "$python" -m pytest tests/test_taxi_matching_resume_known_gaps.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
elif [[ "$mode" == "driver-slot-app-compat" ]]; then
  "$python" -m pytest tests/test_taxi_driver_slot_app_compat_postgres.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
else
  "$python" -m pytest tests/test_taxi_search_revision_postgres.py tests/test_taxi_expiry_cleanup_postgres.py tests/test_taxi_accept_cleanup_postgres.py tests/test_taxi_tried_lifecycle_postgres.py tests/test_taxi_offer_dispatch_postgres.py tests/test_taxi_tried_rollback_postgres.py tests/test_taxi_tried_history_schema_postgres.py tests/test_taxi_tried_history_gc_postgres.py tests/test_taxi_tried_history_write_postgres.py tests/test_taxi_tried_history_init_postgres.py tests/test_taxi_tried_history_reader_postgres.py tests/test_taxi_offer_publication_postgres.py tests/test_taxi_driver_reservation_protocol_postgres.py tests/test_taxi_driver_slot_invariant_postgres.py tests/test_taxi_matching_resume_schema_postgres.py -q --disable-socket --allow-unix-socket --allow-hosts=127.0.0.1
fi
