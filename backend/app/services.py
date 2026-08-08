"""Общий сервисный слой Юлдаша: бизнес-хелперы, переиспользуемые роутерами.

Здесь живёт всё, что НЕ привязано к конкретному набору эндпоинтов:
доступ к броне, батч-витрина водителей (анти-N+1), рейтинги, блокировки,
push (FCM), SMS, гео-дистанция, загрузка медиа, сид демо-данных и
менеджер WebSocket-соединений. Роутеры импортируют отсюда — так монолит
`main.py` разрезан без дублирования логики (поведение 1:1).
"""
from collections import deque
from datetime import timedelta
import base64
import json
import math
import os
import threading
import time

from fastapi import HTTPException
from sqlalchemy import case, delete
from sqlmodel import Session, select

from .config import settings
from .db import engine
from .logs import log
from .models import (
    Block, Booking, BookingStatus, DeviceToken, DriverProfile, Notification, PickupPoint, Rating, Ride,
    RideCategory, RideRequest, RouteWatch, UploadEvent, User, UserRole,
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
# Фото-доказательства споров (порт из pr88): лица/номера/травмы — ПРИВАТНО, ретеншен не трогает.
EVIDENCE_DIR = os.path.join(PRIVATE_DIR, "evidence")
os.makedirs(VOICE_DIR, exist_ok=True)
os.makedirs(CHAT_DIR, exist_ok=True)
os.makedirs(DOC_DIR, exist_ok=True)
os.makedirs(EVIDENCE_DIR, exist_ok=True)


def public_media_url(path: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/media/{path.lstrip('/')}"


def secure_docs_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/docs/{name}"


def secure_evidence_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/evidence/{name}"


def _detect_image_ext(data: bytes) -> "str | None":
    """Реальный тип изображения по magic-bytes (НЕ по заявленному клиентом расширению).

    Зачем: клиент грузит фото профиля/чата, но всегда помечает его `ext=jpg`, а телефоны
    отдают png (скриншот), webp (скачанное) или heic (совр. камера). Раньше строгая проверка
    «содержимое ≠ заявленный jpg» отвергала такие фото 400-й → аватар «не сохранялся».
    Теперь тип берём из содержимого. None = это не изображение (защита от заливки мусора)."""
    if data[:3] == b"\xff\xd8\xff":
        return "jpg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    # HEIC/HEIF: "....ftyp<brand>" (совр. фото с телефона)
    if data[4:8] == b"ftyp" and data[8:12] in (
        b"heic", b"heix", b"hevc", b"heim", b"heis", b"hevm", b"hevs", b"mif1", b"msf1",
    ):
        return "heic"
    return None


def _validate_upload(data: bytes, allowed_ext: set[str], ext: str, kind: str, sniff_image: bool) -> tuple[bytes, str]:
    """Общая валидация загруженных байтов: непусто + лимит размера + whitelist расширений
    + (для фото) magic-bytes. Используется и base64-, и multipart-путём."""
    ext = "".join(c for c in (ext or "").lower() if c.isalnum())
    if not data:
        raise HTTPException(400, f"Пустой файл: {kind}")
    if len(data) > settings.max_upload_bytes:
        raise HTTPException(413, f"Файл слишком большой: максимум {settings.max_upload_mb} МБ")
    if sniff_image:
        # Тип берём из СОДЕРЖИМОГО, а не из заявленного клиентом ext (клиент всегда шлёт «jpg»,
        # а телефон отдаёт png/webp/heic → фото раньше отвергалось и «не сохранялось»).
        detected = _detect_image_ext(data)
        if detected is None:
            raise HTTPException(400, f"Файл не похож на изображение: {kind}")
        ext = detected
    if ext not in allowed_ext:
        raise HTTPException(400, f"Недопустимый тип файла: .{ext}")
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
        if not tokens:
            return
        messages = []
        for t in tokens:
            msg_kwargs = {"token": t}
            if not data_only:
                msg_kwargs["notification"] = messaging.Notification(title=title, body=body)
            else:
                msg_kwargs["android"] = messaging.AndroidConfig(priority="high")   # будим из doze
            if payload_data:                         # data-payload только когда есть (не ломаем прежних вызовов)
                msg_kwargs["data"] = payload_data
            messages.append(messaging.Message(**msg_kwargs))
        # Устойчивость к нагрузке: один batch-вызов вместо N последовательных сетевых round-trip
        # (массовые каскадные уведомления перестают тормозить обработчик).
        resp = messaging.send_each(messages)
        # Чистим МЁРТВЫЕ токены (приложение удалено/токен протух) — иначе DeviceToken растёт вечно.
        # В ОТДЕЛЬНОЙ сессии: commit в переданной session сбросил бы ORM-объекты вызывающего.
        dead = [tok for tok, r in zip(tokens, resp.responses)
                if not r.success and type(r.exception).__name__ in ("UnregisteredError", "SenderIdMismatchError")]
        if dead:
            with Session(engine) as s2:
                s2.execute(delete(DeviceToken).where(DeviceToken.token.in_(dead)))
                s2.commit()
    except Exception as e:  # noqa: BLE001
        log.warning(f"[FCM] send error: {e}")


def push_notification(
    session: Session,
    user_id: int,
    ntype: str,
    title_ru: str,
    title_ba: str,
    body_ru: str,
    body_ba: str,
    ref_kind: str = "",
    ref_id: "int | None" = None,
    push: bool = True,
    data: "dict | None" = None,
) -> None:
    """Единая точка события: пишет строку в Центр уведомлений (двуязычно RU+BA) И шлёт FCM-push.

    Ставится в тех же местах, где раньше был голый send_push → лента уведомлений и пуш всегда
    синхронны. Запись идёт в СВОЕЙ сессии: commit в переданной session сбросил бы (expire) ORM-
    объекты вызывающего до сериализации ответа (напр. Booking в response_model → пустой ответ).
    Уведомление вторично — ошибку БД глотаем и логируем, основную операцию не валим. Push идёт
    на ЯЗЫКЕ ПОЛУЧАТЕЛЯ (User.language, порт из notification-fixes): правило «любая надпись —
    на двух языках» действует и для уведомлений; в ленте оба текста хранятся всегда.
    """
    try:
        with Session(engine) as s:
            s.add(Notification(
                user_id=user_id,
                type=ntype,
                title_ru=(title_ru or "")[:140],
                title_ba=(title_ba or "")[:140],
                body_ru=(body_ru or "")[:500],
                body_ba=(body_ba or "")[:500],
                ref_kind=ref_kind or "",
                ref_id=ref_id,
            ))
            s.commit()
    except Exception as e:  # noqa: BLE001 — уведомление вторично, основную операцию не валим
        log.warning(f"[NOTIFY] db error: {e}")
    if push:
        # Язык получателя: BA-пользователю пуш уходит на башкирском (клиент пишет выбор
        # языка в /me/update). Пустой BA-текст → фолбэк на RU (никогда не шлём пустоту).
        recipient = session.get(User, user_id)
        if recipient and recipient.language == "ba" and (title_ba or body_ba):
            title, body = (title_ba or title_ru), (body_ba or body_ru)
        else:
            title, body = title_ru, body_ru
        # data — опциональный payload для клиентского роутинга (канал/deep-link), напр.
        # {"type": "chat", "id": booking_id} у чат-пушей. Без data зовём по-старому
        # (4 позиционных): тест-двойники и старые обёртки send_push не ломаются.
        if data:
            send_push(session, user_id, title, body, data)
        else:
            send_push(session, user_id, title, body)


# ----------------------------- Подписка на маршрут (RouteWatch) -----------------------------
def _norm_city(s: str) -> str:
    """Нормализация названия города для сравнения подписки с поездкой (регистр/пробелы)."""
    return (s or "").strip().casefold()


def _push_async(items: "list") -> None:
    """FCM-рассылка в фоновом daemon-потоке (своя сессия) — сеть не держит обработчик запроса.
    items: список (user_id, title, body). Ошибки глотаем: пуш вторичен, запись в ленте уже есть."""
    def run():
        try:
            with Session(engine) as s:
                for uid, title, body in items:
                    send_push(s, uid, title, body)
        except Exception as e:  # noqa: BLE001
            log.warning(f"[ROUTE_WATCH] async push error: {e}")
    threading.Thread(target=run, daemon=True).start()


def notify_route_watchers(session: Session, ride: Ride) -> int:
    """Матчинг новой поездки с подписками «карауль поездку».
    Находит непротухшие подписки, чей маршрут совпал с поездкой, и шлёт push + пишет
    запись уведомления. Анти-спам: не чаще 1 пуша на подписку в сутки (last_notified_at).
    Возвращает число оповещённых подписок (для тестов/логов). Не роняет публикацию поездки."""
    try:
        now = utcnow()
        r_from, r_to = _norm_city(ride.from_city), _norm_city(ride.to_city)
        # Берём только непротухшие подписки; чужие водителю (сам себе не шлём).
        watches = session.exec(
            select(RouteWatch).where(
                RouteWatch.expires_at > now,
                RouteWatch.user_id != ride.driver_id,
            )
        ).all()
        notified = 0
        to_push: list = []   # (user_id, title, body) — FCM отправим в фоне после записи в ленту
        # Кого оповещать НЕЛЬЗЯ (аудит 2026-08-07). Рассылка обходила обе защиты сразу:
        #  • чёрный список — человек, которого водитель заблокировал, получал пуш о его поездке;
        #  • «только для своих» — закрытую поездку лента прячет и забронировать её нельзя,
        #    а пуш о ней приходил кому угодно. Само существование поездки — тоже информация:
        #    «кто, куда и когда едет» в Баймаке узнают по одному оповещению.
        # Локальный импорт: trust_service тянет services на верхнем уровне — прямой импорт
        # здесь замкнул бы круг на старте приложения.
        from .trust_service import INSIDER_LEVEL, trust_level
        blocked = blocked_user_ids(session, ride.driver_id)
        for w in watches:
            if w.watch_kind not in ("rides", "both"):   # G3: эта подписка караулит заявки, не поездки
                continue
            if w.user_id in blocked:
                continue
            if ride.only_trusted and trust_level(session, session.get(User, w.user_id)) < INSIDER_LEVEL:
                continue
            w_from, w_to = _norm_city(w.from_city), _norm_city(w.to_city)
            forward = (w_from == r_from and w_to == r_to)
            backward = (w.direction == "both" and w_from == r_to and w_to == r_from)
            if not (forward or backward):
                continue
            # Дата: если у подписки задан день — матчим только поездку в этот календарный день.
            if w.watch_date is not None and w.watch_date.date() != ride.depart_at.date():
                continue
            # Анти-спам: 1 пуш на подписку в сутки.
            if w.last_notified_at is not None and (now - w.last_notified_at) < timedelta(hours=24):
                continue
            route = f"{ride.from_city} → {ride.to_city}"   # города — как есть (имена собственные)
            # Строку в Центре уведомлений пишем СИНХРОННО (лента должна отдаться сразу), а FCM-пуш
            # (сеть) — в фоне ниже, чтобы N подписчиков не держали обработчик создания поездки.
            push_notification(
                session, w.user_id, "route_watch",
                "Появилась поездка", "Сәфәр барлыҡҡа килде",
                route, route,
                ref_kind="ride", ref_id=ride.id, push=False,
            )
            to_push.append((w.user_id, "Появилась поездка", route))
            w.last_notified_at = now
            session.add(w)
            notified += 1
        if notified:
            session.commit()
        if to_push:
            _push_async(to_push)   # FCM-рассылка вне обработчика запроса
        return notified
    except Exception as e:  # noqa: BLE001 — оповещение сторожей не должно ронять публикацию поездки
        log.warning(f"[ROUTE_WATCH] notify error: {e}")
        return 0


def notify_request_watchers(session: Session, request: RideRequest) -> int:
    """G3 — водительская сторона попуток. Матчинг новой ЗАЯВКИ пассажира с подписками
    watch_kind ∈ {requests, both}: водитель, караулящий направление, узнаёт «есть пассажир
    на твоём маршруте» (push + запись в ленте). Зеркало notify_route_watchers, те же правила:
    непротухшие подписки, автору заявки себе не шлём, forward/both, опц. день, анти-спам 1/сутки.
    Возвращает число оповещённых. Не роняет создание заявки."""
    try:
        now = utcnow()
        r_from, r_to = _norm_city(request.from_city), _norm_city(request.to_city)
        watches = session.exec(
            select(RouteWatch).where(
                RouteWatch.expires_at > now,
                RouteWatch.user_id != request.passenger_id,   # автору заявки — не себе
            )
        ).all()
        notified = 0
        to_push: list = []
        for w in watches:
            if w.watch_kind not in ("requests", "both"):   # эта подписка караулит поездки, не заявки
                continue
            w_from, w_to = _norm_city(w.from_city), _norm_city(w.to_city)
            forward = (w_from == r_from and w_to == r_to)
            backward = (w.direction == "both" and w_from == r_to and w_to == r_from)
            if not (forward or backward):
                continue
            # Дата: если у подписки задан день, а у заявки есть желаемое время — матчим по дню.
            if (w.watch_date is not None and request.desired_at is not None
                    and w.watch_date.date() != request.desired_at.date()):
                continue
            if w.last_notified_at is not None and (now - w.last_notified_at) < timedelta(hours=24):
                continue
            route = f"{request.from_city} → {request.to_city}"   # города — как есть
            push_notification(
                session, w.user_id, "request_watch",
                "Пассажир на твоём маршруте", "Юлыңда юлаусы бар",
                route, route,
                ref_kind="request", ref_id=request.id, push=False,
            )
            to_push.append((w.user_id, "Пассажир на твоём маршруте", route))
            w.last_notified_at = now
            session.add(w)
            notified += 1
        if notified:
            session.commit()
        if to_push:
            _push_async(to_push)
        return notified
    except Exception as e:  # noqa: BLE001 — оповещение водителей не должно ронять создание заявки
        log.warning(f"[REQUEST_WATCH] notify error: {e}")
        return 0


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
        log.info("[ADMIN_TG] не настроено (нет токена/chat_id) — пропуск")
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
        log.warning(f"[ADMIN_TG] error {e}")


# ------------------- Алерт при всплеске 5xx (наблюдаемость) -------------------
# Прагматично, без внешних систем: считаем серверные ошибки (5xx) в скользящем окне.
# Перевалили порог → ОДНО сообщение админу в Telegram, потом «остываем» (cooldown),
# чтобы не заспамить. Счётчик — в памяти воркера (для алерта «что-то горит» этого хватает;
# на несколько воркеров каждый шлёт максимум один раз за cooldown, не лавина).
_err_times: deque = deque()
# None = алерта ещё не было. НЕ 0.0: sentinel сравнивается с time.monotonic(), а он на
# свежем сервере/CI-раннере может быть < cooldown → 0.0 давал ложное «ещё остываем».
_last_error_alert: float | None = None


def reset_error_counter() -> None:
    """Сброс окна и cooldown — для тестов и ручного сброса."""
    global _last_error_alert
    _err_times.clear()
    _last_error_alert = None


def record_server_error(path: str = "") -> None:
    """Зарегистрировать один серверный сбой (5xx). При превышении порога в окне —
    один алерт админу в Telegram (с cooldown). Никогда не бросает исключений:
    наблюдаемость не должна ронять сам запрос."""
    global _last_error_alert
    try:
        threshold = max(1, settings.error_alert_threshold)
        window = max(1, settings.error_alert_window_sec)
        cooldown = max(0, settings.error_alert_cooldown_sec)
        now = time.monotonic()
        edge = now - window
        while _err_times and _err_times[0] < edge:
            _err_times.popleft()
        _err_times.append(now)
        if len(_err_times) >= threshold and (_last_error_alert is None or (now - _last_error_alert) >= cooldown):
            _last_error_alert = now
            count = len(_err_times)
            _err_times.clear()  # окно закрыто одним алертом — не копим на следующий тик
            notify_admin_telegram(
                f"⚠️ Юлдаш: всплеск серверных ошибок — {count} шт. за ~{window}с (5xx). "
                f"Последний путь: {path or '—'}. Проверь логи: journalctl -u yuldash-api"
            )
    except Exception as e:  # noqa: BLE001
        log.warning(f"[ERR_ALERT] record failed: {e}")


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
            log.info(f"[SMS] {mp}: smsru sent={ok} ({sms.get('status_code')} {str(sms.get('status_text', ''))[:80]})")
            if not ok and not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsru error {e}")
            if not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
    elif settings.sms_provider == "smsdar" and settings.smsdar_id and settings.smsdar_password:
        try:
            ok, info = _smsdar_send(phone, text)
            log.info(f"[SMS] {mp}: smsdar sent={ok} ({info})")
            if not ok and not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsdar error {e}")
            if not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
    else:
        # Мок-провайдер разрешён и в проде (SMS заморожен, вход через Telegram). Тело может содержать
        # имя и live-ссылку /t/{token} (capability-URL на живую гео) — в проде тело НЕ логируем.
        if settings.is_prod:
            log.info(f"[SMS-MOCK] {mp}: (тело скрыто в проде)")
        else:
            log.info(f"[SMS-MOCK] {mp}: {text}")


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
            log.info(f"[SMS] {mp}: smsru sent={ok} ({sms.get('status_code')} {str(sms.get('status_text', ''))[:80]})")
            if not ok:
                if settings.is_prod:
                    raise HTTPException(502, "SMS не отправлено")
                log.info(f"[OTP] {mp} -> {code}")  # фоллбэк: SMS не ушла → код в лог (только dev)
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsru error {e}")
            if settings.is_prod:
                raise HTTPException(502, "SMS не отправлено")
            log.info(f"[OTP] {mp} -> {code}")  # фоллбэк при ошибке сети (только dev)
    elif settings.sms_provider == "smsdar" and settings.smsdar_id and settings.smsdar_password:
        try:
            ok, info = _smsdar_send(phone, f"Yuldash: kod {code}")
            log.info(f"[SMS] {mp}: smsdar sent={ok} ({info})")
            if not ok:
                if settings.is_prod:
                    raise HTTPException(502, "SMS не отправлено")
                log.info(f"[OTP] {mp} -> {code}")  # фоллбэк: SMS не ушла → код в лог (только dev)
        except HTTPException:
            raise
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsdar error {e}")
            if settings.is_prod:
                raise HTTPException(502, "SMS не отправлено")
            log.info(f"[OTP] {mp} -> {code}")  # фоллбэк при ошибке сети (только dev)
    else:
        if settings.is_prod:
            # SMS заморожен в проде — основной вход через мессенджеры. Понятный ответ вместо 500.
            raise HTTPException(503, "SMS-вход временно недоступен. Войдите через мессенджер.")
        log.info(f"[OTP] {mp} -> {code}")  # мок/dev — код в логе


# ----------------------------- Рейтинги / витрина водителей -----------------------------
# Анти-фрод (B8-5): накрутка рейтинга парой аккаунтов (свои 5★ друг другу десятками).
# От одной пары rater→ratee в агрегат идут только ПЕРВЫЕ 3 оценки за скользящие 30 дней —
# остальные пишутся в БД (история честная), но на средний балл не влияют.
RATING_PAIR_CAP = 3
RATING_PAIR_WINDOW_DAYS = 30
# G6 — «окно свежести»: среднее считаем по ПОСЛЕДНИМ N учтённым оценкам («право исправиться» —
# старые промахи выпадают, когда набралось много свежих; у Яндекса окно 150). При ≤N оценок —
# как раньше (все). Число оценок (count) остаётся ПОЛНЫМ — для «N отзывов» и порогов («Новичок <5»).
RATING_RECENT_WINDOW = 50


def _capped_entries(rows) -> list:
    """rows: (rater_id, stars, created_at) одного ratee → список УЧТЁННЫХ (created_at, stars),
    отсортированный СТАРЫЕ→СВЕЖИЕ (легаси-строки без даты — как самые старые, в начале).
    Скользящий кап анти-накрутки: от одного rater'а за RATING_PAIR_WINDOW_DAYS учитываем не больше
    RATING_PAIR_CAP оценок. Ключ сортировки не сравнивает None напрямую (иначе TypeError на 2+ nulls);
    nulls-first не меняет, КАКИЕ звёзды учтены (nulls учитываются всегда, кап смотрит лишь на даты),
    только их позицию — чтобы окно свежести (_rating_from_rows) корректно считало их старыми."""
    counted: list = []
    counted_at_by_rater: dict = {}
    for rater_id, stars, at in sorted(rows, key=lambda x: (x[2] is not None, x[2] if x[2] is not None else 0)):
        if at is None:                       # легаси-строки без created_at — не режем (совместимость)
            counted.append((None, stars))
            continue
        w = counted_at_by_rater.setdefault(rater_id, [])
        recent = [t for t in w if at - t <= timedelta(days=RATING_PAIR_WINDOW_DAYS)]
        if len(recent) < RATING_PAIR_CAP:
            counted.append((at, stars))
            w.append(at)
    return counted


def _rating_from_rows(rows) -> tuple[float, int]:
    """Единый расчёт рейтинга (G6). Среднее — по ПОСЛЕДНИМ RATING_RECENT_WINDOW учтённым оценкам
    (entries отсортированы старые→свежие → берём хвост); число — ПОЛНОЕ учтённых. ≤ окна → все,
    поведение как раньше. Один хелпер для одиночного (user_rating) и батч (drivers_bundle) —
    рейтинг в профиле и на карточке НЕ расходится при >N оценок."""
    entries = _capped_entries(rows)
    if not entries:
        return (0.0, 0)
    recent = [s for _, s in entries[-RATING_RECENT_WINDOW:]]
    return (sum(recent) / len(recent), len(entries))


def user_rating(session: Session, user_id: int) -> tuple[float, int]:
    """Средний рейтинг пользователя (G6: по последним RATING_RECENT_WINDOW учтённым оценкам) +
    ПОЛНОЕ число учтённых. B8-5: повторные оценки одной пары сверх капа в агрегат не входят."""
    rows = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at)
        .where(Rating.ratee_id == user_id, Rating.excluded == False)  # noqa: E712 — «щит рейтинга»: снятые оценки вне среднего
    ).all())
    return _rating_from_rows(rows)


# Анти-накрутка бейджа «N поездок» (аудит 2026-08-07). Средний балл от накрутки парой аккаунтов
# защищён капом выше, а бейдж — не был ничем: он просто считал брони со статусом done, а перевести
# бронь в done можно было за три запроса, не проехав ни метра. Бейдж доверия — и есть продукт
# «между своими»: по нему человек решает, садиться ли в машину, поэтому подделка тут дороже
# накрученной звезды. Кап тот же по смыслу, что рейтинговый, но щедрее: постоянный попутчик
# (сосед на работу два раза в неделю) — норма района, а не сговор, и обрезать его до трёх
# поездок в месяц значило бы врать о честном водителе в другую сторону.
TRIPS_PAIR_CAP = 8
TRIPS_PAIR_WINDOW_DAYS = 30


def driver_trips_agg(session: Session, driver_ids: set) -> dict:
    """F8 «N поездок»: сколько поездок водитель реально ЗАВЕРШИЛ (агрегат, без новых таблиц).
    Считаем distinct поездок с завершённой бронью — поездка со статусом done не выставляется
    (её ставит только бронь: booking.status=done), поэтому меряем по броням. Один батч-запрос
    на весь список карточек (без N+1). Нового водителя тут нет → бейдж не покажется (0).

    Две планки честности (аудит 2026-08-07):
      * поездка должна была СОСТОЯТЬСЯ — считаем только те, что уже выехали (depart_at в прошлом).
        Бронь можно закрыть не одной ручкой (завершение поездки водителем, «доехал» пассажиром),
        а бейдж считается здесь — значит и планка тут, одна на все пути;
      * от ОДНОГО попутчика в бейдж идёт не больше TRIPS_PAIR_CAP поездок за скользящее окно —
        иначе два аккаунта катают друг друга по кругу и рисуют «100 поездок».
    Строки вместо SQL-агрегата: скользящее окно на пару в SQL не выражается, а объём тот же,
    что уже читает рейтинг тех же водителей (drivers_bundle)."""
    if not driver_ids:
        return {}
    rows = session.exec(
        select(Ride.driver_id, Booking.passenger_id, Booking.ride_id, Ride.depart_at)
        .join(Ride, Booking.ride_id == Ride.id)
        .where(Ride.driver_id.in_(driver_ids), Booking.status == BookingStatus.done,
               Ride.depart_at <= utcnow())
        .order_by(Ride.depart_at)          # старые→свежие: окно пары считается по ходу времени
    ).all()
    counted_rides: dict = {}
    pair_seen: dict = {}
    for driver_id, passenger_id, ride_id, at in rows:
        seen = pair_seen.setdefault((driver_id, passenger_id), [])
        recent = [t for t in seen if at - t <= timedelta(days=TRIPS_PAIR_WINDOW_DAYS)]
        if len(recent) >= TRIPS_PAIR_CAP:
            continue                       # сверх капа пары — в бейдж не идёт (история в БД цела)
        seen.append(at)
        counted_rides.setdefault(driver_id, set()).add(ride_id)
    return {driver_id: len(rides) for driver_id, rides in counted_rides.items()}


def drivers_bundle(session: Session, driver_ids: set) -> tuple[dict, dict, dict, dict]:
    """Батч водителей/профилей/рейтингов/поездок для списка поездок — против N+1
    (раньше _ride_out делал 3 запроса НА КАЖДУЮ поездку)."""
    if not driver_ids:
        return {}, {}, {}, {}
    users = {u.id: u for u in session.exec(select(User).where(User.id.in_(driver_ids))).all()}
    profiles = {p.user_id: p for p in session.exec(select(DriverProfile).where(DriverProfile.user_id.in_(driver_ids))).all()}
    rows_by_driver: dict = {}
    for ratee_id, rater_id, stars, at in session.exec(
        select(Rating.ratee_id, Rating.rater_id, Rating.stars, Rating.created_at)
        .where(Rating.ratee_id.in_(driver_ids), Rating.excluded == False)  # noqa: E712 — «щит рейтинга»: как в user_rating
    ).all():
        rows_by_driver.setdefault(ratee_id, []).append((rater_id, stars, at))
    rating_agg: dict = {}
    for rid, rows in rows_by_driver.items():
        avg, cnt = _rating_from_rows(rows)   # G6: то же окно свежести, что и user_rating (консистентно)
        if cnt:
            rating_agg[rid] = (avg, cnt)
    trips_agg = driver_trips_agg(session, driver_ids)   # F8: счётчик done-поездок для бейджей
    return users, profiles, rating_agg, trips_agg


def ride_out_with(ride: Ride, users: dict, profiles: dict, rating_agg: dict, trips_agg: dict | None = None) -> RideOut:
    """RideOut из предзагруженных батчей (без запросов в БД)."""
    drv = users.get(ride.driver_id)
    prof = profiles.get(ride.driver_id)
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    avg, cnt = rating_agg.get(ride.driver_id, (0.0, 0))
    rating = round(avg, 1) if cnt > 0 else (prof.rating if prof else 5.0)  # реальный рейтинг; до отзывов — сид
    trips = (trips_agg or {}).get(ride.driver_id, 0)                       # F8: завершённых поездок водителя
    since = drv.created_at.strftime("%Y-%m") if (drv and drv.created_at) else ""  # F8: «С нами с <мес год>»
    return RideOut(
        **ride.model_dump(exclude={"created_at"}),
        boosted=(ride.boosted_until is not None and ride.boosted_until > utcnow()),
        driver_name=(drv.name if drv else "Водитель"),
        driver_rating=rating,
        driver_rating_count=cnt,   # G5: клиент покажет «Новичок» при <5 оценок / «N оценок» (не фейк)
        driver_verified=(drv.verified if drv else False),
        driver_car=car,
        driver_avatar=(drv.avatar_url if drv else ""),
        driver_online=(prof.online if prof else False),
        driver_trips=trips,
        driver_since=since,
        # Только ПОДТВЕРЖДЁННЫЙ модератором пол. Самодекларация в витрину не попадает —
        # иначе бейдж «женщина за рулём» ставит себе кто угодно (см. models.DriverProfile).
        driver_is_woman=(bool(prof.gender == "female" and prof.gender_verified) if prof else False),
    )


def rides_out(rides: list, session: Session) -> list:
    """Список поездок → list[RideOut] одним батчем (4 запроса вместо 4×N)."""
    users, profiles, rating_agg, trips_agg = drivers_bundle(session, {r.driver_id for r in rides})
    return [ride_out_with(r, users, profiles, rating_agg, trips_agg) for r in rides]


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


# --------------------- Точки сбора по ориентирам (F14) ---------------------
# Сидовые популярные ориентиры крупных городов (город → список (title_ru, title_ba, dlat, dlng)).
# Координаты = центр города (CITY_COORDS/фолбэк) + небольшое смещение, чтобы пины не слипались.
# BA — ЧЕРНОВИК модели, на проверке носителю (docs/tasks.md «Переводы на проверку — F14»).
_SEED_CITY_FALLBACK = {"Белорецк": (53.966, 58.410)}
_PICKUP_SEEDS: dict[str, list[tuple[str, str, float, float]]] = {
    "Уфа": [
        ("Автовокзал Южный", "Көньяҡ автовокзалы", 0.000, 0.000),
        ("Ж/д вокзал", "Тимер юл вокзалы", 0.004, -0.006),
        ("У Гостиного двора", "Гостиный двор янында", -0.005, 0.004),
        ("ТЦ «Мега»", "«Мега» СҮ янында", 0.008, 0.009),
    ],
    "Сибай": [
        ("Автовокзал", "Автовокзал янында", 0.000, 0.000),
        ("У мечети", "Мәсет янында", 0.003, 0.004),
        ("У центрального рынка", "Үҙәк баҙар янында", -0.004, 0.003),
    ],
    "Баймаҡ": [
        ("У мечети", "Мәсет янында", 0.000, 0.000),
        ("Автостанция", "Автостанция янында", 0.003, -0.003),
        ("У «Магнита»", "«Магнит» янында", -0.003, 0.004),
    ],
    "Белорецк": [
        ("Автовокзал", "Автовокзал янында", 0.000, 0.000),
        ("У центрального рынка", "Үҙәк баҙар янында", 0.004, 0.005),
    ],
    "Учалы": [
        ("Автовокзал", "Автовокзал янында", 0.000, 0.000),
        ("У мечети", "Мәсет янында", -0.004, 0.003),
    ],
}


def seed_pickup_points(session: Session) -> None:
    """Идемпотентный сид ориентиров крупных городов. Публичный справочник — нужен и
    на проде (не под флагом SEED_DEMO). Повторный вызов ничего не дублирует."""
    if session.exec(select(PickupPoint).where(PickupPoint.is_seed == True)).first():  # noqa: E712
        return
    for city, points in _PICKUP_SEEDS.items():
        base = CITY_COORDS.get(city) or _SEED_CITY_FALLBACK.get(city)
        for title_ru, title_ba, dlat, dlng in points:
            lat = round(base[0] + dlat, 6) if base else None
            lng = round(base[1] + dlng, 6) if base else None
            session.add(PickupPoint(
                city=city, title_ru=title_ru, title_ba=title_ba,
                lat=lat, lng=lng, usage_count=0, is_seed=True,
            ))
    session.commit()


def suggest_pickup_points(session: Session, city: str | None, limit: int = 12) -> list[PickupPoint]:
    """Подсказки точек сбора для города — чаще выбираемые первыми (usage_count ↓)."""
    q = select(PickupPoint)
    if city and city.strip():
        q = q.where(PickupPoint.city == city.strip())
    q = q.order_by(PickupPoint.usage_count.desc(), PickupPoint.id.asc())
    return session.exec(q.limit(max(1, min(limit, 50)))).all()


def record_pickup_choice(
    session: Session, *, city: str, point_id: int | None = None,
    title_ru: str = "", title_ba: str = "", lat: float | None = None, lng: float | None = None,
) -> PickupPoint | None:
    """Пополнение справочника из реально выбранной точки сбора.

    - выбрана известная точка (`point_id`) → +1 к usage_count (поднимается в подсказках);
    - новая точка (текст + валидные координаты, без id) и такой ещё нет для города →
      добавляем пользовательский ориентир (usage_count=1), справочник растёт сам.
    Ничего не подходит (нет id и нет текста/координат) → None (просто не записываем)."""
    pt: PickupPoint | None = None
    if point_id:
        pt = session.get(PickupPoint, point_id)
    title_ru = (title_ru or "").strip()
    city = (city or "").strip()
    if pt is None and title_ru and city:
        # дедуп по городу + названию (без регистра) — не плодим дубли одного ориентира
        existing = session.exec(select(PickupPoint).where(PickupPoint.city == city)).all()
        pt = next((p for p in existing if p.title_ru.strip().lower() == title_ru.lower()), None)
    if pt is not None:
        pt.usage_count += 1
        session.add(pt)
        session.commit()
        session.refresh(pt)
        return pt
    # новой точки нет — создаём, только если есть название + валидные координаты
    if title_ru and city and lat is not None and lng is not None and -90 <= lat <= 90 and -180 <= lng <= 180:
        pt = PickupPoint(
            city=city, title_ru=title_ru, title_ba=(title_ba or "").strip(),
            lat=lat, lng=lng, usage_count=1, is_seed=False,
        )
        session.add(pt)
        session.commit()
        session.refresh(pt)
        return pt
    return None


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
            log.warning(f"[CACHE] redis init failed: {e}")
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
_sub_task = None    # задача Redis pub/sub подписки (держим ссылку — иначе GC; снимаем на shutdown)
MAP_FEED_KEY = 2_000_000_000   # спец-ключ ConnectionManager для подписчиков /ws/map (не пересекается с booking_id/-booking_id)
_MAP_REFRESH_GATE = "map:refresh:gate"   # дебаунс-«ворота» для notify_map_changed (SET NX EX)
_MAP_REFRESH_DEBOUNCE_SEC = 3            # не чаще 1 refresh в это окно (всплеск изменений схлопывается)


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
        # Дебаунс против «эффекта толпы»: при всплеске изменений (несколько публикаций/броней
        # подряд) НЕ будим всех map-клиентов на каждое событие — иначе 2000 человек с открытой
        # картой разом дёргают тяжёлый /rides/near. SET NX EX = «ворота»: проходит только первый
        # refresh в окне, остальные схлопываются (клиент и так опрашивает раз в ~25с).
        if not client.set(_MAP_REFRESH_GATE, "1", nx=True, ex=_MAP_REFRESH_DEBOUNCE_SEC):
            return
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
    from .observability import capture
    pubsub = redis_client.pubsub()
    await pubsub.subscribe(_CHAT_CHANNEL)
    try:
        while True:
            try:
                msg = await pubsub.get_message(ignore_subscribe_messages=True, timeout=1.0)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001 — Redis-блип: НЕ роняем цикл навсегда
                capture(e)   # H2/H3: иначе WS-доставка между воркерами тихо умирает без алерта
                log.warning(f"[REDIS] WS sub reconnect: {type(e).__name__}: {e}")
                await asyncio.sleep(2.0)
                try:
                    await pubsub.subscribe(_CHAT_CHANNEL)
                except Exception:  # noqa: BLE001
                    pass
                continue
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
    global _redis_pub, _sub_task
    if not settings.redis_url:
        return
    try:
        import asyncio
        import redis.asyncio as aioredis
        _redis_pub = aioredis.from_url(settings.redis_url, decode_responses=True)
        await _redis_pub.ping()
        sub_client = aioredis.from_url(settings.redis_url, decode_responses=True)
        _sub_task = asyncio.create_task(_chat_subscribe_loop(sub_client))   # H3: держим ссылку (иначе GC)
        log.info("[REDIS] WS pub/sub активен")
    except Exception as e:  # noqa: BLE001 — Redis недоступен → локальный режим, не падаем
        log.warning(f"[REDIS] WS pub/sub недоступен ({e}) → локальный режим")
        _redis_pub = None


async def close_chat_redis():
    """Аккуратно свернуть pub/sub при остановке (H4): снять задачу, закрыть соединение."""
    global _redis_pub, _sub_task
    if _sub_task is not None:
        _sub_task.cancel()
        try:
            await _sub_task
        except Exception:  # noqa: BLE001
            pass
        _sub_task = None
    if _redis_pub is not None:
        try:
            await _redis_pub.aclose()
        except Exception:  # noqa: BLE001
            pass
        _redis_pub = None
