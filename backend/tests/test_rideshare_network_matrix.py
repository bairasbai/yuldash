"""Матрица попутки: ответ потерян ПОСЛЕ настоящего HTTP/SQL, затем новый клиент.

Новый httpx.Client теряет память первого клиента, но использует тестовый ASGI-транспорт.
Это проверка повторного HTTP и сверки с БД, а не перезапуск ОС/сервера или PWA outbox.
Подменяются только внешние отправители и часы для окон ожидания, не обработчики команд.
"""
from datetime import timedelta
import base64
from types import SimpleNamespace
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier, Event

import httpx
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import (Booking, Incident, Message, Notification, Rating, Report,
                        RequestResponse, Ride, RideRequest, SosEvent, TripShare, UserRole)
from app.routers import bookings as booking_routes
from app.routers import family as family_routes
from app.timeutil import utcnow


ROWS = ["R01", "R02", "R03", "R04", "Q01", "Q02", "Q03", "O01", "O02", "O03",
        "O04", "O05", "B01", "B02", "B03", "S01", "S02", "S03", "S04", "S05",
        "S06", "S07-arrived", "S07-done", "C01", "C02", "C03-all", "C03-me",
        "F01-passenger", "F01-driver", "L01", "L02", "X02-passenger", "X02-driver",
        "X03", "X04-check", "X04-ok", "X05-report", "X05-incident"]


def _ok(reply):
    assert reply.status_code == 200, reply.text
    return reply.json()


@pytest.fixture
def external_senders(monkeypatch):
    """Реальные строки Notification остаются; наружу не уходит ни одного сообщения."""
    from app import services
    from app.routers import incidents, safety

    effects = []
    monkeypatch.setattr(settings, "quiet_hours_to", 0)
    monkeypatch.setattr(services, "send_push", lambda *a, **kw: effects.append("push"))
    for module in (services, family_routes, safety, incidents):
        for name in ("send_text", "notify_admin_telegram", "_send_sos_sms"):
            if hasattr(module, name):
                monkeypatch.setattr(module, name, lambda *a, **kw: effects.append("external"))
    return effects


def _world(client, user_factory, row, monkeypatch):
    w = SimpleNamespace(client=client,
                        driver=user_factory("NetworkMatrixDriver", role=UserRole.driver),
                        passenger=user_factory("NetworkMatrixPassenger"),
                        ride=None, request=None, booking=None, offer=None, share=None,
                        message=None, row=row, retry=200)
    w.ride_body = {"from_city": "Баймак", "to_city": "Сибай", "seats_total": 3,
                   "price": 300, "depart_at": (utcnow() - timedelta(minutes=1)).isoformat() + "+00:00",
                   "comment": "Проверка сетевого повтора"}
    w.request_body = {"from_city": "Баймак", "to_city": "Сибай", "seats": 1,
                      "max_price": 300, "comment": "Проверка заявки"}
    if row != "R01":
        w.ride = _ok(client.post("/rides", headers=w.driver["auth"], json=w.ride_body))
    if row.startswith("Q") and row != "Q01" or row.startswith("O"):
        w.request = _ok(client.post("/requests", headers=w.passenger["auth"], json=w.request_body))
    if row.startswith("O") and row != "O01" or row == "Q03":
        w.offer = _ok(client.post(f"/requests/{w.request['id']}/respond", headers=w.driver["auth"],
                                 json={"price": 250, "ride_id": w.ride["id"]}))
    needs_booking = not row.startswith(("R", "Q", "O")) and row != "B01"
    if needs_booking:
        w.booking = _ok(client.post("/bookings", headers=w.passenger["auth"],
                                    json={"ride_id": w.ride["id"], "seats": 1}))
        if row != "B02":
            _ok(client.post(f"/bookings/{w.booking['id']}/confirm", headers=w.driver["auth"]))
    if row in {"R03", "R04"}:
        w.booking = _ok(client.post("/bookings", headers=w.passenger["auth"],
                                    json={"ride_id": w.ride["id"], "seats": 1}))
        _ok(client.post(f"/bookings/{w.booking['id']}/confirm", headers=w.driver["auth"]))
    if row == "R02":
        w.booking = _ok(client.post("/bookings", headers=w.passenger["auth"],
                                    json={"ride_id": w.ride["id"], "seats": 1}))
    if row in {"S04", "S07-arrived", "S07-done"}:
        _ok(client.post(f"/bookings/{w.booking['id']}/driver-status", headers=w.driver["auth"],
                        json={"status": "boarded"}))
    if row.startswith("S07"):
        # Посадка проходит настоящую ручку, затем часы сдвигаются за пятиминутное окно.
        later = utcnow() + timedelta(minutes=6)
        monkeypatch.setattr(booking_routes, "utcnow", lambda: later)
        monkeypatch.setattr(family_routes, "utcnow", lambda: later)
    if row == "S05":
        _ok(client.post(f"/bookings/{w.booking['id']}/driver-status", headers=w.driver["auth"],
                        json={"status": "arriving"}))
        later = utcnow() + timedelta(minutes=16)
        monkeypatch.setattr(booking_routes, "utcnow", lambda: later)
    if row.startswith("C") and row != "C01":
        w.message = _ok(client.post(f"/bookings/{w.booking['id']}/messages",
                                    headers=w.passenger["auth"], json={"text": "Сообщение до правки"}))
    if row.startswith(("F01", "X02")):
        _ok(client.post(f"/bookings/{w.booking['id']}/driver-status", headers=w.driver["auth"],
                        json={"status": "done"}))
    if row.startswith(("L", "S07")):
        contact = _ok(client.post("/trusted-contacts", headers=w.passenger["auth"],
                                  json={"name": "Близкий", "relation": "семья", "phone": "+70000000001"}))
        w.contact_id = contact["id"]
        if row != "L01":
            w.share = _ok(client.post(f"/bookings/{w.booking['id']}/share", headers=w.passenger["auth"],
                                      json={"contact_id": w.contact_id}))
    return w


def _action(w):
    row, drv, pax = w.row, w.driver, w.passenger
    rid = w.ride["id"] if w.ride else None
    bid = w.booking["id"] if w.booking else None
    qid = w.request["id"] if w.request else None
    oid = w.offer["id"] if w.offer else None
    method, actor, body, headers = "POST", drv, None, {}
    if row == "R01":
        path, body = "/rides", w.ride_body
    elif row == "R02":
        path, body = f"/rides/{rid}/edit", {"comment": "Уточнённое место встречи"}
    elif row in {"R03", "R04"}:
        path = f"/rides/{rid}/{'cancel' if row == 'R03' else 'complete'}"
    elif row == "Q01":
        path, body, actor = "/requests", w.request_body, pax
    elif row == "Q02":
        path, body, actor = f"/requests/{qid}/edit", {"comment": "Уточнение заявки"}, pax
    elif row == "Q03":
        path, actor = f"/requests/{qid}/cancel", pax
    elif row == "O01":
        path, body = f"/requests/{qid}/respond", {"price": 250, "ride_id": rid}
    elif row.startswith("O"):
        suffix = {"O02": "counter", "O03": "accept", "O04": "decline"}.get(row)
        path = f"/responses/{oid}" + (f"/{suffix}" if suffix else "")
        if row == "O05":
            method = "DELETE"
        else:
            actor = pax
        if row == "O02":
            body, w.retry = {"price": 200, "comment": "Встречное предложение"}, 409
    elif row == "B01":
        path, body, actor = "/bookings", {"ride_id": rid, "seats": 1}, pax
    elif row in {"B02", "B03"}:
        path = f"/bookings/{bid}/{'confirm' if row == 'B02' else 'cancel'}"
        if row == "B03":
            actor, body = pax, {"reason": "changed_mind"}
    elif row in {"S01", "S02", "S03", "S06"}:
        path = f"/bookings/{bid}/driver-status"
        body = {"status": {"S01": "departed", "S02": "arriving", "S03": "boarded", "S06": "done"}[row]}
    elif row == "S04":
        path = f"/bookings/{bid}/boarded/undo"
    elif row == "S05":
        path = f"/bookings/{bid}/no-show"
    elif row.startswith("S07"):
        path, actor, body = f"/bookings/{bid}/trip-status", pax, {"status": row.split("-")[1]}
    elif row == "C01":
        path, actor, body = f"/bookings/{bid}/messages", pax, {"text": "Дождусь у остановки"}
        headers = {"Idempotency-Key": f"network-matrix-{bid}"}
    elif row == "C02":
        path, actor, body = f"/bookings/{bid}/messages/{w.message['id']}/edit", pax, {"text": "Уточнение встречи"}
    elif row.startswith("C03"):
        method, actor = "DELETE", pax
        path = f"/bookings/{bid}/messages/{w.message['id']}?scope={row.split('-')[1]}"
    elif row.startswith("F01"):
        path = f"/bookings/{bid}/rate"
        actor = drv if row.endswith("driver") else pax
        body = {"stars": 5, "tags": "polite", "text": "Спасибо за поездку"}
    elif row == "L01":
        path, actor, body = f"/bookings/{bid}/share", pax, {"contact_id": w.contact_id}
    elif row == "L02":
        method, actor = "DELETE", pax
        path = f"/bookings/{bid}/share/{w.share['id']}"
    elif row.startswith("X02"):
        path, actor = f"/bookings/{bid}/lost-item", drv if row.endswith("driver") else pax
    elif row == "X03":
        path, actor, body = f"/bookings/{bid}/stuck", pax, {"note": "Нужна помощь на трассе"}
        headers = {"Idempotency-Key": f"network-matrix-stuck-{bid}"}
    elif row.startswith("X04"):
        path, actor = f"/bookings/{bid}/winter-check" + ("/ok" if row.endswith("ok") else ""), pax
    elif row == "X05-report":
        path, actor, body = "/reports", pax, {"booking_id": bid, "category": "rude", "reason": "Грубость в поездке"}
    elif row == "X05-incident":
        path, actor, body = "/incidents", pax, {"booking_id": bid, "respondent_id": drv["id"],
                                               "type": "rude", "description": "Прошу разобрать ситуацию"}
    else:
        raise AssertionError(row)
    return method, path, {**actor["auth"], **headers}, body


def _snapshot(w):
    """Только данные созданных участников; ни паролей, ни телефонов/токенов в diff."""
    with Session(engine) as session:
        def rows(model, where):
            return session.exec(select(model).where(where).order_by(model.id)).all()

        bookings = rows(Booking, Booking.passenger_id == w.passenger["id"])
        bids = [b.id for b in bookings]
        result = {}
        queries = [rows(Ride, Ride.driver_id == w.driver["id"]),
                   rows(RideRequest, RideRequest.passenger_id == w.passenger["id"]),
                   rows(RequestResponse, RequestResponse.driver_id == w.driver["id"]), bookings,
                   rows(Message, Message.booking_id.in_(bids)),
                   rows(Rating, Rating.booking_id.in_(bids)),
                   rows(TripShare, TripShare.booking_id.in_(bids)),
                   rows(SosEvent, SosEvent.user_id == w.passenger["id"]),
                   rows(Report, Report.reporter_id == w.passenger["id"]),
                   rows(Incident, Incident.reporter_id == w.passenger["id"]),
                   rows(Notification, Notification.user_id.in_([w.driver["id"], w.passenger["id"]]))]
        for objects in queries:
            for obj in objects:
                record = obj.model_dump(mode="json")
                # У ссылки API намеренно продлевает срок; идентичность и статус сохраняются.
                for private in ("token", "expires_at"):
                    record.pop(private, None)
                result[f"{type(obj).__name__}:{obj.id}"] = record
        return result


def _reconcile(fresh, w):
    """Все результаты перечитываются по настоящим GET, а не из памяти потерянного ответа."""
    rides = _ok(fresh.get("/driver/rides?status=all", headers=w.driver["auth"]))
    requests = _ok(fresh.get("/requests/mine", headers=w.passenger["auth"]))
    bookings = _ok(fresh.get("/bookings/mine", headers=w.passenger["auth"]))
    with Session(engine) as session:
        expected_rides = session.exec(select(Ride).where(Ride.driver_id == w.driver["id"])).all()
        expected_requests = session.exec(select(RideRequest).where(RideRequest.passenger_id == w.passenger["id"])).all()
        expected_bookings = session.exec(select(Booking).where(Booking.passenger_id == w.passenger["id"])).all()
    for actual, expected in ((rides, expected_rides), (requests, expected_requests)):
        for obj in expected:
            got = next(x for x in actual if x["id"] == obj.id)
            assert got["status"] == obj.status
            for field in ("comment", "price", "max_price", "seats", "seats_total", "seats_left"):
                if hasattr(obj, field):
                    assert got[field] == getattr(obj, field)
    for obj in expected_bookings:
        got = next(x for x in bookings if x["id"] == obj.id)
        assert got["status"] == obj.status
        assert got["price"] == obj.price and got["seats"] == obj.seats
    if w.row.startswith("C"):
        messages = _ok(fresh.get(f"/bookings/{w.booking['id']}/messages", headers=w.passenger["auth"]))
        assert isinstance(messages, list)
        if w.row == "C01":
            assert len([x for x in messages if x["text"] == "Дождусь у остановки"]) == 1
        if w.row == "C02":
            assert any(x["text"] == "Уточнение встречи" for x in messages)
    if w.row.startswith("L"):
        shares = _ok(fresh.get(f"/bookings/{w.booking['id']}/shares", headers=w.passenger["auth"]))
        assert len(shares) == (1 if w.row == "L01" else 0)
    if w.row.startswith("O"):
        offers = _ok(fresh.get(f"/requests/{w.request['id']}/responses", headers=w.passenger["auth"]))
        assert len(offers) <= 1


@pytest.mark.parametrize("row", ROWS)
def test_committed_reply_lost_then_manual_retry_and_fresh_client_reconcile(
        client, user_factory, monkeypatch, external_senders, row):
    w = _world(client, user_factory, row, monkeypatch)
    method, path, headers, body = _action(w)
    real_transport = client._transport.handle_request
    dropped = False

    def drop_committed_reply(request):
        nonlocal dropped
        reply = real_transport(request)
        if not dropped and request.method == method and request.url.raw_path.decode() == path:
            assert reply.status_code == 200, reply.read().decode()
            dropped = True
            reply.close()
            raise httpx.ReadError("Synthetic reply lost after server commit", request=request)
        return reply

    monkeypatch.setattr(client._transport, "handle_request", drop_committed_reply)
    with pytest.raises(httpx.ReadError, match="after server commit"):
        client.request(method, path, headers=headers, json=body)
    assert dropped
    committed = _snapshot(w)
    sent = list(external_senders)
    fresh = httpx.Client(transport=client._transport, base_url=client.base_url)
    try:
        _reconcile(fresh, w)
        retry = fresh.request(method, path, headers=headers, json=body)
        assert retry.status_code == w.retry, retry.text
        assert _snapshot(w) == committed
        assert external_senders == sent
        _reconcile(fresh, w)
    finally:
        fresh.close()


def test_lost_item_active_retry_at_maximum_preserves_deadline_then_expired_limit_is_refused(
        client, user_factory, monkeypatch, external_senders):
    w = _world(client, user_factory, "X02-passenger", monkeypatch)
    method, path, headers, body = _action(w)
    base = utcnow()
    for i in range(booking_routes.LOST_ITEM_MAX_CALLS):
        current = base + timedelta(days=3 * i)
        monkeypatch.setattr(booking_routes, "utcnow", lambda: current)
        _ok(client.request(method, path, headers=headers, json=body))
    before = _snapshot(w)
    sent = list(external_senders)
    first_retry = _ok(client.request(method, path, headers=headers, json=body))
    second_retry = _ok(client.request(method, path, headers=headers, json=body))
    assert first_retry == second_retry
    assert _snapshot(w) == before and external_senders == sent
    monkeypatch.setattr(booking_routes, "utcnow", lambda: base + timedelta(days=10))
    assert client.request(method, path, headers=headers, json=body).status_code == 409
    assert _snapshot(w) == before


@pytest.mark.parametrize("role", ["stranger", "other_driver"])
def test_lost_item_retry_does_not_grant_another_user_access(
        client, user_factory, monkeypatch, external_senders, role):
    w = _world(client, user_factory, "X02-passenger", monkeypatch)
    method, path, headers, body = _action(w)
    _ok(client.request(method, path, headers=headers, json=body))
    before = _snapshot(w)
    sent = list(external_senders)
    outsider = user_factory("OtherNetworkMatrixUser", role=UserRole.driver if role == "other_driver" else UserRole.passenger)
    assert client.request(method, path, headers=outsider["auth"], json=body).status_code == 403
    assert _snapshot(w) == before and external_senders == sent


def test_lost_item_keeps_completion_and_age_guards_before_active_retry(
        client, user_factory, monkeypatch, external_senders):
    w = _world(client, user_factory, "X04-check", monkeypatch)
    path = f"/bookings/{w.booking['id']}/lost-item"
    before = _snapshot(w)
    assert client.post(path, headers=w.passenger["auth"]).status_code == 409
    assert _snapshot(w) == before
    _ok(client.post(f"/bookings/{w.booking['id']}/driver-status", headers=w.driver["auth"], json={"status": "done"}))
    base = utcnow()
    monkeypatch.setattr(booking_routes, "utcnow", lambda: base + timedelta(days=29))
    _ok(client.post(path, headers=w.passenger["auth"]))
    before = _snapshot(w)
    sent = list(external_senders)
    # Срок поиска 30 дней закончился, хотя открытый на 29-й день чат ещё активен.
    monkeypatch.setattr(booking_routes, "utcnow", lambda: base + booking_routes.LOST_ITEM_WINDOW + timedelta(minutes=2))
    assert client.post(path, headers=w.passenger["auth"]).status_code == 409
    assert _snapshot(w) == before and external_senders == sent


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Real PostgreSQL concurrent writers required")
def test_concurrent_preloaded_lost_item_calls_open_chat_once(
        client, user_factory, monkeypatch, external_senders):
    w = _world(client, user_factory, "X02-passenger", monkeypatch)
    original = booking_routes.booking_and_ride_for_user
    barrier = Barrier(2)

    def both_read(session, booking_id, user):
        result = original(session, booking_id, user)
        if booking_id == w.booking["id"]:
            barrier.wait(timeout=10)
        return result

    monkeypatch.setattr(booking_routes, "booking_and_ride_for_user", both_read)
    before = len(external_senders)
    method, path, headers, body = _action(w)
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(client.request, method, path, headers=headers, json=body) for _ in range(2)]
        replies = [_ok(f.result(timeout=20)) for f in futures]
    assert replies[0] == replies[1]
    with Session(engine) as session:
        booking = session.get(Booking, w.booking["id"])
        assert booking.lost_item_calls == 1
        notes = session.exec(select(Notification).where(Notification.ref_kind == "booking",
                            Notification.ref_id == booking.id, Notification.title_ru == "Забытая вещь")).all()
        assert len(notes) == 1
    assert len(external_senders) == before + 1


@pytest.mark.parametrize("kind", ["C04-photo", "C05-voice"])
def test_upload_reply_lost_requires_new_upload_but_keyed_message_retry_stays_single(
        client, user_factory, monkeypatch, external_senders, tmp_path, kind):
    """У upload нет ключа: потерянный URL не восстановить. Два файла, одно сообщение.

    Для voice ключ задан здесь явно; наличие такого ключа у PWA этот тест не доказывает.
    """
    from app import storage
    from app.routers import discovery

    monkeypatch.setattr(storage, "MEDIA_DIR", str(tmp_path))
    actual_storage = storage.LocalStorage()
    monkeypatch.setattr(discovery, "get_storage", lambda: actual_storage)
    w = _world(client, user_factory, "C01", monkeypatch)
    is_photo = kind == "C04-photo"
    upload_path = "/upload/chat-photo" if is_photo else "/voice"
    png = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jY6kAAAAASUVORK5CYII=")
    file_args = ("fixture.png", png, "image/png") if is_photo else ("fixture.m4a", b"\0" * 32, "audio/mp4")
    real_transport = client._transport.handle_request
    lost = set()
    message_path = f"/bookings/{w.booking['id']}/messages"

    def drop_committed(request):
        reply = real_transport(request)
        if request.method == "POST" and request.url.path in {upload_path, message_path} and request.url.path not in lost:
            assert reply.status_code == 200, reply.read().decode()
            lost.add(request.url.path)
            reply.close()
            raise httpx.ReadError("Synthetic reply lost after server commit", request=request)
        return reply

    monkeypatch.setattr(client._transport, "handle_request", drop_committed)
    with pytest.raises(httpx.ReadError, match="after server commit"):
        client.post(upload_path, headers=w.passenger["auth"], files={"file": file_args})
    assert len([p for p in tmp_path.rglob("*") if p.is_file()]) == 1
    fresh = httpx.Client(transport=client._transport, base_url=client.base_url)
    try:
        uploaded = _ok(fresh.post(upload_path, headers=w.passenger["auth"], files={"file": file_args}))
        assert len([p for p in tmp_path.rglob("*") if p.is_file()]) == 2
        body = {"text": "[img]" + uploaded["url"]} if is_photo else {"voice_url": uploaded["url"], "text": ""}
        headers = {**w.passenger["auth"], "Idempotency-Key": f"matrix-upload-{w.booking['id']}"}
        with pytest.raises(httpx.ReadError, match="after server commit"):
            fresh.post(message_path, headers=headers, json=body)
        committed = _snapshot(w)
        effects = list(external_senders)
        message = _ok(fresh.post(message_path, headers=headers, json=body))
        assert _snapshot(w) == committed and external_senders == effects
        messages = _ok(fresh.get(message_path, headers=w.passenger["auth"]))
        assert [m["id"] for m in messages] == [message["id"]]
    finally:
        fresh.close()


def test_location_disconnect_reconnect_forwards_current_frame_then_terminal_blocks_reconnect(
        client, user_factory, monkeypatch, external_senders):
    """GPS — живой канал, старую точку после переподключения не отправляем очередью."""
    w = _world(client, user_factory, "S01", monkeypatch)
    _ok(client.post(f"/bookings/{w.booking['id']}/driver-status", headers=w.driver["auth"], json={"status": "departed"}))
    path = f"/ws/trip/{w.booking['id']}/location"
    with client.websocket_connect(path) as driver_ws:
        driver_ws.send_json({"type": "auth", "token": w.driver["token"]})
        with client.websocket_connect(path) as passenger_ws:
            passenger_ws.send_json({"type": "auth", "token": w.passenger["token"]})
            driver_ws.send_json({"type": "loc", "lat": 54.01, "lng": 58.02, "ts": 1})
            assert passenger_ws.receive_json()["ts"] == 1
        with client.websocket_connect(path) as reconnected:
            reconnected.send_json({"type": "auth", "token": w.passenger["token"]})
            driver_ws.send_json({"type": "loc", "lat": 54.01001, "lng": 58.02001, "ts": 2})
            assert reconnected.receive_json()["ts"] == 2
    _ok(client.post(f"/bookings/{w.booking['id']}/cancel", headers=w.passenger["auth"], json={"reason": "changed_mind"}))
    with client.websocket_connect(path) as terminal:
        terminal.send_json({"type": "auth", "token": w.passenger["token"]})
        assert terminal.receive() == {"type": "websocket.close", "code": 1008, "reason": "Trip ended"}


@pytest.mark.parametrize("state", ["cancelled-driver", "cancelled-passenger", "undone", "pending"])
@pytest.mark.parametrize("status", ["arrived", "done"])
def test_late_queued_passenger_status_cannot_reopen_family_after_cancel_or_without_boarding(
        client, user_factory, monkeypatch, external_senders, state, status):
    row = "B02" if state == "pending" else "L02" if state == "cancelled-passenger" else "S07-done"
    w = _world(client, user_factory, row, monkeypatch)
    if state == "pending":
        contact = _ok(client.post("/trusted-contacts", headers=w.passenger["auth"],
                                  json={"name": "Близкий", "relation": "семья", "phone": "+70000000001"}))
        _ok(client.post(f"/bookings/{w.booking['id']}/share", headers=w.passenger["auth"],
                        json={"contact_id": contact["id"]}))
    elif state == "undone":
        # Водитель исправляет ошибочную посадку настоящим HTTP в разрешённые5минут.
        monkeypatch.setattr(booking_routes, "utcnow", utcnow)
        _ok(client.post(f"/bookings/{w.booking['id']}/boarded/undo", headers=w.driver["auth"]))
    else:
        actor = w.driver if state == "cancelled-driver" else w.passenger
        _ok(client.post(f"/bookings/{w.booking['id']}/cancel", headers=actor["auth"], json={"reason": "changed_mind"}))
    before, sent = _snapshot(w), list(external_senders)
    for _ in range(2):
        response = client.post(f"/bookings/{w.booking['id']}/trip-status", headers=w.passenger["auth"], json={"status": status})
        assert response.status_code == 409, response.text
        assert response.json()["detail"]["ru"] and response.json()["detail"]["ba"]
        assert _snapshot(w) == before and external_senders == sent


@pytest.mark.parametrize("status", ["arrived", "done"])
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Real PostgreSQL stale-reader race required")
def test_preloaded_passenger_family_status_cannot_publish_after_driver_cancel_commits(
        client, user_factory, monkeypatch, external_senders, status):
    w = _world(client, user_factory, "S07-done", monkeypatch)
    original = family_routes.booking_and_ride_for_user
    loaded, release = Event(), Event()

    def hold(session, booking_id, user):
        result = original(session, booking_id, user)
        if booking_id == w.booking["id"] and user.id == w.passenger["id"]:
            loaded.set()
            assert release.wait(timeout=10), "held family request was not released"
        return result

    monkeypatch.setattr(family_routes, "booking_and_ride_for_user", hold)
    with ThreadPoolExecutor(max_workers=1) as pool:
        future = pool.submit(client.post, f"/bookings/{w.booking['id']}/trip-status",
                             headers=w.passenger["auth"], json={"status": status})
        try:
            assert loaded.wait(timeout=10), "family request did not preload booking"
            _ok(client.post(f"/bookings/{w.booking['id']}/cancel", headers=w.driver["auth"], json={"reason": "changed_mind"}))
            before, sent = _snapshot(w), list(external_senders)
        finally:
            release.set()
        response = future.result(timeout=20)
    assert response.status_code == 409, response.text
    assert _snapshot(w) == before and external_senders == sent
