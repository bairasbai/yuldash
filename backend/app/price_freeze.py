"""Заморозка цены: показали сумму — по ней и повезём, пока человек думает.

Что было. Экран показывал 450 ₽, человек смотрел на карту, советовался, нажимал «Заказать»
через полминуты — и сервер считал цену ЗАНОВО. За эти полминуты мог подрасти спрос, начаться
снег, уехать ближайшая машина. Человек платил 495 ₽, ни о чём не предупреждённый. Формально
честно (цену всегда считает сервер), по-человечески — обман: он согласился на другое число.

Что стало. Цена, показанная в оценке, держится `price_freeze_sec` (2 минуты).
И держится ТОЛЬКО В ПОЛЬЗУ ЧЕЛОВЕКА (решение Александра, 2026-08-28):

  • цена выросла  → берём замороженную, ту, что он видел;
  • цена упала    → берём новую, она дешевле.

Так «заморозка» никогда не работает против того, кого защищает. Агрегаторы фиксируют цену
в одну сторону — свою; разница в комиссии для нас копеечная, а сказать можно одной фразой:
«пока думаешь, цена не вырастет».

Где живёт. Только Redis, 2 минуты, ключ — хэш от округлённых точек: координаты не хранятся.
Redis нет — заморозки нет, всё работает как раньше (цена считается заново). Метрика не важнее
поездки, и защита тоже: сломанный Redis не должен мешать человеку уехать.
"""
import hashlib
import json
import logging

from .config import settings

log = logging.getLogger("uvicorn.error")

# Поля денег, которые снимаются с оценки в заказ. Ровно то, что читают `price_fields()`
# и создание заказа: заморозить половину цены значит собрать заказ из двух разных расчётов.
FROZEN_KEYS = (
    "price", "ride_price", "base_price",
    "pickup_fee", "pickup_km", "pickup_pending", "pickup_enroute",
    "options_fee", "weather_fee", "weather_kind",
    "round_trip", "distance_km", "eta_min", "tariff_id", "surge_k", "pricing_k",
)


# Поля цены доставки. Отдельный список, потому что у доставки свой счёт: заморозить
# половину одного расчёта и половину другого нельзя ни там, ни там.
FROZEN_COURIER_KEYS = (
    "price_kop", "commission_kop", "distance_km", "zone",
    "delivery_kop", "pickup_kop", "pickup_pending", "pickup_max_kop",
    "weather_kop", "weather_kind", "night_k",
)


def _courier_key(user_id: int, frm: tuple, to: tuple, подпись: str) -> str:
    """Ключ заморозки доставки. В подпись входит всё, что меняет цену: размер, срочность, тип."""
    raw = f"{frm[0]:.3f},{frm[1]:.3f}->{to[0]:.3f},{to[1]:.3f}|{подпись}"
    return f"pricefreeze:courier:{user_id}:{hashlib.sha1(raw.encode()).hexdigest()[:12]}"


def remember_courier(r, user_id: int, frm: tuple, to: tuple, подпись: str, priced: dict) -> int:
    """Запомнить показанную цену доставки. Возврат — на сколько секунд она закреплена."""
    sec = lock_seconds()
    if r is None or sec <= 0 or not priced:
        return 0
    try:
        snapshot = {k: priced[k] for k in FROZEN_COURIER_KEYS if k in priced}
        r.set(_courier_key(user_id, frm, to, подпись),
              json.dumps(snapshot, ensure_ascii=False), ex=sec)
        return sec
    except Exception:  # noqa: BLE001 — заморозка не имеет права уронить расчёт цены
        log.warning("[PRICEFREEZE] не удалось запомнить цену доставки")
        return 0


def apply_courier(r, user_id: int, frm: tuple, to: tuple, подпись: str, priced: dict) -> dict:
    """Цена доставки, по которой оформляем заказ: замороженная или новая — что дешевле."""
    if r is None or lock_seconds() <= 0 or not priced:
        return priced
    try:
        raw = r.get(_courier_key(user_id, frm, to, подпись))
        if not raw:
            return priced
        frozen = json.loads(raw)
        было, стало = int(frozen["price_kop"]), int(priced["price_kop"])
    except Exception:  # noqa: BLE001
        return priced
    if было >= стало:
        return priced                   # новая цена не хуже — берём её, она дешевле
    merged = dict(priced)
    merged.update({k: v for k, v in frozen.items() if k in FROZEN_COURIER_KEYS})
    merged["price_was_frozen"] = True
    return merged


def _key(user_id: int, frm: tuple, to: tuple, category: str,
         options_csv: str, round_trip: bool) -> str:
    """Ключ заморозки. Всё, что меняет цену, входит в подпись: иначе человек переключил класс
    с Эконома на Бизнес и получил бы цену Эконома — приятно ему, но неправда."""
    raw = (f"{frm[0]:.3f},{frm[1]:.3f}->{to[0]:.3f},{to[1]:.3f}"
           f"|{category}|{options_csv}|{int(bool(round_trip))}")
    return f"pricefreeze:{user_id}:{hashlib.sha1(raw.encode()).hexdigest()[:12]}"


def lock_seconds() -> int:
    """Сколько держим цену. 0 — заморозка выключена настройкой."""
    return max(int(settings.price_freeze_sec), 0)


def remember(r, user_id: int, frm: tuple, to: tuple, category: str,
             options_csv: str, round_trip: bool, est: dict) -> int:
    """Запомнить показанную цену. Возвращает, на сколько секунд она закреплена (0 = не вышло)."""
    sec = lock_seconds()
    if r is None or sec <= 0 or not est:
        return 0
    try:
        snapshot = {k: est[k] for k in FROZEN_KEYS if k in est}
        r.set(_key(user_id, frm, to, category, options_csv, round_trip),
              json.dumps(snapshot, ensure_ascii=False), ex=sec)
        return sec
    except Exception:  # noqa: BLE001 — заморозка не имеет права уронить расчёт цены
        log.warning("[PRICEFREEZE] не удалось запомнить цену")
        return 0


def apply(r, user_id: int, frm: tuple, to: tuple, category: str,
          options_csv: str, round_trip: bool, est: dict) -> dict:
    """Вернуть цену, по которой оформляем заказ: замороженную или новую — что дешевле.

    Заказ собирается из ОДНОГО расчёта целиком: смешать замороженную сумму с новой
    расшифровкой значит показать в чеке строки, которые не складываются в итог.
    """
    if r is None or lock_seconds() <= 0 or not est:
        return est
    try:
        raw = r.get(_key(user_id, frm, to, category, options_csv, round_trip))
        if not raw:
            return est
        frozen = json.loads(raw)
    except Exception:  # noqa: BLE001
        return est
    if not isinstance(frozen, dict) or "price" not in frozen:
        return est
    try:
        was, now = int(frozen["price"]), int(est["price"])
    except (TypeError, ValueError, KeyError):
        return est
    if was >= now:
        return est                      # новая цена не хуже — берём её, она дешевле
    merged = dict(est)
    merged.update({k: v for k, v in frozen.items() if k in FROZEN_KEYS})
    merged["price_was_frozen"] = True   # для теста и для честной строки в ответе
    return merged
