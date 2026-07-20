"""Фаза 0 — прод-харднинг (архитектурное ревью 2026-07-20).

Негативные/контрактные тесты на находки, которых раньше не хватало (аудит: тесты
были только happy-path — именно поэтому дыры приватности и не замечали):
- /match/rides не сливает точную точку сбора чужих поездок (public-витрина);
- /geocode требует авторизацию (был открытый прокси к платной квоте);
- ответ оплаты не раздаёт ФИО владельца;
- прод-гейт: SBP_PHONE не может совпадать с ADMIN_PHONES;
- пул соединений к БД настраивается и держит безопасную формулу;
- /docs скрыт в проде.
"""
import pytest
from sqlmodel import Session

from app.config import Settings
from app.db import engine
from app.models import Ride, RideCategory, RideRequest, RideStatus, UserRole
from app.timeutil import utcnow


# --------------------------- /match/rides: приватность точки сбора ---------------------------

def test_match_rides_hides_exact_pickup(client, user_factory):
    """Пассажир матчит свою заявку с поездками — точная точка сбора чужого водителя
    (pickup / pickup_lat / pickup_lng) НЕ должна утекать до подтверждённой брони."""
    passenger = user_factory("MatchPax", role=UserRole.passenger)
    driver = user_factory("MatchDrv", role=UserRole.driver)
    with Session(engine) as s:
        ride = Ride(
            driver_id=driver["id"], from_city="Баймак", to_city="Сибай",
            depart_at=utcnow(), seats_total=3, seats_left=3, category=RideCategory.regular,
            pickup="двор у школы №2", pickup_lat=52.5912, pickup_lng=58.3170,
            status=RideStatus.active,
        )
        s.add(ride)
        s.commit()
        s.refresh(ride)
        ride_id = ride.id
        req = RideRequest(
            passenger_id=passenger["id"], from_city="Баймак", to_city="Сибай",
            seats=1, category=RideCategory.regular, status="active",
        )
        s.add(req)
        s.commit()
        s.refresh(req)
        req_id = req.id

    r = client.get("/match/rides", headers=passenger["auth"], params={"request_id": req_id})
    assert r.status_code == 200
    data = r.json()
    assert any(m["id"] == ride_id for m in data), "поездка должна попасть в матч"
    for m in data:
        assert m.get("pickup", "") == "", "точка сбора (текст) не должна утекать"
        assert m.get("pickup_lat") is None, "координата точки сбора не должна утекать"
        assert m.get("pickup_lng") is None, "координата точки сбора не должна утекать"


# --------------------------- /geocode: требует авторизацию ---------------------------

def test_geocode_rejects_anonymous(client):
    assert client.get("/geocode", params={"q": "Уфа"}).status_code == 401


def test_geocode_ok_for_authed_user(client, user_factory):
    u = user_factory("GeoAuthed")
    r = client.get("/geocode", params={"q": "Уфа"}, headers=u["auth"])
    assert r.status_code == 200            # 200 (пусто без ключа Яндекса), НЕ 401
    assert "items" in r.json()


# --------------------------- оплата: не раздаём ФИО владельца ---------------------------

def test_sbp_payee_has_no_owner_full_name(client, user_factory, monkeypatch):
    """В sbp_manual ответ /donate отдаёт телефон+банк (нужны для перевода), но НЕ имя владельца."""
    from app.routers import payments as pay
    monkeypatch.setattr(pay.settings, "payments_provider", "sbp_manual")
    monkeypatch.setattr(pay.settings, "sbp_phone", "+79990001122")
    monkeypatch.setattr(pay.settings, "sbp_bank", "Сбербанк")
    monkeypatch.setattr(pay.settings, "sbp_name", "Александр А.")
    monkeypatch.setattr(pay, "_notify_new_payment", lambda *a, **k: None)  # без сети/Telegram

    u = user_factory("PayeeUser")
    r = client.post("/donate", headers=u["auth"], json={"amount": 100})
    assert r.status_code == 200
    payee = r.json().get("payee", {})
    assert "name" not in payee, "ФИО владельца не должно раздаваться"
    assert payee.get("phone") == "+79990001122", "телефон нужен для перевода — остаётся"
    assert payee.get("bank") == "Сбербанк"


# --------------------------- прод-гейт: SBP_PHONE ≠ ADMIN_PHONES ---------------------------

_PROD_BASE = dict(
    env="prod", jwt_secret="x" * 20, cors_origins="https://yulbash.ru",
    database_url="postgresql://u:p@h/db", media_base_url="https://yulbash.ru",
    payments_provider="sbp_manual",
)


def test_prod_rejects_sbp_phone_equal_admin_phone():
    # тот же номер в другом формате (+7… vs 8…) — нормализация ловит совпадение
    s = Settings(**_PROD_BASE, sbp_phone="+79990001122", admin_phones="89990001122")
    with pytest.raises(RuntimeError, match="SBP_PHONE"):
        s.validate_production()


def test_prod_allows_distinct_sbp_and_admin_phones():
    s = Settings(**_PROD_BASE, sbp_phone="+79990001122", admin_phones="+79995556677")
    s.validate_production()   # не бросает — номера разные


# --------------------------- пул соединений: безопасная формула ---------------------------

def test_db_pool_settings_are_conservative():
    s = Settings()
    # 5 воркеров × (pool+overflow) должно уместиться под дефолтный Postgres max_connections=100
    per_worker = s.db_pool_size + s.db_max_overflow
    assert per_worker * 5 <= 90, "пул × воркеры не должен приближаться к лимиту Postgres (100)"


# --------------------------- /docs скрыт в проде ---------------------------

def test_docs_hidden_in_prod(monkeypatch):
    from app import main
    monkeypatch.setattr(main.settings, "env", "prod")
    prod_app = main.create_app()
    assert prod_app.docs_url is None
    assert prod_app.redoc_url is None
    assert prod_app.openapi_url is None


def test_docs_visible_in_dev():
    from app import main
    dev_app = main.create_app()   # conftest держит env=dev
    assert dev_app.docs_url == "/docs"
    assert dev_app.openapi_url == "/openapi.json"
