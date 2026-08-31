"""Деревни (kind='village'): чистый разбор OSM, идемпотентный сид, тёзки по районам,
приоритет в поиске (деревни после городов/райцентров/соседей), district в выдаче,
приграничье соседних регионов и то, что деревня не подменяет город в зонах такси.

Реальные датасеты — app/data/villages_rb.json (вся РБ) и villages_border.json (полоса
~50 км у соседей). Тесты работают на своих фикстурах, чтобы не зависеть от их объёма.

DB-мутирующие тесты идут в СВОЁЙ in-memory БД (isolated), чтобы не засорять общий сид.
"""
import pytest
from sqlmodel import Session, SQLModel, create_engine, select

from app.db import engine
from app import geo
from app.models import Settlement, UserRole


@pytest.fixture
def iso_session():
    """Изолированная in-memory БД — мутации деревень не текут в общий сид (test_geo и пр.)."""
    eng = create_engine("sqlite://", connect_args={"check_same_thread": False})
    SQLModel.metadata.create_all(eng)
    geo.invalidate_cache()          # снимок справочника — свой на каждую БД
    with Session(eng) as s:
        yield s
    geo.invalidate_cache()


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
def test_seed_villages_empty_is_noop(iso_session, monkeypatch):
    """Нет данных (пустые файлы) → seed_villages ничего не добавляет и не падает."""
    monkeypatch.setattr(geo, "_load_villages_data", list)
    assert geo.seed_villages(iso_session) == 0
    assert iso_session.exec(select(Settlement)).all() == []


def test_real_datasets_are_sane():
    """Датасеты в репо: непустые, у каждой строки имя и координаты в пределах Урала-Поволжья."""
    rows = geo._load_villages_data()
    assert len(rows) > 3000, "деревни не залиты — справочник обеднел"
    for r in rows[:2000]:
        assert r.get("name_ru"), r
        assert 50.0 <= float(r["lat"]) <= 60.0 and 46.0 <= float(r["lng"]) <= 62.0, r
    assert sum(1 for r in rows if (r.get("region") or "РБ") != "РБ") > 300, "нет приграничья соседей"


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


def test_neighbor_city_ranks_above_villages(iso_session):
    """«Маг» должен давать Магнитогорск, а не деревню Магадеево: город соседей выше деревень."""
    iso_session.add(Settlement(name_ru="Магадеево", region="РБ", kind="village", district="Абзелиловский р-н",
                               lat=53.6, lng=58.6))
    iso_session.add(Settlement(name_ru="Магнитогорск", region="Челябинская обл.", kind="neighbor",
                               lat=53.412, lng=58.984))
    iso_session.commit()
    assert [r.name_ru for r in geo.search_settlements(iso_session, "Маг", limit=5)] == \
        ["Магнитогорск", "Магадеево"]


def test_rb_village_ranks_above_border_village(iso_session):
    """Своя деревня раньше приграничной — попутка по республике ближе человеку."""
    iso_session.add(Settlement(name_ru="Ивановка", region="Челябинская обл.", kind="village",
                               district="Кунашакский р-н", lat=55.7, lng=61.5))
    iso_session.add(Settlement(name_ru="Ивановка", region="РБ", kind="village",
                               district="Хайбуллинский р-н", lat=51.9, lng=58.2))
    iso_session.commit()
    rows = geo.search_settlements(iso_session, "Иванов", limit=5)
    assert [r.region for r in rows] == ["РБ", "Челябинская обл."]


# ============================ Поиск: буквы, которых нет на клавиатуре ============================
def test_search_ignores_yo_and_bashkir_letters(iso_session):
    """«березовка» находит «Берёзовку», «офо» — «Өфө»: человек печатает как удобно."""
    iso_session.add(Settlement(name_ru="Берёзовка", region="РБ", kind="village",
                               district="Иглинский р-н", lat=54.8, lng=56.4))
    iso_session.add(Settlement(name_ru="Уфа", name_ba="Өфө", region="РБ", kind="city", lat=54.735, lng=55.958))
    iso_session.commit()
    assert [r.name_ru for r in geo.search_settlements(iso_session, "березовка")] == ["Берёзовка"]
    assert [r.name_ru for r in geo.search_settlements(iso_session, "офо")] == ["Уфа"]


def test_search_matches_second_word(iso_session):
    """«киги» находит «Верхние Киги» — люди ищут по главному слову, а не по первому."""
    iso_session.add(Settlement(name_ru="Верхние Киги", region="РБ", kind="district_center", lat=55.4, lng=58.6))
    iso_session.commit()
    assert [r.name_ru for r in geo.search_settlements(iso_session, "киги")] == ["Верхние Киги"]


# ============================ Приграничье соседей ============================
_BORDER_FIX = [
    {"name_ru": "Ташбулатово", "district": "Абзелиловский р-н", "lat": 53.62, "lng": 58.70},   # region по умолчанию — РБ
    {"name_ru": "Смеловский", "region": "Челябинская обл.", "district": "Кизильский р-н", "lat": 52.9, "lng": 59.2},
    {"name_ru": "Магнитогорск", "region": "Челябинская обл.", "district": "г.о. Магнитогорск", "lat": 53.4, "lng": 59.0},
]


def test_seed_villages_keeps_region_and_skips_big_towns(iso_session, monkeypatch):
    """У приграничной деревни свой регион; город из справочника (Магнитогорск) деревней не дублируем."""
    iso_session.add(Settlement(name_ru="Магнитогорск", region="Челябинская обл.", kind="neighbor",
                               lat=53.412, lng=58.984))
    iso_session.commit()
    monkeypatch.setattr(geo, "_load_villages_data", lambda: _BORDER_FIX)
    assert geo.seed_villages(iso_session) == 2                    # Магнитогорск отсеян
    rows = {s.name_ru: s for s in iso_session.exec(select(Settlement)).all()}
    assert rows["Ташбулатово"].region == "РБ"
    assert rows["Смеловский"].region == "Челябинская обл." and rows["Смеловский"].kind == "village"
    assert rows["Магнитогорск"].kind == "neighbor"                # остался городом


# ============================ Зоны такси: деревня не подменяет город ============================
def test_nearest_settlement_ignores_villages(iso_session):
    """«Город точки заказа» — это Уфа, даже если деревня ближе: на ней завязаны зоны таксиста."""
    iso_session.add(Settlement(name_ru="Уфа", region="РБ", kind="city", lat=54.735, lng=55.958))
    iso_session.add(Settlement(name_ru="Дорогино", region="РБ", kind="village", district="Уфимский р-н",
                               lat=54.740, lng=55.960))
    iso_session.commit()
    assert geo.nearest_settlement(iso_session, 54.739, 55.959).name_ru == "Уфа"
    # Явно попросили искать среди всех — тогда деревня побеждает.
    assert geo.nearest_settlement(iso_session, 54.739, 55.959, kinds=()).name_ru == "Дорогино"


def test_by_exact_name_understands_district_in_brackets(iso_session):
    """Приложение пишет в поле «Берёзовка (Иглинский р-н)» — сервер должен взять ИМЕННО ту."""
    iso_session.add(Settlement(name_ru="Берёзовка", region="РБ", kind="village",
                               district="Аургазинский р-н", lat=53.878, lng=55.631))
    iso_session.add(Settlement(name_ru="Берёзовка", region="РБ", kind="village",
                               district="Иглинский р-н", lat=54.800, lng=56.400))
    iso_session.commit()
    st = geo.by_exact_name(iso_session, "Берёзовка (Иглинский р-н)")
    assert st is not None and round(st.lat, 1) == 54.8
    # Район не узнали (опечатка) — не падаем, отдаём тёзку по имени.
    st2 = geo.by_exact_name(iso_session, "Берёзовка (Такогорайонанет)")
    assert st2 is not None and st2.name_ru == "Берёзовка"


def test_by_exact_name_prefers_city_over_village(iso_session):
    """Тёзка-деревня не должна перебивать город: geocode_city вернёт координаты города."""
    iso_session.add(Settlement(name_ru="Октябрьский", region="РБ", kind="village",
                               district="Стерлитамакский р-н", lat=53.5, lng=55.8))
    iso_session.add(Settlement(name_ru="Октябрьский", region="РБ", kind="city", lat=54.481, lng=53.471))
    iso_session.commit()
    st = geo.by_exact_name(iso_session, "октябрьский")
    assert st.kind == "city" and round(st.lng, 3) == 53.471


# ============================ Поиск по ленте: имя с районом и без ============================
def test_rides_search_finds_ride_written_without_district(client, user_factory):
    """Водитель набрал «Кузяново» руками, пассажир выбрал подсказку «Кузяново (Ишимбайский р-н)» —
    поездка обязана найтись: ищем по голому имени."""
    drv = user_factory("Води", role=UserRole.driver)
    body = {"from_city": "Кузяново", "to_city": "Стерлитамак", "depart_at": "2030-01-01T10:00:00",
            "seats": 3, "price": 300}
    assert client.post("/rides", headers=drv["auth"], json=body).status_code in (200, 201)
    found = client.get("/rides", params={"from_city": "Кузяново (Ишимбайский р-н)"}).json()
    items = found if isinstance(found, list) else found.get("items", [])
    assert any(r["from_city"] == "Кузяново" for r in items)


def test_bare_name_strips_district():
    assert geo.bare_name("Берёзовка (Иглинский р-н)") == "Берёзовка"
    assert geo.bare_name("Уфа") == "Уфа"
    assert geo.bare_name("  (странно)  ") == "(странно)"     # пустое имя — оставляем как есть


# ============================ Кеш справочника ============================
def test_cache_sees_new_settlements(iso_session):
    """Снимок в памяти не должен «залипать»: добавили НП — он сразу в подсказках."""
    iso_session.add(Settlement(name_ru="Первое", region="РБ", kind="village", lat=54.0, lng=56.0))
    iso_session.commit()
    assert len(geo.search_settlements(iso_session, "Перв")) == 1
    iso_session.add(Settlement(name_ru="Первомайский", region="РБ", kind="village", lat=54.1, lng=56.1))
    iso_session.commit()
    assert len(geo.search_settlements(iso_session, "Перв")) == 2


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


# ============================ Зона курьера: район и «загород» ============================
def _courier_zone_ok(session, *, zone, city=None, district=None, intercity=False,
                     regions=False, direction_id=None, frm="", to=""):
    """Пропустит ли зона курьера посылку «откуда → куда» (города приходят текстом)."""
    return geo.zone_allows(
        session, zone=zone, work_city=city, work_district=district,
        intercity=intercity, regions=regions, direction_id=direction_id,
        a=geo.area_by_name(session, frm), b=geo.area_by_name(session, to), local_km=40.0,
    )


def test_courier_district_zone_takes_only_its_district(iso_session):
    """Курьер выбрал «Баймакский район»: посылка между сёлами района — его, чужой район — мимо."""
    iso_session.add(Settlement(name_ru="Иткулово", region="РБ", kind="village",
                               district="Баймакский р-н", lat=52.629, lng=57.968))
    iso_session.add(Settlement(name_ru="Темясово", region="РБ", kind="village",
                               district="Баймакский р-н", lat=52.993, lng=58.101))
    iso_session.add(Settlement(name_ru="Абзелилово", region="РБ", kind="village",
                               district="Абзелиловский р-н", lat=53.468, lng=58.661))
    iso_session.commit()
    ok = _courier_zone_ok(iso_session, zone="district", district="Баймакский р-н",
                          frm="Иткулово", to="Темясово")
    assert ok is True
    mimo = _courier_zone_ok(iso_session, zone="district", district="Абзелиловский р-н",
                            frm="Иткулово", to="Темясово")
    assert mimo is False


def test_courier_needs_intercity_toggle_for_far_parcel(iso_session):
    """Без «выезда загород» дальняя посылка не приходит; с тумблером — приходит."""
    iso_session.add(Settlement(name_ru="Иткулово", region="РБ", kind="village",
                               district="Баймакский р-н", lat=52.629, lng=57.968))
    iso_session.add(Settlement(name_ru="Уфа", region="РБ", kind="city", lat=54.735, lng=55.958))
    iso_session.commit()
    args = dict(zone="district", district="Баймакский р-н", frm="Иткулово", to="Уфа")
    assert _courier_zone_ok(iso_session, **args) is False
    assert _courier_zone_ok(iso_session, intercity=True, **args) is True


def test_courier_other_region_needs_regions_toggle(iso_session):
    """Магнитогорск — другой регион: нужен отдельный тумблер «соседние регионы»."""
    iso_session.add(Settlement(name_ru="Сибай", region="РБ", kind="city", lat=52.716, lng=58.664))
    iso_session.add(Settlement(name_ru="Магнитогорск", region="Челябинская обл.", kind="neighbor",
                               lat=53.412, lng=58.984))
    iso_session.commit()
    args = dict(zone="city", city="Сибай", frm="Сибай", to="Магнитогорск")
    assert _courier_zone_ok(iso_session, intercity=True, **args) is False
    assert _courier_zone_ok(iso_session, intercity=True, regions=True, **args) is True


def test_courier_namesake_village_resolved_by_district(iso_session):
    """Посылка записана как «Берёзовка (Иглинский р-н)» — курьеру из Гафурийского она не идёт."""
    iso_session.add(Settlement(name_ru="Берёзовка", region="РБ", kind="village",
                               district="Иглинский р-н", lat=54.800, lng=56.400))
    iso_session.add(Settlement(name_ru="Берёзовка", region="РБ", kind="village",
                               district="Гафурийский р-н", lat=54.080, lng=56.482))
    iso_session.commit()
    frm, to = "Берёзовка (Иглинский р-н)", "Берёзовка (Иглинский р-н)"
    assert _courier_zone_ok(iso_session, zone="district", district="Иглинский р-н",
                            frm=frm, to=to) is True
    assert _courier_zone_ok(iso_session, zone="district", district="Гафурийский р-н",
                            frm=frm, to=to) is False


def test_courier_push_uses_same_zone_rules_as_list(client, user_factory, monkeypatch):
    """Пуш о новой посылке зовёт только тех курьеров, кто увидит её и в списке.
    Раньше пуш сверял город строкой: звал на заказ, которого человек потом не находил."""
    from app.routers import parcels as parcels_mod
    from app.models import CourierApplication, CourierProfile, ParcelDelivery
    sent: list = []
    monkeypatch.setattr(parcels_mod, "push_notification",
                        lambda session, user_id, *a, **kw: sent.append(user_id))
    with Session(engine) as s:
        near = user_factory("CourierNear")["id"]
        far = user_factory("CourierFar")["id"]
        sender = user_factory("ParcelSender")["id"]
        # Рассылка спрашивает и про ДОПУСК курьера (волна 222): на линии без одобренной
        # заявки в проде оказаться нельзя. Тест про зоны — заявку заводим обоим.
        for uid in (near, far):
            s.add(CourierApplication(user_id=uid, status="approved",
                                     full_name="Курьер Курьеров", transport="car",
                                     rules_accepted=True))
        s.add(CourierProfile(user_id=near, online=True, zone="district",
                             work_district="Ишимбайский р-н"))
        s.add(CourierProfile(user_id=far, online=True, zone="district",
                             work_district="Абзелиловский р-н"))
        s.commit()
        parcel = ParcelDelivery(sender_id=sender, delivery_type="courier",
                                from_city="Кузяново", to_city="Кузяново")
        s.add(parcel)
        s.commit()
        s.refresh(parcel)
        parcels_mod._notify_couriers_new_parcel(s, parcel)
    assert near in sent and far not in sent


def test_courier_in_border_town_works_at_home_without_regions_toggle(iso_session):
    """Курьер из приграничного Магнитогорска возит по своему городу — тумблер «соседние
    регионы» ему для этого не нужен: границу региона заказ не пересекает."""
    iso_session.add(Settlement(name_ru="Магнитогорск", region="Челябинская обл.", kind="neighbor",
                               lat=53.412, lng=58.984))
    iso_session.commit()
    assert _courier_zone_ok(iso_session, zone="city", city="Магнитогорск",
                            frm="Магнитогорск", to="Магнитогорск") is True


def test_city_base_covers_nearby_village(iso_session):
    """Подача из села в пяти километрах от Сибая — для сибайского водителя это его город.
    Иначе заказ с окраины не доставался бы никому: базы «Сибай» у села нет."""
    iso_session.add(Settlement(name_ru="Сибай", region="РБ", kind="city", lat=52.716, lng=58.664))
    iso_session.add(Settlement(name_ru="Кусеево", region="РБ", kind="village",
                               district="Баймакский р-н", lat=52.760, lng=58.640))
    iso_session.commit()
    ok = _courier_zone_ok(iso_session, zone="city", city="Сибай", frm="Кусеево", to="Сибай")
    assert ok is True
    # А до села за сотню километров это правило не дотягивается.
    iso_session.add(Settlement(name_ru="Дальнее", region="РБ", kind="village",
                               district="Дуванский р-н", lat=55.536, lng=58.250))
    iso_session.commit()
    assert _courier_zone_ok(iso_session, zone="city", city="Сибай",
                            frm="Дальнее", to="Дальнее") is False


def test_city_districts_map_separates_okrug_from_district(client):
    """Баймак — райцентр ВНУТРИ своего района, Сибай — отдельный городской округ.
    На этом держится зона «мой район»: без карты городов баймакский водитель не получал
    заказы из самого Баймака, а сибайские заказы валились ему как «свои»."""
    from sqlmodel import Session as _S
    from app.db import engine as _e
    with _S(_e) as s:
        baymak = geo.area_by_name(s, "Баймак")
        sibay = geo.area_by_name(s, "Сибай")
    assert baymak.district == "Баймакский р-н"
    assert sibay.district == "г.о. Сибай"
    assert geo._city_districts()["Уфа"] == "г.о. Уфа"
