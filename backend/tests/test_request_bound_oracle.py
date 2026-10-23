# -*- coding: utf-8 -*-
"""«Предложить свой рейс» не должен рассказывать водителю про пассажира (ревью PR190, 23.10.2026).

Отклик шлёт водитель. Если его рейс «только женщины» или «только для своих», а пассажир под правило
не подходит, раньше водитель получал 403 с текстом «Эта поездка только для женщин» / «Укажи пол» /
«Поездка только для своих» — то есть узнавал пол и уровень доверия незнакомого человека, просто
предложив свой рейс. Теперь правило по-прежнему действует (отклик не создаётся, бронь не
открывается), но отказ нейтральный — тот же, что «рейс не подходит заявке».
"""
import json

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import RequestResponse, Ride, UserRole
from app.routers import requests as requests_mod
from app.safety_logic import MSG_GENDER_UNKNOWN, MSG_WOMEN_ONLY_RIDE
from test_booking_segments import seed_route

_LEAKY = (
    *MSG_WOMEN_ONLY_RIDE, *MSG_GENDER_UNKNOWN,
    "Поездка только для своих", "Сәфәр тик үҙ кешеләр өсөн",
)


def _flag_ride(rid, **fields):
    with Session(engine) as session:
        ride = session.get(Ride, rid)
        for key, value in fields.items():
            setattr(ride, key, value)
        session.add(ride)
        session.commit()


def _respond(client, driver, reqid, rid):
    return client.post(f"/requests/{reqid}/respond", headers=driver["auth"],
                       json={"ride_id": rid, "price": 450})


def _responses_of(driver):
    with Session(engine) as session:
        return session.exec(select(RequestResponse).where(RequestResponse.driver_id == driver["id"])).all()


@pytest.mark.parametrize("gender", ["male", ""])
def test_women_only_ride_refusal_does_not_reveal_passenger_gender(client, user_factory, gender):
    driver = user_factory("Oracle driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Oracle passenger", gender=gender)
    rid, reqid = seed_route(driver, passenger)
    _flag_ride(rid, women_only=True)
    refused = _respond(client, driver, reqid, rid)
    assert refused.status_code == 409, refused.text
    text = json.dumps(refused.json(), ensure_ascii=False)
    assert not any(leak in text for leak in _LEAKY), text
    assert not _responses_of(driver)          # правило действует: отклик не создан


def test_only_trusted_ride_refusal_does_not_reveal_passenger_trust(client, user_factory, monkeypatch):
    driver = user_factory("Oracle trust driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Oracle trust passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    _flag_ride(rid, only_trusted=True)
    monkeypatch.setattr(requests_mod, "trust_level", lambda *a: 0)
    refused = _respond(client, driver, reqid, rid)
    assert refused.status_code == 409, refused.text
    text = json.dumps(refused.json(), ensure_ascii=False)
    assert not any(leak in text for leak in _LEAKY), text
    assert not _responses_of(driver)


def test_refusal_is_the_same_neutral_answer_as_a_route_mismatch(client, user_factory):
    """Снаружи не отличить «пол/доверие» от «не подходит маршрут»: ответ побуквенно один и тот же."""
    driver = user_factory("Oracle same driver", role=UserRole.driver, gender="female")
    male = user_factory("Oracle same male", gender="male")
    rid_women, req_women = seed_route(driver, male)
    _flag_ride(rid_women, women_only=True)
    by_gender = _respond(client, driver, req_women, rid_women)

    other = user_factory("Oracle same other", gender="female")
    rid_far, req_far = seed_route(driver, other, frm="Уфа", to="Казань")     # маршрут не по пути
    by_route = _respond(client, driver, req_far, rid_far)

    assert by_gender.status_code == by_route.status_code == 409
    assert by_gender.json() == by_route.json()


def test_women_only_ride_still_accepts_a_woman_passenger(client, user_factory):
    """Правило не сломано в обратную сторону: пассажирка рейса «только женщины» откликается как раньше."""
    driver = user_factory("Oracle ok driver", role=UserRole.driver, gender="female")
    passenger = user_factory("Oracle ok passenger", gender="female")
    rid, reqid = seed_route(driver, passenger)
    _flag_ride(rid, women_only=True)
    assert _respond(client, driver, reqid, rid).status_code == 200
