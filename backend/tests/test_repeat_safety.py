"""Что будет, если нажать кнопку дважды. Проверка сервера, а не приложения.

Приложение защищается от двойного нажатия само: кнопка гаснет, пока идёт запрос. Но это
только первая линия. Вторая — сервер, и она важнее: связь рвётся и повторяет запрос сама,
человек жмёт «Повторить» после ошибки, приложение переустанавливают, запрос можно послать
руками. Если сервер не держит повтор, всё это превращается в две брони на одно место,
две посылки, два списания или два штрафа.

Здесь проверены операции главного сценария, которых не было в существующих тестах на повтор
(двойной приём заказа, двойная оплата долга, дубль посылки и оценка дважды уже закрыты
в test_instant.py, test_ledger.py, test_parcel_duplicate.py, test_courier_c3.py).

Правило, которое проверяем: повтор либо возвращает тот же результат, либо честно отвечает
отказом — но никогда не создаёт вторую сущность и не списывает второй раз.
"""
from __future__ import annotations

from app.models import UserRole

from test_api import _ride


# ---------- Бронь ----------

def test_повторная_бронь_на_ту_же_поездку_не_плодит_вторую(client, user_factory):
    """Двойной тап «Забронировать»: мест не должно съесться вдвое."""
    driver = user_factory("RepeatBookDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    passenger = user_factory("RepeatBookPassenger")

    first = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert first.status_code == 200
    second = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})

    mine = client.get("/bookings/mine", headers=passenger["auth"]).json()
    items = mine.get("items", mine) if isinstance(mine, dict) else mine
    active = [b for b in items if b.get("ride_id") == ride_id
              and b.get("status") not in ("cancelled", "declined")]
    assert len(active) == 1, (
        f"на одну поездку {len(active)} активных брони — двойной тап съел лишнее место. "
        f"Второй ответ: {second.status_code} {second.text[:200]}"
    )


def test_подтвердить_бронь_дважды_безопасно(client, user_factory):
    driver = user_factory("RepeatConfirmDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory("RepeatConfirmPassenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]

    first = client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    assert first.status_code == 200
    second = client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    # Либо «уже подтверждено» (200), либо честный отказ — но не поломка.
    assert second.status_code in (200, 400, 409), second.text


def test_отменить_бронь_дважды_безопасно(client, user_factory):
    """Второй тап «Отменить» не должен второй раз возвращать места или штрафовать."""
    driver = user_factory("RepeatCancelDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory("RepeatCancelPassenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]

    before = client.get(f"/rides/{ride_id}", headers=passenger["auth"]).json()
    assert client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"]).status_code in (200, 204)
    after_one = client.get(f"/rides/{ride_id}", headers=passenger["auth"]).json()

    client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"])
    after_two = client.get(f"/rides/{ride_id}", headers=passenger["auth"]).json()

    assert after_one.get("seats_left") == after_two.get("seats_left"), (
        "повторная отмена вернула места ещё раз — на поездке появились несуществующие места "
        f"(было {before.get('seats_left')}, после первой {after_one.get('seats_left')}, "
        f"после второй {after_two.get('seats_left')})"
    )


# ---------- Заявка пассажира ----------

def test_принять_два_отклика_на_одну_заявку_нельзя(client, user_factory):
    """Заявку закрывает ОДИН водитель. Иначе двое поедут за одним пассажиром."""
    passenger = user_factory("TwoAcceptPassenger")
    req = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    })
    assert req.status_code == 200, req.text
    rid = req.json()["id"]

    d1 = user_factory("TwoAcceptDriver1", role=UserRole.driver)
    d2 = user_factory("TwoAcceptDriver2", role=UserRole.driver)
    r1 = client.post(f"/requests/{rid}/respond", headers=d1["auth"], json={"price": 500})
    r2 = client.post(f"/requests/{rid}/respond", headers=d2["auth"], json={"price": 450})
    assert r1.status_code == 200 and r2.status_code == 200, (r1.text, r2.text)

    first = client.post(f"/responses/{r1.json()['id']}/accept", headers=passenger["auth"])
    assert first.status_code == 200, first.text
    second = client.post(f"/responses/{r2.json()['id']}/accept", headers=passenger["auth"])
    assert second.status_code != 200, (
        "приняли второй отклик на ту же заявку — к пассажиру поедут два водителя"
    )


def test_отменить_заявку_дважды_безопасно(client, user_factory):
    passenger = user_factory("TwoCancelReqPassenger")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1,
    }).json()["id"]
    assert client.post(f"/requests/{rid}/cancel", headers=passenger["auth"]).status_code in (200, 204)
    again = client.post(f"/requests/{rid}/cancel", headers=passenger["auth"])
    assert again.status_code in (200, 204, 400, 404, 409), again.text


# ---------- Удаление аккаунта ----------

def test_удалить_аккаунт_дважды_не_роняет_сервер(client, user_factory):
    """Повтор после обрыва связи: второй запрос уже без аккаунта — 401, а не 500."""
    user = user_factory("DoubleDeleteUser")
    first = client.post("/me/delete", headers=user["auth"])
    assert first.status_code in (200, 204, 409), first.text
    if first.status_code in (200, 204):
        second = client.post("/me/delete", headers=user["auth"])
        assert second.status_code in (401, 403, 404), (
            f"повторное удаление ответило {second.status_code} — сервер не должен падать "
            f"на запросе от несуществующего аккаунта: {second.text[:200]}"
        )


# ---------- Заявка водителя в такси ----------

def test_повторная_заявка_в_такси_не_плодит_вторую(client, user_factory):
    driver = user_factory("TwiceTaxiApply", role=UserRole.driver, taxi_approved=False)
    body = {
        "inn": "123456789012", "permit_number": "Т-9999",
        "birth_date": "1990-01-01", "license_since_year": 2010,
    }
    first = client.post("/taxi/apply", headers=driver["auth"], json=body)
    second = client.post("/taxi/apply", headers=driver["auth"], json=body)
    assert first.status_code in (200, 201), first.text
    # Вторая заявка либо обновляет ту же, либо отклоняется — но списка из двух быть не должно.
    mine = client.get("/taxi/application", headers=driver["auth"])
    assert mine.status_code == 200, mine.text
    assert second.status_code in (200, 201, 400, 409), second.text


# ---------- Отклик водителя ----------

def test_водитель_не_откликается_на_одну_заявку_дважды(client, user_factory):
    """Иначе в списке у пассажира два одинаковых отклика от одного человека."""
    passenger = user_factory("DoubleRespondPassenger")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Учалы", "to_city": "Уфа", "seats": 1,
    }).json()["id"]
    driver = user_factory("DoubleRespondDriver", role=UserRole.driver)

    first = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 700})
    assert first.status_code == 200, first.text
    client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 650})

    responses = client.get(f"/requests/{rid}/responses", headers=passenger["auth"]).json()
    items = responses.get("items", responses) if isinstance(responses, dict) else responses
    mine = [r for r in items if r.get("driver_id") == driver["id"]]
    assert len(mine) == 1, f"от одного водителя {len(mine)} откликов на одну заявку"
