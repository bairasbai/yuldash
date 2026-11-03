"""MOCK: только вымышленные координаты/локальные id; ни одного настоящего solver/HTTP-запроса."""
import asyncio
import json
from copy import deepcopy
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from app.vroom import MAX_RESPONSE_BYTES, Snapshot, VroomAdapter, build_request


@pytest.fixture
def snapshot():
    stop = {"point": {"lon": 10.0, "lat": 20.0}, "time_window": (0, 120), "service_seconds": 5}
    return Snapshot(
        snapshot_id="synthetic_revision_1",
        deliveries=tuple({
            "id": i, "delivery_type": "courier", "status": "created", "courier_id": None,
            "pickup": deepcopy(stop),
            "delivery": {**deepcopy(stop), "point": {"lon": 10.1, "lat": 20.1}},
            "units": 1, "allowed_vehicle_ids": (v,),
        } for i, v in ((10, 101), (20, 102))),
        vehicles=tuple({
            "id": i, "start": {"lon": 9.0, "lat": 19.0}, "end": {"lon": 11.0, "lat": 21.0},
            "time_window": (0, 180), "capacity": 1, "max_deliveries": 2, "active_parcel_ids": (),
        } for i in (101, 102)),
    )


def _step(kind, arrival, task_id=None):
    s = {"type": kind, "arrival": arrival, "waiting_time": 0, "setup": 0,
         "service": 5 if task_id else 0, "violations": []}
    if task_id is not None:
        s["id"] = task_id
    return s


@pytest.fixture
def solution():
    return {
        "code": 0, "summary": {"routes": 2, "unassigned": 0, "violations": []},
        "unassigned": [],
        "routes": [{
            "vehicle": v, "violations": [],
            "steps": [_step("start", 0), _step("pickup", 10, p),
                      _step("delivery", 30, p + 1), _step("end", 60)],
        } for v, p in ((101, 1), (102, 3))],
    }


def _run(snapshot, solution):
    transport = AsyncMock(return_value=json.dumps(solution).encode())
    return asyncio.run(VroomAdapter(enabled=True, transport=transport).preview(snapshot))


def test_default_disabled_never_calls_transport(snapshot):
    transport = AsyncMock(side_effect=AssertionError("must stay offline"))
    result = asyncio.run(VroomAdapter(transport=transport).preview(snapshot))
    assert result.status == "disabled" and result.routes == ()
    transport.assert_not_awaited()
    assert asyncio.run(VroomAdapter(enabled=True).preview(snapshot)).status == "unavailable"


def test_request_and_valid_multi_vehicle_preview(snapshot, solution):
    original = snapshot.model_dump()
    request = build_request(snapshot)
    assert set(request) == {"shipments", "vehicles"}
    assert request["shipments"][0] == {
        "amount": [1], "skills": [1],
        "pickup": {"id": 1, "location": [10.0, 20.0], "time_windows": [[0, 120]], "service": 5},
        "delivery": {"id": 2, "location": [10.1, 20.1], "time_windows": [[0, 120]], "service": 5},
    }
    assert request["vehicles"][0] == {
        "id": 101, "profile": "car", "start": [9.0, 19.0], "end": [11.0, 21.0],
        "time_window": [0, 180], "capacity": [1], "max_tasks": 4, "skills": [1],
    }
    assert request["vehicles"][1]["skills"] == [2]
    transport = AsyncMock(return_value=json.dumps(solution).encode())
    result = asyncio.run(VroomAdapter(enabled=True, transport=transport).preview(snapshot))
    transport.assert_awaited_once_with(request)
    assert result.status == "preview" and result.snapshot_id == snapshot.snapshot_id
    assert [(r.vehicle_id, [(s.delivery_id, s.kind) for s in r.stops]) for r in result.routes] == [
        (101, [(10, "pickup"), (10, "delivery")]), (102, [(20, "pickup"), (20, "delivery")]),
    ]
    assert result.routes[0].stops[1].estimated_arrival_seconds == 30
    assert result.eta_source == "solver_estimate" and result.optimality_proven is False
    assert result.unassigned_delivery_ids == () and snapshot.model_dump() == original
    assert "synthetic_revision" not in json.dumps(request)


def test_nested_input_is_immutable_and_build_is_detached(snapshot):
    with pytest.raises(ValidationError):
        snapshot.deliveries[0].pickup.point.lon = 99.0
    request = build_request(snapshot)
    request["shipments"][0]["pickup"]["location"][0] = 99.0
    request["vehicles"][0]["skills"].clear()
    assert build_request(snapshot)["shipments"][0]["pickup"]["location"] == [10.0, 20.0]
    assert build_request(snapshot)["vehicles"][0]["skills"] == [1]


@pytest.mark.parametrize("field,value", [
    ("delivery_type", "poputka"), ("delivery_type", "buy_bring"),
    ("status", "accepted"), ("status", "in_transit"), ("status", "returning"),
    ("status", "canceled"), ("status", "delivered"), ("courier_id", 123),
    ("units", 0), ("units", -1), ("units", True), ("units", "1"),
    ("allowed_vehicle_ids", (999,)), ("allowed_vehicle_ids", ()),
    ("allowed_vehicle_ids", (101, 101)), ("id", True), ("receiver_phone", "+00000000000"),
])
def test_unsafe_deliveries_reject_entire_snapshot(snapshot, field, value):
    data = snapshot.model_dump()
    data["deliveries"][0][field] = value
    with pytest.raises(ValidationError):
        Snapshot.model_validate(data)


@pytest.mark.parametrize("field,value", [
    ("active_parcel_ids", (123,)), ("capacity", 0), ("capacity", True),
    ("max_deliveries", 0), ("max_deliveries", 51),
    ("time_window", (80, 10)), ("time_window", (-1, 180)), ("time_window", (0, 86_401)),
])
def test_unsafe_vehicles_rejected(snapshot, field, value):
    data = snapshot.model_dump()
    data["vehicles"][0][field] = value
    with pytest.raises(ValidationError):
        Snapshot.model_validate(data)


@pytest.mark.parametrize("field,value", [("lon", 181.0), ("lat", -91.0), ("lon", float("nan")),
                                        ("lat", float("inf")), ("lon", "10"), ("lat", True)])
def test_invalid_coordinates_rejected(snapshot, field, value):
    data = snapshot.model_dump()
    data["deliveries"][0]["pickup"]["point"][field] = value
    with pytest.raises(ValidationError):
        Snapshot.model_validate(data)


@pytest.mark.parametrize("mutation", [
    lambda d: d.update(deliveries=()),
    lambda d: d.update(vehicles=()),
    lambda d: d.update(deliveries=d["deliveries"] * 51),
    lambda d: d.update(vehicles=d["vehicles"] * 11),
    lambda d: d.update(snapshot_id="x" * 129),
    lambda d: d.update(snapshot_id=""),
    lambda d: d["deliveries"][1].update(id=10),
    lambda d: d["vehicles"][1].update(id=101),
    lambda d: d["deliveries"][0]["pickup"].update(time_window=(30, 10)),
])
def test_invalid_batch_rejected(snapshot, mutation):
    data = snapshot.model_dump()
    mutation(data)
    with pytest.raises(ValidationError):
        Snapshot.model_validate(data)


def test_partial_and_all_unassigned_are_explicit(snapshot, solution):
    solution["routes"].pop()
    solution["summary"].update(routes=1, unassigned=2)
    solution["unassigned"] = [{"id": 3, "type": "pickup"}, {"id": 4, "type": "delivery"}]
    result = _run(snapshot, solution)
    assert result.status == "preview" and result.unassigned_delivery_ids == (20,)
    solution["routes"].clear()
    solution["summary"].update(routes=0, unassigned=4)
    solution["unassigned"] += [{"id": 1, "type": "pickup"}, {"id": 2, "type": "delivery"}]
    result = _run(snapshot, solution)
    assert result.status == "preview" and result.routes == () and result.unassigned_delivery_ids == (10, 20)


@pytest.mark.parametrize("mutation", [
    lambda s: s.update(code=1),
    lambda s: s.update(code=False),
    lambda s: s["summary"].update(routes=1),
    lambda s: s["summary"].update(unassigned=1),
    lambda s: s["summary"].update(violations=[{"cause": "load"}]),
    lambda s: s["routes"][0].update(violations=[{"cause": "delay"}]),
    lambda s: s["routes"][0]["steps"][1].update(violations=[{"cause": "skills"}]),
    lambda s: s["routes"][0].update(vehicle=999),
    lambda s: s["routes"][0].update(vehicle=102),
    lambda s: s["routes"][0]["steps"][1].update(id=999),
    lambda s: s["routes"][0]["steps"][1].update(id=True),
    lambda s: s["routes"][0]["steps"][2].update(id=1),
    lambda s: s["routes"][0]["steps"][1].update(type="delivery", id=2),
    lambda s: s["routes"][0]["steps"][1].update(type="job"),
    lambda s: s["routes"][0]["steps"][1].update(arrival=-1),
    lambda s: s["routes"][0]["steps"][1].update(arrival="10"),
    lambda s: s["routes"][0]["steps"][1].update(arrival=121),
    lambda s: s["routes"][0]["steps"][1].update(service=0),
    lambda s: s["routes"][0]["steps"][1].update(setup=1),
    lambda s: s["routes"][0]["steps"][1].update(setup=False),
    lambda s: s["routes"][0]["steps"][1].update(waiting_time=121),
    lambda s: s["routes"][0]["steps"][2].update(arrival=11),
    lambda s: s["routes"][0]["steps"][-1].update(arrival=181),
    lambda s: s["routes"][0]["steps"][0].update(type="end"),
    lambda s: s["routes"][0]["steps"][-1].update(type="start"),
    lambda s: s["routes"][0]["steps"][0].update(id=1),
    lambda s: s["routes"][0]["steps"][-1].update(service=1),
    lambda s: s["routes"][0]["steps"].pop(1),
    lambda s: s["routes"].clear(),
    lambda s: s["unassigned"].append({"id": 1, "type": "pickup"}),
])
def test_untrusted_response_cannot_produce_a_partial_plan(snapshot, solution, mutation):
    mutation(solution)
    result = _run(snapshot, solution)
    assert result.status == "invalid_response" and result.routes == () and result.unassigned_delivery_ids == ()


def test_split_shipment_and_missing_or_duplicate_unassigned_rejected(snapshot, solution):
    data = snapshot.model_dump()
    for d in data["deliveries"]:
        d["allowed_vehicle_ids"] = (101, 102)
    snapshot = Snapshot.model_validate(data)
    solution["routes"][0]["steps"][2], solution["routes"][1]["steps"][2] = (
        solution["routes"][1]["steps"][2], solution["routes"][0]["steps"][2])
    assert _run(snapshot, solution).status == "invalid_response"
    solution["routes"] = []
    solution["summary"].update(routes=0, unassigned=4)
    tasks = [{"id": i, "type": kind} for i, kind in ((1, "pickup"), (2, "delivery"),
                                                    (3, "pickup"), (4, "delivery"))]
    for broken in (tasks[:-1], tasks[:3] + [tasks[2]], tasks[:3] + [{"id": 999, "type": "delivery"}],
                   tasks[:3] + [{"id": 4, "type": "pickup"}]):
        solution["unassigned"] = broken
        assert _run(snapshot, solution).status == "invalid_response"


def test_peak_capacity_and_total_claim_count_are_different(snapshot, solution):
    data = snapshot.model_dump()
    data["deliveries"][1]["allowed_vehicle_ids"] = (101,)
    snapshot = Snapshot.model_validate(data)
    solution["routes"] = [{"vehicle": 101, "violations": [], "steps": [
        _step("start", 0), _step("pickup", 10, 1), _step("delivery", 20, 2),
        _step("pickup", 30, 3), _step("delivery", 40, 4), _step("end", 60),
    ]}]
    solution["summary"]["routes"] = 1
    assert _run(snapshot, solution).status == "preview"  # две доставки подряд, peak load=1
    data["vehicles"][0]["max_deliveries"] = 1
    assert _run(Snapshot.model_validate(data), solution).status == "invalid_response"
    data["vehicles"][0]["max_deliveries"] = 2
    steps = solution["routes"][0]["steps"]
    steps[2], steps[3] = _step("pickup", 20, 3), _step("delivery", 30, 2)
    assert _run(snapshot, solution).status == "invalid_response"  # две посылки одновременно > capacity
    data["vehicles"][0]["capacity"] = 2
    assert _run(Snapshot.model_validate(data), solution).status == "preview"


def test_waiting_is_included_when_checking_time_windows(snapshot, solution):
    data = snapshot.model_dump()
    data["deliveries"][0]["pickup"]["time_window"] = (15, 20)
    snapshot = Snapshot.model_validate(data)
    assert _run(snapshot, solution).status == "invalid_response"
    solution["routes"][0]["steps"][1]["waiting_time"] = 5
    assert _run(snapshot, solution).status == "preview"


@pytest.mark.parametrize("raw", [
    b"", b"not json", b"\xff", b"[]", b"{}", b'{"code":0,"code":1}',
    b'{"code":NaN}', b'{"code":Infinity}', b" " * (MAX_RESPONSE_BYTES + 1),
    b"[" * 2000 + b"]" * 2000, "not bytes",
])
def test_malformed_or_oversized_response_rejected(snapshot, raw):
    transport = AsyncMock(return_value=raw)
    result = asyncio.run(VroomAdapter(enabled=True, transport=transport).preview(snapshot))
    assert result.status == "invalid_response" and result.routes == ()


def test_unused_solver_fields_never_escape(snapshot, solution):
    solution["unused_field"] = "private"
    solution["routes"][0]["description"] = "private"
    solution["routes"][0]["steps"][1]["location"] = [10, 20]
    result = _run(snapshot, solution)
    assert result.status == "preview" and "private" not in repr(result) and "location" not in repr(result)


def test_transport_failure_hides_body_and_does_not_retry(snapshot, caplog):
    transport = AsyncMock(side_effect=OSError("private coordinates or phone"))
    result = asyncio.run(VroomAdapter(enabled=True, transport=transport).preview(snapshot))
    assert result.status == "unavailable" and result.routes == ()
    assert "private" not in repr(result) and "private" not in caplog.text
    transport.assert_awaited_once()


def test_timeout_cancels_transport(snapshot):
    async def scenario():
        canceled = asyncio.Event()

        async def transport(_):
            try:
                await asyncio.Event().wait()
            finally:
                canceled.set()

        result = await VroomAdapter(enabled=True, transport=transport, timeout_seconds=0.01).preview(snapshot)
        assert result.status == "unavailable" and result.routes == () and canceled.is_set()

    asyncio.run(scenario())


def test_caller_cancellation_propagates_to_transport(snapshot):
    async def scenario():
        started, canceled = asyncio.Event(), asyncio.Event()

        async def transport(_):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                canceled.set()

        task = asyncio.create_task(VroomAdapter(enabled=True, transport=transport).preview(snapshot))
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert canceled.is_set()

    asyncio.run(scenario())


@pytest.mark.parametrize("options", [{"enabled": "false"}, {"timeout_seconds": 0},
                                     {"timeout_seconds": float("nan")}, {"timeout_seconds": 11},
                                     {"timeout_seconds": True}])
def test_options_cannot_accidentally_enable_or_disable_timeout(options):
    with pytest.raises(ValueError):
        VroomAdapter(**options)
