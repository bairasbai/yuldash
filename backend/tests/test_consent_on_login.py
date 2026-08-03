# -*- coding: utf-8 -*-
"""152-ФЗ: согласие фиксируется САМИМ входом (разбор №2, 2026-08-03).

Было: на экране входа надпись «Входя, ты принимаешь оферту и политику», реестр `Consent`
в базе есть — и пуст, потому что писался только вручную из настроек. На запрос надзорного
органа или в споре «я ни на что не соглашался» показать было нечего.

Стало: успешный вход любым способом (SMS-код, Telegram, тестовый аккаунт стора) пишет три
факта с датой — оферта, политика, совершеннолетие. Идемпотентно: повторный вход не сдвигает
время ПЕРВОГО согласия, иначе доказательство обесценивается.
"""
from sqlmodel import Session, select

from app.db import engine
from app.models import Consent, User


def _consents(phone: str) -> dict:
    with Session(engine) as s:
        user = s.exec(select(User).where(User.phone == phone)).first()
        assert user, "пользователь должен появиться после входа"
        rows = s.exec(select(Consent).where(Consent.user_id == user.id)).all()
    return {c.kind: c.granted_at for c in rows}


def test_sms_login_records_consents(client):
    phone = "+79990007701"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    r = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "Согласный"})
    assert r.status_code == 200, r.text
    got = _consents(phone)
    assert set(got) >= {"offer", "privacy", "age18"}, got


def test_repeated_login_keeps_first_consent_time(client):
    """Время первого согласия — юридическое доказательство, его нельзя переписывать входом."""
    phone = "+79990007702"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    assert client.post("/auth/verify", json={"phone": phone, "code": code, "name": "Дважды"}).status_code == 200
    first = _consents(phone)
    code2 = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    assert client.post("/auth/verify", json={"phone": phone, "code": code2}).status_code == 200
    assert _consents(phone) == first, "повторный вход не должен сдвигать дату согласия"


def test_consents_are_visible_to_the_person(client):
    """Человек обязан видеть, на что и когда согласился, — иначе это не согласие, а формальность."""
    phone = "+79990007703"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    tok = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "Видящий"}).json()["access_token"]
    r = client.get("/me/consents", headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 200, r.text
    kinds = {row["kind"] for row in r.json()}
    assert {"offer", "privacy", "age18"} <= kinds, kinds


def test_age18_is_a_known_consent_kind(client):
    """Новый вид согласия должен приниматься и ручкой — иначе экран настроек его не выставит."""
    phone = "+79990007704"
    code = client.post("/auth/request-code", json={"phone": phone}).json()["dev_code"]
    tok = client.post("/auth/verify", json={"phone": phone, "code": code, "name": "Взрослый"}).json()["access_token"]
    r = client.post("/me/consents", json={"kind": "age18"},
                    headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 200, r.text
