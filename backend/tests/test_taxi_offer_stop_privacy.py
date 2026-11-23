"""Keep before-accept stop details opaque without changing driver DTO shape."""
from copy import deepcopy

from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder
import test_instant
from test_instant import _driver_online, _offered_order
from test_taxi_route_write_races import _price, _route

fake_redis = test_instant.fake_redis

FIRST = {"lat": 54.74821, "lng": 55.98231, "text": "SYNTHETIC_STOP_A 812", "done": True}
SECOND = {"lat": 54.76821, "lng": 56.00231, "text": "SYNTHETIC_STOP_B 913", "done": False}
EXACT = [FIRST, SECOND]
COARSE = [
    {"lat": 54.75, "lng": 55.98, "text": "", "done": True},
    {"lat": 54.77, "lng": 56.0, "text": "", "done": False},
]


def _fixture_offer(client, user_factory, fake, case):
    driver, passenger, order = _offered_order(client, user_factory, fake, f"{case}D", f"{case}P")
    with Session(engine) as s:
        s.get(InstantOrder, order["id"]).waypoints_json = isv.dump_waypoints(EXACT)
        s.commit()
    return driver, passenger, order["id"]


def _read(client, oid, actor):
    response = client.get(f"/instant/orders/{oid}", headers=actor["auth"])
    assert response.status_code == 200, response.text
    return response.json()


def _poll(client, driver):
    response = client.get("/instant/driver/offer", headers=driver["auth"])
    assert response.status_code == 200, response.text
    assert response.headers["cache-control"] == "no-store"
    return response.json()["offer"]


def test_stops_are_coarse_for_current_offered_driver_and_exact_after_accept_v2(
        client, user_factory, fake_redis):
    driver, passenger, oid = _fixture_offer(client, user_factory, fake_redis, "MaskCard")

    # The PWA consumes JSON numeric coords/string text/boolean done directly;
    # Kotlin's parser optDouble/optString/optBoolean sees those same keys.
    offered = _poll(client, driver)
    assert offered["role"] == "driver" and offered["status"] == "offered"
    assert offered["stops"] == COARSE
    for stop in offered["stops"]:
        assert set(stop) == {"lat", "lng", "text", "done"}
        assert type(stop["lat"]) in (int, float) and type(stop["lng"]) in (int, float)
        assert type(stop["text"]) is str and type(stop["done"]) is bool
    assert _read(client, oid, driver)["stops"] == COARSE
    assert _read(client, oid, passenger)["stops"] == EXACT

    saved = client.post(_route(oid, "accept/v2"), headers=driver["auth"],
                        json={"offer_card_precondition": offered["offer_card_precondition"]})
    assert saved.status_code == 200, saved.text
    assert saved.json()["stops"] == EXACT
    assert _read(client, oid, driver)["stops"] == EXACT
    assert _read(client, oid, passenger)["stops"] == EXACT


def test_response_only_former_offer_actor_does_not_inherit_passenger_exact_stops(
        client, user_factory, fake_redis):
    driver, passenger, oid = _fixture_offer(client, user_factory, fake_redis, "MaskFormer")
    outsider = _driver_online(client, user_factory, "MaskFormerOther")
    assert client.get(f"/instant/orders/{oid}", headers=outsider["auth"]).status_code == 403
    assert _poll(client, outsider) is None
    retired = client.post(_route(oid, "decline"), headers=driver["auth"])
    assert retired.status_code == 200, retired.text
    assert retired.json()["status"] == "expired"  # no replacement driver in GEO
    assert retired.json()["stops"] == COARSE
    assert client.get(f"/instant/orders/{oid}", headers=driver["auth"]).status_code == 403
    assert _read(client, oid, passenger)["stops"] == EXACT


def test_coarse_stop_view_does_not_weaken_exact_precondition(client, user_factory, fake_redis, monkeypatch):
    driver, passenger, oid = _fixture_offer(client, user_factory, fake_redis, "MaskTag")
    before = _poll(client, driver)
    edited_points = [deepcopy(SECOND)]
    edited_points[0].update(lat=54.76832, text="SYNTHETIC_STOP_B_OTHER 917")

    # Real route writer keeps the first completed stop. The changed unvisited
    # stop remains in the same coarse grid cell; its precise terms still change.
    monkeypatch.setattr(isv, "destination_quote", lambda s, o, d, **kw: _price(isv.order_ride_price(o), o))
    edited = client.post(_route(oid, "waypoints"), headers=passenger["auth"], json={"waypoints": edited_points})
    assert edited.status_code == 200, edited.text
    assert edited.json()["order"]["stops"] == [FIRST, edited_points[0]]
    after = _poll(client, driver)
    assert before["stops"] == after["stops"] == COARSE
    assert before["offer_card_precondition"] != after["offer_card_precondition"]
    old = client.post(_route(oid, "accept/v2"), headers=driver["auth"],
                      json={"offer_card_precondition": before["offer_card_precondition"]})
    assert old.status_code == 409 and old.json()["detail"]["code"] == "taxi_offer_changed"
    new = client.post(_route(oid, "accept/v2"), headers=driver["auth"],
                      json={"offer_card_precondition": after["offer_card_precondition"]})
    assert new.status_code == 200, new.text
    assert new.json()["stops"] == [FIRST, edited_points[0]]


def test_existing_cors_supports_driver_poll_and_protected_json(client):
    origin = "https://synthetic-browser.invalid"
    for endpoint, method, headers in (
        ("/instant/driver/offer", "GET", "authorization"),
        ("/instant/orders/1/accept/v2", "POST", "authorization, content-type"),
    ):
        response = client.options(endpoint, headers={
            "origin": origin, "access-control-request-method": method,
            "access-control-request-headers": headers,
        })
        assert response.status_code == 200
        assert response.headers["access-control-allow-origin"] in ("*", origin)


def test_only_exact_stop_data_changes_the_tag_even_when_all_public_masks_match(client, user_factory, fake_redis):
    driver, _, oid = _fixture_offer(client, user_factory, fake_redis, "MaskDigest")
    before = _poll(client, driver)
    changes = [deepcopy(FIRST), {**SECOND, "lat": 54.76832, "text": "SYNTHETIC_OTHER_TEXT"}]
    # Synthetic witness changes only server raw waypoint data; no incidental
    # fare, route version, expiry or timestamp difference can explain the tag.
    with Session(engine) as s:
        order = s.get(InstantOrder, oid)
        order.waypoints_json = isv.dump_waypoints(changes)
        s.commit()
    after = _poll(client, driver)
    assert before["stops"] == after["stops"] == COARSE
    assert before["offer_card_precondition"] != after["offer_card_precondition"]
