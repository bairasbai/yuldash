"""Кнопка на устаревшем экране: человек нажимает то, что уже нельзя.

Экран не обновляется в ту же секунду. Пассажир держит открытой поездку, которую водитель
только что отменил, и жмёт «Отменить». Водитель видит бронь, которую пассажир уже снял,
и жмёт «Подтвердить». Курьер вручает посылку, которую отправитель минуту назад отозвал.
На стороне приложения это не поймать: у него в руках старые данные, и по ним кнопка
выглядит уместной.

Значит, ловить обязан сервер: действие не по текущему состоянию — отказ, а не «сделано».
Иначе поездка воскресает после отмены, места уходят в минус, а посылка числится вручённой
и возвращённой одновременно.

Здесь проверены переходы главного сценария. Правило: сервер отвечает отказом (4xx),
а не 200 и не 500.
"""
from __future__ import annotations

from app.models import UserRole

from test_api import _ride


def _booking(client, user_factory, tag, seats=2):
    driver = user_factory(f"{tag}Driver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=seats)
    passenger = user_factory(f"{tag}Passenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    return driver, passenger, ride_id, bid


# ---------- Отменённое не оживает ----------

def test_отменённую_бронь_нельзя_подтвердить(client, user_factory):
    """Пассажир отменил, у водителя на экране всё ещё «Подтвердить»."""
    driver, passenger, _, bid = _booking(client, user_factory, "ReviveConfirm")
    assert client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"]).status_code in (200, 204)

    r = client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    assert r.status_code != 200, "отменённая бронь ожила от кнопки «Подтвердить» на старом экране"


def test_отменённую_бронь_нельзя_перевести_в_поездку(client, user_factory):
    driver, passenger, _, bid = _booking(client, user_factory, "ReviveStatus")
    client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"])

    r = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "boarded"})
    assert r.status_code != 200, "по отменённой брони прошёл статус «сел в машину»"


def test_чужую_бронь_нельзя_подтвердить(client, user_factory):
    """Кнопка «Подтвердить» есть только у водителя этой поездки — проверяем на сервере."""
    driver, passenger, _, bid = _booking(client, user_factory, "ForeignConfirm")
    outsider = user_factory("ForeignConfirmOutsider", role=UserRole.driver)
    r = client.post(f"/bookings/{bid}/confirm", headers=outsider["auth"])
    assert r.status_code in (401, 403, 404), f"посторонний подтвердил чужую бронь: {r.status_code}"


# ---------- Завершённое не переигрывается ----------

def test_завершённую_поездку_нельзя_отменить(client, user_factory):
    """Поездка состоялась. Кнопка «Отменить» на старом экране не должна её отменять —
    иначе водитель теряет и поездку, и деньги задним числом."""
    driver, passenger, _, bid = _booking(client, user_factory, "DoneCancel")
    client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "boarded"})
    done = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "done"})
    # Раньше тут стоял тихий выход «если не 200 — проверять нечего», и тест проходил впустую
    # на опечатке в адресе. Молчаливый пропуск хуже отсутствия теста: он показывает зелёный.
    assert done.status_code == 200, f"поездку не удалось завершить: {done.status_code} {done.text[:200]}"

    # Сервер отвечает 200 и возвращает бронь КАК ЕСТЬ, ничего не меняя. Это нормально:
    # повторный запрос после обрыва связи не должен ломаться. Важно другое — состояние.
    r = client.post(f"/bookings/{bid}/cancel", headers=passenger["auth"])
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "done", (
        "завершённая поездка отменилась задним числом: водитель теряет и поездку, и деньги"
    )
    # И приложение обязано это заметить: оно смотрит именно на статус в ответе,
    # а не на код 200 (см. ApiClient.cancelBooking → CancelResultDto.cancelled).


def test_нельзя_сесть_в_машину_после_завершения(client, user_factory):
    driver, passenger, _, bid = _booking(client, user_factory, "BackwardStatus")
    client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "boarded"})
    done = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "done"})
    assert done.status_code == 200, f"поездку не удалось завершить: {done.status_code} {done.text[:200]}"

    r = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"], json={"status": "boarded"})
    assert r.status_code != 200, "поездка отыграла назад: из «завершена» вернулась в «еду»"


# ---------- Поездка, которой уже нет ----------

def test_нельзя_забронировать_отменённую_поездку(client, user_factory):
    """Пассажир открыл ленту, водитель тем временем снял поездку."""
    driver = user_factory("GoneRideDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory("GoneRidePassenger")
    cancelled = client.post(f"/rides/{ride_id}/cancel", headers=driver["auth"])
    assert cancelled.status_code in (200, 204), f"поездку не удалось снять: {cancelled.status_code}"

    r = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code != 200, "забронировали поездку, которую водитель уже отменил"


def test_нельзя_забронировать_больше_мест_чем_осталось(client, user_factory):
    """Двое смотрят одну поездку с одним местом. Второй жмёт «Забронировать» позже."""
    driver = user_factory("LastSeatDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=1)
    first = user_factory("LastSeatFirst")
    second = user_factory("LastSeatSecond")

    assert client.post("/bookings", headers=first["auth"], json={"ride_id": ride_id, "seats": 1}).status_code == 200
    r = client.post("/bookings", headers=second["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code != 200, "продали место, которого уже нет"


def test_нельзя_забронировать_ноль_или_отрицательное_число_мест(client, user_factory):
    driver = user_factory("ZeroSeatsDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    passenger = user_factory("ZeroSeatsPassenger")
    for seats in (0, -1, -100):
        r = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": seats})
        assert r.status_code != 200, f"бронь на {seats} мест прошла"


# ---------- Заявка, которую уже закрыли ----------

def test_нельзя_откликнуться_на_отменённую_заявку(client, user_factory):
    passenger = user_factory("GoneReqPassenger")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    }).json()["id"]
    client.post(f"/requests/{rid}/cancel", headers=passenger["auth"])

    driver = user_factory("GoneReqDriver", role=UserRole.driver)
    r = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})
    assert r.status_code != 200, "водитель откликнулся на снятую заявку — потратил время впустую"


def test_нельзя_принять_отклик_по_чужой_заявке(client, user_factory):
    passenger = user_factory("ForeignAcceptOwner")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1,
    }).json()["id"]
    driver = user_factory("ForeignAcceptDriver", role=UserRole.driver)
    resp = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 400})
    assert resp.status_code == 200, resp.text

    outsider = user_factory("ForeignAcceptOutsider")
    r = client.post(f"/responses/{resp.json()['id']}/accept", headers=outsider["auth"])
    assert r.status_code in (401, 403, 404), f"посторонний закрыл чужую заявку: {r.status_code}"


# ---------- Оценка ----------

def test_нельзя_оценить_поездку_которой_не_было(client, user_factory):
    """Кнопка оценки появляется после поездки. Сервер не должен верить экрану на слово."""
    driver, passenger, _, bid = _booking(client, user_factory, "EarlyRate")
    r = client.post(f"/bookings/{bid}/rate", headers=passenger["auth"], json={"stars": 5})
    assert r.status_code != 200, "оценили поездку, которая ещё не состоялась"
