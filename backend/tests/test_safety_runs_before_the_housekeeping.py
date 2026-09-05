"""Волна 199: в фоновом прогоне безопасность идёт ПЕРВОЙ, а не седьмой.

Волна 198 закрыла соседнюю дыру: сбой одной задачи больше не отменяет остальные. Но
изоляция спасает от ИСКЛЮЧЕНИЯ и ничем не помогает, когда процесс просто перестаёт жить.

Воркер запускается systemd раз в минуту как `Type=oneshot`, и своего `TimeoutStartSec`
у него нет — значит действует умолчание systemd (обычно 90 секунд). Шесть такси-задач
стоят первыми, каждая ходит в сеть (пуши через Firebase, SMS, Telegram) и разбирает
до пятисот строк. Затянулись — процесс убивают, и всё, что стояло ниже, не выполняется:
непринятая красная кнопка SOS, зимний протокол и разбор жалоб.

Причём убивают его так каждую минуту: пока прогон идёт, следующий запуск systemd
пропускает. То есть тревога может не уходить часами, и в логе не будет ни одной ошибки.

Правило простое: сначала то, от чего зависит ЧЕЛОВЕК, потом то, от чего зависит ЗАКАЗ.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session

from app import taxi_worker as tw
from app.db import engine

# Задачи, от которых зависит жизнь и справедливость человека.
ПРО_ЧЕЛОВЕКА = ("sos_escalated", "winter_escalated", "incidents_escalated")
# Хозяйственные задачи: заказы, посылки, офферы, реклама.
ПРО_ХОЗЯЙСТВО = ("scheduled_activated", "stuck_closed", "waits_retried",
                 "waits_finished", "offers_advanced", "parcels_handled", "ads_expired")


def _порядок() -> list:
    return [ключ for ключ, _задача, _пусто in tw.ЗАДАЧИ]


def test_safety_tasks_come_first():
    """Порядок в списке — это и есть приоритет: что успеет выполниться, если прогон оборвут."""
    порядок = _порядок()
    последняя_про_человека = max(порядок.index(k) for k in ПРО_ЧЕЛОВЕКА)
    первая_хозяйственная = min(порядок.index(k) for k in ПРО_ХОЗЯЙСТВО)

    assert последняя_про_человека < первая_хозяйственная, (
        "задачи безопасности стоят после хозяйственных: если прогон оборвут по таймауту, "
        f"тревога не уйдёт. Порядок сейчас: {порядок}"
    )


def test_every_task_is_classified():
    """Появилась новая задача — её обязаны отнести к людям или к хозяйству.
    Иначе следующий, кто добавит сюда что-то важное, тихо поставит это в конец."""
    известные = set(ПРО_ЧЕЛОВЕКА) | set(ПРО_ХОЗЯЙСТВО) | {"declares_reminded", "people_waiting"}
    assert set(_порядок()) <= известные, (
        f"новая задача не отнесена ни к одной группе: {set(_порядок()) - известные}"
    )


def test_killed_run_still_delivered_the_alarm(client, monkeypatch):
    """Модель убитого процесса: задача обрывает прогон так, что `except Exception` не ловит.

    Ровно так ведёт себя оборванный по таймауту прогон — код после точки обрыва не
    выполняется вовсе. Проверяем, что к этому моменту тревога УЖЕ отработала.
    """
    выполнено: list = []
    настоящие = {ключ: задача for ключ, задача, _п in tw.ЗАДАЧИ}

    def обёртка(ключ):
        def вызов(s, d):
            if ключ == "stuck_closed":
                raise KeyboardInterrupt("процесс убит по таймауту")
            выполнено.append(ключ)
            return настоящие[ключ](s, d)
        return вызов

    monkeypatch.setattr(tw, "ЗАДАЧИ",
                        tuple((к, обёртка(к), п) for к, _з, п in tw.ЗАДАЧИ))
    with Session(engine) as s:
        with pytest.raises(KeyboardInterrupt):
            tw.run_once(s, dry_run=True)

    for ключ in ПРО_ЧЕЛОВЕКА:
        assert ключ in выполнено, (
            f"прогон оборвали на хозяйственной задаче, а {ключ} до этого не выполнилась"
        )


# ==================== Вторая половина волны: напоминание об оценке ====================
# Тот же изъян я сам завёл волной 197: три сервиса в одном цикле без изоляции. Сбой
# на попутке молча отменял бы напоминания и по такси, и по доставке.

def _напоминалка_включена(monkeypatch):
    from app.config import settings
    monkeypatch.setattr(settings, "rate_reminder_enabled", True, raising=False)


def test_broken_pooling_service_does_not_stop_taxi_and_parcel_reminders(
        client, user_factory, monkeypatch):
    """Упал поиск броней — напоминания по такси и доставке обязаны уйти."""


    from app import rate_reminder as rr
    from app.models import InstantOrder, InstantOrderStatus as S, ParcelDelivery, UserRole
    from app.timeutil import utcnow

    _напоминалка_включена(monkeypatch)
    drv = user_factory("ИзоляцВодитель", role=UserRole.driver)
    pax = user_factory("ИзоляцПассажир")
    courier = user_factory("ИзоляцКурьер", role=UserRole.driver)
    with Session(engine) as s:
        s.add(InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                           from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664,
                           status=S.done, price_estimate=200, done_at=utcnow()))
        s.add(ParcelDelivery(sender_id=pax["id"], courier_id=courier["id"],
                             from_city="Баймак", to_city="Сибай", status="delivered"))
        s.commit()

    сломанная = tuple(
        сервис.__class__(**{**сервис.__dict__,
                            "найти": (lambda _s: (_ for _ in ()).throw(RuntimeError("брони упали")))})
        if сервис.ключ == "booking" else сервис
        for сервис in rr.СЕРВИСЫ
    )
    monkeypatch.setattr(rr, "СЕРВИСЫ", сломанная)
    monkeypatch.setattr(rr, "push_notification", lambda *a, **k: None)

    with Session(engine) as s:
        напомнили = rr.rate_reminder_once(s)

    кому = {uid for _ref, uid in напомнили}
    assert drv["id"] in кому and pax["id"] in кому, (
        f"сбой на попутке отменил напоминания по такси: {напомнили}"
    )
    assert courier["id"] in кому, "сбой на попутке отменил напоминания по доставке"


def test_rate_reminder_rolls_back_after_a_failed_service(client, monkeypatch):
    """Договор: сервис упал — сессию откатили, иначе следующий падёт на ней же."""
    from app import rate_reminder as rr

    _напоминалка_включена(monkeypatch)
    сломанная = tuple(
        сервис.__class__(**{**сервис.__dict__,
                            "найти": (lambda _s: (_ for _ in ()).throw(RuntimeError("упало")))})
        if сервис.ключ == "booking" else сервис
        for сервис in rr.СЕРВИСЫ
    )
    monkeypatch.setattr(rr, "СЕРВИСЫ", сломанная)
    with Session(engine) as s:
        обёртка = _СессияСоСчётчиком(s)
        rr.rate_reminder_once(обёртка)

    assert обёртка.откатов >= 1, (
        "сервис упал, а сессию не откатили — на Postgres остальные падут на ней же"
    )


class _СессияСоСчётчиком:
    """Настоящая сессия, но помнит, сколько раз её откатывали (см. волну 198)."""

    def __init__(self, настоящая):
        self._с = настоящая
        self.откатов = 0

    def rollback(self):
        self.откатов += 1
        return self._с.rollback()

    def __getattr__(self, имя):
        return getattr(self._с, имя)


def test_summary_still_has_every_task(client):
    """Перестановка не должна потерять ни одной задачи."""
    with Session(engine) as s:
        res = tw.run_once(s, dry_run=True)
    assert set(res) == set(_порядок())
