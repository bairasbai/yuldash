"""Двойной тап не должен удваивать последствия.

Откуда берётся второй тап. В деревне со слабой связью человек жмёт «Подтвердить» ещё раз,
потому что первый раз «ничего не произошло»: ответ шёл десять секунд, экран молчал.
Приложение и само повторяет запрос при обрыве. То есть повтор — не редкость, а норма.

Самое дорогое здесь — жалобы. Три разобранных жалобы за месяц АВТОМАТИЧЕСКИ ставят такси
на паузу, и считаются строки, без учёта того, кто их подал. Значит один человек, нажав
«Пожаловаться» три раза, собирал всю лестницу в одиночку — случайно на плохой связи или
намеренно против конкурента (аудит 2026-08-06).

Правило: повтор одного и того же действия не создаёт второе последствие. И отвечать на него
надо не ошибкой, а «уже сделано» — иначе человек жмёт снова.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import Block, Report, Ride, UserRole

from test_api import _ride


def _confirmed(client, user_factory, tag):
    driver = user_factory(f"{tag}Drv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory(f"{tag}Pax")
    bid = client.post("/bookings", headers=pax["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    return driver, pax, ride_id, bid


def _reports_of(reporter_id: int) -> list[Report]:
    with Session(engine) as s:
        return list(s.exec(select(Report).where(Report.reporter_id == reporter_id)).all())


# ---------- Жалобы: главное ----------

def test_три_тапа_по_жалобе_дают_одну_жалобу(client, user_factory):
    """Иначе один человек в одиночку собирает лестницу до автопаузы такси."""
    reporter = user_factory("TapReportA")
    target = user_factory("TapReportB")
    body = {"target_user_id": target["id"], "category": "rude", "reason": "грубость"}

    codes = [client.post("/reports", headers=reporter["auth"], json=body).status_code
             for _ in range(3)]
    assert all(c == 200 for c in codes), f"жалоба не создалась: {codes}"

    rows = _reports_of(reporter["id"])
    assert len(rows) == 1, (
        f"три тапа дали {len(rows)} жалобы. Порог автопаузы — {settings.quality_pause_reports} "
        "разобранных жалоб, то есть один человек ставит паузу другому в одиночку"
    )


def test_повтор_возвращает_ту_же_жалобу_а_не_ошибку(client, user_factory):
    """Ошибка на повтор выглядит как «не отправилось» — человек жмёт ещё раз. Возвращаем
    уже созданную: для него это «сработало»."""
    reporter = user_factory("TapSameA")
    target = user_factory("TapSameB")
    body = {"target_user_id": target["id"], "category": "rude", "reason": "грубость"}
    first = client.post("/reports", headers=reporter["auth"], json=body)
    second = client.post("/reports", headers=reporter["auth"], json=body)
    assert second.status_code == 200, f"повтор ответил ошибкой: {second.status_code} {second.text[:150]}"
    assert second.json()["id"] == first.json()["id"], "повтор создал новую жалобу"


def test_на_разные_поводы_пожаловаться_можно(client, user_factory):
    """Обратная сторона: дедуп не должен затыкать человека. Другая категория — другое событие."""
    reporter = user_factory("TapDiffCatA")
    target = user_factory("TapDiffCatB")
    for category in ("rude", "dangerous_driving"):
        r = client.post("/reports", headers=reporter["auth"], json={
            "target_user_id": target["id"], "category": category, "reason": "детали",
        })
        assert r.status_code == 200, f"жалоба категории {category} не прошла: {r.text[:150]}"
    assert len(_reports_of(reporter["id"])) == 2, "жалобы по разным поводам склеились в одну"


def test_разные_люди_жалуются_независимо(client, user_factory):
    """Дедуп по автору, а не по цели: три РАЗНЫХ человека — это три настоящих сигнала."""
    target = user_factory("TapManyTarget")
    for i in range(3):
        reporter = user_factory(f"TapManyReporter{i}")
        r = client.post("/reports", headers=reporter["auth"], json={
            "target_user_id": target["id"], "category": "rude", "reason": "грубость",
        })
        assert r.status_code == 200, f"жалоба {i + 1} не прошла: {r.text[:150]}"

    with Session(engine) as s:
        rows = list(s.exec(select(Report).where(Report.target_user_id == target["id"])).all())
    assert len(rows) == 3, f"жалобы разных людей склеились: {len(rows)} вместо 3"


def test_жалоба_по_другой_поездке_проходит(client, user_factory):
    """Один и тот же водитель нахамил дважды в разные дни — это два события, не одно."""
    reporter = user_factory("TapTripPax")
    driver = user_factory("TapTripDrv", role=UserRole.driver)
    ids = []
    for i in range(2):
        # Поездки разные: одинаковые схлопываются как двойной тап (2026-08-06), а тут нужны
        # два РАЗНЫХ дня — в этом и смысл теста «нахамил дважды».
        ride_id = _ride(client, driver, seats=3, comment=f"рейс {i}")
        bid = client.post("/bookings", headers=reporter["auth"],
                          json={"ride_id": ride_id, "seats": 1}).json()["id"]
        assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
        r = client.post("/reports", headers=reporter["auth"], json={
            "booking_id": bid, "category": "rude", "reason": "грубость",
        })
        assert r.status_code == 200, f"жалоба по поездке не прошла: {r.text[:150]}"
        ids.append(r.json()["id"])
    assert ids[0] != ids[1], "жалобы по РАЗНЫМ поездкам склеились в одну"


# ---------- Остальные двойные тапы ----------

def test_двойное_подтверждение_брони_не_съедает_место(client, user_factory):
    """Места считаются при бронировании; повторное подтверждение не должно списать ещё одно."""
    driver, pax, ride_id, bid = _confirmed(client, user_factory, "TapConfirm")
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    with Session(engine) as s:
        after_first = s.get(Ride, ride_id).seats_left
    assert client.post(f"/bookings/{bid}/confirm", headers=driver["auth"]).status_code == 200
    with Session(engine) as s:
        after_second = s.get(Ride, ride_id).seats_left
    assert after_first == after_second, (
        f"повторное подтверждение списало ещё место: было {after_first}, стало {after_second}"
    )


def test_двойная_блокировка_не_плодит_записи(client, user_factory):
    blocker = user_factory("TapBlockA")
    blocked = user_factory("TapBlockB")
    body = {"blocked_user_id": blocked["id"]}
    assert client.post("/blocks", headers=blocker["auth"], json=body).status_code in (200, 201)
    assert client.post("/blocks", headers=blocker["auth"], json=body).status_code in (200, 201)
    with Session(engine) as s:
        rows = list(s.exec(select(Block).where(
            Block.user_id == blocker["id"], Block.blocked_user_id == blocked["id"])).all())
    assert len(rows) == 1, f"двойной тап создал {len(rows)} блокировки"


def test_двойная_бронь_одной_поездки_не_съедает_два_места(client, user_factory):
    driver = user_factory("TapBookDrv", role=UserRole.driver)
    ride_id = _ride(client, driver, seats=3)
    pax = user_factory("TapBookPax")
    body = {"ride_id": ride_id, "seats": 1}
    assert client.post("/bookings", headers=pax["auth"], json=body).status_code == 200
    with Session(engine) as s:
        after_first = s.get(Ride, ride_id).seats_left
    client.post("/bookings", headers=pax["auth"], json=body)
    with Session(engine) as s:
        after_second = s.get(Ride, ride_id).seats_left
    assert after_first == after_second, (
        f"повторная бронь съела второе место: было {after_first}, стало {after_second}"
    )


def test_двойная_отправка_посылки_не_плодит_дубли(client, user_factory, monkeypatch):
    from app.models import ParcelDelivery
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    sender = user_factory("TapParcelSender")
    body = {
        "from_city": "Баймак", "to_city": "Сибай", "size": "small",
        "description": "гостинцы", "receiver_name": "Гөлнара",
        "receiver_phone": "+79990008881", "rules_accepted": True,
    }
    assert client.post("/parcels", headers=sender["auth"], json=body).status_code == 200
    client.post("/parcels", headers=sender["auth"], json=body)
    with Session(engine) as s:
        rows = list(s.exec(select(ParcelDelivery).where(
            ParcelDelivery.sender_id == sender["id"])).all())
    assert len(rows) == 1, f"двойной тап создал {len(rows)} посылки — курьер повезёт две"
