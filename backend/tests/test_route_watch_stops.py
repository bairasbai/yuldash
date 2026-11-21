from datetime import timedelta

from sqlmodel import Session, select

from app import services
from app.db import engine
from app.models import Notification, Ride, UserRole
from app.route_watch_dispatch import enqueue_route_watch
from app.timeutil import utcnow


def subscribe(client, who, from_city, to_city, **options):
    reply = client.post("/route-watch", headers=who["auth"], json={
        "from_city": from_city, "to_city": to_city, **options,
    })
    assert reply.status_code == 200, reply.text
    return reply.json()["id"]


def published_notices(ride_id):
    with Session(engine) as session:
        return session.exec(select(Notification).where(
            Notification.type == "route_watch", Notification.ref_id == ride_id,
            Notification.ref_kind == "ride",
        )).all()


def test_published_ride_matches_its_stops_and_direction(client, user_factory, monkeypatch):
    driver = user_factory(role=UserRole.driver)
    tail = user_factory()
    reverse = user_factory()
    both = user_factory()
    requests_only = user_factory()
    subscribe(client, tail, "Ургаза", "Акъяр")
    subscribe(client, reverse, "Акъяр", "Ургаза")
    subscribe(client, both, "Акъяр", "Ургаза", direction="both")
    subscribe(client, requests_only, "Ургаза", "Акъяр", watch_kind="requests")
    queued = []
    monkeypatch.setattr(services, "_push_async", queued.extend)

    reply = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Акъяр", "waypoints": "Ургаза",
        "depart_at": (utcnow() + timedelta(days=2)).isoformat() + "Z",
        "seats_total": 3, "price": 500,
    })
    assert reply.status_code == 200, reply.text
    ride_id = reply.json()["id"]
    notes = published_notices(ride_id)
    assert {note.user_id for note in notes} == {tail["id"], both["id"]}
    assert all("садишься в: Ургаза" in note.body_ru for note in notes)
    assert all("ултырыу урыны: Ургаза" in note.body_ba for note in notes)
    assert {row[0] for row in queued} == {tail["id"], both["id"]}


def test_watch_and_two_feeds_agree_on_free_seats_per_segment(client, user_factory, monkeypatch):
    driver = user_factory(role=UserRole.driver)
    first = user_factory()
    tail = user_factory()
    subscribe(client, first, "Сибай", "Ургаза")
    subscribe(client, tail, "Ургаза", "Акъяр")
    monkeypatch.setattr(services, "_push_async", lambda items: None)
    with Session(engine) as session:
        ride = Ride(
            driver_id=driver["id"], from_city="Сибай", to_city="Акъяр", waypoints="Ургаза",
            depart_at=utcnow() + timedelta(hours=8), price=500, seats_total=3, seats_left=0,
            seats_free_max=3, leg_free="0,3", terms_version=1,
        )
        session.add(ride)
        event_id, = enqueue_route_watch(session, ride, source_key="fixture:full-prefix")
        session.commit()
        ride_id = ride.id
        assert services.notify_route_watchers(session, event_id) == 1
    assert {note.user_id for note in published_notices(ride_id)} == {tail["id"]}

    for user, origin, destination, expected in (
        (first, "Сибай", "Ургаза", False),
        (tail, "Ургаза", "Акъяр", True),
    ):
        route = {"from_city": origin, "to_city": destination}
        feed = client.get("/rides", headers=user["auth"], params=route)
        near = client.get("/rides/near", headers=user["auth"], params=route)
        assert feed.status_code == near.status_code == 200
        assert any(row["id"] == ride_id for row in feed.json()) == expected
        assert any(row["id"] == ride_id for row in near.json()["items"]) == expected
