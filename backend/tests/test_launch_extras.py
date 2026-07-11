"""B9b «Предзапусковые мелочи»: force-update (/version/min), пуши пассажиру о ходе
такси-заказа, дневная сводка админу в Telegram, тестовый аккаунт для модерации сторов."""
import pytest

from app.config import settings

from test_instant import _create_order, _offered_order, fake_redis  # noqa: F401 — fixture реэкспорт
from test_taxi_polish import _accepted_order


@pytest.fixture
def pushes(monkeypatch):
    """Перехват пушей instant-стека (send_push импортирован в app.instant_service по имени)."""
    from app import instant_service as isv
    sent = []
    monkeypatch.setattr(
        isv, "send_push",
        lambda session, uid, title, body, data=None, **kw: sent.append(
            {"uid": uid, "title": title, "body": body, "data": data or {}}),
    )
    return sent


def _status_pushes(sent):
    return [p for p in sent if p["data"].get("type") == "instant_status"]


# ============================ Force-update: /version/min (B9b-1) ============================
def test_version_min_disabled_by_default(client):
    """По умолчанию min_app_version_code=0 → клиент никого не блокирует."""
    r = client.get("/version/min")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["min_version_code"] == 0
    assert data["store_url"] == ""
    # Сообщение всегда двуязычное (RU/BA), даже когда проверка выключена.
    assert data["message"]["ru"] and data["message"]["ba"]


def test_version_min_enabled_via_config(client, monkeypatch):
    """Конфиг включён → ручка отдаёт порог и ссылку на стор (без пересборки клиента)."""
    monkeypatch.setattr(settings, "min_app_version_code", 5)
    monkeypatch.setattr(settings, "app_store_url", "https://example.com/yuldash")
    data = client.get("/version/min").json()
    assert data["min_version_code"] == 5
    assert data["store_url"] == "https://example.com/yuldash"


def test_version_min_no_auth_required(client):
    """Ручка публичная: клиент проверяет версию ДО входа (на сплэше)."""
    assert client.get("/version/min").status_code == 200
    assert client.get("/api/v1/version/min").status_code == 200


# ============================ Пуши о ходе такси-заказа (B9b-2) ============================
def test_status_pushes_on_each_transition(client, user_factory, fake_redis, pushes):
    """accepted / arriving / onboard / done → пассажиру пуш: двуязычный (RU · BA) +
    data type=instant_status с order_id — тап по пушу открывает заказ."""
    d, pax, order = _offered_order(client, user_factory, fake_redis, "PshDrv", "PshPax")
    oid = order["id"]
    steps = [
        ("accept", "accepted", "Водитель найден", "Водитель табылды"),
        ("arrived", "arriving", "Машина на месте!", "Машина килеп етте!"),   # важнейший
        ("onboard", "onboard", "В пути", "Юлда"),
        ("done", "done", "Поездка завершена", "Сәфәр тамамланды"),
    ]
    for endpoint, status, ru, ba in steps:
        before = len(_status_pushes(pushes))
        assert client.post(f"/instant/orders/{oid}/{endpoint}", headers=d["auth"]).status_code == 200
        new = _status_pushes(pushes)[before:]
        assert len(new) == 1, f"{endpoint}: ожидали ровно 1 статус-пуш, got {new}"
        p = new[0]
        assert p["uid"] == pax["id"]                        # получатель — пассажир
        assert ru in p["title"] and ba in p["title"]        # двуязычный заголовок
        assert p["data"]["order_id"] == str(oid)
        assert p["data"]["status"] == status


def test_cancel_by_driver_pushes_passenger(client, user_factory, fake_redis, pushes):
    """Водитель отменил после accept → пассажиру «Заказ отменён» (двуязычно, data-payload)."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "CnDrv", "CnPax")
    assert client.post(f"/instant/orders/{order['id']}/cancel", headers=d["auth"],
                       json={"reason": "сломалась машина"}).status_code == 200
    got = [p for p in _status_pushes(pushes) if p["uid"] == pax["id"] and p["data"]["status"] == "cancelled"]
    assert len(got) == 1
    assert "Заказ отменён" in got[0]["title"] and "кире алынды" in got[0]["title"]
    assert "Ищем другого" in got[0]["body"]


def test_cancel_by_passenger_pushes_driver(client, user_factory, fake_redis, pushes):
    """Пассажир отменил после accept → ВОДИТЕЛЮ пуш «Пассажир отменил заказ»."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "CpDrv", "CpPax")
    assert client.post(f"/instant/orders/{order['id']}/cancel", headers=pax["auth"],
                       json={}).status_code == 200
    got = [p for p in _status_pushes(pushes) if p["uid"] == d["id"] and p["data"]["status"] == "cancelled"]
    assert len(got) == 1
    assert "Пассажир отменил" in got[0]["body"] and "Пассажир заказды кире алды" in got[0]["body"]


def test_expired_push_when_nobody_around(client, user_factory, fake_redis, pushes):
    """Рядом никого → заказ expired → пассажиру честный пуш (двуязычно, status=expired)."""
    pax = user_factory("ExpPax")
    order = _create_order(client, pax)
    assert order["status"] == "expired"
    got = [p for p in _status_pushes(pushes) if p["data"]["status"] == "expired"]
    assert len(got) == 1 and got[0]["uid"] == pax["id"]
    assert "Рядом никого" in got[0]["title"] and "водитель юҡ" in got[0]["title"]


# ============================ Дневная сводка в Telegram (B9b-3) ============================
# Контролируемый день в прошлом: счётчики за него полностью наши (сессионная БД общая,
# «сегодня» засоряют соседние тесты). Местный день D → UTC-окно [D-5ч, D+19ч).
DIGEST_DAY = None   # заполняется в _seed_digest_day (импорт date ниже)


def _seed_digest_day(user_factory):
    """Насыпать за фиксированный местный день: 1 поездка, 1 бронь, 2 такси-заказа
    (1 done + 1 отмена), 1 нового юзера, 1 водителя на линии, комиссию 123.45 ₽, 1 жалобу."""
    from datetime import date, datetime, timedelta

    from sqlmodel import Session

    from app.db import engine
    from app.models import (
        Booking, CommissionDebt, InstantOrder, InstantOrderStatus, Report, Ride,
        TaxiWorkDay, User,
    )
    day = date(2026, 1, 15)
    inside = datetime(2026, 1, 15, 7, 0)     # 12:00 местного (UTC+5) — внутри окна дня
    driver = user_factory("DigDrv")
    pax = user_factory("DigPax")
    with Session(engine) as s:
        u = User(phone="digest-new-user", name="Новичок", created_at=inside)
        ride = Ride(driver_id=driver["id"], from_city="Баймак", to_city="Сибай",
                    depart_at=inside, created_at=inside)
        s.add(u)
        s.add(ride)
        s.commit()
        s.refresh(ride)
        s.add(Booking(ride_id=ride.id, passenger_id=pax["id"], created_at=inside))
        s.add(InstantOrder(passenger_id=pax["id"], driver_id=driver["id"],
                           status=InstantOrderStatus.done, created_at=inside, done_at=inside))
        s.add(InstantOrder(passenger_id=pax["id"],
                           status=InstantOrderStatus.cancelled, created_at=inside,
                           cancelled_at=inside))
        s.add(TaxiWorkDay(driver_id=driver["id"], day=day, seconds_online=3600))
        s.add(CommissionDebt(driver_id=driver["id"], amount_kop=12345, week="2026-W03",
                             created_at=inside))
        s.add(Report(reporter_id=pax["id"], target_user_id=driver["id"],
                     category="other", created_at=inside))
        s.commit()
    return day


def test_digest_counters(client, user_factory):
    """Счётчики сводки верные: считаем ровно то, что насыпали в контролируемый день."""
    from datetime import date

    from sqlmodel import Session

    from app import digest
    from app.db import engine
    day = _seed_digest_day(user_factory)
    with Session(engine) as s:
        text = digest.build_digest(s, day)
    assert "📊 Юлдаш за 15.01.2026" in text
    assert "поездок попутки 1 (брони 1)" in text
    assert "такси-заказов 2 (done 1, отмен 1)" in text
    assert "новых пользователей 1" in text
    assert "водителей на линии 1" in text
    assert "выручка-комиссия ~123 ₽" in text
    assert "жалоб новых 1" in text
    # Соседний пустой день — все нули (окно дня не протекает).
    with Session(engine) as s:
        empty = digest.build_digest(s, date(2026, 1, 20))
    assert "поездок попутки 0 (брони 0)" in empty and "такси-заказов 0" in empty


def test_digest_sent_once_per_day(client, monkeypatch):
    """Первый вызов после 21:00 местного шлёт сводку, повторные — нет (память процесса
    и замок в БД: сброс памяти имитирует второй воркер — БД всё равно не даёт продублировать)."""
    from datetime import datetime

    from sqlmodel import Session

    from app import digest
    from app.db import engine
    sent = []
    monkeypatch.setattr(digest, "notify_admin_telegram", lambda text, **kw: sent.append(text))
    monkeypatch.setattr(settings, "daily_digest_enabled", True)
    monkeypatch.setattr(digest, "_memo_sent_day", None)
    at = datetime(2026, 2, 3, 16, 30)   # 21:30 местного (UTC+5) — порог 21:00 пройден
    with Session(engine) as s:
        assert digest.maybe_send_daily_digest(s, at) is True
        assert digest.maybe_send_daily_digest(s, at) is False       # память процесса
    monkeypatch.setattr(digest, "_memo_sent_day", None)             # «второй воркер»
    with Session(engine) as s:
        assert digest.maybe_send_daily_digest(s, at) is False       # замок в БД
    assert len(sent) == 1 and sent[0].startswith("📊 Юлдаш за 03.02.2026")


def test_digest_not_before_hour(client, monkeypatch):
    """До 21:00 местного сводка не уходит (утренний запрос не триггерит)."""
    from datetime import datetime

    from sqlmodel import Session

    from app import digest
    from app.db import engine
    sent = []
    monkeypatch.setattr(digest, "notify_admin_telegram", lambda text, **kw: sent.append(text))
    monkeypatch.setattr(settings, "daily_digest_enabled", True)
    monkeypatch.setattr(digest, "_memo_sent_day", None)
    with Session(engine) as s:
        assert digest.maybe_send_daily_digest(s, datetime(2026, 2, 4, 10, 0)) is False
    assert sent == []


def test_digest_disabled_by_config(client, monkeypatch):
    """DAILY_DIGEST_ENABLED=false → сводка полностью выключена (даже после 21:00)."""
    from datetime import datetime

    from sqlmodel import Session

    from app import digest
    from app.db import engine
    sent = []
    monkeypatch.setattr(digest, "notify_admin_telegram", lambda text, **kw: sent.append(text))
    monkeypatch.setattr(settings, "daily_digest_enabled", False)
    monkeypatch.setattr(digest, "_memo_sent_day", None)
    with Session(engine) as s:
        assert digest.maybe_send_daily_digest(s, datetime(2026, 2, 5, 16, 30)) is False
    assert sent == []
