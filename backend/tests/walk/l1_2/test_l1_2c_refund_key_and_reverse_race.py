"""leaf-1.2c — две денежные дыры вокруг оплаты долга такси картой (до включения ЮKassa).

**Дыра 1 — ключ возврата.** Когда банк подтверждает счёт за долг, а часть долгов из счёта уже
закрыта другим путём (простил админ, подтвердили СБП, «пассажир не заплатил»), `_activate_payment`
возвращает их сумму в кошелёк через `refund_commission_to_wallet`. Ключ идемпотентности там
строился из заказа: `refund:order:N`, а без заказа — `refund:parcel:None`.

  K1. Пассажир удалил аккаунт → у долга водителя `order_id` обнулён (account.py). Ключ становится
      ОБЩИМ для всех таких долгов всех водителей: `refund:parcel:None`. Первый возврат проходит,
      каждый следующий — у ЛЮБОГО водителя — молча глотает уникальный индекс `uq_ledgerentry_refund`.
  K2. Ключ `refund:order:N` делят два разных возврата: «пассажир не заплатил» (разбор жалобы) и
      «счёт картой списал долг, который уже был закрыт». Это два разных платежа водителя — а второй
      возврат молча глотался первым.
  Правка: возврат по карте — под ключом платежа `refund:taxi_debt:{payment.id}:{debt.id}`.

**Дыра 2 — обратная гонка.** `admin_forgive` / `admin_confirm` / `admin_reject` читали долг как
«ещё не оплачен» и потом перезаписывали его вслепую. Если между чтением и записью вебхук карты
закрыл долг, оплата картой затиралась («Списан админом», или долг вообще возвращался в
неоплаченные) — деньги водителя пропадали. Правка: условие «ещё не закрыт» прямо в UPDATE.

Тесты гонки детерминированы: перед ПЕРВЫМ UPDATE долга со стороны админа вебхук карты успевает
закрыть долг в другом соединении (`_card_closes_first`). Так ведут себя обе базы — SQLite и
PostgreSQL. Настоящая блокировка строки на Postgres — в `test_l1_2c_reverse_race_pg.py`.
"""
import contextlib

import pytest
from sqlalchemy import event
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from app import debt as debt_mod
from app.account import delete_user_account
from app.db import engine
from app.models import CommissionDebt, DebtStatus, LedgerEntry, LedgerKind, User, UserRole
from app.timeutil import utcnow

from test_l1_2b_taxi_debt_snapshot import (_activate, _debt, _driver_with_debts, _ledger_adj_entries,
                                           _snapshot_payment, _wallet_balance)


def _forgive_directly(debt_id: int, note: str = "Списан админом: тест") -> None:
    with Session(engine) as s:
        d = s.get(CommissionDebt, debt_id)
        d.status = DebtStatus.paid
        d.confirmed_at = utcnow()
        d.note = note
        s.add(d)
        s.commit()


def _passenger_of(debt_id: int) -> int:
    from app.models import InstantOrder
    with Session(engine) as s:
        return s.get(InstantOrder, s.get(CommissionDebt, debt_id).order_id).passenger_id


def _delete_account(user_id: int) -> None:
    with Session(engine) as s:
        delete_user_account(s, s.get(User, user_id))


# ───────────────────────────── Дыра 1: ключ возврата ─────────────────────────────

def test_refunds_of_debts_orphaned_by_passenger_deletion_are_not_swallowed_across_drivers(client, user_factory):
    """K1 — два РАЗНЫХ водителя, у обоих пассажир удалил аккаунт: оба получают свой возврат."""
    returned, keys, expected_keys = [], [], []
    for name in ("L12cOrphanA", "L12cOrphanB"):
        drv, (d1, d2) = _driver_with_debts(user_factory, name, [10_000, 15_000])
        pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))
        _forgive_directly(d2)                         # пока счёт висел у банка, d2 простили
        _delete_account(_passenger_of(d2))            # настоящий путь удаления: order_id → NULL
        assert _debt(d2).order_id is None

        payment = _activate(pay_id)

        assert payment.status == "succeeded"
        returned.append(_wallet_balance(drv["id"]))
        keys.append([e.ext_id for e in _ledger_adj_entries(drv["id"])])
        expected_keys.append([f"refund:taxi_debt:{pay_id}:{d2}"])

    assert returned == [15_000, 15_000], (
        "второму водителю возврат не пришёл: ключ без заказа был общим на всех (refund:parcel:None)"
    )
    assert keys == expected_keys


def test_card_refund_is_not_swallowed_by_an_earlier_unpaid_report_refund_on_the_same_order(client, user_factory):
    """K2 — один заказ, два разных платежа водителя (СБП и карта), два возврата."""
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12cSharedKey", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))
    # Водитель перевёл 150 ₽ за d2 по СБП, админ подтвердил — пока счёт картой ещё висел у банка.
    with Session(engine) as s:
        d = s.get(CommissionDebt, d2)
        d.status = DebtStatus.paid
        d.confirmed_at = utcnow()
        s.add(d); s.commit()
        order_id = d.order_id
    # Разбор «пассажир не заплатил» по этому заказу: СБП-перевод возвращается в кошелёк.
    with Session(engine) as s:
        assert debt_mod.void_debt_for_order(s, order_id) == "refunded"
        s.commit()
    assert _wallet_balance(drv["id"]) == 15_000

    payment = _activate(pay_id)                       # банк подтвердил: картой списано ещё 250 ₽

    assert payment.status == "succeeded"
    assert _wallet_balance(drv["id"]) == 30_000, (
        "за d2 водитель заплатил дважды (СБП и карта), а вернули один раз: "
        "возврат по карте проглотил ключ refund:order:N разбора жалобы"
    )
    keys = sorted(e.ext_id for e in _ledger_adj_entries(drv["id"]))
    assert keys == sorted([f"refund:order:{order_id}", f"refund:taxi_debt:{pay_id}:{d2}"])


def test_unpaid_report_after_card_refund_still_refunds_the_other_payment(client, user_factory):
    """K2, обратный порядок: сначала возврат по карте, потом разбор жалобы — тоже два возврата."""
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12cSharedKeyRev", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))
    with Session(engine) as s:
        d = s.get(CommissionDebt, d2)
        d.status = DebtStatus.paid                     # СБП подтверждён, пока висел счёт
        d.confirmed_at = utcnow()
        s.add(d); s.commit()
        order_id = d.order_id
    _activate(pay_id)
    assert _wallet_balance(drv["id"]) == 15_000

    with Session(engine) as s:
        assert debt_mod.void_debt_for_order(s, order_id) == "refunded"
        s.commit()

    assert _wallet_balance(drv["id"]) == 30_000


def test_taxi_debt_refund_key_is_guarded_by_the_unique_refund_index(client, user_factory):
    """Новый ключ обязан попадать под барьер БД `uq_ledgerentry_refund`, а не только под
    проверку «уже возвращали?» в коде (её одну настоящая гонка обходит)."""
    drv = user_factory("L12cIndex", role=UserRole.driver)
    key = debt_mod.taxi_debt_refund_ext_id(777_001, 888_001)
    assert key == "refund:taxi_debt:777001:888001"
    with Session(engine) as s:
        assert debt_mod.refund_commission_to_wallet(s, drv["id"], 5_000, ext_id=key) is not None
        s.commit()
    with Session(engine) as s:
        assert debt_mod.refund_commission_to_wallet(s, drv["id"], 5_000, ext_id=key) is None
        s.commit()
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=5_000, ext_id=key))
        with pytest.raises(IntegrityError):
            s.commit()
    assert _wallet_balance(drv["id"]) == 5_000


def test_explicit_refund_key_outside_the_guarded_namespace_is_refused(client, user_factory):
    """Ключ без префикса `refund:` индекс не покрывает — такой ключ хуже, чем никакого."""
    drv = user_factory("L12cBadKey", role=UserRole.driver)
    with Session(engine) as s:
        with pytest.raises(ValueError):
            debt_mod.refund_commission_to_wallet(s, drv["id"], 5_000, ext_id="taxi_debt:1:2")


def test_refund_never_pays_out_the_amount_of_someone_elses_debt(client, user_factory):
    """Снимок счёта с чужим ID долга (повреждённые данные) не превращается в возврат чужих денег."""
    drv, (d1,) = _driver_with_debts(user_factory, "L12cOwner", [10_000])
    _, (foreign,) = _driver_with_debts(user_factory, "L12cStranger", [40_000])
    _forgive_directly(foreign)
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, foreign)))

    payment = _activate(pay_id)

    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _wallet_balance(drv["id"]) == 0, "сумма чужого долга ушла в кошелёк этого водителя"


@pytest.mark.parametrize("lang", ["ru", "ba"])
def test_card_refund_line_in_wallet_history_speaks_the_drivers_language(client, user_factory, lang):
    ru = debt_mod.TAXI_DEBT_REFUND_NOTE
    expected = ru if lang == "ru" else debt_mod.LEDGER_NOTE_BA[ru]
    drv, (d1, d2) = _driver_with_debts(user_factory, f"L12cNote{lang}", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))
    _forgive_directly(d2)
    _activate(pay_id)
    with Session(engine) as s:
        u = s.get(User, drv["id"])
        u.language = lang
        s.add(u); s.commit()

    rows = client.get("/wallet/ledger", headers=drv["auth"]).json()

    assert [r["note"] for r in rows if r["amount_kop"] == 15_000] == [expected]
    assert expected.strip()


def test_every_refund_note_has_a_bashkir_twin():
    """Подписи возвратов — оба языка. Пустой или одинаковый с русским перевод — это не перевод."""
    for ru in (debt_mod.REFUND_NOTE, debt_mod.TAXI_DEBT_REFUND_NOTE):
        ba = debt_mod.LEDGER_NOTE_BA.get(ru, "")
        assert ba.strip() and ba != ru
        assert debt_mod.ledger_note_text(ru, "ba") == ba
        assert debt_mod.ledger_note_text(ru, "ru") == ru
    # Чужая (например, админская) пометка остаётся как есть — переводить её нечем.
    assert debt_mod.ledger_note_text("Списан админом: тест", "ba") == "Списан админом: тест"
    assert debt_mod.ledger_note_text(None, "ba") is None


# ───────────────────────────── Дыра 2: обратная гонка ─────────────────────────────

@contextlib.contextmanager
def _card_closes_first(pay_id: int):
    """Перед ПЕРВЫМ UPDATE строки долга со стороны админа банк подтверждает счёт и вебхук карты
    закрывает долг в ДРУГОМ соединении. Это ровно окно «админ прочитал — карта закрыла — админ
    записал». `box["fired"]` — доказательство, что окно действительно открывалось."""
    box = {"fired": False}

    def before(conn, cursor, statement, parameters, context, executemany):
        if box["fired"] or not statement.lstrip().upper().startswith("UPDATE COMMISSIONDEBT"):
            return
        box["fired"] = True
        box["payment"] = _activate(pay_id)

    event.listen(engine, "before_cursor_execute", before)
    try:
        yield box
    finally:
        event.remove(engine, "before_cursor_execute", before)


def _admin(user_factory, name):
    return user_factory(name, role=UserRole.admin)


def _declare(driver_id: int) -> None:
    with Session(engine) as s:
        assert debt_mod.declare_paid(s, driver_id) > 0


def _notification_titles(user_id: int) -> list[str]:
    from app.models import Notification
    with Session(engine) as s:
        return [n.title_ru for n in s.exec(select(Notification).where(Notification.user_id == user_id)).all()]


def test_forgive_does_not_overwrite_a_debt_the_card_closed_a_moment_earlier(client, user_factory):
    admin = _admin(user_factory, "L12cAdmF")
    drv, (d1,) = _driver_with_debts(user_factory, "L12cRaceForgive", [10_000])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))

    with _card_closes_first(pay_id) as box:
        r = client.post(f"/admin/debts/{d1}/forgive", headers=admin["auth"], json={"reason": "спор"})

    assert box["fired"], "окно гонки не открылось — тест ничего не доказал"
    assert box["payment"].status == "succeeded"
    assert r.status_code == 200
    assert r.json().get("already") is True, "админу сказали «списано», хотя долг уже оплачен картой"
    after = _debt(d1)
    assert after.status == DebtStatus.paid
    assert not (after.note or "").startswith("Списан админом"), (
        "оплата картой затёрта пометкой «Списан админом» — деньги водителя потеряны"
    )
    assert "Долг списан" not in _notification_titles(drv["id"])


def test_confirm_does_not_take_credit_for_a_batch_the_card_closed_a_moment_earlier(client, user_factory):
    admin = _admin(user_factory, "L12cAdmC")
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12cRaceConfirm", [10_000, 15_000])
    _declare(drv["id"])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))

    with _card_closes_first(pay_id) as box:
        r = client.post(f"/admin/debts/{d1}/confirm", headers=admin["auth"], json={"amount_kop": 25_000})

    assert box["fired"], "окно гонки не открылось — тест ничего не доказал"
    assert r.status_code == 200
    body = r.json()
    assert body.get("already") is True and body.get("paid_kop") == 0, (
        "админ «подтвердил» перевод по долгам, которые уже закрыла карта: двойная оплата не видна"
    )
    assert all(_debt(d).status == DebtStatus.paid for d in (d1, d2))
    assert "Долг подтверждён" not in _notification_titles(drv["id"])


def test_confirm_refuses_and_changes_nothing_when_the_card_closed_part_of_the_batch(client, user_factory):
    admin = _admin(user_factory, "L12cAdmCP")
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12cRaceConfirmPart", [10_000, 15_000])
    _declare(drv["id"])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))

    with _card_closes_first(pay_id) as box:
        r = client.post(f"/admin/debts/{d2}/confirm", headers=admin["auth"], json={"amount_kop": 25_000})

    assert box["fired"]
    assert r.status_code == 409, "половину батча закрыла карта — сумма на экране админа уже неверна"
    assert _debt(d1).status == DebtStatus.paid          # закрыт картой
    assert _debt(d2).status == DebtStatus.pending, "при отказе батч не трогаем вовсе"


def test_reject_does_not_reopen_a_batch_the_card_closed_a_moment_earlier(client, user_factory):
    admin = _admin(user_factory, "L12cAdmR")
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12cRaceReject", [10_000, 15_000])
    _declare(drv["id"])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))

    with _card_closes_first(pay_id) as box:
        r = client.post(f"/admin/debts/{d1}/reject", headers=admin["auth"], json={"amount_kop": 25_000})

    assert box["fired"]
    assert r.status_code == 200
    assert r.json().get("already") is True
    assert [_debt(d).status for d in (d1, d2)] == [DebtStatus.paid, DebtStatus.paid], (
        "оплаченный картой долг снова числится неоплаченным — водитель заплатит второй раз"
    )
    assert "Оплата не найдена" not in _notification_titles(drv["id"])


def test_reject_refuses_and_changes_nothing_when_the_card_closed_part_of_the_batch(client, user_factory):
    admin = _admin(user_factory, "L12cAdmRP")
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12cRaceRejectPart", [10_000, 15_000])
    _declare(drv["id"])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))

    with _card_closes_first(pay_id) as box:
        r = client.post(f"/admin/debts/{d2}/reject", headers=admin["auth"], json={"amount_kop": 25_000})

    assert box["fired"]
    assert r.status_code == 409
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(d2).status == DebtStatus.pending


def test_admin_actions_without_a_race_still_work(client, user_factory):
    """Регресс-страховка: обычные «простить / подтвердить / отклонить» по-прежнему меняют долг."""
    admin = _admin(user_factory, "L12cAdmOK")
    drv, (d1,) = _driver_with_debts(user_factory, "L12cNoRaceF", [10_000])
    r = client.post(f"/admin/debts/{d1}/forgive", headers=admin["auth"], json={"reason": "спор"})
    assert r.status_code == 200 and r.json()["forgiven_kop"] == 10_000 and not r.json().get("already")
    assert _debt(d1).note == "Списан админом: спор"
    r = client.post(f"/admin/debts/{d1}/forgive", headers=admin["auth"])
    assert r.json().get("already") is True

    drv2, (c1, c2) = _driver_with_debts(user_factory, "L12cNoRaceC", [10_000, 15_000])
    _declare(drv2["id"])
    r = client.post(f"/admin/debts/{c1}/confirm", headers=admin["auth"], json={"amount_kop": 25_000})
    assert r.status_code == 200 and r.json()["paid_kop"] == 25_000 and not r.json().get("already")
    assert [_debt(d).status for d in (c1, c2)] == [DebtStatus.paid, DebtStatus.paid]

    drv3, (j1, j2) = _driver_with_debts(user_factory, "L12cNoRaceR", [10_000, 15_000])
    _declare(drv3["id"])
    r = client.post(f"/admin/debts/{j1}/reject", headers=admin["auth"], json={"amount_kop": 25_000})
    assert r.status_code == 200 and r.json()["unpaid_kop"] == 25_000 and not r.json().get("already")
    with Session(engine) as s:
        rows = [s.get(CommissionDebt, d) for d in (j1, j2)]
        assert [d.status for d in rows] == [DebtStatus.unpaid, DebtStatus.unpaid]
        assert all(d.paid_declared_at is None for d in rows)
