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

Здесь — только R3: пожизненный потолок бонусов РЕФЕРЕРА (`MAX_REFERRAL_BONUS_LIFETIME`) не
должен пробиваться, когда grant_referral_credit() вызывается для ОДНОГО и ТОГО ЖЕ реферера
по-настоящему ОДНОВРЕМЕННО (а не переплетением в одном потоке, как в существующем
test_lifetime_cap_counts_every_grant). Это ровно тот путь, которым идёт `reward_driver_referral`,
когда у одного пригласившего ДВА разных приглашённых водителя финишируют третью «живую» поездку
почти одновременно — там `referrer` читается БЕЗ with_for_update() (в отличие от входа по коду
в referral_redeem), и единственная защита — собственный атомарный UPDATE внутри
grant_referral_credit. Эта защита не задублирована ничем другим, поэтому проверяется
ТОЛЬКО на настоящем PostgreSQL.
"""
import threading

import pytest
from sqlalchemy import text
from sqlmodel import Session

from app.db import engine
from app.models import User
from app.routers import referral as ref


def _pg_session():
    s = Session(engine)
    s.execute(text("SET lock_timeout = '4s'"))
    s.execute(text("SET statement_timeout = '8s'"))
    return s


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
