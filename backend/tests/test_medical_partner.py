"""F22 — B2B «медцентр-партнёр»: справочник клиник + поездки к клинике.

Проверяем ЛОГИСТИКУ (доехать до клиники) и ДЕЛИКАТНОСТЬ:
- список партнёров отдаётся;
- поездка привязывается к клинике-назначению и находится в «поездках к клинике»;
- никаких мед.данных пациента в API нет (только точка назначения);
- приватность как везде: телефон/точная точка сбора не утекают в витрине.
"""
from sqlmodel import Session

from app.db import engine
from app.models import MedicalPartner, UserRole

from test_flows import _publish


def _make_partner(name="Тест-клиника", city="Уфа", active=True) -> int:
    with Session(engine) as s:
        p = MedicalPartner(name=name, city=city, address="ул. Тестовая, 1",
                           lat=54.7, lng=55.9, description="Как доехать: центр.", active=active)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def test_list_medical_partners_returns_active_only(client):
    active_id = _make_partner(name="Активная клиника", city="Сибай", active=True)
    hidden_id = _make_partner(name="Скрытая клиника", city="Сибай", active=False)
    rows = client.get("/medical-partners", params={"city": "Сибай"}).json()
    ids = [r["id"] for r in rows]
    assert active_id in ids
    assert hidden_id not in ids       # неактивные не показываем
    # В справочнике — только публичные данные организации, ничего мед./личного.
    sample = next(r for r in rows if r["id"] == active_id)
    assert set(sample.keys()) == {
        "id", "name", "city", "address", "lat", "lng", "description", "active", "created_at",
    }


def test_get_medical_partner_404_for_inactive_or_missing(client):
    hidden_id = _make_partner(name="Неактивная", active=False)
    assert client.get(f"/medical-partners/{hidden_id}").status_code == 404
    assert client.get("/medical-partners/99999999").status_code == 404


def test_ride_to_clinic_is_found_under_partner(client, user_factory):
    """Поездка «в больницу» с выбранной клиникой находится в /medical-partners/{id}/rides."""
    partner_id = _make_partner(name="РКБ тест", city="Уфа")
    driver = user_factory("ClinicDriver", role=UserRole.driver)
    ride = _publish(client, driver, frm="Баймак", to="Уфа",
                    category="hospital", partner_id=partner_id)

    data = client.get(f"/medical-partners/{partner_id}/rides", headers=driver["auth"]).json()
    assert data["partner"]["id"] == partner_id
    assert data["count"] == 1
    got = data["items"][0]
    assert got["id"] == ride["id"]
    assert got["partner_id"] == partner_id
    assert got["category"] == "hospital"


def test_rides_to_partner_are_isolated_per_clinic(client, user_factory):
    """Поездка к клинике A не попадает в список клиники B."""
    a = _make_partner(name="Клиника A", city="Уфа")
    b = _make_partner(name="Клиника B", city="Уфа")
    driver = user_factory("IsoDriver", role=UserRole.driver)
    _publish(client, driver, frm="Баймак", to="Уфа", category="hospital", partner_id=a)

    assert client.get(f"/medical-partners/{a}/rides", headers=driver["auth"]).json()["count"] == 1
    assert client.get(f"/medical-partners/{b}/rides", headers=driver["auth"]).json()["count"] == 0


def test_clinic_rides_require_login(client, user_factory):
    """Приватность: список поездок к клинике (кто едет в больницу) — только для вошедших (152-ФЗ).
    Справочник клиник (без поездок) остаётся публичным."""
    partner_id = _make_partner(name="Логин-клиника", city="Уфа")
    assert client.get(f"/medical-partners/{partner_id}/rides").status_code == 401    # аноним не видит
    assert client.get(f"/medical-partners/{partner_id}").status_code == 200          # справочник публичен
    u = user_factory("ClinicViewer")
    assert client.get(f"/medical-partners/{partner_id}/rides", headers=u["auth"]).status_code == 200


def test_create_ride_rejects_unknown_partner(client, user_factory):
    """Битая ссылка на клинику отклоняется (400), а не пишется в БД."""
    driver = user_factory("BadPartnerDriver", role=UserRole.driver)
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 2, "price": 100, "category": "hospital", "partner_id": 99999999,
    })
    assert r.status_code == 400


def test_clinic_rides_do_not_leak_private_data(client, user_factory):
    """Приватность: витрина поездок к клинике не раскрывает телефон/точную точку сбора,
    и в ответе нет никаких мед.данных пациента (только логистика — точка назначения)."""
    partner_id = _make_partner(name="Приват-клиника", city="Уфа")
    driver = user_factory("PrivClinicDriver", role=UserRole.driver)
    client.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймак", "to_city": "Уфа", "depart_at": "2030-01-01T10:00:00",
        "seats_total": 2, "price": 100, "category": "hospital", "partner_id": partner_id,
        "pickup": "Секретная точка сбора", "pickup_lat": 54.71, "pickup_lng": 55.92,
    })
    got = client.get(f"/medical-partners/{partner_id}/rides", headers=driver["auth"]).json()["items"][0]
    # Витрина для вошедших: точная точка сбора всё равно скрыта.
    assert got["pickup"] == ""
    assert got["pickup_lat"] is None
    assert got["pickup_lng"] is None
    # Никаких мед.полей/телефона в схеме витрины поездки.
    forbidden = {"phone", "diagnosis", "patient", "medical_note", "complaint", "symptoms"}
    assert forbidden.isdisjoint(got.keys())
