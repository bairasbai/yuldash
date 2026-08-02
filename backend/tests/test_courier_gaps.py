# -*- coding: utf-8 -*-
"""Курьер и посылки — блокеры аудита 2026-07-26.

Ветка «что-то пошло не так», которой раньше не было вообще:
- курьер заболел / замело дорогу → снять себя с заказа было НЕЛЬЗЯ (посылка мертва навсегда);
- получателя нет дома → возврата не существовало, заказ вечно «в пути»;
- отправитель отменял на полпути бесплатно, курьер проезжал 40 км даром;
- приостановленный за нарушения спокойно брал новые посылки.
"""
import pytest
from sqlmodel import Session, select

from app import models as M
from app.config import settings
from app.db import engine
from app.models import UserRole


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


@pytest.fixture(autouse=True)
def _quiet(monkeypatch):
    """Пуши/телеграм глушим — проверяем состояние заказов, а не доставку уведомлений."""
    monkeypatch.setattr("app.routers.parcels.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.parcels.notify_admin_telegram", lambda *a, **k: None)


def _make_courier(client, user_factory, name="ГапКурьер"):
    admin = user_factory(name="ГапАдмин", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": "secure/docs/s.jpg"}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve", headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"], json={"zone": "region"}).status_code == 200
    return c


def _order(client, sender, **ov):
    body = {
        "from_city": "Акъяр", "to_city": "Сибай",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
        "size": "small", "description": "Лекарство",
        "receiver_name": "Гөлнара", "receiver_phone": "+79990004455",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    r = client.post("/courier/orders", headers=sender["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()


def _parcel(pid) -> M.ParcelDelivery:
    with Session(engine) as s:
        return s.get(M.ParcelDelivery, pid)


# ============== Курьер может отказаться ==============
def test_courier_can_release_order(client, user_factory):
    """Замело дорогу / заболел: курьер снимает себя, посылка возвращается в общий список.
    Раньше снятия courier_id не было НИГДЕ — заказ оставался мёртвым навсегда."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ГапОтпр")
    pid = _order(client, sender)["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    r = client.post(f"/parcels/{pid}/release", headers=courier["auth"],
                    json={"reason": "дорогу замело"})
    assert r.status_code == 200, r.text
    p = _parcel(pid)
    assert p.courier_id is None and p.status == "created"
    # Посылка снова видна другим курьерам (курьерские заказы — в /courier/available;
    # /parcels/available — это лента «по пути» для попутчиков).
    other = _make_courier(client, user_factory, name="ГапКурьер2")
    avail = client.get("/courier/available", headers=other["auth"])
    assert avail.status_code == 200, avail.text
    assert any(x["id"] == pid for x in avail.json())


def test_release_only_by_assigned_courier(client, user_factory):
    """Чужую посылку не снять — иначе можно было бы «освобождать» чужие заказы."""
    courier = _make_courier(client, user_factory)
    stranger = _make_courier(client, user_factory, name="ГапЧужой")
    sender = user_factory(name="ГапОтпр2")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert client.post(f"/parcels/{pid}/release", headers=stranger["auth"]).status_code == 404


# ============== Возврат посылки ==============
def test_return_flow_closes_order_without_commission(client, user_factory):
    """Получателя нет дома → курьер везёт обратно и закрывает заказ. Комиссию за возврат
    не берём: услуга не оказана. Раньше заказ навис бы «в пути» навсегда."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ГапОтпр3")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"})
    r1 = client.post(f"/parcels/{pid}/return-start", headers=courier["auth"],
                     json={"reason": "получателя нет дома"})
    assert r1.status_code == 200, r1.text
    p = _parcel(pid)
    assert p.status == "returning" and p.delivery_attempts == 1
    assert "нет дома" in p.return_reason
    # Пока везёт обратно — посылка остаётся в списке «что везу» (иначе она пропадает из виду).
    assert any(x["id"] == pid for x in client.get("/parcels/carrying", headers=courier["auth"]).json())
    r2 = client.post(f"/parcels/{pid}/return-done", headers=courier["auth"])
    assert r2.status_code == 200, r2.text
    p = _parcel(pid)
    assert p.status == "returned" and p.returned_at is not None
    assert p.commission_kop == 0 and p.commission_paid is True
    recent = client.get("/parcels/carrying?include_recent=true", headers=courier["auth"])
    returned = next(x for x in recent.json() if x["id"] == pid)
    assert returned["status"] == "returned"
    assert returned["return_reason"] == "получателя нет дома"
    # Идемпотентно: повтор не ломает состояние.
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 200


def test_return_done_requires_return_start(client, user_factory):
    """Нельзя «вернул» без «везу обратно» — иначе статус скакал бы мимо реального пути."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ГапОтпр4")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert client.post(f"/parcels/{pid}/return-done", headers=courier["auth"]).status_code == 409


# ============== Компенсация курьеру за отмену ==============
def test_cancel_after_accept_records_courier_fee(client, user_factory):
    """Отправитель отменил, когда курьер уже выехал → фиксируем компенсацию.
    Раньше отмена была бесплатной на любой стадии: курьер проехал 40 км и получил только пуш."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ГапОтпр5")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    r = client.post(f"/parcels/{pid}/cancel", headers=sender["auth"])
    assert r.status_code == 200, r.text
    assert _parcel(pid).cancel_fee_kop == settings.courier_cancel_fee_kop


def test_cancel_before_accept_is_free(client, user_factory):
    """Пока курьер не взял — отмена бесплатна (никто никуда не ехал)."""
    sender = user_factory(name="ГапОтпр6")
    pid = _order(client, sender)["id"]
    assert client.post(f"/parcels/{pid}/cancel", headers=sender["auth"]).status_code == 200
    assert (_parcel(pid).cancel_fee_kop or 0) == 0


# ============== Пауза «Справедливости» и доставка ==============
def test_suspended_user_cannot_create_or_take_parcels(client, user_factory):
    """Отстранённый за нарушения (напр. кража груза) не заводит и не берёт посылки.
    Раньше проверки не было ни в создании, ни в accept — пауза была декорацией."""
    from app.timeutil import utcnow
    from datetime import timedelta
    sender = user_factory(name="ГапБан")
    with Session(engine) as s:
        s.add(M.SafetyProfile(user_id=sender["id"], strikes=1,
                              suspended_until=utcnow() + timedelta(days=3)))
        s.commit()
    r = client.post("/courier/orders", headers=sender["auth"], json={
        "from_city": "Акъяр", "to_city": "Сибай", "size": "small",
        "description": "тест", "receiver_name": "Х", "receiver_phone": "+79990004466",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
        "from_lat": 51.90, "from_lng": 58.20, "to_lat": 52.71, "to_lng": 58.66,
    })
    assert r.status_code == 403


# ============== Объявленная ценность и фото ==============
def test_declared_value_and_photos_are_stored(client, user_factory):
    """Ценность и фото на границах ответственности: без них спор «ты разбил» ↔ «оно уже было»
    нерешаем ни для одной стороны."""
    courier = _make_courier(client, user_factory)
    sender = user_factory(name="ГапФото")
    pid = _order(client, sender, declared_value_kop=150000)["id"]
    assert _parcel(pid).declared_value_kop == 150000
    ok_url = "https://yulbash.ru/secure/evidence/pickup.jpg"
    r = client.post(f"/parcels/{pid}/accept", headers=courier["auth"],
                    json={"pickup_photo_url": ok_url})
    assert r.status_code == 200, r.text
    assert _parcel(pid).pickup_photo_url == ok_url
    # Чужой хост игнорируем: открытие такой ссылки слило бы IP оппонента.
    client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                json={"status": "in_transit", "delivery_photo_url": "http://evil.example/x.jpg"})
    assert _parcel(pid).delivery_photo_url == ""


# ============== Уведомления курьерам о новых заказах ==============
def test_couriers_get_notified_about_new_order(client, user_factory):
    """Раньше заявка висела в пустоте: три курьера ехали мимо и не знали о ней."""
    courier = _make_courier(client, user_factory, name="ГапПуш")
    sender = user_factory(name="ГапПушОтпр")
    _order(client, sender)
    with Session(engine) as s:
        rows = s.exec(select(M.Notification).where(
            M.Notification.user_id == courier["id"], M.Notification.type == "parcel"
        )).all()
    assert rows, "курьер на линии должен получить уведомление о новой доставке"


# ============== Админ получил рычаги ==============
def test_admin_can_rescue_stuck_parcel(client, user_factory):
    """«Посылка две недели висит, курьер трубку не берёт» — теперь админ может вмешаться.
    Раньше была одна ручка на просмотр: ни отменить, ни переназначить, ни закрыть."""
    admin = user_factory(name="ГапАдмин2", role=UserRole.admin)
    courier = _make_courier(client, user_factory, name="ГапПропал")
    sender = user_factory(name="ГапОтпр7")
    plain = user_factory(name="ГапНеАдмин")
    pid = _order(client, sender)["id"]
    client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    # Не-админа не пускаем.
    assert client.post(f"/admin/parcels/{pid}/release-courier", headers=plain["auth"]).status_code == 403
    # Снять пропавшего курьера — посылка снова в поиске.
    r = client.post(f"/admin/parcels/{pid}/release-courier", headers=admin["auth"],
                    json={"reason": "курьер не отвечает"})
    assert r.status_code == 200, r.text
    p = _parcel(pid)
    assert p.courier_id is None and p.status == "created"
    # Закрыть принудительно как возврат — комиссию не берём (услуга не оказана).
    r2 = client.post(f"/admin/parcels/{pid}/close", headers=admin["auth"],
                     json={"status": "returned", "reason": "договорились вне приложения"})
    assert r2.status_code == 200, r2.text
    p = _parcel(pid)
    assert p.status == "returned" and p.commission_kop == 0 and p.commission_paid is True


def test_admin_cancel_parcel_is_idempotent(client, user_factory):
    admin = user_factory(name="ГапАдмин3", role=UserRole.admin)
    sender = user_factory(name="ГапОтпр8")
    pid = _order(client, sender)["id"]
    assert client.post(f"/admin/parcels/{pid}/cancel", headers=admin["auth"],
                       json={"reason": "дубль заявки"}).status_code == 200
    assert _parcel(pid).status == "canceled"
    again = client.post(f"/admin/parcels/{pid}/cancel", headers=admin["auth"])
    assert again.status_code == 200 and again.json()["already"] is True


def test_admin_close_rejects_bad_status(client, user_factory):
    admin = user_factory(name="ГапАдмин4", role=UserRole.admin)
    sender = user_factory(name="ГапОтпр9")
    pid = _order(client, sender)["id"]
    assert client.post(f"/admin/parcels/{pid}/close", headers=admin["auth"],
                       json={"status": "летит"}).status_code == 422
