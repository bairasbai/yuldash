"""Витрина/лента: популярные маршруты, живой счётчик ленты, частые маршруты юзера,
прокси геокодера, партнёрская реклама + её статистика, загрузка голосовых."""
from collections import Counter
from datetime import timedelta
import os
import uuid

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..models import AdEvent, Booking, Ride, User
from ..security import current_user
from ..services import VOICE_DIR, cache_get_json, cache_set_json, decode_upload_b64, public_media_url
from ..timeutil import utcnow

router = APIRouter(tags=["discovery"])


@router.get("/popular-routes")
def popular_routes(session: Session = Depends(get_session)):
    """Топ направлений — считаем из реальных поездок. Кеш 120с (Redis, если есть)."""
    cached = cache_get_json("popular_routes:v1")
    if cached is not None:
        return cached
    rides = session.exec(select(Ride)).all()
    cnt = Counter((r.from_city, r.to_city) for r in rides if r.from_city and r.to_city)
    result = [{"from_city": f, "to_city": t, "count": n} for (f, t), n in cnt.most_common(6)]
    cache_set_json("popular_routes:v1", result, 120)
    return result


@router.get("/feed")
def feed(session: Session = Depends(get_session)):
    """Живая лента карты: счётчики поездок за период (день/неделя/месяц/год) + топ-маршрут недели. Из реальных данных. Кеш 60с."""
    cached = cache_get_json("feed:v1")
    if cached is not None:
        return cached
    now = utcnow()
    bookings = session.exec(select(Booking)).all()

    def since(days: int) -> int:
        edge = now - timedelta(days=days)
        return sum(1 for b in bookings if b.created_at and b.created_at >= edge)

    rides = session.exec(select(Ride)).all()
    week_rides = [r for r in rides if r.created_at and r.created_at >= now - timedelta(days=7) and r.from_city and r.to_city]
    top = Counter((r.from_city, r.to_city) for r in week_rides).most_common(1)
    top_route = ({"from_city": top[0][0][0], "to_city": top[0][0][1], "count": top[0][1]} if top else None)
    result = {
        "today": since(1), "week": since(7), "month": since(30), "year": since(365),
        "drivers": len({r.driver_id for r in rides}),
        "top_route": top_route,
    }
    cache_set_json("feed:v1", result, 60)
    return result


@router.get("/my-routes")
def my_routes(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Частые поездки пользователя — из его истории броней."""
    bookings = session.exec(select(Booking).where(Booking.passenger_id == user.id)).all()
    pairs = []
    for b in bookings:
        r = session.get(Ride, b.ride_id)
        if r and r.from_city and r.to_city:
            pairs.append((r.from_city, r.to_city))
    cnt = Counter(pairs)
    return [{"from_city": f, "to_city": t, "count": n} for (f, t), n in cnt.most_common(6)]


# Реклама (/ads, /ads/event, /ads/stats) вынесена в routers/ads.py — теперь из БД с админ-управлением.


@router.get("/geocode")
def geocode(q: str = ""):
    """Прокси Яндекс.Геокодера: ключ живёт на сервере, не в APK (раньше клиент слал ключ в URL).
    Отдаём упрощённый список адресов для подсказок «Откуда/Куда»."""
    key = settings.yandex_geocoder_key
    query = (q or "").strip()
    if not key or len(query) < 2:
        return {"items": []}
    try:
        import httpx
        r = httpx.get("https://geocode-maps.yandex.ru/1.x/", params={
            "apikey": key, "geocode": query, "format": "json", "results": 5, "lang": "ru_RU",
        }, timeout=8)
        members = r.json()["response"]["GeoObjectCollection"]["featureMember"]
    except Exception:  # noqa: BLE001
        return {"items": []}
    items: list = []
    for m in members:
        go = m.get("GeoObject", {})
        pos = (go.get("Point", {}).get("pos", "") or "").split(" ")  # "lon lat"
        if len(pos) < 2:
            continue
        try:
            lon, lat = float(pos[0]), float(pos[1])
        except ValueError:
            continue
        name, desc = go.get("name", ""), go.get("description", "")
        title = f"{name}, {desc}" if desc else name
        if title:
            items.append({"title": title, "lat": lat, "lon": lon})
    return {"items": items}


class VoiceIn(BaseModel):
    audio_b64: str
    ext: str = "m4a"


@router.post("/voice")
def upload_voice(body: VoiceIn, user: User = Depends(current_user)):
    """Загрузка голосового (base64) → сохранение в media → публичный URL."""
    ext = "".join(c for c in body.ext.lower() if c.isalnum()) or "m4a"
    data, ext = decode_upload_b64(body.audio_b64, settings.audio_ext_set, ext, "аудио")
    name = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(VOICE_DIR, name), "wb") as f:
        f.write(data)
    return {"url": public_media_url(f"voice/{name}")}
