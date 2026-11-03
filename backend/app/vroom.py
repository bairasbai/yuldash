"""Изолированный черновик VROOM 1.14: только внутренний предварительный расчёт.

Не подключён к API/worker/БД. Нет HTTP-клиента, адреса сервера или чтения settings.
Вход — доверенный снимок backend, НЕ тело клиентского запроса. См. docs/vroom-integration-draft.md.
"""
from __future__ import annotations

import asyncio
import json
import math
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

LocalId = Annotated[int, Field(strict=True, gt=0, le=2_147_483_647)]
Seconds = Annotated[int, Field(strict=True, ge=0, le=86_400)]
Units = Annotated[int, Field(strict=True, gt=0, le=1_000_000)]
Window = tuple[Seconds, Seconds]
MAX_RESPONSE_BYTES = 256 * 1024
Transport = Callable[[dict], Awaitable[bytes]]


class _Input(BaseModel):
    model_config = ConfigDict(frozen=True, strict=True, extra="forbid", hide_input_in_errors=True)


class Point(_Input):
    lon: float = Field(ge=-180, le=180, allow_inf_nan=False, repr=False)
    lat: float = Field(ge=-90, le=90, allow_inf_nan=False, repr=False)


class Stop(_Input):
    point: Point = Field(repr=False)
    time_window: Window
    service_seconds: Seconds

    @model_validator(mode="after")
    def ordered_window(self):
        if self.time_window[0] > self.time_window[1]:
            raise ValueError("invalid_time_window")
        return self


class Delivery(_Input):
    id: LocalId  # локальный псевдоним, не идентификатор пользователя или записи БД
    delivery_type: Literal["courier"]
    status: Literal["created"]
    courier_id: None  # явное подтверждение отсутствия назначения в снимке
    pickup: Stop
    delivery: Stop
    units: Units  # одна явно выбранная мера ёмкости, не выводится из small/medium/large
    allowed_vehicle_ids: tuple[LocalId, ...] = Field(min_length=1, max_length=10)


class Vehicle(_Input):
    id: LocalId
    start: Point = Field(repr=False)
    end: Point = Field(repr=False)
    time_window: Window
    capacity: Units
    max_deliveries: Annotated[int, Field(strict=True, ge=1, le=50)]
    active_parcel_ids: tuple[LocalId, ...] = Field(max_length=0)

    @model_validator(mode="after")
    def ordered_window(self):
        if self.time_window[0] > self.time_window[1]:
            raise ValueError("invalid_time_window")
        return self


class Snapshot(_Input):
    snapshot_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$", repr=False)
    deliveries: tuple[Delivery, ...] = Field(min_length=1, max_length=50)
    vehicles: tuple[Vehicle, ...] = Field(min_length=1, max_length=10)

    @model_validator(mode="after")
    def unique_and_known_ids(self):
        vehicles = {v.id for v in self.vehicles}
        if len(vehicles) != len(self.vehicles) or len({d.id for d in self.deliveries}) != len(self.deliveries):
            raise ValueError("duplicate_local_id")
        for d in self.deliveries:
            allowed = set(d.allowed_vehicle_ids)
            if len(allowed) != len(d.allowed_vehicle_ids) or not allowed <= vehicles:
                raise ValueError("invalid_vehicle_allowlist")
        return self


def _tasks(snapshot: Snapshot):
    return {
        2 * i + offset: (d, kind, stop)
        for i, d in enumerate(snapshot.deliveries)
        for offset, kind, stop in ((1, "pickup", d.pickup), (2, "delivery", d.delivery))
    }


def build_request(snapshot: Snapshot) -> dict:
    """Чистое преобразование; наружу только псевдонимы, координаты и ограничения."""
    shipments = []
    for i, d in enumerate(snapshot.deliveries):
        shipment = {"amount": [d.units], "skills": [i + 1]}
        for offset, kind, stop in ((1, "pickup", d.pickup), (2, "delivery", d.delivery)):
            shipment[kind] = {
                "id": 2 * i + offset,
                "location": [stop.point.lon, stop.point.lat],
                "time_windows": [list(stop.time_window)],
                "service": stop.service_seconds,
            }
        shipments.append(shipment)
    return {
        "shipments": shipments,
        "vehicles": [{
            "id": v.id, "profile": "car",
            "start": [v.start.lon, v.start.lat], "end": [v.end.lon, v.end.lat],
            "time_window": list(v.time_window), "capacity": [v.capacity],
            "max_tasks": 2 * v.max_deliveries,
            "skills": [i + 1 for i, d in enumerate(snapshot.deliveries) if v.id in d.allowed_vehicle_ids],
        } for v in snapshot.vehicles],
    }


class _Output(BaseModel):
    # Полный ответ VROOM содержит дополнительные поля. Они не передаются дальше.
    model_config = ConfigDict(strict=True, extra="ignore", hide_input_in_errors=True)
    violations: list = Field(max_length=0)


class _Step(_Output):
    type: Literal["start", "pickup", "delivery", "end"]
    id: LocalId | None = None
    arrival: Seconds
    waiting_time: Seconds
    setup: Seconds
    service: Seconds


class _Route(_Output):
    vehicle: LocalId
    steps: list[_Step] = Field(min_length=4, max_length=102)


class _Unassigned(BaseModel):
    model_config = ConfigDict(strict=True, extra="ignore")
    id: LocalId
    type: Literal["pickup", "delivery"]


class _Summary(_Output):
    routes: Annotated[int, Field(strict=True, ge=0, le=10)]
    unassigned: Annotated[int, Field(strict=True, ge=0, le=100)]


class _Response(BaseModel):
    model_config = ConfigDict(strict=True, extra="ignore", hide_input_in_errors=True)
    code: Annotated[int, Field(strict=True, ge=0)]
    summary: _Summary
    routes: list[_Route] = Field(max_length=10)
    unassigned: list[_Unassigned] = Field(max_length=100)


@dataclass(frozen=True)
class ProposedStop:
    delivery_id: int
    kind: Literal["pickup", "delivery"]
    estimated_arrival_seconds: int  # от начала горизонта снимка, НЕ обещание человеку


@dataclass(frozen=True)
class ProposedRoute:
    vehicle_id: int
    stops: tuple[ProposedStop, ...]


@dataclass(frozen=True)
class Preview:
    status: Literal["disabled", "unavailable", "invalid_response", "preview"]
    snapshot_id: str
    routes: tuple[ProposedRoute, ...] = ()
    unassigned_delivery_ids: tuple[int, ...] = ()
    eta_source: Literal["solver_estimate"] = "solver_estimate"
    optimality_proven: Literal[False] = False


def _unique_keys(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("duplicate_response_key")
        obj[key] = value
    return obj


def _invalid_constant(_):
    raise ValueError("non_finite_response_number")


def parse_response(snapshot: Snapshot, raw: bytes) -> Preview:
    """Не доверяем даже ответу собственного solver; нарушения отклоняют весь расчёт.

    ValueError/ValidationError — только для внутренней границы; не логировать вход/исключение.
    Проверяется согласованность времени, но без независимой дорожной матрицы НЕ его реальность.
    """
    if not isinstance(raw, bytes) or len(raw) > MAX_RESPONSE_BYTES:
        raise ValueError("invalid_response_size_or_type")
    data = json.loads(raw, object_pairs_hook=_unique_keys, parse_constant=_invalid_constant)
    response = _Response.model_validate(data)
    if response.code != 0 or response.summary.routes != len(response.routes) \
            or response.summary.unassigned != len(response.unassigned):
        raise ValueError("solver_error_or_inconsistent_summary")
    tasks = _tasks(snapshot)
    vehicles = {v.id: v for v in snapshot.vehicles}
    seen_tasks, seen_vehicles, proposals = set(), set(), []
    for route in response.routes:
        v = vehicles.get(route.vehicle)
        if v is None or v.id in seen_vehicles:
            raise ValueError("unknown_or_duplicate_vehicle")
        seen_vehicles.add(v.id)
        if route.steps[0].type != "start" or route.steps[-1].type != "end":
            raise ValueError("missing_route_boundary")
        load, ready, count, onboard, stops = 0, v.time_window[0], 0, set(), []
        for i, step in enumerate(route.steps):
            if step.arrival < ready or step.arrival > v.time_window[1] or step.setup != 0:
                raise ValueError("inconsistent_route_time")
            ready = step.arrival + step.waiting_time + step.service
            if ready > v.time_window[1]:
                raise ValueError("vehicle_window_exceeded")
            if i in (0, len(route.steps) - 1):
                if step.id is not None or step.service or step.waiting_time:
                    raise ValueError("invalid_route_boundary")
                continue
            task = tasks.get(step.id)
            if task is None or step.id in seen_tasks:
                raise ValueError("unknown_or_duplicate_task")
            d, kind, stop = task
            service_at = step.arrival + step.waiting_time
            if step.type != kind or v.id not in d.allowed_vehicle_ids \
                    or step.service != stop.service_seconds \
                    or not stop.time_window[0] <= service_at <= stop.time_window[1]:
                raise ValueError("task_constraint_violated")
            seen_tasks.add(step.id)
            if kind == "pickup":
                onboard.add(d.id)
                load += d.units
                count += 1
            else:
                if d.id not in onboard:
                    raise ValueError("delivery_without_pickup_on_same_vehicle")
                onboard.remove(d.id)
                load -= d.units
            if not 0 <= load <= v.capacity or count > v.max_deliveries:
                raise ValueError("vehicle_capacity_or_count_exceeded")
            stops.append(ProposedStop(d.id, kind, step.arrival))
        if onboard or load:
            raise ValueError("incomplete_shipment")
        proposals.append(ProposedRoute(v.id, tuple(stops)))
    unassigned = set()
    for task in response.unassigned:
        expected = tasks.get(task.id)
        if expected is None or expected[1] != task.type or task.id in seen_tasks:
            raise ValueError("invalid_unassigned_task")
        seen_tasks.add(task.id)
        unassigned.add(task.id)
    if seen_tasks != set(tasks):
        raise ValueError("missing_tasks")
    unassigned_deliveries = []
    for i, d in enumerate(snapshot.deliveries):
        pickup, delivery = 2 * i + 1 in unassigned, 2 * i + 2 in unassigned
        if pickup != delivery:
            raise ValueError("partially_unassigned_shipment")
        if pickup:
            unassigned_deliveries.append(d.id)
    return Preview("preview", snapshot.snapshot_id, tuple(proposals), tuple(unassigned_deliveries))


class VroomAdapter:
    """Включается только явными enabled=True И transport. Сетевой реализации здесь нет.

    Transport обязан быть неблокирующим и поддерживать отмену. wait_for — общий бюджет ожидания;
    транспорт/solver/router в будущей интеграции должны иметь собственные пределы ресурсов и времени.
    """

    def __init__(self, *, enabled: bool = False, transport: Transport | None = None,
                 timeout_seconds: float = 2.0):
        if type(enabled) is not bool or type(timeout_seconds) not in (int, float) \
                or not math.isfinite(timeout_seconds) or not 0 < timeout_seconds <= 10:
            raise ValueError("invalid_adapter_options")
        self._enabled, self._transport, self._timeout = enabled, transport, timeout_seconds

    async def preview(self, snapshot: Snapshot) -> Preview:
        if not self._enabled:
            return Preview("disabled", snapshot.snapshot_id)
        if self._transport is None:
            return Preview("unavailable", snapshot.snapshot_id)
        try:
            raw = await asyncio.wait_for(self._transport(build_request(snapshot)), timeout=self._timeout)
        except Exception:  # noqa: BLE001 — граница недоверенного транспорта, наружу только безопасный статус
            # Исключение транспорта может содержать координаты/тело/URL. Не логируем и не возвращаем.
            # CancelledError наследует BaseException и намеренно проходит наружу.
            return Preview("unavailable", snapshot.snapshot_id)
        try:
            return parse_response(snapshot, raw)
        except (ValueError, TypeError, RecursionError):
            return Preview("invalid_response", snapshot.snapshot_id)
