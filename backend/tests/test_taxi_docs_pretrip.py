# -*- coding: utf-8 -*-
"""580-ФЗ, честный минимум: сроки документов и предрейсовое подтверждение.

Истории, ради которых это написано:
- одобрили таксиста в июле → в декабре он возит с просроченным ОСАГО, а мы зовём его
  «проверенным водителем»: проверка была разовой и не истекала никогда;
- водитель честно продлил ОСАГО, а сказать об этом системе нечем, кроме повторной подачи
  заявки, которая сбрасывает статус в pending — наказание за законопослушность;
- слова «предрейсовый осмотр» в коде не встречалось ни разу.
"""
from datetime import date, timedelta

import pytest
from sqlmodel import Session, select

from app import doc_check, pretrip
from app.config import settings
from app.db import engine
from app.models import (
    DriverProfile, PreTripCheck, TaxiApplication, UserRole,
)
from app.timeutil import local_date, utcnow


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    """Пуши и SMS в тестах молчат: проверяем поведение, а не доставку."""
    monkeypatch.setattr("app.doc_check._push", lambda *a, **k: None)


def _today() -> date:
    """«Сегодня» по Уфе — как считает сервер.

    Стояло `utcnow().date()`: с 19:00 UTC (полночь в Уфе) тест жил уже во вчерашнем дне и
    «до истечения 200 дней» превращалось в 199. Прогон краснел вечером и был зелёным утром —
    хуже, чем просто красный: в такой тест перестают верить (аудит 2026-08-08, волна 154)."""
    return local_date(utcnow())


def _app_of(uid: int) -> TaxiApplication:
    with Session(engine) as s:
        return s.exec(select(TaxiApplication).where(TaxiApplication.user_id == uid)).one()


def _set_dates(uid: int, **kw) -> None:
    with Session(engine) as s:
        app = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == uid)).one()
        for k, v in kw.items():
            setattr(app, k, v)
        s.add(app)
        s.commit()


# ============================== сроки документов: подача ==============================

def test_apply_rejects_already_expired_osago(client, user_factory):
    """Просроченный документ в момент подачи — это отказ, а не «почти готов»."""
    u = user_factory("Кандидат", role=UserRole.passenger, taxi_approved=False)
    r = client.post("/taxi/apply", headers=u["auth"], json={
        "inn": "123456789012", "permit_number": "Т-777",
        "birth_date": "1990-01-01", "license_since_year": 2010,
        "osago_until": (_today() - timedelta(days=1)).isoformat(),
    })
    assert r.status_code == 400
    assert "ОСАГО" in r.json()["detail"]["ru"]


def test_apply_accepts_and_returns_doc_dates(client, user_factory):
    u = user_factory("Кандидат2", role=UserRole.passenger, taxi_approved=False)
    until = (_today() + timedelta(days=200)).isoformat()
    r = client.post("/taxi/apply", headers=u["auth"], json={
        "inn": "123456789012", "permit_number": "Т-778",
        "birth_date": "1990-01-01", "license_since_year": 2010,
        "osago_until": until, "permit_until": until, "inspection_until": until,
    })
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["osago_until"] == until
    assert body["inspection_until"] == until
    assert body["docs_missing"] == []          # всё заполнено → модератору нечего требовать
    assert body["docs_days_left"] == 200


def test_apply_without_dates_still_works_but_flags_missing(client, user_factory):
    """Даты необязательны намеренно: старое приложение их не шлёт, ломать его нельзя.
    Но пропуск виден и водителю, и модератору в очереди заявок."""
    u = user_factory("Кандидат3", role=UserRole.passenger, taxi_approved=False)
    r = client.post("/taxi/apply", headers=u["auth"], json={
        "inn": "123456789012", "permit_number": "Т-779",
        "birth_date": "1990-01-01", "license_since_year": 2010,
    })
    assert r.status_code == 200, r.text
    assert set(r.json()["docs_missing"]) == {"osago_until", "permit_until", "inspection_until"}
    assert r.json()["docs_days_left"] is None


def test_apply_still_requires_three_years_of_experience(client, user_factory):
    """580-ФЗ: стаж от 3 лет. Правило применяется к НОВЫМ заявкам."""
    u = user_factory("Новичок", role=UserRole.passenger, taxi_approved=False)
    r = client.post("/taxi/apply", headers=u["auth"], json={
        "inn": "123456789012", "permit_number": "Т-780",
        "birth_date": "1990-01-01", "license_since_year": _today().year - 2,
    })
    assert r.status_code == 400
    assert "3" in r.json()["detail"]["ru"]


def test_already_approved_driver_is_not_rechecked_for_experience(client, user_factory):
    """Уже одобренных задним числом не выгоняем: мы сами их одобрили (решение 2026-07-26).
    Допуск смотрит только на статус заявки и сроки документов, стаж не пере-проверяется."""
    drv = user_factory("Ветеран", role=UserRole.driver)
    _set_dates(drv["id"], license_since_year=_today().year - 2)
    from app import taxi as taxi_mod
    with Session(engine) as s:
        assert taxi_mod.is_approved_taxi_driver(s, drv["id"]) is True


# ============================== обновление документов ==============================

def test_update_documents_keeps_approved_status(client, user_factory):
    """Продление ОСАГО не должно сбрасывать заявку в pending — иначе человек без работы."""
    drv = user_factory("Продлил", role=UserRole.driver)
    until = (_today() + timedelta(days=365)).isoformat()
    r = client.post("/taxi/documents", headers=drv["auth"], json={"osago_until": until})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "approved"
    assert r.json()["osago_until"] == until


def test_update_documents_restores_access_immediately(client, user_factory):
    """Допуск возвращается сразу, а не ночным прогоном: ждать сутки, чтобы поехать, нельзя."""
    drv = user_factory("Просрочил", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() - timedelta(days=3), docs_expired=True)
    r = client.post("/taxi/documents", headers=drv["auth"], json={
        "osago_until": (_today() + timedelta(days=180)).isoformat(),
    })
    assert r.status_code == 200, r.text
    assert r.json()["docs_expired"] is False


def test_update_documents_rejects_past_date(client, user_factory):
    drv = user_factory("Опечатка", role=UserRole.driver)
    r = client.post("/taxi/documents", headers=drv["auth"], json={
        "permit_until": (_today() - timedelta(days=1)).isoformat(),
    })
    assert r.status_code == 400


def test_update_documents_partial_keeps_other_fields(client, user_factory):
    """Прислали одно поле — остальные не обнуляются."""
    drv = user_factory("Частично", role=UserRole.driver)
    far = _today() + timedelta(days=300)
    _set_dates(drv["id"], permit_until=far)
    client.post("/taxi/documents", headers=drv["auth"], json={
        "osago_until": (_today() + timedelta(days=90)).isoformat(),
    })
    assert _app_of(drv["id"]).permit_until == far


# ============================== фоновый контроль сроков ==============================

def test_expire_overdue_suspends_taxi_and_takes_driver_offline(client, user_factory):
    """Срок прошёл → допуск снят и водитель снят с линии (иначе офферы летят в отбой)."""
    drv = user_factory("Забыл", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() - timedelta(days=1))
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv["id"], online=True))
        s.commit()
    with Session(engine) as s:
        # Проверяем СВОЮ заявку, а не весь список: в общем прогоне соседние тесты тоже
        # оставляют просроченных водителей, и жёсткое равенство ловило бы их, а не баг.
        assert _app_of(drv["id"]).id in doc_check.expire_overdue(s)
    assert _app_of(drv["id"]).docs_expired is True
    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).one()
        assert dp.online is False


def test_expired_docs_block_taxi_but_not_poputka(client, user_factory):
    """Ключевая справедливость: попутка не требует разрешения на такси и продолжает работать."""
    from app import taxi as taxi_mod
    drv = user_factory("Ждёт полис", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() - timedelta(days=1))
    with Session(engine) as s:
        doc_check.expire_overdue(s)
        assert taxi_mod.is_approved_taxi_driver(s, drv["id"]) is False
        assert taxi_mod.taxi_docs_expired(s, drv["id"]) is True
    # Попутка: публикация поездки проходит (гейта такси там нет).
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).isoformat(),
        "seats_total": 3, "price": 200,
    })
    assert r.status_code in (200, 201), r.text


def test_restore_renewed_returns_access(client, user_factory):
    drv = user_factory("Обновил", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() + timedelta(days=100), docs_expired=True)
    with Session(engine) as s:
        assert _app_of(drv["id"]).id in doc_check.restore_renewed(s)
    assert _app_of(drv["id"]).docs_expired is False


def test_warn_soon_fires_once_a_day(client, user_factory):
    """Напоминание не превращается в спам: дедуп по docs_warned_at."""
    drv = user_factory("Скоро", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() + timedelta(days=5))
    app_id = _app_of(drv["id"]).id
    with Session(engine) as s:
        assert app_id in doc_check.warn_soon(s)
    with Session(engine) as s:
        assert app_id not in doc_check.warn_soon(s)   # второй прогон в те же сутки молчит


def test_warn_soon_ignores_far_dates(client, user_factory):
    drv = user_factory("Далеко", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() + timedelta(days=365))
    with Session(engine) as s:
        assert _app_of(drv["id"]).id not in doc_check.warn_soon(s)


def test_remind_missing_only_for_drivers_without_any_date(client, user_factory):
    """Одобренным без дат — просьба дозаполнить; тем, у кого дата есть, не мешаем."""
    empty = user_factory("Без дат", role=UserRole.driver)
    filled = user_factory("С датой", role=UserRole.driver)
    _set_dates(filled["id"], osago_until=_today() + timedelta(days=200))
    with Session(engine) as s:
        touched = doc_check.remind_missing(s)
    assert _app_of(empty["id"]).id in touched
    assert _app_of(filled["id"]).id not in touched


def test_missing_dates_never_suspend_access(client, user_factory):
    """Мы сами не спросили даты при одобрении — наказывать за это человека нечестно."""
    drv = user_factory("Старый", role=UserRole.driver)
    with Session(engine) as s:
        assert _app_of(drv["id"]).id not in doc_check.expire_overdue(s)
    assert _app_of(drv["id"]).docs_expired is False


def test_run_once_survives_broken_step(client, user_factory, monkeypatch):
    """Одна упавшая задача не должна валить обход целиком."""
    monkeypatch.setattr(doc_check, "warn_soon", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("bad")))
    with Session(engine) as s:
        res = doc_check.run_once(s)
    assert res["warned"] == [] and "expired" in res   # упавший шаг пуст, остальные отработали


def test_dry_run_changes_nothing(client, user_factory):
    drv = user_factory("Сухой прогон", role=UserRole.driver)
    _set_dates(drv["id"], osago_until=_today() - timedelta(days=2))
    with Session(engine) as s:
        assert _app_of(drv["id"]).id in doc_check.expire_overdue(s, dry_run=True)
    assert _app_of(drv["id"]).docs_expired is False


# ============================== предрейсовое подтверждение ==============================

def test_pretrip_starts_unconfirmed(client, user_factory):
    drv = user_factory("Смена", role=UserRole.driver)
    r = client.get("/taxi/pretrip", headers=drv["auth"])
    assert r.status_code == 200
    assert r.json()["confirmed"] is False


def test_pretrip_requires_all_three_points(client, user_factory):
    """«Частично готов» — это не готов, и подписывать за человека мы не будем."""
    drv = user_factory("Половина", role=UserRole.driver)
    r = client.post("/taxi/pretrip", headers=drv["auth"], json={
        "health_ok": True, "car_ok": True, "no_alcohol": False,
    })
    assert r.status_code == 400
    with Session(engine) as s:
        assert s.exec(select(PreTripCheck).where(PreTripCheck.driver_id == drv["id"])).first() is None


def test_pretrip_confirm_and_idempotent(client, user_factory):
    drv = user_factory("Готов", role=UserRole.driver)
    body = {"health_ok": True, "car_ok": True, "no_alcohol": True, "note": "фары проверил"}
    assert client.post("/taxi/pretrip", headers=drv["auth"], json=body).json()["confirmed"] is True
    client.post("/taxi/pretrip", headers=drv["auth"], json=body)
    with Session(engine) as s:
        rows = s.exec(select(PreTripCheck).where(PreTripCheck.driver_id == drv["id"])).all()
    assert len(rows) == 1                  # UNIQUE(driver_id, day) — дублей нет
    assert rows[0].note == "фары проверил"


def test_pretrip_gate_blocks_presence_when_enabled(client, user_factory, monkeypatch):
    """Гейт включён и не подтверждено → на линию не пускаем, но текстом «что сделать»."""
    monkeypatch.setattr(settings, "pretrip_check_required", True)
    drv = user_factory("Не отметился", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv["id"], online=True))
        s.commit()
    r = client.post("/instant/presence", headers=drv["auth"], json={"lat": 52.59, "lng": 58.31})
    assert r.status_code == 403
    assert "подтверди" in r.json()["detail"]["ru"].lower()


def test_pretrip_gate_passes_after_confirm(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "pretrip_check_required", True)
    drv = user_factory("Отметился", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv["id"], online=True))
        s.commit()
    client.post("/taxi/pretrip", headers=drv["auth"], json={
        "health_ok": True, "car_ok": True, "no_alcohol": True,
    })
    r = client.post("/instant/presence", headers=drv["auth"], json={"lat": 52.59, "lng": 58.31})
    assert r.status_code == 200, r.text


def test_pretrip_gate_off_by_default_keeps_old_app_working(client, user_factory):
    """Пока приложение без экрана подтверждения — гейт выключен, иначе таксисты встали бы все."""
    assert settings.pretrip_check_required is False
    drv = user_factory("Старое приложение", role=UserRole.driver)
    with Session(engine) as s:
        s.add(DriverProfile(user_id=drv["id"], online=True))
        s.commit()
    r = client.post("/instant/presence", headers=drv["auth"], json={"lat": 52.59, "lng": 58.31})
    assert r.status_code == 200, r.text


def test_admin_pretrip_journal(client, user_factory):
    """След для разбора: кто и когда заявил готовность. Без координат и чужих телефонов."""
    drv = user_factory("В журнале", role=UserRole.driver)
    client.post("/taxi/pretrip", headers=drv["auth"], json={
        "health_ok": True, "car_ok": True, "no_alcohol": True, "note": "всё ок",
    })
    admin = user_factory("Админ", role=UserRole.admin)
    r = client.get("/admin/taxi/pretrip", headers=admin["auth"])
    assert r.status_code == 200
    items = r.json()["items"]
    assert any(i["driver_id"] == drv["id"] and i["note"] == "всё ок" for i in items)


def test_admin_pretrip_journal_forbidden_for_driver(client, user_factory):
    drv = user_factory("Любопытный", role=UserRole.driver)
    assert client.get("/admin/taxi/pretrip", headers=drv["auth"]).status_code == 403


def test_pretrip_is_confirmed_helper(client, user_factory):
    drv = user_factory("Хелпер", role=UserRole.driver)
    with Session(engine) as s:
        assert pretrip.is_confirmed(s, drv["id"]) is False
        pretrip.confirm(s, drv["id"], True, True, True)
        assert pretrip.is_confirmed(s, drv["id"]) is True
