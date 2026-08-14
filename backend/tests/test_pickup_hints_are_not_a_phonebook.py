"""Чужой дом не должен становиться публичной подсказкой.

История. У поездки есть поле «где встречаемся». Пишут туда живым языком: «у дома 15
по Гагарина, синие ворота», «возле магазина Айгуль», иногда «звони +7…, подъеду». Приложение
из этих ответов пополняло справочник ориентиров города — вместе с координатами.

Справочник отдаётся БЕЗ входа кому угодно (это и правильно: «у автовокзала», «у мечети» —
общие места). Но вместе с ними наружу уезжал чужой домашний адрес с точными координатами
и телефон — а рядом в коде стояло обещание «ориентиры — не персональные данные»
(аудит 2026-08-08, волна 91).

Модерация текста тут не спасает: она помечает запись для админа, но не отменяет сохранение —
это осознанное решение проекта. Значит, разделять надо на выдаче.

Теперь наружу идут только курируемые ориентиры. То, что люди называют сами, копится внутри:
по этим записям видно, какие места реально в ходу, и справочник растёт — но через человека,
а не автоматически.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import PickupPoint, UserRole
from app.services import seed_pickup_points


@pytest.fixture
def seeded():
    """Курируемый справочник как на проде."""
    with Session(engine) as s:
        seed_pickup_points(s)


def _publish(client, driver, pickup: str, lat: float, lng: float, day: str = "2030-01-01"):
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": f"{day}T10:00:00", "seats_total": 3, "price": 300,
        "pickup": pickup, "pickup_lat": lat, "pickup_lng": lng,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def _public_titles(client, city: str = "Баймак") -> list[str]:
    r = client.get(f"/pickup-points?city={city}")
    assert r.status_code == 200, r.text
    return [p["title_ru"] for p in r.json()]


def test_домашний_адрес_не_становится_подсказкой(client, user_factory, seeded):
    driver = user_factory("АдресИльдар", role=UserRole.driver)
    _publish(client, driver, "у дома 15 по Гагарина, синие ворота", 52.5911, 58.3178)

    titles = _public_titles(client)
    assert not any("Гагарина" in t for t in titles), \
        f"дом человека уехал в публичный справочник: {titles}"


def test_телефон_в_поле_встречи_не_сохраняем_вовсе(client, user_factory, seeded):
    """Даже во внутреннем списке ему делать нечего."""
    driver = user_factory("ТелефонРинат", role=UserRole.driver)
    _publish(client, driver, "звони +79991234567 подъеду", 52.5915, 58.3180)

    with Session(engine) as s:
        rows = s.exec(select(PickupPoint).where(PickupPoint.city == "Баймак")).all()
    assert not any("79991234567" in p.title_ru for p in rows), \
        "телефон сохранился в справочнике ориентиров"


def test_курируемые_ориентиры_остались_на_месте(client, seeded):
    """Обратная сторона: закрыв утечку, нельзя оставить людей без подсказок вовсе.

    Заодно про написание города. В справочнике ориентиров Баймак записан по-башкирски
    («Баймаҡ»), а поездки приходят с русским написанием — и подсказки для целого района
    молча не находились (волна 91). Спрашиваем именно по-русски, как это делает приложение.
    """
    titles = _public_titles(client)
    assert titles, "публичный справочник опустел — поле «где встречаемся» осталось без подсказок"
    assert any("автовокзал" in t.lower() or "мечет" in t.lower() for t in titles), titles


def test_выбор_известной_точки_по_прежнему_учитывается(client, user_factory, seeded):
    """Счётчик популярности — то, ради чего фича и живёт: частые ориентиры идут первыми."""
    # Город в сиде записан по-башкирски («Баймаҡ») — берём точку так, как её найдёт человек:
    # через ту же выдачу подсказок, а не прямым сравнением строк.
    with Session(engine) as s:
        point = next(
            (p for p in s.exec(select(PickupPoint).where(PickupPoint.is_seed == True)).all()  # noqa: E712
             if p.city.startswith("Байма")), None,
        )
        assert point is not None, "в сиде нет ориентиров Баймака"
        point_id, before = point.id, point.usage_count

    driver = user_factory("ВыборЗухра", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-03T10:00:00", "seats_total": 3, "price": 300,
        "pickup_point_id": point_id,
    })
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        after = s.get(PickupPoint, point_id).usage_count
    assert after > before, "выбор известного ориентира перестал считаться"
