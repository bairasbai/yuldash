"""Честность счёта до конца: компенсации, отмена, ожидание (аудит 2026-08-28).

Три вещи, которые в приложении разошлись со своими же обещаниями:

  1. Чек писал водителю «с компенсаций комиссия не берётся» и перечислял три строки,
     а расчёт комиссии вычитал только две — зимняя дорога облагалась. Приложение врало
     человеку в документе, и это хуже, чем просто ошибиться в формуле.
  2. Пассажир отменял у подъезда — водителю записывали 70 ₽ подачи по тарифу, а компенсация
     за 20 км дороги к нему исчезала. Наш главный принцип «бензин водителя — святое»
     отключался ровно там, где он нужнее всего.
  3. Настройка обещала потолок ожидания «по одному ЗАКАЗУ», а применялся он к каждой
     остановке отдельно: три остановки давали 900 ₽ вместо 300 ₽.
"""
from sqlmodel import Session

from app import compensation as comp_mod
from app import debt as debt_mod
from app import instant_service as isv
from app import promo_ride
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, Tariff
from app.timeutil import utcnow

from test_instant import _offered_order, fake_redis  # noqa: F401 — фикстура реэкспортом

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


def _order(price_rub: int, *, pickup_kop=0, options_kop=0, weather_kop=0,
           tariff_id=None, **extra) -> InstantOrder:
    """Заказ прямо в памяти: тестируем формулы денег, а не путь по базе."""
    return InstantOrder(
        passenger_id=1, driver_id=2,
        from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
        status=InstantOrderStatus.done, price_estimate=price_rub, price_final=price_rub,
        pickup_fee_kop=pickup_kop, options_fee_kop=options_kop, weather_fee_kop=weather_kop,
        tariff_id=tariff_id, **extra,
    )


# ==================== 1. Компенсации: один список на всё приложение ====================
def test_commission_skips_the_winter_road_too(client):
    """С зимней дороги комиссия не берётся — как и обещано в чеке.

    Было: чек говорил «с компенсаций 100 ₽ не берём», а комиссия считалась с 400 ₽ вместо
    300 ₽ — то есть мы забирали 15 ₽ ровно с той сотни, про которую написали «не забираем».
    """
    o = _order(400, weather_kop=10000)                 # поездка 300 + зимняя дорога 100
    assert debt_mod.order_commission_kop(o, 15.0) == debt_mod.fee_kop_for(300 * 100, 15.0)


def test_promo_discount_does_not_eat_the_winter_road(client):
    """Потолок скидки по промокоду считается от поездки, а не от компенсаций водителя."""
    o = _order(400, weather_kop=10000)
    assert promo_ride.discountable_rub(o) == 300


def test_all_three_compensations_are_counted_the_same(client):
    """Чек, комиссия и промокод берут ОДИН список компенсаций."""
    o = _order(1000, pickup_kop=20000, options_kop=15000, weather_kop=10000)   # 200+150+100
    assert isv.order_compensation_rub(o) == 450
    assert comp_mod.compensation_rub(o) == 450
    assert promo_ride.discountable_rub(o) == 1000 - 450
    assert debt_mod.order_commission_kop(o, 15.0) == debt_mod.fee_kop_for(550 * 100, 15.0)


def test_money_paths_use_the_single_source(client):
    """Сторож: никто не переписывает список компенсаций своими руками.

    Проверка идёт по исходникам, потому что баг был именно такой — не в формуле, а в копии
    списка, которая однажды отстала. Формулы такое не ловят: каждая по отдельности верна.
    """
    import pathlib
    root = pathlib.Path(__file__).resolve().parents[1] / "app"
    for name in ("debt.py", "promo_ride.py", "instant_service.py", "ledger.py"):
        src = (root / name).read_text(encoding="utf-8")
        # Складывать pickup_fee_kop с options_fee_kop вручную больше нельзя нигде.
        bad = 'getattr(order, "pickup_fee_kop", 0) or 0)\n' in src and "comp_mod" not in src
        assert not bad, f"{name} снова считает компенсации своим списком — зови compensation.py"
    assert set(comp_mod.COMPENSATION_FIELDS) == {
        "pickup_fee_kop", "options_fee_kop", "weather_fee_kop"
    }, "список компенсаций изменился — проверь чек, комиссию и промокод разом"


# ==================== 2. Отмена возвращает водителю бензин ====================
def _tariff_id(base_rub: int = 70) -> int:
    with Session(engine) as s:
        t = s.exec(isv.select(Tariff).where(Tariff.zone == "city",
                                            Tariff.category == "standard")).first()
        assert t is not None, "в базе нет городского тарифа"
        t.base = base_rub
        s.add(t)
        s.commit()
        return t.id


def test_cancel_fee_includes_the_drive_to_the_passenger(client):
    """Отмена у подъезда: водителю записывают подачу ПЛЮС его дорогу к пассажиру.

    20 км в село — это 240 ₽ сожжённого бензина, и пассажир видел эту строку в цене,
    когда заказывал. Раньше при отмене оставалось 70 ₽ и 40 км порожняка за свой счёт.
    """
    tid = _tariff_id(70)
    o = _order(500, pickup_kop=24000, tariff_id=tid)
    with Session(engine) as s:
        assert isv.cancel_fee_with_pickup_kop(s, o) == 7000 + 24000


def test_cancel_fee_ignores_seat_and_winter(client):
    """Кресло и зимняя дорога в отмену не входят: кресло не пригодилось, дорогу он не проехал."""
    tid = _tariff_id(70)
    o = _order(600, pickup_kop=24000, options_kop=15000, weather_kop=10000, tariff_id=tid)
    with Session(engine) as s:
        assert isv.cancel_fee_with_pickup_kop(s, o) == 7000 + 24000


def test_cancel_is_still_free_before_the_driver_arrives(client):
    """Пока водитель не нажал «Я на месте» — отмена бесплатна, как и была."""
    tid = _tariff_id(70)
    o = _order(500, pickup_kop=24000, tariff_id=tid)
    o.accepted_at = utcnow() - __import__("datetime").timedelta(minutes=30)
    o.waiting_started_at = None
    with Session(engine) as s:
        assert isv.passenger_cancel_fee_kop(s, o) == 0


# ==================== 3. Ожидание: потолок на ЗАКАЗ ====================
def test_waiting_cap_is_per_order_not_per_stop(client):
    """Три долгих остановки не дают трижды потолок.

    В настройках написано «максимум за ожидание по одному ЗАКАЗУ». Считалось по каждой
    остановке отдельно, и суммы складывались — обещание не выполнялось.
    """
    from datetime import timedelta
    o = _order(300)
    o.waiting_fee_kop = 0
    started = utcnow() - timedelta(hours=3)
    for _ in range(3):
        isv.add_waiting_fee(o, started, utcnow())
    assert o.waiting_fee_kop == settings.wait_fee_cap_rub * 100


def test_waiting_adds_up_below_the_cap(client):
    """Ниже потолка промежутки честно складываются."""
    from datetime import timedelta
    o = _order(300)
    o.waiting_fee_kop = 0
    now = utcnow()
    # Два промежутка по 10 минут: платных по 5 сверх бесплатных.
    isv.add_waiting_fee(o, now - timedelta(minutes=10), now)
    isv.add_waiting_fee(o, now - timedelta(minutes=10), now)
    billable = 10 - settings.wait_free_minutes
    assert o.waiting_fee_kop == 2 * billable * settings.wait_fee_rub_per_min * 100


def test_waiting_minute_is_not_cheaper_than_driving(client):
    """Час ожидания должен стоить хотя бы как час работы — иначе стоянку оплачивает водитель."""
    assert settings.wait_fee_rub_per_min >= 7, (
        "ожидание дешевле 7 ₽/мин — это меньше 420 ₽ в час, водитель ждёт себе в убыток"
    )


def test_open_stop_is_settled_when_the_ride_ends(client, user_factory, fake_redis):
    """Водитель нажал «Стоим» и завершил поездку, не нажав «Поехали».

    Раньше это время просто пропадало: человек стоял и ждал, а деньги не доставались никому.
    Дверей две — «завершить здесь» и обычное завершение, — и чинить надо обе: до 28.08 была
    залатана только первая.
    """
    from datetime import timedelta

    from sqlmodel import Session as _S

    from app.db import engine as _engine
    from app.models import InstantOrder as _IO

    d, pax, order = _offered_order(client, user_factory, fake_redis, "СтопВод", "СтопПас")
    oid = order["id"]
    for step in ("accept", "arrived", "onboard"):
        assert client.post(f"/instant/orders/{oid}/{step}", headers=d["auth"]).status_code == 200
    assert client.post(f"/instant/orders/{oid}/stop", headers=d["auth"]).status_code == 200

    # Стояли полчаса — сдвигаем начало стоянки назад, часы в тесте не ждём.
    with _S(_engine) as s:
        o = s.get(_IO, oid)
        o.stop_started_at = utcnow() - timedelta(minutes=30)
        s.add(o)
        s.commit()

    assert client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).status_code == 200
    with _S(_engine) as s:
        o = s.get(_IO, oid)
        платных = 30 - settings.wait_free_minutes
        assert o.stop_started_at is None, "стоянка осталась открытой после завершения"
        assert o.waiting_fee_kop == платных * settings.wait_fee_rub_per_min * 100
        assert o.price_final == o.price_estimate + o.waiting_fee_kop // 100, (
            "ожидание не попало в итоговую сумму"
        )


# ==================== 4. Отмена оплачивает ещё и ожидание ====================
def test_cancel_pays_the_waiting_too(client):
    """Водитель доехал и отждал — обе части оплачиваются.

    Счётчик ожидания закрывается только когда пассажир СЕЛ в машину, а при отмене и при
    «не вышел» он не сел ни разу: раньше эти минуты просто исчезали, хотя водитель отждал
    ровно столько же, сколько отждал бы в поездке (решение Александра, 2026-08-28).
    """
    from datetime import timedelta
    tid = _tariff_id(70)
    o = _order(500, pickup_kop=10000, tariff_id=tid)      # дорога водителя 100 ₽
    o.waiting_started_at = utcnow() - timedelta(minutes=13)
    with Session(engine) as s:
        платных = 13 - settings.wait_free_minutes
        ожидание = платных * settings.wait_fee_rub_per_min * 100
        assert isv.cancel_fee_with_pickup_kop(s, o) == 7000 + 10000 + ожидание


def test_no_show_pays_the_waiting_too(client):
    """«Пассажир не вышел» — тот же счёт: водитель сделал всё, что от него зависело."""
    from datetime import timedelta
    tid = _tariff_id(70)
    o = _order(500, pickup_kop=10000, tariff_id=tid)
    o.waiting_started_at = utcnow() - timedelta(minutes=8)
    with Session(engine) as s:
        платных = 8 - settings.wait_free_minutes
        assert isv.cancel_fee_with_pickup_kop(s, o) == (
            7000 + 10000 + платных * settings.wait_fee_rub_per_min * 100)


def test_cancel_is_capped_by_the_pickup_line_the_person_saw(client):
    """Отмена не может стоить больше строки «дорога водителя», которую человек видел до заказа.

    Без потолка на дальней подаче в 60 км отмена вылетала бы за 700 ₽ — это уже не
    компенсация, а капкан (решение Александра, 2026-08-28).
    """
    from datetime import timedelta
    tid = _tariff_id(70)
    o = _order(2000, pickup_kop=40000, tariff_id=tid)     # подача уже на потолке города
    o.waiting_started_at = utcnow() - timedelta(hours=2)  # и сверху куча ожидания
    with Session(engine) as s:
        t = s.get(Tariff, tid)
        assert isv.cancel_fee_with_pickup_kop(s, o) == int(t.pickup_max_rub) * 100


def test_cancel_without_a_tariff_is_not_capped_to_zero(client):
    """Тариф без настроенной подачи не должен обнулять отмену: потолка просто нет."""
    from datetime import timedelta
    o = _order(300, tariff_id=None)
    o.waiting_started_at = utcnow() - timedelta(minutes=10)
    with Session(engine) as s:
        assert isv.cancel_fee_with_pickup_kop(s, o) > 0


# ==================== 5. Бесплатных минут стало три ====================
def test_free_waiting_is_three_minutes(client):
    assert settings.wait_free_minutes == 3


def test_no_show_button_still_opens_on_the_eighth_minute(client):
    """Бесплатных минут стало меньше, но кнопка «пассажир не вышел» осталась на 8-й.

    Шесть минут — мало для женщины с ребёнком и сумками зимой, а эта кнопка ставит
    человеку страйк. Поэтому запас вырос с 3 до 5 минут (решение Александра).
    """
    from datetime import timedelta
    o = _order(300)
    o.waiting_started_at = utcnow()
    доступна = isv.no_show_available_at(o)
    assert доступна - o.waiting_started_at == timedelta(minutes=8)
