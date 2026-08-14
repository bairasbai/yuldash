"""У каждой витрины есть потолок — иначе один запрос тянет всю базу.

Зачем это важно именно у нас. Юлдаш живёт там, где интернет мобильный и небыстрый, а телефоны
у людей чаще простые. Витрина без потолка ведёт себя незаметно, пока объявлений мало: сервер
собирает все строки в память, шлёт их целиком, телефон разбирает. Разница появляется ровно
тогда, когда сервис вырастет, — и бьёт сильнее всего по тем, у кого слабый телефон и дорогой
трафик, то есть по нашим.

У ленты поездок потолок стоял с самого начала и был подписан «Потолок всегда». Трём соседним
витринам его не поставили: поездки к клинике, посылки «по пути», заказы профессиональных
курьеров (аудит 2026-08-08, волна 89). Правило было, просто жило в одном файле из четырёх.

Тест проверяет обе стороны: за потолок не выходим, но и обычную выдачу не режем — если человек
опубликовал десять посылок, он должен увидеть все десять.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import MedicalPartner, ParcelDelivery, UserRole
from app.timeutil import utcnow
from app.visibility import FEED_MAX

from test_courier import _courier_on, _make_courier  # noqa: F401  (_courier_on — фикстура)


@pytest.fixture
def clinic():
    with Session(engine) as s:
        mp = MedicalPartner(name="РКБ потолок", city="Уфа", address="ул. Достоевского, 132",
                            lat=54.7261, lng=55.9475, description="тест")
        s.add(mp)
        s.commit()
        s.refresh(mp)
        return mp.id


def _many_parcels(sender_id: int, n: int, delivery_type: str = "poputka"):
    """Пишем прямо в базу: через ручку это n запросов и потолок на открытые заявки."""
    with Session(engine) as s:
        for i in range(n):
            s.add(ParcelDelivery(
                sender_id=sender_id, from_city="Уфа", to_city="Стерлитамак",
                size="small", description=f"посылка {i}", status="created",
                delivery_type=delivery_type, receiver_name="Айгуль",
                receiver_phone="+79990001133", code=f"{1000 + i}",
                created_at=utcnow(),
            ))
        s.commit()


def test_витрина_посылок_не_отдаёт_больше_потолка(client, user_factory):
    sender = user_factory("ПотолокОтправитель")
    reader = user_factory("ПотолокЧитатель")
    _many_parcels(sender["id"], FEED_MAX + 25)

    r = client.get("/parcels/available", headers=reader["auth"])
    assert r.status_code == 200, r.text
    assert len(r.json()) <= FEED_MAX, \
        f"витрина отдала {len(r.json())} строк — это вся база в один запрос"


def test_обычная_выдача_не_обрезана(client, user_factory):
    """Обратная сторона: потолок не должен превращаться в «показываем только первые пять»."""
    sender = user_factory("НеОбрезайОтправитель")
    reader = user_factory("НеОбрезайЧитатель")
    _many_parcels(sender["id"], 10)

    r = client.get("/parcels/available", headers=reader["auth"])
    assert r.status_code == 200, r.text
    mine = [p for p in r.json() if p.get("description", "").startswith("посылка ")]
    assert len(mine) >= 10, f"из десяти посылок человек увидел {len(mine)}"


def test_витрина_курьера_тоже_с_потолком(client, user_factory, _courier_on):
    courier = _make_courier(client, user_factory)
    sender = user_factory("ПотолокКурьерОтправитель")
    _many_parcels(sender["id"], FEED_MAX + 15, delivery_type="courier")

    r = client.get("/courier/available", headers=courier["auth"])
    assert r.status_code == 200, r.text
    assert len(r.json()) <= FEED_MAX, f"витрина курьера отдала {len(r.json())} заказов разом"


def test_витрина_клиники_тоже_с_потолком(client, user_factory, clinic):
    driver = user_factory("ПотолокКлиникаВодитель", role=UserRole.driver)
    reader = user_factory("ПотолокКлиникаЧитатель")
    for i in range(5):
        r = client.post("/rides", headers=driver["auth"], json={
            "from_city": "Баймак", "to_city": "Уфа", "depart_at": "2030-05-01T08:00:00",
            "seats_total": 3, "price": 300, "comment": f"в клинику {i}",
            "partner_id": clinic, "category": "hospital",
        })
        assert r.status_code == 200, r.text

    body = client.get(f"/medical-partners/{clinic}/rides", headers=reader["auth"]).json()
    assert body["count"] <= FEED_MAX
    assert body["count"] >= 5, "поездки к клинике пропали из витрины"
