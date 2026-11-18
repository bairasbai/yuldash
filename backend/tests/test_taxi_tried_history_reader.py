"""Input validation only: no SQLite imitation of transactional PostgreSQL history."""
import pytest
from sqlalchemy import create_engine, event
from sqlmodel import Session

from app.taxi_tried_history_reader import inspect_tried_candidate

ARGS = dict(order_id=1, expected_revision=0, expected_lifecycle="a" * 32,
            driver_id=2)


@pytest.mark.parametrize("value", [None, object(), "postgresql://unused"])
def test_nonconnection_rejected(value):
    with pytest.raises(TypeError):
        inspect_tried_candidate(value, **ARGS)


def test_engine_and_session_rejected(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'inputs.db'}")
    try:
        with Session(engine) as session:
            for value in (engine, session):
                with pytest.raises(TypeError):
                    inspect_tried_candidate(value, **ARGS)
    finally:
        engine.dispose()


@pytest.mark.parametrize("field,value", [
    ("order_id", True), ("order_id", 0), ("order_id", -1), ("order_id", "1"), ("order_id", 2**31),
    ("driver_id", False), ("driver_id", 0), ("driver_id", -1), ("driver_id", 1.5), ("driver_id", 2**31),
    ("expected_revision", True), ("expected_revision", -1), ("expected_revision", 2**63),
    ("expected_revision", None), ("expected_lifecycle", None), ("expected_lifecycle", ""),
    ("expected_lifecycle", "A"*32), ("expected_lifecycle", "g"*32), ("expected_lifecycle", "a"*31),
])
def test_bad_inputs_rejected_before_any_sql(tmp_path, field, value):
    engine = create_engine(f"sqlite:///{tmp_path / 'bad-inputs.db'}")
    calls = []
    event.listen(engine, "before_cursor_execute", lambda *args: calls.append(args))
    try:
        with engine.connect() as connection, connection.begin():
            with pytest.raises(ValueError, match=field):
                inspect_tried_candidate(connection, **(ARGS | {field: value}))
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
                inspect_tried_candidate(connection, **ARGS)
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
            inspect_tried_candidate(connection,**ARGS)
        assert calls == []
    engine.dispose()




@pytest.mark.parametrize("delta,excluded",[(-1,False),(0,False),(1,True)])
def test_exact_expiry_boundary_controlled_clock_unit(delta,excluded):
    """Controlled stub only; real PG lock and wall-clock proofs are separate."""
    from contextlib import nullcontext
    from datetime import datetime,timedelta,timezone
    from types import SimpleNamespace
    from unittest.mock import MagicMock
    from dataclasses import FrozenInstanceError
    from sqlalchemy.engine import Connection
    now=datetime(2030,1,2,3,4,5,tzinfo=timezone.utc)
    c=MagicMock(spec=Connection)
    c.dialect=SimpleNamespace(name="postgresql",driver="psycopg2")
    c.closed=False;c.invalidated=False
    c.get_transaction.return_value=SimpleNamespace(is_active=True)
    c.connection=SimpleNamespace(driver_connection=SimpleNamespace(autocommit=False))
    c.begin_nested.return_value=nullcontext()
    statements=[]
    def execute(statement):
        statements.append(statement)
        if len(statements)==1:
            return SimpleNamespace(first=lambda:("searching",0,"a"*32,1,now.replace(tzinfo=None)+timedelta(microseconds=delta)))
        if len(statements)==2:return SimpleNamespace(scalar_one=lambda:now)
        return SimpleNamespace(scalar_one=lambda:True)
    c.execute.side_effect=execute
    result=inspect_tried_candidate(c,**ARGS)
    assert result.excluded is excluded and result.observed_at==now.replace(tzinfo=None)
    assert len(statements)==(3 if excluded else 2)
    if excluded:assert "EXISTS" in str(statements[-1]).upper()
    with pytest.raises(FrozenInstanceError):result.excluded=False
    c.commit.assert_not_called();c.rollback.assert_not_called()
