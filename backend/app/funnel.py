"""Воронка такси: «посмотрел цену → заказал».

Зачем. Мы правим цену вслепую. Видно, сколько заказов сделано, но не видно, сколько людей
посмотрели цену и передумали — а это и есть главный сигнал: цена отпугивает или нет. Одна
поднятая надбавка может убить половину заказов, и без этой цифры мы узнаем об этом только по
пустой выручке через месяц.

Как считаем.
  • **Просмотр** — вызов `/instant/estimate`. Но клиент дёргает оценку на КАЖДОЕ движение
    пальца по карте, поэтому один и тот же маршрут одного человека внутри окна
    `funnel_view_dedupe_min` считается за ОДИН просмотр. Иначе метрика мерила бы не интерес
    людей, а нервность пальца.
  • **Заказ** — создание заказа человеком, который смотрел цену не дальше чем
    `funnel_attribution_min` назад. Так конверсия не может превысить 100%, а заказ «вслепую»
    (повтор из истории) не приписывается просмотру, которого не было.

Где живёт. Только Redis, только счётчики: два числа на день плюс короткие метки. Ни строки
в БД, ни таблицы, которая растёт вечно. Redis нет или упал — метрика молча нулевая, заказ и
оценка работают как работали (деньги важнее статистики).

Приватность. Координаты в ключи не попадают: маршрут сворачивается в короткий хэш от
округлённых до ~100 м точек — по нему нельзя восстановить, откуда и куда человек ехал.
"""
import hashlib
import logging
from datetime import timedelta
from typing import Optional

from .config import settings
from .timeutil import local_date, utcnow

log = logging.getLogger("uvicorn.error")

# Счётчики дня живут месяц: этого хватает, чтобы увидеть тренд и последствия правки цены,
# и при этом Redis не превращается в архив.
_DAY_TTL_SEC = 86400 * 31


def _day(now=None) -> str:
    """Ключ дня — по МЕСТНОМУ времени (Уфа). По UTC день переворачивался бы в 5 утра,
    и вечерние просмотры падали бы в завтрашний столбец (урок `lessons.md`)."""
    return local_date(now or utcnow()).isoformat()


# Режимы считаем РАЗДЕЛЬНО: у такси и у доставки разные люди, разные цены и разные причины
# передумать. Одна общая цифра усреднила бы их в число, по которому нельзя принять решение.
TAXI, COURIER = "taxi", "courier"


def _views_key(day: str, kind: str = TAXI) -> str:
    return f"funnel:view:{day}" if kind == TAXI else f"funnel:{kind}:view:{day}"


def _orders_key(day: str, kind: str = TAXI) -> str:
    return f"funnel:order:{day}" if kind == TAXI else f"funnel:{kind}:order:{day}"


def _route_token(frm: tuple, to: tuple) -> str:
    """Короткий хэш маршрута — чтобы отличать «двигает пин по тому же маршруту» от «смотрит
    другой». Точки округлены до ~100 м (как в кэше маршрутов), сам хэш необратим."""
    raw = f"{frm[0]:.3f},{frm[1]:.3f}->{to[0]:.3f},{to[1]:.3f}"
    return hashlib.sha1(raw.encode()).hexdigest()[:12]


def note_price_view(r, user_id: int, frm: tuple, to: tuple, now=None, kind: str = TAXI) -> bool:
    """Человек посмотрел цену. True — просмотр НОВЫЙ (учли), False — тот же маршрут в окне.

    Никогда не бросает: статистика не имеет права уронить расчёт цены.
    """
    if r is None:
        return False
    now = now or utcnow()
    try:
        seen = f"funnel:seen:{kind}:{user_id}:{_route_token(frm, to)}"
        window = max(int(settings.funnel_view_dedupe_min), 1) * 60
        if not r.set(seen, "1", nx=True, ex=window):
            return False                       # тот же маршрут в окне — это одно намерение
        day = _day(now)
        r.incr(_views_key(day, kind))
        r.expire(_views_key(day, kind), _DAY_TTL_SEC)
        # Метка «этот человек только что видел цену» — по ней заказ попадёт в воронку.
        # Живёт дольше окна склейки: посмотрел, дошёл до подъезда, заказал через полчаса.
        r.set(f"funnel:eye:{kind}:{user_id}", "1",
              ex=max(int(settings.funnel_attribution_min), 1) * 60)
        return True
    except Exception:  # noqa: BLE001 — метрика не важнее заказа
        log.warning("[FUNNEL] не удалось записать просмотр цены")
        return False


def note_order(r, user_id: int, now=None, kind: str = TAXI) -> bool:
    """Человек заказал. True — заказ засчитан в воронку (перед ним был просмотр цены)."""
    if r is None:
        return False
    try:
        if not r.get(f"funnel:eye:{kind}:{user_id}"):
            return False                       # заказал, не глядя на цену — не наша воронка
        day = _day(now or utcnow())
        r.incr(_orders_key(day, kind))
        r.expire(_orders_key(day, kind), _DAY_TTL_SEC)
        return True
    except Exception:  # noqa: BLE001
        log.warning("[FUNNEL] не удалось записать заказ в воронку")
        return False


def _percent(orders: int, views: int) -> Optional[float]:
    """Конверсия в процентах. Нет просмотров — None (а не 0%): «никто не смотрел» и
    «смотрели, но никто не заказал» — разные новости, и путать их нельзя."""
    if views <= 0:
        return None
    return round(orders * 100 / views, 1)


def stats(r, days: Optional[int] = None, now=None, kind: str = TAXI) -> dict:
    """Сводка для админ-пульса: сегодня, за период и разбивка по дням (свежие первыми)."""
    days = max(int(days if days is not None else settings.funnel_window_days), 1)
    now = now or utcnow()
    today = local_date(now)
    empty = [{"day": (today - timedelta(days=i)).isoformat(), "views": 0, "orders": 0} for i in range(days)]
    if r is None:
        return _pack(empty, days)
    rows = []
    try:
        for i in range(days):
            day = (today - timedelta(days=i)).isoformat()
            views = int(r.get(_views_key(day, kind)) or 0)
            orders = int(r.get(_orders_key(day, kind)) or 0)
            rows.append({"day": day, "views": views, "orders": orders})
    except Exception:  # noqa: BLE001 — панель админа не должна падать из-за Redis
        return _pack(empty, days)
    return _pack(rows, days)


def _pack(rows: list, days: int) -> dict:
    today = rows[0] if rows else {"views": 0, "orders": 0}
    views = sum(x["views"] for x in rows)
    orders = sum(x["orders"] for x in rows)
    return {
        "window_days": days,
        "views_today": today["views"],
        "orders_today": today["orders"],
        "percent_today": _percent(today["orders"], today["views"]),
        "views_period": views,
        "orders_period": orders,
        "percent_period": _percent(orders, views),
        "by_day": rows,
    }
