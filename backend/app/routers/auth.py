"""Авторизация: телефон+OTP, Telegram-вход (бот+код), VK/WhatsApp-заглушки,
профиль `/me`, регистрация push-токена."""
from datetime import timedelta
from typing import Optional
import hmac
import uuid

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import delete
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from ..account import delete_user_account, guard_can_delete
from ..antifraud import (MESSAGE_FLAG_CONTACT, guard_device_not_banned, moderate_open_text,
                         phone_looks_recycled, release_phone, remember_login_device)
from ..config import _phone_key, settings
from ..db import engine, get_session
from ..errors import herr
from ..models import Ad, DeviceToken, DriverProfile, OtpCode, Payment, RequestResponse, TgAuth, User, UserRole
from ..security import (
    current_user, gen_otp, is_placeholder_phone, issue_tokens, normalize_phone,
    revoke_all_refresh, rotate_refresh,
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


def _maybe_promote_admin(session: Session, user: User) -> None:
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
    if (by_tg or by_phone) and user.role != UserRole.admin:
        user.role = UserRole.admin
        session.add(user)


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


def _set_user_phone(session: Session, user: User, phone: str) -> None:
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
    clash = find_user_by_phone(session, phone)
    if clash is not None and clash.id != user.id:
        return
    user.phone = phone
    session.add(user)
    session.commit()


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
    # Анти-фрод (B8-1): забаненное устройство → 403. Барьер от «нового номера на том же
    # телефоне»; заголовок клиентский, целевой обход сменой X-Device-Id возможен (Play Integrity — бэклог).
    guard_device_not_banned(session, x_device_id)
    body.phone = normalize_phone(body.phone)   # ищем и создаём человека по одному виду номера
    # Тестовый аккаунт модерации сторов (B9b-4): для review_phone работает ТОЛЬКО фикс-код
    # из env (даже случайно созданные OTP этого номера игнорируются). Ошибка — тот же текст,
    # что у обычного кода (не раскрываем существование режима). Код не логируем.
    if _review_login_active(body.phone):
        if not hmac.compare_digest(settings.review_code, body.code or ""):
            raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
        user = find_user_by_phone(session, body.phone)
        if not user:
            user = User(phone=body.phone, name=body.name or "Проверка стора",
                        is_reviewer=True)   # B1: ревьюер = L0, verified только через модерацию
        user.is_reviewer = True
        session.add(user)
        session.commit()
        session.refresh(user)
        # НЕ вызываем _maybe_promote_admin: ревьюер — всегда обычный пассажир без прав,
        # даже если этот номер случайно совпал со списком админов.
        remember_login_device(session, user, x_device_id)
        record_login_consents(session, user.id)   # 152-ФЗ: оферта/политика/18+ с датой
        tokens = issue_tokens(session, user.id)
        session.refresh(user)
        return {**tokens, "user": user}
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
    # constant-time сравнение — не даём измерить код по времени ответа (перебор и так лимитирован 5 попытками).
    if not hmac.compare_digest(otp.code, body.code or ""):
        otp.attempts += 1
        session.add(otp)
        session.commit()
        raise herr(400, "Неверный или просроченный код", "Код дөрөҫ түгел йәки ваҡыты үткән")
    # Код одноразовый: гасим сразу после успеха, иначе перехваченный код реюзабелен все 5 минут TTL.
    session.delete(otp)
    session.commit()
    user = find_user_by_phone(session, body.phone)
    # Номер мог перейти к ДРУГОМУ человеку: оператор забирает неиспользуемый номер и через
    # полгода-год продаёт (волна 139). Тогда аккаунт прежнего хозяина отвязываем от номера,
    # и дальше по коду заводится чистый новый — вошедший не получает чужую историю, переписку
    # и доверенные контакты. Данные прежнего владельца целы, доступ вернёт поддержка.
    if user and phone_looks_recycled(user, x_device_id):
        release_phone(session, user)
        user = None
    if not user:
        # Имя при регистрации — то же публичное поле, что и в /me/update: проверяем так же,
        # иначе телефон в имени просто въезжает через вход вместо правки профиля.
        user = User(phone=body.phone, name=_guard_display_name(body.name) or "Пользователь")   # B1: verified только через модерацию
        session.add(user)
        session.commit()
        session.refresh(user)
    _maybe_promote_admin(session, user)   # автоадмин по телефону (SMS-вход)
    # Анти-фрод (B8-1/2): фиксируем устройство; вход с нового → push+SMS-сигнал (не блокируем).
    remember_login_device(session, user, x_device_id)
    record_login_consents(session, user.id)   # 152-ФЗ: оферта/политика/18+ с датой
    tokens = issue_tokens(session, user.id)   # commit внутри → user протухает
    session.refresh(user)                     # перечитываем, чтобы сериализовать в ответ
    return {**tokens, "user": user}


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
        update = await request.json()
    except Exception:  # noqa: BLE001
        return {"ok": True}
    callback = update.get("callback_query") or {}
    if callback:
        return _handle_admin_callback(callback)
    msg = update.get("message") or {}
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
            if row and row.status in ("waiting", "sent") and row.expires_at > utcnow():
                code = gen_otp()
                row.telegram_id = str(frm["id"])
                row.username = frm.get("username", "") or ""
                row.first_name = frm.get("first_name", "") or ""
                row.code = code
                row.status = "sent"
                s.add(row)
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
            else:
                reply = "Открой приложение Юлдаш и нажми «Вход через Telegram» — я пришлю код."
            # Гигиена: чистим просроченные строки входа (TgAuth/OtpCode растут на каждую попытку).
            # Делаем здесь — частоту вебхука Telegram сам ограничивает (~30/с), клиентский спайк не грузим.
            now = utcnow()
            s.execute(delete(TgAuth).where(TgAuth.expires_at < now))
            s.execute(delete(OtpCode).where(OtpCode.expires_at < now))
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
    if not hmac.compare_digest(body.code.strip(), row.code):   # constant-time (перебор лимитирован TG_MAX_ATTEMPTS)
        row.attempts += 1
        session.add(row)
        session.commit()
        raise herr(400, "Неверный код", "Код дөрөҫ түгел")
    user = session.exec(select(User).where(User.telegram_id == row.telegram_id)).first()
    if not user and row.shared_phone:
        # Через дверь: номер из Telegram приходит в своём написании («79991234567»), а в базе
        # тот же человек мог быть заведён как «+79991234567». Прямое сравнение их не склеивало,
        # и Telegram-вход заводил ВТОРОЙ аккаунт тому же человеку.
        existing_by_phone = find_user_by_phone(session, row.shared_phone)
        if existing_by_phone and not existing_by_phone.telegram_id:
            existing_by_phone.telegram_id = row.telegram_id
            # B1: НЕ выставляем verified при входе — это только результат модерации документов.
            if not existing_by_phone.name:
                # Имя из профиля Telegram человек ставит себе сам — туда так же помещается
                # телефон. Отказать нельзя (сломает вход), поэтому помеченное просто не берём.
                existing_by_phone.name = _safe_display_name(row.first_name or row.username) or "Telegram"
            session.add(existing_by_phone)
            session.commit()
            session.refresh(existing_by_phone)
            user = existing_by_phone
    if not user:
        user = User(
            phone=f"tg{row.telegram_id}",   # плейсхолдер, пока юзер не поделился реальным номером
            name=_safe_display_name(row.first_name or row.username) or "Telegram",
            telegram_id=row.telegram_id,
            # B1: verified только через модерацию документов (не при входе)
        )
        session.add(user)
        session.commit()
        session.refresh(user)
    # Реальный номер из бота (кнопка «Поделиться номером») — подставляем, если есть.
    if row.shared_phone:
        _set_user_phone(session, user, row.shared_phone)
        session.refresh(user)
    # ⛔ Номер ОБЯЗАТЕЛЕН (безопасность / защита от мошенников). Без реального номера вход
    # не завершаем: код НЕ помечаем used (status='sent') → юзер делится номером в боте и
    # повторяет ввод того же кода. Клиент по 403 phone_required показывает экран-подсказку.
    if is_placeholder_phone(user.phone):
        raise HTTPException(403, "phone_required")
    _maybe_promote_admin(session, user)   # автоадмин по telegram_id или телефону
    row.status = "used"
    session.add(row)
    session.commit()
    session.refresh(user)
    # Анти-фрод (B8-1/2): фиксируем устройство; вход с нового → push+SMS-сигнал (не блокируем).
    remember_login_device(session, user, x_device_id)
    record_login_consents(session, user.id)   # 152-ФЗ: оферта/политика/18+ с датой
    tokens = issue_tokens(session, user.id)   # commit внутри → user протухает
    session.refresh(user)
    return {**tokens, "user": user}


class RefreshIn(BaseModel):
    refresh_token: str


@router.post("/auth/refresh")
def refresh(body: RefreshIn, session: Session = Depends(get_session)):
    """Обновить пару токенов по refresh-токену (ротация: старый refresh гасится)."""
    if not body.refresh_token.strip():
        raise herr(400, "Не получилось продлить вход. Войди заново.",
                   "Инеүҙе оҙайтып булманы. Яңынан ин.")
    return rotate_refresh(session, body.refresh_token.strip())


# VK / WhatsApp вход — ОТКЛЮЧЕНО до безопасной реализации.
# Прежние версии выдавали токен по непроверенному vk_id/телефону (whatsapp-callback —
# угон аккаунта: любой с чужим номером получал токен). Включим, когда будет:
#   VK  — серверный OAuth code-exchange (/auth/vk/callback) с проверкой на стороне VK;
#   WA  — WhatsApp Business API с подтверждением номера.
@router.post("/auth/vk-callback")
def vk_callback():
    raise HTTPException(501, "VK-вход ещё не подключён")


@router.post("/auth/whatsapp-callback")
def whatsapp_callback():
    raise HTTPException(501, "WhatsApp-вход ещё не подключён")


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
    """Выход со всех устройств: гасим access (метка tokens_valid_from) и все refresh-токены."""
    user.tokens_valid_from = utcnow()
    session.add(user)
    session.commit()
    revoke_all_refresh(session, user.id)
    return {"ok": True}


class PushTokenIn(BaseModel):
    token: str


@router.post("/push/register")
def push_register(body: PushTokenIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Регистрация/перепривязка FCM-токена устройства к текущему пользователю."""
    if not body.token.strip():
        raise HTTPException(400, "Пустой токен")
    existing = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
    if existing:
        existing.user_id = user.id
        session.add(existing)
        session.commit()
        return {"ok": True}
    # Нового токена ещё нет — вставляем. Клиент шлёт токен из 2 мест на старте (сохранённый + свежий FCM),
    # оба запроса могут попасть на разные воркеры и одновременно пройти select-пусто → гонка на unique(token).
    try:
        session.add(DeviceToken(user_id=user.id, token=body.token))
        session.commit()
    except IntegrityError:
        # Параллельный запрос успел вставить тот же токен между select и commit → перепривязываем к текущему юзеру.
        session.rollback()
        row = session.exec(select(DeviceToken).where(DeviceToken.token == body.token)).first()
        if row:
            row.user_id = user.id
            session.add(row)
            session.commit()
    return {"ok": True}


@router.post("/push/unregister")
def push_unregister(body: PushTokenIn, user: User = Depends(current_user), session: Session = Depends(get_session)):
    """Отвязка FCM-токена устройства при выходе из аккаунта. Приватность на общем телефоне:
    без этой ручки вышедший пользователь продолжал получать чужие пуши (брони/чат/SOS) —
    клиент удалял токен только локально. Только СВОЙ токен (чужой не отвяжешь). Идемпотентно."""
    token = body.token.strip()
    if not token:
        raise HTTPException(400, "Пустой токен")
    row = session.exec(select(DeviceToken).where(
        DeviceToken.token == token, DeviceToken.user_id == user.id,
    )).first()
    if row:
        session.delete(row)
        session.commit()
    return {"ok": True}
