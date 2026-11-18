"""Input validation only: no SQLite imitation of transactional PostgreSQL history."""
import pytest
from sqlalchemy import create_engine, event
from sqlmodel import Session

from app.taxi_tried_history_write import remember_tried_history

ARGS = dict(order_id=1, expected_revision=0, expected_lifecycle="a" * 32,
            expected_status="searching", driver_id=2)


@pytest.mark.parametrize("value", [None, object(), "postgresql://unused"])
def test_nonconnection_rejected(value):
    with pytest.raises(TypeError):
        remember_tried_history(value, **ARGS)


def test_engine_and_session_rejected(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'inputs.db'}")
    try:
        with Session(engine) as session:
            for value in (engine, session):
                with pytest.raises(TypeError):
                    remember_tried_history(value, **ARGS)
    finally:
        engine.dispose()


@pytest.mark.parametrize("field,value", [
    ("order_id", True), ("order_id", 0), ("order_id", -1), ("order_id", "1"), ("order_id", 2**31),
    ("driver_id", False), ("driver_id", 0), ("driver_id", -1), ("driver_id", 1.5), ("driver_id", 2**31),
    ("expected_revision", True), ("expected_revision", -1), ("expected_revision", 2**63),
    ("expected_revision", None), ("expected_lifecycle", None), ("expected_lifecycle", ""),
    ("expected_lifecycle", "A"*32), ("expected_lifecycle", "g"*32), ("expected_lifecycle", "a"*31),
    ("expected_status", "done"), ("expected_status", None), ("expected_status", True),
])
def test_bad_inputs_rejected_before_any_sql(tmp_path, field, value):
    engine = create_engine(f"sqlite:///{tmp_path / 'bad-inputs.db'}")
    calls = []
    event.listen(engine, "before_cursor_execute", lambda *args: calls.append(args))
    try:
        with engine.connect() as connection, connection.begin():
            with pytest.raises(ValueError, match=field):
                remember_tried_history(connection, **(ARGS | {field: value}))
            assert calls == []
    finally:
        engine.dispose()


def test_non_pg_rejected_without_sql(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'dialect.db'}")
    calls = []
    event.listen(engine, "before_cursor_execute", lambda *args: calls.append(args))
    try:
        with engine.connect() as connection, connection.begin():
            with pytest.raises(ValueError):
                remember_tried_history(connection, **ARGS)
            assert calls == []
    finally:
        engine.dispose()


def test_unsupported_pg_driver_rejected_before_sql(tmp_path, monkeypatch):
    engine = create_engine(f"sqlite:///{tmp_path / 'driver.db'}")
    calls=[]
    with engine.connect() as connection, connection.begin():
        monkeypatch.setattr(connection.dialect,"name","postgresql")
        monkeypatch.setattr(connection.dialect,"driver","unsupported")
        event.listen(connection,"before_cursor_execute",lambda *args:calls.append(args))
        with pytest.raises(ValueError, match="psycopg2"):
            remember_tried_history(connection,**ARGS)
        assert calls == []
    engine.dispose()


@pytest.mark.parametrize("delta,resets", [(-1,True),(0,True),(1,False)])
def test_exact_expiry_reset_predicate_controlled_clock_unit(delta,resets):
    """Controlled Connection stub tests equality; not real PostgreSQL lock/clock evidence."""
    from contextlib import nullcontext
    from datetime import datetime,timedelta,timezone
    from types import SimpleNamespace
    from unittest.mock import MagicMock
    from sqlalchemy.engine import Connection
    now=datetime(2030,1,2,3,4,5,tzinfo=timezone.utc)
    connection=MagicMock(spec=Connection)
    connection.dialect=SimpleNamespace(name="postgresql",driver="psycopg2")
    connection.closed=False; connection.invalidated=False
    connection.get_transaction.return_value=SimpleNamespace(is_active=True)
    connection.connection=SimpleNamespace(driver_connection=SimpleNamespace(autocommit=False))
    connection.begin_nested.return_value=nullcontext()
    statements=[]
    def execute(statement):
        statements.append(statement)
        if len(statements)==1:
            return SimpleNamespace(first=lambda:("searching",0,"a"*32,1,now.replace(tzinfo=None)+timedelta(microseconds=delta),None))
        if len(statements)==2:return SimpleNamespace(scalar_one=lambda:now)
        return SimpleNamespace(rowcount=1)
    connection.execute.side_effect=execute
    assert remember_tried_history(connection,**ARGS) is None
    assert sum(str(s).startswith("DELETE") for s in statements)==int(resets)
    assert "FOR UPDATE" in str(statements[0]) and "clock_timestamp()" in str(statements[1])
    connection.commit.assert_not_called(); connection.rollback.assert_not_called()
