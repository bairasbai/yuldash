import pytest
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.operations import Operations
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine, inspect, text


def test_legacy_inbox_backfill_and_downgrade_guard():
    revision = ScriptDirectory.from_config(Config("alembic.ini")).get_revision("da_route_watch_dispatch").module
    engine = create_engine("sqlite://")
    with engine.begin() as db:
        statements = (
            "CREATE TABLE ride (id INTEGER PRIMARY KEY)",
            "CREATE TABLE routewatch (id INTEGER PRIMARY KEY, user_id INTEGER, watch_kind TEXT, expires_at DATETIME)",
            "CREATE TABLE notification (id INTEGER PRIMARY KEY, user_id INTEGER, type TEXT, ref_kind TEXT, "
            "ref_id INTEGER, created_at DATETIME)",
            "INSERT INTO ride VALUES (1), (2)",
            "INSERT INTO routewatch VALUES (1, 10, 'rides', '2999-01-01'), (2, 10, 'both', '2999-01-01'), "
            "(3, 10, 'requests', '2999-01-01'), (4, 10, 'rides', '2000-01-01'), (5, 20, 'rides', '2999-01-01')",
            "INSERT INTO notification VALUES (1, 10, 'route_watch', 'ride', 1, '2026-11-01'), "
            "(2, 10, 'route_watch', 'ride', 1, '2026-11-02'), "
            "(3, 10, 'route_watch', 'request', 2, '2026-11-02'), "
            "(4, 10, 'route_watch', 'ride', 99, '2026-11-02'), "
            "(5, 20, 'system', 'ride', 1, '2026-11-02')",
        )
        for statement in statements:
            db.execute(text(statement))

        with Operations.context(MigrationContext.configure(db)):
            revision.upgrade()
            revision.upgrade()
            existing = db.execute(text(
                "SELECT watch_id, ride_id, notification_id FROM routewatchnotice ORDER BY watch_id",
            )).all()
            assert existing == [(1, 1, 2), (2, 1, 2)]
            assert db.execute(text("SELECT COUNT(*) FROM notification")).scalar_one() == 5
            db.execute(text(
                "INSERT INTO routewatchevent (ride_id, terms_version, source_key, created_at, next_attempt_at) "
                "VALUES (1, 1, 'synthetic', '2026-11-02', '2026-11-02')",
            ))
            with pytest.raises(RuntimeError, match="events are pending"):
                revision.downgrade()
            db.execute(text("UPDATE routewatchevent SET finished_at = '2026-11-02'"))
            revision.downgrade()
            assert not inspect(db).has_table("routewatchevent")
            assert not inspect(db).has_table("routewatchnotice")
            revision.upgrade()
            assert db.execute(text("SELECT COUNT(*) FROM routewatchnotice")).scalar_one() == 2
    engine.dispose()
