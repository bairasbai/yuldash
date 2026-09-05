"""Два решения Александра про деньги: попутка бесплатна, кошелёк не мёртвый (волна 154).

**Попутка брала 8%, если пассажир нажал «картой».** Наличными водитель получал всю тысячу,
картой — 920 ₽, и разницу ему нигде не объясняли. Один и тот же сосед, одна и та же поездка,
а на руках по-разному — просто потому, что сосед выбрал удобный способ оплаты. При этом сайт
и оферта в четырёх местах обещают «0 ₽ комиссия сервиса», а сервер при запуске сам ругался
на это расхождение. Попутка — то, ради чего люди приходят в Юлдаш; зарабатываем на такси
и доставке. Ставка попутки теперь своя и равна нулю.

**Компенсация промо-скидки лежала в кошельке мёртвым числом.** Пассажир поехал по промокоду
со скидкой: заплатил водителю 320 ₽ наличными, а недостающие 281,40 ₽ платформа положила
водителю в кошелёк. Снять их нельзя — выплаты на карту выключены до оформления ИП. В счёт
комиссии они тоже не шли: комиссия платится отдельным переводом по СБП. Водитель видел
«у меня 281,40 ₽», тронуть не мог — и одновременно ДОЛЖЕН был платформе 18,60 ₽ за ту же
поездку. Теперь деньги платформы у водителя гасят долг водителя платформе.
"""
from __future__ import annotations

from datetime import timedelta

from sqlmodel import Session, select

from app import ledger
from app.config import settings
from app.db import engine
from app.debt import WALLET_PAID_NOTE, fee_charged_kop, settle_debt_from_wallet
from app.models import (
    Booking, BookingStatus, CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus,
    LedgerEntry, LedgerKind, UserRole,
)
from app.timeutil import utcnow

from test_api import _ride


def _завершённая_бронь(client, user_factory, метка: str) -> int:
    водитель = user_factory(метка + "Водитель", role=UserRole.driver)
    сосед = user_factory(метка + "Сосед")
    ride_id = _ride(client, водитель, comment="до города")
    bid = client.post("/bookings", headers=сосед["auth"],
                      json={"ride_id": ride_id, "seats": 1}).json()["id"]
    client.post(f"/bookings/{bid}/confirm", headers=водитель["auth"])
    with Session(engine) as s:
        b = s.get(Booking, bid)
        b.status = BookingStatus.done
        s.add(b)
        s.commit()
    return bid


def _записи(booking_id: int) -> dict:
    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(LedgerEntry.booking_id == booking_id)).all()
    return {r.kind: r.amount_kop for r in rows}


def test_попутка_картой_не_берёт_комиссию(client, user_factory):
    """Главное: сосед платит картой — водитель получает столько же, сколько наличными."""
    bid = _завершённая_бронь(client, user_factory, "Бесплатно")

    with Session(engine) as s:
        ledger.settle_booking(s, bid, "yookassa", 100_000)      # 1000 ₽ картой

    записи = _записи(bid)
    удержано = -записи.get(LedgerKind.fee, 0)
    assert удержано == 0, (
        f"с попутки удержали {удержано / 100:g} ₽, хотя сайт и оферта обещают «0 ₽ комиссия "
        "сервиса»: наличными водитель получил бы всю сумму, а картой — меньше, и нигде "
        "об этом не сказано"
    )
    assert записи.get(LedgerKind.earn) == 100_000, "водителю начислили не всю сумму поездки"


def test_такси_картой_комиссию_берёт(client, user_factory):
    """Обратная сторона: на такси сервис зарабатывает, и это не должно было сломаться."""
    водитель = user_factory("ТаксиСтавка", role=UserRole.driver)
    пассажир = user_factory("ТаксиСтавкаПассажир")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         status=InstantOrderStatus.done, price_estimate=1000, price_final=1000,
                         done_at=utcnow())
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
    with Session(engine) as s:
        ledger.settle_instant_order(s, oid, "yookassa", 100_000)

    with Session(engine) as s:
        rows = s.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all()
    удержано = -sum(r.amount_kop for r in rows if r.kind == LedgerKind.fee)
    assert удержано > 0, (
        "с такси комиссию не взяли — вместе с попуткой обнулили и то, на чём сервис живёт"
    )


def _водитель_с_кошельком(user_factory, метка: str, кошелёк_коп: int, долг_коп: int,
                          возраст_долга=None, оплачен: bool = True):
    """Водитель, у которого в кошельке лежит компенсация промо, а за ту же поездку висит долг.

    Заказ по умолчанию оплачен наличными — это и есть жизненная ситуация: пассажир отдал
    деньги в руки, комиссию водитель должен платформе."""
    водитель = user_factory(метка + "Водитель", role=UserRole.driver)
    пассажир = user_factory(метка + "Пассажир")
    with Session(engine) as s:
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         status=InstantOrderStatus.done, price_estimate=620, price_final=620,
                         done_at=utcnow(), paid=оплачен,
                         payment_method="cash" if оплачен else "")
        s.add(o)
        s.commit()
        s.refresh(o)
        oid = o.id
        s.add(LedgerEntry(driver_id=водитель["id"], kind=LedgerKind.adj, amount_kop=кошелёк_коп,
                          ext_id=f"тест-кошелька:{oid}", note="Компенсация промокода пассажира"))
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=oid, week="2026-W34",
                             amount_kop=долг_коп, status=DebtStatus.unpaid,
                             created_at=возраст_долга or utcnow(), due_at=utcnow()))
        s.commit()
    return водитель, oid


def test_кошелёк_гасит_долг_по_комиссии(client, user_factory):
    """Главное: деньги платформы у водителя закрывают долг водителя платформе."""
    водитель, _ = _водитель_с_кошельком(user_factory, "Зачёт", 28_140, 1_860)

    ответ = client.get("/driver/debt", headers=водитель["auth"])

    assert ответ.json()["unpaid_kop"] == 0, (
        f"в кошельке 281,40 ₽, а долг {ответ.json()['unpaid_kop'] / 100:g} ₽ висит "
        "неоплаченным: тронуть деньги нельзя, и при этом ты должен"
    )
    assert ответ.json()["blocked"] is False, "такси заблокировано долгом, который уже покрыт"


def test_списание_из_кошелька_уменьшает_баланс(client, user_factory):
    """Деньги не берутся из воздуха: долг закрыт — столько же ушло из кошелька."""
    водитель, _ = _водитель_с_кошельком(user_factory, "Баланс", 28_140, 1_860)

    with Session(engine) as s:
        было = ledger.driver_balance(s, водитель["id"])
        снято = settle_debt_from_wallet(s, водитель["id"])
        стало = ledger.driver_balance(s, водитель["id"])

    assert снято == 1_860, f"погасили {снято} коп вместо долга 1860 коп"
    assert было - стало == 1_860, (
        f"долг закрыт, а кошелёк не похудел ({было} → {стало}): деньги появились из воздуха"
    )


def test_повторный_заход_не_списывает_дважды(client, user_factory):
    """Водитель открывает экран долга сто раз в день — кошелёк не должен таять."""
    водитель, _ = _водитель_с_кошельком(user_factory, "Дважды", 28_140, 1_860)

    client.get("/driver/debt", headers=водитель["auth"])
    with Session(engine) as s:
        после_первого = ledger.driver_balance(s, водитель["id"])
    client.get("/driver/debt", headers=водитель["auth"])
    client.get("/driver/debt", headers=водитель["auth"])

    with Session(engine) as s:
        после_трёх = ledger.driver_balance(s, водитель["id"])
    assert после_трёх == после_первого, (
        f"кошелёк тает от захода на экран: {после_первого} → {после_трёх}"
    )


def test_комиссия_остаётся_видна_в_расшифровке(client, user_factory):
    """Комиссию тут ВЗЯЛИ. Спрятать её — значит завысить «заработано» и снова спорить с водителем."""
    водитель, oid = _водитель_с_кошельком(user_factory, "Видно", 28_140, 1_860)

    with Session(engine) as s:
        settle_debt_from_wallet(s, водитель["id"])
        долг = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).first()

    assert долг.status == DebtStatus.paid, "долг не закрылся"
    assert долг.note == WALLET_PAID_NOTE, f"причина закрытия долга не записана: {долг.note!r}"
    assert fee_charged_kop(долг) == 1_860, (
        "в расшифровке заработка комиссия исчезла — водитель увидит «чистыми» больше, "
        "чем получил, и придёт спорить"
    )


def test_не_хватает_на_старый_долг_деньги_ждут(client, user_factory):
    """Гасим по старшинству и только целиком: суммы долга не переписываем."""
    водитель, _ = _водитель_с_кошельком(user_factory, "Очередь", 500, 5_000,
                                        возраст_долга=utcnow() - timedelta(days=10))

    with Session(engine) as s:
        снято = settle_debt_from_wallet(s, водитель["id"])
        баланс = ledger.driver_balance(s, водитель["id"])

    assert снято == 0, f"списали {снято} коп, хотя на долг 5000 коп в кошельке лежит только 500"
    assert баланс == 500, "кошелёк тронули, а долг не закрыли — деньги пропали в никуда"


def test_очередь_долгов_соблюдается(client, user_factory):
    """Старый долг вперёд свежего: иначе он остаётся висеть и продолжает блокировать такси."""
    водитель, _ = _водитель_с_кошельком(user_factory, "Порядок", 900, 5_000,
                                        возраст_долга=utcnow() - timedelta(days=10))
    пассажир = user_factory("ПорядокСвежий")
    with Session(engine) as s:                       # свежий мелкий долг, на него денег хватает
        o = InstantOrder(passenger_id=пассажир["id"], driver_id=водитель["id"],
                         status=InstantOrderStatus.done, price_estimate=200, price_final=200,
                         done_at=utcnow(), paid=True, payment_method="cash")
        s.add(o)
        s.commit()
        s.refresh(o)
        s.add(CommissionDebt(driver_id=водитель["id"], order_id=o.id, week="2026-W35",
                             amount_kop=800, status=DebtStatus.unpaid,
                             created_at=utcnow(), due_at=utcnow()))
        s.commit()

    with Session(engine) as s:
        снято = settle_debt_from_wallet(s, водитель["id"])
        старый = s.exec(select(CommissionDebt).where(
            CommissionDebt.driver_id == водитель["id"],
            CommissionDebt.amount_kop == 5_000,
        )).first()

    assert снято == 0, (
        f"списали {снято} коп на свежий мелкий долг, перепрыгнув старый на 50 ₽: старый остался "
        "висеть, такси так и заблокировано, а денег в кошельке уже нет"
    )
    assert старый.status == DebtStatus.unpaid, "старый долг закрыли деньгами, которых не хватало"


def test_ровно_хватило_значит_гасим(client, user_factory):
    """Обратная сторона: копейка в копейку — тоже хватило, деньги не должны застрять."""
    водитель, _ = _водитель_с_кошельком(user_factory, "ВПритык", 1_860, 1_860)

    with Session(engine) as s:
        снято = settle_debt_from_wallet(s, водитель["id"])
        баланс = ledger.driver_balance(s, водитель["id"])

    assert снято == 1_860, (
        "в кошельке ровно столько, сколько долг, а долг не закрылся — деньги застряли "
        "из-за перестраховки на копейку"
    )
    assert баланс == 0, f"после точного зачёта в кошельке осталось {баланс} коп"


def test_пустой_кошелёк_ничего_не_гасит(client, user_factory):
    """Обратная сторона: без денег долг обязан остаться долгом."""
    водитель, _ = _водитель_с_кошельком(user_factory, "Пусто", 0, 1_860)

    ответ = client.get("/driver/debt", headers=водитель["auth"])

    assert ответ.json()["unpaid_kop"] == 1_860, (
        "долг закрылся сам собой при пустом кошельке — платформа подарила себе минус"
    )


def test_долг_по_неоплаченному_заказу_кошелёк_не_трогает(client, user_factory):
    """Иначе комиссию возьмут дважды: сперва из кошелька, потом при оплате картой.

    Долг заводится в момент «Завершил», а чем пассажир заплатит — известно позже. Оплатил
    картой — комиссия удерживается записью в кошельке, а долг снимается как фиктивный. Погаси
    мы его заранее, водитель заплатил бы за одну поездку два раза.
    """
    водитель, _ = _водитель_с_кошельком(user_factory, "Рано", 28_140, 1_860, оплачен=False)

    with Session(engine) as s:
        снято = settle_debt_from_wallet(s, водитель["id"])

    assert снято == 0, (
        f"погасили {снято} коп по заказу, за который ещё не расплатились: заплатит картой — "
        "и комиссия спишется с водителя второй раз"
    )


def test_поднятая_комиссия_попутки_кричит_в_предупреждении(monkeypatch):
    """Сторож обещания: подняли ставку попутки — сервер обязан назвать это при запуске."""
    monkeypatch.setattr(settings, "ride_service_fee_percent", 5.0)

    предупреждения = " ".join(settings.launch_warnings())

    assert "попутка" in предупреждения.lower(), (
        "ставку попутки подняли молча: сайт продолжает обещать «0 ₽ комиссия сервиса», "
        f"а код берёт 5% — предупреждения нет. Что вывелось: {предупреждения[:200]!r}"
    )


def test_нулевая_ставка_попутки_молчит():
    """Обратная сторона: пока попутка бесплатна, ругаться не на что."""
    предупреждения = " ".join(settings.launch_warnings())

    assert "попутка" not in предупреждения.lower(), (
        f"ругаемся на попутку при нулевой ставке: {предупреждения[:200]!r}"
    )
