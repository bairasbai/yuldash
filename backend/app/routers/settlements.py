"""Справочник населённых пунктов (волна 2, география) — ПУБЛИЧНЫЙ (без auth).

Отдаёт каталог городов, райцентров и сёл (общеизвестные данные, не персональные) —
для автоподсказок «откуда/куда» и выбора зоны таксиста. Сёл ~6600 (вся РБ + приграничье
соседей, источник OSM/ODbL), поэтому поиск идёт по снимку справочника в памяти (см. geo.py),
а не запросом в БД на каждую букву. Rate-limit общий (middleware).
"""
from fastapi import APIRouter, Depends
from sqlmodel import Session

from .. import geo
from ..db import get_session

router = APIRouter(tags=["settlements"])


@router.get("/settlements")
def settlements(q: str = "", limit: int = 10, session: Session = Depends(get_session)):
    """Автоподсказки: поиск по name_ru И name_ba (регистр, ё и башкирские буквы не важны),
    активные. Пустой q → топ справочника (города → райцентры → соседи → сёла, по алфавиту)."""
    return {"items": [geo.settlement_payload(s) for s in geo.search_settlements(session, q, limit)]}


@router.get("/settlements/popular-routes")
def popular_routes(session: Session = Depends(get_session)):
    """Пресеты популярных межгород-маршрутов (§4) — чипы в UI создания поездки/заявки."""
    return {"routes": geo.popular_routes_payload(session)}
