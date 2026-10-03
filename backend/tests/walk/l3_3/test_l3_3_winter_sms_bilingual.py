# -*- coding: utf-8 -*-
"""Зимняя тревога близким уходила SMS ТОЛЬКО по-русски (независимое ревью leaf-3.1, карточка
routers/safety.py, раздел «Остаток»: «Зимняя SMS близким (winter_escalate.py:161) — только
по-русски (нарушение двуязычия), но сам файл вне зоны»).

Башкироязычная мама получала тревожное сообщение о близком на чужом языке — в минуту, когда
читать и понимать нужно быстро. Тот же класс ошибки, что чинили для push «доехал?» (WINTER_ASK_BA,
волна 193): перевод был готов рядом, просто не вызывался.

Правило проекта (services.sms_lang_of): у доверенного контакта своего языка в базе нет — берём
язык ТОГО, кто его завёл (watch_user_id), он знает свою маму лучше нас.
"""
from __future__ import annotations

from sqlmodel import Session, select

from app import winter_escalate
from app.db import engine
from app.models import SosEvent, TrustedContact, User


def _контакт(user_id: int, phone: str) -> None:
    with Session(engine) as s:
        s.add(TrustedContact(user_id=user_id, name="Мама", phone=phone))
        s.commit()


def _поставить_язык(user_id: int, lang: str) -> None:
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.language = lang
        s.add(u)
        s.commit()


def test_русскоязычному_уходит_русский_текст(client, user_factory, monkeypatch):
    человек = user_factory("ЗимаРус")
    _поставить_язык(человек["id"], "ru")
    _контакт(человек["id"], "+79990001111")

    отправлено = []
    monkeypatch.setattr("app.routers.safety._send_sos_sms",
                        lambda phones, text: отправлено.append(text))
    monkeypatch.setattr("app.services.notify_admin_telegram", lambda *a, **kw: True)

    with Session(engine) as s:
        winter_escalate.escalate_now(
            s, kind="parcel", obj_id=901, watch_user_id=человек["id"],
            contact_phones=["+79990001111"],
        )

    assert отправлено, "SMS не ушло вовсе — тест ничего не проверил"
    assert "не отметил" in отправлено[0], отправлено[0]


def test_башкироязычному_уходит_башкирский_текст(client, user_factory, monkeypatch):
    человек = user_factory("ЗимаБаш")
    _поставить_язык(человек["id"], "ba")
    _контакт(человек["id"], "+79990002222")

    отправлено = []
    monkeypatch.setattr("app.routers.safety._send_sos_sms",
                        lambda phones, text: отправлено.append(text))
    monkeypatch.setattr("app.services.notify_admin_telegram", lambda *a, **kw: True)

    with Session(engine) as s:
        итог = winter_escalate.escalate_now(
            s, kind="parcel", obj_id=902, watch_user_id=человек["id"],
            contact_phones=["+79990002222"],
        )

    assert итог["state"] == "escalated"
    assert отправлено, "SMS не ушло вовсе — тест ничего не проверил"
    башкирские_буквы = "әөүҙҫңғҡӘӨҮҘҪҢҒҠ"
    assert any(б in отправлено[0] for б in башкирские_буквы), (
        f"башкироязычному человеку близким ушёл не башкирский текст: {отправлено[0]!r}"
    )
    # Не пустая заглушка — реальный текст про «доехал», а не только название.
    assert "Юлдаш" in отправлено[0]


def test_повторная_эскалация_не_шлёт_второе_sms(client, user_factory, monkeypatch):
    """Идемпотентность (БРИФ §4а): повторный вызов для ТОГО ЖЕ booking/order/parcel (два
    воркера одновременно, или ночной робот зашёл второй раз на то же дело) не должен ещё раз
    пугать близких вторым SMS и заводить второе служебное событие."""
    человек = user_factory("ЗимаПовтор")
    _контакт(человек["id"], "+79990004444")

    отправлено = []
    monkeypatch.setattr("app.routers.safety._send_sos_sms",
                        lambda phones, text: отправлено.append(text))
    monkeypatch.setattr("app.services.notify_admin_telegram", lambda *a, **kw: True)

    with Session(engine) as s:
        первый = winter_escalate.escalate_now(
            s, kind="parcel", obj_id=904, watch_user_id=человек["id"],
            contact_phones=["+79990004444"],
        )
    with Session(engine) as s:
        второй = winter_escalate.escalate_now(
            s, kind="parcel", obj_id=904, watch_user_id=человек["id"],
            contact_phones=["+79990004444"],
        )

    assert первый["state"] == "escalated" and not первый.get("already")
    assert второй.get("already") is True, "повторный вызов не распознан как уже эскалированный"
    assert len(отправлено) == 1, f"повторный вызов отправил SMS ещё раз: {отправлено}"
    with Session(engine) as s:
        события = s.exec(select(SosEvent).where(
            SosEvent.user_id == человек["id"], SosEvent.category == "other",
        )).all()
    assert len(события) == 1, f"повторный вызов завёл второе служебное событие: {len(события)}"


def test_note_совпадает_со_своим_же_identity_pattern():
    """Контракт, на котором держится и идемпотентность здесь (M8), и закрытие тревоги по
    «доехал(а)» в routers/safety.py::_winter_ack (N3, leaf-3.1, чужой файл): текст события,
    который реально пишется в БД, обязан начинаться с префикса, который сам же `escalate_now`
    (и `_winter_ack`) потом ищут через LIKE. Чистая проверка строк, без БД."""
    note = winter_escalate._winter_note("booking", 5)
    pattern = winter_escalate._winter_identity_pattern("booking", 5)
    assert pattern.endswith("%"), pattern
    assert note.startswith(pattern[:-1]), (
        f"{note!r} не начинается с {pattern[:-1]!r} — ни повторная эскалация, ни «доехал(а)» "
        "в safety.py не найдут это событие по префиксу"
    )


def test_identity_pattern_не_путает_order_1_и_order_10():
    """Исторический баг (комментарий в коде): LIKE без закрывающего разделителя после числа
    считал `order#1` и `order#10` одним и тем же — старое событие «проглатывало» новое и
    наоборот. Разделитель — литеральные `)`/`:` сразу после номера."""
    note_10 = winter_escalate._winter_note("order", 10)
    pattern_1 = winter_escalate._winter_identity_pattern("order", 1)
    assert not note_10.startswith(pattern_1[:-1]), (
        f"событие по order#10 ({note_10!r}) спутано с идентичностью order#1 ({pattern_1!r})"
    )

    note_100 = winter_escalate._winter_note("order", 100)
    pattern_10 = winter_escalate._winter_identity_pattern("order", 10)
    assert not note_100.startswith(pattern_10[:-1]), (
        f"событие по order#100 ({note_100!r}) спутано с идентичностью order#10 ({pattern_10!r})"
    )
