# -*- coding: utf-8 -*-
"""«Скрыть у себя» не должно быть видно собеседнику (ревью PR190, 23.10.2026).

Две утечки, одна причина:
* поле `hidden_user_ids` («кто скрыл это сообщение») уходило в КАЖДОМ ответе с сообщением, и
  собеседник читал из истории id того, кто скрыл его слова;
* скрытие «у себя» рассылало по общему каналу брони кадр `history_changed`, и собеседник с
  открытым чатом узнавал, что другая сторона только что что-то скрыла.

Скрытие при этом работает: себе сообщение не показывается, собеседнику остаётся как было.
"""
import pytest
from sqlmodel import Session

from app import services
from app.db import engine
from app.models import Message
from test_location_privacy import _confirmed_booking
from test_rest_chat_local_delivery import connected, receive, send


@pytest.fixture
def local_delivery(monkeypatch):
    monkeypatch.setattr(services, "_cache_client", lambda: None)


def _hide(client, bid, mid, actor):
    return client.delete(f"/bookings/{bid}/messages/{mid}", headers=actor["auth"], params={"scope": "me"})


def test_peer_history_does_not_say_who_hid_a_message(client, user_factory):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "HiddenPeer")
    saved = send(client, bid, driver, "Буду в 8")
    hidden = _hide(client, bid, saved["id"], passenger)
    assert hidden.status_code == 200, hidden.text

    seen = client.get(f"/bookings/{bid}/messages", headers=driver["auth"])
    assert seen.status_code == 200
    rows = seen.json()
    assert [row["id"] for row in rows] == [saved["id"]]          # собеседнику сообщение осталось
    assert all("hidden_user_ids" not in row for row in rows)     # кто скрыл — собеседнику не видно

    assert client.get(f"/bookings/{bid}/messages", headers=passenger["auth"]).json() == []   # себе — скрыто
    # Скрытие реально записано на сервере: поле исключено из ответов, а не из базы.
    with Session(engine) as session:
        assert str(passenger["id"]) in session.get(Message, saved["id"]).hidden_user_ids.split(",")


def test_no_booking_chat_response_carries_the_hidden_list(client, user_factory):
    """Ни один ответ с сообщением (отправка, правка, скрытие, удаление у всех) не несёт поле."""
    driver, passenger, bid = _confirmed_booking(client, user_factory, "HiddenEverywhere")
    sent = client.post(f"/bookings/{bid}/messages", headers=driver["auth"], json={"text": "первое"})
    assert sent.status_code == 200, sent.text
    mid = sent.json()["id"]
    edited = client.post(f"/bookings/{bid}/messages/{mid}/edit", headers=driver["auth"], json={"text": "второе"})
    hidden = _hide(client, bid, mid, passenger)
    again = _hide(client, bid, mid, driver)                       # уже скрыто другим: чужой id не возвращается
    deleted = client.delete(f"/bookings/{bid}/messages/{mid}", headers=driver["auth"], params={"scope": "all"})
    for response in (sent, edited, hidden, again, deleted):
        assert response.status_code == 200, response.text
        assert "hidden_user_ids" not in response.json()


@pytest.mark.parametrize("actor_role", ["driver", "passenger"])
def test_hiding_for_myself_sends_no_signal_to_anyone(client, user_factory, local_delivery, actor_role):
    driver, passenger, bid = _confirmed_booking(client, user_factory, "HiddenSilent")
    actor, peer = (driver, passenger) if actor_role == "driver" else (passenger, driver)
    with connected(client, bid, actor) as own, connected(client, bid, peer) as other:
        saved = send(client, bid, peer, "для скрытия")
        assert receive(own)["id"] == saved["id"]
        assert receive(other)["id"] == saved["id"]
        assert _hide(client, bid, saved["id"], actor).status_code == 200
        assert receive(own, 0.2) is None
        assert receive(other, 0.2) is None


def test_deleting_for_everyone_still_signals_both_chats(client, user_factory, local_delivery):
    """Обратная сторона: «удалить у всех» меняет историю обоих — сигнал остаётся."""
    driver, passenger, bid = _confirmed_booking(client, user_factory, "HiddenAll")
    with connected(client, bid, driver) as own, connected(client, bid, passenger) as other:
        saved = send(client, bid, driver, "удалю у всех")
        assert receive(own)["id"] == saved["id"]
        assert receive(other)["id"] == saved["id"]
        response = client.delete(f"/bookings/{bid}/messages/{saved['id']}", headers=driver["auth"],
                                 params={"scope": "all"})
        assert response.status_code == 200, response.text
        assert receive(own) == {"type": "history_changed"}
        assert receive(other) == {"type": "history_changed"}
