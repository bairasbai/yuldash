"""Passenger selection for ride requests, including older ambiguous rows."""

import unicodedata
from hashlib import sha256
import hmac
import json
from datetime import timedelta

from fastapi.encoders import jsonable_encoder
from pydantic import BaseModel, ConfigDict, Field, field_validator

from . import request_segments
from .config import settings
from .errors import herr
from .security import normalize_guardian_phone


CONFIRMATION_REQUIRED = "request_traveler_confirmation_required"


def traveler_confirmation_required():
    return herr(409, "Владелец заявки должен подтвердить, кто едет.",
                "Ғариза хужаһы кем барғанын раҫларға тейеш.", code=CONFIRMATION_REQUIRED)


def request_traveler_mode(request) -> str:
    if request.traveler_origin in {"self", "other"}:
        return request.traveler_origin
    return "unknown" if (request.for_relative_name or "").strip() else "self"


def normalize_traveler_name(value: str) -> str:
    if (not any(char.isalpha() for char in value) or
            any(char.isnumeric() or unicodedata.category(char).startswith("C") for char in value)):
        raise ValueError("invalid traveler name")
    return " ".join(value.split())


class TravelerConfirmationIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: str = Field(min_length=64, max_length=64)
    for_another: bool
    traveler_name: str | None = Field(default=None, max_length=120)
    minor_passenger: bool = False
    minor_guardian_name: str | None = Field(default=None, max_length=120)
    minor_guardian_phone: str | None = Field(default=None, max_length=32)

    @field_validator("traveler_name")
    @classmethod
    def normalize_name(cls, value: str | None) -> str | None:
        return None if value is None else normalize_traveler_name(value)


def traveler_request_departure(request, response):
    return request.desired_at or (response.created_at + timedelta(hours=1))


def _sign(domain: str, payload) -> str:
    raw = json.dumps(jsonable_encoder(payload), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hmac.new(settings.jwt_secret.encode(), f"{domain}\n{raw}".encode(), sha256).hexdigest()


def traveler_offer_version(request, response, ride) -> str:
    route = request_segments.snapshot(ride, request)
    return _sign("request-traveler-offer-v1", (
        request.id, request.passenger_id, request.traveler_origin, request.traveler_revision, request.for_relative_name,
        request.max_price, request.comment, response.id, response.driver_id, response.price,
        response.current_price, response.last_offer_by, response.bargain_rounds, response.bargain_history,
        response.pickup_stop, response.dropoff_stop, response.route_snapshot, response.ride_id, route,
        (ride.terms_version, ride.no_minors) if ride else traveler_request_departure(request, response),
    ))


def traveler_acceptance_hash(response_id: int, body: TravelerConfirmationIn) -> str:
    return _sign("request-traveler-accept-v1", (response_id, body.model_dump()))


def traveler_booking_fields(request, ride, body: TravelerConfirmationIn) -> dict:
    if request_traveler_mode(request) == "other" and not body.for_another:
        raise traveler_confirmation_required()
    if body.for_another != bool(body.traveler_name):
        raise herr(422, "Подтверди имя пассажира.", "Юлсының исемен раҫла.", code="booking_traveler_required")
    if body.for_another and (request.women_only or (ride and ride.women_only)):
        raise herr(409, "В поездку «Только женщины» можно бронировать место только для себя.",
                   "«Тик ҡатын-ҡыҙ» сәфәренә урынды үҙең өсөн генә бронларға була.", code="booking_traveler_women_only")
    if body.minor_passenger and ride and ride.no_minors:
        raise herr(409, "Водитель не берёт пассажиров младше 18 без сопровождения взрослого.",
                   "Йөрөтөүсе 18-ҙән кесе юлсыларҙы оло кеше оҙатыуынан башҡа алмай.", code="booking_traveler_no_minors")
    guardian_name = (body.minor_guardian_name or "").strip() if body.minor_passenger else ""
    guardian_phone = normalize_guardian_phone(body.minor_guardian_phone or "") if body.minor_passenger else ""
    if body.minor_passenger and (not guardian_name or not guardian_phone):
        raise herr(400, "Укажи имя и телефон ответственного взрослого.",
                   "Яуаплы оло кешенең исемен һәм телефонын күрһәт.", code="guardian_phone_invalid")
    return {"traveler_name": body.traveler_name, "minor_passenger": body.minor_passenger,
            "minor_guardian_name": guardian_name, "minor_guardian_phone": guardian_phone}
