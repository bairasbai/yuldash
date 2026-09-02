# -*- coding: utf-8 -*-
"""Водитель всегда знает, что с ним не так (аудит сценариев 2026-08-30, наша часть).

Шесть мест, где сервер знал причину, а человек её не получал.

1. ОСГОП — страховка ответственности перевозчика, обязательная с 01.09.2024 всем, включая
   самозанятых. Поле в базе было с самого начала, а в контроле сроков его не было: она нигде
   не показывалась и никогда не истекала. Это единственная находка аудита про закон и деньги
   (штраф ИП 25 000 ₽), а не про удобство.

2–4. Отдых, недельный лимит и пауза по качеству отвечали ОДНОЙ СКЛЕЕННОЙ СТРОКОЙ «RU · BA».
   Клиент такую строку показать не умеет и подменяет её общим «нет доступа» — башкироязычный
   водитель видел «Был эшкә рөхсәт юҡ» вместо объяснения.

5. Недельный потолок был невидим: сводка знала только про день, кабинет писал «смена
   свободна», а линия была закрыта неделей.

6. «Ждём заказ» молчал о пяти причинах блокировки — человек сидел и смотрел на надпись,
   которая обещала то, чего не будет.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import quality, workday
from app.config import settings
from app.db import engine
from app.models import DriverProfile, TaxiApplication, TaxiWorkDay, UserRole
from app.timeutil import local_date, utcnow


def _сессия() -> Session:
    return Session(engine, expire_on_commit=False)


def _водитель(user_factory, name: str):
    u = user_factory(name, role=UserRole.driver)
    with _сессия() as s:
        if s.exec(select(DriverProfile).where(DriverProfile.user_id == u["id"])).first() is None:
            s.add(DriverProfile(user_id=u["id"], online=False))
            s.commit()
    return u


def _детали(ответ) -> dict:
    """Тело отказа. Ждём словарь с обоими языками, а не склеенную строку."""
    detail = ответ.json()["detail"]
    assert isinstance(detail, dict), f"отказ пришёл строкой, башкирский потеряется: {detail!r}"
    return detail


# ==================== 1. ОСГОП ====================
def test_osgop_is_watched_like_every_other_document(client, user_factory):
    """Срок ОСГОП живёт в общем контроле: его видно, его можно продлить, он истекает."""
    from app import doc_check

    assert "osgop_until" in doc_check._DOC_NAMES, "ОСГОП снова выпал из контроля сроков"

    u = user_factory("СОСГОПом", taxi_approved=False)
    срок = (local_date(utcnow()) + timedelta(days=200)).isoformat()
    r = client.post("/taxi/apply", headers=u["auth"], json={
        "inn": "123456789012", "permit_number": "Т-900",
        "birth_date": "1990-01-01", "license_since_year": 2010,
        "osago_until": срок, "permit_until": срок, "inspection_until": срок,
        "osgop_until": срок,
    })
    assert r.status_code == 200, r.text
    assert r.json()["osgop_until"] == срок, "ОСГОП не доехал до экрана"
    assert "osgop_until" not in r.json()["docs_missing"]


def test_an_expired_osgop_takes_the_driver_off_the_line(client, user_factory):
    """Просроченный ОСГОП снимает допуск так же, как просроченное ОСАГО.

    Работать без него — это штраф ИП 25 000 ₽ и пассажир без выплаты при аварии.
    """
    from app import doc_check
    from app import taxi as taxi_mod

    d = _водитель(user_factory, "ОСГОПИстёк")
    with _сессия() as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == d["id"])).first()
        app.osgop_until = local_date(utcnow()) - timedelta(days=1)
        s.add(app)
        s.commit()
        assert "osgop_until" in doc_check.overdue_docs(app)
        doc_check.expire_overdue(s)
        assert taxi_mod.is_approved_taxi_driver(s, d["id"]) is False


def test_osgop_can_be_renewed_without_reapplying(client, user_factory):
    """Продлить ОСГОП можно как ОСАГО — не проходя модерацию заново."""
    d = _водитель(user_factory, "ПродлилОСГОП")
    новый = (local_date(utcnow()) + timedelta(days=365)).isoformat()
    r = client.post("/taxi/documents", headers=d["auth"], json={"osgop_until": новый})
    assert r.status_code == 200, r.text
    assert r.json()["osgop_until"] == новый


# ==================== 2. Отказы на двух языках ====================
def test_the_rest_refusal_speaks_bashkir(client, user_factory):
    """Отдых объясняется на обоих языках, а не общим «нет доступа»."""
    d = _водитель(user_factory, "УсталВодитель")
    with _сессия() as s:
        s.add(TaxiWorkDay(driver_id=d["id"], day=workday.local_day(),
                          seconds_online=settings.taxi_shift_limit_hours * 3600 + 60,
                          limit_reached_at=utcnow()))
        s.commit()
    with _сессия() as s:
        with pytest.raises(Exception) as поймали:
            workday.guard_rested(s, d["id"])
    detail = поймали.value.detail
    assert isinstance(detail, dict) and detail["ru"] and detail["ba"]
    assert "отдохни" in detail["ru"].lower()


def test_the_quality_pause_refusal_speaks_bashkir(client, user_factory):
    """Пауза по качеству — то же самое: два языка, а не склейка через точку."""
    d = _водитель(user_factory, "НаПаузе")
    with _сессия() as s:
        quality.pause_taxi(s, d["id"], hours=24, reason=quality.PAUSE_REASON_ADMIN)
        with pytest.raises(Exception) as поймали:
            quality.guard_taxi_quality(s, d["id"])
    detail = поймали.value.detail
    assert isinstance(detail, dict) and detail["ru"] and detail["ba"]
    assert "опутка" in detail["ru"], "не сказали, что попутка работает"


# ==================== 3. Недельный потолок видно ====================
def test_the_weekly_limit_is_visible_in_the_summary(client, user_factory):
    """Сводка знает про неделю, а не только про день.

    Раньше кабинет писал «смена свободна», когда линию закрыл недельный потолок, и человек
    не понимал, почему не идут заказы. Срок разблокировки честно не обещаем: окно скользящее.
    """
    d = _водитель(user_factory, "НедельныйЛимит")
    часов = int(settings.taxi_week_limit_hours)
    with _сессия() as s:
        for i in range(1, 6):
            s.add(TaxiWorkDay(driver_id=d["id"],
                              day=workday.local_day() - timedelta(days=i),
                              seconds_online=(часов * 3600) // 4))
        s.commit()
        сводка = workday.summary(s, d["id"])
    assert "week_blocked" in сводка and "week_seconds" in сводка
    assert сводка["week_limit_hours"] == часов
    assert сводка["week_seconds"] > 0


# ==================== 4. «Ждём заказ» больше не молчит ====================
def test_an_empty_offer_says_why(client, user_factory):
    """Пустой оффер приходит С ПРИЧИНОЙ — иначе экран обещает заказ, которого не будет."""
    d = _водитель(user_factory, "БезОффера")
    with _сессия() as s:
        app = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == d["id"])).first()
        app.docs_expired = True
        s.add(app)
        s.commit()
    r = client.get("/instant/driver/offer", headers=d["auth"])
    assert r.status_code == 200, r.text
    тело = r.json()
    assert тело["offer"] is None
    assert тело.get("blocked") == "not_approved", тело


def test_a_healthy_driver_gets_no_blocked_reason(client, user_factory):
    """У здорового водителя причины нет — «Ждём заказ» снова означает именно ожидание."""
    d = _водитель(user_factory, "ЗдоровыйБезОффера")
    r = client.get("/instant/driver/offer", headers=d["auth"])
    assert r.status_code == 200, r.text
    assert r.json().get("blocked") is None


# ==================== 5. Отказ в брони объясняет себя ====================
def test_a_cancelled_booking_says_why_and_where_to_go(client):
    """Пассажиру уходит причина отмены, а не голый маршрут.

    Подписи держим отдельным модулем рядом со списком кодов: код без подписи просто ничего
    не добавит в текст, а не покажет человеку служебное слово.
    """
    from app.cancel_reason_text import cancel_reason_text
    from app.safety_logic import CANCEL_REASONS

    ru, ba = cancel_reason_text("car_broken")
    assert (ru, ba) == ("", ""), "неизвестный код не должен выдумывать текст"

    ru, ba = cancel_reason_text("plans_changed")
    assert ru and ba and ru != ba

    без_подписи = [c for c in CANCEL_REASONS if cancel_reason_text(c) == ("", "") and c != "other"]
    assert без_подписи == [], f"эти причины отмены останутся без объяснения: {без_подписи}"
