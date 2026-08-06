"""B9b «Предзапусковые мелочи»: force-update (/version/min), пуши пассажиру о ходе
такси-заказа, дневная сводка админу в Telegram, тестовый аккаунт для модерации сторов."""
import pytest

from app.config import settings

from test_instant import _create_order, _offered_order, fake_redis  # noqa: F401 — fixture реэкспорт
from test_taxi_polish import _accepted_order


@pytest.fixture
def pushes(monkeypatch):
    """Перехват пушей instant-стека.

    Подменяем ДВА имени: `send_push` в `app.instant_service` (импортирован туда по имени —
    прямые вызовы статусов) и `send_push` в `app.services` (через него шлёт единая точка
    `push_notification`, которой теперь пользуется отмена заказа). Подмена только первого
    молча пропускала бы пуши отмены — тест был бы зелёным при полностью немом сервере."""
    from app import instant_service as isv
    from app import services as svc
    sent = []
    spy = lambda session, uid, title, body, data=None, **kw: sent.append(   # noqa: E731
        {"uid": uid, "title": title, "body": body, "data": data or {}})
    monkeypatch.setattr(isv, "send_push", spy)
    monkeypatch.setattr(svc, "send_push", spy)
    return sent


def _status_pushes(sent):
    return [p for p in sent if p["data"].get("type") == "instant_status"]


# ============================ Force-update: /version/min (B9b-1) ============================
def test_version_min_disabled_by_default(client):
    """По умолчанию обе проверки выключены → ни блокировки, ни плашки."""
    r = client.get("/version/min")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["min_version_code"] == 0
    assert data["latest_version_code"] == 0
    # Сообщения всегда двуязычные (RU/BA), даже когда проверки выключены.
    assert data["message"]["ru"] and data["message"]["ba"]
    assert data["update_message"]["ru"] and data["update_message"]["ba"]


def test_version_min_enabled_via_config(client, monkeypatch):
    """Конфиг включён → ручка отдаёт порог и ссылку на стор (без пересборки клиента)."""
    monkeypatch.setattr(settings, "min_app_version_code", 5)
    monkeypatch.setattr(settings, "app_store_url", "https://example.com/yuldash")
    data = client.get("/version/min").json()
    assert data["min_version_code"] == 5
    assert data["store_url"] == "https://example.com/yuldash"


def test_store_url_falls_back_to_landing(client, monkeypatch):
    """Стора ещё нет — кнопка «Обновить» обязана вести хотя бы на лендинг с APK.
    Раньше при пустом APP_STORE_URL она не вела никуда."""
    monkeypatch.setattr(settings, "app_store_url", "")
    monkeypatch.setattr(settings, "app_download_url", "https://yulbash.ru/")
    assert client.get("/version/min").json()["store_url"] == "https://yulbash.ru/"


def test_version_min_no_auth_required(client):
    """Ручка публичная: клиент проверяет версию ДО входа (на сплэше)."""
    assert client.get("/version/min").status_code == 200
    assert client.get("/api/v1/version/min").status_code == 200


# ===================== Мягкое обновление: плашка «вышла новая версия» (B9b-1b) =====================
def test_soft_update_serves_version_and_whats_new(client, monkeypatch):
    """Клиент между min и latest → плашка с номером версии и списком «что нового».
    Список двуязычный: башкир не должен видеть русские пункты."""
    monkeypatch.setattr(settings, "latest_app_version_code", 7)
    monkeypatch.setattr(settings, "latest_app_version_name", "1.1.0")
    monkeypatch.setattr(settings, "whats_new_ru", "Карта быстрее|Починили чат")
    monkeypatch.setattr(settings, "whats_new_ba", "Карта тиҙерәк|Чатты төҙәттек")
    data = client.get("/version/min").json()
    assert data["latest_version_code"] == 7
    assert data["latest_version_name"] == "1.1.0"
    assert data["whats_new"]["ru"] == ["Карта быстрее", "Починили чат"]
    assert data["whats_new"]["ba"] == ["Карта тиҙерәк", "Чатты төҙәттек"]


def test_whats_new_trims_and_caps_at_three(client, monkeypatch):
    """Плашка — повод нажать кнопку, а не журнал изменений: не больше трёх пунктов,
    пустые куски (двойной разделитель, хвостовая «|») выкидываем."""
    monkeypatch.setattr(settings, "whats_new_ru", " Раз | Два ||Три|Четыре|")
    assert client.get("/version/min").json()["whats_new"]["ru"] == ["Раз", "Два", "Три"]


def test_soft_update_ignores_empty_whats_new(client, monkeypatch):
    """Список не заполнили → плашка всё равно работает, просто без «что нового»."""
    monkeypatch.setattr(settings, "latest_app_version_code", 7)
    monkeypatch.setattr(settings, "whats_new_ru", "")
    data = client.get("/version/min").json()
    assert data["latest_version_code"] == 7
    assert data["whats_new"]["ru"] == []




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


def _last_note(user_id: int):
    """Последняя запись Центра уведомлений — там оба языка хранятся всегда."""
    from sqlmodel import Session, select

    from app.db import engine
    from app.models import Notification
    with Session(engine) as s:
        rows = list(s.exec(select(Notification).where(Notification.user_id == user_id)).all())
    return rows[-1] if rows else None


def test_cancel_by_driver_pushes_passenger(client, user_factory, fake_redis, pushes):
    """Водитель отменил после accept → пассажиру «Заказ отменён» (двуязычно, data-payload)."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "CnDrv", "CnPax")
    assert client.post(f"/instant/orders/{order['id']}/cancel", headers=d["auth"],
                       json={"reason": "сломалась машина"}).status_code == 200
    got = [p for p in _status_pushes(pushes) if p["uid"] == pax["id"] and p["data"]["status"] == "cancelled"]
    assert len(got) == 1
    # Пуш уходит на ЯЗЫКЕ ПОЛУЧАТЕЛЯ (единая точка services.push_notification), а не обоими
    # языками в одной строке: русскоязычный больше не читает «Заказ отменён · Заказ кире алынды».
    # Оба текста при этом хранятся в Центре уведомлений — см. tests/test_other_side_is_told.py.
    assert "Заказ отменён" in got[0]["title"]
    assert "Ищем другого" in got[0]["body"]
    note = _last_note(pax["id"])
    assert note is not None and "кире алынды" in note.title_ba, (
        "башкирского текста нет даже в Центре уведомлений — правило двух языков нарушено"
    )


def test_cancel_by_passenger_pushes_driver(client, user_factory, fake_redis, pushes):
    """Пассажир отменил после accept → ВОДИТЕЛЮ пуш «Пассажир отменил заказ»."""
    d, pax, order = _accepted_order(client, user_factory, fake_redis, "CpDrv", "CpPax")
    assert client.post(f"/instant/orders/{order['id']}/cancel", headers=pax["auth"],
                       json={}).status_code == 200
    got = [p for p in _status_pushes(pushes) if p["uid"] == d["id"] and p["data"]["status"] == "cancelled"]
    assert len(got) == 1
    assert "Пассажир отменил" in got[0]["body"]
    note = _last_note(d["id"])
    assert note is not None and "Пассажир заказды кире алды" in note.body_ba, (
        "башкирского текста нет даже в Центре уведомлений — правило двух языков нарушено"
    )


def test_expired_push_when_nobody_around(client, user_factory, fake_redis, pushes):
    """Рядом никого → заказ expired → пассажиру честный пуш (двуязычно, status=expired)."""
    pax = user_factory("ExpPax")
    order = _create_order(client, pax)
    assert order["status"] == "expired"
    got = [p for p in _status_pushes(pushes) if p["data"]["status"] == "expired"]
    assert len(got) == 1 and got[0]["uid"] == pax["id"]
    assert "Рядом никого" in got[0]["title"] and "водитель юҡ" in got[0]["title"]


# ============================ Дневная сводка в Telegram (B9b-3) ============================
# Местный день D → UTC-окно [D-5ч, D+19ч).
#
# Считаем ПРИРОСТ, а не абсолютные числа. Раньше тест брал фиксированный день в прошлом и
# верил, что «счётчики за него полностью наши». Это оказалось календарной миной: соседние
# тесты создают строки на `utcnow() - timedelta(days=N)`, и в день, когда сегодняшняя дата
# минус такое N попадает в окно нашего дня, чужие строки приплюсовываются. 2026-08-02
# «минус 200 дней» дало ровно 14.01.2026 — тест, зелёный полгода, покраснел сам по себе.
# Разность до и после засева от этого не зависит вообще.
DIGEST_DAY = None   # заполняется в _seed_digest_day (импорт date ниже)


def _digest_counts(day) -> dict:
    """Числа из текста сводки за день — чтобы сравнивать прирост, а не парсить строки в тесте."""
    import re

    from sqlmodel import Session

    from app import digest
    from app.db import engine
    with Session(engine) as s:
        text = digest.build_digest(s, day)
    fields = {
        "rides": r"поездок попутки (\d+)",
        "bookings": r"брони (\d+)",
        "orders": r"такси-заказов (\d+)",
        "done": r"done (\d+)",
        "cancelled": r"отмен (\d+)",
        "users": r"новых пользователей (\d+)",
        "drivers": r"водителей на линии (\d+)",
        "fee_rub": r"выручка-комиссия ~(\d+)",
        "reports": r"жалоб новых (\d+)",
    }
    out = {}
    for key, pattern in fields.items():
        m = re.search(pattern, text)
        assert m, f"в сводке нет поля {key}: {text}"
        out[key] = int(m.group(1))
    out["_text"] = text
    return out


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
    """Счётчики сводки верные: сводка выросла ровно на то, что мы насыпали в этот день."""
    from datetime import date

    day = date(2026, 1, 15)
    neighbour = date(2026, 1, 20)
    before, before_neighbour = _digest_counts(day), _digest_counts(neighbour)

    assert _seed_digest_day(user_factory) == day
    after = _digest_counts(day)

    assert "📊 Юлдаш за 15.01.2026" in after["_text"]
    assert after["rides"] - before["rides"] == 1
    assert after["bookings"] - before["bookings"] == 1
    assert after["orders"] - before["orders"] == 2
    assert after["done"] - before["done"] == 1
    assert after["cancelled"] - before["cancelled"] == 1
    assert after["users"] - before["users"] == 1
    assert after["drivers"] - before["drivers"] == 1
    assert after["fee_rub"] - before["fee_rub"] == 123
    assert after["reports"] - before["reports"] == 1

    # Окно дня не протекает: соседний день не сдвинулся ни на единицу.
    assert _digest_counts(neighbour) == before_neighbour


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


# ============================ Review-аккаунт для модерации сторов (B9b-4) ============================
REVIEW_PHONE = "+79995558877"   # уникален для сессионной БД (не пересекается с test_share/cleanup/winter)
REVIEW_CODE = "424242"


def _enable_review(monkeypatch):
    monkeypatch.setattr(settings, "review_phone", REVIEW_PHONE)
    monkeypatch.setattr(settings, "review_code", REVIEW_CODE)


def test_review_login_fixed_code_no_sms(client, monkeypatch):
    """Оба env заданы: SMS не шлётся (нет dev_code), входит ТОЛЬКО фикс-код,
    аккаунт помечен is_reviewer и остаётся обычным пассажиром без прав."""
    _enable_review(monkeypatch)
    r = client.post("/auth/request-code", json={"phone": REVIEW_PHONE})
    assert r.status_code == 200 and r.json() == {"sent": True}   # dev_code НЕ утекает
    # Неверный код → та же ошибка, что у обычного кода (режим не раскрываем).
    bad = client.post("/auth/verify", json={"phone": REVIEW_PHONE, "code": "000000"})
    assert bad.status_code == 400
    ok = client.post("/auth/verify", json={"phone": REVIEW_PHONE, "code": REVIEW_CODE})
    assert ok.status_code == 200, ok.text
    data = ok.json()
    assert data["user"]["is_reviewer"] is True
    assert data["user"]["role"] == "passenger"                   # без прав
    me = client.get("/me", headers={"Authorization": f"Bearer {data['access_token']}"})
    assert me.status_code == 200 and me.json()["is_reviewer"] is True


def test_review_login_ignores_real_otp(client, monkeypatch):
    """Даже существующий OTP для review-номера НЕ работает — только фикс-код из env."""
    from datetime import timedelta

    from sqlmodel import Session

    from app.db import engine
    from app.models import OtpCode
    from app.timeutil import utcnow
    _enable_review(monkeypatch)
    with Session(engine) as s:
        s.add(OtpCode(phone=REVIEW_PHONE, code="111111",
                      expires_at=utcnow() + timedelta(minutes=5)))
        s.commit()
    r = client.post("/auth/verify", json={"phone": REVIEW_PHONE, "code": "111111"})
    assert r.status_code == 400


def test_review_login_requires_both_env(client, monkeypatch):
    """Задан только номер (без кода) → режим ВЫКЛЮЧЕН: номер живёт обычной SMS-жизнью,
    фикс-код не подходит. Отдельный номер — прошлые тесты уже пометили REVIEW_PHONE."""
    phone = "+79990002233"
    monkeypatch.setattr(settings, "review_phone", phone)
    monkeypatch.setattr(settings, "review_code", "")
    r = client.post("/auth/request-code", json={"phone": phone})
    assert r.status_code == 200 and "dev_code" in r.json()       # обычный OTP-поток (env=dev)
    bad = client.post("/auth/verify", json={"phone": phone, "code": REVIEW_CODE})
    assert bad.status_code == 400                                # фикс-код не работает
    ok = client.post("/auth/verify", json={"phone": phone, "code": r.json()["dev_code"]})
    assert ok.status_code == 200, ok.text
    assert ok.json()["user"]["is_reviewer"] is False


def test_review_login_does_not_affect_real_numbers(client, monkeypatch):
    """Режим включён → обычные номера входят по SMS как раньше и НЕ помечаются is_reviewer."""
    _enable_review(monkeypatch)
    phone = "+79995559911"   # уникален для сессионной БД (не пересекается с test_share)
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    ok = client.post("/auth/verify", json={"phone": phone, "code": code})
    assert ok.status_code == 200, ok.text
    assert ok.json()["user"]["is_reviewer"] is False


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
