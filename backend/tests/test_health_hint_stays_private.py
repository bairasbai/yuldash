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


_PAGE = 200   # больше одной страницы ленты сервер не отдаёт (`DEFAULT_FEED_LIMIT`)


def _feed(client, query="", headers=None):
    """Вся лента `/rides` постранично, а не первая страница.

    В общей базе набора (PostgreSQL в CI — один процесс на все тесты) первую страницу — 200 поездок,
    поднятые первыми — занимают поездки других тестов, и поездка «в больницу» через несколько часов
    могла на неё просто не попасть (CI PR212). Тогда «аноним не видит связку» проходило бы вхолостую,
    а «вошедший видит» — падало. Читаем ВСЕ страницы: проверка не слабее, а строже прежней.

    Конец ленты — пустые страницы подряд, а не «страница короче 200»: сервер режет страницу в базе,
    а потом убирает из неё поездки заблокированных и отстранённых — неполная страница не последняя.
    """
    out, empty = [], 0
    for page_no in range(100):
        sep = "&" if query else ""
        r = client.get(f"/rides?{query}{sep}limit={_PAGE}&offset={page_no * _PAGE}", headers=headers)
        assert r.status_code == 200, r.text
        page = r.json()
        out += page
        empty = empty + 1 if not page else 0
        if empty >= 3:
            return out
    raise AssertionError("лента длиннее 100 страниц — тест не дочитал её")


_NEAR = "/rides/near?lat=52.591&lng=58.317&radius_km=50"


def _near(client, headers=None):
    """«Ближайшие» целиком: та же история, что с лентой, — страница ограничена, читаем все (`count` — всего)."""
    out = []
    for page_no in range(100):
        r = client.get(f"{_NEAR}&limit={_PAGE}&offset={page_no * _PAGE}", headers=headers)
        assert r.status_code == 200, r.text
        body = r.json()
        out += body.get("items", [])
        if (page_no + 1) * _PAGE >= body.get("count", 0):
            return out
    raise AssertionError("«Ближайшие» длиннее 100 страниц — тест не дочитал их")


# ----------------------------- аноним не видит связку -----------------------------
def test_лента_не_выдаёт_анониму_поездку_в_клинику(client, hospital_ride):
    r = client.get("/rides")
    assert r.status_code == 200
    assert not _hits(r.json(), hospital_ride["partner_id"]), "связка с клиникой ушла анониму"
    feed = _feed(client)
    assert any(x["id"] == hospital_ride["id"] for x in feed), "поездки нет в ленте — проверка вхолостую"
    assert not _hits(feed, hospital_ride["partner_id"]), "связка с клиникой ушла анониму"


def test_рядом_не_выдаёт_анониму_поездку_в_клинику(client, hospital_ride):
    """Отдельный выход со своей сборкой ответа — и он тоже должен молчать.

    Именно здесь первая версия правки не сработала: фильтр применили к объектам из базы,
    а страница потом перечитывалась заново, и правка терялась.
    """
    r = client.get(_NEAR)
    assert r.status_code == 200
    assert not _hits(r.json(), hospital_ride["partner_id"])
    near = _near(client)
    assert any(x["id"] == hospital_ride["id"] for x in near), "поездки нет в «Ближайших» — проверка вхолостую"
    assert not _hits(near, hospital_ride["partner_id"])


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

    items = _feed(client, "category=hospital")
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

    assert _hits(_feed(client, headers=me["auth"]), pid), "вошедший перестал видеть связку — функция сломана"

    assert _hits(_near(client, headers=me["auth"]), pid)

    filtered = _feed(client, "category=hospital", headers=me["auth"])
    assert _hits(filtered, pid), "фильтр «в больницу» перестал работать для своих"
    assert all(x.get("category") == "hospital" for x in filtered), "для своих фильтр по категории — настоящий"

    at_clinic = client.get(f"/medical-partners/{pid}/rides", headers=me["auth"])
    assert at_clinic.status_code == 200 and at_clinic.json()["count"] >= 1


def test_водитель_видит_свою_поездку_целиком(client, hospital_ride):
    assert _hits(_feed(client, headers=hospital_ride["driver"]["auth"]), hospital_ride["partner_id"])


def test_обычные_поездки_не_задеты(client, user_factory):
    """Чистка касается только больничных: у остальных категория и поля на месте."""
    drv = user_factory("Обычная: водитель", role=UserRole.driver)
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(hours=9)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "category": "parcel"})
    assert r.status_code == 200, r.text
    rid = r.json()["id"]
    feed = _feed(client)
    mine = [x for x in feed if x["id"] == rid]
    assert mine and mine[0]["category"] == "parcel", mine
