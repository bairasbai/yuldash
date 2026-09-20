"""Реальный HTTP-контракт восстановления оценки обеих сторон такси."""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Rating, SafetyProfile, UserRole
from app.rating_service import RATING_WINDOW_DAYS
from app.timeutil import utcnow
from test_rating_works_the_same_everywhere import _done_order


def _trip(user_factory, hours_ago=1):
    pax = user_factory("RecoveryPax")
    driver = user_factory("RecoveryDriver", role=UserRole.driver)
    with Session(engine) as session:
        oid = _done_order(session, pax["id"], driver["id"], hours_ago)
    return pax, driver, oid


def test_each_participant_recovers_only_own_stars(client, user_factory):
    pax, driver, oid = _trip(user_factory)
    url = f"/instant/orders/{oid}"
    for actor in (pax, driver):
        payload = client.get(url, headers=actor["auth"])
        assert payload.status_code == 200, payload.text
        assert payload.json()["my_stars"] == 0
        assert payload.json()["can_rate"] is True
    assert client.post(url + "/rate", headers=pax["auth"], json={"stars": 4}).status_code == 200
    assert client.get(url, headers=driver["auth"]).json()["my_stars"] == 0
    assert client.post(url + "/rate", headers=driver["auth"], json={"stars": 2}).status_code == 200
    assert client.get(url, headers=pax["auth"]).json()["my_stars"] == 4
    assert client.get(url, headers=driver["auth"]).json()["my_stars"] == 2
    stranger = user_factory("RecoveryStranger")
    assert client.get(url, headers=stranger["auth"]).status_code == 403
    assert client.post(url + "/rate", headers=stranger["auth"], json={"stars": 1}).status_code == 403


@pytest.mark.parametrize("side", [0, 1])
def test_repeat_and_change_do_not_duplicate_rating(client, user_factory, side):
    pax, driver, oid = _trip(user_factory)
    actor = (pax, driver)[side]
    url = f"/instant/orders/{oid}"
    for stars in (3, 3, 5):
        result = client.post(url + "/rate", headers=actor["auth"], json={"stars": stars})
        assert result.status_code == 200, result.text
        assert result.json()["count"] == 1
        view = client.get(url, headers=actor["auth"]).json()
        assert view["my_stars"] == stars
        assert view["can_rate"] is True
        with Session(engine) as session:
            ratings = session.exec(select(Rating).where(Rating.order_id == oid)).all()
            assert len(ratings) == 1
            assert ratings[0].stars == stars


@pytest.mark.parametrize("side", [0, 1])
def test_expired_window_disables_and_rejects_rating(client, user_factory, side):
    pax, driver, oid = _trip(user_factory, (RATING_WINDOW_DAYS + 1) * 24)
    auth = (pax, driver)[side]["auth"]
    url = f"/instant/orders/{oid}"
    assert client.get(url, headers=auth).json()["can_rate"] is False
    assert client.post(url + "/rate", headers=auth, json={"stars": 4}).status_code == 409


@pytest.mark.parametrize("side", [0, 1])
@pytest.mark.parametrize("hours_ago,allowed", [(1, True), (72, False)])
def test_pause_get_permission_matches_post(client, user_factory, side, hours_ago, allowed):
    pax, driver, oid = _trip(user_factory, hours_ago)
    actor = (pax, driver)[side]
    with Session(engine) as session:
        session.add(SafetyProfile(user_id=actor["id"], strikes=1,
                                  suspended_until=utcnow() + timedelta(days=7)))
        session.commit()
    url = f"/instant/orders/{oid}"
    view = client.get(url, headers=actor["auth"])
    assert view.status_code == 200, view.text
    result = client.post(url + "/rate", headers=actor["auth"], json={"stars": 4})
    assert result.status_code == (200 if allowed else 403), result.text
    assert view.json()["can_rate"] is allowed
