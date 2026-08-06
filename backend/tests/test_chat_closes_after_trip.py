"""Чат попутки не должен оставаться открытым навсегда.

Такси-чат закрывается на запись после поездки (с окном 48 часов на «забыл вещь»), чат
доставки — тоже. А чат попутки не закрывался никогда: водитель мог писать пассажирке спустя
месяцы после поездки, которая давно закончилась.

Для приложения «между своими» это не мелочь. Совместная поездка — это разовая договорённость,
а не знакомство: человек согласился доехать, а не на бессрочный канал связи. Единственным
выходом оставалась блокировка, а это тяжёлый шаг — им пользуются, когда уже плохо, и он
означает «я больше никогда не хочу тебя видеть», хотя человеку нужно всего лишь «поездка
закончилась, спасибо, всё».

Правило: пока поездка идёт — пишем свободно; сразу после — ещё двое суток (забытые вещи,
«скинь номер карты, переведу»); дальше переписка остаётся, но только на чтение.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import Booking, BookingStatus, UserRole
from app.timeutil import utcnow

from test_api import _ride


def _trip(client, user_factory, tag):
    driver = user_factory(f"{tag}Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory(f"{tag}Pax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    return driver, pax, bid


def _finish(bid: int, days_ago: float) -> None:
    """Завершить поездку и отодвинуть ВРЕМЯ ВЫЕЗДА в прошлое.

    Отодвигаем именно время выезда: от него сервер и считает окно. Отметку «завершено»
    человек может не поставить вовсе, а время выезда у поездки есть всегда."""
    from app.models import Ride
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        ride = s.get(Ride, b.ride_id)
        ride.depart_at = utcnow() - timedelta(days=days_ago)
        s.add(b)
        s.add(ride)
        s.commit()


def test_во_время_поездки_пишут_свободно(client, user_factory):
    """Контроль: пока поездка идёт, чат обязан работать — иначе всё ниже ничего не доказывает."""
    driver, pax, bid = _trip(client, user_factory, "ChatLive")
    r = client.post(f"/bookings/{bid}/messages", headers=driver["auth"],
                    json={"text": "выезжаю через 10 минут"})
    assert r.status_code == 200, f"чат не работает во время поездки: {r.status_code} {r.text[:200]}"


def test_сразу_после_поездки_ещё_можно_написать(client, user_factory):
    """Забытые вещи и расчёты — это первые часы после поездки, чат тут нужен."""
    driver, pax, bid = _trip(client, user_factory, "ChatJustAfter")
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
    r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                    json={"text": "кажется, я оставила шапку"})
    assert r.status_code == 200, (
        f"сразу после поездки написать нельзя — а забытые вещи находят именно тогда: "
        f"{r.status_code} {r.text[:200]}"
    )


def test_через_месяц_после_поездки_писать_нельзя(client, user_factory):
    """Главная проверка. Поездка была разовой договорённостью, а не знакомством."""
    driver, pax, bid = _trip(client, user_factory, "ChatLongAfter")
    _finish(bid, days_ago=30)

    r = client.post(f"/bookings/{bid}/messages", headers=driver["auth"],
                    json={"text": "привет, как дела"})
    assert r.status_code != 200, (
        "водитель пишет пассажирке спустя месяц после поездки — чат попутки открыт навсегда, "
        "хотя такси и доставка закрываются"
    )


def test_переписку_после_закрытия_всё_ещё_видно(client, user_factory):
    """Закрываем на запись, но не стираем: по переписке разбирают споры, и человеку может
    понадобиться показать её."""
    driver, pax, bid = _trip(client, user_factory, "ChatReadAfter")
    assert client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                       json={"text": "буду у школы"}).status_code == 200
    _finish(bid, days_ago=30)

    r = client.get(f"/bookings/{bid}/messages", headers=pax["auth"])
    assert r.status_code == 200, f"история переписки закрылась вместе с записью: {r.status_code}"
    assert "буду у школы" in r.text, "сообщения пропали после закрытия чата"


def test_после_отменённой_поездки_тоже_нельзя_писать_вечно(client, user_factory):
    """Отменённая поездка — тем более не повод для бессрочного канала связи."""
    driver, pax, bid = _trip(client, user_factory, "ChatCancelled")
    from app.models import Ride
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.cancelled
        ride = s.get(Ride, b.ride_id)
        ride.depart_at = utcnow() - timedelta(days=31)
        s.add(b)
        s.add(ride)
        s.commit()

    r = client.post(f"/bookings/{bid}/messages", headers=driver["auth"],
                    json={"text": "ну и зря отменила"})
    assert r.status_code != 200, "по отменённой месяц назад поездке всё ещё можно писать"


def test_окно_после_поездки_настраивается(client):
    """Срок вынесен в настройку, а не зашит числом в коде: его наверняка захотят подкрутить."""
    assert getattr(settings, "chat_after_trip_hours", None), (
        "нет настройки срока чата после поездки — значит число зашито в код"
    )
