"""Кошелёк работал в одну сторону: долг он гасил, а уйти с деньгами не мешал (волна 156).

Волной раньше кошелёк научили закрывать долг по комиссии: деньги платформы, лежащие у водителя,
гасят его долг платформе. Ломаю свой же свежий код — и вот дыра.

Вывод на карту смотрел только на то, **сколько лежит**, и не смотрел, **сколько человек должен**.
Проба: в кошельке 600 ₽, долг по комиссии 500 ₽ — водитель выводит все 600 на карту, долг
остаётся неоплаченным. Обиднее всего происхождение этих денег: платформа сама доплатила их
водителю компенсацией промо-скидки за пассажира. То есть мы оплатили пассажиру скидку, отдали
компенсацию водителю на карту — и остались должны сами себе.

Долгов у одного человека два вида и живут они в разных местах: комиссия за такси — отдельными
записями долга, комиссия курьера — флагом на самой доставке. Кошелёк при этом один. Считать
«сколько должен» по одному виду — значит недосчитать, поэтому вывод смотрит на оба.

И то же число теперь видно человеку заранее: в кошельке рядом с балансом показано, сколько
зарезервировано под комиссию и сколько свободно. Иначе он видит 600 ₽, жмёт «Вывести»
и получает отказ без объяснения.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app import ledger
from app.config import settings
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, DriverProfile, InstantOrder, InstantOrderStatus,
    LedgerEntry, LedgerKind, UserRole,
)
from app.timeutil import utcnow


@pytest.fixture
def выплаты_включены(monkeypatch):
    """Выплаты на карту в проде пока выключены — для проверки самого правила включаем."""
    monkeypatch.setattr(type(settings), "payouts_ready", property(lambda self: True))


def _водитель(user_factory, метка: str, кошелёк_коп: int, долг_коп: int = 0):
    водитель = user_factory(метка + "Водитель", role=UserRole.driver)
    пассажир = user_factory(метка + "Пассажир")
    with Session(engine) as s:
        заказ = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                             status=InstantOrderStatus.done, price_estimate=620, price_final=620,
                             done_at=utcnow(), paid=True, payment_method="cash")
        s.add(заказ)
        s.commit()
        s.refresh(заказ)
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj, amount_kop=кошелёк_коп,
                          ext_id=f"promo:{заказ.id}", note="Компенсация промокода пассажира"))
        if долг_коп:
            s.add(CommissionDebt(driver_id=водитель["id"], order_id=заказ.id, week="2026-W34",
                                 amount_kop=долг_коп, status=DebtStatus.unpaid, due_at=utcnow()))
        s.commit()
        профиль = s.exec(select(DriverProfile).where(
            DriverProfile.user_id == водитель["id"])).first()
        if профиль is None:
            профиль = DriverProfile(user_id=водитель["id"])
        профиль.payout_card_last4 = "1234"
        профиль.payout_token = "tok-test"
        s.add(профиль)
        s.commit()
    return водитель


def test_нельзя_вывести_то_что_должен(client, user_factory, выплаты_включены):
    """Главное: сначала комиссия платформе, потом карта."""
    водитель = _водитель(user_factory, "Побег", 60_000, 50_000)

    ответ = client.post("/wallet/payout", headers=водитель["auth"],
                        json={"amount_kop": 60_000, "idempotency_key": "п-1"})

    assert ответ.status_code == 400, (
        f"водитель вывел на карту весь баланс (ответ {ответ.status_code}), хотя 500 ₽ из него — "
        "неоплаченная комиссия: платформа доплатила ему за промо-скидку и осталась должна себе"
    )
    with Session(engine) as s:
        assert ledger.driver_balance(s, водитель["id"]) == 60_000, "деньги всё-таки ушли"


def test_отказ_называет_цифры(client, user_factory, выплаты_включены):
    """Человеку говорят, сколько свободно и почему, а не просто «нельзя»."""
    водитель = _водитель(user_factory, "Цифры", 60_000, 50_000)

    текст = client.post("/wallet/payout", headers=водитель["auth"],
                        json={"amount_kop": 60_000, "idempotency_key": "ц-1"}).text

    assert "100" in текст and "500" in текст, (
        f"в отказе нет ни свободной суммы, ни размера долга: {текст[:200]}"
    )


def test_свободную_часть_вывести_можно(client, user_factory, выплаты_включены):
    """Обратная сторона: резервируем ровно долг, остальное — деньги водителя."""
    водитель = _водитель(user_factory, "Свободно", 60_000, 50_000)

    ответ = client.post("/wallet/payout", headers=водитель["auth"],
                        json={"amount_kop": 10_000, "idempotency_key": "с-1"})

    assert ответ.status_code == 200, (
        f"свободные 100 ₽ вывести не дали: {ответ.text[:200]}. Мы зарезервировали больше, "
        "чем человек должен"
    )


def test_без_долга_вывод_работает_как_прежде(client, user_factory, выплаты_включены):
    """Обратная сторона: у кого долгов нет, тот забирает всё."""
    водитель = _водитель(user_factory, "Чисто", 60_000)

    ответ = client.post("/wallet/payout", headers=водитель["auth"],
                        json={"amount_kop": 60_000, "idempotency_key": "ч-1"})

    assert ответ.status_code == 200, f"вывод сломался у водителя без долгов: {ответ.text[:200]}"
    with Session(engine) as s:
        assert ledger.driver_balance(s, водитель["id"]) == 0


def test_погасил_долг_деньги_освободились(client, user_factory, выплаты_включены):
    """Долг закрыт — резерв снят. Иначе деньги остались бы заперты навсегда."""
    водитель = _водитель(user_factory, "Освобождение", 60_000, 50_000)
    with Session(engine) as s:                      # админ подтвердил перевод по СБП
        долг = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()
        долг.status = DebtStatus.paid
        s.add(долг)
        s.commit()

    свободно = client.get("/wallet/balance", headers=водитель["auth"]).json()["payable_kop"]

    assert свободно == 60_000, (
        f"долг оплачен, а свободно всё ещё {свободно / 100:g} ₽: деньги заперты после расчёта"
    )


def test_долг_курьера_тоже_резервирует(client, user_factory, выплаты_включены):
    """Долгов два вида и лежат они в разных местах, а кошелёк один."""
    from app.models import ParcelDelivery
    водитель = _водитель(user_factory, "Курьер", 60_000)
    отправитель = user_factory("КурьерОтправитель")
    with Session(engine) as s:
        s.add(ParcelDelivery(sender_id=отправитель["id"], courier_id=водитель["id"],
                             status="delivered", delivery_type="courier",
                             commission_kop=50_000, commission_paid=False,
                             from_city="Баймак", to_city="Сибай"))
        s.commit()

    ответ = client.post("/wallet/payout", headers=водитель["auth"],
                        json={"amount_kop": 60_000, "idempotency_key": "к-1"})

    assert ответ.status_code == 400, (
        f"неоплаченная комиссия курьера не мешает вывести деньги (ответ {ответ.status_code}): "
        "долг за доставку живёт отдельно от долга за такси, а кошелёк общий"
    )


def test_кошелёк_показывает_что_заперто(client, user_factory):
    """Иначе человек видит 600 ₽, жмёт «Вывести» и получает отказ без объяснения."""
    водитель = _водитель(user_factory, "Видимость", 60_000, 50_000)

    кошелёк = client.get("/wallet/balance", headers=водитель["auth"]).json()

    assert кошелёк["balance_kop"] == 60_000
    assert кошелёк["reserved_kop"] == 50_000, (
        f"в кошельке не видно резерва под комиссию: {кошелёк}"
    )
    assert кошелёк["payable_kop"] == 10_000, f"свободная сумма посчитана неверно: {кошелёк}"


def test_резерв_не_больше_чем_лежит(client, user_factory):
    """Долг больше кошелька — показываем запертым то, что есть, а не отрицательные деньги."""
    водитель = _водитель(user_factory, "Перебор", 10_000, 50_000)

    кошелёк = client.get("/wallet/balance", headers=водитель["auth"]).json()

    assert кошелёк["reserved_kop"] == 10_000, f"заперли больше, чем лежит: {кошелёк}"
    assert кошелёк["payable_kop"] == 0, f"свободные деньги взялись из ниоткуда: {кошелёк}"
