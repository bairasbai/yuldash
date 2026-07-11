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


def livepos_clear(kind: str, ref_id: int) -> None:
    """Стереть позицию (поездка завершена/отменена — не ждём TTL)."""
    r = _redis()
    if r is None:
        return
    try:
        r.delete(_key(kind, ref_id))
    except Exception:  # noqa: BLE001
        pass
