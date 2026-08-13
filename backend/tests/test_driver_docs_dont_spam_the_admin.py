"""Повторная отправка документов не превращается в поток сообщений Александру.

История. Водитель отправляет фото прав и машины на проверку, и Александру приходит сообщение
с кнопками «Одобрить / Отклонить». Отправить документы заново можно сколько угодно — и это
правильно: снял права бликом, переснял, отправил ещё раз.

Но сообщение уходило на КАЖДОЕ нажатие, даже когда ничего не изменилось: тридцать нажатий —
тридцать одинаковых сообщений (проверено запросом, аудит 2026-08-12, волна 50). Внимание
Александра — общий ресурс: среди дублей теряется и срочная заявка за пожилого, и чужая жалоба.

Соседние двери к тому же Telegram закрыты давно: заявка курьера и регистрация бизнеса на
повтор отвечают «уже на рассмотрении». Здесь дверь оставалась открытой, потому что повтор
тут нужен по делу — и это оказалось важнее, чем закрыть её целиком.

Правило поэтому не «нельзя переотправить», а «не будим админа тем, что для него не новость».
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import DriverProfile, UserRole
from app.timeutil import utcnow

from conftest import upload_doc


@pytest.fixture
def to_admin(monkeypatch) -> list:
    sent: list = []
    monkeypatch.setattr("app.routers.drivers.notify_admin_telegram", lambda *a, **k: sent.append(a))
    return sent


def _submit(client, auth, lic: str, car: str):
    return client.post("/driver/verify", headers=auth,
                       json={"license_url": lic, "car_photo_url": car})


def _age_submission(user_id: int, minutes: int) -> None:
    """Сдвинуть время последней подачи в прошлое — как будто человек вернулся позже."""
    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == user_id)).first()
        dp.verify_submitted_at = utcnow() - timedelta(minutes=minutes)
        s.add(dp)
        s.commit()


def test_первая_подача_доходит_до_админа(client, user_factory, to_admin):
    drv = user_factory("DocsFirst", role=UserRole.driver)
    lic, car = upload_doc(client, drv["auth"]), upload_doc(client, drv["auth"])

    assert _submit(client, drv["auth"], lic, car).status_code == 200
    assert len(to_admin) == 1


def test_долбёжка_одними_и_теми_же_фото_админа_не_будит(client, user_factory, to_admin):
    drv = user_factory("DocsSpam", role=UserRole.driver)
    lic, car = upload_doc(client, drv["auth"]), upload_doc(client, drv["auth"])

    for _ in range(30):
        assert _submit(client, drv["auth"], lic, car).status_code == 200   # принимать — принимаем
    assert len(to_admin) == 1                                             # а будим один раз


def test_переснял_права_админ_узнаёт(client, user_factory, to_admin):
    """Главное, что нельзя сломать защитой: человек исправил документ и ждёт проверки."""
    drv = user_factory("DocsFixed", role=UserRole.driver)
    lic, car = upload_doc(client, drv["auth"]), upload_doc(client, drv["auth"])
    _submit(client, drv["auth"], lic, car)
    assert len(to_admin) == 1

    _age_submission(drv["id"], minutes=10)          # вернулся позже с новым фото
    better = upload_doc(client, drv["auth"])
    assert _submit(client, drv["auth"], better, car).status_code == 200
    assert len(to_admin) == 2


def test_новые_фото_подряд_тоже_не_поток(client, user_factory, to_admin):
    """Обойти правило, подсовывая каждый раз новый файл, не выйдет: у пинга есть пауза."""
    drv = user_factory("DocsNewEachTime", role=UserRole.driver)
    car = upload_doc(client, drv["auth"])
    _submit(client, drv["auth"], upload_doc(client, drv["auth"]), car)
    assert len(to_admin) == 1

    for _ in range(10):
        _submit(client, drv["auth"], upload_doc(client, drv["auth"]), car)
    assert len(to_admin) == 1


def test_после_отказа_новая_подача_доходит(client, user_factory, to_admin):
    """Документы отклонили — это уже другая история, и админ должен её увидеть сразу,
    без всякой паузы."""
    drv = user_factory("DocsRejected", role=UserRole.driver)
    lic, car = upload_doc(client, drv["auth"]), upload_doc(client, drv["auth"])
    _submit(client, drv["auth"], lic, car)
    assert len(to_admin) == 1

    with Session(engine) as s:
        dp = s.exec(select(DriverProfile).where(DriverProfile.user_id == drv["id"])).first()
        dp.docs_status = "rejected"
        s.add(dp)
        s.commit()

    assert _submit(client, drv["auth"], lic, car).status_code == 200
    assert len(to_admin) == 2
