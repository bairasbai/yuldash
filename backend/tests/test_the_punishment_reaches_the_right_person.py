"""Наказание доставалось не тому человеку — двумя разными путями (волна 158).

**Конкурент выключал соседу такси в два тапа.** Тяжёлая жалоба («опасное вождение»,
«домогательство») снимает водителя с линии сразу, до разбора у админа — и правильно, ждать
сутки в таких случаях нельзя. Доказательством того, что люди реально встречались, служила
привязка к поездке: есть номер брони — значит ехали вместе.

Номер брони получает кто угодно в один тап. Кнопка «Забронировать» открыта всем, водителя никто
не спрашивает. Проба: конкурент из соседнего села забронировал чужую поездку, назначенную на
2030 год, тут же пожаловался на опасное вождение и отменил бронь — водитель снят с линии
**до 2036 года**, до ручного разбора. Ехать никуда не надо, десять водителей за час.

Теперь автоматика спрашивает не «есть ли номер», а «сделала ли другая сторона встречный шаг»:
водитель подтвердил бронь, принял заказ такси, курьер взял посылку. Жаловаться это не мешает —
жалоба на постороннего по-прежнему уходит живому человеку в админку.

**Оправданного отключали на 30 дней.** Разбор постановил: жалоба не подтвердилась, наговорил
заявитель. И тем же решением обвинённый — тот, кого только что оправдали — получал паузу на
месяц, а его завтрашние рейсы снимались с извинениями пассажирам. В коде стоял прямой запрет
ровно на этот случай, но он смотрел только на галочку «страйк» и на слово решения, а поле
«дней паузы» — не смотрел. Хватало непочищенного числа в форме админки.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import (Booking, BookingStatus, DriverProfile, InstantOrder, InstantOrderStatus,
                        SafetyProfile, UserRole)
from app.safety_logic import trip_really_happened
from app.timeutil import utcnow

from test_api import _ride


def _водитель_с_профилем(user_factory, имя: str):
    водитель = user_factory(имя, role=UserRole.driver)
    with Session(engine) as s:
        if s.exec(select(DriverProfile).where(
                DriverProfile.user_id == водитель["id"])).first() is None:
            s.add(DriverProfile(user_id=водитель["id"], car_model="Лада", car_number="А123БВ102"))
            s.commit()
    return водитель


def _пауза_такси(uid: int):
    with Session(engine) as s:
        p = s.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first()
        return p.taxi_paused_until if p else None


def test_посторонний_не_снимет_водителя_с_линии(client, user_factory):
    """Главное: тапом «Забронировать» нельзя лишить человека работы."""
    водитель = _водитель_с_профилем(user_factory, "ЖертваКонкурента")
    ride_id = _ride(client, водитель, comment="Баймак — Сибай")
    конкурент = user_factory("Конкурент")

    bid = client.post("/bookings", headers=конкурент["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    жалоба = client.post("/reports", headers=конкурент["auth"], json={
        "booking_id": bid, "category": "dangerous_driving", "reason": "гонял"})
    client.post(f"/bookings/{bid}/cancel", headers=конкурент["auth"],
                json={"reason": "plans_changed"})

    assert жалоба.status_code == 200, "жаловаться на постороннего по-прежнему можно"
    assert _пауза_такси(водитель["id"]) is None, (
        f"водителя сняли с линии до {_пауза_такси(водитель['id'])} по жалобе человека, который "
        "только нажал «Забронировать» и отменил: они нигде не встречались, а заработок потерян"
    )


def test_настоящий_пассажир_снимает_нарушителя(client, user_factory):
    """Обратная сторона: тот, кого водитель вёз, должен быть услышан немедленно."""
    водитель = _водитель_с_профилем(user_factory, "НастоящийНарушитель")
    пассажирка = user_factory("НастоящаяПассажирка")
    ride_id = _ride(client, водитель, comment="ночью по трассе")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])

    client.post("/reports", headers=пассажирка["auth"], json={
        "booking_id": bid, "category": "dangerous_driving", "reason": "летел 140 по гололёду"})

    assert _пауза_такси(водитель["id"]) is not None, (
        "пассажирка, которую этот водитель реально вёз, пожаловалась на опасное вождение — "
        "а он остался на линии до ручного разбора"
    )


def test_встреча_засчитывается_по_встречному_шагу(client, user_factory):
    """Шов один на три режима: подтвердил бронь / принял заказ / взял посылку."""
    водитель = _водитель_с_профилем(user_factory, "ШовВодитель")
    пассажир = user_factory("ШовПассажир")
    ride_id = _ride(client, водитель, comment="шов")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]

    with Session(engine) as s:
        до = trip_really_happened(s, booking_id=bid)
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        после = trip_really_happened(s, booking_id=bid)

    assert до is False, "одностороннего тапа хватило, чтобы считаться попутчиком"
    assert после is True, "водитель принял бронь, а встреча не засчиталась"


def test_отменённая_после_подтверждения_остаётся_встречей(client, user_factory):
    """Водитель не приехал — пассажирка отменила. Пожаловаться она обязана мочь."""
    водитель = _водитель_с_профилем(user_factory, "НеПриехал")
    пассажирка = user_factory("ЖдалаНапрасно")
    ride_id = _ride(client, водитель, comment="не приехал")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    client.post(f"/bookings/{bid}/cancel", headers=пассажирка["auth"],
                json={"reason": "driver_no_response"})

    with Session(engine) as s:
        assert trip_really_happened(s, booking_id=bid) is True, (
            "договорённость была и сорвалась по вине водителя, а пожаловаться на него "
            "по-настоящему уже нельзя"
        )


def test_заказ_такси_без_водителя_не_встреча(client, user_factory):
    """Заказ, который ещё никто не принял, никого ни с кем не связывает."""
    пассажир = user_factory("ЗаказБезВодителя")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], status=InstantOrderStatus.searching,
                         price_estimate=200)
        s.add(o)
        s.commit()
        s.refresh(o)
        assert trip_really_happened(s, order_id=o.id) is False
        o.driver_id = пассажир["id"] + 1000000        # кто-то принял (id неважен)
        assert trip_really_happened(s, order_id=o.id) is True


@pytest.fixture
def завершённая_поездка(client, user_factory):
    водитель = _водитель_с_профилем(user_factory, "ОправданныйВодитель")
    пассажир = user_factory("ЗаявительКлеветник")
    ride_id = _ride(client, водитель, comment="была поездка")
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
    return водитель, пассажир, bid


def _пауза_справедливости(uid: int):
    with Session(engine) as s:
        p = s.exec(select(SafetyProfile).where(SafetyProfile.user_id == uid)).first()
        return p.suspended_until if p else None


def test_оправданного_не_отключают(client, user_factory, завершённая_поездка):
    """Главное: «наговорил заявитель» не должно стоить работы тому, кого оправдали."""
    водитель, пассажир, bid = завершённая_поездка
    админ = user_factory("РазборАдмин", role=UserRole.admin)
    iid = client.post("/incidents", headers=пассажир["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "наговор на ровном месте"}).json()["id"]

    решение = client.post(f"/admin/incidents/{iid}/resolve", headers=админ["auth"], json={
        "resolution": "dismissed", "fault": "reporter",
        "note": "жалоба не подтвердилась", "suspend_days": 30})

    assert решение.status_code == 422, (
        f"разбор оправдал водителя и тем же решением отключил его (ответ {решение.status_code}): "
        "месяц без заработка за чужую ложь"
    )
    assert _пауза_справедливости(водитель["id"]) is None, "оправданный всё-таки отключён"


def test_оправдание_без_наказания_проходит(client, user_factory, завершённая_поездка):
    """Обратная сторона: закрыть спор словами «не подтвердилось» админ обязан мочь."""
    водитель, пассажир, bid = завершённая_поездка
    админ = user_factory("РазборАдмин2", role=UserRole.admin)
    iid = client.post("/incidents", headers=пассажир["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "показалось"}).json()["id"]

    решение = client.post(f"/admin/incidents/{iid}/resolve", headers=админ["auth"], json={
        "resolution": "dismissed", "fault": "reporter", "note": "жалоба не подтвердилась"})

    assert решение.status_code == 200, f"обычное оправдание сломали: {решение.text[:200]}"


def test_виноватого_наказать_можно(client, user_factory, завершённая_поездка):
    """Обратная сторона: когда виноват обвинённый, пауза обязана работать."""
    водитель, пассажир, bid = завершённая_поездка
    админ = user_factory("РазборАдмин3", role=UserRole.admin)
    iid = client.post("/incidents", headers=пассажир["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "кричал и курил в салоне"}).json()["id"]

    решение = client.post(f"/admin/incidents/{iid}/resolve", headers=админ["auth"], json={
        "resolution": "suspend", "fault": "respondent", "suspend_days": 30, "note": "подтверждено"})

    assert решение.status_code == 200, f"наказать виноватого не вышло: {решение.text[:200]}"
    пауза = _пауза_справедливости(водитель["id"])
    assert пауза is not None and пауза > utcnow() + timedelta(days=29), (
        f"пауза виноватому не встала: {пауза}"
    )
