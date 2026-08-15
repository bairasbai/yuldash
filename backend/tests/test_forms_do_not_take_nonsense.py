"""Форма не должна принимать бессмыслицу и делать вид, что всё хорошо.

Три истории про то, как палец промахивается по стеклу в машине.

**Пустое сообщение.** Гульнара задела кнопку отправки, в поле были одни пробелы. Сообщение
уходило, водителю звонил телефон «Новое сообщение», он открывал чат — пустой пузырь.
Проверка на пустоту в проекте была, но только при ПРАВКЕ сообщения; при отправке её не было
(аудит 2026-08-08, волна 108).

**Один и тот же город.** «Сибай → Сибай» публиковалось спокойно. В расписании водителя такое
давно ловится словами «города отправления и назначения совпадают», а в поездке — нет.
Для пассажира это объявление-призрак: он пишет водителю и выясняет, что тот ошибся полем.

**Название из смайликов.** «🚗🚗 → Сибай» тоже проходило и попадало в общую ленту района.

Обратная сторона везде одна: настоящие названия и настоящие сообщения должны проходить.
Башкирские буквы (Баймаҡ, Стәрлетамаҡ) — это буквы, а не «мусор».
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from app.models import UserRole

from test_api import _ride

CHAT = Path(__file__).resolve().parents[1] / "app" / "routers" / "chat.py"


def _publish(client, driver, from_city: str, to_city: str = "Сибай", day: str = "2030-12-05"):
    return client.post("/rides", headers=driver["auth"], json={
        "from_city": from_city, "to_city": to_city,
        "depart_at": f"{day}T10:00:00", "seats_total": 3, "price": 300,
    })


@pytest.fixture
def chat(client, user_factory):
    """Подтверждённая бронь с открытым чатом: водитель и пассажир."""
    driver = user_factory("ФормаВодитель", role=UserRole.driver)
    passenger = user_factory("ФормаПассажир")
    ride_id = _ride(client, driver, comment="формы")
    booking_id = client.post("/bookings", headers=passenger["auth"],
                             json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{booking_id}/confirm", headers=driver["auth"]).status_code == 200
    return passenger, booking_id


def test_сообщение_из_пробелов_не_уходит(client, chat):
    passenger, booking_id = chat

    r = client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"],
                    json={"text": "    "})

    assert r.status_code == 400, "пустое сообщение ушло — собеседника разбудили пустым пузырём"
    assert "пуст" in str(r.json()).lower(), r.json()


def test_обычное_сообщение_уходит(client, chat):
    """Обратная сторона: строгость не должна мешать написать «еду»."""
    passenger, booking_id = chat

    r = client.post(f"/bookings/{booking_id}/messages", headers=passenger["auth"],
                    json={"text": "выезжаю через пять минут"})

    assert r.status_code == 200, r.text


def test_пустое_не_уходит_ни_в_одном_из_чатов():
    """Чатов в Юлдаше три: бронь, мгновенный заказ и посылка. Двери должны быть закрыты все.

    Тест выше проверяет поведением только чат брони. Пока я его писал, выяснилось, что
    сломать можно любую другую дверь и никто не заметит — поэтому здесь читается исходник:
    у каждой отправки сообщения обязана быть проверка на пустоту.
    """
    src = CHAT.read_text(encoding="utf-8").splitlines()
    doors = [i for i, line in enumerate(src)
             if re.match(r"@router\.post\(.*/messages", line.strip())]
    assert len(doors) >= 3, f"чатов стало {len(doors)} — проверь, не появилась ли новая дверь"

    unguarded = []
    for i in doors:
        body = "\n".join(src[i: i + 40])
        # Написано по-разному: где-то `if not (body.text or "").strip()`, где-то сначала
        # `text = body.text.strip()`, а потом `if not text`. Важно не написание, а две вещи:
        # пробелы схлопываются и на пустое приходит отказ.
        trims = ".strip()" in body
        refuses = bool(re.search(r"herr\(\s*400[^)]*[Пп]уст", body))
        if not (trims and refuses):
            unguarded.append(f"chat.py:{i + 1} → {src[i].strip()[:60]}")
    assert not unguarded, (
        "здесь можно отправить пустое сообщение: " + "; ".join(unguarded)
        + ". Собеседнику зазвонит телефон «Новое сообщение», он откроет чат — пустой пузырь."
    )


def test_поездка_из_города_в_тот_же_город_не_публикуется(client, user_factory):
    driver = user_factory("ОдинГородВодитель", role=UserRole.driver)

    r = _publish(client, driver, "Сибай", "Сибай")

    assert r.status_code == 400, "опубликована поездка «из Сибая в Сибай»"
    assert "разные" in str(r.json()).lower(), r.json()


def test_название_из_смайликов_не_проходит(client, user_factory):
    driver = user_factory("СмайликВодитель", role=UserRole.driver)

    r = _publish(client, driver, "🚗🚗")

    assert r.status_code == 400, "поездка из «🚗🚗» попала в общую ленту района"


def test_башкирские_названия_проходят(client, user_factory):
    """Главная проверка обратной стороны: «Баймаҡ» — это буквы, а не мусор."""
    driver = user_factory("БашкирскийНазваниеВодитель", role=UserRole.driver)

    r = _publish(client, driver, "Баймаҡ", "Стәрлетамаҡ", day="2030-12-06")

    assert r.status_code == 200, f"башкирское написание города не приняли: {r.text}"


def test_деревня_с_районом_в_скобках_проходит(client, user_factory):
    """Приложение подставляет «Берёзовка (Иглинский р-н)» — такая запись должна работать."""
    driver = user_factory("ДеревняВодитель", role=UserRole.driver)

    r = _publish(client, driver, "Берёзовка (Иглинский р-н)", "Уфа", day="2030-12-07")

    assert r.status_code == 200, f"деревню с районом не приняли: {r.text}"
