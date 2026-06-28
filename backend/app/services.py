"""Общий сервисный слой Юлдаша: бизнес-хелперы, переиспользуемые роутерами.

Здесь живёт всё, что НЕ привязано к конкретному набору эндпоинтов:
доступ к броне, батч-витрина водителей (анти-N+1), рейтинги, блокировки,
push (FCM), SMS, гео-дистанция, загрузка медиа, сид демо-данных и
менеджер WebSocket-соединений. Роутеры импортируют отсюда — так монолит
`main.py` разрезан без дублирования логики (поведение 1:1).
"""
from datetime import timedelta
import base64
import math
import os

from fastapi import HTTPException
from sqlmodel import Session, select

from .config import settings
from .db import engine
from .models import (
    Block, Booking, DeviceToken, DriverProfile, Rating, Ride, RideCategory,
    RideStatus, User, UserRole,
)
from .schemas import RideOut
from .timeutil import utcnow

# ----------------------------- Медиа-папки -----------------------------
# Голосовые — публично (/media). Документы водителя (права/авто) — ПРИВАТНО (вне /media),
# отдаются только админу/владельцу через /secure/docs/{name} (152-ФЗ — персональные документы).
_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA_DIR = os.path.join(_BASE, "media")
VOICE_DIR = os.path.join(MEDIA_DIR, "voice")
PRIVATE_DIR = os.path.join(_BASE, "private")
DOC_DIR = os.path.join(PRIVATE_DIR, "docs")
os.makedirs(VOICE_DIR, exist_ok=True)
os.makedirs(DOC_DIR, exist_ok=True)


def public_media_url(path: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/media/{path.lstrip('/')}"


def secure_docs_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/docs/{name}"


def decode_upload_b64(raw: str, allowed_ext: set[str], default_ext: str, kind: str) -> tuple[bytes, str]:
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


# ----------------------------- Брони / доступ -----------------------------
def booking_and_ride_for_user(session: Session, booking_id: int, user: User) -> tuple[Booking, Ride]:
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


def user_bookings(session: Session, user: User) -> list:
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


def is_blocked(session: Session, a: int, b: int) -> bool:
    """Есть ли блокировка между a и b в любую сторону."""
    rows = session.exec(select(Block).where(Block.user_id.in_([a, b]))).all()
    return any(
        (r.user_id == a and r.blocked_user_id == b) or (r.user_id == b and r.blocked_user_id == a)
        for r in rows
    )


# ----------------------------- Push (FCM) -----------------------------
_fcm_app = None


def send_push(session: Session, user_id: int, title: str, body: str) -> None:
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


# ----------------------------- SMS -----------------------------
def mask_phone(phone: str) -> str:
    """Маска телефона для логов (152-ФЗ): +7****1234. Полный номер в лог не пишем."""
    d = "".join(c for c in (phone or "") if c.isdigit())
    return f"+{d[0]}****{d[-4:]}" if len(d) >= 5 else "+****"


def send_text(phone: str, text: str) -> None:
    """Отправка произвольного SMS (SOS, статусы близким). smsru → реально; иначе/фоллбэк — в лог."""
    mp = mask_phone(phone)
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


def send_sms(phone: str, code: str) -> None:
    """Отправка OTP. `smsru` — реально через sms.ru; иначе мок (код в лог).
    Если sms.ru НЕ отправил (напр. нет одобренного отправителя) — код падает в лог,
    чтобы вход работал на период настройки отправителя."""
    mp = mask_phone(phone)
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


# ----------------------------- Рейтинги / витрина водителей -----------------------------
def user_rating(session: Session, user_id: int) -> tuple[float, int]:
    """Средний рейтинг пользователя из реальных оценок (звёзды) + их число."""
    rows = list(session.exec(select(Rating.stars).where(Rating.ratee_id == user_id)).all())
    return (sum(rows) / len(rows), len(rows)) if rows else (0.0, 0)


def drivers_bundle(session: Session, driver_ids: set) -> tuple[dict, dict, dict]:
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


def ride_out_with(ride: Ride, users: dict, profiles: dict, rating_agg: dict) -> RideOut:
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


def rides_out(rides: list, session: Session) -> list:
    """Список поездок → list[RideOut] одним батчем (3 запроса вместо 3×N)."""
    users, profiles, rating_agg = drivers_bundle(session, {r.driver_id for r in rides})
    return [ride_out_with(r, users, profiles, rating_agg) for r in rides]


def ride_out(ride: Ride, session: Session) -> RideOut:
    """Одна поездка → RideOut (обёртка над батчем для единичных вызовов)."""
    return rides_out([ride], session)[0]


# ----------------------------- Гео -----------------------------
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


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


# ----------------------------- Демо-данные -----------------------------
def seed_demo(session: Session) -> None:
    """Демо-поездки в пустой БД — чтобы экран «Ближайшие поездки» был живым."""
    if session.exec(select(Ride)).first():
        return
    demo = [
        ("Ильдар", 4.8, True, "Lada", "Vesta", "Баймаҡ", "Сибай", 350, 3),
        ("Айгуль", 4.9, True, "Kia", "Rio", "Темясово", "Уфа", 1400, 3),
        ("Рустам", 4.6, False, "Renault", "Logan", "Сибай", "Баймаҡ", 300, 2),
        ("Гүзәл", 5.0, True, "Hyundai", "Solaris", "Учалы", "Магнитогорск", 800, 4),
    ]
    base = utcnow() + timedelta(hours=3)
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


# ----------------------------- WebSocket -----------------------------
class ConnectionManager:
    """Управление WebSocket соединениями для чата в реальном времени."""
    def __init__(self):
        self.active_connections: dict = {}

    def register(self, booking_id: int, websocket):
        """Зарегистрировать УЖЕ принятое (accept) и авторизованное соединение."""
        self.active_connections.setdefault(booking_id, []).append(websocket)

    def disconnect(self, booking_id: int, websocket):
        if booking_id in self.active_connections:
            self.active_connections[booking_id].remove(websocket)

    async def broadcast(self, booking_id: int, data: dict):
        if booking_id in self.active_connections:
            for connection in self.active_connections[booking_id]:
                try:
                    await connection.send_json(data)
                except Exception:
                    pass


manager = ConnectionManager()
