"""Opaque, order-scoped identities for onboard manual waits (not pricing)."""
import json
from collections import Counter
from uuid import UUID, uuid4

from sqlalchemy import update
from sqlmodel import Session, select

from .errors import herr
from .models import InstantOrder, InstantOrderStatus as S


def new_revision() -> str:
    return str(uuid4())


def waypoint_id(value) -> str | None:
    try:
        return str(UUID(value)) if isinstance(value, str) else None
    except ValueError:
        return None


def lock_order(session: Session, order_id: int, *scope) -> InstantOrder | None:
    criteria = (InstantOrder.id == order_id, *scope)
    if session.get_bind().dialect.name == "sqlite":
        # FOR UPDATE is ignored on SQLite. Reserve the write transaction before the reread.
        session.execute(update(InstantOrder).where(*criteria).values(id=InstantOrder.id)
                        .execution_options(synchronize_session=False))
    return session.exec(select(InstantOrder).where(*criteria).with_for_update()
                        .execution_options(populate_existing=True)).one_or_none()


def opted_out():
    return herr(404, "Управление остановками пока недоступно", "Туҡталыштар менән идара итеү әлегә мөмкин түгел")


def stale_wait():
    return herr(409, "Ожидание уже изменилось. Обнови поездку", "Көтөү үҙгәрҙе. Сәфәрҙе яңырт",
                code="taxi_wait_stale")


def stale_route():
    return herr(409, "Остановки уже изменились. Обнови поездку", "Туҡталыштар үҙгәрҙе. Сәфәрҙе яңырт",
                code="taxi_waypoints_stale")


def active_stop_protected():
    return herr(409, "Нельзя изменить остановку, на которой ждёт водитель",
                "Водитель көткән туҡталышты үҙгәртеп булмай", code="taxi_active_waypoint_protected")


def change_marker(order: InstantOrder) -> tuple:
    """Revalidate after route calculation, before committing under the order lock."""
    return (order.status, order.driver_id, order.waypoints_json, order.stop_route_revision,
            order.stop_started_at, order.stop_wait_revision, order.stop_wait_kind,
            order.stop_active_waypoint_id, order.to_lat, order.to_lng, order.destination_changed_at)


def prepare_raw_ids(raw: str) -> str:
    """Keep every original waypoint value; do not repair corrupt route data implicitly."""
    if not raw:
        return raw
    try:
        points = json.loads(raw)
    except (TypeError, ValueError):
        raise stale_route() from None
    if not isinstance(points, list) or any(not isinstance(point, dict) for point in points):
        raise stale_route()
    return json.dumps([{**point, "waypoint_id": new_revision()} for point in points], ensure_ascii=False)


def _signature(point: dict) -> tuple:
    return point["lat"], point["lng"], point["text"]


def retain_legacy_ids(order: InstantOrder, previous: list[dict], fresh: list[dict]) -> list[dict]:
    """The old passenger API has no IDs. Never guess which duplicate occurrence it retained."""
    planned = order.stop_started_at is not None and order.stop_wait_kind == "planned"
    if planned and (order.stop_active_waypoint_id is None or order.stop_route_revision is None):
        raise active_stop_protected()
    if order.stop_route_revision is None:
        return fresh
    old = [point for point in previous if not point["done"]]
    future = [point for point in fresh if not point["done"]]
    old_counts = Counter(map(_signature, old))
    future_counts = Counter(map(_signature, future))
    by_value = {_signature(point): point["waypoint_id"] for point in old
                if old_counts[_signature(point)] == 1 and point.get("waypoint_id")}
    retained = [by_value.get(_signature(point)) if future_counts[_signature(point)] == 1
                else None for point in future]
    # The legacy wire cannot name same-value occurrences. Preserve their IDs
    # only inside an unchanged ordered prefix, when their total count still
    # agrees. An insertion/deletion of an identical duplicate remains ambiguous;
    # a later unrelated edit, however, cannot change the untouched prefix.
    for index, (before, after) in enumerate(zip(old, future)):
        signature = _signature(before)
        if signature != _signature(after):
            break
        if old_counts[signature] == future_counts[signature] and before.get("waypoint_id"):
            retained[index] = before["waypoint_id"]

    if planned and retained.count(order.stop_active_waypoint_id) != 1:
        raise active_stop_protected()
    ids = iter(retained)
    return [point if point["done"] else {**point, "waypoint_id": next(ids) or new_revision()}
            for point in fresh]


def explicit_wait_requires_command():
    return herr(409, "Это ожидание нужно завершить в обновлённом приложении",
                "Был көтөүҙе яңыртылған ҡушымтала тамамларға кәрәк", code="taxi_explicit_wait_required")


def close_fields(order: InstantOrder) -> dict:
    """Fresh idle generation; a late explicit start must never reopen this episode."""
    return {"stop_wait_revision": new_revision() if order.stop_wait_revision else None,
            "stop_wait_kind": None, "stop_active_waypoint_id": None}


def explicit_wait_active(order: InstantOrder) -> bool:
    return bool(order.status == S.onboard and order.stop_started_at is not None
                and order.stop_wait_kind in ("planned", "unplanned") and order.stop_wait_revision)


def payload(order: InstantOrder, *, admission_enabled: bool) -> dict:
    active = None
    if order.status == S.onboard and order.stop_started_at is not None:
        kind = order.stop_wait_kind or "legacy_unknown"
        active = {
            "kind": kind,
            "wait_id": order.stop_wait_revision if kind != "legacy_unknown" else None,
            "waypoint_id": order.stop_active_waypoint_id if kind == "planned" else None,
        }
    return {"admission_enabled": admission_enabled, "route_revision": order.stop_route_revision,
            "wait_revision": order.stop_wait_revision, "active_wait": active}
