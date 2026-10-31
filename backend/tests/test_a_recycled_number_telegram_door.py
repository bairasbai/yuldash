"""Номер сменил владельца — вторая дверь, вход через Telegram (Б-2 обхода, 2026-10-13).

Вход по SMS уже умел это различать (волна 139): аккаунт молчал полгода и вход идёт с другого
устройства — номер откручивается от прежнего хозяина, вошедший получает чистый аккаунт.

Telegram-вход эту проверку обходил. Бот подтверждает номер, который человек отдал кнопкой
«Поделиться номером», а Telegram помнит номер годами. Новый владелец симки входил через бота:
  • если прежний хозяин Telegram не привязывал — прямо в ЕГО аккаунт: имя, поездки, переписка,
    доверенные контакты (и его SOS ушёл бы маме прежней хозяйки);
  • если привязывал — застревал на «поделись номером» навсегда, войти не мог вообще.

А вход по SMS на проде выключен, так что Telegram сейчас единственная дверь. Поэтому вариант
«привязывать Telegram только из вошедшего аккаунта» не годится: он запер бы снаружи всех, кто
регистрировался по SMS. Здесь та же проверка, что у SMS, — одно правило на обе двери.
"""
from __future__ import annotations

from datetime import timedelta
import itertools
import uuid

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session, select

from app import security
from app.antifraud import PHONE_RECYCLE_DAYS
from app.db import engine
from app.main import app
from app.models import Notification, TgAuth, TrustedContact, User
from app.timeutil import utcnow

CODE = "135790"
_seq = itertools.count(1)


@pytest.fixture
def phone():
    return f"+799656{next(_seq):05d}"


def _хозяйка(phone: str, *, молчала_дней: int, устройство: str = "device-gulnara",
             telegram_id: str | None = None) -> int:
    """Гульнара: аккаунт с номером, своим устройством и мамой в доверенных контактах."""
    with Session(engine) as s:
        когда = utcnow() - timedelta(days=молчала_дней)
        u = User(phone=phone, name="Гульнара", telegram_id=telegram_id,
                 last_device_id=устройство, created_at=когда, last_seen_at=когда)
        s.add(u)
        s.commit()
        s.refresh(u)
        s.add(TrustedContact(user_id=u.id, name="Мама", phone="+79990001391"))
        s.commit()
        return u.id


def _код_в_боте(phone: str, telegram_id: str, имя: str = "Айрат") -> dict:
    """Бот уже выдал код и получил номер кнопкой «Поделиться номером» (доставку не гоняем)."""
    request_id = uuid.uuid4().hex
    with Session(engine) as s:
        s.add(TgAuth(request_id=request_id, telegram_id=telegram_id, status="sent", code=CODE,
                     shared_phone=phone, first_name=имя,
                     expires_at=utcnow() + timedelta(minutes=5)))
        s.commit()
    return {"request_id": request_id, "code": CODE}


def _войти_через_telegram(client, phone: str, telegram_id: str, устройство: str | None):
    headers = {"X-Device-Id": устройство} if устройство else {}
    return client.post("/auth/tg/verify", json=_код_в_боте(phone, telegram_id), headers=headers)


RELEASE_TITLE = "Номер откреплён от аккаунта"


def _уведомлений(user_id: int) -> int:
    """Только объяснения об откреплении номера: «новый вход» сюда не считается."""
    with Session(engine) as s:
        return len(s.exec(select(Notification).where(
            Notification.user_id == user_id, Notification.title_ru == RELEASE_TITLE)).all())


def _аккаунт(user_id: int) -> User:
    with Session(engine) as s:
        return s.get(User, user_id)


def test_новый_владелец_симки_через_telegram_получает_чистый_аккаунт(client, phone):
    """Главное: купленная симка не открывает чужую жизнь и через бота."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)

    r = _войти_через_telegram(client, phone, "700000101", "device-airat")

    assert r.status_code == 200, r.text
    вошёл = r.json()["user"]
    assert вошёл["id"] != uid, (
        "новый владелец номера вошёл через Telegram в аккаунт прежней хозяйки: ему достались "
        "её имя, поездки, переписка и доверенные контакты"
    )
    assert вошёл["phone"] == phone
    прежняя = _аккаунт(uid)
    assert прежняя.phone != phone and прежняя.phone.startswith("released:"), прежняя.phone
    assert прежняя.telegram_id is None, "чужой Telegram привязали к аккаунту прежней хозяйки"
    близкие = client.get("/trusted-contacts",
                         headers={"Authorization": f"Bearer {r.json()['access_token']}"}).json()
    assert близкие == [], f"новому владельцу номера достались чужие близкие: {близкие}"


def test_прежней_хозяйке_объяснили_что_случилось(client, phone):
    """Как и у SMS: в Центре уведомлений — почему вход по номеру перестал работать."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)
    было = _уведомлений(uid)

    r = _войти_через_telegram(client, phone, "700000102", "device-airat")

    assert r.status_code == 200, r.text
    assert _уведомлений(uid) == было + 1, "прежней хозяйке не сказали, почему она больше не входит"
    with Session(engine) as s:
        свежее = s.exec(select(Notification).where(Notification.user_id == uid,
                                                   Notification.title_ru == RELEASE_TITLE)).first()
    assert свежее.title_ba and свежее.body_ba, "нет башкирского текста"
    assert "поддержк" in свежее.body_ru.lower(), "в объяснении нет пути дальше — в поддержку"


def test_привязанный_к_другому_telegram_не_запирает_нового_владельца(client, phone):
    """Раньше: прежний хозяин успел привязать свой Telegram — новый застревал навсегда."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30, telegram_id="700000106")

    r = _войти_через_telegram(client, phone, "700000103", "device-airat")

    assert r.status_code == 200, (
        f"новый владелец номера не может войти: {r.status_code} {r.text}. Номер держит аккаунт, "
        "который молчит полгода"
    )
    assert r.json()["user"]["id"] != uid
    assert r.json()["user"]["phone"] == phone
    assert _аккаунт(uid).telegram_id == "700000106", "чужой Telegram прежней хозяйки тронули"


def test_застрявший_на_поделись_номером_выходит_после_правки(client, phone):
    """Тот, кто пытался войти до правки, уже имеет заготовку аккаунта без номера."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30, telegram_id="700000107")
    with Session(engine) as s:
        заготовка = User(phone="tg700000104", name="Айрат", telegram_id="700000104")
        s.add(заготовка)
        s.commit()
        s.refresh(заготовка)
        заготовка_id = заготовка.id

    r = _войти_через_telegram(client, phone, "700000104", "device-airat")

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == заготовка_id
    assert r.json()["user"]["phone"] == phone
    assert _аккаунт(uid).phone.startswith("released:")


def test_смена_телефона_у_активного_привязывает_telegram(client, phone):
    """Обратная сторона: человек сменил телефон и впервые вошёл через бота — это он сам."""
    uid = _хозяйка(phone, молчала_дней=3)

    r = _войти_через_telegram(client, phone, "700000108", "device-new-phone")

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == uid, "человек сменил телефон и потерял свой аккаунт"
    assert _аккаунт(uid).telegram_id == "700000108"


def test_долгий_перерыв_на_том_же_устройстве_не_страшен(client, phone):
    """Сезонный пассажир на своём же телефоне — тот же человек."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 200)

    r = _войти_через_telegram(client, phone, "700000109", "device-gulnara")

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == uid


def test_старый_клиент_без_отметки_устройства_не_наказан(client, phone):
    """Устройство не назвали — судить не по чему: цена ошибки слишком велика."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)

    r = _войти_через_telegram(client, phone, "700000110", None)

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == uid


def test_активный_аккаунт_с_другим_telegram_номер_не_отдаёт(client, phone):
    """Номер держит живой аккаунт с другим Telegram — отдавать его нельзя, как и раньше."""
    uid = _хозяйка(phone, молчала_дней=3, telegram_id="700000111")

    r = _войти_через_telegram(client, phone, "700000112", "device-stranger")

    assert r.status_code == 403, r.text
    assert r.json()["detail"]["code"] == "phone_required"
    прежняя = _аккаунт(uid)
    assert прежняя.phone == phone and прежняя.telegram_id == "700000111"


def test_сбой_выдачи_ключей_не_отвязывает_номер(client, monkeypatch, phone):
    """Вход не состоялся — значит, и номер у прежней хозяйки никто не забирал."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)
    было = _уведомлений(uid)

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic login signature failure")

    monkeypatch.setattr(security, "make_token", fail)
    requester = TestClient(app, raise_server_exceptions=False)
    try:
        r = requester.post("/auth/tg/verify", json=_код_в_боте(phone, "700000105"),
                           headers={"X-Device-Id": "device-airat"})
    finally:
        requester.close()

    assert r.status_code == 500
    прежняя = _аккаунт(uid)
    assert прежняя.phone == phone, "вход упал, а номер у прежней хозяйки уже отобрали"
    assert _уведомлений(uid) == было, "прежней хозяйке сообщили об отвязке, которой не было"


def _через_настоящего_бота(client, phone: str, telegram_id: str, устройство: str):
    """tg/start → вебхук /start → вебхук «Поделиться номером» → verify, без подстановок в базу."""
    request_id = client.post("/auth/tg/start").json()["request_id"]
    старт = client.post("/telegram/webhook", json={"message": {
        "text": "/start " + request_id, "from": {"id": int(telegram_id), "first_name": "Айрат"},
        "chat": {"id": int(telegram_id), "type": "private"}}})
    assert старт.status_code == 200, старт.text
    номер = client.post("/telegram/webhook", json={"message": {
        "from": {"id": int(telegram_id)}, "chat": {"id": int(telegram_id), "type": "private"},
        "contact": {"user_id": int(telegram_id), "phone_number": phone.lstrip("+")}}})
    assert номер.status_code == 200, номер.text
    with Session(engine) as s:
        код = s.exec(select(TgAuth).where(TgAuth.request_id == request_id)).one().code
    return client.post("/auth/tg/verify", json={"request_id": request_id, "code": код},
                       headers={"X-Device-Id": устройство})


def test_застрявшая_заготовка_выходит_через_настоящего_бота(client, phone):
    """Сквозной путь: бот получил номер кнопкой, а у человека уже есть заготовка tg<id>.

    Раньше вебхук писал номер прямо в заготовку, при занятом номере молча ничего не делал и не
    запоминал его на сессии входа — verify приходил без номера и отвечал «поделись номером»,
    хотя бот только что сказал «Номер сохранён ✅».
    """
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30, telegram_id="700000120")
    with Session(engine) as s:
        заготовка = User(phone="tg700000121", name="Айрат", telegram_id="700000121")
        s.add(заготовка)
        s.commit()
        s.refresh(заготовка)
        заготовка_id = заготовка.id

    r = _через_настоящего_бота(client, phone, "700000121", "device-airat")

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == заготовка_id
    assert r.json()["user"]["phone"] == phone
    assert _аккаунт(uid).phone.startswith("released:")


def test_новый_владелец_через_настоящего_бота(client, phone):
    """Главный сценарий тем же сквозным путём: чужой аккаунт не открывается."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)

    r = _через_настоящего_бота(client, phone, "700000122", "device-airat")

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] != uid
    assert _аккаунт(uid).telegram_id is None


def _прежний_снимок(monkeypatch, holder_id: int):
    """Гонка на PostgreSQL: наш поиск ещё видел прежнего хозяина с номером, а параллельный вход
    уже открепил номер и закоммитил. Подменяем только ПЕРВЫЙ поиск — тот, что до блокировки."""
    import app.routers.auth as auth_mod
    настоящий = auth_mod.find_user_by_phone
    вызовов = {"n": 0}

    def снимок_до_блокировки(session, raw, **kw):
        вызовов["n"] += 1
        if вызовов["n"] == 1:
            return session.get(User, holder_id)
        return настоящий(session, raw, **kw)

    monkeypatch.setattr(auth_mod, "find_user_by_phone", снимок_до_блокировки)


def _параллельно_открепили(phone: str, holder_id: int) -> int:
    from app.antifraud import release_phone
    with Session(engine) as s:
        release_phone(s, s.get(User, holder_id), commit=True, notify=False)
        победитель = User(phone=phone, name="Айрат")
        s.add(победитель)
        s.commit()
        s.refresh(победитель)
        return победитель.id


def test_гонка_telegram_не_отдаёт_откреплённый_аккаунт(client, monkeypatch, phone):
    """Без повторной сверки после блокировки Telegram привязывался ВНУТРЬ прежнего аккаунта."""
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)
    _параллельно_открепили(phone, uid)
    _прежний_снимок(monkeypatch, uid)

    r = _войти_через_telegram(client, phone, "700000123", "device-airat-2")

    assert not (r.status_code == 200 and r.json()["user"]["id"] == uid), (
        "вошедший попал в аккаунт прежней хозяйки, номер которой уже открепили"
    )
    assert _аккаунт(uid).telegram_id is None, "чужой Telegram привязали к откреплённому аккаунту"


def test_гонка_sms_не_отдаёт_откреплённый_аккаунт(client, monkeypatch, phone):
    """Та же гонка у входа по SMS — правило одно на обе двери."""
    from app.models import OtpCode
    uid = _хозяйка(phone, молчала_дней=PHONE_RECYCLE_DAYS + 30)
    победитель_id = _параллельно_открепили(phone, uid)
    with Session(engine) as s:
        s.add(OtpCode(phone=phone, code=CODE, expires_at=utcnow() + timedelta(minutes=5)))
        s.commit()
    _прежний_снимок(monkeypatch, uid)

    r = client.post("/auth/verify", json={"phone": phone, "code": CODE},
                    headers={"X-Device-Id": "device-airat-3"})

    assert r.status_code == 200, r.text
    assert r.json()["user"]["id"] == победитель_id, (
        f"вошли в {r.json()['user']['id']}, а номер сейчас у {победитель_id} "
        f"(прежняя хозяйка — {uid})"
    )


# RS-01: остальные проверки входа требуют настроенного синтетического бота.
pytestmark = pytest.mark.usefixtures("telegram_login_ready")
