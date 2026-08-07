# -*- coding: utf-8 -*-
"""Денежные дыры аудита 2026-08-07: три способа не заплатить платформе.

Все три — про единственный реальный доход Юлдаша (комиссия с такси) и про кошелёк водителя:

1. Двойная компенсация промокода. Скидку пассажиру оплачивает платформа: остаток скидки,
   не влезший в комиссию, кладётся водителю в кошелёк. Проверка «уже начисляли?» жила
   только в коде, без барьера в БД — два одновременных «Завершил» клали компенсацию ДВАЖДЫ.
2. Обход комиссии «не нажал Завершил». Ночная чистка сама закрывала забытую поездку как
   состоявшуюся (done), но комиссию не начисляла → одна бесплатная поездка в сутки, бесконечно.
3. Бесконечный цикл «Я оплатил». Долг в pending не блокировал и не имел срока: раз в неделю
   нажал кнопку — и работаешь дальше, не заплатив ни разу.
"""
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlalchemy.exc import IntegrityError
from sqlmodel import Session, select

from app import cleanup, debt as debt_mod, ledger
from app.config import settings
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus as S,
    LedgerEntry, LedgerKind, UserRole,
)
from app.timeutil import utcnow


# ------------------------------ helpers ------------------------------
def _order(passenger_id: int, driver_id: int, *, status=S.done, price: int = 700, **kw) -> int:
    """Такси-заказ прямо в БД (минуем матчер — тут проверяем только деньги)."""
    base = dict(passenger_id=passenger_id, driver_id=driver_id, status=status,
                from_lat=52.59, from_lng=58.31, to_lat=52.60, to_lng=58.32,
                from_text="Баймак", to_text="Сибай",
                price_estimate=price, price_final=price)
    base.update(kw)
    with Session(engine) as s:
        o = InstantOrder(**base)
        s.add(o)
        s.commit()
        s.refresh(o)
        return o.id


def _comp_rows(order_id: int) -> list[LedgerEntry]:
    ext = ledger.promo_comp_ext_id(order_id)
    with Session(engine) as s:
        return list(s.exec(select(LedgerEntry).where(
            LedgerEntry.kind == LedgerKind.adj, LedgerEntry.ext_id == ext)).all())


def _debt_row(order_id: int):
    with Session(engine) as s:
        return s.exec(select(CommissionDebt).where(CommissionDebt.order_id == order_id)).first()


def _make_debt(driver_id: int, amount=200_000, status=DebtStatus.unpaid, **kw):
    with Session(engine) as s:
        d = CommissionDebt(driver_id=driver_id, amount_kop=amount, week="2026-W32",
                           status=status, **kw)
        s.add(d)
        s.commit()
        s.refresh(d)
        return d.id


# ============ 1. Компенсацию промокода нельзя начислить дважды ============
def test_бд_не_даёт_две_компенсации_промокода_по_одному_заказу(client, user_factory):
    """Барьер должен стоять в БД, а не только в коде: проверка «уже начисляли?» и вставка —
    два разных шага, между ними успевает вклиниться параллельный запрос."""
    drv = user_factory("PromoDupDrv", role=UserRole.driver)
    pax = user_factory("PromoDupPax")
    oid = _order(pax["id"], drv["id"], done_at=utcnow(), promo_discount_kop=30_000)
    ext = ledger.promo_comp_ext_id(oid)

    def _add(session):
        session.add(LedgerEntry(driver_id=drv["id"], order_id=oid, kind=LedgerKind.adj,
                                amount_kop=27_900, ext_id=ext,
                                note="Компенсация промокода пассажира"))
        session.commit()

    with Session(engine) as s:
        _add(s)                                   # первая компенсация — законна
    with Session(engine) as s:
        with pytest.raises(IntegrityError):       # вторая по тому же заказу — БД обязана отбить
            _add(s)
    assert len(_comp_rows(oid)) == 1


def test_гонка_двух_завершил_не_задваивает_компенсацию(client, user_factory):
    """Ровно сценарий дабл-тапа: обе сессии увидели «компенсации нет» и обе пишут свою.
    Соперника вклиниваем перед самой вставкой — так выглядит проигранная гонка изнутри."""
    drv = user_factory("PromoRaceDrv", role=UserRole.driver)
    pax = user_factory("PromoRacePax")
    oid = _order(pax["id"], drv["id"], done_at=utcnow(), promo_discount_kop=30_000)

    session = Session(engine)
    fired = {"n": 0}

    @event.listens_for(session, "before_flush")
    def _rival(sess, ctx, instances):             # noqa: ARG001
        """Конкурент успел закоммитить свою компенсацию между нашей проверкой и вставкой."""
        if fired["n"]:
            return
        fired["n"] = 1
        with Session(engine) as rival:
            rival.add(LedgerEntry(driver_id=drv["id"], order_id=oid, kind=LedgerKind.adj,
                                  amount_kop=27_900, ext_id=ledger.promo_comp_ext_id(oid),
                                  note="Компенсация промокода пассажира"))
            rival.commit()

    try:
        entry = ledger.post_promo_compensation(session, drv["id"], oid, 27_900)
    finally:
        event.remove(session, "before_flush", _rival)
        session.close()

    assert fired["n"] == 1, "соперник не вклинился — тест ничего не проверил"
    assert entry is not None                       # вызывающий код получает запись, а не падение
    assert len(_comp_rows(oid)) == 1               # компенсация ровно одна
    with Session(engine) as s:
        assert ledger.driver_balance(s, drv["id"]) == 27_900   # а не 55 800


# ============ 2. «Не нажал Завершил» больше не бесплатно ============
def test_авто_закрытая_поездка_начисляет_комиссию(client, user_factory):
    """Водитель не жмёт «Завершил» — ночная чистка сама закрывает поездку как состоявшуюся.
    Раз мы считаем её состоявшейся (done), комиссия по ней должна начисляться: иначе
    последняя поездка за день бесплатна всегда, и это повторяемо каждые сутки."""
    drv = user_factory("StaleFeeDrv", role=UserRole.driver)
    pax = user_factory("StaleFeePax")
    old = utcnow() - timedelta(hours=settings.taxi_stale_hours + 1)
    oid = _order(pax["id"], drv["id"], status=S.onboard, price=400, created_at=old)

    cleanup.close_stale_orders()

    with Session(engine) as s:
        o = s.get(InstantOrder, oid)
        assert o.status == S.done                  # закрытие как было
        assert o.done_at is not None               # иначе поездка выпадает из заработка и стажа
    d = _debt_row(oid)
    assert d is not None, "комиссия за авто-закрытую поездку не начислена — поездка бесплатна"
    assert d.amount_kop == 1_200                   # 400 ₽ × 3% (первая ступень)
    assert d.status == DebtStatus.unpaid


def test_авто_закрытие_не_задваивает_комиссию(client, user_factory):
    """Чистка идемпотентна: второй прогон не должен начислить комиссию ещё раз."""
    drv = user_factory("StaleFeeDrv2", role=UserRole.driver)
    pax = user_factory("StaleFeePax2")
    old = utcnow() - timedelta(hours=settings.taxi_stale_hours + 1)
    oid = _order(pax["id"], drv["id"], status=S.onboard, price=400, created_at=old)
    cleanup.close_stale_orders()
    cleanup.close_stale_orders()
    with Session(engine) as s:
        rows = s.exec(select(CommissionDebt).where(CommissionDebt.order_id == oid)).all()
    assert len(rows) == 1


def test_отменённая_авто_закрытием_поездка_комиссию_не_берёт(client, user_factory):
    """accepted/arriving закрываются как cancelled — человек не сел в машину, поездки не было.
    Тут комиссии быть НЕ должно, иначе водитель платит за несостоявшийся заказ."""
    drv = user_factory("StaleNoFeeDrv", role=UserRole.driver)
    pax = user_factory("StaleNoFeePax")
    old = utcnow() - timedelta(hours=settings.taxi_stale_hours + 1)
    oid = _order(pax["id"], drv["id"], status=S.accepted, price=400, created_at=old)
    cleanup.close_stale_orders()
    assert _debt_row(oid) is None


# ============ 3. У «слова» водителя есть срок ============
def test_протухшее_слово_снова_блокирует_такси(client, user_factory):
    """«Я оплатил» снимает блок на доверии — но не навсегда. Если деньги так и не подтвердились
    за DECLARE_TRUST_DAYS, водитель снова заблокирован: иначе pending живёт вечно."""
    drv = user_factory("StaleDeclDrv", role=UserRole.driver)
    _make_debt(drv["id"], amount=200_000, due_at=utcnow() - timedelta(days=1))
    with Session(engine) as s:
        assert debt_mod.taxi_block_reason(s, drv["id"]) == "overdue"
        debt_mod.declare_paid(s, drv["id"])
        assert debt_mod.taxi_block_reason(s, drv["id"]) is None      # слово свежее — верим
    # Состариваем заявление: админ так и не подтвердил.
    with Session(engine) as s:
        d = s.exec(select(CommissionDebt).where(CommissionDebt.driver_id == drv["id"])).first()
        d.paid_declared_at = utcnow() - timedelta(days=debt_mod.DECLARE_TRUST_DAYS + 1)
        s.add(d)
        s.commit()
    with Session(engine) as s:
        assert debt_mod.taxi_block_reason(s, drv["id"]) == "declare_stale"


def test_чистка_возвращает_протухшее_слово_в_неоплаченные(client, user_factory):
    """Долг не должен зависать в pending навсегда: иначе админ обязан руками отклонять каждый,
    а водитель без ответа админа заблокирован без выхода."""
    drv = user_factory("StaleDeclDrv2", role=UserRole.driver)
    did = _make_debt(drv["id"], amount=50_000, status=DebtStatus.pending, declare_count=1,
                     paid_declared_at=utcnow() - timedelta(days=debt_mod.DECLARE_TRUST_DAYS + 1))
    fresh = _make_debt(drv["id"], amount=10_000, status=DebtStatus.pending, declare_count=1,
                       paid_declared_at=utcnow())
    # База в тестах одна на всю сессию — считаем не глобальный счётчик, а судьбу СВОИХ строк.
    assert cleanup.expire_stale_declares() >= 1
    with Session(engine) as s:
        assert s.get(CommissionDebt, did).status == DebtStatus.unpaid
        assert s.get(CommissionDebt, did).declare_count == 1     # счётчик обещаний не сбрасываем
        assert s.get(CommissionDebt, fresh).status == DebtStatus.pending


def test_бесконечный_цикл_я_оплатил_закрывается_сам(client, user_factory):
    """Главное следствие срока: даже если админ не нажал ни одной кнопки, доверие кончается.
    Раньше водитель мог не платить НИКОГДА — раз в неделю жал «Я оплатил» и работал дальше."""
    drv = user_factory("LoopDrv", role=UserRole.driver)
    _make_debt(drv["id"], amount=200_000, due_at=utcnow() - timedelta(days=1))
    stale = timedelta(days=debt_mod.DECLARE_TRUST_DAYS + 1)
    for _ in range(settings.debt_max_declares + 1):
        with Session(engine) as s:
            debt_mod.declare_paid(s, drv["id"])
        with Session(engine) as s:                 # проходит срок доверия, деньги не пришли
            for d in s.exec(select(CommissionDebt).where(
                    CommissionDebt.driver_id == drv["id"],
                    CommissionDebt.status == DebtStatus.pending)).all():
                d.paid_declared_at = utcnow() - stale
                s.add(d)
            s.commit()
        cleanup.expire_stale_declares()
    with Session(engine) as s:
        debt_mod.declare_paid(s, drv["id"])
        # Слово давали больше лимита — кнопка больше не снимает блокировку.
        assert debt_mod.taxi_block_reason(s, drv["id"]) == "declare_abuse"


# ============ 4. Отменённый платёж нельзя оживить ============
def test_отменённый_платёж_не_активируется(client, user_factory):
    """Пассажир создал безнал, передумал и заплатил налом — наш платёж отменён, но ссылка
    ЮKassa жива. Оплата по старой ссылке не должна «воскрешать» отменённый платёж:
    деньги остались бы у платформы, водителю не начислилось бы ничего, а платёж числился
    бы успешным."""
    from app.models import Payment
    from app.routers.payments import _activate_payment

    drv = user_factory("CancelPayDrv", role=UserRole.driver)
    pax = user_factory("CancelPayPax")
    oid = _order(pax["id"], drv["id"], done_at=utcnow(), price=500)
    with Session(engine) as s:
        p = Payment(user_id=pax["id"], purpose="ride", order_id=oid, amount_kop=50_000,
                    method="yookassa", status="canceled")
        s.add(p)
        s.commit()
        s.refresh(p)
        pid = p.id
        _activate_payment(s, p)
    with Session(engine) as s:
        assert s.get(Payment, pid).status == "canceled"       # остался отменённым
        assert s.get(InstantOrder, oid).paid is False         # заказ не помечен оплаченным
        assert s.exec(select(LedgerEntry).where(LedgerEntry.order_id == oid)).all() == []


# ============ 5. Прощённая комиссия не вычитается из заработка ============
def test_прощённая_комиссия_не_показана_удержанной(client, user_factory):
    """Админ списал комиссию (пассажир не заплатил, спор) — водителю пришёл пуш «долг списан»,
    а экран расшифровки всё равно вычитал её из «чистыми». Это ровно тот экран, который
    построили закрывать спор о деньгах, — и он врал в минус водителю."""
    drv = user_factory("ForgivenFeeDrv", role=UserRole.driver)
    pax = user_factory("ForgivenFeePax")
    admin = user_factory("ForgivenFeeAdmin", role=UserRole.admin)
    oid = _order(pax["id"], drv["id"], done_at=utcnow(), price=400)
    with Session(engine) as s:
        d = debt_mod.accrue_for_order(s, s.get(InstantOrder, oid))
        did, fee = d.id, d.amount_kop
    assert fee == 1_200
    with Session(engine) as s:                                 # до прощения комиссия видна
        row = debt_mod.driver_rides(s, drv["id"])["rides"][0]
        assert row["fee_kop"] == fee and row["net_kop"] == 40_000 - fee

    r = client.post(f"/admin/debts/{did}/forgive", headers=admin["auth"],
                    json={"reason": "пассажир не заплатил"})
    assert r.status_code == 200

    with Session(engine) as s:
        row = debt_mod.driver_rides(s, drv["id"])["rides"][0]
        assert row["fee_kop"] == 0, "списанная комиссия всё ещё вычитается из заработка"
        assert row["net_kop"] == 40_000
        assert debt_mod.driver_dashboard(s, drv["id"])["fee_today_kop"] == 0


def test_комиссия_удержанная_онлайн_остаётся_в_расшифровке(client, user_factory):
    """Обратная сторона: при оплате картой долг тоже гасится (комиссия ушла через кошелёк).
    Вот её показывать НАДО — иначе расшифровка завысит заработок."""
    from app import ledger as ledger_mod

    drv = user_factory("OnlineFeeDrv", role=UserRole.driver)
    pax = user_factory("OnlineFeePax")
    oid = _order(pax["id"], drv["id"], done_at=utcnow(), price=400)
    with Session(engine) as s:
        debt_mod.accrue_for_order(s, s.get(InstantOrder, oid))
    with Session(engine) as s:
        assert ledger_mod.settle_instant_order(s, oid, "card", 40_000) == "settled"
    with Session(engine) as s:
        row = debt_mod.driver_rides(s, drv["id"])["rides"][0]
        assert row["fee_kop"] == 1_200          # комиссия реально удержана — показываем


# ============ 6. Показы рекламы не накручиваются ============
def test_показ_не_засчитывается_невидимому_объявлению(client, user_factory):
    """Событие по объявлению, которого публика не видела (черновик) или у которого срок вышел, —
    не показ, а мусор в статистике: им можно уронить CTR в кабинете и раздуть таблицу событий."""
    from app.models import Ad, AdEvent

    u = user_factory("AdEventUser")
    with Session(engine) as s:
        draft = Ad(owner_id=u["id"], title="Черновик", text="т", status="draft", package="city")
        over = Ad(owner_id=u["id"], title="Истекла", text="т", status="active", package="city",
                  starts_at=utcnow() - timedelta(days=40), ends_at=utcnow() - timedelta(days=1))
        live = Ad(owner_id=u["id"], title="Живая", text="т", status="active", package="city",
                  starts_at=utcnow() - timedelta(days=1), ends_at=utcnow() + timedelta(days=1))
        s.add(draft)
        s.add(over)
        s.add(live)
        s.commit()
        s.refresh(draft)
        s.refresh(over)
        s.refresh(live)
        draft_id, over_id, live_id = draft.id, over.id, live.id

    for aid in (draft_id, over_id, live_id):
        assert client.post(f"/ads/{aid}/event", headers=u["auth"],
                           json={"type": "impression"}).status_code == 200
    with Session(engine) as s:
        assert s.exec(select(AdEvent).where(AdEvent.ad_id == draft_id)).all() == []
        assert s.exec(select(AdEvent).where(AdEvent.ad_id == over_id)).all() == []
        assert len(s.exec(select(AdEvent).where(AdEvent.ad_id == live_id)).all()) == 1
