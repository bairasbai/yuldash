"""Башкироязычный водитель видел «нет доступа» вместо причины (волна 177).

Продолжение волны 173, где отказы входа переписали на два языка. Тогда сторож искал русский
текст ПРЯМО в вызове — `HTTPException(403, "Пройди проверку")`. Пять отказов лежали иначе:
текст уехал в константу, а в вызов попало её имя. Сторож их не видел.

**Почему это бьёт по человеку.** Приложение, получив одноязычный отказ, башкироязычному
показывает не русскую строку, а общую заглушку по коду ответа: «Был эшкә рөхсәт юҡ» —
«нет доступа к этому действию». Защита правильная (лучше честная заглушка, чем чужой язык),
но в итоге:

- русскоязычный водитель читает «Оплати долг сервису, чтобы возить такси» и знает, что делать;
- башкироязычный читает «нет доступа» и не знает даже, в чём дело — в долге, в документах
  или в том, что такси ещё не работает в его городе.

Три причины, три разных действия — и все три выглядели одинаково.

Теперь отказы гейтов такси уходят на двух языках, а сторож ищет по обоим признакам:
и текст на месте, и текст, спрятанный в константе.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

from app import debt as debt_mod
from app import taxi as taxi_mod
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, UserRole,
)
from app.timeutil import utcnow

БАШКИРСКИЕ_БУКВЫ = "әөүҙҫңғҡ"
ЗОНА = {"work_zone": "city", "work_city": "Баймак"}


def _по_башкирски(текст: str) -> bool:
    return any(б in (текст or "") for б in БАШКИРСКИЕ_БУКВЫ)


def _на_линию(client, человек) -> None:
    """Профиль водителя заводится первым выходом на линию: без него гейт зоны отвечает другое."""
    client.post("/driver/online", headers=человек["auth"], json={"online": True})


def _оба_языка(ответ, где: str) -> None:
    """Отказ должен приходить объектом {ru, ba} — иначе приложение покажет заглушку."""
    тело = ответ.json().get("detail")
    assert isinstance(тело, dict), (
        f"{где}: отказ пришёл одной строкой — башкироязычный увидит «нет доступа» "
        f"вместо причины: {тело}"
    )
    assert тело.get("ru") and тело.get("ba"), f"{где}: пустая половина отказа: {тело}"
    assert _по_башкирски(тело["ba"]), (
        f"{где}: в башкирской половине нет башкирских букв — похоже, туда попал русский: "
        f"{тело['ba']}"
    )


@pytest.fixture
def новичок(client, user_factory):
    """Водитель, который ещё не прошёл проверку таксиста."""
    человек = user_factory("НовичокБезПроверки", role=UserRole.driver, taxi_approved=False)
    _на_линию(client, человек)
    return человек


def test_непроверенному_объясняют_на_его_языке(client, новичок):
    """Главное: человек узнаёт причину, а не факт «нельзя»."""
    ответ = client.post("/instant/zone", headers=новичок["auth"], json=ЗОНА)

    assert ответ.status_code == 403, f"ожидали отказ проверки, пришло: {ответ.text[:150]}"
    _оба_языка(ответ, "гейт проверки таксиста")


def test_долг_объясняют_на_его_языке(client, user_factory):
    """Самый частый отказ у работающего водителя — от него зависит его заработок."""
    водитель = user_factory("ДолжникБашкирский", role=UserRole.driver)
    пассажир = user_factory("ПассажирДляДолга")
    _на_линию(client, водитель)
    with Session(engine) as s:
        заказ = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                             status=InstantOrderStatus.done,
                             price_estimate=500, price_final=500)
        s.add(заказ)
        s.commit()
        s.refresh(заказ)
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=заказ.id,
                             amount_kop=200000, status=DebtStatus.unpaid,
                             created_at=utcnow() - timedelta(days=10),
                             due_at=utcnow() - timedelta(days=3)))
        s.commit()

    # Гейт долга стоит там, где водитель встаёт в подбор, — это и есть момент «хочу работать».
    ответ = client.post("/instant/presence", headers=водитель["auth"],
                        json={"lat": 52.591, "lng": 58.317})

    assert ответ.status_code == 403, f"ожидали отказ по долгу, пришло: {ответ.text[:150]}"
    _оба_языка(ответ, "гейт долга")


def test_у_каждой_причины_свой_текст():
    """Обратная сторона: три причины не должны звучать одинаково — иначе перевод бесполезен."""
    тексты = {
        taxi_mod.TAXI_NOT_APPROVED_MSG_BA,
        taxi_mod.TAXI_UNAVAILABLE_MSG_BA,
        debt_mod.TAXI_BLOCKED_MSG_BA,
    }
    assert len(тексты) == 3, f"разные причины отказа звучат одинаково: {тексты}"
    for т in тексты:
        assert _по_башкирски(т), f"в башкирской строке нет башкирских букв: {т}"


def test_проверенный_водитель_работает(client, user_factory):
    """Контроль: перевод отказов не должен запирать того, кому можно."""
    водитель = user_factory("ПроверенныйРабочий", role=UserRole.driver)
    _на_линию(client, водитель)

    ответ = client.post("/instant/zone", headers=водитель["auth"], json=ЗОНА)

    assert ответ.status_code == 200, (
        f"водителю с пройденной проверкой закрыли зону работы: {ответ.text[:150]}"
    )
