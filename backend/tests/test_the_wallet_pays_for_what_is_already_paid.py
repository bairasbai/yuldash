# -*- coding: utf-8 -*-
"""Кошелёк гасит доставки, за которые курьер уже платит висящим платежом (волна 218).

Волна 216 научила кошелёк курьера гасить его комиссию. Волна 215 начала класть в этот
кошелёк возврат за доставки, по которым курьеру не заплатили. Вместе они дают вот что.

Курьер должен 200 ₽. Жмёт «оплатить», переводит по СБП, ждёт подтверждения Александра —
это «на доверии», и ждать можно сутки. Пока он ждёт, разбор по старой жалобе заканчивается,
и 50 ₽ комиссии возвращаются ему в кошелёк. Он открывает кабинет — зачёт волны 216 честно
берёт эти 50 ₽ и закрывает ими одну из доставок. Ту самую, которая входит в снапшот
висящего платежа.

Александр подтверждает перевод. Активация помечает оплаченными все доставки из снапшота.

Итог: за 200 ₽ комиссии человек отдал 200 ₽ переводом **и** 50 ₽ из кошелька. Пятьдесят
рублей исчезли, и ни один экран об этом не скажет.

**Окно не теоретическое.** У ЮKassa оно минуты, а у СБП «на доверии» — сутки и больше:
Александр один и подтверждает переводы, когда дойдут руки. Разбор жалобы за это время
закончиться успевает вполне.

Соседние двери от такого уже защищены снапшотом по времени: активация платежа берёт только
`delivered_at <= payment.created_at`, а у такси то же самое делает `up_to` в `mark_all_paid`.
Защищались они от «бесплатно погасить накопленное в окне». Обратная сторона того же окна —
«дважды заплатить за уже оплачиваемое» — осталась открытой, и открыла её волна 216.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app import debt as debt_mod
from app.config import settings
from app.db import engine
from app.ledger import driver_balance
from app.models import ParcelDelivery, Payment, UserRole
from app.timeutil import utcnow
from conftest import upload_doc


@pytest.fixture(autouse=True)
def _режимы():
    было = settings.taxi_enabled, settings.courier_enabled, settings.payments_provider
    settings.taxi_enabled, settings.courier_enabled = True, True
    settings.payments_provider = "sbp_manual"      # «на доверии»: окно ожидания — сутки
    yield
    (settings.taxi_enabled, settings.courier_enabled,
     settings.payments_provider) = было


@pytest.fixture(autouse=True)
def _тихо(monkeypatch):
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)
    monkeypatch.setattr("app.routers.courier.notify_admin_telegram", lambda *a, **k: None)


def _курьер(client, user_factory, имя: str):
    админ = user_factory(name=f"Админ{имя}", role=UserRole.admin)
    c = user_factory(name=имя, role=UserRole.driver)
    aid = client.post("/courier/apply", headers=c["auth"],
                      json={"transport": "car",
                            "selfie_url": upload_doc(client, c["auth"])}).json()["id"]
    assert client.post(f"/admin/courier-applications/{aid}/approve",
                       headers=админ["auth"]).status_code == 200
    return c, админ


def _доставка(курьер_id: int, отправитель_id: int, комиссия_коп: int) -> int:
    with Session(engine) as s:
        p = ParcelDelivery(
            sender_id=отправитель_id, courier_id=курьер_id, status="delivered",
            from_city="Акъяр", to_city="Сибай", description="коробка",
            delivery_type="courier", price=100000,
            accepted_at=utcnow(), delivered_at=utcnow(),
            commission_kop=комиссия_коп, commission_paid=False, settled=True)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _вернули_в_кошелёк(курьер_id: int, сумма_коп: int, parcel_id: int) -> None:
    """Разбор закончился — комиссию вернули (волна 215)."""
    with Session(engine) as s:
        debt_mod.refund_commission_to_wallet(s, курьер_id, сумма_коп, parcel_id=parcel_id)
        s.commit()


def test_пока_платёж_висит_кошелёк_эти_доставки_не_трогает(client, user_factory):
    """Главное: за одну и ту же комиссию человек не должен заплатить дважды."""
    курьер, админ = _курьер(client, user_factory, "РустамДважды")
    отправитель = user_factory("ОтправительДважды")
    # Порядок важен: гасим по старшинству и только целиком (граница волны 216). Значит
    # кошелёк дотянется до доставки, только если СТАРШАЯ ему по карману.
    _доставка(курьер["id"], отправитель["id"], 5000)      # старшая: 50 ₽
    _доставка(курьер["id"], отправитель["id"], 15000)     # 150 ₽

    начал = client.post("/courier/pay-commission", headers=курьер["auth"])
    assert начал.status_code == 200, начал.text
    with Session(engine) as s:
        платёж = s.exec(select(Payment).where(
            Payment.user_id == курьер["id"],
            Payment.purpose == "courier_commission").order_by(Payment.id.desc())).first()
    assert платёж is not None and платёж.amount_kop == 20000, "тест слеп: счёт не на 200 ₽"

    _вернули_в_кошелёк(курьер["id"], 5000, parcel_id=990001)   # 50 ₽ пришли, пока он ждёт
    client.get("/courier/me", headers=курьер["auth"])          # открыл кабинет → зачёт

    with Session(engine) as s:
        осталось = driver_balance(s, курьер["id"])

    assert осталось == 5000, (
        f"кошелёк потратили на доставки, которые человек уже оплачивает переводом на 200 ₽: "
        f"в нём {осталось / 100:.0f} ₽ вместо 50 ₽. Александр подтвердит перевод — и за одну "
        "и ту же комиссию человек отдаст 250 ₽"
    )


def test_после_подтверждения_перевода_деньги_сходятся(client, user_factory):
    """То же, но до конца: платёж подтверждён, и сумма отданного равна сумме долга."""
    курьер, админ = _курьер(client, user_factory, "РустамСходится")
    отправитель = user_factory("ОтправительСходится")
    _доставка(курьер["id"], отправитель["id"], 5000)      # старшая: 50 ₽
    _доставка(курьер["id"], отправитель["id"], 15000)

    client.post("/courier/pay-commission", headers=курьер["auth"])
    with Session(engine) as s:
        платёж = s.exec(select(Payment).where(
            Payment.user_id == курьер["id"],
            Payment.purpose == "courier_commission").order_by(Payment.id.desc())).first()
        pid, сумма_перевода = платёж.id, платёж.amount_kop

    _вернули_в_кошелёк(курьер["id"], 5000, parcel_id=990002)
    client.get("/courier/me", headers=курьер["auth"])

    подтвердил = client.post(f"/admin/payments/{pid}/confirm", headers=админ["auth"])
    assert подтвердил.status_code == 200, подтвердил.text

    with Session(engine) as s:
        из_кошелька = -sum(e.amount_kop for e in s.exec(
            select(__import__("app.models", fromlist=["LedgerEntry"]).LedgerEntry).where(
                __import__("app.models", fromlist=["LedgerEntry"]).LedgerEntry.driver_id
                == курьер["id"])).all() if e.amount_kop < 0)

    отдал = сумма_перевода + из_кошелька
    assert отдал == 20000, (
        f"за 200 ₽ комиссии человек отдал {отдал / 100:.0f} ₽: {сумма_перевода / 100:.0f} ₽ "
        f"переводом и {из_кошелька / 100:.0f} ₽ из кошелька"
    )


# ----------------------------- обратная сторона -----------------------------
def test_новую_доставку_кошелёк_гасит_как_обычно(client, user_factory):
    """Обратная сторона: доставка ВНЕ снапшота платежа — деньги кошелька идут в дело.

    Иначе «починкой» сошло бы «пока висит любой платёж, кошелёк заморожен»: у курьера
    с несвоевременным Александром деньги лежали бы неделями.
    """
    курьер, админ = _курьер(client, user_factory, "РустамНовая")
    отправитель = user_factory("ОтправительНовая")
    _доставка(курьер["id"], отправитель["id"], 5000)                   # старшая: 50 ₽

    client.post("/courier/pay-commission", headers=курьер["auth"])     # снапшот: 50 ₽

    _доставка(курьер["id"], отправитель["id"], 3000)                   # новая, ПОСЛЕ снапшота
    _вернули_в_кошелёк(курьер["id"], 3000, parcel_id=990003)
    client.get("/courier/me", headers=курьер["auth"])

    with Session(engine) as s:
        assert driver_balance(s, курьер["id"]) == 0, (
            "кошелёк не погасил новую доставку, которой нет в снапшоте платежа: деньги "
            "заморожены зря"
        )


def test_без_висящего_платежа_всё_как_было(client, user_factory):
    """Обратная сторона: платежа нет — зачёт волны 216 работает как работал."""
    курьер, админ = _курьер(client, user_factory, "РустамБезПлатежа")
    отправитель = user_factory("ОтправительБезПлатежа")
    _доставка(курьер["id"], отправитель["id"], 5000)
    _вернули_в_кошелёк(курьер["id"], 5000, parcel_id=990004)

    ответ = client.get("/courier/me", headers=курьер["auth"]).json()

    assert ответ["statement"]["commission_owed_kop"] == 0, "зачёт перестал работать вовсе"
    with Session(engine) as s:
        assert driver_balance(s, курьер["id"]) == 0


def test_отклонённый_платёж_кошелёк_освобождает(client, user_factory):
    """Обратная сторона: перевод не пришёл, платёж отклонён — деньги снова в деле.

    Иначе отклонённый платёж навсегда замораживал бы кошелёк на свои доставки.
    """
    курьер, админ = _курьер(client, user_factory, "РустамОтклонён")
    отправитель = user_factory("ОтправительОтклонён")
    _доставка(курьер["id"], отправитель["id"], 5000)

    client.post("/courier/pay-commission", headers=курьер["auth"])
    with Session(engine) as s:
        платёж = s.exec(select(Payment).where(
            Payment.user_id == курьер["id"],
            Payment.purpose == "courier_commission").order_by(Payment.id.desc())).first()
        pid = платёж.id

    _вернули_в_кошелёк(курьер["id"], 5000, parcel_id=990005)
    отклонил = client.post(f"/admin/payments/{pid}/reject", headers=админ["auth"])
    assert отклонил.status_code == 200, отклонил.text

    client.get("/courier/me", headers=курьер["auth"])

    with Session(engine) as s:
        assert driver_balance(s, курьер["id"]) == 0, (
            "платёж отклонён, а кошелёк всё ещё считает эти доставки оплачиваемыми"
        )


# ============================ вторая половина: такси ============================
# У таксиста та же связка: `settle_debt_from_wallet` берёт все долги со статусом «не оплачен»,
# а `pending` — это «я перевёл, жду Александра». Значит кошелёк может закрыть долг, за который
# человек уже отправил деньги, и `admin_confirm` подтвердит только остаток.
# Проверяем ОТДЕЛЬНОЙ пробой, а не по аналогии (урок волны 215).
def _таксист_с_долгом(user_factory, имя: str, долг_коп: int):
    from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus

    водитель = user_factory(имя, role=UserRole.driver)
    пассажир = user_factory(f"Пас{имя}")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=o.id, amount_kop=долг_коп,
                             status=DebtStatus.unpaid, week_key="2026-W35", created_at=utcnow()))
        s.commit()
    return водитель


def test_такси_кошелёк_не_гасит_долг_за_который_уже_перевели(client, user_factory):
    """Главное: «Я оплатил» — это деньги в пути, а не «долга больше нет»."""
    from app.models import CommissionDebt, DebtStatus

    водитель = _таксист_с_долгом(user_factory, "ТаксиДважды", 5000)   # должен 50 ₽

    with Session(engine) as s:
        переведено = debt_mod.declare_paid(s, водитель["id"])          # перевёл, ждёт админа
    assert переведено == 5000, "тест слеп: заявка на оплату не прошла"

    _вернули_в_кошелёк(водитель["id"], 5000, parcel_id=990010)         # возврат пришёл в окне
    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])
        осталось = driver_balance(s, водитель["id"])
        долг = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()

    assert осталось == 5000, (
        f"кошелёк потратили на долг, за который человек уже перевёл деньги: в нём "
        f"{осталось / 100:.0f} ₽ вместо 50 ₽. Александр подтвердит перевод — и за одну "
        "и ту же комиссию человек отдаст вдвое"
    )
    assert долг.status == DebtStatus.pending, (
        "долг, за который перевели, закрыли кошельком — админ подтвердит уже нечего"
    )


def test_такси_после_отклонения_кошелёк_снова_в_деле(client, user_factory):
    """Обратная сторона: перевод не пришёл, админ отклонил — деньги кошелька работают.

    Иначе одна неудачная заявка замораживала бы кошелёк навсегда.
    """
    from app.models import CommissionDebt, DebtStatus

    водитель = _таксист_с_долгом(user_factory, "ТаксиОтклонён", 5000)

    with Session(engine) as s:
        debt_mod.declare_paid(s, водитель["id"])
        долг = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()
        debt_mod.admin_reject(s, долг.id)                    # деньги не пришли → снова unpaid

    _вернули_в_кошелёк(водитель["id"], 5000, parcel_id=990011)
    with Session(engine) as s:
        debt_mod.settle_debt_from_wallet(s, водитель["id"])
        осталось = driver_balance(s, водитель["id"])
        долг = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()

    assert осталось == 0 and долг.status == DebtStatus.paid, (
        f"после отклонения заявки кошелёк не погасил долг: в нём {осталось / 100:.0f} ₽, "
        f"долг {долг.status}"
    )


def test_такси_обычный_неоплаченный_долг_гасится(client, user_factory):
    """Обратная сторона: никакой заявки не было — зачёт волны 154 работает как работал."""
    from app.models import CommissionDebt, DebtStatus

    водитель = _таксист_с_долгом(user_factory, "ТаксиОбычный", 5000)
    _вернули_в_кошелёк(водитель["id"], 5000, parcel_id=990012)

    with Session(engine) as s:
        погашено = debt_mod.settle_debt_from_wallet(s, водитель["id"])
        долг = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()

    assert погашено == 5000 and долг.status == DebtStatus.paid, "зачёт перестал работать вовсе"


# ---------------------------------------------------------------------------
# Разбор мутаций: две защиты у такси маскируют друг друга.
#
# У такси-зачёта их две: выборка берёт только `unpaid`, и условие «ещё unpaid» стоит ВНУТРИ
# самого UPDATE. Поодиночке каждая закрывает дыру, поэтому мутация любой из них по отдельности
# проходит мимо обычного теста — вторая подстраховывает.
#
# Обе нужны, и вот почему они разные. Выборка бережёт работу: незачем тянуть долги, которые
# трогать нельзя. Условие в UPDATE — единственное, что работает, когда выборка УСТАРЕЛА:
# человек нажал «Я оплатил» ровно между чтением и записью. Замок на строке водителя от этого
# не спасает — на SQLite он пустышка, а на нём живут тесты и демо-база (урок волны 201).
#
# Тест ниже ловит именно вторую защиту: вклиниваемся между чтением и UPDATE.
# ---------------------------------------------------------------------------
def test_такси_заявка_подана_между_чтением_и_записью(client, user_factory):
    """Договор на условие внутри UPDATE: устаревшая выборка не должна закрыть долг.

    Человек нажал «Я оплатил» ровно в тот момент, когда зачёт уже прочитал список долгов,
    но ещё не записал. Список у зачёта старый, в нём долг помечен `unpaid`. Записать его
    оплаченным — значит забрать деньги из кошелька за долг, за который перевод уже в пути.
    """
    from sqlalchemy.sql import Update

    from app.models import CommissionDebt, DebtStatus

    водитель = _таксист_с_долгом(user_factory, "ТаксиГонка", 5000)
    _вернули_в_кошелёк(водитель["id"], 5000, parcel_id=990013)

    сработало = {"раз": False}
    with Session(engine) as s:
        исходный = s.execute

        def execute_с_вклиниванием(statement, *a, **kw):
            # Долги уже прочитаны, запись ещё не сделана — самое узкое место.
            if isinstance(statement, Update) and not сработало["раз"]:
                сработало["раз"] = True
                with Session(engine) as чужая:
                    debt_mod.declare_paid(чужая, водитель["id"])   # «Я оплатил» в это окно
            return исходный(statement, *a, **kw)

        s.execute = execute_с_вклиниванием
        debt_mod.settle_debt_from_wallet(s, водитель["id"])

    assert сработало["раз"], "вклиниться не удалось — тест слеп"
    with Session(engine) as s:
        долг = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()
        осталось = driver_balance(s, водитель["id"])

    assert долг.status == DebtStatus.pending, (
        "долг закрыли кошельком по устаревшему списку — а человек уже перевёл за него деньги"
    )
    assert осталось == 5000, (
        f"деньги из кошелька списали: в нём {осталось / 100:.0f} ₽ вместо 50 ₽"
    )


def _ещё_долг(user_factory, водитель, пассажир_имя: str, долг_коп: int) -> int:
    """Второй долг тому же водителю — понадобится, чтобы проверить порядок очереди."""
    from app.models import CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus

    пассажир = user_factory(пассажир_имя)
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         from_lat=52.5, from_lng=58.3, to_lat=52.7, to_lng=58.6,
                         status=InstantOrderStatus.done, price_estimate=300, price_final=300,
                         paid=True, done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        d = CommissionDebt(driver_id=водитель["id"], order_id=o.id, amount_kop=долг_коп,
                           status=DebtStatus.unpaid, week_key="2026-W36", created_at=utcnow())
        s.add(d)
        s.commit()
        s.refresh(d)
        return d.id


def test_такси_долг_в_оплате_не_держит_очередь(client, user_factory):
    """Договор про ОЧЕРЕДЬ, а не только про закрытие: висящий долг не должен всё стопорить.

    Гасим по старшинству и целиком, а «не хватило на старейший — стоим» (правило волны 154).
    Если долг, за который человек уже перевёл, останется в очереди, он этим `break`
    и заблокирует всё, что за ним: кошелёк не тронет даже тот долг, который покрывает
    целиком, и деньги повиснут до подтверждения Александра.

    Именно поэтому такие долги убраны из ВЫБОРКИ, а не только из записи. Условие внутри
    UPDATE спасает от списания, но не от застрявшей очереди.
    """
    from app.models import CommissionDebt, DebtStatus

    водитель = _таксист_с_долгом(user_factory, "ТаксиОчередь", 30000)   # старый: 300 ₽
    свежий_id = _ещё_долг(user_factory, водитель, "ПасТаксиОчередь2", 5000)   # свежий: 50 ₽

    with Session(engine) as s:
        debt_mod.declare_paid(s, водитель["id"])            # оба ушли в pending
        свежий = s.get(CommissionDebt, свежий_id)
        свежий.status = DebtStatus.unpaid                    # свежий вернули (админ отклонил)
        s.add(свежий)
        s.commit()

    _вернули_в_кошелёк(водитель["id"], 5000, parcel_id=990020)

    with Session(engine) as s:
        погашено = debt_mod.settle_debt_from_wallet(s, водитель["id"])
        свежий = s.get(CommissionDebt, свежий_id)

    assert погашено == 5000 and свежий.status == DebtStatus.paid, (
        f"кошелёк не погасил свежий долг на 50 ₽ (погашено {погашено / 100:.0f} ₽): очередь "
        "застряла на старом долге в 300 ₽, за который человек уже перевёл деньги"
    )
