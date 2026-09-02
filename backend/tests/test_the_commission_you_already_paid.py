# -*- coding: utf-8 -*-
"""Комиссию, уже уплаченную за поездку, по которой кинули, не возвращают (волна 215).

Договор простой и правильный: пассажир не заплатил, водитель нажал «пассажир не заплатил»,
админ жалобу подтвердил — комиссию за эту поездку с водителя снимают. Иначе его кинули
на 620 ₽, и он ещё должен платформе 50 ₽ сверху. Волна, которая это заводила
(аудит 2026-07-26), написала прямо: одна такая история в райцентре расходится по всей
деревне и ломает доверие «между своими».

Снятие сделано так:

    if debt is None or debt.status == DebtStatus.paid:
        return False        # уже оплачен — «снимать нечего»

**Но «уже оплачен» не значит «ничего не должны».** Значит ровно обратное: деньги платформа
уже получила, и вернуть их теперь некому.

Как это происходит само собой, без чьей-либо ошибки:

* долг гасится **пачкой за неделю**. Кнопка «Я оплатил» переводит В PENDING ВСЕ неоплаченные
  долги разом — выбрать «всё, кроме спорной поездки» нельзя, такой ручки просто нет;
* разбор жалобы делает **живой человек**, и по тяжёлым делам он идёт дольше недели.

Значит достаточно, чтобы админ разобрал жалобу на восьмой день, а не на шестой. Водитель
к этому моменту уже перевёл комиссию за ту самую поездку, за которую ему не заплатили.
Жалобу подтверждают, ему приходит пуш «Комиссия за поездку списана» — а денег нет и не будет.

Та же дыра в доставке (волна 191 писала эту половину следом за такси): там комиссию
обнуляют полем `commission_kop = 0`. Если курьер уже оплатил, поле обнулят, а деньги
у платформы останутся. У курьера цена выше: в «купи и привези» он тратил на товар СВОИ.

**Куда возвращать.** Выплаты на карту выключены до оформления ИП — но кошелёк есть, и он
именно для этого: компенсацию промо-скидки платформа кладёт водителю в кошелёк, а он потом
гасит ей будущий долг (`settle_debt_from_wallet`, волна 154). Тот же путь годится и здесь.
"""
from __future__ import annotations

import pytest
from sqlmodel import Session, select

from app import debt as debt_mod
from app.config import settings
from app.db import engine
from app.ledger import driver_balance
from app.models import CommissionDebt, DebtStatus, Report, UserRole
from app.timeutil import utcnow

from test_debt import _order_to_done, fake_redis  # noqa: F401 — фикстура нужна как есть


@pytest.fixture(autouse=True)
def _реквизиты(monkeypatch):
    monkeypatch.setattr(settings, "owner_sbp_phone", "+79990001122")
    monkeypatch.setattr(settings, "owner_sbp_name", "Александр А.")
    yield


@pytest.fixture(autouse=True)
def _тихо(monkeypatch):
    monkeypatch.setattr("app.services.send_push", lambda *a, **k: None)


def _долг(водитель_id: int) -> CommissionDebt:
    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель_id).order_by(CommissionDebt.id.desc())).first()
        assert d is not None, "тест слеп: долг за поездку не начислился"
        return d


def _водитель_оплатил_неделю(client, админ, водитель_id: int) -> int:
    """Ровно то, что делает живой человек: «Я оплатил» → админ подтвердил.

    Выбрать «всё, кроме спорной поездки» нельзя — кнопка переводит в pending ВСЕ долги.
    """
    with Session(engine) as s:
        сумма = debt_mod.declare_paid(s, водитель_id)
        assert сумма > 0, "тест слеп: платить нечего"
        d = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель_id).order_by(CommissionDebt.id.desc())).first()
        debt_mod.admin_confirm(s, d.id)
        s.refresh(d)
        assert d.status == DebtStatus.paid, "тест слеп: долг не стал оплаченным"
    return сумма


def _жалоба_не_заплатил(client, водитель, админ, order_id: int) -> int:
    with Session(engine) as s:
        r = Report(reporter_id=водитель["id"], category="unpaid", order_id=order_id,
                   status="new", created_at=utcnow(), text="пассажир уехал не заплатив")
        s.add(r)
        s.commit()
        s.refresh(r)
        rid = r.id
    ответ = client.post(f"/admin/reports/{rid}/resolve", headers=админ["auth"], json={})
    assert ответ.status_code == 200, ответ.text
    return rid


def test_уже_оплаченную_комиссию_за_поездку_возвращают(client, user_factory, fake_redis):  # noqa: F811
    """Главное: человека кинули на поездку, а комиссию за неё он платформе уже отдал."""
    админ = user_factory("КомиссияАдмин", role=UserRole.admin)
    водитель, _пассажир, заказ = _order_to_done(client, user_factory, fake_redis,
                                                dname="КинулиВодителя", pname="НеЗаплатил")

    комиссия = _долг(водитель["id"]).amount_kop
    assert комиссия > 0, "тест слеп: комиссия за поездку нулевая"

    было_в_кошельке = 0
    with Session(engine) as s:
        было_в_кошельке = driver_balance(s, водитель["id"])

    _водитель_оплатил_неделю(client, админ, водитель["id"])      # платит пачкой за неделю
    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])    # разбор пришёл ПОСЛЕ

    with Session(engine) as s:
        стало = driver_balance(s, водитель["id"])

    assert стало - было_в_кошельке >= комиссия, (
        f"комиссия {комиссия / 100:.2f} ₽ за поездку, по которой водителю не заплатили, "
        f"осталась у платформы: он перевёл её вместе с недельным долгом, а разбор пришёл "
        f"позже. Ему пришёл пуш «Комиссия за поездку списана» — и это неправда, "
        f"в кошельке {(стало - было_в_кошельке) / 100:.2f} ₽"
    )


def test_ещё_не_оплаченную_комиссию_просто_снимают(client, user_factory, fake_redis):  # noqa: F811
    """Обратная сторона: успел разобрать до оплаты — долг гасится, кошелёк не трогаем.

    Класть деньги в кошелёк тому, кто ещё ничего не заплатил, — это подарить ему комиссию
    дважды. Проверяем именно это: путь «снять долг» и путь «вернуть деньги» не должны
    сработать оба сразу.
    """
    админ = user_factory("КомиссияАдмин2", role=UserRole.admin)
    водитель, _пассажир, заказ = _order_to_done(client, user_factory, fake_redis,
                                                dname="КинулиВодителя2", pname="НеЗаплатил2")
    with Session(engine) as s:
        было = driver_balance(s, водитель["id"])

    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])    # разбор ДО оплаты

    with Session(engine) as s:
        assert _долг(водитель["id"]).status == DebtStatus.paid, "долг не сняли"
        assert driver_balance(s, водитель["id"]) == было, (
            "долг сняли И положили деньги в кошелёк — комиссию подарили дважды"
        )


def test_честную_поездку_не_трогаем(client, user_factory, fake_redis):  # noqa: F811
    """Обратная сторона: жалобы не было — комиссия остаётся у платформы, как и договорились."""
    админ = user_factory("КомиссияАдмин3", role=UserRole.admin)
    водитель, _пассажир, _заказ = _order_to_done(client, user_factory, fake_redis,
                                                 dname="ЧестныйВодитель", pname="Заплатил")
    with Session(engine) as s:
        было = driver_balance(s, водитель["id"])

    _водитель_оплатил_неделю(client, админ, водитель["id"])

    with Session(engine) as s:
        assert driver_balance(s, водитель["id"]) == было, (
            "комиссию вернули за поездку, по которой никто не жаловался"
        )


# ============================ вторая половина: доставка ============================
# Волна 191 писала курьерскую половину следом за такси и повторила ту же форму: комиссию
# «снимают» обнулением поля `commission_kop`. Если курьер уже оплатил, поле обнулят — а деньги
# останутся у платформы. У курьера цена ошибки выше: в «купи и привези» он тратит на товар СВОИ.
def _доставка_оплаченная_комиссия(курьер_id: int, отправитель_id: int, комиссия_коп: int) -> int:
    """Вручённая доставка, комиссия по которой УЖЕ переведена платформе."""
    from app.models import ParcelDelivery
    with Session(engine) as s:
        p = ParcelDelivery(
            sender_id=отправитель_id, courier_id=курьер_id, status="delivered",
            from_city="Акъяр", to_city="Сибай", description="коробка",
            delivery_type="courier", price=60000,
            accepted_at=utcnow(), delivered_at=utcnow(),
            commission_kop=комиссия_коп, commission_paid=True, settled=True)
        s.add(p)
        s.commit()
        s.refresh(p)
        return p.id


def _жалоба_по_доставке(client, курьер, админ, parcel_id: int) -> None:
    with Session(engine) as s:
        r = Report(reporter_id=курьер["id"], category="unpaid", parcel_id=parcel_id,
                   status="new", created_at=utcnow(), text="получатель не рассчитался")
        s.add(r)
        s.commit()
        s.refresh(r)
        rid = r.id
    ответ = client.post(f"/admin/reports/{rid}/resolve", headers=админ["auth"], json={})
    assert ответ.status_code == 200, ответ.text


def test_уже_оплаченную_комиссию_за_доставку_возвращают(client, user_factory):
    """Главное: курьеру не заплатили, а комиссию за эту доставку он уже перевёл."""
    админ = user_factory("ДоставкаАдмин", role=UserRole.admin)
    курьер = user_factory("КинулиКурьера", role=UserRole.driver)
    отправитель = user_factory("ОтправительКинул")
    комиссия = 5000                                       # 50 ₽
    pid = _доставка_оплаченная_комиссия(курьер["id"], отправитель["id"], комиссия)

    with Session(engine) as s:
        было = driver_balance(s, курьер["id"])

    _жалоба_по_доставке(client, курьер, админ, pid)

    with Session(engine) as s:
        стало = driver_balance(s, курьер["id"])

    assert стало - было >= комиссия, (
        f"комиссия {комиссия / 100:.2f} ₽ за доставку, по которой курьеру не заплатили, "
        f"осталась у платформы: поле обнулили, а деньги уже были переведены. "
        f"В кошельке {(стало - было) / 100:.2f} ₽"
    )


def test_неоплаченную_комиссию_за_доставку_просто_снимают(client, user_factory):
    """Обратная сторона: не успел оплатить — просто снимаем, кошелёк не трогаем."""
    from app.models import ParcelDelivery

    админ = user_factory("ДоставкаАдмин2", role=UserRole.admin)
    курьер = user_factory("КинулиКурьера2", role=UserRole.driver)
    отправитель = user_factory("ОтправительКинул2")
    with Session(engine) as s:
        p = ParcelDelivery(sender_id=отправитель["id"], courier_id=курьер["id"],
                           status="delivered", from_city="Акъяр", to_city="Сибай",
                           description="коробка", delivery_type="courier", price=60000,
                           accepted_at=utcnow(), delivered_at=utcnow(),
                           commission_kop=5000, commission_paid=False, settled=True)
        s.add(p)
        s.commit()
        s.refresh(p)
        pid, было = p.id, driver_balance(s, курьер["id"])

    _жалоба_по_доставке(client, курьер, админ, pid)

    with Session(engine) as s:
        assert driver_balance(s, курьер["id"]) == было, (
            "комиссию сняли И вернули деньгами — подарили дважды"
        )
        p = s.get(ParcelDelivery, pid)
        assert p.commission_kop == 0 and p.commission_paid, "комиссию не сняли"


# ---------------------------------------------------------------------------
# Разбор мутаций: проба доказывала только «деньги вернулись», и всё.
#
# Четыре мутации прошли мимо: возврат перестал быть идемпотентным, возврат уходил по
# НЕподтверждённому переводу, возврат уходил при нулевой сумме, и пуш всегда говорил
# «списана». Каждая — про деньги или про правду в тексте; договоры ниже закрывают все четыре.
# ---------------------------------------------------------------------------
def _уведомления(user_id: int) -> list:
    from app.models import Notification
    with Session(engine) as s:
        return s.exec(select(Notification).where(
            Notification.user_id == user_id).order_by(Notification.id.desc())).all()


def test_повторный_разбор_не_платит_дважды(client, user_factory, fake_redis):  # noqa: F811
    """Договор: на один заказ — один возврат, сколько бы раз разбор ни повторили.

    Ручку «подтвердить» админ может нажать дважды (промахнулся, обновил страницу, вернулся
    к жалобе). Деньги от этого удваиваться не должны — это прямой убыток платформы.
    """
    админ = user_factory("ПовторАдмин", role=UserRole.admin)
    водитель, _пассажир, заказ = _order_to_done(client, user_factory, fake_redis,
                                                dname="ПовторВодитель", pname="ПовторПассажир")
    комиссия = _долг(водитель["id"]).amount_kop
    with Session(engine) as s:
        было = driver_balance(s, водитель["id"])

    _водитель_оплатил_неделю(client, админ, водитель["id"])
    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])
    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])   # второй раз по тому же заказу

    with Session(engine) as s:
        стало = driver_balance(s, водитель["id"])

    assert стало - было == комиссия, (
        f"по одному заказу вернули {(стало - было) / 100:.2f} ₽ вместо "
        f"{комиссия / 100:.2f} ₽: повторный разбор платит второй раз"
    )


def test_по_незаподтверждённому_переводу_не_возвращаем(client, user_factory, fake_redis):  # noqa: F811
    """Договор: возвращаем только то, приход чего админ подтвердил.

    «Я оплатил» — это слово водителя, и админ его может отклонить (деньги не пришли).
    Вернуть по слову — значит подарить комиссию тому, чей перевод не дошёл, да ещё и снять
    с него долг: он и не платил, и не должен.
    """
    админ = user_factory("PendingАдмин", role=UserRole.admin)
    водитель, _пассажир, заказ = _order_to_done(client, user_factory, fake_redis,
                                                dname="PendingВодитель", pname="PendingПассажир")
    with Session(engine) as s:
        было = driver_balance(s, водитель["id"])
        debt_mod.declare_paid(s, водитель["id"])              # сказал «оплатил», админ ещё не смотрел
        assert _долг(водитель["id"]).status == DebtStatus.pending, "тест слеп"

    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])

    with Session(engine) as s:
        assert driver_balance(s, водитель["id"]) == было, (
            "вернули деньги по переводу, приход которого никто не подтвердил"
        )


def test_нулевую_комиссию_в_кошелёк_не_кладём(client, user_factory, fake_redis):  # noqa: F811
    """Договор: возврат нуля — это не возврат, а мусорная запись в истории денег.

    Кошелёк append-only и его читает человек. Нулевые строки в нём — шум, а отрицательные
    (если сумма однажды приедет со знаком) — тихий минус на балансе.
    """
    from app.models import LedgerEntry

    админ = user_factory("НульАдмин", role=UserRole.admin)
    водитель, _пассажир, заказ = _order_to_done(client, user_factory, fake_redis,
                                                dname="НульВодитель", pname="НульПассажир")
    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"])).first()
        d.amount_kop = 0                       # поездка по промо: комиссия целиком ушла в скидку
        d.status = DebtStatus.paid
        s.add(d)
        s.commit()
        строк_было = len(s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == водитель["id"])).all())

    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])

    with Session(engine) as s:
        строк_стало = len(s.exec(select(LedgerEntry).where(
            LedgerEntry.driver_id == водитель["id"])).all())
    assert строк_стало == строк_было, (
        "в кошелёк добавили запись на 0 ₽ — мусор в истории денег, которую читает человек"
    )


def test_текст_говорит_вернули_а_не_списали(client, user_factory, fake_redis):  # noqa: F811
    """Договор ради которого волна и случилась: человеку нельзя врать про его деньги.

    «Комиссию списали» тому, кто её уже перевёл, — это сообщение о том, чего не было.
    Он прочитает его и не пойдёт искать деньги, потому что решит, что их и не брали.
    """
    админ = user_factory("ТекстАдмин", role=UserRole.admin)
    водитель, _пассажир, заказ = _order_to_done(client, user_factory, fake_redis,
                                                dname="ТекстВодитель", pname="ТекстПассажир")
    _водитель_оплатил_неделю(client, админ, водитель["id"])
    _жалоба_не_заплатил(client, водитель, админ, заказ["id"])

    свежее = [n for n in _уведомления(водитель["id"]) if "омисси" in (n.title_ru or "")]
    assert свежее, "человеку вообще ничего не сказали про его комиссию"
    title, body = свежее[0].title_ru, свежее[0].body_ru

    assert "верн" in title.lower() or "верн" in body.lower(), (
        f"человеку написали «{title}» — а комиссию он уже перевёл, и её ВЕРНУЛИ в кошелёк. "
        "Прочитав «списали», он не пойдёт искать деньги: решит, что их и не брали"
    )
    assert (свежее[0].title_ba or "").strip(), "башкирская версия текста пустая"
