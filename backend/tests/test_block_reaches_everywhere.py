"""Работает ли блокировка человека во ВСЕХ сценариях, а не только в попутках.

Зачем это важнее обычной проверки. Кнопка «заблокировать» — обещание безопасности:
женщина заблокировала водителя, который вёл себя плохо, и рассчитывает больше с ним
не встретиться. Если блокировка действует в попутках, но не действует в такси, обещание
ложное — а человек об этом не знает и чувствует себя защищённым.

Приложение к моменту проверки умело три сценария: попутки, быстрый заказ такси и доставку
посылок. Блокировка изначально писалась под попутки. Здесь проверяется, дотянулась ли она
до остальных двух.

Правило: если между двумя людьми есть блокировка — они не должны сводиться НИГДЕ:
ни в поездке, ни в такси-заказе, ни в доставке, ни в чате.
"""
from __future__ import annotations

import fakeredis
import pytest

from app import instant_service as isv
from app.models import UserRole

from test_api import _ride

ORIG = (52.5911, 58.3178)   # Баймак
DEST = (52.9128, 58.6689)   # Сибай


@pytest.fixture
def taxi_on(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)
    yield


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


def _block(client, who, whom_id: int):
    r = client.post("/blocks", headers=who["auth"], json={"blocked_user_id": whom_id})
    assert r.status_code in (200, 201), f"не удалось заблокировать: {r.status_code} {r.text[:200]}"


# ---------- Попутки: тут блокировка работала изначально ----------

def test_заблокированный_не_бронирует_поездку(client, user_factory):
    driver = user_factory("BlockRideDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    passenger = user_factory("BlockRidePassenger")
    _block(client, passenger, driver["id"])

    r = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code != 200, "заблокированный водитель всё равно повёз пассажира"


def test_заблокированный_не_откликается_на_заявку(client, user_factory):
    passenger = user_factory("BlockReqPassenger")
    rid = client.post("/requests", headers=passenger["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    }).json()["id"]
    driver = user_factory("BlockReqDriver", role=UserRole.driver)
    _block(client, passenger, driver["id"])

    r = client.post(f"/requests/{rid}/respond", headers=driver["auth"], json={"price": 500})
    assert r.status_code != 200, "заблокированный водитель откликнулся на заявку"


def test_блокировка_работает_в_обе_стороны(client, user_factory):
    """Неважно, кто кого заблокировал — сводить их нельзя в любом случае."""
    driver = user_factory("BlockBothDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    passenger = user_factory("BlockBothPassenger")
    _block(client, driver, passenger["id"])   # блокирует ВОДИТЕЛЬ

    r = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1})
    assert r.status_code != 200, "блокировка сработала только в одну сторону"


# ---------- Такси ----------

def test_заблокированный_водитель_не_получает_заказ_такси(client, user_factory, taxi_on, fake_redis):
    """Главная проверка этого файла. Человек заблокировал водителя после плохой поездки
    и заказывает такси. Если блокировка не дотянулась до такси — приедет он же."""
    passenger = user_factory("BlockTaxiPassenger")
    driver = user_factory("BlockTaxiDriver", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    _block(client, passenger, driver["id"])

    created = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert created.status_code == 200, created.text
    oid = created.json()["id"]

    # Заблокированному водителю заказ предлагаться не должен. Проверяем и предложение,
    # и — на всякий случай — саму возможность его принять.
    offer = client.get("/driver/offer", headers=driver["auth"])
    offered_id = (offer.json() or {}).get("id") if offer.status_code == 200 and offer.json() else None
    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])

    assert offered_id != oid or accepted.status_code != 200, (
        "заблокированный водитель получил и принял заказ такси от человека, который его "
        "заблокировал — кнопка «заблокировать» обещает безопасность, которой нет"
    )


# ---------- Доставка ----------

def test_заблокированный_курьер_не_берёт_посылку(client, user_factory, taxi_on, fake_redis, monkeypatch):
    """То же самое в доставке: посылка отправителя не должна достаться тому, кого он заблокировал."""
    from app.config import settings
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)

    sender = user_factory("BlockParcelSender")
    courier = user_factory("BlockParcelCourier", role=UserRole.driver)
    _block(client, sender, courier["id"])

    created = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990009901", "rules_accepted": True,
    })
    # Не skip: пропущенный тест показывает зелёный и ничего не сторожит.
    assert created.status_code == 200, f"посылка не создалась: {created.status_code} {created.text[:200]}"
    pid = created.json()["id"]

    taken = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert taken.status_code != 200, (
        "заблокированный курьер забрал посылку человека, который его заблокировал"
    )


# ---------- Чат ----------

def test_заблокированный_не_пишет_в_чат(client, user_factory):
    driver = user_factory("BlockChatDriver", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=2)
    passenger = user_factory("BlockChatPassenger")
    bid = client.post("/bookings", headers=passenger["auth"], json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=driver["auth"])
    _block(client, passenger, driver["id"])

    r = client.post(f"/bookings/{bid}/messages", headers=driver["auth"], json={"text": "ответь мне"})
    assert r.status_code != 200, "заблокированный продолжает писать в чат"


def test_контроль_без_блокировки_водитель_заказ_получает(client, user_factory, taxi_on, fake_redis):
    """Контрольный случай к тесту выше.

    Без него проверка блокировки в такси ничего не стоит: если бы водитель не получал
    заказ вообще никогда (например, матчер в тестовой среде не срабатывает), тест про
    блокировку был бы зелёным по неправильной причине. Здесь тот же сценарий БЕЗ блокировки —
    водитель заказ получить обязан."""
    passenger = user_factory("ControlTaxiPassenger")
    driver = user_factory("ControlTaxiDriver", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200

    created = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert created.status_code == 200, created.text
    oid = created.json()["id"]

    offer = client.get("/driver/offer", headers=driver["auth"])
    offered = (offer.json() or {}) if offer.status_code == 200 and offer.json() else {}
    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])

    assert offered.get("id") == oid or accepted.status_code == 200, (
        "свободный водитель рядом не получил заказ — значит проверка блокировки в такси "
        f"ничего не доказывает. Оффер: {offer.status_code} {offer.text[:150]}, "
        f"приём: {accepted.status_code} {accepted.text[:150]}"
    )


def test_заблокированный_не_видит_посылку_в_ленте(client, user_factory, monkeypatch):
    """Мало отказать при попытке взять — незачем и показывать. Иначе человек жмёт «Взять»
    и получает необъяснимый отказ."""
    from app.config import settings
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)

    sender = user_factory("FeedBlockSender")
    courier = user_factory("FeedBlockCourier", role=UserRole.driver)
    created = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990009902", "rules_accepted": True,
    })
    assert created.status_code == 200, created.text
    pid = created.json()["id"]

    # Контроль: до блокировки посылка в ленте есть — иначе проверка ниже ничего не значит.
    before = client.get("/parcels/available", headers=courier["auth"]).json()
    assert any(p.get("id") == pid for p in before), (
        "посылки нет в ленте и без блокировки — проверка фильтра ничего не докажет"
    )

    _block(client, sender, courier["id"])
    after = client.get("/parcels/available", headers=courier["auth"]).json()
    assert all(p.get("id") != pid for p in after), (
        "заблокированный курьер продолжает видеть посылку в ленте"
    )
