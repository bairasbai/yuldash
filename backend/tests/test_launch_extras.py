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
