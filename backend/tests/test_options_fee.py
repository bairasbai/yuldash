"""Опции салона стали платными: детское кресло 150 ₽ водителю (решение Александра 2026-08-23).

Что защищаем:
- ГЛАВНОЕ: доступность (инвалидная коляска, собака-проводник) стоит 0 ₽ ВСЕГДА, и это
  нельзя переопределить конфигом. Плата за них — дискриминация и штраф;
- цену: кресло видно в оценке ДО заказа, а не появляется сюрпризом в чеке;
- деньги водителя: комиссия с опций не берётся, они компенсация, а не выручка;
- пересчёты: активация предзаказа не теряет кресло.
"""
import fakeredis
import pytest
from sqlmodel import Session, select

from app import car_class as cc
from app import debt as debt_mod
from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import CommissionDebt, DriverProfile, InstantOrder, UserRole

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _driver(client, user_factory, name="OptDrv", coord=ORIG, car_options="") -> dict:
    """Водитель на линии. `car_options` — что у него есть в машине.

    Без этого заказ с креслом ему не предложат вообще: фильтр жёсткий, и это правильно —
    подобрать машину без кресла «раз никого нет» значит обмануть в том единственном,
    ради чего галочку и ставили."""
    d = user_factory(name, role=UserRole.driver)
    if car_options:
        with Session(engine) as s:
            p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
            if p is None:
                p = DriverProfile(user_id=d["id"])   # профиль заводится при первой настройке
            p.car_options = car_options
            s.add(p)
            s.commit()
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": coord[0], "lng": coord[1]}).status_code == 200
    return d


def _estimate(client, pax, options=None):
    body = {"from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1]}
    if options is not None:
        body["options"] = options
    resp = client.post("/instant/estimate", headers=pax["auth"], json=body)
    assert resp.status_code == 200, resp.text
    return resp.json()


def _order_body(options=None):
    body = {"from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
            "from_text": "Баймак", "to_text": "Сибай"}
    if options is not None:
        body["options"] = options
    return body


# ============================ Цены и главное правило ============================
def test_child_seat_costs_150_and_pets_luggage_100():
    """Цены из решения 23.08: детские — 150 ₽, животное и большой багаж — по 100 ₽."""
    for code in ("seat_0_1", "seat_1_4", "seat_4_7", "booster"):
        assert cc.option_price_rub(code) == 150, code
    assert cc.option_price_rub("pets") == 100
    assert cc.option_price_rub("big_luggage") == 100
    # Коляска — не услуга, а семья с ребёнком.
    assert cc.option_price_rub("stroller") == 0
    # Две опции складываются.
    assert cc.options_fee_rub("seat_1_4,pets") == 250
    # Мусор и пустота не ломают расчёт.
    assert cc.options_fee_rub("") == 0
    assert cc.options_fee_rub("нет_такой_опции") == 0


def test_a_charger_is_an_option_and_costs_nothing():
    """Зарядка в машине — опция, а не требование класса, и она бесплатна.

    Провод в прикуривателе водителю почти ничего не стоит, а пассажиру с севшим телефоном
    между сёлами это связь и возможность заплатить за поездку. Требованием класса делать
    нельзя: у кого зарядки нет, тот просто выпал бы из подбора целиком.
    """
    assert "charger" in cc.OPTIONS
    assert cc.option_price_rub("charger") == 0
    assert cc.options_fee_rub("charger,seat_1_4") == 150      # платим только за кресло


def test_accessibility_is_free_forever_and_config_cannot_change_it(monkeypatch):
    """СТОРОЖ. Плата за инвалидную коляску или собаку-проводника — дискриминация: в 2025
    с водителя взыскали 5 000 ₽ морального вреда и 30 000 ₽ штрафа за отказ везти незрячего
    с собакой. Поэтому ноль зашит в код, и настройка в .env его не переопределяет."""
    monkeypatch.setattr(settings, "option_prices_rub",
                        {"wheelchair": 500, "guide_dog": 500, "seat_1_4": 200})

    assert cc.option_price_rub("wheelchair") == 0
    assert cc.option_price_rub("guide_dog") == 0
    assert cc.options_fee_rub("wheelchair,guide_dog") == 0
    # А обычную опцию конфиг менять вправе — это и есть смысл настройки.
    assert cc.option_price_rub("seat_1_4") == 200


def test_broken_config_value_does_not_break_the_order(monkeypatch):
    """Кривое значение в .env не должно ронять заказ — падаем на цену по умолчанию."""
    monkeypatch.setattr(settings, "option_prices_rub", {"seat_1_4": "дорого"})
    assert cc.option_price_rub("seat_1_4") == 150


# ============================ Цена до заказа ============================
def test_seat_is_visible_in_the_price_before_ordering(client, user_factory, fake_redis):
    """Человек ставит галочку — цена меняется сразу, а не в чеке после поездки."""
    _driver(client, user_factory, "SeatDrv")
    pax = user_factory("SeatPax")

    plain = _estimate(client, pax)
    with_seat = _estimate(client, pax, ["seat_1_4"])

    assert with_seat["options_fee"] == 150
    assert with_seat["price"] == plain["price"] + 150
    assert with_seat["ride_price"] == plain["ride_price"]      # поездка не подорожала
    # Расшифровка по каждой опции — чтобы клиент подписал строку названием.
    assert with_seat["options_prices"] == [{"code": "seat_1_4", "price": 150}]

    factor = next((f for f in with_seat["price_factors"] if f["code"] == "options_fee"), None)
    assert factor is not None and factor["amount_rub"] == 150
    assert factor["title_ru"] and factor["title_ba"]
    assert "водител" in factor["description_ru"].lower()


def test_accessibility_does_not_change_the_price(client, user_factory, fake_redis):
    """Собака-проводник в заказе — цена та же. Это проверка сквозная, а не на функции."""
    _driver(client, user_factory, "AccDrv")
    pax = user_factory("AccPax")

    plain = _estimate(client, pax)
    with_dog = _estimate(client, pax, ["guide_dog", "wheelchair"])

    assert with_dog["options_fee"] == 0
    assert with_dog["price"] == plain["price"]
    assert all(f["code"] != "options_fee" for f in with_dog["price_factors"])


def test_every_class_carries_the_same_seat_price(client, user_factory, fake_redis):
    """Кресло в Бизнесе не дороже, чем в Экономе: это то же самое кресло."""
    _driver(client, user_factory, "ClassSeatDrv")
    pax = user_factory("ClassSeatPax")
    body = _estimate(client, pax, ["booster"])

    assert body["options"], "витрина классов пуста"
    for opt in body["options"]:
        assert opt["options_fee"] == 150
        assert opt["price"] == opt["ride_price"] + opt["pickup_fee"] + 150


# ============================ Заказ и деньги ============================
def test_seat_money_survives_to_the_receipt(client, user_factory, fake_redis):
    """Сумма живёт от заказа до чека и не теряется по дороге."""
    d = _driver(client, user_factory, "SeatFlowDrv", car_options="seat_4_7")
    pax = user_factory("SeatFlowPax")
    order = client.post("/instant/orders", headers=pax["auth"],
                        json=_order_body(["seat_4_7"])).json()
    assert order["status"] == "offered", order
    oid = order["id"]

    assert order["options_fee_kop"] == 150 * 100
    assert order["price_estimate"] == order["ride_price"] + order["pickup_fee_kop"] // 100 + 150

    for step in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200
    done = client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()

    assert done["options_fee_kop"] == 150 * 100
    assert done["price_final"] == done["ride_price"] + 150


def test_commission_is_not_taken_from_the_seat(client, user_factory, fake_redis):
    """Кресло — компенсация водителю, а не наша выручка: комиссия с него не берётся."""
    d = _driver(client, user_factory, "SeatFeeDrv", car_options="seat_1_4")
    pax = user_factory("SeatFeePax")
    oid = client.post("/instant/orders", headers=pax["auth"],
                      json=_order_body(["seat_1_4"])).json()["id"]
    for step in ("accept", "arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        debt = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == d["id"]).order_by(CommissionDebt.id.desc())).first()
        percent = debt_mod.driver_fee_percent(s, d["id"], order.created_at)

    ride_only_kop = (order.price_final - 150 - order.pickup_fee_kop // 100) * 100
    assert debt.amount_kop == debt_mod.fee_kop_for(ride_only_kop, percent)
    assert debt.amount_kop < debt_mod.fee_kop_for(order.price_final * 100, percent)


def test_scheduled_order_does_not_lose_the_seat(client, user_factory, fake_redis):
    """Предзаказ пересчитывается при активации — кресло, выбранное вечером, не должно
    исчезнуть к утру вместе с деньгами водителя."""
    from datetime import timedelta
    from app.timeutil import utcnow

    _driver(client, user_factory, "SchedSeatDrv", car_options="seat_0_1")
    pax = user_factory("SchedSeatPax")
    when = (utcnow() + timedelta(hours=3)).isoformat()
    body = _order_body(["seat_0_1"])
    body["scheduled_at"] = when
    created = client.post("/instant/schedule", headers=pax["auth"], json=body).json()
    assert created["options_fee_kop"] == 150 * 100

    with Session(engine) as s:
        row = s.get(InstantOrder, created["id"])
        row.scheduled_at = utcnow() - timedelta(minutes=1)   # время пришло
        s.add(row)
        s.commit()
    activated = client.post(f"/instant/scheduled/{created['id']}/activate",
                            headers=pax["auth"])
    assert activated.status_code == 200, activated.text

    with Session(engine) as s:
        row = s.get(InstantOrder, created["id"])
    assert row.options_fee_kop == 150 * 100
    assert row.price_estimate == row.ride_price + row.pickup_fee_kop // 100 + 150
