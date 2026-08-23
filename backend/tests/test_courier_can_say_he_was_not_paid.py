# -*- coding: utf-8 -*-
"""У курьера не было кнопки «мне не заплатили» — при том что рискует он больше всех (волна 191).

История. Курьер купил лекарства на СВОИ 1800 ₽, привёз, получатель назвал код и забрал пакет.
Денег не отдал. У водителя такси на этот случай есть кнопка «пассажир не заплатил» — одним
тапом, и после разбора комиссию за поездку с него снимают. У водителя попутки — тоже.
У курьера не было ничего.

Что видел курьер (проверено пробой):

    доставка помечена «получатель рассчитался»
    должен платформе 164 ₽ комиссии
    заработок за неделю: 3 129 ₽ «чистыми»
    жалобу привязать к доставке нельзя — поля просто нет

Последнее и есть корень: `ReportIn` принимал привязку к заказу такси и к брони попутки,
а к доставке — нет. Жалоба уходила «в никуда», разбор такую не обрабатывал, и человек,
оставивший в аптеке свои деньги, оставался с ними один.

Теперь третья дверь работает так же, как две первые: только курьер этой доставки, только
вручённая, дедуп на доставку. Разбор подтвердил — комиссию снимают, отметку «рассчитался»
снимают, доставка уходит из заработка, а в чеке названо, сколько отправитель должен курьеру:
товар плюс доставка (услуга-то оказана, коробка у получателя).
"""
import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, Report, UserRole
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _make_courier(client, user_factory, name):
    admin = user_factory(name=f"Админ{name}", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    client.post("/courier/online", headers=c["auth"], json={"zone": "region"})
    return c


def _вручённая_покупка(client, user_factory, метка):
    """Полный путь «купи и привези» до вручения: курьер потратил свои 1800 ₽."""
    отправитель = _make_courier(client, user_factory, f"Отпр191{метка}")
    курьер = _make_courier(client, user_factory, f"Кур191{метка}")
    pid = client.post("/courier/orders", headers=отправитель["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Лекарство", "receiver_name": "Айгуль",
        "receiver_phone": f"+7999{abs(hash(метка)) % 10**7:07d}", "rules_accepted": True,
        "delivery_type": "buy_bring", "urgency": "bypath",
        "cod_amount_kop": 200_000, "declared_value_kop": 250_000,
    }).json()["id"]
    assert client.post(f"/parcels/{pid}/accept", headers=курьер["auth"]).status_code == 200
    assert client.post(f"/courier/orders/{pid}/goods-cost", headers=курьер["auth"],
                       json={"actual_kop": 180_000}).status_code == 200
    client.post(f"/parcels/{pid}/status", headers=курьер["auth"], json={"status": "in_transit"})
    with Session(engine) as s:
        код = s.get(ParcelDelivery, pid).confirm_code
    assert client.post(f"/parcels/{pid}/status", headers=курьер["auth"],
                       json={"status": "delivered", "code": код}).status_code == 200
    return отправитель, курьер, pid


def _пожаловаться(client, кто, pid, цель=None):
    тело = {"category": "unpaid", "parcel_id": pid, "reason": "Получатель не заплатил"}
    if цель is not None:
        тело["target_user_id"] = цель
    return client.post("/reports", headers=кто["auth"], json=тело)


def _разобрать(client, user_factory, rid, метка, подтвердить=True):
    админ = user_factory(f"Админ191Р{метка}", role=UserRole.admin)
    путь = "resolve" if подтвердить else "reject"
    r = client.post(f"/admin/reports/{rid}/{путь}", headers=админ["auth"],
                    json={"resolution": "разобрано"})
    assert r.status_code == 200, r.text


def test_курьер_может_сказать_что_ему_не_заплатили(client, user_factory):
    """Главное: у человека, оставившего в аптеке свои деньги, должна быть дверь."""
    _, курьер, pid = _вручённая_покупка(client, user_factory, "Дверь")

    r = _пожаловаться(client, курьер, pid)

    assert r.status_code == 200, f"курьеру некуда пойти: {r.status_code} {r.text[:200]}"
    with Session(engine) as s:
        жалоба = s.exec(select(Report).where(Report.id == r.json()["id"])).first()
    assert жалоба.parcel_id == pid, (
        "жалоба ушла без привязки к доставке — разбор такую не обрабатывает, "
        "и она ничего не изменит"
    )


def test_подтверждённая_жалоба_снимает_комиссию_и_отметку_расчёта(client, user_factory):
    """Отметку «получатель рассчитался» ставит вручение. Вручение — это код, а не деньги."""
    _, курьер, pid = _вручённая_покупка(client, user_factory, "Комис")
    rid = _пожаловаться(client, курьер, pid).json()["id"]

    _разобрать(client, user_factory, rid, "Комис")

    with Session(engine) as s:
        p = s.get(ParcelDelivery, pid)
    assert p.settled is False, "доставка всё ещё числится оплаченной получателем"
    assert p.commission_kop == 0, (
        f"с курьера требуют {p.commission_kop // 100} ₽ комиссии за доставку, "
        "за которую ему не заплатили"
    )
    кабинет = client.get("/courier/me", headers=курьер["auth"]).json()
    assert кабинет["statement"]["commission_owed_kop"] == 0, кабинет["statement"]


def test_неоплаченная_доставка_уходит_из_заработка(client, user_factory):
    """Тот же принцип, что у водителя (волна 190): в заработке — полученные деньги."""
    _, курьер, pid = _вручённая_покупка(client, user_factory, "Зараб")
    до = client.get("/courier/earnings?period=week", headers=курьер["auth"]).json()
    assert до["net_kop"] > 0, до
    rid = _пожаловаться(client, курьер, pid).json()["id"]

    _разобрать(client, user_factory, rid, "Зараб")

    после = client.get("/courier/earnings?period=week", headers=курьер["auth"]).json()
    assert после["net_kop"] == 0, (
        f"в заработке остались {после['net_kop'] // 100} ₽, которых курьер не получал"
    )
    assert после["unpaid_net_kop"] > 0, (
        f"работа исчезла из отчёта совсем: {после}. Курьер не поймёт, куда делся день"
    )
    assert после["unpaid_deliveries"] == 1, после


def test_чек_называет_сколько_должен_отправитель(client, user_factory):
    """Число в чеке читают как решение спора — значит оно обязано быть верным."""
    отправитель, курьер, pid = _вручённая_покупка(client, user_factory, "Чек")
    rid = _пожаловаться(client, курьер, pid).json()["id"]

    _разобрать(client, user_factory, rid, "Чек")

    чек = client.get(f"/parcels/{pid}/receipt", headers=курьер["auth"]).json()
    assert чек["owed_to_courier_kop"] == чек["goods_kop"] + чек["delivery_price_kop"], (
        f"долг курьеру {чек['owed_to_courier_kop'] // 100} ₽ — а он оставил в магазине "
        f"{чек['goods_kop'] // 100} ₽ и довёз за {чек['delivery_price_kop'] // 100} ₽"
    )
    чек_отправителя = client.get(f"/parcels/{pid}/receipt", headers=отправитель["auth"]).json()
    assert чек_отправителя["owed_to_courier_kop"] == чек["owed_to_courier_kop"], (
        "стороны видят разные суммы в одном чеке — спор упрётся ровно в это"
    )


def test_дверь_только_для_курьера_этой_доставки(client, user_factory):
    """Чужой не отмечает чужие деньги — как у такси и попутки."""
    отправитель, _, pid = _вручённая_покупка(client, user_factory, "Чужой")

    r = _пожаловаться(client, отправитель, pid)

    assert r.status_code == 403, (
        f"отправитель отметил «мне не заплатили» за курьера: {r.status_code}"
    )
    detail = r.json()["detail"]
    assert detail["ru"] and detail["ba"] and detail["ru"] != detail["ba"], detail


def test_повторный_тап_не_плодит_жалобы(client, user_factory):
    """Человек в сердцах жмёт дважды — это не две жалобы."""
    _, курьер, pid = _вручённая_покупка(client, user_factory, "Дубль")

    первая = _пожаловаться(client, курьер, pid)
    вторая = _пожаловаться(client, курьер, pid)

    assert первая.json()["id"] == вторая.json()["id"], "второй тап завёл вторую жалобу"


# --------------------------- обратная сторона ---------------------------

def test_отклонённая_жалоба_ничего_не_меняет(client, user_factory):
    """Разбор не подтвердил — значит деньги были. Слово курьера фактом не является."""
    _, курьер, pid = _вручённая_покупка(client, user_factory, "Откл")
    rid = _пожаловаться(client, курьер, pid).json()["id"]

    _разобрать(client, user_factory, rid, "Откл", подтвердить=False)

    заработок = client.get("/courier/earnings?period=week", headers=курьер["auth"]).json()
    assert заработок["net_kop"] > 0, (
        f"жалобу отклонили, а заработок обнулили: {заработок}. Так любой переписывал бы "
        "свой отчёт одной кнопкой"
    )
    with Session(engine) as s:
        assert s.get(ParcelDelivery, pid).settled is True


def test_честная_доставка_считается_как_прежде(client, user_factory):
    """Перестраховка не должна съесть нормальный заработок курьера."""
    _, курьер, pid = _вручённая_покупка(client, user_factory, "Честн")

    заработок = client.get("/courier/earnings?period=week", headers=курьер["auth"]).json()
    чек = client.get(f"/parcels/{pid}/receipt", headers=курьер["auth"]).json()

    assert заработок["deliveries"] == 1, заработок
    assert заработок["unpaid_net_kop"] == 0, "честную доставку записали в неоплаченные"
    assert чек["owed_to_courier_kop"] == 0, "по вручённой и оплаченной доставке долга нет"


def test_отметить_можно_только_вручённую(client, user_factory):
    """До вручения денег и не должно быть — кнопка тут ни при чём."""
    отправитель = _make_courier(client, user_factory, "ОтпрРано191")
    курьер = _make_courier(client, user_factory, "КурРано191")
    pid = client.post("/courier/orders", headers=отправитель["auth"], json={
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958, "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Коробка", "receiver_name": "Айгуль",
        "receiver_phone": "+79990001919", "rules_accepted": True,
        "delivery_type": "courier", "urgency": "bypath",
    }).json()["id"]
    client.post(f"/parcels/{pid}/accept", headers=курьер["auth"])

    r = _пожаловаться(client, курьер, pid)

    assert r.status_code == 409, f"отметили «не заплатили» до вручения: {r.status_code}"
