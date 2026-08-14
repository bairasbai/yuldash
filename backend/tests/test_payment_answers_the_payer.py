"""Заплатил — узнай, чем кончилось.

История. Переводы у нас «на доверии»: человек отправляет деньги по СБП и ждёт, пока админ
увидит поступление и нажмёт кнопку. Ни подтверждение, ни отказ до него не доходили никак —
ни одного уведомления на всём пути (аудит 2026-08-08, волна 84).

Со стороны человека это выглядит так. Ильдар оплатил поднятие объявления. Дальше он сидит
и обновляет ленту, пытаясь понять, сработало или нет. А если админ отклонил перевод («денег
не пришло»), не происходит вообще ничего: Ильдар уверен, что заплатил, и ждёт неделю,
прежде чем написать в поддержку.

Деньги — то место, где тишина обходится дороже всего: человек не может проверить сам.

Границы: служебные платежи внутри поездки (оплата брони, оплата заказа) отдельным
уведомлением не сопровождаем — человек и так видит состояние поездки, и лишний пуш там
только шумит.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import Notification, Payment, UserRole


@pytest.fixture
def quiet_push(monkeypatch):
    import app.services as svc
    monkeypatch.setattr(svc, "send_push", lambda *a, **kw: None)


def _payment(user_id: int, purpose: str, tier: str = "day") -> int:
    with Session(engine) as s:
        p = Payment(user_id=user_id, purpose=purpose, amount_kop=10000, status="pending", tier=tier)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _notes(user_id: int) -> list[str]:
    with Session(engine) as s:
        rows = s.exec(select(Notification).where(Notification.user_id == user_id)).all()
    return [f"{n.title_ru} · {n.body_ru}" for n in rows]


def test_отклонённый_перевод_не_оставляют_в_тишине(client, user_factory, quiet_push):
    admin = user_factory("ОтказАдмин", role=UserRole.admin)
    ildar = user_factory("ОтказИльдар")
    payment_id = _payment(ildar["id"], "donate")

    r = client.post(f"/admin/payments/{payment_id}/reject", headers=admin["auth"])
    assert r.status_code == 200, r.text

    texts = _notes(ildar["id"])
    assert texts, "перевод отклонили, а человек об этом не узнал — он будет ждать неделю"
    assert any("не нашли" in t.lower() or "поддержк" in t.lower() for t in texts), texts


def test_подтверждённый_перевод_подтверждают_человеку(client, user_factory, quiet_push):
    admin = user_factory("ДонатАдмин", role=UserRole.admin)
    zuhra = user_factory("ДонатЗухра")
    payment_id = _payment(zuhra["id"], "donate")

    r = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert r.status_code == 200, r.text

    texts = _notes(zuhra["id"])
    assert texts, "деньги получили и промолчали"
    assert any("получен" in t.lower() for t in texts), texts


def test_служебный_платёж_поездки_не_шумит(client, user_factory, quiet_push):
    """Оплата брони видна по самой поездке — отдельный пуш здесь только мешает."""
    admin = user_factory("СлужебныйАдмин", role=UserRole.admin)
    rinat = user_factory("СлужебныйРинат")
    payment_id = _payment(rinat["id"], "booking")

    r = client.post(f"/admin/payments/{payment_id}/confirm", headers=admin["auth"])
    assert r.status_code == 200, r.text

    assert not _notes(rinat["id"]), f"служебный платёж прислал лишнее уведомление: {_notes(rinat['id'])}"


def test_повторное_отклонение_не_шлёт_второе(client, user_factory, quiet_push):
    """Админ тапнул дважды — человек не должен получить два «перевод не нашли»."""
    admin = user_factory("ДваждыАдмин", role=UserRole.admin)
    aigul = user_factory("ДваждыАйгуль")
    payment_id = _payment(aigul["id"], "donate")

    client.post(f"/admin/payments/{payment_id}/reject", headers=admin["auth"])
    after_first = len(_notes(aigul["id"]))
    client.post(f"/admin/payments/{payment_id}/reject", headers=admin["auth"])

    assert len(_notes(aigul["id"])) == after_first, "второй тап админа прислал человеку ещё один отказ"
