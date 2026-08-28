"""C1 — профиль «Курьер»: позитив и негатив.

Флоу: заявка курьера → админ approve → CourierProfile → выход на линию → estimate (цена с сервера,
без суржа) → создание courier-заказа → /courier/available БЕЗ телефона → accept (курьер видит телефон)
→ delivered по коду → /courier/me statement считает комиссию. buy_bring в пределах потолка — ок.
Негатив: apply без селфи/неверный transport → 422; повтор заявки → 409; онлайн/accept без одобрения
или при выключенном режиме → 403; buy_bring выше потолка → 422; без rules_accepted → 422; курьер-заказ
НЕ в /parcels/available; телефон скрыт в /courier/available; IDOR. detail 4xx — двуязычный dict {ru,ba}.
"""
import pytest

from app.config import settings
from app.models import UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    """Режим курьера ВКЛючён по умолчанию для тестов (сам гейт выключения проверяем точечно)."""
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


def _make_courier(client, user_factory):
    """Одобренный курьер на линии (city, work_city=Уфа). Возвращает dict пользователя."""
    admin = user_factory(name="Админ", role=UserRole.admin)
    c = user_factory(name="Курьер")
    ra = _apply(client, c)
    assert ra.status_code == 200, ra.text
    aid = ra.json()["id"]
    assert _approve(client, admin, aid).status_code == 200
    ron = client.post("/courier/online", headers=c["auth"],
                      json={"zone": "region"})   # region → видит все заказы
    assert ron.status_code == 200, ron.text
    return c


def _order(client, sender, **ov):
    body = {
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Документы",
        "receiver_name": "Айгүл", "receiver_phone": "+79990001133",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    return client.post("/courier/orders", headers=sender["auth"], json=body)


# ----------------------------- ПОЗИТИВ -----------------------------

def test_onboarding_and_full_flow(client, user_factory):
    admin = user_factory(name="Админ", role=UserRole.admin)
    courier = user_factory(name="Курьер")

    # заявка
    ra = _apply(client, courier)
    assert ra.status_code == 200, ra.text
    app = ra.json()
    assert app["status"] == "pending"
    assert app["transport"] == "car"

    # моя заявка
    rget = client.get("/courier/application", headers=courier["auth"])
    assert rget.status_code == 200
    assert rget.json()["application"]["id"] == app["id"]

    # админ одобряет → профиль создан
    assert _approve(client, admin, app["id"]).status_code == 200
    rme = client.get("/courier/me", headers=courier["auth"])
    assert rme.status_code == 200, rme.text
    assert rme.json()["profile"] is not None
    assert rme.json()["profile"]["car_class"] == "car"

    # выход на линию
    ron = client.post("/courier/online", headers=courier["auth"], json={"zone": "region"})
    assert ron.status_code == 200
    assert ron.json()["online"] is True

    # estimate — цена с сервера, без суржа
    re = client.get("/courier/estimate", headers=courier["auth"], params={
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "medium", "urgency": "now",
    })
    assert re.status_code == 200, re.text
    est = re.json()
    assert est["price_kop"] > 0
    assert est["distance_km"] > 0
    # Ставку берём из конфига: верхняя ступень курьера уже менялась (8% → 15%), и проверка,
    # зашитая числом, ловила бы не ошибку, а собственную несвежесть. База — доставка БЕЗ
    # компенсаций курьеру (дорога к посылке, зимняя дорога): с них комиссия не берётся.
    база = est["price_kop"] - est["pickup_kop"] - est["weather_kop"]
    assert est["commission_kop"] == round(база * settings.courier_service_fee_percent / 100)
    assert est["breakdown"]["size_kop"] == 5000        # medium
    assert est["breakdown"]["urgency_kop"] == 10000    # now

    # заказ курьера от отправителя
    sender = user_factory(name="Отправитель")
    ro = _order(client, sender)
    assert ro.status_code == 200, ro.text
    order = ro.json()
    pid = order["id"]
    code = order["confirm_code"]
    assert order["delivery_type"] == "courier"
    assert order["commission_kop"] > 0
    assert order["price_kop"] > order["commission_kop"]

    # в /courier/available — БЕЗ телефона получателя
    rav = client.get("/courier/available", headers=courier["auth"])
    assert rav.status_code == 200, rav.text
    row = next(x for x in rav.json() if x["id"] == pid)
    assert not row.get("receiver_phone")
    assert "confirm_code" not in row

    # accept → курьер видит телефон
    rac = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert rac.status_code == 200, rac.text
    assert rac.json()["receiver_phone"] == "+79990001133"

    # delivered по коду
    rt = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert rt.status_code == 200
    rd = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                     json={"status": "delivered", "code": code})
    assert rd.status_code == 200, rd.text
    assert rd.json()["status"] == "delivered"

    # statement курьера считает комиссию
    rme2 = client.get("/courier/me", headers=courier["auth"])
    st = rme2.json()["statement"]
    assert st["delivered_count"] >= 1
    # C4: комиссия финализируется при вручении по стажу курьера (свежий курьер → tier1 3%),
    # поэтому она может отличаться от оценки при создании (8%). Главное — statement её считает.
    assert st["commission_kop"] > 0


def test_buy_bring_within_cap(client, user_factory):
    _make_courier(client, user_factory)  # хотя бы один курьер на линии
    sender = user_factory(name="Покупатель")
    ro = _order(client, sender, delivery_type="buy_bring", cod_amount_kop=300000,
                shopping_list="Хлеб, молоко, лекарство")
    assert ro.status_code == 200, ro.text
    order = ro.json()
    assert order["delivery_type"] == "buy_bring"
    assert order["cod_amount_kop"] == 300000
    assert "лекарство" in order["description"]


def test_courier_order_not_in_poputka_available(client, user_factory):
    """Курьер-заказ НЕ появляется в /parcels/available (по пути) — даже у неодобренного юзера."""
    sender = user_factory(name="Отпр")
    ro = _order(client, sender)
    pid = ro.json()["id"]
    plain = user_factory(name="Случайный")   # не курьер
    rav = client.get("/parcels/available", headers=plain["auth"])
    assert rav.status_code == 200
    assert all(x["id"] != pid for x in rav.json())


def test_zone_city_filters_available(client, user_factory):
    admin = user_factory(name="Админ2", role=UserRole.admin)
    courier = user_factory(name="ГородскойКурьер")
    aid = _apply(client, courier).json()["id"]
    _approve(client, admin, aid)
    # курьер работает только по Сибаю
    client.post("/courier/online", headers=courier["auth"],
                json={"zone": "city", "work_city": "Сибай"})
    sender = user_factory(name="Отпр3")
    # заказ Уфа→Стерлитамак — вне зоны курьера
    pid = _order(client, sender).json()["id"]
    rav = client.get("/courier/available", headers=courier["auth"])
    assert all(x["id"] != pid for x in rav.json())
    # заказ по Сибаю — в зоне
    pid2 = _order(client, sender, from_city="Сибай", to_city="Сибай").json()["id"]
    rav2 = client.get("/courier/available", headers=courier["auth"])
    assert any(x["id"] == pid2 for x in rav2.json())


# ----------------------------- НЕГАТИВ -----------------------------

def test_apply_validation(client, user_factory):
    u = user_factory(name="U")
    r1 = _apply(client, u, transport="plane")
    assert r1.status_code == 422
    assert "транспорт" in str(r1.json()["detail"]).lower()
    r2 = _apply(client, u, selfie="")
    assert r2.status_code == 422
    assert "селфи" in str(r2.json()["detail"]).lower()


def test_duplicate_application_409(client, user_factory):
    u = user_factory(name="Dup")
    assert _apply(client, u).status_code == 200
    r = _apply(client, u)
    assert r.status_code == 409
    assert "ru" in r.json()["detail"]


def test_online_without_approval_403(client, user_factory):
    u = user_factory(name="NoApprove")
    _apply(client, u)   # только pending, не одобрен
    r = client.post("/courier/online", headers=u["auth"], json={"zone": "city"})
    assert r.status_code == 403
    assert "курьер" in str(r.json()["detail"]).lower()


def test_accept_courier_order_without_approval_403(client, user_factory):
    sender = user_factory(name="Отпр4")
    pid = _order(client, sender).json()["id"]
    stranger = user_factory(name="НеКурьер")
    r = client.post(f"/parcels/{pid}/accept", headers=stranger["auth"])
    assert r.status_code == 403
    assert "ru" in r.json()["detail"]


def test_feature_off_blocks(client, user_factory):
    """Мастер-флаг выключен → онлайн/заказ/available → 403 «Курьер скоро»."""
    settings.courier_enabled = False
    try:
        u = user_factory(name="OffUser")
        r1 = client.post("/courier/orders", headers=u["auth"], json={
            "from_city": "Уфа", "to_city": "Сибай", "size": "small",
            "receiver_name": "X", "rules_accepted": True, "delivery_type": "courier",
        })
        assert r1.status_code == 403
        assert "скоро" in str(r1.json()["detail"]).lower()
    finally:
        settings.courier_enabled = True


def test_buy_bring_over_cap_422(client, user_factory):
    _make_courier(client, user_factory)
    sender = user_factory(name="Богач")
    r = _order(client, sender, delivery_type="buy_bring", cod_amount_kop=600000)
    assert r.status_code == 422
    assert "большая" in str(r.json()["detail"]).lower()


def test_order_without_rules_422(client, user_factory):
    sender = user_factory(name="БезПравил")
    r = _order(client, sender, rules_accepted=False)
    assert r.status_code == 422
    assert "правил" in str(r.json()["detail"]).lower()


def test_phone_hidden_in_available(client, user_factory):
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="Отпр5")
    pid = _order(client, sender).json()["id"]
    rav = client.get("/courier/available", headers=courier["auth"])
    row = next(x for x in rav.json() if x["id"] == pid)
    assert not row.get("receiver_phone")


def test_idor_foreign_application(client, user_factory):
    """Чужую заявку курьера обычный юзер одобрить не может (админ-гейт → 403)."""
    victim = user_factory(name="Жертва")
    aid = _apply(client, victim).json()["id"]
    attacker = user_factory(name="Взломщик")
    r = client.post(f"/admin/courier-applications/{aid}/approve", headers=attacker["auth"])
    assert r.status_code == 403


def test_idor_foreign_order_accept(client, user_factory):
    """courier/buy_bring accept закрыт гейтом; свою посылку курьер взять не может (409)."""
    courier = _make_courier(client, user_factory)
    # курьер сам создаёт заказ и пытается его же принять
    ro = _order(client, courier)
    pid = ro.json()["id"]
    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code == 409
    assert "ru" in r.json()["detail"]
