"""Route subscription delivery keeps the destination recorded in the inbox.

Exercise the real HTTP publication and background worker. Only the outbound
transport is replaced, so no Firebase or browser notifications leave the test.
"""
from threading import Event

import pytest

import app.services as services
from test_route_watch import _publish_ride
from test_request_watch import _watch


@pytest.mark.parametrize("reverse", [False, True])
def test_route_subscription_push_keeps_destination(client, user_factory, monkeypatch, reverse):
    watcher = user_factory("Destination watcher")
    author = user_factory("Destination author")
    _watch(client, watcher, kind="rides", direction="both" if reverse else "forward")
    delivered = Event()
    calls = []

    def capture(session, user_id, title, body, data=None):
        if user_id == watcher["id"]:
            calls.append(data)
            delivered.set()

    monkeypatch.setattr(services, "send_push", capture)
    frm, to = ("Уфа", "Сибай") if reverse else ("Сибай", "Уфа")
    created = _publish_ride(client, author, frm=frm, to=to)
    assert delivered.wait(5), "The real background worker did not reach the transport"
    assert calls == [{"type": "ride", "id": str(created["id"])}]
    notes = client.get("/notifications", headers=watcher["auth"]).json()["items"]
    assert any(n["ref_kind"] == "ride" and n["ref_id"] == created["id"] for n in notes)
