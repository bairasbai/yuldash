# -*- coding: utf-8 -*-
"""`/support/tickets` была единственной «важной кнопкой» без защиты от двойного нажатия
(test_bad_connection_does_not_double_things.py, волна 135, проверил бронь/отклик/жалобу/
контакт — тикет поддержки туда не попал). В деревне и на трассе связь рвётся посреди отправки:
человек жмёт «Отправить», ответа не видит, жмёт ещё раз — приложение само повторяет запрос.

Было: два одинаковых тикета. Человек видит в «Моих обращениях» два одинаковых вопроса (непонятно,
ответили уже или нет), а дежурному в Telegram уходит ДВА сигнала на одно и то же нажатие
(`_notify_admin_new_ticket` считает кулдаун отдельно на каждый ticket_id).

Стало: пока прежний тикет ещё открыт, совпадение темы и текста первого сообщения — тот же
вопрос, повторное нажатие отдаёт его же (как `create_incident` отдаёт то же дело, волна 135)."""
from __future__ import annotations

from sqlmodel import Session, select

from app.db import engine
from app.models import SupportTicket


def _create(client, u, subject="Проблема", body="Не приходит смс"):
    return client.post("/support/tickets", headers=u["auth"],
                       json={"subject": subject, "body": body})


def test_двойной_тап_не_плодит_два_тикета(client, user_factory):
    u = user_factory("СаппортДвойнойТап")

    r1 = _create(client, u, body="Деньги списались, а поездки нет")
    r2 = _create(client, u, body="Деньги списались, а поездки нет")

    assert r1.status_code == 200 and r2.status_code == 200
    assert r1.json()["id"] == r2.json()["id"], (
        "двойное нажатие завело два тикета: в «Моих обращениях» два одинаковых вопроса, "
        "а дежурному ушло два отдельных сигнала в Telegram на одно и то же нажатие"
    )
    with Session(engine) as s:
        тикетов = len(s.exec(select(SupportTicket).where(SupportTicket.user_id == u["id"])).all())
    assert тикетов == 1, f"в базе {тикетов} тикета вместо одного"


def test_двойной_тап_не_дублирует_сигнал_админу(client, user_factory, monkeypatch):
    u = user_factory("СаппортДвойнойПинг")
    сигналы = []
    monkeypatch.setattr("app.routers.support.notify_admin_telegram",
                        lambda text, **kw: сигналы.append(text))

    _create(client, u, body="Курьер не отвечает")
    _create(client, u, body="Курьер не отвечает")

    assert len(сигналы) == 1, f"дежурному ушло {len(сигналы)} сигналов на одно нажатие: {сигналы}"


def test_разный_текст_заводит_отдельный_тикет(client, user_factory):
    """Обратная сторона: это не общий дедуп по времени — два РАЗНЫХ вопроса не склеиваются."""
    u = user_factory("СаппортДваВопроса")

    первый = _create(client, u, subject="Оплата", body="Деньги списались, а поездки нет")
    второй = _create(client, u, subject="Водитель", body="Водитель опоздал на час")

    assert первый.json()["id"] != второй.json()["id"], "два разных вопроса склеились в один тикет"


def test_после_ответа_поддержки_новое_такое_же_сообщение_не_склеивается(client, user_factory):
    """Если тикет уже ПРОДВИНУЛСЯ (есть ответ поддержки), совпадение текста первого сообщения
    больше не значит «то же самое нажатие» — но тикет всё ещё open, и повторная попытка
    создать НОВЫЙ тикет с тем же текстом по-прежнему возвращает существующий тред (ожидаемо:
    это тот же открытый вопрос, админ видит историю целиком, а не теряет контекст)."""
    from app.models import UserRole
    u = user_factory("СаппортПослеОтвета")
    admin = user_factory("СаппортАдминОтвет", role=UserRole.admin)
    t = _create(client, u, body="Вопрос по тарифу").json()
    client.post(f"/admin/support/tickets/{t['id']}/reply", headers=admin["auth"],
               json={"body": "Уточните, пожалуйста, маршрут"})

    повтор = _create(client, u, body="Вопрос по тарифу")
    assert повтор.json()["id"] == t["id"], (
        "повторная отправка того же вопроса потеряла контекст уже начатого разговора"
    )


def test_закрытый_тикет_с_тем_же_текстом_заводит_новый(client, user_factory):
    """Если прежний тикет ЗАКРЫТ — это уже история, а не тот же разговор: новая жалоба
    с тем же текстом месяц спустя должна дойти до поддержки заново, а не молча исчезнуть
    в архиве закрытого обращения."""
    u = user_factory("СаппортПослеЗакрытия")
    t = _create(client, u, body="Не приходит смс").json()
    client.post(f"/support/tickets/{t['id']}/close", headers=u["auth"])

    снова = _create(client, u, body="Не приходит смс")
    assert снова.json()["id"] != t["id"], (
        "новое обращение склеилось с ДАВНО закрытым — человек не достучался до поддержки"
    )
