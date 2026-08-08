"""Тестовое окружение: изолированная SQLite (НЕ трогает прод), env=dev."""
import atexit
import base64
import os
import pathlib
import tempfile
import time

import pytest

os.environ["ENV"] = "dev"
# По умолчанию — изолированная SQLite. Если снаружи задан Postgres DATABASE_URL
# (CI-джоба для теста гонки брони `test_overbooking_concurrent`) — уважаем его, не перетираем.
#
# Файл СВОЙ У КАЖДОГО ПРОГОНА (в имени номер процесса). Раньше имя было общим, и два
# одновременных pytest (например «прогоняю весь набор в фоне» + «проверяю один файл»)
# сносили базу друг у друга: строка выше удаляет файл при импорте. Результат — 1151
# «упавший» тест с ошибкой «disk I/O error», хотя код в полном порядке (2026-08-06).
# Ложно-красный прогон опаснее отсутствующего: на него легко списать настоящую поломку —
# или, наоборот, час искать несуществующую.
_TMP = pathlib.Path(tempfile.gettempdir())
if not os.environ.get("DATABASE_URL", "").startswith("postgres"):
    _DB = _TMP / f"yuldash_test_{os.getpid()}.db"
    if _DB.exists():
        _DB.unlink()
    os.environ["DATABASE_URL"] = f"sqlite:///{_DB.as_posix()}"
    atexit.register(lambda: _DB.unlink(missing_ok=True))   # свой файл за собой убираем
    # Хвосты от прогонов, прибитых по Ctrl+C или таймауту: старше суток — мусор.
    for _old in _TMP.glob("yuldash_test_*.db"):
        try:
            if _old != _DB and time.time() - _old.stat().st_mtime > 86_400:
                _old.unlink()
        except OSError:
            pass
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
    def make(name="User", role=UserRole.passenger, taxi_approved: bool | None = None,
             gender: str = ""):
        """taxi_approved: None → водителю авто-одобряем заявку таксиста (существующие тесты
        такси-стека написаны про работающих таксистов); False → без заявки (для тестов гейта)."""
        _uid_counter["n"] += 1
        i = _uid_counter["n"]
        with Session(engine) as s:
            # gender: "" | female | male. Нужен тестам про «только женщины» — правило
            # проверяется у ОБЕИХ сторон (аудит 2026-08-08).
            u = User(phone=f"tg-test-{i}", name=name, telegram_id=f"test{i}", verified=True,
                     role=role, gender=gender)
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


# --- Настоящий загруженный документ (селфи курьера, фото прав и т.п.) ---
# Сервер принимает в заявку ТОЛЬКО ссылку на файл, который этот же человек загрузил через
# /upload/photo (`_ensure_owned_doc_url`). Раньше тесты слали выдуманную строку
# "secure/docs/s.jpg", и это скрывало настоящую дыру: селфи курьера принималось каким угодно
# адресом, а модерация грузила его с токеном админа в заголовке (аудит 2026-08-08).
# Хелпер загружает крошечный JPEG и возвращает честный URL — тесты идут тем же путём, что люди.
def upload_doc(client, auth) -> str:
    """Загрузить минимальный JPEG как приватный документ → его /secure/docs URL."""
    b64 = base64.b64encode(b"\xff\xd8\xfftest-jpeg").decode()
    r = client.post("/upload/photo", headers=auth, json={"photo_b64": b64, "ext": "jpg"})
    assert r.status_code == 200, r.text
    return r.json()["url"]

# --- Настоящее загруженное фото-доказательство (спор, фото «взял/отдал целой») ---
# Сервер принимает ТОЛЬКО снимок, который загрузил сам этот человек: имя файла начинается
# с его id (`guard_own_evidence`). Выдуманная строка вида "secure/evidence/pickup.jpg"
# скрывала настоящую дыру — чужое фото с лицами и травмами читалось посторонним
# (аудит 2026-08-08, волна 9). Хелпер ведёт тест тем же путём, что человека.
def upload_evidence(client, auth) -> str:
    """Загрузить минимальный JPEG как фото-доказательство → его /secure/evidence URL."""
    b64 = base64.b64encode(bytes([0xFF, 0xD8, 0xFF]) + b"test-evidence").decode()
    r = client.post("/upload/evidence", headers=auth, json={"photo_b64": b64, "ext": "jpg"})
    assert r.status_code == 200, r.text
    return r.json()["url"]
