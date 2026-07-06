"""F16 — публичная витрина поездки для расшаривания (yulbash.ru/r/{id}).

Контракт (роль Security-инженера): превью НЕ отдаёт ПДн — ни телефона, ни точной
точки сбора; несуществующая поездка → 404; приватные поля не «текут» в JSON/HTML.
"""
from sqlmodel import Session

from app.db import engine
from app.models import Ride, User, UserRole


def _publish(client, drv, frm="Баймак", to="Сибай", seats=3, price=300, **extra):
    body = {"from_city": frm, "to_city": to, "depart_at": "2030-01-01T10:00:00",
            "seats_total": seats, "price": price, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def test_preview_shape_public(client, user_factory):
    drv = user_factory("ShareDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="Темясово", to="Уфа", price=1400, women_only=True)
    r = client.get(f"/r/{ride['id']}/preview")
    assert r.status_code == 200, r.text
    p = r.json()
    # Публичная витрина: маршрут, цена, время, имя/рейтинг водителя, удобства.
    assert p["from_city"] == "Темясово" and p["to_city"] == "Уфа"
    assert p["price"] == 1400 and p["women_only"] is True
    assert {"driver_name", "driver_rating", "driver_verified", "seats_left", "depart_at"} <= set(p)


def test_preview_no_pii(client, user_factory):
    """Ни телефона, ни точной точки сбора, ни внутренних id — ни в ключах, ни в значениях."""
    drv = user_factory("PiiDrv", role=UserRole.driver)
    # Задаём телефон водителю + точку сбора поездке — проверяем, что они НЕ утекают.
    with Session(engine) as s:
        u = s.get(User, drv["id"])
        u.phone = "+79990001122"
        s.add(u)
        s.commit()
    ride = _publish(client, drv, pickup="ул. Секретная 5", pickup_lat=54.1, pickup_lng=58.3)
    p = client.get(f"/r/{ride['id']}/preview").json()
    # Запрещённые ключи отсутствуют.
    for banned in ("phone", "driver_phone", "pickup", "pickup_lat", "pickup_lng",
                   "driver_id", "receiver_name", "parcel_size"):
        assert banned not in p, f"ключ {banned} не должен быть в превью"
    # Телефон/точный адрес не встречаются нигде в значениях.
    blob = str(p)
    assert "+79990001122" not in blob and "Секретная" not in blob


def test_preview_missing_404(client):
    assert client.get("/r/99999999/preview").status_code == 404


def test_share_page_html_and_og(client, user_factory):
    drv = user_factory("HtmlDrv", role=UserRole.driver)
    ride = _publish(client, drv, frm="Сибай", to="Магнитогорск", price=500)
    r = client.get(f"/r/{ride['id']}")
    assert r.status_code == 200
    assert "text/html" in r.headers["content-type"]
    body = r.text
    assert 'property="og:title"' in body and 'property="og:image"' in body
    assert 'name="twitter:card"' in body
    assert "Сибай" in body and "Магнитогорск" in body
    # Кнопки deep-link + скачивания на месте.
    assert "intent://" in body and "yuldash.apk" in body


def test_share_page_html_no_pii(client, user_factory):
    drv = user_factory("HtmlPiiDrv", role=UserRole.driver)
    with Session(engine) as s:
        u = s.get(User, drv["id"])
        u.phone = "+79995556677"
        s.add(u)
        s.commit()
    ride = _publish(client, drv, pickup="двор дома 12", pickup_lat=53.9, pickup_lng=58.4)
    body = client.get(f"/r/{ride['id']}").text
    assert "+79995556677" not in body and "двор дома 12" not in body


def test_share_page_missing_404(client):
    assert client.get("/r/99999999").status_code == 404


def test_share_page_bashkir(client, user_factory):
    drv = user_factory("BaDrv", role=UserRole.driver)
    ride = _publish(client, drv)
    body = client.get(f"/r/{ride['id']}?lang=ba").text
    assert 'lang="ba"' in body and "ba_RU" in body
