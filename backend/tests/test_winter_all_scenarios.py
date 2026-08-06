"""❄️ Зимний протокол во всех трёх сценариях, а не только в попутке.

Что это для человека. Зимой трасса Сибай–Уфа — четыре часа. Если он не отметил, что доехал,
приложение спрашивает «всё в порядке?». Не ответил полчаса — тем, кого он сам выбрал, уходит
SMS «позвони, проверь». Один вопрос и один звонок близкого — не слежка и не тревога по
каждому поводу.

Механика жила только у попутки. В такси её не было, хотя пассажир едет те же четыре часа
с незнакомым водителем; в доставке — тоже, хотя курьер едет ОДИН, и рядом нет никого,
кто заметит, что что-то не так.

Здесь проверяется весь путь в каждом сценарии: спросили → ждём → позвали близких → отметка
«я доехал» гасит эскалацию. И обратные стороны: рано не спрашиваем, дважды не звоним,
чужому не даём.
"""
from __future__ import annotations

from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import Booking, InstantOrder, ParcelDelivery, TrustedContact, UserRole
from app.timeutil import utcnow

from test_api import _ride

ORIG = (52.5911, 58.3178)
DEST = (52.9128, 58.6689)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def courier_on(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    yield


def _add_contact(client, user, phone="+79990004401") -> int:
    """Доверенный контакт — тот, кому позвонят, если человек молчит. Возвращает его номер."""
    r = client.post("/trusted-contacts", headers=user["auth"],
                    json={"name": "Мама", "phone": phone})
    assert r.status_code in (200, 201), f"контакт не добавился: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def _age_check(model, obj_id: int, minutes: int) -> None:
    """Отодвинуть момент вопроса в прошлое — как будто человек молчит уже N минут."""
    with Session(engine) as s:
        obj = s.get(model, obj_id)
        obj.winter_check_sent_at = utcnow() - timedelta(minutes=minutes)
        s.add(obj)
        s.commit()


# ---------- Попутка (регресс: было и должно остаться) ----------

def _booking_started(client, user_factory, tag):
    driver = user_factory(f"{tag}Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory(f"{tag}Pax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    from app.models import Ride
    with Session(engine) as s:
        ride = s.get(Ride, ride_id)
        ride.depart_at = utcnow() - timedelta(hours=1)   # выехали час назад
        s.add(ride)
        s.commit()
    return driver, pax, bid


def test_попутка_спрашивает_доехал(client, user_factory):
    driver, pax, bid = _booking_started(client, user_factory, "WinterRide")
    r = client.post(f"/bookings/{bid}/winter-check", headers=pax["auth"])
    assert r.json()["state"] == "check_sent", f"попутка не спросила: {r.text[:200]}"


def test_попутка_отметка_доехал_гасит(client, user_factory):
    driver, pax, bid = _booking_started(client, user_factory, "WinterRideAck")
    client.post(f"/bookings/{bid}/winter-check", headers=pax["auth"])
    assert client.post(f"/bookings/{bid}/winter-check/ok", headers=pax["auth"]).status_code == 200
    r = client.post(f"/bookings/{bid}/winter-check", headers=pax["auth"])
    assert r.json()["state"] == "ok", f"после отметки протокол продолжает тревожить: {r.text[:200]}"


# ---------- Такси ----------

def _order_onboard(client, user_factory, tag):
    passenger = user_factory(f"{tag}Pax")
    driver = user_factory(f"{tag}Drv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"],
                       json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    oid = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"]).status_code == 200
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    assert client.post(f"/instant/orders/{oid}/onboard",
                       headers=driver["auth"]).status_code == 200
    return passenger, driver, oid


def test_такси_до_посадки_не_спрашивает(client, user_factory, fake_redis):
    """«Доехал?» до того, как человек сел в машину — бессмысленный вопрос."""
    passenger = user_factory("WinterTaxiEarlyPax")
    driver = user_factory("WinterTaxiEarlyDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"],
                       json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    oid = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])

    r = client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    assert r.json()["state"] == "too_early", f"спросили до посадки: {r.text[:200]}"


def test_такси_спрашивает_доехал(client, user_factory, fake_redis):
    passenger, driver, oid = _order_onboard(client, user_factory, "WinterTaxi")
    r = client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    assert r.json()["state"] == "check_sent", f"такси не спросило: {r.text[:200]}"


def test_такси_молчание_зовёт_близких(client, user_factory, fake_redis):
    """Главная проверка: человек не ответил полчаса — близкому уходит SMS."""
    passenger, driver, oid = _order_onboard(client, user_factory, "WinterTaxiEsc")
    contact_id = _add_contact(client, passenger, "+79990004411")
    share = client.post(f"/instant/orders/{oid}/share", headers=passenger["auth"],
                        json={"contact_id": contact_id})
    assert share.status_code in (200, 201), f"не удалось расшарить поездку: {share.text[:200]}"

    client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    _age_check(InstantOrder, oid, minutes=45)
    r = client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    assert r.json()["state"] == "escalated", (
        f"пассажир такси молчит 45 минут, а близких не позвали: {r.text[:250]}"
    )
    assert r.json().get("contacts_notified", 0) >= 1, r.text[:250]


def test_такси_дважды_не_тревожит(client, user_factory, fake_redis):
    """Повторные вызовы после порога не должны слать SMS снова и снова."""
    passenger, driver, oid = _order_onboard(client, user_factory, "WinterTaxiTwice")
    contact_id = _add_contact(client, passenger, "+79990004412")
    client.post(f"/instant/orders/{oid}/share", headers=passenger["auth"],
                json={"contact_id": contact_id})
    client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    _age_check(InstantOrder, oid, minutes=45)
    client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    again = client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    assert again.json().get("already") is True, (
        f"второй заход снова поднял тревогу — это поток SMS близким: {again.text[:250]}"
    )


def test_такси_отметка_доехал_гасит(client, user_factory, fake_redis):
    passenger, driver, oid = _order_onboard(client, user_factory, "WinterTaxiAck")
    client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    assert client.post(f"/instant/orders/{oid}/winter-check/ok",
                       headers=passenger["auth"]).status_code == 200
    r = client.post(f"/instant/orders/{oid}/winter-check", headers=passenger["auth"])
    assert r.json()["state"] == "ok"


def test_такси_чужой_не_лезет(client, user_factory, fake_redis):
    passenger, driver, oid = _order_onboard(client, user_factory, "WinterTaxiForeign")
    outsider = user_factory("WinterTaxiOutsider")
    r = client.post(f"/instant/orders/{oid}/winter-check", headers=outsider["auth"])
    assert r.status_code in (403, 404), f"посторонний вмешался в чужую поездку: {r.status_code}"


# ---------- Доставка ----------

def _parcel_taken(client, user_factory, tag):
    sender = user_factory(f"{tag}Sender")
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990004499", "rules_accepted": True,
    }).json()["id"]
    courier = user_factory(f"{tag}Courier", role=UserRole.driver)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    return sender, courier, pid


def test_доставка_до_приёма_не_спрашивает(client, user_factory, courier_on):
    sender = user_factory("WinterParcelEarlySender")
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990004498", "rules_accepted": True,
    }).json()["id"]
    r = client.post(f"/parcels/{pid}/winter-check", headers=sender["auth"])
    assert r.json()["state"] == "too_early", (
        f"спросили «доехал?» у посылки, которую никто не забрал: {r.text[:200]}"
    )


def test_доставка_спрашивает_курьера(client, user_factory, courier_on):
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterParcel")
    r = client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    assert r.json()["state"] == "check_sent", f"доставка не спросила курьера: {r.text[:200]}"


def test_доставка_молчание_зовёт_близких_курьера(client, user_factory, courier_on):
    """Курьер едет ОДИН: рядом нет пассажира, который заметит беду. Звоним его близким."""
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterParcelEsc")
    _add_contact(client, courier, "+79990004421")

    client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    _age_check(ParcelDelivery, pid, minutes=45)
    r = client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    assert r.json()["state"] == "escalated", (
        f"курьер молчит 45 минут, а его близких не позвали: {r.text[:250]}"
    )
    assert r.json().get("contacts_notified", 0) >= 1, r.text[:250]


def test_доставка_отправитель_узнаёт_о_молчании(client, user_factory, courier_on):
    """Его посылка не движется — сказать должны мы, а не получатель через неделю."""
    from app.models import Notification

    sender, courier, pid = _parcel_taken(client, user_factory, "WinterParcelNotify")
    _add_contact(client, courier, "+79990004422")
    client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    _age_check(ParcelDelivery, pid, minutes=45)

    with Session(engine) as s:
        before = len(list(s.exec(select(Notification).where(
            Notification.user_id == sender["id"])).all()))
    client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    with Session(engine) as s:
        after = len(list(s.exec(select(Notification).where(
            Notification.user_id == sender["id"])).all()))
    assert after > before, "курьер пропал, а отправитель об этом не узнал"


def test_доставка_отметка_гасит(client, user_factory, courier_on):
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterParcelAck")
    client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    assert client.post(f"/parcels/{pid}/winter-check/ok",
                       headers=courier["auth"]).status_code == 200
    r = client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    assert r.json()["state"] == "ok"


def test_доставка_отметить_может_только_курьер(client, user_factory, courier_on):
    """«Я доехал» за курьера не отмечает никто — иначе отметка ничего не значит."""
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterParcelWho")
    r = client.post(f"/parcels/{pid}/winter-check/ok", headers=sender["auth"])
    assert r.status_code == 403, f"отправитель отметил «доехал» за курьера: {r.status_code}"


def test_доставка_чужой_не_лезет(client, user_factory, courier_on):
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterParcelForeign")
    outsider = user_factory("WinterParcelOutsider")
    r = client.post(f"/parcels/{pid}/winter-check", headers=outsider["auth"])
    assert r.status_code in (403, 404), f"посторонний вмешался в чужую доставку: {r.status_code}"


# ---------- Общее ----------

def test_без_доверенных_контактов_молча_не_эскалируем(client, user_factory, courier_on):
    """Человек не добавил близких — звать некого. Придумывать за него, кому звонить,
    мы не вправе; и падать тоже не должны."""
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterNoContacts")
    client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    _age_check(ParcelDelivery, pid, minutes=45)
    r = client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    assert r.json()["state"] == "no_share", f"неожиданное состояние: {r.text[:200]}"


def test_закрытая_доставка_не_тревожит(client, user_factory, courier_on):
    sender, courier, pid = _parcel_taken(client, user_factory, "WinterClosed")
    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
        p.status = "delivered"
        s.add(p)
        s.commit()
    r = client.post(f"/parcels/{pid}/winter-check", headers=courier["auth"])
    assert r.json()["state"] == "closed"
