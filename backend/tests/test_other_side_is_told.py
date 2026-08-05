"""Вторая сторона узнаёт о том, что её касается — особенно об отмене.

Самая дорогая тишина в приложении попуток. Человек стоит у трассы в минус двадцать и ждёт
машину, которой уже не будет: водитель отменил поездку, а приложение промолчало. Или наоборот
— водитель ждёт пассажира, который давно отменил бронь.

Уведомление здесь не «приятная мелочь», а часть услуги. Отмена без уведомления хуже, чем
отсутствие отмены: во втором случае человек хотя бы понимает, что договорённость в силе.

Проверяется по записи в Центре уведомлений (`Notification`): она создаётся ровно там же, где
уходит пуш (`services.push_notification`), поэтому её наличие означает, что человека
действительно позвали, а не просто поменяли статус в базе.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Notification, UserRole

from test_api import _ride


def _notes(user_id: int) -> list[Notification]:
    with Session(engine) as s:
        return list(s.exec(select(Notification).where(Notification.user_id == user_id)).all())


def _new_notes(user_id: int, before: int) -> list[Notification]:
    """Уведомления, появившиеся после отметки `before` (число прежних)."""
    return _notes(user_id)[before:]


def _confirmed(client, user_factory, tag):
    driver = user_factory(f"{tag}Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory(f"{tag}Pax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    return driver, pax, ride_id, bid


# ---------- Попутка ----------

def test_водитель_отменил_поездку_пассажир_узнал(client, user_factory):
    """Худший сценарий: человек уже собрался и вышел к дороге."""
    driver, pax, ride_id, bid = _confirmed(client, user_factory, "TellRideCancel")
    before = len(_notes(pax["id"]))

    cancelled = client.post(f"/rides/{ride_id}/cancel", headers=driver["auth"])
    assert cancelled.status_code == 200, f"отмена не прошла: {cancelled.status_code} {cancelled.text[:200]}"

    fresh = _new_notes(pax["id"], before)
    assert fresh, (
        "водитель отменил поездку, а пассажиру не пришло ничего — он выйдет к дороге и будет ждать"
    )


def test_пассажир_отменил_бронь_водитель_узнал(client, user_factory):
    """Водителю это тоже важно: место освободилось, можно взять другого попутчика."""
    driver, pax, ride_id, bid = _confirmed(client, user_factory, "TellBookCancel")
    before = len(_notes(driver["id"]))

    cancelled = client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": ""})
    assert cancelled.status_code == 200, f"отмена брони не прошла: {cancelled.text[:200]}"

    fresh = _new_notes(driver["id"], before)
    assert fresh, "пассажир отменил бронь, а водитель не узнал — поедет за ним впустую"


def test_водитель_подтвердил_бронь_пассажир_узнал(client, user_factory):
    """Контрольная сторона: хорошие новости доходят так же, как плохие."""
    driver = user_factory("TellConfirmDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("TellConfirmPax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    before = len(_notes(pax["id"]))

    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    assert _new_notes(pax["id"], before), "бронь подтвердили, а пассажир об этом не узнал"


def test_новая_бронь_доходит_до_водителя(client, user_factory):
    driver = user_factory("TellNewBookDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("TellNewBookPax")
    before = len(_notes(driver["id"]))

    assert client.post("/bookings", headers=pax["auth"],
                       json={"ride_id": ride_id, "seats": 1}).status_code == 200
    assert _new_notes(driver["id"], before), (
        "к водителю записались, а он не узнал — бронь провисит без ответа"
    )


# ---------- Заявка ----------

def test_отклик_на_заявку_доходит_до_пассажира(client, user_factory):
    pax = user_factory("TellRespPax")
    rid = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    }).json()["id"]
    driver = user_factory("TellRespDrv", role=UserRole.driver)
    before = len(_notes(pax["id"]))

    assert client.post(f"/requests/{rid}/respond", headers=driver["auth"],
                       json={"price": 500}).status_code == 200
    assert _new_notes(pax["id"], before), (
        "водитель откликнулся, а пассажир не узнал — заявка провисит, человек не уедет"
    )


def test_встречная_цена_доходит_до_второй_стороны(client, user_factory):
    """Торг работает только если обе стороны знают, что сейчас их ход."""
    pax = user_factory("TellBargainPax")
    rid = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    }).json()["id"]
    driver = user_factory("TellBargainDrv", role=UserRole.driver)
    resp_id = client.post(f"/requests/{rid}/respond", headers=driver["auth"],
                          json={"price": 500}).json()["id"]
    before = len(_notes(driver["id"]))

    assert client.post(f"/responses/{resp_id}/counter", headers=pax["auth"],
                       json={"price": 400}).status_code == 200
    assert _new_notes(driver["id"], before), (
        "пассажир предложил свою цену, а водитель не узнал — торг встанет"
    )


# ---------- Доставка ----------

def test_курьер_взял_посылку_отправитель_узнал(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("TellParcelSender")
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990009911", "rules_accepted": True,
    }).json()["id"]
    courier = user_factory("TellParcelCourier", role=UserRole.driver)
    before = len(_notes(sender["id"]))

    taken = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert taken.status_code == 200, f"курьер не смог взять посылку: {taken.text[:200]}"
    assert _new_notes(sender["id"], before), (
        "посылку взяли, а отправитель не узнал — он не знает, кому её отдавать"
    )


# ---------- Такси ----------

@pytest.fixture
def fake_redis():
    import fakeredis

    from app import instant_service as isv
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.mark.skip(
    reason="Назначение машины пассажир видит на экране заказа живьём (поллинг статуса), "
           "поэтому запись в Центре уведомлений тут не нужна — она только засорила бы ленту. "
           "След обязателен у ОТМЕНЫ (тест ниже): её человек может не увидеть вовремя."
)
def test_водитель_принял_заказ_пассажир_узнал(client, user_factory, fake_redis):
    passenger = user_factory("TellTaxiPax")
    driver = user_factory("TellTaxiDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": 52.5911, "lng": 58.3178}).status_code == 200
    oid = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": 52.5911, "from_lng": 58.3178, "to_lat": 52.9128, "to_lng": 58.6689,
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    before = len(_notes(passenger["id"]))

    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    assert accepted.status_code == 200, f"водитель не принял заказ: {accepted.text[:200]}"
    assert _new_notes(passenger["id"], before), (
        "машина назначена, а пассажир не узнал — он продолжает ждать «ищем водителя»"
    )


def test_водитель_отменил_принятый_заказ_пассажир_узнал(client, user_factory, fake_redis):
    """Самое обидное в такси: машину назначили, человек расслабился — и она пропала."""
    passenger = user_factory("TellTaxiCancelPax")
    driver = user_factory("TellTaxiCancelDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": 52.5911, "lng": 58.3178}).status_code == 200
    oid = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": 52.5911, "from_lng": 58.3178, "to_lat": 52.9128, "to_lng": 58.6689,
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"]).status_code == 200
    before = len(_notes(passenger["id"]))

    cancelled = client.post(f"/instant/orders/{oid}/cancel", headers=driver["auth"],
                            json={"reason": "сломался"})
    assert cancelled.status_code == 200, f"отмена заказа водителем не прошла: {cancelled.text[:200]}"
    assert _new_notes(passenger["id"], before), (
        "водитель отменил уже принятый заказ, а пассажир не узнал — стоит и ждёт машину"
    )
