"""Просроченный полис «продлевался» вводом даты — навсегда и без единой проверки (волна 170).

У таксиста кончилось ОСАГО, ночной робот снял его с линии — всё правильно. Дальше человек
открывает приложение, вписывает «полис действует до 2030 года» и работает дальше. Фото
не приложено, модератор ничего не видел, и больше уже не увидит: ночной робот смотрит на даты,
а даты водитель поправил своей рукой.

Случись авария — пассажир останется без выплаты, а водитель узнает о своём «полисе» в ГИБДД.

**Возвращать допуск сразу — правильно, и это решено давно** (2026-07-26): продлил полис
в обед — работай вечером, ждать модератора ради этого никто не должен. Наказывать за
законопослушность нельзя. Но дальше слово надо подтвердить бумагой.

Теперь так: вписал дату без фото — работаешь, но у обещания появился срок. Принёс фото —
срок снимается, вопрос закрыт. Не принёс за отпущенные дни — ночной робот возвращает снятие
и объясняет, что делать. Админу о таком возврате приходит сигнал сразу: это единственный
случай, когда допуск держится на честном слове.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import doc_check
from app.config import settings
from app.db import engine
from app.models import TaxiApplication, TaxiApplicationStatus, UserRole
from app.timeutil import local_date, utcnow


@pytest.fixture
def такси_включено(monkeypatch):
    monkeypatch.setattr(settings, "taxi_enabled", True, raising=False)


@pytest.fixture
def снятый_за_осаго(client, user_factory, такси_включено):
    """Таксист, которого ночной робот снял с линии за кончившийся полис."""
    водитель = user_factory("ТаксистСПолисом", role=UserRole.driver)
    with Session(engine) as s:
        заявка = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == водитель["id"])).first()
        if заявка is None:
            заявка = TaxiApplication(user_id=водитель["id"])
        заявка.status = TaxiApplicationStatus.approved
        заявка.reviewed_at = utcnow()
        заявка.osago_until = local_date(utcnow()) - timedelta(days=1)
        заявка.osago_url = ""
        заявка.docs_expired = True
        s.add(заявка)
        s.commit()
    return водитель


def _заявка(user_id: int) -> TaxiApplication:
    with Session(engine) as s:
        return s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == user_id)).first()


def _новая_дата() -> str:
    return (local_date(utcnow()) + timedelta(days=365)).isoformat()


def test_дата_без_фото_даёт_срок_а_не_вечный_допуск(client, снятый_за_осаго):
    """Главное: слово возвращает на линию, но обещание должно быть подтверждено."""
    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата()})

    заявка = _заявка(снятый_за_осаго["id"])
    assert заявка.docs_expired is False, (
        "водителя не пустили на линию после продления полиса — это наказание "
        "за законопослушность"
    )
    assert заявка.docs_photo_due_at is not None, (
        "допуск вернули по одной дате и без срока: фото не приложено, модератор ничего "
        "не видел, а ночной робот больше не вмешается — даты в порядке. Страховки может "
        "не быть вовсе"
    )


def test_не_принёс_фото_допуск_снимается(client, снятый_за_осаго):
    """Срок вышел — возвращаемся туда, откуда пришли."""
    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата()})
    with Session(engine) as s:                       # прошли отпущенные дни
        заявка = s.exec(select(TaxiApplication).where(
            TaxiApplication.user_id == снятый_за_осаго["id"])).first()
        заявка.docs_photo_due_at = utcnow() - timedelta(minutes=1)
        s.add(заявка)
        s.commit()

    with Session(engine) as s:
        doc_check.expire_unconfirmed_docs(s)

    заявка = _заявка(снятый_за_осаго["id"])
    assert заявка.docs_expired is True, (
        "фото так и не пришло, а водитель продолжает возить пассажиров: обещание осталось "
        "словом навсегда"
    )


def test_принёс_фото_срок_снимается(client, снятый_за_осаго):
    """Обратная сторона: подтвердил бумагой — вопрос закрыт, работай спокойно."""
    from conftest import upload_doc

    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата()})
    фото = upload_doc(client, снятый_за_осаго["auth"])

    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата(), "osago_url": фото})

    заявка = _заявка(снятый_за_осаго["id"])
    assert заявка.docs_photo_due_at is None, (
        "водитель прислал фото полиса, а срок «принеси документ» остался висеть: "
        "через три дня его снимут с линии ни за что"
    )
    assert заявка.docs_expired is False


def test_сразу_с_фото_срока_не_возникает(client, снятый_за_осаго):
    """Обратная сторона: аккуратному водителю лишних шагов не добавляем."""
    from conftest import upload_doc
    фото = upload_doc(client, снятый_за_осаго["auth"])

    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата(), "osago_url": фото})

    заявка = _заявка(снятый_за_осаго["id"])
    assert заявка.docs_photo_due_at is None, (
        "фото приложено сразу, а от человека всё равно чего-то ждут"
    )
    assert заявка.docs_expired is False


def test_ночной_обход_не_трогает_тех_кто_в_сроке(client, снятый_за_осаго):
    """Обратная сторона: пока срок не вышел, человек работает — это и есть смысл отсрочки."""
    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата()})

    with Session(engine) as s:
        doc_check.expire_unconfirmed_docs(s)

    заявка = _заявка(снятый_за_осаго["id"])
    assert заявка.docs_expired is False, (
        "водителя сняли с линии в первый же вечер, хотя срок принести фото ещё не вышел"
    )


def test_админ_узнаёт_о_возврате_на_честном_слове(client, снятый_за_осаго, monkeypatch):
    """Это единственный случай, когда допуск держится на слове — админ должен знать."""
    сообщения = []
    monkeypatch.setattr("app.routers.taxi.notify_admin_telegram",
                        lambda текст, **kw: сообщения.append(текст))

    client.post("/taxi/documents", headers=снятый_за_осаго["auth"],
                json={"osago_until": _новая_дата()})

    assert сообщения, "админ не узнал, что допуск вернули без документа"
    assert "фото" in " ".join(сообщения).lower()
