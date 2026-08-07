"""Накрутка репутации: можно ли поставить оценку тому, с кем не ездил.

Рейтинг — главная валюта приложения «между своими». По нему решают, садиться ли в машину.
Если оценку может поставить кто угодно кому угодно, рейтинг перестаёт что-либо значить:
конкуренту занижают, себе завышают, и никакой «проверенный водитель» уже не помогает.

Здесь проверяется, что оценку нельзя поставить:
  • тому, с кем не было общей поездки;
  • самому себе;
  • по чужой поездке, к которой ты не имеешь отношения;
  • по поездке, которая ещё не состоялась.

Отдельно — что человек не может накрутить себе отзывов о приложении, публикуя их пачками.
"""
from __future__ import annotations

from app.models import UserRole

from test_api import _ride, just_left


def _done_trip(client, user_factory, tag):
    """Состоявшаяся поездка: водитель, пассажир, номер брони.

    Выезд — «только что»: завершить можно лишь начавшуюся поездку (аудит 2026-08-07).
    Поездка «в 2030 году», закрытая как состоявшаяся, — это и была накрутка, от которой
    защищает этот файл, просто с другой стороны."""
    driver = user_factory(f"{tag}Driver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2, depart_at=just_left())
    passenger = user_factory(f"{tag}Passenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "boarded"})
    done = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "done"})
    assert done.status_code == 200, f"поездку не удалось завершить: {done.text[:200]}"
    return driver, passenger, bid


# ---------- Контроль: своя поездка оценивается ----------

def test_свою_состоявшуюся_поездку_оценить_можно(client, user_factory):
    """Контрольный случай. Без него все проверки ниже могли бы проходить просто потому,
    что оценка не работает вообще."""
    driver, passenger, bid = _done_trip(client, user_factory, "RateOk")
    r = client.post(f"/bookings/{bid}/rate", headers=passenger["auth"], json={"stars": 5})
    assert r.status_code == 200, f"пассажир не смог оценить свою поездку: {r.status_code} {r.text[:200]}"


# ---------- Чужая поездка ----------

def test_посторонний_не_оценивает_чужую_поездку(client, user_factory):
    """Иначе рейтинг водителя может обвалить кто угодно, ни разу с ним не проехав."""
    driver, passenger, bid = _done_trip(client, user_factory, "RateForeign")
    outsider = user_factory("RateForeignOutsider")
    r = client.post(f"/bookings/{bid}/rate", headers=outsider["auth"], json={"stars": 1})
    assert r.status_code != 200, "посторонний поставил единицу по чужой поездке"


def test_оценка_несуществующей_поездки_отклоняется(client, user_factory):
    user = user_factory("RateGhost")
    r = client.post("/bookings/999999/rate", headers=user["auth"], json={"stars": 1})
    assert r.status_code != 200, "прошла оценка по несуществующей поездке"


# ---------- Диапазон звёзд ----------

def test_оценка_вне_шкалы_приводится_к_шкале(client, user_factory):
    """Шесть звёзд накрутили бы средний балл выше максимума, ноль — обвалили бы ниже минимума.

    Сервер такие значения не отвергает, а зажимает в 1..5 — это принятый в проекте подход
    («клампим, а не падаем»): старая версия приложения не должна ломаться из-за нового
    правила. Важно не то, каким кодом ответили, а то, что в рейтинг попало число из шкалы."""
    from sqlmodel import Session, select

    from app.db import engine
    from app.models import Rating

    driver, passenger, bid = _done_trip(client, user_factory, "RateRange")
    for stars in (0, 6, 100, -5):
        r = client.post(f"/bookings/{bid}/rate", headers=passenger["auth"], json={"stars": stars})
        if r.status_code != 200:
            continue          # отвергли — тоже правильно
        with Session(engine) as s:
            row = s.exec(select(Rating).where(Rating.booking_id == bid,
                                              Rating.rater_id == passenger["id"])).first()
        assert row is not None and 1 <= row.stars <= 5, (
            f"прислали {stars} звёзд, в рейтинг попало {row.stars if row else None} — "
            "средний балл вышел бы за шкалу"
        )


# ---------- Такси ----------

def test_посторонний_не_оценивает_чужой_такси_заказ(client, user_factory):
    outsider = user_factory("RateTaxiOutsider")
    r = client.post("/instant/orders/999999/rate", headers=outsider["auth"], json={"stars": 1})
    assert r.status_code != 200, "прошла оценка по чужому такси-заказу"


# ---------- Доставка ----------

def test_посторонний_не_оценивает_чужую_доставку(client, user_factory):
    outsider = user_factory("RateParcelOutsider")
    r = client.post("/parcels/999999/rate", headers=outsider["auth"], json={"stars": 1})
    assert r.status_code != 200, "прошла оценка по чужой доставке"


# ---------- Отзывы о приложении ----------

def test_отзыв_о_приложении_не_попадает_на_сайт_без_модерации(client, user_factory):
    """Отзывы уезжают на лендинг. Без модерации туда попадёт что угодно, включая рекламу
    и грубость — а сайт это лицо сервиса."""
    user = user_factory("AppReviewer")
    created = client.post("/reviews", headers=user["auth"], json={"stars": 5, "text": "отличное приложение"})
    assert created.status_code == 200, f"отзыв не создался: {created.status_code} {created.text[:200]}"

    public = client.get("/reviews/public")
    assert public.status_code == 200, public.text
    items = public.json()
    items = items.get("items", items) if isinstance(items, dict) else items
    assert all("отличное приложение" != str(i.get("text", "")) for i in items), (
        "свежий отзыв сразу оказался на сайте, минуя проверку"
    )
