# -*- coding: utf-8 -*-
"""Сырой текст чужого исключения в логе может нести телефон (§8 CLAUDE.md, 152-ФЗ) — тот же
класс ошибки, что чинили в routers/safety.py (E1, независимое ревью leaf-3.1): SQLAlchemy
кладёт параметры запроса прямо в текст `IntegrityError`, а лог сервера читает не только
разработчик (диск, выгрузки, разбор инцидента).

`incident_escalate.escalate_silent_incidents` ловит сбой `push_notification` и раньше писал
`{e}` напрямую. push_notification умеет падать не только из-за БД (у её собственного try/except
на запись в БД нет защиты на втором шаге — отправку пуша/чтение User — то есть исключение
снаружи того try вполне реально), и его текст — чужой, непроверенный.

**Почему не `caplog` (самопроверка перед сдачей).** На полном прогоне (~5000 тестов) `caplog`
иногда не видел записи логгера "yuldash" — тест падал не на утверждении о телефоне, а на том,
что `текст_лога` оказывался пустым: какой-то ДРУГОЙ тест где-то раньше в прогоне меняет
глобальное состояние логирования (уровень/`disabled`/`logging.disable`), а `caplog.at_level`
не везде это перебивает. Виновника не искал (дорого и не моя зона) — вместо этого здесь свой
`logging.Handler` повешен ПРЯМО на логгер "yuldash", с явным уровнем и явным снятием в `finally`,
независимо от того, что сделали чужие тесты до этого."""
from __future__ import annotations

import logging
from datetime import timedelta

from sqlmodel import Session

from app import incident_escalate
from app.db import engine
from app.models import Incident, UserRole
from app.timeutil import utcnow

from test_api import _ride


ТЕЛЕФОН = "+79997776655"


class _СборщикЗаписей(logging.Handler):
    """Копит текст сообщений — не зависит от caplog/pytest, только от самого logging."""

    def __init__(self):
        super().__init__()
        self.тексты: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.тексты.append(record.getMessage())


def _поймать_лог_yuldash():
    """Контекст-менеджер: свой handler на логгере "yuldash", восстанавливает всё в finally.

    Три вещи, которые ДРУГОЙ тест мог оставить испорченными: уровень логгера, флаг
    `disabled`, и глобальный потолок `logging.Manager.disable` (`logging.disable(...)`
    перекрывает ЛЮБОЙ уровень ЛЮБОГО логгера, пока кто-то не позовёт `logging.disable(0)` —
    самый коварный вариант, так как он не виден через сам логгер). Все три сохраняются и
    возвращаются как было, чтобы этот тест сам не стал тем «виновником» для соседей."""
    import contextlib

    @contextlib.contextmanager
    def _cm():
        logger = logging.getLogger("yuldash")
        handler = _СборщикЗаписей()
        handler.setLevel(logging.DEBUG)
        старый_уровень = logger.level
        старый_disabled = logger.disabled
        старый_manager_disable = logging.root.manager.disable
        logger.setLevel(logging.DEBUG)
        logger.disabled = False
        logging.root.manager.disable = logging.NOTSET
        logger.addHandler(handler)
        try:
            yield handler.тексты
        finally:
            logger.removeHandler(handler)
            logger.setLevel(старый_уровень)
            logger.disabled = старый_disabled
            logging.root.manager.disable = старый_manager_disable

    return _cm()


def _молчащее_дело(client, user_factory) -> int:
    водитель = user_factory("ЛогВодитель", role=UserRole.driver)
    пассажирка = user_factory("ЛогПассажирка")
    ride_id = _ride(client, водитель, comment="проверка лога")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    дело = client.post("/incidents", headers=пассажирка["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "грубил",
    }).json()
    with Session(engine) as s:
        inc = s.get(Incident, дело["id"])
        inc.created_at = utcnow() - timedelta(days=incident_escalate.RESPONSE_DAYS + 1)
        s.add(inc)
        s.commit()
        return inc.id


def test_сбой_push_при_эскалации_не_пишет_телефон_в_лог(client, user_factory, monkeypatch):
    iid = _молчащее_дело(client, user_factory)

    def _падает(*args, **kwargs):
        raise RuntimeError(f"FCM отказал для абонента {ТЕЛЕФОН}")

    monkeypatch.setattr("app.services.push_notification", _падает)

    with _поймать_лог_yuldash() as тексты:
        with Session(engine) as s:
            incident_escalate.escalate_silent_incidents(s)

    текст_лога = "\n".join(тексты)
    assert тексты, "лог вообще ничего не поймал — тест ничего не проверил"
    assert ТЕЛЕФОН not in текст_лога, f"телефон попал в лог сервера: {текст_лога!r}"
    assert "push failed" in текст_лога, (
        "сам факт сбоя должен остаться виден — не ошибка должна исчезнуть, а только номер"
    )


def test_дело_всё_равно_переходит_на_разбор_даже_если_push_упал(client, user_factory, monkeypatch):
    """Уведомление вторично: сбой push не должен держать дело в «ждём объяснения» навсегда."""
    iid = _молчащее_дело(client, user_factory)
    monkeypatch.setattr("app.services.push_notification",
                        lambda *a, **kw: (_ for _ in ()).throw(RuntimeError("boom")))

    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)
        inc = s.get(Incident, iid)
        assert inc.status == "under_review", "сбой уведомления не должен хоронить переход дела"


def test_повторный_прогон_эскалации_не_дублирует_уведомления(client, user_factory, monkeypatch):
    """Идемпотентность запуска задачи (БРИФ §4а): робот может запуститься дважды (второй
    воркер, перезапуск после сбоя) — повторный прогон не должен ещё раз уведомлять по уже
    переведённому в разбор делу. Статус-фильтр `awaiting_response` в самом запросе — это и
    есть защита; без него второй прогон нашёл бы то же дело снова."""
    iid = _молчащее_дело(client, user_factory)
    уведомлений = []
    monkeypatch.setattr("app.services.push_notification",
                        lambda *a, **kw: уведомлений.append(1))

    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)
    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)   # второй прогон той же задачи

    assert len(уведомлений) == 2, (
        f"повторный прогон задачи прислал уведомления снова: {len(уведомлений)} вместо 2 "
        "(по одному на каждую сторону — но только с ПЕРВОГО прогона)"
    )
    with Session(engine) as s:
        inc = s.get(Incident, iid)
        assert inc.status == "under_review"
