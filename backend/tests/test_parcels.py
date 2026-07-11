"""M3 — доставка посылок между сёлами: позитив и негатив.

Проверяем весь флоу (отправитель создаёт → курьер видит без телефона → accept → видит телефон →
in_transit → delivered по коду → statement) и ошибки (правила, размер, чужие/свои, неверный код,
приватность телефона в /available). Все detail для 4xx — двуязычный dict {ru, ba}: ассертим str(detail).
"""
from app.models import UserRole


def _create_parcel(client, sender, **overrides):
    body = {
        "from_city": "Стерлитамак",
        "to_city": "Уфа",
        "size": "small",
        "description": "Лекарство для бабушки",
        "receiver_name": "Гүзәл",
        "receiver_phone": "+79990001122",
        "rules_accepted": True,
    }
    body.update(overrides)
    return client.post("/parcels", headers=sender["auth"], json=body)


# ------------------------- ПОЗИТИВ -------------------------

def test_full_happy_path(client, user_factory):
    sender = user_factory(name="Отправитель")
    courier = user_factory(name="Курьер", role=UserRole.driver)

    # создание
    r = _create_parcel(client, sender, from_city="Сибай", to_city="Уфа")
    assert r.status_code == 200, r.text
    p = r.json()
    pid = p["id"]
    assert p["status"] == "created"
    assert p["fee_kop"] == 3000                 # small = 30 ₽
    assert p["confirm_code"]                     # отправитель видит код
    code = p["confirm_code"]
    assert p["receiver_name"] == "Гүзәл"

    # в /available у курьера — БЕЗ телефона получателя
    ra = client.get("/parcels/available", headers=courier["auth"])
    assert ra.status_code == 200, ra.text
    mine_row = next(x for x in ra.json() if x["id"] == pid)
    assert not mine_row.get("receiver_phone")    # ключа нет или пуст
    assert "confirm_code" not in mine_row        # код тоже скрыт

    # accept → курьер видит телефон
    rac = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert rac.status_code == 200, rac.text
    assert rac.json()["status"] == "accepted"
    assert rac.json()["receiver_phone"] == "+79990001122"
    assert rac.json()["courier_id"] == courier["id"]

    # /carrying содержит + телефон виден
    rc = client.get("/parcels/carrying", headers=courier["auth"])
    assert rc.status_code == 200, rc.text
    carry = next(x for x in rc.json() if x["id"] == pid)
    assert carry["receiver_phone"] == "+79990001122"

    # отправитель видит курьера в /parcels/mine
    rm = client.get("/parcels/mine", headers=sender["auth"])
    mine = next(x for x in rm.json() if x["id"] == pid)
    assert mine["courier"]["id"] == courier["id"]

    # in_transit
    rt = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    assert rt.status_code == 200, rt.text
    assert rt.json()["status"] == "in_transit"

    # delivered по верному коду
    rd = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "delivered", "code": code})
    assert rd.status_code == 200, rd.text
    assert rd.json()["status"] == "delivered"
    assert rd.json()["delivered_at"]

    # после доставки — не в /carrying
    rc2 = client.get("/parcels/carrying", headers=courier["auth"])
    assert all(x["id"] != pid for x in rc2.json())

    # admin statement суммирует fee
    admin = user_factory(name="Админ", role=UserRole.admin)
    radm = client.get("/admin/parcels", headers=admin["auth"])
    assert radm.status_code == 200, radm.text
    st = radm.json()["statement"]
    assert st["collected_fee_kop"] >= 3000
    assert st["delivered_count"] >= 1
    row = next(x for x in radm.json()["parcels"] if x["id"] == pid)
    assert row["confirm_code"] == code           # админ видит код (для поддержки)


def test_available_excludes_own(client, user_factory):
    sender = user_factory(name="Отпр2")
    r = _create_parcel(client, sender, from_city="Учалы", to_city="Уфа")
    pid = r.json()["id"]
    # свою заявку в /available сам отправитель НЕ видит
    ra = client.get("/parcels/available", headers=sender["auth"])
    assert all(x["id"] != pid for x in ra.json())


def test_city_filter(client, user_factory):
    sender = user_factory(name="Отпр3")
    courier = user_factory(name="Кур3", role=UserRole.driver)
    r = _create_parcel(client, sender, from_city="Бирск", to_city="Нефтекамск")
    pid = r.json()["id"]
    ra = client.get("/parcels/available?from_city=бирск&to_city=Нефтекамск", headers=courier["auth"])
    assert any(x["id"] == pid for x in ra.json())
    rb = client.get("/parcels/available?from_city=Уфа", headers=courier["auth"])
    assert all(x["id"] != pid for x in rb.json())


# ------------------------- НЕГАТИВ -------------------------

def test_create_requires_rules(client, user_factory):
    sender = user_factory()
    r = _create_parcel(client, sender, rules_accepted=False)
    assert r.status_code == 422
    assert "правила" in str(r.json()["detail"])


def test_create_unknown_size(client, user_factory):
    sender = user_factory()
    r = _create_parcel(client, sender, size="huge")
    assert r.status_code == 422
    assert "размер" in str(r.json()["detail"])


def test_create_requires_cities_and_receiver(client, user_factory):
    sender = user_factory()
    assert _create_parcel(client, sender, from_city="").status_code == 422
    assert _create_parcel(client, sender, to_city="").status_code == 422
    assert _create_parcel(client, sender, receiver_name="").status_code == 422


def test_cannot_accept_own(client, user_factory):
    sender = user_factory()
    r = _create_parcel(client, sender)
    pid = r.json()["id"]
    ra = client.post(f"/parcels/{pid}/accept", headers=sender["auth"])
    assert ra.status_code == 409
    assert "свою" in str(ra.json()["detail"])


def test_cannot_accept_twice(client, user_factory):
    sender = user_factory()
    c1 = user_factory(role=UserRole.driver)
    c2 = user_factory(role=UserRole.driver)
    pid = _create_parcel(client, sender).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=c1["auth"]).status_code == 200
    r2 = client.post(f"/parcels/{pid}/accept", headers=c2["auth"])
    assert r2.status_code == 409
    assert "инде" in str(r2.json()["detail"]) or "взяли" in str(r2.json()["detail"])


def test_delivered_wrong_code(client, user_factory):
    sender = user_factory()
    courier = user_factory(role=UserRole.driver)
    pid = _create_parcel(client, sender).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    r = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "delivered", "code": "ZZZZZZ"})
    assert r.status_code == 422
    assert "код" in str(r.json()["detail"]).lower() or "коды" in str(r.json()["detail"])


def test_move_not_courier(client, user_factory):
    sender = user_factory()
    courier = user_factory(role=UserRole.driver)
    stranger = user_factory(role=UserRole.driver)
    pid = _create_parcel(client, sender).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    r = client.post(f"/parcels/{pid}/status", headers=stranger["auth"], json={"status": "in_transit"})
    assert r.status_code == 404
    assert "табылманы" in str(r.json()["detail"]) or "не найдена" in str(r.json()["detail"])


def test_cancel_delivered(client, user_factory):
    sender = user_factory()
    courier = user_factory(role=UserRole.driver)
    r = _create_parcel(client, sender)
    pid = r.json()["id"]
    code = r.json()["confirm_code"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "delivered", "code": code})
    rc = client.post(f"/parcels/{pid}/cancel", headers=sender["auth"])
    assert rc.status_code == 409
    assert "доставлена" in str(rc.json()["detail"])


def test_cancel_not_mine(client, user_factory):
    sender = user_factory()
    stranger = user_factory()
    pid = _create_parcel(client, sender).json()["id"]
    rc = client.post(f"/parcels/{pid}/cancel", headers=stranger["auth"])
    assert rc.status_code == 404
    assert "табылманы" in str(rc.json()["detail"]) or "не найдена" in str(rc.json()["detail"])


def test_available_hides_phone(client, user_factory):
    sender = user_factory()
    courier = user_factory(role=UserRole.driver)
    pid = _create_parcel(client, sender, receiver_phone="+79995554433").json()["id"]
    ra = client.get("/parcels/available", headers=courier["auth"])
    row = next(x for x in ra.json() if x["id"] == pid)
    assert not row.get("receiver_phone")     # ключа нет или пуст — телефон скрыт до принятия


def test_admin_only(client, user_factory):
    user = user_factory()
    r = client.get("/admin/parcels", headers=user["auth"])
    assert r.status_code == 403
