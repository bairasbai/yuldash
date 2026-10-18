"""Точный участок опубликованного рейса для заявки — общий для ленты, подбора и принятия."""
from __future__ import annotations

import json
from datetime import timedelta

from . import stop_search
from .geo import snapshot_cached
from .models import RideStatus
from .timeutil import local_date, utcnow

REQUEST_FIELDS = ("from_city", "to_city", "seats", "desired_at", "category", "only_trusted", "women_only",
                  "child_seat", "pets", "wheelchair", "baggage", "non_smoking", "air_conditioner")
RIDE_FIELDS = ("driver_id", "from_city", "to_city", "waypoints", "depart_at", "category", "only_trusted",
               "women_only", "child_seat", "pets_allowed", "baggage", "smoking", "air_conditioner")


def segment(ride, req, entry=None):
    """Каноническая пара (посадка, высадка); пустая строка означает начало/конец."""
    if (ride.status != RideStatus.active or ride.depart_at is None
            or ride.depart_at < utcnow() - timedelta(hours=2)
            or ride.seats_left < req.seats or ride.category != req.category):
        return None
    if req.desired_at is not None and local_date(req.desired_at) != local_date(ride.depart_at):
        return None
    for wanted, offered in (("child_seat", "child_seat"), ("pets", "pets_allowed"),
                            ("baggage", "baggage"), ("air_conditioner", "air_conditioner")):
        if getattr(req, wanted, False) and not getattr(ride, offered, False):
            return None
    if getattr(req, "non_smoking", False) and getattr(ride, "smoking", False):
        return None
    # У Ride пока нет подтверждённого признака доступности для коляски. Не обещаем её автоматически.
    if getattr(req, "wheelchair", False):
        return None
    entry = entry if entry is not None else snapshot_cached()
    fit = stop_search.route_fit(ride, stop_search.variants(None, req.from_city, entry),
                               stop_search.variants(None, req.to_city, entry), entry, strict=True)
    if not fit.ok or (not fit.matched_from_start and fit.matched_stop is None):
        return None
    if not fit.matched_to_end and fit.matched_dropoff_stop is None:
        return None
    return ("" if fit.matched_from_start else fit.matched_stop,
            "" if fit.matched_to_end else fit.matched_dropoff_stop)


def snapshot(ride, req) -> str:
    """Фиксируем существенные условия, не местa/статус: они проверяются заново при принятии."""
    def encode(value):
        if hasattr(value, "isoformat"):
            return value.isoformat()
        return value.value if hasattr(value, "value") else value

    return json.dumps({"request": {k: encode(getattr(req, k, None)) for k in REQUEST_FIELDS},
                       "ride": {k: encode(getattr(ride, k, None)) for k in RIDE_FIELDS}},
                      sort_keys=True, ensure_ascii=False, separators=(",", ":"))
