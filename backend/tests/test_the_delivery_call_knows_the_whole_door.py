"""Дверь — это ВЕСЬ список проверок, а не одна функция гейта (волна 223).

Волна 222 донесла до рассылки «новая доставка рядом» допуск курьера — и на этом остановилась.
А приём заказа (`POST /parcels/{id}/accept`) проверяет ШЕСТЬ вещей, и `_guard_courier` — только
первая из них. Рассылка не знала ещё трёх:

  * **отдых** (`workday.guard_rested`) — восемь часов за рулём. Общий счётчик на оба режима
    (2026-08-29): усталость не спрашивает, человек в машине или коробка;
  * **живой заказ такси** (`_guard_no_live_taxi_order`) — водитель прямо сейчас везёт
    пассажира, который платит поминутно;
  * **просроченные документы такси** (`_guard_papers_not_expired`) — у таксиста кончилось
    ОСАГО, и на той же машине он не должен возить чужие вещи.

Все три отваливаются САМИ, посреди смены: часы набегают, заказ такси принимается, полис
истекает в местную полночь. Человек при этом остаётся «на линии».

Что происходило. Ринату приходит пуш «Новая доставка рядом 📦 Баймак → Сибай» ровно в тот
момент, когда он везёт пассажира в другую сторону. Он открывает «Курьер», жмёт взять —
409 «Сначала закончи поездку». Уставшему после восьми часов приходит то же приглашение,
и то же — таксисту с истёкшим ОСАГО.

Урок волны 221 был «копируй УСЛОВИЕ целиком». Волна 222 скопировала целиком одну функцию —
а дверью оказался весь список у неё на пороге.
"""
from __future__ import annotations

from datetime import date, timedelta

import pytest
from sqlmodel import Session, select

from app.config import settings
from app.db import engine
from app.models import (CourierApplication, CourierProfile, DriverProfile, InstantOrder,
                        InstantOrderStatus as S, ParcelDelivery, TaxiApplication,
                        TaxiApplicationStatus, TaxiWorkDay, UserRole)
from app.routers.parcels import _notify_couriers_new_parcel
from app.timeutil import utcnow
from app.workday import local_day


@pytest.fixture
def режим_курьера(monkeypatch):
    monkeypatch.setattr(settings, "courier_enabled", True, raising=False)
    # Зона и география — отдельное правило; здесь проверяем ТОЛЬКО состояние человека.
    monkeypatch.setattr("app.geo.zone_allows", lambda *a, **k: True)
    yield


@pytest.fixture
def кого_позвали(monkeypatch):
    """Кому реально ушёл пуш «новая доставка рядом»."""
    позвали: list = []
    monkeypatch.setattr("app.routers.parcels.push_notification",
                        lambda session, uid, *a, **k: позвали.append(uid))
    return позвали


def _курьер_на_линии(user_factory, метка):
    """Одобренный курьер, вышедший на линию, — исходная точка всех историй."""
    u = user_factory(метка, role=UserRole.passenger)
    with Session(engine) as s:
        s.add(CourierApplication(user_id=u["id"], status="approved",
                                 full_name="Курьер Курьеров", transport="car",
                                 rules_accepted=True))
        s.add(CourierProfile(user_id=u["id"], online=True, zone="region",
                             work_regions=True, work_intercity=True))
        s.commit()
    return u


def _отработал_смену(user_id: int, часов: int) -> None:
    """Счётчик рабочего времени за сегодня — тот же, что у такси (общий на оба режима)."""
    with Session(engine) as s:
        wd = TaxiWorkDay(driver_id=user_id, day=local_day(),
                         worked_seconds=часов * 3600,
                         limit_reached_at=utcnow())
        s.add(wd)
        s.commit()


def _везёт_пассажира(user_id: int, passenger_id: int) -> None:
    with Session(engine) as s:
        s.add(InstantOrder(passenger_id=passenger_id, driver_id=user_id, status=S.onboard,
                           from_lat=52.59, from_lng=58.31, to_lat=52.72, to_lng=58.66,
                           from_text="Баймак", to_text="Сибай", category="standard"))
        s.commit()


def _таксист_с_истёкшим_осаго(user_id: int) -> None:
    with Session(engine) as s:
        s.add(TaxiApplication(user_id=user_id, inn="123456789012", permit_number="Т-1",
                              birth_date=date(1990, 1, 1), license_since_year=2010,
                              status=TaxiApplicationStatus.approved,
                              osago_until=local_day() - timedelta(days=1)))
        s.add(DriverProfile(user_id=user_id, car_make="Lada", car_model="Granta"))
        s.commit()


def _разослать(sender_id: int) -> None:
    """Бабушке из Сибая нужно лекарство — заявка на курьерскую доставку."""
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=sender_id, from_city="Баймак", to_city="Сибай",
                           size="small", description="лекарство", receiver_name="Гөлнара",
                           receiver_phone="+79995550001", status="created",
                           delivery_type="courier", rules_accepted=True)
        s.add(p)
        s.commit()
        s.refresh(p)
        _notify_couriers_new_parcel(s, p)


def test_уставшего_курьера_на_доставку_не_зовём(user_factory, режим_курьера, кого_позвали):
    """Главное: усталость не спрашивает, человек в машине или коробка.

    Счётчик смены общий на оба режима (2026-08-29). Приём заказа его знает, рассылка — нет:
    отработавшему восемь часов приходило приглашение выйти на ту же ночную трассу.
    """
    отправитель = user_factory("УсталостьОтправитель", role=UserRole.passenger)
    бодрый = _курьер_на_линии(user_factory, "КурьерБодрый")
    уставший = _курьер_на_линии(user_factory, "КурьерУставший")
    _отработал_смену(уставший["id"], часов=9)

    _разослать(отправитель["id"])

    assert бодрый["id"] in кого_позвали, "отдохнувшего курьера рассылка не позвала"
    assert уставший["id"] not in кого_позвали, (
        "рассылка позвала на доставку человека, которому сама же запретила работать "
        "из-за усталости: взять заказ он не сможет, а ночная трасса та же самая"
    )


def test_кто_везёт_пассажира_на_доставку_не_зовём(user_factory, режим_курьера, кого_позвали):
    """Вторая проверка двери: машина, за которую пассажир платит поминутно, едет к нему."""
    отправитель = user_factory("ПассажирОтправитель", role=UserRole.passenger)
    пассажир = user_factory("ЕдетВМашине", role=UserRole.passenger)
    занятый = _курьер_на_линии(user_factory, "КурьерЗанятый")
    _везёт_пассажира(занятый["id"], пассажир["id"])

    _разослать(отправитель["id"])

    assert занятый["id"] not in кого_позвали, (
        "рассылка позвала на доставку водителя, который прямо сейчас везёт пассажира"
    )


def test_таксиста_с_истёкшим_осаго_на_доставку_не_зовём(user_factory, режим_курьера,
                                                        кого_позвали):
    """Третья проверка: полис у машины один, дорога одна.

    «Страховки нет, но коробки вози» не выдерживает ни здравого смысла, ни первого же ДТП
    с чужим грузом в багажнике.
    """
    отправитель = user_factory("ОсагоОтправитель", role=UserRole.passenger)
    без_полиса = _курьер_на_линии(user_factory, "КурьерБезПолиса")
    _таксист_с_истёкшим_осаго(без_полиса["id"])

    _разослать(отправитель["id"])

    assert без_полиса["id"] not in кого_позвали, (
        "рассылка позвала возить чужие вещи таксиста, у которого кончилось ОСАГО"
    )


def test_курьер_без_такси_остаётся_при_работе(user_factory, режим_курьера, кого_позвали):
    """Обратная сторона: граница ровно по тому, что мы знаем.

    Курьеру, который никогда не подавался в такси, ОСАГО никто не показывал — требовать его
    задним числом значило бы менять условия входа для уже принятых людей. Такое решение
    принимает Александр, а не гейт.
    """
    отправитель = user_factory("ЧистыйОтправитель", role=UserRole.passenger)
    только_курьер = _курьер_на_линии(user_factory, "КурьерБезТакси")

    _разослать(отправитель["id"])

    assert только_курьер["id"] in кого_позвали, (
        "курьера без заявки в такси отрезали от доставок из-за документов, "
        "которых он нам никогда не сдавал"
    )


# ------------------------------ договоры из разбора мутаций (волна 223) ------------------------------
# Каждый тест ниже родился из мутации, которую не поймал ни один существующий тест.


def test_курьера_с_долгом_на_доставку_не_зовём(user_factory, режим_курьера, кого_позвали):
    """Договор: комиссия — единственный доход платформы, и дверь про неё знает.

    Мутация «дверь забыла про долг по комиссии» прошла все тесты насквозь. У такси такая
    блокировка была с самого начала, у курьера её не было вообще: можно было возить месяцами
    и не заплатить ни рубля (аудит 2026-07-26). Раз проверка на двери есть — приглашение
    на работу за неё заходить не должно.
    """
    отправитель = user_factory("ДолгОтправитель", role=UserRole.passenger)
    должник = _курьер_на_линии(user_factory, "КурьерДолжник")
    with Session(engine) as s:
        s.add(ParcelDelivery(
            sender_id=отправитель["id"], courier_id=должник["id"], status="delivered",
            delivery_type="courier", from_city="Баймак", to_city="Сибай", size="small",
            description="прошлая доставка", receiver_name="Гөлнара",
            receiver_phone="+79995550002", rules_accepted=True,
            commission_kop=settings.courier_debt_block_threshold_kop, commission_paid=False,
        ))
        s.commit()

    _разослать(отправитель["id"])

    assert должник["id"] not in кого_позвали, (
        "рассылка позвала на новую доставку курьера, которому за долг по комиссии "
        "заказы уже закрыты"
    )


def test_режим_курьера_выключен_заказ_взять_нельзя(client, user_factory, monkeypatch):
    """Договор про мастер-флаг: выключенный режим закрывает приём заказа.

    Мутация «приём заказа перестал проверять мастер-флаг» прошла все тесты насквозь.
    Флаг — единственное, чем режим курьера выключается целиком; если его снять с двери,
    выключение перестаёт что-либо значить.

    Флаг сознательно НЕ входит в общий список проверок человека (`_guard_courier_can_take`):
    он про весь сервис, а не про конкретного человека. Поэтому у него отдельная строка —
    и отдельный сторож.
    """
    monkeypatch.setattr(settings, "courier_enabled", False, raising=False)
    отправитель = user_factory("ВыключенОтправитель", role=UserRole.passenger)
    курьер = _курьер_на_линии(user_factory, "КурьерПриВыключенном")
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], from_city="Баймак", to_city="Сибай",
                           size="small", description="лекарство", receiver_name="Гөлнара",
                           receiver_phone="+79995550003", status="created",
                           delivery_type="courier", rules_accepted=True,
                           created_at=utcnow())
        s.add(p)
        s.commit()
        s.refresh(p)
        parcel_id = p.id

    ответ = client.post(f"/parcels/{parcel_id}/accept", headers=курьер["auth"])

    assert ответ.status_code == 403, (
        f"режим курьера выключен, а заказ взять дали (ответ {ответ.status_code})"
    )
