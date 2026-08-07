"""«Только для своих» должно закрывать поездку ЦЕЛИКОМ, а не только ленту.

Фильтр only_trusted стоял на выдаче (/rides, /rides/near, /rides/{id}, /r/{id}, /match/rides)
и на отклике по заявке — но не на самом бронировании. То есть лента поездку прячет, прямой
GET отдаёт 404, а POST /bookings проходит с 200: чужой человек становится полноправным
участником закрытой поездки (чат, детали брони, телефон водителя после подтверждения).
Id поездки чужому взять есть откуда — они последовательные, плюс пуш «карауль маршрут».

Обещание «круга своих» — это и есть продукт Юлдаша, поэтому дыра именно в бронировании
дороже, чем кажется: она ломает обещание на самом важном шаге.
"""
import uuid

from sqlmodel import Session

from app.db import engine
from app.models import Booking, Ride, User, UserRole
from app.security import make_token

_n = {"i": 0}


def mk_user(name="", avatar="", verified=False, role=UserRole.passenger):
    """Пользователь с точным контролем уровня доверия (L0 = без имени/фото/документов)."""
    _n["i"] += 1
    i = _n["i"]
    with Session(engine) as s:
        u = User(phone=f"real-closed-{i}", name=name, avatar_url=avatar,
                 verified=verified, role=role, telegram_id=f"closed{i}")
        s.add(u)
        s.commit()
        s.refresh(u)
        return {"id": u.id, "token": make_token(u.id),
                "auth": {"Authorization": f"Bearer {make_token(u.id)}"}}


def _city():
    return "Город" + uuid.uuid4().hex[:8]      # уникальный маршрут → изоляция от чужих поездок и кеша


def _publish(client, drv, **extra):
    body = {"from_city": _city(), "to_city": _city(), "depart_at": "2030-01-01T10:00:00",
            "seats_total": 3, "price": 300, **extra}
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _make_insider(client):
    """L3 «свой» — через инвайт от проверенного участника (как в жизни)."""
    inviter = mk_user(name="Приглаш", avatar="https://x/inv.jpg", verified=True)
    code = client.post("/invites", headers=inviter["auth"]).json()["code"]
    u = mk_user()
    client.post("/invites/redeem", headers=u["auth"], json={"code": code})
    return u


def _seats_left(ride_id: int) -> int:
    with Session(engine) as s:
        return s.get(Ride, ride_id).seats_left


def test_outsider_cannot_book_only_trusted_ride(client):
    """Лента прячет, прямой id отдаёт 404 — и бронь тоже должна быть закрыта (IDOR)."""
    drv = mk_user(name="Вод", avatar="https://x/v.jpg", verified=True, role=UserRole.driver)
    ride = _publish(client, drv, only_trusted=True)
    outsider = mk_user()                                    # L0: телефон и всё

    assert client.get(f"/rides/{ride['id']}", headers=outsider["auth"]).status_code == 404
    r = client.post("/bookings", headers=outsider["auth"],
                    json={"ride_id": ride["id"], "seats": 1})
    assert r.status_code == 403, r.text
    assert _seats_left(ride["id"]) == 3                     # место не списано
    with Session(engine) as s:
        assert s.exec(
            Booking.__table__.select().where(Booking.__table__.c.ride_id == ride["id"])
        ).first() is None                                   # брони не появилось


def test_verified_but_not_insider_cannot_book_only_trusted_ride(client):
    """L2 (документы проверены) — ещё не «свой»: та же планка, что у отклика на заявку."""
    drv = mk_user(name="Вод2", avatar="https://x/v2.jpg", verified=True, role=UserRole.driver)
    ride = _publish(client, drv, only_trusted=True)
    l2 = mk_user(name="Проверенный", avatar="https://x/l2.jpg", verified=True)

    r = client.post("/bookings", headers=l2["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert r.status_code == 403, r.text


def test_insider_books_only_trusted_ride(client):
    """Обратная сторона: «свой» (L3) бронирует закрытую поездку как обычную."""
    drv = mk_user(name="Вод3", avatar="https://x/v3.jpg", verified=True, role=UserRole.driver)
    ride = _publish(client, drv, only_trusted=True)
    insider = _make_insider(client)

    r = client.post("/bookings", headers=insider["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert r.status_code == 200, r.text
    assert _seats_left(ride["id"]) == 2


def test_open_ride_still_bookable_by_anyone(client):
    """Страховка: обычную поездку новичок бронирует как раньше — фильтр не задел открытые."""
    drv = mk_user(name="Вод4", avatar="https://x/v4.jpg", verified=True, role=UserRole.driver)
    ride = _publish(client, drv)
    l0 = mk_user()

    r = client.post("/bookings", headers=l0["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert r.status_code == 200, r.text
