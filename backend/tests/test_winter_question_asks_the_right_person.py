# -*- coding: utf-8 -*-
"""Зимний вопрос «доехал?» спрашивали не того и не на том языке (волна 193).

Зимой трасса Сибай–Уфа — четыре часа. Если человек не отметил, что доехал, приложение
спрашивает «всё в порядке?», а через полчаса молчания зовёт близких. Механика правильная
и работает. Нашлись две дыры в том, ЧТО именно она спрашивает.

**Первая — язык.** Перевод вопроса на башкирский был написан и лежал в файле рядом
(`WINTER_ASK_BA`), но вызов слал только русский. Проверено пробой: башкирский не уходил
никому и никогда. Значит башкироязычный человек получал вопрос безопасности на чужом языке —
ровно там, где правило двух языков важнее всего: ночь, трасса, тридцать минут до тревоги.
Тот же класс, что волна 173, где по-русски приходил отказ на входе.

**Вторая — не тот человек.** Сын из Уфы вызывает такси маме в Баймаке (в приложении для
этого есть поля «кого везём»). Вопрос «Отметь, что доехал(а)» уходил СЫНУ — он никуда
не ехал. Нажмёт машинально «всё в порядке» — тревога погашена за человека, о котором он
ничего не знает; его «да» система принимает как факт, что мама на месте.

Право отметить оставлено за заказчиком: у мамы нет аккаунта, больше некому. Но вопрос теперь
называет ЕЁ и говорит, что сделать до ответа: позвонить. Разница между «отметь, что доехал»
и «мама доехала? позвони и отметь» — это разница между привычным тапом и одним звонком.
"""
import fakeredis
import pytest
from sqlmodel import Session

from app.db import engine
from app import instant_service as isv
from app.models import TripShare, UserRole

БАЙМАК = (52.591, 58.317)
СИБАЙ = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture
def пуши(monkeypatch):
    """Ловим двуязычный пуш: (кому, заголовок_ru, тело_ru, тело_ba)."""
    поймано = []
    monkeypatch.setattr(
        "app.routers.safety.push_bilingual",
        lambda s, uid, t_ru, t_ba, b_ru, b_ba, **kw: поймано.append((uid, t_ru, b_ru, b_ba)),
    )
    return поймано


def _поездка(client, user_factory, метка, для_другого=True):
    водитель = user_factory(f"Води193{метка}", role=UserRole.driver)
    заказчик = user_factory(f"Заказ193{метка}")
    client.post("/driver/online", headers=водитель["auth"], json={"online": True})
    client.post("/instant/presence", headers=водитель["auth"],
                json={"lat": БАЙМАК[0], "lng": БАЙМАК[1]})
    тело = {
        "from_lat": БАЙМАК[0], "from_lng": БАЙМАК[1],
        "to_lat": СИБАЙ[0], "to_lng": СИБАЙ[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }
    if для_другого:
        тело |= {"for_name": "Мама Гульнур", "for_phone": "+79170009302"}
    заказ = client.post("/instant/orders", headers=заказчик["auth"], json=тело).json()
    assert заказ.get("status") == "offered", заказ
    oid = заказ["id"]
    for шаг in ("accept", "arrived", "onboard"):
        client.post(f"/instant/orders/{oid}/{шаг}", headers=водитель["auth"])
    return заказчик, водитель, oid


def _спросить(client, кто, oid):
    return client.post(f"/instant/orders/{oid}/winter-check", headers=кто["auth"])


def test_вопрос_уходит_на_двух_языках(client, user_factory, fake_redis, пуши):
    """Главное: вопрос безопасности не может быть односторонним."""
    заказчик, _, oid = _поездка(client, user_factory, "Язык", для_другого=False)

    _спросить(client, заказчик, oid)

    assert пуши, "вопрос вообще не ушёл"
    for uid, _, ru, ba in пуши:
        assert ru and ba, f"вопрос без одного из языков: ru={ru!r} ba={ba!r}"
        assert ru != ba, f"в оба поля положили один и тот же текст: {ru!r}"


def test_заказчику_называют_того_кого_везут(client, user_factory, fake_redis, пуши):
    """«Отметь, что доехал» человеку, который никуда не ехал, — вопрос ни о чём."""
    заказчик, _, oid = _поездка(client, user_factory, "Кто")

    _спросить(client, заказчик, oid)

    вопрос = next((т for uid, _, т, _ in пуши if uid == заказчик["id"]), None)
    assert вопрос, "заказчика вообще не спросили"
    assert "Мама Гульнур" in вопрос, (
        f"у сына спрашивают про него самого: {вопрос!r}. Он в Уфе, в машине мама"
    )
    assert "озвони" in вопрос, (
        f"не сказано, что сделать перед ответом: {вопрос!r}. Без звонка его «да» — догадка"
    )


def test_башкирский_вариант_тоже_про_того_кого_везут(client, user_factory, fake_redis, пуши):
    """Второй язык не должен отставать от первого — это одна и та же мысль."""
    заказчик, _, oid = _поездка(client, user_factory, "КтоБа")

    _спросить(client, заказчик, oid)

    ba = next((б for uid, _, _, б in пуши if uid == заказчик["id"]), "")
    assert "Мама Гульнур" in ba, f"по-башкирски спрашивают не про того: {ba!r}"
    assert "шылтырат" in ba.lower(), f"нет просьбы позвонить: {ba!r}"


def test_водителя_спрашивают_как_прежде(client, user_factory, fake_redis, пуши):
    """Водитель едет сам — ему вопрос про него, а не про пассажира."""
    _, водитель, oid = _поездка(client, user_factory, "Води")

    _спросить(client, водитель, oid)

    вопрос = next((т for uid, _, т, _ in пуши if uid == водитель["id"]), None)
    assert вопрос, "водителя не спросили"
    assert "Мама Гульнур" not in вопрос, (
        f"водителю прислали вопрос про пассажира: {вопрос!r}"
    )


# --------------------------- обратная сторона ---------------------------

def test_в_обычной_поездке_вопрос_прежний(client, user_factory, fake_redis, пуши):
    """Человек едет сам — «отметь, что доехал» правильный вопрос. Не сломать его."""
    пассажир, _, oid = _поездка(client, user_factory, "Обычн", для_другого=False)

    _спросить(client, пассажир, oid)

    вопрос = next((т for uid, _, т, _ in пуши if uid == пассажир["id"]), None)
    assert "доехал" in (вопрос or ""), f"обычный вопрос потеряли: {вопрос!r}"
    assert "Позвони" not in (вопрос or ""), (
        f"человеку в машине советуют позвонить самому себе: {вопрос!r}"
    )


def test_ответ_гасит_тревогу_как_прежде(client, user_factory, fake_redis, пуши):
    """Механику не трогали: ответ по-прежнему принимается и останавливает эскалацию."""
    заказчик, _, oid = _поездка(client, user_factory, "Ответ")
    кид = client.post("/trusted-contacts", headers=заказчик["auth"],
                      json={"name": "Сестра", "phone": "+79170009301"}).json()["id"]
    with Session(engine) as s:
        s.add(TripShare(order_id=oid, contact_id=кид, user_id=заказчик["id"]))
        s.commit()
    _спросить(client, заказчик, oid)

    ответ = client.post(f"/instant/orders/{oid}/winter-check/ok", headers=заказчик["auth"])

    assert ответ.status_code == 200, ответ.text
    assert _спросить(client, заказчик, oid).json()["state"] == "ok"


def test_перевод_не_остаётся_лежать_без_дела():
    """Сторож: строка с переводом, которую никто не зовёт, — это ноль пользы.

    Так и была устроена эта дыра: `WINTER_ASK_BA` лежала в файле, глаз её видел, а вызов
    слал только русский. Проверяем по исходнику, что обе константы реально используются.
    """
    from pathlib import Path

    файл = (Path(__file__).resolve().parents[1] / "app" / "routers" / "safety.py").read_text(
        encoding="utf-8")
    for имя in ("WINTER_ASK_RU", "WINTER_ASK_BA", "WINTER_ASK_FOR_RU", "WINTER_ASK_FOR_BA"):
        assert файл.count(имя) >= 2, (
            f"{имя} объявлена, но нигде не используется — перевод есть, а человек его не увидит"
        )
