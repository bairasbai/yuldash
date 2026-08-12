# -*- coding: utf-8 -*-
"""Деньги пришли поздно — они не должны раствориться.

Аудит 2026-08-12, волна 26. Реальный случай, который сам код называет возможным: человек начал
платить картой, передумал и отдал наличными водителю. Наш платёж мы гасим у себя, но ссылка
ЮKassa живёт (отменить неоплаченный платёж их API не умеет), и по ней МОЖНО заплатить.

Что было: вебхук про такой платёж молча выходил. Пробой волны 26 — 500 ₽ ушли с карты, а дальше
ноль: платёж у нас «отменён», записей нет, уведомлений нет, сигнала админу нет. Деньги остались
у платформы, и о долге не знал никто, включая нас.

Что должно быть: платёж помечен как «нужен возврат», у человека открыт тред поддержки — его нить
для разговора, — и админ предупреждён. Тесты написаны от человека: «заплатил дважды и узнал,
что деньги вернут», а не «поле равно refund_due».
"""
import pytest
from sqlmodel import Session, select

from app.db import engine
from app.models import (InstantOrder, InstantOrderStatus, Notification, Payment,
                        SupportTicket, UserRole)


@pytest.fixture
def yookassa_says_paid(monkeypatch):
    """Провайдер включён и подтверждает: деньги пришли. Плюс перехваченный сигнал админу."""
    sent: list = []
    monkeypatch.setattr("app.config.settings.payments_provider", "yookassa")
    monkeypatch.setattr("app.routers.payments.fetch_payment",
                        lambda pid: {"status": "succeeded", "metadata": {}})
    monkeypatch.setattr("app.routers.payments.notify_admin_telegram", lambda text: sent.append(text))
    return sent


def _done_order_with_stale_link(client, user_factory, provider_id: str):
    """Завершённая поездка + начатая, но брошенная оплата картой (ссылка ЮKassa жива)."""
    pax = user_factory(name="Пассажир с двойной оплатой")
    drv = user_factory(name="Водитель наличными", role=UserRole.driver)
    with Session(engine) as s:
        o = InstantOrder(passenger_id=pax["id"], driver_id=drv["id"],
                         status=InstantOrderStatus.done, price_final=500,
                         from_lat=53.0, from_lng=58.0, to_lat=53.1, to_lng=58.1)
        s.add(o)
        s.commit()
        s.refresh(o)
        p = Payment(user_id=pax["id"], purpose="ride", order_id=o.id, amount_kop=50000,
                    method="card", provider_id=provider_id, status="pending")
        s.add(p)
        s.commit()
        s.refresh(p)
        return pax, drv, o.id, p.id


def test_оплата_по_старой_ссылке_не_растворяется(client, user_factory, yookassa_says_paid):
    """Заплатил наличными, потом случайно ещё и картой — деньги видны, и человеку сказали."""
    pax, _drv, order_id, payment_id = _done_order_with_stale_link(client, user_factory, "pid_late_1")

    r = client.post(f"/instant/orders/{order_id}/pay", headers=pax["auth"], json={"method": "cash"})
    assert r.status_code == 200 and r.json()["status"] == "paid"
    with Session(engine) as s:
        assert s.get(Payment, payment_id).status == "canceled"   # свой безнал погасили

    # По старой ссылке всё-таки заплатили.
    assert client.post("/payments/yookassa/webhook",
                       json={"object": {"id": "pid_late_1"}}).status_code == 200

    with Session(engine) as s:
        pay = s.get(Payment, payment_id)
        assert pay.status == "refund_due", "деньги пришли, а следа о долге нет"
        notes = s.exec(select(Notification).where(Notification.user_id == pax["id"])).all()
        tickets = s.exec(select(SupportTicket).where(SupportTicket.user_id == pax["id"])).all()

    assert notes, "человеку не сказали, что с его деньгами"
    n = notes[-1]
    assert n.title_ru and n.title_ba and n.title_ru != n.title_ba   # два языка, не склейка
    assert "500" in n.body_ru
    assert len(tickets) == 1, "нет нити, по которой человек может спросить про возврат"
    assert n.ref_kind == "support" and n.ref_id == tickets[0].id    # тап ведёт в эту нить
    assert yookassa_says_paid, "админ не узнал, что нужно вернуть деньги"

    # Человек видит тред у себя, и там уже есть объяснение от поддержки.
    thread = client.get(f"/support/tickets/{tickets[0].id}", headers=pax["auth"])
    assert thread.status_code == 200, thread.text
    msgs = thread.json()["messages"]
    assert msgs and any("верн" in (m["body"] or "").lower() for m in msgs), msgs


def test_повторный_вебхук_не_плодит_обращения(client, user_factory, yookassa_says_paid):
    """ЮKassa повторяет вебхуки. Второй раз — ни второго тикета, ни второго обещания."""
    pax, _drv, order_id, payment_id = _done_order_with_stale_link(client, user_factory, "pid_late_2")
    client.post(f"/instant/orders/{order_id}/pay", headers=pax["auth"], json={"method": "cash"})

    for _ in range(3):
        assert client.post("/payments/yookassa/webhook",
                           json={"object": {"id": "pid_late_2"}}).status_code == 200

    with Session(engine) as s:
        tickets = s.exec(select(SupportTicket).where(SupportTicket.user_id == pax["id"])).all()
        notes = s.exec(select(Notification).where(Notification.user_id == pax["id"])).all()
        assert s.get(Payment, payment_id).status == "refund_due"
    assert len(tickets) == 1, "каждый повтор вебхука заводил новое обращение"
    assert len(notes) == 1, "человеку три раза пообещали вернуть одни и те же деньги"


def test_обычная_оплата_картой_по_прежнему_доходит_до_водителя(client, user_factory, yookassa_says_paid):
    """Регресс: живой платёж (pending) вебхук как применял, так и применяет."""
    pax, drv, _order_id, payment_id = _done_order_with_stale_link(client, user_factory, "pid_ok_1")

    assert client.post("/payments/yookassa/webhook",
                       json={"object": {"id": "pid_ok_1"}}).status_code == 200

    with Session(engine) as s:
        assert s.get(Payment, payment_id).status == "succeeded"
    bal = client.get("/wallet/balance", headers=drv["auth"]).json()
    assert bal["balance_kop"] > 0, "водителю не начислили за оплаченную картой поездку"


def test_вторая_дверь_проверка_статуса_тоже_замечает_поздние_деньги(client, user_factory,
                                                                    yookassa_says_paid):
    """Вебхук может не дойти. Тогда деньги должна заметить перепроверка статуса — второй путь.

    Раньше она смотрела только на «ещё не оплачен» и мимо отменённого платежа проходила молча:
    правило стояло на одной двери из двух.
    """
    pax, _drv, order_id, payment_id = _done_order_with_stale_link(client, user_factory, "pid_late_3")
    client.post(f"/instant/orders/{order_id}/pay", headers=pax["auth"], json={"method": "cash"})

    # Вебхука НЕ было. Человек просто вернулся в приложение, и оно спросило статус платежа.
    r = client.get(f"/payments/{payment_id}/status", headers=pax["auth"])
    assert r.status_code == 200, r.text

    with Session(engine) as s:
        assert s.get(Payment, payment_id).status == "refund_due"
        tickets = s.exec(select(SupportTicket).where(SupportTicket.user_id == pax["id"])).all()
    assert len(tickets) == 1
    assert yookassa_says_paid, "админ не узнал про возврат по второму пути"
