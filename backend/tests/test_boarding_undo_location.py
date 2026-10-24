"""Исправленная посадка завершает старую GPS-эпоху, сохраняя остальные брони/новые каналы."""
import json
import threading
from datetime import timedelta

import pytest

from app.db import engine
from app.routers import location
from app import instant_service
from test_boarding_undo_consent import _answer, _asked
from test_driver_marks_boarding import _board, _booking


@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_only_corrected_location_channel_closes_with_a_distinct_reason(client, user_factory, monkeypatch, role):
    import fakeredis
    monkeypatch.setattr(instant_service, "_redis_override", fakeredis.FakeStrictRedis(decode_responses=True))
    drv, pax, _ride, bid, token = _asked(client, user_factory, f"ws-{role}")
    actor = drv if role == "driver" else pax
    observed = threading.Event()
    real = location._Пора.наступила

    def gate(self):
        result = real(self)
        if self._id == bid and result:
            observed.set()
        return result

    monkeypatch.setattr(location._Пора, "наступила", gate)
    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": actor["token"]}))
        ws.send_text(json.dumps({"type": "loc", "lat": 52.7, "lng": 58.6, "ts": 1}))
        assert observed.wait(5), "исходный onboard-канал не обработал контрольный кадр"
        response = _answer(client, pax, bid, token)
        assert response.status_code == 200, response.text
        ws.send_text(json.dumps({"type": "loc", "lat": 52.7, "lng": 58.6, "ts": 2}))
        message = ws.receive()
        # В пассажирском канале до отмены мог прийти свой разрешённый кадр машины; читаем до close.
        if message["type"] == "websocket.send":
            message = ws.receive()
        assert message == {"type": "websocket.close", "code": 1008, "reason": "Boarding corrected"}
    assert _booking(bid).status == "confirmed"


def test_fresh_confirmed_channel_and_other_booking_are_not_terminated(client, user_factory):
    drv, pax, ride, bid, token = _asked(client, user_factory, "other")
    other = user_factory("consent-other-passenger")
    made = client.post("/bookings", headers=other["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert made.status_code == 200, made.text
    second = made.json()["id"]
    assert client.post(f"/bookings/{second}/confirm", headers=drv["auth"]).status_code == 200
    assert _board(client, drv, second).status_code == 200
    a = location._Пора(bid, drv["id"])
    b = location._Пора(second, drv["id"])
    assert a.наступила() and b.наступила()
    assert _answer(client, pax, bid, token).status_code == 200
    assert a.наступила() is False and a.причина == "Boarding corrected"
    assert b.наступила() is True and b.кончилась is False
    fresh = location._Пора(bid, drv["id"])
    assert fresh.наступила() is True and fresh.кончилась is False


def test_initial_onboard_epoch_is_checked_even_before_first_location_frame(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "before-frame")
    mark = _booking(bid).onboard_at
    old = location._Пора(bid, drv["id"], initial_onboard_at=mark)
    different = location._Пора(bid, drv["id"], initial_onboard_at=mark - timedelta(seconds=1))
    assert _answer(client, pax, bid, token).status_code == 200
    assert old.наступила() is False and old.причина == "Boarding corrected"
    assert different.наступила() is True and different.кончилась is False


def test_request_and_refusal_do_not_close_onboard_channel(client, user_factory):
    drv, pax, _ride, bid, token = _asked(client, user_factory, "refusal")
    channel = location._Пора(bid, drv["id"])
    assert channel.наступила() is True
    assert _answer(client, pax, bid, token, False).status_code == 200
    assert channel.наступила() is True and channel.кончилась is False


def test_short_onboard_epoch_between_frames_still_refreshes_old_confirmed_channel(client, user_factory):
    from test_driver_marks_boarding import _trip
    from test_boarding_undo_consent import _request

    drv, pax, _ride, bid = _trip(client, user_factory, "consent-between-frames")
    channel = location._Пора(bid, drv["id"], initial_undo_id=None)
    assert channel.наступила() is True
    assert _board(client, drv, bid).status_code == 200
    token = _request(client, drv, bid).json()["boarding_undo_request_id"]
    assert _answer(client, pax, bid, token).status_code == 200
    assert channel.наступила() is False and channel.причина == "Boarding corrected"
    # Новый сокет авторизован уже после исправления: исходный receipt тот же, петли закрытий нет.
    fresh = location._Пора(bid, drv["id"], initial_undo_id=token)
    assert fresh.наступила() is True and fresh.кончилась is False


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="настоящая гонка row-lock — PostgreSQL")
def test_confirmation_and_finish_preserve_terminal_state_and_channel_on_pg(client, user_factory):
    from concurrent.futures import ThreadPoolExecutor
    from sqlalchemy import event

    drv, pax, _ride, bid, token = _asked(client, user_factory, "finish-race")
    old = location._Пора(bid, drv["id"], initial_onboard_at=_booking(bid).onboard_at)
    assert old.наступила() is True
    barrier = threading.Barrier(2, timeout=10)

    def together(conn, cursor, statement, params, context, many):
        if statement.startswith("UPDATE booking SET"):
            barrier.wait()

    event.listen(engine, "before_cursor_execute", together)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            yes = pool.submit(_answer, client, pax, bid, token)
            done = pool.submit(client.post, f"/bookings/{bid}/driver-status", headers=drv["auth"], json={"status": "done"})
            answer, finish = yes.result(timeout=20), done.result(timeout=20)
    finally:
        event.remove(engine, "before_cursor_execute", together)
    assert answer.status_code in (200, 409) and finish.status_code == 200
    assert _booking(bid).status == "done", "согласие не должно воскресить завершённую поездку"
    assert old.наступила() is False and old.кончилась is True
    assert old.причина == "Trip ended"
