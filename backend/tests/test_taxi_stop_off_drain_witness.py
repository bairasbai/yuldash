"""Mounted API evidence for a planned OFF drain, not browser or live-server coverage."""
from datetime import timedelta

from app import instant_service
from app.config import settings
from app.timeutil import utcnow
from fastapi.testclient import TestClient
from test_taxi_stop_control import A, B, C, edit_body, keep, prepare, scene, start  # noqa: F401


def test_off_drain_keeps_second_duplicate_bound_and_accrues_once(scene, client, monkeypatch):
    clock = utcnow()
    monkeypatch.setattr(instant_service, "utcnow", lambda: clock)
    monkeypatch.setattr(settings, "wait_free_minutes", 3)
    monkeypatch.setattr(settings, "wait_fee_rub_per_min", 5)
    monkeypatch.setattr(settings, "wait_fee_cap_rub", 100)
    oid = scene.new(A, A, B)
    ready = prepare(scene, oid)
    first, selected, other = ready["stops"]
    assert len({w["waypoint_id"] for w in (first, selected, other)}) == 3
    assert {**first, "waypoint_id": selected["waypoint_id"]} == selected
    waiting = scene.request(oid, "/stop/v1", scene.d, body=start(ready, 1))["order"]
    active = waiting["stop_control"]["active_wait"]
    assert active["kind"] == "planned" and active["waypoint_id"] == selected["waypoint_id"]
    before = scene.snapshot(oid)
    assert before["stop_started_at"] == clock
    monkeypatch.setattr(settings, "taxi_stop_control_enabled", False)

    # Two new independently authenticated HTTP client instances, same mounted app/DB.
    # Deliberately do not re-enter application lifespan, already owned by `client`.
    path = f"/instant/orders/{oid}"
    driver, passenger = TestClient(client.app), TestClient(client.app)
    try:
        def get(actor, auth, role):
            response = actor.get(path, headers=auth)
            assert response.status_code == 200
            result = response.json()
            assert result["role"] == role
            return result

        resumed_driver = get(driver, scene.d, "driver")
        resumed_passenger = get(passenger, scene.p, "passenger")
        for order in (resumed_driver, resumed_passenger):
            ctl = order["stop_control"]
            assert not ctl["admission_enabled"] and ctl["passenger_edit_v1"] and ctl["active_wait"] == active
        assert driver.post(path + "/stop/v1", headers=scene.d, json={
            "action": "start_unplanned", "wait_revision": active["wait_id"],
        }).status_code == 404

        request = edit_body(resumed_passenger, keep(first["waypoint_id"]), keep(selected["waypoint_id"]),
                            {"kind": "new", **C})
        changed = passenger.post(path + "/waypoints/v1", headers=scene.p, json=request)
        assert changed.status_code == 200 and changed.json()["applied"] is True
        updated = get(passenger, scene.p, "passenger")
        assert updated["stops"][:2] == [first, selected]
        replacement = updated["stops"][2]
        assert replacement["waypoint_id"] not in {w["waypoint_id"] for w in (first, selected, other)}
        assert {k: replacement[k] for k in C} == C
        assert updated["stop_control"]["route_revision"] != waiting["stop_control"]["route_revision"]
        assert updated["stop_control"]["active_wait"] == active
        assert get(driver, scene.d, "driver")["stop_control"]["active_wait"] == active
        during = scene.snapshot(oid)
        for field in ("stop_started_at", "stop_wait_revision", "waiting_fee_kop"):
            assert during[field] == before[field]

        clock += timedelta(minutes=5, seconds=30)
        exact = {"action": "resume", "wait_id": active["wait_id"]}
        result = driver.post(path + "/stop/v1", headers=scene.d, json=exact)
        assert result.status_code == 200 and result.json()["standing"] is False
        after = scene.snapshot(oid)
        assert after["stop_started_at"] is None
        assert after["waiting_fee_kop"] == before["waiting_fee_kop"] + 1000
        assert driver.post(path + "/stop/v1", headers=scene.d, json=exact).status_code == 404
        assert scene.snapshot(oid) == after
        for fresh in (get(driver, scene.d, "driver"), get(passenger, scene.p, "passenger")):
            assert fresh["standing"] is False and "stop_control" not in fresh
        assert passenger.post(path + "/waypoints/v1", headers=scene.p, json=request).status_code == 404
        assert scene.quotes == scene.notices == [oid]
    finally:
        driver.close()
        passenger.close()
