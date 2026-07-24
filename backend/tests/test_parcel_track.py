"""G1 «Трекинг-ссылка посылки получателю»: POST /parcels/{id}/track-link + публичная /t/{token}.

Покрываем: отправитель получает токен + SMS получателю; статусы посылки → упрощённые фазы
(created→searching, accepted→accepted, in_transit→onway); позиция курьера из livepos отдаётся
получателю; после вручения — «доставлено» БЕЗ координат; kind=parcel в state; чужой отправитель
не шарит (IDOR 404); завершённую посылку не шарим (409); телефоны не текут; невалидный токен 404.
"""
from app.livepos import livepos_set

from test_parcels import _create_parcel
from test_instant import fake_redis  # noqa: F401 — fixture реэкспорт (даёт livepos-кэш fakeredis)


def _track_link(client, sender, pid):
    r = client.post(f"/parcels/{pid}/track-link", headers=sender["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def _accept(client, courier, pid):
    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 200, r.text
    return r.json()


def _move(client, courier, pid, status, code=None):
    body = {"status": status}
    if code:
        body["code"] = code
    return client.post(f"/parcels/{pid}/status", headers=courier["auth"], json=body)


# ============================ Токен + SMS получателю ============================
def test_track_link_returns_token_and_sms(client, user_factory, monkeypatch):
    """track-link выдаёт случайный токен (≥16 байт → ≥22 симв.), url на /t/{token},
    получателю (receiver_phone) уходит SMS со ссылкой. Дедуп: повтор — тот же токен."""
    sent = []
    from app.routers import family as family_router
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: sent.append((phone, text)))
    sender = user_factory(name="Отправитель")
    pid = _create_parcel(client, sender, receiver_phone="+79990002001").json()["id"]

    out = _track_link(client, sender, pid)
    token = out["token"]
    assert token and len(token) >= 22
    assert out["url"] == f"https://yulbash.ru/t/{token}"
    assert out["sms_sent"] is True
    assert len(sent) == 1 and f"/t/{token}" in sent[0][1]
    assert sent[0][0] == "+79990002001"

    # Дедуп: повторный track-link не плодит токены (ссылка у получателя не протухает).
    again = _track_link(client, sender, pid)
    assert again["token"] == token


def test_track_link_no_phone_still_returns_link(client, user_factory):
    """Нет телефона получателя → SMS не шлём (sms_sent=false), но ссылку отдаём (отправитель отдаст сам)."""
    sender = user_factory(name="БезТел")
    pid = _create_parcel(client, sender, receiver_phone="").json()["id"]
    out = _track_link(client, sender, pid)
    assert out["token"] and out["sms_sent"] is False


# ============================ Статусы → фазы + kind ============================
def test_state_phases_progress(client, user_factory, fake_redis):
    """created→searching, accepted→accepted, in_transit→onway; kind всегда parcel."""
    sender = user_factory(name="ФазаОтпр")
    courier = user_factory(name="ФазаКурьер")
    p = _create_parcel(client, sender, from_city="Сибай", to_city="Уфа").json()
    pid, code = p["id"], p["confirm_code"]
    token = _track_link(client, sender, pid)["token"]

    st = client.get(f"/t/{token}/state.json").json()
    assert st["kind"] == "parcel"
    assert st["status"] == "searching"
    assert st["phase_text"]["ru"] == "Ищем курьера…"
    assert st["from"]["text"] == "Сибай" and st["to"]["text"] == "Уфа"

    _accept(client, courier, pid)
    st = client.get(f"/t/{token}/state.json").json()
    assert st["status"] == "accepted" and st["kind"] == "parcel"

    assert _move(client, courier, pid, "in_transit").status_code == 200
    st = client.get(f"/t/{token}/state.json").json()
    assert st["status"] == "onway"
    assert st["phase_text"]["ru"] == "Посылка в пути"


def test_courier_position_served_to_recipient(client, user_factory, fake_redis):
    """Позиция курьера (livepos «parcel») отдаётся получателю в state.json, пока курьер везёт."""
    sender = user_factory(name="ГеоОтпр")
    courier = user_factory(name="ГеоКурьер")
    pid = _create_parcel(client, sender).json()["id"]
    token = _track_link(client, sender, pid)["token"]
    _accept(client, courier, pid)
    _move(client, courier, pid, "in_transit")

    # Курьер стримит гео → кэш (в проде это делает WS location.py; тут пишем напрямую в fakeredis).
    livepos_set("parcel", pid, 53.10, 56.20, 90)
    body = client.get(f"/t/{token}/state.json").json()
    assert body["car"] == {"lat": 53.10, "lng": 56.20, "bearing": 90}


def test_no_car_before_courier(client, user_factory, fake_redis):
    """Пока курьера нет (created) — машины нет, даже если в кэше что-то лежит от прошлого."""
    sender = user_factory(name="НетКурьера")
    pid = _create_parcel(client, sender).json()["id"]
    token = _track_link(client, sender, pid)["token"]
    livepos_set("parcel", pid, 1.0, 2.0)          # мусор в кэше
    body = client.get(f"/t/{token}/state.json").json()
    assert body["status"] == "searching" and body["car"] is None   # created не в _PARCEL_LIVE_CAR


# ============================ Вручение — БЕЗ координат ============================
def test_delivered_hides_coordinates(client, user_factory, fake_redis):
    """После вручения: status=finished, car=None, координат маршрута в ответе нет."""
    sender = user_factory(name="ДостОтпр")
    courier = user_factory(name="ДостКурьер")
    p = _create_parcel(client, sender).json()
    pid, code = p["id"], p["confirm_code"]
    token = _track_link(client, sender, pid)["token"]
    _accept(client, courier, pid)
    _move(client, courier, pid, "in_transit")
    livepos_set("parcel", pid, 53.0, 56.0)
    assert _move(client, courier, pid, "delivered", code=code).status_code == 200

    body = client.get(f"/t/{token}/state.json").json()
    assert body["status"] == "finished"
    assert body["car"] is None
    assert body["phase_text"]["ru"] == "Посылка доставлена ✅"
    assert "from" not in body and "to" not in body   # маршрут после доставки не отдаём


# ============================ Приватность / доступ ============================
def test_only_sender_can_make_link(client, user_factory):
    """Не отправитель → 404 (чужие посылки не раскрываем, IDOR закрыт)."""
    sender = user_factory(name="Хозяин")
    stranger = user_factory(name="Чужой")
    pid = _create_parcel(client, sender).json()["id"]
    r = client.post(f"/parcels/{pid}/track-link", headers=stranger["auth"])
    assert r.status_code == 404


def test_finished_parcel_not_shareable(client, user_factory):
    """Завершённую посылку не шарим (следить не за чем) → 409."""
    sender = user_factory(name="ЗавОтпр")
    courier = user_factory(name="ЗавКурьер")
    p = _create_parcel(client, sender).json()
    pid, code = p["id"], p["confirm_code"]
    _accept(client, courier, pid)
    _move(client, courier, pid, "in_transit")
    _move(client, courier, pid, "delivered", code=code)
    r = client.post(f"/parcels/{pid}/track-link", headers=sender["auth"])
    assert r.status_code == 409


def test_no_phone_leak_in_state(client, user_factory, fake_redis):
    """Телефон получателя (и отправителя) не течёт в публичный state.json."""
    sender = user_factory(name="Утечка")
    courier = user_factory(name="УтечкаКур")
    pid = _create_parcel(client, sender, receiver_phone="+79995558877").json()["id"]
    token = _track_link(client, sender, pid)["token"]
    _accept(client, courier, pid)
    raw = client.get(f"/t/{token}/state.json").text
    assert "+79995558877" not in raw
    assert "5558877" not in raw


def test_invalid_token_404(client):
    """Короткий и случайный несуществующий токен → 404 (анти-перебор)."""
    assert client.get("/t/short/state.json").status_code == 404
    assert client.get("/t/zzzzzzzzzzzzzzzzzzzzzz/state.json").status_code == 404


def test_track_page_renders(client, user_factory):
    """HTML-страница /t/{token} отдаётся (200) по валидному токену посылки."""
    sender = user_factory(name="Стр")
    pid = _create_parcel(client, sender).json()["id"]
    token = _track_link(client, sender, pid)["token"]
    r = client.get(f"/t/{token}")
    assert r.status_code == 200 and "Юлдаш" in r.text
