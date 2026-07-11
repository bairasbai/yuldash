"""Анти-фрод (батч B8) — прагматичный v1 без ML: устройство, вход, GPS, чат.

Принцип (утверждён): автоматика только ПОМЕЧАЕТ (флаги, сигналы, счётчики админу),
жёстко банит ЧЕЛОВЕК. Честного пользователя автоматика не наказывает.
Приватность: device_id, координаты и телефоны в логи открытым текстом НЕ пишем.
"""
import re
from typing import Optional

from fastapi import HTTPException
from sqlmodel import Session, select

from .models import DeviceBan, User
from .timeutil import utcnow

# ------------------------------ устройство (B8-1) ------------------------------
MAX_DEVICE_ID_LEN = 64

# Текст 403 забаненному устройству: коротко, с путём в поддержку (RU + черновой BA).
DEVICE_BANNED_MSG = ("Аккаунт заблокирован — напиши в поддержку."
                     " · Аккаунт бикләнгән — ярҙам хеҙмәтенә яҙ.")


def normalize_device_id(raw: Optional[str]) -> str:
    """X-Device-Id из заголовка: обрезаем мусор/длину. Пусто → '' (старый клиент без заголовка)."""
    return (raw or "").strip()[:MAX_DEVICE_ID_LEN]


def device_banned(session: Session, device_id: Optional[str]) -> bool:
    did = normalize_device_id(device_id)
    if not did:
        return False   # старый клиент без заголовка — не наказываем (не по кому проверять)
    return session.exec(select(DeviceBan).where(DeviceBan.device_id == did)).first() is not None


def guard_device_not_banned(session: Session, device_id: Optional[str]) -> None:
    """Гейт регистрации/логина: забаненное устройство → 403 (обход бана новым номером)."""
    if device_banned(session, device_id):
        raise HTTPException(403, DEVICE_BANNED_MSG)


def ban_device(session: Session, device_id: str, reason: str = "",
               user_id: Optional[int] = None) -> DeviceBan:
    """Забанить устройство (только админ, идемпотентно — повторный бан возвращает существующий)."""
    did = normalize_device_id(device_id)
    existing = session.exec(select(DeviceBan).where(DeviceBan.device_id == did)).first()
    if existing:
        return existing
    ban = DeviceBan(device_id=did, reason=(reason or "")[:300], user_id=user_id)
    session.add(ban)
    session.commit()
    session.refresh(ban)
    return ban


def unban_device(session: Session, device_id: str) -> bool:
    """Снять бан устройства. True — бан был и снят, False — бана не было."""
    did = normalize_device_id(device_id)
    rows = session.exec(select(DeviceBan).where(DeviceBan.device_id == did)).all()
    for r in rows:
        session.delete(r)
    session.commit()
    return bool(rows)


# ------------------------------ вход: фиксация устройства + сигнал (B8-1/B8-2) ------------------------------
def remember_login_device(session: Session, user: User, device_id: Optional[str]) -> None:
    """После успешного входа: фиксируем устройство на юзере. Вход с НОВОГО устройства
    (device_id ≠ последнего) → push + SMS «это не ты — смени номер / напиши в поддержку».
    Не блокируем — только сигнал (честный пользователь мог сменить телефон)."""
    did = normalize_device_id(device_id)
    if not did:
        return                          # старый клиент без заголовка — фиксировать нечего
    if user.last_device_id == did:
        return                          # то же устройство — тишина
    is_new_device = bool(user.last_device_id)   # первый вход (None/пусто) сигналом не считаем
    user.last_device_id = did
    session.add(user)
    session.commit()
    if not is_new_device:
        return
    # Сигнал (пункт 2): push + SMS. Локальный импорт — тесты патчат app.services.
    from .services import send_push, send_text
    warn = ("Вход в Юлдаш с нового устройства. Это не ты — смени номер и напиши в поддержку."
            " · Юлдашҡа яңы ҡоролмандан инеү. Был һин түгел икән — номерҙы алмаштыр һәм"
            " ярҙам хеҙмәтенә яҙ.")
    send_push(session, user.id, "Вход с нового устройства · Яңы ҡоролмандан инеү", warn)
    if user.phone and not user.phone.startswith("tg"):
        send_text(user.phone, f"Юлдаш: {warn}")


# ------------------------------ GPS: анти-телепорт (B8-3) ------------------------------
# Скорость между последовательными точками выше физически разумной → точка фейковая
# (спуфинг/телепорт): игнорируем её и копим счётчик подозрительности. Честных не роняем:
# первая точка после паузы проходит сама (время выросло → скорость упала).
TELEPORT_MAX_KMH = 200.0
TELEPORT_FLAG_COUNT = 3          # 3+ телепорта за час → флаг в админ-пульс/лог
_TP_ANCHOR_TTL = 3600            # якорь «последняя честная точка» живёт час
_TP_COUNTER_TTL = 3600           # окно счётчика подозрительности — час
_TP_FLAG_TTL = 172800            # суточный набор подозрительных живёт 2 суток


def _tp_day_key(now=None) -> str:
    return f"af:tpflag:{(now or utcnow()).strftime('%Y%m%d')}"


def teleport_filter(r, user_id: int, lat: float, lng: float, now_ts: Optional[float] = None) -> bool:
    """True — точка честная (публикуем), False — телепорт (игнорируем, точку НЕ публикуем).

    Якорь — последняя ПРИНЯТАЯ точка: отвергнутая якорь не двигает (иначе два телепорта
    подряд «легализуются»). Без Redis — пропускаем всё (фильтр не роняет функциональность)."""
    if r is None:
        return True
    from .services import haversine_km   # локальный импорт: без циклов на старте
    now_ts = now_ts if now_ts is not None else utcnow().timestamp()
    key = f"af:pt:{user_id}"
    try:
        prev = r.get(key)
    except Exception:  # noqa: BLE001 — сбой Redis не роняет приём координат
        return True
    ok = True
    if prev:
        try:
            p_lat, p_lng, p_ts = (prev.decode() if isinstance(prev, bytes) else prev).split(",")
            dist_km = haversine_km(float(p_lat), float(p_lng), lat, lng)
            elapsed_h = max(now_ts - float(p_ts), 1.0) / 3600.0   # пол 1с — защита от деления на ~0
            ok = (dist_km / elapsed_h) <= TELEPORT_MAX_KMH
        except (ValueError, TypeError):
            ok = True   # битый якорь — не наказываем
    try:
        if ok:
            r.set(key, f"{lat},{lng},{now_ts}", ex=_TP_ANCHOR_TTL)
        else:
            _count_teleport(r, user_id)
    except Exception:  # noqa: BLE001
        return True
    return ok


def _count_teleport(r, user_id: int) -> None:
    """Счётчик телепортов за час; на TELEPORT_FLAG_COUNT — флаг в суточный набор + лог
    (БЕЗ координат — только id и факт, приватность)."""
    ckey = f"af:tpc:{user_id}"
    n = r.incr(ckey)
    r.expire(ckey, _TP_COUNTER_TTL)
    if int(n) == TELEPORT_FLAG_COUNT:
        dkey = _tp_day_key()
        r.sadd(dkey, str(user_id))
        r.expire(dkey, _TP_FLAG_TTL)
        print(f"[ANTIFRAUD] gps-suspect user={user_id}: {TELEPORT_FLAG_COUNT}+ телепортов за час "
              f"(точки игнорируются, решает админ)")


def gps_suspects_today(r) -> int:
    """Сколько пользователей сегодня помечено GPS-подозрительными (для админ-пульса)."""
    if r is None:
        return 0
    try:
        return int(r.scard(_tp_day_key()))
    except Exception:  # noqa: BLE001
        return 0


# ------------------------------ чат: анти-фишинг (B8-6) ------------------------------
# Сообщение НЕ блокируем (свобода честного разговора) — только помечаем flag="warn",
# клиент показывает получателю плашку «Никому не сообщай коды из SMS…».
# Паттерны узкие, чтобы не флажить честные сообщения (код посадки, «буду через 5 минут»):
#   1) просьба кода ИЗ SMS / кода подтверждения / кода для входа;
#   2) номер банковской карты (16 цифр, с пробелами/дефисами или слитно);
#   3) «переведи на другой номер / другую карту» (увод оплаты не тому человеку).
MESSAGE_FLAG_WARN = "warn"

_PHISHING_RES = (
    re.compile(r"код\w*[^.!?\n]{0,40}\b(?:смс|sms)\b", re.IGNORECASE),
    re.compile(r"\b(?:смс|sms)\b[^.!?\n]{0,40}код", re.IGNORECASE),
    re.compile(r"код\w*\s+(?:подтвержден\w*|для\s+входа|из\s+приложени\w*)", re.IGNORECASE),
    re.compile(r"\b\d{4}[ \-]?\d{4}[ \-]?\d{4}[ \-]?\d{4}\b"),
    re.compile(r"перевед\w*[^.!?\n]{0,30}на\s+друг(?:ой|ую)\s+(?:номер|карт\w*)", re.IGNORECASE),
)


def phishing_flag(text: Optional[str]) -> str:
    """'' — обычное сообщение, 'warn' — похоже на развод (см. паттерны выше)."""
    t = (text or "").strip()
    if not t:
        return ""
    return MESSAGE_FLAG_WARN if any(rx.search(t) for rx in _PHISHING_RES) else ""


class TrackGuard:
    """Анти-телепорт для WS-треков (пер-соединение): якорь — в памяти соединения (сокет и есть
    непрерывный поток одного клиента, Redis не нужен). Телепорт-кадр не ретранслируем, счётчик
    подозрительности копится в Redis (если он есть) — тем же путём, что presence."""

    def __init__(self, user_id: int):
        self.user_id = user_id
        self._anchor: Optional[tuple] = None   # (lat, lng, ts) последней ЧЕСТНОЙ точки

    def ok(self, lat: float, lng: float, now_ts: Optional[float] = None) -> bool:
        from .services import haversine_km
        now_ts = now_ts if now_ts is not None else utcnow().timestamp()
        if self._anchor is not None:
            p_lat, p_lng, p_ts = self._anchor
            elapsed_h = max(now_ts - p_ts, 1.0) / 3600.0
            if haversine_km(p_lat, p_lng, lat, lng) / elapsed_h > TELEPORT_MAX_KMH:
                self._note_teleport()
                return False              # якорь не двигаем: два телепорта подряд не «легализуются»
        self._anchor = (lat, lng, now_ts)
        return True

    def _note_teleport(self) -> None:
        from . import instant_service as isv   # локальный импорт: без циклов на старте
        r = isv._redis()
        if r is None:
            return
        try:
            _count_teleport(r, self.user_id)
        except Exception:  # noqa: BLE001 — счётчик не должен ронять сокет
            pass
