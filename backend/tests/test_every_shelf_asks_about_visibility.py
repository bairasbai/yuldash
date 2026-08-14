"""Сторож: новая витрина обязана спросить, кому это видно.

Зачем этот тест существует. Четыре волны подряд находили одно и то же: правило «этого
человека не показывать» жило в ленте, а соседняя витрина про него не знала.

* блокировка возвращалась через подбор под заявку и витрину клиники (волна 71);
* заявка человека на паузе висела в «заявках рядом» как живая (волна 72);
* профессиональная витрина курьеров не знала про блокировку вовсе (волна 73);
* три витрины отдавали всю базу без потолка (волна 89).

Каждый раз это писали не по невнимательности: правило было не видно со стороны. Теперь оно
собрано в `app/visibility.py`, и этот тест требует, чтобы КАЖДАЯ ручка, отдающая чужие
объявления, звала оттуда функцию — или была вписана сюда с человеческой причиной.

Проверка идёт по исходникам, а не по поведению: так новая ручка попадёт под охрану в тот же
день, когда её напишут, даже если тестов на неё ещё нет.
"""
from __future__ import annotations

import re
from pathlib import Path

ROUTERS = Path(__file__).resolve().parents[1] / "app" / "routers"

# Функции, любая из которых означает «я спросил, кому это видно».
VISIBILITY_CALLS = (
    "visible_rides(", "visible_parcels(", "hidden_author_ids(",
    "hide_blocked(", "hide_suspended(", "hide_trusted_only(", "may_be_notified(",
)

# Ручки, которые витрину НЕ образуют. У каждой — причина, понятная человеку.
ALLOWED_WITHOUT = {
    "/rides/mine": "свои поездки: человек смотрит на себя, прятать не от кого",
    "/parcels/mine": "свои посылки",
    "/parcels/carrying": "то, что курьер уже везёт: сделка состоялась, стороны известны",
    "/requests/mine": "свои заявки",
    "/instant/orders/mine": "свои заказы такси",
    "/instant/scheduled": "свои предзаказы",
    "/driver/rides": "поездки самого водителя",
    "/driver/bookings": "брони на СВОИ поездки — вторая сторона сделки",
    "/responses/mine": "свои отклики",
    "/incidents/mine": "свои разборы",
    "/admin/parcels": "админ видит всё по должности",
    "/bookings/mine": "свои брони",
    "/conversations": "свои переписки: диалог с заблокированным остаётся историей, писать в него всё равно нельзя",
    "/popular-routes": "счётчики маршрутов без людей",
    "/feed": "счётчики для карты, личности нет",
    "/landing-stats": "цифры для лендинга",
    "/my-routes": "свои частые маршруты",
    "/drivers/{driver_id}/public": "витрина конкретного водителя, её открывают тапом с карточки; лишних людей она не перебирает (проверка «водитель ли это» стоит внутри)",
    "/boost/plans": "прайс на поднятие",
    "/rides/price_hint": "средняя цена по направлению, без объявлений",
    "/reportable-users": "только те, с кем человек реально ездил",
    "/me/stats": "своя статистика",
}


def _handlers() -> list[tuple[str, str, str]]:
    """(файл, путь ручки, тело) для всех GET-ручек роутеров."""
    out: list[tuple[str, str, str]] = []
    for f in sorted(ROUTERS.glob("*.py")):
        src = f.read_text(encoding="utf-8")
        for chunk in re.split(r"\n@router\.", src)[1:]:
            head = chunk.split("\n", 1)[0]
            if not head.startswith("get("):
                continue
            m = re.search(r'"([^"]+)"', head)
            if not m:
                continue
            out.append((f.name, m.group(1), chunk.split("\n@router.")[0]))
    return out


def test_витрины_чужих_объявлений_спрашивают_про_видимость():
    """Ручка отдаёт чужие поездки/заявки/посылки → обязана позвать visibility."""
    forgotten = []
    for fname, path, body in _handlers():
        if path.startswith("/admin") or path in ALLOWED_WITHOUT:
            continue
        # Витрина — та, что выбирает объявления и отдаёт их наружу.
        selects_offers = re.search(r"select\((Ride|RideRequest|ParcelDelivery)\b", body)
        if not selects_offers:
            continue
        if any(call in body for call in VISIBILITY_CALLS):
            continue
        forgotten.append(f"{fname}: {path}")
    assert not forgotten, (
        "Эти ручки отдают чужие объявления и ни разу не спрашивают, кому их видно: "
        f"{forgotten}. Позови функцию из app/visibility.py (блокировка, пауза, «только "
        "для своих») — или впиши ручку в ALLOWED_WITHOUT с причиной, понятной человеку."
    )


def test_правило_видимости_живёт_в_одном_месте():
    """Копия фильтра внутри роутера — это будущее расхождение (так и вышло в волнах 71–73)."""
    copies = []
    for f in sorted(ROUTERS.glob("*.py")):
        src = f.read_text(encoding="utf-8")
        if re.search(r"\ndef _hide_(blocked|suspended|trusted_only)\b", src):
            copies.append(f.name)
    assert not copies, (
        f"В этих роутерах снова завелась своя копия фильтра видимости: {copies}. "
        "Правило должно жить в app/visibility.py — иначе копии разойдутся, как в волне 71."
    )


def test_у_витрин_есть_потолок_выдачи():
    """Витрина без потолка отдаёт всю базу — незаметно, пока объявлений мало (волна 89)."""
    from app.visibility import FEED_MAX

    assert 20 <= FEED_MAX <= 500, (
        f"потолок витрины {FEED_MAX} выглядит опасно: слишком мало — человек не увидит "
        "предложений, слишком много — телефон подавится выдачей"
    )
    unlimited = []
    for fname, path, body in _handlers():
        if path.startswith("/admin") or path in ALLOWED_WITHOUT:
            continue
        if not re.search(r"select\((Ride|ParcelDelivery)\b", body):
            continue
        if ".limit(" in body or "FEED_MAX" in body or "eff_limit" in body:
            continue
        unlimited.append(f"{fname}: {path}")
    assert not unlimited, (
        f"Эти витрины отдают выдачу без потолка: {unlimited}. Ограничь её FEED_MAX "
        "из app/visibility.py — иначе один запрос вытянет всю базу."
    )
