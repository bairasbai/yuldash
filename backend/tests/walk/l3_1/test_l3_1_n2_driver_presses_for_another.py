"""N2 (P1, независимое ревью): SOS, нажатый ВОДИТЕЛЕМ в заказе «для другого» (сын вызвал
такси маме), не должен подменяться сигналом «за маму».

Было: `_кто_в_беде`/`_телефон_в_беде`/`_место_беды` проверяли только наличие `for_name`/
`for_phone` на заказе, не глядя, КТО нажал кнопку. Нажми водитель (у него тоже есть SOS в
активном заказе) — его близким уходили ЧУЖИЕ имя и телефон пассажирки (152-ФЗ), а место
брали с трека машины вместо GPS из тела запроса водителя — для водителя это один и тот же
трек, но сама логика «место беды = где человек» переставала работать на будущее (например
если трек машины устарел).

Правка: «для другого» действует ТОЛЬКО когда сигнал подал сам заказчик
(`order.passenger_id == нажал.id`). Водителю — его имя, его GPS, без `for_phone` в SMS.
"""
from sqlmodel import Session

import app.routers.safety as safety
from app.db import engine
from app.models import DriverProfile, InstantOrder, InstantOrderStatus as S, UserRole


def _capture_sms(monkeypatch):
    sent = []
    monkeypatch.setattr(safety, "_send_sos_sms", lambda phones, text: sent.append((list(phones), text)))
    return sent


def _order_for_another(pax_id: int, drv_id: int, *, onboard=True) -> int:
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv_id, car_model="Соляріс", car_color="серебристая",
                            car_plate="В555ЕЕ102"))
        order = InstantOrder(passenger_id=pax_id, driver_id=drv_id,
                             status=S.onboard if onboard else S.accepted,
                             price_estimate=300, price_final=300,
                             for_name="Мама Гульнур", for_phone="+79170009999")
        s.add(order)
        s.commit()
        s.refresh(order)
        return order.id


def test_водитель_жмёт_sos_близким_уходит_его_имя_не_пассажирки(client, user_factory, monkeypatch):
    drv = user_factory("N2Drv1", role=UserRole.driver)
    pax = user_factory("N2Pax1")
    oid = _order_for_another(pax["id"], drv["id"])
    client.post("/trusted-contacts", headers=drv["auth"],
                json={"name": "Жена", "relation": "жена", "phone": "+79170001234"})
    sent = _capture_sms(monkeypatch)

    r = client.post("/sos", headers=drv["auth"], json={"category": "other", "order_id": oid})
    assert r.status_code == 200, r.text

    to_wife = [t for phones, t in sent if "+79170001234" in phones]
    assert to_wife, "жене водителя SMS не ушло"
    text = to_wife[0]
    assert "Мама Гульнур" not in text, f"чужое имя пассажирки утекло близким водителя: {text}"
    assert "+79170009999" not in text, f"чужой телефон пассажирки утёк близким водителя: {text}"
    assert "N2Drv1" in text, f"в SMS нет имени самого водителя (это ОН в беде): {text}"


def test_водитель_жмёт_sos_место_его_gps_а_не_только_трек_машины(client, user_factory, monkeypatch):
    """Контроль: координаты из тела запроса (GPS водителя) используются как обычно —
    «для другого» не подменяет их треком машины, когда нажал не заказчик."""
    drv = user_factory("N2Drv2", role=UserRole.driver)
    pax = user_factory("N2Pax2")
    oid = _order_for_another(pax["id"], drv["id"])
    client.post("/trusted-contacts", headers=drv["auth"],
                json={"name": "Брат", "relation": "брат", "phone": "+79170005678"})
    sent = _capture_sms(monkeypatch)

    r = client.post("/sos", headers=drv["auth"],
                    json={"category": "other", "order_id": oid, "lat": 54.111, "lng": 56.222})
    assert r.status_code == 200, r.text
    assert "54.111" in r.json()["note"] and "56.222" in r.json()["note"], (
        "GPS самого водителя должен попасть в место беды, а не трек машины по умолчанию"
    )


def test_пассажир_заказчик_по_прежнему_работает_как_раньше(client, user_factory, monkeypatch):
    """Контроль обратной стороны: когда нажимает САМ заказчик (сын), «для другого» всё ещё
    действует — имя и телефон мамы уходят его близким, это рабочий сценарий (волна 192)."""
    drv = user_factory("N2Drv3", role=UserRole.driver)
    pax = user_factory("N2Pax3")
    oid = _order_for_another(pax["id"], drv["id"])
    client.post("/trusted-contacts", headers=pax["auth"],
                json={"name": "Бабушка", "relation": "бабушка", "phone": "+79170009000"})
    sent = _capture_sms(monkeypatch)

    r = client.post("/sos", headers=pax["auth"], json={"category": "other", "order_id": oid})
    assert r.status_code == 200, r.text

    to_grandma = [t for phones, t in sent if "+79170009000" in phones]
    assert to_grandma, "близким заказчика SMS не ушло"
    assert "Мама Гульнур" in to_grandma[0], (
        f"сценарий «для другого» сломан для самого заказчика: {to_grandma[0]}"
    )
