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


def driver_points_for_ride(session, ride, now=None) -> list:
    """ВСЕ свежие точки ВОДИТЕЛЯ этой поездки — по её подтверждённым броням и броням «в пути»
    (18.10.2026, PR0). Свежие первыми; у каждой — `booking_id` брони, чей канал её записал.

    ТОЛЬКО ДЛЯ ВНУТРЕННИХ ПРОВЕРОК СЕРВЕРА — никому не отдаётся (ни ленте, ни карте, ни превью
    `/r/{id}`, ни ссылке близким, ни каналу пассажира; сторож — `test_driver_point_for_ride.py`).
    Сейчас их читает только честное «Подъезжаю» (`bookings._verify_arrival`).

    Зачем. Точку водителя кладёт в кэш канал ОДНОЙ брони — той, чью поездку водитель открыл
    («Едут с тобой» → бронь A). По брони B того же рейса кэш пуст. Раньше «Подъезжаю» по B
    читало кэш B, не находило там ничего и пропускало слово водителя без проверки: открыл бронь A,
    жмёшь «Подъезжаю» по B за 25 км — пассажирке B уходит «Водитель подъезжает», и она выходит
    на мороз. Машина у водителя одна: где он для A, там он и для B.

    Почему СПИСОК, а не одна «самая свежая» (приёмка PR0, P2). Под тем же аккаунтом водителя может
    быть открыто второе устройство — семейный планшет дома с открытой бронью B шлёт пульс раз в
    45 с. Самая свежая точка тогда — дом за 27 км, и водитель у пина получал бы ложный отказ.
    Проверка берёт ближайшую к цели из всех свежих: отказ — только когда ВСЕ свежие точки далеко
    (ложный отказ дороже пропущенного обмана).

    Почему это не открывает новых данных: в кэш броней пишет только водитель (`location.py`, роль
    driver), это его собственные точки, а проверка отвечает ему же — «далеко»/«рядом». Пассажиры
    точки друг друга сюда не попадают никогда.

    Брони — только подтверждённые и «в пути»: только по ним канал вообще пускает водителя
    (`location.trip_location`). Свежесть — не старше `LIVEPOS_TTL_SEC` (кэш и так забывает точку
    через столько же); точку без метки времени (старый формат) берём последней. Без Redis — пустой
    список и в базу не ходим. Ошибку Redis глотаем (кэш best-effort), ошибку базы — нет: она
    испортила бы транзакцию ручки «Подъезжаю» для её следующего UPDATE."""
    r = _redis()
    if r is None:
        return []
    from sqlmodel import select

    from .models import Booking, BookingStatus

    ids = list(session.exec(select(Booking.id).where(
        Booking.ride_id == ride.id,
        Booking.status.in_((BookingStatus.confirmed, BookingStatus.onboard)),  # type: ignore[attr-defined]
    )).all())
    if not ids:
        return []
    try:
        raw = r.mget([_key("booking", bid) for bid in ids])
    except Exception:  # noqa: BLE001 — кэш best-effort: Redis молчит → проверить нечем
        return []
    now = now or utcnow()
    found = []
    for bid, v in zip(ids, raw or []):
        if not v:
            continue
        try:
            pos = json.loads(v)
        except (TypeError, ValueError):
            continue
        if not isinstance(pos, dict) or pos.get("lat") is None or pos.get("lng") is None:
            continue
        age = _age_sec(pos, now)
        if age is not None and age > LIVEPOS_TTL_SEC:
            continue   # протухла: машина могла уехать куда угодно
        found.append((age if age is not None else LIVEPOS_TTL_SEC + 1, {**pos, "booking_id": bid}))
    found.sort(key=lambda x: x[0])
    return [pos for _age, pos in found]


def livepos_clear(kind: str, ref_id: int) -> None:
    """Стереть позицию (поездка завершена/отменена — не ждём TTL)."""
    r = _redis()
    if r is None:
        return
    try:
        r.delete(_key(kind, ref_id))
    except Exception:  # noqa: BLE001
        pass
