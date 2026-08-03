"""Витрина заказов курьера показывает комиссию по ЕГО ступени, а не по дефолтной.

Почему это важно. Комиссия сохраняется на заказе при СОЗДАНИИ, когда курьер ещё не назначен —
значит по верхней ступени (8%). Курьер-новичок (3%) или промо-курьер (0%) видел на карточке
заказа чужой, заниженный доход: в кабинете «ты платишь 3%», а из суммы вычиталось 8%.
Два разных числа про одни деньги в приложении «между своими» — прямой удар по доверию.

Здесь проверяем: /courier/available отдаёт комиссию по стажу ЗАПРОСИВШЕГО курьера,
но сохранённое на заказе значение не трогает (финал по-прежнему считается при вручении).
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

import app.routers.courier as cr
from app.config import settings
from app.db import engine
from app.models import CourierApplication, ParcelDelivery, UserRole
from app.timeutil import utcnow


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _make_courier(client, user_factory, name="КурьерВитрина"):
    admin = user_factory(name="АдминВитрина", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": "secure/docs/selfie.jpg"}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve", headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"], json={"zone": "region"}).status_code == 200
    return c


def _set_tenure_days(courier_id: int, days: int):
    """Смещаем reviewed_at в прошлое — эмулируем стаж для лесенки 3/5/8."""
    with Session(engine) as s:
        app = s.exec(
            select(CourierApplication).where(CourierApplication.user_id == courier_id)
            .order_by(CourierApplication.id.desc())
        ).first()
        app.reviewed_at = utcnow() - timedelta(days=days)
        s.add(app)
        s.commit()


def _order(client, sender, **ov):
    body = {
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,        # межгород → цена большая, ступени различимы
        "size": "small", "description": "Документы",
        "receiver_name": "Айгуль", "receiver_phone": "+79990009900",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    r = client.post("/courier/orders", headers=sender["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def test_available_shows_commission_of_this_courier_not_default_tier(client, user_factory):
    sender = _make_courier(client, user_factory, name="ОтправительКурьер")
    created = _order(client, sender)
    price_kop = created["price_kop"]
    stored_commission = created["commission_kop"]
    # При создании — верхняя ступень (курьер ещё не назначен).
    assert stored_commission == cr.courier_commission_kop(price_kop, settings.courier_service_fee_percent)

    newbie = _make_courier(client, user_factory, name="Новичок")
    _set_tenure_days(newbie["id"], 10)          # стаж 10 дней → tier1 = 3%

    rows = client.get("/courier/available", headers=newbie["auth"])
    assert rows.status_code == 200, rows.text
    card = next(p for p in rows.json() if p["id"] == created["id"])

    expected = cr.courier_commission_kop(price_kop, settings.courier_fee_tier1_percent)
    assert card["commission_kop"] == expected
    assert card["commission_kop"] < stored_commission   # новичку выгоднее — он это и видит
    assert card["commission_estimated"] is True         # финал считается при вручении
    assert card["price_kop"] == price_kop               # цена доставки не поехала

    # Сохранённое значение на заказе НЕ изменено: витрина ничего не переписывает в БД.
    with Session(engine) as s:
        assert s.get(ParcelDelivery, created["id"]).commission_kop == stored_commission


def test_available_commission_matches_veteran_default_tier(client, user_factory):
    """У ветерана (>60 дней) ступень совпадает с дефолтной — витрина не меняет число зря."""
    sender = _make_courier(client, user_factory, name="ОтправительВетеран")
    created = _order(client, sender)

    veteran = _make_courier(client, user_factory, name="Ветеран")
    _set_tenure_days(veteran["id"], 200)

    card = next(p for p in client.get("/courier/available", headers=veteran["auth"]).json()
                if p["id"] == created["id"])
    assert card["commission_kop"] == created["commission_kop"]


def test_available_buy_bring_keeps_extra_percent_for_newbie(client, user_factory):
    """«Купи и привези» дороже на надбавку — и на витрине новичка тоже (3% + надбавка)."""
    sender = _make_courier(client, user_factory, name="ОтправительBuyBring")
    created = _order(client, sender, delivery_type="buy_bring", cod_amount_kop=30000)

    newbie = _make_courier(client, user_factory, name="НовичокBuyBring")
    _set_tenure_days(newbie["id"], 5)

    card = next(p for p in client.get("/courier/available", headers=newbie["auth"]).json()
                if p["id"] == created["id"])
    expected = cr.courier_commission_kop(
        created["price_kop"], settings.courier_fee_tier1_percent + cr.COURIER_BUY_BRING_EXTRA_PERCENT)
    assert card["commission_kop"] == expected
