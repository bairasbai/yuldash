# -*- coding: utf-8 -*-
"""«Кто едет в больницу» — не для посторонних глаз.

Откуда взялось. Ручка `/medical-partners/{id}/rides` требует входа, и в ней прямо написано
почему: сам факт «кто и когда едет в конкретную клинику» — вывод о здоровье, а не просто
маршрут (152-ФЗ). Но та же связка спокойно уезжала во вторую дверь: `GET /rides` без токена
отдавал `partner_id` и `category=hospital` рядом с именем водителя и временем выезда
(аудит 2026-08-08, волна 22).

Здесь проверяются ВСЕ выходы сразу — лента, «рядом», карточка по прямой ссылке и фильтр
по категории, — потому что дыра ровно в том, что защиту поставили на один выход из четырёх.
И столько же внимания второй половине: вошедший человек обязан видеть всё как раньше,
иначе это не починка, а поломка функции.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import MedicalPartner, UserRole
from app.timeutil import utcnow


@pytest.fixture
def clinic():
    with Session(engine) as s:
        p = s.exec(select(MedicalPartner)).first()
        if p is None:
            p = MedicalPartner(name="Тестовая ЦРБ", city="Уфа", address="ул. Тестовая, 1",
                               lat=54.735, lng=55.958, description="Тест", active=True)
            s.add(p)
            s.commit()
            s.refresh(p)
        return p.id


@pytest.fixture
def hospital_ride(client, user_factory, clinic):
    """Опубликованная поездка «в больницу» + её водитель."""
    drv = user_factory("Больница: водитель", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа",
        "depart_at": (utcnow() + timedelta(hours=10)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 800,
        "category": "hospital", "partner_id": clinic})
    assert r.status_code == 200, r.text
    return {"id": r.json()["id"], "partner_id": clinic, "driver": drv}


def _hits(payload, partner_id):
    items = payload if isinstance(payload, list) else payload.get("items", [])
    return [x for x in items if x.get("partner_id") == partner_id]


# ----------------------------- аноним не видит связку -----------------------------
def test_лента_не_выдаёт_анониму_поездку_в_клинику(client, hospital_ride):
    r = client.get("/rides")
    assert r.status_code == 200
    assert not _hits(r.json(), hospital_ride["partner_id"]), "связка с клиникой ушла анониму"


def test_рядом_не_выдаёт_анониму_поездку_в_клинику(client, hospital_ride):
    """Отдельный выход со своей сборкой ответа — и он тоже должен молчать.

    Именно здесь первая версия правки не сработала: фильтр применили к объектам из базы,
    а страница потом перечитывалась заново, и правка терялась.
    """
    r = client.get("/rides/near?lat=52.591&lng=58.317&radius_km=50")
    assert r.status_code == 200
    assert not _hits(r.json(), hospital_ride["partner_id"])


def test_карточка_по_ссылке_не_выдаёт_анониму_клинику(client, hospital_ride):
    r = client.get(f"/rides/{hospital_ride['id']}")
    if r.status_code == 200:                      # карточка открыта по ссылке — но без связки
        assert r.json().get("partner_id") is None
        assert r.json().get("category") != "hospital"


def test_фильтром_по_больнице_анониму_выборку_не_собрать(client, user_factory, hospital_ride):
    """Затирать поле в ответе бесполезно, если выборку можно получить самим запросом.

    Проверяем именно это: в ответе на `?category=hospital` анониму обязана быть и ОБЫЧНАЯ
    поездка. Если её нет — значит выборка всё-таки сузилась до больничных, и человек знает,
    что перед ним они, как бы мы ни затирали метку в полях.
    """
    drv = user_factory("Фильтр: обычный водитель", role=UserRole.driver)
    ordinary = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(hours=11)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300})
    assert ordinary.status_code == 200, ordinary.text
    ordinary_id = ordinary.json()["id"]

    r = client.get("/rides?category=hospital")
    assert r.status_code == 200
    items = r.json()
    assert not _hits(items, hospital_ride["partner_id"])
    assert any(x["id"] == ordinary_id for x in items), "выборка сузилась до больничных"


def test_кеш_ленты_не_проносит_связку_мимо_чистки(client, hospital_ride, monkeypatch):
    """У ленты две дороги: свежий запрос и ответ из кеша. Прикрыть надо обе.

    Первый вызов наполняет кеш, второй идёт коротким путём — и на нём фильтры применяются
    отдельно, поверх кеша. Ровно там легче всего забыть новый фильтр, поэтому кеш поднимаем
    настоящий (fakeredis): без него эта ветка в тестах просто не исполняется и «зелёное»
    ничего не значит.
    """
    import fakeredis

    from app import services as svc
    monkeypatch.setattr(svc, "_cache", fakeredis.FakeStrictRedis(decode_responses=True))
    monkeypatch.setattr(svc, "_cache_tried", True)

    first = client.get("/rides")
    assert first.status_code == 200
    assert svc.cache_get_json("rides:active:v2") is not None, "кеш не наполнился — тест слеп"

    second = client.get("/rides")
    assert second.status_code == 200
    assert not _hits(second.json(), hospital_ride["partner_id"]), "кеш пронёс связку с клиникой"


def test_ручка_клиники_анониму_закрыта(client, hospital_ride):
    assert client.get(f"/medical-partners/{hospital_ride['partner_id']}/rides").status_code == 401


# ----------------------------- вошедший видит всё -----------------------------
def test_вошедший_видит_поездку_в_клинику_целиком(client, user_factory, hospital_ride):
    """Вторая половина обещания: барьер — вход, а не запрет функции."""
    me = user_factory("Больница: пассажир")
    pid = hospital_ride["partner_id"]

    feed = client.get("/rides", headers=me["auth"])
    assert _hits(feed.json(), pid), "вошедший перестал видеть связку — функция сломана"

    near = client.get("/rides/near?lat=52.591&lng=58.317&radius_km=50", headers=me["auth"])
    assert _hits(near.json(), pid)

    filtered = client.get("/rides?category=hospital", headers=me["auth"])
    assert _hits(filtered.json(), pid), "фильтр «в больницу» перестал работать для своих"

    at_clinic = client.get(f"/medical-partners/{pid}/rides", headers=me["auth"])
    assert at_clinic.status_code == 200 and at_clinic.json()["count"] >= 1


def test_водитель_видит_свою_поездку_целиком(client, hospital_ride):
    r = client.get("/rides", headers=hospital_ride["driver"]["auth"])
    assert _hits(r.json(), hospital_ride["partner_id"])


def test_обычные_поездки_не_задеты(client, user_factory):
    """Чистка касается только больничных: у остальных категория и поля на месте."""
    drv = user_factory("Обычная: водитель", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(hours=9)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "category": "parcel"})
    assert r.status_code == 200, r.text
    rid = r.json()["id"]
    feed = client.get("/rides").json()
    mine = [x for x in feed if x["id"] == rid]
    assert mine and mine[0]["category"] == "parcel", mine
