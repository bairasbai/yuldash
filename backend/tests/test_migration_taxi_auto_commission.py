from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


def test_auto_commission_migration_does_not_enroll_old_orders(monkeypatch, tmp_path):
    url = f"sqlite:///{tmp_path / 'orders.db'}"
    backend = Path(__file__).resolve().parents[1]
    cfg = Config(str(backend / "alembic.ini"))
    cfg.set_main_option("script_location", str(backend / "alembic"))
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url)
    previous = "dd_taxi_assessed_commission"
    target = "de_taxi_auto_commission_retry"
    column = "auto_commission_retry_at"
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE instantorder (id INTEGER PRIMARY KEY, status TEXT)"))
        conn.execute(text("INSERT INTO instantorder (id, status) VALUES (10, 'done')"))
    command.stamp(cfg, previous)
    for _ in range(2):
        command.upgrade(cfg, target)
        with engine.connect() as conn:
            assert conn.execute(text("SELECT auto_commission_retry_at FROM instantorder")).scalar_one() is None
        assert any(i["column_names"] == [column] for i in inspect(engine).get_indexes("instantorder"))
        command.downgrade(cfg, previous)
        assert column not in {c["name"] for c in inspect(engine).get_columns("instantorder")}
    engine.dispose()
