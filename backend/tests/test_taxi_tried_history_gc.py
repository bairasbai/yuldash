"""Validation gates only; SQLite is never a substitute for GC row-lock evidence."""
import pytest
from sqlalchemy import create_engine, event
from sqlalchemy.pool import SingletonThreadPool, StaticPool

from app.taxi_tried_history_gc import collect_tried_history_batch


@pytest.mark.parametrize("batch", [True, False, 0, -1, 501, 1.5, "1", None])
def test_invalid_batch_rejected_before_sql(batch):
    engine = create_engine("postgresql+psycopg2://unused@127.0.0.1/never_connect")
    calls = []
    event.listen(engine, "do_connect", lambda *args: calls.append(args))
    try:
        with pytest.raises(ValueError):
            collect_tried_history_batch(engine, 1, batch)
        assert calls == []
    finally:
        engine.dispose()


@pytest.mark.parametrize("value", [None, object(), "postgresql://unused"])
def test_nonengine_rejected(value):
    with pytest.raises(TypeError):
        collect_tried_history_batch(value, 1)


@pytest.mark.parametrize("pool", [StaticPool, SingletonThreadPool])
def test_shared_pg_pool_rejected_before_connection(pool):
    engine = create_engine("postgresql+psycopg2://unused@127.0.0.1/never_connect", poolclass=pool)
    calls = []
    event.listen(engine, "do_connect", lambda *args: calls.append(args))
    try:
        with pytest.raises(ValueError):
            collect_tried_history_batch(engine, 1)
        assert calls == []
    finally:
        engine.dispose()


def test_wrong_dialect_and_connection_bind_rejected_without_sql(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'guard.db'}")
    calls = []
    event.listen(engine, "before_cursor_execute", lambda *args: calls.append(args))
    try:
        with pytest.raises(ValueError):
            collect_tried_history_batch(engine, 1)
        with engine.connect() as connection:
            with pytest.raises(TypeError):
                collect_tried_history_batch(connection, 1)
        assert calls == []
    finally:
        engine.dispose()


@pytest.mark.parametrize("delta,includes_current", [(-1, True), (0, True), (1, False)])
def test_exact_expiry_predicate_with_controlled_clock_unit(monkeypatch, delta, includes_current):
    """Pure SQL-construction boundary, NOT evidence of equality to real PG clock."""
    from contextlib import contextmanager
    from datetime import datetime, timedelta, timezone
    from types import SimpleNamespace
    from sqlalchemy.dialects import postgresql
    now = datetime(2030, 1, 2, 3, 4, 5, tzinfo=timezone.utc)
    statements = []
    class StubConnection:
        def execute(self, statement):
            statements.append(statement)
            if len(statements) == 1:
                return SimpleNamespace(first=lambda: (7, 1, "a" * 32, now.replace(tzinfo=None) + timedelta(microseconds=delta)))
            if len(statements) == 2:
                return SimpleNamespace(scalar_one=lambda: now)
            return SimpleNamespace(rowcount=0)
    @contextmanager
    def transaction():
        yield StubConnection()
    engine = create_engine("postgresql+psycopg2://unused@127.0.0.1/never_connect")
    monkeypatch.setattr(engine, "begin", transaction)
    try:
        assert collect_tried_history_batch(engine, 7, 2) == 0
        assert len(statements) == 3
        assert "FOR UPDATE" in str(statements[0])
        assert "clock_timestamp()" in str(statements[1])
        sql = str(statements[2].compile(dialect=postgresql.dialect(), compile_kwargs={"literal_binds": True}))
        assert ("lifecycle !=" not in sql) is includes_current
        assert "instanttriedexclusion.order_id = 7" in sql and "LIMIT 2" in sql
    finally:
        engine.dispose()
