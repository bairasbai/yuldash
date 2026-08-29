"""Волна 198: сбой в одной задаче воркера не отменяет остальные — в том числе тревогу.

Воркер такси крутится чаще всех и поэтому несёт на себе не только такси. В нём же живут
три вещи, от которых зависит человек, а не заказ:

  * непринятая красная кнопка SOS уходит выше (волна 82);
  * зимний протокол: человек молчит полчаса — зовём близких (волна 114);
  * жалоба, на которую обвинённый не ответил, идёт на разбор сама (волна 175).

В комментариях рядом с ними прямо написано, почему они здесь: «безопасность не должна
зависеть от того, открыт ли у человека экран». Но все двенадцать задач собирались ОДНИМ
выражением-словарём, а значит первое же исключение обрывало сборку — и до тревоги очередь
не доходила вовсе. Зависшая посылка молча выключала эскалацию SOS.

Порядок в словаре был такой, что шесть такси-задач стояли ПЕРЕД тремя про безопасность.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session

import app.services as svc
from app import taxi_worker as tw
from app.db import engine
from app.models import Booking, Ride, UserRole
from app.timeutil import utcnow

from test_api import _ride

# Задачи в порядке их вызова. Ломаем каждую по очереди — остальные обязаны отработать.
ЗАДАЧИ = [
    ("activate_due_scheduled", "scheduled_activated"),
    ("close_stuck_orders", "stuck_closed"),
    ("retry_waiting_orders", "waits_retried"),
    ("finish_expired_waits", "waits_finished"),
    ("advance_stale_offers", "offers_advanced"),
    ("close_stuck_parcels", "parcels_handled"),
]

# Ключи, которые ОБЯЗАНЫ появиться в сводке при любом сбое: три про безопасность
# и справедливость плюс те, что просто идут следом.
ОБЯЗАТЕЛЬНЫЕ = ("sos_escalated", "winter_escalated", "incidents_escalated",
                "ads_expired", "declares_reminded", "people_waiting")


def _ломаем(monkeypatch, имя: str) -> None:
    def взрыв(*a, **k):
        raise RuntimeError(f"{имя} упала")
    monkeypatch.setattr(tw, имя, взрыв)


@pytest.mark.parametrize("имя,ключ", ЗАДАЧИ)
def test_broken_taxi_task_does_not_stop_the_safety_ones(client, monkeypatch, имя, ключ):
    """Любая упавшая такси-задача не должна отменять эскалацию SOS и зимний протокол."""
    _ломаем(monkeypatch, имя)
    with Session(engine) as s:
        res = tw.run_once(s)

    for обязательный in ОБЯЗАТЕЛЬНЫЕ:
        assert обязательный in res, (
            f"упала «{имя}» — и {обязательный} не выполнилась вовсе"
        )
    assert ключ in res, "у упавшей задачи должен остаться пустой результат, а не пропасть ключ"


def test_broken_sos_escalation_does_not_stop_the_winter_protocol(client, monkeypatch):
    """И наоборот: сбой в самой тревоге не должен глушить зимний протокол, идущий следом."""
    monkeypatch.setattr(
        tw.sos_escalate, "escalate_unhandled",
        lambda *a, **k: (_ for _ in ()).throw(RuntimeError("sos упала")))
    with Session(engine) as s:
        res = tw.run_once(s)

    assert "winter_escalated" in res, "зимний протокол не выполнился из-за сбоя в SOS"
    assert "incidents_escalated" in res
    assert "people_waiting" in res


@pytest.fixture
def смс(monkeypatch):
    """SMS, которые уходят близким."""
    поймано: list = []
    monkeypatch.setattr(svc, "send_text", lambda phone, text: поймано.append((phone, text)))
    import app.routers.safety as safety
    monkeypatch.setattr(safety, "send_text", lambda phone, text: поймано.append((phone, text)))
    return поймано


def test_broken_parcel_task_does_not_stop_the_call_to_relatives(client, user_factory,
                                                                monkeypatch, смс):
    """Не «ключ в сводке есть», а человек реально получил помощь.

    Зухра едет ночью по трассе, поездка расшарена маме, на вопрос «доехала?» ответа нет
    сорок пять минут. В это же время у чужой посылки сломалась своя задача. Мама обязана
    получить SMS: одно к другому отношения не имеет.
    """
    водитель = user_factory("СбойВодитель", role=UserRole.driver)
    зухра = user_factory("СбойЗухра")
    мама = client.post("/trusted-contacts", headers=зухра["auth"],
                       json={"name": "Мама", "phone": "+79990000198"}).json()
    ride_id = _ride(client, водитель, comment="ночная трасса")
    bid = client.post("/bookings", headers=зухра["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    client.post(f"/bookings/{bid}/board", headers=водитель["auth"], json={"code": ""})
    assert client.post(f"/bookings/{bid}/share", headers=зухра["auth"],
                       json={"contact_id": мама["id"]}).status_code == 200
    with Session(engine) as s:                       # поездка уже выехала
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=3)
        s.add(r)
        s.commit()
    assert client.post(f"/bookings/{bid}/winter-check",
                       headers=зухра["auth"]).json()["state"] == "check_sent"
    with Session(engine) as s:                       # сорок пять минут молчания
        b = s.get(Booking, bid)
        b.winter_check_sent_at = utcnow() - timedelta(minutes=45)
        s.add(b)
        s.commit()

    смс.clear()
    _ломаем(monkeypatch, "close_stuck_parcels")      # у чужой посылки сломалась своя задача
    with Session(engine) as s:
        tw.run_once(s)

    assert any(тел == "+79990000198" for тел, _текст in смс), (
        "зависшая посылка выключила зов близких — мама не узнала, что дочь молчит на трассе"
    )


class _СессияСоСчётчиком:
    """Настоящая сессия, но помнит, сколько раз её откатывали.

    Нужна, потому что главного здесь SQLite не показывает: у Postgres на проде сорванный
    запрос оставляет транзакцию в состоянии «дальше только rollback», и КАЖДАЯ следующая
    задача падает на той же сессии — защита «одна задача не валит остальные» становится
    бумажной. SQLite восстанавливается сам, поэтому сценарием это не проверить, и мы
    проверяем сам договор: упала задача — откатили.
    """

    def __init__(self, настоящая):
        self._с = настоящая
        self.откатов = 0

    def rollback(self):
        self.откатов += 1
        return self._с.rollback()

    def __getattr__(self, имя):
        return getattr(self._с, имя)


def test_worker_rolls_back_after_a_failed_task(client, monkeypatch):
    """Договор: после упавшей задачи сессию откатывают, иначе на ней падают все следующие."""
    _ломаем(monkeypatch, "close_stuck_parcels")
    with Session(engine) as s:
        обёртка = _СессияСоСчётчиком(s)
        res = tw.run_once(обёртка)

    assert обёртка.откатов >= 1, (
        "задача упала, а сессию не откатили — на проде (Postgres) на ней падёт всё остальное"
    )
    assert "sos_escalated" in res


def test_worker_does_not_roll_back_when_all_is_well(client):
    """И наоборот: без сбоев откатывать нечего — лишний rollback потерял бы работу задач."""
    with Session(engine) as s:
        обёртка = _СессияСоСчётчиком(s)
        tw.run_once(обёртка, dry_run=True)

    assert обёртка.откатов == 0


def test_failed_database_call_does_not_poison_the_rest(client, monkeypatch):
    """Сбой в БАЗЕ, а не просто исключение: сессия остаётся в сорванной транзакции.

    Без отката следующая задача падает на той же сессии, потом следующая — и защита
    «одна задача не валит остальные» превращается в бумажную: ключи-то будут, а работы
    не сделает никто. Мутационный проход это и показал: убрал `session.rollback()` —
    и все тесты остались зелёными.
    """
    from sqlalchemy import text

    def сорвать_транзакцию(session, dry_run=False):
        session.exec(text("SELECT * FROM таблицы_такой_нет"))   # сорвёт транзакцию
        return []

    monkeypatch.setattr(tw, "close_stuck_parcels", сорвать_транзакцию)
    with Session(engine) as s:
        res = tw.run_once(s)

    # Задачи ПОСЛЕ сорванной должны отработать по-настоящему, а не упасть на той же сессии.
    assert res["parcels_handled"] == []
    for ключ in ОБЯЗАТЕЛЬНЫЕ:
        assert ключ in res, f"после сорванной транзакции {ключ} не выполнилась"
    # При сбое сюда ложится запасное `[]`; реальная работа возвращает сводку-словарь.
    assert res["people_waiting"] != [], "последняя задача упала на отравленной сессии"


def test_dry_run_does_not_call_relatives(client, user_factory, monkeypatch, смс):
    """Сухой прогон — это «посмотреть, что было бы». Он не имеет права звонить близким."""
    водитель = user_factory("СухойВодитель", role=UserRole.driver)
    зухра = user_factory("СухаяЗухра")
    мама = client.post("/trusted-contacts", headers=зухра["auth"],
                       json={"name": "Мама", "phone": "+79990000199"}).json()
    ride_id = _ride(client, водитель, comment="ночная трасса")
    bid = client.post("/bookings", headers=зухра["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    assert client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"]).status_code == 200
    client.post(f"/bookings/{bid}/board", headers=водитель["auth"], json={"code": ""})
    assert client.post(f"/bookings/{bid}/share", headers=зухра["auth"],
                       json={"contact_id": мама["id"]}).status_code == 200
    with Session(engine) as s:
        r = s.get(Ride, ride_id)
        r.depart_at = utcnow() - timedelta(hours=3)
        s.add(r)
        s.commit()
    assert client.post(f"/bookings/{bid}/winter-check",
                       headers=зухра["auth"]).json()["state"] == "check_sent"
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.winter_check_sent_at = utcnow() - timedelta(minutes=45)
        s.add(b)
        s.commit()

    смс.clear()
    with Session(engine) as s:
        res = tw.run_once(s, dry_run=True)

    assert res["winter_escalated"] == 0
    assert not any(тел == "+79990000199" for тел, _т in смс), (
        "сухой прогон разбудил маму среди ночи — а он ничего менять не должен"
    )


def test_dry_run_does_not_close_a_stuck_order(client, user_factory):
    """И заказ он тоже не трогает: у сухого прогона одна работа — показать."""
    from app.models import InstantOrder, InstantOrderStatus as S

    drv = user_factory("СухойЗависший", role=UserRole.driver)
    pax = user_factory("СухойПассажир")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664,
                         status=S.onboard, price_estimate=200,
                         created_at=utcnow() - timedelta(days=2),
                         accepted_at=utcnow() - timedelta(days=2))
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        res = tw.run_once(s, dry_run=True)

    assert oid in res["stuck_closed"], "сухой прогон должен ПОКАЗАТЬ зависший заказ"
    with Session(engine) as s:
        assert s.get(InstantOrder, oid).status == S.onboard, (
            "сухой прогон закрыл заказ — а он только смотрит"
        )


def test_summary_keeps_every_task_key(client):
    """Сводка называет все задачи — по ней в логе видно, что прогон был полным."""
    with Session(engine) as s:
        res = tw.run_once(s, dry_run=True)

    ожидаемые = {ключ for _имя, ключ in ЗАДАЧИ} | set(ОБЯЗАТЕЛЬНЫЕ)
    assert ожидаемые <= set(res), f"в сводке нет: {ожидаемые - set(res)}"
