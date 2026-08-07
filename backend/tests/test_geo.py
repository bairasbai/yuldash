"""Тесты географии (волна 2, батч B2): сид справочника НП, поиск /settlements,
приоритет geocode_city (Settlement → CITY_COORDS → Яндекс), популярные маршруты,
зона работы таксиста (/instant/zone) и её учёт в matcher'е (eligible).

Существующее поведение НЕ меняем: водитель без зоны (NULL) ведёт себя как раньше —
это покрыто и старыми matcher-тестами (они зону не задают), и явным тестом здесь.
"""
import fakeredis
import pytest
from sqlmodel import Session, select

from app.db import engine
from app import geo, services
from app import instant_service as isv
from app.models import InstantOrder, Settlement, UserRole

# Координаты (lat, lng): Баймак — точка А; Сибай — ~35 км по дороге (city, порог 40);
# Уфа — межгород. FAR10 — ~10 км от Баймака.
BAYMAK = (52.591, 58.317)
SIBAY = (52.716, 58.664)
UFA = (54.735, 55.958)
FAR10 = (52.681, 58.317)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="Drv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _heartbeat(client, d, coord):
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})
    assert r.status_code == 200, r.text


def _set_zone(client, d, **zone):
    r = client.post("/instant/zone", headers=d["auth"], json=zone)
    assert r.status_code == 200, r.text
    return r.json()


def _order(client, pax, frm=BAYMAK, to=SIBAY):
    r = client.post("/instant/orders", headers=pax["auth"], json={
        "from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
        "from_text": "A", "to_text": "B",
    })
    assert r.status_code == 200, r.text
    return r.json()


def _offer_driver_id(order_id: int):
    with Session(engine) as s:
        return s.get(InstantOrder, order_id).current_offer_driver_id


def _settlement_id(name_ru: str) -> int:
    with Session(engine) as s:
        return s.exec(select(Settlement).where(Settlement.name_ru == name_ru)).first().id


# ============================ Сид справочника ============================
def test_seed_counts_by_kind(client):
    """21 город + 40 райцентров-сёл (54 района минус 14 центров-городов) + 18 соседей."""
    with Session(engine) as s:
        rows = s.exec(select(Settlement)).all()
    kinds = {}
    for r in rows:
        kinds[r.kind] = kinds.get(r.kind, 0) + 1
    assert kinds["city"] == 21
    assert kinds["district_center"] == 40
    assert kinds["neighbor"] == 18


def test_seed_no_duplicates_and_rerun_noop(client):
    """Города/райцентры/соседи уникальны по имени; деревни-тёзки («Берёзовка» в трёх районах) —
    это НЕ дубли, у них ключ (имя, регион, район). Повторный сид ничего не добавляет."""
    with Session(engine) as s:
        big = list(s.exec(select(Settlement.name_ru).where(Settlement.kind != "village")).all())
        assert len(big) == len(set(big))
        villages = [(x.name_ru, x.region, x.district) for x in
                    s.exec(select(Settlement).where(Settlement.kind == "village")).all()]
        assert len(villages) == len(set(villages))
        before = len(s.exec(select(Settlement.name_ru)).all())
        geo.seed_settlements(s)                  # повторный сид
        geo.seed_villages(s)
        after = len(s.exec(select(Settlement.name_ru)).all())
    assert after == before                       # no-op


def test_seed_city_coords_match_legacy(client):
    """Города, известные старому CITY_COORDS, в справочнике с теми же координатами —
    geocode_city не «уезжает» при смене приоритета."""
    with Session(engine) as s:
        for name in ("Уфа", "Сибай", "Баймак", "Учалы", "Магнитогорск", "Акъяр"):
            st = s.exec(select(Settlement).where(Settlement.name_ru == name)).first()
            assert st is not None, name
    ufa = services.geocode_city("Уфа")
    assert ufa == (54.735, 55.958)


# ============================ Поиск /settlements ============================
def test_settlements_public_no_auth(client):
    r = client.get("/settlements", params={"q": "Сиб"})
    assert r.status_code == 200
    assert any(i["name_ru"] == "Сибай" for i in r.json()["items"])


def test_settlements_search_ru_prefix_case_insensitive(client):
    items = client.get("/settlements", params={"q": "стерли"}).json()["items"]
    assert any(i["name_ru"] == "Стерлитамак" for i in items)
    assert any(i["name_ru"] == "Стерлибашево" for i in items)


def test_settlements_search_ba_prefix(client):
    items = client.get("/settlements", params={"q": "өфө"}).json()["items"]
    assert any(i["name_ru"] == "Уфа" for i in items)
    items = client.get("/settlements", params={"q": "аҡъ"}).json()["items"]
    assert any(i["name_ru"] == "Акъяр" for i in items)


def test_settlements_limit_and_inactive_hidden(client):
    items = client.get("/settlements", params={"q": "", "limit": 5}).json()["items"]
    assert len(items) == 5
    with Session(engine) as s:
        st = Settlement(name_ru="Скрытоград", region="РБ", kind="city", lat=50, lng=50, active=False)
        s.add(st)
        s.commit()
    items = client.get("/settlements", params={"q": "Скрытоград"}).json()["items"]
    assert items == []


# ============================ geocode_city: приоритет ============================
def test_geocode_prefers_settlement_table(client):
    """Имя, которого нет в CITY_COORDS и без Яндекс-ключа → координаты из Settlement."""
    with Session(engine) as s:
        s.add(Settlement(name_ru="Тестоград", name_ba="Тестҡала", region="РБ",
                         kind="district_center", lat=50.5, lng=57.5))
        s.commit()
    assert services.geocode_city("Тестоград") == (50.5, 57.5)
    assert services.geocode_city("тестҡала") == (50.5, 57.5)   # BA-имя, без регистра
    # Темясово раньше жило только в хардкоде CITY_COORDS, теперь оно есть в справочнике (из OSM):
    # координаты берутся оттуда. Хардкод был прикидкой и мазал на ~4,5 км — проверяем, что
    # это по-прежнему то же село (в пределах 6 км), а не соседнее.
    lat, lng = services.geocode_city("Темясово")
    assert services.haversine_km(lat, lng, 52.972, 58.160) < 6.0
    # Ни в справочнике, ни в CITY_COORDS, ключа Яндекса нет → честно None, а не выдумка.
    assert services.geocode_city("Такогогороданет") is None


# ============================ Популярные маршруты ============================
def test_popular_routes(client):
    routes = client.get("/settlements/popular-routes").json()["routes"]
    assert len(routes) == len(geo.POPULAR_ROUTES) == 10
    pairs = {(r["from"]["name_ru"], r["to"]["name_ru"]) for r in routes}
    assert ("Сибай", "Магнитогорск") in pairs
    assert ("Баймак", "Уфа") in pairs
    assert all(r["from"]["lat"] and r["to"]["lat"] for r in routes)


# ============================ /instant/zone: права ============================
def test_zone_requires_driver_profile(client, user_factory):
    pax = user_factory("ZonePax")
    r = client.post("/instant/zone", headers=pax["auth"], json={"work_zone": "city"})
    assert r.status_code == 409


def test_zone_requires_approved_taxi_driver(client, user_factory):
    d = user_factory("ZoneNotTaxi", role=UserRole.driver, taxi_approved=False)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    r = client.post("/instant/zone", headers=d["auth"], json={"work_zone": "city"})
    assert r.status_code == 403


def test_zone_set_get_and_city_clears_direction(client, user_factory):
    """Старое приложение шлёт work_zone=intercity — сервер переводит это в новую схему:
    база остаётся городом, включается тумблер «выезд загород». Направление сохраняется."""
    d = _driver_online(client, user_factory, "ZoneSet")
    ufa_id = _settlement_id("Уфа")
    z = _set_zone(client, d, work_zone="intercity", work_direction_id=ufa_id)
    assert z["work_zone"] == "city" and z["work_intercity"] is True
    assert z["work_direction"]["name_ru"] == "Уфа"
    g = client.get("/instant/zone", headers=d["auth"]).json()
    assert g["work_direction_id"] == ufa_id
    # Без «загорода» направление смысла не имеет → сервер его чистит.
    z = _set_zone(client, d, work_zone="city", work_city="Баймак", work_direction_id=ufa_id)
    assert z["work_city"] == "Баймак" and z["work_direction_id"] is None
    assert z["work_intercity"] is False and z["work_regions"] is False


def test_zone_district_base_saved(client, user_factory):
    """Новая база «мой район»: город чистится, район сохраняется, тумблеры — как прислали."""
    d = _driver_online(client, user_factory, "ZoneDistrict")
    z = _set_zone(client, d, work_zone="district", work_district="Абзелиловский р-н",
                  work_city="Сибай", work_intercity=True, work_regions=True)
    assert z["work_zone"] == "district" and z["work_district"] == "Абзелиловский р-н"
    assert z["work_city"] is None                      # база одна: либо НП, либо район
    assert z["work_intercity"] is True and z["work_regions"] is True


def test_zone_regions_require_intercity(client, user_factory):
    """«Соседние регионы» без «выезда загород» — бессмыслица: тумблер не включаем."""
    d = _driver_online(client, user_factory, "ZoneRegOnly")
    z = _set_zone(client, d, work_zone="city", work_city="Сибай",
                  work_intercity=False, work_regions=True)
    assert z["work_intercity"] is False and z["work_regions"] is False


def test_zone_unknown_direction_404_and_bad_zone_422(client, user_factory):
    d = _driver_online(client, user_factory, "ZoneBad")
    r = client.post("/instant/zone", headers=d["auth"],
                    json={"work_zone": "intercity", "work_direction_id": 99999999})
    assert r.status_code == 404
    r = client.post("/instant/zone", headers=d["auth"], json={"work_zone": "galaxy"})
    assert r.status_code == 422


# ============================ Matcher: зона в eligible() ============================
def test_zone_city_driver_gets_own_city_not_foreign(client, user_factory, fake_redis):
    """Городской заказ в Баймаке: ближе стоит «сибайский» водитель, но оффер уходит
    «баймакскому» — чужой город отфильтрован."""
    wrong = _driver_online(client, user_factory, "SibayDrv")
    _set_zone(client, wrong, work_zone="city", work_city="Сибай")
    _heartbeat(client, wrong, BAYMAK)                 # ближе всех, но город не его
    right = _driver_online(client, user_factory, "BaymakDrv")
    _set_zone(client, right, work_zone="city", work_city="Баймак")
    _heartbeat(client, right, FAR10)                  # дальше, но город совпадает
    pax = user_factory("CityPax")
    order = _order(client, pax)                       # Баймак → Сибай (~35 км дороги = city)
    assert order["status"] == "offered"
    assert _offer_driver_id(order["id"]) == right["id"]


def test_zone_city_driver_alone_in_foreign_city_expires(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "OnlyForeign")
    _set_zone(client, d, work_zone="city", work_city="Сибай")
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("CityPax2"))
    assert order["status"] == "expired"


def test_zone_city_driver_not_offered_intercity(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "CityOnly")
    _set_zone(client, d, work_zone="city", work_city="Баймак")
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("IcPax"), to=UFA)   # Баймак → Уфа = межгород
    assert order["status"] == "expired"


def test_zone_intercity_direction_match_offered(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "IcUfa")
    _set_zone(client, d, work_zone="intercity", work_direction_id=_settlement_id("Уфа"))
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("IcPax2"), to=UFA)
    assert order["status"] == "offered"
    assert _offer_driver_id(order["id"]) == d["id"]


def test_zone_intercity_direction_mismatch_expires(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "IcMagnit")
    _set_zone(client, d, work_zone="intercity", work_direction_id=_settlement_id("Магнитогорск"))
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("IcPax3"), to=UFA)   # едем в Уфу, направление — Магнитогорск
    assert order["status"] == "expired"


def test_zone_intercity_no_direction_takes_all(client, user_factory, fake_redis):
    """Межгород без закреплённого направления: берёт и межгород куда угодно, и городские."""
    d = _driver_online(client, user_factory, "IcFree")
    _set_zone(client, d, work_zone="intercity")
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("IcPax4"), to=UFA)
    assert order["status"] == "offered"
    client.post(f"/instant/orders/{order['id']}/decline", headers=d["auth"])
    _heartbeat(client, d, BAYMAK)
    order2 = _order(client, user_factory("IcPax5"))          # городской (Баймак → Сибай)
    assert order2["status"] == "offered"
    assert _offer_driver_id(order2["id"]) == d["id"]


def test_zone_intercity_with_direction_skips_city_order(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "IcPinned")
    _set_zone(client, d, work_zone="intercity", work_direction_id=_settlement_id("Уфа"))
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("CityPax3"))         # городской заказ
    assert order["status"] == "expired"


def test_zone_null_prior_behavior(client, user_factory, fake_redis):
    """Без зоны (NULL) — прежнее поведение: получает и городской, и межгород."""
    d = _driver_online(client, user_factory, "NoZone")
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("NullPax"), to=UFA)
    assert order["status"] == "offered"
    client.post(f"/instant/orders/{order['id']}/decline", headers=d["auth"])
    _heartbeat(client, d, BAYMAK)
    order2 = _order(client, user_factory("NullPax2"))
    assert order2["status"] == "offered"
    assert _offer_driver_id(order2["id"]) == d["id"]


# ============================ Зона «мой район» (2026-08-06) ============================
# Точки: два села Баймакского района (заказ целиком внутри района). Водители стоят рядом
# с подачей — подбор ищет в радиусе 3/7/15 км, дальше присланных просто не найдёт.
ITKULOVO = (52.629, 57.968)       # 1-е Иткулово, Баймакский р-н
ITKULOVO_2 = (52.825, 57.980)     # 2-е Иткулово, Баймакский р-н (~22 км — местная поездка)
NEAR_ITKULOVO = (52.665, 57.968)  # ~4 км от подачи


def test_zone_district_driver_gets_village_order_in_his_district(client, user_factory, fake_redis):
    """Главный сценарий Александра: водитель выбрал «Баймакский район» — заказ между двумя
    сёлами этого района приходит ему, а «абзелиловскому», стоящему прямо на подаче, — нет."""
    foreign = _driver_online(client, user_factory, "AbzDrv")
    _set_zone(client, foreign, work_zone="district", work_district="Абзелиловский р-н")
    _heartbeat(client, foreign, ITKULOVO)                 # стоит прямо на подаче, но район чужой
    mine = _driver_online(client, user_factory, "BaymakDistrictDrv")
    _set_zone(client, mine, work_zone="district", work_district="Баймакский р-н")
    _heartbeat(client, mine, NEAR_ITKULOVO)               # чуть дальше, зато район свой
    order = _order(client, user_factory("VillPax"), frm=ITKULOVO, to=ITKULOVO_2)
    assert order["status"] == "offered"
    assert _offer_driver_id(order["id"]) == mine["id"]


def test_zone_district_driver_not_offered_far_order_without_intercity(client, user_factory, fake_redis):
    """«Работаю по своему району» без «выезда загород»: дальний заказ в Уфу не предлагаем."""
    d = _driver_online(client, user_factory, "BaymakOnly")
    _set_zone(client, d, work_zone="district", work_district="Баймакский р-н")
    _heartbeat(client, d, ITKULOVO)
    order = _order(client, user_factory("FarPax"), frm=ITKULOVO, to=UFA)
    assert order["status"] == "expired"


def test_zone_district_with_intercity_gets_far_order(client, user_factory, fake_redis):
    """Тот же водитель включил «выезд загород» — дальний заказ приходит."""
    d = _driver_online(client, user_factory, "BaymakOut")
    _set_zone(client, d, work_zone="district", work_district="Баймакский р-н", work_intercity=True)
    _heartbeat(client, d, ITKULOVO)
    order = _order(client, user_factory("FarPax2"), frm=ITKULOVO, to=UFA)
    assert order["status"] == "offered"
    assert _offer_driver_id(order["id"]) == d["id"]


def test_zone_city_driver_takes_short_hop_to_neighbour_town(client, user_factory, fake_redis):
    """Баймак → Сибай (35 км) — для человека это «по-местному», а не межгород:
    водитель с базой «Баймак» получает заказ и без тумблера «загород»."""
    d = _driver_online(client, user_factory, "BaymakShort")
    _set_zone(client, d, work_zone="city", work_city="Баймак")
    _heartbeat(client, d, BAYMAK)
    order = _order(client, user_factory("ShortPax"))       # Баймак → Сибай
    assert order["status"] == "offered"
    assert _offer_driver_id(order["id"]) == d["id"]


def test_districts_endpoint_lists_bashkortostan_first(client):
    """Список районов для пикера: публичный, РБ сверху, у района видно число НП."""
    items = client.get("/settlements/districts").json()["items"]
    assert len(items) > 50
    assert items[0]["region"] == "РБ"
    baymak = next(i for i in items if i["district"] == "Баймакский р-н")
    assert baymak["settlements"] > 30
    # Поиск по началу названия — как в подсказках НП.
    only = client.get("/settlements/districts", params={"q": "абзел"}).json()["items"]
    assert [i["district"] for i in only] == ["Абзелиловский р-н"]


def test_zone_district_typo_rejected(client, user_factory):
    """Район с опечаткой сервер не принимает: иначе водитель сидел бы без заказов
    и не понимал, почему тихо."""
    d = _driver_online(client, user_factory, "ZoneTypo")
    r = client.post("/instant/zone", headers=d["auth"],
                    json={"work_zone": "district", "work_district": "Абзелиловскй"})
    assert r.status_code == 422
    ok = client.post("/instant/zone", headers=d["auth"],
                     json={"work_zone": "district", "work_district": "абзелиловский р-н"})
    assert ok.status_code == 200      # регистр не важен — сверяем по свёртке
