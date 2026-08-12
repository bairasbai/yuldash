"""Авто-подбор не сажает за руль того, кому ездить запрещено.

История. Бабушка из Сибая не пользуется смартфоном — заявку за неё создали по звонку.
Выбрать водителя в приложении ей нечем, поэтому спустя пару минут это делает система:
берёт лучший отклик и оформляет поездку. Внучка, оформлявшая заявку, узнаёт о выборе
постфактум — отменить «не того» водителя в момент подбора некому.

Что было не так. Проверки «водитель на паузе после разбора» и «эти двое друг друга
заблокировали» стояли только на кнопке в приложении. У приёма отклика дверей три —
приложение, авто-подбор и кнопка ✅ у админа в Telegram, — и две последние шли мимо гейта.
Получалось наоборот: пассажир с приложением от отстранённого водителя защищён, а бабушка,
которая даже не увидит подбор, — нет (аудит 2026-08-12, волна 46).

Здесь проверено человеческое: отстранённого и заблокированного система за руль не сажает,
но и без машины человека не оставляет — берёт следующего честного водителя.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import automatch
from app.config import settings
from app.db import engine
from app.models import (Block, Booking, RequestResponse, Ride, RideRequest, SafetyProfile,
                        UserRole)
from app.timeutil import utcnow


@pytest.fixture
def matching_on(monkeypatch):
    """Подбор включён, паузы «дать откликнуться другим» нет — иначе тест ждал бы 2 минуты."""
    monkeypatch.setattr(settings, "automatch_enabled", True, raising=False)
    monkeypatch.setattr(settings, "automatch_grace_sec", 0, raising=False)
    yield


def _request_by_phone(session: Session, passenger_id: int) -> RideRequest:
    """Заявка человека без приложения — та самая, которую закрывает система, а не он сам."""
    req = RideRequest(passenger_id=passenger_id, from_city="Сибай", to_city="Уфа",
                      seats=1, status="active")
    session.add(req)
    session.commit()
    session.refresh(req)
    return req


def _offer(session: Session, req_id: int, driver_id: int, price: int = 500) -> RequestResponse:
    """Отклик водителя, уже «созревший» — пауза на раздумье прошла."""
    resp = RequestResponse(request_id=req_id, driver_id=driver_id, price=price,
                           status="offered", created_at=utcnow() - timedelta(minutes=10))
    session.add(resp)
    session.commit()
    session.refresh(resp)
    return resp


def _suspend(session: Session, user_id: int, days: int = 7) -> None:
    """Пауза лестницы «Справедливости»: аккаунт отстранён до разбора."""
    session.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=days)))
    session.commit()


def _matched_ids(matched: list[tuple[int, int]]) -> set[int]:
    """Какие заявки подобрал проход. Смотрим ТОЛЬКО на свою: проход идёт по всей базе, и
    заявки соседних тестов в общей тестовой БД попадают в тот же список (урок 2026-08-06)."""
    return {req_id for req_id, _ in matched}


def test_отстранённого_водителя_система_за_руль_не_сажает(client, user_factory, matching_on):
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req = _request_by_phone(s, passenger["id"])
        _offer(s, req.id, driver["id"])
        _suspend(s, driver["id"])

        assert req.id not in _matched_ids(automatch.automatch_once(s))
        assert s.exec(select(Booking).where(Booking.passenger_id == passenger["id"])).all() == []
        # Заявка осталась открытой: человек ждёт машину, а не остаётся ни с чем.
        assert s.get(RideRequest, req.id).status == "active"


def test_заблокированного_водителя_система_не_подставляет(client, user_factory, matching_on):
    """Заблокировать могли ровно затем, чтобы больше не встречаться. Система не сводит их снова."""
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req = _request_by_phone(s, passenger["id"])
        _offer(s, req.id, driver["id"])
        s.add(Block(user_id=passenger["id"], blocked_user_id=driver["id"]))
        s.commit()

        assert req.id not in _matched_ids(automatch.automatch_once(s))
        assert s.exec(select(Booking).where(Booking.passenger_id == passenger["id"])).all() == []


def test_без_машины_человека_не_оставляем_берём_честного(client, user_factory, matching_on):
    """Отстранённый предложил дешевле — и всё равно едет честный. Один негодный отклик
    не должен уводить всю заявку в отказ: пассажир без приложения ждёт машину, а не разбор."""
    passenger = user_factory(role=UserRole.passenger)
    banned = user_factory(role=UserRole.driver)
    honest = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req = _request_by_phone(s, passenger["id"])
        _offer(s, req.id, banned["id"], price=300)     # дешевле → в обычном сравнении победил бы
        good = _offer(s, req.id, honest["id"], price=900)
        _suspend(s, banned["id"])

        assert (req.id, good.id) in automatch.automatch_once(s)
        booking = s.exec(select(Booking).where(Booking.passenger_id == passenger["id"])).first()
        assert booking is not None
        assert s.get(Ride, booking.ride_id).driver_id == honest["id"]


def test_кнопка_админа_в_телеграме_тоже_под_правилом(client, user_factory, matching_on):
    """Третья дверь: админ жмёт ✅ прямо в Telegram, минуя приложение. Правило одно на всех."""
    from fastapi import HTTPException

    from app.routers.requests import accept_request_response

    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req = _request_by_phone(s, passenger["id"])
        resp = _offer(s, req.id, driver["id"])
        _suspend(s, driver["id"])

        with pytest.raises(HTTPException) as e:
            accept_request_response(s, resp)
        assert e.value.status_code == 409
        assert s.get(RideRequest, req.id).status == "active"


def test_обычный_подбор_работает_как_прежде(client, user_factory, matching_on):
    """Страховка от перестраховки: без паузы и блокировки система подбирает как раньше."""
    passenger = user_factory(role=UserRole.passenger)
    driver = user_factory(role=UserRole.driver)
    with Session(engine) as s:
        req = _request_by_phone(s, passenger["id"])
        resp = _offer(s, req.id, driver["id"])

        assert (req.id, resp.id) in automatch.automatch_once(s)
        assert s.get(RideRequest, req.id).status == "matched"
