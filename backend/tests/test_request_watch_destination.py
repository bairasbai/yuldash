"""Real request publication and worker preserve the driver's accessible destination."""
from threading import Event
import json

import pytest
from sqlmodel import Session

import app.services as services
from app.db import engine
from app.models import WebPushSubscription
from test_request_watch import _request, _watch


def test_request_subscription_targets_driver_feed(client, user_factory, monkeypatch):
    driver = user_factory("Request destination driver")
    passenger = user_factory("Request destination passenger")
    _watch(client, driver)
    delivered = Event()
    calls = []

    def capture(session, user_id, title, body, data=None):
        if user_id == driver["id"]:
            calls.append(data)
            delivered.set()

    monkeypatch.setattr(services, "send_push", capture)
    created = _request(client, passenger)
    assert delivered.wait(5), "The real background worker did not reach the transport"
    request_id = created["id"]
    assert client.get(f"/requests/{request_id}/responses", headers=driver["auth"]).status_code == 403
    assert client.get(f"/requests/{request_id}/responses", headers=passenger["auth"]).status_code == 200
    feed = client.get("/requests/feed", headers=driver["auth"])
    assert feed.status_code == 200
    assert any(r["id"] == request_id for r in feed.json())
    notes = client.get("/notifications", headers=driver["auth"]).json()["items"]
    note = next(n for n in notes if n["type"] == "request_watch")
    assert note["ref_kind"] == "request_watch"
    assert note["ref_id"] == request_id
    assert calls == [{"type": "request_watch", "id": str(request_id)}]
    assert services._web_push_url(calls[0]) == "/requests-feed"


@pytest.mark.parametrize("data,path", [
    ({"type": "request_watch", "id": "42"}, "/requests-feed"),
    ({"ref_kind": "request_watch", "ref_id": 42}, "/requests-feed"),
    ({"type": "request", "id": "42"}, "/requests/42/responses"),
    ({"ref_kind": "request", "ref_id": 42}, "/requests/42/responses"),
    ({"type": "ride", "id": "42"}, "/rides/42"),
    ({"ref_kind": "ride", "ref_id": 42}, "/rides/42"),
    ({"type": "booking", "id": "42"}, "/booking/42"),
    ({"ref_kind": "booking", "ref_id": 42}, "/booking/42"),
    ({"type": "chat", "id": "42"}, "/booking/42"),
    ({"type": "order_chat", "id": "42"}, "/taxi"),
    ({"type": "instant_status", "order_id": "42"}, "/taxi"),
    ({"type": "instant_payment", "order_id": "42"}, "/taxi"),
    ({"type": "instant_im_coming", "order_id": "42"}, "/taxi"),
    (None, "/"),
    ({"type": "unknown", "id": "42"}, "/"),
])
def test_web_push_accepts_transport_and_record_destination(data, path):
    assert services._web_push_url(data) == path


@pytest.mark.parametrize("data,path,tag", [
    ({"type": "request_watch", "id": "42"}, "/requests-feed", "request_watch-42"),
    ({"type": "ride", "id": "42"}, "/rides/42", "ride-42"),
    ({"ref_kind": "booking", "ref_id": 42}, "/booking/42", "booking-42"),
])
def test_browser_transport_serializes_destination(user_factory, monkeypatch, data, path, tag):
    import pywebpush

    owner = user_factory("Browser destination owner")
    captured = []
    monkeypatch.setattr(services.settings, "vapid_private_key", "local-not-read")
    monkeypatch.setattr(services.settings, "vapid_public_key", "local-not-read")
    monkeypatch.setattr(pywebpush, "webpush", lambda **kwargs: captured.append(kwargs))
    with Session(engine) as session:
        session.add(WebPushSubscription(user_id=owner["id"],
                    endpoint=f"https://fcm.googleapis.com/local-destination/{owner['id']}", p256dh="test", auth="test"))
        session.commit()
        services._send_web_push(session, owner["id"], "Title", "Body", data)
    assert len(captured) == 1
    payload = json.loads(captured[0]["data"])
    assert payload["url"] == path
    assert payload["tag"] == tag
