"""Тесты батча B4 «8-часовой лимит + отдых» (волна 2, §8 Отдых водителя).

1) Учёт: инкремент на интервал heartbeat с кэпом workday_step_cap_sec (редкие пинги
   не накручивают), первый пинг дня времени не даёт, местный день (UTC+5).
2) Лимит: seconds_online ≥ 8ч → limit_reached_at; гейт держит presence/offer/accept;
   АКТИВНЫЙ заказ доводится (arrived/onboard/done проходят).
3) Разблокировка: следующий день И ≥06:00 местного И ≥8ч от последнего heartbeat
   дня лимита (время мокается через monkeypatch utcnow в app.workday).
4) «Один попутчик домой»: первая публикация ок + флаг, вторая 403, отклик 403;
   вне блока попутка/отклики не ограничены вообще.
5) Вежливые пуши: ≤60/≤15 мин и «хорошо поработал» — по одному разу (дедуп);
   зимней ночью — совет про тепло/заряд, тоже один раз.
6) GET /instant/workday — сводка водителю.
"""
from datetime import datetime, timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import instant_service as isv
from app import workday
from app.config import settings
from app.db import engine
from app.models import TaxiWorkDay, UserRole

ORIG = (52.591, 58.317)    # Баймак — точка А
DEST = (52.716, 58.664)    # Сибай — точка Б (~30 км)

LIMIT = 8 * 3600           # дефолтный лимит смены, секунд


def L(y, m, d, h, mi=0, s=0):
    """Момент «h:mi местного (Уфа, UTC+5)» → наивный UTC, как хранит БД."""
    return datetime(y, m, d, h, mi, s) - timedelta(hours=settings.local_tz_offset_hours)


# Лето, полдень по-местному: далеко от границы дня и от «зимней ночи».
T0 = L(2026, 7, 8, 12, 0)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def clock(monkeypatch):
    """Мок времени ТОЛЬКО в app.workday (учёт/гейт/разблокировка живут там)."""
    state = {"now": T0}
    monkeypatch.setattr(workday, "utcnow", lambda: state["now"])

    def set_now(dt):
        state["now"] = dt
        return dt

    return set_now


@pytest.fixture
def pushes(monkeypatch):
    """Перехват вежливых пушей workday (send_push импортирован в модуль по имени)."""
    sent: list[tuple[int, str]] = []
    monkeypatch.setattr(workday, "send_push",
                        lambda session, uid, title, body, data=None: sent.append((uid, title)))
    return sent


# ------------------------------ helpers ------------------------------
def _driver_online(client, user_factory, name="ShiftDrv"):
    d = user_factory(name, role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    return d


def _hb(client, d, coord=ORIG):
    return client.post("/instant/presence", headers=d["auth"], json={"lat": coord[0], "lng": coord[1]})


def _order_body(frm=ORIG, to=DEST):
    return {"from_lat": frm[0], "from_lng": frm[1], "to_lat": to[0], "to_lng": to[1],
            "from_text": "Баймак", "to_text": "Сибай"}


def _wd(driver_id) -> TaxiWorkDay | None:
    with Session(engine) as s:
        return s.exec(
            select(TaxiWorkDay).where(TaxiWorkDay.driver_id == driver_id)
            .order_by(TaxiWorkDay.day.desc())
        ).first()


def _set_wd(driver_id, day, **fields):
    """Строка учёта напрямую в БД (get-or-create по driver_id+day)."""
    with Session(engine) as s:
        wd = s.exec(select(TaxiWorkDay).where(
            TaxiWorkDay.driver_id == driver_id, TaxiWorkDay.day == day)).first()
        if wd is None:
            wd = TaxiWorkDay(driver_id=driver_id, day=day)
        for k, v in fields.items():
            setattr(wd, k, v)
        s.add(wd)
        s.commit()


def _block(driver_id, now, **extra):
    """Действующий блок: лимит достигнут в момент now (местный день от now)."""
    fields = dict(seconds_online=LIMIT, limit_reached_at=now, last_heartbeat_at=now,
                  warned_60=True, warned_15=True)
    fields.update(extra)
    _set_wd(driver_id, workday.local_day(now), **fields)


def _ride_body():
    from app.timeutil import utcnow as real_utcnow
    return {"from_city": "Сибай", "to_city": "Баймак",
            "depart_at": (real_utcnow() + timedelta(hours=2)).isoformat(),
            "seats_total": 2, "price": 300}


# ============================ 1. Учёт: инкремент + кэп ============================
def test_heartbeat_increment_and_cap(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "IncDrv")
    assert _hb(client, d).status_code == 200          # первый пинг дня: строка есть, времени 0
    wd = _wd(d["id"])
    assert wd is not None and wd.seconds_online == 0
    assert wd.day == workday.local_day(T0)

    clock(T0 + timedelta(seconds=30))
    r = _hb(client, d)
    assert r.status_code == 200
    assert _wd(d["id"]).seconds_online == 30          # обычный интервал — целиком

    clock(T0 + timedelta(seconds=30) + timedelta(minutes=10))
    _hb(client, d)
    # Редкий пинг (10 мин) даёт максимум кэп, а не 600с — часы не накручиваются.
    assert _wd(d["id"]).seconds_online == 30 + settings.workday_step_cap_sec
    r = _hb(client, d)                                 # нулевой/крохотный шаг не ломает учёт
    assert r.status_code == 200
    assert r.json()["shift_remaining_sec"] <= LIMIT


def test_new_local_day_new_row(client, user_factory, clock, pushes):
    """Через местную полночь время капает в НОВУЮ строку (unique driver_id+day)."""
    d = _driver_online(client, user_factory, "MidnightDrv")
    clock(L(2026, 7, 8, 23, 59))
    _hb(client, d)
    clock(L(2026, 7, 9, 0, 1))
    _hb(client, d)
    with Session(engine) as s:
        rows = s.exec(select(TaxiWorkDay).where(TaxiWorkDay.driver_id == d["id"])).all()
    assert {r.day.isoformat() for r in rows} == {"2026-07-08", "2026-07-09"}
    assert all(r.seconds_online == 0 for r in rows)    # первые пинги каждого дня


# ============================ 2. Лимит → гейт; активный заказ доводится ============================
def test_limit_reached_on_heartbeat_then_gate(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "LimDrv")
    clock(T0)
    _hb(client, d)
    _set_wd(d["id"], workday.local_day(T0), seconds_online=LIMIT - 10,
            last_heartbeat_at=T0, warned_60=True, warned_15=True)
    clock(T0 + timedelta(seconds=30))
    r = _hb(client, d)                                 # этот пинг пересекает лимит — ещё 200
    assert r.status_code == 200 and r.json()["shift_remaining_sec"] == 0
    wd = _wd(d["id"])
    assert wd.limit_reached_at is not None
    assert [t for _, t in pushes] == ["Хорошо поработал 👏"]   # ровно один пуш лимита

    r = _hb(client, d)                                 # следующий пинг — уже отдых
    assert r.status_code == 403
    assert "отдохни" in r.json()["detail"].lower() and "ял ит" in r.json()["detail"]
    assert [t for _, t in pushes] == ["Хорошо поработал 👏"]   # летний день: второго пуша нет


def test_gate_holds_offer_and_accept(client, user_factory, fake_redis, clock, pushes):
    d = _driver_online(client, user_factory, "GateDrv")
    _hb(client, d)
    pax = user_factory("GatePax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    assert order["status"] == "offered"                # оффер уже висит на водителе

    _block(d["id"], T0)                                # лимит достигнут ДО принятия
    assert client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"] is None
    r = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"])
    assert r.status_code == 403 and "отдохни" in r.json()["detail"].lower()


def test_active_order_completes_despite_limit(client, user_factory, fake_redis, clock, pushes):
    """Не рубим посреди заказа: лимит наступил после accept → arrived/onboard/done проходят."""
    d = _driver_online(client, user_factory, "ActiveDrv")
    _hb(client, d)
    pax = user_factory("ActivePax")
    order = client.post("/instant/orders", headers=pax["auth"], json=_order_body()).json()
    oid = order["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=d["auth"]).status_code == 200

    _block(d["id"], T0)                                # 8 часов набежало по дороге
    assert _hb(client, d).status_code == 403           # presence уже закрыт
    for path in ("arrived", "onboard", "done"):        # но заказ спокойно доводится
        assert client.post(f"/instant/orders/{oid}/{path}", headers=d["auth"]).status_code == 200, path
    with Session(engine) as s:
        from app.models import InstantOrder, InstantOrderStatus
        assert s.get(InstantOrder, oid).status == InstantOrderStatus.done


# ============================ 3. Разблокировка по времени ============================
def test_unlock_next_day_after_six_and_rest(client, user_factory, clock, pushes):
    """Лимит вчера 21:00, последний пинг 21:30 → сегодня 05:30 рано (до 6), 06:30 — можно."""
    d = _driver_online(client, user_factory, "UnlockDrv")
    limit_at = L(2026, 7, 8, 21, 0)
    _block(d["id"], limit_at, last_heartbeat_at=L(2026, 7, 8, 21, 30))

    clock(L(2026, 7, 8, 23, 0))
    assert _hb(client, d).status_code == 403           # тот же день — блок
    clock(L(2026, 7, 9, 5, 30))
    assert _hb(client, d).status_code == 403           # следующий день, но до 06:00
    clock(L(2026, 7, 9, 6, 30))
    r = _hb(client, d)                                 # 06:30 и отдых 9ч — на линию
    assert r.status_code == 200
    wd = _wd(d["id"])
    assert wd.day.isoformat() == "2026-07-09" and wd.seconds_online == 0   # новый день с нуля


def test_unlock_waits_full_rest_hours(client, user_factory, clock, pushes):
    """Последний пинг вчера 23:30 → в 06:30 отдыха лишь 7ч < 8 — блок; 07:35 — можно."""
    d = _driver_online(client, user_factory, "RestDrv")
    _block(d["id"], L(2026, 7, 8, 23, 0), last_heartbeat_at=L(2026, 7, 8, 23, 30))
    clock(L(2026, 7, 9, 6, 30))
    assert _hb(client, d).status_code == 403
    clock(L(2026, 7, 9, 7, 35))
    assert _hb(client, d).status_code == 200


def test_workday_summary_endpoint(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "SumDrv")
    body = client.get("/instant/workday", headers=d["auth"]).json()
    assert body["blocked"] is False and body["seconds_online"] == 0
    assert body["remaining_sec"] == LIMIT and body["limit_sec"] == LIMIT
    assert body["unlock_at"] is None and body["return_ride_used"] is False

    limit_at = L(2026, 7, 8, 20, 0)
    _block(d["id"], limit_at)
    clock(L(2026, 7, 8, 22, 0))
    body = client.get("/instant/workday", headers=d["auth"]).json()
    assert body["blocked"] is True and body["remaining_sec"] == 0
    # Разблокировка: max(завтра 06:00 местного; последний пинг + 8ч) = завтра 06:00.
    assert body["unlock_at"] == L(2026, 7, 9, 6, 0).isoformat()


# ============================ 4. «Один попутчик домой» ============================
def test_return_ride_once_then_403(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "HomeDrv")
    _block(d["id"], T0)
    clock(T0 + timedelta(minutes=5))

    r1 = client.post("/rides", headers=d["auth"], json=_ride_body())
    assert r1.status_code == 200                       # первая публикация — «домой» можно
    assert _wd(d["id"]).return_ride_used is True
    assert client.get("/instant/workday", headers=d["auth"]).json()["return_ride_used"] is True

    r2 = client.post("/rides", headers=d["auth"], json=_ride_body())
    assert r2.status_code == 403                       # вторая — мягкий отказ до разблокировки
    assert "попутчика домой" in r2.json()["detail"] and "ял ит" in r2.json()["detail"]


def test_respond_blocked_during_rest(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "RespDrv")
    pax = user_factory("RespPax")
    req = client.post("/requests", headers=pax["auth"],
                      json={"from_city": "Сибай", "to_city": "Баймак", "seats": 1}).json()
    _block(d["id"], T0)
    r = client.post(f"/requests/{req['id']}/respond", headers=d["auth"], json={"price": 300})
    assert r.status_code == 403 and "попутчика домой" in r.json()["detail"]


def test_poputka_unlimited_outside_block(client, user_factory, clock, pushes):
    """Вне блока попутка НЕ ограничена: сколько угодно публикаций и отклики работают."""
    d = _driver_online(client, user_factory, "FreeDrv")
    assert client.post("/rides", headers=d["auth"], json=_ride_body()).status_code == 200
    assert client.post("/rides", headers=d["auth"], json=_ride_body()).status_code == 200
    assert _wd(d["id"]) is None or _wd(d["id"]).return_ride_used is False
    pax = user_factory("FreePax")
    req = client.post("/requests", headers=pax["auth"],
                      json={"from_city": "Сибай", "to_city": "Баймак", "seats": 1}).json()
    r = client.post(f"/requests/{req['id']}/respond", headers=d["auth"], json={"price": 250})
    assert r.status_code == 200


def test_return_ride_allowed_again_after_unlock(client, user_factory, clock, pushes):
    """После разблокировки return_ride_used не мешает: обычные публикации без лимита."""
    d = _driver_online(client, user_factory, "AfterDrv")
    _block(d["id"], L(2026, 7, 8, 15, 0), return_ride_used=True)
    clock(L(2026, 7, 9, 9, 0))                         # утро следующего дня, отдых пройден
    assert client.post("/rides", headers=d["auth"], json=_ride_body()).status_code == 200
    assert client.post("/rides", headers=d["auth"], json=_ride_body()).status_code == 200


# ============================ 5. Предупреждения и зимняя ночь (дедуп) ============================
def test_warning_60_and_15_sent_once(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "WarnDrv")
    clock(T0)
    _hb(client, d)
    _set_wd(d["id"], workday.local_day(T0), seconds_online=LIMIT - 55 * 60, last_heartbeat_at=T0)

    clock(T0 + timedelta(seconds=15))
    _hb(client, d)                                     # остаток ≈55 мин → «остался час»
    clock(T0 + timedelta(seconds=30))
    _hb(client, d)                                     # дедуп: второй раз не шлём
    assert [t for _, t in pushes] == ["Остался час смены"]

    _set_wd(d["id"], workday.local_day(T0), seconds_online=LIMIT - 10 * 60)
    clock(T0 + timedelta(seconds=45))
    _hb(client, d)                                     # остаток ≈10 мин → «15 минут»
    clock(T0 + timedelta(seconds=60))
    _hb(client, d)
    assert [t for _, t in pushes] == ["Остался час смены", "Осталось 15 минут смены"]


def test_winter_night_advice_once(client, user_factory, clock, pushes):
    """Блок зимней ночью (янв, 22:00 местного) → один совет про тепло/заряд, с дедупом.
    Итого за период отдыха ≤2 пуша: «хорошо поработал» + зимний совет."""
    d = _driver_online(client, user_factory, "WinterDrv")
    night = L(2026, 1, 15, 21, 55)
    clock(night)
    _hb(client, d)
    _set_wd(d["id"], workday.local_day(night), seconds_online=LIMIT - 5,
            last_heartbeat_at=night, warned_60=True, warned_15=True)
    clock(night + timedelta(seconds=30))
    _hb(client, d)                                     # лимит: «хорошо поработал» + зимний совет
    assert [t for _, t in pushes] == ["Хорошо поработал 👏", "Береги себя ❄️"]
    clock(night + timedelta(minutes=5))
    assert _hb(client, d).status_code == 403           # повторные попытки — без новых пушей
    assert _hb(client, d).status_code == 403
    assert len(pushes) == 2


def test_summer_block_no_winter_push(client, user_factory, clock, pushes):
    d = _driver_online(client, user_factory, "SummerDrv")
    _block(d["id"], T0)
    clock(T0 + timedelta(minutes=1))
    assert _hb(client, d).status_code == 403
    assert pushes == []                                # блок выставлен вручную — пушей нет вовсе
