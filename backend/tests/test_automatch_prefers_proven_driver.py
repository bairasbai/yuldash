"""ТЗ-1 (26.09.2026): авто-подбор для человека без смартфона выбирает ПРОВЕРЕННОГО водителя.

Заявку, созданную по телефону за пожилого человека, закрывает система сама
(`automatch._best_response`). До этого она брала самый высокий СРЕДНИЙ балл — и новичок
с единственной пятёркой (5.0) обходил водителя с двумя десятками поездок и 4.9. Одна оценка
ничего не говорит о том, как человек водит; для бабушки надёжнее тот, чей балл подтверждён.

Теперь сравнивается «надёжный балл» (байесовское среднее): пока оценок мало, балл
подтянут к априорному `automatch_rating_prior`, и чем больше оценок, тем ближе он к
настоящему среднему. Водитель без оценок по-прежнему идёт после всех, у кого они есть.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app import automatch
from app.config import settings
from app.db import engine
from app.models import (
    DriverProfile, InstantOrder, InstantOrderStatus as S, Rating, RequestResponse,
    RideRequest, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture(autouse=True)
def default_prior(monkeypatch):
    """Пороги как в ТЗ: m = 4.5, C = 5 — тесты не зависят от чужих настроек окружения."""
    monkeypatch.setattr(settings, "automatch_rating_prior", 4.5, raising=False)
    monkeypatch.setattr(settings, "automatch_rating_prior_weight", 5, raising=False)
    yield


def _ensure_profile(s: Session, user_id: int) -> None:
    if s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first() is None:
        s.add(DriverProfile(user_id=user_id))
        s.commit()


def _rate(s: Session, *, driver_id: int, passenger_id: int, stars: int) -> None:
    """Завершённый заказ такси + оценка за него. Кто за рулём — тот и оценён."""
    _ensure_profile(s, driver_id)
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=200, price_final=200, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    s.add(Rating(order_id=o.id, rater_id=passenger_id, ratee_id=driver_id, stars=stars))
    s.commit()


def _rate_many(s: Session, user_factory, *, driver_id: int, tag: str, stars: list[int]) -> None:
    """Каждую оценку ставит отдельный пассажир: кап «одна пара — ограниченный вклад»
    не должен съесть историю водителя."""
    for i, st in enumerate(stars):
        _rate(s, driver_id=driver_id, passenger_id=user_factory(f"{tag}Pax{i}")["id"], stars=st)


def _request(s: Session, passenger_id: int) -> RideRequest:
    req = RideRequest(passenger_id=passenger_id, from_city="Сибай", to_city="Уфа",
                      depart_at=utcnow(), seats=1, status="active")
    s.add(req)
    s.commit()
    s.refresh(req)
    return req


def _response(s: Session, request_id: int, driver_id: int, price: int = 500) -> RequestResponse:
    r = RequestResponse(request_id=request_id, driver_id=driver_id, price=price,
                        status="offered", created_at=utcnow())
    s.add(r)
    s.commit()
    s.refresh(r)
    return r


def test_novice_with_one_five_loses_to_a_proven_driver(client, user_factory):
    """Новичок: одна пятёрка (5.0 → надёжный 4.58). Опытный: 20 оценок, среднее 4.9
    (→ 4.82). Раньше выигрывал новичок — у него «выше рейтинг». Бабушку везёт опытный."""
    granny = user_factory("PGranny1")
    novice = user_factory("PNovice", role=UserRole.driver)
    veteran = user_factory("PVeteran", role=UserRole.driver)

    with Session(engine) as s:
        _rate_many(s, user_factory, driver_id=novice["id"], tag="Nov", stars=[5])
        _rate_many(s, user_factory, driver_id=veteran["id"], tag="Vet", stars=[5] * 18 + [4] * 2)
        req = _request(s, granny["id"])
        offers = [_response(s, req.id, novice["id"]), _response(s, req.id, veteran["id"])]

        best = automatch._best_response(s, offers)

    assert best.driver_id == veteran["id"], (
        "бабушку посадили к новичку с одной пятёркой, а не к водителю с подтверждённым 4.9"
    )


def test_enough_perfect_ratings_beat_a_longer_but_weaker_record(client, user_factory):
    """Надёжный балл — не «стаж ради стажа». 10 пятёрок (→ 4.83) обходят
    20 оценок со средним 4.8 (→ 4.74): хорошая подтверждённая работа побеждает."""
    granny = user_factory("PGranny2")
    sharp = user_factory("PSharp", role=UserRole.driver)
    steady = user_factory("PSteady", role=UserRole.driver)

    with Session(engine) as s:
        _rate_many(s, user_factory, driver_id=sharp["id"], tag="Shp", stars=[5] * 10)
        _rate_many(s, user_factory, driver_id=steady["id"], tag="Std", stars=[5] * 16 + [4] * 4)
        req = _request(s, granny["id"])
        offers = [_response(s, req.id, steady["id"]), _response(s, req.id, sharp["id"])]

        best = automatch._best_response(s, offers)

    assert best.driver_id == sharp["id"]


def test_driver_without_ratings_stays_behind_a_low_but_real_rating(client, user_factory):
    """Без оценок надёжный балл был бы ровно априорным (4.5) — выше, чем у водителя
    с одной четвёркой (→ 4.42). Но «нет данных» не награждаем: водитель с настоящими
    оценками всегда идёт раньше водителя без них."""
    granny = user_factory("PGranny3")
    rated = user_factory("PRated", role=UserRole.driver)
    fresh = user_factory("PFresh", role=UserRole.driver)

    with Session(engine) as s:
        _rate_many(s, user_factory, driver_id=rated["id"], tag="Rtd", stars=[4])
        req = _request(s, granny["id"])
        offers = [_response(s, req.id, fresh["id"]), _response(s, req.id, rated["id"])]

        best = automatch._best_response(s, offers)

    assert best.driver_id == rated["id"]


def test_equal_proven_rating_still_goes_to_the_cheaper(client, user_factory):
    """Одинаковая история (по 3 пятёрки) — решает цена на столе, как и раньше."""
    granny = user_factory("PGranny4")
    a = user_factory("PEqA", role=UserRole.driver)
    b = user_factory("PEqB", role=UserRole.driver)

    with Session(engine) as s:
        _rate_many(s, user_factory, driver_id=a["id"], tag="EqA", stars=[5, 5, 5])
        _rate_many(s, user_factory, driver_id=b["id"], tag="EqB", stars=[5, 5, 5])
        req = _request(s, granny["id"])
        offers = [_response(s, req.id, a["id"], price=700), _response(s, req.id, b["id"], price=500)]

        best = automatch._best_response(s, offers)

    assert best.driver_id == b["id"], "при равном надёжном балле дешевле — лучше"


def test_prior_weight_zero_restores_the_plain_average(client, user_factory, monkeypatch):
    """Выключатель: `automatch_rating_prior_weight = 0` → обычное среднее, как до ТЗ-1.
    Тогда новичок с 5.0 снова обходит опытного с 4.9 — поведение прежнее."""
    monkeypatch.setattr(settings, "automatch_rating_prior_weight", 0, raising=False)
    granny = user_factory("PGranny5")
    novice = user_factory("PNovice0", role=UserRole.driver)
    veteran = user_factory("PVeteran0", role=UserRole.driver)

    with Session(engine) as s:
        _rate_many(s, user_factory, driver_id=novice["id"], tag="Nv0", stars=[5])
        _rate_many(s, user_factory, driver_id=veteran["id"], tag="Vt0", stars=[5] * 18 + [4] * 2)
        req = _request(s, granny["id"])
        offers = [_response(s, req.id, veteran["id"]), _response(s, req.id, novice["id"])]

        best = automatch._best_response(s, offers)

    assert best.driver_id == novice["id"]
