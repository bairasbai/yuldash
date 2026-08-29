"""Волна 194: водителя судят по его собственной работе, а не по всем его ролям сразу.

Соседняя дверь к волне 186 (там то же самое чинили у курьера). Оценка после поездки
взаимная: пассажир оценивает водителя, водитель — пассажира. Обе стороны шли в один
`apply_rating`, а он переписывал `DriverProfile.rating` по ОБЩЕМУ агрегату всех оценок
человека, кем бы он их ни получил. По этому числу matcher (`instant_service._score`)
решает, кому предложить заказ, — то есть это прямо деньги водителя.

Здесь проверяем обе двери (такси и попутка), обе стороны (наказание за свою работу
остаётся) и щит рейтинга (пересчёт после снятия оценки админом).
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app import quality
from app.config import settings
from app.db import engine
from app.models import (
    Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus as S, Rating,
    Ride, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def pushes(monkeypatch):
    """Оба канала «человеку сказали»: голый пуш-совет и запись в Центр уведомлений."""
    sent: list[tuple[int, str]] = []
    monkeypatch.setattr("app.services.send_push",
                        lambda session, uid, title, body, data=None: sent.append((uid, title)))
    monkeypatch.setattr(
        quality, "push_notification",
        lambda session, uid, ntype, title_ru, title_ba, body_ru, body_ba, **kw:
            sent.append((uid, title_ru)))
    return sent


def _ensure_profile(user_id: int) -> None:
    with Session(engine) as s:
        if s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first() is None:
            s.add(DriverProfile(user_id=user_id))
            s.commit()


def _done_order(driver_id: int, passenger_id: int, price: int = 200) -> int:
    _ensure_profile(driver_id)
    with Session(engine) as s:
        o = InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id,
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            status=S.done, price_estimate=price, price_final=price, done_at=utcnow(),
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _done_booking(driver_id: int, passenger_id: int) -> int:
    """Завершённая бронь попутки напрямую в БД (вторая дверь к тому же расчёту)."""
    _ensure_profile(driver_id)
    with Session(engine) as s:
        ride = Ride(driver_id=driver_id, from_city="Баймак", to_city="Сибай", seats=4,
                    price=300, depart_at=utcnow() - timedelta(days=1))
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=passenger_id, seats=1, price=300,
                    status=BookingStatus.done)
        s.add(b)
        s.commit()
        s.refresh(b)
        return b.id


def _profile(user_id: int) -> DriverProfile:
    with Session(engine) as s:
        return s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()


def _penalised(prof: DriverProfile) -> bool:
    """Штрафует ли matcher этого водителя — то есть станет ли у него меньше заказов."""
    uid = prof.user_id
    clean = isv._score({uid: DriverProfile(user_id=uid, rating=5.0)}, uid, 1.0)
    return isv._score({uid: prof}, uid, 1.0) < clean


def _advice(pushes, uid: int) -> list:
    return [t for u, t in pushes if u == uid and t == "Совет от Юлдаша"]


# ==================== 1. Такси: оценки, полученные как пассажиром ====================
def test_taxi_driver_not_punished_for_passenger_side_ratings(client, user_factory, pushes):
    """Марат работает таксистом. Как ПАССАЖИР он получил три единицы от чужих водителей.
    Работу это трогать не должно: как водитель он не возил никого из них."""
    marat = user_factory("Marat", role=UserRole.driver)
    _ensure_profile(marat["id"])                       # он на линии как таксист

    for i in range(3):                                 # три поездки, где Марат — ПАССАЖИР
        drv = user_factory(f"OtherDrv{i}", role=UserRole.driver)
        oid = _done_order(drv["id"], marat["id"])
        r = client.post(f"/instant/orders/{oid}/rate", headers=drv["auth"], json={"stars": 1})
        assert r.status_code == 200, r.text

    prof = _profile(marat["id"])
    assert prof.rating >= settings.matcher_low_rating, (
        f"рейтинг ВОДИТЕЛЯ просел до {prof.rating} из-за оценок, полученных им как пассажиром"
    )
    assert not _penalised(prof), "штраф в matcher: заказов такси станет меньше"
    assert _advice(pushes, marat["id"]) == [], "письмо «рейтинг просел» за чужую роль"


# ==================== 2. Попутка: та же дверь, тот же расчёт ====================
def test_pooling_driver_not_punished_for_passenger_side_ratings(client, user_factory, pushes):
    """Ильдар возит попутчиков. Три раза он сам ехал пассажиром и получил по единице.
    Соседний сервис — та же ошибка ловилась бы отдельно, поэтому проверяем обе двери."""
    ildar = user_factory("Ildar", role=UserRole.driver)
    _ensure_profile(ildar["id"])

    for i in range(3):                                 # три поездки, где Ильдар — ПАССАЖИР
        drv = user_factory(f"PoolDrv{i}", role=UserRole.driver)
        bid = _done_booking(drv["id"], ildar["id"])
        r = client.post(f"/bookings/{bid}/rate", headers=drv["auth"], json={"stars": 1})
        assert r.status_code == 200, r.text

    prof = _profile(ildar["id"])
    assert prof.rating >= settings.matcher_low_rating, f"рейтинг просел до {prof.rating}"
    assert not _penalised(prof)
    assert _advice(pushes, ildar["id"]) == []


# ==================== 3. Защита не сломана: за своё вождение отвечает ====================
def test_driver_still_punished_for_his_own_driving(client, user_factory, pushes):
    """Обратная сторона: плохие оценки ЗА РУЛЁМ должны бить по рейтингу как раньше."""
    drv = user_factory("BadDrv", role=UserRole.driver)
    for i in range(3):
        pax = user_factory(f"BadPax{i}")
        oid = _done_order(drv["id"], pax["id"])
        assert client.post(f"/instant/orders/{oid}/rate",
                           headers=pax["auth"], json={"stars": 1}).status_code == 200

    prof = _profile(drv["id"])
    assert prof.rating == pytest.approx(1.0), "оценки за свою работу обязаны считаться"
    assert _penalised(prof), "штраф в matcher должен остаться"
    assert len(_advice(pushes, drv["id"])) == 1


def test_pooling_and_taxi_ratings_count_together(client, user_factory):
    """Один водитель, два сервиса: 5★ за такси и 1★ за попутку дают среднее 3.0.
    Водительская история одна, и кап на пару должен видеть её целиком."""
    drv = user_factory("MixDrv", role=UserRole.driver)
    pax1, pax2 = user_factory("MixPax1"), user_factory("MixPax2")
    oid = _done_order(drv["id"], pax1["id"])
    bid = _done_booking(drv["id"], pax2["id"])
    assert client.post(f"/instant/orders/{oid}/rate",
                       headers=pax1["auth"], json={"stars": 5}).status_code == 200
    assert client.post(f"/bookings/{bid}/rate",
                       headers=pax2["auth"], json={"stars": 1}).status_code == 200
    assert _profile(drv["id"]).rating == pytest.approx(3.0)


# ==================== 4. Наказание не возобновляется от чужого действия ====================
def test_passenger_side_rating_does_not_restart_the_advice(client, user_factory, pushes):
    """Водитель уже получил совет за свои оценки и отсидел неделю дедупа. Оценка,
    полученная им как ПАССАЖИРОМ, не должна заводить лестницу заново."""
    drv = user_factory("DedupDrv", role=UserRole.driver)
    pax = user_factory("DedupPax")
    oid = _done_order(drv["id"], pax["id"])
    assert client.post(f"/instant/orders/{oid}/rate",
                       headers=pax["auth"], json={"stars": 2}).status_code == 200
    assert len(_advice(pushes, drv["id"])) == 1

    with Session(engine) as s:                         # неделя дедупа прошла
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        p.low_rating_advice_at = utcnow() - timedelta(days=settings.quality_advice_interval_days + 1)
        s.add(p)
        s.commit()

    other = user_factory("DedupOtherDrv", role=UserRole.driver)
    oid2 = _done_order(other["id"], drv["id"])         # теперь ОН пассажир
    assert client.post(f"/instant/orders/{oid2}/rate",
                       headers=other["auth"], json={"stars": 1}).status_code == 200
    assert len(_advice(pushes, drv["id"])) == 1, "лестницу завёл посторонний человек"


def test_pooling_passenger_side_rating_does_not_restart_the_advice(client, user_factory, pushes):
    """То же самое, но чужую оценку человек получает в ПОПУТКЕ. Двери две — гейт роли
    должен стоять на обеих (мутационный проход поймал: на попутке его не было видно)."""
    drv = user_factory("PoolDedupDrv", role=UserRole.driver)
    pax = user_factory("PoolDedupPax")
    bid = _done_booking(drv["id"], pax["id"])
    assert client.post(f"/bookings/{bid}/rate",
                       headers=pax["auth"], json={"stars": 2}).status_code == 200
    assert len(_advice(pushes, drv["id"])) == 1

    with Session(engine) as s:                         # неделя дедупа прошла
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        p.low_rating_advice_at = utcnow() - timedelta(days=settings.quality_advice_interval_days + 1)
        s.add(p)
        s.commit()

    other = user_factory("PoolDedupOtherDrv", role=UserRole.driver)
    bid2 = _done_booking(other["id"], drv["id"])       # теперь ОН пассажир попутки
    assert client.post(f"/bookings/{bid2}/rate",
                       headers=other["auth"], json={"stars": 1}).status_code == 200
    assert len(_advice(pushes, drv["id"])) == 1, "лестницу завёл посторонний человек"


def test_profile_rating_ignores_passenger_side_even_when_driver_side_exists(client, user_factory):
    """Главный случай: человек и возит, и ездит. За рулём его хвалят (5★), пассажиром
    ругают (1★). В рабочий балл обязаны попасть только пятёрки — общий балл дал бы 3.0."""
    drv = user_factory("BothRolesDrv", role=UserRole.driver)
    for i in range(3):                                 # как ПАССАЖИР — три единицы
        other = user_factory(f"BothOtherDrv{i}", role=UserRole.driver)
        oid = _done_order(other["id"], drv["id"])
        assert client.post(f"/instant/orders/{oid}/rate",
                           headers=other["auth"], json={"stars": 1}).status_code == 200
    for i in range(3):                                 # как ВОДИТЕЛЬ — три пятёрки
        pax = user_factory(f"BothPax{i}")
        oid = _done_order(drv["id"], pax["id"])
        assert client.post(f"/instant/orders/{oid}/rate",
                           headers=pax["auth"], json={"stars": 5}).status_code == 200

    prof = _profile(drv["id"])
    assert prof.rating == pytest.approx(5.0), (
        f"в рабочий балл затекли пассажирские оценки: {prof.rating}"
    )
    assert not _penalised(prof)


# ==================== 5. Щит рейтинга: пересчёт после снятия оценки ====================
def test_rating_shield_recomputes_by_driver_side_only(client, user_factory):
    """Админ снял накрученную единицу за поездку. Пересчёт обязан взять водительские
    оценки, а не общий балл — иначе щит вернёт в профиль оценки пассажирской роли."""
    drv = user_factory("ShieldDrv", role=UserRole.driver)
    pax = user_factory("ShieldPax")
    oid = _done_order(drv["id"], pax["id"])
    assert client.post(f"/instant/orders/{oid}/rate",
                       headers=pax["auth"], json={"stars": 1}).status_code == 200

    other = user_factory("ShieldOtherDrv", role=UserRole.driver)   # его же оценили как пассажира
    oid2 = _done_order(other["id"], drv["id"])
    assert client.post(f"/instant/orders/{oid2}/rate",
                       headers=other["auth"], json={"stars": 1}).status_code == 200

    admin = user_factory("ShieldAdmin", role=UserRole.admin)
    with Session(engine) as s:
        rid = s.exec(select(Rating.id).where(Rating.order_id == oid)).first()
    assert client.post(f"/admin/ratings/{rid}/exclude", headers=admin["auth"],
                       json={"excluded": True}).status_code == 200

    prof = _profile(drv["id"])
    assert prof.rating == 5.0, (
        f"водительских оценок не осталось → нейтральный сид, а стало {prof.rating}"
    )
    assert not _penalised(prof)
