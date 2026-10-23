# -*- coding: utf-8 -*-
"""Пуш «Заявку приняли» водителю не называет село посадки/высадки пассажирки (ревью PR190, 23.10.2026).

Пуш лежит на экране блокировки водителя. Раньше в нём стоял маршрут ЗАЯВКИ — то есть село,
где пассажирка садится и выходит. Как в пуше «Новая бронь» (PR186): маршрут РЕЙСА и пометка
«· посадка по пути» без названия села. Само место водитель видит в приложении.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Notification, UserRole
from test_booking_segments import seed_route

NAME = "Push passenger"


def _accepted_push(client, user_factory, *, frm, to, bound=True):
    driver = user_factory("Push driver", role=UserRole.driver, gender="female")
    passenger = user_factory(NAME, gender="female")
    rid, reqid = seed_route(driver, passenger, frm=frm, to=to)
    body = {"ride_id": rid, "price": 450} if bound else {"price": 450}
    r = client.post(f"/requests/{reqid}/respond", headers=driver["auth"], json=body)
    assert r.status_code == 200, r.text
    a = client.post(f"/responses/{r.json()['id']}/accept", headers=passenger["auth"])
    assert a.status_code == 200, a.text
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(
            Notification.user_id == driver["id"], Notification.title_ru == "Заявку приняли")).all()
    assert len(rows) == 1, rows
    return rows[0]


def test_pickup_on_the_way_is_a_hint_without_the_village_name(client, user_factory):
    push = _accepted_push(client, user_factory, frm="Ургаза", to="Бурибай")
    assert push.body_ru == f"{NAME}: Сибай → Акъяр · посадка по пути"
    assert push.body_ba == f"{NAME}: Сибай → Акъяр · юл ыңғайында ултыра"
    for village in ("Ургаза", "Бурибай"):
        assert village not in push.body_ru and village not in push.body_ba


def test_pickup_at_the_start_has_no_hint_and_dropoff_village_is_not_named(client, user_factory):
    push = _accepted_push(client, user_factory, frm="Сибай", to="Бурибай")
    assert push.body_ru == f"{NAME}: Сибай → Акъяр"
    assert push.body_ba == f"{NAME}: Сибай → Акъяр"
    assert "Бурибай" not in push.body_ru and "Бурибай" not in push.body_ba


def test_old_response_without_ride_keeps_the_request_route(client, user_factory):
    """Отклик без рейса создаёт поездку по заявке: города те же, текст пуша не меняется."""
    push = _accepted_push(client, user_factory, frm="Ургаза", to="Бурибай", bound=False)
    assert push.body_ru == f"{NAME}: Ургаза → Бурибай"
    assert push.body_ba == f"{NAME}: Ургаза → Бурибай"
