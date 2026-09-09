"""Одни аккаунты проходят попутку и такси, меняясь местами по окончании поездки."""
from app.models import UserRole
from test_flows import _publish, _book, just_left
from test_instant import fake_redis, _heartbeat, _create_order, ORIG  # noqa: F401


def test_rideshare_then_taxi_with_reversed_participants(client, user_factory, fake_redis):
    first = user_factory("Сначала водитель", role=UserRole.driver)
    second = user_factory("Сначала пассажир", role=UserRole.driver)
    stranger = user_factory("Не участник")
    ride = _publish(client, first, depart_at=just_left())
    booking = _book(client, second, ride["id"])
    bid = booking["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=first["auth"]).status_code == 200
    for actor, role in ((first, "driver"), (second, "passenger")):
        response = client.get(f"/bookings/{bid}/role", headers=actor["auth"])
        assert response.status_code == 200 and response.json()["role"] == role
    assert client.get(f"/bookings/{bid}/role", headers=stranger["auth"]).status_code == 403
    assert client.post(f"/rides/{ride['id']}/complete", headers=first["auth"]).status_code == 200
    for actor in (first, second):
        response = client.get(f"/trips/{bid}/receipt", headers=actor["auth"])
        assert response.status_code == 200, response.text
        assert client.post(f"/bookings/{bid}/rate", headers=actor["auth"], json={"stars": 5}).status_code == 200

    # Бывший пассажир везёт бывшего водителя. UserRole.driver не должен мешать ехать пассажиром.
    assert client.post("/driver/online", headers=second["auth"], json={"online": True}).status_code == 200
    _heartbeat(client, second, ORIG)
    order = _create_order(client, first)
    oid = order["id"]
    assert order["status"] == "offered"
    repeated = _create_order(client, first)
    assert repeated["id"] == oid
    assert client.post(f"/instant/orders/{oid}/accept", headers=first["auth"]).status_code == 403
    assert client.get(f"/instant/orders/{oid}", headers=stranger["auth"]).status_code == 403
    for action, status in (("accept", "accepted"), ("arrived", "arriving"),
                           ("onboard", "onboard"), ("done", "done")):
        response = client.post(f"/instant/orders/{oid}/{action}", headers=second["auth"])
        assert response.status_code == 200, response.text
        assert response.json()["status"] == status
        for actor in (first, second):
            observed = client.get(f"/instant/orders/{oid}", headers=actor["auth"])
            assert observed.status_code == 200 and observed.json()["status"] == status
    for actor in (first, second):
        response = client.get(f"/instant/orders/{oid}/receipt", headers=actor["auth"])
        assert response.status_code == 200, response.text
    _heartbeat(client, second, ORIG)
    next_order = _create_order(client, first)
    assert next_order["id"] != oid
    cancelled = client.post(f"/instant/orders/{next_order['id']}/cancel", headers=first["auth"])
    assert cancelled.status_code == 200 and cancelled.json()["status"] == "cancelled"
