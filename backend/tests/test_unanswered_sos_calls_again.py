"""Непринятый SOS должен позвать ещё раз.

История. Ночь, трасса. Гульнара нажала красную кнопку. Близким ушли SMS, дежурному —
сообщение в Telegram. Дежурный спит и Telegram не читает. Дальше не происходило ничего:
сигнал оставался в списке со статусом «открыт» и молчал до утра (аудит 2026-08-08, волна 82).

Замысел довести дело до конца в проекте был: в настройках лежало «не принят за N минут →
повторный сигнал», у события — поле «когда ушёл повторный сигнал». Настройка была, поле было,
кода не было ни строчки. Со стороны выглядело так, будто эскалация работает, — а это хуже
честного отсутствия: на неё можно понадеяться.

Границы, которые нельзя перейти:
* сигнал, который дежурный УЖЕ принял, повторять нельзя — это шум поверх работы;
* свежий сигнал (минуту назад) не эскалируем: дежурный ещё читает;
* повтор ровно один — иначе каждый прогон воркера будет звонить снова.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import sos_escalate
from app.db import engine
from app.models import SosEvent
from app.timeutil import utcnow


@pytest.fixture
def caught_admin(monkeypatch):
    """Ловим повторные сигналы дежурному (Telegram + SMS)."""
    sent: list[str] = []
    monkeypatch.setattr(sos_escalate, "notify_admin_telegram", lambda text: sent.append(text))
    monkeypatch.setattr(sos_escalate, "send_text", lambda phone, text: sent.append(f"SMS:{phone}"))
    return sent


def _sos(user_id: int, minutes_ago: int, status: str = "open") -> int:
    with Session(engine) as s:
        e = SosEvent(user_id=user_id, category="medical", note="помогите",
                     status=status, created_at=utcnow() - timedelta(minutes=minutes_ago))
        s.add(e)
        s.commit()
        s.refresh(e)
        return e.id


def _escalate() -> list[int]:
    with Session(engine) as s:
        return sos_escalate.escalate_unhandled(s)


def _event(event_id: int) -> SosEvent:
    with Session(engine) as s:
        return s.exec(select(SosEvent).where(SosEvent.id == event_id)).one()


def test_сигнал_ждавший_дольше_порога_зовёт_снова(client, user_factory, caught_admin):
    gulnara = user_factory("НочьГульнара")
    event_id = _sos(gulnara["id"], minutes_ago=30)

    assert event_id in _escalate(), "непринятый сигнал так и остался без повтора"
    assert caught_admin, "повтор никому не ушёл"
    assert "НЕ ПРИНЯТ" in caught_admin[0], caught_admin[0]
    assert _event(event_id).escalated_at is not None, "повтор ушёл, а следа о нём не осталось"


def test_свежий_сигнал_не_дёргают(client, user_factory, caught_admin):
    """Дежурный ещё читает — повтор через минуту только мешает."""
    rinat = user_factory("СвежийРинат")
    event_id = _sos(rinat["id"], minutes_ago=1)

    assert event_id not in _escalate(), "повтор ушёл через минуту после сигнала"
    assert not caught_admin


def test_принятый_сигнал_не_повторяют(client, user_factory, caught_admin):
    """Дежурный уже работает по сигналу — шуметь поверх нельзя."""
    aigul = user_factory("ПринятаАйгуль")
    event_id = _sos(aigul["id"], minutes_ago=60, status="handled")

    assert event_id not in _escalate(), "повтор ушёл по сигналу, который уже приняли"
    assert not caught_admin


def test_повтор_ровно_один(client, user_factory, caught_admin):
    """Воркер крутится постоянно: без метки он звонил бы каждую минуту."""
    zuhra = user_factory("ПовторЗухра")
    event_id = _sos(zuhra["id"], minutes_ago=30)

    assert event_id in _escalate()
    caught_admin.clear()

    assert event_id not in _escalate(), "второй повтор по тому же сигналу"
    assert not caught_admin, f"дежурного зовут снова и снова: {caught_admin}"
