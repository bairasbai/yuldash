"""Круг «своих» знает про наказание.

«Только для своих» — сердце Юлдаша: закрытая поездка, которую видят лишь те, кого привёл
проверенный человек. Статус «свой» даётся навсегда по инвайту и хранится отдельной строкой.

Из-за этого наказание до него не доставало (проверено запросами, аудит 2026-08-12, волна 56):

  • участника отстранил разбор жалобы — он по-прежнему видел и бронировал закрытые поездки;
  • и сам продолжал раздавать приглашения, то есть цепочка «своих» росла от человека,
    которому платформа прямо сейчас не доверяет.

Половина защиты уже была написана: при активации кода проверяли, не разжалован ли пригласивший.
Но проверка смотрела на УРОВЕНЬ, а у отстранённого уровень оставался прежним — дарованный
«свой» её и обходил.

Теперь на время паузы дарованный статус заморожен, а звать в круг нельзя вообще. Наказание
кончилось — «свой» возвращается сам: отбирать доверие навсегда мы не хотим.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import SafetyProfile, User, UserRole
from app.timeutil import utcnow
from app.trust_service import INSIDER_LEVEL, trust_level


def _suspend(session: Session, user_id: int, days: int = 7) -> None:
    session.add(SafetyProfile(user_id=user_id, suspended_until=utcnow() + timedelta(days=days)))
    session.commit()


def _invite(client, owner, newcomer):
    code = client.post("/invites", headers=owner["auth"])
    assert code.status_code == 200, code.text
    return client.post("/invites/redeem", headers=newcomer["auth"],
                       json={"code": code.json()["code"]})


def _closed_ride(client, driver) -> int:
    depart = (utcnow() + timedelta(hours=settings.local_tz_offset_hours, days=1)).replace(
        microsecond=0).isoformat()
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "CircleA", "to_city": "CircleB", "seats_total": 3, "price": 500,
        "depart_at": depart, "only_trusted": True,
    })
    assert r.status_code == 200, r.text
    return r.json()["id"]


def test_отстранённый_свой_закрытых_поездок_не_видит(client, user_factory):
    owner = user_factory("CircleOwner", role=UserRole.driver)
    guest = user_factory("CircleGuest", role=UserRole.passenger)
    assert _invite(client, owner, guest).status_code == 200
    ride_id = _closed_ride(client, owner)

    def sees() -> bool:
        rows = client.get("/rides", params={"from_city": "CircleA"}, headers=guest["auth"]).json()
        return any(r["id"] == ride_id for r in rows)

    assert sees() is True                       # пока всё в порядке — он свой
    with Session(engine) as s:
        _suspend(s, guest["id"])
    assert sees() is False
    assert client.post("/bookings", headers=guest["auth"],
                       json={"ride_id": ride_id, "seats": 1}).status_code == 403


def test_отстранённый_никого_в_круг_не_приводит(client, user_factory):
    """Главное: цепочка доверия не должна расти от того, кто под разбором."""
    owner = user_factory("CircleBad", role=UserRole.driver)
    with Session(engine) as s:
        _suspend(s, owner["id"])

    assert client.post("/invites", headers=owner["auth"]).status_code == 403


def test_код_выпущенный_до_наказания_тоже_не_работает(client, user_factory):
    """Иначе достаточно раздать коды заранее — и наказание ничего не меняет."""
    owner = user_factory("CircleEarly", role=UserRole.driver)
    newbie = user_factory("CircleLate", role=UserRole.passenger)
    code = client.post("/invites", headers=owner["auth"]).json()["code"]

    with Session(engine) as s:
        _suspend(s, owner["id"])

    r = client.post("/invites/redeem", headers=newbie["auth"], json={"code": code})
    assert r.status_code == 400
    with Session(engine) as s:
        assert trust_level(s, s.get(User, newbie["id"])) < INSIDER_LEVEL


def test_наказание_кончилось_свой_вернулся(client, user_factory):
    """Страховка от перестраховки: пауза — не приговор. Срок вышел — статус на месте."""
    owner = user_factory("CircleBack", role=UserRole.driver)
    guest = user_factory("CircleBackGuest", role=UserRole.passenger)
    assert _invite(client, owner, guest).status_code == 200

    with Session(engine) as s:
        prof = SafetyProfile(user_id=guest["id"], suspended_until=utcnow() - timedelta(hours=1))
        s.add(prof)
        s.commit()
        assert trust_level(s, s.get(User, guest["id"])) >= INSIDER_LEVEL


def test_обычный_свой_живёт_как_прежде(client, user_factory):
    """Контроль: без наказаний всё работает — и видит, и приглашает."""
    owner = user_factory("CircleOk", role=UserRole.driver)
    guest = user_factory("CircleOkGuest", role=UserRole.passenger)
    assert _invite(client, owner, guest).status_code == 200
    with Session(engine) as s:
        assert trust_level(s, s.get(User, guest["id"])) == INSIDER_LEVEL

    third = user_factory("CircleOkThird", role=UserRole.passenger)
    assert _invite(client, guest, third).status_code == 200
