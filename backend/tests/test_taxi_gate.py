"""Тесты «Гейт такси + онбординг таксиста» (волна 2, 580-ФЗ).

Покрываем:
- глобальный флаг ВЫКЛ → такси недоступно пассажиру (estimate/заказ) и водителю (presence),
  ПОПУТКА (плановые Ride) работает — важнейший инвариант;
- флаг ВКЛ: пустой список городов = такси везде; город в списке / не в списке / выключенная
  запись / город неизвестен (далеко от всех) / RU-BA алиасы имён городов;
- заявка таксиста: валидация возраста (20+), стажа (2+ лет), ИНН (10-12 цифр);
- цикл: подал → pending (гейт держит) → админ approve → гейт пропускает;
  reject → гейт держит + комментарий виден + повторная подача разрешена;
- анти-IDOR (чужая заявка не видна; чужой документ не подставить) и админ-права.
"""
from datetime import date, timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app.db import engine
from app import instant_service as isv
from app.config import settings
from app.models import InstantOrder, InstantOrderStatus as S, TaxiCity, UserRole
from app.timeutil import utcnow

BAIMAK = (52.591, 58.317)
SIBAY = (52.716, 58.664)
FAR_AWAY = (55.160, 61.400)   # Челябинск — дальше 30 км от всех известных городов (город неизвестен)

VALID_APPLY = {
    "inn": "123456789012",
    "permit_number": "Т-77-001",
    "birth_date": "1990-05-01",
    "license_since_year": 2015,
    "permit_photo_url": "",
    "osago_url": "",
}


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _clean_taxi_cities():
    """Список городов — глобальное состояние сессионной БД: чистим после каждого теста,
    чтобы не ломать инвариант других тестов «пустой список = такси везде»."""
    yield
    with Session(engine) as s:
        for c in s.exec(select(TaxiCity)).all():
            s.delete(c)
        s.commit()


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="GateDrv", taxi_approved=None):
    d = user_factory(name, role=UserRole.driver, taxi_approved=taxi_approved)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _heartbeat(client, d, coord=BAIMAK):
    return client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})


def _availability(client, u, coord):
    return client.get(f"/instant/availability?lat={coord[0]}&lng={coord[1]}", headers=u["auth"]).json()


def _estimate_body(frm=BAIMAK, to=SIBAY):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай"}


def _add_city(client, admin, city, enabled=True):
    r = client.post("/admin/taxi-cities", headers=admin["auth"], json={"city": city, "enabled": enabled})
    assert r.status_code == 200, r.text
    return r.json()


# ============================ Глобальный флаг ВЫКЛ ============================
def test_global_off_blocks_taxi_but_not_poputka(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "taxi_enabled", False)
    pax = user_factory("GOffPax")
    # Пассажир: availability понятно объясняет, estimate и заказ — 403.
    av = _availability(client, pax, BAIMAK)
    assert av["enabled"] is False and av["reason"] == "global_off"
    assert av["message"]["ru"] and av["message"]["ba"]          # два языка обязательны
    assert client.post("/instant/estimate", headers=pax["auth"], json=_estimate_body()).status_code == 403
    assert client.post("/instant/orders", headers=pax["auth"], json=_estimate_body()).status_code == 403
    # Водитель (одобренный таксист!) — тоже выключено.
    d = _driver_online(client, user_factory, "GOffDrv")
    assert _heartbeat(client, d).status_code == 403
    # ПОПУТКА работает: плановая поездка публикуется спокойно.
    ride = {"from_city": "Уфа", "to_city": "Сибай",
            "depart_at": (utcnow() + timedelta(days=1)).isoformat(),
            "seats_total": 3, "price": 500}
    r = client.post("/rides", headers=d["auth"], json=ride)
    assert r.status_code == 200, r.text


# ============================ Флаг ВКЛ: города ============================
def test_empty_city_list_means_everywhere(client, user_factory):
    pax = user_factory("EmptyPax")
    assert _availability(client, pax, BAIMAK)["enabled"] is True
    assert _availability(client, pax, FAR_AWAY)["enabled"] is True   # пустой список = все города


def test_city_in_list_and_not_in_list(client, user_factory):
    admin = user_factory("CityAdmin", role=UserRole.admin)
    _add_city(client, admin, "Баймак")
    pax = user_factory("CityPax")
    # Баймак в списке → включено; Сибай не в списке → выключено; далеко от всех → выключено.
    ok = _availability(client, pax, BAIMAK)
    assert ok["enabled"] is True and ok["reason"] == "ok"
    sib = _availability(client, pax, SIBAY)
    assert sib["enabled"] is False and sib["reason"] == "city_off"
    assert sib["message"]["ru"] and sib["message"]["ba"]
    far = _availability(client, pax, FAR_AWAY)
    assert far["enabled"] is False and far["reason"] == "city_off"
    # Гейт на пассажирских ручках: из Сибая — 403, из Баймака — считается.
    assert client.post("/instant/estimate", headers=pax["auth"],
                       json=_estimate_body(frm=SIBAY, to=BAIMAK)).status_code == 403
    est = client.post("/instant/estimate", headers=pax["auth"], json=_estimate_body())
    assert est.status_code == 200 and est.json()["price"] > 0


def test_disabled_city_entry_counts_as_off(client, user_factory):
    admin = user_factory("DisAdmin", role=UserRole.admin)
    _add_city(client, admin, "Баймак", enabled=False)
    pax = user_factory("DisPax")
    av = _availability(client, pax, BAIMAK)
    assert av["enabled"] is False and av["reason"] == "city_off"


def test_city_alias_ru_ba_names_match(client, user_factory):
    """Админ внёс башкирское имя «Баймаҡ» — точка Баймака считается включённой (алиасы одной точки)."""
    admin = user_factory("AliasAdmin", role=UserRole.admin)
    _add_city(client, admin, "Баймаҡ")
    pax = user_factory("AliasPax")
    assert _availability(client, pax, BAIMAK)["enabled"] is True


def test_availability_without_coords_in_city_mode_is_off(client, user_factory):
    admin = user_factory("NoCoordAdmin", role=UserRole.admin)
    _add_city(client, admin, "Баймак")
    pax = user_factory("NoCoordPax")
    av = client.get("/instant/availability", headers=pax["auth"]).json()
    assert av["enabled"] is False and av["reason"] == "city_off"   # город неизвестен → выключено


def test_driver_gate_respects_city_list(client, user_factory, fake_redis):
    """Водитель в выключенном городе: presence → 403; в включённом — работает."""
    admin = user_factory("DrvCityAdmin", role=UserRole.admin)
    _add_city(client, admin, "Баймак")
    d = _driver_online(client, user_factory, "DrvCityDrv")
    assert _heartbeat(client, d, coord=BAIMAK).status_code == 200
    assert _heartbeat(client, d, coord=SIBAY).status_code == 403


# ============================ Заявка: валидация ============================
def test_apply_validation(client, user_factory):
    d = user_factory("ValDrv", role=UserRole.driver, taxi_approved=False)
    year = utcnow().year
    # Возраст < 20.
    young = {**VALID_APPLY, "birth_date": f"{year - 18}-01-01"}
    r = client.post("/taxi/apply", headers=d["auth"], json=young)
    assert r.status_code == 400 and "20" in r.json()["detail"]
    # Стаж < 2 лет.
    fresh = {**VALID_APPLY, "license_since_year": year}
    r = client.post("/taxi/apply", headers=d["auth"], json=fresh)
    assert r.status_code == 400 and "стаж" in r.json()["detail"].lower()
    # Год прав в будущем.
    assert client.post("/taxi/apply", headers=d["auth"],
                       json={**VALID_APPLY, "license_since_year": year + 1}).status_code == 400
    # ИНН: короткий / с буквами — отказ; 10 и 12 цифр — ок.
    assert client.post("/taxi/apply", headers=d["auth"], json={**VALID_APPLY, "inn": "12345"}).status_code == 400
    assert client.post("/taxi/apply", headers=d["auth"], json={**VALID_APPLY, "inn": "12345абв9012"}).status_code == 400
    assert client.post("/taxi/apply", headers=d["auth"], json={**VALID_APPLY, "inn": "1234567890"}).status_code == 200
    assert client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY).status_code == 200


# ============================ Цикл: pending → approve / reject ============================
def test_pending_then_approve_opens_gate(client, user_factory):
    d = _driver_online(client, user_factory, "CycleDrv", taxi_approved=False)
    # Без заявки: гейт (b) держит, ошибка понятная.
    resp = _heartbeat(client, d)
    assert resp.status_code == 403 and "провер" in resp.json()["detail"].lower()
    assert client.get("/taxi/application", headers=d["auth"]).status_code == 404
    # Подал → pending, гейт всё ещё держит.
    made = client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY).json()
    assert made["status"] == "pending"
    assert client.get("/taxi/application", headers=d["auth"]).json()["status"] == "pending"
    assert _heartbeat(client, d).status_code == 403
    # Админ одобрил → гейт пропускает.
    admin = user_factory("CycleAdmin", role=UserRole.admin)
    pend = client.get("/admin/taxi-applications?status=pending", headers=admin["auth"]).json()
    mine = [a for a in pend if a["user_id"] == d["id"]]
    assert len(mine) == 1 and mine[0]["phone"]
    ok = client.post(f"/admin/taxi-applications/{mine[0]['id']}/approve", headers=admin["auth"]).json()
    assert ok["status"] == "approved"
    assert client.get("/taxi/application", headers=d["auth"]).json()["status"] == "approved"
    assert _heartbeat(client, d).status_code == 200


def test_reject_holds_gate_and_reapply_allowed(client, user_factory):
    d = _driver_online(client, user_factory, "RejGateDrv", taxi_approved=False)
    app_id = client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY).json()["id"]
    admin = user_factory("RejGateAdmin", role=UserRole.admin)
    rej = client.post(f"/admin/taxi-applications/{app_id}/reject", headers=admin["auth"],
                      json={"comment": "Фото разрешения нечитаемое"}).json()
    assert rej["status"] == "rejected"
    mine = client.get("/taxi/application", headers=d["auth"]).json()
    assert mine["status"] == "rejected" and "нечитаемое" in mine["comment"]
    assert _heartbeat(client, d).status_code == 403          # гейт держит
    # Повторная подача разрешена: та же заявка → снова pending, комментарий очищен.
    again = client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY).json()
    assert again["id"] == app_id and again["status"] == "pending" and again["comment"] == ""
    assert _heartbeat(client, d).status_code == 403          # но до approve такси всё ещё нельзя


def test_apply_when_already_approved_conflict(client, user_factory):
    d = user_factory("ApprDrv", role=UserRole.driver)   # авто-approved (conftest)
    assert client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY).status_code == 409


def test_unapproved_driver_gets_no_offers_and_cannot_accept(client, user_factory, fake_redis):
    """Гейт (b) на офферах: без approved-заявки поллинг пуст, accept — 403."""
    d = _driver_online(client, user_factory, "NoAppDrv", taxi_approved=False)
    pax = user_factory("NoAppPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_estimate_body()).json()
    with Session(engine) as s:
        o = s.get(InstantOrder, order["id"])
        o.status = S.offered
        o.current_offer_driver_id = d["id"]
        o.offer_expires_at = utcnow() + timedelta(minutes=5)
        s.add(o)
        s.commit()
    assert client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"] is None
    resp = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
    assert resp.status_code == 403 and "провер" in resp.json()["detail"].lower()


# ============================ Анти-IDOR / права ============================
def test_application_is_per_token_no_idor(client, user_factory):
    a = user_factory("IdorA", role=UserRole.driver, taxi_approved=False)
    client.post("/taxi/apply", headers=a["auth"], json=VALID_APPLY)
    b = user_factory("IdorB", role=UserRole.driver, taxi_approved=False)
    assert client.get("/taxi/application", headers=b["auth"]).status_code == 404   # чужая не видна


def test_apply_with_foreign_document_forbidden(client, user_factory):
    """Подставить чужой защищённый документ в свою заявку нельзя (анти-IDOR документов)."""
    d = user_factory("ForeignDoc", role=UserRole.driver, taxi_approved=False)
    body = {**VALID_APPLY, "permit_photo_url": "/secure/docs/999999_deadbeef.jpg"}
    assert client.post("/taxi/apply", headers=d["auth"], json=body).status_code == 403


def test_admin_endpoints_require_admin(client, user_factory):
    d = user_factory("NotAdmin", role=UserRole.driver, taxi_approved=False)
    app_id = client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY).json()["id"]
    assert client.get("/admin/taxi-applications", headers=d["auth"]).status_code == 403
    assert client.post(f"/admin/taxi-applications/{app_id}/approve", headers=d["auth"]).status_code == 403
    assert client.post(f"/admin/taxi-applications/{app_id}/reject", headers=d["auth"],
                       json={"comment": "x"}).status_code == 403
    assert client.get("/admin/taxi-cities", headers=d["auth"]).status_code == 403
    assert client.post("/admin/taxi-cities", headers=d["auth"],
                       json={"city": "Уфа", "enabled": True}).status_code == 403
    assert client.delete("/admin/taxi-cities/1", headers=d["auth"]).status_code == 403


def test_admin_approve_unknown_404(client, user_factory):
    admin = user_factory("Admin404T", role=UserRole.admin)
    assert client.post("/admin/taxi-applications/99999999/approve", headers=admin["auth"]).status_code == 404


# ============================ Админ: города CRUD ============================
def test_admin_cities_crud(client, user_factory):
    admin = user_factory("CrudAdmin", role=UserRole.admin)
    row = _add_city(client, admin, "Уфа")
    cities = client.get("/admin/taxi-cities", headers=admin["auth"]).json()
    assert any(c["city"] == "Уфа" and c["enabled"] for c in cities)
    # Повторное добавление того же имени (без учёта регистра) — обновляет, не дублирует.
    row2 = _add_city(client, admin, "уфа", enabled=False)
    assert row2["id"] == row["id"] and row2["enabled"] is False
    cities = client.get("/admin/taxi-cities", headers=admin["auth"]).json()
    assert len([c for c in cities if c["city"].casefold() == "уфа"]) == 1
    # Удаление.
    assert client.delete(f"/admin/taxi-cities/{row['id']}", headers=admin["auth"]).json()["ok"] is True
    assert client.delete(f"/admin/taxi-cities/{row['id']}", headers=admin["auth"]).status_code == 404


# ---- Проверки водителя, Уровень 1: селфи + справка о несудимости + «кто пригласил» ----

def test_apply_carries_driver_check_fields(client, user_factory):
    """Заявка отдаёт новые поля проверок (селфи/справка). Без загрузки — пустые (не ломает старый флоу)."""
    d = user_factory("SelfieDrv", role=UserRole.driver, taxi_approved=False)
    r = client.post("/taxi/apply", headers=d["auth"], json=VALID_APPLY)
    assert r.status_code == 200, r.text
    app = client.get("/taxi/application", headers=d["auth"]).json()
    assert "selfie_url" in app and "criminal_record_url" in app
    assert app["selfie_url"] == "" and app["criminal_record_url"] == ""


def test_admin_applications_show_checks_and_inviter(client, user_factory):
    """Админ-очередь заявок отдаёт поля проверок и «кто пригласил» (доверие между своими)."""
    admin = user_factory("ChkAdmin", role=UserRole.admin)
    inviter = user_factory("Пригласивший")
    code = client.get("/referral/me", headers=inviter["auth"]).json()["code"]
    invited = user_factory("Приглашённый", role=UserRole.driver, taxi_approved=False)
    client.post("/referral/redeem", headers=invited["auth"], json={"code": code})
    client.post("/taxi/apply", headers=invited["auth"], json=VALID_APPLY)
    rows = client.get("/admin/taxi-applications?status=pending", headers=admin["auth"]).json()
    mine = [x for x in rows if x["user_id"] == invited["id"]]
    assert mine, "заявка приглашённого должна быть в очереди"
    row = mine[0]
    assert "selfie_url" in row and "criminal_record_url" in row
    assert row["invited_by"] == "Пригласивший"
