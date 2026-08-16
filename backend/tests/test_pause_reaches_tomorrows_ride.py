"""Пауза за опасное вождение должна доходить и до завтрашней поездки.

Разбор признал водителя опасным и поставил паузу на 30 дней. Ленту от него закрыли, автоматч
закрыли, новую бронь у него не оформить — всё это работало. А поездка, на которую женщина
записалась ВЧЕРА, оставалась в силе: рейс `active`, бронь `confirmed`, и ей не сказали ни слова
(аудит 2026-08-08, волна 137).

То есть завтра она садится в машину к человеку, которого сервис в этот самый момент признал
опасным, — и узнаёт об этом никогда. Наказание закрывало только «новое»; всё уже назначенное
проезжало мимо.

Теперь пауза снимает будущие рейсы отстранённого и его брони в чужих рейсах, а каждому задетому
человеку приходит запись в Центр уведомлений и пуш на двух языках.

**Где сознательно остановились.** Поездку, которая уже началась, не рвём: люди могут быть
в дороге, и высадить их посреди трассы опаснее, чем довезти. Бронь `onboard` — по той же
причине. И `cancelled_by` остаётся пустым: отменил не человек, а решение разбора. Запиши мы
туда id водителя — «Надёжность» посчитала бы это поздней отменой и наказала бы вторым
наказанием за тот же спор, а пассажирку — за чужую вину.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import (Booking, BookingStatus, Notification, Ride, RideStatus, SafetyProfile,
                        UserRole)
from app.timeutil import utcnow

from test_api import _ride


def _уведомлений(user_id: int) -> int:
    with Session(engine) as s:
        return len(s.exec(select(Notification).where(Notification.user_id == user_id)).all())


def _бронь(client, пассажир, ride_id: int, водитель) -> int:
    bid = client.post("/bookings", headers=пассажир["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    return bid


def _пауза_через_разбор(client, админ, жалобщик, водитель, bid: int, дней: int = 30) -> None:
    """Настоящий путь: жалоба → разбор админа → пауза. Не подкручиваем базу руками."""
    inc = client.post("/incidents", headers=жалобщик["auth"], json={
        "booking_id": bid, "type": "unsafe", "text": "гнал по встречке",
        "respondent_id": водитель["id"],
    })
    assert inc.status_code == 200, inc.text
    r = client.post(f"/admin/incidents/{inc.json()['id']}/resolve", headers=админ["auth"], json={
        "resolution": "suspend", "fault": "respondent", "suspend_days": дней,
        "note": "опасное вождение",
    })
    assert r.status_code == 200, r.text


@pytest.fixture
def разбор(client, user_factory):
    водитель = user_factory("ПаузаВодитель", role=UserRole.driver)
    жалобщик = user_factory("ПаузаЖалобщик")
    админ = user_factory("ПаузаАдмин", role=UserRole.admin)
    прошлый = _ride(client, водитель, comment="прошлая поездка")
    прошлый_bid = _бронь(client, жалобщик, прошлый, водитель)
    return водитель, жалобщик, админ, прошлый_bid


def test_завтрашняя_поездка_отменяется(client, user_factory, разбор):
    """Главное: женщина не должна завтра сесть к отстранённому за опасное вождение."""
    водитель, жалобщик, админ, прошлый_bid = разбор
    женщина = user_factory("ПаузаЖенщина")
    завтра_ride = _ride(client, водитель, comment="завтра утром")
    завтра_bid = _бронь(client, женщина, завтра_ride, водитель)

    _пауза_через_разбор(client, админ, жалобщик, водитель, прошлый_bid)

    with Session(engine) as s:
        ride = s.get(Ride, завтра_ride)
        bk = s.get(Booking, завтра_bid)
    assert ride.status == RideStatus.cancelled, (
        f"рейс отстранённого водителя остался {ride.status!r}: завтра к нему сядут люди, "
        "хотя разбор только что признал его опасным"
    )
    assert bk.status == BookingStatus.cancelled, f"бронь осталась {bk.status!r}"


def test_женщине_говорят_а_не_молчат(client, user_factory, разбор):
    """Отменить молча — почти так же плохо: она придёт к остановке и не поймёт, что случилось."""
    водитель, жалобщик, админ, прошлый_bid = разбор
    женщина = user_factory("ПаузаЖенщина2")
    завтра_ride = _ride(client, водитель, comment="завтра утром")
    _бронь(client, женщина, завтра_ride, водитель)
    было = _уведомлений(женщина["id"])

    _пауза_через_разбор(client, админ, жалобщик, водитель, прошлый_bid)

    стало = _уведомлений(женщина["id"])
    assert стало > было, (
        "поездку отменили, а женщине не сказали ничего: она узнает об этом у обочины"
    )
    with Session(engine) as s:
        свежее = s.exec(select(Notification).where(
            Notification.user_id == женщина["id"]).order_by(Notification.id.desc())).first()
    assert свежее.title_ba, "нет башкирского текста — половина района читает не по-русски"
    assert свежее.body_ba, "нет башкирского текста уведомления"


def test_отмена_не_наказывает_ни_водителя_ни_пассажирку(client, user_factory, разбор):
    """«Надёжность» не должна увидеть тут поздней отмены — отменил разбор, а не человек.

    Иначе водитель получил бы второе наказание за тот же спор, а женщина — за чужую вину.
    """
    водитель, жалобщик, админ, прошлый_bid = разбор
    женщина = user_factory("ПаузаЖенщина3")
    завтра_ride = _ride(client, водитель, comment="завтра утром")
    завтра_bid = _бронь(client, женщина, завтра_ride, водитель)

    _пауза_через_разбор(client, админ, жалобщик, водитель, прошлый_bid)

    with Session(engine) as s:
        bk = s.get(Booking, завтра_bid)
    assert bk.cancelled_by is None, (
        f"в отмене записан человек (id={bk.cancelled_by}): «Надёжность» посчитает это поздней "
        "отменой и накажет за то, чего он не делал"
    )
    assert bk.cancel_reason == "safety", f"причина отмены {bk.cancel_reason!r} вместо «safety»"


def test_идущую_поездку_не_рвут(client, user_factory, разбор):
    """Обратная сторона: высадить людей посреди трассы опаснее, чем довезти.

    Время выезда уже прошло — значит машина в пути. Пауза начинается со следующего рейса.
    """
    водитель, жалобщик, админ, прошлый_bid = разбор
    попутчик = user_factory("ПаузаПопутчик")
    в_пути = _ride(client, водитель, comment="уже едем")
    в_пути_bid = _бронь(client, попутчик, в_пути, водитель)
    with Session(engine) as s:                       # выехали полчаса назад
        ride = s.get(Ride, в_пути)
        ride.depart_at = utcnow() - timedelta(minutes=30)
        s.add(ride)
        s.commit()

    _пауза_через_разбор(client, админ, жалобщик, водитель, прошлый_bid)

    with Session(engine) as s:
        ride = s.get(Ride, в_пути)
        bk = s.get(Booking, в_пути_bid)
    assert ride.status == RideStatus.active, (
        "рейс, который уже в пути, отменили: люди остались на трассе, а это опаснее того, "
        "от чего мы их защищаем"
    )
    assert bk.status == BookingStatus.confirmed, "бронь в идущей поездке снесли"


def test_пассажир_на_паузе_освобождает_место(client, user_factory):
    """Вторая сторона: отстранённый пассажир не должен занимать место в чужом рейсе.

    Иначе «пауза» значила бы только «нельзя забронировать новое», а водитель ждал бы
    отстранённого на остановке и уехал полупустым.
    """
    водитель = user_factory("ПаузаВодитель2", role=UserRole.driver)
    грубиян = user_factory("ПаузаГрубиян")
    админ = user_factory("ПаузаАдмин2", role=UserRole.admin)
    прошлый = _ride(client, водитель, comment="где нагрубил")
    прошлый_bid = _бронь(client, грубиян, прошлый, водитель)

    завтра_ride = _ride(client, водитель, comment="завтра")
    завтра_bid = _бронь(client, грубиян, завтра_ride, водитель)
    было = _уведомлений(водитель["id"])

    inc = client.post("/incidents", headers=водитель["auth"], json={
        "booking_id": прошлый_bid, "type": "rude", "text": "хамил всю дорогу",
        "respondent_id": грубиян["id"],
    })
    client.post(f"/admin/incidents/{inc.json()['id']}/resolve", headers=админ["auth"], json={
        "resolution": "suspend", "fault": "respondent", "suspend_days": 7, "note": "грубость",
    })

    with Session(engine) as s:
        bk = s.get(Booking, завтра_bid)
        rd = s.get(Ride, завтра_ride)
    assert bk.status == BookingStatus.cancelled, (
        f"место отстранённого осталось занятым ({bk.status!r}): водитель будет ждать его зря"
    )
    assert rd.status == RideStatus.active, "чужой рейс отменять было не за что — водитель ни при чём"
    assert _уведомлений(водитель["id"]) > было, "водителю не сказали, что место освободилось"


def test_замечание_без_паузы_ничего_не_отменяет(client, user_factory, разбор):
    """Обратная мутация: не всякий разбор — пауза.

    Устное замечание не должно сносить людям поездки. Если снесёт — цена ошибки админа
    вырастет с «поговорили» до «сорвал рейс полудюжине человек».
    """
    водитель, жалобщик, админ, прошлый_bid = разбор
    попутчик = user_factory("ПаузаПопутчик2")
    завтра_ride = _ride(client, водитель, comment="завтра")
    завтра_bid = _бронь(client, попутчик, завтра_ride, водитель)

    inc = client.post("/incidents", headers=жалобщик["auth"], json={
        "booking_id": прошлый_bid, "type": "rude", "text": "резко ответил",
        "respondent_id": водитель["id"],
    })
    client.post(f"/admin/incidents/{inc.json()['id']}/resolve", headers=админ["auth"], json={
        "resolution": "warning", "fault": "respondent", "note": "устное замечание",
    })

    with Session(engine) as s:
        assert s.get(Ride, завтра_ride).status == RideStatus.active, (
            "устное замечание отменило поездку — наказание не по размеру вины"
        )
        assert s.get(Booking, завтра_bid).status == BookingStatus.confirmed


def test_чужие_поездки_не_задеты(client, user_factory, разбор):
    """Обратная мутация: отмена бьёт точно по отстранённому, а не по всем подряд."""
    водитель, жалобщик, админ, прошлый_bid = разбор
    другой_водитель = user_factory("ПаузаЧужой", role=UserRole.driver)
    пассажир = user_factory("ПаузаЧужойПассажир")
    чужой_ride = _ride(client, другой_водитель, comment="чужая поездка")
    чужой_bid = _бронь(client, пассажир, чужой_ride, другой_водитель)

    _пауза_через_разбор(client, админ, жалобщик, водитель, прошлый_bid)

    with Session(engine) as s:
        assert s.get(Ride, чужой_ride).status == RideStatus.active, (
            "под отмену попал рейс постороннего водителя"
        )
        assert s.get(Booking, чужой_bid).status == BookingStatus.confirmed


def test_снятие_паузы_апелляцией_не_ломается(client, user_factory, разбор):
    """Человек оспорил и выиграл — пауза снимается, и разбор не падает по дороге.

    Вернуть уже отменённые поездки нельзя (места могли уйти другим), но и уронить отмену
    решения нельзя тем более: иначе оправданный останется наказанным из-за ошибки в коде.
    """
    водитель, жалобщик, админ, прошлый_bid = разбор
    inc = client.post("/incidents", headers=жалобщик["auth"], json={
        "booking_id": прошлый_bid, "type": "unsafe", "text": "гнал",
        "respondent_id": водитель["id"],
    }).json()["id"]
    client.post(f"/admin/incidents/{inc}/resolve", headers=админ["auth"], json={
        "resolution": "suspend", "fault": "respondent", "suspend_days": 30, "note": "пауза",
    })

    # Настоящий путь пересмотра: человек подаёт апелляцию, админ решает заново.
    ап = client.post(f"/incidents/{inc}/appeal", headers=водитель["auth"],
                     json={"text": "не было такого, есть видео с регистратора"})
    assert ап.status_code == 200, ап.text
    снятие = client.post(f"/admin/incidents/{inc}/resolve", headers=админ["auth"], json={
        "resolution": "dismissed", "fault": "none", "note": "разобрались, водитель не виноват",
    })

    assert снятие.status_code == 200, снятие.text
    with Session(engine) as s:
        prof = s.exec(select(SafetyProfile).where(
            SafetyProfile.user_id == водитель["id"])).first()
    assert prof.suspended_until is None, "пауза не снялась после оправдания"
