"""Regression tests for passenger request matching and response flows."""

from sqlmodel import Session, select

from app.db import engine
from app.models import Block, Booking, BookingStatus, RequestResponse, Ride, RideRequest, User, UserRole


def _create_request(client, passenger, **overrides):
    body = {
        "from_city": "ReqCityA",
        "to_city": "ReqCityB",
        "seats": 2,
        "max_price": 500,
        "comment": "Need a calm ride",
        "women_only": True,
        "child_seat": True,
        "pets": True,
        "wheelchair": True,
        "baggage": True,
        "non_smoking": True,
        "air_conditioner": True,
        **overrides,
    }
    response = client.post("/requests", headers=passenger["auth"], json=body)
    assert response.status_code == 200, response.text
    return response.json()


def test_admin_request_for_phone_creates_request_for_target_user(client, user_factory):
    user = user_factory("RequestRegular")
    admin = user_factory("RequestAdmin", role=UserRole.admin)

    assert client.post(
        "/admin/request-for-phone",
        headers=user["auth"],
        json={"phone": "+79990000100", "name": "No access", "from_city": "A", "to_city": "B"},
    ).status_code == 403
    assert client.post(
        "/admin/request-for-phone",
        headers=admin["auth"],
        json={"phone": " ", "name": "No phone", "from_city": "A", "to_city": "B"},
    ).status_code == 400

    created = client.post(
        "/admin/request-for-phone",
        headers=admin["auth"],
        json={"phone": "+79990000101", "name": "Phone Passenger", "from_city": "A", "to_city": "B", "seats": 3},
    )
    assert created.status_code == 200
    body = created.json()
    assert body["from_city"] == "A"
    assert body["to_city"] == "B"
    assert body["seats"] == 3
    assert body["for_relative_name"] == "Phone Passenger"

    with Session(engine) as session:
        target = session.exec(select(User).where(User.phone == "+79990000101")).first()
        assert target is not None
        assert session.get(RideRequest, body["id"]).passenger_id == target.id


def test_request_response_acceptance_flow(client, user_factory):
    passenger = user_factory("RequestPassenger")
    driver = user_factory("RequestDriver", role=UserRole.driver)
    outsider = user_factory("RequestOutsider")
    request = _create_request(client, passenger)

    feed = client.get("/requests/feed", headers=driver["auth"])
    assert feed.status_code == 200
    row = next(item for item in feed.json() if item["id"] == request["id"])
    assert row["responded"] is False
    assert set(row["prefs"]) == {"women", "child", "pets", "wheelchair", "baggage", "nosmoke", "ac"}

    assert client.post(f"/requests/{request['id']}/respond", headers=passenger["auth"], json={}).status_code == 400
    response = client.post(
        f"/requests/{request['id']}/respond",
        headers=driver["auth"],
        json={"price": 450, "comment": "I can help"},
    )
    assert response.status_code == 200
    response_id = response.json()["id"]

    duplicate = client.post(
        f"/requests/{request['id']}/respond",
        headers=driver["auth"],
        json={"price": 999, "comment": "duplicate"},
    )
    assert duplicate.status_code == 200
    assert duplicate.json()["id"] == response_id

    assert client.get(f"/requests/{request['id']}/responses", headers=outsider["auth"]).status_code == 403
    responses = client.get(f"/requests/{request['id']}/responses", headers=passenger["auth"])
    assert responses.status_code == 200
    assert responses.json()[0]["id"] == response_id
    assert responses.json()[0]["driver_name"] == "RequestDriver"

    accepted = client.post(f"/responses/{response_id}/accept", headers=passenger["auth"])
    assert accepted.status_code == 200
    booking_id = accepted.json()["booking_id"]
    assert client.post(f"/responses/{response_id}/accept", headers=passenger["auth"]).status_code == 400

    with Session(engine) as session:
        booking = session.get(Booking, booking_id)
        request_db = session.get(RideRequest, request["id"])
        response_db = session.get(RequestResponse, response_id)
        ride = session.get(Ride, booking.ride_id)
        assert booking.status == BookingStatus.confirmed
        assert request_db.status == "matched"
        assert response_db.status == "accepted"
        assert ride.driver_id == driver["id"]
        assert ride.seats_total == 2
        assert ride.seats_left == 0


def test_blocked_request_is_hidden_and_cannot_be_responded_to(client, user_factory):
    passenger = user_factory("BlockedRequestPassenger")
    driver = user_factory("BlockedRequestDriver", role=UserRole.driver)
    request = _create_request(client, passenger, from_city="BlockA", to_city="BlockB")

    with Session(engine) as session:
        session.add(Block(user_id=driver["id"], blocked_user_id=passenger["id"]))
        session.commit()

    feed = client.get("/requests/feed", headers=driver["auth"])
    assert feed.status_code == 200
    assert all(item["id"] != request["id"] for item in feed.json())
    assert client.post(f"/requests/{request['id']}/respond", headers=driver["auth"], json={}).status_code == 403


def test_requests_near_filters_paginates_and_keeps_phone_private(client, user_factory):
    passenger = user_factory("NearRequestPassenger")
    viewer = user_factory("NearRequestViewer", role=UserRole.driver)
    first = _create_request(client, passenger, from_city="Уфа", to_city="Сибай", comment="first")
    _create_request(client, passenger, from_city="Уфа", to_city="Сибай", comment="second")

    near = client.get(
        "/requests/near",
        headers=viewer["auth"],
        params={"from_city": "Уфа", "to_city": "Сибай", "lat": 54.735, "lng": 55.958, "radius_km": 5, "limit": 1},
    )
    assert near.status_code == 200
    body = near.json()
    assert body["count"] >= 2
    assert len(body["items"]) == 1
    assert "phone" not in body["items"][0]
    assert body["items"][0]["from_lat"] is not None
    assert body["items"][0]["from_lng"] is not None
    assert body["items"][0]["distance_km"] is not None

    far = client.get(
        "/requests/near",
        headers=viewer["auth"],
        params={"from_city": "Уфа", "to_city": "Сибай", "lat": 52.0, "lng": 58.0, "radius_km": 1},
    )
    assert far.status_code == 200
    assert all(item["id"] != first["id"] for item in far.json()["items"])


def test_missing_request_endpoints_return_404(client, user_factory):
    user = user_factory("MissingRequestUser")
    assert client.get("/match/rides", headers=user["auth"], params={"request_id": 99999999}).status_code == 404
    assert client.get("/requests/99999999/responses", headers=user["auth"]).status_code == 404
    assert client.post("/requests/99999999/respond", headers=user["auth"], json={}).status_code == 404
    assert client.post("/responses/99999999/accept", headers=user["auth"]).status_code == 404
