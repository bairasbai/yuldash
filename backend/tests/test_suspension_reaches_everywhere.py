"""Дотягивается ли пауза «Справедливости» до ВСЕХ действий, а не только до части.

Что такое пауза. Разобрав жалобу (грубость, опасное вождение, кража груза), админ ставит
человеку паузу на 3 / 7 / 30 дней. Это самое сильное наказание в приложении до бана: всё
это время он не должен возить и не должен заказывать.

Почему проверка нужна отдельно. Гейт паузы стоит на каждой ручке ПОШТУЧНО — забыли одну,
и наказание превращается в декорацию: отстранённый водитель в тот же день везёт следующего
человека, а пассажир уверен, что «на платформе такого не допустят». Ровно так уже случилось
с кнопкой «заблокировать» в доставке (аудит 2026-08-06).

Правило: пока пауза активна, человек не берёт НОВЫХ обязательств нигде — ни попутка,
ни заявка, ни такси, ни доставка, ни торг о цене.

Обратная сторона тоже важна и проверяется здесь же:
  • SOS обязан работать ВСЕГДА — отстранённому тоже может понадобиться помощь;
  • начатую поездку надо дать завершить, иначе пауза бросает пассажира на полдороге.
"""
from __future__ import annotations

from datetime import timedelta

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.db import engine
from app.models import SafetyProfile, UserRole
from app.timeutil import utcnow

from test_api import _ride, just_left

ORIG = (52.5911, 58.3178)   # Баймак
DEST = (52.9128, 58.6689)   # Сибай


def _suspend(user_id: int, days: int = 3) -> None:
    """Поставить паузу так же, как это делает разбор жалобы."""
    with Session(engine) as s:
        s.add(SafetyProfile(user_id=user_id, strikes=1,
                            suspended_until=utcnow() + timedelta(days=days)))
        s.commit()


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _request_of(client, passenger):
    r = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    })
    assert r.status_code == 200, f"заявка не создалась: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def _order_of(client, passenger):
    r = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert r.status_code == 200, f"заказ такси не создался: {r.status_code} {r.text[:200]}"
    return r.json()["id"]


def _online_at(client, driver, point=ORIG):
    assert client.post("/driver/online", headers=driver["auth"],
                       json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": point[0], "lng": point[1]}).status_code == 200


# ---------- Контроль: сама пауза работает ----------

def test_контроль_пауза_блокирует_публикацию_поездки(client, user_factory):
    """Без этого теста все проверки ниже могли бы «проходить» по неправильной причине —
    например если пауза вообще не ставится в тестовой среде."""
    driver = user_factory("SuspRideDriver", role=UserRole.driver)
    _suspend(driver["id"])
    r = client.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 3, "price": 500,
        "depart_at": (utcnow() + timedelta(days=1, hours=5)).replace(microsecond=0).isoformat(),
    })
    assert r.status_code == 403, f"отстранённый опубликовал поездку: {r.status_code} {r.text[:200]}"


# ---------- Отклик на заявку пассажира ----------

def test_контроль_обычный_водитель_откликается_на_заявку(client, user_factory):
    """Контрольный случай к тесту ниже: без паузы отклик обязан проходить."""
    passenger = user_factory("SuspRespControlPax")
    rid = _request_of(client, passenger)
    driver = user_factory("SuspRespControlDrv", role=UserRole.driver)
    r = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})
    assert r.status_code == 200, (
        f"обычный водитель не смог откликнуться — проверка паузы ничего не докажет: "
        f"{r.status_code} {r.text[:200]}"
    )


def test_отстранённый_водитель_не_откликается_на_заявку(client, user_factory):
    """Человек просит подвезти. Отклик — это предложение сесть к водителю в машину.
    Отстранённый за плохое поведение не должен получать такую возможность."""
    passenger = user_factory("SuspRespPax")
    rid = _request_of(client, passenger)
    driver = user_factory("SuspRespDrv", role=UserRole.driver)
    _suspend(driver["id"])

    r = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})
    assert r.status_code == 403, (
        f"отстранённый водитель откликнулся на заявку и может повезти человека — "
        f"пауза «Справедливости» оказалась декорацией: {r.status_code} {r.text[:200]}"
    )


# ---------- Такси ----------

def test_отстранённый_водитель_не_принимает_заказ_такси(client, user_factory, fake_redis):
    """Худший из сценариев: разбор жалобы поставил паузу, а машина продолжает возить."""
    passenger = user_factory("SuspTaxiPax")
    driver = user_factory("SuspTaxiDrv", role=UserRole.driver)
    _online_at(client, driver)
    _suspend(driver["id"])

    oid = _order_of(client, passenger)
    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    assert accepted.status_code != 200, (
        f"отстранённый водитель принял заказ такси: {accepted.status_code} {accepted.text[:200]}"
    )


def test_контроль_обычный_водитель_заказ_принимает(client, user_factory, fake_redis):
    """Контроль к тесту выше: без паузы тот же сценарий обязан сработать."""
    passenger = user_factory("SuspTaxiControlPax")
    driver = user_factory("SuspTaxiControlDrv", role=UserRole.driver)
    _online_at(client, driver)

    oid = _order_of(client, passenger)
    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    assert accepted.status_code == 200, (
        f"обычный водитель не смог принять заказ — проверка паузы в такси ничего не докажет: "
        f"{accepted.status_code} {accepted.text[:200]}"
    )


def test_отстранённый_пассажир_не_делает_предзаказ_такси(client, user_factory, fake_redis):
    """Обычный заказ такси отстранённому закрыт. Предзаказ «на время» — та же поездка,
    просто оформленная заранее: если закрыт один вход, а второй открыт, паузу обходят в два тапа."""
    passenger = user_factory("SuspSchedulePax")
    _suspend(passenger["id"])
    when = (utcnow() + timedelta(hours=6)).replace(microsecond=0).isoformat()
    r = client.post("/instant/schedule", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай", "scheduled_at": when,
    })
    assert r.status_code == 403, (
        f"отстранённый оформил предзаказ такси в обход паузы: {r.status_code} {r.text[:200]}"
    )


# ---------- Торг о цене ----------

def test_отстранённый_не_торгуется_о_цене(client, user_factory):
    """Торг — часть сделки. Если отклик отстранённому закрыт, а торг по уже висящему отклику
    открыт, он доведёт сделку до конца, начав её до паузы."""
    passenger = user_factory("SuspBargainPax")
    rid = _request_of(client, passenger)
    driver = user_factory("SuspBargainDrv", role=UserRole.driver)
    resp = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})
    assert resp.status_code == 200, f"отклик до паузы не прошёл: {resp.status_code} {resp.text[:200]}"
    resp_id = resp.json()["id"]

    # Пассажир делает встречное предложение — теперь ход водителя.
    counter = client.post(f"/responses/{resp_id}/counter", headers=passenger["auth"], json={"price": 400})
    assert counter.status_code == 200, f"встречная цена не прошла: {counter.status_code} {counter.text[:200]}"

    _suspend(driver["id"])
    r = client.post(f"/responses/{resp_id}/counter", headers=driver["auth"], json={"price": 450})
    assert r.status_code == 403, (
        f"отстранённый продолжает торговаться и закроет сделку: {r.status_code} {r.text[:200]}"
    )


# ---------- Что пауза ломать НЕ должна ----------

def test_отстранённый_всё_равно_может_позвать_на_помощь(client, user_factory):
    """Пауза — наказание за поведение, а не поражение в праве на безопасность.
    Кнопка SOS обязана работать у всех и всегда."""
    user = user_factory("SuspSosUser")
    _suspend(user["id"])
    r = client.post("/sos", headers=user["auth"], json={"lat": ORIG[0], "lng": ORIG[1]})
    assert r.status_code == 200, (
        f"отстранённый не смог нажать SOS: {r.status_code} {r.text[:200]} — "
        "наказание не должно отбирать экстренную помощь"
    )


def test_пауза_не_бросает_пассажира_в_начатой_поездке(client, user_factory):
    """Водителя отстранили, пока он уже вёз человека. Завершить поездку он обязан суметь —
    иначе бронь зависнет, а пассажир останется без отметки о состоявшейся поездке."""
    driver = user_factory("SuspMidTripDrv", role=UserRole.driver)
    # Поездка УЖЕ выехала — иначе её и завершить нельзя (аудит 2026-08-07), и тест проверял бы
    # не паузу, а планку времени. По смыслу теста человек как раз в дороге.
    ride_id = _ride(client, driver, seats=2, depart_at=just_left())
    passenger = user_factory("SuspMidTripPax")
    bid = client.post("/bookings", headers=passenger["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    sat = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"],
                      json={"status": "sat"})
    assert sat.status_code == 200, f"«сел в машину» не прошло: {sat.status_code} {sat.text[:200]}"

    _suspend(driver["id"])
    done = client.post(f"/bookings/{bid}/trip-status", headers=passenger["auth"],
                       json={"status": "done"})
    assert done.status_code == 200, (
        f"начатую поездку не дали завершить из-за паузы: {done.status_code} {done.text[:200]}"
    )
