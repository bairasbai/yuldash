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

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import RideRequest, UserRole
from app.timeutil import utcnow

from test_api import _ride


def _make_request(client, pax, city_to="Уфа"):
    r = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": city_to, "seats": 1,
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
    ids = [_make_request(client, pax) for _ in range(cap)]
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
