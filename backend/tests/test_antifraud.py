"""Тесты анти-фрода (батч B8): баны устройств, сигнал нового устройства, анти-телепорт GPS,
реферал-фрод, кап оценок пары, анти-фишинг чата, «не заплатил», contact-then-cancel,
бейдж «Юлдаш ✓». Это безопасность — покрываем плотно, включая IDOR всех новых ручек."""
from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session, select

from app import antifraud as af
from app import instant_service as isv
from app.db import engine
from app.models import (
    Rating,
    Booking, BookingStatus, InstantOrder, InstantOrderStatus, ReferralBonus, Ride, User, UserRole,
)
from app.routers.referral import reward_driver_referral
from app.timeutil import utcnow


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


_phone_counter = {"n": 0}


def _fresh_phone() -> str:
    _phone_counter["n"] += 1
    return f"+7900555{_phone_counter['n']:04d}"


def _login(client, phone, device="", name="Пользователь"):
    """Полный OTP-вход (регистрация при первом входе) с заголовком X-Device-Id."""
    headers = {"X-Device-Id": device} if device else {}
    r = client.post("/auth/request-code", json={"phone": phone}, headers=headers)
    if r.status_code != 200:
        return r
    code = r.json()["dev_code"]
    return client.post("/auth/verify", json={"phone": phone, "code": code, "name": name},
                       headers=headers)


def _last_device(user_id: int):
    with Session(engine) as s:
        return s.get(User, user_id).last_device_id


# ============================ B8-1: бан устройства ============================
def test_login_fixes_device_on_user(client):
    phone = _fresh_phone()
    r = _login(client, phone, device="dev-fix-1")
    assert r.status_code == 200
    assert _last_device(r.json()["user"]["id"]) == "dev-fix-1"


def test_device_ban_blocks_registration_and_login(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    phone = _fresh_phone()
    r = _login(client, phone, device="dev-ban-1")
    assert r.status_code == 200
    uid = r.json()["user"]["id"]

    # Админ банит по user_id → баним его последнее устройство (галочка «баним и устройство»).
    r = client.post("/admin/bans/device", headers=admin["auth"],
                    json={"user_id": uid, "reason": "мошенничество"})
    assert r.status_code == 200
    assert r.json()["device_id"] == "dev-ban-1"

    # Повторный вход тем же номером с забаненного устройства → 403 ещё на request-code.
    r = client.post("/auth/request-code", json={"phone": phone}, headers={"X-Device-Id": "dev-ban-1"})
    assert r.status_code == 403
    assert "поддержк" in r.json()["detail"]

    # Обход бана НОВЫМ номером с того же устройства (ban evasion) → тоже 403.
    r = _login(client, _fresh_phone(), device="dev-ban-1")
    assert r.status_code == 403

    # Другое (чистое) устройство — регистрация работает: бан не задел честных.
    r = _login(client, _fresh_phone(), device="dev-clean-1")
    assert r.status_code == 200


def test_device_ban_blocks_telegram_login_too(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    client.post("/admin/bans/device", headers=admin["auth"],
                json={"device_id": "dev-tg-ban", "reason": "спам"})
    # tg/verify с забаненного устройства режется ДО проверки кода.
    r = client.post("/auth/tg/verify", json={"request_id": "whatever", "code": "000000"},
                    headers={"X-Device-Id": "dev-tg-ban"})
    assert r.status_code == 403


def test_device_unban_restores_access(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    client.post("/admin/bans/device", headers=admin["auth"],
                json={"device_id": "dev-unban-1", "reason": "ошибка"})
    assert _login(client, _fresh_phone(), device="dev-unban-1").status_code == 403
    r = client.delete("/admin/bans/device/dev-unban-1", headers=admin["auth"])
    assert r.status_code == 200
    assert _login(client, _fresh_phone(), device="dev-unban-1").status_code == 200
    # Повторное снятие → 404 (бана уже нет).
    assert client.delete("/admin/bans/device/dev-unban-1", headers=admin["auth"]).status_code == 404


def test_ban_device_idempotent_and_listed(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    r1 = client.post("/admin/bans/device", headers=admin["auth"],
                     json={"device_id": "dev-idem-1", "reason": "раз"})
    r2 = client.post("/admin/bans/device", headers=admin["auth"],
                     json={"device_id": "dev-idem-1", "reason": "два"})
    assert r1.status_code == r2.status_code == 200
    assert r1.json()["id"] == r2.json()["id"]          # идемпотентно — второй бан не плодит строк
    bans = client.get("/admin/bans", headers=admin["auth"]).json()
    assert any(b["device_id"] == "dev-idem-1" for b in bans)


def test_ban_user_without_device_conflict(client, user_factory):
    admin = user_factory("Admin", role=UserRole.admin)
    ghost = user_factory("БезУстройства")   # ни разу не входил с X-Device-Id
    r = client.post("/admin/bans/device", headers=admin["auth"],
                    json={"user_id": ghost["id"], "reason": "-"})
    assert r.status_code == 409


def test_bans_endpoints_admin_only(client, user_factory):
    mortal = user_factory("НеАдмин")
    assert client.post("/admin/bans/device", headers=mortal["auth"],
                       json={"device_id": "x", "reason": "-"}).status_code == 403
    assert client.get("/admin/bans", headers=mortal["auth"]).status_code == 403
    assert client.delete("/admin/bans/device/x", headers=mortal["auth"]).status_code == 403


def test_login_without_device_header_still_works(client):
    """Старый клиент без X-Device-Id не ломается (и не наказывается)."""
    r = _login(client, _fresh_phone(), device="")
    assert r.status_code == 200


# ============================ B8-2: сигнал входа с нового устройства ============================
@pytest.fixture
def signal_spy(monkeypatch):
    """Перехват push+SMS сигнала нового устройства (antifraud импортирует из services локально)."""
    calls = {"push": [], "sms": []}
    monkeypatch.setattr("app.services.send_push",
                        lambda session, uid, title, body, **kw: calls["push"].append((uid, title, body)))
    monkeypatch.setattr("app.services.send_text",
                        lambda phone, text: calls["sms"].append((phone, text)))
    return calls


def test_new_device_login_signals_push_and_sms(client, signal_spy):
    phone = _fresh_phone()
    r = _login(client, phone, device="dev-sig-A")
    uid = r.json()["user"]["id"]
    assert signal_spy["push"] == []          # первый вход (устройства ещё не было) — тишина

    r = _login(client, phone, device="dev-sig-B")   # вход с ДРУГОГО устройства
    assert r.status_code == 200              # не блокируем — только сигнал
    assert len(signal_spy["push"]) == 1 and signal_spy["push"][0][0] == uid
    assert "нов" in signal_spy["push"][0][2].lower()          # «с нового устройства»
    assert len(signal_spy["sms"]) == 1 and signal_spy["sms"][0][0] == phone
    assert _last_device(uid) == "dev-sig-B"  # устройство перефиксировано

    _login(client, phone, device="dev-sig-B")       # то же устройство снова — сигналов больше нет
    assert len(signal_spy["push"]) == 1 and len(signal_spy["sms"]) == 1


def test_same_device_login_no_signal(client, signal_spy):
    phone = _fresh_phone()
    _login(client, phone, device="dev-same-1")
    _login(client, phone, device="dev-same-1")
    assert signal_spy["push"] == [] and signal_spy["sms"] == []


# ============================ B8-3: анти-телепорт GPS ============================
def test_teleport_filter_honest_track_passes(fake_redis):
    """Честный водитель ~55 км/ч, точки раз в 10 секунд — всё проходит."""
    ts = 1_000_000.0
    assert af.teleport_filter(fake_redis, 42, 52.0000, 58.0, ts)
    assert af.teleport_filter(fake_redis, 42, 52.0014, 58.0, ts + 10)
    assert af.teleport_filter(fake_redis, 42, 52.0028, 58.0, ts + 20)
    assert af.gps_suspects_today(fake_redis) == 0


def test_teleport_filter_blocks_and_flags(fake_redis):
    """Телепорт (111 км за 5 с) игнорируется; якорь не двигается; 3 за час → флаг админу."""
    ts = 1_000_000.0
    assert af.teleport_filter(fake_redis, 43, 52.0, 58.0, ts)
    assert not af.teleport_filter(fake_redis, 43, 53.0, 58.0, ts + 5)
    assert not af.teleport_filter(fake_redis, 43, 53.0, 58.0, ts + 10)   # якорь всё ещё честный
    assert af.gps_suspects_today(fake_redis) == 0                        # 2 — ещё не флаг
    assert not af.teleport_filter(fake_redis, 43, 54.0, 58.0, ts + 15)
    assert af.gps_suspects_today(fake_redis) == 1                        # 3-й — пометили


def test_teleport_filter_first_point_after_pause_ok(fake_redis):
    """Честного не роняем: после паузы 2 часа переезд на 111 км — это 55 км/ч, проходит."""
    ts = 1_000_000.0
    assert af.teleport_filter(fake_redis, 44, 52.0, 58.0, ts)
    assert af.teleport_filter(fake_redis, 44, 53.0, 58.0, ts + 7200)


def test_track_guard_ws_mirror(fake_redis):
    """TrackGuard (WS-треки) — та же физика: честный кадр идёт, телепорт-кадр глушится."""
    g = af.TrackGuard(user_id=45)
    ts = 1_000_000.0
    assert g.ok(52.0000, 58.0, ts)
    assert g.ok(52.0014, 58.0, ts + 10)          # ~55 км/ч — честно
    assert not g.ok(53.0, 58.0, ts + 20)          # телепорт — кадр не ретранслируем
    assert g.ok(52.0028, 58.0, ts + 30)           # честный продолжает ехать как ни в чём не бывало


# ============================ B8-6: анти-фишинг чата ============================
@pytest.mark.parametrize("text", [
    "Продиктуй код из СМС, я водитель поддержки",
    "пришли смс-код скорее",
    "скажи код подтверждения",
    "назови код для входа",
    "Оплати на карту 2202 2005 1234 5678",
    "переведи на другой номер +79991234567",
    "лучше переведи на другую карту, эта не работает",
])
def test_phishing_patterns_flagged(text):
    assert af.phishing_flag(text) == "warn"


@pytest.mark.parametrize("text", [
    "Буду через 5 минут, жди у подъезда",
    "Код посадки 482913",                      # честный флоу посадки — не фишинг
    "Назови код посадки, пожалуйста",
    "Переведи по СБП как договорились",       # обычная оплата — без «другого номера»
    "Заберу у дома 12, квартира 34",
    "",
])
def test_ordinary_messages_not_flagged(text):
    assert af.phishing_flag(text) == ""


def _make_booking_pair(user_factory):
    """Водитель + пассажир + бронь (для чата) — напрямую в БД, без полного флоу."""
    drv = user_factory("Водитель", role=UserRole.driver)
    pax = user_factory("Пассажир")
    with Session(engine) as s:
        ride = Ride(driver_id=drv["id"], from_city="Уфа", to_city="Сибай", depart_at=utcnow())
        s.add(ride)
        s.commit()
        s.refresh(ride)
        b = Booking(ride_id=ride.id, passenger_id=pax["id"], status=BookingStatus.confirmed)
        s.add(b)
        s.commit()
        s.refresh(b)
        return drv, pax, b.id


def test_chat_message_flagged_and_visible_to_recipient(client, user_factory):
    """Фишинговое сообщение в чате брони: не блокируется, но flag=warn и получатель его видит."""
    drv, pax, bid = _make_booking_pair(user_factory)
    r = client.post(f"/bookings/{bid}/messages", headers=drv["auth"],
                    json={"text": "Продиктуй код из смс"})
    assert r.status_code == 200
    assert r.json()["flag"] == "warn"
    msgs = client.get(f"/bookings/{bid}/messages", headers=pax["auth"]).json()
    assert msgs[-1]["flag"] == "warn"


def test_chat_ordinary_message_not_flagged(client, user_factory):
    drv, pax, bid = _make_booking_pair(user_factory)
    r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"],
                    json={"text": "Выезжаю, буду через 10 минут"})
    assert r.status_code == 200 and r.json()["flag"] == ""


def test_chat_edit_recomputes_flag(client, user_factory):
    """Обход «отправил безобидное → отредактировал в фишинг» закрыт."""
    drv, pax, bid = _make_booking_pair(user_factory)
    mid = client.post(f"/bookings/{bid}/messages", headers=drv["auth"],
                      json={"text": "привет"}).json()["id"]
    r = client.post(f"/bookings/{bid}/messages/{mid}/edit", headers=drv["auth"],
                    json={"text": "скинь код из смс"})
    assert r.status_code == 200 and r.json()["flag"] == "warn"


def test_order_chat_message_flagged(client, user_factory):
    """Чат такси-заказа — та же защита."""
    drv = user_factory("Таксист", role=UserRole.driver)
    pax = user_factory("Клиент")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         status=InstantOrderStatus.accepted)
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
    r = client.post(f"/instant/orders/{oid}/messages", headers=drv["auth"],
                    json={"text": "переведи на другой номер"})
    assert r.status_code == 200 and r.json()["flag"] == "warn"
    msgs = client.get(f"/instant/orders/{oid}/messages", headers=pax["auth"]).json()
    assert msgs[-1]["flag"] == "warn"


# ============================ B8-5: кап оценок одной пары ============================
def _add_rating(rater_id, ratee_id, stars, days_ago=0.0):
    with Session(engine) as s:
        s.add(Rating(rater_id=rater_id, ratee_id=ratee_id, stars=stars,
                     created_at=utcnow() - timedelta(days=days_ago)))
        s.commit()


def test_rating_pair_cap_blocks_pumping(client, user_factory):
    """Пара аккаунтов гоняет 5★ десятками: в агрегат идут только первые 3 за 30 дней —
    средний балл не накручивается (пишутся все, влияют первые)."""
    from app.services import user_rating
    ratee = user_factory("Накручиваемый", role=UserRole.driver)
    pumper = user_factory("Накрутчик")
    honest = user_factory("Честный")
    _add_rating(honest["id"], ratee["id"], 3, days_ago=5)     # честная тройка
    for i in range(6):                                        # 6×5★ от одной пары за неделю
        _add_rating(pumper["id"], ratee["id"], 5, days_ago=4 - i * 0.5)
    with Session(engine) as s:
        avg, cnt = user_rating(s, ratee["id"])
        all_rows = len(s.exec(select(Rating).where(Rating.ratee_id == ratee["id"])).all())
    assert all_rows == 7                    # записаны ВСЕ (история честная)
    assert cnt == 4                         # но учтены: 1 честная + только 3 от пары
    assert avg == pytest.approx((3 + 5 * 3) / 4)


def test_rating_pair_cap_window_slides(client, user_factory):
    """Старые оценки пары (за пределами 30 дней) окно не занимают — честный постоянный
    попутчик может оценивать дальше."""
    from app.services import user_rating
    ratee = user_factory("Водитель5", role=UserRole.driver)
    mate = user_factory("ПостоянныйПопутчик")
    for days in (100, 90, 80):              # три старые — вне окна
        _add_rating(mate["id"], ratee["id"], 4, days_ago=days)
    for days in (10, 5, 1):                 # три свежие — в окне
        _add_rating(mate["id"], ratee["id"], 5, days_ago=days)
    with Session(engine) as s:
        avg, cnt = user_rating(s, ratee["id"])
    assert cnt == 6                         # все 6 учтены: в каждом окне ≤3
    assert avg == pytest.approx((4 * 3 + 5 * 3) / 6)


def test_rating_pair_cap_in_drivers_bundle(client, user_factory):
    """Витрина списка поездок (drivers_bundle) считает с тем же капом, что и user_rating."""
    from app.services import drivers_bundle, user_rating
    ratee = user_factory("ВодительВитрина", role=UserRole.driver)
    pumper = user_factory("Накрутчик2")
    for i in range(5):
        _add_rating(pumper["id"], ratee["id"], 5, days_ago=i * 0.1)
    with Session(engine) as s:
        avg_u, cnt_u = user_rating(s, ratee["id"])
        _, _, agg = drivers_bundle(s, {ratee["id"]})
    assert cnt_u == 3
    assert agg[ratee["id"]] == (pytest.approx(avg_u), cnt_u)


# ============================ B8-4: реферал-фрод (водительский бонус) ============================
def _make_referred_driver(user_factory, referrer_id):
    d = user_factory("ПриглашённыйВодитель", role=UserRole.driver)
    with Session(engine) as s:
        u = s.get(User, d["id"])
        u.referred_by = referrer_id
        s.add(u)
        s.commit()
    return d


def _add_done_order(driver_id, passenger_id, distance_km=10.0, minutes=15):
    now = utcnow()
    with Session(engine) as s:
        s.add(InstantOrder(
            passenger_id=passenger_id, driver_id=driver_id,
            status=InstantOrderStatus.done, distance_km=distance_km,
            onboard_at=now - timedelta(minutes=minutes), done_at=now,
        ))
        s.commit()


def _credits(user_id):
    with Session(engine) as s:
        return s.get(User, user_id).referral_credits


def test_referral_bonus_needs_three_distinct_passengers(client, user_factory):
    """Накрутка той же парой (один пассажир гоняет 3 фейк-поездки) бонуса НЕ даёт;
    3 живые поездки с 3 разными пассажирами — даёт, ровно один раз."""
    referrer = user_factory("Пригласивший")
    driver = _make_referred_driver(user_factory, referrer["id"])
    accomplice = user_factory("Сообщник")
    for _ in range(3):   # та же пара × 3 — «поездки» есть, пассажир один
        _add_done_order(driver["id"], accomplice["id"])
    with Session(engine) as s:
        assert reward_driver_referral(s, driver["id"]) is False
    assert _credits(referrer["id"]) == 0

    p2, p3 = user_factory("Пасс2"), user_factory("Пасс3")
    _add_done_order(driver["id"], p2["id"])
    _add_done_order(driver["id"], p3["id"])
    with Session(engine) as s:
        assert reward_driver_referral(s, driver["id"]) is True
    assert _credits(referrer["id"]) == 1
    with Session(engine) as s:   # повторный done → бонус не дублируется (unique на приглашённого)
        assert reward_driver_referral(s, driver["id"]) is False
    assert _credits(referrer["id"]) == 1


def test_referral_bonus_requires_live_trips(client, user_factory):
    """«Мёртвые» поездки (без движения: <1 км и <5 мин) не считаются живыми — бонуса нет."""
    referrer = user_factory("Пригласивший2")
    driver = _make_referred_driver(user_factory, referrer["id"])
    for name in ("Ф1", "Ф2", "Ф3"):
        p = user_factory(name)
        _add_done_order(driver["id"], p["id"], distance_km=0.3, minutes=2)
    with Session(engine) as s:
        assert reward_driver_referral(s, driver["id"]) is False
    assert _credits(referrer["id"]) == 0


def test_referral_bonus_counts_live_poputka(client, user_factory):
    """Попутка тоже считается живой поездкой: done-бронь на маршруте длиннее 1 км."""
    referrer = user_factory("Пригласивший3")
    driver = _make_referred_driver(user_factory, referrer["id"])
    p1, p2, p3 = user_factory("П1"), user_factory("П2"), user_factory("П3")
    with Session(engine) as s:
        ride = Ride(driver_id=driver["id"], from_city="Баймак", to_city="Сибай",
                    depart_at=utcnow(), from_lat=52.591, from_lng=58.317,
                    to_lat=52.716, to_lng=58.664)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        for p in (p1, p2, p3):
            s.add(Booking(ride_id=ride.id, passenger_id=p["id"], status=BookingStatus.done))
        s.commit()
        assert reward_driver_referral(s, driver["id"]) is True
    assert _credits(referrer["id"]) == 1


def test_referral_bonus_monthly_cap(client, user_factory):
    """≤5 водительских бонусов на пригласившего в месяц: шестой не выдаётся."""
    referrer = user_factory("Хаб")
    with Session(engine) as s:
        for i in range(5):
            fake_invited = user_factory(f"Р{i}")
            s.add(ReferralBonus(referrer_id=referrer["id"], invited_user_id=fake_invited["id"]))
        s.commit()
    driver = _make_referred_driver(user_factory, referrer["id"])
    for name in ("К1", "К2", "К3"):
        p = user_factory(name)
        _add_done_order(driver["id"], p["id"])
    with Session(engine) as s:
        assert reward_driver_referral(s, driver["id"]) is False   # кэп месяца
    assert _credits(referrer["id"]) == 0


def test_referral_bonus_fires_from_done_endpoint(client, user_factory, fake_redis):
    """Интеграция: бонус выдаётся сам после done 3-й живой поездки (хук в /instant/.../done)."""
    referrer = user_factory("Дед")
    driver = _make_referred_driver(user_factory, referrer["id"])
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    orig, dest = (52.591, 58.317), (52.716, 58.664)
    for name in ("Гость1", "Гость2", "Гость3"):
        pax = user_factory(name)
        client.post("/instant/presence", headers=driver["auth"],
                    json={"lat": orig[0], "lng": orig[1]})
        r = client.post("/instant/orders", headers=pax["auth"], json={
            "from_lat": orig[0], "from_lng": orig[1], "to_lat": dest[0], "to_lng": dest[1],
            "from_text": "Баймак", "to_text": "Сибай",
        })
        assert r.status_code == 200, r.text
        oid = r.json()["id"]
        assert client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"]).status_code == 200
        assert client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"]).status_code == 200
        assert client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"]).status_code == 200
        assert client.post(f"/instant/orders/{oid}/done", headers=driver["auth"]).status_code == 200
    assert _credits(referrer["id"]) == 1


def test_presence_teleport_not_published(client, user_factory, fake_redis):
    """Presence: телепорт-точка не публикуется (водитель в GEO не «прыгает»), ok=False,
    честный heartbeat в той же точке дальше работает; пульс админа видит подозрительных."""
    d = user_factory("ГонщикGPS", role=UserRole.driver)
    assert client.post("/driver/online", headers=d["auth"], json={"online": True}).status_code == 200
    orig = (52.591, 58.317)
    far = (53.591, 58.317)   # ~111 км — телепорт при мгновенной отправке
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": orig[0], "lng": orig[1]})
    assert r.status_code == 200 and r.json()["ok"] is True
    member = f"driver:{d['id']}"
    pos_before = fake_redis.geopos(isv.PRESENCE_KEY, member)
    for _ in range(3):   # три телепорта подряд → флаг
        r = client.post("/instant/presence", headers=d["auth"], json={"lat": far[0], "lng": far[1]})
        assert r.status_code == 200          # запрос не падает — точка просто игнорируется
        assert r.json()["ok"] is False
    assert fake_redis.geopos(isv.PRESENCE_KEY, member) == pos_before   # в GEO не сдвинулся
    # Повтор честной точки (та же координата) — проходит: автоматика не наказывает.
    r = client.post("/instant/presence", headers=d["auth"], json={"lat": orig[0], "lng": orig[1]})
    assert r.json()["ok"] is True
    admin = user_factory("Admin", role=UserRole.admin)
    pulse = client.get("/admin/taxi/pulse", headers=admin["auth"]).json()
    assert pulse["gps_suspects_today"] >= 1
