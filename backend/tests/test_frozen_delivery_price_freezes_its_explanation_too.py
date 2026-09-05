"""Волна 207: замороженная цена доставки должна замораживать и свою расшифровку.

Заморозка цены (`app/price_freeze.py`) существует ради одного обещания: человек видел
450 ₽, полминуты думал — и платит 450 ₽, а не пересчитанные 495 ₽. У такси и у доставки
свои списки замораживаемых полей, и в самом модуле написано, почему список должен быть
полным:

    «Заморозить половину цены значит собрать заказ из двух разных расчётов»
    «Смешать замороженную сумму с новой расшифровкой значит показать в чеке строки,
     которые не складываются в итог»

У такси договор соблюдён: `price_fields()` читает ровно те ключи, что заморожены.
У доставки — нет. Ответ на создание заказа отдаёт человеку `breakdown` — вложенную
расшифровку («доставка 200 ₽ + дорога 60 ₽ + зимняя дорога 40 ₽»), и её в списке
заморозки НЕТ. Значит итог приезжает из старого дешёвого расчёта, а слагаемые под ним —
из нового дорогого.

Человек видит: «к оплате 300 ₽», под этим строки на 380 ₽. Складывает глазами — не сходится.
Ровно то, ради чего расшифровку и делали.
"""
from __future__ import annotations

import json

import fakeredis

from app import price_freeze as pf
from app.config import settings

ОТКУДА = (52.591, 58.317)
КУДА = (52.716, 58.664)
ПОДПИСЬ = "m|regular|simple"


def _дешёвый() -> dict:
    """Цена, которую человек увидел минуту назад."""
    return {
        "price_kop": 30_000, "commission_kop": 2_400, "distance_km": 12.0, "zone": "city",
        "delivery_kop": 24_000, "pickup_kop": 6_000, "pickup_pending": False,
        "pickup_max_kop": 40_000, "weather_kop": 0, "weather_kind": "", "night_k": 1.0,
        "breakdown": {"delivery_kop": 24_000, "pickup_kop": 6_000, "weather_kop": 0,
                      "night_k": 1.0, "commission_percent": 8.0},
    }


def _дорогой() -> dict:
    """Пересчёт через полминуты: пошёл снег, добавилась зимняя дорога."""
    return {
        "price_kop": 38_000, "commission_kop": 3_040, "distance_km": 12.0, "zone": "city",
        "delivery_kop": 24_000, "pickup_kop": 6_000, "pickup_pending": False,
        "pickup_max_kop": 40_000, "weather_kop": 8_000, "weather_kind": "snow", "night_k": 1.0,
        "breakdown": {"delivery_kop": 24_000, "pickup_kop": 6_000, "weather_kop": 8_000,
                      "night_k": 1.0, "commission_percent": 8.0},
    }


def _redis_с_замороженной_ценой(monkeypatch):
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    pf.remember_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дешёвый())
    return r


def _сходится(цена: dict) -> bool:
    """Складываются ли строки расшифровки в итог (без комиссии — её платит курьер)."""
    b = цена["breakdown"]
    сумма = int(b["delivery_kop"]) + int(b["pickup_kop"]) + int(b["weather_kop"])
    return сумма == int(цена["price_kop"])


def test_frozen_price_keeps_its_own_breakdown(monkeypatch):
    """Главное: взяли замороженный итог — значит и строки под ним замороженные."""
    r = _redis_с_замороженной_ценой(monkeypatch)

    итог = pf.apply_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дорогой())

    assert итог["price_kop"] == 30_000, "заморозка вообще не сработала — проба бессмысленна"
    assert итог["breakdown"]["weather_kop"] == 0, (
        f"итог замороженный (300 ₽), а расшифровка из нового расчёта "
        f"(зимняя дорога {итог['breakdown']['weather_kop'] / 100:g} ₽)"
    )
    assert _сходится(итог), (
        f"строки не складываются в итог: {итог['breakdown']} против "
        f"{итог['price_kop'] / 100:g} ₽"
    )


def test_new_cheaper_price_keeps_its_own_breakdown(monkeypatch):
    """Обратная сторона: подешевело — берём новый расчёт ЦЕЛИКОМ, вместе с его строками."""
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    pf.remember_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дорогой())     # запомнили дорогую

    итог = pf.apply_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дешёвый())  # стало дешевле

    assert итог["price_kop"] == 30_000, "взяли дорогую замороженную вместо дешёвой новой"
    assert итог["breakdown"]["weather_kop"] == 0
    assert _сходится(итог)
    assert not итог.get("price_was_frozen"), "цена не замораживалась — пометки быть не должно"


def test_freeze_marks_itself_for_the_human(monkeypatch):
    """Заморозка обязана представиться: по этой пометке человеку и говорят «цена та же»."""
    r = _redis_с_замороженной_ценой(monkeypatch)
    итог = pf.apply_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дорогой())
    assert итог.get("price_was_frozen") is True


def test_another_size_is_another_price(monkeypatch):
    """Заморозка привязана к подписи заказа: сменил размер — цена считается заново.

    Иначе человек переключил «маленькая» на «большая» и получил бы цену маленькой.
    """
    r = _redis_с_замороженной_ценой(monkeypatch)

    итог = pf.apply_courier(r, 7, ОТКУДА, КУДА, "l|express|buy_bring", _дорогой())

    assert итог["price_kop"] == 38_000, "чужая замороженная цена подошла к другому заказу"
    assert _сходится(итог)


def test_broken_redis_does_not_break_the_order(monkeypatch):
    """Заморозка не имеет права мешать человеку заказать: Redis молчит — считаем заново."""
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    итог = pf.apply_courier(None, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дорогой())
    assert итог == _дорогой()


def test_corrupted_snapshot_falls_back_to_the_new_price(monkeypatch):
    """В памяти мусор — берём новый расчёт целиком, а не половину."""
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    r.set(pf._courier_key(7, ОТКУДА, КУДА, ПОДПИСЬ), "не json")

    итог = pf.apply_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дорогой())

    assert итог == _дорогой()


def test_frozen_snapshot_covers_everything_the_order_reads():
    """Сторож: всё, что создание доставки берёт из расчёта, обязано быть в списке заморозки.

    Так дыра и появилась: в ответ человеку добавили `breakdown`, а в список заморозки
    его добавить забыли — списки живут в разных файлах.
    """
    import pathlib
    import re

    исходник = (pathlib.Path("app") / "routers" / "courier.py").read_text(encoding="utf-8")
    начало = исходник.index("def courier_order_create")
    читает = set(re.findall(r'priced\["([a-z_]+)"\]', исходник[начало:]))

    не_заморожены = читает - set(pf.FROZEN_COURIER_KEYS)
    assert not не_заморожены, (
        f"создание доставки читает из расчёта поля, которых нет в заморозке: "
        f"{sorted(не_заморожены)}. Заказ соберётся из двух разных расчётов"
    )


def test_the_guard_itself_notices_a_missing_field():
    """Сторож обязан уметь краснеть (урок волны 204)."""
    читает = {"price_kop", "breakdown", "выдуманное_поле"}
    assert читает - set(pf.FROZEN_COURIER_KEYS) == {"выдуманное_поле"} or (
        "breakdown" not in pf.FROZEN_COURIER_KEYS
    ), "проверка списка не отличает известное поле от неизвестного"


def test_the_json_snapshot_survives_a_nested_breakdown(monkeypatch):
    """Расшифровка вложенная — она обязана пережить сохранение и чтение из памяти."""
    r = _redis_с_замороженной_ценой(monkeypatch)
    сырое = r.get(pf._courier_key(7, ОТКУДА, КУДА, ПОДПИСЬ))
    снимок = json.loads(сырое)

    assert "breakdown" in снимок, "расшифровка не попала в снимок — замораживать нечего"
    assert снимок["breakdown"]["weather_kop"] == 0


def test_switch_off_really_switches_off(monkeypatch):
    """Заморозка выключена настройкой — старый снимок не должен применяться.

    `price_freeze_sec = 0` это рубильник: Александр выключает заморозку, а в памяти ещё
    лежат снимки последних минут. Если их продолжать применять, рубильник не работает,
    и понять это по логам нельзя.
    """
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    pf.remember_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дешёвый())

    monkeypatch.setattr(settings, "price_freeze_sec", 0, raising=False)   # рубильник вниз
    итог = pf.apply_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, _дорогой())

    assert итог == _дорогой(), "заморозка выключена, а старая цена всё равно применилась"


def test_snapshot_keeps_only_the_money_fields(monkeypatch):
    """В снимок идут ТОЛЬКО денежные поля из списка, а не весь расчёт целиком.

    Расчёт несёт и служебное — сколько секунд держим цену, пометки для клиента. Клади
    в память всё подряд, и однажды туда попадёт то, что не переживёт сохранения, а заморозка
    молча перестанет работать: ошибку глушит `except`.
    """
    monkeypatch.setattr(settings, "price_freeze_sec", 90, raising=False)
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    расчёт = dict(_дешёвый())
    расчёт["price_locked_sec"] = 90          # служебное, в цену не входит
    расчёт["служебное"] = "не для памяти"
    pf.remember_courier(r, 7, ОТКУДА, КУДА, ПОДПИСЬ, расчёт)

    снимок = json.loads(r.get(pf._courier_key(7, ОТКУДА, КУДА, ПОДПИСЬ)))

    лишние = set(снимок) - set(pf.FROZEN_COURIER_KEYS)
    assert not лишние, f"в снимок попало лишнее: {sorted(лишние)}"
