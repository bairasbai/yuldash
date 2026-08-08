"""C3 — рейтинг курьера (взаимные оценки доставки) + биллинг комиссии платформы «на доверии».

Позитив: доставка delivered → отправитель оценивает курьера (stars+text) → рейтинг курьера в
/courier/me растёт; курьер оценивает отправителя; накопленная комиссия = сумма delivered-заказов →
pay-commission создаёт pending → админ подтверждает → commission_paid=True, owed=0.
Негатив: оценить до вручения → 409; оценить чужую доставку → 404; двойная оценка → 409;
stars вне 1..5 → 422; pay-commission при owed==0 → 409; повтор pay-commission → тот же pending.
detail 4xx — двуязычный dict {ru, ba}.
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
        "size": "small", "description": "Документы",
        "receiver_name": "Айгүл", "receiver_phone": "+79990002244",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    return client.post("/courier/orders", headers=sender["auth"], json=body)


def _deliver(client, courier, sender, **ov):
    """Полный флоу до вручения: создать courier-заказ → accept → in_transit → delivered по коду.
    Возвращает (parcel_id, commission_kop)."""
    ro = _order(client, sender, **ov)
    assert ro.status_code == 200, ro.text
    order = ro.json()
    pid, code = order["id"], order["confirm_code"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                       json={"status": "in_transit"}).status_code == 200
    rd = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                     json={"status": "delivered", "code": code})
    assert rd.status_code == 200, rd.text
    # C4: комиссия ФИНАЛИЗИРУЕТСЯ при вручении (по стажу курьера) — берём её из delivered-ответа,
    # именно её суммирует statement (не оценку при создании).
    commission = rd.json()["commission_kop"]
    return pid, commission


# ----------------------------- ПОЗИТИВ -----------------------------

def test_mutual_rating_after_delivery(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерРейт")
    sender = user_factory(name="ОтправительРейт")
    pid, _ = _deliver(client, courier, sender)

    # отправитель оценивает курьера: 5 звёзд + текст (на модерацию)
    r = client.post(f"/parcels/{pid}/rate", headers=sender["auth"],
                    json={"stars": 5, "text": "Быстро и аккуратно, спасибо!"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["ratee_id"] == courier["id"]
    assert body["rating"] == 5.0
    assert body["count"] == 1

    # рейтинг курьера виден в /courier/me
    me = client.get("/courier/me", headers=courier["auth"]).json()
    assert me["rating"]["avg"] == 5.0
    assert me["rating"]["count"] == 1

    # курьер оценивает отправителя (обратная сторона)
    r2 = client.post(f"/parcels/{pid}/rate", headers=courier["auth"],
                     json={"stars": 4})
    assert r2.status_code == 200, r2.text
    assert r2.json()["ratee_id"] == sender["id"]
    assert r2.json()["rating"] == 4.0


def test_courier_rating_visible_to_sender_in_parcel(client, user_factory):
    """Рейтинг курьера показываем отправителю в объекте courier деталей заказа."""
    courier = _make_courier(client, user_factory, name="КурьерВид")
    sender = user_factory(name="ОтправительВид")
    pid, _ = _deliver(client, courier, sender)
    client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 5})

    mine = client.get("/parcels/mine", headers=sender["auth"]).json()
    row = next(x for x in mine if x["id"] == pid)
    assert row["courier"]["id"] == courier["id"]
    assert row["courier"]["rating"] == 5.0
    assert row["courier"]["rating_count"] == 1


def test_commission_billing_full_flow(client, user_factory):
    admin = user_factory(name="АдминКомиссия", role=UserRole.admin)
    courier = _make_courier(client, user_factory, name="КурьерКомиссия")
    s1 = user_factory(name="Отпр1")
    s2 = user_factory(name="Отпр2")
    pid1, c1 = _deliver(client, courier, s1)
    pid2, c2 = _deliver(client, courier, s2, urgency="now")   # надбавка → выше комиссия
    assert c1 > 0 and c2 > 0

    # /courier/me: заработали = c1+c2, к оплате = c1+c2, оплачено = 0
    me = client.get("/courier/me", headers=courier["auth"]).json()["statement"]
    assert me["commission_earned_kop"] == c1 + c2
    assert me["commission_owed_kop"] == c1 + c2
    assert me["commission_paid_kop"] == 0
    assert me["delivered_count"] == 2

    # курьер декларирует оплату → pending на сумму owed
    pay = client.post("/courier/pay-commission", headers=courier["auth"])
    assert pay.status_code == 200, pay.text
    pj = pay.json()
    assert pj["status"] == "pending"
    assert pj["amount_kop"] == c1 + c2
    payment_id = pj["payment_id"]

    # админ подтверждает получение перевода → доставки помечаются оплаченными
    conf = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert conf.status_code == 200, conf.text
    assert conf.json()["status"] == "succeeded"

    me2 = client.get("/courier/me", headers=courier["auth"]).json()["statement"]
    assert me2["commission_owed_kop"] == 0
    assert me2["commission_paid_kop"] == c1 + c2
    assert me2["commission_earned_kop"] == c1 + c2   # заработок не меняется


# ----------------------------- НЕГАТИВ -----------------------------

def test_rate_before_delivery_409(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерРано")
    sender = user_factory(name="ОтпрРано")
    pid = _order(client, sender).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    # ещё не вручено (accepted) — оценить нельзя
    r = client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 5})
    assert r.status_code == 409
    assert "ru" in r.json()["detail"]
    assert "вручени" in str(r.json()["detail"]).lower()


def test_rate_stranger_parcel_404(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерЧужой")
    sender = user_factory(name="ОтпрЧужой")
    pid, _ = _deliver(client, courier, sender)
    stranger = user_factory(name="Посторонний")
    r = client.post(f"/parcels/{pid}/rate", headers=stranger["auth"], json={"stars": 5})
    assert r.status_code == 404
    assert "ru" in r.json()["detail"]


def test_double_rate_409(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерДважды")
    sender = user_factory(name="ОтпрДважды")
    pid, _ = _deliver(client, courier, sender)
    assert client.post(f"/parcels/{pid}/rate", headers=sender["auth"],
                       json={"stars": 5}).status_code == 200
    r = client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 3})
    assert r.status_code == 409
    assert "уже" in str(r.json()["detail"]).lower()


def test_rate_stars_out_of_range_422(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерЗвёзды")
    sender = user_factory(name="ОтпрЗвёзды")
    pid, _ = _deliver(client, courier, sender)
    r = client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 7})
    assert r.status_code == 422
    assert "ru" in r.json()["detail"]
    r0 = client.post(f"/parcels/{pid}/rate", headers=sender["auth"], json={"stars": 0})
    assert r0.status_code == 422


def test_pay_commission_zero_owed_409(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерБезДолга")
    r = client.post("/courier/pay-commission", headers=courier["auth"])
    assert r.status_code == 409
    assert "ru" in r.json()["detail"]
    assert "комисси" in str(r.json()["detail"]).lower()


def test_pay_commission_idempotent_pending(client, user_factory):
    courier = _make_courier(client, user_factory, name="КурьерПовтор")
    sender = user_factory(name="ОтпрПовтор")
    _deliver(client, courier, sender)
    p1 = client.post("/courier/pay-commission", headers=courier["auth"])
    assert p1.status_code == 200, p1.text
    p2 = client.post("/courier/pay-commission", headers=courier["auth"])
    assert p2.status_code == 200, p2.text
    # тот же pending — второй платёж не создаётся
    assert p1.json()["payment_id"] == p2.json()["payment_id"]
    assert p2.json()["status"] == "pending"
