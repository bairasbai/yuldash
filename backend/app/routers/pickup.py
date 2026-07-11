"""Точки сбора по ориентирам города/села (F14, РБ-фишка).

Публичный двуязычный справочник ориентиров («у мечети», «автовокзал», «у Магнита»)
с координатами пина. Подсказки для конкретного города — вместо ручного тыка в карту
при создании поездки/заявки. Наполнение и usage_count — в services (record_pickup_choice)."""
from typing import List, Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlmodel import Session

from ..db import get_session
from ..services import suggest_pickup_points

router = APIRouter(tags=["pickup"])


class PickupPointOut(BaseModel):
    id: int
    city: str
    title_ru: str
    title_ba: str
    lat: Optional[float] = None
    lng: Optional[float] = None
    usage_count: int


@router.get("/pickup-points", response_model=List[PickupPointOut])
def list_pickup_points(
    city: Optional[str] = None,
    limit: int = 12,
    session: Session = Depends(get_session),
):
    """Подсказки точек сбора для города (чаще выбираемые — первыми).

    Публично (ориентиры — не персональные данные, ни телефонов, ни чужой геолокации):
    вход открыт и без токена, как справочник. Клиент показывает чипы «у мечети» и т.п."""
    return suggest_pickup_points(session, city, limit)
