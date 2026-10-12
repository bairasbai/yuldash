"""leaf-1.3 — деньги: реферальная программа (app/routers/referral.py).

Три из четырёх денежных правил этого файла уже защищены отдельным, уже существующим и очень
плотным набором тестов — дублировать их новым кодом было бы лишним весом, а не защитой:
  R1 (бонус не самому себе: свой код и взаимный обмен A→B→A)
      → tests/test_referral_farm_cannot_be_raced.py::test_own_code_and_swap_are_still_refused
  R2 (бонус не начисляется дважды — гонка двух РАЗНЫХ кодов одного нового юзера, в т.ч. PostgreSQL)
      → tests/test_referral_farm_cannot_be_raced.py::test_two_codes_at_once_give_only_one_bonus
        (там же — настоящая гонка потоков на Postgres и honest-эквивалент на SQLite)
  R4 (месячный потолок водительского бонуса — календарь Уфы, не серверный/UTC)
      → tests/test_the_calendar_is_the_humans_not_the_servers.py::test_monthly_bonus_cap_counts_the_humans_month

R3: пожизненный потолок бонусов РЕФЕРЕРА (`MAX_REFERRAL_BONUS_LIFETIME`) не должен пробиваться,
когда grant_referral_credit() вызывается для ОДНОГО и ТОГО ЖЕ реферера по-настоящему
ОДНОВРЕМЕННО (а не переплетением в одном потоке, как в существующем
test_lifetime_cap_counts_every_grant).

R5/R6 (круг 3, найдено при ревью круга 2, починено здесь): `reward_driver_referral` читал
реферера БЕЗ `with_for_update()` — два «раскатавшихся» одновременно приглашённых водителя
ОДНОГО пригласившего оба проходили месячный потолок (R5), а гонка на UNIQUE(invited_user_id)
падала необработанным 500 уже ПОСЛЕ чужого done-перехода (R6). Теперь — row-lock на реферере
+ тихий откат на IntegrityError.
"""
import threading

import pytest
from sqlalchemy import select, text
from sqlmodel import Session

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, ReferralBonus, User, UserRole
from app.routers import referral as ref


def _pg_session():
    # SET LOCAL — без LOCAL настройка осталась бы на соединении и после commit внутри
    # вызванной функции утекла бы в СЛЕДУЮЩИЙ тест, взявший то же соединение из пула.
    s = Session(engine)
    s.execute(text("SET LOCAL lock_timeout = '4s'"))
    s.execute(text("SET LOCAL statement_timeout = '8s'"))
    return s


def _live_order(driver_id: int, passenger_id: int) -> None:
    """Минимальный done-заказ, который `_live_driver_trips` сочтёт «живым» (дистанция>1км)."""
    with Session(engine) as s:
        s.add(InstantOrder(driver_id=driver_id, passenger_id=passenger_id, status=S.done,
                           distance_km=2.0, from_lat=52.0, from_lng=56.0, to_lat=52.1, to_lng=56.1))
        s.commit()


def _make_live_driver(driver_id: int, user_factory, tag: str) -> None:
    """Три живых поездки с тремя РАЗНЫМИ пассажирами — порог `reward_driver_referral`."""
    for i in range(3):
        p = user_factory(f"{tag}Пас{i}")
        _live_order(driver_id, p["id"])


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_grants_respect_lifetime_cap(user_factory):
    referrer = user_factory("PgReferrerLifetimeCap")
    with Session(engine) as s:
        u = s.get(User, referrer["id"])
        u.referral_bonus_lifetime = ref.MAX_REFERRAL_BONUS_LIFETIME - 1   # один слот до потолка
        u.referral_credits = 0
        s.add(u)
        s.commit()

    barrier = threading.Barrier(2)
    results, lock = [], threading.Lock()

    def grant():
        with _pg_session() as s:
            u = s.get(User, referrer["id"])
            barrier.wait(timeout=10)
            got = ref.grant_referral_credit(s, u)
            s.commit()
        with lock:
            results.append(got)

    threads = [threading.Thread(target=grant) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert sorted(results) == [False, True], (
        f"из двух одновременных начислений реферера на последнем слоте должно пройти ровно "
        f"одно: {results}"
    )
    with Session(engine) as s:
        u = s.get(User, referrer["id"])
        assert u.referral_bonus_lifetime == ref.MAX_REFERRAL_BONUS_LIFETIME, (
            "пожизненный потолок фермы пробит гонкой двух одновременных начислений"
        )
        assert u.referral_credits == 1


# ============================ R5: месячный потолок водительского бонуса (гонка) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_driver_bonuses_respect_monthly_cap(client, user_factory, monkeypatch):
    """Двое приглашённых водителей ОДНОГО пригласившего «раскатываются» одновременно, а
    реферер уже в одном бонусе от месячного потолка — пройти должен ровно один.

    Задержка — СРАЗУ ПОСЛЕ проверки месячного потолка (внутри _live_driver_trips, а не внутри
    самого подсчёта granted_driver_bonuses_this_month): обоим потокам нужно время ПОСЛЕ
    прочитанного «ещё не исчерпан», но ДО записи — иначе окно гонки закрывается само собой
    за счёт разной скорости потоков ОС (на Windows голого Barrier не хватает — см. остальные
    гонки этого листа)."""
    referrer = user_factory("PgМесячныйРеферер")
    drv1 = user_factory("PgМесячныйВод1", role=UserRole.driver)
    drv2 = user_factory("PgМесячныйВод2", role=UserRole.driver)
    with Session(engine) as s:
        for d in (drv1, drv2):
            u = s.get(User, d["id"])
            u.referred_by = referrer["id"]
            s.add(u)
        s.commit()
        # Реферер уже в одном бонусе от месячного потолка (фиктивные прошлые приглашённые).
        for i in range(ref.DRIVER_BONUS_MONTHLY_CAP - 1):
            filler = user_factory(f"PgФиллер{i}")
            s.add(ReferralBonus(referrer_id=referrer["id"], invited_user_id=filler["id"], kind="driver"))
        s.commit()
    _make_live_driver(drv1["id"], user_factory, "PgМВод1")
    _make_live_driver(drv2["id"], user_factory, "PgМВод2")

    # Задержка — СРАЗУ ПОСЛЕ проверки месячного потолка (а не внутри самого подсчёта!):
    # обоим потокам нужно время ПОСЛЕ прочитанного «ещё не исчерпан», но ДО записи, чтобы
    # окно гонки не закрылось само собой за счёт разной скорости потоков ОС.
    original_trips = ref._live_driver_trips

    def delayed_trips(session, driver_id):
        import time as _t
        _t.sleep(0.25)
        return original_trips(session, driver_id)

    monkeypatch.setattr(ref, "_live_driver_trips", delayed_trips)

    barrier = threading.Barrier(2)
    results, lock = [], threading.Lock()

    def call(driver_id):
        with _pg_session() as s:
            barrier.wait(timeout=10)
            got = ref.reward_driver_referral(s, driver_id)
        with lock:
            results.append(got)

    threads = [threading.Thread(target=call, args=(drv1["id"],)),
              threading.Thread(target=call, args=(drv2["id"],))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert sorted(results) == [False, True], (
        f"на последнем слоте месячного потолка из двух одновременных водителей должен "
        f"пройти ровно один: {results}"
    )
    with Session(engine) as s:
        assert ref.granted_driver_bonuses_this_month(s, referrer["id"]) == ref.DRIVER_BONUS_MONTHLY_CAP


# ============================ R6: гонка на UNIQUE не роняет вызывающий эндпоинт ============================
# Не Postgres-only: воспроизводим ИСХОД гонки детерминированно, без настоящих потоков. Прямой
# нитевой тест (вторая сессия вставляет конкурирующую строку, пока первая ещё держит
# with_for_update() на реферере) здесь невозможен и на Postgres: ReferralBonus ссылается на
# User внешним ключом, и вставка в другой сессии, затрагивающая ТОТ ЖЕ referrer_id, что
# заблокирован FOR UPDATE, сама встаёт в очередь на снятие FK-блокировки — то есть ровно тот
# лок, который чинит гонку месячного потолка (R5), структурно делает ДВА одновременных
# INSERT на одного реферера невозможными (к обеим проверено: попытка собрать такой тест
# честно привела к взаимной блокировке и была переписана). Обработка IntegrityError —
# защита на будущее (другой рефакторинг однажды уберёт лок, не убрав гонку) и повторяет
# паттерн, уже проверенный в этом же файле (`post_promo_compensation`, `debt.accrue_for_order`).
def test_driver_bonus_race_on_unique_is_handled_gracefully(user_factory, monkeypatch):
    """Раньше гонка на UNIQUE(invited_user_id, kind) падала необработанным IntegrityError —
    эта ошибка улетала В ВЫЗЫВАЮЩИЙ эндпоинт (разметка done в такси/попутке/посылках),
    то есть человек, честно завершивший СВОЮ поездку, видел 500 из-за гонки вокруг ЧУЖОГО
    реферального бонуса. Деньги при этом были целы (бонус не задваивался) — ломался только
    ответ. Воспроизводим ИСХОД гонки точно: конкурирующая запись уже физически лежит в базе
    (как будто её вставил параллельный вызов для той же пары хуков такси/попутка/посылка), а
    наша проверка `_driver_bonus_already_granted` её «не увидела» — ровно так выглядит гонка
    между своей проверкой и своей записью."""
    referrer = user_factory("ГрейсфулРеферер")
    drv = user_factory("ГрейсфулВод", role=UserRole.driver)
    with Session(engine) as s:
        u = s.get(User, drv["id"])
        u.referred_by = referrer["id"]
        s.add(u)
        s.commit()
    _make_live_driver(drv["id"], user_factory, "Грейсфул")

    with Session(engine) as s0:
        s0.add(ReferralBonus(referrer_id=referrer["id"], invited_user_id=drv["id"], kind="driver"))
        s0.commit()

    monkeypatch.setattr(ref, "_driver_bonus_already_granted", lambda session, driver_id: False)

    with Session(engine) as s:
        result = ref.reward_driver_referral(s, drv["id"])   # не должно бросить исключение наружу

    assert result is False, "проигравший гонку на UNIQUE должен тихо отказать, а не упасть"
    with Session(engine) as s:
        rows = s.exec(select(ReferralBonus).where(ReferralBonus.invited_user_id == drv["id"])).all()
    assert len(rows) == 1, "бонус должен остаться ровно один, несмотря на гонку"
