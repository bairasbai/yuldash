"""Last-position кэш машины для live-ссылки близкого (B7c, страница /t/{token}).

Сервер и так получает координаты водителя через WS (/ws/instant/{id}/location,
/ws/trip/{id}/location — location.py) и лишь ретранслирует их. Здесь при приёме кадра
от ВОДИТЕЛЯ дополнительно кладём последнюю позицию в Redis с коротким TTL — публичный
state.json читает её без всяких подписок. В БД координаты по-прежнему НЕ пишем.

Приватность: TTL ~120с — после конца поездки/потери связи позиция сама испаряется.
Координаты НЕ логируем. Без Redis — все функции no-op (car=null, страница не падает).

Redis-клиент — общий с presence/matcher (instant_service._redis): тот же
_redis_override для тестов (fakeredis), тот же _cache_client в проде.
"""
import json
from datetime import datetime, timezone

from .timeutil import utcnow

LIVEPOS_TTL_SEC = 120   # позиция «живёт» 2 мин без обновления — дальше car=null


def _redis():
    from .instant_service import _redis as isv_redis
    return isv_redis()


def _key(kind: str, ref_id: int) -> str:
    return f"livepos:{kind}:{ref_id}"   # kind: order | booking


def livepos_set(kind: str, ref_id: int, lat: float, lng: float, bearing=None) -> None:
    """Записать последнюю позицию машины. Ошибки Redis глотаем — кэш не роняет WS-поток."""
    r = _redis()
    if r is None:
        return
    try:
        payload = {"lat": lat, "lng": lng, "bearing": bearing, "ts": utcnow().isoformat()}
        r.set(_key(kind, ref_id), json.dumps(payload), ex=LIVEPOS_TTL_SEC)
    except Exception:  # noqa: BLE001 — кэш best-effort
        pass


def livepos_get(kind: str, ref_id: int):
    """Последняя позиция машины или None (нет Redis / TTL истёк / не писалась)."""
    r = _redis()
    if r is None:
        return None
    try:
        v = r.get(_key(kind, ref_id))
        return json.loads(v) if v else None
    except Exception:  # noqa: BLE001
        return None


def _age_sec(pos: dict, now: datetime):
    """Сколько секунд назад записана точка; None — метки времени нет или она кривая."""
    try:
        ts = datetime.fromisoformat(str(pos.get("ts") or ""))
    except ValueError:
        return None
    if ts.tzinfo is not None:
        ts = ts.astimezone(timezone.utc).replace(tzinfo=None)
    return (now - ts).total_seconds()


def driver_point_for_ride(session, ride, now=None):
    """Самая свежая точка ВОДИТЕЛЯ этой поездки — по всем её живым броням (18.10.2026, PR0).

    ТОЛЬКО ДЛЯ ВНУТРЕННИХ ПРОВЕРОК СЕРВЕРА — никому не отдаётся (ни ленте, ни карте, ни превью
    `/r/{id}`, ни ссылке близким, ни каналу пассажира; сторож — `test_driver_point_for_ride.py`).
    Сейчас её читает только честное «Подъезжаю» (`bookings._verify_arrival`).

    Зачем. Точку водителя кладёт в кэш канал ОДНОЙ брони — той, чью поездку водитель открыл
    («Едут с тобой» → бронь A). По брони B того же рейса кэш пуст. Раньше «Подъезжаю» по B
    читало кэш B, не находило там ничего и пропускало слово водителя без проверки: открыл бронь A,
    жмёшь «Подъезжаю» по B за 25 км — пассажирке B уходит «Водитель подъезжает», и она выходит
    на мороз. Машина у водителя одна: где он для A, там он и для B.

    Почему это не открывает новых данных: в кэш броней пишет только водитель (`location.py`, роль
    driver), это его собственная точка, а проверка отвечает ему же — «далеко»/«рядом». Пассажиры
    точки друг друга сюда не попадают никогда.

    Свежесть — не старше `LIVEPOS_TTL_SEC` (кэш и так забывает точку через столько же). Точку
    без метки времени (старый формат) берём, но ставим ниже точек с меткой. Живые брони — как
    `rides._live_bookings`: всё, кроме отменённых и завершённых. None — точки нет (Redis молчит,
    водитель не прислал ни кадра, все точки протухли)."""
    from sqlmodel import select

    from .models import Booking, BookingStatus

    now = now or utcnow()
    ids = session.exec(select(Booking.id).where(
        Booking.ride_id == ride.id,
        Booking.status.notin_((BookingStatus.cancelled, BookingStatus.done)),  # type: ignore[attr-defined]
    )).all()
    best, best_age = None, None
    for bid in ids:
        pos = livepos_get("booking", bid)
        if not pos or pos.get("lat") is None or pos.get("lng") is None:
            continue
        age = _age_sec(pos, now)
        if age is not None and age > LIVEPOS_TTL_SEC:
            continue   # протухла: машина могла уехать куда угодно
        rank = age if age is not None else LIVEPOS_TTL_SEC + 1   # без метки — хуже любой свежей
        if best is None or rank < best_age:
            best, best_age = pos, rank
    return best


def livepos_clear(kind: str, ref_id: int) -> None:
    """Стереть позицию (поездка завершена/отменена — не ждём TTL)."""
    r = _redis()
    if r is None:
        return
    try:
        r.delete(_key(kind, ref_id))
    except Exception:  # noqa: BLE001
        pass
