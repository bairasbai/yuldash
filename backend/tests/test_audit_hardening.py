# -*- coding: utf-8 -*-
"""Аудит 2026-07-26 — тесты фиксов ревью release-2026-07.

Покрываем: Telegram-✅ активирует ВСЕ назначения платежа (подписка бизнеса, комиссия курьера);
админ-confirm не активирует карточные платежи провайдера; жизненный цикл споров (мир только до
вердикта, апелляция один раз и только на решение, «оставить в силе» не наказывает дважды);
пауза лестницы реально блокирует действия; удаление доверенного контакта; отзыв/перевыпуск
трекинг-ссылки посылки; отвязка push-токена при выходе; чат-пуш несёт data.type=chat.
"""
from datetime import timedelta

from sqlmodel import Session, select

from app import models as M
from app.db import engine
from app.models import BookingStatus, UserRole
from app.timeutil import utcnow


def _booking(passenger_id, driver_id, status=BookingStatus.done):
    with Session(engine) as s:
        ride = M.Ride(driver_id=driver_id, from_city="A", to_city="B", depart_at=utcnow())
        s.add(ride); s.commit(); s.refresh(ride)
        b = M.Booking(ride_id=ride.id, passenger_id=passenger_id, status=status)
        s.add(b); s.commit(); s.refresh(b)
        return b.id


def _tg_callback(monkeypatch, payment_id: int):
    """Синтетический тап админа по кнопке «✅ Подтвердить» в Telegram (pay:ok:{id})."""
    from app.config import settings
    from app.routers import auth as auth_router
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "777001")
    monkeypatch.setattr(auth_router, "_telegram_api", lambda method, payload: None)
    return auth_router._handle_admin_callback({
        "id": "cb-test", "from": {"id": 777001}, "data": f"pay:ok:{payment_id}",
        "message": {"chat": {"id": 777001}, "message_id": 1},
    })


# ============== Telegram-✅ активирует ВСЕ назначения (не только boost/ad) ==============
def test_tg_pay_ok_activates_partner_sub(client, user_factory, monkeypatch):
    """Раньше локальная копия активатора знала только boost/ad: бизнес платил по СБП,
    админ жал ✅ — платёж succeeded, а подписка НЕ продлевалась (витрина закрыта)."""
    owner = user_factory("BizOwner")
    with Session(engine) as s:
        partner = M.Partner(owner_id=owner["id"], name="Пекарня", city="Баймак", status="active")
        s.add(partner); s.commit(); s.refresh(partner)
        pay = M.Payment(user_id=owner["id"], purpose="partner_sub", partner_id=partner.id,
                        tier="basic", amount_kop=99_000, status="pending")
        s.add(pay); s.commit(); s.refresh(pay)
        partner_id, pay_id = partner.id, pay.id
    _tg_callback(monkeypatch, pay_id)
    with Session(engine) as s:
        assert s.get(M.Payment, pay_id).status == "succeeded"
        p = s.get(M.Partner, partner_id)
        assert p.subscription_until is not None and p.subscription_until > utcnow() + timedelta(days=29)
        assert p.subscription_plan == "basic"


def test_tg_pay_ok_activates_courier_commission(client, user_factory, monkeypatch):
    """Тот же класс: ✅ по комиссии курьера должен гасить commission_paid его доставок."""
    courier = user_factory("Courier")
    sender = user_factory("Sender")
    with Session(engine) as s:
        pd = M.ParcelDelivery(sender_id=sender["id"], courier_id=courier["id"],
                              from_city="A", to_city="B", status="delivered",
                              delivered_at=utcnow() - timedelta(hours=2), commission_kop=5000)
        s.add(pd); s.commit(); s.refresh(pd)
        pay = M.Payment(user_id=courier["id"], purpose="courier_commission",
                        amount_kop=5000, method="sbp", status="pending")
        s.add(pay); s.commit(); s.refresh(pay)
        pd_id, pay_id = pd.id, pay.id
    _tg_callback(monkeypatch, pay_id)
    with Session(engine) as s:
        assert s.get(M.Payment, pay_id).status == "succeeded"
        assert s.get(M.ParcelDelivery, pd_id).commission_paid is True


def test_tg_pay_ok_refuses_provider_payment(client, user_factory, monkeypatch):
    """Карточный платёж (создан у ЮKassa, есть provider_id) руками не активируется —
    его судьбу знает вебхук. Иначе тап ✅ = начисление без денег."""
    u = user_factory("CardPayer")
    with Session(engine) as s:
        pay = M.Payment(user_id=u["id"], purpose="boost", amount_kop=10_000,
                        method="yookassa", provider_id="yk-prov-1", status="pending")
        s.add(pay); s.commit(); s.refresh(pay)
        pay_id = pay.id
    _tg_callback(monkeypatch, pay_id)
    with Session(engine) as s:
        assert s.get(M.Payment, pay_id).status == "pending"   # не активирован


# ============== Админ-ручки: карточные pending скрыты и не подтверждаются ==============
def test_admin_confirm_guards_provider_payments(client, user_factory):
    admin = user_factory("PayAdmin", role=UserRole.admin)
    u = user_factory("Payer")
    with Session(engine) as s:
        manual = M.Payment(user_id=u["id"], purpose="donate", amount_kop=5000, status="pending")
        card = M.Payment(user_id=u["id"], purpose="boost", amount_kop=10_000,
                         method="yookassa", provider_id="yk-prov-2", status="pending")
        s.add(manual); s.add(card); s.commit(); s.refresh(manual); s.refresh(card)
        manual_id, card_id = manual.id, card.id
    pend = client.get("/admin/payments/pending", headers=admin["auth"]).json()
    ids = {p["payment_id"] for p in pend}
    assert manual_id in ids and card_id not in ids
    assert client.post(f"/admin/payments/{card_id}/confirm", headers=admin["auth"]).status_code == 409
    assert client.post(f"/admin/payments/{manual_id}/confirm", headers=admin["auth"]).status_code == 200


# ============== Жизненный цикл споров ==============
def _resolved_strike(client, user_factory):
    drv = user_factory("LcDrv", role=UserRole.driver)
    pax = user_factory("LcPax")
    admin = user_factory("LcAdmin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    iid = client.post("/incidents", headers=pax["auth"],
                      json={"respondent_id": drv["id"], "type": "rude", "booking_id": bid}).json()["id"]
    r = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                    json={"resolution": "strike", "fault": "respondent", "note": "подтверждено"})
    assert r.status_code == 200
    return drv, pax, admin, iid


def test_withdraw_only_before_verdict(client, user_factory):
    """«Мы решили миром» после вердикта админа не перетирает решение (409):
    иначе давление на заявителя стирало resolved-неявку из «Надёжности»."""
    drv, pax, admin, iid = _resolved_strike(client, user_factory)
    assert client.post(f"/incidents/{iid}/withdraw", headers=pax["auth"]).status_code == 409


def test_appeal_only_from_resolved_and_once(client, user_factory):
    drv = user_factory("ApDrv", role=UserRole.driver)
    pax = user_factory("ApPax")
    bid = _booking(pax["id"], drv["id"])
    iid = client.post("/incidents", headers=pax["auth"],
                      json={"respondent_id": drv["id"], "type": "rude", "booking_id": bid}).json()["id"]
    # До вердикта апелляции нет.
    assert client.post(f"/incidents/{iid}/appeal", headers=drv["auth"], json={"text": "рано"}).status_code == 409
    admin = user_factory("ApAdmin", role=UserRole.admin)
    client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                json={"resolution": "strike", "fault": "respondent"})
    assert client.post(f"/incidents/{iid}/appeal", headers=drv["auth"], json={"text": "не согласен"}).status_code == 200
    # Второй раз — нет (спам админ-канала + вечно «активный» спор).
    assert client.post(f"/incidents/{iid}/appeal", headers=drv["auth"], json={"text": "ещё раз"}).status_code == 409


def test_appeal_upheld_does_not_double_punish(client, user_factory):
    """«Оставить в силе» после апелляции не добавляет второй страйк за тот же спор."""
    drv, pax, admin, iid = _resolved_strike(client, user_factory)
    assert client.get("/me/standing", headers=drv["auth"]).json()["strikes"] == 1
    client.post(f"/incidents/{iid}/appeal", headers=drv["auth"], json={"text": "не согласен"})
    r = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                    json={"resolution": "strike", "fault": "respondent", "note": "оставлено в силе"})
    assert r.status_code == 200
    assert client.get("/me/standing", headers=drv["auth"]).json()["strikes"] == 1   # не 2!


def test_appeal_overturned_removes_strike(client, user_factory):
    """Апелляция удовлетворена (dismissed) → страйк этого спора снят."""
    drv, pax, admin, iid = _resolved_strike(client, user_factory)
    client.post(f"/incidents/{iid}/appeal", headers=drv["auth"], json={"text": "оболгали"})
    r = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                    json={"resolution": "dismissed", "fault": "none", "note": "оправдан"})
    assert r.status_code == 200
    assert client.get("/me/standing", headers=drv["auth"]).json()["strikes"] == 0


def test_fault_reporter_with_punishment_rejected(client, user_factory):
    """«Виноват заявитель» + карательное решение → 422 (страйк лёг бы на невиновного обвинённого)."""
    drv = user_factory("FrDrv", role=UserRole.driver)
    pax = user_factory("FrPax")
    admin = user_factory("FrAdmin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    iid = client.post("/incidents", headers=pax["auth"],
                      json={"respondent_id": drv["id"], "type": "rude", "booking_id": bid}).json()["id"]
    r = client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                    json={"resolution": "strike", "fault": "reporter"})
    assert r.status_code == 422


def test_suspension_blocks_new_activity(client, user_factory):
    """Пауза лестницы реально блокирует публикацию поездки и заявку (раньше — декорация)."""
    drv = user_factory("SusDrv", role=UserRole.driver)
    pax = user_factory("SusPax")
    admin = user_factory("SusAdmin", role=UserRole.admin)
    bid = _booking(pax["id"], drv["id"])
    iid = client.post("/incidents", headers=pax["auth"],
                      json={"respondent_id": drv["id"], "type": "rude", "booking_id": bid}).json()["id"]
    client.post(f"/admin/incidents/{iid}/resolve", headers=admin["auth"],
                json={"resolution": "suspend", "fault": "respondent", "note": "пауза"})
    r = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300,
    })
    assert r.status_code == 403
    assert client.post("/requests", headers=drv["auth"],
                       json={"from_city": "Баймак", "to_city": "Сибай"}).status_code == 403
    # Чистый пользователь без SafetyProfile проходит как раньше (гейт не создаёт строк).
    clean = user_factory("CleanDrv", role=UserRole.driver)
    ok = client.post("/rides", headers=clean["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300,
    })
    assert ok.status_code == 200
    with Session(engine) as s:
        assert s.exec(select(M.SafetyProfile).where(M.SafetyProfile.user_id == clean["id"])).first() is None


# ============== Доверенные контакты: удаление ==============
def test_trusted_contact_delete(client, user_factory):
    u = user_factory("ContactOwner")
    stranger = user_factory("Stranger")
    cid = client.post("/trusted-contacts", headers=u["auth"],
                      json={"name": "Мама", "phone": "+79990001122"}).json()["id"]
    # Чужой удалить не может (404 — не раскрываем существование).
    assert client.delete(f"/trusted-contacts/{cid}", headers=stranger["auth"]).status_code == 404
    assert client.delete(f"/trusted-contacts/{cid}", headers=u["auth"]).status_code == 200
    assert client.get("/trusted-contacts", headers=u["auth"]).json() == []


# ============== Трекинг-ссылка посылки: отзыв и перевыпуск ==============
def test_parcel_track_link_revoke_and_reissue(client, user_factory, monkeypatch):
    from app.routers import family as family_router
    monkeypatch.setattr(family_router, "send_text", lambda phone, text: None)
    sender = user_factory("TrackSender")
    from test_parcels import _create_parcel
    pid = _create_parcel(client, sender, receiver_phone="+79990002002").json()["id"]
    token = client.post(f"/parcels/{pid}/track-link", headers=sender["auth"]).json()["token"]
    assert client.get(f"/t/{token}").status_code == 200
    # Отзыв (опечатка в номере → ссылка у чужого): токен сгорает.
    r = client.delete(f"/parcels/{pid}/track-link", headers=sender["auth"])
    assert r.status_code == 200 and r.json()["revoked"] == 1
    assert client.get(f"/t/{token}").status_code == 404
    # Повторный запрос — НОВЫЙ токен.
    token2 = client.post(f"/parcels/{pid}/track-link", headers=sender["auth"]).json()["token"]
    assert token2 and token2 != token
    # Протухшая ссылка при живой посылке: дедуп продлевает срок ТОГО ЖЕ токена.
    with Session(engine) as s:
        share = s.exec(select(M.TripShare).where(M.TripShare.parcel_id == pid)).first()
        share.expires_at = utcnow() - timedelta(hours=1)
        s.add(share); s.commit()
    assert client.get(f"/t/{token2}").status_code == 404          # сгорела
    again = client.post(f"/parcels/{pid}/track-link", headers=sender["auth"]).json()
    assert again["token"] == token2                               # тот же токен из SMS получателя
    assert client.get(f"/t/{token2}").status_code == 200          # снова живая
    # Чужому посылку не отозвать.
    other = user_factory("NotSender")
    assert client.delete(f"/parcels/{pid}/track-link", headers=other["auth"]).status_code == 404


# ============== Push-токен: отвязка при выходе ==============
def test_push_unregister(client, user_factory):
    a = user_factory("PushA")
    b = user_factory("PushB")
    assert client.post("/push/register", headers=a["auth"], json={"token": "fcm-tok-a1"}).status_code == 200
    # Чужой токен не отвяжешь (идемпотентный ok, но строка живёт).
    client.post("/push/unregister", headers=b["auth"], json={"token": "fcm-tok-a1"})
    with Session(engine) as s:
        assert s.exec(select(M.DeviceToken).where(M.DeviceToken.token == "fcm-tok-a1")).first() is not None
    # Свой — отвязывается: чужие пуши на общий телефон больше не приходят.
    assert client.post("/push/unregister", headers=a["auth"], json={"token": "fcm-tok-a1"}).status_code == 200
    with Session(engine) as s:
        assert s.exec(select(M.DeviceToken).where(M.DeviceToken.token == "fcm-tok-a1")).first() is None


# ============== Чат-пуш несёт data.type=chat (канал «Сообщения») ==============
def test_chat_push_carries_chat_type(client, user_factory, monkeypatch):
    captured = []
    monkeypatch.setattr("app.services.send_push",
                        lambda session, uid, title, body, data=None, **kw: captured.append(data))
    drv = user_factory("ChDrv", role=UserRole.driver)
    pax = user_factory("ChPax")
    bid = _booking(pax["id"], drv["id"], status=BookingStatus.confirmed)
    r = client.post(f"/bookings/{bid}/messages", headers=pax["auth"], json={"text": "Привет!"})
    assert r.status_code == 200, r.text
    assert captured and captured[-1] == {"type": "chat", "id": bid}


# ============== Порт из notification-fixes: язык пользователя + двуязычный пуш ==============
def test_me_update_language(client, user_factory):
    u = user_factory("LangUser")
    r = client.post("/me/update", headers=u["auth"], json={"language": "ba"})
    assert r.status_code == 200 and r.json()["language"] == "ba"
    # Мусорный язык молча игнорируется (остаётся прежний).
    r2 = client.post("/me/update", headers=u["auth"], json={"language": "xx"})
    assert r2.status_code == 200 and r2.json()["language"] == "ba"


def test_push_notification_uses_recipient_language(client, user_factory, monkeypatch):
    """BA-пользователю пуш уходит на башкирском; RU (и пустой BA) — на русском."""
    sent = []
    monkeypatch.setattr("app.services.send_push",
                        lambda session, uid, title, body, data=None, **kw: sent.append((title, body)))
    from app.services import push_notification
    ba_user = user_factory("BaUser")
    ru_user = user_factory("RuUser")
    client.post("/me/update", headers=ba_user["auth"], json={"language": "ba"})
    with Session(engine) as s:
        push_notification(s, ba_user["id"], "test", "Привет", "Сәләм", "тело", "тәне")
        push_notification(s, ru_user["id"], "test", "Привет", "Сәләм", "тело", "тәне")
        push_notification(s, ba_user["id"], "test", "Привет", "", "тело", "")   # пустой BA → фолбэк RU
    assert sent[0] == ("Сәләм", "тәне")
    assert sent[1] == ("Привет", "тело")
    assert sent[2] == ("Привет", "тело")


# ============== Порт из pr88: фото-доказательства споров ==============
_JPG = bytes.fromhex("ffd8ffe000104a464946000101") + b"\x00" * 24


def _upload_evidence(client, auth):
    r = client.post("/upload/evidence", headers=auth,
                    files={"file": ("proof.jpg", _JPG, "image/jpeg")}, data={"ext": "jpg"})
    assert r.status_code == 200, r.text
    return r.json()["url"]


def test_evidence_flow_private_to_parties(client, user_factory):
    """Фото прикладывается к спору, видно сторонам и админу, чужому — 403;
    внешние URL молча отбрасываются (анти-деанон по IP)."""
    drv = user_factory("EvDrv", role=UserRole.driver)
    pax = user_factory("EvPax")
    admin = user_factory("EvAdmin", role=UserRole.admin)
    stranger = user_factory("EvStranger")
    bid = _booking(pax["id"], drv["id"])
    url = _upload_evidence(client, pax["auth"])
    assert "/secure/evidence/" in url
    r = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": bid,
        "evidence_urls": [url, "http://evil.example/x.png"],   # внешний должен отпасть
    })
    assert r.status_code == 200, r.text
    inc = r.json()
    assert inc["evidence_urls"] == [url]
    # Обвинённый объясняется со своим фото (право на защиту).
    url2 = _upload_evidence(client, drv["auth"])
    r2 = client.post(f"/incidents/{inc['id']}/respond", headers=drv["auth"],
                     json={"statement": "вот моя версия", "evidence_urls": [url2]})
    assert r2.status_code == 200 and r2.json()["respondent_evidence_urls"] == [url2]
    # Выдача файла: стороны и админ — 200, чужой — 403.
    name = url.rsplit("/", 1)[-1]
    assert client.get(f"/secure/evidence/{name}", headers=pax["auth"]).status_code == 200
    assert client.get(f"/secure/evidence/{name}", headers=drv["auth"]).status_code == 200
    assert client.get(f"/secure/evidence/{name}", headers=admin["auth"]).status_code == 200
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403
    # Файл, не приложенный ни к одному спору, не виден даже загрузившему (нет спора — нет доступа).
    orphan = _upload_evidence(client, stranger["auth"]).rsplit("/", 1)[-1]
    assert client.get(f"/secure/evidence/{orphan}", headers=stranger["auth"]).status_code == 403


# ============== Порт из pr88: белый список причин отмены ==============
def test_cancel_reason_whitelist(client, user_factory):
    """Известный код хранится как есть; произвольная строка → 'other' (гигиена БД)."""
    drv = user_factory("CrDrv", role=UserRole.driver)
    rid = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300,
    }).json()["id"]
    pax = user_factory("CrPax")
    bid = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/cancel", headers=pax["auth"], json={"reason": "plans_changed"})
    with Session(engine) as s:
        assert s.get(M.Booking, bid).cancel_reason == "plans_changed"
    pax2 = user_factory("CrPax2")
    bid2 = client.post("/bookings", headers=pax2["auth"], json={"ride_id": rid, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid2}/cancel", headers=pax2["auth"], json={"reason": "какой-то мусор <script>"})
    with Session(engine) as s:
        assert s.get(M.Booking, bid2).cancel_reason == "other"


# ============== Пробелы такси/курьера: чаевые в такси, споры по доставке ==============
def _done_order(passenger_id, driver_id):
    with Session(engine) as s:
        o = M.InstantOrder(passenger_id=passenger_id, driver_id=driver_id,
                           from_lat=54.0, from_lng=55.0, to_lat=54.1, to_lng=55.1,
                           status=M.InstantOrderStatus.done, price_estimate=200)
        s.add(o); s.commit(); s.refresh(o)
        return o.id


def test_taxi_thanks_and_tip_info(client, user_factory, monkeypatch):
    """«Рәхмәт» после такси: раньше благодарность была только у попуток."""
    monkeypatch.setattr("app.routers.family.send_push", lambda *a, **k: None)
    drv = user_factory("TipDrv", role=UserRole.driver)
    pax = user_factory("TipPax")
    other = user_factory("TipOther")
    oid = _done_order(pax["id"], drv["id"])
    info = client.get(f"/instant/orders/{oid}/tip", headers=pax["auth"])
    assert info.status_code == 200 and info.json()["already_thanked"] is False
    # Денежные чаевые за флагом (по умолчанию выключены) — реквизит наружу не идёт.
    assert info.json()["money"] is None
    r = client.post(f"/instant/orders/{oid}/thanks", headers=pax["auth"])
    assert r.status_code == 200 and r.json()["already"] is False
    assert client.post(f"/instant/orders/{oid}/thanks", headers=pax["auth"]).json()["already"] is True
    # Чужой поблагодарить не может.
    assert client.post(f"/instant/orders/{oid}/thanks", headers=other["auth"]).status_code == 403


def test_incident_can_be_filed_for_parcel(client, user_factory):
    """Спор по доставке: типы parcel_damage/parcel_lost были заведены, но недостижимы —
    код требовал booking_id и отвечал 400. Теперь у спора есть привязка к посылке."""
    from app.routers.incidents import create_incident
    sender = user_factory("PdSender")
    courier = user_factory("PdCourier")
    with Session(engine) as s:
        p = M.ParcelDelivery(sender_id=sender["id"], courier_id=courier["id"],
                             from_city="Акъяр", to_city="Сибай", status="delivered")
        s.add(p); s.commit(); s.refresh(p)
        pid = p.id
        u = s.get(M.User, sender["id"])
        inc = create_incident(s, reporter=u, respondent_id=courier["id"],
                              type="parcel_damage", description="банка разбилась",
                              parcel_id=pid, rate_limit=False)
        assert inc.parcel_id == pid and inc.booking_id is None
        inc_id = inc.id
    out = client.get("/incidents/mine", headers=sender["auth"]).json()
    mine = [i for i in out if i["id"] == inc_id]
    assert mine and "📦" in (mine[0]["booking_route"] or ""), "в подписи спора виден маршрут доставки"


def test_taxi_docs_expiry_blocks_taxi_only(client, user_factory):
    """Просроченные документы снимают допуск к ТАКСИ, попутка продолжает работать."""
    from app.taxi import is_approved_taxi_driver, taxi_docs_expired
    drv = user_factory("DocDrv", role=UserRole.driver)   # user_factory сам создаёт approved-заявку
    with Session(engine) as s:
        assert is_approved_taxi_driver(s, drv["id"]) is True
        app_row = s.exec(select(M.TaxiApplication).where(M.TaxiApplication.user_id == drv["id"])).first()
        app_row.docs_expired = True
        s.add(app_row); s.commit()
        assert is_approved_taxi_driver(s, drv["id"]) is False
        assert taxi_docs_expired(s, drv["id"]) is True
    # Попутка не завязана на разрешение такси — публикация проходит.
    ok = client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": "2030-01-01T10:00:00", "seats_total": 3, "price": 300,
    })
    assert ok.status_code == 200


# ============== Безопасность: SOS-лента и сохранность улик (аудит 2026-07-26) ==============
def test_admin_sos_feed_and_handle(client, user_factory, monkeypatch):
    """Раньше SOS уходил ОДНИМ сообщением в Telegram: списка не было, статус не менялся никогда —
    админ уснул, и следа о происшествии не оставалось нигде."""
    monkeypatch.setattr("app.routers.safety._send_sos_sms", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **k: None)
    admin = user_factory("SosAdmin", role=UserRole.admin)
    pax = user_factory("SosPax")
    plain = user_factory("SosPlain")
    r = client.post("/sos", headers=pax["auth"], json={"category": "medical", "note": "плохо стало"})
    assert r.status_code == 200, r.text
    sos_id = r.json()["id"]
    assert client.get("/admin/sos", headers=plain["auth"]).status_code == 403
    feed = client.get("/admin/sos", headers=admin["auth"]).json()
    row = next((x for x in feed if x["id"] == sos_id), None)
    assert row is not None and row["status"] == "open"
    assert row["user_phone"] and row["category"] == "medical"   # админу нужен телефон, чтобы позвонить
    h = client.post(f"/admin/sos/{sos_id}/handle", headers=admin["auth"],
                    json={"note": "дозвонился, помощь выехала"})
    assert h.status_code == 200 and h.json()["status"] == "handled"
    assert client.post(f"/admin/sos/{sos_id}/handle", headers=admin["auth"]).json()["already"] is True
    # После обработки сигнал уходит из ленты «открытых» — видно, что разобрано.
    assert all(x["id"] != sos_id for x in client.get("/admin/sos", headers=admin["auth"]).json())


def test_deleting_account_keeps_reports_against_you(client, user_factory):
    """Нарушитель удаляет аккаунт → жалобы НА НЕГО остаются (обезличенными), его собственные —
    стираются. Раньше удалялись обе стороны: три жалобы за поведение → «удалить» → чисто."""
    from app.account import delete_user_account
    bad = user_factory("EvidenceBad")
    victim = user_factory("EvidenceVictim")
    with Session(engine) as s:
        against = M.Report(reporter_id=victim["id"], target_user_id=bad["id"],
                           category="rude", reason="грубил и кричал")
        own = M.Report(reporter_id=bad["id"], target_user_id=victim["id"],
                       category="other", reason="моя жалоба")
        s.add(against); s.add(own); s.commit()
        s.refresh(against); s.refresh(own)
        against_id, own_id = against.id, own.id
        delete_user_account(s, s.get(M.User, bad["id"]))
    with Session(engine) as s:
        kept = s.get(M.Report, against_id)
        assert kept is not None, "жалоба на нарушителя не должна исчезать вместе с аккаунтом"
        assert kept.target_user_id is None and kept.reason == "", "но должна быть обезличена"
        assert kept.category == "rude", "категория и факт разбора сохраняются"
        assert s.get(M.Report, own_id) is None, "свои жалобы — это его данные, их стираем"
