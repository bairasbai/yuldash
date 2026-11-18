"""Observe local connection close versus server backend exit, without cloning.

This fresh-cluster diagnostic does not retry phase 2 or change its zero guard.
A locally closed DBAPI connection does not prove its backend has already exited.
"""

from datetime import datetime, timezone
import json
import os
from time import monotonic, sleep

from sqlalchemy import text

import test_taxi_actual_schema_pg_type_rehearsal as rehearsal

source = rehearsal.source
MODE = "actual-schema-close-observation"
ACTIVITY = text(
    "SELECT pid,backend_type,state,application_name,backend_start,state_change,"
    "wait_event_type,wait_event FROM pg_stat_activity WHERE datname=:db ORDER BY pid"
)


def test_observe_twenty_physical_closes_per_source(source):
    assert os.environ.get("YULDASH_TEST_PG_MODE") == MODE
    url, source_db, source_name, root = source
    artifact = root / f"close-observation-{source_name}.jsonl"
    admin = rehearsal._engine(url)
    nonzero_cycles = []
    completed = 0
    try:
        with artifact.open("x") as evidence:

            def record(kind, **values):
                evidence.write(
                    json.dumps(
                        {
                            "event": kind,
                            "source": source_name,
                            "utc": datetime.now(timezone.utc).isoformat(),
                            "monotonic": monotonic(),
                            **values,
                        },
                        default=str,
                        sort_keys=True,
                    )
                    + "\n"
                )
                evidence.flush()

            print(f"close observation evidence: {artifact}")
            with admin.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as observer:
                rehearsal._guard(observer, url.database)
                assert observer.connection.driver_connection.autocommit is True
                observer_pid = observer.exec_driver_sql(
                    "SELECT pg_backend_pid()"
                ).scalar_one()
                record("observer", pid=observer_pid, autocommit=True)
                try:
                    for cycle in range(20):
                        template = rehearsal._engine(url.set(database=source_db))
                        try:
                            with template.connect() as connection:
                                rehearsal._guard(connection, source_db)
                                raw = connection.connection.driver_connection
                                own_pid = connection.exec_driver_sql(
                                    "SELECT pg_backend_pid()"
                                ).scalar_one()
                                assert own_pid != observer_pid
                                # Exactly the source reads immediately preceding
                                # the failing fixture's close/dispose sequence.
                                rehearsal._catalog(connection)
                                rehearsal._data(connection)
                                record(
                                    "before_close",
                                    cycle=cycle,
                                    own_pid=own_pid,
                                    raw_closed=raw.closed,
                                )
                            closed_at = monotonic()
                        finally:
                            template.dispose()
                        record(
                            "physical_close",
                            cycle=cycle,
                            own_pid=own_pid,
                            raw_closed=raw.closed,
                            closed_at=closed_at,
                        )
                        assert raw.closed != 0
                        deadline = closed_at + 2.0
                        previous = None
                        first_nonzero = None
                        while True:
                            rows = [
                                dict(row)
                                for row in observer.execute(ACTIVITY, {"db": source_db})
                                .mappings()
                                .all()
                            ]
                            observed_at = monotonic()
                            if rows != previous:
                                record(
                                    "backend_transition",
                                    cycle=cycle,
                                    own_pid=own_pid,
                                    raw_closed=raw.closed,
                                    backends=rows,
                                    elapsed=observed_at - closed_at,
                                )
                                previous = rows
                            if rows and first_nonzero is None:
                                first_nonzero = rows
                                nonzero_cycles.append(cycle)
                                record(
                                    "first_nonzero",
                                    cycle=cycle,
                                    own_pid=own_pid,
                                    raw_closed=raw.closed,
                                    backends=rows,
                                    elapsed=observed_at - closed_at,
                                )
                            if not rows and observed_at <= deadline:
                                record(
                                    "cycle_zero",
                                    cycle=cycle,
                                    own_pid=own_pid,
                                    elapsed=observed_at - closed_at,
                                    had_nonzero=first_nonzero is not None,
                                )
                                completed += 1
                                break
                            if observed_at >= deadline:
                                record(
                                    "timeout",
                                    cycle=cycle,
                                    own_pid=own_pid,
                                    backends=rows,
                                    raw_closed=raw.closed,
                                )
                                raise AssertionError(
                                    f"source backends remained after 2s; evidence: {artifact}"
                                )
                            sleep(min(0.01, max(0.0, deadline - monotonic())))
                finally:
                    record(
                        "summary",
                        completed_cycles=completed,
                        nonzero_cycles=nonzero_cycles,
                    )
    finally:
        admin.dispose()


def _records(path):
    # Writer flushes every line. Ignore an in-flight incomplete final line only.
    if not path.exists():
        return []
    data = path.read_text().splitlines(keepends=True)
    return [json.loads(line) for line in data if line.endswith("\n")]


def test_zero_wait_controls(source):
    """Real held client timeout, observed nonzero-to-close, then immediate zero."""
    from concurrent.futures import ThreadPoolExecutor
    import pytest
    from sqlalchemy import event

    assert os.environ.get("YULDASH_TEST_PG_MODE") == MODE
    url, source_db, source_name, root = source
    admin = rehearsal._engine(url)
    template = rehearsal._engine(url.set(database=source_db))
    statements = []

    def observed_sql(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    event.listen(admin, "before_cursor_execute", observed_sql)
    held = template.connect()
    try:
        rehearsal._guard(held, source_db)
        pid = held.exec_driver_sql("SELECT pg_backend_pid()").scalar_one()
        raw = held.connection.driver_connection
        timeout_log = root / f"held-timeout-{source_name}.jsonl"
        with admin.connect().execution_options(isolation_level="AUTOCOMMIT") as c:
            with pytest.raises(AssertionError, match="within 2s"):
                rehearsal._await_zero_backends(
                    c, source_db, timeout_log, own_pid=pid, raw_closed=raw.closed
                )
            assert raw.closed == 0
            assert (
                c.execute(
                    text("SELECT count(*) FROM pg_stat_activity WHERE pid=:pid"),
                    {"pid": pid},
                ).scalar_one()
                == 1
            )
        records = _records(timeout_log)
        assert any(
            row["event"] == "first_nonzero"
            and any(
                b["pid"] == pid and b["backend_type"] == "client backend"
                for b in row["backends"]
            )
            for row in records
        )
        assert records[-1]["event"] == "timeout"
        transition_log = root / f"held-release-{source_name}.jsonl"

        def wait_for_release():
            with admin.connect().execution_options(isolation_level="AUTOCOMMIT") as c:
                rehearsal._await_zero_backends(
                    c, source_db, transition_log, own_pid=pid, raw_closed=0
                )

        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(wait_for_release)
            try:
                deadline = monotonic() + 1.0
                while monotonic() < deadline:
                    if any(
                        r["event"] == "first_nonzero" for r in _records(transition_log)
                    ):
                        break
                    sleep(0.005)
                else:
                    raise AssertionError("held client was not observed before release")
                held.close()
                template.dispose()
                assert raw.closed != 0
                future.result(timeout=3)
            finally:
                held.close()  # Release before executor join even on assertion failure.
        records = _records(transition_log)
        assert records[-1]["event"] == "zero" and records[-1]["backends"] == []
        immediate_log = root / f"immediate-zero-{source_name}.jsonl"
        with admin.connect().execution_options(isolation_level="AUTOCOMMIT") as c:
            rehearsal._await_zero_backends(
                c, source_db, immediate_log, own_pid=pid, raw_closed=raw.closed
            )
        immediate = _records(immediate_log)
        assert immediate[-1]["event"] == "zero"
        assert immediate[0]["backends"] == []
        assert not any(
            s.lstrip().upper().startswith(("CREATE", "DROP")) for s in statements
        )
    finally:
        held.close()
        template.dispose()
        event.remove(admin, "before_cursor_execute", observed_sql)
        admin.dispose()
