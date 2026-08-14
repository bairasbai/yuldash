"""Два языка не должны разрезать деревню пополам.

История. Юлдаш двуязычный, и это не украшение: в поле «Откуда» приложение подставляет
название на языке человека. У Ильдара интерфейс башкирский — его рейс уезжает в базу как
«Баймаҡ». Гульнара пользуется русским — она ищет «Баймак».

Названия сравнивались как есть, и эти два написания не встречались (аудит 2026-08-08,
волна 92). Получалось, что две половины жителей ОДНОГО района не видят поездки друг друга:
машины едут, места свободны, а на экране пусто. Хуже всего, что никто бы не пожаловался —
люди просто решили бы, что попуток нет.

Проверяем обе стороны и оба направления, потому что ошибка симметричная: русский ищет
башкирское и наоборот. И отдельно — что чужой город не начал находиться заодно.
"""
from __future__ import annotations

from app.models import UserRole

BASHKIR = "Стәрлетамаҡ"   # как пишет приложение на башкирском
RUSSIAN = "Стерлитамак"   # как пишет приложение на русском
TO_CITY = "Учалы"         # редкая пара городов: в общей тестовой базе такого маршрута больше нет,
                          # иначе рейс тонет за потолком выдачи и тест краснеет на ровном месте


def _publish(client, driver, from_city: str, comment: str) -> int:
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": from_city, "to_city": TO_CITY,
        "depart_at": "2030-06-01T10:00:00", "seats_total": 3, "price": 300,
        "comment": comment,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _search(client, city: str) -> list[int]:
    body = client.get(f"/rides?from_city={city}&to_city={TO_CITY}").json()
    items = body if isinstance(body, list) else body.get("items", [])
    return [x["id"] for x in items]


def test_рейс_на_башкирском_находит_русскоязычный(client, user_factory):
    ildar = user_factory("БашкирскийИльдар", role=UserRole.driver)
    ride_id = _publish(client, ildar, BASHKIR, "рейс опубликован по-башкирски")

    assert ride_id in _search(client, RUSSIAN), \
        "рейс из Стерлитамака не виден тем, у кого приложение на русском"
    assert ride_id in _search(client, BASHKIR), "рейс не находится даже на своём языке"


def test_рейс_на_русском_находит_башкироязычный(client, user_factory):
    rinat = user_factory("РусскийРинат", role=UserRole.driver)
    ride_id = _publish(client, rinat, RUSSIAN, "рейс опубликован по-русски")

    assert ride_id in _search(client, BASHKIR), \
        "рейс из Стерлитамака не виден тем, у кого приложение на башкирском"


def test_заявки_пассажиров_живут_по_тому_же_правилу(client, user_factory):
    """Вторая половина сделки: водитель ищет пассажиров и должен найти их так же."""
    aigul = user_factory("ЗаявкаАйгуль")
    r = client.post("/requests", headers=aigul["auth"], json={
        "from_city": BASHKIR, "to_city": TO_CITY,
        "depart_at": "2030-06-01T10:00:00", "seats": 1,
    })
    assert r.status_code == 200, r.text
    request_id = r.json()["id"]

    driver = user_factory("ЗаявкаВодитель", role=UserRole.driver)   # лента заявок требует входа
    found = client.get(f"/requests/near?from_city={RUSSIAN}&to_city={TO_CITY}",
                       headers=driver["auth"]).json()
    items = found if isinstance(found, list) else found.get("items", [])
    assert request_id in [x["id"] for x in items], \
        "заявка из Стерлитамака не видна водителю с русским интерфейсом"


def test_чужой_город_не_начал_находиться(client, user_factory):
    """Сторож на перегиб: расширив поиск, легко начать показывать соседний район."""
    driver = user_factory("ЧужойГородВодитель", role=UserRole.driver)
    other = _publish(client, driver, "Сибай", "совсем другой город")

    assert other not in _search(client, RUSSIAN), "поиск по Стерлитамаку выдал рейс из Сибая"
