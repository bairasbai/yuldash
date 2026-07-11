"""Общий сервисный слой Юлдаша: бизнес-хелперы, переиспользуемые роутерами.

Здесь живёт всё, что НЕ привязано к конкретному набору эндпоинтов:
доступ к броне, батч-витрина водителей (анти-N+1), рейтинги, блокировки,
push (FCM), SMS, гео-дистанция, загрузка медиа, сид демо-данных и
менеджер WebSocket-соединений. Роутеры импортируют отсюда — так монолит
`main.py` разрезан без дублирования логики (поведение 1:1).
"""
from datetime import timedelta
import base64
import json
import math
import os

from fastapi import HTTPException
from sqlalchemy import case
from sqlmodel import Session, select

from .config import settings
from .db import engine
from .models import (
    Block, Booking, DeviceToken, DriverProfile, Rating, Ride, RideCategory,
    RideStatus, UploadEvent, User, UserRole,
)
from .schemas import RideOut
from .timeutil import utcnow

# ----------------------------- Медиа-папки -----------------------------
# Голосовые — публично (/media). Документы водителя (права/авто) — ПРИВАТНО (вне /media),
# отдаются только админу/владельцу через /secure/docs/{name} (152-ФЗ — персональные документы).
_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MEDIA_DIR = os.path.join(_BASE, "media")
VOICE_DIR = os.path.join(MEDIA_DIR, "voice")
CHAT_DIR = os.path.join(MEDIA_DIR, "chat")   # фото в чате — публично (как голосовые)
PRIVATE_DIR = os.path.join(_BASE, "private")
DOC_DIR = os.path.join(PRIVATE_DIR, "docs")
os.makedirs(VOICE_DIR, exist_ok=True)
os.makedirs(CHAT_DIR, exist_ok=True)
os.makedirs(DOC_DIR, exist_ok=True)


def public_media_url(path: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/media/{path.lstrip('/')}"


def secure_docs_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/docs/{name}"


def _looks_like_image(data: bytes, ext: str) -> bool:
    """Сигнатура (magic-bytes) совпадает с заявленным расширением изображения?
    Защита от заливки произвольных байтов под видом .jpg. Неизвестный тип — пропускаем
    (расширение уже прошло whitelist)."""
    if ext in ("jpg", "jpeg"):
        return data[:3] == b"\xff\xd8\xff"
    if ext == "png":
        return data[:8] == b"\x89PNG\r\n\x1a\n"
    if ext == "webp":
        return data[:4] == b"RIFF" and data[8:12] == b"WEBP"
    return True


def _validate_upload(data: bytes, allowed_ext: set[str], ext: str, kind: str, sniff_image: bool) -> tuple[bytes, str]:
    """Общая валидация загруженных байтов: непусто + лимит размера + whitelist расширений
    + (для фото) magic-bytes. Используется и base64-, и multipart-путём."""
    ext = "".join(c for c in (ext or "").lower() if c.isalnum())
    if not data:
        raise HTTPException(400, f"Пустой файл: {kind}")
    if len(data) > settings.max_upload_bytes:
        raise HTTPException(413, f"Файл слишком большой: максимум {settings.max_upload_mb} МБ")
    if ext not in allowed_ext:
        raise HTTPException(400, f"Недопустимый тип файла: .{ext}")
    if sniff_image and not _looks_like_image(data, ext):
        raise HTTPException(400, f"Файл не похож на изображение: {kind}")
    return data, ext


def decode_upload_b64(raw: str, allowed_ext: set[str], default_ext: str, kind: str,
                      sniff_image: bool = False) -> tuple[bytes, str]:
    """Безопасная обработка base64 upload: whitelist расширений + лимит размера.
    sniff_image=True — дополнительно проверяем magic-bytes (для фото)."""
    ext = "".join(c for c in default_ext.lower() if c.isalnum()) or default_ext
    if "," in raw and raw.strip().lower().startswith("data:"):
        raw = raw.split(",", 1)[1]
    try:
        data = base64.b64decode(raw, validate=True)
    except Exception:
        raise HTTPException(400, f"Некорректный файл: {kind}")
    return _validate_upload(data, allowed_ext, ext, kind, sniff_image)


async def read_upload(request, allowed_ext: set[str], default_ext: str, kind: str,
                      sniff_image: bool = False) -> tuple[bytes, str]:
    """Прочитать загрузку из multipart/form-data (поле `file` [+ опц. `ext`]) ИЛИ из JSON-base64
    (обратная совместимость со старыми установленными клиентами). multipart не держит весь файл
    как base64-строку в памяти (+33%) — Starlette стримит в SpooledTemporaryFile."""
    ctype = request.headers.get("content-type", "")
    if "multipart/form-data" in ctype:
        form = await request.form()
        up = form.get("file")
        if up is None or not hasattr(up, "read"):
            raise HTTPException(400, f"Нет файла в запросе: {kind}")
        data = await up.read()
        ext = (str(form.get("ext") or "")
               or os.path.splitext(getattr(up, "filename", "") or "")[1].lstrip(".")
               or default_ext)
        return _validate_upload(data, allowed_ext, ext, kind, sniff_image)
    # JSON base64 — старый клиент
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(400, f"Некорректный запрос: {kind}")
    raw = body.get("photo_b64") or body.get("audio_b64") or ""
    ext = body.get("ext") or default_ext
    return decode_upload_b64(raw, allowed_ext, ext, kind, sniff_image)


def enforce_upload_quota(session: Session, user_id: int) -> None:
    """Суточная квота загрузок на юзера (анти disk-fill / спам). Считаем загрузки за 24ч,
    при превышении — 429. Записываем факт текущей загрузки."""
    edge = utcnow() - timedelta(days=1)
    recent = session.exec(
        select(UploadEvent.id).where(UploadEvent.user_id == user_id, UploadEvent.created_at > edge)
    ).all()
    if len(recent) >= settings.max_uploads_per_day:
        raise HTTPException(429, "Слишком много загрузок за сутки. Попробуй позже.")
    session.add(UploadEvent(user_id=user_id))
    session.commit()


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


def blocked_user_ids(session: Session, uid: int) -> set[int]:
    """Все user_id, с кем у uid есть блокировка в любую сторону — для фильтра выдачи поездок (без N+1)."""
    rows = session.exec(select(Block).where((Block.user_id == uid) | (Block.blocked_user_id == uid))).all()
    return {(r.blocked_user_id if r.user_id == uid else r.user_id) for r in rows}


# ----------------------------- Push (FCM) -----------------------------
_fcm_app = None


def send_push(session: Session, user_id: int, title: str, body: str,
              data: dict | None = None, data_only: bool = False) -> None:
    """Push на все устройства пользователя. Тихо ничего, если Firebase не настроен (нет ключа).
    `data` — необязательный data-payload (напр. оффер «Быстрого заказа» → полноэкранная карточка
    на клиенте). FCM требует строковые значения в data — приводим к str на всякий случай.

    `data_only=True` (оффер такси, B7a-2): БЕЗ блока notification + AndroidConfig(priority=high).
    Иначе свёрнутое приложение получает системную плашку вместо onMessageReceived → полноэкранная
    карточка «Новый заказ» не всплывает. title/body кладём в data — клиент сам рисует уведомление."""
    if not settings.firebase_credentials:
        return
    try:
        global _fcm_app
        import firebase_admin
        from firebase_admin import credentials, messaging
        if _fcm_app is None:
            _fcm_app = firebase_admin.initialize_app(credentials.Certificate(settings.firebase_credentials))
        payload_data = {k: str(v) for k, v in data.items()} if data else None
        if data_only:
            payload_data = payload_data or {}
            payload_data.setdefault("title", title)
            payload_data.setdefault("body", body)
        tokens = [d.token for d in session.exec(select(DeviceToken).where(DeviceToken.user_id == user_id)).all()]
        for t in tokens:
            try:
                msg_kwargs = {"token": t}
                if not data_only:
                    msg_kwargs["notification"] = messaging.Notification(title=title, body=body)
                else:
                    msg_kwargs["android"] = messaging.AndroidConfig(priority="high")   # будим из doze
                if payload_data:                         # data-payload только когда есть (не ломаем прежних вызовов)
                    msg_kwargs["data"] = payload_data
                messaging.send(messaging.Message(**msg_kwargs))
            except Exception as e:  # noqa: BLE001
                print(f"[FCM] send error: {e}")
    except Exception as e:  # noqa: BLE001
        print(f"[FCM] init error: {e}")


# ----------------------------- SMS -----------------------------
def mask_phone(phone: str) -> str:
    """Маска телефона для логов (152-ФЗ): +7****1234. Полный номер в лог не пишем."""
    d = "".join(c for c in (phone or "") if c.isdigit())
    return f"+{d[0]}****{d[-4:]}" if len(d) >= 5 else "+****"


def _smsdar_send(phone: str, text: str) -> tuple[bool, str]:
    """Отправка одного SMS через SMSDAR (брендовый канал /v1/brand). Возврат (ok, инфо для лога).
    Авторизация в теле JSON: {id, password}. Номер → формат 79XXXXXXXXX."""
    import httpx
    d = "".join(c for c in (phone or "") if c.isdigit())
    if d.startswith("8"):
        d = "7" + d[1:]
    if len(d) == 10:
        d = "7" + d
    body = {
        "id": settings.smsdar_id,
        "password": settings.smsdar_password,
        "pack": [{"phone": d, "message": text, "sender": settings.smsdar_sender}],
    }
    r = httpx.post("https://api.zmtech.ru:7778/v1/brand", json=body, timeout=10)
    ok = False
    if r.status_code == 200:
        try:
            data = r.json()
            ok = isinstance(data, list) and len(data) > 0 and bool(data[0].get("id"))
        except Exception:  # noqa: BLE001
            ok = False
    return ok, f"{r.status_code} {str(r.text)[:80]}"


def notify_admin_telegram(text: str, reply_markup: dict | None = None) -> None:
    """Уведомление администратору (Александру) в Telegram через бот: запрос звонка и пр.
    Тихо ничего не делает, если бот/chat_id не настроены."""
    if not settings.telegram_bot_token or not settings.admin_telegram_chat_id:
        print("[ADMIN_TG] не настроено (нет токена/chat_id) — пропуск")
        return
    try:
        import httpx
        payload = {"chat_id": settings.admin_telegram_chat_id, "text": text}
        if reply_markup:
            payload["reply_markup"] = reply_markup
        httpx.post(
            f"https://api.telegram.org/bot{settings.telegram_bot_token}/sendMessage",
            json=payload,
            timeout=8,
        )
    except Exception as e:  # noqa: BLE001 — уведомление не должно ронять запрос
        print(f"[ADMIN_TG] error {e}")


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
    elif settings.sms_provider == "smsdar" and settings.smsdar_id and settings.smsdar_password:
        try:
            ok, info = _smsdar_send(phone, text)
            print(f"[SMS] {mp}: smsdar sent={ok} ({info})")
            if not ok and not settings.is_prod:
                print(f"[SMS-FALLBACK] {mp}: {text}")
        except Exception as e:  # noqa: BLE001
            print(f"[SMS] {mp}: smsdar error {e}")
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
    elif settings.sms_provider == "smsdar" and settings.smsdar_id and settings.smsdar_password:
        try:
            ok, info = _smsdar_send(phone, f"Yuldash: kod {code}")
            print(f"[SMS] {mp}: smsdar sent={ok} ({info})")
            if not ok:
                if settings.is_prod:
                    raise HTTPException(502, "SMS не отправлено")
                print(f"[OTP] {mp} -> {code}")  # фоллбэк: SMS не ушла → код в лог (только dev)
        except HTTPException:
            raise
        except Exception as e:  # noqa: BLE001
            print(f"[SMS] {mp}: smsdar error {e}")
            if settings.is_prod:
                raise HTTPException(502, "SMS не отправлено")
            print(f"[OTP] {mp} -> {code}")  # фоллбэк при ошибке сети (только dev)
    else:
        if settings.is_prod:
            # SMS заморожен в проде — основной вход через мессенджеры. Понятный ответ вместо 500.
            raise HTTPException(503, "SMS-вход временно недоступен. Войдите через мессенджер.")
        print(f"[OTP] {mp} -> {code}")  # мок/dev — код в логе


# ----------------------------- Рейтинги / витрина водителей -----------------------------
# Анти-фрод (B8-5): накрутка рейтинга парой аккаунтов (свои 5★ друг другу десятками).
# От одной пары rater→ratee в агрегат идут только ПЕРВЫЕ 3 оценки за скользящие 30 дней —
# остальные пишутся в БД (история честная), но на средний балл не влияют.
RATING_PAIR_CAP = 3
RATING_PAIR_WINDOW_DAYS = 30


def _capped_stars(rows) -> list:
    """rows: (rater_id, stars, created_at) одного ratee → список УЧИТЫВАЕМЫХ звёзд.
    Скользящее окно: оценка учитывается, если от этого же rater'а за последние 30 дней
    учтено меньше RATING_PAIR_CAP. Старые записи без created_at учитываем как раньше."""
    counted: list = []
    counted_at_by_rater: dict = {}
    for rater_id, stars, at in sorted(rows, key=lambda x: (x[2] is None, x[2])):
        if at is None:                       # легаси-строки без даты — не режем (совместимость)
            counted.append(stars)
            continue
        w = counted_at_by_rater.setdefault(rater_id, [])
        recent = [t for t in w if at - t <= timedelta(days=RATING_PAIR_WINDOW_DAYS)]
        if len(recent) < RATING_PAIR_CAP:
            counted.append(stars)
            w.append(at)
    return counted


def user_rating(session: Session, user_id: int) -> tuple[float, int]:
    """Средний рейтинг пользователя из реальных оценок (звёзды) + их число.
    B8-5: повторные оценки одной пары сверх капа в агрегат не входят."""
    rows = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at).where(Rating.ratee_id == user_id)
    ).all())
    stars = _capped_stars(rows)
    return (sum(stars) / len(stars), len(stars)) if stars else (0.0, 0)


def drivers_bundle(session: Session, driver_ids: set) -> tuple[dict, dict, dict]:
    """Батч водителей/профилей/рейтингов для списка поездок — против N+1
    (раньше _ride_out делал 3 запроса НА КАЖДУЮ поездку)."""
    if not driver_ids:
        return {}, {}, {}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(driver_ids))).all()}
    profiles = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(driver_ids))).all()}
    rows_by_driver: dict = {}
    for ratee_id, rater_id, stars, at in session.exec(
        select(Rating.ratee_id, Rating.rater_id, Rating.stars, Rating.created_at)
        .where(Rating.ratee_id.in_(driver_ids))
    ).all():
        rows_by_driver.setdefault(ratee_id, []).append((rater_id, stars, at))
    rating_agg: dict = {}
    for rid, rows in rows_by_driver.items():
        s = _capped_stars(rows)   # B8-5: кап оценок одной пары — как в user_rating
        if s:
            rating_agg[rid] = (sum(s) / len(s), len(s))
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
        boosted=(ride.boosted_until is not None and ride.boosted_until > utcnow()),
        driver_name=(drv.name if drv else "Водитель"),
        driver_rating=rating,
        driver_verified=(drv.verified if drv else False),
        driver_car=car,
        driver_avatar=(drv.avatar_url if drv else ""),
        driver_online=(prof.online if prof else False),
    )


def rides_out(rides: list, session: Session) -> list:
    """Список поездок → list[RideOut] одним батчем (3 запроса вместо 3×N)."""
    users, profiles, rating_agg = drivers_bundle(session, {r.driver_id for r in rides})
    return [ride_out_with(r, users, profiles, rating_agg) for r in rides]


def ride_out(ride: Ride, session: Session) -> RideOut:
    """Одна поездка → RideOut (обёртка над батчем для единичных вызовов)."""
    return rides_out([ride], session)[0]


def public_ride_payload(item):
    """Публичная витрина поездки без точного места встречи.

    Телефон и точная точка сбора раскрываются только участникам подтверждённой
    брони через `/bookings/{id}/details`.
    """
    patch = {"pickup": "", "pickup_lat": None, "pickup_lng": None}
    if isinstance(item, RideOut):
        return item.model_copy(update=patch)
    data = dict(item)
    data.update(patch)
    return data


def public_rides_payload(items: list):
    return [public_ride_payload(item) for item in items]


def boost_then_depart_order():
    """ORDER BY для выдачи поездок: с активным Boost — первыми, затем по времени выезда.
    Истёкший/отсутствующий boost (NULL) попадает в общий порядок."""
    now = utcnow()
    return (case((Ride.boosted_until > now, 0), else_=1), Ride.depart_at)


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


def geocode_city(name: str) -> tuple[float, float] | None:
    """Координаты города. Приоритет (волна 2, география): 1) справочник Settlement
    (точное имя RU/BA, без регистра), 2) старый CITY_COORDS, 3) Яндекс.Геокодер
    (если задан ключ). Нужно для радиус-поиска поездок."""
    if not name:
        return None
    try:
        # Ленивый импорт: geo.py сам импортирует services (haversine) — без циклов.
        from .geo import by_exact_name
        with Session(engine) as _s:
            st = by_exact_name(_s, name)
        if st is not None:
            return (st.lat, st.lng)
    except Exception:  # noqa: BLE001 — таблицы ещё нет (юнит-тест без БД) → фолбэк ниже
        pass
    c = CITY_COORDS.get(name.strip())
    if c:
        return c
    key = settings.yandex_geocoder_key
    if not key:
        return None
    try:
        import httpx
        r = httpx.get("https://geocode-maps.yandex.ru/1.x/", params={
            "apikey": key, "geocode": name, "format": "json", "results": 1, "lang": "ru_RU",
        }, timeout=8)
        members = r.json()["response"]["GeoObjectCollection"]["featureMember"]
        pos = members[0]["GeoObject"]["Point"]["pos"].split(" ")  # "lon lat"
        return (float(pos[1]), float(pos[0]))
    except Exception:  # noqa: BLE001
        return None


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


# ----------------------------- Кеш (Redis) -----------------------------
# Кешируем горячие глобальные read-эндпоинты (feed, popular-routes) с коротким TTL.
# Снимает нагрузку full-table-scan при росте трафика. Без Redis — просто без кеша.
_cache = None
_cache_tried = False


def _cache_client():
    global _cache, _cache_tried
    if _cache_tried:
        return _cache
    _cache_tried = True
    if settings.redis_url:
        try:
            import redis
            _cache = redis.from_url(settings.redis_url, decode_responses=True)
        except Exception as e:  # noqa: BLE001
            print(f"[CACHE] redis init failed: {e}")
            _cache = None
    return _cache


def cache_get_json(key: str):
    c = _cache_client()
    if not c:
        return None
    try:
        v = c.get(key)
        return json.loads(v) if v else None
    except Exception:  # noqa: BLE001 — кеш не должен ронять запрос
        return None


def cache_set_json(key: str, value, ttl_sec: int) -> None:
    c = _cache_client()
    if not c:
        return
    try:
        c.set(key, json.dumps(value, ensure_ascii=False), ex=ttl_sec)
    except Exception:  # noqa: BLE001
        pass


# ----------------------------- WebSocket -----------------------------
# Несколько воркеров gunicorn → WS-коннекты разбросаны по процессам, прямой broadcast
# чужие не достаёт. Решение: Redis pub/sub — publish в канал, каждый воркер раздаёт
# своим локальным соединениям. Без Redis (один воркер/dev) — локальная доставка.
_CHAT_CHANNEL = "yuldash:chat"
_redis_pub = None   # async-клиент для publish (заполняется в init_chat_redis, если есть Redis)
MAP_FEED_KEY = 2_000_000_000   # спец-ключ ConnectionManager для подписчиков /ws/map (не пересекается с booking_id/-booking_id)


class ConnectionManager:
    """WebSocket-соединения чата: локальные коннекты процесса + раздача между воркерами через Redis."""
    def __init__(self):
        self.active_connections: dict = {}

    def register(self, booking_id: int, websocket):
        """Зарегистрировать УЖЕ принятое (accept) и авторизованное соединение."""
        self.active_connections.setdefault(booking_id, []).append(websocket)

    def disconnect(self, booking_id: int, websocket):
        if booking_id in self.active_connections:
            try:
                self.active_connections[booking_id].remove(websocket)
            except ValueError:
                pass
            if not self.active_connections[booking_id]:
                self.active_connections.pop(booking_id, None)

    async def local_broadcast(self, booking_id: int, data: dict):
        """Доставить только соединениям ЭТОГО процесса."""
        for connection in list(self.active_connections.get(booking_id, [])):
            try:
                await connection.send_json(data)
            except Exception:  # noqa: BLE001
                pass

    async def broadcast(self, booking_id: int, data: dict):
        """Есть Redis → publish (подписчик доставит на всех воркерах, включая этот).
        Нет Redis → доставляем только локально (режим одного воркера)."""
        if _redis_pub is not None:
            try:
                await _redis_pub.publish(_CHAT_CHANNEL, json.dumps({"booking_id": booking_id, "data": data}))
                return
            except Exception:  # noqa: BLE001 — Redis отвалился → мягко на локальную доставку
                pass
        await self.local_broadcast(booking_id, data)


manager = ConnectionManager()


def notify_map_changed():
    """Сигнал «на карте что-то изменилось» подписчикам /ws/map (новая поездка/заявка, бронь, отмена, done).
    SYNC — зовём из REST-хендлеров после commit; publish в общий чат-канал Redis, async-loop доставит
    map-клиентам {"type":"refresh"} → клиент перетягивает /rides/near + /requests/near. Без Redis — no-op
    (клиент и так опрашивает раз в ~25с). Падать на сбое Redis НЕЛЬЗЯ — это лишь «приятный» live-апдейт."""
    client = _cache_client()
    if client is None:
        return
    try:
        client.publish(_CHAT_CHANNEL, json.dumps({"booking_id": MAP_FEED_KEY, "data": {"type": "refresh"}}))
    except Exception:  # noqa: BLE001 — Redis недоступен → молча, polling подстрахует
        pass


def notify_chat_message(booking_id: int, data: dict) -> None:
    """Разослать живым WS-подписчикам чата сообщение, отправленное по REST (голос/фото/текст-фолбэк).
    Иначе собеседник с открытым чатом видит его только после переполла истории. SYNC — зовём из
    REST-хендлера после commit; publish в общий Redis-канал, async-loop доставит локальным сокетам
    всех воркеров. Без Redis — no-op (клиент подстрахуется опросом истории)."""
    client = _cache_client()
    if client is None:
        return
    try:
        client.publish(_CHAT_CHANNEL, json.dumps({"booking_id": booking_id, "data": data}))
    except Exception:  # noqa: BLE001 — Redis недоступен → молча, история подстрахует
        pass


async def _chat_subscribe_loop(redis_client):
    """Слушает Redis-канал и доставляет сообщения локальным WS-соединениям этого воркера.
    get_message(timeout) вместо listen()-генератора — чисто отменяется при рестарте воркера
    (иначе RuntimeError: aclose async generator already running на graceful-shutdown)."""
    import asyncio
    pubsub = redis_client.pubsub()
    await pubsub.subscribe(_CHAT_CHANNEL)
    try:
        while True:
            msg = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
            if msg and msg.get("type") == "message":
                try:
                    obj = json.loads(msg["data"])
                    await manager.local_broadcast(int(obj["booking_id"]), obj["data"])
                except Exception:  # noqa: BLE001
                    pass
    except asyncio.CancelledError:
        pass
    finally:
        try:
            await pubsub.unsubscribe(_CHAT_CHANNEL)
            await pubsub.aclose()
        except Exception:  # noqa: BLE001
            pass


async def init_chat_redis():
    """Поднять Redis pub/sub для WS-чата (из lifespan). Без Redis/библиотеки — тихо локальный режим."""
    global _redis_pub
    if not settings.redis_url:
        return
    try:
        import asyncio
        import redis.asyncio as aioredis
        _redis_pub = aioredis.from_url(settings.redis_url, decode_responses=True)
        await _redis_pub.ping()
        sub_client = aioredis.from_url(settings.redis_url, decode_responses=True)
        asyncio.create_task(_chat_subscribe_loop(sub_client))
        print("[REDIS] WS pub/sub активен", flush=True)
    except Exception as e:  # noqa: BLE001 — Redis недоступен → локальный режим, не падаем
        print(f"[REDIS] WS pub/sub недоступен ({e}) → локальный режим")
        _redis_pub = None
