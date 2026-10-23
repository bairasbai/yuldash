"""Committed booking chat changes invalidate history without disclosing row contents.

Actual HTTP/WS handlers and the isolated test database; no external delivery claim.
"""
import pytest

from app import services
from test_location_privacy import _confirmed_booking
from test_rest_chat_local_delivery import connected, receive, send


@pytest.fixture
def local_delivery(monkeypatch):
    monkeypatch.setattr(services, "_cache_client", lambda: None)


def mutation(client, bid, mid, actor, operation, text="edited live"):
    path = f"/bookings/{bid}/messages/{mid}"
    if operation == "edit":
        return client.post(path + "/edit", headers=actor["auth"], json={"text": text})
    return client.delete(path, headers=actor["auth"], params={"scope": operation})


def history(client, bid, actor):
    response = client.get(f"/bookings/{bid}/messages", headers=actor["auth"])
    assert response.status_code == 200
    return response.json()


def signal_for(operation):
    """(ожидаемый кадр, сколько ждать): «у себя» молчит, правка и «у всех» — history_changed."""
    if operation == "me":
        return None, 0.1
    return {"type": "history_changed"}, 1


@pytest.mark.parametrize("actor_role", ["driver", "passenger"])
@pytest.mark.parametrize("operation", ["edit", "all", "me"])
def test_committed_mutation_reaches_both_open_chats_as_content_free_invalidation(
    client, user_factory, local_delivery, actor_role, operation,
):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "MutationLive")
    actor, peer = ((driver, passenger) if actor_role == "driver" else (passenger, driver))
    # Hiding a peer-authored row is allowed; editing/deleting all requires authorship.
    author = peer if operation == "me" else actor
    with connected(client, bid, actor) as own, connected(client, bid, peer) as other:
        saved = send(client, bid, author, "private source text")
        assert receive(own)["id"] == saved["id"]
        assert receive(other)["id"] == saved["id"]
        response = mutation(client, bid, saved["id"], actor, operation)
        assert response.status_code == 200, response.text
        # Скрытие «у себя» — тихое: история собеседника не меняется, сигнала ему не шлём.
        expected, wait = signal_for(operation)
        assert receive(own, wait) == expected, "own chat: wrong invalidation"
        assert receive(other, wait) == expected, "peer chat: wrong invalidation"
        assert receive(own, 0.1) is None
        assert receive(other, 0.1) is None
    own_rows, peer_rows = history(client, bid, actor), history(client, bid, peer)
    if operation == "me":
        assert own_rows == []
        assert len(peer_rows) == 1 and peer_rows[0]["text"] == saved["text"]
        assert not peer_rows[0]["deleted"]
    elif operation == "all":
        assert own_rows[0]["deleted"] and peer_rows[0]["deleted"]
        assert own_rows[0]["text"] == peer_rows[0]["text"] == ""
    else:
        assert own_rows[0]["text"] == peer_rows[0]["text"] == "edited live"


@pytest.mark.parametrize("operation", ["edit", "all", "me"])
def test_mutation_invalidation_does_not_reach_another_booking(
    client, user_factory, local_delivery, operation,
):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "MutationTarget")
    _, stranger, other_bid = _confirmed_booking(client, user_factory, "MutationOther")
    with connected(client, bid, passenger) as own, connected(client, other_bid, stranger) as unrelated:
        saved = send(client, bid, driver)
        assert receive(own)["id"] == saved["id"]
        assert mutation(client, bid, saved["id"], driver, operation).status_code == 200
        expected, wait = signal_for(operation)
        assert receive(own, wait) == expected
        assert receive(unrelated, 0.1) is None


@pytest.mark.parametrize("operation", ["edit", "all", "me"])
def test_rejected_or_missing_mutation_emits_no_invalidation(
    client, user_factory, local_delivery, operation,
):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "MutationDenied")
    outsider = user_factory("MutationOutsider")
    saved = send(client, bid, driver)
    with connected(client, bid, passenger) as receiver:
        assert mutation(client, bid, saved["id"], outsider, operation).status_code == 403
        assert receive(receiver, 0.1) is None
        assert mutation(client, bid, 999999999, driver, operation).status_code == 404
        assert receive(receiver, 0.1) is None
        if operation != "me":
            assert mutation(client, bid, saved["id"], passenger, operation).status_code == 403
            assert receive(receiver, 0.1) is None


@pytest.mark.parametrize("operation", ["edit", "all", "me"])
def test_retry_only_reasserts_state_and_invalidation_without_new_push(
    client, user_factory, monkeypatch, local_delivery, operation,
):
    from app.routers import chat

    driver, passenger, bid = _confirmed_booking(client, user_factory, "MutationRetry")
    saved = send(client, bid, driver)
    pushes = []
    monkeypatch.setattr(chat, "push_notification", lambda *args, **kwargs: pushes.append("push"))
    monkeypatch.setattr(chat, "send_push", lambda *args, **kwargs: pushes.append("send"))
    with connected(client, bid, passenger) as receiver:
        for _ in range(2):
            response = mutation(client, bid, saved["id"], driver, operation)
            assert response.status_code == 200
            assert response.json()["id"] == saved["id"]
            expected, wait = signal_for(operation)
            assert receive(receiver, wait) == expected
            assert receive(receiver, 0.1) is None
    assert pushes == []
    assert len(history(client, bid, passenger)) == 1


def test_hidden_history_remains_hidden_after_later_peer_edit_and_delete_all(
    client, user_factory, local_delivery,
):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "MutationHidden")
    saved = send(client, bid, driver)
    with connected(client, bid, passenger) as own, connected(client, bid, driver) as peer:
        for actor, operation in [(passenger, "me"), (driver, "edit"), (driver, "all")]:
            response = mutation(client, bid, saved["id"], actor, operation)
            assert response.status_code == 200
            expected, wait = signal_for(operation)
            assert receive(own, wait) == expected
            assert receive(peer, wait) == expected
            assert history(client, bid, passenger) == []
        peer_rows = history(client, bid, driver)
        assert len(peer_rows) == 1 and peer_rows[0]["deleted"]


def test_failed_live_delivery_does_not_undo_a_committed_edit(
    client, user_factory, monkeypatch, local_delivery,
):
    from unittest.mock import AsyncMock

    driver, passenger, bid = _confirmed_booking(client, user_factory, "MutationBestEffort")
    saved = send(client, bid, driver)
    monkeypatch.setattr(services.manager, "local_broadcast",
                        AsyncMock(side_effect=RuntimeError("synthetic local delivery unavailable")))
    response = mutation(client, bid, saved["id"], driver, "edit")
    assert response.status_code == 200
    assert history(client, bid, passenger)[0]["text"] == "edited live"
