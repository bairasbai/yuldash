"""Человек ждал ответа неделями и не знал, читают ли его вообще (волна 178).

Четыре очереди упираются в одного человека — Александра: заявка в таксисты, заявка в курьеры,
документы водителя, обращение в поддержку. Устроены они одинаково: при создании ему уходит
одно сообщение в телеграм, дальше тишина.

**Проба.** Человек написал в поддержку: «Забыл телефон в машине, ехал вчера из Сибая,
помогите связаться». Через неделю — ни одного письма ему, тикет всё так же открыт, никому
ничего не напомнили. Второй случай: собрал ИНН, разрешение, ОСАГО, подал заявку в таксисты —
через месяц ноль писем и статус «на рассмотрении» без слова о сроке.

Дело не в невнимательности: Александр один, четыре-пять дней в неделю в командировках,
на приложение у него пять-десять минут в день. Одно пропущенное сообщение в телеграме — и
человек ждёт вечно. Для того, кто потерял вещь или хочет выйти на работу, это выглядит
одинаково: сервису всё равно.

Теперь через двое суток ожидания человеку уходит одно письмо — «мы получили и помним»,
а Александру одна сводка: что где висит и сколько ждёт самое старое дело. Точный срок
не обещаем: обещание, которое не выполнят, хуже честного «мы тебя видим».
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import taxi_worker, waiting_on_us
from app.db import engine
from app.models import (
    Notification, SupportMessage, SupportSender, SupportTicket, TaxiApplication, UserRole,
)
from app.timeutil import utcnow

ЗАЯВКА = {
    "inn": "025500011122", "permit_number": "АА-778899",
    "birth_date": "1991-03-14", "license_since_year": 2013,
    "car_make": "Lada", "car_model": "Largus", "car_plate": "Е777КХ102",
}


def _письма(user_id: int) -> list[Notification]:
    with Session(engine) as s:
        return list(s.exec(select(Notification).where(
            Notification.user_id == user_id)).all())


def _состарить_тикет(user_id: int, дней: float) -> None:
    момент = utcnow() - timedelta(days=дней)
    with Session(engine) as s:
        for t in s.exec(select(SupportTicket).where(
                SupportTicket.user_id == user_id)).all():
            t.created_at = момент
            t.updated_at = момент
            s.add(t)
        s.commit()


def _состарить_заявку(user_id: int, дней: float) -> None:
    with Session(engine) as s:
        for a in s.exec(select(TaxiApplication).where(
                TaxiApplication.user_id == user_id)).all():
            a.created_at = utcnow() - timedelta(days=дней)
            s.add(a)
        s.commit()


@pytest.fixture
def потерял_телефон(client, user_factory):
    """Человек написал в поддержку и ждёт."""
    человек = user_factory("АйгульПотерялаТелефон")
    ответ = client.post("/support/tickets", headers=человек["auth"], json={
        "subject": "Забыл телефон в машине",
        "body": "Ехала вчера из Сибая, оставила телефон на заднем сиденье. Помогите связаться.",
    })
    assert ответ.status_code == 200, ответ.text[:200]
    return человек


def test_ждущему_говорят_что_его_видят(client, потерял_телефон):
    """Главное: молчание страшнее задержки — человек должен знать, что его читают."""
    _состарить_тикет(потерял_телефон["id"], 3)

    with Session(engine) as s:
        waiting_on_us.remind_waiting_people(s)

    письма = _письма(потерял_телефон["id"])
    assert письма, (
        "человек написал «забыл телефон в машине» и неделю не слышит ничего: "
        "ни ответа, ни «мы получили» — он решит, что его не читают"
    )
    тексты = " ".join((n.body_ru or "") for n in письма)
    assert "не забыли" in тексты, f"письмо есть, но оно не про то: {тексты[:200]}"


def test_свежее_обращение_не_дёргают(client, потерял_телефон):
    """Обратная сторона: написал час назад — Александр и так ответит вечером."""
    with Session(engine) as s:
        waiting_on_us.remind_waiting_people(s)

    assert not _письма(потерял_телефон["id"]), (
        "человеку написали «мы помним» через час после обращения — это шум, а не забота"
    )


def test_письмо_приходит_один_раз(client, потерял_телефон):
    """Каждую ночь одно и то же — спам, который перестают читать."""
    _состарить_тикет(потерял_телефон["id"], 3)

    with Session(engine) as s:
        waiting_on_us.remind_waiting_people(s)
        сначала = len(_письма(потерял_телефон["id"]))
        waiting_on_us.remind_waiting_people(s)

    assert len(_письма(потерял_телефон["id"])) == сначала, (
        "робот пишет одно и то же каждую ночь"
    )


def test_отвеченное_обращение_не_считается(client, потерял_телефон, user_factory):
    """Контроль: если Александр ответил, человек уже не в тишине — дёргать не за что."""
    админ = user_factory("АдминОтветил178", role=UserRole.admin)
    with Session(engine) as s:
        тикет = s.exec(select(SupportTicket).where(
            SupportTicket.user_id == потерял_телефон["id"])).first()
        s.add(SupportMessage(ticket_id=тикет.id, sender=SupportSender.admin,
                             body="Нашли водителя, он привезёт телефон завтра."))
        s.commit()
    _состарить_тикет(потерял_телефон["id"], 3)
    assert админ["id"]

    with Session(engine) as s:
        итог = waiting_on_us.remind_waiting_people(s)

    assert итог["support"] == 0, "человеку ответили, а робот всё равно пишет «мы помним»"


def test_заявка_в_таксисты_тоже_не_молчит(client, user_factory):
    """Второй случай из пробы: человек собрал документы и ждёт работу."""
    человек = user_factory("РустемХочетРаботать", role=UserRole.driver, taxi_approved=False)
    ответ = client.post("/taxi/apply", headers=человек["auth"], json=ЗАЯВКА)
    assert ответ.status_code == 200, ответ.text[:200]
    _состарить_заявку(человек["id"], 30)

    with Session(engine) as s:
        итог = waiting_on_us.remind_waiting_people(s)

    assert итог["taxi_app"] >= 1, "заявка месяц лежит, а человеку так и не написали"
    assert _письма(человек["id"]), "письмо не дошло"


def test_ночной_робот_зовёт_эту_задачу(client, потерял_телефон):
    """Задача бесполезна, если её никто не запускает — проверяем сам вызов."""
    _состарить_тикет(потерял_телефон["id"], 3)

    with Session(engine) as s:
        сводка = taxi_worker.run_once(s)

    assert "people_waiting" in сводка, (
        f"ночной робот не знает про людей, ждущих ответа: {sorted(сводка)}"
    )
    assert _письма(потерял_телефон["id"]), "робот прогнался, а человеку никто не написал"
