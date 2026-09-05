"""Волна 205: расшифровка платной отмены после отмены обязана совпадать со списанным.

Расшифровку («60 ₽ подача + 240 ₽ дорога водителя + 25 ₽ ожидание») завели ровно затем,
чтобы человек не читал одну сумму как «нас обобрали». В коде так и написано: показываем
её ДО тапа и в чеке ПОСЛЕ.

Но считает её одна и та же функция, и считает она «прямо сейчас». До отмены это правильно:
человек должен видеть, во сколько ему обойдётся тап в эту секунду. После отмены — нет:
счётчик ожидания у отменённого заказа никто не останавливает, и цифра в чеке продолжает
расти вместе с часами, пока не упрётся в потолок.

Что видит человек. Отменил, с него зафиксировали 130 ₽. Через час открыл поездку — в чеке
390 ₽. Он уверен, что его обманули, и по-своему прав: приложение показывает ему число,
которого никто не брал.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, Tariff, UserRole
from app.timeutil import utcnow

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _тариф(s: Session) -> Tariff:
    """СВОЙ тариф для этой пробы — неактивный, чтобы не влиять на остальной прогон.

    Первая версия искала общий городской тариф и заводила его, если не нашла. Так тест
    начинал определять цены всем, кто идёт после него: `active_tariff` выбирает первую
    активную строку зоны, и моя оказывалась ею. Это ровно тот класс, из-за которого
    у нас уже есть плавающие тесты (аудит 2026-08-08, волна 206).

    `active=False` + своя категория: `session.get` по номеру такой тариф находит,
    а `active_tariff` — никогда.
    """
    t = Tariff(zone="city", category="проба-чека", base=60, per_km=12, per_min=4,
               min_price=100, active=False,
               # Подача и потолок — явно: раньше они приезжали из чужого общего тарифа,
               # и проба молча зависела от того, кто отработал до неё.
               pickup_free_km=3.0, pickup_per_km=11.5, pickup_max_rub=400)
    s.add(t)
    s.commit()
    s.refresh(t)
    return t


def _заказ_на_отмену(user_factory, минут_ожидания: int = 5):
    """Водитель принял, приехал, нажал «Я на месте» и ждёт. Бесплатное окно позади."""
    водитель = user_factory("ЧекВодитель", role=UserRole.driver)
    пассажир = user_factory("ЧекПассажир")
    сейчас = utcnow()
    with Session(engine) as s:
        t = _тариф(s)
        o = InstantOrder(
            passenger_id=пассажир["id"], driver_id=водитель["id"],
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            status=S.arriving, price_estimate=300, tariff_id=t.id,
            created_at=сейчас - timedelta(minutes=30),
            accepted_at=сейчас - timedelta(minutes=20),
            waiting_started_at=сейчас - timedelta(minutes=минут_ожидания),
            pickup_fee_kop=24_000,          # дальняя подача: водитель ехал 20 км
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        return водитель, пассажир, o.id, сейчас


def test_receipt_after_cancel_equals_what_was_charged(client, user_factory):
    """Главное: через час после отмены в чеке то же число, что списали."""
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        списано = isv.cancel_fee_with_pickup_kop(s, o, сейчас)
        assert списано > 0, "проба бессмысленна: отмена оказалась бесплатной"
        o.cancel_fee_kop = списано
        o.status = S.cancelled
        s.add(o)
        s.commit()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        чек = isv.cancel_fee_parts_kop(s, o, сейчас + timedelta(hours=1))

    assert чек["total_kop"] == списано, (
        f"списали {списано / 100:g} ₽, а в чеке через час {чек['total_kop'] / 100:g} ₽ — "
        "счётчик ожидания у отменённого заказа продолжает крутиться"
    )


def test_receipt_before_the_tap_is_still_live(client, user_factory):
    """Защита не сломана: ДО отмены расшифровка обязана считаться на сейчас.

    Человек смотрит на неё, решая, отменять ли. Заморозить её здесь — значит показать
    вчерашнюю цену сегодняшнему тапу.
    """
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        сейчас_же = isv.cancel_fee_parts_kop(s, o, сейчас)
        через_полчаса = isv.cancel_fee_parts_kop(s, o, сейчас + timedelta(minutes=30))

    assert через_полчаса["waiting_kop"] > сейчас_же["waiting_kop"], (
        "заказ ещё живой, а расшифровка перестала считать набежавшее ожидание"
    )


def test_receipt_parts_add_up_to_the_total(client, user_factory):
    """Слагаемые должны сходиться с итогом — иначе объяснение не объясняет."""
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        чек = isv.cancel_fee_parts_kop(s, o, сейчас)

    сумма = чек["base_kop"] + чек["pickup_kop"] + чек["waiting_kop"]
    if чек["capped"]:
        assert чек["total_kop"] == чек["cap_kop"], "урезали не до потолка"
        assert сумма > чек["cap_kop"]
    else:
        assert чек["total_kop"] == сумма, (
            f"итог {чек['total_kop']} не равен сумме строк {сумма}: "
            "человек складывает их глазами и не сходится"
        )


def test_receipt_equals_charge_for_no_show_too(client, user_factory):
    """Вторая дверь: «пассажир не вышел» — деньги берут по тому же правилу."""
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory, минут_ожидания=15)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        списано = isv.cancel_fee_with_pickup_kop(s, o, сейчас)
        o.cancel_fee_kop = списано
        o.no_show = True
        o.status = S.cancelled
        s.add(o)
        s.commit()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        чек = isv.cancel_fee_parts_kop(s, o, сейчас + timedelta(hours=3))

    assert чек["total_kop"] == списано, (
        f"после «пассажир не вышел» списали {списано / 100:g} ₽, "
        f"а в чеке {чек['total_kop'] / 100:g} ₽"
    )


def test_free_cancel_shows_no_charge_in_the_receipt(client, user_factory):
    """Отмена была бесплатной — в чеке ноль, а не «сколько было бы»."""
    водитель = user_factory("ЧекБесплатноВод", role=UserRole.driver)
    пассажир = user_factory("ЧекБесплатноПас")
    сейчас = utcnow()
    with Session(engine) as s:
        t = _тариф(s)
        o = InstantOrder(
            passenger_id=пассажир["id"], driver_id=водитель["id"],
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            status=S.cancelled, price_estimate=300, tariff_id=t.id,
            created_at=сейчас - timedelta(minutes=10),
            accepted_at=сейчас - timedelta(minutes=1),   # внутри бесплатного окна
            waiting_started_at=None,                     # «Я на месте» не нажимали
            cancel_fee_kop=0,
        )
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        чек = isv.cancel_fee_parts_kop(s, o, сейчас + timedelta(hours=2))

    assert чек["total_kop"] == 0, (
        f"отмена была бесплатной, а в чеке {чек['total_kop'] / 100:g} ₽"
    )


def test_cancel_free_window_is_really_free(client, user_factory):
    """Опора: внутри бесплатного окна штрафа нет вовсе."""
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory)
    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.accepted_at = сейчас - timedelta(seconds=10)   # только что приняли
        s.add(o)
        s.commit()
        assert isv.passenger_cancel_fee_kop(s, o, сейчас) == 0
    assert settings.cancel_free_minutes > 0


def test_closed_receipt_lines_are_frozen_too(client, user_factory):
    """Не только итог: строки чека закрытого заказа тоже не должны шевелиться.

    Иначе итог верный, а «ожидание» под ним растёт — и человек снова видит цифры,
    которые между собой не сходятся.
    """
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        списано = isv.cancel_fee_with_pickup_kop(s, o, сейчас)
        o.cancel_fee_kop = списано
        o.status = S.cancelled
        s.add(o)
        s.commit()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        сразу = isv.cancel_fee_parts_kop(s, o, сейчас)
        через_час = isv.cancel_fee_parts_kop(s, o, сейчас + timedelta(hours=1))

    assert сразу == через_час, (
        f"строки чека поехали за час: {сразу} → {через_час}"
    )
    assert (сразу["base_kop"] + сразу["pickup_kop"] + сразу["waiting_kop"]
            == сразу["total_kop"]), "строки не сходятся с итогом"


def test_closed_receipt_lines_never_exceed_the_charge(client, user_factory):
    """Списали меньше, чем стоит одна подача (урезал потолок) — строки не должны врать вверх.

    Человек складывает строки глазами. Если «подача 70 ₽» стоит в чеке на 50 ₽, он видит
    в приложении заведомую неправду о собственных деньгах.
    """
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        o.cancel_fee_kop = 5_000          # 50 ₽: меньше, чем подача по тарифу
        o.status = S.cancelled
        s.add(o)
        s.commit()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        чек = isv.cancel_fee_parts_kop(s, o, сейчас)

    assert чек["total_kop"] == 5_000
    assert чек["base_kop"] <= 5_000, f"подача {чек['base_kop']} больше списанного"
    assert (чек["base_kop"] + чек["pickup_kop"] + чек["waiting_kop"]) == 5_000, (
        f"строки {чек} в сумме не дают списанные 50 ₽"
    )
    assert min(чек["base_kop"], чек["pickup_kop"], чек["waiting_kop"]) >= 0, (
        "в чеке появилась отрицательная строка"
    )


def test_live_receipt_is_capped(client, user_factory):
    """Живой расчёт обязан упираться в потолок дороги: отмена не может стоить больше,
    чем человек видел ДО заказа."""
    _drv, _pax, oid, сейчас = _заказ_на_отмену(user_factory, минут_ожидания=200)

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        чек = isv.cancel_fee_parts_kop(s, o, сейчас)
        потолок = чек["cap_kop"]

    assert потолок > 0, "у тарифа нет потолка — проба бессмысленна"
    assert чек["capped"] is True, f"строки {чек} переросли потолок, но урезания нет"
    assert чек["total_kop"] == потолок, (
        f"итог {чек['total_kop'] / 100:g} ₽ выше потолка {потолок / 100:g} ₽"
    )
