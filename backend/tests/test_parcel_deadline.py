"""У доставки появляется срок: «к какому дню нужно» (deliver_by) + серверный флаг overdue.

Раньше у заявки была только `urgency` (bypath|now) — это про СПОСОБ, а не про срок: у «по пути»
доставка зависит от того, поедет ли кто-то в ту сторону, и могла тянуться неделю. Отправитель
не понимал, доедет ли посылка сегодня или через неделю; курьер, глядя на заявку, не знал,
ждут ли её к завтрашнему утру.

Что проверяем:
- срок сохраняется и отдаётся во ВСЕХ витринах (создание, «мои», принявший курьер, админ);
- срок виден в ОТКРЫТОМ списке свободных заказов — курьеру он нужен ДО принятия (в отличие
  от телефона и адресов, которые до accept скрыты): это условие заказа, а не персональные данные;
- окно выбора: сегодня — ок, +30 дней — ок; вчера → 422; +31 день → 422 (detail двуязычный);
- без срока всё работает как раньше: deliver_by=null, overdue=false, никаких 422;
- overdue считает СЕРВЕР: срок вышел и доставка не завершена → true; у доставленной → false.

День везде МЕСТНЫЙ (Уфа UTC+5) — берём тот же `workday.local_day`, что и сервер, иначе тест
мигал бы около полуночи (у машины разработчика свой часовой пояс).
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.config import settings
from app.db import engine
from app.models import ParcelDelivery, UserRole
from app.workday import local_day

from test_parcels import _create_parcel

_MAX_DAYS = 30           # окно выбора срока (parcels._DELIVER_BY_MAX_DAYS)


def _iso(days_from_today: int) -> str:
    """Местный день + N дней в формате ГГГГ-ММ-ДД (как шлёт клиент)."""
    return (local_day() + timedelta(days=days_from_today)).isoformat()


def _set_deadline(parcel_id: int, days_from_today: int, status: str | None = None) -> None:
    """Подвинуть срок (и, если надо, статус) прямо в БД — «прошедший срок» через API не создать:
    заявку с датой из прошлого сервер честно не принимает."""
    with Session(engine) as s:
        p = s.get(ParcelDelivery, parcel_id)
        p.deliver_by = local_day() + timedelta(days=days_from_today)
        if status:
            p.status = status
        s.add(p)
        s.commit()


# ============================ Приём и хранение ============================

def test_deadline_saved_and_returned_everywhere(client, user_factory):
    """Срок сохраняется как есть и виден всем, кто видит заявку: отправителю (создание и «мои»),
    принявшему курьеру (accept и «я везу»), админу. Пока срок не вышел — overdue=false."""
    sender = user_factory(name="СрокОтпр")
    courier = user_factory(name="СрокКурьер", role=UserRole.driver)
    admin = user_factory(name="СрокАдмин", role=UserRole.admin)
    day = _iso(3)

    created = _create_parcel(client, sender, from_city="Сибай", to_city="Баймак", deliver_by=day)
    assert created.status_code == 200, created.text
    assert created.json()["deliver_by"] == day
    assert created.json()["overdue"] is False
    pid = created.json()["id"]

    mine = next(x for x in client.get("/parcels/mine", headers=sender["auth"]).json() if x["id"] == pid)
    assert mine["deliver_by"] == day

    accepted = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["deliver_by"] == day

    carrying = next(x for x in client.get("/parcels/carrying", headers=courier["auth"]).json()
                    if x["id"] == pid)
    assert carrying["deliver_by"] == day

    adm = client.get("/admin/parcels", headers=admin["auth"])
    assert adm.status_code == 200, adm.text
    assert next(x for x in adm.json()["parcels"] if x["id"] == pid)["deliver_by"] == day


def test_deadline_visible_in_open_available_list(client, user_factory):
    """Открытый список свободных заявок: срок ЕСТЬ. Курьер решает, берётся ли он успеть, —
    значит должен видеть срок ДО принятия. Телефон и адреса там по-прежнему скрыты."""
    sender = user_factory(name="СрокОтпр2")
    courier = user_factory(name="СрокКурьер2", role=UserRole.driver)
    day = _iso(2)
    pid = _create_parcel(client, sender, from_city="Сибай", to_city="Учалы",
                         deliver_by=day).json()["id"]

    ra = client.get("/parcels/available", headers=courier["auth"])
    assert ra.status_code == 200, ra.text
    row = next(x for x in ra.json() if x["id"] == pid)
    assert row["deliver_by"] == day
    assert row["overdue"] is False
    assert not row.get("receiver_phone")        # приватное по-прежнему скрыто
    assert "to_address" not in row


def test_no_deadline_works_as_before(client, user_factory):
    """Без срока — всё как раньше: 200, deliver_by=null, overdue=false. «Когда получится» —
    честный вариант заказа, а не забытое поле. Явный null тоже принимаем (старый и новый клиент)."""
    sender = user_factory(name="СрокОтпр3")

    silent = _create_parcel(client, sender)                    # поле вообще не прислали
    assert silent.status_code == 200, silent.text
    assert silent.json()["deliver_by"] is None
    assert silent.json()["overdue"] is False

    explicit = _create_parcel(client, sender, deliver_by=None)  # прислали null
    assert explicit.status_code == 200, explicit.text
    assert explicit.json()["deliver_by"] is None

    blank = _create_parcel(client, sender, deliver_by="   ")    # форму открыли и не заполнили
    assert blank.status_code == 200, blank.text
    assert blank.json()["deliver_by"] is None


# ============================ Окно выбора ============================

def test_deadline_today_and_max_day_allowed(client, user_factory):
    """Границы окна открыты: сегодня — можно (успеть за день реально), ровно +30 дней — тоже."""
    sender = user_factory(name="СрокОтпр4")

    today = _create_parcel(client, sender, deliver_by=_iso(0))
    assert today.status_code == 200, today.text
    assert today.json()["deliver_by"] == _iso(0)

    edge = _create_parcel(client, sender, deliver_by=_iso(_MAX_DAYS))
    assert edge.status_code == 200, edge.text
    assert edge.json()["deliver_by"] == _iso(_MAX_DAYS)


def test_deadline_in_past_rejected(client, user_factory):
    """Вчерашний день → 422: заявка родилась бы уже просроченной. Сообщение — двуязычное."""
    sender = user_factory(name="СрокОтпр5")
    r = _create_parcel(client, sender, deliver_by=_iso(-1))
    assert r.status_code == 422, r.text
    detail = r.json()["detail"]
    assert "прошёл" in detail["ru"]
    assert detail["ba"] and detail["ba"] != detail["ru"]


def test_deadline_too_far_rejected(client, user_factory):
    """Дальше окна (+31 день) → 422: это уже не срок, а опечатка в календаре."""
    sender = user_factory(name="СрокОтпр6")
    r = _create_parcel(client, sender, deliver_by=_iso(_MAX_DAYS + 1))
    assert r.status_code == 422, r.text
    assert str(_MAX_DAYS) in r.json()["detail"]["ru"]
    assert r.json()["detail"]["ba"]


def test_deadline_garbage_rejected(client, user_factory):
    """Не дата (мусор в поле) → 422, а не 500."""
    sender = user_factory(name="СрокОтпр7")
    assert _create_parcel(client, sender, deliver_by="завтра утром").status_code == 422


# ============================ overdue: считает сервер ============================

def test_overdue_true_when_deadline_passed(client, user_factory):
    """Срок вышел, а посылка ещё в пути → overdue=true у обеих сторон и в открытом списке.
    Клиент не занимается календарной арифметикой и часовыми поясами — флаг приходит готовым."""
    sender = user_factory(name="ПросрочОтпр")
    courier = user_factory(name="ПросрочКурьер", role=UserRole.driver)
    pid = _create_parcel(client, sender, from_city="Сибай", to_city="Баймак",
                         deliver_by=_iso(1)).json()["id"]
    _set_deadline(pid, -1)          # вчера: срок вышел, статус остался created

    mine = next(x for x in client.get("/parcels/mine", headers=sender["auth"]).json() if x["id"] == pid)
    assert mine["overdue"] is True
    assert mine["deliver_by"] == _iso(-1)

    row = next(x for x in client.get("/parcels/available", headers=courier["auth"]).json()
               if x["id"] == pid)
    assert row["overdue"] is True

    # взяли в работу — просрочка никуда не делась (пока не довезли, срок остаётся сорванным)
    assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).json()["overdue"] is True


def test_overdue_false_when_delivered(client, user_factory):
    """У завершённой доставки overdue=false, даже если срок в прошлом: везти уже нечего,
    а красный значок на вручённой посылке — просто враньё. Так же для отменённой и возвращённой."""
    sender = user_factory(name="ПросрочОтпр2")
    for status in ("delivered", "canceled", "returned"):
        pid = _create_parcel(client, sender, deliver_by=_iso(1)).json()["id"]
        _set_deadline(pid, -5, status=status)
        mine = next(x for x in client.get("/parcels/mine", headers=sender["auth"]).json()
                    if x["id"] == pid)
        assert mine["overdue"] is False, status
        assert mine["deliver_by"] == _iso(-5)


def test_overdue_false_on_last_day(client, user_factory):
    """Сегодняшний срок ещё НЕ просрочен: день не кончился, курьер успевает."""
    sender = user_factory(name="ПросрочОтпр3")
    p = _create_parcel(client, sender, deliver_by=_iso(0)).json()
    assert p["overdue"] is False


# ============================ Заказ курьера (тот же контракт) ============================

@pytest.fixture(autouse=True)
def _courier_on():
    prev = settings.courier_enabled
    settings.courier_enabled = True
    yield
    settings.courier_enabled = prev


def _order(client, sender, **ov):
    body = {
        "from_city": "Уфа", "to_city": "Стерлитамак",
        "from_lat": 54.735, "from_lng": 55.958,
        "to_lat": 53.630, "to_lng": 55.950,
        "size": "small", "description": "Документы",
        "receiver_name": "Айгүл", "receiver_phone": "+79990001144",
        "rules_accepted": True, "delivery_type": "courier", "urgency": "bypath",
    }
    body.update(ov)
    return client.post("/courier/orders", headers=sender["auth"], json=body)


def _make_courier(client, user_factory, name="СрокКурьерПроф"):
    admin = user_factory(name="СрокАдминК", role=UserRole.admin)
    c = user_factory(name=name)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car", "selfie_url": "secure/docs/selfie.jpg"}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=admin["auth"]).status_code == 200
    assert client.post("/courier/online", headers=c["auth"],
                       json={"zone": "region"}).status_code == 200
    return c


def test_courier_order_deadline_saved_and_visible_before_accept(client, user_factory):
    """Заказ курьера принимает срок и показывает его в закрытом списке заказов ДО принятия:
    курьер на линии решает, успеет ли он к этому дню, ещё до того как взял заказ."""
    sender = user_factory(name="СрокЗаказчик")
    courier = _make_courier(client, user_factory)
    day = _iso(4)

    ro = _order(client, sender, deliver_by=day)
    assert ro.status_code == 200, ro.text
    assert ro.json()["deliver_by"] == day
    assert ro.json()["overdue"] is False
    oid = ro.json()["id"]

    av = client.get("/courier/available", headers=courier["auth"])
    assert av.status_code == 200, av.text
    row = next(x for x in av.json() if x["id"] == oid)
    assert row["deliver_by"] == day
    assert not row.get("receiver_phone")     # приватное по-прежнему скрыто


def test_courier_order_deadline_window_same_rule(client, user_factory):
    """Окно у заказа курьера то же самое (одна функция проверки на оба входа):
    прошлое → 422, дальше 30 дней → 422, без срока → 200 и deliver_by=null."""
    sender = user_factory(name="СрокЗаказчик2")

    past = _order(client, sender, deliver_by=_iso(-1))
    assert past.status_code == 422, past.text
    assert past.json()["detail"]["ba"]

    far = _order(client, sender, deliver_by=_iso(_MAX_DAYS + 1))
    assert far.status_code == 422, far.text

    none = _order(client, sender)
    assert none.status_code == 200, none.text
    assert none.json()["deliver_by"] is None
    assert none.json()["overdue"] is False

    blank = _order(client, sender, deliver_by="")   # пустое поле — не ошибка
    assert blank.status_code == 200, blank.text
    assert blank.json()["deliver_by"] is None
