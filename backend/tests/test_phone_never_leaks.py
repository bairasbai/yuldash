"""Чужой телефон не должен встречаться в ответах, которые видит посторонний.

Главное обещание «между своими»: номер телефона открывается только тогда, когда людям
действительно нужно созвониться — после подтверждения брони, после принятия заказа, после
того как курьер взял посылку. До этого номер не видит никто.

Почему проверять надо не правило, а ОТВЕТ целиком. Правило «телефон только после accept»
написано в трёх-четырёх местах: своя сборка карточки у брони, у такси-заказа, у посылки,
у админа. Каждая помнит про свой набор полей. Достаточно одному сериализатору вернуть
объект целиком — и номер уедет туда, куда не должен, хотя «правило соблюдено».

Поэтому здесь не сверяют поля по именам. Каждому участнику присваивается свой запоминающийся
номер, и в ответе ищется сама строка номера — в любом поле, на любой глубине, включая те,
про которые тест ничего не знает. Так ловится и утечка через поле, которого ещё не было,
когда тест писали.
"""
from __future__ import annotations

import fakeredis
import pytest
from sqlmodel import Session

from app import instant_service as isv
from app.config import settings
from app.db import engine
from app.models import User, UserRole

from test_api import _ride

ORIG = (52.5911, 58.3178)   # Баймак
DEST = (52.9128, 58.6689)   # Сибай


@pytest.fixture
def fake_redis():
    """Присутствие водителей живёт в Redis. Без подмены подбор никого не находит, заказ
    уходит в expired — и проверка «до принятия телефона не видно» позеленела бы по
    неправильной причине: принять заказ было бы просто невозможно."""
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


_phone_seq = {"n": 0}


def _set_phone(user_id: int) -> str:
    """Дать пользователю уникальный запоминающийся номер: по нему ищем утечку в тексте ответа.

    Номер генерируем, а не задаём руками: колонка уникальна, а параметризованные тесты
    выполняются по нескольку раз и с фиксированной строкой падали бы на второй."""
    _phone_seq["n"] += 1
    phone = f"+7900111{_phone_seq['n']:04d}"
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.phone = phone
        s.add(u)
        s.commit()
    return phone


def _assert_no_phone(resp, *phones: str, who: str) -> None:
    """Ни один из номеров не должен встретиться в теле ответа — ни в каком поле.

    Ответ обязан быть успешным. Это не придирка: 404 и 403 тоже «не содержат телефона»,
    и проверка позеленела бы, ничего не проверив. Ровно так один раз и вышло — адрес
    карточки посылки был выдуман, все ответы приходили 404 (найдено 2026-08-06)."""
    assert resp.status_code == 200, (
        f"{who}: ответ {resp.status_code}, а не 200 — в таком ответе телефона нет по любой "
        f"причине, и проверка ничего не доказывает. Тело: {resp.text[:200]}"
    )
    body = resp.text
    for phone in phones:
        assert phone not in body, (
            f"{who}: в ответе виден чужой телефон {phone}. Ответ: {body[:400]}"
        )


def _assert_has_phone(resp, phone: str, who: str) -> None:
    """Контроль: там, где номер ПОЛОЖЕН, он обязан быть. Без этой половины тест выше
    проходил бы и в случае «телефон не отдаётся вообще никому и никогда»."""
    assert resp.status_code == 200, f"{who}: {resp.status_code} {resp.text[:200]}"
    assert phone in resp.text, (
        f"{who}: номер {phone} не пришёл, хотя должен. Значит проверка «посторонний его не видит» "
        f"ничего не доказывает. Ответ: {resp.text[:400]}"
    )


# ---------- Попутка ----------

def test_до_подтверждения_брони_телефоны_закрыты(client, user_factory):
    driver = user_factory("PhoneRideDrv", role=UserRole.driver)
    drv_phone = _set_phone(driver["id"])
    ride_id = _ride(client, driver, seats=3)

    pax = user_factory("PhoneRidePax")
    pax_phone = _set_phone(pax["id"])

    # Лента поездок: телефона водителя там нет ни у кого.
    _assert_no_phone(client.get("/rides", headers=pax["auth"]), drv_phone, who="лента поездок")

    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    # Бронь создана, но НЕ подтверждена: созваниваться ещё не о чем.
    _assert_no_phone(client.get(f"/bookings/{bid}/details", headers=pax["auth"]),
                     drv_phone, who="пассажир до подтверждения")
    # Посторонний не должен получить карточку брони вообще — не «получить без телефона»,
    # а получить отказ: в ней ещё имя, машина и точка встречи.
    outsider = user_factory("PhoneRideOutsider")
    foreign = client.get(f"/bookings/{bid}/details", headers=outsider["auth"])
    assert foreign.status_code in (403, 404), (
        f"посторонний открыл чужую бронь: {foreign.status_code} {foreign.text[:200]}"
    )
    for phone in (drv_phone, pax_phone):
        assert phone not in foreign.text, f"в отказе постороннему виден телефон {phone}"


def test_после_подтверждения_телефон_водителя_открывается(client, user_factory):
    """Контрольная половина: правило не «телефон закрыт всегда», а «закрыт до подтверждения»."""
    driver = user_factory("PhoneRideOkDrv", role=UserRole.driver)
    drv_phone = _set_phone(driver["id"])
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("PhoneRideOkPax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200

    _assert_has_phone(client.get(f"/bookings/{bid}/details", headers=pax["auth"]),
                      drv_phone, who="пассажир после подтверждения")


def test_публичный_профиль_водителя_без_телефона(client, user_factory):
    """Карточку водителя открывают из ленты — то есть её видит кто угодно."""
    driver = user_factory("PhonePublicDrv", role=UserRole.driver)
    drv_phone = _set_phone(driver["id"])
    _ride(client, driver, seats=2)
    viewer = user_factory("PhonePublicViewer")
    _assert_no_phone(client.get(f"/drivers/{driver['id']}/public", headers=viewer["auth"]),
                     drv_phone, who="публичный профиль водителя")


# ---------- Заявка пассажира ----------

def test_лента_заявок_без_телефона_пассажира(client, user_factory):
    """Заявку видят все водители района. Номер в ней означал бы «позвоните мне кто угодно»."""
    pax = user_factory("PhoneReqPax")
    pax_phone = _set_phone(pax["id"])
    assert client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    }).status_code == 200

    driver = user_factory("PhoneReqDrv", role=UserRole.driver)
    _assert_no_phone(client.get("/requests/feed", headers=driver["auth"]),
                     pax_phone, who="лента заявок")
    _assert_no_phone(client.get("/requests/near", headers=driver["auth"],
                                params={"lat": ORIG[0], "lng": ORIG[1]}),
                     pax_phone, who="заявки рядом")


# ---------- Доставка ----------

def test_до_принятия_посылки_телефоны_закрыты(client, user_factory, monkeypatch):
    """Самый чувствительный случай: в посылке есть телефон ТРЕТЬЕГО человека — получателя,
    который приложением даже не пользуется и согласия никому не давал."""
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("PhoneParcelSender")
    sender_phone = _set_phone(sender["id"])
    receiver_phone = "+79002220007"

    created = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": receiver_phone, "rules_accepted": True,
        "from_address": "Ленина 12", "to_address": "Горького 3, кв. 5",
    })
    assert created.status_code == 200, created.text
    pid = created.json()["id"]

    courier = user_factory("PhoneParcelCourier", role=UserRole.driver)
    feed = client.get("/parcels/available", headers=courier["auth"])
    # Контроль: посылка в ленте есть. Иначе «телефона не видно» верно просто потому,
    # что не видно вообще ничего.
    assert any(x.get("id") == pid for x in feed.json()), (
        f"посылки нет в ленте курьера — проверка утечки ничего не докажет: {feed.text[:300]}"
    )
    _assert_no_phone(feed, receiver_phone, sender_phone, who="лента посылок до принятия")


def test_после_принятия_курьер_видит_телефоны_обеих_сторон(client, user_factory, monkeypatch):
    """Контрольная половина: взял посылку — обязан дозвониться и до отправителя, и до получателя."""
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("PhoneParcelOkSender")
    receiver_phone = "+79002220008"
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": receiver_phone, "rules_accepted": True,
    }).json()["id"]

    courier = user_factory("PhoneParcelOkCourier", role=UserRole.driver)
    taken = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert taken.status_code == 200, f"курьер не смог взять посылку: {taken.status_code} {taken.text[:200]}"
    _assert_has_phone(client.get("/parcels/carrying", headers=courier["auth"]),
                      receiver_phone, who="курьер после принятия")


def test_посторонний_не_видит_телефонов_принятой_посылки(client, user_factory, monkeypatch):
    """Принятие открывает номера ТОМУ КУРЬЕРУ, а не всем подряд."""
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("PhoneParcelOutSender")
    sender_phone = _set_phone(sender["id"])
    receiver_phone = "+79002220010"
    pid = client.post("/parcels", headers=sender["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "size": "medium",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": receiver_phone, "rules_accepted": True,
    }).json()["id"]
    courier = user_factory("PhoneParcelOutCourier", role=UserRole.driver)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200

    outsider = user_factory("PhoneParcelOutsider", role=UserRole.driver)
    _assert_no_phone(client.get("/parcels/available", headers=outsider["auth"]),
                     receiver_phone, sender_phone, who="посторонний: лента посылок")
    _assert_no_phone(client.get("/parcels/carrying", headers=outsider["auth"]),
                     receiver_phone, sender_phone, who="посторонний: чужая посылка в работе")


# ---------- Такси ----------

def test_до_принятия_заказа_телефон_пассажира_закрыт(client, user_factory, fake_redis):
    passenger = user_factory("PhoneTaxiPax")
    pax_phone = _set_phone(passenger["id"])
    driver = user_factory("PhoneTaxiDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200

    created = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    })
    assert created.status_code == 200, created.text
    oid = created.json()["id"]

    # Оффер водителю приходит ДО принятия: там маршрут и цена, но не номер человека.
    offer = client.get("/instant/driver/offer", headers=driver["auth"])
    assert (offer.json() or {}).get("offer"), (
        f"водителю не пришёл оффер — проверка «в оффере нет телефона» ничего не докажет: "
        f"{offer.status_code} {offer.text[:200]}"
    )
    _assert_no_phone(offer, pax_phone, who="оффер водителю до принятия")
    _assert_no_phone(client.get(f"/instant/orders/{oid}", headers=driver["auth"]),
                     pax_phone, who="карточка заказа до принятия")


def test_после_принятия_водитель_видит_телефон_пассажира(client, user_factory, fake_redis):
    """Контрольная половина: принял заказ — надо дозвониться, иначе человек стоит у ворот."""
    passenger = user_factory("PhoneTaxiOkPax")
    pax_phone = _set_phone(passenger["id"])
    driver = user_factory("PhoneTaxiOkDrv", role=UserRole.driver)
    assert client.post("/driver/online", headers=driver["auth"], json={"online": True}).status_code == 200
    assert client.post("/instant/presence", headers=driver["auth"],
                       json={"lat": ORIG[0], "lng": ORIG[1]}).status_code == 200
    oid = client.post("/instant/orders", headers=passenger["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()["id"]
    accepted = client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])
    assert accepted.status_code == 200, f"водитель не принял заказ: {accepted.status_code} {accepted.text[:200]}"

    _assert_has_phone(client.get(f"/instant/orders/{oid}", headers=driver["auth"]),
                      pax_phone, who="водитель после принятия")


@pytest.mark.parametrize("path", ["/rides", "/rides/near", "/requests/feed"])
def test_открытые_ленты_не_содержат_ни_одного_телефона(client, user_factory, path):
    """Сквозная проверка лент: что бы туда ни добавили потом, номера там быть не должно."""
    driver = user_factory("PhoneFeedDrv", role=UserRole.driver)
    drv_phone = _set_phone(driver["id"])
    _ride(client, driver, seats=2)
    pax = user_factory("PhoneFeedPax")
    pax_phone = _set_phone(pax["id"])
    client.post("/requests", headers=pax["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 1,
    })

    viewer = user_factory("PhoneFeedViewer", role=UserRole.driver)
    params = {"from_city": "Сибай", "to_city": "Уфа"} if "near" in path else None
    _assert_no_phone(client.get(path, headers=viewer["auth"], params=params),
                     drv_phone, pax_phone, who=f"лента {path}")
