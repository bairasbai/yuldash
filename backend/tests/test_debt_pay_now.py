"""Комиссия за дальнюю поездку гасится СРАЗУ, а не в недельном цикле (решение 2026-08-21).

Зачем правило. При комиссии 15% дальний межгород (Сибай→Уфа ≈ 4 300 ₽) даёт долг в сотни
рублей с ОДНОЙ поездки — двух таких хватает, чтобы упереться в порог блокировки. И главное:
деньги за поездку у водителя в руках именно в момент её окончания, а через неделю их уже нет.

Что здесь проверяется — места, где такое правило ломается о живого человека:
- ночь: поездка кончилась в час ночи → срок не «через три часа», а с утра (банк спит с человеком);
- автомат: поездку закрыла ночная чистка, а не водитель → короткий срок не ставим вообще;
- история: подняли порог в конфиге → уже выданные обещания «неделя» не переписываются задним числом;
- граница утра: 6:59 ждёт до утра СЕГОДНЯ, 7:00 не ждёт вовсе;
- предупреждение: короткий срок блокирует только того, у кого был шанс о нём узнать, а долг,
  подошедший к порогу, сообщает о себе ДО блокировки и ровно один раз.
"""
from datetime import timedelta

from sqlmodel import Session

from app import debt as debt_mod
from app.config import settings
from app.db import engine
from app.models import (
    CommissionDebt, DebtStatus, InstantOrder, InstantOrderStatus, User, UserRole,
)
from app.timeutil import utcnow

TZ = 5  # Уфа, UTC+5 (settings.local_tz_offset_hours)


def _utc_at_local_hour(hour: int):
    """Наивный UTC-момент, которому в Уфе соответствует ровно `hour` часов."""
    base = utcnow().replace(minute=0, second=0, microsecond=0)
    return base.replace(hour=(hour - TZ) % 24)


# ------------------------------ размер комиссии решает срок ------------------------------
def test_big_commission_is_due_within_hours_not_a_week():
    """Крупная комиссия днём: срок — часы, а не неделя."""
    now = _utc_at_local_hour(14)                       # два часа дня по Уфе — не тихий час
    due = debt_mod._due_at(now, settings.debt_now_threshold_kop)
    assert due == now + timedelta(hours=settings.debt_now_grace_hours)
    assert due - now < timedelta(days=1)


def test_ordinary_commission_keeps_the_weekly_cycle():
    """Обычная городская поездка недельный цикл не теряет — правило не должно задеть её."""
    now = _utc_at_local_hour(14)
    due = debt_mod._due_at(now, settings.debt_now_threshold_kop - 1)
    assert due == now + timedelta(days=settings.debt_due_days)


# ------------------------------ ночь ------------------------------
# В тестовой среде тихие часы выключены (quiet_hours_to=0), поэтому включаем их явно —
# иначе «ночные» проверки молча проверяли бы дневное поведение и всегда были бы зелёными.
def _quiet_on(monkeypatch, frm=22, to=7):
    monkeypatch.setattr(settings, "quiet_hours_from", frm)
    monkeypatch.setattr(settings, "quiet_hours_to", to)


def test_night_ride_is_not_due_before_morning(monkeypatch):
    """Поездка закончилась в час ночи — срок начинает течь с утра, а не сразу.

    Иначе такси закрывается к четырём утра за то, что водитель не сделал перевод посреди ночи.
    """
    _quiet_on(monkeypatch)
    night = _utc_at_local_hour(1)
    due = debt_mod._due_at(night, settings.debt_now_threshold_kop)
    local_due_hour = (due + timedelta(hours=TZ)).hour
    assert local_due_hour == settings.quiet_hours_to + settings.debt_now_grace_hours
    assert due > night + timedelta(hours=settings.debt_now_grace_hours)
    # И всё же это по-прежнему «сразу», а не недельный цикл.
    assert due - night < timedelta(days=1)


def test_late_evening_ride_rolls_over_to_next_morning(monkeypatch):
    """23:00 — тихий час ДО полуночи: утро наступает уже следующим днём, а не сегодняшним."""
    _quiet_on(monkeypatch)
    evening = _utc_at_local_hour(23)
    due = debt_mod._due_at(evening, settings.debt_now_threshold_kop)
    assert due > evening
    assert due - evening < timedelta(days=1)
    assert (due + timedelta(hours=TZ)).hour == settings.quiet_hours_to + settings.debt_now_grace_hours


def test_daytime_ride_is_not_shifted_by_quiet_hours(monkeypatch):
    """Тихие часы включены, но поездка днём — сдвига быть не должно (парный тест к ночным)."""
    _quiet_on(monkeypatch)
    day = _utc_at_local_hour(14)
    due = debt_mod._due_at(day, settings.debt_now_threshold_kop)
    assert due == day + timedelta(hours=settings.debt_now_grace_hours)


def test_quiet_hours_disabled_means_no_shift(monkeypatch):
    """Александр выключил тихие часы → сдвигать нечего, работает голый grace."""
    monkeypatch.setattr(settings, "quiet_hours_to", 0)
    night = _utc_at_local_hour(1)
    due = debt_mod._due_at(night, settings.debt_now_threshold_kop)
    assert due == night + timedelta(hours=settings.debt_now_grace_hours)


# ------------------------------ поездку закрыл автомат ------------------------------
def test_auto_closed_order_never_gets_the_short_deadline():
    """Ночная чистка закрыла заказ за водителя: короткий срок был бы ловушкой.

    Момент окончания поездки неизвестен, водитель спит и пуша не видел — к утру он оказался бы
    заблокирован за просрочку, о которой его никто не предупреждал.
    """
    now = _utc_at_local_hour(14)
    due = debt_mod._due_at(now, settings.debt_now_threshold_kop * 10, pay_now_allowed=False)
    assert due == now + timedelta(days=settings.debt_due_days)


# ------------------------------ is_pay_now: признак берётся из записи ------------------------------
def _debt(created, due, amount=50000):
    return CommissionDebt(driver_id=1, order_id=1, amount_kop=amount,
                          week="2026-W34", created_at=created, due_at=due)


def test_is_pay_now_reads_the_record_not_the_current_config(monkeypatch):
    """Порог в конфиге подняли — старые долги не должны задним числом стать «срочными».

    Водителю обещали неделю. Если после правки конфига он увидит «оплати сегодня» по поездке
    недельной давности — это обман, даже если цифра формально сходится.
    """
    now = utcnow()
    weekly = _debt(now, now + timedelta(days=7), amount=1_000_000)   # обещали неделю
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 1)       # порог опустили до копейки
    assert debt_mod.is_pay_now(weekly) is False

    urgent = _debt(now, now + timedelta(hours=3), amount=1)
    monkeypatch.setattr(settings, "debt_now_threshold_kop", 10_000_000)  # порог подняли
    assert debt_mod.is_pay_now(urgent) is True


def test_is_pay_now_survives_missing_dates():
    """Долг без дат (старые записи) не должен ронять кабинет водителя."""
    assert debt_mod.is_pay_now(_debt(None, None)) is False
    assert debt_mod.is_pay_now(_debt(utcnow(), None)) is False


def test_a_hand_moved_deadline_is_not_an_urgent_debt():
    """Срок сдвинули в прошлое руками — это не «оплати сегодня».

    Так делают админка, миграции и тесты, эмулирующие просрочку. Окно там получается
    ОТРИЦАТЕЛЬНЫМ, и наивная проверка «меньше суток» считала такой долг срочным — а по
    этому признаку теперь решается, блокировать ли водителя, который о сроке не знал.
    """
    now = utcnow()
    moved = _debt(now, now - timedelta(days=1))     # срок раньше начисления
    assert debt_mod.is_pay_now(moved) is False
    # А настоящий срочный долг просрочку переживает: окно считается от записи, не от «сейчас».
    overdue_urgent = _debt(now - timedelta(hours=9), now - timedelta(hours=6))
    assert debt_mod.is_pay_now(overdue_urgent) is True


# ------------------------------ граница утра ------------------------------
# Ровно на стыке ночи и утра ошибиться на сутки проще всего: «утро» легко посчитать
# завтрашним и дать водителю сутки вместо трёх часов — или, наоборот, разбудить его в 6:59.

def test_ride_finished_a_minute_before_morning_waits_only_until_today_morning(monkeypatch):
    """6:59 — ещё ночь, но утро СЕГОДНЯШНЕЕ: ждём минуту, а не сутки."""
    _quiet_on(monkeypatch)
    almost = _utc_at_local_hour(settings.quiet_hours_to) - timedelta(minutes=1)
    due = debt_mod._due_at(almost, settings.debt_now_threshold_kop)
    assert (due + timedelta(hours=TZ)).hour == settings.quiet_hours_to + settings.debt_now_grace_hours
    # Сутки означали бы, что до вечера долг не спросят вовсе.
    assert due - almost < timedelta(hours=settings.debt_now_grace_hours + 1)


def test_ride_finished_exactly_at_morning_starts_the_clock_at_once(monkeypatch):
    """07:00 ровно — ночь кончилась, ждать больше нечего, срок идёт сразу."""
    _quiet_on(monkeypatch)
    morning = _utc_at_local_hour(settings.quiet_hours_to)
    due = debt_mod._due_at(morning, settings.debt_now_threshold_kop)
    assert due == morning + timedelta(hours=settings.debt_now_grace_hours)


# ------------------------------ короткий срок и «а он вообще знал?» ------------------------------
# Пуш уходит молча в никуда сразу в нескольких случаях: уведомления выключены в системе,
# у аккаунта нет ни одного устройства, Firebase не настроен, антишторм проглотил событие.
# Ни один из них наружу не сообщает — значит блокировать по трём часам можно только того,
# у кого был шанс о них узнать.

_order_no = {"n": 9000}


def _owe(driver_id, *, created, due, amount=50000, status=DebtStatus.unpaid):
    """Долг за НАСТОЯЩИЙ завершённый заказ: у долга внешний ключ на заказ, и выдуманный
    номер база не примет. Заодно тест идёт тем же путём, что живые деньги."""
    _order_no["n"] += 1
    n = _order_no["n"]
    with Session(engine) as s:
        pax = User(phone=f"tg-debt-pax-{n}", name="Пассажир", telegram_id=f"debtpax{n}",
                   verified=True)
        s.add(pax)
        s.commit()
        s.refresh(pax)
        order = InstantOrder(passenger_id=pax.id, driver_id=driver_id,
                             status=InstantOrderStatus.done, price_estimate=500, price_final=500)
        s.add(order)
        s.commit()
        s.refresh(order)
        s.add(CommissionDebt(driver_id=driver_id, order_id=order.id, amount_kop=amount,
                             week="2026-W34", created_at=created, due_at=due, status=status))
        s.commit()


def _last_seen(user_id, when):
    with Session(engine) as s:
        u = s.get(User, user_id)
        u.last_seen_at = when
        s.add(u)
        s.commit()


def _reason(driver_id, now=None):
    with Session(engine) as s:
        return debt_mod.taxi_block_reason(s, driver_id, now or utcnow())


def test_short_deadline_does_not_block_a_driver_who_could_not_know(user_factory):
    """Предупреждение не дошло, в приложение водитель не заходил — тихой блокировки быть не должно."""
    drv = user_factory("ДолгНеЗнал", role=UserRole.driver)
    started = utcnow() - timedelta(hours=5)
    _owe(drv["id"], created=started, due=started + timedelta(hours=3))
    _last_seen(drv["id"], started - timedelta(hours=1))       # был до поездки, после — ни разу
    assert _reason(drv["id"]) is None


def test_short_deadline_blocks_when_the_driver_has_been_in_the_app(user_factory):
    """А вот заходил после поездки — значит требование видел, и три часа работают."""
    drv = user_factory("ДолгЗнал", role=UserRole.driver)
    started = utcnow() - timedelta(hours=5)
    _owe(drv["id"], created=started, due=started + timedelta(hours=3))
    _last_seen(drv["id"], started + timedelta(minutes=10))
    assert _reason(drv["id"]) == "overdue"


def test_weekly_deadline_blocks_even_if_the_driver_never_came_back(user_factory):
    """Лазейки «не заходи — не заблокируют» нет: недельный срок работает как работал."""
    drv = user_factory("ДолгНеделя", role=UserRole.driver)
    started = utcnow() - timedelta(days=10)
    _owe(drv["id"], created=started, due=started + timedelta(days=settings.debt_due_days))
    _last_seen(drv["id"], started - timedelta(days=1))
    assert _reason(drv["id"]) == "overdue"


def test_batch_check_judges_exactly_like_the_single_gate(user_factory):
    """Подбор такси смотрит пакетно, ручка — поштучно. Разъедутся — водителя перестанут
    находить заказы, а он будет видеть «всё в порядке» и не поймёт почему."""
    quiet = user_factory("ПакетНеЗнал", role=UserRole.driver)
    loud = user_factory("ПакетЗнал", role=UserRole.driver)
    started = utcnow() - timedelta(hours=5)
    for drv, seen in ((quiet, started - timedelta(hours=1)), (loud, started + timedelta(minutes=10))):
        _owe(drv["id"], created=started, due=started + timedelta(hours=3))
        _last_seen(drv["id"], seen)
    now = utcnow()
    with Session(engine) as s:
        batch = debt_mod.blocked_driver_ids(s, [quiet["id"], loud["id"]], now)
    assert (quiet["id"] in batch) is (_reason(quiet["id"], now) is not None)
    assert (loud["id"] in batch) is (_reason(loud["id"], now) is not None)
    assert quiet["id"] not in batch and loud["id"] in batch


# ------------------------------ «Я оплатил» ночью, админ подтвердит утром ------------------------------
def test_declared_payment_stops_the_short_clock(user_factory):
    """Нажал «Я оплатил» в два ночи — Александр подтвердит в десять утра. В промежутке
    водитель обязан работать: деньги он отправил, ждём человека, а не его."""
    drv = user_factory("ДолгЗаявил", role=UserRole.driver)
    started = utcnow() - timedelta(hours=5)
    _owe(drv["id"], created=started, due=started + timedelta(hours=3))
    _last_seen(drv["id"], started + timedelta(minutes=10))
    assert _reason(drv["id"]) == "overdue"          # до заявления — блок

    with Session(engine) as s:
        debt_mod.declare_paid(s, drv["id"])

    assert _reason(drv["id"]) is None               # заявил — блок снят
    # И держится не пару часов, а пока у админа есть срок на подтверждение.
    assert _reason(drv["id"], utcnow() + timedelta(hours=20)) is None


# ------------------------------ предупреждение ДО блокировки ------------------------------
# Три админских события про долг («подтверждён», «списан», «оплата не найдена») пуш имели,
# а самое частое — долг дорос до порога и такси выключилось — не имело ни одного: водитель
# упирался в отказ посреди рабочего дня и выяснял причину задним числом (аудит 2026-08-21).


def _warn_line_kop():
    return int(settings.debt_block_threshold_kop * settings.debt_warn_ratio)


def _crossed(driver_id, just_added_kop):
    with Session(engine) as s:
        return debt_mod.crossed_warn_line(s, driver_id, just_added_kop)


def test_warning_fires_on_the_ride_that_crosses_the_line(user_factory):
    """Заказ, которым долг перешагнул линию, — единственный повод предупредить."""
    drv = user_factory("ПорогПересёк", role=UserRole.driver)
    line = _warn_line_kop()
    now = utcnow()
    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=line - 5000)
    assert _crossed(drv["id"], line - 5000) is None      # до линии не дотянули — молчим

    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=10000)
    assert _crossed(drv["id"], 10000) == line + 5000     # этой поездкой перешагнули


def test_warning_does_not_repeat_after_every_next_ride(user_factory):
    """Повторяющееся предупреждение перестают читать ровно к тому моменту, когда оно важно."""
    drv = user_factory("ПорогПовтор", role=UserRole.driver)
    line = _warn_line_kop()
    now = utcnow()
    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=line + 1000)
    assert _crossed(drv["id"], line + 1000) is not None  # первый раз — сказали

    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=1000)
    assert _crossed(drv["id"], 1000) is None             # дальше молчим


def test_no_warning_to_a_driver_already_over_the_limit(user_factory):
    """У кого такси уже закрыто, «скоро закроется» — враньё. Ему нужен другой разговор."""
    drv = user_factory("ПорогПревышен", role=UserRole.driver)
    now = utcnow()
    over = settings.debt_block_threshold_kop + 50000
    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=over)
    assert _crossed(drv["id"], over) is None


def test_warning_can_be_switched_off(monkeypatch, user_factory):
    """Ноль в настройке выключает предупреждение целиком — без правки кода."""
    drv = user_factory("ПорогВыкл", role=UserRole.driver)
    monkeypatch.setattr(settings, "debt_warn_ratio", 0.0)
    now = utcnow()
    _owe(drv["id"], created=now, due=now + timedelta(days=7),
         amount=settings.debt_block_threshold_kop - 1)
    assert _crossed(drv["id"], settings.debt_block_threshold_kop - 1) is None


def test_paid_debts_do_not_count_towards_the_warning(user_factory):
    """Считаем только НЕОПЛАЧЕННОЕ: иначе честный водитель, который платит каждую неделю,
    получал бы предупреждение по сумме за всю жизнь."""
    drv = user_factory("ПорогОплачен", role=UserRole.driver)
    line = _warn_line_kop()
    now = utcnow()
    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=line,
         status=DebtStatus.paid)
    _owe(drv["id"], created=now, due=now + timedelta(days=7), amount=10000)
    assert _crossed(drv["id"], 10000) is None
