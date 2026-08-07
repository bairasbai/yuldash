"""Жалобой на постороннего нельзя выключить водителю работу (аудит 2026-08-07).

Что было не так. Тяжёлая жалоба (угроза безопасности, высадил, опасная езда) сразу ставит
такси обвинённого на паузу «до разбора» — это правильно и сделано намеренно: пока админ
не посмотрел, человек за руль не выходит. Но жалобу можно было подать **на кого угодно**:
если в теле запроса не указана поездка, целью становится просто `target_user_id` — любой
номер пользователя, без всякой проверки, что жалующийся и обвинённый вообще пересекались.

Итог: любой вошедший одним запросом выключал такси любому водителю на `REVIEW_PAUSE_DAYS`
(3650 дней — то есть «навсегда, пока админ не разберёт вручную»). Для водителя это потеря
заработка, для конкурента — кнопка «убрать соседа с линии».

Замысел в проекте был правильный, он даже записан в самой модели: «привязка к поездке
(order_id/booking_id) доказывает, что стороны реально ехали вместе» (`models.py`, Report).
Не хватало только того, чтобы авто-пауза этой привязки ТРЕБОВАЛА.

Как починено. Пожаловаться на постороннего по-прежнему можно — это нужно (увидел опасную
езду, столкнулся вне заказа), и админ такую жалобу увидит. Но автоматическую паузу теперь
ставит только жалоба, привязанная к общей поездке, заказу или доставке: там участие
проверено на входе (`_report_counterparty`).
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile, InstantOrder, InstantOrderStatus as S, User, UserRole
from app.quality import PAUSE_REASON_REVIEW


def _profile(uid: int) -> DriverProfile | None:
    with Session(engine) as s:
        return s.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first()


def _ensure_profile(uid: int) -> None:
    with Session(engine) as s:
        if not s.exec(select(DriverProfile).where(DriverProfile.user_id == uid)).first():
            s.add(DriverProfile(user_id=uid, verified=True))
            s.commit()


def test_stranger_report_does_not_pause_the_driver(client, user_factory):
    """Главная дыра: жалоба «в никуда» выключала водителю такси."""
    attacker = user_factory("Недоброжелатель")
    driver = user_factory("Водитель", role=UserRole.driver)
    _ensure_profile(driver["id"])

    r = client.post("/reports", headers=attacker["auth"],
                    json={"target_user_id": driver["id"], "category": "dangerous_driving",
                          "reason": "просто так"})
    assert r.status_code == 200, r.text        # пожаловаться можно — админ увидит

    prof = _profile(driver["id"])
    assert prof.taxi_paused_until is None, (
        "такси водителя выключено жалобой человека, который с ним никогда не ехал"
    )


def test_report_tied_to_a_real_trip_still_pauses(client, user_factory):
    """Обратная сторона: настоящая жалоба пассажира по своей поездке паузу СТАВИТ —
    иначе починка сломала бы защиту, ради которой пауза и заводилась."""
    pax = user_factory("Пассажир")
    driver = user_factory("Водитель2", role=UserRole.driver)
    _ensure_profile(driver["id"])
    with Session(engine) as s:
        order = InstantOrder(passenger_id=pax["id"], driver_id=driver["id"], status=S.done,
                             price_estimate=300, price_final=300)
        s.add(order)
        s.commit()
        s.refresh(order)
        oid = order.id

    r = client.post("/reports", headers=pax["auth"],
                    json={"order_id": oid, "category": "dangerous_driving",
                          "reason": "гнал по трассе"})
    assert r.status_code == 200, r.text

    prof = _profile(driver["id"])
    assert prof.taxi_paused_until is not None, "настоящая жалоба перестала ставить паузу"
    assert prof.taxi_pause_reason == PAUSE_REASON_REVIEW


def test_stranger_report_is_still_recorded(client, user_factory):
    """Жалоба на постороннего не пропадает: она сохраняется и попадает к админу.
    Мы убрали автоматическое наказание, а не саму возможность пожаловаться."""
    from app.models import Report
    attacker = user_factory("Свидетель")
    driver = user_factory("Водитель3", role=UserRole.driver)
    _ensure_profile(driver["id"])

    r = client.post("/reports", headers=attacker["auth"],
                    json={"target_user_id": driver["id"], "category": "safety_threat",
                          "reason": "видел на дороге"})
    assert r.status_code == 200
    with Session(engine) as s:
        rep = s.get(Report, r.json()["id"])
        assert rep is not None and rep.target_user_id == driver["id"]
        assert rep.status == "new", "жалоба должна ждать разбора админом"
