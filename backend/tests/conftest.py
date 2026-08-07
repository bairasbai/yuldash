"""Тестовое окружение: изолированная SQLite (НЕ трогает прод), env=dev."""
import os
import pathlib
import tempfile

import pytest

os.environ["ENV"] = "dev"
# По умолчанию — изолированная SQLite. Если снаружи задан Postgres DATABASE_URL
# (CI-джоба для теста гонки брони `test_overbooking_concurrent`) — уважаем его, не перетираем.
if not os.environ.get("DATABASE_URL", "").startswith("postgres"):
    _DB = pathlib.Path(tempfile.gettempdir()) / "yuldash_test.db"
    if _DB.exists():
        _DB.unlink()
    os.environ["DATABASE_URL"] = f"sqlite:///{_DB.as_posix()}"
os.environ["SEED_DEMO"] = "false"
os.environ["JWT_SECRET"] = "test-secret-key-1234567890"
# Лимитер выключен для тестов: все /auth-хиты сессии делят один IP 'testclient'
# и иначе упёрлись бы в строгий бюджет. Тест лимита включает его локально.
os.environ["RATE_LIMIT_ENABLED"] = "false"
# Гейт такси (волна 2): в тестах такси ВКЛЮЧЕНО (иначе весь instant-стек отдаёт 403 «скоро»).
# Сам гейт (выключенный флаг/города) проверяется в test_taxi_gate.py через monkeypatch.
os.environ["TAXI_ENABLED"] = "true"
# Дневная сводка (B9b-3): middleware выключен, чтобы прогон после 21:00 местного не слал
# фоновую «сводку» посреди тестов. Сама логика проверяется в test_launch_extras.py напрямую.
os.environ["DAILY_DIGEST_ENABLED"] = "false"

from datetime import date  # noqa: E402
import sqlite3  # noqa: E402

from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import event  # noqa: E402
from sqlalchemy.engine import Engine  # noqa: E402
from sqlmodel import Session  # noqa: E402

from app.main import app  # noqa: E402
from app.db import engine  # noqa: E402
from app.models import TaxiApplication, TaxiApplicationStatus, User, UserRole  # noqa: E402
from app.security import make_token  # noqa: E402


# SQLite по умолчанию НЕ проверяет внешние ключи — это её историческая особенность, а не наша
# настройка. Из-за неё тест мог посеять бронь на несуществующего пассажира, пройти зелёным
# дома и упасть только в CI-джобе на Postgres (аудит 2026-08-06: так жили 12 тестов).
# Хуже самой поломки то, что зелёный локальный прогон переставал что-либо значить.
# Включаем проверку и здесь — пусть ловится там, где пишут код, а не через час в CI.
@event.listens_for(Engine, "connect")
def _sqlite_foreign_keys_on(dbapi_connection, _record):
    if isinstance(dbapi_connection, sqlite3.Connection):
        cur = dbapi_connection.cursor()
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as c:   # триггерит lifespan → init_db
        yield c


_uid_counter = {"n": 0}   # глобальный — уникальные юзеры через ВСЕ тесты (одна сессионная БД)


@pytest.fixture
def user_factory(client):
    def make(name="User", role=UserRole.passenger, taxi_approved: bool | None = None):
        """taxi_approved: None → водителю авто-одобряем заявку таксиста (существующие тесты
        такси-стека написаны про работающих таксистов); False → без заявки (для тестов гейта)."""
        _uid_counter["n"] += 1
        i = _uid_counter["n"]
        with Session(engine) as s:
            u = User(phone=f"tg-test-{i}", name=name, telegram_id=f"test{i}", verified=True, role=role)
            s.add(u)
            s.commit()
            s.refresh(u)
            if taxi_approved is None:
                taxi_approved = role == UserRole.driver
            if taxi_approved:
                s.add(TaxiApplication(
                    user_id=u.id, inn="123456789012", permit_number="Т-0001",
                    birth_date=date(1990, 1, 1), license_since_year=2010,
                    status=TaxiApplicationStatus.approved,
                ))
                s.commit()
            tok = make_token(u.id)
            return {"id": u.id, "token": tok, "auth": {"Authorization": f"Bearer {tok}"}}

    return make
