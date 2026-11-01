# -*- coding: utf-8 -*-
"""RS-02 А/Б: миграции мест по участкам (cc) и цен остановок (cd) — туда-обратно.

Старые данные не трогаем: колонки появляются с дефолтами; повтор — без изменений;
откат cd снимает цены, откат cc пересчитывает seats_left активных из живых броней.
(Образец — test_pickup_stop_booking.py::test_migration_adds_an_empty_column_and_back.)
"""
import importlib.util
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy import create_engine, inspect, text


def _mig(name: str):
    path = Path(__file__).resolve().parents[1] / "alembic" / "versions" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(f"{name}_mod", path)
    mig = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mig)
    return mig


def test_segment_migrations_roundtrip(tmp_path):
    cc = _mig("cc_ride_segment_seats_20261031")
    cd = _mig("cd_ride_stop_prices_20261031")
    assert cc.down_revision == "cb_sos_retry_intent_20261021"
    assert cd.down_revision == "cc_ride_segment_seats_20261031"

    eng = create_engine(f"sqlite:///{(tmp_path / 'old.db').as_posix()}")
    with eng.begin() as c:
        # Старая схема (до RS-02) + синтетические строки: активный рейс 4 места с НЕВЕРНЫМ
        # счётчиком 9 и двумя живыми бронями; завершённый рейс — для контроля «не тронули».
        c.execute(text("CREATE TABLE ride (id INTEGER PRIMARY KEY, driver_id INTEGER, status VARCHAR,"
                       " seats_total INTEGER, seats_left INTEGER, price INTEGER)"))
        c.execute(text("CREATE TABLE booking (id INTEGER PRIMARY KEY, ride_id INTEGER, passenger_id INTEGER,"
                       " seats INTEGER, status VARCHAR)"))
        c.execute(text("INSERT INTO ride VALUES (1, 1, 'active', 4, 9, 500), (2, 1, 'done', 2, 1, 300)"))
        c.execute(text("INSERT INTO booking VALUES (1, 1, 1, 2, 'confirmed'), (2, 1, 1, 1, 'cancelled')"))

        with Operations.context(MigrationContext.configure(c)):
            cc.upgrade()
            cc.upgrade()   # идемпотентно
            cd.upgrade()
            cd.upgrade()
        row = c.execute(text("SELECT seats_left, terms_version, leg_free, seats_free_max, stop_prices_json"
                             " FROM ride WHERE id=1")).fetchone()
        assert row == (9, 0, "", 0, ""), f"старые данные тронуты: {row}"

        with Operations.context(MigrationContext.configure(c)):
            cd.downgrade()
            cc.downgrade()
        cols = {x["name"] for x in inspect(c).get_columns("ride")}
        assert not ({"terms_version", "leg_free", "seats_free_max", "stop_prices_json"} & cols), cols
        # Откат cc пересчитал seats_left активного рейса из живых броней: 4 − 2 = 2 (не 9).
        assert c.execute(text("SELECT seats_left FROM ride WHERE id=1")).scalar_one() == 2
        # done-рейс не тронули.
        assert c.execute(text("SELECT seats_left FROM ride WHERE id=2")).scalar_one() == 1

        with Operations.context(MigrationContext.configure(c)):
            cc.upgrade()
            cd.upgrade()   # повторный upgrade после downgrade
        assert c.execute(text("SELECT terms_version FROM ride WHERE id=1")).scalar_one() == 0
