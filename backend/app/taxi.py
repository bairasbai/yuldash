"""Гейт такси (волна 2): глобальный/городской флаг + проверка таксиста (580-ФЗ).

Два независимых гейта для режима ТАКСИ (instant):
  (a) доступность по месту: settings.taxi_enabled=False → выключено везде;
      True и TaxiCity пуста → включено везде; True и есть записи → только города
      с enabled=True. Город определяем ближайшим из CITY_COORDS в радиусе
      taxi_city_radius_km; дальше/без координат → «город неизвестен» → в city-режиме выкл.
  (b) онбординг таксиста: возить такси может только водитель с approved TaxiApplication.

ПОПУТКА (плановые Ride/Booking) этими гейтами НЕ затрагивается — отдельный поток.
Приватность: координаты не логируем.
"""
from typing import Optional

from sqlmodel import Session, select

from .config import settings
from .models import TaxiApplication, TaxiApplicationStatus, TaxiCity
from .services import CITY_COORDS, haversine_km

# Сообщения гейта — двуязычно (RU + черновой BA, финал башкирского — за Александром).
MSG_GLOBAL_OFF = {
    "ru": "Такси Юлдаш скоро запустится! А пока поезжай попуткой 🚗",
    "ba": "Юлдаш таксиы тиҙҙән асыла! Ә әлегә юлдаш булып бар 🚗",
}
MSG_CITY_OFF = {
    "ru": "Такси скоро в твоём городе 🚕 А пока поезжай попуткой",
    "ba": "Тиҙҙән таксиы һинең ҡалаңда ла булыр 🚕 Ә әлегә юлдаш булып бар",
}
MSG_OK = {
    "ru": "Такси доступно",
    "ba": "Такси эшләй",
}

# Понятные 403 гейтов (RU — серверная строка; UI локализует сам через availability/appText).
TAXI_UNAVAILABLE_MSG = "Такси скоро в вашем городе. А пока поезжай попуткой 🚗"
TAXI_NOT_APPROVED_MSG = "Пройди проверку таксиста, чтобы возить такси"


def _nearest_city(lat: float, lng: float) -> Optional[str]:
    """Ближайший известный город (CITY_COORDS) в радиусе taxi_city_radius_km, иначе None.
    У города может быть два имени (RU/BA, напр. Баймак/Баймаҡ) — возвращаем найденный ключ,
    сравнение со списком TaxiCity идёт по всем именам той же точки (см. availability)."""
    best_name, best_km = None, None
    for name, (clat, clng) in CITY_COORDS.items():
        km = haversine_km(lat, lng, clat, clng)
        if best_km is None or km < best_km:
            best_name, best_km = name, km
    if best_name is not None and best_km is not None and best_km <= settings.taxi_city_radius_km:
        return best_name
    return None


def _city_aliases(name: str) -> set[str]:
    """Все имена одной точки CITY_COORDS (RU/BA-варианты), в нижнем регистре."""
    coords = CITY_COORDS.get(name)
    if coords is None:
        return {name.casefold()}
    return {n.casefold() for n, c in CITY_COORDS.items() if c == coords}


def availability(session: Session, lat: Optional[float] = None, lng: Optional[float] = None) -> dict:
    """Доступно ли такси в точке (lat, lng). Ответ единый для API и внутренних гейтов:
    {"enabled": bool, "reason": "global_off"|"city_off"|"ok", "message": {"ru","ba"}, "city": str|None}.
    city — ближайший известный город (для предзаполнения формы листа ожидания, §11);
    None = город не определён. Аддитивное поле, старые клиенты его игнорируют."""
    near = _nearest_city(lat, lng) if (lat is not None and lng is not None) else None
    if not settings.taxi_enabled:
        return {"enabled": False, "reason": "global_off", "message": MSG_GLOBAL_OFF, "city": near}
    cities = session.exec(select(TaxiCity)).all()
    if not cities:
        return {"enabled": True, "reason": "ok", "message": MSG_OK, "city": near}
    enabled_names: set[str] = set()
    for c in cities:
        if c.enabled:
            enabled_names |= _city_aliases(c.city.strip())
    if lat is None or lng is None:
        return {"enabled": False, "reason": "city_off", "message": MSG_CITY_OFF, "city": None}
    if near is not None and _city_aliases(near) & enabled_names:
        return {"enabled": True, "reason": "ok", "message": MSG_OK, "city": near}
    return {"enabled": False, "reason": "city_off", "message": MSG_CITY_OFF, "city": near}


def my_application(session: Session, user_id: int) -> Optional[TaxiApplication]:
    return session.exec(select(TaxiApplication).where(TaxiApplication.user_id == user_id)).first()


def is_approved_taxi_driver(session: Session, user_id: int) -> bool:
    """Гейт (b): есть ли одобренная заявка таксиста (580-ФЗ)."""
    app = my_application(session, user_id)
    return app is not None and app.status == TaxiApplicationStatus.approved
