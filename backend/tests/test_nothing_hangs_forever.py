"""Ничто не должно висеть «активным» вечно.

Уже пойманный на проде случай этого класса: у поездки не было состояния «просрочена», и три
штуки от 5, 6 и 15 июля висели активными третью неделю — пассажирам не видно, а у водителя
в «моих поездках» они навсегда числились текущими. Закрыть их было нечем.

Здесь проверяется, что то же самое не происходит с остальными сущностями. Цена вечно живого
«активного» объекта тройная:

  • он засоряет чужую ленту — водитель откликается на заявку, по которой человек уехал
    ещё в мае;
  • он врёт владельцу — в «моих заявках» висит текущей та, что давно неактуальна;
  • он занимает место в потолке (`app/flood.py`): если объект не закрывается никогда,
    потолок «15 активных заявок» однажды превращается в пожизненный запрет.

Третий пункт — не теория: потолки появились 2026-08-06 и заявок это касалось напрямую.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import RideRequest, UserRole
from app.timeutil import utcnow

from test_api import _ride


def _make_request(client, pax, city_to="Уфа", note=""):
    # Комментарий свой у каждой: одинаковые заявки схлопываются как двойной тап (2026-08-06),
    # и до потолка так не дойти. Настоящий человек шлёт разное — его и проверяем.
    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": city_to, "seats": 1, "comment": note,
    })
    assert r.status_code == 200, f"заявка не создалась: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def _age_request(request_id: int, days: int) -> None:
    """Отправить заявку в прошлое — и по желаемому времени, и по дате создания."""
    with Session(engine) as s:
        req = s.get(RideRequest, request_id)
        past = utcnow() - timedelta(days=days)
        req.desired_at = past
        req.created_at = past
        s.add(req)
        s.commit()


def _in_feed(client, driver, request_id: int) -> bool:
    feed = client.get("/requests/feed", headers=driver["auth"])
    assert feed.status_code == 200, feed.text
    return any(x.get("id") == request_id for x in feed.json())


# ---------- Заявка пассажира ----------

def test_контроль_свежая_заявка_в_ленте_есть(client, user_factory):
    """Без этого контроля проверки ниже могли бы проходить просто потому, что лента пустая."""
    pax = user_factory("HangFreshPax")
    rid = _make_request(client, pax)
    driver = user_factory("HangFreshDrv", role=UserRole.driver)
    assert _in_feed(client, driver, rid), "свежей заявки нет в ленте водителя — лента сломана"


def test_прошедшая_заявка_уходит_из_ленты(client, user_factory):
    """Водитель не должен откликаться на заявку, по которой человек уехал три месяца назад."""
    pax = user_factory("HangOldPax")
    rid = _make_request(client, pax, city_to="Магнитогорск")
    _age_request(rid, days=90)

    driver = user_factory("HangOldDrv", role=UserRole.driver)
    assert not _in_feed(client, driver, rid), (
        "заявка трёхмесячной давности всё ещё висит в ленте водителей"
    )


def test_прошедшая_заявка_не_занимает_место_в_потолке(client, user_factory):
    """Прямое следствие для человека: потолок «15 активных заявок» не должен становиться
    пожизненным запретом только потому, что старые заявки не закрываются."""
    cap = settings.flood_active_requests_max
    pax = user_factory("HangCapPax")
    ids = [_make_request(client, pax, note=f"заявка {i}") for i in range(cap)]
    # Упёрлись в потолок — это правильно.
    over = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    })
    assert over.status_code == 429, f"потолок не сработал: {over.status_code} {over.text[:150]}"

    # Все заявки давно прошли — место обязано освободиться.
    for rid in ids:
        _age_request(rid, days=60)
    again = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    })
    assert again.status_code == 200, (
        f"все старые заявки давно прошли, а создать новую нельзя — потолок стал пожизненным "
        f"запретом: {again.status_code} {again.text[:200]}"
    )


def test_ночная_чистка_закрывает_прошедшие_заявки(client, user_factory):
    """Из ленты заявка уходит сразу по времени, но и в базе она не должна вечно числиться
    активной: в «моих заявках» у человека висело бы «ищем водителя» без конца."""
    from app.cleanup import close_past_requests

    pax = user_factory("HangCleanupPax")
    rid = _make_request(client, pax)
    _age_request(rid, days=30)

    closed = close_past_requests()
    assert closed >= 1, "ночная чистка не закрыла ни одной прошедшей заявки"

    with Session(engine) as s:
        req = s.get(RideRequest, rid)
    assert req.status != "active", f"заявка осталась активной после чистки: {req.status}"


def test_чистка_не_трогает_будущие_заявки(client, user_factory):
    """Обратная сторона: заявка «на послезавтра» должна остаться активной."""
    from app.cleanup import close_past_requests

    pax = user_factory("HangFuturePax")
    rid = _make_request(client, pax)
    close_past_requests()
    with Session(engine) as s:
        req = s.get(RideRequest, rid)
    assert req.status == "active", "чистка закрыла свежую заявку — человек остался без поиска"


# ---------- Поездка (регресс к найденному на проде) ----------

def test_прошедшая_поездка_закрывается_чисткой(client, user_factory):
    """Регресс к случаю 2026-08-03: три поездки висели активными третью неделю."""
    from app.cleanup import close_past_rides
    from app.models import Ride

    driver = user_factory("HangRideDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        ride.depart_at = utcnow() - timedelta(days=3)
        s.add(ride)
        s.commit()

    close_past_rides()
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
    assert ride.status != "active", f"поездка трёхдневной давности осталась активной: {ride.status}"


def test_прошедшая_поездка_не_занимает_место_в_потолке(client, user_factory):
    """То же следствие, что у заявок: старые поездки не должны запирать публикацию навсегда."""
    from app.cleanup import close_past_rides
    from app.models import Ride

    cap = settings.flood_active_rides_max
    driver = user_factory("HangRideCapDrv", role=UserRole.driver)
    ids = []
    for i in range(cap):
        r = client.post("/rides", headers=driver["auth"], json={
            "from_city": "Сибай", "to_city": "Уфа", "seats": 3, "price": 500,
            "depart_at": (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1, minutes=i))
            .replace(microsecond=0).isoformat(),
        })
        assert r.status_code == 200, f"публикация {i + 1} не прошла: {r.text[:150]}"
        ids.append(r.json()["id"])

    with Session(engine) as s:
        for rid in ids:
            ride = s.get(Ride, rid)
            ride.depart_at = utcnow() - timedelta(days=5)
            s.add(ride)
        s.commit()
    close_past_rides()

    again = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 3, "price": 500,
        "depart_at": (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=2))
        .replace(microsecond=0).isoformat(),
    })
    assert again.status_code == 200, (
        f"все прошлые поездки закрыты, а публиковать нельзя: {again.status_code} {again.text[:200]}"
    )


# ---------- Отклики на закрытую заявку ----------

def test_на_прошедшую_заявку_нельзя_откликнуться(client, user_factory):
    """Если заявка ушла из ленты, но отклик по прямой ссылке проходит — водитель звонит
    человеку, который уехал месяц назад."""
    pax = user_factory("HangRespPax")
    rid = _make_request(client, pax)
    _age_request(rid, days=45)
    from app.cleanup import close_past_requests
    close_past_requests()

    driver = user_factory("HangRespDrv", role=UserRole.driver)
    r = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})
    assert r.status_code != 200, "водитель откликнулся на давно прошедшую заявку"


def test_список_моих_заявок_переживает_закрытие(client, user_factory):
    """Закрытая заявка не должна пропадать из истории человека — он должен понимать,
    что с ней стало."""
    from app.cleanup import close_past_requests

    pax = user_factory("HangMinePax")
    rid = _make_request(client, pax)
    _age_request(rid, days=20)
    close_past_requests()

    mine = client.get("/requests/mine", headers=pax["auth"])
    assert mine.status_code == 200, mine.text
    assert any(x.get("id") == rid for x in mine.json()), (
        "закрытая заявка исчезла из «моих заявок» — человек не понимает, что с ней стало"
    )


# ---------- Посылка ----------

def _make_parcel(client, sender, phone="+79990009920"):
    r = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": phone, "rules_accepted": True,
    })
    assert r.status_code == 200, f"посылка не создалась: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def _age_parcel(parcel_id: int, days: int) -> None:
    from app.models import ParcelDelivery
    with Session(engine) as s:
        p = s.get(ParcelDelivery, parcel_id)
        p.created_at = utcnow() - timedelta(days=days)
        s.add(p)
        s.commit()


def _parcel_in_feed(client, courier, parcel_id: int) -> bool:
    feed = client.get("/parcels/available", headers=courier["auth"])
    assert feed.status_code == 200, feed.text
    return any(x.get("id") == parcel_id for x in feed.json())


def test_контроль_свежая_посылка_в_ленте_есть(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("HangParcelFreshSender")
    pid = _make_parcel(client, sender, phone="+79990009921")
    courier = user_factory("HangParcelFreshCourier", role=UserRole.driver)
    assert _parcel_in_feed(client, courier, pid), "свежей посылки нет в ленте — лента сломана"


def test_никем_не_взятая_посылка_уходит_из_ленты(client, user_factory, monkeypatch):
    """Отправитель давно отвёз гостинцы сам, а посылка всё висит: курьер берёт её через
    три месяца, звонит — и слышит «я уже сам съездил»."""
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("HangParcelOldSender")
    pid = _make_parcel(client, sender, phone="+79990009922")
    _age_parcel(pid, days=90)

    courier = user_factory("HangParcelOldCourier", role=UserRole.driver)
    assert not _parcel_in_feed(client, courier, pid), (
        "посылка трёхмесячной давности всё ещё висит в ленте курьеров"
    )


def test_старая_посылка_не_занимает_место_в_потолке(client, user_factory, monkeypatch):
    """Как с заявками: потолок «15 посылок в работе» не должен стать пожизненным запретом."""
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    cap = settings.flood_active_parcels_max
    sender = user_factory("HangParcelCapSender")
    ids = [_make_parcel(client, sender, phone=f"+7999001{i:04d}") for i in range(cap)]
    over = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "ещё", "receiver_name": "Х",
        "receiver_phone": "+79990009999", "rules_accepted": True,
    })
    assert over.status_code == 429, f"потолок не сработал: {over.status_code} {over.text[:150]}"

    for pid in ids:
        _age_parcel(pid, days=60)
    again = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "новая", "receiver_name": "Х",
        "receiver_phone": "+79990009998", "rules_accepted": True,
    })
    assert again.status_code == 200, (
        f"все старые посылки давно протухли, а создать новую нельзя — потолок стал "
        f"пожизненным запретом: {again.status_code} {again.text[:200]}"
    )


def test_ночная_чистка_закрывает_протухшие_посылки(client, user_factory, monkeypatch):
    from app.cleanup import close_stale_parcels
    from app.models import ParcelDelivery

    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("HangParcelCleanupSender")
    pid = _make_parcel(client, sender, phone="+79990009923")
    _age_parcel(pid, days=45)

    assert close_stale_parcels() >= 1, "чистка не закрыла ни одной протухшей посылки"
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
    assert p.status != "created", f"посылка осталась открытой после чистки: {p.status}"


def test_чистка_не_трогает_свежие_посылки(client, user_factory, monkeypatch):
    from app.cleanup import close_stale_parcels
    from app.models import ParcelDelivery

    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("HangParcelFutureSender")
    pid = _make_parcel(client, sender, phone="+79990009924")
    close_stale_parcels()
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
    assert p.status == "created", "чистка закрыла свежую посылку — отправитель остался без курьера"


def test_протухшую_посылку_нельзя_взять(client, user_factory, monkeypatch):
    """Из ленты ушла, но прямая ссылка не должна обходить фильтр."""
    from app.cleanup import close_stale_parcels

    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("HangParcelTakeSender")
    pid = _make_parcel(client, sender, phone="+79990009925")
    _age_parcel(pid, days=70)
    close_stale_parcels()

    courier = user_factory("HangParcelTakeCourier", role=UserRole.driver)
    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert r.status_code != 200, "курьер взял давно протухшую посылку"


# ---------- Дети закрытых объектов ----------
# Правило из lessons.md: закрыв родителя, обязательно закрой детей. Иначе поездка уже
# «просрочена», а бронь на ней навсегда «ждём подтверждения» — и человек читает вечное
# ожидание по поездке, которая уехала неделю назад.

def test_бронь_на_прошедшей_поездке_не_висит_вечно(client, user_factory):
    """Пассажир записался, водитель не подтвердил, поездка уехала. В приложении у пассажира
    не должно остаться вечного «ждём подтверждения»."""
    from app.cleanup import close_past_rides
    from app.models import Booking, Ride

    driver = user_factory("ChildBookDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("ChildBookPax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]

    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        ride.depart_at = utcnow() - timedelta(days=5)
        s.add(ride)
        s.commit()
    close_past_rides()

    with Session(engine) as s:
        b = s.get(Booking, bid)
    assert b.status.value if hasattr(b.status, "value") else b.status, "бронь исчезла"
    assert str(b.status).endswith("cancelled"), (
        f"поездка закрыта, а бронь осталась {b.status} — у пассажира вечное «ждём подтверждения»"
    )


def test_подтверждённая_бронь_состоявшейся_поездки_становится_завершённой(client, user_factory):
    """Обратная сторона: если поездка реально состоялась, бронь обязана стать завершённой,
    а не отменённой — на завершённых строится рейтинг и история человека."""
    from app.cleanup import close_past_rides
    from app.models import Booking, Ride

    driver = user_factory("ChildDoneDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("ChildDonePax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200

    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        ride.depart_at = utcnow() - timedelta(days=5)
        s.add(ride)
        s.commit()
    close_past_rides()

    with Session(engine) as s:
        b = s.get(Booking, bid)
        r = s.get(Ride, ride_id)
    assert str(r.status).endswith("done"), f"поездка с подтверждённой бронью стала {r.status}"
    assert str(b.status).endswith("done"), (
        f"поездка состоялась, а бронь осталась {b.status} — поездка не попадёт в историю и рейтинг"
    )


def test_отклик_на_закрытой_заявке_не_висит_в_торге(client, user_factory):
    """Водитель предложил цену, пассажир не ответил, заявка закрылась по времени.
    В «моих откликах» не должно остаться открытого торга по заявке, которой уже нет."""
    from app.cleanup import close_past_requests
    from app.models import RequestResponse

    pax = user_factory("ChildRespPax")
    rid = _make_request(client, pax)
    driver = user_factory("ChildRespDrv", role=UserRole.driver)
    resp_id = client.post(f"/requests/{rid}/respond", headers=driver["auth"],
                          json={"price": 500}).json()["id"]

    _age_request(rid, days=30)
    close_past_requests()

    with Session(engine) as s:
        rr = s.get(RequestResponse, resp_id)
    assert rr.status != "offered", (
        f"заявка закрыта, а отклик остался {rr.status} — водитель ждёт ответа по заявке, "
        "которой уже месяц нет"
    )

    mine = client.get("/responses/mine", headers=driver["auth"])
    assert mine.status_code == 200, mine.text
    row = next((x for x in mine.json() if x.get("id") == resp_id), None)
    assert row is not None, "отклик исчез из «моих откликов» — человек не понимает, что с ним стало"
    assert row.get("status") != "offered", f"в списке отклик всё ещё «в торге»: {row}"


# ---------- Забытый такси-заказ ----------
# Самый дорогой из зависших: у водителя стоит защита «нельзя взять второй заказ при активном
# первом», поэтому один забытый заказ навсегда закрывал ему возможность работать.

@pytest.fixture
def fake_redis():
    import fakeredis

    from app import instant_service as isv
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


TAXI_ORIG = (52.5911, 58.3178)
TAXI_DEST = (52.9128, 58.6689)


def _taxi_order(client, passenger, driver):
    assert client.post("/driver/online", headers=driver["auth"],
                       json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": TAXI_ORIG[0], "lng": TAXI_ORIG[1]}).status_code == 200
    r = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": TAXI_ORIG[0], "from_lng": TAXI_ORIG[1],
        "to_lat": TAXI_DEST[0], "to_lng": TAXI_DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert r.status_code == 200, f"заказ не создался: {r.status_code} {r.text[:200]}"
    oid = r.json()["id"]
    assert client.post(f"/instant/orders/{oid}/accept",
                       headers=driver["auth"]).status_code == 200
    return oid


def _age_order(order_id: int, hours: int) -> None:
    from app.models import InstantOrder
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        o.created_at = utcnow() - timedelta(hours=hours)
        s.add(o)
        s.commit()


def test_забытый_заказ_не_запирает_водителя_навсегда(client, user_factory, fake_redis):
    """Водитель не нажал «завершил» — и без чистки он больше НИКОГДА не мог взять заказ."""
    from app.cleanup import close_stale_orders

    passenger = user_factory("StaleTaxiPax")
    driver = user_factory("StaleTaxiDrv", role=UserRole.driver)
    oid = _taxi_order(client, passenger, driver)
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])
    _age_order(oid, hours=48)

    # Контроль: пока заказ висит, второй взять нельзя — это правильная защита.
    pax2 = user_factory("StaleTaxiPax2")
    oid2 = client.post("/instant/orders", headers=pax2["auth"], json={
        "from_lat": TAXI_ORIG[0], "from_lng": TAXI_ORIG[1],
        "to_lat": TAXI_DEST[0], "to_lng": TAXI_DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    blocked = client.post(f"/instant/orders/{oid2}/accept", headers=driver["auth"])
    assert blocked.status_code == 409, (
        f"защита «один заказ за раз» не работает — проверка ниже ничего не докажет: {blocked.status_code}"
    )

    assert close_stale_orders() >= 1, "чистка не закрыла ни одного забытого заказа"

    pax3 = user_factory("StaleTaxiPax3")
    oid3 = client.post("/instant/orders", headers=pax3["auth"], json={
        "from_lat": TAXI_ORIG[0], "from_lng": TAXI_ORIG[1],
        "to_lat": TAXI_DEST[0], "to_lng": TAXI_DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    freed = client.post(f"/instant/orders/{oid3}/accept", headers=driver["auth"])
    assert freed.status_code == 200, (
        f"забытый заказ закрыт, а водитель всё ещё не может работать: "
        f"{freed.status_code} {freed.text[:200]}"
    )


def test_человек_сидел_в_машине_поездка_считается_состоявшейся(client, user_factory, fake_redis):
    """Две правды у закрытия: если пассажир был в машине, честнее «завершена», а не «отменена» —
    иначе поездка исчезает из истории обоих."""
    from app.cleanup import close_stale_orders
    from app.models import InstantOrder

    passenger = user_factory("StaleOnboardPax")
    driver = user_factory("StaleOnboardDrv", role=UserRole.driver)
    oid = _taxi_order(client, passenger, driver)
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])
    _age_order(oid, hours=48)
    close_stale_orders()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
    assert str(o.status).endswith("done"), f"поездка с пассажиром в машине закрылась как {o.status}"


def test_машина_так_и_не_подъехала_поездка_считается_отменённой(client, user_factory, fake_redis):
    """А если человек в машину не сел — «завершена» было бы враньём и попало бы в статистику."""
    from app.cleanup import close_stale_orders
    from app.models import InstantOrder

    passenger = user_factory("StaleAcceptedPax")
    driver = user_factory("StaleAcceptedDrv", role=UserRole.driver)
    oid = _taxi_order(client, passenger, driver)
    _age_order(oid, hours=48)
    close_stale_orders()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
    assert str(o.status).endswith("cancelled"), (
        f"человек в машину не сел, а заказ закрылся как {o.status}"
    )


def test_чистка_не_трогает_идущую_поездку(client, user_factory, fake_redis):
    """Обратная сторона: живой заказ рубить нельзя — человек прямо сейчас едет."""
    from app.cleanup import close_stale_orders
    from app.models import InstantOrder

    passenger = user_factory("StaleLivePax")
    driver = user_factory("StaleLiveDrv", role=UserRole.driver)
    oid = _taxi_order(client, passenger, driver)
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])

    close_stale_orders()
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
    assert str(o.status).endswith("onboard"), (
        f"чистка оборвала идущую поездку: заказ стал {o.status}"
    )


# ---------- Автомат закрыл — человек узнал ----------
# Правило из этого же аудита: если объект человека закрывает автомат, человек обязан узнать.
# Иначе он ждёт: отправитель ждёт курьера по посылке, снятой с ленты месяц назад; пассажир
# ждёт отклика по заявке, которая давно закрыта. Закрыть молча — хуже, чем не закрывать.

def _notes_count(user_id: int) -> int:
    from app.models import Notification
    with Session(engine) as s:
        return len(list(s.exec(select(Notification).where(
            Notification.user_id == user_id)).all()))


def test_закрыли_заявку_пассажир_узнал(client, user_factory):
    from app.cleanup import close_past_requests

    pax = user_factory("NotifyReqPax")
    rid = _make_request(client, pax)
    _age_request(rid, days=30)
    before = _notes_count(pax["id"])

    close_past_requests()
    assert _notes_count(pax["id"]) > before, (
        "заявку закрыли молча — человек продолжает ждать отклика по закрытой заявке"
    )


def test_закрыли_посылку_отправитель_узнал(client, user_factory, monkeypatch):
    from app.cleanup import close_stale_parcels

    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("NotifyParcelSender")
    pid = _make_parcel(client, sender, phone="+79990009931")
    _age_parcel(pid, days=45)
    before = _notes_count(sender["id"])

    close_stale_parcels()
    assert _notes_count(sender["id"]) > before, (
        "посылку сняли с ленты молча — отправитель ждёт курьера, которого уже не будет"
    )


def test_закрыли_поездку_водитель_узнал(client, user_factory):
    from app.cleanup import close_past_rides
    from app.models import Ride

    driver = user_factory("NotifyRideDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        ride.depart_at = utcnow() - timedelta(days=3)
        s.add(ride)
        s.commit()
    before = _notes_count(driver["id"])

    close_past_rides()
    assert _notes_count(driver["id"]) > before, (
        "поездку закрыли молча — у водителя она просто исчезла из текущих"
    )


def test_закрыли_забытый_заказ_водитель_узнал(client, user_factory, fake_redis):
    """Водителю это важнее всех: пока заказ висел, он не мог взять ни одного нового."""
    from app.cleanup import close_stale_orders

    passenger = user_factory("NotifyOrderPax")
    driver = user_factory("NotifyOrderDrv", role=UserRole.driver)
    oid = _taxi_order(client, passenger, driver)
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])
    _age_order(oid, hours=48)
    before = _notes_count(driver["id"])

    close_stale_orders()
    assert _notes_count(driver["id"]) > before, (
        "забытый заказ закрыли молча — водитель не знает, почему снова может работать"
    )


def test_свежие_объекты_никого_не_будят(client, user_factory):
    """Обратная сторона: чистка не должна слать уведомления по тому, что не закрывала."""
    from app.cleanup import close_past_requests

    pax = user_factory("NotifyQuietPax")
    _make_request(client, pax)
    before = _notes_count(pax["id"])
    close_past_requests()
    assert _notes_count(pax["id"]) == before, (
        "чистка разбудила человека по живой заявке"
    )
