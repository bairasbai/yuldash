from contextlib import closing

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text
from sqlmodel import Session, SQLModel, create_engine

from app import db
from app.config import settings
from app.main import app
from app.models import User


@pytest.fixture
def legacy_sqlite(tmp_path, monkeypatch):
    url = f"sqlite:///{(tmp_path / 'legacy.db').as_posix()}"
    engine = create_engine(url, connect_args={"check_same_thread": False})
    monkeypatch.setattr(db, "engine", engine)
    monkeypatch.setattr(settings, "database_url", url)
    monkeypatch.setattr(settings, "sms_provider", "mock")
    SQLModel.metadata.create_all(engine)
    try:
        yield engine
    finally:
        engine.dispose()


def _sign_in(client, phone):
    response = client.post("/auth/request-code", json={"phone": phone})
    assert response.status_code == 200
    response = client.post("/auth/verify", json={"phone": phone, "code": response.json()["dev_code"]})
    assert response.status_code == 200
    return {"Authorization": f"Bearer {response.json()['access_token']}"}


@pytest.mark.parametrize("column_exists", [False, True])
def test_sms_login_on_legacy_sqlite(legacy_sqlite, column_exists):
    phone = "+79990001731"
    other_phone = "+79990001732"
    with Session(legacy_sqlite) as session:
        session.add(User(phone=phone, name="Passenger"))
        session.add(User(phone=other_phone, name="Other"))
        session.commit()

    with legacy_sqlite.begin() as connection:
        connection.execute(text('ALTER TABLE "user" DROP COLUMN auth_epoch'))
        if column_exists:
            connection.execute(text('ALTER TABLE "user" ADD COLUMN auth_epoch INTEGER'))
    if column_exists:
        with legacy_sqlite.begin() as connection:
            connection.execute(
                text('UPDATE "user" SET auth_epoch = 4 WHERE phone = :phone'), {"phone": other_phone}
            )

    db.init_db()
    with closing(TestClient(app)) as client:
        headers = _sign_in(client, phone)
        assert client.get("/me", headers=headers).status_code == 200
        assert client.post("/auth/logout", headers=headers).status_code == 200
        db._migrate_sqlite_add_columns()
        assert client.get("/me", headers=headers).status_code == 401
    with legacy_sqlite.connect() as connection:
        epochs = connection.execute(
            text('SELECT auth_epoch FROM "user" WHERE phone IN (:phone, :other) ORDER BY phone'),
            {"phone": phone, "other": other_phone},
        ).scalars().all()
    assert epochs == [0, 4 if column_exists else 0]


def test_auth_epoch_backfill_is_dev_only(legacy_sqlite, monkeypatch):
    with Session(legacy_sqlite) as session:
        session.add(User(phone="+79990001733"))
        session.commit()
    with legacy_sqlite.begin() as connection:
        connection.execute(text('ALTER TABLE "user" DROP COLUMN auth_epoch'))
        connection.execute(text('ALTER TABLE "user" ADD COLUMN auth_epoch INTEGER'))
    monkeypatch.setattr(settings, "env", "prod")

    db._migrate_sqlite_add_columns()

    with legacy_sqlite.connect() as connection:
        assert connection.execute(text('SELECT auth_epoch FROM "user"')).scalar_one() is None
