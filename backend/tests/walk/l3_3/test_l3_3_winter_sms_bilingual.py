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

from sqlmodel import Session

from app import winter_escalate
from app.db import engine
from app.models import TrustedContact, User


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
