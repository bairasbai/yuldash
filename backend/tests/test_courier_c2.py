"""C2 — «купи и привези»: расчёт с получателем + объявленная ценность (спор).

Позитив: создать buy_bring → курьер accept → goods-cost (в пределах потолка) → delivered по коду →
settled=True, settlement показывает «к оплате получателем = товар + доставка». Спор: сторона заказа
(отправитель/курьер) создаёт dispute → Report(category=parcel_dispute) создан, admin видит его в ленте.
Негатив: goods-cost выше потолка → 422; на не-buy_bring → 409; чужим (не курьер) → 404; delivered
buy_bring без goods-cost → 409; dispute чужим (не участник) → 404. detail 4xx — двуязычный dict {ru,ba}.
"""
import pytest

from app.config import settings
from app.models import UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _apply(client, u, transport="car", selfie=None):
    return client.post("/courier/apply", headers=u["auth"],
                       json={"transport": transport,
                             "selfie_url": upload_doc(client, u["auth"]) if selfie is None else selfie})


def _approve(client, admin, app_id):
    return client.post(f"/admin/courier-applications/{app_id}/approve", headers=admin["auth"])


def _make_courier(client, user_factory, name="Курьер"):
    admin = user_factory(name="Админ", role=UserRole.admin)
    c = user_factory(name=name)
    aid = _apply(client, c).json()["id"]
    assert _approve(client, admin, aid).status_code == 200
    assert client.post("/courier/online", headers=c["auth"], json={"zone": "region"}).status_code == 200
    return c


def _order(client, sender, **ov):
    body = {
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Лекарство",
        "receiver_name": "Айгүл", "receiver_phone": "+79990001133",
        "rules_accepted": True, "delivery_type": "buy_bring", "urgency": "bypath",
        "cod_amount_kop": 200000, "declared_value_kop": 250000,
    }
    body.update(ov)
    return client.post("/courier/orders", headers=sender["auth"], json=body)


def _accept(client, courier, pid):
    return client.post(f"/parcels/{pid}/accept", headers=courier["auth"])


# ----------------------------- ПОЗИТИВ -----------------------------

def test_buy_bring_settlement_full_flow(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Покупатель")
    ro = _order(client, sender)
    assert ro.status_code == 200, ro.text
    order = ro.json()
    pid, code = order["id"], order["confirm_code"]
    # settlement уже в ответе на создание: товар ещё не подтверждён (goods=0), доставка есть
    st0 = order["settlement"]
    assert st0 is not None
    assert st0["goods_actual_kop"] == 0
    assert st0["delivery_kop"] > 0
    assert st0["total_due_kop"] == st0["delivery_kop"]
    assert st0["settled"] is False
    delivery_kop = st0["delivery_kop"]

    assert _accept(client, courier, pid).status_code == 200

    # курьер вводит фактическую стоимость товара (по факту дороже заявленных 2000 ₽)
    rg = client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                     json={"actual_kop": 230000})
    assert rg.status_code == 200, rg.text
    stg = rg.json()["settlement"]
    assert stg["goods_actual_kop"] == 230000
    assert stg["delivery_kop"] == delivery_kop
    assert stg["total_due_kop"] == 230000 + delivery_kop
    assert stg["settled"] is False

    # settlement виден в /parcels/carrying (что везу)
    rcar = client.get("/parcels/carrying", headers=courier["auth"])
    row = next(x for x in rcar.json() if x["id"] == pid)
    assert row["settlement"]["total_due_kop"] == 230000 + delivery_kop

    # доставка по коду → settled=True, итог расчёта в ответе
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    rd = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                     json={"status": "delivered", "code": code})
    assert rd.status_code == 200, rd.text
    body = rd.json()
    assert body["status"] == "delivered"
    assert body["settlement"]["settled"] is True
    assert body["settlement"]["total_due_kop"] == 230000 + delivery_kop


def test_dispute_goes_to_fairness_system(client, user_factory):
    """Спор по доставке теперь идёт в «Справедливость» (двусторонний разбор), а не в урезанную
    жалобу. Раньше это был Report с одним текстовым полем: без фото, без ответа второй стороны
    и без компенсации — курьер даже не узнавал, что на него пожаловались (аудит 2026-07-26)."""
    admin = user_factory(name="АдминСпор", role=UserRole.admin)
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Спорщик")
    pid = _order(client, sender).json()["id"]
    assert _accept(client, courier, pid).status_code == 200

    # отправитель открывает спор с типом и фото-доказательствами
    rd = client.post(f"/parcels/{pid}/dispute", headers=sender["auth"],
                     json={"reason": "Товар повреждён", "type": "parcel_damage"})
    assert rd.status_code == 200, rd.text
    dj = rd.json()
    assert dj["type"] == "parcel_damage"
    assert dj["parcel_id"] == pid
    assert dj["declared_value_kop"] == 250000
    assert dj["status"] in ("awaiting_response", "under_review")

    # курьер тоже участник — тоже может открыть свой спор
    rd2 = client.post(f"/parcels/{pid}/dispute", headers=courier["auth"],
                      json={"reason": "Получатель не рассчитался", "type": "recipient_absent"})
    assert rd2.status_code == 200, rd2.text

    # обвинённый видит спор у себя и может объясниться — это и есть смысл двустороннего разбора
    mine = client.get("/incidents/mine", headers=courier["auth"]).json()
    assert any(i["id"] == dj["id"] for i in mine)
    resp = client.post(f"/incidents/{dj['id']}/respond", headers=courier["auth"],
                       json={"statement": "Посылка была такой при получении"})
    assert resp.status_code == 200, resp.text

    # админ видит спор в ленте инцидентов с маршрутом доставки в подписи
    ra = client.get("/admin/incidents", headers=admin["auth"])
    assert ra.status_code == 200, ra.text
    row = next((x for x in ra.json() if x["id"] == dj["id"]), None)
    assert row is not None and "📦" in (row["booking_route"] or "")


def test_dispute_rejects_unknown_type(client, user_factory):
    """Неизвестный тип спора не проходит — иначе в разбор попадали бы мусорные категории."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="СпорТип")
    pid = _order(client, sender).json()["id"]
    _accept(client, courier, pid)
    r = client.post(f"/parcels/{pid}/dispute", headers=sender["auth"],
                    json={"reason": "что-то", "type": "нет_такого"})
    assert r.status_code == 422


# ----------------------------- НЕГАТИВ -----------------------------

def test_goods_cost_over_cap_422(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Богач2")
    pid = _order(client, sender).json()["id"]
    _accept(client, courier, pid)
    r = client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                    json={"actual_kop": 600000})
    assert r.status_code == 422
    assert "большая" in str(r.json()["detail"]).lower()


def test_goods_cost_on_non_buy_bring_409(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ОбычныйЗаказ")
    # обычный courier-заказ, не buy_bring
    pid = _order(client, sender, delivery_type="courier", cod_amount_kop=0).json()["id"]
    _accept(client, courier, pid)
    r = client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"],
                    json={"actual_kop": 100000})
    assert r.status_code == 409
    assert "ru" in r.json()["detail"]
    assert "привези" in str(r.json()["detail"]).lower()


def test_goods_cost_by_stranger_404(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Отпр6")
    pid = _order(client, sender).json()["id"]
    _accept(client, courier, pid)
    stranger = user_factory(name="Чужак")
    r = client.post(f"/courier/orders/{pid}/goods-cost", headers=stranger["auth"],
                    json={"actual_kop": 100000})
    assert r.status_code == 404
    assert "ru" in r.json()["detail"]


def test_delivered_buy_bring_without_goods_cost_409(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Отпр7")
    ro = _order(client, sender)
    pid, code = ro.json()["id"], ro.json()["confirm_code"]
    _accept(client, courier, pid)
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    # пытаемся вручить БЕЗ ввода стоимости покупки
    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                    json={"status": "delivered", "code": code})
    assert r.status_code == 409
    assert "стоимость" in str(r.json()["detail"]).lower()


def test_dispute_by_non_participant_404(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Отпр8")
    pid = _order(client, sender).json()["id"]
    _accept(client, courier, pid)
    stranger = user_factory(name="Посторонний")
    r = client.post(f"/parcels/{pid}/dispute", headers=stranger["auth"],
                    json={"reason": "Просто так"})
    assert r.status_code == 404
    assert "ru" in r.json()["detail"]


# ----------------------------- ЦЕНА ДОСТАВКИ ВИДНА КУРЬЕРУ (C1) -----------------------------

def test_courier_sees_delivery_price_not_commission(client, user_factory):
    """Курьер должен видеть ЦЕНУ ДОСТАВКИ (свой заработок), а не наш сбор.
    Регресс: _parcel_base не отдавал price_kop → в списках светилась комиссия (~25₽)
    вместо цены доставки (~140₽), а «Твой доход» уходил в минус."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Отправитель")
    ro = _order(client, sender)
    assert ro.status_code == 200, ro.text
    pid = ro.json()["id"]

    # 1) В доступных заказах цена доставки видна и это НЕ комиссия.
    av = client.get("/courier/available", headers=courier["auth"])
    assert av.status_code == 200, av.text
    row = next(x for x in av.json() if x["id"] == pid)
    assert "price_kop" in row, "price_kop должен отдаваться курьеру"
    assert row["price_kop"] > 0, "цена доставки должна быть положительной"
    assert row["price_kop"] > row["commission_kop"], "заработок курьера > нашего сбора"

    # 2) После accept — в «что везу» цена тоже видна, доход = цена - комиссия > 0.
    assert _accept(client, courier, pid).status_code == 200
    car = client.get("/parcels/carrying", headers=courier["auth"])
    row2 = next(x for x in car.json() if x["id"] == pid)
    income = row2["price_kop"] - row2["commission_kop"]
    assert income > 0, "«Твой доход» = цена - комиссия должен быть положительным"


# ----------------------------- ОНЛАЙН-ТРЕКИНГ ДОСТАВКИ (WS) -----------------------------

def test_parcel_location_rejects_non_participant(client, user_factory):
    """Приватность: живую позицию курьера видит ТОЛЬКО отправитель этой посылки, не посторонний."""
    import json
    from starlette.websockets import WebSocketDisconnect
    courier = _make_courier(client, user_factory, name="ТрекКурьер")
    sender = user_factory(name="ТрекОтпр")
    pid = _order(client, sender).json()["id"]
    assert _accept(client, courier, pid).status_code == 200
    outsider = user_factory(name="ТрекЧужой")
    with pytest.raises((WebSocketDisconnect, Exception)):
        with client.websocket_connect(f"/ws/parcel/{pid}/location") as ws:
            ws.send_text(json.dumps({"type": "auth", "token": outsider["token"]}))
            ws.receive_text()


def test_parcel_location_relays_courier_to_sender(client, user_factory):
    """Курьер шлёт свою позицию → отправитель видит движущегося курьера (role=courier)."""
    import json
    courier = _make_courier(client, user_factory, name="ТрекКурьер2")
    sender = user_factory(name="ТрекОтпр2")
    pid = _order(client, sender).json()["id"]
    assert _accept(client, courier, pid).status_code == 200
    with client.websocket_connect(f"/ws/parcel/{pid}/location") as courier_ws:
        courier_ws.send_text(json.dumps({"type": "auth", "token": courier["token"]}))
        with client.websocket_connect(f"/ws/parcel/{pid}/location") as sender_ws:
            sender_ws.send_text(json.dumps({"type": "auth", "token": sender["token"]}))
            courier_ws.send_text(json.dumps({"type": "loc", "lat": 54.05, "lng": 55.96, "ts": 77}))
            msg = json.loads(sender_ws.receive_text())
            assert msg["role"] == "courier"
            assert msg["lat"] == 54.05
            assert msg["lng"] == 55.96
            assert msg["ts"] == 77


# ----------------------------- ОПЛАТА КОМИССИИ КАРТОЙ (ЮKassa, по флажку) -----------------------------

def test_courier_commission_via_yookassa_clears_on_success(client, user_factory, monkeypatch):
    """payments_provider=yookassa → оплата картой: без ключей create_payment=succeeded (mock),
    комиссия сразу гасится (доставка commission_paid=True), метод в ответе — yookassa."""
    from app.config import settings
    from app.db import engine
    from app.models import ParcelDelivery
    from sqlmodel import Session
    courier = _make_courier(client, user_factory, name="ЮкКурьер")
    sender = user_factory(name="ЮкОтпр")
    ro = _order(client, sender)
    pid, code = ro.json()["id"], ro.json()["confirm_code"]
    assert _accept(client, courier, pid).status_code == 200
    client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"], json={"actual_kop": 100000})
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "delivered", "code": code}).status_code == 200

    monkeypatch.setattr(settings, "payments_provider", "yookassa")   # ключей нет → mock-succeeded
    r = client.post("/courier/pay-commission", headers=courier["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["method"] == "yookassa"
    assert body["status"] == "succeeded"
    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).commission_paid is True


def test_courier_commission_sbp_by_default(client, user_factory, monkeypatch):
    """По умолчанию (sbp_manual) — прежний поток «на доверии»: pending + реквизиты СБП."""
    from app.config import settings
    monkeypatch.setattr(settings, "payments_provider", "sbp_manual")
    monkeypatch.setattr(settings, "sbp_phone", "+79990001122")
    courier = _make_courier(client, user_factory, name="СбпКурьер")
    sender = user_factory(name="СбпОтпр")
    ro = _order(client, sender)
    pid, code = ro.json()["id"], ro.json()["confirm_code"]
    assert _accept(client, courier, pid).status_code == 200
    client.post(f"/courier/orders/{pid}/goods-cost", headers=courier["auth"], json={"actual_kop": 100000})
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "delivered", "code": code})
    r = client.post("/courier/pay-commission", headers=courier["auth"])
    body = r.json()
    assert body["method"] == "sbp_manual"
    assert body["status"] == "pending"
    assert body["payee"]["phone"] == "+79990001122"
