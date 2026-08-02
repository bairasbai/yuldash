"""Деревни РБ (kind='village'): чистый разбор OSM, идемпотентный сид, тёзки по районам,
приоритет в поиске (деревни после городов/райцентров), district в выдаче.

Реальный датасет (app/data/villages_rb.json) в репо пустой — фича «готова, ждёт данных»:
на пустом seed_villages = no-op, поэтому счётчики справочника (test_geo) не трогаются.

DB-мутирующие тесты идут в СВОЁЙ in-memory БД (isolated), чтобы не засорять общий сид.
"""
import pytest
from sqlmodel import Session, SQLModel, create_engine, select

from app.db import engine
from app import geo
from app.models import Settlement


@pytest.fixture
def iso_session():
    """Изолированная in-memory БД — мутации деревень не текут в общий сид (test_geo и пр.)."""
    eng = create_engine("sqlite://", connect_args={"check_same_thread": False})
    SQLModel.metadata.create_all(eng)
    with Session(eng) as s:
        yield s


# ============================ Чистый разбор OSM (без БД) ============================
def test_parse_overpass_filters_and_extracts():
    elements = [
        {"type": "node", "lat": 54.1, "lon": 56.2,
         "tags": {"place": "village", "name": "Кузяново", "name:ba": "Ҡужан", "is_in:district": "Ишимбайский район"}},
        {"type": "node", "lat": 55.0, "lon": 57.0, "tags": {"place": "hamlet", "name": "Ольховка"}},   # без name:ba/district — ок
        {"type": "node", "lat": 55.1, "lon": 57.1, "tags": {"place": "town", "name": "Приютово"}},
        {"type": "node", "lat": 55.2, "lon": 57.2, "tags": {"place": "suburb", "name": "Не-деревня"}},  # place не тот → выкинуть
        {"type": "node", "lat": 55.3, "lon": 57.3, "tags": {"place": "village"}},                        # без имени → выкинуть
        {"type": "way", "tags": {"place": "village", "name": "Контур"}},                                 # не node → выкинуть
    ]
    rows = geo.parse_overpass_elements(elements)
    names = {r["name_ru"] for r in rows}
    assert names == {"Кузяново", "Ольховка", "Приютово"}
    kuz = next(r for r in rows if r["name_ru"] == "Кузяново")
    assert kuz["name_ba"] == "Ҡужан" and kuz["district"] == "Ишимбайский район"
    olh = next(r for r in rows if r["name_ru"] == "Ольховка")
    assert olh["name_ba"] is None and olh["district"] is None


# ============================ Пустой датасет = no-op ============================
def test_seed_villages_empty_is_noop(iso_session):
    """В репо villages_rb.json = [] → seed_villages ничего не добавляет."""
    assert geo.seed_villages(iso_session) == 0
    assert iso_session.exec(select(Settlement)).all() == []


# ============================ Идемпотентный сид + тёзки ============================
_FIX = [
    {"name_ru": "Кузяново", "name_ba": "Ҡужан", "district": "Ишимбайский р-н", "lat": 53.30, "lng": 56.30},
    {"name_ru": "Берёзовка", "name_ba": None, "district": "Иглинский р-н", "lat": 54.80, "lng": 56.40},
    {"name_ru": "Берёзовка", "district": "Гафурийский р-н", "lat": 53.90, "lng": 56.50},  # тёзка в ДРУГОМ районе — не дубль
]


def test_seed_villages_idempotent_with_namesakes(iso_session, monkeypatch):
    monkeypatch.setattr(geo, "_load_villages_data", lambda: _FIX)
    assert geo.seed_villages(iso_session) == 3               # тёзки в разных районах — обе строки
    assert geo.seed_villages(iso_session) == 0               # повтор — no-op
    namesakes = iso_session.exec(select(Settlement).where(Settlement.name_ru == "Берёзовка")).all()
    assert len(namesakes) == 2
    assert {n.district for n in namesakes} == {"Иглинский р-н", "Гафурийский р-н"}
    v = iso_session.exec(select(Settlement).where(Settlement.name_ru == "Кузяново")).first()
    assert v.kind == "village" and v.region == "РБ" and v.name_ba == "Ҡужан"


# ============================ Приоритет в поиске: деревни после городов/райцентров ============================
def test_village_ranks_after_city_and_district_center(iso_session):
    iso_session.add(Settlement(name_ru="Ясквиль", region="РБ", kind="village", district="Р-н", lat=54.0, lng=56.0))
    iso_session.add(Settlement(name_ru="Яскрайцентр", region="РБ", kind="district_center", lat=54.1, lng=56.1))
    iso_session.add(Settlement(name_ru="Яскгород", region="РБ", kind="city", lat=54.2, lng=56.2))
    iso_session.commit()
    rows = geo.search_settlements(iso_session, "Яск", limit=10)
    assert [r.kind for r in rows] == ["city", "district_center", "village"]   # порядок по _KIND_ORDER


# ============================ district в payload/выдаче ============================
def test_settlement_payload_includes_district(iso_session):
    iso_session.add(Settlement(name_ru="Асяново", region="РБ", kind="village",
                               district="Дюртюлинский р-н", lat=55.4, lng=54.8))
    iso_session.commit()
    v = iso_session.exec(select(Settlement).where(Settlement.name_ru == "Асяново")).first()
    payload = geo.settlement_payload(v)
    assert payload["district"] == "Дюртюлинский р-н" and payload["kind"] == "village"


def test_settlements_endpoint_returns_district_field(client):
    r = client.get("/settlements", params={"q": "Уфа"})
    assert r.status_code == 200
    items = r.json()["items"]
    assert items and "district" in items[0]   # контракт: поле есть всегда (у города = null)
