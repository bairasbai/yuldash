"""Isolated actual InstantOrder mapping, not an unchanged production model.

Parent imports no app modules. PG uses only actual init_db registered clones;
a separate guarded prepare child installs existing validated CHECK helpers.
Every mapping scenario starts a new child. No private cache clearing/remapping.
SQLite is fresh full metadata without the PG storage CHECK, not a rebuild.
"""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

import pytest

# Child must guard its exact destination before any shared test-helper import.
if __name__ != "__main__":
    import test_taxi_actual_schema_pg_type_rehearsal as phase1

    source = phase1.source
    database = phase1.database
pytestmark = pytest.mark.skipif(
    os.environ.get("YULDASH_TEST_PG_MODE") != "actual-orm-counter",
    reason="explicit dedicated actual-orm-counter mode required",
)

BACKEND = Path(__file__).resolve().parents[1]
CASE = "mapping_sqlite"
FIELDS = ("search_revision", "matching_resume_revision")
SCENARIOS = ("matrix", "identity", "constructor", "guard_payload")
CASES = {CASE, "prepare_pg"} | {f"{s}_{d}" for s in SCENARIOS for d in ("sqlite", "pg")}


def _root():
    root = Path(os.environ.get("YULDASH_ORM_CASE_ROOT", ""))
    assert root.is_absolute() and root.parent == Path("/tmp")
    assert re.fullmatch(r"yuldash-actual-orm\.[A-Za-z0-9_-]+", root.name)
    assert root.is_dir() and not root.is_symlink()
    return root


def _network_boundary(report):
    """Child-only Python socket denial with executable, separately counted probes.

    This does not intercept libpq's C sockets. The fresh guarded PG connection
    below must prove that the exact test database still works under this policy.
    """
    import socket
    import pytest_socket

    state = {
        "python_policy": "deny all",
        "control_attempts": [],
        "scenario_attempts": [],
    }
    report["network_boundary"] = state
    phase = "control_attempts"
    pytest_socket.disable_socket(allow_unix_socket=False)
    denied_socket = socket.socket
    denied_getaddrinfo = socket.getaddrinfo
    denied_gethostbyname = socket.gethostbyname

    class RecordedDeniedSocket(denied_socket):
        def __new__(cls, *args, **kwargs):
            state[phase].append({"operation": "socket"})
            return super().__new__(cls, *args, **kwargs)

    def getaddrinfo(host, port, *args, **kwargs):
        state[phase].append(
            {"operation": "getaddrinfo", "host": str(host), "port": port}
        )
        return denied_getaddrinfo(host, port, *args, **kwargs)

    def gethostbyname(host):
        state[phase].append({"operation": "gethostbyname", "host": str(host)})
        return denied_gethostbyname(host)

    socket.socket = RecordedDeniedSocket
    socket.getaddrinfo = getaddrinfo
    socket.gethostbyname = gethostbyname
    # These call the real stdlib connection API, denied before DNS/connect.
    for host in ("127.0.0.1", "203.0.113.1"):
        with pytest.raises(pytest_socket.SocketBlockedError):
            socket.create_connection((host, 1), timeout=0.01)
    with pytest.raises(pytest_socket.SocketBlockedError):
        socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    assert state["control_attempts"] == [
        {"operation": "getaddrinfo", "host": "127.0.0.1", "port": 1},
        {"operation": "getaddrinfo", "host": "203.0.113.1", "port": 1},
        {"operation": "socket"},
    ]
    phase = "scenario_attempts"


def _no_unexpected_network(report):
    assert report["network_boundary"]["scenario_attempts"] == [], (
        "Unexpected service socket attempt, including swallowed fallback"
    )


def _guard_destination(root, case, report):
    assert not any(k.startswith("PG") for k in os.environ)
    _network_boundary(report)
    from sqlalchemy import create_engine, text
    from sqlalchemy.engine import make_url
    from sqlalchemy.pool import NullPool

    url = make_url(os.environ["DATABASE_URL"])
    if case.endswith("_pg"):
        record = json.loads((root / "registry.json").read_text())
        assert record["source"] == "yuldash_schema_init_fixture"
        assert re.fullmatch(r"yuldash_type_case_[0-9a-f]{32}", record["database"])
        assert url.drivername == "postgresql+psycopg2" and not url.query
        assert url.username == "yuldash_test"
        assert (
            url.database == record["database"]
            and url.host == record["host"] == "127.0.0.1"
        )
        assert url.port == record["port"]
        data = Path(record["data_directory"])
        assert str(data) == os.environ["YULDASH_TEST_PG_DATA"]
        assert (
            data.is_absolute()
            and data.name == "data"
            and data.parent.parent == Path("/tmp")
        )
        assert data.parent.name.startswith("yuldash-revision-pg.")
        engine = create_engine(url, poolclass=NullPool, hide_parameters=True)
        try:
            with engine.connect() as c:
                probe = c.execute(
                    text(
                        "SELECT current_database(),inet_server_addr(),inet_server_port(),pg_backend_pid()"
                    )
                ).one()
                assert probe[:3] == (record["database"], record["host"], record["port"])
                assert (
                    Path(c.exec_driver_sql("SHOW data_directory").scalar_one()) == data
                )
        except BaseException:
            engine.dispose()
            raise
        _no_unexpected_network(report)
        report["fresh_guarded_libpq_probe"] = {
            "database": record["database"],
            "host": record["host"],
            "port": record["port"],
            "python_socket_deny_active": True,
            "pg_backend_pid": probe[3],
        }
        return engine, {**record, "verified_before_app_imports": True}
    assert case.endswith("_sqlite")
    database = root / "fresh.sqlite"
    assert not database.exists() and not database.is_symlink()
    assert (
        url.drivername == "sqlite" and not url.query and Path(url.database) == database
    )
    return create_engine(url, poolclass=NullPool, hide_parameters=True), {
        "database": str(database),
        "verified_before_app_imports": True,
    }


def _person(engine):
    from sqlmodel import Session
    from app.models import User

    with Session(engine) as s:
        person = User(phone="+70000000003", name="Synthetic ORM scenario")
        s.add(person)
        s.commit()
        s.refresh(person)
        return person.id


def _observe(engine, oid):
    from sqlalchemy import text

    with engine.connect() as c:
        return c.execute(
            text(
                "SELECT search_revision,matching_resume_revision,from_text FROM instantorder WHERE id=:id"
            ),
            {"id": oid},
        ).one_or_none()


def _assert_raw(row, expected, dialect):
    from decimal import Decimal

    assert row is not None
    for actual, value in zip(row[:2], expected):
        if value is None:
            assert actual is None
        elif dialect == "postgresql":
            assert isinstance(actual, Decimal) and actual == Decimal(value)
        else:
            assert type(actual) is str and actual == str(value)


def _matrix(engine, report):
    from sqlalchemy import select, update
    from sqlmodel import Session
    from app.models import InstantOrder

    pid = _person(engine)
    values = [None, 0, 2**63, 10**40 + 1, 10**128 - 1]
    report["roundtrips"] = []
    for field in FIELDS:
        for value in values:
            with Session(engine) as s:
                order = InstantOrder(passenger_id=pid, **{field: value})
                s.add(order)
                s.flush()
                oid = order.id
                assert _observe(engine, oid) is None
                s.commit()
                s.expire(order)
                s.refresh(order)
                actual = getattr(order, field)
                assert actual == value and (actual is None or type(actual) is int)
                s.expunge(order)
                order = s.get(InstantOrder, oid)
                assert getattr(order, field) == value
                raw = _observe(engine, oid)
                expected = tuple(value if name == field else None for name in FIELDS)
                _assert_raw(raw, expected, engine.dialect.name)
                replacement = 1 if value == 0 else 0
                result = s.execute(
                    update(InstantOrder)
                    .where(InstantOrder.id == oid)
                    .values({field: replacement})
                    .returning(getattr(InstantOrder, field))
                ).scalar_one()
                assert type(result) is int and result == replacement
                s.rollback()
                assert _observe(engine, oid) == raw
                assert (
                    s.execute(
                        select(getattr(InstantOrder, field)).where(
                            InstantOrder.id == oid
                        )
                    ).scalar_one()
                    == value
                )
                report["roundtrips"].append(
                    {
                        "field": field,
                        "input": value,
                        "raw_types": [type(v).__name__ for v in raw[:2]],
                        "raw_repr": [repr(v) for v in raw[:2]],
                    }
                )


def _identity(engine, report):
    from contextlib import nullcontext
    from sqlalchemy import inspect, select, update, text
    from sqlmodel import Session
    from app.models import InstantOrder, User

    pid = _person(engine)
    report["identity_modes"] = []
    for number, mode in enumerate(("autoflush", "disabled", "context")):
        with Session(engine) as setup:
            order = InstantOrder(
                passenger_id=pid,
                search_revision=0,
                matching_resume_revision=0,
                from_text="committed route",
            )
            setup.add(order)
            setup.commit()
            setup.refresh(order)
            oid = order.id
        with Session(engine, expire_on_commit=False, autoflush=mode != "disabled") as s:
            order = s.get(InstantOrder, oid)
            s.commit()
            with engine.begin() as writer:
                writer.execute(
                    update(InstantOrder)
                    .where(InstantOrder.id == oid)
                    .values(search_revision=3, matching_resume_revision=3)
                )
            assert order.search_revision == order.matching_resume_revision == 0
            s.expire(order, FIELDS)
            s.refresh(order)
            assert order.search_revision == order.matching_resume_revision == 3
            order.search_revision = 4
            order.matching_resume_revision = 4
            order.from_text = "dirty route"
            pending = User(phone=f"+7000000010{number}", name="Unrelated pending")
            s.add(pending)
            with s.no_autoflush if mode == "context" else nullcontext():
                values = s.execute(
                    select(
                        InstantOrder.search_revision,
                        InstantOrder.matching_resume_revision,
                    ).where(InstantOrder.id == oid)
                ).one()
            assert values == ((4, 4) if mode == "autoflush" else (3, 3))
            _assert_raw(_observe(engine, oid), (3, 3), engine.dialect.name)
            s.flush()
            assert pending.id is not None
            assert (
                s.execute(
                    select(InstantOrder.from_text).where(InstantOrder.id == oid)
                ).scalar_one()
                == "dirty route"
            )
            s.rollback()
            _assert_raw(_observe(engine, oid), (3, 3), engine.dialect.name)
            assert _observe(engine, oid)[2] == "committed route"
            with engine.connect() as observer:
                assert (
                    observer.execute(
                        text('SELECT count(*) FROM "user" WHERE phone=:p'),
                        {"p": f"+7000000010{number}"},
                    ).scalar_one()
                    == 0
                )
        with Session(engine, expire_on_commit=True) as s:
            order = s.get(InstantOrder, oid)
            order.search_revision = 5
            order.matching_resume_revision = 5
            order.from_text = "committed new route"
            s.commit()
            assert set(FIELDS) | {"from_text"} <= inspect(order).expired_attributes
            _assert_raw(_observe(engine, oid), (5, 5), engine.dialect.name)
            assert order.search_revision == order.matching_resume_revision == 5
            assert order.from_text == "committed new route"
        report["identity_modes"].append(mode)


def _constructor(engine, report):
    from sqlalchemy import event, text
    from sqlalchemy.exc import StatementError
    from sqlmodel import Session
    from app.models import InstantOrder

    pid = _person(engine)
    report["invalid_observations"] = []
    for field in FIELDS:
        for value in (True, 1.5, 10**128):
            for method in ("constructor", "assignment"):
                order = InstantOrder(
                    passenger_id=pid,
                    **({field: value} if method == "constructor" else {}),
                )
                if method == "assignment":
                    setattr(order, field, value)
                observed = getattr(order, field)
                report["invalid_observations"].append(
                    {
                        "field": field,
                        "method": method,
                        "input_type": type(value).__name__,
                        "observed_type": type(observed).__name__,
                        "observed_repr": repr(observed),
                    }
                )
                # Source-derived expectation: current table construction uses
                # sqlmodel_table_construct, not validating/coercing model_validate.
                # Any different observation FAILS with the report retained.
                assert type(observed) is type(value) and observed == value
                with engine.connect() as c:
                    before = c.exec_driver_sql(
                        "SELECT count(*) FROM instantorder"
                    ).scalar_one()
                with Session(engine) as s:
                    s.add(order)
                    c = s.connection()
                    calls = []

                    def record(conn, cursor, statement, params, context, many):
                        calls.append(statement)

                    event.listen(c, "before_cursor_execute", record)
                    try:
                        with pytest.raises(StatementError) as error:
                            s.flush()
                        assert type(error.value.orig) is ValueError
                        assert str(error.value.orig) == "bounded exact integer required"
                        assert calls == []
                    finally:
                        event.remove(c, "before_cursor_execute", record)
                        s.rollback()
                with engine.connect() as c:
                    assert (
                        c.execute(
                            text("SELECT count(*) FROM instantorder")
                        ).scalar_one()
                        == before
                    )


def _guard_payload(engine, report):
    from sqlalchemy import select
    from fastapi import HTTPException
    from sqlmodel import Session
    from app.models import InstantOrder, User
    from app import instant_service as service

    pid = _person(engine)
    values = [None, 0, 2, 9, 10, 100, 2**63 - 2, 2**63 - 1, 2**63, 10**40 + 1]
    report["guard_truth"] = []
    with Session(engine) as s:
        orders = [
            InstantOrder(
                passenger_id=pid,
                search_revision=v,
                from_text="Synthetic A",
                to_text="Synthetic B",
            )
            for v in values
        ]
        s.add_all(orders)
        s.commit()
        ids = [o.id for o in orders]
        matches = set(
            s.execute(
                select(InstantOrder.id).where(
                    InstantOrder.id.in_(ids), service._can_increment_search_revision()
                )
            ).scalars()
        )
        for oid, value in zip(ids, values):
            observed = oid in matches
            python_expected = value is None or 0 <= value < 2**63 - 1
            report["guard_truth"].append(
                {"value": value, "observed": observed, "bigint_python": python_expected}
            )
        if engine.dialect.name == "postgresql":
            assert all(
                r["observed"] == r["bigint_python"] for r in report["guard_truth"]
            )
        else:
            # Exact diagnostic expectation from the actual expression's TEXT
            # coalesce/comparison semantics; not a numeric-domain protection.
            assert [r["observed"] for r in report["guard_truth"]] == [
                False,
                True,
                True,
                True,
                True,
                True,
                True,
                False,
                False,
                True,
            ]
        for oid in ids[-2:]:
            order = s.get(InstantOrder, oid)
            before = _observe(engine, oid)
            with pytest.raises(HTTPException) as error:
                service._bump_search_revision(s, order)
            assert error.value.status_code == 409
            s.rollback()
            assert _observe(engine, oid) == before
        order = s.get(InstantOrder, ids[1])
        person = s.get(User, pid)
        baseline = service.order_payload(s, order, person)
        for name in FIELDS:
            setattr(order, name, 10**40 + 1)
        s.flush()
        changed = service.order_payload(s, order, person)
        assert changed == baseline  # No broad volatile-field exclusion.
        assert all(name not in changed for name in FIELDS)
        assert json.loads(json.dumps(changed)) == changed
        dumped = order.model_dump()
        assert all(
            type(dumped[name]) is int and dumped[name] == 10**40 + 1 for name in FIELDS
        )
        report["public_payload_equal"] = True
        report["private_dump_large_counters"] = {name: dumped[name] for name in FIELDS}
        s.rollback()


def _child(case):
    """Closed scenario, exact own file guard and network deny before app imports."""
    root = _root()
    report_path = root / "report.json"
    assert not report_path.exists()
    report = {
        "case": case,
        "stage": "guard",
        "success": False,
        "child_pid": os.getpid(),
        "parent_pid": os.getppid(),
    }
    engine = None
    try:
        assert case in CASES and os.environ.get("YULDASH_ORM_CASE") == case
        assert not any(name == "app" or name.startswith("app.") for name in sys.modules)
        engine, destination = _guard_destination(root, case, report)
        report["guard"] = destination
        import test_taxi_actual_schema_pg_type_rehearsal as phase1

        if case == "prepare_pg":
            report["stage"] = "prepare_pg_existing_helpers"
            # Separate child: importing this helper's adapter must never touch
            # the parent or warm the later mapping child's expressions.
            import test_taxi_storage_check_postgres as storage

            before = phase1._read(engine)
            with engine.connect() as c:
                tx = phase1._begin(c)
                storage._install(c, before[0])
                tx.commit()
            _no_unexpected_network(report)
            report.update(
                stage="complete",
                success=True,
                preparation="existing validated CHECK helper; no app mapping patch",
            )
            report_path.write_text(json.dumps(report, sort_keys=True, default=str))
            return
        from decimal import Decimal
        from sqlalchemy import BigInteger, event, inspect, select, update
        from sqlalchemy.exc import StatementError
        from sqlmodel import SQLModel, Session
        from app.models import InstantOrder, User
        from app.taxi_generation_adapter_lab import ExactGeneration

        report["stage"] = "mapping_capture"
        table = InstantOrder.__table__
        before = {
            name: (id(col), id(col.type), type(col.type).__name__)
            for name, col in table.c.items()
        }
        # Do not inspect InstrumentedAttribute/comparator before replacement:
        # introspection itself can warm its memoized annotated expression.
        report["before"] = {name: list(before[name]) for name in FIELDS}
        report["before_comparator"] = "not accessed: avoid cache warming"
        assert all(isinstance(table.c[name].type, BigInteger) for name in FIELDS)
        assert "app.instant_service" not in sys.modules
        for name in FIELDS:
            table.c[name].type = ExactGeneration()
        assert all(id(col) == before[name][0] for name, col in table.c.items())
        assert all(
            id(col.type) == before[name][1]
            for name, col in table.c.items()
            if name not in FIELDS
        )

        report["stage"] = "mapper_expression_gate"
        mapper = inspect(InstantOrder)
        types = {}
        for name in FIELDS:
            attribute = getattr(InstantOrder, name)
            mapped_column = mapper.attrs[name].columns[0]
            comparator_expression = attribute.comparator.__clause_element__()
            expression = attribute.__clause_element__()
            types[name] = {
                "table": type(table.c[name].type).__name__,
                "mapper": type(mapped_column.type).__name__,
                "comparator": type(comparator_expression.type).__name__,
                "expression": type(expression.type).__name__,
            }
            report["mapping_types"] = types
            assert mapped_column is table.c[name]
            assert all(
                isinstance(value, ExactGeneration)
                for value in (
                    table.c[name].type,
                    mapped_column.type,
                    comparator_expression.type,
                    expression.type,
                )
            ), f"stale mapping type for {name}: {types[name]}"
            compiled = (
                update(InstantOrder)
                .values({name: 10**40 + 1})
                .returning(attribute)
                .compile(dialect=engine.dialect)
            )
            processor = compiled._bind_processors[name]
            physical = (
                Decimal(10**40 + 1)
                if engine.dialect.name == "postgresql"
                else str(10**40 + 1)
            )
            assert processor(10**40 + 1) == physical
            with pytest.raises(ValueError, match="^bounded exact integer required$"):
                processor(True)
            result_type = compiled._result_columns[0].type
            assert isinstance(result_type, ExactGeneration)
            result_processor = result_type.result_processor(engine.dialect, None)
            assert type(result_processor(physical)) is int
            assert result_processor(physical) == 10**40 + 1

        report["stage"] = "schema_policy"
        if engine.dialect.name == "sqlite":
            SQLModel.metadata.create_all(engine)
            with engine.connect() as c:
                assert (
                    Path(c.exec_driver_sql("PRAGMA database_list").all()[0][2])
                    == root / "fresh.sqlite"
                )
                columns = {
                    col["name"]: col for col in inspect(c).get_columns("instantorder")
                }
                assert all(str(columns[name]["type"]) == "TEXT" for name in FIELDS)
                assert inspect(c).get_check_constraints("instantorder") == []
            report["sqlite_new_storage_checks"] = []
        else:
            with engine.connect() as c:
                columns = {
                    col["name"]: col for col in inspect(c).get_columns("instantorder")
                }
                assert all(str(columns[name]["type"]) == "NUMERIC" for name in FIELDS)
                checks = c.exec_driver_sql(
                    "SELECT conname,convalidated FROM pg_constraint WHERE conrelid='instantorder'::regclass AND contype='c' ORDER BY conname"
                ).all()
                assert checks == [
                    ("ck_instant_resume_revision_domain", True),
                    ("ck_instant_search_revision_domain", True),
                ]
            report["pg_validated_checks"] = [list(r) for r in checks]
        if case != CASE:
            scenario = case.rsplit("_", 1)[0]
            report["stage"] = scenario
            {
                "matrix": _matrix,
                "identity": _identity,
                "constructor": _constructor,
                "guard_payload": _guard_payload,
            }[scenario](engine, report)
            _no_unexpected_network(report)
            report.update(stage="complete", success=True)
            report_path.write_text(json.dumps(report, sort_keys=True, default=str))
            return
        report["stage"] = "actual_orm_processor_gate"
        with Session(engine) as session:
            person = User(phone="+70000000002", name="Synthetic mapping fixture")
            session.add(person)
            session.flush()
            order = InstantOrder(passenger_id=person.id)
            session.add(order)
            session.flush()
            oid = order.id
            for name in FIELDS:
                setattr(order, name, 10**40 + 1)
            session.flush()
            session.commit()
            session.expire(order)
            session.refresh(order)
            assert all(
                type(getattr(order, name)) is int and getattr(order, name) == 10**40 + 1
                for name in FIELDS
            )
            for name in FIELDS:
                returned = session.execute(
                    update(InstantOrder)
                    .where(InstantOrder.id == oid)
                    .values({name: 10**40 + 2})
                    .returning(getattr(InstantOrder, name))
                ).scalar_one()
                assert type(returned) is int and returned == 10**40 + 2
            session.commit()
            session.refresh(order)
            with engine.connect() as observer:
                assert (
                    observer.connection.driver_connection
                    is not session.connection().connection.driver_connection
                )
                raw = observer.execute(
                    select(table.c.id).where(table.c.id == oid)
                ).scalar_one()
                assert raw == oid
                for name in FIELDS:
                    row = observer.exec_driver_sql(
                        f"SELECT {name},typeof({name}) FROM instantorder WHERE id={oid}"
                    ).one()
                    assert row == (str(10**40 + 2), "text")
            session.rollback()

        # Direct assignment retains bool before the actual ORM bind. Constructor
        # coercion is deliberately deferred to its separate diagnostic stage.
        for name in FIELDS:
            with Session(engine) as session:
                order = session.get(InstantOrder, oid)
                setattr(order, name, True)
                assert getattr(order, name) is True
                calls = []

                def record(conn, cursor, statement, params, context, many):
                    calls.append(statement)

                connection = session.connection()
                event.listen(connection, "before_cursor_execute", record)
                try:
                    with pytest.raises(StatementError) as error:
                        session.flush()
                    assert type(error.value.orig) is ValueError
                    assert str(error.value.orig) == "bounded exact integer required"
                    assert calls == []
                finally:
                    event.remove(connection, "before_cursor_execute", record)
                    session.rollback()
            with engine.connect() as observer:
                assert observer.exec_driver_sql(
                    f"SELECT {name} FROM instantorder WHERE id={oid}"
                ).scalar_one() == str(10**40 + 2)
        _no_unexpected_network(report)
        report["stage"] = "complete"
        report["success"] = True
    except Exception as error:
        report["error_type"] = type(error).__name__
        message = str(error).replace(
            os.environ.get("DATABASE_URL", ""), "<owned test DSN>"
        )
        if engine is not None and engine.url.password:
            message = message.replace(engine.url.password, "<redacted>")
        report["error"] = message
        report_path.write_text(json.dumps(report, sort_keys=True, default=str))
        print(
            f"candidate FAIL: stage={report['stage']} type={type(error).__name__}",
            file=sys.stderr,
        )
        raise SystemExit(1) from None
    finally:
        if engine is not None:
            engine.dispose()
    report_path.write_text(json.dumps(report, sort_keys=True, default=str))


def _launch(case, pg_engine=None):
    assert case in CASES
    assert not any(name == "app" or name.startswith("app.") for name in sys.modules)
    root = Path(tempfile.mkdtemp(prefix="yuldash-actual-orm.", dir="/tmp"))
    try:
        env = {
            k: v
            for k, v in os.environ.items()
            if not k.startswith("PG")
            and k.lower() not in {"http_proxy", "https_proxy", "all_proxy", "no_proxy"}
        }
        env.update(
            DATABASE_URL="sqlite:///" + str(root / "fresh.sqlite"),
            YULDASH_ORM_CASE_ROOT=str(root),
            YULDASH_ORM_CASE=case,
            PYTHONPATH=str(BACKEND),
            ENV="dev",
            SEED_DEMO="false",
            JWT_SECRET="synthetic-mapper-gate-secret",
        )
        if pg_engine is not None:
            # Engine comes only from phase1's currently registered fixture clone.
            assert re.fullmatch(
                r"yuldash_type_case_[0-9a-f]{32}", pg_engine.url.database
            )
            with pg_engine.connect() as c:
                phase1._guard(c, pg_engine.url.database)
            registry = {
                "database": pg_engine.url.database,
                "source": "yuldash_schema_init_fixture",
                "host": pg_engine.url.host,
                "port": pg_engine.url.port,
                "data_directory": os.environ["YULDASH_TEST_PG_DATA"],
            }
            (root / "registry.json").write_text(json.dumps(registry, sort_keys=True))
            env["DATABASE_URL"] = pg_engine.url.render_as_string(hide_password=False)
        with (
            (root / "stdout.log").open("w") as out,
            (root / "stderr.log").open("w") as err,
        ):
            try:
                result = subprocess.run(
                    [sys.executable, str(Path(__file__).resolve()), "--child", case],
                    cwd=BACKEND,
                    env=env,
                    stdout=out,
                    stderr=err,
                    timeout=90,
                    check=False,
                )
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
                err.write("Child timeout; killed and waited before cleanup.\n")
        (root / "exit").write_text(str(code))
        print(f"actual ORM artifacts {case}: {root}")
        report = (
            json.loads((root / "report.json").read_text())
            if (root / "report.json").exists()
            else {}
        )
        assert code == 0 and report.get("success"), (
            f"candidate failed stage={report.get('stage')} type={report.get('error_type')}; evidence={root}"
        )
        assert report["child_pid"] != os.getpid()
        assert report["parent_pid"] == os.getpid()
        assert report["network_boundary"]["scenario_attempts"] == []
        assert len(report["network_boundary"]["control_attempts"]) == 3
        assert not any(name == "app" or name.startswith("app.") for name in sys.modules)
        return report
    finally:
        for suffix in ("", "-wal", "-shm", "-journal"):
            (root / f"fresh.sqlite{suffix}").unlink(missing_ok=True)


def test_actual_mapper_candidate_minimal_sqlite_gate():
    assert _launch(CASE)["sqlite_new_storage_checks"] == []


@pytest.mark.parametrize("scenario", SCENARIOS)
def test_actual_orm_sqlite_scenarios(scenario):
    report = _launch(scenario + "_sqlite")
    assert report["sqlite_new_storage_checks"] == []


@pytest.mark.parametrize("source", ["init_db"], indirect=True)
@pytest.mark.parametrize("scenario", SCENARIOS)
def test_actual_orm_pg_init_db_scenarios(database, scenario):
    engine, source_name = database
    assert source_name == "init_db"
    assert not any(name == "app" or name.startswith("app.") for name in sys.modules)
    before = phase1._read(engine)
    with engine.connect() as c:
        tx = phase1._begin(c)
        phase1._upgrade(c, before[0])
        tx.commit()
    _launch("prepare_pg", engine)
    _launch(scenario + "_pg", engine)


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "--child" or sys.argv[2] not in CASES:
        raise SystemExit("Only fixed actual-ORM child scenarios are allowed")
    _child(sys.argv[2])
