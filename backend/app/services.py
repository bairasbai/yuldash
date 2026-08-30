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
import re
import math
import os
import threading
import time

from fastapi import HTTPException
from sqlalchemy import case, delete
from sqlmodel import Session, select

from .config import settings
from urllib.parse import urlparse

from .errors import herr
from .db import engine
from .imagemeta import shrink_image, strip_audio_metadata, strip_image_metadata
from .logs import log
from .observability import scrub_text
from .models import (
    Block, Booking, BookingStatus, DeviceToken, DriverProfile, FamilySmsLog, InstantOrder,
    Notification, PickupPoint, Rating, Ride, RideCategory, RideRequest, RouteWatch, UploadEvent,
    User, UserRole,
)
from .schemas import RideOut
from .timeutil import local_date, local_month, utcnow

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
# Кадры фотоконтроля машины — ПРИВАТНО и с ретеншеном 90 дней (см. app/cleanup.py).
CARPHOTO_DIR = os.path.join(PRIVATE_DIR, "carphoto")
os.makedirs(VOICE_DIR, exist_ok=True)
os.makedirs(CHAT_DIR, exist_ok=True)
os.makedirs(DOC_DIR, exist_ok=True)
os.makedirs(EVIDENCE_DIR, exist_ok=True)
os.makedirs(CARPHOTO_DIR, exist_ok=True)


def public_media_url(path: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/media/{path.lstrip('/')}"


def guard_own_media_url(url: str, *, allow_empty: bool = True) -> str:
    """Ссылка на картинку/медиа принимается ТОЛЬКО на наше хранилище.

    Зачем (правило заведено 2026-08-07 для аватара, распространено 2026-08-12, волна 39).
    Любая чужая ссылка, попавшая в карточку, объявление или профиль, подгружается у КАЖДОГО,
    кто это видит. Хозяин чужого сервера при этом собирает IP, город и время просмотра всех
    наших людей — тихая слежка через обычную картинку. Для приложения, чей продукт — доверие,
    это дороже, чем кажется.

    Правило было применено к аватару, голосовым в чате, документам и фото-доказательствам,
    но не к картинке рекламного объявления: её ставит админ, и внешний адрес там принимался
    как есть. Админ у нас один и свой — но ссылку ему присылает партнёр, и «вставь эту
    картинку» выглядит совершенно обычной просьбой.
    """
    u = (url or "").strip()
    if not u:
        if allow_empty:
            return ""
        raise herr(422, "Нужна ссылка на файл", "Файлға һылтанма кәрәк")
    ours = urlparse(public_media_url("")).netloc.lower()
    parsed = urlparse(u)
    if parsed.scheme in ("http", "https") and parsed.netloc and parsed.netloc.lower() == ours:
        return u[:500]
    if u.startswith("/media/") or u.startswith("/secure/"):
        return u[:500]          # относительный путь = наша же база
    raise herr(422,
               "Картинку нужно загрузить в приложении — чужая ссылка не подойдёт.",
               "Һүрәтте ҡушымтала йөкләргә кәрәк — ят һылтанма ярамай.")


def secure_docs_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/docs/{name}"


def secure_evidence_url(name: str) -> str:
    return f"{settings.media_base_url.rstrip('/')}/secure/evidence/{name}"


def secure_carphoto_url(name: str) -> str:
    """Ссылка на кадр фотоконтроля. Приватная область: отдаётся владельцу и админу,
    и живёт 90 дней (`car_photo_keep_days`), в отличие от документов."""
    return f"{settings.media_base_url.rstrip('/')}/secure/carphoto/{name}"


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


def _validate_upload(data: bytes, allowed_ext: set[str], ext: str, kind: str, sniff_image: bool,
                    probe=None) -> tuple[bytes, str]:
    """Общая валидация загруженных байтов: непусто + лимит размера + whitelist расширений
    + (для фото) magic-bytes. Используется и base64-, и multipart-путём.

    `probe` — необязательный взгляд на ИСХОДНЫЕ байты, до пережатия и срезания метаданных.
    Нужен ровно одному месту (фотоконтроль машины): дата съёмки лежит в тех самых
    метаданных, которые мы строчкой ниже вырезаем, и посмотреть на неё можно только здесь.
    Наружу оттуда уходят лишь безобидные факты (когда снято, каким размером), координаты
    не покидают эту функцию."""
    ext = "".join(c for c in (ext or "").lower() if c.isalnum())
    if not data:
        raise herr(400, f"Файл пустой: {kind}", f"Файл буш: {kind}")
    if len(data) > settings.max_upload_bytes:
        raise herr(413, f"Файл слишком большой: максимум {settings.max_upload_mb} МБ",
                   f"Файл артыҡ ҙур: күп тигәндә {settings.max_upload_mb} МБ")
    if sniff_image:
        # Тип берём из СОДЕРЖИМОГО, а не из заявленного клиентом ext (клиент всегда шлёт «jpg»,
        # а телефон отдаёт png/webp/heic → фото раньше отвергалось и «не сохранялось»).
        detected = _detect_image_ext(data)
        if detected is None:
            raise herr(400, f"Это не похоже на фото: {kind}", f"Был һүрәткә оҡшамаған: {kind}")
        ext = detected
        if probe is not None:
            probe(data, ext)          # исходные байты: дальше они будут ужаты и обезличены
    if ext not in allowed_ext:
        raise herr(400, f"Такой тип файла не подходит: .{ext}", f"Был төр файл ярамай: .{ext}")
    if sniff_image:
        # Срезаем EXIF/GPS и прочие метаданные — ЗДЕСЬ, в единственной точке, через которую
        # проходят обе дороги (multipart и base64) и все загрузки: аватар, чат, документы,
        # доказательства, фото посылки. Иначе координаты съёмки уезжали вместе с фото:
        # снимок из дома в профиле скачивался по прямой ссылке кем угодно (аудит 2026-08-08,
        # волна 21). Наш Android пережимает фото и метаданные теряет сам, но веб-версия шлёт
        # файл как есть, а к API можно прийти и напрямую — правило должно жить на сервере.
        # Сначала ужимаем (волна 97), потом срезаем метаданные: Pillow при пересохранении
        # выбрасывает EXIF сам, но полагаться на это нельзя — исходник мог не пережаться.
        data = strip_image_metadata(shrink_image(data, ext), ext)
    else:
        # Голосовые чистим здесь же. У фото метаданные срезаются с волны 21, а голосовые всё
        # это время сохранялись байт-в-байт — и лежат они по ПУБЛИЧНОЙ ссылке, чтобы собеседник
        # мог послушать. Проверено пробой (волна 142): файл с местом записи внутри скачивался
        # гостем без входа. Женщина записала голосовое дома — адрес дома уехал вместе со звуком.
        data = strip_audio_metadata(data, ext)
    return data, ext


def decode_upload_b64(raw: str, allowed_ext: set[str], default_ext: str, kind: str,
                      sniff_image: bool = False, probe=None) -> tuple[bytes, str]:
    """Безопасная обработка base64 upload: whitelist расширений + лимит размера.
    sniff_image=True — дополнительно проверяем magic-bytes (для фото)."""
    ext = "".join(c for c in default_ext.lower() if c.isalnum()) or default_ext
    if "," in raw and raw.strip().lower().startswith("data:"):
        raw = raw.split(",", 1)[1]
    try:
        data = base64.b64decode(raw, validate=True)
    except Exception:
        raise herr(400, f"Файл не читается: {kind}", f"Файлды уҡып булмай: {kind}")
    return _validate_upload(data, allowed_ext, ext, kind, sniff_image, probe)


async def read_upload(request, allowed_ext: set[str], default_ext: str, kind: str,
                      sniff_image: bool = False, probe=None) -> tuple[bytes, str]:
    """Прочитать загрузку из multipart/form-data (поле `file` [+ опц. `ext`]) ИЛИ из JSON-base64
    (обратная совместимость со старыми установленными клиентами). multipart не держит весь файл
    как base64-строку в памяти (+33%) — Starlette стримит в SpooledTemporaryFile."""
    ctype = request.headers.get("content-type", "")
    if "multipart/form-data" in ctype:
        form = await request.form()
        up = form.get("file")
        if up is None or not hasattr(up, "read"):
            raise herr(400, f"Файл не приложен: {kind}", f"Файл ҡушылмаған: {kind}")
        data = await up.read()
        ext = (str(form.get("ext") or "")
               or os.path.splitext(getattr(up, "filename", "") or "")[1].lstrip(".")
               or default_ext)
        return _validate_upload(data, allowed_ext, ext, kind, sniff_image, probe)
    # JSON base64 — старый клиент
    try:
        body = await request.json()
    except Exception:
        raise herr(400, f"Запрос не понят: {kind}", f"Һорау аңлашылманы: {kind}")
    raw = body.get("photo_b64") or body.get("audio_b64") or ""
    ext = body.get("ext") or default_ext
    return decode_upload_b64(raw, allowed_ext, ext, kind, sniff_image, probe)


def enforce_upload_quota(session: Session, user_id: int) -> None:
    """Суточная квота загрузок на юзера (анти disk-fill / спам). Считаем загрузки за 24ч,
    при превышении — 429. Записываем факт текущей загрузки."""
    edge = utcnow() - timedelta(days=1)
    recent = session.exec(
        select(UploadEvent.id).where(UploadEvent.user_id == user_id, UploadEvent.created_at > edge)
    ).all()
    if len(recent) >= settings.max_uploads_per_day:
        raise herr(429, "Слишком много загрузок за сутки. Попробуй позже.",
                   "Бер тәүлектә артыҡ күп йөкләү. Һуңыраҡ ҡабатла.")
    session.add(UploadEvent(user_id=user_id))
    session.commit()


# ----------------------------- Брони / доступ -----------------------------
def booking_and_ride_for_user(session: Session, booking_id: int, user: User) -> tuple[Booking, Ride]:
    """Вернуть бронь и поездку, если пользователь — пассажир или водитель этой брони."""
    booking = session.get(Booking, booking_id)
    if not booking:
        raise herr(404, "Бронь не найдена", "Урын һаҡлау табылманы")
    ride = session.get(Ride, booking.ride_id)
    if not ride:
        raise herr(404, "Поездка не найдена", "Сәфәр табылманы")
    if booking.passenger_id != user.id and ride.driver_id != user.id:
        raise herr(403, "Нет доступа к этой брони", "Был урын һаҡлауға инеү юҡ")
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


# ----------------------------- Телефон: один номер — один человек -----------------------------
def find_user_by_phone(session: Session, raw: str):
    """ЕДИНСТВЕННАЯ дверь «найти человека по номеру». Возвращает `User` или None.

    Ищет по приведённому виду (`normalize_phone`), а если не нашёл — по написаниям, которые
    могли попасть в базу раньше: «8XXXXXXXXXX», «7XXXXXXXXXX», без плюса, как ввели. Найдя
    старое написание, ЧИНИТ строку — записывает приведённый вид. Так база выправляется сама,
    по одному человеку за вход, без разовой миграции и без риска потерять чужие номера.

    Почему не «просто сравнивать нормализованные»: `User.phone` — обычная колонка, сравнение
    по функции не использовало бы индекс, а список кандидатов даёт то же самое за 2–3 запроса.
    """
    from .security import normalize_phone

    s = (raw or "").strip()
    if not s:
        return None
    norm = normalize_phone(s)
    candidates = [norm, s]
    digits = re.sub(r"\D", "", s)
    if len(digits) == 11 and digits[0] in ("7", "8"):
        candidates += ["+7" + digits[1:], "7" + digits[1:], "8" + digits[1:], digits]
    elif len(digits) == 10 and digits[0] == "9":
        candidates += ["+7" + digits, "7" + digits, "8" + digits, digits]
    seen: set[str] = set()
    for cand in candidates:
        if not cand or cand in seen:
            continue
        seen.add(cand)
        user = session.exec(select(User).where(User.phone == cand)).first()
        if user is None:
            continue
        if user.phone != norm:
            user.phone = norm      # самолечение: дальше этот человек ищется по одному виду
            session.add(user)
            session.commit()
            session.refresh(user)
        return user
    return None


# ----------------------------- Пол (F9, безопасность женщин) -----------------------------
def set_user_gender(session: Session, user: User, gender: str) -> None:
    """ЕДИНСТВЕННОЕ место, где меняется пол. Пишет `User.gender` и гасит подтверждение.

    Пол можно поменять из двух мест — тумблер в кабинете водителя (`/driver/gender`) и
    профиль (`/me/update`). Сбрасывал подтверждение только первый. Дыра: модератор
    подтверждает пол и мужчине (`gender_verified` разрешён для male), а дальше водитель
    меняет пол в профиле на «женщина» — подтверждение остаётся, и он получает публичный
    бейдж «женщина за рулём» и женские заказы. Ровно то, ради чего проверка и вводилась
    («заказала женщину — приехал мужчина»). Держим правило в одном месте, чтобы пути
    снова не разъехались; сторожит `test_gender_writes_go_through_one_door`.
    """
    g = (gender or "").strip().lower()
    was = (user.gender or "").strip().lower()
    user.gender = g
    session.add(user)
    if was == g:
        return
    prof = session.exec(select(DriverProfile).where(DriverProfile.user_id == user.id)).first()
    # Новое заявление — новый просмотр прав. Прежнее подтверждение недействительно.
    if prof is not None and prof.gender_verified:
        prof.gender_verified = False
        session.add(prof)


def set_driver_docs_verdict(session: Session, user: User, profile: DriverProfile, approved: bool) -> None:
    """ЕДИНСТВЕННОЕ место, где выносится вердикт по документам водителя.

    Вердикт ставят три двери: кнопка админа (`/admin/drivers/{id}/moderate`), кнопки в
    телеграм-боте и авто-проверка прав (OCR). Правило «документы отклонили → подтверждение
    пола сгорает» было написано только в первой: пол подтверждают по фото прав, и если
    права признали негодными, подтверждать по ним нечего. Через две другие двери водитель
    с отклонёнными документами сохранял бейдж «женщина за рулём» и женские заказы.
    Сторож — `test_docs_verdicts_go_through_one_door`.
    """
    user.verified = approved
    profile.docs_status = "verified" if approved else "rejected"
    if not approved and profile.gender_verified:
        profile.gender_verified = False
    session.add(user)
    session.add(profile)


def revoke_verification_on_car_change(session: Session, user: User,
                                      profile: DriverProfile) -> bool:
    """Машину подменили после проверки — бейдж «Проверен» гаснет (волна 169).

    Живёт здесь, рядом с `set_driver_docs_verdict`, по той же причине, по которой там живёт
    вердикт: у бейджа должна быть ОДНА дверь. Сторож `test_docs_verdicts_go_through_one_door`
    следит, чтобы никто не трогал `User.verified` из обработчиков напрямую, — и он прав.
    Первая версия этой правки писала `user.verified = False` прямо в роутере, и сторож её поймал.

    Почему бейдж вообще должен гаснуть. Женщина ждёт машину у подъезда вечером; на экране
    «Lada Granta, белая, А123БВ102» и зелёная галочка. Сверить номер — единственный способ
    убедиться, что садишься в ту машину. Если водитель после проверки вписал другой автомобиль,
    галочка относится к машине, которой уже нет: модератор сверял одну, приедет другая.

    Возвращает True, если бейдж действительно сняли (роутеру — чтобы сказать об этом человеку).
    Документы при этом не отклоняем: они в порядке, устарела привязка к автомобилю. Работать
    водителю это не мешает — бейдж про доверие, а не про допуск.
    """
    if not user.verified:
        return False
    user.verified = False
    session.add(user)
    if profile is not None:
        session.add(profile)
    return True


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


def push_bilingual(session: Session, user_id: int, title_ru: str, title_ba: str,
                   body_ru: str, body_ba: str, data: dict | None = None) -> None:
    """Пуш БЕЗ записи в ленте, но на языке человека. Для мгновенных вещей: «осталось 15 минут
    смены», «оффер», «водитель у подъезда» — их читают сейчас или не читают вовсе, и место
    в Центре уведомлений они занимать не должны.

    Зачем отдельный помощник (аудит 2026-08-12, волна 37). Такие пуши писали строкой
    «Осталось 15 минут · Оҙаҡламай ял»: два языка склеены в один текст. Человек с башкирским
    интерфейсом получал сначала русский, а свой язык — через точку в середине; для него это
    выглядит как сообщение с мусором. Правило проекта «любая надпись — два ОТДЕЛЬНЫХ текста»
    (docs/lessons.md, волна 20) на пуши без записи просто никто не распространил.

    Пустой башкирский → русский: пустоту не шлём никогда.
    """
    recipient = session.get(User, user_id)
    if recipient is not None and recipient.language == "ba" and (title_ba or body_ba):
        title, body = (title_ba or title_ru), (body_ba or body_ru)
    else:
        title, body = title_ru, body_ru
    if data:
        send_push(session, user_id, title, body, data)
    else:
        send_push(session, user_id, title, body)


# Предохранитель от «шторма»: сколько уведомлений один человек может получить ПО ОДНОМУ И ТОМУ
# ЖЕ объекту (броне, заявке, посылке) за минуту. Обычная поездка укладывается в единицы событий:
# забронировали → подтвердили → выехал → подъезжает → завершена. Всё, что чаще, — это уже не
# информирование, а звонок в карман (аудит 2026-08-12, волна 52).
_NOTIFY_STORM_PER_MIN = 8


# Виды уведомлений, которые ЗВОНЯТ всегда — даже ночью.
#
# Беда и идущая сделка ждать до утра не могут: SOS и наказания (safety), сообщение
# от попутчика (message), такси в поиске и подаче (instant), деньги и долг (money),
# статусы начатой доставки и поездки (parcel, booking, taxi). Всё остальное — новости,
# которые прекрасно подождут: «появилась поездка по маршруту», «оцени поездку», реклама,
# купоны, сводки.
_LOUD_AT_NIGHT = {"safety", "message", "instant", "money", "parcel", "booking", "taxi"}

# Типы, чей ТЕКСТ не должен светиться на заблокированном экране (аудит 2026-08-08, волна 110).
#
# Телефон лежит на столе в доме, где живёт вся семья, или в кармане у водителя на стоянке
# среди коллег. На погашенном экране высвечивалось целиком: «Поступила жалоба. Категория:
# не заплатил», «Такси на паузе: за месяц накопилось несколько подтверждённых жалоб»,
# «Долг списан: пассажир не заплатил». В районе, где все друг друга знают, это не мелочь —
# это разговор у магазина.
#
# Волна 14 уже закрыла так переписку, но остановилась на ней: приватным помечался КАНАЛ чата,
# а не смысл сообщения. Здесь помечается смысл — клиент по флагу показывает на локскрине
# нейтральное «Юлдаш · Уведомление», а текст открывает после разблокировки.
#
# `safety` — это разборы, жалобы и паузы; ни один SOS этим типом не ходит (проверено пробой),
# так что помощь по-прежнему видна сразу.
_PRIVATE_ON_LOCKSCREEN = {"safety", "money", "docs"}


def _is_quiet_hour(now=None) -> bool:
    """Сейчас ночь по МЕСТНОМУ времени? (аудит 2026-08-08, волна 105)

    Считаем по башкирскому календарю, как долги и сводка: сервер живёт в UTC, а спит человек
    по своим часам. Порог `quiet_hours_to = 0` выключает тишину целиком — на случай, если
    Александр решит, что тихие часы мешают.
    """
    start = int(settings.quiet_hours_from or 0)
    end = int(settings.quiet_hours_to or 0)
    if end <= 0:
        return False
    hour = ((now or utcnow()) + timedelta(hours=settings.local_tz_offset_hours)).hour
    return hour >= start or hour < end if start > end else start <= hour < end


def _notify_storm(s: Session, user_id: int, ref_kind: str, ref_id: "int | None") -> bool:
    """Не пора ли замолчать по этому объекту. True — уведомление глотаем.

    Последний рубеж, а не основная защита: каждое место, которое умеет будить человека, обязано
    само не повторяться (например, статус поездки шлётся только при СМЕНЕ фазы). Но такие
    правила пишутся поштучно и забываются — а цена ошибки лежит на том, кому ночью звонит
    телефон. Здесь общий потолок на пару «человек + объект»: он не различает поводов и потому
    переживает появление новых мест, откуда шлют.

    Уведомления без привязки к объекту (`ref_id` пуст) не трогаем: там нечего группировать,
    а глотать системные сообщения вслепую опаснее, чем пропустить их."""
    if not ref_kind or ref_id is None:
        return False
    edge = utcnow() - timedelta(minutes=1)
    recent = len(s.exec(
        select(Notification.id).where(
            Notification.user_id == user_id,
            Notification.ref_kind == ref_kind,
            Notification.ref_id == ref_id,
            Notification.created_at > edge,
        ).limit(_NOTIFY_STORM_PER_MIN + 1)
    ).all())
    if recent >= _NOTIFY_STORM_PER_MIN:
        log.info(f"[NOTIFY] шторм по {ref_kind}#{ref_id} для user={user_id} — молчим")
        return True
    return False


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
    # Ночью не звоним по несрочному: запись в Центре уведомлений остаётся, человек увидит её
    # утром, а телефон молчит (волна 105). Беда, идущая поездка и чужое сообщение проходят.
    if push and ntype not in _LOUD_AT_NIGHT and _is_quiet_hour():
        push = False
    try:
        with Session(engine) as s:
            if _notify_storm(s, user_id, ref_kind, ref_id):
                return
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
        # data — payload для клиентского роутинга: по нему приложение открывает НУЖНЫЙ экран.
        #
        # Раньше его передавали руками, и передавали не везде: из 37 уведомлений с известным
        # адресатом (`ref_kind` + `ref_id`) адрес несли 18, а 34 приходили пустыми. Тап по
        # такому пушу открывал просто приложение. «Поддержка Юлдаш ответила» — и ищи свой
        # тикет сам; «Заявку приняли» — и ищи поездку сам. Для посылок и такси это уже чинили
        # отдельными заходами (см. комментарии в FcmService.kt), для попуток, поддержки,
        # споров и долга — нет.
        #
        # Чинить 34 места по одному незачем: сервер УЖЕ знает и вид, и номер — он кладёт их
        # в ленту уведомлений строкой выше. Значит адрес можно собрать здесь, один раз.
        # Явный `data` (чаты) по-прежнему главнее: там свой тип канала.
        if not data and ref_kind and ref_id:
            data = {"type": ref_kind, "id": str(ref_id)}
        # Чувствительное просим не показывать на заблокированном экране: жалобы, паузы, долги
        # и документы — не то, что человек хочет читать чужими глазами (волна 110). Флаг идёт
        # ПОВЕРХ адреса выше: тап по уведомлению должен вести туда же, куда и раньше.
        if ntype in _PRIVATE_ON_LOCKSCREEN:
            data = {**(data or {}), "private": "1"}
        # `data` передаём ПО ИМЕНИ, а не пятым позиционным: тест-двойники и старые обёртки
        # объявлены как `lambda s, uid, title, body, **kw` — лишний позиционный их ломает.
        # (Сломал и починил тут же: полный прогон поймал `test_confirm_only_by_driver`.)
        if data:
            send_push(session, user_id, title, body, data=data)
        else:
            send_push(session, user_id, title, body)


# ----------------------------- Подписка на маршрут (RouteWatch) -----------------------------
def _norm_city(s: str) -> str:
    """Нормализация названия города для сравнения подписки с поездкой (регистр/пробелы)."""
    return (s or "").strip().casefold()


def _city_keys(name: str) -> set[str]:
    """Все написания города одним множеством — для сравнения «это тот же город?».

    Приложение подставляет название на языке человека, поэтому подписка «Стерлитамак»
    не срабатывала на рейс «Стәрлетамаҡ»: караульщик просто не получал оповещения
    (аудит 2026-08-08, волна 93). Та же беда, что в поиске поездок (волна 92), только тише —
    человек не видит, что чего-то не пришло, и решает, что по его маршруту никто не ездит.

    Свёртки букв тут мало: «Баймаҡ» и «Баймак» она сводит, а «Стәрлетамаҡ» и «Стерлитамак»
    отличаются ещё и гласными. Единственный честный способ — справочник населённых пунктов.
    """
    from .geo import name_variants

    # Справочник читаем СВОЕЙ короткой сессией и сразу её закрываем. Через сессию вызывающего
    # нельзя: рассылка уведомлений пишет в собственной сессии, а наше чтение держало бы
    # транзакцию открытой — на SQLite это «database is locked», и уведомление молча терялось
    # (поймано полным прогоном после волны 93). Справочник лежит в памяти, так что второй
    # и последующие вызовы в базу не ходят вовсе.
    with Session(engine) as s:
        return {_norm_city(v) for v in name_variants(s, name)} - {""}


def _push_async(items: "list") -> None:
    """FCM-рассылка в фоновом daemon-потоке (своя сессия) — сеть не держит обработчик запроса.
    items: список (user_id, title, body). Ошибки глотаем: пуш вторичен, запись в ленте уже есть.

    Ночью молчим: сюда приходят только новости про маршруты («появилась поездка», «пассажир
    на твоём маршруте»), а они прекрасно ждут до утра. Запись в Центре уведомлений уже сделана
    отдельно — человек проснётся и увидит (аудит 2026-08-08, волна 105).
    """
    if _is_quiet_hour():
        log.info(f"[NOTIFY] тихие часы — {len(items)} оповещений о маршрутах без звука")
        return

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
        r_from, r_to = _city_keys(ride.from_city), _city_keys(ride.to_city)
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
        from .visibility import may_be_notified
        blocked = set(blocked_user_ids(session, ride.driver_id))
        for w in watches:
            if w.watch_kind not in ("rides", "both"):   # G3: эта подписка караулит заявки, не поездки
                continue
            w_from, w_to = _city_keys(w.from_city), _city_keys(w.to_city)
            forward = bool(w_from & r_from) and bool(w_to & r_to)
            backward = w.direction == "both" and bool(w_from & r_to) and bool(w_to & r_from)
            if not (forward or backward):
                continue
            # Дата: если у подписки задан день — матчим только поездку в этот календарный день.
            # Дни сравниваем в МЕСТНОМ календаре: подписка «на 15-е» обязана поймать выезд
            # в четыре утра 15-го, который в базе лежит четырнадцатым числом (волна 79).
            if w.watch_date is not None and local_date(w.watch_date) != local_date(ride.depart_at):
                continue
            # Анти-спам: 1 пуш на подписку в сутки.
            if w.last_notified_at is not None and (now - w.last_notified_at) < timedelta(hours=24):
                continue
            # Проверка доверия стоит ПОСЛЕ дешёвых отсевов, и это не косметика.
            #
            # Она единственная здесь ходит в базу — по два запроса на подписчика. Стояла первой,
            # то есть у поездки «только для своих» доверие проверялось у КАЖДОГО подписчика ленты,
            # включая тех, чья подписка вообще про другой маршрут. При пятистах подписках и трёх
            # подходящих это тысяча запросов вместо шести — и растёт вместе с числом пользователей.
            #
            # Порядок проверок на результат не влияет: все они одинаково отсеивают подписчика,
            # и от перестановки набор получивших уведомление не меняется — меняется только цена.
            # Через общую точку: она проверяет и «только для своих», и чёрный список —
            # человек, которого водитель заблокировал, не должен узнать даже о существовании
            # его поездки (волна 77). Позиция после дешёвых отсевов сохранена.
            if not may_be_notified(session, w.user_id, ride.driver_id,
                                   bool(ride.only_trusted), blocked):
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
        r_from, r_to = _city_keys(request.from_city), _city_keys(request.to_city)
        watches = session.exec(
            select(RouteWatch).where(
                RouteWatch.expires_at > now,
                RouteWatch.user_id != request.passenger_id,   # автору заявки — не себе
            )
        ).all()
        notified = 0
        to_push: list = []
        # Те же правила, что у оповещения о поездке. Раньше их тут не было ни одного: водитель,
        # которого пассажир заблокировал, узнавал из пуша, что тот собрался ехать — когда и куда;
        # а заявка «только для своих» уходила любому караульщику (аудит 2026-08-08, волна 77).
        from .visibility import may_be_notified
        blocked = set(blocked_user_ids(session, request.passenger_id))
        for w in watches:
            if w.watch_kind not in ("requests", "both"):   # эта подписка караулит поездки, не заявки
                continue
            if not may_be_notified(session, w.user_id, request.passenger_id,
                                   bool(getattr(request, "only_trusted", False)), blocked):
                continue
            w_from, w_to = _city_keys(w.from_city), _city_keys(w.to_city)
            forward = bool(w_from & r_from) and bool(w_to & r_to)
            backward = w.direction == "both" and bool(w_from & r_to) and bool(w_to & r_from)
            if not (forward or backward):
                continue
            # Дата: если у подписки задан день, а у заявки есть желаемое время — матчим по дню.
            if (w.watch_date is not None and request.desired_at is not None
                    and local_date(w.watch_date) != local_date(request.desired_at)):   # местный день (волна 79)
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


def notify_admin_telegram(text: str, reply_markup: dict | None = None) -> bool:
    """Уведомление администратору (Александру) в Telegram через бот: запрос звонка и пр.
    Тихо ничего не делает, если бот/chat_id не настроены.

    Возвращает True, только если сообщение РЕАЛЬНО принято Telegram. Раньше функция всегда
    возвращала None и глотала ошибку внутри — то есть вызывающий не мог отличить «доставлено»
    от «не ушло никуда». Для обычного уведомления это неважно, а для повтора по непринятому
    SOS оказалось важно: эскалация помечала сигнал как «напомнили», хотя не напомнила никому
    (аудит 2026-08-08, волна 140)."""
    if not settings.telegram_bot_token or not settings.admin_telegram_chat_id:
        log.info("[ADMIN_TG] не настроено (нет токена/chat_id) — пропуск")
        return False
    try:
        import httpx
        payload = {"chat_id": settings.admin_telegram_chat_id, "text": text}
        if reply_markup:
            payload["reply_markup"] = reply_markup
        r = httpx.post(
            f"https://api.telegram.org/bot{settings.telegram_bot_token}/sendMessage",
            json=payload,
            timeout=8,
        )
        return 200 <= r.status_code < 300
    except Exception as e:  # noqa: BLE001 — уведомление не должно ронять запрос
        log.warning(f"[ADMIN_TG] error {e}")
        return False


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


def sms_channel_live() -> bool:
    """Может ли SMS вообще дойти до человека прямо сейчас — ОДНО правило на весь сервис.

    Зачем отдельная функция. На проде `sms_provider=mock`: SMS никуда не уходят, они пишутся
    в лог (вход у нас через Telegram, юрлица для sms.ru нет). Это осознанное решение и оно
    в порядке — не в порядке было то, что ручки безопасности продолжали считать «скольким
    близким ушло SMS» по длине списка контактов. Женщина нажимала SOS и получала в ответ
    «двоим близким отправлено», курьер на трассе читал «близкие получили твои координаты» —
    и оба переставали звонить сами. Не ушло никому (аудит 2026-08-08, волна 184).

    Правило живёт здесь одно, потому что мест, где надо ответить человеку «позвали или нет»,
    три: красная кнопка SOS, «застрял на трассе» и зимний протокол. Разъедутся они молча.
    """
    if settings.sms_provider == "smsru":
        return bool(settings.sms_ru_api_id)
    if settings.sms_provider == "smsdar":
        return bool(settings.smsdar_id and settings.smsdar_password)
    return False


def sms_will_reach(phones) -> int:
    """Скольким из этих номеров SMS РЕАЛЬНО уйдёт. Ноль, если канал молчит.

    Единственный источник числа «контактов уведомлено» во всех ручках безопасности:
    человек должен видеть факт, а не намерение.
    """
    return len([p for p in (phones or []) if p]) if sms_channel_live() else 0


def send_text(phone: str, text: str) -> bool:
    """Отправка произвольного SMS (SOS, статусы близким). smsru → реально; иначе/фоллбэк — в лог.

    Возвращает True, только если провайдер ПОДТВЕРДИЛ отправку. Мок-режим и сбой шлюза дают
    False: вызывающему важно знать, ушло сообщение или только легло в лог. Понадобилось для
    повтора по непринятому SOS — он помечал сигнал доставленным вслепую (волна 140)."""
    mp = mask_phone(phone)
    if sms_channel_live() and settings.sms_provider == "smsru":
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
            return bool(ok)
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsru error {e}")
            if not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
            return False
    elif sms_channel_live() and settings.sms_provider == "smsdar":
        try:
            ok, info = _smsdar_send(phone, text)
            log.info(f"[SMS] {mp}: smsdar sent={ok} ({info})")
            if not ok and not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
            return bool(ok)
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsdar error {e}")
            if not settings.is_prod:
                log.info(f"[SMS-FALLBACK] {mp}: {text}")
            return False
    else:
        # Мок-провайдер разрешён и в проде (SMS заморожен, вход через Telegram). Тело может содержать
        # имя и live-ссылку /t/{token} (capability-URL на живую гео) — в проде тело НЕ логируем.
        if settings.is_prod:
            log.info(f"[SMS-MOCK] {mp}: (тело скрыто в проде)")
        else:
            log.info(f"[SMS-MOCK] {mp}: {text}")
        return False        # мок — это запись в лог, а не доставленное человеку сообщение


# Сколько SMS близким человек может отправить за счёт платформы за сутки. Число взято тем же,
# что уже действовало для трекинг-ссылок посылок, — там потолок додумали, а у «поделиться
# поездкой» забыли (аудит 2026-08-12, волна 48). Двадцать — это заведомо больше, чем нужно
# честному человеку: у него от силы двое-трое близких и одна поездка за раз.
FAMILY_SMS_PER_DAY = 20


def family_sms_sent_today(session: Session, user_id: int) -> int:
    """Сколько SMS близким этот человек уже отправил за последние сутки."""
    edge = utcnow() - timedelta(days=1)
    return len(session.exec(
        select(FamilySmsLog.id).where(FamilySmsLog.user_id == user_id, FamilySmsLog.created_at > edge)
    ).all())


def may_send_family_sms(session: Session, user_id: int, kind: str) -> bool:
    """Можно ли отправить ещё одну SMS близкому за счёт платформы. True — можно (расход уже
    записан), False — потолок исчерпан.

    ОДНО правило на все входы: попутка, такси, посылка. Номер близкого никем не подтверждён —
    человек вводит его сам, и сервис честно шлёт туда сообщение со своим именем. Значит это
    одновременно расход платформы и канал, которым можно достать чужого человека. Пока потолок
    жил только в трекинге посылок, достаточно было перейти в соседний раздел (аудит 2026-08-12,
    волна 48).

    Расход пишем ДО отправки: сбой шлюза не должен превращаться в бесплатную попытку, иначе
    потолок обходится подбором момента, когда провайдер отвечает ошибкой.

    Не бросает исключение: одни вызывающие обязаны сказать человеку «слишком много» (там, где
    он нажал кнопку сам), другие — промолчать (статусы поездки идут автоматом, и красная ошибка
    посреди дороги никому не поможет). Решение — за вызывающим.

    Экстренную помощь сюда НЕ заводим: у SOS свой троттл, и он намеренно щедрее — жизнь дороже
    денег на сообщения."""
    if family_sms_sent_today(session, user_id) >= FAMILY_SMS_PER_DAY:
        log.info(f"[SMS] потолок «близким» на сегодня исчерпан у user={user_id} ({kind})")
        return False
    session.add(FamilySmsLog(user_id=user_id, kind=kind))
    session.commit()
    return True


def _sms_failed() -> Exception:
    """Код входа не ушёл: человек стоит на пороге приложения и не может войти (волна 173).

    Раньше здесь был односторонний русский текст «SMS не отправлено». Это самое неудачное место
    для одного языка: башкироязычный человек в этот момент вообще ничего не может сделать —
    ни войти, ни понять, что случилось, ни узнать, куда обращаться. Правило проекта «каждая
    видимая надпись на двух языках» существует ровно для таких минут, а не для украшения.

    Текст ещё и говорит, ЧТО делать: сбой у оператора связи проходит сам, и «повтори через
    минуту» полезнее, чем «ошибка 502».
    """
    from .errors import herr
    return herr(
        502,
        "Не получилось отправить код. Попробуй ещё раз через минуту.",
        "Кодты ебәреп булманы. Бер минуттан ҡабатлап ҡара.",
    )


def send_sms(phone: str, code: str) -> None:
    """Отправка OTP. `smsru` — реально через sms.ru; иначе мок (код в лог).
    Если sms.ru НЕ отправил (напр. нет одобренного отправителя) — код падает в лог,
    чтобы вход работал на период настройки отправителя."""
    mp = mask_phone(phone)
    if sms_channel_live() and settings.sms_provider == "smsru":
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
                    raise _sms_failed()
                log.info(f"[OTP] {mp} -> {code}")  # фоллбэк: SMS не ушла → код в лог (только dev)
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsru error {e}")
            if settings.is_prod:
                raise _sms_failed()
            log.info(f"[OTP] {mp} -> {code}")  # фоллбэк при ошибке сети (только dev)
    elif sms_channel_live() and settings.sms_provider == "smsdar":
        try:
            ok, info = _smsdar_send(phone, f"Yuldash: kod {code}")
            log.info(f"[SMS] {mp}: smsdar sent={ok} ({info})")
            if not ok:
                if settings.is_prod:
                    raise _sms_failed()
                log.info(f"[OTP] {mp} -> {code}")  # фоллбэк: SMS не ушла → код в лог (только dev)
        except HTTPException:
            raise
        except Exception as e:  # noqa: BLE001
            log.warning(f"[SMS] {mp}: smsdar error {e}")
            if settings.is_prod:
                raise _sms_failed()
            log.info(f"[OTP] {mp} -> {code}")  # фоллбэк при ошибке сети (только dev)
    else:
        if settings.is_prod:
            # SMS заморожен в проде — основной вход через мессенджеры. Понятный ответ вместо 500.
            # Читает человек, который пытается войти: текст обязан быть на двух языках (волна 94).
            raise herr(503, "Вход по SMS временно не работает. Зайди через мессенджер 💚",
                       "SMS аша инеү ваҡытлыса эшләмәй. Мессенджер аша кер 💚")
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


def driver_rating(session: Session, driver_id: int) -> tuple[float, int]:
    """Рейтинг человека КАК ВОДИТЕЛЯ: только оценки за поездки, которые он вёз сам.

    Зачем отдельно от общего рейтинга (аудит 2026-08-08, волна 194 — соседняя дверь к 186,
    где то же самое чинили у курьера). Общий балл человека складывается из всех его ролей
    сразу: он пассажир такси, он попутчик, он отправитель посылок и он же водитель. Для
    ВИТРИНЫ это правильно — доверие человеку одно, и `user_rating` там и остаётся. Но
    лестница качества отнимает у него РАБОТУ, а работа у него одна — за рулём.

    Что было. Марат работает таксистом. Сам он тоже ездит пассажиром, и трое чужих водителей
    поставили ему по единице как ПАССАЖИРУ. `DriverProfile.rating` — то самое число, по
    которому matcher решает, кому предложить заказ, — стало 1.0, и Марат ушёл в конец
    очереди офферов за то, что было в другой роли. Ни одной жалобы на его вождение при этом
    не было.

    Правила подсчёта берём общие (`_rating_from_rows`): тот же кап на пару «оценил–оценённый»
    и то же окно свежести. Другой здесь только НАБОР строк — не все оценки человека, а те,
    где за рулём был он: быстрый заказ такси или рейс попутки.
    """
    taxi = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at)
        .join(InstantOrder, Rating.order_id == InstantOrder.id)      # type: ignore[arg-type]
        .where(
            Rating.ratee_id == driver_id,
            Rating.excluded == False,        # noqa: E712 — снятые оценки вне среднего
            InstantOrder.driver_id == driver_id,
        )
    ).all())
    pooling = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at)
        .join(Booking, Rating.booking_id == Booking.id)              # type: ignore[arg-type]
        .join(Ride, Booking.ride_id == Ride.id)                      # type: ignore[arg-type]
        .where(
            Rating.ratee_id == driver_id,
            Rating.excluded == False,        # noqa: E712
            Ride.driver_id == driver_id,
        )
    ).all())
    # Два набора складываем ДО расчёта: кап на пару и окно свежести должны видеть всю
    # водительскую историю разом, иначе постоянный попутчик обойдёт кап через второй сервис.
    return _rating_from_rows(taxi + pooling)


def passenger_rating(session: Session, passenger_id: int) -> tuple[float, int]:
    """Балл человека КАК ПАССАЖИРА: только оценки за поездки, в которых он ехал.

    Зеркало `driver_rating` (волна 195). Нужно там, где водитель решает, брать ли заказ:
    в оффере ему показывают карточку пассажира — рейтинг и число поездок. Число поездок
    там всегда считалось по-пассажирски, а рейтинг рядом был общий, со всеми ролями сразу.
    Гульнара — спокойная пассажирка (три пятёрки), но своя машина у неё старая, и как
    водителя попутки её оценили на единицы: в карточке пассажирки водитель видел 3.0.

    Правила подсчёта общие (`_rating_from_rows`), другой только набор строк: быстрый заказ
    такси и рейс попутки, где он ехал. Посылки сюда не идут — отправитель это другая роль,
    и число поездок в той же карточке их тоже не считает.
    """
    taxi = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at)
        .join(InstantOrder, Rating.order_id == InstantOrder.id)      # type: ignore[arg-type]
        .where(
            Rating.ratee_id == passenger_id,
            Rating.excluded == False,        # noqa: E712 — снятые оценки вне среднего
            InstantOrder.passenger_id == passenger_id,
        )
    ).all())
    pooling = list(session.exec(
        select(Rating.rater_id, Rating.stars, Rating.created_at)
        .join(Booking, Rating.booking_id == Booking.id)              # type: ignore[arg-type]
        .where(
            Rating.ratee_id == passenger_id,
            Rating.excluded == False,        # noqa: E712
            Booking.passenger_id == passenger_id,
        )
    ).all())
    return _rating_from_rows(taxi + pooling)


def rated_as_driver(session: Session, ratee_id: int, *, booking_id: "int | None" = None,
                    order_id: "int | None" = None) -> bool:
    """Поставлена ли ЭТА оценка человеку за его работу за рулём (а не как пассажиру).

    Нужна отдельно от `driver_rating`, потому что водительский балл от чужой роли не
    меняется — а лестница всё равно сработала бы, если он и так лежал ниже порога. Тогда
    наказание возобновлялось бы само, от действия постороннего человека: отсидел паузу,
    кто-то оценил тебя как пассажира — и снова здравствуй (тот же капкан ловили в волне 186).
    """
    if order_id is not None:
        o = session.get(InstantOrder, order_id)
        return bool(o is not None and o.driver_id == ratee_id)
    if booking_id is not None:
        b = session.get(Booking, booking_id)
        r = session.get(Ride, b.ride_id) if b is not None else None
        return bool(r is not None and r.driver_id == ratee_id)
    return False


# Анти-накрутка бейджа «N поездок» (аудит 2026-08-07). Средний балл от накрутки парой аккаунтов
# защищён капом выше, а бейдж — не был ничем: он просто считал брони со статусом done, а перевести
# бронь в done можно было за три запроса, не проехав ни метра. Бейдж доверия — и есть продукт
# «между своими»: по нему человек решает, садиться ли в машину, поэтому подделка тут дороже
# накрученной звезды. Кап тот же по смыслу, что рейтинговый, но щедрее: постоянный попутчик
# (сосед на работу два раза в неделю) — норма района, а не сговор, и обрезать его до трёх
# поездок в месяц значило бы врать о честном водителе в другую сторону.
TRIPS_PAIR_CAP = 8
TRIPS_PAIR_WINDOW_DAYS = 30


def member_since(created_at) -> str:
    """«С нами с <месяц год>» строкой «2026-09». Месяц МЕСТНЫЙ (волна 203).

    Считался серверным: человек, зарегистрировавшийся в ночь на первое сентября по Уфе,
    показывался как «с августа». Мелочь, но это бейдж доверия — по нему решают, садиться
    ли в машину, — и та же семья ошибок, что ранний рейс из волны 79.
    """
    return local_month(created_at)


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


def _is_verified_female_driver(drv, prof) -> bool:
    """Обёртка над правилом из `safety_logic` — импорт локальный, там цикл (он тянет services)."""
    from .safety_logic import is_verified_female_driver
    return bool(drv is not None and prof is not None and is_verified_female_driver(drv, prof))


def ride_out_with(ride: Ride, users: dict, profiles: dict, rating_agg: dict, trips_agg: dict | None = None) -> RideOut:
    """RideOut из предзагруженных батчей (без запросов в БД)."""
    drv = users.get(ride.driver_id)
    prof = profiles.get(ride.driver_id)
    car = f"{prof.car_make} {prof.car_model}".strip() if prof else ""
    avg, cnt = rating_agg.get(ride.driver_id, (0.0, 0))
    rating = round(avg, 1) if cnt > 0 else (prof.rating if prof else 5.0)  # реальный рейтинг; до отзывов — сид
    trips = (trips_agg or {}).get(ride.driver_id, 0)                       # F8: завершённых поездок водителя
    since = member_since(drv.created_at if drv else None)   # F8: «С нами с <мес год>», месяц местный
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
        # Пол берём у ЧЕЛОВЕКА (`User.gender`) — он переехал туда, потому что нужен и
        # пассажирке для фильтра «только женщины». Но в ВИТРИНУ он попадает лишь после
        # подтверждения модератором по фото прав: самодекларации мало, иначе бейдж «женщина
        # за рулём» ставит себе кто угодно. Оба условия — из разных веток, при слиянии
        # 2026-08-12 сведены вместе, потерять любое нельзя.
        # Само правило — одной точкой в `safety_logic.is_verified_female_driver`: те же две
        # половины проверяют лента попуток и подбор такси, и расходиться им нельзя.
        driver_is_woman=_is_verified_female_driver(drv, prof),
    )


def rides_out(rides: list, session: Session) -> list:
    """Список поездок → list[RideOut] одним батчем (4 запроса вместо 4×N)."""
    users, profiles, rating_agg, trips_agg = drivers_bundle(session, {r.driver_id for r in rides})
    return [ride_out_with(r, users, profiles, rating_agg, trips_agg) for r in rides]


def ride_out(ride: Ride, session: Session) -> RideOut:
    """Одна поездка → RideOut (обёртка над батчем для единичных вызовов)."""
    return rides_out([ride], session)[0]


def public_ride_payload(item, *, full: bool = False):
    """Публичная витрина поездки без точного места встречи.

    Телефон и точная точка сбора раскрываются только участникам подтверждённой
    брони через `/bookings/{id}/details`.

    СВОБОДНЫЙ КОММЕНТАРИЙ тоже чистим (аудит 2026-08-08, волна 138). Точку сбора и координаты
    прятали с самого начала — а рядом лежало поле, куда водитель своими руками пишет то же самое:
    «заберу у дома, Баймак ул. Ленина 12, звони +7 999 111-22-33». Оно уходило В ЛЕНТЕ и БЕЗ
    ВХОДА: любой человек в интернете, не заводя аккаунта, читал адреса и телефоны водителей
    всего района. Дверь была заперта, окно рядом — открыто.

    Маскируем не весь текст, а только личное: телефон, номер дома, координаты (общая мойка
    `scrub_text`). Смысл объявления остаётся — «еду через Сибай, заберу у автовокзала»
    не трогается вовсе.

    `full=True` — для своих: водителя и пассажира с живой бронью. Им ориентир у дома и телефон
    как раз и написаны.
    """
    patch = {"pickup": "", "pickup_lat": None, "pickup_lng": None}
    if isinstance(item, RideOut):
        if not full:
            patch["comment"] = scrub_text(item.comment or "")
        return item.model_copy(update=patch)
    data = dict(item)
    if not full:
        patch["comment"] = scrub_text(data.get("comment") or "")
    data.update(patch)
    return data


def public_rides_payload(items: list, *, full: bool = False):
    return [public_ride_payload(item, full=full) for item in items]


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


def geocode_city(name: str, *, allow_external: bool = True) -> tuple[float, float] | None:
    """Координаты города. Приоритет (волна 2, география): 1) справочник Settlement
    (точное имя RU/BA, без регистра), 2) старый CITY_COORDS, 3) Яндекс.Геокодер
    (если задан ключ). Нужно для радиус-поиска поездок.

    `allow_external=False` — ходить ТОЛЬКО по своим спискам, наружу не выходить. Нужно там,
    где имя города приходит от человека БЕЗ ВХОДА в аккаунт: у Яндекс.Геокодера суточный лимит
    и он платный, а выдуманное название стоит нам запроса. Пробой волны 34: 25 анонимных
    обращений к погоде с несуществующими городами = 50 платных запросов, и лимит на день
    выбирается за минуту — вместе с ним ломается радиус-поиск для настоящих людей.
    """
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
    if not allow_external:
        return None            # своих списков не хватило, а наружу нам отсюда нельзя
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
    """Подсказки точек сбора для города — чаще выбираемые первыми (usage_count ↓).

    Наружу отдаём ТОЛЬКО курируемые ориентиры (`is_seed`). Пользовательские копятся в базе
    для статистики и будущего одобрения, но в публичный справочник не попадают.

    Почему так (аудит 2026-08-08, волна 91). Справочник пополнялся тем, что водитель написал
    в поле «где встречаемся», — вместе с координатами. А пишут туда живым языком: «у дома 15
    по Гагарина, синие ворота», «звони +7…, подъеду». Проверено запросом: и адрес с точными
    координатами, и телефон оказывались в справочнике, который отдаётся БЕЗ входа кому угодно.
    То есть чужой дом становился публичной подсказкой для всего города, а рядом в коде стояло
    обещание «ориентиры — не персональные данные».

    Модерация текста тут не спасает: она помечает запись для админа, но не отменяет сохранение
    (осознанное решение проекта). Значит, разделять надо на выдаче.
    """
    from .geo import bare_name, fold   # локальный импорт: geo тянет services на верхнем уровне
    q = select(PickupPoint).where(PickupPoint.is_seed == True)  # noqa: E712 — SQL IS TRUE
    q = q.order_by(PickupPoint.usage_count.desc(), PickupPoint.id.asc())
    rows = session.exec(q).all()
    if city and city.strip():
        # Город сверяем СВЁРНУТЫМ именем: в справочнике ориентиров он записан по-башкирски
        # («Баймаҡ»), а поездки приходят с русским написанием («Баймак») — и подсказки
        # для целого района молча не находились (аудит 2026-08-08, волна 91). Функция
        # свёртки в проекте уже была, её просто сюда не применили.
        key = fold(bare_name(city))
        rows = [p for p in rows if fold(bare_name(p.city)) == key]
    return list(rows)[: max(1, min(limit, 50))]


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
        from .geo import bare_name, fold   # локальный импорт (см. suggest_pickup_points)
        # Тот же свёрнутый ключ, что и в подсказках: иначе «Баймак» и «Баймаҡ» будут двумя
        # разными городами, и один ориентир заведётся дважды (волна 91).
        key = fold(bare_name(city))
        existing = [p for p in session.exec(select(PickupPoint)).all() if fold(bare_name(p.city)) == key]
        pt = next((p for p in existing if fold(p.title_ru) == fold(title_ru)), None)
    if pt is not None:
        pt.usage_count += 1
        session.add(pt)
        session.commit()
        session.refresh(pt)
        return pt
    # Новой точки нет — создаём, только если есть название + валидные координаты. Запись
    # остаётся ВНУТРЕННЕЙ (`is_seed=False`): в публичные подсказки она не идёт, но по ней видно,
    # какие ориентиры люди называют сами, — из этого потом растёт курируемый справочник.
    #
    # Текст с контактом (телефон, «пиши в вотсап») не сохраняем совсем: даже во внутреннем
    # списке ему делать нечего, а модерация такие записи только помечает (волна 91).
    from .antifraud import moderate_text   # локальный импорт: antifraud тянет services
    if moderate_text(title_ru, check_contact=True):
        return None
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


def sms_lang_of(session: Session, user_id: int) -> str:
    """Язык, на котором писать SMS близким этого человека: "ba" или "ru".

    У доверенного контакта своего языка в базе нет — мы про него ничего не знаем, кроме
    имени и номера. Поэтому берём язык ТОГО, кто его добавил: человек знает свою маму лучше
    нас, и если он сам сидит в приложении по-башкирски, то и родным привычнее башкирский.

    Зачем (аудит 2026-08-08, волна 95). Все SMS близким уходили только по-русски: «Юлдаш:
    Гульнара села в машину», «отправил тебе посылку», ссылка слежения. В башкироязычной семье
    мама получала тревожное сообщение на чужом языке — и не понимала, что происходит.
    Правило проекта «любая надпись на двух языках» до SMS просто не дошло.
    """
    u = session.get(User, user_id)
    return "ba" if (u is not None and (u.language or "") == "ba") else "ru"


def pick_lang(lang: str, ru: str, ba: str) -> str:
    """Текст на нужном языке. Пустой перевод → русский: пустоту не отправляем никогда."""
    return ba if (lang == "ba" and ba.strip()) else ru


def short_name(full: str) -> str:
    """Имя для публичной витрины: «Гульнара Ахметова» → «Гульнара А.».

    Зачем (аудит 2026-08-08, волна 146). Страница водителя открыта без входа, а оценка в коде
    объявлена анонимной — но опубликованный текст отзыва нёс полное имя автора из профиля.
    Значит посторонний по одной ссылке собирал поимённый список тех, кто с этим водителем ездит.
    В районе, где все друг друга знают, это готовый ответ на вопрос «с кем она ездит».

    Полностью прятать нельзя: отзыв без человека читается как накрутка, а доверие в Юлдаше
    и держится на том, что за словами стоит сосед. Инициал — середина: сосед узнаётся, список
    посторонним не собирается.
    """
    части = (full or "").strip().split()
    if not части:
        return "Аноним"
    if len(части) == 1:
        return части[0][:40]
    return f"{части[0][:40]} {части[1][0]}."
