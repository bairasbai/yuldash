"""Авторизация: телефон+OTP, Telegram-вход (бот+код), VK/WhatsApp-заглушки,
профиль `/me`, регистрация push-токена."""
from datetime import timedelta
from typing import Optional
import hmac
import hashlib
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, or_, text, update
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..account import delete_user_account, guard_can_delete
from ..antifraud import (MESSAGE_FLAG_CONTACT, guard_device_not_banned, moderate_open_text,
                         normalize_device_id, phone_looks_recycled, release_phone,
                         remember_login_device, notify_login_device, notify_phone_release)
from ..config import _phone_key, settings
from ..db import engine, get_session
from ..errors import herr
from ..logs import admin_action, log
from ..models import (
    Ad, Booking, DeviceToken, DriverProfile, InstantOrder, Message, Notification, OtpCode,
    Payment, Rating, RequestResponse, Ride, SosEvent, TgAuth, User, UserRole,
    WebPushSubscription,
)
from ..security import (
    current_user, gen_otp, is_placeholder_phone, issue_tokens, normalize_phone,
    revoke_all_refresh, rotate_refresh, lock_refresh_user,
)
from ..services import (
    find_user_by_phone, guard_own_media_url, send_sms, set_driver_docs_verdict,
    set_user_gender, user_rating,
)
from ..safety_logic import GENDERS
from ..trust_service import record_login_consents
from ..timeutil import utcnow

router = APIRouter(tags=["auth"])

# Сколько неудачных попыток кода допускаем НА ОДИН НОМЕР за время жизни кодов (5 минут).
# Считается по всем живым кодам номера сразу — см. комментарий в `verify`.
MAX_OTP_ATTEMPTS_PER_PHONE = 15


def _lock_otp_phone(session: Session, phone: str) -> None:
    """Serialize SMS issuance/verification, including a phone with no OTP/User yet."""
    dialect = session.get_bind().dialect.name
    if dialect == "postgresql":
        # Stable across workers; the phone is never sent to logs/lock metadata.
        key = int.from_bytes(hashlib.sha256(b"yuldash-otp-phone\0" + phone.encode()).digest()[:8],
                             "big", signed=True)
        session.execute(text("SELECT pg_advisory_xact_lock(CAST(:key AS bigint))"), {"key": key})
    elif dialect == "sqlite":
        # Even a zero-row UPDATE obtains SQLite's transaction writer lock.
        session.execute(update(OtpCode).where(OtpCode.phone == phone).values(attempts=OtpCode.attempts))
    else:
        raise RuntimeError("OTP phone locking requires PostgreSQL or SQLite")


def _maybe_promote_admin(session: Session, user: User, *, log_action: bool = True):
    """Автоадмин: вход с Telegram-id владельца ИЛИ с админ-телефона (config) → роль admin.
    Реюз admin_telegram_chat_id + список admin_phones. Кабинет админа появляется сам.

    Номера сверяем через `_phone_key` (последние 10 цифр) — тем же правилом, что стартовая
    проверка конфигурации в `config.validate_production`. Раньше здесь стояло точное
    сравнение строк, а телефон в базе всегда нормализован (`_norm_phone` → «+7…»): запись
    `ADMIN_PHONES=8987…` в .env молча не срабатывала, и владелец не попадал в свой кабинет
    админа, а стартовая проверка об этом не предупреждала — она-то номера нормализует.
    """
    admin_keys = {_phone_key(p) for p in (settings.admin_phones or "").split(",") if p.strip()}
    by_tg = bool(settings.admin_telegram_chat_id) and user.telegram_id == settings.admin_telegram_chat_id
    by_phone = bool(user.phone) and _phone_key(user.phone) in admin_keys
    заслужил = by_tg or by_phone
    event = None
    if заслужил and user.role != UserRole.admin:
        user.role = UserRole.admin
        session.add(user)
        # Выдача прав администратора — самое чувствительное событие в системе: этот человек
        # увидит сигналы SOS с координатами, паспорта водителей, переписку с поддержкой
        # и все телефоны. В журнал это не писалось вовсе (аудит 2026-08-08, волна 150).
        event = ("role.promote", {"target_user": user.id, "by": "telegram" if by_tg else "phone"})
    elif user.role == UserRole.admin and not заслужил and admin_keys:
        # Обратная сторона, которой не было: права НЕЛЬЗЯ было отобрать. Убрать номер
        # из настроек недостаточно — роль уже записана в базу и живёт вечно. Когда появится
        # помощник-модератор, «уволить» его можно было бы только руками в базе, и никто
        # об этом не вспомнит.
        #
        # Теперь вход сверяет роль с настройками в обе стороны: нет в списке — права снимаются
        # при первом же входе. Условие `admin_keys` намеренно: пустой список означает
        # «настройка не заполнена», и разжаловать по нему нельзя — иначе один неверно
        # прочитанный .env оставит сервис без администратора вообще.
        user.role = UserRole.passenger
        session.add(user)
        event = ("role.revoke", {"target_user": user.id, "reason": "нет в ADMIN_PHONES"})
    if event and log_action:
        admin_action(user.id, event[0], **event[1])
    return event


def _is_owner_telegram(frm: dict) -> bool:
    """Это владелец пишет боту? Только он управляет ботом и жмёт инлайн-кнопки админа.

    Пустой `ADMIN_TELEGRAM_CHAT_ID` НИКОГО не пускает (аудит 2026-08-07). Раньше сравнение
    шло в лоб — `str(frm.get("id")) == str(settings.admin_telegram_chat_id)`. При незаданной
    настройке справа получалась пустая строка, и апдейт с `from.id = ""` совпадал с ней:
    подделав такой апдейт, посторонний одобрял водителей, объявления и платежи. Сам вебхук
    прикрыт секретным заголовком, а прод без него не стартует (`config.validate_production`),
    так что дыра была второго эшелона — но настройка, которой нет, не должна открывать дверь,
    она должна её закрывать.
    """
    owner = str(settings.admin_telegram_chat_id or "").strip()
    if not owner:
        return False
    return str(frm.get("id") or "").strip() == owner


def _norm_phone(raw: str) -> str:
    """Номер из Telegram-контакта — тем же правилом, что и везде (`normalize_phone`).

    Своя реализация тут была не просто лишней, а неверной: она оставляла только цифры и
    ведущий плюс, поэтому номер, которым человек поделился в виде «89991234567», становился
    «+89991234567» — несуществующий код страны 8. С таким номером он не совпадал сам с собой,
    введённым как «+7…», и получал второй аккаунт. Третья копия одного правила (были ещё
    `config._phone_key` и `security.normalize_phone`) — и именно копия ошибалась.

    `config._phone_key` остаётся отдельно осознанно: он делает другую работу — ключ для
    СРАВНЕНИЯ с номерами из .env, а не канонический вид для хранения.
    """
    return normalize_phone(raw)


def _name_flag(name: str, user_id: Optional[int] = None) -> str:
    """Метка модерации для ОТОБРАЖАЕМОГО имени ('' — чисто).

    Имя видно везде: в карточках поездок, в ленте заявок, в откликах, в чате, в отзывах.
    Открытые поля (комментарий заявки, отклик, отзыв, описание посылки) проверяются
    `moderate_open_text` с 2026-08-03 — имя было единственным публичным полем, куда проверку
    забыли навесить (аудит 2026-08-07). Телефон в имени («Такси Баймак 8987…») — это
    объявление в обход приложения, то есть обход комиссии в Такси и Курьере: ровно то,
    от чего защищает проверка остальных полей.
    """
    return moderate_open_text((name or "").strip(), user_id)


def _guard_display_name(name: str, user_id: Optional[int] = None) -> str:
    """Имя, которое человек задаёт сам → честный отказ с объяснением.

    В отличие от комментария имя редактируют осознанно и результат видят сразу, поэтому
    здесь 422 с текстом, а не молчаливая метка: молча обрезать чужое имя хуже, чем сказать,
    что так нельзя.
    """
    n = (name or "").strip()
    if not n:
        return ""
    kind = _name_flag(n, user_id)
    if kind == MESSAGE_FLAG_CONTACT:
        raise herr(422,
                   "В имени нельзя указывать телефон или ссылку.",
                   "Исемдә телефон йәки һылтанма күрһәтергә ярамай.")
    if kind:
        raise herr(422,
                   "Такое имя показать нельзя — выбери другое.",
                   "Бындай исемде күрһәтеп булмай — башҡаһын һайла.")
    return n[:120]


def _safe_display_name(name: str) -> str:
    """Имя, пришедшее ИЗВНЕ (профиль Telegram) → тихая замена, без отказа.

    Отказать нельзя: это сломает вход человеку, который просто зашёл через бота. Но и брать
    как есть нельзя — имя в Telegram человек ставит себе сам, туда так же помещается телефон.
    Поэтому помеченное имя не сохраняем, а отдаём пустую строку — вызывающий подставит
    нейтральное.
    """
    n = (name or "").strip()
    return "" if (not n or _name_flag(n)) else n[:120]


def _clean_avatar_url(raw: str) -> str:
    """Аватар принимаем ТОЛЬКО ссылкой на наше хранилище (пустая строка = сброс).

    Правило одно на весь проект — `services.guard_own_media_url` (волна 39): чужая ссылка,
    попавшая в карточку или чат, подгружается у КАЖДОГО, кто её видит, и хозяин чужого сервера
    собирает их IP, город и время просмотра. Раньше та же проверка жила здесь своей копией,
    а рекламная картинка, где она нужна ровно так же, осталась без неё.
    """
    return guard_own_media_url(raw)


def _review_login_active(phone: str) -> bool:
    """Тестовый аккаунт модерации сторов (B9b-4). Активен ТОЛЬКО когда в env заданы ОБА
    review_phone и review_code — иначе номер живёт обычной SMS-жизнью. Код не логируем."""
    # Сравниваем ПРИВЕДЁННЫЕ номера: во `verify` номер уже приведён, а в .env его могли
    # записать как угодно — иначе тестовый режим просто перестал бы включаться.
    return bool(settings.review_phone and settings.review_code
                and normalize_phone(phone) == normalize_phone(settings.review_phone))


def _set_user_phone(session: Session, user: User, phone: str, *, commit: bool = True) -> None:
    """Сохранить реальный номер юзеру. Не перезаписываем, если номер уже занят
    другим юзером (User.phone unique) — тогда тихо оставляем как есть.

    Вторая дверь к тому же полю, что и вход (Telegram делится номером). Без приведения к
    одному виду она пускала мимо проверки: у одного «+79991234567», второй сохраняет
    «89991234567» — строки разные, `clash` пуст, UNIQUE молчит, и получаются два аккаунта
    на один номер. Ровно та дыра, что во входе; чинить надо ОБЕ двери, иначе правило
    держится там, где о нём вспомнили.
    """
    phone = normalize_phone(phone)
    if not phone or user.phone == phone:
        return
    clash = find_user_by_phone(session, phone, commit=commit)
    if clash is not None and clash.id != user.id:
        return
    user.phone = phone
    session.add(user)
    if commit:
        session.commit()
    else:
        session.flush()


def _complete_login(session: Session, user: User, device_id: str, *,
                    promote_admin: bool = True, released_user_id: int | None = None,
                    review_session: bool = False):
    """Одна запись аккаунта, кода и ключей; вторичные сигналы после commit."""
    user = lock_refresh_user(session, user.id)
    event = _maybe_promote_admin(session, user, log_action=False) if promote_admin else None
    new_device = remember_login_device(session, user, device_id, commit=False, notify=False)
    record_login_consents(session, user.id, commit=False)
    uid, phone = user.id, user.phone
    tokens = issue_tokens(session, uid, review_session=review_session)
    session.refresh(user)
    response_user = user.model_dump()
    secondary = []
    if event:
        secondary.append(lambda: admin_action(uid, event[0], **event[1]))
    if released_user_id is not None:
        secondary.append(lambda: notify_phone_release(session, released_user_id))
    if new_device:
        secondary.append(lambda: notify_login_device(session, uid, phone))
    for notify in secondary:
        try:
            notify()
        except Exception as exc:
            session.rollback()
            log.warning("[AUTH] post-commit signal failed: %s", type(exc).__name__)
    return {**tokens, "user": response_user}


# ----------------------------- Телефон + OTP -----------------------------
class PhoneIn(BaseModel):
    phone: str


class VerifyIn(BaseModel):
    phone: str
    code: str
    name: str = Field("", max_length=120)


@router.post("/auth/request-code")
def request_code(body: PhoneIn, session: Session = Depends(get_session),
                 x_device_id: str = Header(default="", alias="X-Device-Id")):
    # Анти-фрод (B8-1): забаненное устройство не регистрируется даже новым номером
    # (гейт до отправки SMS — не тратим деньги на код мошеннику).
    guard_device_not_banned(session, x_device_id)
    # Один номер — одна запись: код кладём под ПРИВЕДЁННЫМ видом, иначе «код на 8-ку» не
    # находится при вводе «+7» и человек получает второй аккаунт (см. normalize_phone).
    body.phone = normalize_phone(body.phone)
    # Тестовый аккаунт модерации сторов (B9b-4): реальную SMS не шлём и OTP не создаём —
    # verify примет ТОЛЬКО фикс-код из env. Ответ обычный (dev_code не утекает).
    if _review_login_active(body.phone):
        return {"sent": True}
    _lock_otp_phone(session, body.phone)
    # Throttle: ≤3 кода в минуту на номер (анти-флуд: расходы на SMS + защита от забивания OtpCode).
    recent = session.exec(
        select(OtpCode).where(
            OtpCode.phone == body.phone,
            OtpCode.created_at > utcnow() - timedelta(seconds=60),
        )
    ).all()
    if len(recent) >= 3:
        raise herr(429, "Слишком часто. Подожди минуту и попробуй снова.", "Артыҡ йыш. Бер минут көт тә ҡабатла.")
    code = gen_otp()
    session.add(OtpCode(
        phone=body.phone, code=code,
        expires_at=utcnow() + timedelta(seconds=settings.otp_ttl_sec),
    ))
    session.commit()
    send_sms(body.phone, code)
    resp = {"sent": True}
    if settings.env == "dev":
        resp["dev_code"] = code  # в dev возвращаем код, чтобы тестировать без SMS
    return resp


@router.post("/auth/verify")
def verify(body: VerifyIn, session: Session = Depends(get_session),
           x_device_id: str = Header(default="", alias="X-Device-Id")):
    try:
        return _verify_sms_login(body, session, x_device_id)
    except Exception:
        session.rollback()
        raise


def _verify_sms_login(body: VerifyIn, session: Session, x_device_id: str):
    # Анти-фрод (B8-1): забаненное устройство → 403. Барьер от «нового номера на том же
    # телефоне»; заголовок клиентский, целевой обход сменой X-Device-Id возможен (Play Integrity — бэклог).
    guard_device_not_banned(session, x_device_id)
    body.phone = normalize_phone(body.phone)   # ищем и создаём человека по одному виду номера
    # Тестовый аккаунт модерации сторов (B9b-4): для review_phone работает ТОЛЬКО фикс-код
    # из env (даже случайно созданные OTP этого номера игнорируются). Ошибка — тот же текст,
    # что у обычного кода (не раскрываем существование режима). Код не логируем.
    if _review_login_active(body.phone):
        submitted_code = body.code or ""
        if (not settings.review_code.isascii() or not submitted_code.isascii()
                or not hmac.compare_digest(settings.review_code, submitted_code)):
            raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
        user = find_user_by_phone(session, body.phone, commit=False)
        if user:
            user = lock_refresh_user(session, user.id)
            # Фикс-код стора не подтверждает владение привилегированным аккаунтом.
            # Не меняем его роль или данные; проверка выполняется под блокировкой строки.
            if not user or user.role != UserRole.passenger:
                raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
        if not user:
            user = User(phone=body.phone, name=body.name or "Проверка стора",
                        is_reviewer=True)   # B1: ревьюер = L0, verified только через модерацию
        user.is_reviewer = True
        session.add(user)
        session.flush()
        # НЕ вызываем _maybe_promote_admin: ревьюер — всегда обычный пассажир без прав,
        # даже если этот номер случайно совпал со списком админов.
        return _complete_login(session, user, x_device_id, promote_admin=False, review_session=True)
    _lock_otp_phone(session, body.phone)
    live_otps = session.exec(
        select(OtpCode).where(OtpCode.phone == body.phone, OtpCode.expires_at > utcnow())
    ).all()
    # Потолок попыток НА НОМЕР, а не на код. Счётчик `attempts` живёт на строке кода, а сверяется
    # всегда самый свежий код — значит, запросив новый код, перебирающий обнулял себе счётчик и
    # получал ещё пять попыток. При лимите «3 кода в минуту» это 15 угадываний в минуту, то есть
    # промышленный перебор шестизначного кода (аудит 2026-08-08). Теперь неудачи складываются по
    # всем живым кодам номера. Порог намеренно щедрый: человек ошибается два-три раза, упереться
    # в него можно только специально.
    if sum(o.attempts for o in live_otps) >= MAX_OTP_ATTEMPTS_PER_PHONE:
        raise herr(429, "Слишком много попыток. Подожди немного и запроси новый код.",
                   "Артыҡ күп талап. Бер аҙ көт тә яңы код һора.")
    otp = max(live_otps, key=lambda o: o.id) if live_otps else None
    if not otp:
        raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
    if otp.attempts >= 5:                       # защита от перебора 6-значного кода
        raise herr(429, "Слишком много попыток. Запроси новый код.", "Артыҡ күп талап. Яңы код һора.")
    otp_id, verified_code = otp.id, otp.code
    submitted_code = body.code or ""
    # compare_digest со строками принимает только ASCII; другой ввод — обычная неверная попытка.
    # Счётчик меняет сама БД: параллельные запросы не перезаписывают прочитанное значение.
    if not submitted_code.isascii() or not hmac.compare_digest(verified_code, submitted_code):
        counted = session.execute(update(OtpCode).where(
            OtpCode.id == otp_id, OtpCode.phone == body.phone,
            OtpCode.code == verified_code, OtpCode.attempts < 5,
            OtpCode.expires_at > utcnow(),
        ).values(attempts=OtpCode.attempts + 1))
        session.commit()
        if counted.rowcount != 1:
            current = session.get(OtpCode, otp_id, populate_existing=True)
            if (current and current.code == verified_code and current.expires_at > utcnow()
                    and current.attempts >= 5):
                raise herr(429, "Слишком много попыток. Запроси новый код.", "Артыҡ күп талап. Яңы код һора.")
        raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
    # Keep the issuance timestamp for the existing SMS throttle after use.
    # Clear the secret and expire it in the same transaction as session issuance.
    consumed = session.execute(update(OtpCode).where(
        OtpCode.id == otp_id, OtpCode.phone == body.phone,
        OtpCode.code == verified_code, OtpCode.attempts < 5,
        OtpCode.expires_at > utcnow(),
    ).values(code="", expires_at=utcnow()))
    if consumed.rowcount != 1:
        raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
    user = find_user_by_phone(session, body.phone, commit=False)
    if user:
        user = lock_refresh_user(session, user.id)
    released_user_id = None
    # Номер мог перейти к ДРУГОМУ человеку: оператор забирает неиспользуемый номер и через
    # полгода-год продаёт (волна 139). Тогда аккаунт прежнего хозяина отвязываем от номера,
    # и дальше по коду заводится чистый новый — вошедший не получает чужую историю, переписку
    # и доверенные контакты. Данные прежнего владельца целы, доступ вернёт поддержка.
    if user and phone_looks_recycled(user, x_device_id):
        released_user_id = user.id
        release_phone(session, user, commit=False, notify=False)
        user = None
    if not user:
        # Имя при регистрации — то же публичное поле, что и в /me/update: проверяем так же,
        # иначе телефон в имени просто въезжает через вход вместо правки профиля.
        user = User(phone=body.phone, name=_guard_display_name(body.name) or "Пользователь")   # B1: verified только через модерацию
        try:
            with session.begin_nested():
                session.add(user)
                session.flush()
        except IntegrityError:
            # Одновременный вход уже создал человека с этим подтверждённым
            # номером. Продолжаем с ним; победителя определит consumption кода.
            user = find_user_by_phone(session, body.phone, commit=False)
            if user is None:
                raise
        session.refresh(user)
    return _complete_login(session, user, x_device_id, released_user_id=released_user_id)


# ==================== TELEGRAM-ВХОД (бот, код подтверждения) ====================
# Поток как у SMS, но 6-значный код шлёт Telegram-бот:
# 1) app: POST /auth/tg/start → request_id; app открывает t.me/<bot>?start=<request_id>
# 2) юзер жмёт Старт → Telegram шлёт /start <request_id> на вебхук
# 3) вебхук привязывает ПОДТВЕРЖДЁННЫЙ Telegram'ом from.id к request_id, генерит 6-значный
#    код и присылает его юзеру в чат бота
# 4) app: POST /auth/tg/verify {request_id, code} → сервер сверяет код → JWT
# Безопасность: request_id — случайный UUID (не угадать); код 6 цифр, живёт 5 мин,
# ≤5 попыток; telegram_id подтверждён Telegram'ом. За чужого войти нельзя.

TG_CODE_TTL_SEC = 300
TG_MAX_ATTEMPTS = 5


class TgStartOut(BaseModel):
    request_id: str


@router.post("/auth/tg/start", response_model=TgStartOut)
def tg_start(session: Session = Depends(get_session)):
    req = uuid.uuid4().hex
    session.add(TgAuth(
        request_id=req, status="waiting",
        expires_at=utcnow() + timedelta(seconds=TG_CODE_TTL_SEC),
    ))
    session.commit()
    return TgStartOut(request_id=req)


@router.post("/telegram/webhook")
async def telegram_webhook(request: Request, x_telegram_bot_api_secret_token: str = Header(default="")):
    """Telegram шлёт сюда апдейты. На /start <request_id> привязываем юзера и шлём код."""
    if settings.telegram_webhook_secret and not hmac.compare_digest(
        x_telegram_bot_api_secret_token or "", settings.telegram_webhook_secret
    ):
        raise HTTPException(403, "bad secret")
    # Битый JSON НЕ роняем в 500: иначе Telegram ретраит «ядовитый» апдейт по расписанию (шум/дубли).
    try:
        telegram_update = await request.json()
    except Exception:  # noqa: BLE001
        return {"ok": True}
    callback = telegram_update.get("callback_query") or {}
    if callback:
        return _handle_admin_callback(callback)
    msg = telegram_update.get("message") or {}
    text = msg.get("text") or ""
    frm = msg.get("from") or {}
    chat = msg.get("chat") or {}
    contact = msg.get("contact") or {}

    # Юзер поделился номером кнопкой request_contact. Принимаем ТОЛЬКО свой номер
    # (contact.user_id == отправитель) — не пересланный чужой контакт.
    if contact and frm.get("id"):
        tid = str(frm["id"])
        if str(contact.get("user_id")) == tid and contact.get("phone_number"):
            phone = _norm_phone(contact["phone_number"])
            with Session(engine) as s:
                u = s.exec(select(User).where(User.telegram_id == tid)).first()
                if u:
                    _set_user_phone(s, u, phone)               # юзер уже есть → сразу пишем
                else:
                    # ещё не верифицировался → запомним на сессии входа, применим при verify
                    row = s.exec(
                        select(TgAuth).where(TgAuth.telegram_id == tid).order_by(TgAuth.id.desc())
                    ).first()
                    if row:
                        row.shared_phone = phone
                        s.add(row)
                        s.commit()
            return {"method": "sendMessage", "chat_id": chat.get("id"),
                    "text": "Спасибо! Номер сохранён ✅ Вернись в приложение и введи код.",
                    "reply_markup": {"remove_keyboard": True}}
        return {"method": "sendMessage", "chat_id": chat.get("id"),
                "text": "Поделись своим номером кнопкой ниже 🙏"}

    reply = None
    reply_markup = None
    if text.startswith("/start") and frm.get("id"):
        parts = text.split(maxsplit=1)
        req = parts[1].strip() if len(parts) > 1 else ""
        with Session(engine) as s:
            row = s.exec(select(TgAuth).where(TgAuth.request_id == req)).first() if req else None
            if (row and row.status in ("waiting", "sent") and row.expires_at > utcnow()
                    and row.telegram_id in (None, str(frm["id"]))):
                code = gen_otp()
                claimed = s.execute(update(TgAuth).where(
                    TgAuth.id == row.id, TgAuth.status.in_(("waiting", "sent")),
                    TgAuth.expires_at > utcnow(),
                    or_(TgAuth.telegram_id.is_(None), TgAuth.telegram_id == str(frm["id"])),
                ).values(telegram_id=str(frm["id"]), username=frm.get("username", "") or "",
                         first_name=frm.get("first_name", "") or "", code=code, status="sent"))
                s.commit()
                reply = (
                    f"Твой код для входа в Юлдаш: {code}\n"
                    "Код живёт 5 минут.\n\n"
                    "Для безопасности нажми «📱 Поделиться номером» ниже — без номера "
                    "вход не завершится. Затем введи код в приложении."
                )
                reply_markup = {
                    "keyboard": [[{"text": "📱 Поделиться номером", "request_contact": True}]],
                    "resize_keyboard": True, "one_time_keyboard": True,
                }
                if claimed.rowcount != 1:
                    reply = "Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код."
                    reply_markup = None
            else:
                reply = "Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код."
            # Гигиена: чистим просроченные строки входа (TgAuth/OtpCode растут на каждую попытку).
            # Делаем здесь — частоту вебхука Telegram сам ограничивает (~30/с), клиентский спайк не грузим.
            now = utcnow()
            s.execute(delete(TgAuth).where(TgAuth.expires_at < now))
            # A used/expired code still records an SMS in the 60s throttle window.
            s.execute(delete(OtpCode).where(
                OtpCode.expires_at < now, OtpCode.created_at <= now - timedelta(seconds=60)))
            s.commit()
    if reply is not None:
        out = {"method": "sendMessage", "chat_id": chat.get("id"), "text": reply}
        if reply_markup is not None:
            out["reply_markup"] = reply_markup
        return out
    # Админ пишет боту текстом («Одобрить», «/Одобрить», «#1») — раньше бот молчал, и это путало.
    # Подсказываем, где реально одобрять (кнопка под откликом / Кабинет админа), вместо тишины.
    low = text.strip().lower()
    if _is_owner_telegram(frm) and (
        low.startswith(("/", "#")) or "одобр" in low or "принят" in low or "прими" in low
    ):
        return {"method": "sendMessage", "chat_id": chat.get("id"),
                "text": "Заявку нельзя одобрить текстом. Отклик водителя принимается так:\n"
                        "• кнопкой ✅ Принять прямо под сообщением «🚗 Отклик на заявку»;\n"
                        "• или в приложении: Профиль → Кабинет админа → «Отклики по заявке» → введи № заявки → «Принять за пользователя».\n"
                        "Кнопка появляется после того, как на заявку откликнется водитель."}
    return {"ok": True}


def _telegram_api(method: str, payload: dict) -> None:
    if not settings.telegram_bot_token:
        return
    try:
        import httpx
        httpx.post(f"https://api.telegram.org/bot{settings.telegram_bot_token}/{method}", json=payload, timeout=8)
    except Exception:
        pass


def _handle_admin_callback(callback: dict):
    """Inline-кнопки админа в Telegram. Сейчас поддерживает модерацию водителей."""
    cb_id = callback.get("id")
    frm = callback.get("from") or {}
    data = callback.get("data") or ""
    msg = callback.get("message") or {}
    chat = msg.get("chat") or {}
    chat_id = chat.get("id")
    message_id = msg.get("message_id")

    if not _is_owner_telegram(frm):
        if cb_id:
            _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": "Нет доступа", "show_alert": True})
        return {"ok": True}

    parts = data.split(":")
    if len(parts) != 3 or parts[0] not in ("drv", "ad", "pay", "resp") or parts[1] not in ("ok", "no"):
        if cb_id:
            _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": "Неизвестная команда"})
        return {"ok": True}

    try:
        user_id = int(parts[2])
    except ValueError:
        if cb_id:
            _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": "Некорректный ID"})
        return {"ok": True}

    approve = parts[1] == "ok"
    # След админского действия — и для этой двери тоже (аудит 2026-08-08, волна 150).
    #
    # Кнопки в Telegram делают ровно то же, что ручки админки: одобряют водителя (то есть
    # допускают чужого человека к пассажирам), публикуют рекламу, ПОДТВЕРЖДАЮТ ПЛАТЁЖ. Через
    # HTTP каждое из этих действий оставляло запись «кто и что сделал», через кнопку —
    # ни одной. Журнал при этом выглядел полным: он фиксировал один вход из двух, и по нему
    # нельзя было понять, что половина решений прошла мимо.
    #
    # Владелец у бота один (`_is_owner_telegram` выше), но в этом и смысл следа: он нужен
    # не чтобы ловить чужого, а чтобы через год можно было ответить, кто и когда решил.
    admin_action(user_id, f"telegram.{parts[0]}", decision=parts[1], target=user_id, via="telegram")
    # Решение по рекламе приходит и отсюда, и из админки — текст один на оба входа
    # (локальный импорт: роутеры друг друга на старте не тянут).
    from .ads import notify_ad_decision
    with Session(engine) as s:
        if parts[0] == "drv":
            target = s.get(User, user_id)
            if not target:
                text = f"Пользователь #{user_id} не найден"
            else:
                dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
                if not dp:
                    dp = DriverProfile(user_id=user_id)
                # Тот же вердикт, что и кнопкой в админке, — через общий хелпер: отказ
                # гасит подтверждение пола (его давали по этим правам).
                set_driver_docs_verdict(s, target, dp, approved=approve)
                s.commit()
                text = f"{'Одобрен' if approve else 'Отклонён'} водитель #{user_id}: {target.name or target.phone}"
        elif parts[0] == "ad":
            ad = s.get(Ad, user_id)
            if not ad:
                text = f"Объявление #{user_id} не найдено"
            elif approve:
                ad.status = "active"
                ad.reject_reason = ""
                ad.reviewed_at = utcnow()
                if ad.period_days > 0:
                    ad.starts_at = utcnow()
                    ad.ends_at = utcnow() + timedelta(days=ad.period_days)
                s.add(ad)
                s.commit()
                notify_ad_decision(s, ad, approved=True)
                text = f"Одобрена реклама #{ad.id}: «{ad.title}»"
            else:
                ad.status = "rejected"
                ad.reject_reason = "Отклонено администратором в Telegram"
                ad.reviewed_at = utcnow()
                s.add(ad)
                s.commit()
                notify_ad_decision(s, ad, approved=False)
                text = f"Отклонена реклама #{ad.id}: «{ad.title}»"
        elif parts[0] == "resp":
            resp = s.get(RequestResponse, user_id)   # user_id здесь = id отклика
            if not resp:
                text = f"Отклик #{user_id} не найден"
            elif resp.status == "accepted":
                text = f"Отклик #{resp.id} уже принят"
            elif not approve:
                resp.status = "declined"
                s.add(resp)
                s.commit()
                text = f"Отклонён отклик #{resp.id}"
            else:
                from .requests import accept_request_response   # локальный импорт — без цикла на старте
                try:
                    accept_request_response(s, resp)
                    text = f"Принят отклик #{resp.id} — поездка создана. Перезвони пассажиру и водителю."
                except HTTPException as e:
                    text = f"Не удалось принять отклик #{resp.id}: {e.detail}"
        else:
            payment = s.get(Payment, user_id)
            if not payment:
                text = f"Платёж #{user_id} не найден"
            elif approve:
                if payment.status == "succeeded":
                    text = f"Платёж #{payment.id} уже подтверждён"
                elif payment.provider_id:
                    # Карточный платёж (создан у провайдера) руками не активируем — его подтвердит
                    # вебхук после реального списания (иначе тап ✅ = начисление без денег).
                    text = f"Платёж #{payment.id} у провайдера — подтвердится сам после оплаты"
                else:
                    # ЕДИНЫЙ активатор из payments.py: знает ВСЕ назначения (boost/ad/partner_sub/
                    # courier_commission/…). Локальная копия здесь знала только boost/ad — Telegram-✅
                    # «подтверждал» подписку бизнеса и комиссию курьера, не применяя эффект.
                    from .payments import _activate_payment   # локальный импорт — без цикла на старте
                    _activate_payment(s, payment)
                    text = f"Подтверждена оплата #{payment.id}: {payment.purpose} {payment.amount_kop // 100} ₽"
            else:
                if payment.status == "pending":
                    payment.status = "canceled"
                    s.add(payment)
                    s.commit()
                text = f"Отклонена оплата #{payment.id}: {payment.purpose} {payment.amount_kop // 100} ₽"

    if cb_id:
        _telegram_api("answerCallbackQuery", {"callback_query_id": cb_id, "text": text})
    if chat_id and message_id:
        _telegram_api("editMessageReplyMarkup", {"chat_id": chat_id, "message_id": message_id, "reply_markup": {"inline_keyboard": []}})
        _telegram_api("sendMessage", {"chat_id": chat_id, "text": text})
    return {"ok": True}


class TgVerifyIn(BaseModel):
    request_id: str
    code: str


@router.post("/auth/tg/verify")
def tg_verify(body: TgVerifyIn, session: Session = Depends(get_session),
              x_device_id: str = Header(default="", alias="X-Device-Id")):
    try:
        return _verify_telegram_login(body, session, x_device_id)
    except Exception:
        session.rollback()
        raise


def _verify_telegram_login(body: TgVerifyIn, session: Session, x_device_id: str):
    # Анти-фрод (B8-1): забаненное устройство → 403 (обход бана через Telegram-вход закрыт).
    guard_device_not_banned(session, x_device_id)
    # Статусы различимы клиентом для разных сообщений: 409 ещё не получен, 410 истёк,
    # 429 много попыток, 400 неверный код.
    row = session.exec(select(TgAuth).where(TgAuth.request_id == body.request_id)).first()
    if not row or row.status != "sent" or not row.telegram_id or not row.code:
        raise herr(409, "Сначала получи код в Telegram", "Башта Telegram-да код ал")
    if row.expires_at < utcnow():
        raise herr(410, "Код истёк. Получи новый.", "Код ваҡыты үтте. Яңыһын ал.")
    if row.attempts >= TG_MAX_ATTEMPTS:
        raise herr(429, "Слишком много попыток. Получи новый код.", "Артыҡ күп талап. Яңы код ал.")
    request_row_id, verified_code, verified_telegram_id = row.id, row.code, row.telegram_id
    submitted_code = body.code.strip()
    if not submitted_code.isascii() or not hmac.compare_digest(submitted_code, verified_code):
        counted = session.execute(update(TgAuth).where(
            TgAuth.id == request_row_id, TgAuth.status == "sent",
            TgAuth.code == verified_code, TgAuth.telegram_id == verified_telegram_id,
            TgAuth.attempts < TG_MAX_ATTEMPTS, TgAuth.expires_at >= utcnow(),
        ).values(attempts=TgAuth.attempts + 1))
        session.commit()
        if counted.rowcount != 1:
            current = session.get(TgAuth, request_row_id, populate_existing=True)
            if not current or current.status != "sent" or not current.telegram_id or not current.code:
                raise herr(409, "Сначала получи код в Telegram", "Башта Telegram-да код ал")
            if current.expires_at < utcnow():
                raise herr(410, "Код истёк. Получи новый.", "Код ваҡыты үтте. Яңыһын ал.")
            if current.attempts >= TG_MAX_ATTEMPTS:
                raise herr(429, "Слишком много попыток. Получи новый код.", "Артыҡ күп талап. Яңы код ал.")
        raise herr(400, "Неверный код", "Код дөрөҫ түгел")
    verified_phone, verified_name = row.shared_phone, row.first_name or row.username
    consumed = session.execute(update(TgAuth).where(
        TgAuth.id == request_row_id, TgAuth.status == "sent",
        TgAuth.code == verified_code, TgAuth.telegram_id == verified_telegram_id,
        TgAuth.attempts < TG_MAX_ATTEMPTS, TgAuth.expires_at >= utcnow(),
    ).values(status="used"))
    if consumed.rowcount != 1:
        raise herr(409, "Сначала получи код в Telegram", "Башта Telegram-да код ал")
    user = session.exec(select(User).where(User.telegram_id == verified_telegram_id)).first()
    if user:
        user = lock_refresh_user(session, user.id)
    if not user and verified_phone:
        # Через дверь: номер из Telegram приходит в своём написании («79991234567»), а в базе
        # тот же человек мог быть заведён как «+79991234567». Прямое сравнение их не склеивало,
        # и Telegram-вход заводил ВТОРОЙ аккаунт тому же человеку.
        existing_by_phone = find_user_by_phone(session, verified_phone, commit=False)
        if existing_by_phone:
            existing_by_phone = lock_refresh_user(session, existing_by_phone.id)
        if existing_by_phone and not existing_by_phone.telegram_id:
            existing_by_phone.telegram_id = verified_telegram_id
            # B1: НЕ выставляем verified при входе — это только результат модерации документов.
            if not existing_by_phone.name:
                # Имя из профиля Telegram человек ставит себе сам — туда так же помещается
                # телефон. Отказать нельзя (сломает вход), поэтому помеченное просто не берём.
                existing_by_phone.name = _safe_display_name(verified_name) or "Telegram"
            session.add(existing_by_phone)
            session.flush()
            session.refresh(existing_by_phone)
            user = existing_by_phone
    if not user:
        user = User(
            phone=f"tg{verified_telegram_id}",   # плейсхолдер, пока юзер не поделился реальным номером
            name=_safe_display_name(verified_name) or "Telegram",
            telegram_id=verified_telegram_id,
            # B1: verified только через модерацию документов (не при входе)
        )
        try:
            with session.begin_nested():
                session.add(user)
                session.flush()
        except IntegrityError:
            user = session.exec(select(User).where(User.telegram_id == verified_telegram_id)).first()
            if user is None:
                raise
        session.refresh(user)
    # Реальный номер из бота (кнопка «Поделиться номером») — подставляем, если есть.
    if verified_phone:
        _set_user_phone(session, user, verified_phone, commit=False)
        session.refresh(user)
    # ⛔ Номер ОБЯЗАТЕЛЕН (безопасность / защита от мошенников). Без реального номера вход
    # не завершаем: код НЕ помечаем used (status='sent') → юзер делится номером в боте и
    # повторяет ввод того же кода. Клиент по 403 phone_required показывает экран-подсказку.
    if is_placeholder_phone(user.phone):
        raise HTTPException(403, "phone_required")
    return _complete_login(session, user, x_device_id)


class RefreshIn(BaseModel):
    refresh_token: str
    rotation_id: str | None = Field(default=None, strict=True, pattern=r'^[0-9a-f]{64}$')


@router.post("/auth/refresh")
def refresh(body: RefreshIn, session: Session = Depends(get_session),
            x_device_id: str = Header(default="", alias="X-Device-Id")):
    """Обновить пару токенов по refresh-токену (ротация: старый refresh гасится).

    Бан устройства проверяем и здесь (аудит 2026-08-08, волна 204). Гейт стоял на трёх
    дверях входа — запрос кода, проверка кода, Telegram, — а приложение продлевает вход
    само и бесконечно. То есть забаненный работал дальше как ни в чём не бывало, и бан
    выглядел выполненным: в админке он есть, новый вход режется, а человек на линии.

    Заголовок шлёт клиент, значит его можно и не слать, — поэтому вторая половина проверки
    живёт в `rotate_refresh` и смотрит на устройство, которое сервер запомнил сам.
    """
    if not body.refresh_token.strip():
        raise herr(400, "Не получилось продлить вход. Войди заново.",
                   "Инеүҙе оҙайтып булманы. Яңынан ин.")
    guard_device_not_banned(session, x_device_id)
    return rotate_refresh(session, body.refresh_token.strip(), rotation_id=body.rotation_id)


# VK / WhatsApp вход — ОТКЛЮЧЕНО до безопасной реализации.
# Прежние версии выдавали токен по непроверенному vk_id/телефону (whatsapp-callback —
# угон аккаунта: любой с чужим номером получал токен). Включим, когда будет:
#   VK  — серверный OAuth code-exchange (/auth/vk/callback) с проверкой на стороне VK;
#   WA  — WhatsApp Business API с подтверждением номера.
@router.post("/auth/vk-callback")
def vk_callback():
    raise herr(501, "Вход через VK пока не подключён. Войди по номеру телефона.",
           "VK аша инеү әлегә тоташтырылмаған. Телефон номеры буйынса ин.")


@router.post("/auth/whatsapp-callback")
def whatsapp_callback():
    raise herr(501, "Вход через WhatsApp пока не подключён. Войди по номеру телефона.",
           "WhatsApp аша инеү әлегә тоташтырылмаған. Телефон номеры буйынса ин.")


@router.get("/me")
def me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    avg, cnt = user_rating(session, user.id)
    return {**user.model_dump(), "rating": round(avg, 1) if cnt > 0 else None, "rating_count": cnt}


class MeUpdateIn(BaseModel):
    name: Optional[str] = Field(None, max_length=120)
    avatar_url: Optional[str] = Field(None, max_length=500)
    city: Optional[str] = Field(None, max_length=80)
    language: Optional[str] = Field(None, max_length=2)   # "ru" | "ba" — двуязычные push идут на языке юзера
    # Пол — по желанию: "" (не указывать/снять) | female | male. Нужен для отметки
    # «только женщины» на попутке: она проверяется у ОБЕИХ сторон (аудит 2026-08-08).
    gender: Optional[str] = Field(None, max_length=8)


_SOS_MAP_MARKER = "https://yandex.ru/maps/?pt="


def _location_summary(session: Session, user_id: int) -> dict[str, int | bool]:
    """Какие точные точки реально лежат в БД для этого человека.

    Ride выбирается одним запросом по уникальным строкам: если человек одновременно водитель
    и пассажир одной поездки, её pickup-точка считается один раз. У InstantOrder каждая
    заполненная пара from/to — отдельная точка маршрута.
    """
    passenger_rides = select(Booking.ride_id).where(Booking.passenger_id == user_id)
    rides = session.exec(
        select(Ride).where(or_(Ride.driver_id == user_id, Ride.id.in_(passenger_rides)))
    ).all()
    route_points = sum(
        1 for ride in rides
        if ride.pickup_lat is not None and ride.pickup_lng is not None
    )

    orders = session.exec(
        select(InstantOrder).where(or_(
            InstantOrder.passenger_id == user_id,
            InstantOrder.driver_id == user_id,
        ))
    ).all()

    def real_point(lat: float | None, lng: float | None) -> bool:
        # У старых/незаполненных InstantOrder координаты имеют техническое значение 0,0.
        return lat is not None and lng is not None and (lat != 0.0 or lng != 0.0)

    for order in orders:
        route_points += int(real_point(order.from_lat, order.from_lng))
        route_points += int(real_point(order.to_lat, order.to_lng))

    sos_filter = (SosEvent.user_id == user_id, SosEvent.note.contains(_SOS_MAP_MARKER))
    sos_locations = int(session.exec(
        select(func.count()).select_from(SosEvent).where(*sos_filter)
    ).one())
    open_sos_locations = int(session.exec(
        select(func.count()).select_from(SosEvent).where(
            *sos_filter, SosEvent.status == "open",
        )
    ).one())
    return {
        "location_stored": bool(route_points or sos_locations),
        "live_location_history_stored": False,
        "route_location_points": route_points,
        "sos_location_events": sos_locations,
        "open_sos_location_events": open_sos_locations,
    }


@router.get("/me/data")
def my_data(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Что Юлдаш знает о человеке — живыми числами и со сроками.

    Зачем. «Удалить мои данные» люди просят не потому, что данные им мешают, а потому что
    не знают, что именно у нас лежит и надолго ли. Общие слова в оферте на этот страх
    не отвечают. Числа и сроки отвечают: переписка уходит сама через месяц, поездки —
    через полгода, а сохранённые точки поездок и SOS показываем отдельно от live-трека.

    Сроки берём из ретеншена (`cleanup.py`), а не пишем в клиенте: иначе приложение
    начнёт обещать одно, а чистилка делать другое.

    Документы водителя стоят отдельно: это единственное, что не чистится никогда,
    и единственное, что можно удалить точечно (POST /me/driver-docs/delete).
    """
    from .. import cleanup

    uid = user.id
    def count(model, *where):
        return int(session.exec(select(func.count()).select_from(model).where(*where)).one())

    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first()
    docs = sum(1 for u in ((dp.license_url if dp else ""), (dp.car_photo_url if dp else "")) if u)
    voices = count(Message, Message.sender_id == uid, Message.voice_url.is_not(None))
    return {
        "rides": count(Ride, Ride.driver_id == uid),
        "rides_days": cleanup.TRIP_DAYS,
        "bookings": count(Booking, Booking.passenger_id == uid),
        "messages": count(Message, Message.sender_id == uid),
        "messages_days": cleanup.MSG_DAYS,
        "voices": voices,
        "voices_days": cleanup.MEDIA_DAYS,
        "notifications": count(Notification, Notification.user_id == uid),
        "notifications_days": cleanup.NOTIF_DAYS,
        "driver_docs": docs,
        "driver_docs_removable": bool(dp and docs and not dp.online and dp.docs_status != "pending"),
        **_location_summary(session, uid),
        "route_location_points_days": cleanup.TRIP_DAYS,
        "sos_location_days_from_signal": cleanup.SOS_DAYS,
        "card_stored": False,
    }


@router.get("/me/export")
def export_my_data(
    lang: str = Query("ru"),
    user: User = Depends(current_user),
    session: Session = Depends(get_session),
):
    """«Скачать мои данные» — читаемый человеком файл, а не выгрузка для программиста.

    Зачем именно текстом. Закон требует по запросу выдать человеку его сведения; формат
    не назван. JSON выдал бы «сведения» формально: получатель — водитель или пенсионерка,
    и файл со скобками ответом для них не является. Обычный текст открывается в любом
    телефоне и читается вслух.

    Чужого в файле нет. Сообщения — только свои отправленные: в переписке участвует
    второй человек, и его слова не наши, чтобы их отдавать. Телефон попутчиков,
    координаты и чужие оценки в выгрузку не идут по той же причине.

    Объём ограничен: у активного водителя тысячи строк превратили бы файл в нечитаемый.
    Обрезали — говорим об этом прямо в файле, а не молчим.
    """
    from .. import cleanup
    from ..services import pick_lang

    lim = 300
    uid = user.id
    L = lambda ru, ba: pick_lang(lang, ru, ba)   # noqa: E731 — короткий алиас читается лучше в тексте

    def dt(v) -> str:
        return v.strftime("%d.%m.%Y %H:%M") if v else "—"

    out: list[str] = []
    out.append(L("МОИ ДАННЫЕ В ЮЛДАШЕ", "ЮЛДАШТА МИНЕҢ МӘҒЛҮМӘТТӘРЕМ"))
    out.append(L("Файл собран", "Файл йыйылған") + ": " + dt(utcnow()))
    out.append("")

    out.append(L("ПРОФИЛЬ", "ПРОФИЛЬ"))
    out.append(L("Имя", "Исем") + ": " + (user.name or "—"))
    out.append(L("Телефон", "Телефон") + ": " + (user.phone or "—"))
    out.append(L("Город", "Ҡала") + ": " + (user.city or "—"))
    out.append(L("Язык приложения", "Ҡушымта теле") + ": " + (user.language or "ru"))
    out.append(L("Профиль подтверждён", "Профиль раҫланған") + ": " + (L("да", "эйе") if user.verified else L("нет", "юҡ")))
    out.append("")

    dp = session.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first()
    if dp:
        out.append(L("ВОДИТЕЛЬ", "ШОФЕР"))
        car = " ".join(x for x in (dp.car_make, dp.car_model, dp.car_color) if x)
        out.append(L("Машина", "Машина") + ": " + (car or "—"))
        out.append(L("Госномер", "Дәүләт һаны") + ": " + (dp.car_plate or "—"))
        out.append(L("Документы", "Документтар") + ": " + {
            "verified": L("проверены", "тикшерелгән"),
            "pending": L("на проверке", "тикшереүҙә"),
            "rejected": L("отклонены", "кире ҡағылған"),
        }.get(dp.docs_status, L("не загружены", "һалынмаған")))
        out.append(L("Поездок выполнено", "Үтәлгән сәфәр") + ": " + str(dp.trips_count or 0))
        out.append("")

    rides = session.exec(
        select(Ride).where(Ride.driver_id == uid).order_by(Ride.depart_at.desc()).limit(lim)
    ).all()
    if rides:
        out.append(L("МОИ ПОЕЗДКИ ЗА РУЛЁМ", "РУЛЬ АРТЫНДАҒЫ СӘФӘРҘӘРЕМ"))
        for r in rides:
            out.append(f"{dt(r.depart_at)}  {r.from_city} → {r.to_city}  {int(r.price or 0)} ₽")
        out.append("")

    books = session.exec(
        select(Booking).where(Booking.passenger_id == uid).order_by(Booking.id.desc()).limit(lim)
    ).all()
    if books:
        out.append(L("МОИ ПОЕЗДКИ ПАССАЖИРОМ", "ЮЛСЫ БУЛАРАҠ СӘФӘРҘӘРЕМ"))
        for b in books:
            r = session.get(Ride, b.ride_id)
            route = f"{r.from_city} → {r.to_city}" if r else "—"
            when = dt(r.depart_at) if r else "—"
            out.append(f"{when}  {route}  {b.seats} " + L("мест", "урын") + f"  {int(b.price or 0)} ₽")
        out.append("")

    msgs = session.exec(
        select(Message).where(Message.sender_id == uid).order_by(Message.id.desc()).limit(lim)
    ).all()
    if msgs:
        out.append(L("МОИ СООБЩЕНИЯ", "МИНЕҢ ХӘБӘРҘӘРЕМ"))
        out.append(L("Только отправленные мной — чужие слова не наши, чтобы их отдавать.",
                     "Тик үҙем ебәргәндәр — башҡа кешенең һүҙҙәре беҙҙеке түгел."))
        for m in msgs:
            body = m.text or (L("[голосовое]", "[тауышлы хәбәр]") if m.voice_url else "")
            if body:
                out.append(f"{dt(m.created_at)}  {body}")
        out.append("")

    rates = session.exec(
        select(Rating).where(Rating.rater_id == uid).order_by(Rating.id.desc()).limit(lim)
    ).all()
    if rates:
        out.append(L("ОЦЕНКИ, КОТОРЫЕ Я СТАВИЛ", "МИН ҠУЙҒАН БАҺАЛАР"))
        for g in rates:
            out.append(f"{'★' * int(g.stars or 0)}  {g.text or ''}".rstrip())
        out.append("")

    locations = _location_summary(session, uid)
    out.append(L("ГЕОЛОКАЦИЯ", "ГЕОЛОКАЦИЯ"))
    out.append(L(
        "Историю точной геолокации в реальном времени отдельным архивом не храним.",
        "Реаль ваҡытта теүәл геолокация тарихын айырым архив итеп һаҡламайбыҙ.",
    ))
    out.append(L(
        f"Точки поездок: {locations['route_location_points']}. Они видны участникам поездки. "
        f"Проверяем для удаления после {cleanup.TRIP_DAYS} дней; связанные записи могут "
        "продлить срок.",
        f"Сәфәр нөктәләре: {locations['route_location_points']}. Улар сәфәрҙә ҡатнашыусыларға "
        f"күренә. {cleanup.TRIP_DAYS} көндән һуң юйыу өсөн тикшерәбеҙ; бәйле яҙмалар "
        "һаҡлау ваҡытын оҙайта ала.",
    ))
    out.append(L(
        f"Точки SOS: {locations['sos_location_events']}.",
        f"SOS нөктәләре: {locations['sos_location_events']}.",
    ))
    out.append(L(
        f"Открытые SOS с точкой: {locations['open_sos_location_events']}. "
        "Они хранятся до обработки администратором.",
        f"Нөктәле асыҡ SOS: {locations['open_sos_location_events']}. "
        "Улар администратор эшкәрткәнгә тиклем һаҡлана.",
    ))
    out.append(L(
        f"Закрытые SOS с точкой удаляются через {cleanup.SOS_DAYS} дней от даты сигнала.",
        f"Нөктәле ябыҡ SOS сигнал көнөнән {cleanup.SOS_DAYS} көн үткәс таҙартыла.",
    ))
    out.append("")

    out.append(L("ЧЕГО В ФАЙЛЕ НЕТ", "ФАЙЛДА НИМӘ ЮҠ"))
    out.append(L("Данных банковской карты — деньги идут мимо нас.",
                 "Банк картаһы мәғлүмәттәре — аҡса беҙҙән үтмәй."))
    out.append(L(f"Показано не больше {lim} записей в каждом разделе.",
                 f"Һәр бүлектә {lim} яҙмананан артыҡ түгел күрһәтелгән."))
    return {"filename": "yuldash-my-data.txt", "text": "\n".join(out)}


@router.post("/me/update")
def update_me(body: MeUpdateIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Редактирование профиля: имя, аватар, родной город и/или язык. Телефон не меняем. Поля опциональны.
    city: свободная строка (name_ru из справочника Settlement); пустая строка сбрасывает город.
    language: клиент шлёт при переключении RU⇄BA — оживляет User.language (порт из notification-fixes)."""
    if body.name is not None:
        n = _guard_display_name(body.name, user.id)
        if n:
            user.name = n
    if body.avatar_url is not None:
        user.avatar_url = _clean_avatar_url(body.avatar_url)
    if body.city is not None:
        user.city = body.city.strip()[:80]
    if body.language is not None:
        lang = body.language.strip().lower()
        if lang in ("ru", "ba"):        # только поддерживаемые языки; мусор молча игнорируем
            user.language = lang
    if body.gender is not None:
        g = body.gender.strip().lower()
        # Мусор молча игнорируем, как и с языком: профиль сохранять надо, а не падать.
        # Пустая строка — законное значение: «не указывать» / снять раньше указанное.
        # Пишем через общий хелпер: он гасит подтверждение пола. Прямое присваивание тут
        # было дырой — подтверждённый мужчина менял пол на «женщина» и забирал бейдж
        # «женщина за рулём» вместе с женскими заказами (см. services.set_user_gender).
        if g in GENDERS:
            set_user_gender(session, user, g)
    session.add(user)
    session.commit()
    session.refresh(user)
    return {"ok": True, "name": user.name, "avatar_url": user.avatar_url, "city": user.city,
            "language": user.language, "gender": user.gender or ""}


@router.post("/me/delete")
def delete_me(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Необратимое удаление аккаунта и ВСЕХ персональных данных пользователя (152-ФЗ,
    право на удаление). Каскад по всем таблицам — в app/account.py. После — токен 401.

    Сначала — честные гейты (409 с объяснением): нельзя уйти с неоплаченной комиссией,
    посреди поездки или с чужой посылкой в руках (см. guard_can_delete)."""
    guard_can_delete(session, user)
    delete_user_account(session, user)
    return {"ok": True}


@router.post("/auth/logout")
def logout(user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Выход со всех устройств: гасим access, все refresh-токены И приём уведомлений.

    Про уведомления (аудит 2026-08-08, волна 147). Раньше выход гасил ключи входа, но запись
    устройства не трогал: сервер продолжал считать трубку принадлежащей этому человеку и слал
    туда его уведомления. Отвязка жила в отдельной ручке, которую клиент должен позвать
    по доброй воле и при живой сети — то есть когда телефон отобрали, она не сработает.

    Деревенский сценарий проще: общий телефон, отец вышел, зашёл сын. Сервер по-прежнему считал
    трубку отцовской и слал его уведомления на экран, который теперь смотрит сын. А кнопка
    называется «Выйти со всех устройств» — значит должна выходить и здесь.
    """
    user = lock_refresh_user(session, user.id)
    if user is None:
        raise HTTPException(401, "Unauthorized")
    user.tokens_valid_from = utcnow()
    session.add(user)
    revoke_all_refresh(session, user.id, commit=False)
    for строка in session.exec(select(DeviceToken).where(DeviceToken.user_id == user.id)).all():
        session.delete(строка)
    for строка in session.exec(
        select(WebPushSubscription).where(WebPushSubscription.user_id == user.id)
    ).all():
        session.delete(строка)
    session.commit()
    return {"ok": True}


class PushTokenIn(BaseModel):
    token: str


def _guard_push_token_owner(existing: DeviceToken, user_id: int, device_id: str) -> None:
    # Смена аккаунта требует совпадающей отметки устройства. Отсутствие отметки
    # не доказывает владение: старый клиент может обновить СВОЙ токен, а для смены
    # владельца сначала должен выйти (logout/unregister освобождает запись).
    different_device = bool(existing.device_id and device_id and existing.device_id != device_id)
    unproven_transfer = existing.user_id != user_id and not (
        existing.device_id and device_id and existing.device_id == device_id
    )
    if different_device or unproven_transfer:
        log.warning("[PUSH] попытка забрать чужую запись устройства: user_id=%s, владелец=%s",
                    user_id, existing.user_id)
        raise herr(409,
                   "Это устройство привязано к другому аккаунту. Выйди из него на этом "
                   "телефоне и войди заново.",
                   "Был ҡоролма башҡа иҫәпкә бәйләнгән. Ошо телефондан унан сыҡ та яңынан ин.")


@router.post("/push/register")
def push_register(body: PushTokenIn, user: User = Depends(current_user),
                  session: Session = Depends(get_session),
                  x_device_id: str = Header(default="", alias="X-Device-Id")):
    """Регистрация/перепривязка FCM-токена устройства к текущему пользователю."""
    if not body.token.strip():
        raise herr(400, "Не получилось подключить уведомления. Попробуй позже.",
           "Хәбәрҙәрҙе тоташтырып булманы. Һуңыраҡ ҡабатла.")
    did = normalize_device_id(x_device_id)
    existing = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
    if existing:
        _guard_push_token_owner(existing, user.id, did)
        existing.user_id = user.id
        if did:
            existing.device_id = did
        session.add(existing)
        session.commit()
        return {"ok": True}
    # Нового токена ещё нет — вставляем. Клиент шлёт токен из 2 мест на старте (сохранённый + свежий FCM),
    # оба запроса могут попасть на разные воркеры и одновременно пройти select-пусто → гонка на unique(token).
    try:
        session.add(DeviceToken(user_id=user.id, token=body.token, device_id=did))
        session.commit()
    except IntegrityError:
        # Параллельный запрос успел вставить тот же токен между select и commit → перепривязываем к текущему юзеру.
        session.rollback()
        row = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
        if row:
            _guard_push_token_owner(row, user.id, did)
            row.user_id = user.id
            if did:
                row.device_id = did
            session.add(row)
            session.commit()
    return {"ok": True}


class WebPushKeysIn(BaseModel):
    """Ключи подписки браузера. Их выдаёт сам браузер, клиент только пересылает."""
    p256dh: str = Field("", max_length=200)
    auth: str = Field("", max_length=100)


class WebPushSubIn(BaseModel):
    """Подписка браузера на Web Push (стандарт RFC 8291).

    `endpoint` — адрес пуш-сервиса браузера (Google/Mozilla/Apple), по нему и уходит
    сообщение. `keys` — то, чем оно шифруется: без них отправить нельзя ничего.
    """
    endpoint: str = Field(..., max_length=1000)
    keys: WebPushKeysIn = Field(default_factory=WebPushKeysIn)
    # aes128gcm у современных браузеров, aesgcm у старых. Пусто → современный.
    content_encoding: str = Field("aes128gcm", max_length=20)


@router.post("/push/web/subscribe")
def push_web_subscribe(body: WebPushSubIn, user: User = Depends(current_user),
                       session: Session = Depends(get_session),
                       x_device_id: str = Header(default="", alias="X-Device-Id")):
    """Подписка браузера на уведомления.

    Зачем отдельно от `/push/register`. Тот принимает FCM-токен приложения — одну строку,
    которой достаточно для отправки. Браузер устроен иначе: сообщение шифруется ключами
    самой подписки, и хранить их надо рядом с адресом.

    Пара к `/push/unregister`: там отвязка FCM-токена, здесь — подписки браузера.

    Перепривязка к текущему человеку разрешена и нужна: на общем телефоне отец вышел,
    зашёл сын — уведомления должны идти тому, кто сейчас в аккаунте. Подменить чужую
    подписку «зная строку» тут нельзя так же, как и у FCM: браузер выдаёт endpoint только
    своему сайту и своему устройству, а перед перепривязкой мы всё равно требуем вход.

    Идемпотентно: повторная подписка тем же браузером обновляет запись, а не плодит новую.
    """
    endpoint = (body.endpoint or "").strip()
    p256dh = (body.keys.p256dh or "").strip()
    auth_key = (body.keys.auth or "").strip()
    # Без ключей подписка бесполезна: зашифровать сообщение нечем, и каждая отправка
    # по ней будет молча падать. Честнее отказать сразу.
    if not endpoint or not p256dh or not auth_key:
        raise herr(400, "Не получилось подключить уведомления. Попробуй позже.",
                   "Хәбәрҙәрҙе тоташтырып булманы. Һуңыраҡ ҡабатла.")
    # Адрес пуш-сервиса — это всегда https. Всё остальное принимать незачем: своим
    # запросом человек ничего не добьётся, а нам чинить потом «почему не приходит».
    if not endpoint.startswith("https://"):
        raise herr(400, "Не получилось подключить уведомления. Попробуй позже.",
                   "Хәбәрҙәрҙе тоташтырып булманы. Һуңыраҡ ҡабатла.")

    did = normalize_device_id(x_device_id)
    enc = (body.content_encoding or "aes128gcm").strip() or "aes128gcm"
    row = session.exec(
        select(WebPushSubscription).where(WebPushSubscription.endpoint == endpoint)
    ).first()
    if row:
        row.user_id = user.id
        row.p256dh, row.auth, row.content_encoding = p256dh, auth_key, enc
        if did:
            row.device_id = did
        session.add(row)
        session.commit()
        return {"ok": True}
    try:
        session.add(WebPushSubscription(
            user_id=user.id, endpoint=endpoint, p256dh=p256dh, auth=auth_key,
            content_encoding=enc, device_id=did,
        ))
        session.commit()
    except IntegrityError:
        # Тот же браузер успел подписаться параллельно (две вкладки) — перепривязываем.
        session.rollback()
        row = session.exec(
            select(WebPushSubscription).where(WebPushSubscription.endpoint == endpoint)
        ).first()
        if row:
            row.user_id = user.id
            row.p256dh, row.auth, row.content_encoding = p256dh, auth_key, enc
            if did:
                row.device_id = did
            session.add(row)
            session.commit()
    return {"ok": True}


@router.post("/push/web/unsubscribe")
def push_web_unsubscribe(body: WebPushSubIn, user: User = Depends(current_user),
                         session: Session = Depends(get_session)):
    """Отписка браузера при выходе из аккаунта.

    Та же приватность, что у FCM-токена: на общем телефоне следующий вошедший не должен
    получать чужие уведомления — брони, чат, сигналы SOS. Только СВОЮ подписку.
    Идемпотентно: нечего удалять — отвечаем «хорошо».
    """
    endpoint = (body.endpoint or "").strip()
    if not endpoint:
        return {"ok": True}
    row = session.exec(select(WebPushSubscription).where(
        WebPushSubscription.endpoint == endpoint,
        WebPushSubscription.user_id == user.id,
    )).first()
    if row:
        session.delete(row)
        session.commit()
    return {"ok": True}


@router.post("/push/unregister")
def push_unregister(body: PushTokenIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отвязка FCM-токена устройства при выходе из аккаунта. Приватность на общем телефоне:
    без этой ручки вышедший пользователь продолжал получать чужие пуши (брони/чат/SOS) —
    клиент удалял токен только локально. Только СВОЙ токен (чужой не отвяжешь). Идемпотентно."""
    token = body.token.strip()
    if not token:
        raise herr(400, "Не получилось подключить уведомления. Попробуй позже.",
           "Хәбәрҙәрҙе тоташтырып булманы. Һуңыраҡ ҡабатла.")
    row = session.exec(select(DeviceToken).where(
        DeviceToken.token == token, DeviceToken.user_id == user.id,
    )).first()
    if row:
        session.delete(row)
        session.commit()
    return {"ok": True}
