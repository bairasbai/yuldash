"""Двойной тап не должен плодить одинаковые поездки и заявки.

Аудит 2026-08-06, заход 32. Гард от дубля поставили посылкам (2026-08-03) с точным доводом:
«лаг сети или авто-ретрай запроса — и у человека две одинаковые заявки». У поездок и у заявок
его не поставили — при том что приложение ПОВТОРЯЕТ POST при обрыве связи, а ответ сервера
может опоздать дольше таймаута.

Цена дубля тут выше, чем мусор в ленте:
  • две копии одного рейса — пассажиры бронируют РАЗНЫЕ, водитель получает две брони
    на одну машину и одно место;
  • две копии заявки — водители откликаются на обе, пассажир ведёт две переписки об одной
    поездке и кому-то в итоге отказывает ни за что.

Правило файла: повтор того же содержимого в коротком окне возвращает ТУ ЖЕ запись, а не
ошибку (человек не виноват, что связь моргнула). Осознанная правка — уже другая запись.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app.db import engine
from app.models import Ride, RideRequest, UserRole
from app.timeutil import utcnow


def _depart(hours=30):
    """Завтра. Наивное время сервер читает как уфимское (−5ч), поэтому берём с запасом."""
    return (utcnow() + timedelta(hours=hours)).replace(microsecond=0).isoformat()


def _ride_body(**over):
    body = {"from_city": "Баймак", "to_city": "Сибай", "depart_at": _depart(),
            "seats_total": 3, "price": 300, "comment": "еду утром"}
    body.update(over)
    return body


def _request_body(**over):
    body = {"from_city": "Баймак", "to_city": "Уфа", "desired_at": _depart(),
            "seats": 1, "comment": "нужна машина"}
    body.update(over)
    return body


def test_two_taps_publish_one_ride(client, user_factory):
    drv = user_factory("TapDrv1", role=UserRole.driver)
    body = _ride_body()

    first = client.post("/rides", headers=drv["auth"], json=body)
    second = client.post("/rides", headers=drv["auth"], json=body)
    assert first.status_code in (200, 201), first.text
    assert second.status_code in (200, 201), f"повтор ответил ошибкой вместо той же поездки: {second.text}"
    assert first.json()["id"] == second.json()["id"], "второй тап создал вторую поездку"

    with Session(engine) as s:
        rows = s.exec(select(Ride).where(Ride.driver_id == drv["id"])).all()
    assert len(rows) == 1, f"в ленте {len(rows)} копии одного рейса"


def test_an_edited_ride_is_a_new_one(client, user_factory):
    """Осознанная правка — не дубль: иначе исправленную цену потеряли бы молча."""
    drv = user_factory("TapDrv2", role=UserRole.driver)
    first = client.post("/rides", headers=drv["auth"], json=_ride_body())
    second = client.post("/rides", headers=drv["auth"], json=_ride_body(price=350))
    assert first.json()["id"] != second.json()["id"], "правку цены схлопнули с прежней поездкой"


def test_two_taps_create_one_request(client, user_factory):
    pax = user_factory("TapPax1")
    body = _request_body()

    first = client.post("/requests", headers=pax["auth"], json=body)
    second = client.post("/requests", headers=pax["auth"], json=body)
    assert first.status_code in (200, 201), first.text
    assert second.status_code in (200, 201), f"повтор ответил ошибкой вместо той же заявки: {second.text}"
    assert first.json()["id"] == second.json()["id"], "второй тап создал вторую заявку"

    with Session(engine) as s:
        rows = s.exec(select(RideRequest).where(RideRequest.passenger_id == pax["id"])).all()
    assert len(rows) == 1, f"у пассажира {len(rows)} одинаковые заявки"


def test_a_different_request_still_goes_through(client, user_factory):
    """Человек правда едет в два города — обе заявки должны жить."""
    pax = user_factory("TapPax2")
    first = client.post("/requests", headers=pax["auth"], json=_request_body())
    second = client.post("/requests", headers=pax["auth"], json=_request_body(to_city="Сибай"))
    assert first.json()["id"] != second.json()["id"], "разные направления схлопнули в одну заявку"
