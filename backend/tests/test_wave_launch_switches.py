"""Запуск волнами (решение Александра, 13.10.2026): выключатели направлений.

Волна 1 — только попутки. Посылки, реклама и «Скидки по пути» написаны, но включаются
по одному, когда готовы документы, — переменной окружения, без обновления приложения.

Что здесь проверяется:
  • GET /config/features — публичная сводка для приложения и сайта: только флаги,
    попутки всегда включены, флаги отражают настройки сервера;
  • по умолчанию (как на проде без переменных) всё, кроме попуток, выключено;
  • при выключенном флаге каждая НОВАЯ дверь направления отвечает «скоро» на двух языках,
    а публичные витрины пусты (не ошибка — их рисует главный экран);
  • при включённом — те же двери работают (иначе тесты зеленели бы просто потому,
    что сценарий не запускается);
  • начатое доживает: посылка, взятая до выключения, доезжает; попутка не задета ничем.
"""
from __future__ import annotations

from datetime import timedelta

import pytest

from app.config import Settings, settings
from app.models import UserRole
from app.timeutil import utcnow

from test_api import _ride
from test_coupons import _future, _make_active_coupon, _register_active_partner

FLAGS = ("parcels_enabled", "ads_enabled", "business_enabled")


def _off(monkeypatch, *names):
    for n in names:
        monkeypatch.setattr(settings, n, False, raising=False)


def _parcel(client, user, phone="+79990007301"):
    return client.post("/parcels", headers=user["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": phone, "rules_accepted": True,
    })


def _soon(r) -> None:
    """Отказ «скоро»: 403 и текст на ОБОИХ языках, разный (не одна строка дважды)."""
    assert r.status_code == 403, f"ждали «скоро» (403), а пришло {r.status_code}: {r.text[:200]}"
    detail = r.json()["detail"]
    assert isinstance(detail, dict) and detail.get("ru") and detail.get("ba"), detail
    assert detail["ru"] != detail["ba"], "башкирский текст — копия русского"


# ---------------------------------------------------------------------------
# Сводка /config/features
# ---------------------------------------------------------------------------

def test_по_умолчанию_включены_только_попутки():
    """Прод без переменных = волна 1. Новое направление, добавленное «включённым» по
    умолчанию, тихо открылось бы людям без документов — ловим это здесь."""
    for name in FLAGS + ("taxi_enabled", "courier_enabled"):
        assert Settings.model_fields[name].default is False, f"{name} по умолчанию включён"


def test_сводка_публичная_и_только_флаги(client):
    r = client.get("/config/features")
    assert r.status_code == 200
    data = r.json()
    assert set(data) == {"rides", "taxi", "courier", "parcels", "ads", "business"}
    assert all(isinstance(v, bool) for v in data.values()), data
    assert data["rides"] is True
    # Кеш на клиенте и прокси — минута: включение доходит без обновления приложения.
    assert "max-age=60" in r.headers.get("cache-control", "")
    # Тот же адрес под версионным префиксом — так ходит приложение.
    assert client.get("/api/v1/config/features").json() == data


def test_сводка_повторяет_флаги_сервера(client, monkeypatch):
    for name in FLAGS + ("taxi_enabled", "courier_enabled"):
        monkeypatch.setattr(settings, name, False, raising=False)
    assert client.get("/config/features").json() == {
        "rides": True, "taxi": False, "courier": False,
        "parcels": False, "ads": False, "business": False,
    }
    monkeypatch.setattr(settings, "ads_enabled", True, raising=False)
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    data = client.get("/config/features").json()
    assert data["ads"] is True and data["courier"] is True
    assert data["parcels"] is False and data["business"] is False and data["taxi"] is False
    assert data["rides"] is True   # попутки не выключаются никогда


# ---------------------------------------------------------------------------
# Посылки «по пути»
# ---------------------------------------------------------------------------

def test_посылки_включены_работают(client, user_factory):
    sender = user_factory("WaveParcelOnSender")
    r = _parcel(client, sender)
    assert r.status_code == 200, r.text
    carrier = user_factory("WaveParcelOnCarrier", role=UserRole.driver)
    assert client.get("/parcels/available", headers=carrier["auth"]).status_code == 200


def test_посылки_выключены_новую_не_отправить(client, user_factory, monkeypatch):
    _off(monkeypatch, "parcels_enabled")
    sender = user_factory("WaveParcelOffSender")
    _soon(_parcel(client, sender, phone="+79990007302"))


def test_посылки_выключены_лента_и_приём_закрыты(client, user_factory, monkeypatch):
    sender = user_factory("WaveParcelMidSender")
    pid = _parcel(client, sender, phone="+79990007303").json()["id"]
    _off(monkeypatch, "parcels_enabled")
    carrier = user_factory("WaveParcelMidCarrier", role=UserRole.driver)
    _soon(client.get("/parcels/available", headers=carrier["auth"]))
    _soon(client.post(f"/parcels/{pid}/accept", headers=carrier["auth"]))


def test_посылки_выключены_начатая_доставка_доезжает(client, user_factory, monkeypatch):
    """Посылка уже в дороге, когда флаг выключили. Отрезать её на полпути — значит оставить
    чужую вещь у курьера без способа сдать её получателю."""
    sender = user_factory("WaveParcelLiveSender")
    created = _parcel(client, sender, phone="+79990007304").json()
    pid, code = created["id"], created["confirm_code"]
    carrier = user_factory("WaveParcelLiveCarrier", role=UserRole.driver)
    assert client.post(f"/parcels/{pid}/accept", headers=carrier["auth"]).status_code == 200

    _off(monkeypatch, "parcels_enabled")
    assert client.get("/parcels/mine", headers=sender["auth"]).status_code == 200
    assert client.get("/parcels/carrying", headers=carrier["auth"]).status_code == 200
    r = client.post(f"/parcels/{pid}/status", headers=carrier["auth"], json={"status": "in_transit"})
    assert r.status_code == 200, r.text
    r = client.post(f"/parcels/{pid}/status", headers=carrier["auth"],
                    json={"status": "delivered", "code": code})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "delivered"


def test_выключенные_направления_не_задевают_попутку(client, user_factory, monkeypatch):
    """Волна 1 — это попутки. Ни один из новых выключателей не должен их коснуться."""
    _off(monkeypatch, *FLAGS, "taxi_enabled", "courier_enabled")
    driver = user_factory("WaveRideDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("WaveRidePax")
    r = client.post("/bookings", headers=pax["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code == 200, r.text


# ---------------------------------------------------------------------------
# Реклама
# ---------------------------------------------------------------------------

def _live_ad(client, admin):
    body = {
        "partner_name": "Кафе у дороги", "partner_contact": "+79990000000",
        "title": "Волна", "text": "Текст", "button": "Открыть",
        "target": "https://example.test", "image_url": "/media/ads/test-banner.jpg",
        "erid": "erid-wave-1", "plan": "standard", "placements": "map,profile",
        "cities": "", "priority": 1,
        "starts_at": (utcnow() - timedelta(minutes=1)).isoformat(),
        "ends_at": (utcnow() + timedelta(days=1)).isoformat(),
    }
    r = client.post("/admin/ads", headers=admin["auth"], json=body)
    assert r.status_code == 200, r.text
    ad_id = r.json()["id"]
    rs = client.post(f"/admin/ads/{ad_id}/status", headers=admin["auth"], json={"status": "active"})
    assert rs.status_code == 200, rs.text
    return ad_id


@pytest.fixture
def wave_admin(user_factory):
    return user_factory("WaveAdsAdmin", role=UserRole.admin)


def test_реклама_выключена_витрина_пуста_включена_видна(client, wave_admin, monkeypatch):
    ad_id = _live_ad(client, wave_admin)
    try:
        assert any(str(a["id"]) == str(ad_id) for a in client.get("/ads").json())
        _off(monkeypatch, "ads_enabled")
        r = client.get("/ads")
        assert r.status_code == 200 and r.json() == [], "витрина рекламы не пуста при выключенной рекламе"
    finally:
        client.delete(f"/admin/ads/{ad_id}", headers=wave_admin["auth"])


def test_реклама_выключена_новое_размещение_и_оплата_скоро(client, user_factory, monkeypatch):
    owner = user_factory("WaveAdsOwner")
    ad = client.post("/ads", headers=owner["auth"], json={"title": "Шиномонтаж", "package": "city"})
    assert ad.status_code == 200, ad.text
    ad_id = ad.json()["id"]
    _off(monkeypatch, "ads_enabled")
    _soon(client.post("/ads", headers=owner["auth"], json={"title": "Ещё одна", "package": "city"}))
    _soon(client.post(f"/ads/{ad_id}/submit", headers=owner["auth"]))
    _soon(client.post(f"/ads/{ad_id}/pay", headers=owner["auth"]))
    _soon(client.post(f"/ads/{ad_id}/renew", headers=owner["auth"]))
    # Свой кабинет открывается: человек видит, что у него есть, — просто разместить нельзя.
    assert client.get("/ads/mine", headers=owner["auth"]).status_code == 200


# ---------------------------------------------------------------------------
# «Скидки по пути» (купоны) и кабинет бизнеса
# ---------------------------------------------------------------------------

def test_скидки_включены_работают_выключены_витрина_пуста(client, user_factory, monkeypatch):
    owner, _admin, _pid, _ = _register_active_partner(client, user_factory, city="Белорецк")
    cid = _make_active_coupon(client, owner, city="Белорецк", valid_until=_future())
    assert any(c["id"] == cid for c in client.get("/coupons", params={"city": "Белорецк"}).json())

    _off(monkeypatch, "business_enabled")
    r = client.get("/coupons", params={"city": "Белорецк"})
    assert r.status_code == 200 and r.json() == []
    _soon(client.get(f"/coupons/{cid}"))
    pax = user_factory("WaveCouponPax")
    _soon(client.post(f"/coupons/{cid}/activate", headers=pax["auth"]))
    _soon(client.post("/partner/coupons", headers=owner["auth"],
                      json={"title": "Новый", "discount_text": "−10%"}))
    _soon(client.post("/partner/subscribe", headers=owner["auth"], json={"plan": "basic"}))
    # Свой кабинет бизнеса открывается — выключатель закрывает новые двери, а не прошлое.
    assert client.get("/partner/me", headers=owner["auth"]).status_code == 200


def test_скидки_выключены_бизнес_не_зарегистрировать(client, user_factory, monkeypatch):
    _off(monkeypatch, "business_enabled")
    owner = user_factory("WaveBizOffOwner")
    _soon(client.post("/partner", headers=owner["auth"], json={"name": "Пекарня", "city": "Уфа"}))


def test_скидки_выключены_уже_выданный_код_гасится(client, user_factory, monkeypatch):
    """Человек взял купон, пока витрина работала, и пришёл в кафе. Отказать ему на кассе
    из-за нашего выключателя — обмануть и его, и бизнес."""
    owner, _admin, _pid, _ = _register_active_partner(client, user_factory, city="Учалы")
    cid = _make_active_coupon(client, owner, city="Учалы", valid_until=_future())
    pax = user_factory("WaveCouponLivePax")
    code = client.post(f"/coupons/{cid}/activate", headers=pax["auth"]).json()["code"]
    _off(monkeypatch, "business_enabled")
    r = client.post("/coupons/redeem", headers=owner["auth"], json={"code": code})
    assert r.status_code == 200, r.text



def test_выключенный_бизнес_не_публикует_старый_черновик(client, user_factory, monkeypatch):
    owner, _admin, _pid, _ = _register_active_partner(client, user_factory, city="Баймак")
    response = client.post("/partner/coupons", headers=owner["auth"],
                           json={"title": "Гостинцы", "discount_text": "10 процентов"})
    assert response.status_code == 200, response.text
    cid = response.json()["id"]
    _off(monkeypatch, "business_enabled")
    _soon(client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"], json={"status": "active"}))
    # Пауза старого купона остаётся доступной: выключатель не мешает убрать своё.
    assert client.post(f"/partner/coupons/{cid}/status", headers=owner["auth"], json={"status": "paused"}).status_code == 200


def test_курьер_выключен_уже_взятый_заказ_доезжает(client, user_factory, monkeypatch):
    from test_courier import _make_courier, _order
    monkeypatch.setattr(settings, "courier_enabled", True)
    courier = _make_courier(client, user_factory)
    sender = user_factory("WaveProfessionalSender")
    created = _order(client, sender)
    assert created.status_code == 200, created.text
    pid, code = created.json()["id"], created.json()["confirm_code"]
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
    _off(monkeypatch, "courier_enabled", "parcels_enabled")
    _soon(_order(client, sender))
    _soon(client.get("/courier/available", headers=courier["auth"]))
    carrying = client.get("/parcels/carrying", headers=courier["auth"])
    assert carrying.status_code == 200 and any(p["id"] == pid for p in carrying.json())
    assert client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "in_transit"}).status_code == 200
    delivered = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json={"status": "delivered", "code": code})
    assert delivered.status_code == 200 and delivered.json()["status"] == "delivered", delivered.text
