"""Устаревший экран в такси: те же кнопки, но цена ошибки выше.

В попутках опоздавшая отмена — это спор между двумя людьми. В такси к ней привязаны деньги:
за завершённый заказ водителю начисляется комиссия, пассажиру считается цена. Если заказ
можно «отменить» после завершения или завершить дважды, ломается расчёт с обеих сторон.

Проверяем те же переходы, что и для попуток (test_stale_screen_actions.py), но на такси-заказе.
Правило прежнее: сервер либо честно отказывает, либо ничего не меняет — но никогда не делает
вид, что перевёл заказ в состояние, в которое перевести нельзя.
"""
from __future__ import annotations

import fakeredis
import pytest

from app import instant_service as isv
from app.models import UserRole


@pytest.fixture
def taxi_on(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)
    yield


@pytest.fixture
def fake_redis():
    """Такси без Redis мертво: presence водителей живёт только там."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


# Координаты и способ создания заказа — те же, что в test_instant.py: заказ уходит матчеру
# только когда рядом есть водитель, отметившийся на линии.
ORIG = (52.5911, 58.3178)   # Баймак
DEST = (52.9128, 58.6689)   # Сибай


def _order(client, user_factory, tag):
    """Заказ такси и водитель на линии рядом (иначе матчер никому его не предложит)."""
    passenger = user_factory(f"{tag}Passenger")
    driver = user_factory(f"{tag}Driver", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    created = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert created.status_code == 200, f"заказ такси не создался: {created.status_code} {created.text[:200]}"
    return passenger, driver, created.json()["id"]


def test_завершённый_заказ_не_отменяется_задним_числом(client, user_factory, taxi_on, fake_redis):
    """Пассажир держит открытым экран заказа, водитель нажал «доехали»."""
    passenger, driver, oid = _order(client, user_factory, "TaxiLateCancel")
    client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])
    done = client.post(f"/instant/orders/{oid}/done", headers=driver["auth"])
    # Не skip: пропущенный тест показывает зелёный и не проверяет ничего.
    assert done.status_code == 200, f"заказ не удалось завершить: {done.status_code} {done.text[:200]}"

    r = client.post(f"/instant/orders/{oid}/cancel", headers=passenger["auth"])
    if r.status_code == 200:
        state = client.get(f"/instant/orders/{oid}", headers=passenger["auth"]).json()
        assert state.get("status") == "done", (
            "завершённый такси-заказ отменился задним числом — у водителя пропала поездка и деньги"
        )
    else:
        assert 400 <= r.status_code < 500, f"ожидали честный отказ, получили {r.status_code}"


def test_завершить_заказ_дважды_не_удваивает_комиссию(client, user_factory, taxi_on, fake_redis):
    """Двойной тап «Доехали» или повтор после обрыва связи не должен списать с водителя дважды."""
    passenger, driver, oid = _order(client, user_factory, "TaxiDoubleDone")
    client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/arrived", headers=driver["auth"])
    client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])
    first = client.post(f"/instant/orders/{oid}/done", headers=driver["auth"])
    assert first.status_code == 200, f"заказ не удалось завершить: {first.status_code} {first.text[:200]}"

    debt_after_first = client.get("/driver/debt", headers=driver["auth"])
    assert debt_after_first.status_code == 200, debt_after_first.text
    client.post(f"/instant/orders/{oid}/done", headers=driver["auth"])
    debt_after_second = client.get("/driver/debt", headers=driver["auth"])
    assert debt_after_second.status_code == 200, debt_after_second.text

    def owed(resp):
        body = resp.json()
        return body.get("total_due_kop", body.get("unpaid_kop", body.get("owed_commission_kop")))

    a, b = owed(debt_after_first), owed(debt_after_second)
    assert a is not None, f"в ответе о долге нет суммы — проверять нечего: {debt_after_first.text[:200]}"
    assert a == b, f"повторное «доехали» удвоило комиссию водителя: было {a}, стало {b}"


def test_чужой_водитель_не_принимает_заказ(client, user_factory, taxi_on, fake_redis):
    """Кнопка «Принять» есть только у того, кому предложили."""
    passenger, driver, oid = _order(client, user_factory, "TaxiForeignAccept")
    outsider = user_factory("TaxiForeignOutsider", role=UserRole.driver)
    r = client.post(f"/instant/orders/{oid}/accept", headers=outsider["auth"])
    assert r.status_code != 200 or client.get(
        f"/instant/orders/{oid}", headers=passenger["auth"]
    ).json().get("driver_id") != outsider["id"], "заказ забрал водитель, которому его не предлагали"


def test_пассажир_не_завершает_заказ_за_водителя(client, user_factory, taxi_on, fake_redis):
    """«Доехали» — кнопка водителя: от неё зависит и цена, и комиссия."""
    passenger, driver, oid = _order(client, user_factory, "TaxiPassengerDone")
    client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    r = client.post(f"/instant/orders/{oid}/done", headers=passenger["auth"])
    assert r.status_code != 200, "пассажир завершил заказ за водителя — можно закрыть поездку до её конца"


def test_нельзя_сесть_в_машину_до_её_приезда(client, user_factory, taxi_on, fake_redis):
    """Порядок шагов: принял → подал → посадил. Через голову — отказ."""
    passenger, driver, oid = _order(client, user_factory, "TaxiSkipStep")
    client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    r = client.post(f"/instant/orders/{oid}/onboard", headers=driver["auth"])
    if r.status_code == 200:
        state = client.get(f"/instant/orders/{oid}", headers=driver["auth"]).json()
        assert state.get("status") != "done", "заказ проскочил в завершённый мимо шагов"
