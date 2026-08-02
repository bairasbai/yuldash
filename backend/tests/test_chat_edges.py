from app.models import UserRole


def _publish(client, driver, frm="ChatA", to="ChatB"):
    response = client.post(
        "/rides",
        headers=driver["auth"],
        json={
            "from_city": frm,
            "to_city": to,
            "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3,
            "price": 300,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _book(client, passenger, ride_id):
    response = client.post(
        "/bookings",
        headers=passenger["auth"],
        json={"ride_id": ride_id, "seats": 1},
    )
    assert response.status_code == 200, response.text
    return response.json()


def _trip(client, user_factory, suffix=""):
    driver = user_factory(f"ChatDriver{suffix}", role=UserRole.driver)
    passenger = user_factory(f"ChatPassenger{suffix}")
    ride = _publish(client, driver, frm=f"ChatFrom{suffix}", to=f"ChatTo{suffix}")
    booking = _book(client, passenger, ride["id"])
    return driver, passenger, ride, booking


def test_chat_requires_participant_for_message_history(client, user_factory):
    _driver, passenger, _ride, booking = _trip(client, user_factory, "History")
    outsider = user_factory("ChatOutsider")

    assert client.get(f"/bookings/{booking['id']}/messages", headers=passenger["auth"]).status_code == 200
    assert client.get(f"/bookings/{booking['id']}/messages", headers=outsider["auth"]).status_code == 403


def test_chat_rejects_empty_edit_deleted_edit_and_voice_edit(client, user_factory):
    _driver, passenger, _ride, booking = _trip(client, user_factory, "Edit")
    booking_id = booking["id"]

    text_message = client.post(
        f"/bookings/{booking_id}/messages",
        headers=passenger["auth"],
        json={"text": "hello"},
    ).json()
    empty_edit = client.post(
        f"/bookings/{booking_id}/messages/{text_message['id']}/edit",
        headers=passenger["auth"],
        json={"text": "   "},
    )
    assert empty_edit.status_code == 400

    deleted = client.delete(
        f"/bookings/{booking_id}/messages/{text_message['id']}?scope=all",
        headers=passenger["auth"],
    )
    assert deleted.status_code == 200
    edit_deleted = client.post(
        f"/bookings/{booking_id}/messages/{text_message['id']}/edit",
        headers=passenger["auth"],
        json={"text": "after delete"},
    )
    assert edit_deleted.status_code == 400

    voice_message = client.post(
        f"/bookings/{booking_id}/messages",
        headers=passenger["auth"],
        json={"voice_url": "https://yulbash.ru/media/voice/test.ogg", "transcript": "voice text"},
    ).json()
    edit_voice = client.post(
        f"/bookings/{booking_id}/messages/{voice_message['id']}/edit",
        headers=passenger["auth"],
        json={"text": "typed text"},
    )
    assert edit_voice.status_code == 400


def test_chat_rejects_external_voice_url(client, user_factory):
    """voice_url — только наш медиа-URL; внешнюю ссылку не принимаем (утечка IP / трекинг собеседника)."""
    _driver, passenger, _ride, booking = _trip(client, user_factory, "Ext")
    bad = client.post(f"/bookings/{booking['id']}/messages", headers=passenger["auth"],
                      json={"voice_url": "https://example.test/voice.ogg"})
    assert bad.status_code == 422
    ok = client.post(f"/bookings/{booking['id']}/messages", headers=passenger["auth"],
                     json={"voice_url": "https://yulbash.ru/media/voice/x.ogg"})
    assert ok.status_code == 200


def test_chat_message_must_belong_to_booking(client, user_factory):
    _driver1, passenger1, _ride1, booking1 = _trip(client, user_factory, "One")
    _driver2, passenger2, _ride2, booking2 = _trip(client, user_factory, "Two")

    message = client.post(
        f"/bookings/{booking1['id']}/messages",
        headers=passenger1["auth"],
        json={"text": "first booking"},
    ).json()
    wrong_booking = client.post(
        f"/bookings/{booking2['id']}/messages/{message['id']}/edit",
        headers=passenger2["auth"],
        json={"text": "wrong booking"},
    )
    assert wrong_booking.status_code == 404


def test_conversations_and_notifications_empty_and_voice_fallback(client, user_factory):
    solo = user_factory("ChatSolo")
    assert client.get("/conversations", headers=solo["auth"]).json() == []
    # Центр уведомлений: типизированная лента {unread, items}; у нового юзера событий нет.
    assert client.get("/notifications", headers=solo["auth"]).json() == {"unread": 0, "items": []}

    driver, passenger, _ride, booking = _trip(client, user_factory, "Voice")
    booking_id = booking["id"]
    fresh_conversations = client.get("/conversations", headers=passenger["auth"]).json()
    assert any(
        item["booking_id"] == booking_id and item["last_message"] == "Чат открыт"
        for item in fresh_conversations
    )
    client.post(
        f"/bookings/{booking_id}/messages",
        headers=passenger["auth"],
        json={"voice_url": "https://yulbash.ru/media/voice/test.ogg"},
    )

    conversations = client.get("/conversations", headers=driver["auth"]).json()
    assert any(item["booking_id"] == booking_id and item["last_message"] for item in conversations)
    # peer_verified — РЕАЛЬНЫЙ статус собеседника, не фейк «проверен» у всех. Помечаем пассажира
    # непроверенным в БД → в инбоксе водителя peer_verified должен стать False (доказывает чтение статуса).
    from app.db import engine as _engine
    from app.models import User as _User
    from sqlmodel import Session as _Session
    with _Session(_engine) as _s:
        pax = _s.get(_User, passenger["id"])
        pax.verified = False
        _s.add(pax); _s.commit()
    conv = next(item for item in client.get("/conversations", headers=driver["auth"]).json()
                if item["booking_id"] == booking_id)
    assert conv["peer_verified"] is False

    driver_notifications = client.get("/notifications", headers=driver["auth"]).json()["items"]
    assert any(item["type"] == "message" and item["body_ru"] for item in driver_notifications)
    passenger_notifications = client.get("/notifications", headers=passenger["auth"]).json()["items"]
    assert all(item["type"] != "message" for item in passenger_notifications)


def test_conversations_ignore_messages_hidden_for_current_user(client, user_factory):
    driver, passenger, _ride, booking = _trip(client, user_factory, "HiddenPreview")
    booking_id = booking["id"]
    text_message = client.post(
        f"/bookings/{booking_id}/messages",
        headers=passenger["auth"],
        json={"text": "hidden only for driver"},
    ).json()
    voice_message = client.post(
        f"/bookings/{booking_id}/messages",
        headers=passenger["auth"],
        json={"voice_url": "https://yulbash.ru/media/voice/test.ogg"},
    ).json()
    client.delete(
        f"/bookings/{booking_id}/messages/{text_message['id']}?scope=me",
        headers=driver["auth"],
    )
    client.delete(
        f"/bookings/{booking_id}/messages/{voice_message['id']}?scope=me",
        headers=driver["auth"],
    )

    driver_conversations = client.get("/conversations", headers=driver["auth"]).json()
    passenger_conversations = client.get("/conversations", headers=passenger["auth"]).json()

    assert any(
        item["booking_id"] == booking_id and item["last_message"] == "Чат открыт"
        for item in driver_conversations
    )
    assert any(
        item["booking_id"] == booking_id and item["last_message"] == "Голосовое"
        for item in passenger_conversations
    )
