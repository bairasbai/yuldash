"""Набор «первым водителям — 0% комиссии» пускал тех, кто опоздал на ночь (волна 155).

Обещание простое: кто пришёл до конца августа — три месяца возит без комиссии. Дата окончания
набора лежит в настройках обычной календарной датой, а вот дату одобрения сервер брал по
мировому времени. Уфа — это UTC+5, и с местной полуночи до пяти утра два календаря расходятся
на день: водитель, одобренный **1 сентября в два часа ночи**, по мировому счёту числился
августовским и получал 0% комиссии, которого не заслужил.

Пять часов каждые сутки — и каждый такой водитель три месяца возит бесплатно за счёт платформы.
Обратной ошибки не бывает: мировая дата никогда не опережает местную, то есть промо
систематически раздавалось опоздавшим и никогда не отнималось у своих.

То же самое было у курьера, слово в слово, — и вдобавок его промо жило лишние пять часов после
последнего дня.

Дыру пропустил сторож, поставленный на этот же класс волной 152: он искал знакомое написание
(мировой день ровно в одну строчку) и не видел ту же беду, записанную иначе. Сторож
переделан на признак, и у него появилась своя проверка.
"""
from __future__ import annotations

from datetime import datetime

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.debt import driver_fee_percent
from app.models import TaxiApplication, TaxiApplicationStatus, UserRole
from app.routers import courier as courier_mod

# 2026-09-01 02:00 по Уфе = 2026-08-31 21:00 UTC. По местному календарю — уже сентябрь.
СЕНТЯБРЬСКАЯ_НОЧЬ = datetime(2026, 8, 31, 21, 0)
# 2026-08-31 12:00 по Уфе = 07:00 UTC. Оба календаря согласны: август.
АВГУСТОВСКИЙ_ПОЛДЕНЬ = datetime(2026, 8, 31, 7, 0)


def _таксист(user_factory, метка: str, одобрен: datetime) -> dict:
    водитель = user_factory(метка, role=UserRole.driver)
    with Session(engine) as s:
        заявка = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == водитель["id"])).first()
        if заявка is None:
            заявка = TaxiApplication(user_id=водитель["id"])
        заявка.status = TaxiApplicationStatus.approved
        заявка.reviewed_at = одобрен
        заявка.created_at = одобрен
        s.add(заявка)
        s.commit()
    return водитель


@pytest.fixture
def набор_до_31_августа(monkeypatch):
    monkeypatch.setattr(settings, "launch_promo_until", "2026-08-31")
    monkeypatch.setattr(settings, "launch_promo_percent", 0.0)


def test_одобренный_в_сентябре_не_попадает_в_августовский_набор(
        client, user_factory, набор_до_31_августа):
    """Главное: набор кончился 31 августа — значит по календарю человека, а не сервера."""
    водитель = _таксист(user_factory, "НочнойНабор", СЕНТЯБРЬСКАЯ_НОЧЬ)

    with Session(engine) as s:
        ставка = driver_fee_percent(s, водитель["id"], СЕНТЯБРЬСКАЯ_НОЧЬ)

    assert ставка != 0.0, (
        "водителя, одобренного 1 сентября в два часа ночи, записали в августовский набор: "
        "три месяца без комиссии за счёт платформы, и так каждую ночь"
    )


def test_одобренный_в_августе_промо_получает(client, user_factory, набор_до_31_августа):
    """Обратная сторона: кто успел — тот успел, обещание должно работать."""
    водитель = _таксист(user_factory, "ДневнойНабор", АВГУСТОВСКИЙ_ПОЛДЕНЬ)

    with Session(engine) as s:
        ставка = driver_fee_percent(s, водитель["id"], АВГУСТОВСКИЙ_ПОЛДЕНЬ)

    assert ставка == 0.0, (
        f"водитель успел в набор, а комиссию ему поставили {ставка}%: обещание нарушено "
        "перед своим же первым водителем"
    )


def _курьер(user_factory, метка: str, одобрен: datetime) -> dict:
    человек = user_factory(метка)
    from app.models import CourierApplication
    with Session(engine) as s:
        заявка = s.exec(select(CourierApplication).where(
            CourierApplication.user_id == человек["id"])).first()
        if заявка is None:
            заявка = CourierApplication(user_id=человек["id"])
        заявка.status = "approved"
        заявка.reviewed_at = одобрен
        s.add(заявка)
        s.commit()
    return человек


def test_у_курьера_та_же_граница_набора(client, user_factory, monkeypatch):
    """Курьер написан по образцу такси — и дыра у него была та же, слово в слово."""
    monkeypatch.setattr(courier_mod, "COURIER_LAUNCH_PROMO_UNTIL", "2026-08-31")
    человек = _курьер(user_factory, "НочнойКурьер", СЕНТЯБРЬСКАЯ_НОЧЬ)

    with Session(engine) as s:
        в_промо = courier_mod._launch_promo_active(s, человек["id"], СЕНТЯБРЬСКАЯ_НОЧЬ)

    assert в_промо is False, (
        "курьера, одобренного 1 сентября ночью, записали в августовский набор"
    )


def test_курьер_из_набора_промо_получает(client, user_factory, monkeypatch):
    """Обратная сторона: тот, кто пришёл вовремя, возит без комиссии."""
    monkeypatch.setattr(courier_mod, "COURIER_LAUNCH_PROMO_UNTIL", "2026-08-31")
    человек = _курьер(user_factory, "ДневнойКурьер", АВГУСТОВСКИЙ_ПОЛДЕНЬ)

    with Session(engine) as s:
        в_промо = courier_mod._launch_promo_active(s, человек["id"], АВГУСТОВСКИЙ_ПОЛДЕНЬ)

    assert в_промо is True, "курьер успел в набор, а промо ему не дали"


def test_промо_курьера_гаснет_в_ту_же_ночь(client, user_factory, monkeypatch):
    """Окно «по 31 августа» закрывается в местную полночь, а не под утро следующего дня."""
    monkeypatch.setattr(courier_mod, "COURIER_LAUNCH_PROMO_UNTIL", "2026-08-31")
    человек = _курьер(user_factory, "ПоследнийКурьер", АВГУСТОВСКИЙ_ПОЛДЕНЬ)

    with Session(engine) as s:
        ещё_действует = courier_mod._launch_promo_active(s, человек["id"], СЕНТЯБРЬСКАЯ_НОЧЬ)

    assert ещё_действует is False, (
        "промо «по 31 августа» продолжало действовать 1 сентября в два часа ночи — "
        "лишние пять часов бесплатной работы за наш счёт"
    )
