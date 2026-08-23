"""Отстранённый шёл по архиву поездок и раздавал единицы всем подряд (волна 161).

«Справедливость» — лестница наказаний: за подтверждённую жалобу человек получает паузу
на 3, 7 или 30 дней. Пауза закрывает публикацию поездок, брони, отклики, заказы такси
и доставку. А звёзды остались открыты полностью.

Перекос ровно в ту сторону, где он опаснее всего: отстраняют чаще всего именно за поведение
с людьми — нахамил, обманул с ценой, не приехал. Получив паузу, человек открывал список своих
поездок за два месяца (столько живёт право оценить) и шёл сверху вниз, ставя единицы. Наказание,
которое не мешает продолжать поведение, за которое назначено, — декорация.

**Но запретить оценки целиком было бы другой ошибкой.** Волной 64 сознательно решено: пауза
не бросает людей на полдороге. Наказание может прийти, когда пассажир уже в машине, — человек
обязан довести начатое и сказать о поездке правду. Молчаливые «пять звёзд» за опасную поездку
не нужны никому.

Грань проходит не по факту паузы, а **по свежести поездки**: закончил сегодня — оценивай, это
твой голос о том, что было; пошёл по архиву — нет. Окно то же, что у связи после поездки
(48 часов): одно событие — одно окно.

Дверей к оценке три: попутка, такси и доставка. Первые две ходят через общий шов, третья живёт
отдельно и зовёт то же правило функцией, а не копией.

Заодно проверено пробой и НЕ подтвердилось (дыр нет, защита работает):

* **«месть звёздами после щита»** — админ снял накрученную оценку, автор переставил ту же
  единицу: отметка «снята» остаётся, в рейтинг оценка не возвращается;
* **«травля разборами»** — восемь жалоб подряд по одной поездке дают одно и то же дело,
  а не восемь: дедуп открытых обращений держит.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import (Booking, BookingStatus, ParcelDelivery, Rating, Ride,
                        SafetyProfile, UserRole)
from app.timeutil import utcnow

from test_api import _ride


def _состарить_поездку(ride_id: int, дней: int = 10) -> None:
    """Поездка была давно: оценка по ней — уже поход по архиву, а не голос о том, что было."""
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(days=дней)
        s.add(r)
        s.commit()


def _отстранить(user_id: int, дней: int = 7) -> None:
    """Поставить паузу так же, как это делает разбор жалобы."""
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, strikes=1,
                            suspended_until=utcnow() + timedelta(days=дней)))
        s.commit()


@pytest.fixture
def завершённая_попутка(client, user_factory):
    водитель = user_factory("ОценкиВодитель", role=UserRole.driver)
    пассажир = user_factory("ОценкиПассажир")
    ride_id = _ride(client, водитель, seats=3)
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
    return водитель, пассажир, bid, ride_id


def test_отстранённый_не_ставит_оценку_из_архива(client, завершённая_попутка):
    """Главное: пауза за поведение — не продолжаешь его звёздами по старым поездкам."""
    _, пассажир, bid, ride_id = завершённая_попутка
    _состарить_поездку(ride_id)
    _отстранить(пассажир["id"])

    ответ = client.post(f"/bookings/{bid}/rate", headers=пассажир["auth"],
                        json={"stars": 1, "text": "ужасный водитель"})

    assert ответ.status_code == 403, (
        f"отстранённый поставил единицу (ответ {ответ.status_code}): его отстранили как раз "
        "за поведение с людьми, а раздавать оценки он продолжает"
    )
    with Session(engine) as s:
        assert not s.exec(select(Rating).where(Rating.booking_id == bid)).first(), (
            "оценка всё-таки записалась"
        )


def test_на_паузе_свежую_поездку_оценить_можно(client, завершённая_попутка):
    """Обратная сторона и суть грани: доводишь начатое — говоришь правду о том, что было.

    Волна 64 решила это сознательно: наказание может прийти, когда пассажир уже в машине,
    и молчаливые «пять звёзд» за опасную поездку никому не нужны.
    """
    _, пассажир, bid, _ = завершённая_попутка
    _отстранить(пассажир["id"])

    ответ = client.post(f"/bookings/{bid}/rate", headers=пассажир["auth"],
                        json={"stars": 1, "text": "гнал по трассе"})

    assert ответ.status_code == 200, (
        "человек на паузе не может сказать правду о поездке, которую только что закончил: "
        f"{ответ.text[:150]}"
    )


def test_обычный_пассажир_оценку_ставит(client, завершённая_попутка):
    """Обратная сторона: ради оценок сервис и держит доверие — они должны работать."""
    _, пассажир, bid, _ = завершённая_попутка

    ответ = client.post(f"/bookings/{bid}/rate", headers=пассажир["auth"],
                        json={"stars": 5, "text": "довёз вовремя, спасибо"})

    assert ответ.status_code == 200, f"обычная оценка не прошла: {ответ.text[:150]}"
    assert ответ.json()["rating"] == 5.0


def test_отстранённый_не_ставит_оценку_такси(client, user_factory):
    """Вторая дверь: тот же общий шов, тот же ответ."""
    from app.models import InstantOrder, InstantOrderStatus
    водитель = user_factory("ТаксиОценкиВодитель", role=UserRole.driver)
    пассажир = user_factory("ТаксиОценкиПассажир")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
    with Session(engine) as s:               # заказ был давно — архив, а не «доводим начатое»
        o = s.get(InstantOrder, oid)
        o.done_at = utcnow() - timedelta(days=10)
        s.add(o)
        s.commit()
    _отстранить(пассажир["id"])

    ответ = client.post(f"/instant/orders/{oid}/rate", headers=пассажир["auth"],
                        json={"stars": 1})

    assert ответ.status_code == 403, (
        f"отстранённый оценил такси-водителя (ответ {ответ.status_code})"
    )


def test_на_паузе_свежий_заказ_такси_оценить_можно(client, user_factory):
    """Обратная сторона второй двери: она обязана знать, КОГДА была поездка.

    Если дверь не сообщает момент поездки, «свежая» не отличается от «архивной» — и человек,
    закрывший заказ пять минут назад, теряет право сказать о нём правду.
    """
    from app.models import InstantOrder, InstantOrderStatus
    водитель = user_factory("СвежийТаксиВодитель", role=UserRole.driver)
    пассажир = user_factory("СвежийТаксиПассажир")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
    _отстранить(пассажир["id"])

    ответ = client.post(f"/instant/orders/{oid}/rate", headers=пассажир["auth"],
                        json={"stars": 1})

    assert ответ.status_code == 200, (
        f"заказ закрыт только что, а сказать о нём правду человек уже не может: "
        f"{ответ.text[:150]}"
    )


def test_отстранённый_не_ставит_оценку_доставке(client, user_factory):
    """Третья дверь живёт отдельно от общего шва — и её тоже надо закрывать."""
    from app.models import CourierApplication, CourierProfile
    курьер = user_factory("ДоставкаОценкиКурьер", role=UserRole.driver)
    отправитель = user_factory("ДоставкаОценкиОтправитель")
    with Session(engine) as s:
        заявка = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == курьер["id"])).first()
        if заявка is None:
            заявка = CourierApplication(user_id=курьер["id"])
        заявка.status = "approved"
        заявка.reviewed_at = utcnow()
        s.add(заявка)
        if s.exec(select(CourierProfile).where(
                CourierProfile.user_id == курьер["id"])).first() is None:
            s.add(CourierProfile(user_id=курьер["id"]))
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=курьер["id"],
                           status="delivered", delivery_type="courier",
                           from_city="Акъяр", to_city="Сибай",
                           delivered_at=utcnow() - timedelta(days=10))
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id
    _отстранить(отправитель["id"])

    ответ = client.post(f"/parcels/{pid}/rate", headers=отправитель["auth"],
                        json={"stars": 1})

    assert ответ.status_code == 403, (
        f"отстранённый оценил курьера (ответ {ответ.status_code}): у доставки своя дверь, "
        "и её забыли закрыть вместе с остальными"
    )


def test_обычный_отправитель_курьера_оценивает(client, user_factory):
    """Обратная сторона: у курьеров рейтинг — единственная защита от плохих заказчиков и наоборот."""
    from app.models import CourierApplication, CourierProfile
    курьер = user_factory("ЧестныйКурьер", role=UserRole.driver)
    отправитель = user_factory("ЧестныйОтправитель")
    with Session(engine) as s:
        заявка = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == курьер["id"])).first()
        if заявка is None:
            заявка = CourierApplication(user_id=курьер["id"])
        заявка.status = "approved"
        заявка.reviewed_at = utcnow()
        s.add(заявка)
        if s.exec(select(CourierProfile).where(
                CourierProfile.user_id == курьер["id"])).first() is None:
            s.add(CourierProfile(user_id=курьер["id"]))
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=курьер["id"],
                           status="delivered", delivery_type="courier",
                           from_city="Акъяр", to_city="Сибай", delivered_at=utcnow())
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id

    ответ = client.post(f"/parcels/{pid}/rate", headers=отправитель["auth"],
                        json={"stars": 5, "text": "привёз быстро"})

    assert ответ.status_code == 200, f"обычная оценка доставки не прошла: {ответ.text[:150]}"


def test_щит_рейтинга_держит_повторную_месть(client, завершённая_попутка, user_factory):
    """Проверено пробой: снятая админом оценка не возвращается повторным нажатием."""
    _, пассажир, bid, _ = завершённая_попутка
    админ = user_factory("ЩитАдминПроверка", role=UserRole.admin)
    client.post(f"/bookings/{bid}/rate", headers=пассажир["auth"], json={"stars": 1})
    with Session(engine) as s:
        rid = s.exec(select(Rating).where(Rating.booking_id == bid)).first().id
    client.post(f"/admin/ratings/{rid}/exclude", headers=админ["auth"], json={"excluded": True})

    client.post(f"/bookings/{bid}/rate", headers=пассажир["auth"], json={"stars": 1})

    with Session(engine) as s:
        assert s.get(Rating, rid).excluded is True, (
            "щит рейтинга слетел от повторного нажатия: админ снял накрутку, а она вернулась"
        )


def test_повторные_жалобы_дают_одно_дело(client, завершённая_попутка):
    """Проверено пробой: восемь нажатий подряд — одно дело, а не восемь."""
    водитель, пассажир, bid, _ = завершённая_попутка

    дела = {client.post("/incidents", headers=пассажир["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": f"жалоба {i}",
    }).json().get("id") for i in range(8)}

    assert len(дела) == 1, (
        f"по одной поездке завелось {len(дела)} разборов: админу разбирать копии одного и того же, "
        "а счётчик жалоб посчитает их как разные случаи"
    )
