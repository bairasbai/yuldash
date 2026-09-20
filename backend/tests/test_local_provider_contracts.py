"""Offline provider contracts: real SDK objects, transport replaced before any I/O.

These tests do not certify remote credentials, bucket policy or phone delivery.
"""
import io
import json
from types import SimpleNamespace

import boto3
import httpx
import pytest
from botocore.response import StreamingBody
from botocore.stub import Stubber
from sqlmodel import Session, select

from app import payments, services
from app.config import settings
from app.db import engine
from app.models import DeviceToken
from app.storage import S3Storage, StorageError


@pytest.fixture
def offline_yookassa(monkeypatch):
    monkeypatch.setattr(settings, "payments_provider", "yookassa")
    monkeypatch.setattr(settings, "yookassa_shop_id", "local-test-shop")
    monkeypatch.setattr(settings, "yookassa_secret_key", "local-test-secret")
    monkeypatch.setattr(payments, "YOOKASSA_API", "https://provider.invalid/v3/payments")


def test_payment_serializes_and_reuses_idempotence_key(monkeypatch, offline_yookassa):
    requests = []

    def handler(request):
        requests.append(request)
        if request.method == "POST":
            return httpx.Response(200, json={"id": "local-payment", "status": "pending",
                                           "confirmation": {"confirmation_url": "https://provider.invalid/pay"}})
        return httpx.Response(200, json={"status": "succeeded", "metadata": {"payment_id": "42"}})

    with httpx.Client(transport=httpx.MockTransport(handler)) as transport_client:
        monkeypatch.setattr(httpx, "post", transport_client.post)
        monkeypatch.setattr(httpx, "get", transport_client.get)
        for _ in range(2):
            result = payments.create_payment(12345, "Локальная проверка", {"payment_id": "42"},
                                             "+79990000000", idempotence_key="payment-42")
            assert result["provider_id"] == "local-payment"
            assert result["mock"] is False
        assert payments.fetch_payment("local-payment")["status"] == "succeeded"

    assert len(requests) == 3
    first, retry, fetched = requests
    assert first.content == retry.content
    assert first.headers["Idempotence-Key"] == retry.headers["Idempotence-Key"] == "payment-42"
    assert first.headers["Authorization"].startswith("Basic ")
    body = json.loads(first.content)
    assert body["amount"] == {"value": "123.45", "currency": "RUB"}
    assert body["receipt"]["customer"]["phone"] == "79990000000"
    assert body["metadata"] == {"payment_id": "42"}
    assert fetched.url.path.endswith("/local-payment")


@pytest.mark.parametrize("status", [401, 429, 500])
def test_payment_http_errors_do_not_become_success(monkeypatch, offline_yookassa, status):
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(status))) as client:
        monkeypatch.setattr(httpx, "post", client.post)
        monkeypatch.setattr(httpx, "get", client.get)
        with pytest.raises(httpx.HTTPStatusError):
            payments.create_payment(1000, "local", {}, idempotence_key="one")
        with pytest.raises(httpx.HTTPStatusError):
            payments.fetch_payment("one")


def test_payment_timeout_does_not_become_success(monkeypatch, offline_yookassa):
    def timeout(request):
        raise httpx.ReadTimeout("synthetic timeout", request=request)
    with httpx.Client(transport=httpx.MockTransport(timeout)) as client:
        monkeypatch.setattr(httpx, "post", client.post)
        with pytest.raises(httpx.ReadTimeout):
            payments.create_payment(1000, "local", {}, idempotence_key="one")


@pytest.fixture
def s3_contract():
    client = boto3.client("s3", endpoint_url="https://s3.invalid", region_name="local-1",
                          aws_access_key_id="local-test", aws_secret_access_key="local-test")
    with Stubber(client) as stub:
        yield S3Storage(bucket="local-audit", client=client), stub
        stub.assert_no_pending_responses()


def test_s3_real_sdk_upload_read_delete_contract(s3_contract):
    storage, stub = s3_contract
    params = {"Bucket": "local-audit", "Key": "docs/42_test.jpg"}
    payload = b"synthetic-image"
    stub.add_response("put_object", {}, {**params, "Body": payload})
    stub.add_response("get_object", {"Body": StreamingBody(io.BytesIO(payload), len(payload))}, params)
    stub.add_response("head_object", {}, params)
    stub.add_response("delete_object", {}, params)
    stub.add_client_error("head_object", "404", http_status_code=404, expected_params=params)
    storage.save(params["Key"], payload)
    assert storage.load(params["Key"]) == payload
    assert storage.exists(params["Key"])
    storage.delete(params["Key"])
    assert not storage.exists(params["Key"])


@pytest.mark.parametrize("method, operation", [("load", "get_object"), ("exists", "head_object")])
def test_s3_access_denied_is_not_missing_file(s3_contract, method, operation):
    storage, stub = s3_contract
    stub.add_client_error(operation, "AccessDenied", http_status_code=403,
                          expected_params={"Bucket": "local-audit", "Key": "docs/private.jpg"})
    with pytest.raises(StorageError):
        getattr(storage, method)("docs/private.jpg")


@pytest.mark.parametrize("data_only", [False, True])
def test_fcm_real_sdk_payload_and_selective_dead_token_cleanup(monkeypatch, user_factory, data_only):
    messaging = pytest.importorskip(
        "firebase_admin.messaging",
        reason="Install firebase-admin from requirements.txt to verify the real FCM SDK offline",
    )

    owner = user_factory("LocalFcmOwner")
    neighbor = user_factory("LocalFcmNeighbor")
    token_prefix = str(owner["id"])
    tokens = [f"{token_prefix}-{suffix}" for suffix in ("ok", "dead", "retry")]
    with Session(engine) as session:
        for token in tokens:
            session.add(DeviceToken(user_id=owner["id"], token=token))
        session.add(DeviceToken(user_id=neighbor["id"], token=f"{token_prefix}-neighbor"))
        session.commit()
    monkeypatch.setattr(settings, "firebase_credentials", "local-not-read.json")
    monkeypatch.setattr(services, "_fcm_app", object())
    web = []
    monkeypatch.setattr(services, "_send_web_push", lambda *args: web.append(args[1]))
    captured = []

    def send_each(messages):
        captured.extend(messages)
        errors = {tokens[0]: None, tokens[1]: messaging.UnregisteredError("synthetic expired"),
                  tokens[2]: RuntimeError("synthetic temporary failure")}
        return SimpleNamespace(responses=[SimpleNamespace(success=errors[m.token] is None,
                                                         exception=errors[m.token]) for m in messages])

    monkeypatch.setattr(messaging, "send_each", send_each)
    with Session(engine) as session:
        services.send_push(session, owner["id"], "Заголовок", "Текст",
                           data={"ref_kind": "instant", "ref_id": 42}, data_only=data_only)
    assert web == [owner["id"]]
    assert {message.token for message in captured} == set(tokens)
    for message in captured:
        assert isinstance(message, messaging.Message)
        assert message.data["ref_id"] == "42"
        assert message.data["ref_kind"] == "instant"
        assert message.notification is None
        assert message.android.priority == "high"
        assert message.data["title"] == "Заголовок"
        assert message.data["body"] == "Текст"
        assert message.data["recipient_user_id"] == str(owner["id"])
    with Session(engine) as session:
        remaining = set(session.exec(select(DeviceToken.token).where(
            DeviceToken.user_id.in_([owner["id"], neighbor["id"]]))).all())
    assert remaining == {tokens[0], tokens[2], f"{token_prefix}-neighbor"}
