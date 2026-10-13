"""leaf-1.2d — та же обратная гонка, что закрыта в 1.2c для «простить / подтвердить / отклонить»,
в трёх оставшихся местах (docs/tasks.md, «Осталось» под листом 1.2c).

Везде одна ошибка: долг читали как «ещё не оплачен», а потом записывали вслепую. Если между чтением
и записью долг закрыли другим путём (вебхук карты, админ, кошелёк), запись затирала это закрытие:

  D. «Я оплатил» (`declare_paid`): оплаченный картой долг снова становился pending — висел на
     подтверждении, а «Отклонить» у админа возвращал его в неоплаченные: водитель платил второй раз.
  V. Разбор «пассажир не заплатил» и онлайн-оплата заказа (`void_debt_for_order`): оплата картой
     перезаписывалась пометкой «Списан: снят по разбору» — и возврат водителю уже не полагался.
     Или админское «Списан админом: <причина>» — подменялось безымянным «снят по разбору».
  L. Старый счёт картой без снимка состава (`Payment.tier` пуст, до листа 1.1): если долг из счёта
     закрыли в окне «прочитали — записали», карта молча забирала деньги второй раз, без возврата.

Окно гонки детерминировано тем же приёмом, что в 1.2c: перед ПЕРВЫМ UPDATE строки долга другой
путь успевает закрыть долг в ДРУГОМ соединении (`_in_the_window`). Настоящая блокировка строки
PostgreSQL — в `test_l1_2d_reverse_race_pg.py`.
"""
import contextlib
from datetime import timedelta

from sqlalchemy import event
from sqlmodel import Session, select

from app import debt as debt_mod
from app.db import engine
from app.models import CommissionDebt, DebtStatus, LedgerEntry, LedgerKind, Payment
from app.timeutil import utcnow

from test_l1_2b_taxi_debt_snapshot import (_activate, _debt, _driver_with_debts, _ledger_adj_entries,
                                           _snapshot_payment, _wallet_balance)


@contextlib.contextmanager
def _in_the_window(action):
    """Перед ПЕРВЫМ UPDATE строки долга `action()` закрывает долг в ДРУГОМ соединении — это ровно
    окно «прочитал — другой закрыл — записал». `box["fired"]` — доказательство, что окно открывалось."""
    box = {"fired": False}

    def before(conn, cursor, statement, parameters, context, executemany):
        if box["fired"] or not statement.lstrip().upper().startswith("UPDATE COMMISSIONDEBT"):
            return
        box["fired"] = True
        box["result"] = action()

    event.listen(engine, "before_cursor_execute", before)
    try:
        yield box
    finally:
        event.remove(engine, "before_cursor_execute", before)


def _forgive_directly(debt_id: int, note: str = "Списан админом: спор") -> None:
    with Session(engine) as s:
        d = s.get(CommissionDebt, debt_id)
        d.status = DebtStatus.paid
        d.confirmed_at = utcnow()
        d.note = note
        s.add(d)
        s.commit()


def _confirm_directly(debt_id: int) -> None:
    """Админ подтвердил СБП-перевод по pending-долгу (paid, без пометки)."""
    with Session(engine) as s:
        d = s.get(CommissionDebt, debt_id)
        d.status = DebtStatus.paid
        d.confirmed_at = utcnow()
        s.add(d)
        s.commit()


def _order_of(debt_id: int) -> int:
    return _debt(debt_id).order_id


def _legacy_payment(driver_id: int, amount_kop: int) -> int:
    """Счёт картой ДО листа 1.1: состава долгов в `tier` нет, гасится по времени создания."""
    return _snapshot_payment(driver_id, amount_kop, tier="")


# ───────────────────────────── D. «Я оплатил» ─────────────────────────────

def _declare_via_http(client, drv, monkeypatch):
    told = []
    monkeypatch.setattr("app.routers.debt.notify_admin_telegram", lambda text: told.append(text))
    r = client.post("/driver/debt/paid", headers=drv["auth"])
    return r, told


def test_declare_does_not_reopen_debts_the_card_closed_a_moment_earlier(client, user_factory, monkeypatch):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dDeclareAll", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 25_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1, d2)))

    with _in_the_window(lambda: _activate(pay_id)) as box:
        r, told = _declare_via_http(client, drv, monkeypatch)

    assert box["fired"], "окно гонки не открылось — тест ничего не доказал"
    assert box["result"].status == "succeeded"
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("already") is True and body["pending_kop"] == 0, (
        f"водителю ответили «ждём подтверждения», хотя долг уже оплачен картой: {body}"
    )
    after = [_debt(d) for d in (d1, d2)]
    assert [d.status for d in after] == [DebtStatus.paid, DebtStatus.paid], (
        "оплаченный картой долг снова «на подтверждении» — «Отклонить» у админа вернёт его в неоплаченные"
    )
    assert [d.declare_count or 0 for d in after] == [0, 0], "обещание засчитано по долгу, который уже оплачен"
    assert len(told) == 1 and "уже закрыт" in told[0] and "вернуть" in told[0], (
        f"админ должен узнать, что перевод по СБП, если пришёл, — лишний: {told}"
    )
    assert "заявил оплату" not in told[0], "админу ушло «заявил оплату» по долгу, который уже закрыт картой"


def test_declare_moves_only_what_is_still_unpaid_when_the_card_closed_part(client, user_factory, monkeypatch):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dDeclarePart", [10_000, 15_000])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))

    with _in_the_window(lambda: _activate(pay_id)) as box:
        r, told = _declare_via_http(client, drv, monkeypatch)

    assert box["fired"]
    assert r.status_code == 200, r.text
    assert r.json()["pending_kop"] == 15_000, "в заявку попал долг, который закрыла карта"
    assert not r.json().get("already")
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(d2).status == DebtStatus.pending
    assert (_debt(d2).declare_count, _debt(d1).declare_count or 0) == (1, 0)
    assert len(told) == 1 and "150 ₽" in told[0]


def test_declare_does_not_reopen_a_debt_the_admin_forgave_a_moment_earlier(client, user_factory, monkeypatch):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dDeclareForgive", [10_000])

    with _in_the_window(lambda: _forgive_directly(d1)) as box:
        r, _ = _declare_via_http(client, drv, monkeypatch)

    assert box["fired"]
    assert r.status_code == 200 and r.json().get("already") is True
    after = _debt(d1)
    assert (after.status, after.note) == (DebtStatus.paid, "Списан админом: спор"), (
        "прощённый долг снова «на подтверждении»: водитель опять должен комиссию, которую ему простили"
    )


def test_double_tap_counts_the_promise_once(client, user_factory):
    """Второе «Я оплатил», пришедшее в окне первого, не должно второй раз тратить обещание
    (`declare_count` → `declare_abuse` закрывает такси) и не выдаётся за «уже оплачено»."""
    drv, (d1,) = _driver_with_debts(user_factory, "L12dDoubleTap", [10_000])

    def first_tap():
        with Session(engine) as s:
            return debt_mod.declare_paid(s, drv["id"])

    with _in_the_window(first_tap) as box:
        with Session(engine) as s:
            second = debt_mod.declare_paid(s, drv["id"])

    assert box["fired"] and box["result"] == 10_000
    assert second == 0
    assert (_debt(d1).status, _debt(d1).declare_count) == (DebtStatus.pending, 1)


def test_declare_without_a_race_still_works(client, user_factory, monkeypatch):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dDeclareOk", [10_000, 15_000])
    r, told = _declare_via_http(client, drv, monkeypatch)
    assert r.status_code == 200 and r.json()["pending_kop"] == 25_000 and not r.json().get("already")
    assert [_debt(d).status for d in (d1, d2)] == [DebtStatus.pending, DebtStatus.pending]
    assert [_debt(d).declare_count for d in (d1, d2)] == [1, 1]
    assert all(_debt(d).paid_declared_at is not None for d in (d1, d2))
    assert len(told) == 1
    # Нечего заявлять (всё уже pending) — как раньше: 0 и без «уже оплачено».
    r, told = _declare_via_http(client, drv, monkeypatch)
    assert r.json()["pending_kop"] == 0 and not r.json().get("already") and told == []


# ─────────────────── V. Разбор «пассажир не заплатил» / онлайн-оплата заказа ───────────────────

def _void(order_id: int, note: str = ""):
    with Session(engine) as s:
        res = debt_mod.void_debt_for_order(s, order_id, note=note)
        s.commit()
        return res


def test_unpaid_report_does_not_relabel_a_card_payment_as_written_off(client, user_factory):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dVoidCard", [10_000])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))
    order_id = _order_of(d1)

    with _in_the_window(lambda: _activate(pay_id)) as box:
        res = _void(order_id)

    assert box["fired"]
    after = _debt(d1)
    assert not (after.note or "").startswith(debt_mod.WRITTEN_OFF_PREFIX), (
        "оплата картой перезаписана пометкой «Списан: снят по разбору» — возврат водителю уже не положен"
    )
    assert res == "refunded", f"водитель заплатил картой, а разбор ответил «{res}» вместо «вернули»"
    assert _wallet_balance(drv["id"]) == 10_000
    assert [(e.ext_id, e.note) for e in _ledger_adj_entries(drv["id"])] == [
        (f"refund:order:{order_id}", debt_mod.REFUND_NOTE)]


def test_online_order_payment_refunds_a_debt_the_card_closed_a_moment_earlier(client, user_factory):
    """Тот же путь из онлайн-оплаты заказа (ledger.settle_instant_order): комиссию уже удержали
    записью fee, а долг в ту же секунду оплачен картой — комиссия взята дважды, нужен возврат."""
    drv, (d1,) = _driver_with_debts(user_factory, "L12dVoidOnline", [10_000])
    pay_id = _snapshot_payment(drv["id"], 10_000, tier=debt_mod.make_taxi_debt_snapshot_tier((d1,)))

    with _in_the_window(lambda: _activate(pay_id)) as box:
        res = _void(_order_of(d1), note="Комиссия удержана при онлайн-оплате")

    assert box["fired"]
    assert res == "refunded"
    assert _wallet_balance(drv["id"]) == 10_000
    # Подпись в кошельке — про онлайн-оплату, а не «разбор подтвердил, что не заплатили».
    assert [e.note for e in _ledger_adj_entries(drv["id"])] == [debt_mod.ONLINE_REFUND_NOTE]
    ba = debt_mod.LEDGER_NOTE_BA.get(debt_mod.ONLINE_REFUND_NOTE, "")
    assert ba.strip() and ba != debt_mod.ONLINE_REFUND_NOTE


def test_unpaid_report_keeps_the_admins_own_reason_when_he_forgave_a_moment_earlier(client, user_factory):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dVoidForgive", [10_000])

    with _in_the_window(lambda: _forgive_directly(d1, "Списан админом: поездка сорвалась")) as box:
        res = _void(_order_of(d1))

    assert box["fired"]
    assert _debt(d1).note == "Списан админом: поездка сорвалась", "причина админа затёрта «снят по разбору»"
    assert res is None, "долг уже списан админом — второе «списали» водителю не нужно"
    assert _wallet_balance(drv["id"]) == 0, "вернули комиссию, которую платформа не получала"


def test_unpaid_report_refunds_a_transfer_the_admin_confirmed_a_moment_earlier(client, user_factory):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dVoidConfirm", [10_000])
    with Session(engine) as s:
        assert debt_mod.declare_paid(s, drv["id"]) == 10_000

    with _in_the_window(lambda: _confirm_directly(d1)) as box:
        res = _void(_order_of(d1))

    assert box["fired"]
    assert res == "refunded", "СБП-перевод подтверждён, а разбор его «списал» — деньги водителя пропали"
    assert _wallet_balance(drv["id"]) == 10_000


def test_unpaid_report_without_a_race_still_writes_off(client, user_factory):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dVoidOk", [10_000, 15_000])
    with Session(engine) as s:
        d = s.get(CommissionDebt, d2)
        d.note = "служебная пометка"
        s.add(d); s.commit()
    assert _void(_order_of(d1)) == "voided"
    assert _debt(d1).note == f"{debt_mod.WRITTEN_OFF_PREFIX}: снят по разбору"
    assert _void(_order_of(d2)) == "voided"
    assert _debt(d2).note == f"{debt_mod.WRITTEN_OFF_PREFIX}: снят по разбору; прежняя пометка: служебная пометка"
    assert _void(_order_of(d1)) is None, "повторный разбор не должен ничего возвращать"
    # Онлайн-оплата пишет свою причину только в пустую пометку — как раньше.
    drv2, (o1,) = _driver_with_debts(user_factory, "L12dVoidOnlineOk", [10_000])
    assert _void(_order_of(o1), note="Комиссия удержана при онлайн-оплате") == "voided"
    assert _debt(o1).note == "Комиссия удержана при онлайн-оплате"
    assert all(_debt(d).status == DebtStatus.paid for d in (d1, d2, o1))
    assert _wallet_balance(drv["id"]) == 0 and _wallet_balance(drv2["id"]) == 0


# ───────────────────────────── L. Старый счёт картой без снимка ─────────────────────────────

def test_legacy_card_bill_refunds_a_debt_forgiven_inside_the_window(client, user_factory):
    drv, (d1, d2) = _driver_with_debts(user_factory, "L12dLegacyForgive", [10_000, 15_000])
    pay_id = _legacy_payment(drv["id"], 25_000)

    with _in_the_window(lambda: _forgive_directly(d2)) as box:
        payment = _activate(pay_id)

    assert box["fired"]
    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(d2).note == "Списан админом: спор", "оплата картой затёрла прощение"
    assert _wallet_balance(drv["id"]) == 15_000, (
        "карта взяла 150 ₽ за долг, который в ту же секунду простили, — и не вернула"
    )
    assert [e.ext_id for e in _ledger_adj_entries(drv["id"])] == [f"refund:taxi_debt:{pay_id}:{d2}"]


def test_legacy_card_bill_refunds_a_debt_the_wallet_paid_inside_the_window(client, user_factory):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dLegacyWallet", [10_000])
    pay_id = _legacy_payment(drv["id"], 10_000)
    with Session(engine) as s:
        s.add(LedgerEntry(driver_id=drv["id"], kind=LedgerKind.adj, amount_kop=10_000, note="компенсация промо"))
        s.commit()

    def wallet_pays():
        with Session(engine) as s:
            return debt_mod.settle_debt_from_wallet(s, drv["id"])

    with _in_the_window(wallet_pays) as box:
        payment = _activate(pay_id)

    assert box["fired"] and box["result"] == 10_000
    assert payment.status == "succeeded"
    assert _debt(d1).note == debt_mod.WALLET_PAID_NOTE
    assert _wallet_balance(drv["id"]) == 10_000, "долг оплачен и кошельком, и картой — второй раз не вернули"


def test_legacy_card_bill_without_a_race_closes_only_debts_before_it(client, user_factory):
    drv, (d1,) = _driver_with_debts(user_factory, "L12dLegacyOk", [10_000])
    pay_id = _legacy_payment(drv["id"], 10_000)
    with Session(engine) as s:
        late = CommissionDebt(driver_id=drv["id"], order_id=None, amount_kop=5_000, week="2026-W40",
                              status=DebtStatus.unpaid, created_at=utcnow() + timedelta(minutes=5))
        s.add(late); s.commit(); s.refresh(late)
        late_id = late.id

    payment = _activate(pay_id)

    assert payment.status == "succeeded"
    assert _debt(d1).status == DebtStatus.paid
    assert _debt(late_id).status == DebtStatus.unpaid, "долг, начисленный после счёта, погашен бесплатно"
    assert _wallet_balance(drv["id"]) == 0
    with Session(engine) as s:
        assert s.exec(select(Payment).where(Payment.id == pay_id)).one().settled_at is not None
