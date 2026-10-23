"""Один SOS после потерянного ответа; реальный HTTP/SQL, внешняя доставка заменена."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

from fastapi import FastAPI
from fastapi.testclient import TestClient
import httpx
import pytest
from sqlalchemy.exc import OperationalError
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.middleware import RateLimitMiddleware
from app.models import SosEvent, TrustedContact
from app.routers import safety


KEY = "synthetic_sos_intent_001"
BODY = {"category": "other", "note": "Synthetic emergency", "lat": 54.1, "lng": 58.2}
PRIVATE_FIELDS = {"idempotency_key", "payload_hash", "handled_note", "handled_by", "handled_at", "escalated_at"}


@pytest.fixture
def emergency(user_factory, monkeypatch):
    user = user_factory("Synthetic SOS")
    calls = {"telegram": [], "sms": []}
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda text: calls["telegram"].append(text))
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: calls["sms"].append((phones, text)))
    monkeypatch.setattr(safety, "sms_will_reach", lambda phones: len(phones))
    monkeypatch.setattr(settings, "sos_sms_to_admin", False)
    with Session(engine) as session:
        session.add(TrustedContact(user_id=user["id"], name="Synthetic trusted contact", phone="synthetic-contact"))
        session.commit()
    return user, calls


def _headers(user, key=KEY):
    return {**user["auth"], **({"Idempotency-Key": key} if key is not None else {})}


def _rows(user):
    with Session(engine) as session:
        return session.exec(select(SosEvent).where(SosEvent.user_id == user["id"])).all()


class _LoseAcknowledgement(httpx.BaseTransport):
    def __init__(self, delegate):
        self.delegate = delegate
        self.lost = False
        self.committed_id = None

    def handle_request(self, request):
        response = self.delegate.handle_request(request)
        if request.method == "POST" and request.url.path == "/sos" and not self.lost and response.status_code == 200:
            response.read()
            self.committed_id = response.json()["id"]
            response.close()
            self.lost = True
            raise httpx.ReadError("Saved SOS acknowledgement lost", request=request)
        return response


def test_lost_ack_retry_returns_same_committed_event_and_one_original_fanout(client, emergency, monkeypatch):
    user, calls = emergency
    transport = _LoseAcknowledgement(client._transport)
    monkeypatch.setattr(client, "_transport", transport)
    with pytest.raises(httpx.ReadError, match="Saved SOS acknowledgement lost"):
        client.post("/sos", headers=_headers(user), json=BODY)
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1
    retry = client.post("/sos", headers=_headers(user), json=BODY)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] == transport.committed_id
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1
    assert not PRIVATE_FIELDS.intersection(retry.json())
    assert not {"contacts_notified", "contacts_total", "sms_suppressed"}.intersection(retry.json())
    assert retry.json()["hint_ru"] and retry.json()["hint_ba"]


def test_fresh_http_client_replay_reads_same_persisted_event(client, emergency):
    user, calls = emergency
    first = client.post("/sos", headers=_headers(user), json=BODY)
    assert first.status_code == 200, first.text
    from app.main import app
    # A new HTTP session has no first response, only the persisted owner/key/body.
    with TestClient(app) as restarted:
        retry = restarted.post("/sos", headers=_headers(user), json=BODY)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] == first.json()["id"]
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1


@pytest.mark.parametrize("key", ["a" * 16, "b" * 128])
def test_valid_key_length_boundaries_protect_retry(client, emergency, key):
    user, calls = emergency
    first = client.post("/sos", headers=_headers(user, key), json=BODY)
    retry = client.post("/sos", headers=_headers(user, key), json=BODY)
    assert first.status_code == retry.status_code == 200
    assert first.json()["id"] == retry.json()["id"]
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1


def test_failed_commit_does_not_claim_success_or_prevent_retry(client, emergency, monkeypatch):
    user, calls = emergency
    commit = Session.commit
    fail_once = [True]

    def fail_before_commit(session):
        if fail_once[0] and any(isinstance(row, SosEvent) and row.user_id == user["id"] for row in session.new):
            fail_once[0] = False
            raise OperationalError("Synthetic failed commit", {}, RuntimeError("Synthetic SQL outage"))
        return commit(session)

    monkeypatch.setattr(Session, "commit", fail_before_commit)
    with pytest.raises(OperationalError, match="Synthetic failed commit"):
        client.post("/sos", headers=_headers(user), json=BODY)
    assert _rows(user) == []
    assert calls == {"telegram": [], "sms": []}
    retry = client.post("/sos", headers=_headers(user), json=BODY)
    assert retry.status_code == 200, retry.text
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1


def test_corrupted_internal_fingerprint_never_acknowledges_another_body(client, emergency):
    user, calls = emergency
    first = client.post("/sos", headers=_headers(user), json=BODY)
    with Session(engine) as session:
        event = session.get(SosEvent, first.json()["id"])
        event.payload_hash = "0" * 64
        session.add(event)
        session.commit()
    retry = client.post("/sos", headers=_headers(user), json=BODY)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] != first.json()["id"]
    assert len(_rows(user)) == 2
    assert len(calls["telegram"]) == len(calls["sms"]) == 2


def test_same_raw_key_changed_body_is_new_signal_with_its_own_protected_retry(client, emergency):
    user, calls = emergency
    first = client.post("/sos", headers=_headers(user), json=BODY).json()
    changed = {**BODY, "note": "Changed emergency"}
    second = client.post("/sos", headers=_headers(user), json=changed)
    retry = client.post("/sos", headers=_headers(user), json=changed)
    assert second.status_code == retry.status_code == 200
    assert first["id"] != second.json()["id"] == retry.json()["id"]
    assert len(_rows(user)) == 2
    assert len(calls["telegram"]) == len(calls["sms"]) == 2


def test_same_raw_key_other_owner_does_not_reuse_another_person_event(client, emergency, user_factory):
    user, _ = emergency
    other = user_factory("Other SOS owner")
    first = client.post("/sos", headers=_headers(user), json=BODY).json()
    second = client.post("/sos", headers=_headers(other), json=BODY).json()
    retry = client.post("/sos", headers=_headers(other), json=BODY).json()
    assert first["id"] != second["id"] == retry["id"]
    assert retry["user_id"] == other["id"]
    assert len(_rows(user)) == len(_rows(other)) == 1


@pytest.mark.parametrize("key", [None, "", "short", "!" * 20, "a" * 129])
def test_absent_or_malformed_optional_key_preserves_legacy_emergency(client, emergency, key):
    user, calls = emergency
    replies = [client.post("/sos", headers=_headers(user, key), json=BODY) for _ in range(2)]
    assert all(reply.status_code == 200 for reply in replies)
    assert replies[0].json()["id"] != replies[1].json()["id"]
    assert all(not PRIVATE_FIELDS.intersection(reply.json()) for reply in replies)
    assert len(_rows(user)) == 2
    assert len(calls["telegram"]) == len(calls["sms"]) == 2


def test_normalized_missing_and_null_defaults_replay_same_event(client, emergency):
    user, _ = emergency
    first = client.post("/sos", headers=_headers(user), json={"note": "Synthetic emergency"})
    retry = client.post("/sos", headers=_headers(user), json={
        "category": "other", "note": "Synthetic emergency", "booking_id": None,
        "order_id": None, "lat": None, "lng": None})
    assert first.status_code == retry.status_code == 200
    assert first.json()["id"] == retry.json()["id"]


def test_invalid_booking_context_does_not_lose_emergency_or_its_retry(client, emergency):
    user, calls = emergency
    body = {**BODY, "booking_id": 2_000_000_000}
    first = client.post("/sos", headers=_headers(user), json=body)
    retry = client.post("/sos", headers=_headers(user), json=body)
    assert first.status_code == retry.status_code == 200
    assert first.json()["id"] == retry.json()["id"]
    assert first.json()["booking_id"] is None
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1


def test_replay_does_not_expose_later_admin_handling_note(client, emergency):
    user, _ = emergency
    first = client.post("/sos", headers=_headers(user), json=BODY)
    with Session(engine) as session:
        event = session.get(SosEvent, first.json()["id"])
        event.status = "handled"
        event.handled_note = "Private operational note"
        session.add(event)
        session.commit()
    retry = client.post("/sos", headers=_headers(user), json=BODY)
    assert retry.status_code == 200
    assert retry.json()["id"] == first.json()["id"]
    assert retry.json()["status"] == "handled"
    assert "Private operational note" not in retry.text
    assert not PRIVATE_FIELDS.intersection(retry.json())


def test_keyed_sos_keeps_rate_budget_and_can_replay_after_window(client, emergency, monkeypatch):
    user, calls = emergency
    monkeypatch.setattr(settings, "rate_limit_enabled", True)
    monkeypatch.setattr(settings, "rate_limit_sos_per_min", 1)
    monkeypatch.setattr(RateLimitMiddleware, "_get_redis", lambda self: None)
    original = RateLimitMiddleware._over_mem
    advance = [0]
    monkeypatch.setattr(RateLimitMiddleware, "_over_mem", lambda self, store, key, limit, now:
                        original(self, store, key, limit, now + advance[0]))
    local = FastAPI()
    local.include_router(safety.router)
    local.add_middleware(RateLimitMiddleware)
    with TestClient(local) as limited:
        first = limited.post("/sos", headers=_headers(user), json=BODY)
        blocked = limited.post("/sos", headers=_headers(user), json=BODY)
        new_key = limited.post("/sos", headers=_headers(user, "different_synthetic_key"), json=BODY)
        assert first.status_code == 200, first.text
        assert blocked.status_code == new_key.status_code == 429
        assert int(blocked.headers["Retry-After"]) > 0
        advance[0] = 61
        retry = limited.post("/sos", headers=_headers(user), json=BODY)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] == first.json()["id"]
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Real PostgreSQL unique-key writers required")
def test_concurrent_same_owner_key_body_commits_one_event_and_original_fanout(client, emergency, monkeypatch):
    user, calls = emergency
    barrier = Barrier(2)
    commit = Session.commit

    def both_commit(session):
        if any(isinstance(row, SosEvent) and row.user_id == user["id"] for row in session.new):
            barrier.wait(timeout=10)
        return commit(session)

    monkeypatch.setattr(Session, "commit", both_commit)
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(lambda _: client.post("/sos", headers=_headers(user), json=BODY), range(2)))
    assert [reply.status_code for reply in replies] == [200, 200]
    assert replies[0].json()["id"] == replies[1].json()["id"]
    assert len(_rows(user)) == 1
    assert len(calls["telegram"]) == len(calls["sms"]) == 1
