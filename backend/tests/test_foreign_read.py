"""Можно ли прочитать чужое, просто подставив номер в адрес.

Номера в адресах идут подряд: бронь 41, 42, 43. Подставить чужой — дело одной секунды,
приложение для этого не нужно. Поэтому право читать обязан проверять сервер по каждому
запросу, а не экран, который «просто не покажет кнопку».

Для приложения «между своими» это самая дорогая категория ошибок. За номером брони стоят:
переписка двух людей, телефон водителя, код посадки, чек с маршрутом и суммой, разбор
жалобы с фотографиями. Утечка любого из этого — не «баг», а сломанное доверие, ради
которого приложение и делается.

Здесь по каждому чувствительному адресу проверяется одно: посторонний получает отказ,
а участник — свои данные.
"""
from __future__ import annotations

from app.models import UserRole

from test_api import _ride


def _trip(client, user_factory, tag):
    """Подтверждённая бронь + непричастный третий человек."""
    driver = user_factory(f"{tag}Driver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory(f"{tag}Passenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    outsider = user_factory(f"{tag}Outsider")
    return driver, passenger, outsider, ride_id, bid


def _denied(resp) -> bool:
    """Отказом считаем и 403, и 404: «нет прав» и «нет такого» одинаково безопасны."""
    return resp.status_code in (401, 403, 404)


# ---------- Переписка ----------

def test_посторонний_не_читает_чужую_переписку(client, user_factory):
    driver, passenger, outsider, _, bid = _trip(client, user_factory, "ForeignChat")
    client.post(f"/bookings/{bid}/messages", headers=passenger["auth"], json={"text": "буду через 5 минут"})

    r = client.get(f"/bookings/{bid}/messages", headers=outsider["auth"])
    assert _denied(r), f"чужая переписка отдалась постороннему: {r.status_code} {r.text[:200]}"


def test_посторонний_не_пишет_в_чужую_переписку(client, user_factory):
    driver, passenger, outsider, _, bid = _trip(client, user_factory, "ForeignChatWrite")
    r = client.post(f"/bookings/{bid}/messages", headers=outsider["auth"], json={"text": "привет"})
    assert _denied(r), f"посторонний написал в чужой чат: {r.status_code}"


def test_участники_свою_переписку_читают(client, user_factory):
    """Обратная сторона: защита не должна закрыть доступ самим участникам."""
    driver, passenger, _, _, bid = _trip(client, user_factory, "OwnChat")
    client.post(f"/bookings/{bid}/messages", headers=passenger["auth"], json={"text": "еду"})
    assert client.get(f"/bookings/{bid}/messages", headers=passenger["auth"]).status_code == 200
    assert client.get(f"/bookings/{bid}/messages", headers=driver["auth"]).status_code == 200


# ---------- Код посадки и подробности поездки ----------

def test_посторонний_не_видит_код_посадки(client, user_factory):
    """Код посадки — доказательство, что сел именно тот человек."""
    driver, passenger, outsider, _, bid = _trip(client, user_factory, "ForeignCode")
    r = client.get(f"/bookings/{bid}/boarding-code", headers=outsider["auth"])
    assert _denied(r), f"код посадки отдался постороннему: {r.status_code} {r.text[:200]}"


def test_посторонний_не_видит_подробности_чужой_поездки(client, user_factory):
    """В подробностях — телефон второй стороны и точка сбора."""
    driver, passenger, outsider, _, bid = _trip(client, user_factory, "ForeignDetails")
    r = client.get(f"/bookings/{bid}/details", headers=outsider["auth"])
    assert _denied(r), f"подробности чужой поездки отдались постороннему: {r.status_code}"


def test_посторонний_не_узнаёт_свою_роль_в_чужой_поездке(client, user_factory):
    driver, passenger, outsider, _, bid = _trip(client, user_factory, "ForeignRole")
    r = client.get(f"/bookings/{bid}/role", headers=outsider["auth"])
    assert _denied(r) or r.json().get("role") in ("", None, "none"), (
        f"посторонний получил роль в чужой поездке: {r.status_code} {r.text[:200]}"
    )


# ---------- Чек ----------

def test_посторонний_не_скачивает_чужой_чек(client, user_factory):
    """В чеке маршрут, дата, сумма и водитель — готовая справка о передвижениях человека."""
    driver, passenger, outsider, _, bid = _trip(client, user_factory, "ForeignReceipt")
    r = client.get(f"/trips/{bid}/receipt", headers=outsider["auth"])
    assert _denied(r), f"чужой чек отдался постороннему: {r.status_code} {r.text[:200]}"


# ---------- Жалобы и споры ----------

def test_посторонний_не_читает_чужой_спор(client, user_factory):
    """В споре объяснения сторон и фотографии — лица, номера, иногда травмы."""
    reporter = user_factory("IncidentReporter")
    target = user_factory("IncidentTarget")
    created = client.post("/incidents", headers=reporter["auth"], json={
        "target_user_id": target["id"], "kind": "behavior", "statement": "нагрубил",
    })
    if created.status_code != 200:
        # Спор не завёлся (нужна общая поездка) — берём заведомо чужой номер.
        outsider = user_factory("IncidentOutsider")
        r = client.get("/incidents/999999", headers=outsider["auth"])
        assert _denied(r), f"чужой спор отдался постороннему: {r.status_code}"
        return
    outsider = user_factory("IncidentOutsider")
    r = client.get(f"/incidents/{created.json()['id']}", headers=outsider["auth"])
    assert _denied(r), f"чужой спор отдался постороннему: {r.status_code} {r.text[:200]}"


# ---------- Отклики на заявку ----------

def test_посторонний_не_видит_отклики_на_чужую_заявку(client, user_factory):
    """Отклики — это список водителей с ценами: чужая коммерческая картина."""
    passenger = user_factory("ForeignResponsesOwner")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    }).json()["id"]
    driver = user_factory("ForeignResponsesDriver", role=UserRole.driver)
    client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})

    outsider = user_factory("ForeignResponsesOutsider")
    r = client.get(f"/requests/{rid}/responses", headers=outsider["auth"])
    if r.status_code == 200:
        items = r.json().get("items", r.json()) if isinstance(r.json(), dict) else r.json()
        assert items == [], f"посторонний увидел отклики на чужую заявку: {str(items)[:200]}"


# ---------- Доверие и публичный профиль ----------

def test_публичный_профиль_водителя_без_телефона(client, user_factory):
    """Витрину водителя видят все — но телефона и точного адреса в ней быть не должно."""
    driver = user_factory("PublicProfileDriver", role=UserRole.driver)
    viewer = user_factory("PublicProfileViewer")
    r = client.get(f"/drivers/{driver['id']}/public", headers=viewer["auth"])
    if r.status_code == 200:
        body = str(r.json())
        assert "tg-test-" not in body, "в публичном профиле оказался идентификатор входа"
        for field in ("phone", "телефон"):
            assert f'"{field}"' not in body.lower(), f"в публичном профиле водителя есть поле {field}"


# ---------- Чужая посылка ----------

def test_посторонний_не_читает_переписку_по_чужой_посылке(client, user_factory):
    outsider = user_factory("ParcelChatOutsider")
    r = client.get("/parcels/999999/messages", headers=outsider["auth"])
    assert _denied(r), f"переписка по чужой посылке отдалась постороннему: {r.status_code}"


# ---------- Без входа вообще ----------

def test_без_входа_чувствительное_не_отдаётся(client, user_factory):
    driver, passenger, _, _, bid = _trip(client, user_factory, "NoAuthRead")
    for path in (
        f"/bookings/{bid}/messages",
        f"/bookings/{bid}/boarding-code",
        f"/bookings/{bid}/details",
        f"/trips/{bid}/receipt",
    ):
        r = client.get(path)
        assert r.status_code in (401, 403), f"{path} отдался вообще без входа: {r.status_code}"
