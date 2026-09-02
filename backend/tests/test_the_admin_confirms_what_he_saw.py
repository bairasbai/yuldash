# -*- coding: utf-8 -*-
"""Админ подтверждает ТО, ЧТО ВИДЕЛ, а не то, что успело набежать (волна 220).

Комиссию водитель платит переводом по СБП «на доверии»: перевёл на номер → нажал «Я оплатил»
→ долг ушёл в `pending` → админ сверил поступление и подтвердил. Вся выручка платформы держится
на этом одном экране.

`admin_confirm` брал любой `debt_id` из строки списка и закрывал **весь текущий pending этого
водителя**. Между тем, как админ увидел строку, и тем, как он нажал кнопку, проходит время —
и за это время водитель может заявить оплату ещё раз, по новым поездкам.

    10:00  админ открыл список: «Ильдар · 500 ₽»
    10:30  Ильдар откатал ещё, заявил оплату на 900 ₽
    10:35  админ вернулся к открытой странице и нажал «Подтвердить»
           → закрыто 1400 ₽, получено 500 ₽

Экран админа обновляется не сам: он открыл его утром, а нажимает днём. Это не гонка на
миллисекундах — это чашка чая.

Починка — обычная оптимистичная блокировка: админ присылает сумму, которую видит. Не сошлась
с текущей — отказ «сумма изменилась, обнови список», и ничего не закрывается.

Второй путь — для клиента, который суммы не шлёт (веб-админка, её делает другой ИИ). Ломать
его нельзя: Александр пользуется ей каждый день. Там батч сужается по времени заявки —
закрывается ровно то нажатие «Я оплатил», на строку которого админ кликнул, а заявленное
позже остаётся ждать. Клиент делает меньше, а не падает.
"""
from datetime import timedelta

import pytest
from sqlmodel import Session, select

from app import debt as debt_mod
from app.db import engine
from app.models import CommissionDebt, DebtStatus, UserRole
from app.timeutil import utcnow


def _сессия() -> Session:
    return Session(engine, expire_on_commit=False)


def _долг(driver_id: int, kop: int, week: str = "2026-W35") -> int:
    with _сессия() as s:
        d = CommissionDebt(driver_id=driver_id, amount_kop=kop, week=week,
                           status=DebtStatus.unpaid, created_at=utcnow())
        s.add(d)
        s.commit()
        s.refresh(d)
        return d.id


def _строка_админа(client, admin) -> dict:
    r = client.get("/admin/debts", headers=admin["auth"])
    assert r.status_code == 200, r.text
    строки = r.json()
    assert строки, "долг не доехал до списка админа"
    return строки[0]


def test_the_admin_sees_the_declared_sum(client, user_factory):
    """Опора: заявленный долг виден админу одной строкой с суммой."""
    d = user_factory("ЗаявилОплату", role=UserRole.driver)
    admin = user_factory("АдминДолгов", role=UserRole.admin)
    _долг(d["id"], 50_000)
    with _сессия() as s:
        assert debt_mod.declare_paid(s, d["id"]) == 50_000
    строка = _строка_админа(client, admin)
    assert строка["driver_id"] == d["id"]
    assert строка["amount_kop"] == 50_000


def test_confirming_a_stale_row_does_not_forgive_new_debt(client, user_factory):
    """Долг, набежавший ПОСЛЕ того как админ увидел строку, подтверждением не закрывается.

    Это и есть находка: между «увидел» и «нажал» водитель успел заявить ещё одну оплату,
    и одно нажатие закрывало обе — платформа дарила деньги, которых не получала.
    """
    d = user_factory("ДобавилПослеПросмотра", role=UserRole.driver)
    admin = user_factory("АдминСЧашкойЧая", role=UserRole.admin)
    _долг(d["id"], 50_000, week="2026-W35")
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])

    строка = _строка_админа(client, admin)          # админ видит 500 ₽
    assert строка["amount_kop"] == 50_000

    # Пока админ пил чай, водитель откатал ещё и заявил оплату по новым поездкам.
    _долг(d["id"], 90_000, week="2026-W36")
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])

    r = client.post(f"/admin/debts/{строка['debt_id']}/confirm", headers=admin["auth"],
                    json={"amount_kop": строка["amount_kop"]})
    assert r.status_code == 409, (
        f"подтверждение закрыло больше, чем админ видел: {r.text}"
    )

    with _сессия() as s:
        всё = s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == d["id"])).all()
        assert all(x.status == DebtStatus.pending for x in всё), (
            "долг закрылся, хотя сумма не сошлась"
        )


def test_confirming_what_you_see_still_works(client, user_factory):
    """Обычный случай не сломан: сумма сошлась — долг закрыт, блок снят."""
    d = user_factory("ОбычноеПодтверждение", role=UserRole.driver)
    admin = user_factory("АдминОбычный", role=UserRole.admin)
    _долг(d["id"], 50_000)
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])
    строка = _строка_админа(client, admin)

    r = client.post(f"/admin/debts/{строка['debt_id']}/confirm", headers=admin["auth"],
                    json={"amount_kop": строка["amount_kop"]})
    assert r.status_code == 200, r.text
    with _сессия() as s:
        всё = s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == d["id"])).all()
        assert all(x.status == DebtStatus.paid for x in всё)
        assert debt_mod.taxi_block_reason(s, d["id"]) is None


def test_an_old_admin_screen_can_still_confirm_after_a_refresh(client, user_factory):
    """Отказ не тупик: обновил список — новая сумма, подтверждение проходит."""
    d = user_factory("ПослеОбновления", role=UserRole.driver)
    admin = user_factory("АдминОбновил", role=UserRole.admin)
    _долг(d["id"], 50_000)
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])
    старая = _строка_админа(client, admin)

    _долг(d["id"], 90_000, week="2026-W36")
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])

    assert client.post(f"/admin/debts/{старая['debt_id']}/confirm", headers=admin["auth"],
                       json={"amount_kop": старая["amount_kop"]}).status_code == 409

    свежая = _строка_админа(client, admin)          # обновил страницу
    assert свежая["amount_kop"] == 140_000
    r = client.post(f"/admin/debts/{свежая['debt_id']}/confirm", headers=admin["auth"],
                    json={"amount_kop": свежая["amount_kop"]})
    assert r.status_code == 200, r.text


def test_rejecting_a_stale_row_is_guarded_too(client, user_factory):
    """Отказ бьёт по всему батчу — значит и он обязан сверять сумму.

    Иначе честный водитель, чей ПЕРВЫЙ перевод дошёл, теряет его вместе со вторым: обе
    заявки возвращаются в неоплаченные, а счётчик обещаний уже потрачен.
    """
    d = user_factory("ЧестныйВодитель", role=UserRole.driver)
    admin = user_factory("АдминОтклонил", role=UserRole.admin)
    _долг(d["id"], 50_000)
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])
    строка = _строка_админа(client, admin)

    _долг(d["id"], 90_000, week="2026-W36")
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])

    r = client.post(f"/admin/debts/{строка['debt_id']}/reject", headers=admin["auth"],
                    json={"amount_kop": строка["amount_kop"]})
    assert r.status_code == 409, f"отказ снёс больше, чем админ видел: {r.text}"


def test_an_old_admin_client_closes_only_the_row_it_clicked(client, user_factory):
    """Старая админка (без суммы) закрывает ТОЛЬКО ту заявку, на которую нажали.

    Ломать чужой клиент ради защиты нельзя — веб-админкой Александр пользуется каждый день.
    Поэтому без суммы батч сужается по времени заявки: долг, заявленный ПОЗЖЕ, не закрывается.
    Клиент просто делает меньше, а не падает.
    """
    d = user_factory("СтараяАдминка", role=UserRole.driver)
    admin = user_factory("АдминБезСуммы", role=UserRole.admin)
    _долг(d["id"], 50_000)
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])
    строка = _строка_админа(client, admin)

    # Водитель заявил оплату ещё раз, по новым поездкам.
    новый = _долг(d["id"], 90_000, week="2026-W36")
    with _сессия() as s:
        debt_mod.declare_paid(s, d["id"])

    r = client.post(f"/admin/debts/{строка['debt_id']}/confirm", headers=admin["auth"], json={})
    assert r.status_code == 200, r.text
    with _сессия() as s:
        поздний = s.get(CommissionDebt, новый)
        assert поздний.status == DebtStatus.pending, (
            "закрылся долг, заявленный ПОСЛЕ того, как админ открыл список"
        )
