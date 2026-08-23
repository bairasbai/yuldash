# -*- coding: utf-8 -*-
"""Сын нажал SOS за маму — родные поехали за 300 км не туда (волна 192).

История. Сын из Уфы вызывает такси маме в Баймаке: в приложении для этого есть поля «кого
везём» — имя и телефон. Мама звонит из машины: «водитель везёт не туда». Сын жмёт SOS.

Что уходило близким:

    SOS! СЫН просит срочной помощи. Место: <ссылка на Уфу>

Сын в этот момент дома и в безопасности, а в машине мама — в трёхстах километрах оттуда.
Сигнал брал имя и координаты ТОГО ТЕЛЕФОНА, ЧТО НАЖАЛ. Для обычной поездки это правильно:
человек в машине сам и жмёт. Для заказа «для другого» — ровно наоборот.

Хуже всего именно место. Родные читают ссылку как «она здесь» и едут. Ложное место опаснее
отсутствующего: без него они хотя бы звонят и выясняют.

Теперь: имя — того, кого везут; место — машины, её позицию сервер знает по живому треку
поездки. Нет свежего трека — не пишем места вовсе.
"""
import fakeredis
import pytest
from sqlmodel import Session

from app.db import engine
from app import instant_service as isv
from app.livepos import livepos_set
from app.models import TrustedContact, UserRole

БАЙМАК = (52.591, 58.317)          # где мама и машина
СИБАЙ = (52.716, 58.664)
УФА = (54.7351, 55.9587)           # где сын с телефоном — 300 км от мамы


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def смс(monkeypatch):
    """Собираем то, что ушло бы близким."""
    ушло = []
    monkeypatch.setattr("app.routers.safety._send_sos_sms",
                        lambda phones, text: ушло.append((list(phones), text)))
    monkeypatch.setattr("app.routers.safety.notify_admin_telegram", lambda *a, **kw: True)
    return ушло


def _поездка(client, user_factory, метка, для_другого=True):
    водитель = user_factory(f"Води192{метка}", role=UserRole.driver)
    заказчик = user_factory(f"Заказ192{метка}")
    with Session(engine) as s:
        s.add(TrustedContact(user_id=заказчик["id"], name="Сестра",
                             phone=f"+7917000{abs(hash(метка)) % 10000:04d}"))
        s.commit()
    client.post("/driver/online", headers=водитель["auth"], json={"online": True})
    client.post("/instant/presence", headers=водитель["auth"],
                json={"lat": БАЙМАК[0], "lng": БАЙМАК[1]})
    тело = {
        "from_lat": БАЙМАК[0], "from_lng": БАЙМАК[1],
        "to_lat": СИБАЙ[0], "to_lng": СИБАЙ[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }
    if для_другого:
        тело |= {"for_name": "Мама Гульнур", "for_phone": "+79170009202"}
    заказ = client.post("/instant/orders", headers=заказчик["auth"], json=тело).json()
    oid = заказ["id"]
    assert заказ.get("status") == "offered", заказ
    for шаг in ("accept", "arrived", "onboard"):
        client.post(f"/instant/orders/{oid}/{шаг}", headers=водитель["auth"])
    return заказчик, oid


def _sos(client, кто, oid, откуда=УФА):
    return client.post("/sos", headers=кто["auth"], json={
        "category": "other", "order_id": oid, "note": "везут не туда",
        "lat": откуда[0], "lng": откуда[1],
    })


def test_родным_уходит_место_машины_а_не_того_кто_нажал(client, user_factory, fake_redis, смс):
    """Главное: ложная ссылка отправляет людей не туда, и это опаснее отсутствия ссылки."""
    заказчик, oid = _поездка(client, user_factory, "Место")
    livepos_set("order", oid, БАЙМАК[0], БАЙМАК[1])      # машина едет и шлёт трек

    _sos(client, заказчик, oid, откуда=УФА)

    assert смс, "рассылка вообще не собралась"
    текст = смс[-1][1]
    assert f"{БАЙМАК[1]}" in текст, (
        f"в сигнале нет места машины: {текст}"
    )
    assert f"{УФА[1]}" not in текст, (
        f"родным ушли координаты того, кто нажал, — он в Уфе, а мама под Баймаком: {текст}"
    )


def test_родным_называют_того_кого_везут(client, user_factory, fake_redis, смс):
    """«Сын просит помощи» — сестра будет искать сына, а он дома."""
    заказчик, oid = _поездка(client, user_factory, "Имя")
    livepos_set("order", oid, БАЙМАК[0], БАЙМАК[1])

    _sos(client, заказчик, oid)

    текст = смс[-1][1]
    assert "Мама Гульнур" in текст, f"в сигнале не тот человек: {текст}"


def test_без_живого_трека_место_не_выдумывается(client, user_factory, fake_redis, смс):
    """Сигнал без места честнее сигнала с чужим местом."""
    заказчик, oid = _поездка(client, user_factory, "БезТрека")
    # Трек не пишем: связь у водителя пропала.

    _sos(client, заказчик, oid, откуда=УФА)

    текст = смс[-1][1]
    assert "https://" not in текст, (
        f"подставили координаты нажавшего вместо неизвестного места: {текст}"
    )
    assert "срочная помощь" in текст, "сам сигнал должен уйти в любом случае"


# --------------------------- обратная сторона ---------------------------

def test_в_обычной_поездке_место_прежнее(client, user_factory, fake_redis, смс):
    """Человек едет сам — его телефон и есть источник правды. Не сломать это."""
    пассажир, oid = _поездка(client, user_factory, "Сам", для_другого=False)
    livepos_set("order", oid, БАЙМАК[0], БАЙМАК[1])
    ГДЕ_ОН = (52.60, 58.33)                     # чуть в стороне от последней точки машины

    _sos(client, пассажир, oid, откуда=ГДЕ_ОН)

    текст = смс[-1][1]
    assert f"{ГДЕ_ОН[1]}" in текст, (
        f"координаты человека подменили позицией машины: {текст}. Он в кювете в стороне "
        "от дороги — искать надо там, где он, а не где машина"
    )


def test_в_обычной_поездке_имя_прежнее(client, user_factory, fake_redis, смс):
    """Имя нажавшего — правильное, когда он и есть тот, кто в беде."""
    пассажир, oid = _поездка(client, user_factory, "СамИмя", для_другого=False)
    livepos_set("order", oid, БАЙМАК[0], БАЙМАК[1])

    _sos(client, пассажир, oid)

    assert "Заказ192СамИмя" in смс[-1][1], смс[-1][1]


def test_без_своих_координат_берём_машину(client, user_factory, fake_redis, смс):
    """GPS не схватился — позиция машины лучше, чем ничего."""
    пассажир, oid = _поездка(client, user_factory, "БезGPS", для_другого=False)
    livepos_set("order", oid, БАЙМАК[0], БАЙМАК[1])

    client.post("/sos", headers=пассажир["auth"], json={
        "category": "other", "order_id": oid, "note": "без гео",
    })

    текст = смс[-1][1]
    assert f"{БАЙМАК[1]}" in текст, (
        f"человек не смог отдать координаты, а машина известна — место всё равно должно уйти: {текст}"
    )


def test_сигнал_без_поездки_работает_как_прежде(client, user_factory, fake_redis, смс):
    """SOS без привязки к поездке — самый частый случай, его трогать нельзя."""
    человек = user_factory("Одиночка192")
    with Session(engine) as s:
        s.add(TrustedContact(user_id=человек["id"], name="Брат", phone="+79170009299"))
        s.commit()

    client.post("/sos", headers=человек["auth"], json={
        "category": "other", "note": "иду одна", "lat": УФА[0], "lng": УФА[1],
    })

    текст = смс[-1][1]
    assert f"{УФА[1]}" in текст, f"потеряли место человека без поездки: {текст}"
    assert "Одиночка192" in текст
