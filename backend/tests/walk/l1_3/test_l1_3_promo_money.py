"""leaf-1.3 — деньги: именные промокоды и кампании (app/routers/promo.py).

Фокус группы «Деньги»:
  R1 — общий лимит кампании (limit_total) не превышается, даже когда два РАЗНЫХ человека
       одновременно применяют последний доступный код (PostgreSQL);
  R2 — владелец кампании не может активировать свой же код (бесплатный бонус самому себе);
  R3 — бонус-«поднятия» (kind=boost) не выдаётся сверх общего потолка бонусов на руках
       (MAX_REFERRAL_CREDITS), даже если perk_value кампании больше остатка до потолка;
  R4 — срок кампании: дата без времени (форма админа шлёт именно так) = конец суток по Уфе,
       а не начало — та же дыра и то же исправление, что у купонов (B-3, найдено ревью).
"""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import pytest
from sqlalchemy import text
from sqlmodel import Session, select

from app.db import engine
from app.models import PromoCode, User, UserRole
from app.routers import promo as promo_router
from app.routers.promo import MAX_REFERRAL_CREDITS, ApplyIn


def _pg_session():
    # SET LOCAL — без LOCAL настройка осталась бы на соединении и после commit внутри
    # вызванной функции утекла бы в СЛЕДУЮЩИЙ тест, взявший то же соединение из пула.
    s = Session(engine)
    s.execute(text("SET LOCAL lock_timeout = '4s'"))
    s.execute(text("SET LOCAL statement_timeout = '8s'"))
    return s


# ============================ R1: общий лимит кампании (гонка, PostgreSQL) ============================
@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires isolated PostgreSQL")
def test_concurrent_apply_respects_total_limit(client, user_factory, monkeypatch):
    """Кампания с limit_total=1: два РАЗНЫХ человека одновременно жмут «применить» —
    должен выиграть ровно один, счётчик активаций — вырасти ровно на 1.

    Голого Barrier перед вызовом недостаточно для надёжной проверки: после барьера оба потока
    ещё должны реально ПЕРЕСЕЧЬСЯ внутри критического участка (после чтения, до записи), а
    планировщик Windows может целиком прогнать один поток раньше, чем ОС вообще переключится
    на второй — тогда поломка не поймается не потому что защита есть, а просто по везению.
    Поэтому дополнительно задерживаем обоих ПРЯМО ПЕРЕД атомарным обновлением счётчика
    (единственная точка, которую зовут обе ветки — welcome и boost): это гарантирует, что
    оба запроса окажутся внутри гонки одновременно, независимо от того, как их планирует ОС.
    """
    admin = user_factory("ГонкаПромоАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={
        "code": "PGPROMOLIMIT", "kind": "welcome", "limit_total": 1,
    })
    assert r.status_code == 200, r.text
    alice = user_factory("ГонкаПромоАлиса")
    bob = user_factory("ГонкаПромоБоб")

    original_granted_kop = promo_router.promo_ride.granted_kop

    def delayed_granted_kop(promo):
        time.sleep(0.2)   # окно, в котором оба потока гарантированно уже прочитали старое состояние
        return original_granted_kop(promo)

    monkeypatch.setattr(promo_router.promo_ride, "granted_kop", delayed_granted_kop)

    barrier = threading.Barrier(2)

    def apply(user_id):
        with _pg_session() as s:
            u = s.get(User, user_id)
            barrier.wait(timeout=10)
            try:
                return promo_router.promo_apply(ApplyIn(code="PGPROMOLIMIT"), user=u, session=s, x_device_id="")
            except Exception as exc:
                return exc

    with ThreadPoolExecutor(max_workers=2) as ex:
        futures = [ex.submit(apply, uid) for uid in (alice["id"], bob["id"])]
        results = [f.result(timeout=20) for f in futures]

    oks = [r for r in results if isinstance(r, dict)]
    fails = [r for r in results if not isinstance(r, dict)]
    assert len(oks) == 1, f"код с лимитом 1 не должен был достаться обоим: {results}"
    assert len(fails) == 1
    assert getattr(fails[0], "status_code", None) == 409
    assert fails[0].detail["ru"] and fails[0].detail["ba"]

    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "PGPROMOLIMIT")).first()
        assert promo.redeemed_count == 1, "счётчик активаций кампании не должен задваиваться"


# ============================ R2: нельзя активировать свой же код ============================
def test_owner_cannot_apply_own_code(client, user_factory):
    blogger = user_factory("СвойКодБлогер")
    admin = user_factory("СвойКодАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"], json={"code": "OWNCODE1", "kind": "welcome"})
    assert r.status_code == 200, r.text
    # owner_phone ищется по User.phone — у user_factory телефон синтетический ("tg-test-N"),
    # поэтому проставим владельца напрямую в БД (ровно то же поле, что и ручка админа).
    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "OWNCODE1")).first()
        promo.owner_id = blogger["id"]
        s.add(promo)
        s.commit()

    resp = client.post("/promo/apply", headers=blogger["auth"], json={"code": "OWNCODE1"})
    assert resp.status_code == 409, resp.text
    detail = resp.json()["detail"]
    assert detail["ru"] and detail["ba"]

    with Session(engine) as s:
        promo = s.exec(select(PromoCode).where(PromoCode.code == "OWNCODE1")).first()
        assert promo.redeemed_count == 0, "свой код не должен был засчитаться как активация"


# ============================ R3: boost не выдаётся сверх потолка на руках ============================
def test_boost_grant_capped_at_wallet_limit(client, user_factory):
    admin = user_factory("БустПотолокАдмин", role=UserRole.admin)
    pax = user_factory("БустПотолокПас")
    with Session(engine) as s:
        u = s.get(User, pax["id"])
        u.referral_credits = MAX_REFERRAL_CREDITS - 2
        s.add(u)
        s.commit()

    r = client.post("/admin/promo", headers=admin["auth"], json={
        "code": "HUGEBOOST1", "kind": "boost", "perk_value": 1000,
    })
    assert r.status_code == 200, r.text

    resp = client.post("/promo/apply", headers=pax["auth"], json={"code": "HUGEBOOST1"})
    assert resp.status_code == 200, resp.text

    with Session(engine) as s:
        u = s.get(User, pax["id"])
        assert u.referral_credits == MAX_REFERRAL_CREDITS, \
            "бонус boost не должен перелиться через общий потолок бонусов на руках"


# ============================ R4 (B-3): дата без времени = конец суток по Уфе ============================
def test_promo_valid_until_treats_date_only_midnight_as_end_of_day():
    naive_midnight = datetime(2026, 10, 31, 0, 0, 0)
    stored = promo_router._promo_valid_until(naive_midnight)
    assert stored == datetime(2026, 10, 31, 18, 59, 59)


def test_promo_valid_until_leaves_explicit_time_alone():
    naive_evening = datetime(2026, 10, 31, 20, 0, 0)
    stored = promo_router._promo_valid_until(naive_evening)
    assert stored == datetime(2026, 10, 31, 15, 0, 0)


def test_promo_deadline_survives_repeated_date_only_resaves(client, user_factory):
    """Тот же круговой путь, что у купонов: форма админа при правке подставляет в поле только
    дату — повторное сохранение той же даты не должно сдвигать срок кампании."""
    admin = user_factory("СрокПромоАдмин", role=UserRole.admin)
    r = client.post("/admin/promo", headers=admin["auth"],
                    json={"code": "DEADLINE1", "kind": "welcome", "valid_until": "2026-10-31"})
    assert r.status_code == 200, r.text
    first = r.json()["valid_until"]
    assert first[:10] == "2026-10-31", f"дата уже съехала на первом сохранении: {first}"

    r2 = client.post(f"/admin/promo/{r.json()['id']}", headers=admin["auth"],
                     json={"valid_until": first[:10]})
    assert r2.status_code == 200, r2.text
    second = r2.json()["valid_until"]
    assert second == first, f"срок сдвинулся после повторного сохранения той же даты: {first} -> {second}"
