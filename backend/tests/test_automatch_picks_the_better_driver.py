"""Волна 195: авто-подбор для человека без смартфона выбирает по ВОДИТЕЛЬСКОМУ баллу.

Соседняя дверь к волне 194. Обычную заявку пассажир закрывает сам — смотрит отклики
и выбирает. Заявку, созданную по телефону за пожилого человека, выбрать некому: водителя
за него назначает система (`automatch._best_response`, «выше рейтинг → ниже цена»).

Рейтинг там брался ОБЩИЙ — вместе с оценками, которые водитель получил, сам сидя
пассажиром. То есть на выбор машины для бабушки влияло то, как человек ведёт себя
в чужом салоне, а не то, как он водит.
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


@pytest.fixture
def matching_on(monkeypatch):
    monkeypatch.setattr(settings, "automatch_enabled", True, raising=False)
    monkeypatch.setattr(settings, "automatch_grace_sec", 0, raising=False)
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


def _rate_passenger(s: Session, *, driver_id: int, passenger_id: int, stars: int) -> None:
    """Обратная сторона: за рулём чужой человек, оценку получает наш ПАССАЖИР."""
    _ensure_profile(s, driver_id)
    o = InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                     from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                     status=S.done, price_estimate=200, price_final=200, done_at=utcnow())
    s.add(o)
    s.commit()
    s.refresh(o)
    s.add(Rating(order_id=o.id, rater_id=driver_id, ratee_id=passenger_id, stars=stars))
    s.commit()


def _response(s: Session, request_id: int, driver_id: int, price: int = 500) -> RequestResponse:
    r = RequestResponse(request_id=request_id, driver_id=driver_id, price=price,
                        status="offered", created_at=utcnow())
    s.add(r)
    s.commit()
    s.refresh(r)
    return r


def test_automatch_ranks_by_driving_not_by_all_roles(client, user_factory):
    """Бабушка без смартфона, двое откликнулись, цена у обоих одна — решает рейтинг.

    Ильдар водит отлично (три пятёрки за рулём), но сам ездил пассажиром и получил там
    единицу. Рустем водит средне (три четвёрки), зато пассажиром вежлив — пятёрка.
    По общему баллу Рустем впереди (4.25 против 4.0), по водительскому — Ильдар (5.0
    против 4.0). Бабушку должен везти тот, кто лучше ВОДИТ.
    """
    granny = user_factory("Granny")
    ildar = user_factory("Ildar", role=UserRole.driver)
    rustem = user_factory("Rustem", role=UserRole.driver)
    outsider = user_factory("Outsider", role=UserRole.driver)

    with Session(engine) as s:
        for i in range(3):                       # Ильдар за рулём — пятёрки
            _rate(s, driver_id=ildar["id"], passenger_id=user_factory(f"IPax{i}")["id"], stars=5)
        for i in range(3):                       # Рустем за рулём — четвёрки
            _rate(s, driver_id=rustem["id"], passenger_id=user_factory(f"RPax{i}")["id"], stars=4)
        # А теперь оба сами ехали пассажирами у чужого водителя.
        _rate_passenger(s, driver_id=outsider["id"], passenger_id=ildar["id"], stars=1)
        _rate_passenger(s, driver_id=outsider["id"], passenger_id=rustem["id"], stars=5)

        req = RideRequest(passenger_id=granny["id"], from_city="Сибай", to_city="Уфа",
                          depart_at=utcnow(), seats=1, status="active")
        s.add(req)
        s.commit()
        s.refresh(req)
        offers = [_response(s, req.id, ildar["id"]), _response(s, req.id, rustem["id"])]

        best = automatch._best_response(s, offers)

    assert best.driver_id == ildar["id"], (
        "бабушку посадили к водителю, который хуже водит, — победил его балл пассажира"
    )


def test_automatch_still_prefers_the_cheaper_at_equal_driving(client, user_factory):
    """Защита не сломана: при равном вождении по-прежнему решает цена."""
    granny = user_factory("Granny2")
    a = user_factory("EqualA", role=UserRole.driver)
    b = user_factory("EqualB", role=UserRole.driver)

    with Session(engine) as s:
        _rate(s, driver_id=a["id"], passenger_id=user_factory("EqPaxA")["id"], stars=5)
        _rate(s, driver_id=b["id"], passenger_id=user_factory("EqPaxB")["id"], stars=5)
        req = RideRequest(passenger_id=granny["id"], from_city="Сибай", to_city="Уфа",
                          depart_at=utcnow(), seats=1, status="active")
        s.add(req)
        s.commit()
        s.refresh(req)
        offers = [_response(s, req.id, a["id"], price=700),
                  _response(s, req.id, b["id"], price=500)]

        best = automatch._best_response(s, offers)

    assert best.driver_id == b["id"], "при равном вождении дешевле — лучше"


def test_automatch_driver_without_ratings_is_not_pushed_ahead(client, user_factory):
    """Новичок без единой оценки не должен обгонять водителя с пятёрками.
    Нет данных — ноль, как и было у общего балла: подбор не награждает пустоту."""
    granny = user_factory("Granny3")
    good = user_factory("GoodDrv", role=UserRole.driver)
    fresh = user_factory("FreshDrv", role=UserRole.driver)

    with Session(engine) as s:
        _rate(s, driver_id=good["id"], passenger_id=user_factory("GoodPax")["id"], stars=5)
        req = RideRequest(passenger_id=granny["id"], from_city="Сибай", to_city="Уфа",
                          depart_at=utcnow(), seats=1, status="active")
        s.add(req)
        s.commit()
        s.refresh(req)
        offers = [_response(s, req.id, fresh["id"]), _response(s, req.id, good["id"])]

        best = automatch._best_response(s, offers)

    assert best.driver_id == good["id"]
