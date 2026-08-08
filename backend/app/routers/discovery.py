"""Витрина/лента: популярные маршруты, живой счётчик ленты, частые маршруты юзера,
прокси геокодера, партнёрская реклама + её статистика, загрузка голосовых."""
from collections import Counter
from datetime import timedelta
import uuid

from fastapi import APIRouter, Depends, Request
from starlette.concurrency import run_in_threadpool
from sqlmodel import Session, select

from ..config import settings
from ..db import get_session
from ..errors import herr
from ..middleware import user_over_limit
from ..models import AppReview, Booking, Payment, Ride, User
from ..security import current_user
from ..services import (
    cache_get_json, cache_set_json,
    enforce_upload_quota, public_media_url, read_upload, secure_evidence_url,
)
from ..storage import get_storage
from ..timeutil import utcnow

router = APIRouter(tags=["discovery"])

# Анти-OOM: на miss кеша не тащим всю таблицу в память, а только последние N строк.
# При текущем размере БД N покрывает всё → результат идентичен; при росте — ограничивает память.
SCAN_LIMIT = 20000


@router.get("/popular-routes")
def popular_routes(session: Session = Depends(get_session)):
    """Топ направлений — считаем из реальных поездок. Кеш 120с (Redis, если есть)."""
    cached = cache_get_json("popular_routes:v1")
    if cached is not None:
        return cached
    rides = session.exec(select(Ride).order_by(Ride.id.desc()).limit(SCAN_LIMIT)).all()
    cnt = Counter((r.from_city, r.to_city) for r in rides if r.from_city and r.to_city)
    result = [{"from_city": f, "to_city": t, "count": n} for (f, t), n in cnt.most_common(6)]
    cache_set_json("popular_routes:v1", result, 120)
    return result


@router.get("/feed")
def feed(session: Session = Depends(get_session)):
    """Живая лента карты: счётчики поездок за период (день/неделя/месяц/год) + топ-маршрут недели
    + сумма донатов от пользователей за всё время. Из реальных данных. Кеш 60с."""
    cached = cache_get_json("feed:v2")
    if cached is not None:
        return cached
    now = utcnow()
    bookings = session.exec(select(Booking).order_by(Booking.id.desc()).limit(SCAN_LIMIT)).all()

    def since(days: int) -> int:
        edge = now - timedelta(days=days)
        return sum(1 for b in bookings if b.created_at and b.created_at >= edge)

    rides = session.exec(select(Ride).order_by(Ride.id.desc()).limit(SCAN_LIMIT)).all()
    week_rides = [r for r in rides if r.created_at and r.created_at >= now - timedelta(days=7) and r.from_city and r.to_city]
    top = Counter((r.from_city, r.to_city) for r in week_rides).most_common(1)
    top_route = ({"from_city": top[0][0][0], "to_city": top[0][0][1], "count": top[0][1]} if top else None)
    # Донаты пользователей за всё время — сумма подтверждённых (succeeded) платежей purpose=donate, в рублях.
    donate_kop = sum(
        p.amount_kop for p in session.exec(
            select(Payment).where(Payment.purpose == "donate", Payment.status == "succeeded")
        ).all()
    )
    result = {
        "today": since(1), "week": since(7), "month": since(30), "year": since(365),
        "drivers": len({r.driver_id for r in rides}),
        "top_route": top_route,
        "donations_total": donate_kop // 100,   # ₽, за всё время
    }
    cache_set_json("feed:v2", result, 60)
    return result


@router.get("/landing-stats")
def landing_stats(session: Session = Depends(get_session)):
    """Живые метрики для лендинга (StatsBand) в формате [{value, ru, ba}]. Кеш 300с.

    Честность: пока реальных поездок мало (пре-запуск) — отдаём ПУСТО, и лендинг
    сам показывает ценностные метрики (0₽ комиссия, 2 языка, SOS 24/7), а не
    унылые «0 поездок». Как только пойдёт реальное использование (порог ≥15
    поездок) — цифры сами станут живыми, без правки фронта."""
    cached = cache_get_json("landing_stats:v1")
    if cached is not None:
        return cached
    rides = session.exec(select(Ride).order_by(Ride.id.desc()).limit(SCAN_LIMIT)).all()
    if len(rides) < 15:  # пре-запуск → пусть фронт покажет ценностные метрики
        cache_set_json("landing_stats:v1", [], 300)
        return []
    now = utcnow()
    bookings = session.exec(select(Booking).order_by(Booking.id.desc()).limit(SCAN_LIMIT)).all()
    reviews = session.exec(select(AppReview).where(AppReview.published == True)).all()  # noqa: E712
    month_trips = sum(1 for b in bookings if b.created_at and b.created_at >= now - timedelta(days=30))
    drivers = len({r.driver_id for r in rides})
    out = [
        {"value": f"{len(rides)}+", "ru": "поездок опубликовано", "ba": "сәфәр баҫтырылған"},
        {"value": f"{month_trips}+", "ru": "поездок за месяц", "ba": "айына сәфәр"},
        {"value": f"{drivers}+", "ru": "водителей рядом", "ba": "янәшә водитель"},
    ]
    if reviews:
        avg = round(sum(r.stars for r in reviews) / len(reviews), 1)
        out.append({"value": f"{avg} ★", "ru": "средний рейтинг", "ba": "уртаса рейтинг"})
    else:
        out.append({"value": "0 ₽", "ru": "комиссия сервиса", "ba": "сервис комиссияһы"})
    result = out[:4]
    cache_set_json("landing_stats:v1", result, 300)
    return result


@router.get("/my-routes")
def my_routes(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Частые поездки пользователя — из его истории броней."""
    bookings = session.exec(
        select(Booking).where(Booking.passenger_id == user.id).order_by(Booking.id.desc()).limit(SCAN_LIMIT)
    ).all()
    ride_ids = {b.ride_id for b in bookings}
    rides = session.exec(select(Ride).where(Ride.id.in_(ride_ids))).all() if ride_ids else []  # 1 запрос вместо N
    by_id = {r.id: r for r in rides}
    pairs = []
    for b in bookings:
        r = by_id.get(b.ride_id)
        if r and r.from_city and r.to_city:
            pairs.append((r.from_city, r.to_city))
    cnt = Counter(pairs)
    return [{"from_city": f, "to_city": t, "count": n} for (f, t), n in cnt.most_common(6)]


# Реклама (/ads, /ads/event, /ads/stats) вынесена в routers/ads.py — теперь из БД с админ-управлением.


@router.get("/geocode")
def geocode(q: str = "", user: User = Depends(current_user)):
    """Прокси Яндекс.Геокодера: ключ живёт на сервере, не в APK (раньше клиент слал ключ в URL).
    Отдаём упрощённый список адресов для подсказок «Откуда/Куда». Требуем авторизацию —
    иначе аноним уникальными запросами жжёт бесплатную квоту Яндекса (~1000/день) и подсказки лягут."""
    key = settings.yandex_geocoder_key
    # Обрезаем запрос: настоящий адрес не длиннее пары строк, а необрезанный уезжал целиком
    # в КЛЮЧ кеша Redis. Двадцать промахов в минуту по мегабайтной строке — это сотни мегабайт
    # в Redis за сутки на одном аккаунте, и всё это с суточным TTL (аудит 2026-08-08).
    query = (q or "").strip()[:200]
    if not key or len(query) < 2:
        return {"items": []}
    # Кеш адресов в Redis на сутки. Адреса стабильны, а все ищут одни города
    # (Баймаҡ/Сибай/Уфа) → кеш режет вызовы к Яндексу в разы (бесплатная квота ~1000/день).
    ckey = f"geocode:v1:{query.lower()}"
    cached = cache_get_json(ckey)
    if cached is not None:
        return cached
    # Персональный бюджет — ТОЛЬКО на промах кеша, то есть на настоящий платный вызов
    # (аудит 2026-08-07). Авторизация тут уже есть, но её мало: один вошедший человек
    # уникальными запросами («аа», «аб», «ав»…) выжигает дневную квоту Яндекса за минуты,
    # и подсказки адреса ложатся у ВСЕХ — а поле «Куда» есть в такси, в посылках и в заявке.
    # Общий лимит на IP от этого не спасает: он на порядок выше и рассчитан на другое.
    # Считаем после кеша, чтобы обычный набор текста по знакомым городам бюджет не тратил.
    if user_over_limit("geocode", user.id, settings.rate_limit_geocode_per_min):
        raise herr(429, "Слишком много запросов адресов. Подожди минуту.",
                   "Адрес һорауҙары артыҡ күп. Бер минут көт.")
    try:
        import httpx
        r = httpx.get("https://geocode-maps.yandex.ru/1.x/", params={
            "apikey": key, "geocode": query, "format": "json", "results": 5, "lang": "ru_RU",
        }, timeout=8)
        members = r.json()["response"]["GeoObjectCollection"]["featureMember"]
    except Exception:  # noqa: BLE001
        return {"items": []}     # ошибку НЕ кешируем — попробуем снова в следующий раз
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
    result = {"items": items}
    cache_set_json(ckey, result, 86400)   # сутки
    return result


@router.post("/voice")
async def upload_voice(request: Request, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Загрузка голосового (multipart `file` ИЛИ base64 — обратная совместимость) → media → публичный URL."""
    enforce_upload_quota(session, user.id)
    data, ext = await read_upload(request, settings.audio_ext_set, "m4a", "аудио")
    name = f"{uuid.uuid4().hex}.{ext}"
    # save() синхронный (диск/boto3.put_object) → в async-хендлере оборачиваем в threadpool,
    # иначе заливка МБ (или зависший S3) морозит event-loop воркера (все запросы+WS встают).
    await run_in_threadpool(get_storage().save, f"voice/{name}", data)
    return {"url": public_media_url(f"voice/{name}")}


@router.post("/upload/chat-photo")
async def upload_chat_photo(request: Request, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Фото для чата (multipart `file` ИЛИ base64) → публичная папка media/chat → публичный URL.
    Отдельно от документов водителя (/secure/docs): те приватны, фото чата видит собеседник."""
    enforce_upload_quota(session, user.id)
    data, ext = await read_upload(request, settings.image_ext_set, "jpg", "фото", sniff_image=True)
    name = f"{uuid.uuid4().hex}.{ext}"
    await run_in_threadpool(get_storage().save, f"chat/{name}", data)   # см. upload_voice
    return {"url": public_media_url(f"chat/{name}")}


@router.post("/upload/evidence")
async def upload_evidence(request: Request, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Фото-доказательство спора (порт из pr88) → ПРИВАТНАЯ область evidence/ (не в /media!).
    На фото лица/номера/травмы — отдаёт только /secure/evidence/{name} участникам спора и админу.
    URL из ответа прикладывается к POST /incidents (evidence_urls) или /respond."""
    enforce_upload_quota(session, user.id)
    data, ext = await read_upload(request, settings.image_ext_set, "jpg", "фото", sniff_image=True)
    # Имя НАЧИНАЕТСЯ с id загрузившего — по нему сервер потом отличает «моё фото» от чужого.
    # Без этого чужое имя можно было вписать в свой спор и скачать фото с лицами и травмами
    # (аудит 2026-08-08, волна 9). Тот же приём, что у документов водителя.
    name = f"{user.id}_{uuid.uuid4().hex}.{ext}"
    await run_in_threadpool(get_storage().save, f"evidence/{name}", data)
    return {"url": secure_evidence_url(name)}
