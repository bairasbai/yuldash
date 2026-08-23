"""Водитель заплатил — и его отключали за то, что Александр не нажал кнопку (волна 176).

Оплата комиссии идёт «на доверии»: водитель переводит деньги по СБП и жмёт «Я оплатил».
Долг уходит в ожидание, такси работает дальше — верим слову. Александр видит перевод в банке
и подтверждает. Не подтвердил за три дня — доверие кончается, такси закрывается.

Срок нужен, и он честный: без него выгоднее было бы раз в неделю жать кнопку и не платить
вовсе (эту дыру закрыли аудитом 2026-08-07). Но у срока оказался второй конец.

**Проба.** Ильшат перевёл деньги в пятницу. Александр — один человек, четыре-пять дней
в неделю в командировках. Через три дня:

- такси закрыто;
- уведомлений ни одного — ни что срок идёт, ни что отключили;
- в кабинете написано «Оплати долг сервису, чтобы снова возить такси» — человеку,
  который уже заплатил;
- повторное «Я оплатил» не делает ничего: заявлять нечего, долг и так в ожидании.

Выхода нет. Человек теряет рабочие дни за чужое молчание и решает, что его обманули, —
а доверие и есть то единственное, на чём вся эта схема держится.

Теперь обе стороны предупреждают за сутки: водителя — что перевод пока не подтверждён
и что с этим делать, Александра — что заявки ждут. Срок при этом не сдвинулся ни на час:
лазейка «не платить вовсе» остаётся закрытой.
"""
from __future__ import annotations

from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import debt as debt_mod
from app import declare_remind, taxi_worker
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, Notification, User, UserRole,
)
from app.timeutil import utcnow

_номер = {"n": 0}


def _долг(driver_id: int, amount_kop: int = 50000) -> int:
    """Долг за настоящий завершённый заказ — у долга внешний ключ на заказ."""
    _номер["n"] += 1
    n = _номер["n"]
    with Session(engine) as s:
        pax = User(phone=f"tg-adm176-{n}", name="Пассажир", telegram_id=f"adm176x{n}")
        s.add(pax)
        s.commit()
        s.refresh(pax)
        o = InstantOrder(passenger_id=pax.id, driver_id=driver_id,
                         status=InstantOrderStatus.done, price_estimate=500, price_final=500)
        s.add(o)
        s.commit()
        s.refresh(o)
        d = CommissionDebt(driver_id=driver_id, order_id=o.id, amount_kop=amount_kop,
                           status=DebtStatus.unpaid, created_at=utcnow(),
                           due_at=utcnow() + timedelta(days=7))
        s.add(d)
        s.commit()
        s.refresh(d)
        return d.id


def _состарить_заявку(driver_id: int, дней: float) -> None:
    """Сдвинуть момент «я оплатил» назад — как будто Александр молчит столько дней."""
    with Session(engine) as s:
        for d in s.exec(select(CommissionDebt).where(
                CommissionDebt.driver_id == driver_id)).all():
            d.paid_declared_at = utcnow() - timedelta(days=дней)
            s.add(d)
        s.commit()


def _письма(user_id: int) -> list[Notification]:
    with Session(engine) as s:
        return list(s.exec(select(Notification).where(
            Notification.user_id == user_id)).all())


@pytest.fixture
def ильшат(client, user_factory):
    """Водитель, который честно перевёл деньги и нажал «Я оплатил»."""
    водитель = user_factory("ИльшатЗаплатил", role=UserRole.driver)
    _долг(водитель["id"])
    assert client.post("/driver/debt/paid", headers=водитель["auth"]).status_code == 200
    return водитель


def test_водителя_предупреждают_до_отключения(client, ильшат):
    """Главное: человек узнаёт о риске ЗАРАНЕЕ, а не по факту закрытого такси."""
    _состарить_заявку(ильшат["id"], 2.5)          # доверие кончается через полдня

    with Session(engine) as s:
        declare_remind.remind_pending_declares(s)

    письма = _письма(ильшат["id"])
    assert письма, (
        "водитель перевёл деньги, подтверждения нет, завтра его отключат — и он об этом "
        "не знает. Узнает утром, когда такси уже не работает"
    )
    тексты = " ".join((n.body_ru or "") for n in письма)
    assert "поддержку" in тексты, (
        f"предупредили, но не сказали, что делать: {тексты[:200]}"
    )


def test_предупреждение_приходит_один_раз(client, ильшат):
    """Обратная сторона: каждую ночь одно и то же — спам, который перестают читать."""
    _состарить_заявку(ильшат["id"], 2.5)

    with Session(engine) as s:
        declare_remind.remind_pending_declares(s)
        сначала = len(_письма(ильшат["id"]))
        declare_remind.remind_pending_declares(s)      # робот прошёл ещё раз

    assert len(_письма(ильшат["id"])) == сначала, "водителю пишут одно и то же каждую ночь"


def test_свежую_заявку_не_дёргают(client, ильшат):
    """Обратная сторона: перевод заявлен час назад — тревожить человека не за что."""
    _состарить_заявку(ильшат["id"], 0.05)

    with Session(engine) as s:
        declare_remind.remind_pending_declares(s)

    assert not _письма(ильшат["id"]), (
        "человек нажал «Я оплатил» час назад и уже получил тревожное письмо"
    )


def test_срок_доверия_не_сдвинулся(client, ильшат):
    """Смысл всей правки: предупредить — не значит простить. Лазейка остаётся закрытой."""
    _состарить_заявку(ильшат["id"], 2.5)
    with Session(engine) as s:
        declare_remind.remind_pending_declares(s)

    _состарить_заявку(ильшат["id"], 4)            # доверие кончилось
    with Session(engine) as s:
        причина = debt_mod.taxi_block_reason(s, ильшат["id"])

    assert причина == "declare_stale", (
        f"предупреждение отменило срок — теперь можно жать кнопку вместо оплаты: {причина}"
    )


def test_попутка_у_него_работает(client, ильшат):
    """Контроль: долг закрывает такси, но не выкидывает человека из попутки."""
    _состарить_заявку(ильшат["id"], 4)

    with Session(engine) as s:
        assert debt_mod.taxi_block_reason(s, ильшат["id"]) == "declare_stale"

    ответ = client.post("/rides", headers=ильшат["auth"], json={
        "from_city": "Сибай", "to_city": "Уфа", "seats": 3, "price": 700,
        "depart_at": (utcnow() + timedelta(days=1)).isoformat(),
    })
    assert ответ.status_code == 200, (
        f"человека выкинули и из попутки — из-за неподтверждённого перевода: {ответ.text[:150]}"
    )


def test_ночной_робот_зовёт_эту_задачу(client, ильшат):
    """Задача бесполезна, если её никто не запускает — проверяем сам вызов."""
    _состарить_заявку(ильшат["id"], 2.5)

    with Session(engine) as s:
        сводка = taxi_worker.run_once(s)

    assert "declares_reminded" in сводка, (
        f"ночной робот не знает про горящие заявки об оплате: {sorted(сводка)}"
    )
    assert _письма(ильшат["id"]), "робот прогнался, а водителя никто не предупредил"
