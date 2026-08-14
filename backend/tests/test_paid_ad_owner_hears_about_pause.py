"""Партнёр должен узнать, что его оплаченную рекламу сняли с показа.

История. Магазин в Сибае оплатил размещение на месяц. Реклама шла, потом админ поставил её
на паузу — исправить текст, перепроверить, что угодно. Владелец об этом не узнавал: просто
в какой-то день перестали приходить клиенты по рекламе (аудит 2026-08-08, волна 86).

Про одобрение и отказ ему говорили с самой волны 24 — а про снятие руками нет. Деньги
уплачены вперёд, и молчание тут читается однозначно: «нас обманули». Для маленького
бизнеса в районе это разговор на всю деревню.

Границы:
* объявления самой платформы (без владельца) молчат — адресата нет;
* смена статуса, не меняющая факта показа (черновик → архив), тоже молчит: человек ничего
  не видел в эфире и ничего не потерял;
* возврат в эфир — новость хорошая, о ней говорим тоже.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Ad, Notification, UserRole


@pytest.fixture
def quiet_push(monkeypatch):
    import app.services as svc
    monkeypatch.setattr(svc, "send_push", lambda *a, **kw: None)


def _ad(owner_id: int | None, status: str = "active") -> int:
    with Session(engine) as s:
        ad = Ad(title="Пекарня «Каравай»", text="скидка 10%", partner_name="Каравай",
                owner_id=owner_id, status=status, plan="basic", period_days=30)
        s.add(ad)
        s.commit()
        s.refresh(ad)
        return ad.id


def _notes(user_id: int) -> list[str]:
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(Notification.user_id == user_id)).all()
    return [f"{n.title_ru} · {n.body_ru}" for n in rows]


def _set_status(client, admin, ad_id: int, status: str):
    return client.post(f"/admin/ads/{ad_id}/status", headers=admin["auth"], json={"status": status})


def test_снятие_с_показа_не_проходит_молча(client, user_factory, quiet_push):
    admin = user_factory("РекламаАдмин", role=UserRole.admin)
    owner = user_factory("ПекарняВладелец")
    ad_id = _ad(owner["id"], status="active")

    r = _set_status(client, admin, ad_id, "paused")
    assert r.status_code == 200, r.text

    texts = _notes(owner["id"])
    assert texts, "оплаченную рекламу сняли с показа, а владельцу не сказали"
    assert any("не показывается" in t.lower() or "снята" in t.lower() for t in texts), texts


def test_возврат_в_эфир_тоже_новость(client, user_factory, quiet_push):
    admin = user_factory("ВозвратАдмин", role=UserRole.admin)
    owner = user_factory("ВозвратВладелец")
    ad_id = _ad(owner["id"], status="paused")

    r = _set_status(client, admin, ad_id, "active")
    assert r.status_code == 200, r.text

    texts = _notes(owner["id"])
    assert any("эфир" in t.lower() for t in texts), f"рекламу вернули, человек не узнал: {texts}"


def test_объявление_платформы_никого_не_будит(client, user_factory, quiet_push):
    """У админского объявления нет владельца — адресата не существует."""
    admin = user_factory("СвоёАдмин", role=UserRole.admin)
    ad_id = _ad(None, status="active")

    with Session(engine) as s:
        before = len(s.exec(select(Notification)).all())

    r = _set_status(client, admin, ad_id, "paused")
    assert r.status_code == 200, r.text   # не падаем на отсутствующем владельце

    with Session(engine) as s:
        after = len(s.exec(select(Notification)).all())
    assert after == before, "объявление без владельца всё-таки кого-то разбудило"


def test_черновик_в_архив_не_тревожит(client, user_factory, quiet_push):
    """Показа не было — терять нечего, и уведомление тут только пугает."""
    admin = user_factory("ЧерновикАдмин", role=UserRole.admin)
    owner = user_factory("ЧерновикВладелец")
    ad_id = _ad(owner["id"], status="draft")

    r = _set_status(client, admin, ad_id, "archived")
    assert r.status_code == 200, r.text

    assert not _notes(owner["id"]), f"о снятии того, что не показывалось, пришло письмо: {_notes(owner['id'])}"
