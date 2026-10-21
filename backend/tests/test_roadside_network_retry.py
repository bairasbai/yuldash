"""Попутка: один сигнал «застрял» после commit и потери HTTP-ответа."""
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import httpx
import pytest
from sqlalchemy.exc import OperationalError
from sqlmodel import Session, select

from app.db import engine
from app.models import SosEvent, TrustedContact
from app.routers import safety
from test_flows import _trip


KEY = "synthetic_roadside_intent_001"
BODY = {"lat": 54.1, "lng": 58.2, "note": "Synthetic roadside help"}


@pytest.fixture
def roadside(client, user_factory, monkeypatch):
    driver, passenger, _, booking = _trip(client, user_factory)
    calls = {"telegram": [], "sms": []}
    monkeypatch.setattr(safety, "notify_admin_telegram", lambda text: calls["telegram"].append(text))
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: calls["sms"].append((phones, text)))
    monkeypatch.setattr(safety, "sms_will_reach", lambda phones: len(phones))
    with Session(engine) as session:
        session.add(TrustedContact(user_id=passenger["id"], name="Synthetic contact", phone="synthetic-contact"))
        session.commit()
    return {"driver": driver, "passenger": passenger, "booking_id": booking["id"], "calls": calls}


def _headers(user, key=KEY):
    return {**user["auth"], **({"Idempotency-Key": key} if key is not None else {})}


def _rows(user):
    with Session(engine) as session:
        return session.exec(select(SosEvent).where(SosEvent.user_id == user["id"])).all()


def _post(client, case, body=None, key=KEY, user=None, booking_id=None):
    return client.post(f"/bookings/{booking_id if booking_id is not None else case['booking_id']}/stuck",
                       headers=_headers(user or case["passenger"], key), json=BODY if body is None else body)


class _LoseAcknowledgement(httpx.BaseTransport):
    def __init__(self, delegate, path):
        self.delegate, self.path = delegate, path
        self.lost = False
        self.committed_id = None

    def handle_request(self, request):
        response = self.delegate.handle_request(request)
        if request.method == "POST" and request.url.path == self.path and not self.lost and response.status_code == 200:
            response.read()
            self.committed_id = response.json()["id"]
            response.close()
            self.lost = True
            raise httpx.ReadError("Saved roadside acknowledgement lost", request=request)
        return response


def test_lost_commit_ack_manual_retry_returns_same_event_without_another_fanout(client, roadside, monkeypatch):
    case = roadside
    transport = _LoseAcknowledgement(client._transport, f"/bookings/{case['booking_id']}/stuck")
    monkeypatch.setattr(client, "_transport", transport)
    with pytest.raises(httpx.ReadError, match="Saved roadside acknowledgement lost"):
        _post(client, case)
    assert len(_rows(case["passenger"])) == 1
    assert len(case["calls"]["telegram"]) == len(case["calls"]["sms"]) == 1
    retry = _post(client, case)
    assert retry.status_code == 200, retry.text
    body = retry.json()
    assert body["id"] == transport.committed_id
    assert body["delivery_unconfirmed"] is True
    assert body["hint_ru"] and body["hint_ba"]
    assert not {"contacts_notified", "contacts_total", "idempotency_key", "payload_hash", "handled_note"}.intersection(body)
    assert len(_rows(case["passenger"])) == 1
    assert len(case["calls"]["telegram"]) == len(case["calls"]["sms"]) == 1


def test_first_response_keeps_existing_contact_delivery_contract(client, roadside):
    response = _post(client, roadside)
    assert response.status_code == 200, response.text
    assert response.json()["contacts_notified"] == response.json()["contacts_total"] == 1
    assert response.json().get("delivery_unconfirmed") is not True


@pytest.mark.parametrize("key", [None, "short", "!" * 20, "x" * 129])
def test_unkeyed_or_malformed_key_remains_legacy_new_emergency(client, roadside, key):
    first, second = [_post(client, roadside, key=key) for _ in range(2)]
    assert first.status_code == second.status_code == 200
    assert first.json()["id"] != second.json()["id"]
    assert len(_rows(roadside["passenger"])) == 2
    assert len(roadside["calls"]["telegram"]) == len(roadside["calls"]["sms"]) == 2


@pytest.mark.parametrize("changed", [{"note": "Changed emergency"}, {"lat": 54.101}])
def test_changed_body_creates_new_protected_signal(client, roadside, changed):
    first = _post(client, roadside)
    second = _post(client, roadside, body={**BODY, **changed})
    retry = _post(client, roadside, body={**BODY, **changed})
    assert first.status_code == second.status_code == retry.status_code == 200
    assert first.json()["id"] != second.json()["id"] == retry.json()["id"]
    assert len(_rows(roadside["passenger"])) == 2
    assert len(roadside["calls"]["telegram"]) == len(roadside["calls"]["sms"]) == 2


def test_another_owner_does_not_reuse_event_even_with_same_booking_key_body(client, roadside):
    first = _post(client, roadside)
    second = _post(client, roadside, user=roadside["driver"])
    retry = _post(client, roadside, user=roadside["driver"])
    assert first.status_code == second.status_code == retry.status_code == 200
    assert first.json()["id"] != second.json()["id"] == retry.json()["id"]
    assert retry.json()["user_id"] == roadside["driver"]["id"]
    assert len(_rows(roadside["passenger"])) == len(_rows(roadside["driver"])) == 1


def test_original_invalid_booking_participates_in_fingerprint_before_diagnostic_note(client, roadside):
    first = _post(client, roadside, booking_id=2_000_000_001)
    retry = _post(client, roadside, booking_id=2_000_000_001)
    another = _post(client, roadside, booking_id=2_000_000_002)
    another_retry = _post(client, roadside, booking_id=2_000_000_002)
    assert all(reply.status_code == 200 for reply in (first, retry, another, another_retry))
    assert first.json()["id"] == retry.json()["id"] != another.json()["id"] == another_retry.json()["id"]
    assert first.json()["booking_id"] is retry.json()["booking_id"] is None
    assert "бронь #2000000001 не подошла" in retry.json()["note"]
    assert len(_rows(roadside["passenger"])) == 2
    assert len(roadside["calls"]["telegram"]) == len(roadside["calls"]["sms"]) == 2


def test_foreign_booking_context_does_not_block_signal_or_reveal_route(client, roadside, user_factory):
    stranger = user_factory("Synthetic stranger")
    first = _post(client, roadside, user=stranger)
    retry = _post(client, roadside, user=stranger)
    assert first.status_code == retry.status_code == 200
    assert first.json()["id"] == retry.json()["id"]
    assert retry.json()["booking_id"] is None
    assert "Сибай" not in retry.text and "Баймак" not in retry.text
    assert len(_rows(stranger)) == 1


def test_replay_does_not_need_to_reload_booking_context(client, roadside, monkeypatch):
    first = _post(client, roadside)
    assert first.status_code == 200, first.text

    def should_not_load_context(*args, **kwargs):
        raise AssertionError("A saved replay must not need booking context again")

    monkeypatch.setattr(safety, "booking_and_ride_for_user", should_not_load_context)
    retry = _post(client, roadside)
    assert retry.status_code == 200, retry.text
    assert retry.json()["id"] == first.json()["id"]


def test_sos_and_roadside_with_same_raw_key_do_not_ack_each_other(client, roadside):
    first = client.post("/sos", headers=_headers(roadside["passenger"]), json={
        **BODY, "category": "breakdown", "booking_id": roadside["booking_id"]})
    stuck = _post(client, roadside)
    retry_sos = client.post("/sos", headers=_headers(roadside["passenger"]), json={
        **BODY, "category": "breakdown", "booking_id": roadside["booking_id"]})
    retry_stuck = _post(client, roadside)
    assert all(response.status_code == 200 for response in (first, stuck, retry_sos, retry_stuck))
    assert first.json()["id"] == retry_sos.json()["id"] != stuck.json()["id"] == retry_stuck.json()["id"]
    assert len(_rows(roadside["passenger"])) == 2


def test_commit_failure_never_claims_ack_and_does_not_prevent_retry(client, roadside, monkeypatch):
    commit = Session.commit
    fail_once = [True]

    def fail_before_commit(session):
        if fail_once[0] and any(isinstance(row, SosEvent) and row.user_id == roadside["passenger"]["id"] for row in session.new):
            fail_once[0] = False
            raise OperationalError("Synthetic failed commit", {}, RuntimeError("Synthetic SQL outage"))
        return commit(session)

    monkeypatch.setattr(Session, "commit", fail_before_commit)
    with pytest.raises(OperationalError, match="Synthetic failed commit"):
        _post(client, roadside)
    assert _rows(roadside["passenger"]) == []
    assert roadside["calls"] == {"telegram": [], "sms": []}
    retry = _post(client, roadside)
    assert retry.status_code == 200, retry.text
    assert len(_rows(roadside["passenger"])) == 1
    assert len(roadside["calls"]["telegram"]) == len(roadside["calls"]["sms"]) == 1


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Real PostgreSQL unique-key writers required")
def test_concurrent_same_owner_key_body_commits_one_event_and_original_fanout(client, roadside, monkeypatch):
    barrier = Barrier(2)
    commit = Session.commit

    def both_commit(session):
        if any(isinstance(row, SosEvent) and row.user_id == roadside["passenger"]["id"] for row in session.new):
            barrier.wait(timeout=10)
        return commit(session)

    monkeypatch.setattr(Session, "commit", both_commit)
    with ThreadPoolExecutor(max_workers=2) as pool:
        replies = list(pool.map(lambda _: _post(client, roadside), range(2)))
    assert [reply.status_code for reply in replies] == [200, 200]
    assert replies[0].json()["id"] == replies[1].json()["id"]
    assert len(_rows(roadside["passenger"])) == 1
    assert len(roadside["calls"]["telegram"]) == len(roadside["calls"]["sms"]) == 1
