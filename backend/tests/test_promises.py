# -*- coding: utf-8 -*-
"""Обещания Юлдаша — то, что человек читает в приложении, проверенное сквозным сценарием.

Зачем отдельный файл, если каждая дыра и так закрыта своим регресс-тестом. Тех тестов много,
и они устроены «от кода»: имя функции, статус, поле. Здесь наоборот — «от человека»: одно
обещание = один сценарий целиком, от входа до результата. Такой файл читается как список
того, что сервис реально гарантирует, и падает, когда очередная правка тихо отменяет одно
из обещаний.

Пятнадцать волн аудита 2026-08-08 нашли около двадцати мест, где обещание расходилось с кодом.
Половина — не «дыра в защите», а «защиту поставили на первый шаг и забыли про второй». Такое
не ловится чтением диффа, только сквозной проверкой.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app.db import engine
from app.models import Booking, BookingStatus, User, UserRole
from app.timeutil import utcnow
from test_instant import fake_redis  # noqa: F401 — фикстура Redis для такси-сценариев
from test_suspension_reaches_everywhere import _suspend


def _ride(client, drv, **extra):
    body = {
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300,
    }
    body.update(extra)
    r = client.post("/rides", headers=drv["auth"], json=body)
    assert r.status_code == 200, r.text
    return r.json()["id"]


# ---------------------------------------------------------------------------
# «Телефон и точка сбора открываются только после подтверждения поездки»
# ---------------------------------------------------------------------------
def test_обещание_телефон_закрыт_до_подтверждения(client, user_factory):
    drv = user_factory("Обещание: водитель", role=UserRole.driver)
    rid = _ride(client, drv, pickup="У третьего подъезда",
                pickup_lat=52.5911, pickup_lng=58.3178)
    pax = user_factory("Обещание: пассажир")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]

    before = client.get(f"/bookings/{bid}/details", headers=pax["auth"])
    assert before.status_code == 200, before.text
    assert not before.json().get("driver_phone"), "телефон открыт до подтверждения"
    assert before.json().get("pickup_lat") is None, "точная точка сбора открыта до подтверждения"

    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    after = client.get(f"/bookings/{bid}/details", headers=pax["auth"]).json()
    assert after.get("driver_phone"), "после подтверждения телефон обязан открыться"
    assert after.get("pickup_lat") is not None, "после подтверждения точка сбора обязана открыться"


# ---------------------------------------------------------------------------
# «Только женщины» = за рулём женщина И в салоне женщины
# ---------------------------------------------------------------------------
def test_обещание_только_женщины_держит_обе_стороны(client, user_factory):
    woman = user_factory("Обещание: женщина за рулём", role=UserRole.driver, gender="female")
    rid = _ride(client, woman, women_only=True)

    man = user_factory("Обещание: мужчина", gender="male")
    assert client.post("/bookings", headers=man["auth"],
                       json={"ride_id": rid, "seats": 1}).status_code == 403

    unknown = user_factory("Обещание: без пола")
    denied = client.post("/bookings", headers=unknown["auth"], json={"ride_id": rid, "seats": 1})
    assert denied.status_code == 403
    assert "профил" in denied.json()["detail"]["ru"].lower(), "отказ должен подсказывать, что делать"

    she = user_factory("Обещание: пассажирка", gender="female")
    assert client.post("/bookings", headers=she["auth"],
                       json={"ride_id": rid, "seats": 1}).status_code == 200

    # И мужчина не может поставить такую отметку своей поездке.
    man_drv = user_factory("Обещание: мужчина за рулём", role=UserRole.driver, gender="male")
    r = client.post("/rides", headers=man_drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=1)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300, "women_only": True,
    })
    assert r.status_code == 403, "мужчина поставил отметку «только женщины»"


# ---------------------------------------------------------------------------
# «Отстранённый разбором не возит» — на всех шагах сделки, а не только на первом
# ---------------------------------------------------------------------------
def test_обещание_пауза_доводит_дело_до_конца(client, user_factory):
    drv = user_factory("Обещание: отстранённый", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("Обещание: его пассажир")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]

    _suspend(drv["id"])

    # 1) новую поездку не опубликует
    assert client.post("/rides", headers=drv["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай",
        "depart_at": (utcnow() + timedelta(days=2)).replace(microsecond=0).isoformat(),
        "seats_total": 3, "price": 300,
    }).status_code == 403
    # 2) уже висящую бронь не подтвердит
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 403
    # 3) его поездка уходит из ленты — человек не тратит время впустую
    feed = client.get("/rides", headers=pax["auth"]).json()
    feed = feed.get("items", feed) if isinstance(feed, dict) else feed
    assert rid not in {x["id"] for x in feed}, "поездка отстранённого осталась в ленте"
    # 4) и по прямой ссылке к нему не забронируешь
    other = user_factory("Обещание: другой пассажир")
    assert client.post("/bookings", headers=other["auth"],
                       json={"ride_id": rid, "seats": 1}).status_code == 409


# ---------------------------------------------------------------------------
# «Заблокировал — значит не пересечёмся»
# ---------------------------------------------------------------------------
def test_обещание_блокировка_не_обходится_вторым_шагом(client, user_factory):
    pax = user_factory("Обещание: закрылась")
    req = client.post("/requests", headers=pax["auth"], json={
        "from_city": "Баймак", "to_city": "Сибай", "seats": 1})
    assert req.status_code == 200, req.text
    rid = req.json()["id"]

    drv = user_factory("Обещание: неприятный", role=UserRole.driver)
    resp = client.post(f"/requests/{rid}/respond", headers=drv["auth"], json={"price": 500})
    assert resp.status_code == 200, resp.text
    resp_id = resp.json()["id"]
    assert client.post(f"/responses/{resp_id}/counter", headers=pax["auth"],
                       json={"price": 400}).status_code == 200

    assert client.post("/blocks", headers=pax["auth"],
                       json={"blocked_user_id": drv["id"]}).status_code == 200

    # ни новым откликом, ни принятием уже сделанного предложения
    assert client.post(f"/requests/{rid}/respond", headers=drv["auth"],
                       json={"price": 450}).status_code == 403
    a = client.post(f"/responses/{resp_id}/accept", headers=drv["auth"])
    assert a.status_code == 403, "заблокированный довёл сделку до поездки"
    # и отказ не сообщает о самой блокировке
    assert "блок" not in a.json()["detail"]["ru"].lower()


# ---------------------------------------------------------------------------
# «Удалить аккаунт — значит стереть данные», все три хранилища
# ---------------------------------------------------------------------------
def test_обещание_удаление_стирает_базу_диск_и_кэш(client, user_factory, fake_redis):
    from app.account import delete_user_account
    from app.instant_service import PRESENCE_KEY
    from app.storage import get_storage
    from conftest import upload_evidence
    from test_instant import _driver_online, _heartbeat, ORIG

    d = _driver_online(client, user_factory, "Обещание: удаляется")
    _heartbeat(client, d, ORIG)
    photo = upload_evidence(client, d["auth"])
    name = photo.rsplit("/", 1)[-1]

    storage = get_storage()
    assert storage.exists(f"evidence/{name}"), "файл не сохранился — тест ничего не проверит"
    assert f"driver:{d['id']}" in set(fake_redis.zrange(PRESENCE_KEY, 0, -1))

    with Session(engine) as s:
        delete_user_account(s, s.get(User, d["id"]))

    with Session(engine) as s:
        assert s.get(User, d["id"]) is None, "строка пользователя осталась"
    assert not storage.exists(f"evidence/{name}"), "файл пережил удаление аккаунта"
    assert f"driver:{d['id']}" not in set(fake_redis.zrange(PRESENCE_KEY, 0, -1)), \
        "координаты остались в кэше"


# ---------------------------------------------------------------------------
# «Фото из разбора видят только его стороны»
# ---------------------------------------------------------------------------
def test_обещание_чужое_фото_доказательства_недоступно(client, user_factory):
    from conftest import upload_evidence

    victim = user_factory("Обещание: чужое фото")
    url = upload_evidence(client, victim["auth"])
    name = url.rsplit("/", 1)[-1]

    stranger = user_factory("Обещание: посторонний")
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403

    # и приложить чужой снимок к своему разбору тоже нельзя
    drv = user_factory("Обещание: водитель разбора", role=UserRole.driver)
    rid = _ride(client, drv)
    b = client.post("/bookings", headers=stranger["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    inc = client.post("/incidents", headers=stranger["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": b.json()["id"],
        "description": "повод выдуман", "evidence_urls": [url],
    })
    assert inc.status_code == 403, "чужое фото приложилось к разбору"
    assert client.get(f"/secure/evidence/{name}", headers=stranger["auth"]).status_code == 403


# ---------------------------------------------------------------------------
# «Точный адрес — только тому, кто взял заказ»
# ---------------------------------------------------------------------------
def test_обещание_адрес_назначения_до_принятия_приблизителен(client, user_factory, fake_redis):
    from test_instant import _driver_online, _heartbeat, _create_order, ORIG, DEST

    d = _driver_online(client, user_factory, "Обещание: таксист")
    _heartbeat(client, d, ORIG)
    pax = user_factory("Обещание: пассажирка такси")
    order = _create_order(client, pax, to_text="Сибай, ул. Горького, 15")
    assert order["status"] == "offered"

    offer = client.get("/instant/driver/offer", headers=d["auth"]).json()["offer"]
    assert offer is not None, "оффер не пришёл — тест ничего не проверяет"
    assert offer["to_text"] == "Сибай, ул. Горького", "номер дома виден до принятия"
    assert offer["to_lat"] == round(DEST[0], 2), "точная точка назначения видна до принятия"
    assert offer["price_estimate"], "но решать, брать ли заказ, водителю по-прежнему есть по чему"

    acc = client.post(f"/instant/orders/{order['id']}/accept", headers=d["auth"]).json()
    assert acc["to_text"] == "Сибай, ул. Горького, 15", "после принятия адрес обязан открыться"

# ---------------------------------------------------------------------------
# «Пожаловался — тебе не отомстят звёздочкой»
# ---------------------------------------------------------------------------
def test_обещание_щит_снимает_месть_за_жалобу(client, user_factory):
    """Самый частый и болезненный случай: человек пожаловался, виновного наказали,
    а тот в отместку поставил единицу — и она оставалась в рейтинге жертвы.

    Щит смотрел только в одну сторону (оценка заявителя на обвинённого). Проверено
    запросом: админ признал вину и включил щит, а рейтинг жертвы всё равно 1.0
    (аудит 2026-08-08, волна 16). Теперь снимаются обе оценки по спорной поездке:
    когда дело дошло до разбора, звёзды уже не про поездку, а про конфликт.
    """
    drv = user_factory("Щит: виновный", role=UserRole.driver)
    rid = _ride(client, drv)
    pax = user_factory("Щит: пожаловалась")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        bk = s.get(Booking, bid)
        bk.status = BookingStatus.done
        s.add(bk)
        s.commit()

    inc = client.post("/incidents", headers=pax["auth"], json={
        "respondent_id": drv["id"], "type": "rude", "booking_id": bid,
        "description": "Нахамил в дороге"})
    assert inc.status_code == 200, inc.text

    # обвинённый мстит единицей
    assert client.post(f"/bookings/{bid}/rate", headers=drv["auth"],
                       json={"stars": 1}).status_code == 200
    admin = user_factory("Щит: админ", role=UserRole.admin)
    before = client.get(f"/users/{pax['id']}/trust", headers=admin["auth"]).json()
    assert before["rating_count"] == 1, "месть не записалась — тест ничего не проверяет"

    res = client.post(f"/admin/incidents/{inc.json()['id']}/resolve", headers=admin["auth"],
                      json={"resolution": "warning", "fault": "respondent", "shield": True})
    assert res.status_code == 200, res.text

    after = client.get(f"/users/{pax['id']}/trust", headers=admin["auth"]).json()
    assert after["rating_count"] == 0, f"месть осталась в рейтинге жертвы: {after}"


# ---------------------------------------------------------------------------
# «За попутку сервис не берёт ничего»
# ---------------------------------------------------------------------------
def test_обещание_попутка_без_комиссии(client, user_factory):
    """На лендинге это первая цифра: 0 ₽ за попутку. Такси и доставка — 8%, попутка — ноль."""
    from app.models import CommissionDebt, LedgerEntry
    from sqlmodel import select as _sel

    drv = user_factory("Комиссия: водитель попутки", role=UserRole.driver)
    rid = _ride(client, drv, price=500)
    pax = user_factory("Комиссия: пассажир")
    b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
    assert b.status_code == 200, b.text
    bid = b.json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=drv["auth"]).status_code == 200
    with Session(engine) as s:
        bk = s.get(Booking, bid)
        bk.status = BookingStatus.done
        s.add(bk)
        s.commit()

    assert client.post(f"/bookings/{bid}/pay", headers=pax["auth"],
                       json={"method": "cash"}).status_code == 200

    with Session(engine) as s:
        debts = s.exec(_sel(CommissionDebt).where(CommissionDebt.driver_id == drv["id"])).all()
        led = s.exec(_sel(LedgerEntry).where(LedgerEntry.driver_id == drv["id"])).all()
    assert not debts, f"за попутку начислили комиссию: {[(d.amount_kop, d.status) for d in debts]}"
    assert not led, f"за попутку тронули кошелёк: {[(e.kind, e.amount_kop) for e in led]}"


# ---------------------------------------------------------------------------
# «Посылку отдадут только по коду»
# ---------------------------------------------------------------------------
def test_обещание_посылку_без_кода_не_вручить(client, user_factory):
    """Код вручения — единственное, что отделяет получателя от постороннего."""
    from app.config import settings as cfg
    from test_courier import _make_courier, _order

    prev = cfg.courier_enabled
    cfg.courier_enabled = True
    try:
        courier = _make_courier(client, user_factory)
        sender = user_factory("Код: отправитель")
        order = _order(client, sender).json()
        pid, code = order["id"], order["confirm_code"]

        assert client.post(f"/parcels/{pid}/accept", headers=courier["auth"]).status_code == 200
        assert client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                           json={"status": "in_transit"}).status_code == 200

        for body in ({"status": "delivered"},
                     {"status": "delivered", "code": ""},
                     {"status": "delivered", "code": "000000"}):
            r = client.post(f"/parcels/{pid}/status", headers=courier["auth"], json=body)
            assert r.status_code == 422, f"вручили без верного кода: {body} → {r.status_code}"

        ok = client.post(f"/parcels/{pid}/status", headers=courier["auth"],
                         json={"status": "delivered", "code": str(code).lower()})
        assert ok.status_code == 200, "верный код (в любом регистре) обязан работать"
    finally:
        cfg.courier_enabled = prev

# ---------------------------------------------------------------------------
# «Надёжность честна к обеим сторонам»
# ---------------------------------------------------------------------------
def test_обещание_надёжность_видит_и_срывы_водителя(client, user_factory):
    """Отмена в последний момент бьёт по Надёжности одинаково — кто бы её ни сделал.

    Было иначе: при отмене ПОЕЗДКИ водителем брони гасились без пометки «кто отменил», а
    формула Надёжности считает поздние отмены именно по ней. Проверено запросом: водитель
    трижды снял рейс за 20 минут до выезда — Надёжность 100, пассажирка за одну позднюю
    отмену — 0 (аудит 2026-08-08, волна 17). Человек, оставшийся на дороге, страдал дважды.

    Время выезда берём с запасом на местный пояс: сервер трактует наивную дату как уфимскую.
    """
    admin = user_factory("Надёжность: админ", role=UserRole.admin)
    drv = user_factory("Надёжность: срывает рейсы", role=UserRole.driver)
    pax = user_factory("Надёжность: его пассажир")

    def _soon_ride():
        r = client.post("/rides", headers=drv["auth"], json={
            "from_city": "Баймак", "to_city": "Сибай",
            "depart_at": (utcnow() + timedelta(hours=5, minutes=20)).replace(microsecond=0).isoformat(),
            "seats_total": 3, "price": 300})
        assert r.status_code == 200, r.text
        return r.json()["id"]

    for _ in range(3):
        rid = _soon_ride()
        b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
        assert b.status_code == 200, b.text
        assert client.post(f"/bookings/{b.json()['id']}/confirm", headers=drv["auth"]).status_code == 200
        assert client.post(f"/rides/{rid}/cancel", headers=drv["auth"]).status_code == 200

    t = client.get(f"/users/{drv['id']}/trust", headers=admin["auth"]).json()
    assert t["reliability"] < 100, f"срывы водителя не видны в Надёжности: {t}"


def test_обещание_ранняя_отмена_водителя_безвредна(client, user_factory):
    """Наказывается внезапность, а не сам отказ: снял рейс заранее — Надёжность цела.

    Обратная сторона предыдущего теста. Без неё правка «считать отмены водителя» легко
    превратилась бы в «наказывать за любую отмену», а это уже другой продукт.
    """
    admin = user_factory("Ранняя отмена: админ", role=UserRole.admin)
    drv = user_factory("Ранняя отмена: водитель", role=UserRole.driver)
    pax = user_factory("Ранняя отмена: пассажир")

    for _ in range(3):
        r = client.post("/rides", headers=drv["auth"], json={
            "from_city": "Баймак", "to_city": "Сибай",
            "depart_at": (utcnow() + timedelta(days=3)).replace(microsecond=0).isoformat(),
            "seats_total": 3, "price": 300})
        assert r.status_code == 200, r.text
        rid = r.json()["id"]
        b = client.post("/bookings", headers=pax["auth"], json={"ride_id": rid, "seats": 1})
        assert b.status_code == 200, b.text
        assert client.post(f"/bookings/{b.json()['id']}/confirm", headers=drv["auth"]).status_code == 200
        assert client.post(f"/rides/{rid}/cancel", headers=drv["auth"]).status_code == 200

    t = client.get(f"/users/{drv['id']}/trust", headers=admin["auth"]).json()
    assert t["reliability"] == 100, f"ранняя отмена наказана — это уже другой продукт: {t}"
