"""Анонимная продуктовая аналитика (для веб-версии).

POST /events — приём событий воронки БЕЗ обязательной авторизации (веб шлёт до логина).
Приватность: сервер САМ вырезает потенциально чувствительные ключи из props (телефон,
имя, координаты, токены, e-mail, адрес…) и обрезает строки — в БД попадает только
анонимный агрегат. Личности нет: ни user_id, ни телефона, ни точных координат.
Битый/пустой payload → 204 без ошибки (телеметрия не должна ронять клиента).

GET /admin/events/summary — счётчики по event за период (только админ, SQL GROUP BY).

Rate-limit: отдельного нет намеренно — глобальный лимитер по IP (middleware) уже
покрывает /events (best-effort, потеря события не критична).
"""
import json
from datetime import timedelta

from fastapi import APIRouter, Depends, Request, Response
from fastapi import HTTPException
from sqlalchemy import func
from sqlmodel import Session, select

from ..db import get_session
from ..models import AnalyticsEvent, User, UserRole
from ..security import current_user
from ..timeutil import utcnow

router = APIRouter(tags=["events"])

# Максимальные длины (сервер не верит клиенту).
_MAX_EVENT = 64
_MAX_CLIENT_ID = 64
_MAX_STR = 128            # значение-строку в props режем до 128 символов
_MAX_PROPS = 24          # не больше 24 ключей props (защита от раздувания строки)

# Денилист: если ключ props СОДЕРЖИТ любой из фрагментов (регистронезависимо) —
# ключ вырезается целиком. Ловим и вариации (phone/phone_number, lat/latitude, ...).
# Приватность-first: лучше срезать чуть лишнего, чем пропустить личное.
_DENY_FRAGMENTS = (
    "phone", "name", "lat", "lng", "latitude", "longitude", "coord", "gps", "geo",
    "token", "email", "mail", "address", "addr", "street", "secret",
    "password", "passwd", "pwd", "otp", "cvv", "card", "pan",
    "session", "auth", "user_id", "userid", "uid",
)

# Служебные ключи тела запроса — НЕ дублируем их в context (они разбираются отдельно).
_RESERVED = {"event", "client_id", "ts"}
# Явно безопасные поля контракта — пропускаем даже если случайно совпали с фрагментом
# (напр. "standalone" содержит "lon", но это флаг PWA, не координата).
_SAFE_KEYS = {"lang", "role", "standalone"}


def _clean_value(v):
    """Оставляем только безопасные скаляры. Строки режем до _MAX_STR. Вложенное
    (dict/list) отбрасываем — телеметрия воронки в них не нуждается, а глубокий
    объект легче спрятать в него чувствительное."""
    if isinstance(v, bool):          # bool раньше int (bool — подтип int)
        return v
    if isinstance(v, (int, float)):
        return v
    if isinstance(v, str):
        return v[:_MAX_STR]
    return None                       # None/dict/list/прочее — не пишем


def sanitize_props(raw) -> dict:
    """Из произвольного тела события собираем анонимный, безопасный набор props:
    выкидываем служебные и чувствительные ключи (по денилисту), режем строки,
    ограничиваем число ключей. Не-словарь → пусто."""
    if not isinstance(raw, dict):
        return {}
    out: dict = {}
    for k, v in raw.items():
        if not isinstance(k, str):
            continue
        key = k.strip()[:_MAX_STR]
        if not key or key in _RESERVED:
            continue
        low = key.casefold()
        if low not in _SAFE_KEYS and any(frag in low for frag in _DENY_FRAGMENTS):
            continue                  # чувствительный ключ — вырезаем целиком
        cleaned = _clean_value(v)
        if cleaned is None:
            continue
        out[key] = cleaned
        if len(out) >= _MAX_PROPS:
            break
    return out


@router.post("/events", status_code=204)
async def ingest_event(request: Request, session: Session = Depends(get_session)) -> Response:
    """Приём анонимного события. Тело: {event, client_id, ts?, lang?, role?, ...props}.
    Всегда 204: битое/пустое тело или отсутствие event — просто молча пропускаем
    (клиентская телеметрия не должна получать ошибок и ретраить). Персональные данные
    не логируем и не пишем — сервер режет чувствительные ключи до записи."""
    try:
        raw = await request.json()
    except Exception:  # noqa: BLE001 — битый JSON: не событие, не падаем
        return Response(status_code=204)
    if not isinstance(raw, dict):
        return Response(status_code=204)

    event = str(raw.get("event") or "").strip()[:_MAX_EVENT]
    if not event:
        return Response(status_code=204)   # без имени события писать нечего
    client_id = str(raw.get("client_id") or "").strip()[:_MAX_CLIENT_ID]

    ts = raw.get("ts")
    ts_val = ts if isinstance(ts, int) and not isinstance(ts, bool) else None

    props = sanitize_props(raw)
    try:
        session.add(AnalyticsEvent(
            event=event, client_id=client_id, ts=ts_val,
            context_json=json.dumps(props, ensure_ascii=False, separators=(",", ":")),
        ))
        session.commit()
    except Exception:  # noqa: BLE001 — сбой записи телеметрии не роняет клиента
        session.rollback()
    return Response(status_code=204)


@router.get("/admin/events/summary")
def events_summary(days: int = 7, user: User = Depends(current_user),
                   session: Session = Depends(get_session)):
    """Мини-агрегат воронки для админа: сколько каких событий за последние N дней.
    Только админ. Считаем SQL-агрегатом (GROUP BY event), таблицу целиком не тянем."""
    if user.role != UserRole.admin:
        raise HTTPException(403, "Только для админа")
    days = max(1, min(days, 90))
    since = utcnow() - timedelta(days=days)
    rows = session.exec(
        select(AnalyticsEvent.event, func.count())
        .where(AnalyticsEvent.created_at >= since)
        .group_by(AnalyticsEvent.event)
        .order_by(func.count().desc())
    ).all()
    events = [{"event": ev, "count": int(cnt)} for ev, cnt in rows]
    return {
        "days": days,
        "since": since.isoformat(),
        "total": sum(e["count"] for e in events),
        "events": events,
    }
