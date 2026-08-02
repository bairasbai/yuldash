"""Карта спроса для водителя (/instant/demand): агрегация активных поисков в анонимные зоны,
приватность (координаты огрублены до сетки), пусто при отсутствии спроса, доступ таксиста.

Важно: seeded-заказы удаляем в teardown — они «активный поиск» (searching) и иначе
засоряли бы спрос/сурж/пульс в других тестах общей сессионной БД."""
import pytest
from sqlalchemy import delete
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow

# Точки поиска: три в одной ~1км-ячейке (Баймак), одна далеко (Сибай) — две зоны.
CELL_A = [(52.5913, 58.3171), (52.5928, 58.3169), (52.5905, 58.3182)]
CELL_B = (52.7161, 58.6642)


@pytest.fixture
def seed_searches():
    """Создаёт активные поиски для пассажира и подчищает их после теста."""
    created_pax: list[int] = []

    def _seed(passenger_id: int, lat: float, lng: float):
        created_pax.append(passenger_id)
        with Session(engine) as s:
            s.add(InstantOrder(
                passenger_id=passenger_id, status=S.searching,
                from_lat=lat, from_lng=lng, to_lat=CELL_B[0], to_lng=CELL_B[1],
                created_at=utcnow(),
            ))
            s.commit()

    yield _seed
    if created_pax:
        with Session(engine) as s:
            s.exec(delete(InstantOrder).where(InstantOrder.passenger_id.in_(set(created_pax))))
            s.commit()


def test_demand_aggregates_into_zones_and_blurs_coords(client, user_factory, seed_searches):
    pax = user_factory("DemPax")
    for lat, lng in CELL_A:
        seed_searches(pax["id"], lat, lng)
    seed_searches(pax["id"], CELL_B[0], CELL_B[1])

    drv = user_factory("DemDrv", role=UserRole.driver)   # авто-одобренный таксист
    r = client.get("/instant/demand", headers=drv["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert "updated_at" in body
    zones = {(z["lat"], z["lng"]): z for z in body["zones"]}
    # три поиска склеились в одну зону-ячейку (>=3: общая БД может иметь свои поиски в этой ячейке)
    hot = zones.get((52.59, 58.32))
    assert hot is not None and hot["requests"] >= 3
    # вторая зона (Сибай) — отдельная ячейка
    cold = zones.get((52.72, 58.66))
    assert cold is not None and cold["requests"] >= 1
    # веса нормированы 0..1; более горячая зона — не легче холодной
    for z in body["zones"]:
        assert 0.0 < z["weight"] <= 1.0
    assert hot["weight"] >= cold["weight"]
    # ПРИВАТНОСТЬ: координаты огрублены до 2 знаков (сетка ~1 км) — не точка пассажира
    exact = [(round(la, 4), round(ln, 4)) for la, ln in CELL_A]
    for z in body["zones"]:
        assert round(z["lat"], 2) == z["lat"]
        assert round(z["lng"], 2) == z["lng"]
        assert (z["lat"], z["lng"]) not in exact


def test_demand_empty_without_demand(client, user_factory):
    drv = user_factory("EmptyDrv", role=UserRole.driver)
    # фильтр по несуществующему городу → спроса нет → пустые зоны, но честный ответ
    r = client.get("/instant/demand?city=НетТакогоГорода", headers=drv["auth"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["zones"] == []
    assert "updated_at" in body


def test_demand_requires_taxi_driver(client, user_factory):
    pax = user_factory("NotDriver")           # пассажир — без заявки таксиста
    r = client.get("/instant/demand", headers=pax["auth"])
    assert r.status_code == 403
    # без токена — тоже не 200
    assert client.get("/instant/demand").status_code in (401, 403)
