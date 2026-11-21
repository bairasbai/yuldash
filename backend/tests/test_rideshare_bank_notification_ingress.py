"""Exercise the mounted webhook with an isolated provider transport."""

from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta
from pathlib import Path
from threading import Event
from uuid import uuid4

import httpx
import anyio
import pytest
from pydantic import SecretStr
from sqlmodel import Session

from app import rideshare_bank_queue as queue
from app.config import settings
from app.db import engine
from app.main import app
from app.models import Booking
from app.owner_switches import PAID_SWITCH
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankPosting
from app.rideshare_bank_runtime import configure_rideshare_bank
from app.routers import rideshare_bank_notification as ingress
from app.routers import payments as legacy_payments
from app.timeutil import utcnow
from test_rideshare_bank_command import card_path
from test_rideshare_bank_dispatch import bank_checkout as bank_checkout
from test_rideshare_bank_dispatch import send
from test_rideshare_bank_runtime import _config


PATH = "/payments/rideshare-bank/webhook"
TOKEN = "synthetic-proxy-only-test-token-0123456789"
AUTH = {"X-Yuldash-Bank-Proxy-Token": TOKEN}


def _event(provider_id):
    return {"type": "notification", "event": "payment.succeeded", "object": {"id": provider_id}}


def test_disabled_and_spoofed_paths_never_open_a_provider(client, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("Undelegated webhook reached provider processing")

    monkeypatch.setattr(ingress, "_process_notification", forbidden)
    monkeypatch.setattr(settings, "rideshare_bank_webhook_proxy_token", SecretStr(""))
    assert client.post(PATH, headers=AUTH, json=_event("synthetic")).status_code == 404
    monkeypatch.setattr(settings, "rideshare_bank_webhook_proxy_token", SecretStr(TOKEN))
    monkeypatch.setattr(settings, "rideshare_bank_read_enabled", True)
    monkeypatch.setattr(settings, "rideshare_bank_shop_id", "")
    assert client.post(PATH, headers=AUTH, json=_event("synthetic")).status_code == 404
    configured = _config()
    monkeypatch.setattr(settings, "rideshare_bank_shop_id", configured.rideshare_bank_shop_id)
    monkeypatch.setattr(settings, "rideshare_bank_secret", configured.rideshare_bank_secret)
    monkeypatch.setattr(settings, "rideshare_bank_fernet_key", configured.rideshare_bank_fernet_key)
    assert client.post(PATH, headers={"X-Real-IP": "185.71.76.5", "X-Forwarded-For": "185.71.76.5"},
                       json=_event("synthetic")).status_code == 404
    assert client.post(PATH, headers=list(AUTH.items()) * 2, json=_event("synthetic")).status_code == 404
    assert client.post("/api/v1" + PATH, headers=AUTH, json=_event("synthetic")).status_code == 404
    assert client.post(PATH, headers=AUTH, content=b"{" + b"x" * (64 * 1024)).status_code == 413
    assert client.post(PATH, headers=AUTH, content=b"[broken").status_code == 400
    assert client.post(PATH, headers=AUTH, json={"event": "unsupported"}).status_code == 200


@pytest.fixture
def pending_notice(bank_checkout, monkeypatch):
    service = bank_checkout
    checkout_id = service.begin()
    assert send(service, checkout_id) == "succeeded"
    assert send(service, checkout_id) == "pending"
    operation_id = service.fake.requests[-1][2]
    provider_id = service.fake.by_key[operation_id]
    configuration = _config(shop_id=str(uuid4().int))
    for name in ("rideshare_bank_read_enabled", "rideshare_bank_shop_id",
                 "rideshare_bank_secret", "rideshare_bank_fernet_key"):
        monkeypatch.setattr(settings, name, getattr(configuration, name))
    monkeypatch.setattr(settings, "rideshare_bank_webhook_proxy_token", SecretStr(TOKEN))
    service.toggle(PAID_SWITCH, False)
    with Session(engine) as db:
        checkout = db.get(RideshareBankCheckout, checkout_id)
        checkout.test_mode = False
        checkout.provider_account_id = configuration.rideshare_bank_shop_id
        db.commit()
    return service, checkout_id, operation_id, provider_id


@pytest.fixture
def bank_http(monkeypatch):
    make_client = httpx.Client
    clients = []

    def install(handler):
        def open_client(**kwargs):
            assert kwargs["follow_redirects"] is False and kwargs["trust_env"] is False
            timeout = kwargs["timeout"]
            assert (timeout.connect, timeout.read, timeout.pool) == (2.0, 5.0, 1.0)
            client = make_client(transport=httpx.MockTransport(handler), **kwargs)
            clients.append(client)
            return client

        monkeypatch.setattr(ingress.httpx, "Client", open_client)
        return clients, make_client

    return install


def test_incoming_hint_composes_with_worker_without_a_second_get(client, pending_notice, bank_http):
    service, checkout_id, operation_id, provider_id = pending_notice
    service.fake.finish_payment(operation_id)
    entered, release = Event(), Event()
    calls = []

    def reply(request):
        calls.append(request.url.path)
        assert request.method == "GET" and request.url.path == f"/v3/payments/{provider_id}"
        entered.set()
        assert release.wait(8)
        return httpx.Response(200, json={**service.fake.objects[provider_id], "test": False})

    clients, http_client = bank_http(reply)
    hint = _event(provider_id)
    hint["object"].update(status="pending", amount={"value": "1", "currency": "USD"})
    with ThreadPoolExecutor(max_workers=1) as pool:
        first = pool.submit(client.post, PATH, headers=AUTH, json=hint)
        try:
            assert entered.wait(6)
            assert client.post(PATH, headers=AUTH, json=hint).status_code == 503
            profile = configure_rideshare_bank(settings)
            assert profile is not None
            with http_client(transport=httpx.MockTransport(reply), trust_env=False) as transport:
                assert queue.run_bank_reconciliation(engine, runtime=profile.runtime,
                                                     provider=profile.provider(transport)) == []
        finally:
            release.set()
        assert first.result(timeout=8).status_code == 200
    assert client.post(PATH, headers=AUTH, json=hint).status_code == 200
    assert client.post(PATH, headers=AUTH, json=_event("unmatched-id")).status_code == 200
    assert calls == [f"/v3/payments/{provider_id}"]
    assert all(client.is_closed for client in clients)
    assert client.get(card_path(service), headers=service.passenger["auth"]).json()["paid_kop"] == 45_000
    with Session(engine) as db:
        assert db.get(RideshareBankPosting, operation_id).amount_kop == 45_000
        assert db.get(RideshareBankCheckout, checkout_id).next_bank_check_at is not None


def test_hint_recovers_missing_provider_id_without_touching_legacy_payments(client, pending_notice,
                                                                          bank_http, monkeypatch):
    service, _, operation_id, provider_id = pending_notice
    service.fake.finish_payment(operation_id)
    result = {**service.fake.objects[provider_id], "test": False}
    with Session(engine) as db:
        operation = db.get(RideshareBankOperation, operation_id)
        operation.provider_id = None
        operation.state = "needs_reconciliation"
        db.commit()
    reads = []

    def forbidden(*args):
        pytest.fail("Rideshare notice reached the legacy payment updater")

    monkeypatch.setattr(legacy_payments, "_process_yookassa_webhook", forbidden)

    def reply(request):
        reads.append(request.url.path)
        assert request.method == "GET"
        return httpx.Response(200, json=result)

    bank_http(reply)
    assert client.post(PATH, headers=AUTH, json=_event(provider_id)).status_code == 200
    assert not reads
    hint = _event(provider_id)
    hint["object"]["metadata"] = result["metadata"]
    assert client.post(PATH, headers=AUTH, json=hint).status_code == 200
    assert reads == [f"/v3/payments/{provider_id}"]
    with Session(engine) as db:
        assert db.get(RideshareBankOperation, operation_id).provider_id == provider_id
        assert db.get(RideshareBankPosting, operation_id).amount_kop == 45_000


def test_ingress_has_a_bounded_wait_for_busy_slots(client, pending_notice, monkeypatch):
    _, _, _, provider_id = pending_notice
    slots = app.state.rideshare_bank_notification_slots

    async def reserve():
        for _ in range(4):
            await slots.acquire()

    async def release():
        for _ in range(4):
            slots.release()

    def forbidden(*args):
        pytest.fail("Webhook entered the bank transport with all slots occupied")

    monkeypatch.setattr(ingress, "_process_notification", forbidden)
    client.portal.call(reserve)
    try:
        assert client.post(PATH, headers=AUTH, json=_event(provider_id)).status_code == 503
    finally:
        client.portal.call(release)


def test_slow_request_body_times_out_before_provider_processing(pending_notice, monkeypatch):
    def forbidden(*args):
        pytest.fail("A timed-out request reached provider processing")

    class SlowBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b"{"
            await anyio.sleep(5)
            yield b"}"

    async def request():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            response = await client.post(PATH, headers=AUTH, content=SlowBody())
            return response.status_code

    monkeypatch.setattr(ingress, "_process_notification", forbidden)
    assert anyio.run(request) == 408


@pytest.mark.parametrize(("invalid", "status"), [
    ("timeout", 503), ("currency", 503), ("amount", 200), ("metadata", 200), ("deal", 200),
])
def test_unverified_result_keeps_the_operation_unpaid(client, pending_notice, bank_http, monkeypatch, invalid, status):
    service, checkout_id, operation_id, provider_id = pending_notice
    service.fake.finish_payment(operation_id)
    clock = [utcnow()]
    monkeypatch.setattr(queue, "utcnow", lambda: clock[0])
    attempts = []

    def reply(request):
        assert request.method == "GET"
        attempts.append(request.url.path)
        result = {**service.fake.objects[provider_id], "test": False}
        if len(attempts) == 1:
            if invalid == "timeout":
                raise httpx.ReadTimeout("synthetic")
            if invalid == "currency":
                result["amount"] = {"value": "450.00", "currency": "USD"}
            if invalid == "amount":
                result["amount"] = {"value": "449.00", "currency": "RUB"}
            if invalid == "metadata":
                result["metadata"] = {}
            if invalid == "deal":
                result["deal"] = {**result["deal"], "id": "synthetic-other-deal"}
        return httpx.Response(200, json=result)

    clients, _ = bank_http(reply)
    assert client.post(PATH, headers=AUTH, json=_event(provider_id)).status_code == status
    assert all(client.is_closed for client in clients)
    with Session(engine) as db:
        assert db.get(RideshareBankPosting, operation_id) is None
        assert db.get(RideshareBankOperation, operation_id).state == "pending"
        assert not db.get(Booking, service.booking_id).paid
        assert db.get(RideshareBankCheckout, checkout_id).next_bank_check_at == clock[0] + timedelta(minutes=1)
    clock[0] += timedelta(minutes=2)
    assert client.post(PATH, headers=AUTH, json=_event(provider_id)).status_code == 200
    assert len(attempts) == 2
    with Session(engine) as db:
        assert db.get(RideshareBankPosting, operation_id).amount_kop == 45_000


def test_nginx_template_has_one_callback_and_replaces_the_proxy_authentication():
    path = Path(__file__).resolve().parents[1] / "deploy" / "nginx-rideshare-bank-webhook.conf.example"
    configuration = path.read_text(encoding="utf-8")
    assert f"location = {PATH} {{" in configuration
    assert f"location = /api/v1{PATH} {{\n    return 404;" in configuration
    assert {line.strip() for line in configuration.splitlines() if line.strip().startswith("allow ")} == {
        "allow 185.71.76.0/27;", "allow 185.71.77.0/27;", "allow 77.75.153.0/25;",
        "allow 77.75.156.11;", "allow 77.75.156.35;", "allow 77.75.154.128/25;", "allow 2a02:5180::/32;",
    }
    assert "deny all;" in configuration
    assert "include /etc/yuldash/rideshare-bank-webhook-token.conf;" in configuration
