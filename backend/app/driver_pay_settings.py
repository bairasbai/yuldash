"""Declared ways to settle a passenger ride directly with the driver."""

from sqlmodel import Session, select

from .errors import herr
from .models import DriverPaySettings, PayMethod, Ride, RideCategory


DIRECT_METHODS = (PayMethod.cash.value, PayMethod.sbp.value)
METHOD_NOT_ACCEPTED = (409, "Водитель изменил способы оплаты. Выбери другой или договорись с ним.",
                       "Йөрөтөүсе түләү ысулдарын үҙгәртте. Икенсеһен һайла йәки уның менән килеш.")


def decode_methods(value: str | None) -> list[str]:
    saved = (value or "").split(",")
    return [method for method in DIRECT_METHODS if method in saved]


def passenger_ride(ride: Ride) -> bool:
    return ride.category not in (RideCategory.parcel, RideCategory.cargo)


def methods_for_driver(session: Session, driver_id: int) -> list[str]:
    stored = session.exec(select(DriverPaySettings.methods).where(DriverPaySettings.user_id == driver_id)).first()
    return decode_methods(stored)


def methods_for_drivers(session: Session, driver_ids: set[int]) -> dict[int, list[str]]:
    if not driver_ids:
        return {}
    rows = session.exec(select(DriverPaySettings.user_id, DriverPaySettings.methods)
                        .where(DriverPaySettings.user_id.in_(driver_ids))).all()
    return {driver_id: decode_methods(methods) for driver_id, methods in rows}


def refused_method(accepted: list[str]):
    return herr(*METHOD_NOT_ACCEPTED, code="pay_method_not_accepted", accepted=accepted)


def require_driver_method(session: Session, ride: Ride, method: PayMethod) -> None:
    if method == PayMethod.negotiate or not passenger_ride(ride):
        return
    accepted = methods_for_driver(session, ride.driver_id)
    if accepted and method.value not in accepted:
        raise refused_method(accepted)
