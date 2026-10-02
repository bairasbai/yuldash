"""Лист 2.1, R5 account.py (независимое ревью): удаление аккаунта обязано освобождать номер
от следов кода входа (`OtpCode`), а не только от `WaitlistEntry`.

Сценарий человека: номер телефона сменил владельца (старый аккаунт удалён), новый человек
регистрируется по этому же номеру. Если старая запись `OtpCode` переживает удаление — счётчик
неудачных попыток (`MAX_OTP_ATTEMPTS_PER_PHONE`/суточный бюджет, см. Б-1) считал бы историю
ПРЕЖНЕГО владельца как свою, и новый человек мог бы наткнуться на чужой остаток лимита.

Существующий широкий тест `test_account_deletion.py::test_delete_account_leaves_no_residual_anywhere`
сеет и проверяет `WaitlistEntry` по номеру, но не заводит для него `OtpCode` — поломка, убирающая
`account.py`: `delete(OtpCode).where(OtpCode.phone == phone)`, тем тестом не ловится.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app import models as M
from app.db import engine
from app.timeutil import utcnow


def test_delete_account_removes_otp_history_for_the_phone(client, user_factory):
    u = user_factory("УдаляюсьСКодом")
    phone = "+79995554040"
    with Session(engine) as s:
        user = s.get(M.User, u["id"])
        user.phone = phone
        s.add(user)
        s.add(M.OtpCode(phone=phone, code="112233", attempts=3,
                        expires_at=utcnow() + timedelta(minutes=5)))
        s.commit()

    assert client.post("/me/delete", headers=u["auth"]).status_code == 200

    with Session(engine) as s:
        leftover = s.exec(select(M.OtpCode).where(M.OtpCode.phone == phone)).all()
        assert leftover == [], (
            f"код/попытки прежнего владельца номера пережили удаление аккаунта: {leftover}"
        )
