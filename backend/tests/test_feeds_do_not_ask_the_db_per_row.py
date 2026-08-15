"""Сторож: витрина не должна ходить в базу за каждой строкой.

Зачем. Самая частая незаметная поломка в таких проектах — «плюс один запрос на каждую
запись». Пока в базе десять поездок, никто ничего не замечает. Когда их станет тысяча,
лента начнёт открываться секундами, и виноватой будет выглядеть сеть в деревне, а не код.

Ловится это только так: наполнить данными и посмотреть, растёт ли число обращений к базе.
Одного замера мало — важна РАЗНИЦА между «мало данных» и «много».

Тест держит три главные витрины: ленту поездок, карточку водителя с отзывами и список
переписок. Если кто-то поставит `session.get(...)` внутрь цикла, тест покраснеет в тот же
день, а не через год на живых людях.
"""
from __future__ import annotations

import pytest
from sqlalchemy import event
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, Rating, UserRole
from app.timeutil import utcnow

from test_api import _ride

# Небольшой запас сверху: разовые служебные запросы (проверка токена, настройки) допустимы.
ALLOWED_GROWTH = 3


class _Counter:
    """Считает обращения к базе за время запроса."""

    def __init__(self) -> None:
        self.n = 0

    def __enter__(self):
        @event.listens_for(engine, "before_cursor_execute")
        def _hook(conn, cursor, statement, params, context, executemany):
            self.n += 1

        self._hook = _hook
        self.n = 0
        return self

    def __exit__(self, *exc):
        event.remove(engine, "before_cursor_execute", self._hook)
        return False


def _cost(client, url: str, who=None) -> tuple[int, int]:
    """(сколько запросов к базе, сколько записей в ответе)."""
    headers = who["auth"] if who else {}
    with _Counter() as c:
        r = client.get(url, headers=headers)
        assert r.status_code == 200, r.text
        body = r.json()
    if isinstance(body, list):
        rows = len(body)
    elif isinstance(body, dict) and isinstance(body.get("items"), list):
        rows = len(body["items"])
    elif isinstance(body, dict) and isinstance(body.get("reviews"), list):
        rows = len(body["reviews"])
    else:
        rows = 0
    return c.n, rows


@pytest.fixture
def reader(user_factory):
    return user_factory("СчётчикЧитатель")


def test_лента_поездок_не_растёт_по_запросам(client, user_factory, reader):
    first = user_factory("СчётчикВодитель1", role=UserRole.driver)
    _ride(client, first, comment="счётчик-мало")
    few_queries, few_rows = _cost(client, "/rides", reader)

    for i in range(15):
        drv = user_factory(f"СчётчикВодитель{i + 2}", role=UserRole.driver)
        _ride(client, drv, comment=f"счётчик-много-{i}")
    many_queries, many_rows = _cost(client, "/rides", reader)

    assert many_rows > few_rows, "лента не выросла — замер бессмысленный"
    assert many_queries <= few_queries + ALLOWED_GROWTH, (
        f"на {few_rows} поездках лента стоила {few_queries} запросов, на {many_rows} — уже "
        f"{many_queries}. Это «плюс запрос на каждую строку»: сейчас незаметно, на тысяче "
        "поездок лента будет открываться секундами."
    )


def test_карточка_водителя_не_растёт_по_отзывам(client, user_factory):
    def driver_with_reviews(tag: str, count: int):
        driver = user_factory(f"Отзывы{tag}", role=UserRole.driver)
        with Session(engine) as s:
            for i in range(count):
                passenger = user_factory(f"Отзывы{tag}Пасс{i}")
                ride_id = _ride(client, driver, comment=f"отзыв-{tag}-{i}")
                booking = Booking(ride_id=ride_id, passenger_id=passenger["id"], seats=1,
                                  status=BookingStatus.done, created_at=utcnow())
                s.add(booking)
                s.commit()
                s.refresh(booking)
                s.add(Rating(booking_id=booking.id, rater_id=passenger["id"],
                             ratee_id=driver["id"], stars=5,
                             text=f"спасибо {i}", text_published=True))
                s.commit()
        return driver

    quiet = driver_with_reviews("Мало", 2)
    popular = driver_with_reviews("Много", 15)

    few_queries, few_rows = _cost(client, f"/drivers/{quiet['id']}/public?limit=20")
    many_queries, many_rows = _cost(client, f"/drivers/{popular['id']}/public?limit=20")

    assert many_rows > few_rows, "отзывы не появились — замер бессмысленный"
    assert many_queries <= few_queries + ALLOWED_GROWTH, (
        f"карточка с {few_rows} отзывами стоила {few_queries} запросов, с {many_rows} — "
        f"{many_queries}. Отзывы тянутся по одному."
    )


def test_список_переписок_не_растёт_по_диалогам(client, user_factory):
    driver = user_factory("ПерепискиВодитель", role=UserRole.driver)
    baseline = None
    for i in range(8):
        passenger = user_factory(f"ПерепискиПассажир{i}")
        ride_id = _ride(client, driver, comment=f"переписка-{i}")
        r = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
        assert r.status_code == 200, r.text
        booking_id = r.json()["id"]
        client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"])
        client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"],
                    json={"text": f"еду {i}"})
        if i == 0:
            baseline = _cost(client, "/conversations", driver)

    few_queries, few_rows = baseline
    many_queries, many_rows = _cost(client, "/conversations", driver)

    assert many_rows > few_rows, "диалоги не появились — замер бессмысленный"
    assert many_queries <= few_queries + ALLOWED_GROWTH, (
        f"инбокс с {few_rows} диалогом стоил {few_queries} запросов, с {many_rows} — "
        f"{many_queries}. Собеседники подтягиваются по одному."
    )
