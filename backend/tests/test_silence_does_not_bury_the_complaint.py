"""Молчание обвинённого хоронило жалобу навсегда (волна 175).

Спор устроен так: человек жалуется, обвинённому уходит приглашение объясниться, дело ждёт
его версию — и только потом попадает к человеку-разбирающему.

Право на защиту тут правильное и сознательное: односторонний разбор в райцентре, где все знают
друг друга, ломает жизнь быстрее самой жалобы. Но у ожидания не было конца.

**Проба:** женщина пожаловалась на водителя — «вёл себя грубо, пугал». Тот просто не отвечает.
Через месяц дело всё ещё висит в «ждём объяснения»: админ не видит его в очереди разбора,
«Надёжность» водителя не меняется, заявительница ждёт решения, которого не будет.

Молчание оказалось лучшей стратегией для того, на кого пожаловались.

Теперь у ожидания есть срок: три дня. Не ответил — дело само уходит на разбор, с пометкой,
что версия второй стороны не поступила. Право на защиту не отнимается: объясниться можно
и на разборе, это прямо сказано в уведомлении. Просто молчание больше не останавливает дело.

Тот же класс, что волна 174: право человека не может зависеть от чужой кнопки. Там оно
запирало выход из сервиса, здесь — разбор жалобы.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import incident_escalate, taxi_worker
from app.db import engine
from app.models import Booking, BookingStatus, Incident, Notification, UserRole
from app.timeutil import utcnow

from test_api import _ride


@pytest.fixture
def жалоба(client, user_factory):
    """Женщина пожаловалась на водителя после поездки."""
    водитель = user_factory("ГрубыйВодитель", role=UserRole.driver)
    пассажирка = user_factory("ПожаловаласьПассажирка")
    ride_id = _ride(client, водитель, comment="ночью по трассе")
    bid = client.post("/bookings", headers=пассажирка["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
    дело = client.post("/incidents", headers=пассажирка["auth"], json={
        "respondent_id": водитель["id"], "type": "rude", "booking_id": bid,
        "description": "вёл себя грубо, пугал",
    }).json()
    return водитель, пассажирка, дело["id"]


def _состарить(iid: int, дней: int) -> None:
    with Session(engine) as s:
        inc = s.get(Incident, iid)
        inc.created_at = utcnow() - timedelta(days=дней)
        s.add(inc)
        s.commit()


def _статус(iid: int) -> str:
    with Session(engine) as s:
        return s.get(Incident, iid).status


def test_молчание_не_хоронит_жалобу(client, жалоба):
    """Главное: разбор начинается, даже если вторая сторона молчит."""
    _, _, iid = жалоба
    assert _статус(iid) == "awaiting_response", "дело должно ждать объяснения"
    _состарить(iid, incident_escalate.RESPONSE_DAYS + 1)

    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)

    assert _статус(iid) == "under_review", (
        "обвинённый молчит — и жалоба висит в ожидании навсегда: админ её не видит, "
        "«Надёжность» не меняется, а женщина ждёт разбора, которого не будет"
    )


def test_свежему_делу_дают_ответить(client, жалоба):
    """Обратная сторона: право на защиту — не формальность, человеку нужно время."""
    _, _, iid = жалоба
    _состарить(iid, 1)

    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)

    assert _статус(iid) == "awaiting_response", (
        "дело ушло на разбор через сутки: обвинённый мог быть в дороге или на вахте "
        "и просто не успел ответить"
    )


def test_обе_стороны_узнают_о_переходе(client, жалоба):
    """Заявитель должен знать, что его услышали; обвинённый — что высказаться ещё можно."""
    водитель, пассажирка, iid = жалоба
    _состарить(iid, incident_escalate.RESPONSE_DAYS + 1)

    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)

    with Session(engine) as s:
        письма = s.exec(select(Notification).where(
            Notification.ref_kind == "incident", Notification.ref_id == iid)).all()
    кому = {n.user_id for n in письма}
    assert водитель["id"] in кому and пассажирка["id"] in кому, (
        f"о начале разбора узнали не все: {кому}"
    )
    тексты = " ".join((n.body_ru or "") for n in письма)
    assert "объясниться" in тексты, (
        f"обвинённому не сказали, что высказаться ещё можно: {тексты[:200]}"
    )


def test_после_перехода_объясниться_ещё_можно(client, жалоба):
    """Смысл всей правки: молчание перестало останавливать дело, но защиту не отняло."""
    водитель, _, iid = жалоба
    _состарить(iid, incident_escalate.RESPONSE_DAYS + 1)
    with Session(engine) as s:
        incident_escalate.escalate_silent_incidents(s)

    ответ = client.post(f"/incidents/{iid}/respond", headers=водитель["auth"],
                        json={"text": "я не грубил, было недопонимание"})

    assert ответ.status_code == 200, (
        f"дело ушло на разбор — и обвинённый больше не может объясниться: {ответ.text[:150]}. "
        "Это уже не защита, а наказание за молчание"
    )


def test_разобранное_дело_робот_не_трогает(client, жалоба, user_factory):
    """Контроль: робот двигает только то, что действительно застряло."""
    _, _, iid = жалоба
    админ = user_factory("АдминРазбора175", role=UserRole.admin)
    client.post(f"/admin/incidents/{iid}/resolve", headers=админ["auth"],
                json={"resolution": "warning", "fault": "respondent"})
    _состарить(iid, 60)

    with Session(engine) as s:
        тронутые = incident_escalate.escalate_silent_incidents(s)

    assert iid not in тронутые, "робот трогает уже разобранное дело"


def test_ночной_робот_зовёт_эту_задачу(client, жалоба):
    """Задача бесполезна, если её никто не запускает — проверяем сам вызов."""
    _, _, iid = жалоба
    _состарить(iid, incident_escalate.RESPONSE_DAYS + 1)

    with Session(engine) as s:
        сводка = taxi_worker.run_once(s)

    assert "incidents_escalated" in сводка, (
        f"ночной робот не знает про молчащие жалобы: {sorted(сводка)}"
    )
    assert _статус(iid) == "under_review", "робот прогнался, а дело осталось в ожидании"
