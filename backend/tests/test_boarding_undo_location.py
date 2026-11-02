"""Исправленная посадка завершает старую GPS-эпоху, сохраняя остальные брони/новые каналы."""
import asyncio
import json
import threading
from datetime import timedelta

import pytest

from app.db import engine
from app.routers import bookings as booking_routes, location
from app import instant_service
from test_boarding_undo_consent import _answer, _asked, _request
from test_driver_marks_boarding import _board, _booking, _trip


class _RealLocationFrames:
    """Только наблюдение за настоящими broadcast/ASGI close; решения gate не подменены.

    Событие close записывается ПОСЛЕ await настоящего WebSocket.close: receive уже не висит.
    Все ожидания ограничены 5 сек; собственных фоновых потоков fixture не создаёт.
    """

    def __init__(self, monkeypatch, *, hold_passenger_feed=False):
        import fakeredis

        monkeypatch.setattr(instant_service, "_redis_override", fakeredis.FakeStrictRedis(decode_responses=True))
        monkeypatch.setattr(location, "CAR_FEED_SEC", 0.05)
        # Барьер только откладывает настоящий feed до окончания HTTP-цикла. Сам gate и
        # _машина_пассажиру после барьера работают без подмены результата/данных из базы.
        self.resume_feed = threading.Event()
        if not hold_passenger_feed:
            self.resume_feed.set()
        feed = location._машина_пассажиру

        async def paused_feed(socket, bid, gate):
            while not self.resume_feed.is_set():
                await asyncio.sleep(0.01)
            await feed(socket, bid, gate)

        monkeypatch.setattr(location, "_машина_пассажиру", paused_feed)
        self._changed = threading.Condition()
        self._frames = set()
        self._closes = []
        broadcast = location.manager.broadcast
        close = location.WebSocket.close

        async def observed_broadcast(key, payload):
            await broadcast(key, payload)
            if payload.get("type") == "loc":
                with self._changed:
                    self._frames.add((key, payload.get("role"), payload.get("ts")))
                    self._changed.notify_all()

        async def observed_close(socket, code=1000, reason=None):
            await close(socket, code=code, reason=reason)
            with self._changed:
                self._closes.append((socket.scope["path"], code, reason))
                self._changed.notify_all()

        monkeypatch.setattr(location.manager, "broadcast", observed_broadcast)
        monkeypatch.setattr(location.WebSocket, "close", observed_close)

    @staticmethod
    def _key(bid, role, ts):
        return (-bid * 2 - (role == "driver"), role, ts)

    def frame(self, ws, bid, role, ts):
        ws.send_text(json.dumps({"type": "loc", "lat": 52.7, "lng": 58.6, "ts": ts}))
        with self._changed:
            assert self._changed.wait_for(lambda: self._key(bid, role, ts) in self._frames, timeout=5), (
                f"реальный WS не пропустил контрольный кадр {role}/{ts} брони {bid}"
            )

    def corrected_close(self, ws, bid, ts, *, send=True):
        if send:
            ws.send_text(json.dumps({"type": "loc", "lat": 52.7, "lng": 58.6, "ts": ts}))
        path = f"/ws/trip/{bid}/location"
        with self._changed:
            assert self._changed.wait_for(lambda: any(item[0] == path for item in self._closes), timeout=5), (
                "старый WS принял новую onboard-эпоху после undo→consent→reboard между кадрами"
            )
        assert ws.receive() == {"type": "websocket.close", "code": 1008, "reason": "Boarding corrected"}

    def not_closed(self, bid):
        with self._changed:
            assert not any(item[0] == f"/ws/trip/{bid}/location" for item in self._closes)


def _accepted_undo_then_reboard(client, drv, pax, bid, monkeypatch):
    """Все три перехода — реальные HTTP. Часы задают разным посадкам разные метки без sleep/SQL."""
    first = _booking(bid).onboard_at
    assert first is not None
    asked = _request(client, drv, bid)
    assert asked.status_code == 200, asked.text
    assert asked.json()["status"] == "onboard" and asked.json()["boarding_undo_pending"] is True
    answer = _answer(client, pax, bid, asked.json()["boarding_undo_request_id"])
    assert answer.status_code == 200, answer.text
    assert answer.json()["status"] == "confirmed" and _booking(bid).onboard_at is None
    with monkeypatch.context() as clock:
        clock.setattr(booking_routes, "utcnow", lambda: first + timedelta(seconds=1))
        boarded = _board(client, drv, bid)
    assert boarded.status_code == 200, boarded.text
    second = _booking(bid)
    assert second.status == "onboard" and second.onboard_at == first + timedelta(seconds=1)
    assert second.onboard_at != first and second.boarding_undo_request_id is None


@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_real_old_ws_rejects_reboard_epoch_when_accepted_undo_was_between_frames(
        client, user_factory, monkeypatch, role):
    drv, pax, _ride, bid = _trip(client, user_factory, f"epoch-reboard-{role}")
    assert _board(client, drv, bid).status_code == 200
    first = _booking(bid).onboard_at
    probe = _RealLocationFrames(monkeypatch, hold_passenger_feed=True)
    actor = drv if role == "driver" else pax
    with client.websocket_connect(f"/ws/trip/{bid}/location") as old:
        old.send_text(json.dumps({"type": "auth", "token": actor["token"]}))
        probe.frame(old, bid, role, 101)
        # Broadcast возможен только после настоящего gate: канал уже обработал onboard1.
        assert _booking(bid).onboard_at == first
        _accepted_undo_then_reboard(client, drv, pax, bid, monkeypatch)
        # Между проверками не было ни прямого gate, ни WS-кадра/feed в confirmed.
        # Пассажир после цикла только СЛУШАЕТ: старую эпоху обязан закрыть настоящий feed.
        probe.resume_feed.set()
        probe.corrected_close(old, bid, 102, send=role == "driver")


@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_real_confirmed_ws_accepts_first_boarding_without_epoch_close(client, user_factory, monkeypatch, role):
    drv, pax, _ride, bid = _trip(client, user_factory, f"epoch-first-{role}")
    probe = _RealLocationFrames(monkeypatch)
    actor = drv if role == "driver" else pax
    with client.websocket_connect(f"/ws/trip/{bid}/location") as ws:
        ws.send_text(json.dumps({"type": "auth", "token": actor["token"]}))
        probe.frame(ws, bid, role, 201)
        assert _booking(bid).onboard_at is None
        boarded = _board(client, drv, bid)
        assert boarded.status_code == 200, boarded.text
        assert _booking(bid).onboard_at is not None
        probe.frame(ws, bid, role, 202)
        probe.not_closed(bid)


@pytest.mark.parametrize("role", ["driver", "passenger"])
def test_real_fresh_reboard_ws_and_existing_other_booking_keep_working(client, user_factory, monkeypatch, role):
    drv, pax, ride, bid = _trip(client, user_factory, f"epoch-fresh-{role}")
    assert _board(client, drv, bid).status_code == 200
    other = user_factory(f"epoch-other-{role}")
    made = client.post("/bookings", headers=other["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert made.status_code == 200, made.text
    second = made.json()["id"]
    assert client.post(f"/bookings/{second}/confirm", headers=drv["auth"]).status_code == 200
    assert _board(client, drv, second).status_code == 200
    probe = _RealLocationFrames(monkeypatch)
    actor = drv if role == "driver" else pax
    with client.websocket_connect(f"/ws/trip/{second}/location") as unchanged:
        unchanged.send_text(json.dumps({"type": "auth", "token": drv["token"]}))
        probe.frame(unchanged, second, "driver", 301)
        _accepted_undo_then_reboard(client, drv, pax, bid, monkeypatch)
        probe.frame(unchanged, second, "driver", 302)
        probe.not_closed(second)
        with client.websocket_connect(f"/ws/trip/{bid}/location") as fresh:
            fresh.send_text(json.dumps({"type": "auth", "token": actor["token"]}))
            probe.frame(fresh, bid, role, 303)
            probe.not_closed(bid)


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
