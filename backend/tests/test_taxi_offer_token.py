"""Совместимый токен предложения: старые клиенты работают, новые отказы привязаны к офферу."""
import importlib.util
import re
from pathlib import Path
from datetime import timedelta

import pytest
import fakeredis
from sqlmodel import Session, select
from sqlalchemy import create_engine, inspect, text
from alembic.migration import MigrationContext
from alembic.operations import Operations

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, OfferDecline
from test_instant import _offered_order


@pytest.fixture
def fake_redis():
    redis = fakeredis.FakeStrictRedis(decode_responses=True)
    previous = isv._redis_override
    isv._redis_override = redis
    try:
        yield redis
    finally:
        isv._redis_override = previous


def test_offer_token_visible_only_to_current_driver(client, user_factory, fake_redis):
    driver, pax, order = _offered_order(client, user_factory, fake_redis, "TokenDriver", "TokenPax")
    oid = order["id"]
    body = client.get(f"/instant/orders/{oid}", headers=driver["auth"]).json()
    assert re.fullmatch(r"[0-9a-f]{32}", body["offer_token"])
    assert client.get(f"/instant/orders/{oid}", headers=pax["auth"]).json()["offer_token"] is None


def test_wrong_token_does_not_decline(client, user_factory, fake_redis):
    driver, _, order = _offered_order(client, user_factory, fake_redis, "WrongTokenDriver", "WrongTokenPax")
    oid = order["id"]
    response = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"],
                           json={"expected_offer_token": "0" * 32, "reason": "far"})
    assert response.status_code == 200
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).status == S.offered


@pytest.mark.parametrize("body", [None, {}, {"expected_offer_token": None}])
@pytest.mark.parametrize("legacy_row", [False, True])
def test_legacy_decline_remains_compatible(client, user_factory, fake_redis, body, legacy_row):
    driver, _, order = _offered_order(client, user_factory, fake_redis, "LegacyTokenDriver", "LegacyTokenPax")
    oid = order["id"]
    if legacy_row:
        with Session(engine) as session:
            current = session.get(InstantOrder, oid)
            current.offer_token = None
            session.add(current)
            session.commit()
    response = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"], **({} if body is None else {"json": body}))
    assert response.status_code == 200
    assert response.json()["status"] != "offered"
    assert response.json()["offer_token"] is None


@pytest.mark.parametrize("token", ["", "a" * 31, "a" * 33, "A" * 32, "g" * 32, " " * 32, 123, True])
def test_invalid_token_rejected(client, user_factory, fake_redis, token):
    driver, _, order = _offered_order(client, user_factory, fake_redis, "InvalidTokenDriver", "InvalidTokenPax")
    response = client.post(f"/instant/orders/{order['id']}/decline", headers=driver["auth"], json={"expected_offer_token": token})
    assert response.status_code == 422
    with Session(engine) as session:
        assert session.get(InstantOrder, order["id"]).status == S.offered


def test_token_rotates_for_same_driver_and_delayed_request_is_noop(client, user_factory, fake_redis, monkeypatch):
    driver, _, order = _offered_order(client, user_factory, fake_redis, "RotateTokenDriver", "RotateTokenPax")
    oid = order["id"]
    with Session(engine) as session:
        current = session.get(InstantOrder, oid)
        old_token = current.offer_token
        fixed = current.offered_at
        monkeypatch.setattr(isv, "utcnow", lambda: fixed)
        monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
        monkeypatch.setattr(isv, "_push_offer", lambda *args: None)
        new = isv.advance_after_no_accept(session, current)
        new_token = new.offer_token
        assert new.offered_at == fixed and new_token != old_token
        # Продление срока — то же предложение, token не меняется.
        new.offer_expires_at += timedelta(minutes=1)
        session.add(new)
        session.commit()
        assert session.get(InstantOrder, oid).offer_token == new_token
    old_response = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"],
                               json={"expected_offer_token": old_token, "reason": "far"})
    assert old_response.status_code == 200 and old_response.json()["status"] == "offered"
    with Session(engine) as session:
        assert session.get(InstantOrder, oid).offer_token == new_token
        assert session.exec(select(OfferDecline).where(OfferDecline.order_id == oid)).all() == []
    # Текущий token действует; оставляем обычное поведение matcher без кандидатов.
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [])
    response = client.post(f"/instant/orders/{oid}/decline", headers=driver["auth"], json={"expected_offer_token": new_token})
    assert response.status_code == 200 and response.json()["status"] == "expired"
    assert response.json()["offer_token"] is None


@pytest.mark.parametrize("analytics", ["none", "commit", "rollback"])
def test_token_cas_survives_race_and_analytics(client, user_factory, fake_redis, monkeypatch, analytics):
    driver, _, order = _offered_order(client, user_factory, fake_redis, "RaceTokenDriver", "RaceTokenPax")
    oid = order["id"]
    with Session(engine) as session:
        current = session.get(InstantOrder, oid)
        old = current.offer_token
        replacement = "f" * 32 if old != "f" * 32 else "e" * 32
        execute = session.execute
        def change_token_before_update(statement, *args, **kwargs):
            if getattr(statement, "is_update", False):
                monkeypatch.setattr(session, "execute", execute)
                with Session(engine) as other:
                    latest = other.get(InstantOrder, oid)
                    latest.offer_token = replacement  # тот же получатель и timestamp
                    other.add(latest)
                    other.commit()
            return execute(statement, *args, **kwargs)
        monkeypatch.setattr(session, "execute", change_token_before_update)
        if analytics == "rollback":
            commit = session.commit
            def fail_analytics():
                monkeypatch.setattr(session, "commit", commit)
                current.passenger_id = None
                session.add(current)
                session.flush()  # настоящий failed transaction
            monkeypatch.setattr(session, "commit", fail_analytics)
        monkeypatch.setattr(isv, "try_offer_next", lambda *args, **kwargs: pytest.fail("stale token advanced"))
        result = isv.decline_offer(session, oid, driver["id"], reason="far" if analytics != "none" else "", expected_offer_token=old)
        assert result.status == S.offered and result.offer_token == replacement


def test_token_not_authorization_and_not_exposed_to_previous_actor(client, user_factory, fake_redis):
    from app.models import User, UserRole
    driver, _, order = _offered_order(client, user_factory, fake_redis, "AuthTokenDriver", "AuthTokenPax")
    outsider = user_factory("TokenOutsider", role=UserRole.driver)
    oid = order["id"]
    with Session(engine) as session:
        current = session.get(InstantOrder, oid)
        token = current.offer_token
        viewer = session.get(User, outsider["id"])
        assert isv.order_payload(session, current, viewer, actor_authorized=True)["offer_token"] is None
    response = client.post(f"/instant/orders/{oid}/decline", headers=outsider["auth"], json={"expected_offer_token": token})
    assert response.status_code == 403


def test_token_migration_preserves_rows_and_is_idempotent(tmp_path):
    path = Path(__file__).parents[1] / "alembic/versions/ck_instant_offer_token.py"
    spec = importlib.util.spec_from_file_location("token_migration", path)
    migration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(migration)
    db = create_engine(f"sqlite:///{tmp_path / 'old-token.db'}")
    try:
        with db.begin() as connection:
            connection.execute(text("CREATE TABLE instantorder (id INTEGER PRIMARY KEY, status VARCHAR)"))
            connection.execute(text("INSERT INTO instantorder VALUES (1, 'offered')"))
            migration.op = Operations(MigrationContext.configure(connection))
            migration.upgrade()
            migration.upgrade()
            assert connection.execute(text("SELECT status,offer_token FROM instantorder")).one() == ("offered", None)
            migration.downgrade()
            assert "offer_token" not in {c["name"] for c in inspect(connection).get_columns("instantorder")}
            assert connection.execute(text("SELECT status FROM instantorder")).scalar_one() == "offered"
    finally:
        db.dispose()


@pytest.mark.parametrize("entrypoint", ["initial", "retry", "next", "scheduled", "reassign", "lazy"])
def test_all_offer_writer_entrypoints_rotate_before_notify(client, user_factory, fake_redis, monkeypatch, entrypoint):
    """Каждый сервисный путь выдаёт новый token и сохраняет его до уведомления."""
    from app.timeutil import utcnow
    driver, _, payload = _offered_order(client, user_factory, fake_redis, "WriterTokenDriver", "WriterTokenPax")
    oid = payload["id"]
    calls = []
    monkeypatch.setattr(isv, "candidates", lambda *args, **kwargs: [driver["id"]])
    monkeypatch.setattr(isv, "_notify_reassign", lambda *args: None)
    def observe_committed_token(session, order, recipient):
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, oid)
            assert saved.status == S.offered
            assert saved.current_offer_driver_id == recipient == driver["id"]
            assert saved.offer_token == order.offer_token
            calls.append(saved.offer_token)
    monkeypatch.setattr(isv, "_push_offer", observe_committed_token)
    with Session(engine) as session:
        order = session.get(InstantOrder, oid)
        previous_token = order.offer_token
        if entrypoint in ("initial", "retry", "scheduled", "reassign"):
            order.status = {"initial": S.created, "retry": S.expired,
                            "scheduled": S.scheduled, "reassign": S.cancelled}[entrypoint]
            order.current_offer_driver_id = None
            order.offer_expires_at = None
        if entrypoint == "scheduled":
            order.scheduled_at = utcnow() - timedelta(minutes=1)
        if entrypoint == "lazy":
            order.offer_expires_at = utcnow() - timedelta(minutes=1)
        session.add(order)
        session.commit()
        if entrypoint in ("initial", "retry"):
            result = isv.start_matching(session, order, notify=False)
        elif entrypoint == "next":
            result = isv.advance_after_no_accept(session, order, notify=False)
        elif entrypoint == "scheduled":
            result = isv.activate_scheduled(session, order)
        elif entrypoint == "reassign":
            result = isv._reassign_after_driver_cancel(session, oid, None)
        else:
            result = isv.reconcile_offer(session, order)
        assert result.status == S.offered
        assert re.fullmatch(r"[0-9a-f]{32}", result.offer_token)
        assert result.offer_token != previous_token
        assert calls == [result.offer_token]
