from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, inspect, text


def test_assessed_commission_migration_distinguishes_legacy_from_zero(monkeypatch, tmp_path):
    """Start with the previous schema, not SQLModel's already-updated create_all."""
    url = f"sqlite:///{tmp_path / 'orders.db'}"
    backend = Path(__file__).resolve().parents[1]
    cfg = Config(str(backend / "alembic.ini"))
    cfg.set_main_option("script_location", str(backend / "alembic"))
    monkeypatch.setenv("DATABASE_URL", url)
    engine = create_engine(url)
    before = "dc_taxi_checkout_scan"
    after = "dd_taxi_assessed_commission"
    with engine.begin() as conn:
        conn.execute(text("CREATE TABLE instantorder (id INTEGER PRIMARY KEY)"))
        conn.execute(text("INSERT INTO instantorder (id) VALUES (10), (20)"))
    command.stamp(cfg, before)
    command.upgrade(cfg, after)
    with engine.begin() as conn:
        conn.execute(text("UPDATE instantorder SET commission_assessed_kop = 0 WHERE id = 20"))
        assert conn.execute(text(
            "SELECT commission_assessed_kop FROM instantorder ORDER BY id",
        )).scalars().all() == [None, 0]
    command.downgrade(cfg, before)
    assert "commission_assessed_kop" not in {c["name"] for c in inspect(engine).get_columns("instantorder")}
    command.upgrade(cfg, after)
    with engine.connect() as conn:
        assert conn.execute(text(
            "SELECT commission_assessed_kop FROM instantorder ORDER BY id",
        )).scalars().all() == [None, None]
    engine.dispose()
