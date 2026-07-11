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
from app.models import DeviceBan, User, UserRole
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
