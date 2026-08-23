# -*- coding: utf-8 -*-
"""Водителя кинули на 620 ₽ — и приложение записало их ему в заработок (волна 190).

Число 620 — из того разбора; в тестах цену берём у сервера, потому что тариф меняется.

История. Пассажир вышел у подъезда и ушёл, не заплатив. Водитель нажал «пассажир не
заплатил», админ разобрал жалобу и признал её: комиссию за эту поездку с водителя сняли —
всё правильно, так и задумано.

А в заработке поездка осталась. Мало того: комиссию сняли, значит «чистыми» по ней стало
БОЛЬШЕ, чем по честной поездке. В списке она выглядела лучше остальных:

    цена 620 ₽ · комиссия 0 ₽ · чистыми 620 ₽

То есть после официального «тебя кинули» приложение показывало человеку самую выгодную
поездку недели. Он планирует по этому числу бензин, платёж по кредиту и продукты.

Это тот же класс, что волна 184 («двоим близким отправлено», когда не отправлено никому):
число, которое человек читает как факт, было намерением. Только там терялась помощь,
а здесь — деньги, и именно у того, кого уже один раз обманули.

Теперь: в заработок идут только полученные деньги. Неполученные не исчезают — они названы
отдельной строкой (`unpaid_total` / `unpaid_trips`) и помечены в списке поездок. Спрятать
их совсем было бы вторым обманом: работу человек сделал, и она должна быть видна.
"""
import fakeredis
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app import instant_service as isv
from app.models import CommissionDebt, UserRole

ORIG = (52.591, 58.317)
DEST = (52.716, 58.664)


@pytest.fixture
def fake_redis():
    r = fakeredis.FakeStrictRedis(decode_responses=True)
    isv._redis_override = r
    yield r
    isv._redis_override = None


@pytest.fixture(autouse=True)
def _sbp(monkeypatch):
    monkeypatch.setattr(settings, "owner_sbp_phone", "+79990001122")
    monkeypatch.setattr(settings, "owner_sbp_name", "Александр А.")


def _поездка(client, user_factory, fake_redis, метка):
    """Полный путь такси-заказа до «завершил»."""
    водитель = user_factory(f"Води190{метка}", role=UserRole.driver)
    пассажир = user_factory(f"Пасс190{метка}")
    assert client.post("/driver/online", headers=водитель["auth"],
                       json={"online": True}).status_code == 200
    client.post("/instant/presence", headers=водитель["auth"],
                json={"lat": ORIG[0], "lng": ORIG[1]})
    order = client.post("/instant/orders", headers=пассажир["auth"], json={
        "from_lat": ORIG[0], "from_lng": ORIG[1], "to_lat": DEST[0], "to_lng": DEST[1],
        "from_text": "Баймак", "to_text": "Сибай",
    }).json()
    assert order.get("status") == "offered", order
    oid = order["id"]
    for шаг in ("accept", "arrived", "onboard", "done"):
        assert client.post(f"/instant/orders/{oid}/{шаг}",
                           headers=водитель["auth"]).status_code == 200
    # Цену берём из ответа сервера, а не пишем числом: тариф меняется решением Александра
    # (волна 158 подняла городской per_km и per_min), и прибитая константа роняла этот тест
    # при каждой такой правке — хотя проверяет он совсем другое.
    цена = int(order.get("price_estimate") or 0)
    assert цена > 0, f"сервер не вернул цену заказа: {order}"
    return водитель, пассажир, oid, цена


def _кинули(client, user_factory, водитель, пассажир, oid, метка, подтвердить=True):
    """Водитель жалуется «не заплатил», админ подтверждает или отклоняет."""
    админ = user_factory(f"Админ190{метка}", role=UserRole.admin)
    жалоба = client.post("/reports", headers=водитель["auth"], json={
        "category": "unpaid", "order_id": oid, "target_user_id": пассажир["id"],
        "reason": "Вышел и ушёл",
    })
    assert жалоба.status_code == 200, жалоба.text
    путь = "resolve" if подтвердить else "reject"
    r = client.post(f"/admin/reports/{жалоба.json()['id']}/{путь}",
                    headers=админ["auth"], json={"resolution": "разобрано"})
    assert r.status_code == 200, r.text


def test_неоплаченная_поездка_не_идёт_в_заработок(client, user_factory, fake_redis):
    """Главное: в заработке — деньги, которые человек получил, а не которые ему обещали."""
    водитель, пассажир, oid, цена = _поездка(client, user_factory, fake_redis, "Осн")
    до = client.get("/driver/earnings?period=week", headers=водитель["auth"]).json()
    assert до["total"] == цена, до

    _кинули(client, user_factory, водитель, пассажир, oid, "Осн")

    после = client.get("/driver/earnings?period=week", headers=водитель["auth"]).json()
    assert после["total"] == 0, (
        f"разбор признал, что водителю не заплатили, а в заработке всё те же "
        f"{после['total']} ₽ — он спланирует по ним бензин и платёж"
    )


def test_неоплаченное_названо_отдельно_а_не_спрятано(client, user_factory, fake_redis):
    """Работу человек сделал. Убрать её совсем — второй обман."""
    водитель, пассажир, oid, цена = _поездка(client, user_factory, fake_redis, "Отд")

    _кинули(client, user_factory, водитель, пассажир, oid, "Отд")

    тело = client.get("/driver/earnings?period=week", headers=водитель["auth"]).json()
    assert тело["unpaid_total"] == цена, (
        f"поездка исчезла из отчёта совсем: {тело}. Человек не поймёт, куда делся вечер работы"
    )
    assert тело["unpaid_trips"] == 1, тело


def test_в_списке_поездок_чистыми_ноль_и_метка(client, user_factory, fake_redis):
    """Раньше такая поездка выглядела ЛУЧШЕ честной: комиссию сняли, «чистыми» больше."""
    водитель, пассажир, oid, цена = _поездка(client, user_factory, fake_redis, "Спис")

    _кинули(client, user_factory, водитель, пассажир, oid, "Спис")

    тело = client.get("/driver/taxi-rides", headers=водитель["auth"]).json()
    строка = next(r for r in тело["rides"] if r["order_id"] == oid)
    assert строка["net_kop"] == 0, (
        f"«чистыми» {строка['net_kop'] // 100} ₽ за поездку, за которую не заплатили"
    )
    assert строка["unpaid_confirmed"] is True, "экран не отличит её от обычной"
    assert тело["total_net_kop"] == 0, f"итог по списку врёт: {тело['total_net_kop']}"
    assert строка["price"] == цена, "цена поездки должна остаться видимой — это её работа"


def test_комиссию_с_такой_поездки_по_прежнему_снимают(client, user_factory, fake_redis):
    """Обратная сторона: старое правило (волна 2026-07-26) не должно сломаться."""
    водитель, пассажир, oid, цена = _поездка(client, user_factory, fake_redis, "Ком")

    _кинули(client, user_factory, водитель, пассажир, oid, "Ком")

    with Session(engine) as s:
        долги = s.exec(select(CommissionDebt).where(
            CommissionDebt.order_id == oid)).all()
    assert долги, "долг по заказу вообще не заводился — проверять нечего"
    assert all(str(getattr(d.status, "value", d.status)) != "pending" for d in долги), (
        "комиссию за поездку, где водителя кинули, снова требуют с него"
    )


# --------------------------- обратная сторона ---------------------------

def test_честная_поездка_считается_как_прежде(client, user_factory, fake_redis):
    """Перестраховка не должна съесть нормальный заработок."""
    водитель, _, _, цена = _поездка(client, user_factory, fake_redis, "Честн")

    тело = client.get("/driver/earnings?period=week", headers=водитель["auth"]).json()

    assert тело["total"] == цена, f"обычная поездка пропала из заработка: {тело}"
    assert тело["trips"] == 1
    assert тело["unpaid_total"] == 0, "честную поездку записали в неоплаченные"


def test_отклонённая_жалоба_заработок_не_трогает(client, user_factory, fake_redis):
    """Разбор не подтвердил — значит деньги были. Слово водителя фактом не является."""
    водитель, пассажир, oid, цена = _поездка(client, user_factory, fake_redis, "Откл")

    _кинули(client, user_factory, водитель, пассажир, oid, "Откл", подтвердить=False)

    тело = client.get("/driver/earnings?period=week", headers=водитель["auth"]).json()
    assert тело["total"] == цена, (
        f"жалобу отклонили, а деньги из заработка убрали: {тело}. Так любой мог бы "
        "переписывать свой отчёт одной кнопкой"
    )
    assert тело["unpaid_total"] == 0, тело


def test_правило_берёт_только_жалобы_этого_водителя(client, user_factory, fake_redis):
    """Правило спрашивают напрямую — иначе тест ничего не доказывает.

    Через ручку это не проверить: заказы и так отфильтрованы по водителю, а жалобу «не
    заплатил» по заказу может подать ТОЛЬКО его водитель (иначе 403). То есть снаружи
    поведение одинаковое, есть в правиле фильтр по автору жалобы или нет — мутация это
    показала. Но правило описывает смысл («жалоба ЭТОГО водителя»), и его надо закрепить:
    завтра появится вторая дверь — например, админ заведёт жалобу от чужого имени.
    """
    from app.debt import unpaid_confirmed_order_ids

    водитель, пассажир, oid, цена = _поездка(client, user_factory, fake_redis, "Чуж")
    # Уводим первого с линии: иначе оффер второго заказа уйдёт ему же — он ближе всех.
    client.post("/driver/online", headers=водитель["auth"], json={"online": False})
    другой, пассажир2, oid2, _ = _поездка(client, user_factory, fake_redis, "Чуж2")
    _кинули(client, user_factory, другой, пассажир2, oid2, "Чуж2")

    with Session(engine) as s:
        мои = unpaid_confirmed_order_ids(s, водитель["id"])
        чужие = unpaid_confirmed_order_ids(s, другой["id"])

    assert oid2 in чужие, "подтверждённая жалоба не попала в правило вовсе"
    assert oid2 not in мои, (
        f"чужая подтверждённая жалоба числится за мной: {мои}. Появится вторая дверь — "
        "и чужое «мне не заплатили» обнулит мой отчёт"
    )
    assert client.get("/driver/earnings?period=week",
                      headers=водитель["auth"]).json()["total"] == цена


def test_экран_поездок_называет_это_словами():
    """Сторож: метка живёт на телефоне и разойтись с сервером может молча."""
    from pathlib import Path

    экран = (Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main" / "java" /
             "com" / "yuldash" / "app" / "DriverTaxiRidesScreen.kt").read_text(encoding="utf-8")
    assert "unpaidConfirmed" in экран, (
        "поездка, за которую не заплатили, снова выглядит как обычная — а комиссии на ней нет, "
        "и в списке она смотрится выгоднее честных"
    )
    assert "Түләмәнеләр" in экран, "метка не на двух языках"
