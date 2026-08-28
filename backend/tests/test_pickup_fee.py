"""Дальняя подача — отдельной строкой в рублях (разбор 2026-08-23).

Что защищаем:
- формулу строки: бесплатные километры, ставка, потолок, выключенный тариф;
- ГЛАВНОЕ: старый множитель дальней подачи выключен. Строка и множитель вместе — это
  двойная оплата одной и той же дороги к пассажиру;
- цену: итог = поездка + подача, и обе части видны человеку;
- случай «рядом никого»: сумму не выдумываем, обещаем потолок, фиксируем при accept;
- комиссию: с компенсации бензина её не берём (решение Александра, 2026-08-23);
- пересчёты: смена адреса не стирает подачу — водитель к пассажиру уже съездил.
"""
import fakeredis
import pytest
from sqlmodel import Session, select

from app import debt as debt_mod
from app import instant_service as isv
from app import pricing
from app.config import settings
from app.db import engine
from app.models import (CommissionDebt, DriverProfile, InstantOrder, Notification,
                        Tariff, UserRole)

ORIG = (52.591, 58.317)          # Баймак
DEST = (52.716, 58.664)          # Сибай, ~35 км по дорогам → зона «город»
KM_IN_DEGREE = 111.19            # градус широты в километрах (для точки «в N км севернее»)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _north_of(point, km: float):
    """Точка ровно в `km` километрах севернее — сдвиг только по широте, без тригонометрии."""
    return (point[0] + km / KM_IN_DEGREE, point[1])


def _driver_at(client, user_factory, coord, name="PickupDrv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": coord[0], "lng": coord[1]}).status_code == 200
    return d


def _estimate(client, pax, frm=ORIG, to=DEST):
    resp = client.post("/instant/estimate", headers=pax["auth"],
                       json={"from_lat": frm[0], "from_lng": frm[1],
                             "to_lat": to[0], "to_lng": to[1]})
    assert resp.status_code == 200, resp.text
    return resp.json()


def _order_body(frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай", **extra}


def _city_tariff() -> Tariff:
    with Session(engine) as s:
        return s.exec(select(Tariff).where(Tariff.zone == "city",
                                           Tariff.category == "standard")).first()


def _let_time_pass(fake_redis, driver_id: int, seconds: int = 120):
    """Сдвинуть «время» между пингами водителя, не засыпая в тесте.

    В бою пинги идут раз в 10–15 секунд. В тесте они уходят подряд за миллисекунды, и
    анти-фрод честно считает это телепортом (200 км/ч), а точку — поддельной. Поэтому
    состариваем якорь анти-фрода: для кода это выглядит как «прошло две минуты».
    """
    raw = fake_redis.get(f"af:pt:{driver_id}")
    if not raw:
        return
    text = raw.decode() if isinstance(raw, bytes) else raw
    lat, lng, ts = text.split(",")
    fake_redis.set(f"af:pt:{driver_id}", f"{lat},{lng},{float(ts) - seconds}")


def _factor(body: dict, code: str):
    for f in body.get("price_factors", []):
        if f.get("code") == code:
            return f
    return None


# ============================ Формула строки ============================
def test_pickup_fee_formula_free_km_rate_and_cap():
    """Бесплатные километры, ставка за остальные, потолок. Числа руками, без обращения к БД."""
    t = Tariff(zone="city", category="standard", base=70, per_km=11.5, per_min=5.0,
               min_price=100, pickup_free_km=3.0, pickup_per_km=11.5, pickup_max_rub=400)

    # 2 км по прямой → 2,6 км по дорогам → всё внутри бесплатных трёх.
    assert isv.pickup_fee_rub(t, 2.0) == 0
    # 20 км по прямой → 26 км по дорогам → платных 23 → 264,5 ₽ → округление до 10.
    assert isv.pickup_fee_rub(t, 20.0) == 260
    # 100 км → упёрлись в потолок, а не «сколько получилось».
    assert isv.pickup_fee_rub(t, 100.0) == 400


def test_pickup_fee_is_zero_when_tariff_is_not_configured():
    """База, куда миграция ещё не дошла: молча начать брать новые деньги нельзя."""
    off = Tariff(zone="city", category="standard", pickup_per_km=0.0, pickup_max_rub=0)
    assert isv.pickup_fee_rub(off, 50.0) == 0
    assert isv.pickup_enabled(off) is False
    # Расстояние неизвестно (рядом никого) — тоже ноль, а не «наверное далеко».
    on = Tariff(zone="city", category="standard", pickup_free_km=3.0,
                pickup_per_km=11.5, pickup_max_rub=400)
    assert isv.pickup_fee_rub(on, None) == 0
    assert isv.pickup_enabled(on) is True


def test_old_pickup_multiplier_is_off_so_we_never_charge_twice():
    """СТОРОЖ. Дальняя подача теперь строка счёта. Если кто-то вернёт множитель, не убрав
    строку, мы возьмём с человека за одну и ту же дорогу дважды — и никто этого не заметит,
    потому что обе цифры по отдельности выглядят разумно."""
    assert settings.taxi_pickup_max_k == 1.0
    assert pricing.pickup_k_for(60) == 1.0
    assert pricing.pickup_k_for(None) == 1.0


# ============================ Цена в оценке ============================
def test_close_car_adds_nothing(client, user_factory, fake_redis):
    """Машина у подъезда — строки нет вообще. Подача внутри бесплатных километров."""
    _driver_at(client, user_factory, ORIG, "NearDrv")
    pax = user_factory("NearPickupPax")
    body = _estimate(client, pax)

    assert body["pickup_fee"] == 0
    assert body["pickup_pending"] is False
    assert body["price"] == body["ride_price"]
    assert _factor(body, "pickup_fee") is None


def test_far_car_is_a_money_line_added_to_the_ride(client, user_factory, fake_redis):
    """Машина за 8 км: в цене появляется отдельная строка, и итог = поездка + строка."""
    _driver_at(client, user_factory, _north_of(ORIG, 8.0), "FarDrv")
    pax = user_factory("FarPickupPax")
    body = _estimate(client, pax)

    fee = body["pickup_fee"]
    assert fee > 0
    assert body["price"] == body["ride_price"] + fee
    assert body["pickup_km"] == pytest.approx(8.0 * settings.instant_road_k, rel=0.05)
    # Наценки нет: подача — это компенсация бензина, а не «дороже, потому что спрос».
    assert body["dynamic_k"] == 1.0

    factor = _factor(body, "pickup_fee")
    assert factor is not None
    assert factor["kind"] == "money"
    assert factor["amount_rub"] == fee
    # Объяснение — на двух языках и словами человека, а не «коэффициент подачи».
    assert factor["title_ru"] and factor["title_ba"]
    assert "водител" in factor["description_ru"].lower()
    note = body["pickup_note"]
    assert note and note["ru"] and note["ba"]


def test_no_cars_nearby_promises_a_ceiling_instead_of_inventing_a_price(client, user_factory,
                                                                       fake_redis):
    """Рядом никого: цифры нет и выдумывать её нельзя. Говорим потолок и когда покажем точную."""
    pax = user_factory("EmptyPickupPax")
    body = _estimate(client, pax)

    assert body["pickup_fee"] == 0
    assert body["pickup_pending"] is True
    assert body["price"] == body["ride_price"]
    assert body["pickup_max_rub"] > 0

    factor = _factor(body, "pickup_pending")
    assert factor is not None
    assert str(body["pickup_max_rub"]) in factor["description_ru"]
    # Про бесплатную отмену человек должен прочитать здесь же, а не узнать потом.
    assert str(settings.cancel_free_minutes) in factor["description_ru"]
    assert body["pickup_note"]["ba"]


def test_every_class_shows_its_own_pickup(client, user_factory, fake_redis):
    """Витрина классов: у каждого своя подача. Считать её по выбранному классу значит
    показать в списке цену, которой при переключении не будет."""
    _driver_at(client, user_factory, _north_of(ORIG, 8.0), "ClassPickupDrv")
    pax = user_factory("ClassPickupPax")
    body = _estimate(client, pax)

    assert body["options"], "витрина классов не должна быть пустой"
    for opt in body["options"]:
        assert opt["price"] == opt["ride_price"] + opt["pickup_fee"]
        assert opt["pickup_fee"] > 0


# ============================ «Водителю по пути» — подача дешевле ============================
def test_enroute_discount_is_half_and_rounded():
    """Скидка «по пути»: ровно половина, округление до 10 ₽, ноль не уходит в минус."""
    assert isv.pickup_fee_after_enroute(190, enroute=True) == 100    # 95 → округляем до 10
    assert isv.pickup_fee_after_enroute(190, enroute=False) == 190
    assert isv.pickup_fee_after_enroute(0, enroute=True) == 0


def test_approaching_needs_a_previous_point_and_real_progress(client, user_factory, fake_redis):
    """Признак «он реально едет сюда» = расстояние до точки подачи сократилось.

    Одного пинга мало: пока не с чем сравнивать, скидки нет. Сомнение стоит водителю денег,
    поэтому по умолчанию — не даём."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 12.0), "ApproachDrv")
    # Первый пинг: прошлой точки ещё нет.
    assert isv._approaching(d["id"], ORIG[0], ORIG[1]) is False

    # Проехал в нашу сторону — вот теперь видно, что он едет сюда.
    _let_time_pass(fake_redis, d["id"])
    near = _north_of(ORIG, 8.0)
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": near[0], "lng": near[1]}).status_code == 200
    assert isv._approaching(d["id"], ORIG[0], ORIG[1]) is True

    # Отъехал обратно — «по пути» больше не считается.
    _let_time_pass(fake_redis, d["id"])
    far = _north_of(ORIG, 13.0)
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": far[0], "lng": far[1]}).status_code == 200
    assert isv._approaching(d["id"], ORIG[0], ORIG[1]) is False


def test_driver_returning_to_his_own_village_gets_the_discount(client, user_factory,
                                                               fake_redis, monkeypatch):
    """Он выбрал это село своей рабочей зоной, а сам сейчас вне её — значит домой поедет
    так и так, и наш заказ просто оказался по дороге."""
    from app import geo
    d = _driver_at(client, user_factory, _north_of(ORIG, 10.0), "HomeDrv")
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        p.work_zone, p.work_city = "city", "Баймак"
        s.add(p)
        s.commit()

    home = geo.Area(None, "Баймакский", "Башкортостан")
    away = geo.Area(None, "Сибайский", "Башкортостан")
    monkeypatch.setattr(geo, "area_at",
                        lambda session, lat, lng: home if abs(lat - ORIG[0]) < 0.01 else away)
    monkeypatch.setattr(geo, "same_area",
                        lambda kind, city, district, area: area.district == "Баймакский")

    with Session(engine) as s:
        assert isv.pickup_enroute(s, d["id"], ORIG[0], ORIG[1]) is True
        # Тот же водитель, но точка подачи НЕ в его зоне — скидки нет.
        assert isv.pickup_enroute(s, d["id"], ORIG[0] + 1.0, ORIG[1]) is False


def test_unknown_place_never_cheapens_the_driver(client, user_factory, fake_redis, monkeypatch):
    """Справочник не узнал точку → это НЕ «по пути». `same_area` намеренно не режет
    неизвестное (для подбора так правильно), но в деньгах «не знаю» не может значить
    «плачу водителю меньше»."""
    from app import geo
    d = _driver_at(client, user_factory, _north_of(ORIG, 10.0), "UnknownAreaDrv")
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        p.work_zone, p.work_city = "city", "Баймак"
        s.add(p)
        s.commit()
    monkeypatch.setattr(geo, "area_at", lambda session, lat, lng: geo._UNKNOWN_AREA)

    with Session(engine) as s:
        assert isv.pickup_enroute(s, d["id"], ORIG[0], ORIG[1]) is False


def test_enroute_halves_the_line_for_the_passenger(client, user_factory, fake_redis):
    """Сквозной сценарий: водитель едет в нашу сторону → пассажир видит половину суммы
    и объяснение, почему дешевле."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 12.0), "EnrouteDrv")
    pax = user_factory("EnroutePax")
    full = _estimate(client, pax)["pickup_fee"]
    assert full > 0 and _estimate(client, pax)["pickup_enroute"] is False

    _let_time_pass(fake_redis, d["id"])
    near = _north_of(ORIG, 8.0)
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": near[0], "lng": near[1]}).status_code == 200
    body = _estimate(client, pax)

    assert body["pickup_enroute"] is True
    assert body["pickup_fee"] == isv.pickup_fee_after_enroute(body["pickup_full_fee"], True)
    assert body["pickup_fee"] < body["pickup_full_fee"]
    assert body["price"] == body["ride_price"] + body["pickup_fee"]
    # Выгода должна быть НАЗВАНА: иначе человек видит просто другое число.
    factor = _factor(body, "pickup_fee")
    assert "по пути" in factor["title_ru"].lower()
    assert str(body["pickup_full_fee"]) in factor["description_ru"]
    assert factor["description_ba"]
    assert str(body["pickup_full_fee"]) in body["pickup_note"]["ru"]
    # Скидка распространяется на все классы витрины, а не только на выбранный.
    for opt in body["options"]:
        assert opt["price"] == opt["ride_price"] + opt["pickup_fee"]


def test_enroute_is_written_on_the_order(client, user_factory, fake_redis):
    """Факт скидки живёт на заказе: иначе в чеке не объяснить, почему у соседа дороже."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 12.0), "EnrouteOrderDrv")
    _let_time_pass(fake_redis, d["id"])
    near = _north_of(ORIG, 8.0)
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": near[0], "lng": near[1]}).status_code == 200
    pax = user_factory("EnrouteOrderPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()

    assert order["pickup_enroute"] is True
    assert order["pickup_fee_kop"] > 0
    assert order["price_estimate"] == order["ride_price"] + order["pickup_fee_kop"] // 100


# ============================ «Сюда уже едет машина — подожди» ============================
def test_wait_hint_appears_when_a_car_is_already_heading_here(client, user_factory, fake_redis):
    """Честная форма идеи «поделить подачу»: соседу говорим, что машина и так сюда едет.

    Он ничего не теряет от ожидания и экономит подачу целиком — в отличие от «деления»,
    где второй платил бы за километры, которые ради него никто не проехал."""
    # Кого-то уже везут В наше село: эта машина освободится здесь.
    driver_in = _driver_at(client, user_factory, _north_of(ORIG, 6.0), "IncomingDrv")
    rider = user_factory("IncomingPax")
    incoming = client.post("/instant/orders", headers=rider["auth"],
                           json=_order_body(frm=_north_of(ORIG, 6.0), to=ORIG)).json()
    for step in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{incoming['id']}/{step}",
                           headers=driver_in["auth"]).status_code == 200

    # Второй человек из этого же села смотрит цену: ближайшая свободная машина далеко.
    _driver_at(client, user_factory, _north_of(ORIG, 9.0), "FarWaitDrv")
    neighbour = user_factory("NeighbourPax")
    body = _estimate(client, neighbour)

    hint = body["pickup_wait_hint"]
    assert body["pickup_fee"] > 0
    assert hint is not None, body["pickup_fee"]
    assert 1 <= hint["minutes"] <= settings.pickup_wait_max_min
    assert hint["save_rub"] == body["pickup_fee"]
    assert str(hint["minutes"]) in hint["ru"] and hint["ba"]
    # Никакой личности: сосед не должен по подсказке вычислить, кто именно едет.
    for leak in ("driver", "order_id", "from", "phone", "name"):
        assert leak not in hint


def test_wait_hint_ignores_cars_that_are_leaving(client, user_factory, fake_redis):
    """Машина, которая УВОЗИТ пассажира отсюда, соседу ничем не поможет — она уедет вместе
    с ним. Такую подсказку показывать нельзя: это обещание, которое не сбудется."""
    d = _driver_at(client, user_factory, ORIG, "LeavingDrv")
    rider = user_factory("LeavingPax")
    leaving = client.post("/instant/orders", headers=rider["auth"],
                          json=_order_body(frm=ORIG, to=DEST)).json()
    for step in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{leaving['id']}/{step}",
                           headers=d["auth"]).status_code == 200

    _driver_at(client, user_factory, _north_of(ORIG, 9.0), "FarLeaveDrv")
    neighbour = user_factory("LeaveNeighbourPax")
    body = _estimate(client, neighbour)

    assert body["pickup_wait_hint"] is None


def test_wait_hint_is_silent_when_there_is_nothing_to_save(client, user_factory, fake_redis):
    """Машина рядом, подача ничего не стоит — просить человека ждать не за что."""
    _driver_at(client, user_factory, ORIG, "NoSaveDrv")
    pax = user_factory("NoSavePax")
    body = _estimate(client, pax)

    assert body["pickup_fee"] == 0
    assert body["pickup_wait_hint"] is None


# ============================ Заказ, accept, деньги ============================
def test_order_keeps_the_pickup_line_through_to_the_receipt(client, user_factory, fake_redis):
    """Строка живёт от заказа до чека: в заказе, после accept и в итоговой цене."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 8.0), "FlowPickupDrv")
    pax = user_factory("FlowPickupPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["status"] == "offered", order
    oid = order["id"]

    fee_kop = order["pickup_fee_kop"]
    assert fee_kop > 0
    assert order["price_estimate"] == order["ride_price"] + fee_kop // 100
    assert order["pickup_pending"] is False

    for step in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200
    done = client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()

    assert done["status"] == "done"
    assert done["pickup_fee_kop"] == fee_kop           # цена подачи не «уехала» по дороге
    assert done["price_final"] == done["ride_price"] + fee_kop // 100


def test_commission_is_not_taken_from_the_fuel_compensation(client, user_factory, fake_redis):
    """Решение Александра 2026-08-23: комиссия — с работы, а не с бензина.

    Строка «машина едет издалека» уходит водителю целиком. Иначе мы зарабатываем на его
    топливе, и заказ из «еле окупается» снова становится «не поеду»."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 8.0), "FeeDrv")
    pax = user_factory("FeePax")
    oid = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()["id"]
    for step in ("accept", "arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200

    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        debt = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == d["id"]).order_by(CommissionDebt.id.desc())).first()
        percent = debt_mod.driver_fee_percent(s, d["id"], order.created_at)

    pickup_rub = order.pickup_fee_kop // 100
    assert pickup_rub > 0
    assert debt is not None
    # База комиссии — цена МИНУС компенсация.
    ride_only_kop = (order.price_final - pickup_rub) * 100
    assert debt.amount_kop == debt_mod.fee_kop_for(ride_only_kop, percent)
    # И это строго меньше, чем комиссия со всей суммы, — иначе тест ничего не проверяет.
    assert debt.amount_kop < debt_mod.fee_kop_for(order.price_final * 100, percent)


def test_promo_discount_is_not_calculated_from_the_drivers_fuel():
    """Промокод — наш подарок пассажиру за наш счёт. Считать его долей от компенсации
    бензина неправильно: потолок «не больше N% от цены» рос бы ровно тогда, когда водителю
    и так тяжело."""
    from app import promo_ride

    order = InstantOrder(passenger_id=1, price_estimate=360, ride_price=170,
                         pickup_fee_kop=190 * 100)
    assert promo_ride.discountable_rub(order) == 170
    # Пассажир при этом платит полную сумму — компенсация из чека никуда не девается.
    assert promo_ride.price_kop(order) == 360 * 100


def test_pending_pickup_is_settled_by_the_driver_who_actually_agreed(client, user_factory,
                                                                    fake_redis):
    """«Рядом никого» при заказе → сумму фиксируем при accept по НАСТОЯЩЕЙ позиции водителя."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 9.0), "PendingDrv")
    pax = user_factory("PendingPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    oid = order["id"]

    # Приводим заказ к состоянию «создавался, когда рядом никого не было»: строки нет,
    # обещан потолок. Так выглядит заказ, пролежавший в очереди «подожду машину».
    ride_price = order["ride_price"]
    with Session(engine) as s:
        row = s.get(InstantOrder, oid)
        row.pickup_fee_kop = 0
        row.pickup_km = 0.0
        row.pickup_pending = True
        row.price_estimate = ride_price
        s.add(row)
        s.commit()

    accepted = client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    assert accepted.status_code == 200
    body = accepted.json()

    assert body["pickup_pending"] is False
    assert body["pickup_fee_kop"] > 0
    assert body["price_estimate"] == ride_price + body["pickup_fee_kop"] // 100
    assert body["pickup_km"] == pytest.approx(9.0 * settings.instant_road_k, rel=0.05)

    # Молча дорожать нельзя: пассажир получает отдельное уведомление с суммой и напоминанием
    # про бесплатную отмену — он в этот момент ещё внутри окна и может отказаться.
    with Session(engine) as s:
        notes = s.exec(select(Notification).where(
            Notification.user_id == pax["id"], Notification.ref_kind == "instant",
            Notification.ref_id == oid)).all()
    added = body["pickup_fee_kop"] // 100
    money_notes = [n for n in notes if str(added) in n.body_ru]
    assert money_notes, [n.body_ru for n in notes]
    assert money_notes[0].body_ba, "текст обязан быть на двух языках"
    assert str(settings.cancel_free_minutes) in money_notes[0].body_ru


def test_old_order_without_the_new_fields_still_prices_correctly():
    """Заказы, созданные до этой правки, полей не имеют. Там вся сумма — это поездка,
    и пересчёт цены не должен придумывать им компенсацию из воздуха."""
    old = InstantOrder(passenger_id=1, price_estimate=240, ride_price=0, pickup_fee_kop=0)

    assert isv.order_ride_price(old) == 240
    assert isv.order_compensation_rub(old) == 0
    # Пересчёт (смена адреса) переводит заказ на новую схему, ничего не теряя.
    assert isv.set_ride_price(old, 300) == 300
    assert old.ride_price == 300 and old.price_estimate == 300


def test_fixed_pickup_is_not_recalculated_on_accept(client, user_factory, fake_redis):
    """Цена, которую человек видел, нажимая «Заказать», после accept не меняется."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 8.0), "FixedDrv")
    pax = user_factory("FixedPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    oid, before = order["id"], order["price_estimate"]

    # Водитель отъехал дальше — на уже посчитанную цену это влиять не должно.
    assert client.post("/instant/presence", headers=d["auth"],
                       json={"lat": _north_of(ORIG, 14.0)[0],
                             "lng": _north_of(ORIG, 14.0)[1]}).status_code == 200
    body = client.post(f"/instant/orders/{oid}/accept", headers=d["auth"]).json()

    assert body["price_estimate"] == before
    assert body["pickup_fee_kop"] == order["pickup_fee_kop"]


def test_changing_destination_keeps_the_pickup_line(client, user_factory, fake_redis):
    """Пассажир поменял адрес: цена поездки пересчитана, компенсация за подачу осталась —
    эти километры водитель уже проехал, и забирать их не за что."""
    d = _driver_at(client, user_factory, _north_of(ORIG, 8.0), "DestDrv")
    pax = user_factory("DestPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    oid = order["id"]
    fee_kop = order["pickup_fee_kop"]
    assert fee_kop > 0
    for step in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200

    # Ближе прежнего: зона та же, цена не растёт втрое — адрес меняется сразу, без вопроса
    # водителю. Нам здесь важен пересчёт цены, а не ветка «спроси водителя».
    new_to = (52.650, 58.450)
    resp = client.post(f"/instant/orders/{oid}/destination", headers=pax["auth"],
                       json={"to_lat": new_to[0], "to_lng": new_to[1], "to_text": "Сибай, вокзал"})
    assert resp.status_code == 200, resp.text
    assert resp.json().get("applied") is True, resp.text
    changed = resp.json()["order"]

    assert changed["pickup_fee_kop"] == fee_kop
    assert changed["price_estimate"] == changed["ride_price"] + fee_kop // 100
