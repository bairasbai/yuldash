"""Opt-in stop provenance, mounted roles, stale command and legacy edit boundaries."""
from datetime import timedelta
from types import SimpleNamespace
from uuid import uuid4

import pytest
from sqlmodel import Session
from test_auth_session_logout import _bearer, _issue
from test_waypoints import STOP_A, STOP_B, _make_order

from app import instant_service
from app.config import settings
from app.db import engine
from app.models import InstantOrder, UserRole
from app.models import InstantOrderStatus as S
from app.timeutil import utcnow


def point(coords, label):
    return {"lat": coords[0], "lng": coords[1], "text": label}


A = point(STOP_A, "Synthetic A")
B = point(STOP_B, "Synthetic B")
C = {**B, "text": "Synthetic C"}


@pytest.fixture
def scene(client, user_factory, monkeypatch):
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", True)
    monkeypatch.setattr(settings, "taxi_external_pricing_enabled", False)
    monkeypatch.setattr(settings, "redis_url", "")
    passenger = user_factory("Synthetic Stop Passenger")
    driver = user_factory("Synthetic Stop Driver", role=UserRole.driver)
    other = user_factory("Synthetic Stop Stranger")
    p, d, x = [_bearer(_issue(actor["id"])["access_token"]) for actor in (passenger, driver, other)]
    quotes, notices = [], []

    def quote(session, order, destination, *, waypoints_override):
        quotes.append(order.id)
        return {"ok": True, "price": order.price_estimate, "base_price": order.price_estimate,
                "distance_km": order.distance_km, "eta_min": order.eta_min}

    def notice(session, order, estimate):
        notices.append(order.id)

    monkeypatch.setattr(instant_service, "destination_quote", quote)
    monkeypatch.setattr(instant_service, "notify_driver_destination", notice)

    def new(*points):
        oid = _make_order(passenger["id"], driver["id"], waiting_fee_kop=700,
                          waypoints_json=instant_service.dump_waypoints(list(points)))
        return oid

    def request(oid, path, auth, *, body=None, status=200, method="POST"):
        r = client.request(method, f"/instant/orders/{oid}{path}", headers=auth, json=body)
        assert r.status_code == status
        return r.json()

    def snapshot(oid):
        with Session(engine) as session:
            row = session.get(InstantOrder, oid)
            assert row is not None
            return {name: getattr(row, name) for name in (
                "status", "waypoints_json", "stop_route_revision", "stop_wait_revision", "stop_wait_kind",
                "stop_active_waypoint_id", "stop_started_at", "waiting_fee_kop", "price_estimate",
                "price_final", "ride_price", "promo_discount_kop", "distance_km", "eta_min",
                "destination_changed_at", "destination_ack_at",
            )}

    return SimpleNamespace(new=new, request=request, snapshot=snapshot, p=p, d=d, x=x,
                           quotes=quotes, notices=notices)


def prepare(scene, oid):
    return scene.request(oid, "/waypoints/v1/prepare", scene.d)["order"]


def start(ready, index=0):
    return {"action": "start_planned", "waypoint_id": ready["stops"][index]["waypoint_id"],
            "route_revision": ready["stop_control"]["route_revision"],
            "wait_revision": ready["stop_control"]["wait_revision"]}


def test_opt_in_planned_single_identity_and_unchanged_timer(scene, monkeypatch):
    oid = scene.new(A, B)
    before = scene.snapshot(oid)
    scene.request(oid, "/waypoints/v1/prepare", scene.p, status=404)
    scene.request(oid, "/stop/v1", scene.x,
                  body={"action": "start_unplanned", "wait_revision": None}, status=404)
    ready = prepare(scene, oid)
    assert prepare(scene, oid) == ready
    marked = scene.snapshot(oid)
    assert not scene.quotes and not scene.notices
    assert {k for k in marked if marked[k] != before[k]} == {"waypoints_json", "stop_route_revision"}

    command = start(ready)
    active = scene.request(oid, "/stop/v1", scene.d, body=command)["order"]["stop_control"]["active_wait"]
    assert active["kind"] == "planned" and active["waypoint_id"] == command["waypoint_id"]
    standing = scene.snapshot(oid)
    for edit in ([B], [{**A, "text": "Edited"}, B], [A, A, B], [{**A, "lat": B["lat"]}, B]):
        rejected = scene.request(oid, "/waypoints", scene.p, body={"waypoints": edit}, status=409)
        assert rejected["detail"]["code"] == "taxi_active_waypoint_protected"
    assert scene.snapshot(oid) == standing and not scene.quotes and not scene.notices

    # Turn off new commands, but keep the protection for a durable active episode.
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", False)
    scene.request(oid, "/waypoints", scene.p, body={"waypoints": [B]}, status=409)
    draining = scene.request(oid, "", scene.p, method="GET")
    assert draining["stop_control"]["admission_enabled"] is False
    assert draining["stop_control"]["active_wait"] == active
    assert draining["stops"] == ready["stops"]
    scene.request(oid, "/waypoints/v1/prepare", scene.d, status=404)
    scene.request(oid, "/stop/v1", scene.d, body=command, status=404)
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", True)
    changed = scene.request(oid, "/waypoints", scene.p, body={"waypoints": [A]})["order"]
    assert changed["stops"] == [{**A, "done": False, "waypoint_id": command["waypoint_id"]}]
    assert changed["stop_control"]["active_wait"] == active
    assert changed["stop_control"]["route_revision"] != command["route_revision"]
    after = scene.snapshot(oid)
    for field in ("stop_started_at", "stop_wait_revision", "waiting_fee_kop", "price_estimate"):
        assert after[field] == standing[field]
    assert scene.quotes == scene.notices == [oid]
    assert scene.request(oid, "/stop/v1", scene.d,
                         body={"action": "resume", "wait_id": active["wait_id"]})["standing"] is False
    assert scene.request(oid, "/stop/v1", scene.d, body=command, status=409)["detail"]["code"] == "taxi_wait_stale"


def test_wait_generation_prevents_delayed_starts_and_stale_resumes(scene):
    oid = scene.new()
    old = {"action": "start_unplanned", "wait_revision": None}
    opened = scene.request(oid, "/stop/v1", scene.d, body=old)["order"]["stop_control"]
    assert opened["active_wait"] == {"kind": "unplanned", "wait_id": opened["wait_revision"], "waypoint_id": None}
    scene.request(oid, "/stop/v1", scene.d, body=old, status=409)
    resume = {"action": "resume", "wait_id": opened["wait_revision"]}
    closed = scene.request(oid, "/stop/v1", scene.d, body=resume)["order"]["stop_control"]
    assert closed["active_wait"] is None and closed["wait_revision"] != opened["wait_revision"]
    assert scene.request(oid, "/stop/v1", scene.d, body=resume)["order"]["stop_control"] == closed
    scene.request(oid, "/stop/v1", scene.d, body=old, status=409)
    now = {**old, "wait_revision": closed["wait_revision"]}
    again = scene.request(oid, "/stop/v1", scene.d, body=now)["order"]["stop_control"]
    scene.request(oid, "/stop/v1", scene.d, body=resume, status=409)
    assert scene.request(oid, "", scene.p, method="GET")["stop_control"] == again
    assert scene.request(oid, "/waypoints", scene.p, body={"waypoints": [A]})["order"]["standing"] is True
    done = scene.request(oid, "/done", scene.d)
    assert done["status"] == "done" and done["standing"] is False
    final = scene.snapshot(oid)
    assert final["status"] == S.done and final["stop_wait_kind"] is None
    assert final["stop_wait_revision"] != again["wait_revision"]


def test_duplicate_points_get_independent_ids_and_foreign_or_stale_point_fails(scene):
    one, two = scene.new(A, A), scene.new(A)
    ready, other = prepare(scene, one), prepare(scene, two)
    one_ids = [point["waypoint_id"] for point in ready["stops"]]
    assert len(set(one_ids + [other["stops"][0]["waypoint_id"]])) == 3
    command = start(ready, index=1)
    scene.request(one, "/stop/v1", scene.d, body={**command, "waypoint_id": str(uuid4())}, status=409)
    scene.request(two, "/stop/v1", scene.d, body={**command,
                  "route_revision": other["stop_control"]["route_revision"]}, status=409)
    assert scene.snapshot(one)["stop_started_at"] is None and scene.snapshot(two)["stop_started_at"] is None
    active = scene.request(one, "/stop/v1", scene.d, body=command)["order"]["stop_control"]["active_wait"]
    assert active["waypoint_id"] == one_ids[1]
    scene.request(one, "/waypoints", scene.p, body={"waypoints": [A]}, status=409)
    assert not scene.quotes and not scene.notices


def test_legacy_unknown_is_never_backfilled_even_when_a_waypoint_exists_later(scene, monkeypatch):
    oid = scene.new()
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", False)
    old = scene.request(oid, "/stop", scene.d)
    assert old["standing"] is True and "stop_control" not in old["order"]
    scene.request(oid, "/stop/v1", scene.d,
                  body={"action": "start_unplanned", "wait_revision": None}, status=404)
    timer = scene.snapshot(oid)["stop_started_at"]
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", True)
    ready = prepare(scene, oid)
    assert ready["stop_control"]["active_wait"] == {"kind": "legacy_unknown", "wait_id": None, "waypoint_id": None}
    added = scene.request(oid, "/waypoints", scene.p, body={"waypoints": [A]})["order"]
    assert added["standing"] is True and added["stop_control"]["active_wait"] == ready["stop_control"]["active_wait"]
    assert scene.snapshot(oid)["stop_started_at"] == timer
    assert scene.request(oid, "/stop", scene.d)["standing"] is False


def test_intervening_wait_cannot_commit_a_previously_priced_legacy_edit(scene, monkeypatch):
    oid = scene.new(A, B)
    ready = prepare(scene, oid)
    original = instant_service.destination_quote

    def interleave(*args, **kwargs):
        result = original(*args, **kwargs)
        scene.request(oid, "/stop/v1", scene.d, body=start(ready))
        return result

    monkeypatch.setattr(instant_service, "destination_quote", interleave)
    denied = scene.request(oid, "/waypoints", scene.p, body={"waypoints": [B]}, status=409)
    assert denied["detail"]["code"] == "taxi_waypoints_stale"
    observed = scene.request(oid, "", scene.d, method="GET")
    assert observed["stops"] == ready["stops"] and observed["standing"] is True
    assert scene.quotes == [oid] and not scene.notices


def test_new_waypoint_revision_rejects_stale_start_without_wait_change(scene):
    oid = scene.new(A, B)
    ready = prepare(scene, oid)
    command = start(ready)
    edited = scene.request(oid, "/waypoints", scene.p, body={"waypoints": [A]})["order"]
    assert edited["stops"][0]["waypoint_id"] == command["waypoint_id"]
    assert edited["stop_control"]["route_revision"] != command["route_revision"]
    idle = scene.snapshot(oid)
    stale = scene.request(oid, "/stop/v1", scene.d, body=command, status=409)
    assert stale["detail"]["code"] == "taxi_waypoints_stale"
    assert scene.snapshot(oid) == idle
    assert scene.request(oid, "/stop/v1", scene.d, body=start(edited))["standing"] is True


def test_off_resume_drains_only_exact_active_wait_and_hides_capability_then(scene, monkeypatch):
    oid = scene.new(A)
    active = scene.request(oid, "/stop/v1", scene.d,
                           body={"action": "start_unplanned", "wait_revision": None})
    wait_id = active["order"]["stop_control"]["active_wait"]["wait_id"]
    body = {"action": "resume", "wait_id": wait_id}
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", False)
    standing = scene.snapshot(oid)
    scene.request(oid, "/stop/v1", scene.p, body=body, status=404)
    scene.request(oid, "/stop/v1", scene.d, body={**body, "wait_id": str(uuid4())}, status=409)
    assert scene.snapshot(oid) == standing
    drained = scene.request(oid, "/stop/v1", scene.d, body=body)
    assert drained["standing"] is False and "stop_control" not in drained["order"]
    idle = scene.snapshot(oid)
    assert idle["stop_wait_revision"] != wait_id and idle["stop_wait_kind"] is None
    scene.request(oid, "/stop/v1", scene.d, body=body, status=404)
    scene.request(oid, "/stop/v1", scene.d,
                  body={"action": "start_unplanned", "wait_revision": idle["stop_wait_revision"]}, status=404)
    assert scene.snapshot(oid) == idle
    assert not scene.quotes and not scene.notices


@pytest.mark.parametrize("tail", ([C], []))
def test_duplicate_occurrence_prefix_retains_ids_independent_of_tail(scene, monkeypatch, tail):
    clock = utcnow()
    monkeypatch.setattr(instant_service, "utcnow", lambda: clock)
    oid = scene.new(A, A, B)
    ready = prepare(scene, oid)
    command = start(ready, index=1)
    active = scene.request(oid, "/stop/v1", scene.d, body=command)["order"]["stop_control"]["active_wait"]
    previous = scene.snapshot(oid)
    changed = scene.request(oid, "/waypoints", scene.p, body={"waypoints": [A, A, *tail]})["order"]
    assert changed["stops"][:2] == ready["stops"][:2]
    assert changed["stops"][1]["waypoint_id"] == active["waypoint_id"]
    assert changed["stop_control"]["active_wait"] == active
    assert changed["stop_control"]["route_revision"] != ready["stop_control"]["route_revision"]
    after = scene.snapshot(oid)
    assert (after["stop_started_at"], after["stop_wait_revision"], after["waiting_fee_kop"]) == (
        previous["stop_started_at"], previous["stop_wait_revision"], previous["waiting_fee_kop"],
    )
    clock += timedelta(seconds=instant_service.DESTINATION_MIN_GAP_SEC + 1)
    for ambiguous in ([A, *tail], [A, A, A], [A, B, A]):
        response = scene.request(oid, "/waypoints", scene.p, body={"waypoints": ambiguous}, status=409)
        assert response["detail"]["code"] == "taxi_active_waypoint_protected"
    assert scene.snapshot(oid) == after and scene.quotes == scene.notices == [oid]


@pytest.mark.parametrize("kind", ("planned", "unplanned"))
def test_legacy_toggle_cannot_end_explicit_wait_on_or_off(scene, monkeypatch, kind):
    oid = scene.new(A)
    prepared = prepare(scene, oid)
    command = start(prepared) if kind == "planned" else {"action": "start_unplanned", "wait_revision": None}
    active = scene.request(oid, "/stop/v1", scene.d, body=command)["order"]["stop_control"]["active_wait"]
    previous = scene.snapshot(oid)
    for setting in (True, False):
        monkeypatch.setattr(settings, "taxi_stop_control_enabled", setting)
        rejected = scene.request(oid, "/stop", scene.d, status=409)
        assert rejected["detail"]["code"] == "taxi_explicit_wait_required"
        assert scene.snapshot(oid) == previous
    assert scene.request(oid, "/stop/v1", scene.d,
                         body={"action": "resume", "wait_id": active["wait_id"]})["standing"] is False


def test_same_label_different_locations_require_current_selected_identity(scene, monkeypatch):
    first, second = point(STOP_A, "Аптека"), point(STOP_B, "Аптека")
    oid = scene.new(first, second)
    original = prepare(scene, oid)
    stale = start(original)
    # Passenger edit wins: the remaining identically named location must not
    # be substituted for the removed opaque ID, even with its new revision.
    remaining = scene.request(oid, "/waypoints", scene.p, body={"waypoints": [second]})["order"]
    for revision in (stale["route_revision"], remaining["stop_control"]["route_revision"]):
        rejected = scene.request(oid, "/stop/v1", scene.d, body={**stale, "route_revision": revision}, status=409)
        assert rejected["detail"]["code"] == "taxi_waypoints_stale"
    assert scene.snapshot(oid)["stop_started_at"] is None

    # Advance beyond the existing route-edit cooldown without changing product behavior.
    # Driver command wins next: selected location remains, but its sibling can be added.
    clock = utcnow() + timedelta(seconds=instant_service.DESTINATION_MIN_GAP_SEC + 1)
    monkeypatch.setattr(instant_service, "utcnow", lambda: clock)
    monkeypatch.setattr(settings, "wait_free_minutes", 3)
    monkeypatch.setattr(settings, "wait_fee_rub_per_min", 5)
    monkeypatch.setattr(settings, "wait_fee_cap_rub", 100)
    selected = start(remaining)
    active = scene.request(oid, "/stop/v1", scene.d, body=selected)["order"]["stop_control"]["active_wait"]
    timing = scene.snapshot(oid)
    for edit in ([], [first]):
        rejected = scene.request(oid, "/waypoints", scene.p, body={"waypoints": edit}, status=409)
        assert rejected["detail"]["code"] == "taxi_active_waypoint_protected"
    scene.request(oid, "/waypoints", scene.p, body={"waypoints": [second, first]})
    for auth in (scene.d, scene.p):
        refreshed = scene.request(oid, "", auth, method="GET")
        assert refreshed["stop_control"]["active_wait"] == active
    assert scene.snapshot(oid)["stop_started_at"] == timing["stop_started_at"]
    clock += timedelta(minutes=5, seconds=30)
    command = {"action": "resume", "wait_id": active["wait_id"]}
    for _ in range(2):
        assert scene.request(oid, "/stop/v1", scene.d, body=command)["standing"] is False
        assert scene.snapshot(oid)["waiting_fee_kop"] == 1700
    assert scene.quotes == scene.notices == [oid, oid]
