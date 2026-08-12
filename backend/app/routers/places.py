"""Сохранённые и недавние адреса пользователя (быстрый выбор точки в форме заказа).

Всё СТРОГО про себя (анти-IDOR): каждый эндпоинт читает/пишет только строки текущего
пользователя (по токену). Адреса и координаты — приватные данные, чужие не отдаём и не
раскрываем существование (чужое удаление → 404).

`GET  /places/saved`           — мои сохранённые места (дом/работа/произвольные).
`POST /places/saved`           — создать/обновить: home/work по одному (upsert по kind),
                                 custom — до лимита MAX_SAVED.
`DELETE /places/saved/{id}`    — удалить своё (чужое → 404).
`GET  /places/recent`          — мои недавние точки (свежие сверху, ≤ MAX_RECENT).
`POST /places/recent`          — добавить недавнюю (вызывается при заказе); дедуп по адресу.
"""
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from sqlalchemy import delete, func
from sqlmodel import Session, select

from ..db import get_session
from ..errors import herr
from ..models import RecentPlace, SavedPlace, SavedPlaceKind, User
from ..security import current_user
from ..timeutil import utcnow

router = APIRouter(tags=["places"])

MAX_SAVED = 20    # потолок сохранённых мест на пользователя (защита от мусора)
MAX_RECENT = 10   # держим последние N недавних точек
_KINDS = {k.value for k in SavedPlaceKind}
_SINGLE = (SavedPlaceKind.home.value, SavedPlaceKind.work.value)   # по одному на пользователя


class SavedPlaceIn(BaseModel):
    kind: str = Field("custom", max_length=16)          # home | work | custom
    label: str = Field("", max_length=120)
    address: str = Field("", max_length=500)
    lat: Optional[float] = Field(None, ge=-90, le=90)   # валидные координаты (не NaN/мусор)
    lng: Optional[float] = Field(None, ge=-180, le=180)


class RecentPlaceIn(BaseModel):
    address: str = Field("", max_length=500)
    lat: Optional[float] = Field(None, ge=-90, le=90)
    lng: Optional[float] = Field(None, ge=-180, le=180)


def _saved_out(p: SavedPlace) -> dict:
    return {
        "id": p.id, "kind": p.kind, "label": p.label, "address": p.address,
        "lat": p.lat, "lng": p.lng,
        "created_at": p.created_at.isoformat() if p.created_at else "",
    }


def _recent_out(p: RecentPlace) -> dict:
    return {
        "id": p.id, "address": p.address, "lat": p.lat, "lng": p.lng,
        "used_at": p.used_at.isoformat() if p.used_at else "",
    }


# ------------------------------ сохранённые ------------------------------
@router.get("/places/saved")
def list_saved(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои сохранённые места (дом/работа сверху, затем произвольные по времени добавления)."""
    rows = session.exec(
        select(SavedPlace).where(SavedPlace.user_id == user.id).order_by(SavedPlace.id.asc())
    ).all()
    return [_saved_out(p) for p in rows]


@router.post("/places/saved")
def upsert_saved(body: SavedPlaceIn, user: User = Depends(current_user),
                 session: Session = Depends(get_session)):
    """Создать/обновить сохранённое место. home/work — по одному (upsert по kind:
    повторный POST обновляет ту же строку). custom — новая строка до лимита MAX_SAVED."""
    kind = body.kind if body.kind in _KINDS else SavedPlaceKind.custom.value
    address = (body.address or "").strip()
    if not address and body.lat is None and body.lng is None:
        raise herr(400, "Нужен адрес или координаты места",
                   "Урын өсөн адрес йәки координаталар кәрәк")
    if kind in _SINGLE:
        place = session.exec(
            select(SavedPlace).where(SavedPlace.user_id == user.id, SavedPlace.kind == kind)
        ).first()
        if place is None:
            place = SavedPlace(user_id=user.id, kind=kind)
        place.label = body.label
        place.address = address
        place.lat = body.lat
        place.lng = body.lng
        session.add(place)
        session.commit()
        session.refresh(place)
        return _saved_out(place)
    # custom — лимит на пользователя (считаем в БД, не тянем строки).
    count = session.exec(
        select(func.count()).select_from(SavedPlace).where(SavedPlace.user_id == user.id)
    ).one()
    if int(count or 0) >= MAX_SAVED:
        raise herr(409, "Слишком много сохранённых мест — удали лишнее",
                   "Һаҡланған урындар бигерәк күп — артыҡты юй")
    place = SavedPlace(user_id=user.id, kind=kind, label=body.label,
                       address=address, lat=body.lat, lng=body.lng)
    session.add(place)
    session.commit()
    session.refresh(place)
    return _saved_out(place)


@router.delete("/places/saved/{place_id}")
def delete_saved(place_id: int, user: User = Depends(current_user),
                 session: Session = Depends(get_session)):
    """Удалить своё сохранённое место. Чужое/несуществующее → 404 (не раскрываем чужое)."""
    place = session.get(SavedPlace, place_id)
    if not place or place.user_id != user.id:   # анти-IDOR: чужое место не трогаем и не подтверждаем
        raise herr(404, "Место не найдено", "Урын табылманы")
    session.delete(place)
    session.commit()
    return {"ok": True}


# ------------------------------ недавние ------------------------------
@router.get("/places/recent")
def list_recent(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Мои недавние точки (свежие сверху, не больше MAX_RECENT)."""
    rows = session.exec(
        select(RecentPlace).where(RecentPlace.user_id == user.id)
        .order_by(RecentPlace.used_at.desc(), RecentPlace.id.desc()).limit(MAX_RECENT)
    ).all()
    return [_recent_out(p) for p in rows]


@router.post("/places/recent")
def add_recent(body: RecentPlaceIn, user: User = Depends(current_user),
               session: Session = Depends(get_session)):
    """Добавить недавнюю точку (клиент дёргает при заказе). Дедуп по адресу: повтор
    обновляет used_at и координаты. Держим только последние MAX_RECENT — старые чистим."""
    address = (body.address or "").strip()
    if not address:
        raise herr(400, "Нужен адрес точки", "Нөктә адресы кәрәк")
    place = session.exec(
        select(RecentPlace).where(RecentPlace.user_id == user.id, RecentPlace.address == address)
    ).first()
    if place is None:
        place = RecentPlace(user_id=user.id, address=address)
    place.lat = body.lat
    place.lng = body.lng
    place.used_at = utcnow()
    session.add(place)
    session.commit()
    session.refresh(place)
    # Ретеншен: оставляем последние MAX_RECENT, лишние удаляем.
    ids = session.exec(
        select(RecentPlace.id).where(RecentPlace.user_id == user.id)
        .order_by(RecentPlace.used_at.desc(), RecentPlace.id.desc())
    ).all()
    stale = ids[MAX_RECENT:]
    if stale:
        session.execute(delete(RecentPlace).where(RecentPlace.id.in_(stale)))
        session.commit()
    return _recent_out(place)
