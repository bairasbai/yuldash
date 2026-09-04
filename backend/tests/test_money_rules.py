"""Тесты батча B3 «Деньги-тонкости» (волна 2, §5 Деньги + §6 Классы). Деньги — критично,
покрываем плотнее, чем кажется нужным:

1) Комиссия лесенкой 3/8/15: границы 30/100 ПОЕЗДОК, промо запуска вкл/выкл,
   нулевая комиссия долг не создаёт.
2) Сурж: ступени по спрос/предложение, потолок ×1.5 (и конфигом ниже), без Redis k=1,
   k фиксируется на заказе, прозрачная плашка (surge_note).
3) Отмены/ожидание (Модель А = страйки, денег не двигаем): свободное окно 3 мин,
   платная отмена (= подача) после, ожидание 5 мин бесплатно → +5 ₽/мин,
   price_final = estimate + ожидание, no-show тайминги, страйки → пауза → истечение,
   водительская отмена без штрафа пассажиру.
4) Классы Эконом/Комфорт: сид тарифов, estimate дороже + options, matcher фильтрует по классу,
   заявка/approve выставляют car_class.
"""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app.db import engine
from app import debt as debt_mod
from app import instant_service as isv
from app.config import settings
from app.models import (
    DriverProfile, InstantOrder, InstantOrderStatus as S, Tariff, User, UserRole,
)
from app.timeutil import utcnow

ORIG = (52.591, 58.317)    # Баймак — точка А (город)
DEST = (52.716, 58.664)    # Сибай — точка Б (город, ~30 км)
UFA = (54.735, 55.958)     # отдельная «сурж-зона» — не мешаем другим тестам

CITY_BASE_KOP = 70 * 100   # подача городского тарифа Эконом (сид), копейки


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="MrDrv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _heartbeat(client, d, coord=ORIG):
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})
    assert r.status_code == 200, r.text
    return r


def _order_body(frm=ORIG, to=DEST, **extra):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай", **extra}


def _offered_order(client, user_factory, dname, pname, **extra):
    d = _driver_online(client, user_factory, dname)
    _heartbeat(client, d)
    pax = user_factory(pname)
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body(**extra)).json()
    assert order["status"] == "offered", order
    return d, pax, order


def _shift(order_id: int, **fields):
    """Сдвинуть таймстампы заказа в БД (эмуляция прошедшего времени)."""
    with Session(engine) as s:
        o = s.get(InstantOrder, order_id)
        for k, v in fields.items():
            setattr(o, k, v)
        s.add(o)
        s.commit()


def _db_order(order_id: int) -> InstantOrder:
    with Session(engine) as s:
        return s.get(InstantOrder, order_id)


def _seed_done_order(driver_id: int, passenger_id: int, done_days_ago: int) -> None:
    """Прошлый завершённый заказ водителя N дней назад."""
    _seed_done_trips(driver_id, passenger_id, 1, done_days_ago=done_days_ago)


def _seed_done_trips(driver_id: int, passenger_id: int, count: int, done_days_ago: int = 1) -> None:
    """N завершённых поездок водителя в прошлом — позиция на лесенке комиссии.

    Лесенка считается по ПОЕЗДКАМ (решение 2026-08-23), поэтому тестам нужна именно пачка,
    а не одна старая поездка.
    """
    if count <= 0:
        return
    base = utcnow() - timedelta(days=done_days_ago)
    with Session(engine) as s:
        for i in range(count):
            s.add(InstantOrder(
                passenger_id=passenger_id, driver_id=driver_id,
                from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                status=S.done, price_estimate=100, price_final=100,
                done_at=base + timedelta(seconds=i),
            ))
        s.commit()


# ==================== 1. Комиссия лесенкой 3/8/15 по поездкам ====================
def test_fee_first_order_is_tier1(client, user_factory):
    """Новичок (нет прошлых done) платит 1-ю ступень — 3%."""
    d = user_factory("Lad0", role=UserRole.driver)
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, d["id"]) == settings.fee_tier1_percent


@pytest.mark.parametrize("trips,expected_attr", [
    (29, "fee_tier1_percent"),    # едет 30-ю поездку — ещё 3%
    (30, "fee_tier2_percent"),    # 31-я — уже 8%
    (99, "fee_tier2_percent"),    # 100-я — ещё 8%
    (100, "service_fee_percent"),  # 101-я и дальше — навсегда 15%
])
def test_fee_ladder_boundaries(client, user_factory, trips, expected_attr):
    """Границы лесенки — по числу УЖЕ завершённых поездок, календарь ни при чём."""
    d = user_factory(f"Lad{trips}", role=UserRole.driver)
    pax = user_factory(f"LadPax{trips}")
    _seed_done_trips(d["id"], pax["id"], trips)
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, d["id"]) == getattr(settings, expected_attr)


def test_fee_ladder_applies_to_debt_amount(client, user_factory, fake_redis):
    """Интеграция: водитель с 40 поездками за спиной получает долг 8% (а не 3% и не 15%)."""
    d, pax, order = _offered_order(client, user_factory, "LadIntDrv", "LadIntPax")
    _seed_done_trips(d["id"], pax["id"], 40)
    oid = order["id"]
    for path in ("accept", "arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"]).status_code == 200
    done = _db_order(oid)
    with Session(engine) as s:
        from app.models import CommissionDebt
        row = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()
    assert row is not None
    assert row.amount_kop == round(done.price_final * 100 * settings.fee_tier2_percent / 100)


def test_launch_promo_zero_percent_no_debt(client, user_factory, fake_redis, monkeypatch):
    """Промо запуска: одобрен до launch_promo_until → 0% → долг НЕ создаётся."""
    monkeypatch.setattr(settings, "launch_promo_until",
                        (utcnow() + timedelta(days=30)).date().isoformat())
    monkeypatch.setattr(settings, "launch_promo_percent", 0.0)
    d, pax, order = _offered_order(client, user_factory, "PromoDrv", "PromoPax")
    oid = order["id"]
    for path in ("accept", "arrived", "onboard", "done"):
        client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"])
    with Session(engine) as s:
        from app.models import CommissionDebt
        assert s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first() is None


def test_launch_promo_custom_percent(client, user_factory, monkeypatch):
    """Промо с ненулевым процентом перекрывает лесенку на промо-период."""
    monkeypatch.setattr(settings, "launch_promo_until",
                        (utcnow() + timedelta(days=1)).date().isoformat())
    monkeypatch.setattr(settings, "launch_promo_percent", 1.0)
    d = user_factory("Promo1", role=UserRole.driver)
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, d["id"]) == 1.0


def test_launch_promo_expired_window_back_to_ladder(client, user_factory, monkeypatch):
    """Промо-период (launch_promo_days) истёк → обычная лесенка, даже если одобрен в окно."""
    monkeypatch.setattr(settings, "launch_promo_until",
                        (utcnow() + timedelta(days=365)).date().isoformat())
    monkeypatch.setattr(settings, "launch_promo_percent", 0.0)
    d = user_factory("PromoOld", role=UserRole.driver)
    pax = user_factory("PromoOldPax")
    with Session(engine) as s:
        from app.models import TaxiApplication
        app = s.exec(select(TaxiApplication).where(TaxiApplication.user_id == d["id"])).first()
        app.reviewed_at = utcnow() - timedelta(days=settings.launch_promo_days + 1)
        s.add(app)
        s.commit()
    _seed_done_trips(d["id"], pax["id"], settings.fee_tier2_trips)
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, d["id"]) == settings.service_fee_percent


def test_promo_disabled_by_default(client, user_factory):
    """Пустая launch_promo_until (дефолт) → промо выключено, работает лесенка."""
    assert settings.launch_promo_until == ""
    d = user_factory("NoPromo", role=UserRole.driver)
    with Session(engine) as s:
        assert debt_mod.driver_fee_percent(s, d["id"]) == settings.fee_tier1_percent


# ============================ 2. Сурж ============================
def _presence(r, coord, n, start_id=90000):
    """n «живых» водителей в точке (только для _surge_supply — без юзеров в БД)."""
    for i in range(n):
        did = start_id + i
        r.geoadd(isv.PRESENCE_KEY, (coord[1], coord[0], f"driver:{did}"))
        r.set(f"presence:hb:{did}", "1", ex=60)


@pytest.fixture
def surge_demand(client, user_factory):
    """Фабрика спроса: n searching-заказов в точке. После теста гасим (общая БД сессии)."""
    made: list[int] = []
    pax = user_factory("SurgeDemandPax")

    def make(n, coord=UFA):
        with Session(engine) as s:
            for _ in range(n):
                o = InstantOrder(
                    passenger_id=pax["id"], status=S.searching,
                    from_lat=coord[0], from_lng=coord[1], to_lat=DEST[0], to_lng=DEST[1],
                )
                s.add(o)
                s.commit()
                made.append(o.id)
        return made

    yield make
    with Session(engine) as s:
        for oid in made:
            o = s.get(InstantOrder, oid)
            o.status = S.expired
            s.add(o)
        s.commit()


@pytest.mark.parametrize("demand,supply,expected_k", [
    (1, 2, 1.0),    # ratio 0.5 — спокойно
    (2, 2, 1.1),    # ratio 1
    (3, 2, 1.2),    # ratio 1.5
    (4, 2, 1.3),    # ratio 2
    (6, 2, 1.5),    # ratio 3 — потолок
    (20, 2, 1.5),   # ratio 10 — всё равно потолок ×1.5
    (2, 0, 1.3),    # предложения нет → знаменатель 1 → ratio 2
])
def test_surge_steps(client, fake_redis, surge_demand, demand, supply, expected_k):
    surge_demand(demand)
    _presence(fake_redis, UFA, supply)
    with Session(engine) as s:
        assert isv.surge_k_for(s, UFA[0], UFA[1]) == expected_k


def test_surge_cap_configurable(client, fake_redis, surge_demand, monkeypatch):
    """Потолок конфигом ниже ступени: surge_max_k=1.2 режет даже ratio≥3."""
    monkeypatch.setattr(settings, "surge_max_k", 1.2)
    surge_demand(6)
    _presence(fake_redis, UFA, 2)
    with Session(engine) as s:
        assert isv.surge_k_for(s, UFA[0], UFA[1]) == 1.2


def test_surge_disabled_by_flag(client, fake_redis, surge_demand, monkeypatch):
    monkeypatch.setattr(settings, "surge_enabled", False)
    surge_demand(6)
    with Session(engine) as s:
        assert isv.surge_k_for(s, UFA[0], UFA[1]) == 1.0


def test_surge_no_redis_is_one(client, surge_demand):
    """Без Redis (предложение неизвестно) k=1.0 — не наживаемся и не падаем."""
    surge_demand(6)
    with Session(engine) as s:
        assert isv.surge_k_for(s, UFA[0], UFA[1]) == 1.0


def test_surge_far_demand_ignored(client, fake_redis, surge_demand):
    """Спрос в другом городе (за пределами surge_radius_km) не поднимает k здесь."""
    surge_demand(6, coord=UFA)
    with Session(engine) as s:
        assert isv.surge_k_for(s, ORIG[0], ORIG[1]) == 1.0


def test_estimate_returns_surge_and_note(client, user_factory, fake_redis, surge_demand):
    """estimate: surge_k + прозрачная плашка «цена выше на N%» (RU/BA), цена уже с k."""
    surge_demand(4)                      # ratio 4/1 → k=1.5 (потолок)
    _presence(fake_redis, UFA, 1)
    pax = user_factory("SurgeEstPax")
    body = client.post("/instant/estimate", headers=pax["auth"],
                       json=_order_body(frm=UFA, to=(UFA[0] + 0.09, UFA[1]))).json()
    assert body["surge_k"] == 1.5
    assert body["surge_note"] is not None
    assert "50%" in body["surge_note"]["ru"] and "50%" in body["surge_note"]["ba"]
    # Цена честно умножена: пересчёт по формуле с тем же k.
    t_city = None
    with Session(engine) as s:
        t_city = s.exec(select(Tariff).where(
            Tariff.zone == "city", Tariff.category == "standard", Tariff.active == True)).first()  # noqa: E712
    from app.services import haversine_km
    dist = max(haversine_km(UFA[0], UFA[1], UFA[0] + 0.09, UFA[1]) * settings.instant_road_k, 0.5)
    eta = dist / settings.instant_avg_speed_kmh * 60
    expected = max(t_city.min_price,
                   isv.round_to_10((t_city.base + t_city.per_km * dist + t_city.per_min * eta) * t_city.k * 1.5))
    assert body["price"] == expected


def test_estimate_calm_no_note(client, user_factory, fake_redis):
    """Спокойный час: k=1.0, плашки нет."""
    pax = user_factory("CalmPax")
    body = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    assert body["surge_k"] == 1.0
    assert body["surge_note"] is None


def test_surge_fixed_on_order(client, user_factory, fake_redis, surge_demand):
    """k фиксируется на заказе при создании (price_estimate уже с ним) — задним числом не меняется."""
    surge_demand(4)
    _presence(fake_redis, UFA, 1)
    pax = user_factory("SurgeFixPax")
    order = client.post("/instant/orders", headers=pax["auth"],
                        json=_order_body(frm=UFA, to=(UFA[0] + 0.09, UFA[1]))).json()
    assert order["surge_k"] == 1.5
    assert _db_order(order["id"]).surge_k == 1.5


def test_poputka_untouched_by_surge(client, user_factory, fake_redis, surge_demand):
    """На ПОПУТКЕ суржа нет: публикация плановой поездки не зависит от спроса такси."""
    surge_demand(6)
    d = user_factory("PopSurge", role=UserRole.driver)
    ride = {"from_city": "Уфа", "to_city": "Сибай",
            "depart_at": (utcnow() + timedelta(days=1)).isoformat(), "seats_total": 3, "price": 500}
    r = client.post("/rides", headers=d["auth"], json=ride)
    assert r.status_code == 200 and r.json()["price"] == 500


# ============================ 3. Отмены / ожидание / страйки ============================
def test_cancel_free_within_window(client, user_factory, fake_redis):
    """≤3 мин от принятия — отмена бесплатна (даже если водитель уже на месте)."""
    d, pax, order = _offered_order(client, user_factory, "Cw1Drv", "Cw1Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={"reason": "передумал"})
    assert r.status_code == 200
    assert r.json()["cancel_fee_kop"] == 0 and r.json()["no_show"] is False


def test_cancel_free_if_driver_not_arrived(client, user_factory, fake_redis):
    """Водитель ещё не нажал «Я на месте» — бесплатно, сколько бы времени ни прошло."""
    d, pax, order = _offered_order(client, user_factory, "Cw2Drv", "Cw2Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    _shift(oid, accepted_at=utcnow() - timedelta(minutes=10))
    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["cancel_fee_kop"] == 0


def test_cancel_paid_after_window_and_arrival(client, user_factory, fake_redis):
    """>3 мин от принятия И водитель на месте → штраф = подача (фиксируем, денег не двигаем)."""
    d, pax, order = _offered_order(client, user_factory, "Cw3Drv", "Cw3Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    _shift(oid, accepted_at=utcnow() - timedelta(minutes=10))
    # Предупреждение ДО тапа: payload говорит, что отмена сейчас платная.
    view = client.get(f"/instant/orders/{oid}", headers=pax["auth"]).json()
    assert view["cancel_fee_now_kop"] == CITY_BASE_KOP
    r = client.post(f"/instant/orders/{oid}/cancel", headers=pax["auth"], json={"reason": "не жду"})
    assert r.status_code == 200
    body = r.json()
    assert body["cancel_fee_kop"] == CITY_BASE_KOP
    assert body["cancel_by"] == "passenger" and body["no_show"] is False
    # Модель А: никакого движения денег — ни ledger, ни долга по этому заказу.
    with Session(engine) as s:
        from app.models import CommissionDebt, LedgerEntry
        assert s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first() is None
        assert s.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).first() is None


def test_waiting_timer_starts_on_arrived(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, "WtDrv", "WtPax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    assert _db_order(oid).waiting_started_at is None
    body = client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"]).json()
    assert body["waiting_started_at"] is not None
    assert body["wait_free_min"] == settings.wait_free_minutes
    assert body["wait_fee_rub_per_min"] == settings.wait_fee_rub_per_min
    assert body["no_show_at"] is not None      # тайминг кнопки «пассажир не вышел»


def test_waiting_within_free_no_fee(client, user_factory, fake_redis):
    d, pax, order = _offered_order(client, user_factory, "Wt0Drv", "Wt0Pax")
    oid = order["id"]
    for path in ("accept", "arrived", "onboard", "done"):
        client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"])
    o = _db_order(oid)
    assert o.waiting_fee_kop == 0
    assert o.price_final == o.price_estimate


def test_waiting_over_free_charged_per_minute(client, user_factory, fake_redis):
    """8 полных минут ожидания при 5 бесплатных → 3 платных × 5 ₽ = 1500 коп; в price_final."""
    d, pax, order = _offered_order(client, user_factory, "Wt8Drv", "Wt8Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=8, seconds=30))
    client.post(f"/instant/orders/{oid}/onboard", headers=d["auth"])
    o = _db_order(oid)
    # Границу бесплатных минут берём из конфига: она уже менялась (5 → 3), и проверка,
    # зашитая цифрой, ловит не ошибку, а собственную несвежесть.
    платных = 8 - settings.wait_free_minutes   # ждали 8,5 мин; неполная минута — в пользу пассажира
    assert o.waiting_fee_kop == платных * settings.wait_fee_rub_per_min * 100
    done = client.post(f"/instant/orders/{oid}/done", headers=d["auth"]).json()
    assert done["price_final"] == o.price_estimate + платных * settings.wait_fee_rub_per_min
    assert done["waiting_fee_kop"] == o.waiting_fee_kop


def test_no_show_too_early_409(client, user_factory, fake_redis):
    """Кнопка «пассажир не вышел» до истечения 5+3 минут → 409."""
    d, pax, order = _offered_order(client, user_factory, "Ns1Drv", "Ns1Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"], json={"reason": "no_show"})
    assert r.status_code == 409


def test_no_show_requires_arrival(client, user_factory, fake_redis):
    """До «Я на месте» no-show невозможен (нечестно жать из другого района)."""
    d, pax, order = _offered_order(client, user_factory, "Ns2Drv", "Ns2Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"], json={"reason": "no_show"})
    assert r.status_code == 409


def test_no_show_after_timeout_ok(client, user_factory, fake_redis):
    """5 бесплатных + 3 сверх → no-show проходит: cancelled, no_show=true, штраф = подача."""
    d, pax, order = _offered_order(client, user_factory, "Ns3Drv", "Ns3Pax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=d["auth"])
    total = settings.wait_free_minutes + settings.no_show_extra_minutes
    _shift(oid, waiting_started_at=utcnow() - timedelta(minutes=total, seconds=5))
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"], json={"reason": "no_show"})
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "cancelled" and body["no_show"] is True
    # Счёт «пассажир не вышел» = подача по тарифу + дорога водителя + его ожидание
    # (2026-08-28). Машина рядом, дороги нет — значит подача плюс отжданные минуты.
    ждал = settings.wait_free_minutes + settings.no_show_extra_minutes
    ожидание = (ждал - settings.wait_free_minutes) * settings.wait_fee_rub_per_min * 100
    assert body["cancel_fee_kop"] == CITY_BASE_KOP + ожидание
    assert body["cancel_by"] == "driver"


def test_driver_normal_cancel_no_penalty(client, user_factory, fake_redis):
    """Обычная отмена водителем (поломка и т.п.) штрафа/страйка пассажиру НЕ даёт."""
    d, pax, order = _offered_order(client, user_factory, "DcDrv", "DcPax")
    oid = order["id"]
    client.post(f"/instant/orders/{oid}/accept", headers=d["auth"])
    r = client.post(f"/instant/orders/{oid}/cancel", headers=d["auth"], json={"reason": "поломка"})
    assert r.status_code == 200
    assert r.json()["cancel_fee_kop"] == 0 and r.json()["no_show"] is False
    with Session(engine) as s:
        assert isv.strike_pause_until(s, pax["id"]) is None


def _mk_strike(passenger_id: int, hours_ago: float = 0.0, no_show: bool = False):
    """Страйк напрямую в БД: платная отмена пассажира или no-show."""
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=passenger_id, status=S.cancelled,
            from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
            cancel_by=("driver" if no_show else "passenger"),
            cancel_fee_kop=CITY_BASE_KOP, no_show=no_show,
            cancelled_at=utcnow() - timedelta(hours=hours_ago),
        ))
        s.commit()


def test_strikes_pause_orders(client, user_factory, fake_redis):
    """3 страйка за 7 дней (2 платные отмены + 1 no-show) → POST /instant/orders 403, тёплый текст."""
    pax = user_factory("StrikePax")
    _mk_strike(pax["id"], hours_ago=2)
    _mk_strike(pax["id"], hours_ago=1)
    _mk_strike(pax["id"], hours_ago=0, no_show=True)
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 403
    # Ошибка двуязычная: detail={ru, ba}. Проверяем ОБА языка — иначе можно потерять
    # башкирскую половину и не заметить (правило «две отдельные строки», не одна).
    detail = r.json()["detail"]
    assert "пауз" in detail["ru"].lower()
    assert detail["ba"].strip(), "башкирский текст паузы пуст"
    # Попутка при этом работает: заявку пассажира гейт такси не трогает.
    with Session(engine) as s:
        assert isv.strike_pause_until(s, pax["id"]) is not None


def test_two_strikes_not_paused(client, user_factory, fake_redis):
    pax = user_factory("Strike2Pax")
    _mk_strike(pax["id"], hours_ago=2)
    _mk_strike(pax["id"], hours_ago=1)
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200               # 2 < strike_limit — можно (заказ просто expired без водителей)


def test_strike_pause_expires_after_24h(client, user_factory, fake_redis):
    """Пауза 24 ч от ПОСЛЕДНЕГО страйка: прошло больше → снова можно заказывать."""
    pax = user_factory("StrikeOldPax")
    for h in (30, 28, 26):                    # все 3 страйка в окне 7 дней, но паузе >24 ч
        _mk_strike(pax["id"], hours_ago=h)
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200


def test_strikes_outside_window_ignored(client, user_factory, fake_redis):
    """Страйки старше strike_window_days не считаются."""
    pax = user_factory("StrikeWinPax")
    _mk_strike(pax["id"], hours_ago=24 * (settings.strike_window_days + 1))
    _mk_strike(pax["id"], hours_ago=24 * (settings.strike_window_days + 2))
    _mk_strike(pax["id"], hours_ago=1)
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200


def test_free_cancels_are_not_strikes(client, user_factory, fake_redis):
    """Бесплатные отмены страйками не считаются — сколько угодно."""
    pax = user_factory("FreeCanPax")
    for i in range(3):
        with Session(engine) as s:
            s.add(InstantOrder(
                passenger_id=pax["id"], status=S.cancelled, cancel_by="passenger",
                from_lat=ORIG[0], from_lng=ORIG[1], to_lat=DEST[0], to_lng=DEST[1],
                cancel_fee_kop=0, cancelled_at=utcnow() - timedelta(hours=i),
            ))
            s.commit()
    r = client.post("/instant/orders", headers=pax["auth"], json=_order_body())
    assert r.status_code == 200


# ============================ 4. Классы Эконом/Комфорт ============================
def test_seed_comfort_tariffs():
    """Комфорт засеян стартовыми числами (город 90/14/6/130, межгород 100/26/3/200).

    Минута и километр подняты вслед за Экономом (пересмотр 2026-08-21): минута Комфорта не
    может стоить дешевле минуты Эконома, а километр межгорода — сравняться с ним. Пассажир
    видит разбивку цены, и такая инверсия читалась бы как ошибка в приложении.
    """
    with Session(engine) as s:
        c = s.exec(select(Tariff).where(Tariff.zone == "city", Tariff.category == "comfort")).first()
        i = s.exec(select(Tariff).where(Tariff.zone == "intercity", Tariff.category == "comfort")).first()
    assert (c.base, c.per_km, c.per_min, c.min_price, c.k) == (90, 14.0, 6.0, 130, 1.0)
    assert (i.base, i.per_km, i.per_min, i.min_price, i.k) == (100, 26.0, 3.0, 200, 1.0)


def test_estimate_comfort_pricier_with_options(client, user_factory):
    """estimate(comfort) дороже эконома; options отдаёт ОБЕ цены одним запросом."""
    pax = user_factory("ClsPax")
    std = client.post("/instant/estimate", headers=pax["auth"], json=_order_body()).json()
    cmf = client.post("/instant/estimate", headers=pax["auth"],
                      json=_order_body(category="comfort")).json()
    assert cmf["price"] > std["price"]
    assert cmf["category"] == "comfort" and cmf["tariff_id"] != std["tariff_id"]
    opts = {o["category"]: o["price"] for o in std["options"]}
    assert opts["standard"] == std["price"] and opts["comfort"] == cmf["price"]


def test_estimate_rejects_unknown_category(client, user_factory):
    pax = user_factory("ClsBadPax")
    r = client.post("/instant/estimate", headers=pax["auth"], json=_order_body(category="premier"))
    assert r.status_code == 422


def _set_car_class(driver_id: int, car_class):
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == driver_id)).first()
        p.car_class = car_class
        s.add(p)
        s.commit()


def test_matcher_comfort_order_skips_economy(client, user_factory, fake_redis):
    """comfort-заказ обычной машине не предлагается: единственный economy-водитель → expired."""
    d = _driver_online(client, user_factory, "EcoDrv")
    _heartbeat(client, d)
    pax = user_factory("CmfPax")
    order = client.post("/instant/orders", headers=pax["auth"],
                        json=_order_body(category="comfort")).json()
    assert order["status"] == "expired"


def test_matcher_comfort_order_to_comfort_driver(client, user_factory, fake_redis):
    d = _driver_online(client, user_factory, "CmfDrv")
    _set_car_class(d["id"], "comfort")
    _heartbeat(client, d)
    pax = user_factory("CmfPax2")
    order = client.post("/instant/orders", headers=pax["auth"],
                        json=_order_body(category="comfort")).json()
    assert order["status"] == "offered"
    assert _db_order(order["id"]).current_offer_driver_id == d["id"]


def test_matcher_standard_order_to_any_class(client, user_factory, fake_redis):
    """standard-заказ получают ВСЕ классы (комфорт тоже возит эконом)."""
    d = _driver_online(client, user_factory, "AnyDrv")
    _set_car_class(d["id"], "comfort")
    _heartbeat(client, d)
    pax = user_factory("StdPax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["status"] == "offered"


def test_apply_computes_car_class(client, user_factory):
    """Класс машины СЧИТАЕТСЯ по характеристикам, а не заявляется водителем.

    До 2026-08-08 водитель писал в заявке «у меня Комфорт» — и получал Комфорт. Любой мог
    поставить себе класс повыше, а пассажир получал Гранту по цене Комфорта. Теперь
    `car_class` из запроса игнорируется: без года выпуска и кондиционера машина остаётся
    Экономом, что бы водитель ни заявил."""
    d = user_factory("ApplyCls", role=UserRole.driver, taxi_approved=False)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    r = client.post("/taxi/apply", headers=d["auth"], json={
        "inn": "123456789012", "permit_number": "Т-0777",
        "birth_date": "1990-01-01", "license_since_year": 2010, "car_class": "comfort",
    })
    assert r.status_code == 200, r.text
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        assert p.car_class == "economy"          # заявку «Комфорт» классификатор не подтвердил
        assert p.car_classes_available == "economy"
    # Те же документы, но машина реально подходит: свежая, с кондиционером → Комфорт доступен.
    r2 = client.post("/taxi/apply", headers=d["auth"], json={
        "inn": "123456789012", "permit_number": "Т-0777",
        "birth_date": "1990-01-01", "license_since_year": 2010,
        "car_year": utcnow().year - 3, "car_ac": True, "seats": 4,
    })
    assert r2.status_code == 200, r2.text
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        assert p.car_class == "comfort"
        assert "comfort" in p.car_classes_available
    # Последнее слово — за модератором: он видел фото и вручную оставил Эконом.
    admin = user_factory("ClsAdmin", role=UserRole.admin)
    app_id = r2.json()["id"]
    ok = client.post(f"/admin/taxi-applications/{app_id}/approve", headers=admin["auth"],
                     json={"car_class": "economy"})
    assert ok.status_code == 200 and ok.json()["status"] == "approved"
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == d["id"])).first()
        assert p.car_class == "economy"


def test_apply_rejects_bus_and_wrong_color(client, user_factory):
    """Два стоп-фактора допуска: девять мест — это автобус, а в РБ такси только чёрное,
    белое или жёлтое. Оба ловим на подаче, а не после модерации."""
    d = user_factory("ApplyStop", role=UserRole.driver, taxi_approved=False)
    base = {"inn": "123456789012", "permit_number": "Т-0778",
            "birth_date": "1990-01-01", "license_since_year": 2010}
    bus = client.post("/taxi/apply", headers=d["auth"], json={**base, "seats": 13})
    assert bus.status_code == 400 and "мест" in bus.json()["detail"]["ru"]
    color = client.post("/taxi/apply", headers=d["auth"], json={**base, "car_color": "серебристый"})
    assert color.status_code == 400 and "жёлт" in color.json()["detail"]["ru"]
    # Нераспознанный цвет НЕ отклоняем — решает модератор по фото.
    ok = client.post("/taxi/apply", headers=d["auth"], json={**base, "car_color": "мокрый асфальт"})
    assert ok.status_code == 200, ok.text
