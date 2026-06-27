"""Тестовое окружение: изолированная SQLite (НЕ трогает прод), env=dev."""
import os
import pathlib
import tempfile

import pytest

_DB = pathlib.Path(tempfile.gettempdir()) / "yuldash_test.db"
if _DB.exists():
    _DB.unlink()
os.environ["ENV"] = "dev"
os.environ["DATABASE_URL"] = f"sqlite:///{_DB.as_posix()}"
os.environ["SEED_DEMO"] = "false"
os.environ["JWT_SECRET"] = "test-secret-key-1234567890"

from fastapi.testclient import TestClient  # noqa: E402
from sqlmodel import Session  # noqa: E402

from app.main import app  # noqa: E402
from app.db import engine  # noqa: E402
from app.models import User, UserRole  # noqa: E402
from app.security import make_token  # noqa: E402


@pytest.fixture(scope="session")
def client():
    with TestClient(app) as c:   # триггерит lifespan → init_db
        yield c


_uid_counter = {"n": 0}   # глобальный — уникальные юзеры через ВСЕ тесты (одна сессионная БД)


@pytest.fixture
def user_factory(client):
    def make(name="User", role=UserRole.passenger):
        _uid_counter["n"] += 1
        i = _uid_counter["n"]
        with Session(engine) as s:
            u = User(phone=f"tg-test-{i}", name=name, telegram_id=f"test{i}", verified=True, role=role)
            s.add(u)
            s.commit()
            s.refresh(u)
            tok = make_token(u.id)
            return {"id": u.id, "token": tok, "auth": {"Authorization": f"Bearer {tok}"}}

    return make
