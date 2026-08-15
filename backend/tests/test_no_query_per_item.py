"""Горячие места не ходят в базу по разу на каждую строку.

Это первый заход в производительность за сессию — до сих пор смотрели только правильность.

Что нашлось. Три места делали по запросу на элемент списка:

* **Оценка цены такси.** На каждый из четырёх классов (стандарт, комфорт, бизнес, минивэн)
  уходил свой запрос за тарифом. Оценка пересчитывается каждый раз, когда пассажир двигает
  точку подачи по карте, — то есть четыре похода в базу на каждое движение пальца.
* **Автоподбор водителя.** На каждую активную заявку два запроса: «есть ли у пассажира
  приложение» и «какие есть отклики». При полусотне живых заявок — сто запросов за прогон,
  и растёт линейно вместе с городом.
* **Напоминание оценить поездку.** Три запроса на бронь: поездка плюс проверка оценки на
  каждого из двоих.

Все три переписаны на «один запрос на весь список плюс поиск по словарю». Поведение не
менялось — менялось только число походов в базу.

Тест держит именно это: считает запросы, а не время. Время на разных машинах разное и в
тестах ничего не значит, а «сколько раз сходили в базу» — величина честная и воспроизводимая.
"""
from __future__ import annotations

from sqlalchemy import event
from sqlmodel import Session

from app.db import engine


class _Counter:
    """Считает SQL-запросы за блок. Дешевле и надёжнее замеров времени.

    `table` сужает счёт до одной таблицы. Это важно: общее число запросов зависит от того,
    сколько данных оставили после себя соседние тесты, и сторож на «всего запросов» начинает
    краснеть от чужой работы. Счёт по одной таблице от этого не зависит.
    """

    def __init__(self, table: str | None = None) -> None:
        self.n = 0
        self._table = f" {table.lower()} " if table else None

    def _count(self, conn, cursor, statement, *a, **k):
        if self._table is None or self._table in f" {statement.lower()} ".replace("\n", " "):
            self.n += 1

    def __enter__(self):
        event.listen(engine, "before_cursor_execute", self._count)
        return self

    def __exit__(self, *exc):
        event.remove(engine, "before_cursor_execute", self._count)
        return False


def test_оценка_цены_берёт_тарифы_одним_запросом(client, user_factory):
    """Четыре класса такси — один запрос за тарифами, а не четыре.

    Считаем только походы в таблицу тарифов. Общее число запросов у оценки зависит от того,
    сколько зон, водителей и точек накопили соседние тесты, — сторож на «всего запросов»
    краснел бы от чужой работы, а не от нашей ошибки.

    Честных походов за тарифами ровно два: один за выбранным классом и один за всей витриной.
    Запрос на каждый класс даёт пять.
    """
    from app.instant_service import estimate

    with Session(engine) as s:
        with _Counter(table="tariff") as c:
            # Оценка от Баймака до Сибая — обычный городской запрос.
            estimate(s, (52.59, 58.31), (52.72, 58.66))
    assert c.n <= 2, (
        "оценка цены сходила за тарифами %d раз. Она пересчитывается на каждое движение точки "
        "по карте — запрос на каждый класс тут недопустим." % c.n
    )


def test_автоподбор_не_ходит_в_базу_на_каждую_заявку(client, user_factory):
    """Число запросов не должно расти вместе с числом заявок.

    Проверяем не «сколько запросов», а «растёт ли». Порог-число здесь плохой сторож: на трёх
    заявках сломанный код делает семь запросов, и любой порог «с запасом» его пропускает.
    А вот рост — признак однозначный: если прогон на восьми заявках стоит дороже, чем на трёх,
    значит внутри цикл ходит в базу на каждую, и в живом городе это сотни запросов.
    """
    from app.automatch import automatch_once

    pax = user_factory("Пассажир без приложения")

    def _добавить_заявок(n: int) -> None:
        for i in range(n):
            r = client.post("/requests", headers=pax["auth"], json={
                "from_city": "Баймак", "to_city": "Сибай", "seats": 1, "comment": f"заявка {i}",
            })
            assert r.status_code == 200, r.text

    def _запросов_за_прогон() -> int:
        with Session(engine) as s:
            with _Counter() as c:
                automatch_once(s, dry_run=True)
        return c.n

    _добавить_заявок(3)
    мало = _запросов_за_прогон()
    _добавить_заявок(5)          # стало 8
    много = _запросов_за_прогон()

    assert много <= мало, (
        "на 3 заявках автоподбор сделал %d запросов, на 8 — уже %d. Значит ходит в базу "
        "на каждую заявку: с ростом города это растёт линейно." % (мало, много)
    )


def test_напоминание_оценить_не_ходит_в_базу_на_каждую_бронь(client, user_factory):
    """Столько же запросов на восемь завершённых поездок, сколько на три."""
    from app.models import Booking, BookingStatus, Ride, UserRole
    from app.rate_reminder import rate_reminder_once
    from app.timeutil import utcnow

    drv = user_factory("Водитель напоминаний", role=UserRole.driver)
    pax = user_factory("Пассажир напоминаний")

    def _добавить_поездок(n: int) -> None:
        with Session(engine) as s:
            for _ in range(n):
                ride = Ride(driver_id=drv["id"], from_city="Баймак", to_city="Сибай", depart_at=utcnow())
                s.add(ride)
                s.commit()
                s.refresh(ride)
                s.add(Booking(ride_id=ride.id, passenger_id=pax["id"], status=BookingStatus.done))
                s.commit()

    def _запросов_за_прогон() -> int:
        with Session(engine) as s:
            with _Counter() as c:
                # Сухой прогон: ничего не помечаем, поэтому второй раз видим те же брони.
                rate_reminder_once(s, dry_run=True)
        return c.n

    _добавить_поездок(3)
    мало = _запросов_за_прогон()
    _добавить_поездок(5)         # стало 8
    много = _запросов_за_прогон()

    assert много <= мало, (
        "на 3 завершённых поездках напоминание сделало %d запросов, на 8 — уже %d. "
        "Значит ходит в базу на каждую бронь." % (мало, много)
    )


def test_рассылка_подписок_не_проверяет_доверие_у_чужих_маршрутов(client, user_factory):
    """У поездки «только для своих» доверие проверяется лишь у совпавших по маршруту.

    Проверка доверия — единственная в рассылке, что ходит в базу: два запроса на подписчика.
    Стояла она первой, до дешёвых отсевов, — то есть платили за неё и те, кто подписан совсем
    на другой маршрут. При пятистах подписках и трёх подходящих это тысяча запросов вместо шести.

    Считаем походы в таблицу пользователей: подписчиков на чужой маршрут тут двадцать, и рост
    числа запросов вместе с ними означает, что проверка снова уехала наверх.
    """
    from datetime import timedelta

    from app.models import Ride, RideStatus, RouteWatch, UserRole
    from app.services import notify_route_watchers
    from app.timeutil import utcnow

    drv = user_factory("Водитель для своих", role=UserRole.driver)

    def _чужих_подписок(n: int) -> None:
        with Session(engine) as s:
            for i in range(n):
                u = user_factory(f"Мимо {i}")
                s.add(RouteWatch(
                    user_id=u["id"], from_city="Учалы", to_city="Белорецк",   # другой маршрут
                    expires_at=utcnow() + timedelta(days=7), watch_kind="both",
                ))
            s.commit()

    def _запросов_на_рассылку() -> int:
        with Session(engine) as s:
            ride = Ride(
                driver_id=drv["id"], from_city="Баймак", to_city="Сибай",
                depart_at=utcnow() + timedelta(hours=5), seats=4, seats_left=4, price=300,
                status=RideStatus.active, only_trusted=True,
            )
            s.add(ride)
            s.commit()
            s.refresh(ride)
            with _Counter(table="user") as c:
                notify_route_watchers(s, ride)
        return c.n

    _чужих_подписок(3)
    мало = _запросов_на_рассылку()
    _чужих_подписок(17)          # стало 20 подписок на посторонний маршрут
    много = _запросов_на_рассылку()

    assert много <= мало, (
        "при 3 посторонних подписках рассылка сделала %d запросов к пользователям, при 20 — уже "
        "%d. Значит проверка доверия снова стоит до отсева по маршруту и платится за каждого "
        "подписчика ленты." % (мало, много)
    )


def test_лента_курьера_не_ищет_город_заново_на_каждую_посылку(client, user_factory):
    """Одинаковый маршрут у всех посылок — справочник городов спрашиваем один раз.

    Лента доступных заказов отбирает посылки по зоне курьера, а города в посылке хранятся
    текстом («Уфа», «Берёзовка (Иглинский р-н)»). Перевод названия в справочник ходит в базу,
    и делался он на каждую строку ленты — хотя городов в районе десяток, а посылок сотни.

    Считаем походы в справочник населённых пунктов: он не должен расти вместе с лентой.
    """
    from app.config import settings
    from test_courier import _make_courier, _order

    # Режим курьера включаем явно: авто-фикстура, которая делает это в test_courier.py,
    # действует только внутри своего файла.
    было = settings.courier_enabled
    settings.courier_enabled = True
    try:
        _проверить_ленту(client, user_factory, _make_courier, _order)
    finally:
        settings.courier_enabled = было


def _проверить_ленту(client, user_factory, _make_courier, _order):
    courier = _make_courier(client, user_factory)
    sender = user_factory("Отправитель лент")

    номер = [0]

    def _посылок(n: int) -> None:
        # Одинаковые заказы сервер считает повтором и схлопывает в один — делаем разные.
        for _ in range(n):
            номер[0] += 1
            r = _order(client, sender, description=f"Документы {номер[0]}")
            assert r.status_code == 200, r.text

    def _запросов_на_ленту() -> int:
        with _Counter(table="settlement") as c:
            r = client.get("/courier/available", headers=courier["auth"])
            assert r.status_code == 200, r.text
        assert len(r.json()) >= 2, "лента пустая — сторож ничего не проверяет"
        return c.n

    _посылок(2)
    мало = _запросов_на_ленту()
    _посылок(8)                  # стало 10 посылок по тому же маршруту
    много = _запросов_на_ленту()

    assert много <= мало, (
        "на 2 посылках лента сходила в справочник городов %d раз, на 10 — уже %d. Значит "
        "название города переводится заново на каждую строку." % (мало, много)
    )
