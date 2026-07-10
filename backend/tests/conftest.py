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

from datetime import date  # noqa: E402

from fastapi.testclient import TestClient  # noqa: E402
from sqlmodel import Session  # noqa: E402

from app.main import app  # noqa: E402
from app.db import engine  # noqa: E402
from app.models import TaxiApplication, TaxiApplicationStatus, User, UserRole  # noqa: E402
from app.security import make_token  # noqa: E402


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
