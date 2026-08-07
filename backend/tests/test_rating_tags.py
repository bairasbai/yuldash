# -*- coding: utf-8 -*-
"""Быстрые метки к оценке: «вежливый», «вовремя» — тапнул и не пишешь ничего.

Зачем отдельно от текстового отзыва: текст пишут единицы, и он ещё ждёт модерации,
а метку ставит почти каждый. Метки из закрытого списка, поэтому оскорбить ими нельзя
и модерация им не нужна — но именно поэтому список надо держать закрытым: иначе
клиент положит в базу произвольную строку.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, BookingStatus, Rating, UserRole
from app.safety_logic import RATING_TAGS_MAX, clean_tags


def _publish(client, drv):
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "depart_at": "2030-01-01T08:00:00",
        "seats": 3, "price": 300,
    })
    assert r.status_code == 200, r.text
    return r.json()


def _booked_and_done(client, drv, pax):
    ride = _publish(client, drv)
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride["id"], "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    with Session(engine) as s:
        row = s.get(Booking, bid)
        row.status = BookingStatus.done
        s.add(row)
        s.commit()
    return bid


def _tags(booking_id: int, rater_id: int) -> str:
    with Session(engine) as s:
        r = s.exec(select(Rating).where(Rating.booking_id == booking_id,
                                        Rating.rater_id == rater_id)).first()
        return r.tags if r else "<оценки нет>"


# --------------------------- чистка списка ---------------------------

def test_unknown_tags_dropped():
    """Выдуманный код в базу не попадает, а известные рядом — остаются."""
    assert clean_tags("polite,сожгиавтобус,ontime") == "polite,ontime"


def test_tags_deduplicated_and_capped():
    """Дубли схлопываются, длина ограничена — иначе метка превращается в помойку."""
    assert clean_tags("polite,polite,ontime") == "polite,ontime"
    many = ",".join(["polite", "ontime", "clean", "safe", "comfortable", "helpful"])
    assert len(clean_tags(many).split(",")) == RATING_TAGS_MAX


def test_empty_tags_are_empty():
    assert clean_tags(None) == ""
    assert clean_tags("") == ""
    assert clean_tags(",,,") == ""


# --------------------------- через ручку ---------------------------

def test_tags_saved_with_rating(client, user_factory):
    drv = user_factory("TagDrv", role=UserRole.driver)
    pax = user_factory("TagPax")
    bid = _booked_and_done(client, drv, pax)
    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                    json={"stars": 5, "tags": "polite,ontime"})
    assert r.status_code == 200, r.text
    assert _tags(bid, pax["id"]) == "polite,ontime"


def test_garbage_tags_do_not_break_rating(client, user_factory):
    """Мусор в метках не должен ронять саму оценку — звёзды важнее меток."""
    drv = user_factory("TagDrv2", role=UserRole.driver)
    pax = user_factory("TagPax2")
    bid = _booked_and_done(client, drv, pax)
    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                    json={"stars": 4, "tags": "drop table,<script>,polite"})
    assert r.status_code == 200, r.text
    assert _tags(bid, pax["id"]) == "polite"


def test_restars_without_tags_keeps_tags(client, user_factory):
    """Поправил звёзды после меток — метки на месте.

    Так и ходит приложение: первый тап по звезде улетает с пустым CSV, метки прилетают
    следующим запросом. Если бы пустое затирало, человек терял бы уже поставленные метки.
    """
    drv = user_factory("TagDrv3", role=UserRole.driver)
    pax = user_factory("TagPax3")
    bid = _booked_and_done(client, drv, pax)
    client.post(f"/bookings/{bid}/rate", headers=pax["auth"],
                json={"stars": 5, "tags": "polite,clean"})
    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 3})
    assert r.status_code == 200, r.text
    assert _tags(bid, pax["id"]) == "polite,clean"


def test_rating_without_tags_still_works(client, user_factory):
    """Старое приложение метки не шлёт вообще — оценка обязана работать как раньше."""
    drv = user_factory("TagDrv4", role=UserRole.driver)
    pax = user_factory("TagPax4")
    bid = _booked_and_done(client, drv, pax)
    r = client.post(f"/bookings/{bid}/rate", headers=pax["auth"], json={"stars": 5})
    assert r.status_code == 200, r.text
    assert _tags(bid, pax["id"]) == ""
