"""Пожаловался — узнай, чем кончилось.

История. Зульфия поехала попуткой, водитель вёл себя плохо, она пожаловалась. Дальше —
тишина: разбор шёл, решение принималось, а ей не приходило ничего (аудит 2026-08-08, волна 85).

Человек не понимает, посмотрели его жалобу или она утонула. В следующий раз он просто
не напишет: тишина учит молчать, а на молчании безопасность не строится — это тот самый
случай, когда «между своими» рассыпается.

Вторая сторона в таком же положении. Ринату пришло «поступила жалоба, разбираемся» — и всё.
Если жалоба не подтвердилась, он об этом не узнавал и жил с ощущением висящего обвинения.

Чего делать нельзя:
* автору не рассказываем, что именно сделали с человеком — это чужое наказание;
* цели не рассказываем, кто пожаловался — анонимность здесь и есть продукт;
* при подтверждённой жалобе цель НЕ получает второе сообщение подряд: она уже узнала
  про наказание, а «и ещё раз: ты виноват» — это добивание.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Notification, Report, UserRole


@pytest.fixture
def quiet_push(monkeypatch):
    import app.services as svc
    monkeypatch.setattr(svc, "send_push", lambda *a, **kw: None)


def _report(reporter_id: int, target_id: int, category: str = "rude") -> int:
    with Session(engine) as s:
        r = Report(reporter_id=reporter_id, target_user_id=target_id,
                   category=category, description="грубил всю дорогу", status="new")
        s.add(r)
        s.commit()
        s.refresh(r)
        return r.id


def _notes(user_id: int) -> list[str]:
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(Notification.user_id == user_id)).all()
    return [f"{n.title_ru} · {n.body_ru}" for n in rows]


def test_автор_жалобы_узнаёт_что_её_подтвердили(client, user_factory, quiet_push):
    admin = user_factory("РазборАдмин", role=UserRole.admin)
    zulfia = user_factory("ЖаловаласьЗульфия")
    rinat = user_factory("ОбвиняемыйРинат", role=UserRole.driver)
    report_id = _report(zulfia["id"], rinat["id"])

    r = client.post(f"/admin/reports/{report_id}/resolve", headers=admin["auth"],
                    json={"resolution": "подтверждено"})
    assert r.status_code == 200, r.text

    texts = _notes(zulfia["id"])
    assert texts, "жалобу разобрали, а человеку не сказали ничего — в следующий раз он не напишет"
    assert any("подтвердил" in t.lower() for t in texts), texts


def test_автор_жалобы_узнаёт_и_про_отказ(client, user_factory, quiet_push):
    admin = user_factory("ОтказРазборАдмин", role=UserRole.admin)
    zulfia = user_factory("ОтказЗульфия")
    rinat = user_factory("ОтказРинат", role=UserRole.driver)
    report_id = _report(zulfia["id"], rinat["id"])

    r = client.post(f"/admin/reports/{report_id}/reject", headers=admin["auth"], json={})
    assert r.status_code == 200, r.text

    texts = _notes(zulfia["id"])
    assert texts, "жалобу отклонили молча — человек так и не узнал, что её вообще смотрели"
    assert any("не нашли" in t.lower() or "разобрал" in t.lower() for t in texts), texts


def test_обвинение_снимают_вслух(client, user_factory, quiet_push):
    """Иначе человек остаётся с висящим обвинением: сказали «на тебя пожаловались» и замолчали."""
    admin = user_factory("СнятиеОбвАдмин", role=UserRole.admin)
    zulfia = user_factory("СнятиеОбвЗульфия")
    rinat = user_factory("СнятиеОбвРинат", role=UserRole.driver)
    report_id = _report(zulfia["id"], rinat["id"])

    r = client.post(f"/admin/reports/{report_id}/reject", headers=admin["auth"], json={})
    assert r.status_code == 200, r.text

    texts = _notes(rinat["id"])
    assert any("не подтвердил" in t.lower() for t in texts), \
        f"жалобу отклонили, а человек об этом не узнал: {texts}"
    # Заголовка мало: человеку важно услышать, что ограничений больше нет и можно работать.
    # Пустое тело под правильным заголовком — это уведомление ни о чём.
    assert any("ограничен" in t.lower() or "спокойно" in t.lower() for t in texts), \
        f"сказали «не подтвердилась» и не сказали главного — что можно работать: {texts}"


def test_подтверждённая_жалоба_не_добивает_вторым_сообщением(client, user_factory, quiet_push):
    """Про наказание человек уже узнал. Второе «ты виноват» подряд — это не информирование."""
    admin = user_factory("ДобиваниеАдмин", role=UserRole.admin)
    zulfia = user_factory("ДобиваниеЗульфия")
    rinat = user_factory("ДобиваниеРинат", role=UserRole.driver)
    report_id = _report(zulfia["id"], rinat["id"])

    r = client.post(f"/admin/reports/{report_id}/resolve", headers=admin["auth"],
                    json={"resolution": "подтверждено"})
    assert r.status_code == 200, r.text

    texts = _notes(rinat["id"])
    assert not any("разбор закончен" in t.lower() for t in texts), \
        f"обвиняемому пришло лишнее сообщение про исход: {texts}"
