"""Юлдаш API — Фаза 1 (см. docs/backend.md).
Вход по SMS-коду (OTP), поездки, заявки, брони, матчинг, статус водителя.
SMS пока мок: код пишется в лог и (в dev) возвращается в ответе.
"""
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
from typing import List, Optional
import asyncio
import base64
import math
import os
import threading
import uuid

try:
    import redis.asyncio as aioredis   # WS pub/sub между воркерами (опционально)
except Exception:  # noqa: BLE001 — библиотеки может не быть в dev
    aioredis = None

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from sqlmodel import Session, select
import json
import time
from urllib.parse import urlencode

from .config import settings
from .db import engine, get_session, init_db
from .models import (
    AdEvent, Block, Booking, BookingStatus, DeviceToken, DriverProfile, Message, OtpCode, Rating, Report,
    Ride, RideCategory, RideRequest, RideStatus, SosEvent, TgAuth, TripShare,
    TrustedContact, User, UserRole,
)
from .security import current_user, gen_otp, make_token


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.validate_production()
    init_db()
    with Session(engine) as session:
        if settings.seed_demo:
            _seed_demo(session)
    # Redis pub/sub для WS-чата между воркерами (если настроен и библиотека есть).
    global _redis_pub
    sub_client = None
    sub_task = None
    if settings.redis_url and aioredis is not None:
        try:
            _redis_pub = aioredis.from_url(settings.redis_url, decode_responses=True)
            await _redis_pub.ping()
            sub_client = aioredis.from_url(settings.redis_url, decode_responses=True)
            sub_task = asyncio.create_task(_chat_subscribe_loop(sub_client))
            print("[REDIS] WS pub/sub активен")
        except Exception as e:  # noqa: BLE001 — Redis недоступен → локальный режим, не падаем
            print(f"[REDIS] недоступен ({e}) → WS локальный режим")
            _redis_pub = None
    yield
    # Закрытие
    if sub_task is not None:
        sub_task.cancel()
    for c in (sub_client, _redis_pub):
        if c is not None:
            try:
                await c.aclose()
            except Exception:
                pass


app = FastAPI(title="Yuldash API", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_methods=["GET", "POST", "OPTIONS"],   # API использует только их
    allow_headers=["Authorization", "Content-Type"],
)

# Медиа. Голосовые — публично (/media). Документы водителя (права/авто) — ПРИВАТНО (вне /media),
# отдаются только админу/владельцу через /secure/docs/{name} (152-ФЗ — персональные документы).
MEDIA_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "media")
VOICE_DIR = os.path.join(MEDIA_DIR, "voice")
PRIVATE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "private")
DOC_DIR = os.path.join(PRIVATE_DIR, "docs")
os.makedirs(VOICE_DIR, exist_ok=True)
os.makedirs(DOC_DIR, exist_ok=True)
app.mount("/media", StaticFiles(directory=MEDIA_DIR), name="media")


def _public_media_url(path: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/media/{path.lstrip('/')}"


def _secure_docs_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/docs/{name}"


def _decode_upload_b64(raw: str, allowed_ext: set[str], default_ext: str, kind: str) -> tuple[bytes, str]:
    """Безопасная обработка base64 upload: whitelist расширений + лимит размера."""
    ext = "".join(c for c in default_ext.lower() if c.isalnum()) or default_ext
    if "," in raw and raw.strip().lower().startswith("data:"):
        raw = raw.split(",", 1)[1]
    try:
        data = base64.b64decode(raw, validate=True)
    except Exception:
        raise HTTPException(400, f"Некорректный файл: {kind}")
    if not data:
        raise HTTPException(400, f"Пустой файл: {kind}")
    if len(data) > settings.max_upload_bytes:
        raise HTTPException(413, f"Файл слишком большой: максимум {settings.max_upload_mb} МБ")
    if ext not in allowed_ext:
        raise HTTPException(400, f"Недопустимый тип файла: .{ext}")
    return data, ext


def _booking_and_ride_for_user(session: Session, booking_id: int, user: User) -> tuple[Booking, Ride]:
    """Вернуть бронь и поездку, если пользователь — пассажир или водитель этой брони."""
    booking = session.get(Booking, booking_id)
    if not booking:
        raise HTTPException(404, "Бронь не найдена")
    ride = session.get(Ride, booking.ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    if booking.passenger_id != user.id and ride.driver_id != user.id:
        raise HTTPException(403, "Нет доступа к этой брони")
    return booking, ride


# ---- Push (FCM) ----
_fcm_app = None


def _send_push(session: Session, user_id: int, title: str, body: str) -> None:
    """Push на все устройства пользователя. Тихо ничего, если Firebase не настроен (нет ключа)."""
    if not settings.firebase_credentials:
        return
    try:
        global _fcm_app
        import firebase_admin
        from firebase_admin import credentials, messaging
        if _fcm_app is None:
            _fcm_app = firebase_admin.initialize_app(credentials.Certificate(settings.firebase_credentials))
        tokens = [d.token for d in session.exec(select(DeviceToken).where(DeviceToken.user_id == user_id)).all()]
        for t in tokens:
            try:
                messaging.send(messaging.Message(
                    notification=messaging.Notification(title=title, body=body),
                    token=t,
                ))
            except Exception as e:  # noqa: BLE001
                print(f"[FCM] send error: {e}")
    except Exception as e:  # noqa: BLE001
        print(f"[FCM] init error: {e}")


def _user_bookings(session: Session, user: User) -> list:
    """Все брони пользователя — как пассажир И как водитель (по его поездкам), без дублей."""
    bookings = list(session.exec(select(Booking).where(Booking.passenger_id == user.id)).all())
    my_ride_ids = list(session.exec(select(Ride.id).where(Ride.driver_id == user.id)).all())
    if my_ride_ids:
        bookings += session.exec(select(Booking).where(Booking.ride_id.in_(my_ride_ids))).all()
    seen: set = set()
    out: list = []
    for b in bookings:
        if b.id in seen:
            continue
        seen.add(b.id)
        out.append(b)
    return out


@app.get("/health")
def health():
    return {"status": "ok", "env": settings.env}


# ----------------------------- Авторизация (телефон + OTP) -----------------------------
class PhoneIn(BaseModel):
    phone: str


class VerifyIn(BaseModel):
    phone: str
    code: str
    name: str = Field("", max_length=120)


def _mask_phone(phone: str) -> str:
    """Маска телефона для логов (152-ФЗ): +7****1234. Полный номер в лог не пишем."""
    d = "".join(c for c in (phone or "") if c.isdigit())
    return f"+{d[0]}****{d[-4:]}" if len(d) >= 5 else "+****"


def _send_text(phone: str, text: str) -> None:
    """Отправка произвольного SMS (SOS, статусы близким). smsru → реально; иначе/фоллбэк — в лог."""
    mp = _mask_phone(phone)
    if settings.sms_provider == "smsru" and settings.sms_ru_api_id:
        try:
            import httpx
            params = {"api_id": settings.sms_ru_api_id, "to": phone, "msg": text, "json": 1}
            if settings.sms_from:
                params["from"] = settings.sms_from
            data = httpx.get("https://sms.ru/sms/send", params=params, timeout=10).json()
            sms = (data.get("sms") or {}).get(phone, {})
            ok = sms.get("status_code") == 100
            print(f"[SMS] {mp}: smsru sent={ok} ({sms.get('status_code')} {str(sms.get('status_text', ''))[:80]})")
            if not ok and not settings.is_prod:
                print(f"[SMS-FALLBACK] {mp}: {text}")
        except Exception as e:  # noqa: BLE001
            print(f"[SMS] {mp}: smsru error {e}")
            if not settings.is_prod:
                print(f"[SMS-FALLBACK] {mp}: {text}")
    else:
        print(f"[SMS-MOCK] {mp}: {text}")


def _send_texts_bg(items: list) -> None:
    """Фоновая рассылка SMS (SOS, статусы близким) ПОСЛЕ ответа клиенту.
    items: список (phone, text). Каждая отправка блокирует до 10с (таймаут httpx) —
    в обработчике это держало бы ответ; здесь крутится в BackgroundTasks, юзер не ждёт."""
    for phone, text in items:
        try:
            _send_text(phone, text)
        except Exception as e:  # noqa: BLE001 — фон не должен падать
            print(f"[SMS-BG] {_mask_phone(phone)}: error {e}")


def _send_sms(phone: str, code: str) -> None:
    """Отправка OTP. `smsru` — реально через sms.ru; иначе мок (код в лог).
    Если sms.ru НЕ отправил (напр. нет одобренного отправителя) — код падает в лог,
    чтобы вход работал на период настройки отправителя."""
    mp = _mask_phone(phone)
    if settings.sms_provider == "smsru" and settings.sms_ru_api_id:
        try:
            import httpx
            params = {"api_id": settings.sms_ru_api_id, "to": phone, "msg": f"Yuldash: kod {code}", "json": 1}
            if settings.sms_from:
                params["from"] = settings.sms_from
            data = httpx.get("https://sms.ru/sms/send", params=params, timeout=10).json()
            sms = (data.get("sms") or {}).get(phone, {})
            ok = sms.get("status_code") == 100
            print(f"[SMS] {mp}: smsru sent={ok} ({sms.get('status_code')} {str(sms.get('status_text', ''))[:80]})")
            if not ok:
                if settings.is_prod:
                    raise HTTPException(502, "SMS не отправлено")
                print(f"[OTP] {mp} -> {code}")  # фоллбэк: SMS не ушла → код в лог (только dev)
        except Exception as e:  # noqa: BLE001
            print(f"[SMS] {mp}: smsru error {e}")
            if settings.is_prod:
                raise HTTPException(502, "SMS не отправлено")
            print(f"[OTP] {mp} -> {code}")  # фоллбэк при ошибке сети (только dev)
    else:
        if settings.is_prod:
            # SMS заморожен в проде — основной вход через мессенджеры. Понятный ответ вместо 500.
            raise HTTPException(503, "SMS-вход временно недоступен. Войдите через мессенджер.")
        print(f"[OTP] {mp} -> {code}")  # мок/dev — код в логе


@app.post("/auth/request-code")
def request_code(body: PhoneIn, session: Session = Depends(get_session)):
    # Throttle: ≤3 кода в минуту на номер (анти-флуд: расходы на SMS + защита от забивания OtpCode).
    recent = session.exec(
        select(OtpCode).where(
            OtpCode.phone == body.phone,
            OtpCode.created_at > datetime.utcnow() - timedelta(seconds=60),
        )
    ).all()
    if len(recent) >= 3:
        raise HTTPException(429, "Слишком часто. Подожди минуту и попробуй снова.")
    code = gen_otp()
    session.add(OtpCode(
        phone=body.phone, code=code,
        expires_at=datetime.utcnow() + timedelta(seconds=settings.otp_ttl_sec),
    ))
    session.commit()
    _send_sms(body.phone, code)
    resp = {"sent": True}
    if settings.env == "dev":
        resp["dev_code"] = code  # в dev возвращаем код, чтобы тестировать без SMS
    return resp


@app.post("/auth/verify")
def verify(body: VerifyIn, session: Session = Depends(get_session)):
    otp = session.exec(
        select(OtpCode).where(OtpCode.phone == body.phone).order_by(OtpCode.id.desc())
    ).first()
    if not otp or otp.expires_at < datetime.utcnow():
        raise HTTPException(400, "Неверный или просроченный код")
    if otp.attempts >= 5:                       # защита от перебора 4-значного кода
        raise HTTPException(429, "Слишком много попыток. Запроси новый код.")
    if otp.code != body.code:
        otp.attempts += 1
        session.add(otp)
        session.commit()
        raise HTTPException(400, "Неверный или просроченный код")
    user = session.exec(select(User).where(User.phone == body.phone)).first()
    if not user:
        user = User(phone=body.phone, name=body.name or "Пользователь", verified=True)
        session.add(user)
        session.commit()
        session.refresh(user)
    return {"access_token": make_token(user.id), "token_type": "bearer", "user": user}


# ==================== TELEGRAM-ВХОД (бот, код подтверждения) ====================
# Поток как у SMS, но 4-значный код шлёт Telegram-бот:
# 1) app: POST /auth/tg/start → request_id; app открывает t.me/<bot>?start=<request_id>
# 2) юзер жмёт Старт → Telegram шлёт /start <request_id> на вебхук
# 3) вебхук привязывает ПОДТВЕРЖДЁННЫЙ Telegram'ом from.id к request_id, генерит 4-значный
#    код и присылает его юзеру в чат бота
# 4) app: POST /auth/tg/verify {request_id, code} → сервер сверяет код → JWT
# Безопасность: request_id — случайный UUID (не угадать); код 4 цифры, живёт 5 мин,
# ≤5 попыток; telegram_id подтверждён Telegram'ом. За чужого войти нельзя.

TG_CODE_TTL_SEC = 300
TG_MAX_ATTEMPTS = 5


class TgStartOut(BaseModel):
    request_id: str


@app.post("/auth/tg/start", response_model=TgStartOut)
def tg_start(session: Session = Depends(get_session)):
    req = uuid.uuid4().hex
    session.add(TgAuth(
        request_id=req, status="waiting",
        expires_at=datetime.utcnow() + timedelta(seconds=TG_CODE_TTL_SEC),
    ))
    session.commit()
    return TgStartOut(request_id=req)


@app.post("/telegram/webhook")
async def telegram_webhook(request: Request, x_telegram_bot_api_secret_token: str = Header(default="")):
    """Telegram шлёт сюда апдейты. На /start <request_id> привязываем юзера и шлём код."""
    if settings.telegram_webhook_secret and x_telegram_bot_api_secret_token != settings.telegram_webhook_secret:
        raise HTTPException(403, "bad secret")
    update = await request.json()
    msg = update.get("message") or {}
    text = msg.get("text") or ""
    frm = msg.get("from") or {}
    chat = msg.get("chat") or {}
    reply = None
    if text.startswith("/start") and frm.get("id"):
        parts = text.split(maxsplit=1)
        req = parts[1].strip() if len(parts) > 1 else ""
        with Session(engine) as s:
            row = s.exec(select(TgAuth).where(TgAuth.request_id == req)).first() if req else None
            if row and row.status in ("waiting", "sent") and row.expires_at > datetime.utcnow():
                code = gen_otp()
                row.telegram_id = str(frm["id"])
                row.username = frm.get("username", "") or ""
                row.first_name = frm.get("first_name", "") or ""
                row.code = code
                row.status = "sent"
                s.add(row)
                s.commit()
                reply = f"Твой код для входа в Юлдаш: {code}\nВведи его в приложении. Код живёт 5 минут."
            else:
                reply = "Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код."
    if reply is not None:
        return {"method": "sendMessage", "chat_id": chat.get("id"), "text": reply}
    return {"ok": True}


class TgVerifyIn(BaseModel):
    request_id: str
    code: str


@app.post("/auth/tg/verify")
def tg_verify(body: TgVerifyIn, session: Session = Depends(get_session)):
    # Статусы различимы клиентом для разных сообщений: 409 ещё не получен, 410 истёк,
    # 429 много попыток, 400 неверный код.
    row = session.exec(select(TgAuth).where(TgAuth.request_id == body.request_id)).first()
    if not row or row.status != "sent" or not row.telegram_id or not row.code:
        raise HTTPException(409, "Сначала получи код в Telegram")
    if row.expires_at < datetime.utcnow():
        raise HTTPException(410, "Код истёк. Получи новый.")
    if row.attempts >= TG_MAX_ATTEMPTS:
        raise HTTPException(429, "Слишком много попыток. Получи новый код.")
    if body.code.strip() != row.code:
        row.attempts += 1
        session.add(row)
        session.commit()
        raise HTTPException(400, "Неверный код")
    row.status = "used"
    session.add(row)
    user = session.exec(select(User).where(User.telegram_id == row.telegram_id)).first()
    if not user:
        user = User(
            phone=f"tg{row.telegram_id}",   # плейсхолдер (не настоящий номер), уникален по telegram_id
            name=row.first_name or row.username or "Telegram",
            telegram_id=row.telegram_id,
            verified=True,
        )
        session.add(user)
    session.commit()
    session.refresh(user)
    return {"access_token": make_token(user.id), "token_type": "bearer", "user": user}


# VK / WhatsApp вход — ОТКЛЮЧЕНО до безопасной реализации.
# Прежние версии выдавали токен по непроверенному vk_id/телефону (whatsapp-callback —
# угон аккаунта: любой с чужим номером получал токен). Включим, когда будет:
#   VK  — серверный OAuth code-exchange (/auth/vk/callback) с проверкой на стороне VK;
#   WA  — WhatsApp Business API с подтверждением номера.
@app.post("/auth/vk-callback")
def vk_callback():
    raise HTTPException(501, "VK-вход ещё не подключён")


@app.post("/auth/whatsapp-callback")
def whatsapp_callback():
    raise HTTPException(501, "WhatsApp-вход ещё не подключён")


@app.get("/me")
def me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    avg, cnt = _user_rating(session, user.id)
    return {**user.model_dump(), "rating": round(avg, 1) if cnt > 0 else None, "rating_count": cnt}


class PushTokenIn(BaseModel):
    token: str


@app.post("/push/register")
def push_register(body: PushTokenIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Регистрация/перепривязка FCM-токена устройства к текущему пользователю."""
    if not body.token.strip():
        raise HTTPException(400, "Пустой токен")
    existing = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
    if existing:
        existing.user_id = user.id
        session.add(existing)
    else:
        session.add(DeviceToken(user_id=user.id, token=body.token))
    session.commit()
    return {"ok": True}


# ----------------------------- Поездки -----------------------------
class RideIn(BaseModel):
    from_city: str
    to_city: str
    depart_at: datetime
    seats_total: int = 3
    price: int = 0
    category: RideCategory = RideCategory.regular
    comment: str = Field("", max_length=2000)
    pickup: str = Field("", max_length=500)
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    pets_allowed: bool = False
    child_seat: bool = False
    women_only: bool = False
    smoking: bool = False
    baggage: bool = False
    air_conditioner: bool = False
    recurrence: str = "none"          # none / daily / weekdays / weekly


class RideOut(BaseModel):
    """Поездка + витрина водителя (имя/рейтинг/авто) — чтобы приложение рисовало карточку."""
    id: int
    driver_id: int
    from_city: str
    to_city: str
    depart_at: datetime
    seats_total: int
    seats_left: int
    price: int
    category: RideCategory
    comment: str
    pickup: str = ""
    pickup_lat: Optional[float] = None
    pickup_lng: Optional[float] = None
    pets_allowed: bool = False
    child_seat: bool = False
    women_only: bool = False
    smoking: bool = False
    baggage: bool = False
    air_conditioner: bool = False
    status: RideStatus
    driver_name: str
    driver_rating: float
    driver_verified: bool
    driver_car: str


def _user_rating(session: Session, user_id: int) -> tuple[float, int]:
    """Средний рейтинг пользователя из реальных оценок (звёзды) + их число."""
    rows = list(session.exec(select(Rating.stars).where(Rating.ratee_id == user_id)).all())
    return (sum(rows) / len(rows), len(rows)) if rows else (0.0, 0)


def _drivers_bundle(session: Session, driver_ids: set) -> tuple[dict, dict, dict]:
    """Батч водителей/профилей/рейтингов для списка поездок — против N+1
    (раньше _ride_out делал 3 запроса НА КАЖДУЮ поездку)."""
    if not driver_ids:
        return {}, {}, {}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(driver_ids))).all()}
    profiles = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(driver_ids))).all()}
    stars_by_driver: dict = {}
    for ratee_id, stars in session.exec(select(Rating.ratee_id, Rating.stars).where(Rating.ratee_id.in_(driver_ids))).all():
        stars_by_driver.setdefault(ratee_id, []).append(stars)
    rating_agg = {rid: (sum(s) / len(s), len(s)) for rid, s in stars_by_driver.items()}
    return users, profiles, rating_agg


def _ride_out_with(ride: Ride, users: dict, profiles: dict, rating_agg: dict) -> RideOut:
    """RideOut из предзагруженных батчей (без запросов в БД)."""
    drv = users.get(ride.driver_id)
    prof = profiles.get(ride.driver_id)
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    avg, cnt = rating_agg.get(ride.driver_id, (0.0, 0))
    rating = round(avg, 1) if cnt > 0 else (prof.rating if prof else 5.0)  # реальный рейтинг; до отзывов — сид
    return RideOut(
        **ride.model_dump(exclude={"created_at"}),
        driver_name=(drv.name if drv else "Водитель"),
        driver_rating=rating,
        driver_verified=(drv.verified if drv else False),
        driver_car=car,
    )


def _rides_out(rides: list, session: Session) -> list:
    """Список поездок → list[RideOut] одним батчем (3 запроса вместо 3×N)."""
    users, profiles, rating_agg = _drivers_bundle(session, {r.driver_id for r in rides})
    return [_ride_out_with(r, users, profiles, rating_agg) for r in rides]


def _ride_out(ride: Ride, session: Session) -> RideOut:
    """Одна поездка → RideOut (обёртка над батчем для единичных вызовов)."""
    return _rides_out([ride], session)[0]


# Координаты городов Башкортостана (approx) — для гео-дистанции «сколько в N км от тебя».
CITY_COORDS = {
    "Баймаҡ": (52.591, 58.317), "Баймак": (52.591, 58.317),
    "Сибай": (52.716, 58.664),
    "Уфа": (54.735, 55.958),
    "Темясово": (52.972, 58.160),
    "Учалы": (54.304, 59.430),
    "Магнитогорск": (53.412, 58.984),
    "Ургаза": (52.850, 58.300),
    "Зилаир": (52.230, 57.443),
    "Акъяр": (51.880, 58.198),
}


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _seed_demo(session: Session) -> None:
    """Демо-поездки в пустой БД — чтобы экран «Ближайшие поездки» был живым."""
    if session.exec(select(Ride)).first():
        return
    demo = [
        ("Ильдар", 4.8, True, "Lada", "Vesta", "Баймаҡ", "Сибай", 350, 3),
        ("Айгуль", 4.9, True, "Kia", "Rio", "Темясово", "Уфа", 1400, 3),
        ("Рустам", 4.6, False, "Renault", "Logan", "Сибай", "Баймаҡ", 300, 2),
        ("Гүзәл", 5.0, True, "Hyundai", "Solaris", "Учалы", "Магнитогорск", 800, 4),
    ]
    base = datetime.utcnow() + timedelta(hours=3)
    for i, (name, rating, verified, make, model, frm, to, price, seats) in enumerate(demo):
        u = User(phone=f"+7000000000{i}", name=name, role=UserRole.driver, verified=verified)
        session.add(u)
        session.commit()
        session.refresh(u)
        session.add(DriverProfile(
            user_id=u.id, rating=rating, trips_count=42,
            car_make=make, car_model=model, seats=seats,
        ))
        session.add(Ride(
            driver_id=u.id, from_city=frm, to_city=to,
            depart_at=base + timedelta(hours=i * 6),
            seats_total=seats, seats_left=seats, price=price,
            category=RideCategory.regular,
        ))
    session.commit()


@app.post("/rides", response_model=Ride)
def create_ride(body: RideIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    ride = Ride(driver_id=user.id, seats_left=body.seats_total, **body.model_dump())
    session.add(ride)
    # Регулярная поездка: сразу создаём ближайшие 4 рейса серии (реальные, бронируемые).
    if body.recurrence and body.recurrence != "none":
        step = {"weekly": timedelta(weeks=1)}.get(body.recurrence, timedelta(days=1))
        dt = body.depart_at
        made = 0
        guard = 0
        while made < 4 and guard < 40:
            guard += 1
            dt = dt + step
            if body.recurrence == "weekdays" and dt.weekday() >= 5:   # пропускаем сб/вс
                continue
            session.add(Ride(driver_id=user.id, seats_left=body.seats_total, **{**body.model_dump(), "depart_at": dt}))
            made += 1
    session.commit()
    session.refresh(ride)
    return ride


@app.get("/rides", response_model=List[RideOut])
def search_rides(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    category: Optional[RideCategory] = None,
    pets_allowed: Optional[bool] = None,
    child_seat: Optional[bool] = None,
    women_only: Optional[bool] = None,
    baggage: Optional[bool] = None,
    limit: int = 100,
    offset: int = 0,
    session: Session = Depends(get_session),
):
    # Пагинация (limit/offset) — чтобы не отдавать всю таблицу разом при росте числа поездок.
    # Старый клиент без параметров получит первые 100 (как раньше «всё» при малом объёме).
    limit = max(1, min(limit, 200))
    offset = max(0, offset)
    q = select(Ride).where(Ride.status == RideStatus.active)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    if category:
        q = q.where(Ride.category == category)
    if pets_allowed:
        q = q.where(Ride.pets_allowed == True)  # noqa: E712
    if child_seat:
        q = q.where(Ride.child_seat == True)  # noqa: E712
    if women_only:
        q = q.where(Ride.women_only == True)  # noqa: E712
    if baggage:
        q = q.where(Ride.baggage == True)  # noqa: E712
    rides = session.exec(q.order_by(Ride.depart_at).offset(offset).limit(limit)).all()
    return _rides_out(rides, session)


@app.get("/rides/price_hint")
def price_hint(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    session: Session = Depends(get_session),
):
    """Ориентир цены по маршруту: средняя цена поездок (price>0). Подсказка водителю, не навязываем."""
    q = select(Ride.price).where(Ride.price > 0)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    prices = [p for p in session.exec(q).all() if p and p > 0]
    if not prices:
        return {"avg": 0, "count": 0}
    return {"avg": round(sum(prices) / len(prices)), "count": len(prices)}


@app.get("/rides/near")
def rides_near(
    from_city: Optional[str] = None,
    to_city: Optional[str] = None,
    lat: Optional[float] = None,
    lng: Optional[float] = None,
    radius_km: Optional[float] = None,
    session: Session = Depends(get_session),
):
    """Ближайшие поездки по маршруту клиента, отсортированы по времени выезда (ранняя — первой).
    Если переданы координаты клиента (lat/lng) — добавляем дистанцию до точки выезда и (опц.) фильтр по радиусу.
    Сценарий: водитель отменил/сломался → клиент видит ближайшую по времени машину на своём маршруте и уезжает."""
    q = select(Ride).where(Ride.status == RideStatus.active, Ride.seats_left > 0)
    if from_city:
        q = q.where(Ride.from_city.contains(from_city))
    if to_city:
        q = q.where(Ride.to_city.contains(to_city))
    rides = session.exec(q.order_by(Ride.depart_at)).all()  # по времени выезда ↑
    users, profiles, rating_agg = _drivers_bundle(session, {r.driver_id for r in rides})
    items: list = []
    for r in rides:
        dist = None
        if lat is not None and lng is not None:
            c = CITY_COORDS.get(r.from_city)
            if c:
                dist = round(_haversine_km(lat, lng, c[0], c[1]), 1)
        if radius_km is not None and dist is not None and dist > radius_km:
            continue
        out = _ride_out_with(r, users, profiles, rating_agg).model_dump()
        out["distance_km"] = dist
        items.append(out)
    return {"count": len(items), "items": items}


@app.get("/rides/{ride_id}", response_model=Ride)
def get_ride(ride_id: int, session: Session = Depends(get_session)):
    ride = session.get(Ride, ride_id)
    if not ride:
        raise HTTPException(404, "Поездка не найдена")
    return ride


# ----------------------------- Заявки -----------------------------
class RequestIn(BaseModel):
    from_city: str
    to_city: str
    desired_at: Optional[datetime] = None
    seats: int = 1
    max_price: Optional[int] = None
    category: RideCategory = RideCategory.regular
    with_kids: bool = False
    baggage: bool = False
    comment: str = Field("", max_length=2000)
    for_relative_name: Optional[str] = Field(None, max_length=120)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)


@app.post("/requests", response_model=RideRequest)
def create_request(body: RequestIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = RideRequest(passenger_id=user.id, **body.model_dump())
    session.add(req)
    session.commit()
    session.refresh(req)
    return req


@app.get("/requests/mine", response_model=List[RideRequest])
def my_requests(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(RideRequest).where(RideRequest.passenger_id == user.id)).all()


# ----------------------------- Матчинг (см. backend.md §4) -----------------------------
@app.get("/match/rides", response_model=List[Ride])
def match_rides(request_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    req = session.get(RideRequest, request_id)
    if not req:
        raise HTTPException(404, "Заявка не найдена")
    if req.passenger_id != user.id:
        raise HTTPException(403, "Нет доступа к этой заявке")
    q = select(Ride).where(
        Ride.status == RideStatus.active,
        Ride.from_city.contains(req.from_city),
        Ride.to_city.contains(req.to_city),
        Ride.seats_left >= req.seats,
        Ride.category == req.category,
    )
    return session.exec(q.order_by(Ride.depart_at)).all()


# ----------------------------- Брони -----------------------------
class BookIn(BaseModel):
    ride_id: int
    seats: int = 1


def _is_blocked(session: Session, a: int, b: int) -> bool:
    """Есть ли блокировка между a и b в любую сторону."""
    rows = session.exec(select(Block).where(Block.user_id.in_([a, b]))).all()
    return any(
        (r.user_id == a and r.blocked_user_id == b) or (r.user_id == b and r.blocked_user_id == a)
        for r in rows
    )


@app.post("/bookings", response_model=Booking)
def book(body: BookIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.seats < 1:
        raise HTTPException(400, "Количество мест должно быть больше 0")
    # FOR UPDATE: блокируем строку поездки на время транзакции → нет овербукинга при гонке.
    ride = session.exec(select(Ride).where(Ride.id == body.ride_id).with_for_update()).first()
    if not ride or ride.status != RideStatus.active:
        raise HTTPException(400, "Поездка недоступна")
    if ride.driver_id == user.id:
        raise HTTPException(400, "Нельзя бронировать собственную поездку")
    if _is_blocked(session, user.id, ride.driver_id):
        raise HTTPException(403, "Бронь недоступна")
    if ride.seats_left < body.seats:
        raise HTTPException(400, "Не хватает мест")
    booking = Booking(
        ride_id=ride.id, passenger_id=user.id, seats=body.seats,
        price=ride.price * body.seats, boarding_code=gen_otp(),
    )
    ride.seats_left -= body.seats
    session.add(booking)
    session.add(ride)
    session.commit()
    session.refresh(booking)
    # Push водителю о новой брони.
    _send_push(session, ride.driver_id, "Новая бронь", f"{user.name or 'Пассажир'}: {ride.from_city} → {ride.to_city}, мест {body.seats}")
    return booking


@app.post("/bookings/{booking_id}/confirm", response_model=Booking)
def confirm_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = _booking_and_ride_for_user(session, booking_id, user)
    if ride.driver_id != user.id:
        raise HTTPException(403, "Подтвердить бронь может только водитель")
    booking.status = BookingStatus.confirmed
    session.add(booking)
    session.commit()
    session.refresh(booking)
    return booking


@app.post("/bookings/{booking_id}/cancel", response_model=Booking)
def cancel_booking(booking_id: int, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отмена поездки пассажиром или водителем. Места возвращаются в поездку."""
    booking, ride = _booking_and_ride_for_user(session, booking_id, user)
    if booking.status not in (BookingStatus.cancelled, BookingStatus.done):
        booking.status = BookingStatus.cancelled
        ride.seats_left = min(ride.seats_total, ride.seats_left + booking.seats)  # вернуть освобождённые места
        session.add(booking)
        session.add(ride)
        session.commit()
        session.refresh(booking)
    return booking


@app.get("/bookings/mine", response_model=List[Booking])
def my_bookings(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(Booking).where(Booking.passenger_id == user.id)).all()


@app.get("/driver/bookings")
def driver_bookings(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Брони на поездки текущего водителя — чтобы оценить пассажиров после поездки."""
    my_ride_ids = [r.id for r in session.exec(select(Ride).where(Ride.driver_id == user.id)).all()]
    if not my_ride_ids:
        return []
    bookings = session.exec(select(Booking).where(Booking.ride_id.in_(my_ride_ids))).all()
    out: list = []
    for b in bookings:
        ride = session.get(Ride, b.ride_id)
        passenger = session.get(User, b.passenger_id)
        avg, cnt = _user_rating(session, b.passenger_id)
        out.append({
            "booking_id": b.id,
            "passenger_name": (passenger.name if passenger else "Пассажир"),
            "passenger_rating": (round(avg, 1) if cnt > 0 else None),
            "route": (f"{ride.from_city} → {ride.to_city}" if ride else ""),
            "status": b.status,
        })
    return out


# ----------------------------- Водитель -----------------------------
class OnlineIn(BaseModel):
    online: bool


@app.post("/driver/online", response_model=DriverProfile)
def driver_online(body: OnlineIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    if not dp:
        dp = DriverProfile(user_id=user.id, online=body.online)
    else:
        dp.online = body.online
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return dp


# ----------------------------- Водитель: профиль авто и проверка -----------------------------
class PhotoIn(BaseModel):
    photo_b64: str
    ext: str = "jpg"


@app.post("/upload/photo")
def upload_photo(body: PhotoIn, user: User = Depends(current_user)):
    """Загрузка фото документа/авто → приватная папка → защищённый URL (только админ/владелец)."""
    ext = "".join(c for c in body.ext.lower() if c.isalnum()) or "jpg"
    data, ext = _decode_upload_b64(body.photo_b64, settings.image_ext_set, ext, "фото")
    name = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(DOC_DIR, name), "wb") as f:
        f.write(data)
    return {"url": _secure_docs_url(name)}


@app.get("/secure/docs/{name}")
def secure_doc(name: str, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отдать фото документа водителя. Доступ: админ ИЛИ владелец этого документа."""
    safe = os.path.basename(name)   # защита от path traversal
    if user.role != UserRole.admin:
        prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
        owns = prof is not None and (safe in (prof.license_url or "") or safe in (prof.car_photo_url or ""))
        if not owns:
            raise HTTPException(403, "Нет доступа к документу")
    path = os.path.join(DOC_DIR, safe)
    if not os.path.isfile(path):
        raise HTTPException(404, "Файл не найден")
    return FileResponse(path)


class DriverProfileIn(BaseModel):
    car_make: str = ""
    car_model: str = ""
    car_color: str = ""
    car_plate: str = ""
    seats: int = 4


def _get_or_create_profile(session: Session, user_id: int) -> DriverProfile:
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
    if not dp:
        dp = DriverProfile(user_id=user_id)
        session.add(dp)
        session.commit()
        session.refresh(dp)
    return dp


@app.post("/driver/profile", response_model=DriverProfile)
def set_driver_profile(body: DriverProfileIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель заполняет реальные данные авто (вместо захардкоженных)."""
    dp = _get_or_create_profile(session, user.id)
    dp.car_make = body.car_make
    dp.car_model = body.car_model
    dp.car_color = body.car_color
    dp.car_plate = body.car_plate
    dp.seats = body.seats
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return dp


class DriverVerifyIn(BaseModel):
    license_url: str = ""
    car_photo_url: str = ""


@app.post("/driver/verify", response_model=DriverProfile)
def submit_driver_verify(body: DriverVerifyIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Водитель отправляет документы на проверку → статус 'pending' (модерацию делает админ)."""
    if not body.license_url or not body.car_photo_url:
        raise HTTPException(400, "Нужны фото прав и фото автомобиля")
    dp = _get_or_create_profile(session, user.id)
    dp.license_url = body.license_url
    dp.car_photo_url = body.car_photo_url
    dp.docs_status = "pending"
    dp.verify_submitted_at = datetime.utcnow()
    session.add(dp)
    session.commit()
    session.refresh(dp)
    return dp


@app.get("/driver/status")
def driver_status(user: User = Depends(current_user), session: Session = Depends(get_session)):
    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    return {
        "docs_status": dp.docs_status if dp else "none",
        "verified": user.verified,
        "car_make": dp.car_make if dp else "",
        "car_model": dp.car_model if dp else "",
        "car_color": dp.car_color if dp else "",
        "car_plate": dp.car_plate if dp else "",
        "seats": dp.seats if dp else 4,
        "license_url": dp.license_url if dp else "",
        "car_photo_url": dp.car_photo_url if dp else "",
    }


class ModerateIn(BaseModel):
    approve: bool = True


@app.post("/admin/drivers/{user_id}/moderate")
def moderate_driver(user_id: int, body: ModerateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Модерация водителя админом: подтвердить (verified=True) или отклонить."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    target = session.get(User, user_id)
    if not target:
        raise HTTPException(404, "Пользователь не найден")
    dp = _get_or_create_profile(session, user_id)
    if body.approve:
        target.verified = True
        dp.docs_status = "verified"
    else:
        target.verified = False
        dp.docs_status = "rejected"
    session.add(target)
    session.add(dp)
    session.commit()
    return {"user_id": user_id, "verified": target.verified, "docs_status": dp.docs_status}


# ----------------------------- Чат (сообщения по брони) -----------------------------
class MessageIn(BaseModel):
    text: str = Field("", max_length=4000)
    voice_url: Optional[str] = None
    transcript: Optional[str] = Field(None, max_length=4000)


# ==================== WebSocket для чата ====================
# При НЕСКОЛЬКИХ воркерах участники одной брони могут попасть на разные процессы.
# WS-объекты живут в одном процессе → прямой broadcast их не достаёт. Решение: Redis
# pub/sub. Сообщение публикуется в канал "chat", КАЖДЫЙ воркер слушает канал и
# доставляет своим локальным соединениям. Без Redis (один воркер/dev) — локальный режим.
_CHAT_CHANNEL = "chat"
_redis_pub = None   # клиент для publish (заполняется в lifespan, если есть Redis)


class ConnectionManager:
    """WebSocket-соединения чата. Локальные коннекты в этом процессе + раздача через Redis."""
    def __init__(self):
        self.active_connections: dict = {}

    def register(self, booking_id: int, websocket: WebSocket):
        """Зарегистрировать УЖЕ принятое (accept) и авторизованное соединение."""
        self.active_connections.setdefault(booking_id, []).append(websocket)

    def disconnect(self, booking_id: int, websocket: WebSocket):
        if booking_id in self.active_connections:
            try:
                self.active_connections[booking_id].remove(websocket)
            except ValueError:
                pass
            if not self.active_connections[booking_id]:
                self.active_connections.pop(booking_id, None)

    async def local_broadcast(self, booking_id: int, data: dict):
        """Доставить локальным соединениям этого процесса."""
        for connection in list(self.active_connections.get(booking_id, [])):
            try:
                await connection.send_json(data)
            except Exception:
                pass

    async def broadcast(self, booking_id: int, data: dict):
        """Раздать сообщение всем участникам брони на ЛЮБОМ воркере.
        Есть Redis → publish (подписчик доставит на всех, включая этот воркер).
        Нет Redis → доставляем только локально (режим одного воркера)."""
        if _redis_pub is not None:
            try:
                await _redis_pub.publish(_CHAT_CHANNEL, json.dumps({"booking_id": booking_id, "data": data}))
                return
            except Exception:
                pass  # Redis отвалился → мягко падаем на локальную доставку
        await self.local_broadcast(booking_id, data)


manager = ConnectionManager()


async def _chat_subscribe_loop(redis_client):
    """Слушает Redis-канал и доставляет сообщения локальным WS-соединениям воркера."""
    pubsub = redis_client.pubsub()
    await pubsub.subscribe(_CHAT_CHANNEL)
    async for msg in pubsub.listen():
        if msg.get("type") != "message":
            continue
        try:
            obj = json.loads(msg["data"])
            await manager.local_broadcast(int(obj["booking_id"]), obj["data"])
        except Exception:
            pass


@app.websocket("/ws/bookings/{booking_id}")
async def websocket_endpoint(websocket: WebSocket, booking_id: int):
    """WebSocket чат брони. Токен — первым сообщением {"type":"auth","token":...}
    (в URL не передаём: query-string утекает в логи nginx/прокси). Для совместимости
    принимаем и ?token=. Доступ — ТОЛЬКО участнику брони (пассажир или водитель)."""
    await websocket.accept()
    token = websocket.query_params.get("token")
    if not token:
        try:
            first = json.loads(await websocket.receive_text())
            if first.get("type") == "auth":
                token = first.get("token")
        except Exception:
            token = None
    try:
        from .security import verify_token
        user_id = verify_token(token or "")
    except Exception:
        await websocket.close(code=1008, reason="Invalid token")
        return
    # Авторизация на ресурс (закрывает IDOR): юзер должен быть участником ИМЕННО этой брони.
    with Session(engine) as s:
        booking = s.get(Booking, booking_id)
        ride = s.get(Ride, booking.ride_id) if booking else None
        if not booking or not ride or (booking.passenger_id != user_id and ride.driver_id != user_id):
            await websocket.close(code=1008, reason="Forbidden")
            return

    manager.register(booking_id, websocket)
    try:
        while True:
            data = await websocket.receive_text()
            payload = json.loads(data)
            if payload.get("type") == "message":
                session = next(get_session())
                msg = Message(booking_id=booking_id, sender_id=user_id, text=(payload.get("text") or "")[:4000])
                session.add(msg)
                session.commit()
                session.refresh(msg)
                await manager.broadcast(booking_id, {
                    "type": "message",
                    "id": msg.id,
                    "sender_id": msg.sender_id,
                    "text": msg.text,
                    "timestamp": msg.created_at.isoformat()
                })
                # Push другой стороне (она может быть офлайн / не в чате).
                other_id = ride.driver_id if user_id == booking.passenger_id else booking.passenger_id
                sender = session.get(User, user_id)
                _send_push(session, other_id, (sender.name if sender else None) or "Новое сообщение", (msg.text or "Сообщение")[:120])
    except WebSocketDisconnect:
        manager.disconnect(booking_id, websocket)


@app.post("/bookings/{booking_id}/messages", response_model=Message)
def send_message(booking_id: int, body: MessageIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, ride = _booking_and_ride_for_user(session, booking_id, user)
    other_party = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    if _is_blocked(session, user.id, other_party):
        raise HTTPException(403, "Переписка недоступна")
    msg = Message(booking_id=booking_id, sender_id=user.id, **body.model_dump())
    session.add(msg)
    session.commit()
    session.refresh(msg)
    # Push другой стороне брони (кто не отправитель).
    other_id = ride.driver_id if user.id == booking.passenger_id else booking.passenger_id
    _send_push(session, other_id, user.name or "Новое сообщение", (msg.text or "Голосовое сообщение")[:120])
    return msg


@app.get("/bookings/{booking_id}/messages", response_model=List[Message])
def list_messages(
    booking_id: int,
    before_id: Optional[int] = None,
    limit: int = 200,
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """Сообщения брони, по возрастанию id. Пагинация: отдаём последние `limit`;
    `before_id` подгружает более старые (для «показать ещё» вверх чата).
    Старый клиент без параметров получит последние 200 — при малом чате это «всё»."""
    _booking_and_ride_for_user(session, booking_id, user)
    limit = max(1, min(limit, 500))
    q = select(Message).where(Message.booking_id == booking_id)
    if before_id is not None:
        q = q.where(Message.id < before_id)
    rows = session.exec(q.order_by(Message.id.desc()).limit(limit)).all()
    return list(reversed(rows))  # клиент рисует по возрастанию


class ConversationOut(BaseModel):
    booking_id: int
    peer_name: str
    route: str
    last_message: str


@app.get("/conversations", response_model=List[ConversationOut])
def conversations(limit: int = 100, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Инбокс: брони пользователя (как пассажир и как водитель), где есть сообщения.
    `limit` — защитный потолок (число диалогов = свои брони, естественно ограничено)."""
    limit = max(1, min(limit, 200))
    out: list = []
    for b in _user_bookings(session, user):
        last = session.exec(
            select(Message).where(Message.booking_id == b.id).order_by(Message.id.desc())
        ).first()
        if last is None:
            continue
        ride = session.get(Ride, b.ride_id)
        peer = session.get(User, ride.driver_id) if (ride and b.passenger_id == user.id) else session.get(User, b.passenger_id)
        out.append(ConversationOut(
            booking_id=b.id,
            peer_name=(peer.name if peer and peer.name else "Собеседник"),
            route=(f"{ride.from_city} → {ride.to_city}" if ride else ""),
            last_message=(last.text if last.text else "Голосовое"),
        ))
        if len(out) >= limit:
            break
    return out


# ----- Лёгкий TTL-кеш для глобальных лент (одинаковы для всех юзеров) -----
# /feed и /popular-routes опрашивает КАЖДЫЙ клиент раз в 45-60с. Данные общие → без
# кеша это N× одинаковых полных сканов Ride/Booking в секунду при запуске с наплывом.
# Кешируем результат на TTL: при тысячах юзеров БД дёргается ~раз в TTL, а не на каждый запрос.
_cache_lock = threading.Lock()
_cache: dict = {}


def _cached(key: str, ttl: float, producer):
    """Вернуть закешированное значение или пересчитать через producer(). Потокобезопасно.
    Producer зовётся вне лока (там работа с БД) — при гонке возможен двойной пересчёт, это
    безопасно и дёшево. Свежесть данных в пределах TTL достаточна (клиент и так поллит реже)."""
    hit = _cache.get(key)
    if hit is not None and time.monotonic() - hit[0] < ttl:
        return hit[1]
    value = producer()
    with _cache_lock:
        _cache[key] = (time.monotonic(), value)
    return value


@app.get("/popular-routes")
def popular_routes(session: Session = Depends(get_session)):
    """Топ направлений — считаем из реальных поездок. Кеш 60с (общий для всех)."""
    def build():
        from collections import Counter
        rides = session.exec(select(Ride)).all()
        cnt = Counter((r.from_city, r.to_city) for r in rides if r.from_city and r.to_city)
        return [{"from_city": f, "to_city": t, "count": n} for (f, t), n in cnt.most_common(6)]
    return _cached("popular_routes", 60.0, build)


@app.get("/feed")
def feed(session: Session = Depends(get_session)):
    """Живая лента карты: счётчики поездок за период (день/неделя/месяц/год) + топ-маршрут недели.
    Из реальных данных. Кеш 30с (общий для всех) — снимает основную read-нагрузку при наплыве."""
    def build():
        from collections import Counter
        now = datetime.utcnow()
        bookings = session.exec(select(Booking)).all()
        def since(days: int) -> int:
            edge = now - timedelta(days=days)
            return sum(1 for b in bookings if b.created_at and b.created_at >= edge)
        rides = session.exec(select(Ride)).all()
        week_rides = [r for r in rides if r.created_at and r.created_at >= now - timedelta(days=7) and r.from_city and r.to_city]
        top = Counter((r.from_city, r.to_city) for r in week_rides).most_common(1)
        top_route = ({"from_city": top[0][0][0], "to_city": top[0][0][1], "count": top[0][1]} if top else None)
        return {
            "today": since(1), "week": since(7), "month": since(30), "year": since(365),
            "drivers": len({r.driver_id for r in rides}),
            "top_route": top_route,
        }
    return _cached("feed", 30.0, build)


@app.get("/my-routes")
def my_routes(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Частые поездки пользователя — из его истории броней."""
    from collections import Counter
    bookings = session.exec(select(Booking).where(Booking.passenger_id == user.id)).all()
    pairs = []
    for b in bookings:
        r = session.get(Ride, b.ride_id)
        if r and r.from_city and r.to_city:
            pairs.append((r.from_city, r.to_city))
    cnt = Counter(pairs)
    return [{"from_city": f, "to_city": t, "count": n} for (f, t), n in cnt.most_common(6)]


@app.get("/notifications")
def notifications(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Лента событий: входящие сообщения по броням пользователя (как пассажир и водитель)."""
    booking_ids = [b.id for b in _user_bookings(session, user)]
    out: list = []
    if booking_ids:
        msgs = session.exec(
            select(Message).where(Message.booking_id.in_(booking_ids), Message.sender_id != user.id).order_by(Message.id.desc())
        ).all()
        for m in msgs[:15]:
            out.append({"type": "message", "title": "Новое сообщение", "text": (m.text if m.text else "Голосовое сообщение")})
    return out


@app.get("/ads")
def ads():
    """Партнёрская реклама. В production без real ad-store не подмешиваем демо-креативы."""
    if not settings.seed_demo:
        return []
    return [
        {"id": "a_cafe", "title": "Кафе «Юлдаш»", "text": "Горячий чай и еда по дороге Баймаҡ → Сибай", "button": "Посмотреть", "erid": "2VtzqyYYYY", "placement": "route"},
        {"id": "a_sto", "title": "СТО «АвтоМастер»", "text": "Проверка перед дальней дорогой, скидка попутчикам", "button": "Узнать", "erid": "2VtzqyZZZZ", "placement": "ridesList"},
    ]


class AdEventIn(BaseModel):
    type: str


@app.post("/ads/{ad_id}/event")
def ad_event(ad_id: str, body: AdEventIn, session: Session = Depends(get_session)):
    """Записать показ/клик по рекламе (реальная статистика кабинета)."""
    t = "click" if body.type == "click" else "impression"
    session.add(AdEvent(ad_id=ad_id, event_type=t))
    session.commit()
    return {"ok": True}


@app.get("/ads/stats")
def ad_stats(session: Session = Depends(get_session)):
    """Сводка показов/кликов по каждой рекламе (для кабинета)."""
    from collections import Counter
    rows = session.exec(select(AdEvent)).all()
    imp: Counter = Counter()
    clk: Counter = Counter()
    for r in rows:
        (clk if r.event_type == "click" else imp)[r.ad_id] += 1
    return {aid: {"impressions": imp[aid], "clicks": clk[aid]} for aid in (set(imp) | set(clk))}


@app.get("/geocode")
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


@app.post("/voice")
def upload_voice(body: VoiceIn, user: User = Depends(current_user)):
    """Загрузка голосового (base64) → сохранение в media → публичный URL."""
    ext = "".join(c for c in body.ext.lower() if c.isalnum()) or "m4a"
    data, ext = _decode_upload_b64(body.audio_b64, settings.audio_ext_set, ext, "аудио")
    name = f"{uuid.uuid4().hex}.{ext}"
    with open(os.path.join(VOICE_DIR, name), "wb") as f:
        f.write(data)
    return {"url": _public_media_url(f"voice/{name}")}


# ----------------------------- Семейный контроль -----------------------------
class ContactIn(BaseModel):
    name: str = Field(..., max_length=120)
    relation: str = Field("", max_length=120)
    phone: str = Field("", max_length=32)
    notify_by_default: bool = True


@app.post("/trusted-contacts", response_model=TrustedContact)
def add_contact(body: ContactIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    contact = TrustedContact(user_id=user.id, **body.model_dump())
    session.add(contact)
    session.commit()
    session.refresh(contact)
    return contact


@app.get("/trusted-contacts", response_model=List[TrustedContact])
def list_contacts(user: User = Depends(current_user), session: Session = Depends(get_session)):
    return session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()


class ShareIn(BaseModel):
    contact_id: int


@app.post("/bookings/{booking_id}/share", response_model=TripShare)
def share_trip(booking_id: int, body: ShareIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, _ = _booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise HTTPException(403, "Расшарить поездку может только пассажир")
    contact = session.get(TrustedContact, body.contact_id)
    if not contact or contact.user_id != user.id:
        raise HTTPException(404, "Контакт не найден")
    share = TripShare(booking_id=booking_id, contact_id=body.contact_id)
    session.add(share)
    session.commit()
    session.refresh(share)
    return share


class TripStatusIn(BaseModel):
    status: str  # sat / arrived / done


@app.post("/bookings/{booking_id}/trip-status", response_model=List[TripShare])
def set_trip_status(booking_id: int, body: TripStatusIn, background: BackgroundTasks, user: User = Depends(current_user), session: Session = Depends(get_session)):
    booking, _ = _booking_and_ride_for_user(session, booking_id, user)
    if booking.passenger_id != user.id:
        raise HTTPException(403, "Статус семейного контроля меняет только пассажир")
    if body.status not in {"sat", "arrived", "done"}:
        raise HTTPException(400, "Недопустимый статус поездки")
    contact_ids = [c.id for c in session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()]
    if not contact_ids:
        return []
    shares = session.exec(select(TripShare).where(TripShare.booking_id == booking_id, TripShare.contact_id.in_(contact_ids))).all()
    for share in shares:
        share.last_status = body.status
        session.add(share)
    session.commit()
    for share in shares:
        session.refresh(share)  # после commit объекты «обнуляются» — перечитываем
    # SMS близким — в фоне (ответ возвращается сразу, не ждём sms.ru по каждому контакту).
    status_text = {"sat": "сел в машину", "arrived": "доехал до места", "done": "завершил поездку"}.get(body.status, body.status)
    who = user.name or user.phone
    items = []
    for share in shares:
        c = session.get(TrustedContact, share.contact_id)
        if c and c.phone:
            items.append((c.phone, f"Юлдаш: {who} {status_text}."))
    background.add_task(_send_texts_bg, items)
    return shares


class RateIn(BaseModel):
    stars: int


@app.post("/bookings/{booking_id}/rate")
def rate_booking(booking_id: int, body: RateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Оценить вторую сторону поездки (1..5). Пассажир оценивает водителя, водитель — пассажира. Одна оценка на бронь от каждого."""
    b = session.get(Booking, booking_id)
    if not b:
        raise HTTPException(status_code=404, detail="Бронь не найдена")
    ride = session.get(Ride, b.ride_id)
    if user.id == b.passenger_id and ride:
        ratee_id = ride.driver_id          # пассажир → водитель
    elif ride and user.id == ride.driver_id:
        ratee_id = b.passenger_id          # водитель → пассажир
    else:
        raise HTTPException(status_code=403, detail="Нельзя оценить эту поездку")
    stars = max(1, min(5, body.stars))
    existing = session.exec(
        select(Rating).where(Rating.booking_id == booking_id, Rating.rater_id == user.id)
    ).first()
    if existing:
        existing.stars = stars
        session.add(existing)
    else:
        session.add(Rating(booking_id=booking_id, rater_id=user.id, ratee_id=ratee_id, stars=stars))
    session.commit()
    avg, cnt = _user_rating(session, ratee_id)
    # Оценили водителя → обновим витринный рейтинг в профиле.
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == ratee_id)).first()
    if prof and cnt > 0:
        prof.rating = round(avg, 1)
        session.add(prof)
        session.commit()
    return {"ratee_id": ratee_id, "rating": round(avg, 1), "count": cnt}


# ----------------------------- Безопасность -----------------------------
class SosIn(BaseModel):
    category: str = "other"      # medical / breakdown / other
    booking_id: Optional[int] = None
    note: str = Field("", max_length=2000)


@app.post("/sos", response_model=SosEvent)
def sos(body: SosIn, background: BackgroundTasks, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.booking_id is not None:
        _booking_and_ride_for_user(session, body.booking_id, user)
    event = SosEvent(user_id=user.id, **body.model_dump())
    session.add(event)
    session.commit()
    session.refresh(event)
    # SMS близким — в фоне: SOS-ответ возвращается мгновенно, юзер не ждёт sms.ru (до 10с/контакт).
    contacts = session.exec(select(TrustedContact).where(TrustedContact.user_id == user.id)).all()
    who = user.name or user.phone
    items = [(c.phone, f"SOS! {who} просит срочной помощи (Юлдаш). Свяжитесь скорее.") for c in contacts if c.phone]
    background.add_task(_send_texts_bg, items)
    print(f"[SOS] user={user.id} category={body.category} contacts_queued={len(items)}")
    return event


class ReportIn(BaseModel):
    target_user_id: int
    reason: str = ""


@app.post("/reports", response_model=Report)
def create_report(body: ReportIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.target_user_id == user.id:
        raise HTTPException(400, "Нельзя пожаловаться на себя")
    if not session.get(User, body.target_user_id):
        raise HTTPException(404, "Пользователь не найден")
    report = Report(reporter_id=user.id, **body.model_dump())
    session.add(report)
    session.commit()
    session.refresh(report)
    return report


class BlockIn(BaseModel):
    blocked_user_id: int


@app.post("/blocks", response_model=Block)
def create_block(body: BlockIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    if body.blocked_user_id == user.id:
        raise HTTPException(400, "Нельзя заблокировать себя")
    if not session.get(User, body.blocked_user_id):
        raise HTTPException(404, "Пользователь не найден")
    existing = session.exec(
        select(Block).where(Block.user_id == user.id, Block.blocked_user_id == body.blocked_user_id)
    ).first()
    if existing:
        return existing
    block = Block(user_id=user.id, **body.model_dump())
    session.add(block)
    session.commit()
    session.refresh(block)
    return block
