"""Выписка «что Юлдаш обо мне знает» — числа и сроки, а не общие слова.

«Удалить мои данные» люди просят не потому, что данные мешают, а потому что не знают,
что именно у нас лежит и надолго ли. Оферта на этот страх не отвечает. Отвечают числа:
переписка уходит сама через месяц, поездки — через полгода, геолокацию мы не храним вовсе.

Сроки обязаны совпадать с настоящим ретеншеном: если приложение обещает одно,
а чистилка делает другое — это то же враньё, только про приватность.
"""

from datetime import timedelta

from sqlmodel import Session

from app import cleanup
from app.db import engine
from app.models import Booking, DriverProfile, Message, Ride
from app.timeutil import utcnow


def test_empty_account_shows_honest_zeros(client, user_factory):
    """Новый человек: ничего не накоплено — и выписка это прямо говорит."""
    u = user_factory("DataNewbie")

    r = client.get("/me/data", headers=u["auth"])
    assert r.status_code == 200, r.text
    d = r.json()
    assert d["rides"] == 0 and d["messages"] == 0 and d["driver_docs"] == 0


def test_counts_are_real(client, user_factory):
    """Числа берутся из базы, а не выдумываются."""
    u = user_factory("DataOwner")
    with Session(engine) as s:
        ride = Ride(driver_id=u["id"], from_city="Баймаҡ", to_city="Сибай", price=300,
                    seats_total=3, seats_left=3, depart_at=utcnow() + timedelta(hours=3))
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=u["id"], seats=1, price=300)
        s.add(b)
        s.commit()
        s.refresh(b)
        s.add(Message(booking_id=b.id, sender_id=u["id"], text="привет"))
        s.add(Message(booking_id=b.id, sender_id=u["id"], text="", voice_url="/media/voice/a.m4a"))
        s.commit()

    d = client.get("/me/data", headers=u["auth"]).json()
    assert d["rides"] == 1
    assert d["messages"] == 2
    assert d["voices"] == 1


def test_retention_days_match_the_real_cleaner(client, user_factory):
    """Сроки в выписке — те же, по которым реально чистится база."""
    u = user_factory("DataRetention")

    d = client.get("/me/data", headers=u["auth"]).json()
    assert d["messages_days"] == cleanup.MSG_DAYS
    assert d["voices_days"] == cleanup.MEDIA_DAYS
    assert d["notifications_days"] == cleanup.NOTIF_DAYS
    assert d["rides_days"] == cleanup.TRIP_DAYS


def test_we_say_plainly_what_we_do_not_store(client, user_factory):
    """Точную геопозицию и данные карты не храним — и говорим это прямо."""
    u = user_factory("DataNotStored")

    d = client.get("/me/data", headers=u["auth"]).json()
    assert d["location_stored"] is False
    assert d["card_stored"] is False


def test_driver_documents_are_counted_and_removable(client, user_factory):
    """Документы водителя видно отдельно: их можно удалить точечно."""
    u = user_factory("DataDriver")
    with Session(engine) as s:
        s.add(DriverProfile(user_id=u["id"], license_url="/media/docs/l.jpg",
                            car_photo_url="/media/docs/c.jpg", docs_status="verified"))
        s.commit()

    d = client.get("/me/data", headers=u["auth"]).json()
    assert d["driver_docs"] == 2
    assert d["driver_docs_removable"] is True


def test_documents_under_review_are_not_offered_for_removal(client, user_factory):
    """На проверке кнопку удаления не обещаем — сервер её всё равно не пропустит."""
    u = user_factory("DataPending")
    with Session(engine) as s:
        s.add(DriverProfile(user_id=u["id"], license_url="/media/docs/l.jpg",
                            car_photo_url="/media/docs/c.jpg", docs_status="pending"))
        s.commit()

    d = client.get("/me/data", headers=u["auth"]).json()
    assert d["driver_docs"] == 2
    assert d["driver_docs_removable"] is False


def test_counts_drop_after_documents_are_deleted(client, user_factory):
    """Удалил документы — выписка сразу это показывает, а не помнит старое."""
    u = user_factory("DataAfterDelete")
    with Session(engine) as s:
        s.add(DriverProfile(user_id=u["id"], license_url="/media/docs/l.jpg",
                            car_photo_url="/media/docs/c.jpg", docs_status="verified"))
        s.commit()

    assert client.post("/me/driver-docs/delete", headers=u["auth"]).status_code == 200
    assert client.get("/me/data", headers=u["auth"]).json()["driver_docs"] == 0


def test_stranger_sees_only_his_own(client, user_factory):
    """Выписка строго про себя: чужие поездки в неё не попадают."""
    owner = user_factory("DataMine")
    other = user_factory("DataYours")
    with Session(engine) as s:
        s.add(Ride(driver_id=owner["id"], from_city="Баймаҡ", to_city="Сибай", price=300,
                   seats_total=3, seats_left=3, depart_at=utcnow() + timedelta(hours=3)))
        s.commit()

    assert client.get("/me/data", headers=other["auth"]).json()["rides"] == 0
