"""Водитель должен узнать и о паузе, и о её снятии — кто бы её ни поставил.

История. Ринат выходит на линию, а заказов нет. Час, другой. Он перезагружает приложение,
проверяет интернет, пишет в поддержку. На самом деле поддержка поставила ему паузу такси
руками — и не сказала об этом (аудит 2026-08-08, волна 83).

Уведомление в проекте было, но только на одном пути: когда паузу выдаёт автомат по
накопленным жалобам. Ручная пауза и пауза «до разбора жалобы» уходили молча. Для человека
разницы нет: он видит не наказание, а поломку, и теряет смену, пока ищет несуществующую
неисправность.

Обратная сторона такая же дорогая. Паузу сняли — например, признали жалобу ошибочной, —
а человек об этом не знает и не выходит на линию. Молчание тут стоит ему денег.

Что должно остаться неизменным: попутка при паузе такси работает, и текст обязан это
говорить — иначе водитель решит, что закрыто всё.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app import quality
from app.db import engine
from app.models import DriverProfile, Notification, UserRole


@pytest.fixture
def quiet_push(monkeypatch):
    """Пуш на телефон подменяем: проверяем, что уведомление создано, а не как оно доставлено."""
    import app.services as svc
    monkeypatch.setattr(svc, "send_push", lambda *a, **kw: None)


def _make_driver(client, user_factory, name: str):
    driver = user_factory(name, role=UserRole.driver)
    with Session(engine) as s:
        if not s.exec(select(DriverProfile).where(DriverProfile.user_id == driver["id"])).first():
            s.add(DriverProfile(user_id=driver["id"]))
            s.commit()
    return driver


def _notes(user_id: int) -> list[Notification]:
    with Session(engine) as s:
        return list(s.exec(select(Notification).where(Notification.user_id == user_id)).all())


def test_ручная_пауза_не_уходит_молча(client, user_factory, quiet_push):
    admin = user_factory("ПаузаАдмин", role=UserRole.admin)
    rinat = _make_driver(client, user_factory, "ПаузаРинатРучная")

    r = client.post(f"/admin/quality/{rinat['id']}/pause", headers=admin["auth"], json={"hours": 24})
    assert r.status_code == 200, r.text

    texts = [f"{n.title_ru} {n.body_ru}" for n in _notes(rinat["id"])]
    assert any("пауз" in t.lower() for t in texts), \
        f"поддержка поставила паузу, а водитель об этом не узнал: {texts}"
    assert any("опутк" in t for t in texts), \
        "в тексте не сказано, что попутка работает — человек решит, что закрыто всё"


def test_пауза_до_разбора_жалобы_тоже_объясняется(client, user_factory, quiet_push):
    rinat = _make_driver(client, user_factory, "ПаузаРинатРазбор")

    with Session(engine) as s:
        quality.pause_taxi(s, rinat["id"], hours=None, reason=quality.PAUSE_REASON_REVIEW)

    texts = [f"{n.title_ru} {n.body_ru}" for n in _notes(rinat["id"])]
    assert any("жалоб" in t.lower() for t in texts), \
        f"человека отстранили на время разбора и не сказали почему: {texts}"


def test_о_снятии_паузы_тоже_говорят(client, user_factory, quiet_push):
    """Иначе водитель не выйдет на линию: он уверен, что всё ещё наказан."""
    admin = user_factory("СнятиеАдмин", role=UserRole.admin)
    rinat = _make_driver(client, user_factory, "СнятиеРинат")
    assert client.post(f"/admin/quality/{rinat['id']}/pause", headers=admin["auth"],
                       json={"hours": 24}).status_code == 200
    before_ids = {n.id for n in _notes(rinat["id"])}

    r = client.post(f"/admin/quality/{rinat['id']}/unpause", headers=admin["auth"])
    assert r.status_code == 200, r.text

    # SELECT без ORDER BY не обещает порядок: новое уведомление может прийти первым.
    texts = [f"{n.title_ru} {n.body_ru}" for n in _notes(rinat["id"]) if n.id not in before_ids]
    assert texts, "паузу сняли, а человек об этом не узнал"
    assert any("снят" in t.lower() or "доступн" in t.lower() for t in texts), texts


def test_повторная_пауза_не_шлёт_второе_уведомление(client, user_factory, quiet_push):
    """Пауза только удлиняется. Короткая поверх длинной ничего не меняет — и молчит,
    иначе каждый тап админа отправляет человеку ещё одно «ты наказан»."""
    admin = user_factory("ПовторАдмин", role=UserRole.admin)
    rinat = _make_driver(client, user_factory, "ПовторРинат")
    assert client.post(f"/admin/quality/{rinat['id']}/pause", headers=admin["auth"],
                       json={"hours": 48}).status_code == 200
    after_first = len(_notes(rinat["id"]))

    assert client.post(f"/admin/quality/{rinat['id']}/pause", headers=admin["auth"],
                       json={"hours": 1}).status_code == 200

    assert len(_notes(rinat["id"])) == after_first, "короткая пауза поверх длинной прислала лишнее уведомление"
