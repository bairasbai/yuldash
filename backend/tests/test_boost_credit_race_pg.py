"""Остаток поднятия (Boost) под одновременными запросами — на настоящих замках PostgreSQL (24.10.2026).

Дежурный снимает поднятую поездку водителя («Снять поездку»), а водитель в ту же секунду публикует
новую — и она забирает себе прежний остаток. Раньше публикация читала остаток ДО замка и записывала
своё «0» поверх: только что перенесённые часы поднятия пропадали. Теперь строку водителя берут под
замок оба пути (порядок Ride → Booking → User у отмены, у публикации — только своя строка), и
сумма сохраняется: перенесено на новую поездку + осталось на счету = прежний остаток + снятое.

И «Поднять за бонус» (`/boost/free`) против «Снять поездку»: раньше бонус списывался под замком
строки водителя, а поездка писалась без замка — встречная отмена давала взаимную блокировку, а подъём
ложился поверх снятой поездки. На SQLite замков нет — эти проверки только для Postgres.
"""
from __future__ import annotations

import threading
import time
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient
from sqlmodel import Session

from app.db import engine
from app.main import app
from app.models import Ride, User, UserRole
from app.routers import rides as rides_router
from app.timeutil import utcnow

pytestmark = [
    pytest.mark.skipif(engine.dialect.name != "postgresql", reason="гонка на замках — только PostgreSQL"),
    pytest.mark.timeout(60),
]


def _ride(api, driver, hours_boost=6):
    r = api.post("/rides", headers=driver["auth"], json={
        "from_city": "Баймаҡ", "to_city": "Сибай", "price": 300, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(hours=5)).isoformat()})
    assert r.status_code == 200, r.text
    rid = r.json()["id"]
    if hours_boost:
        with Session(engine) as s:
            row = s.get(Ride, rid)
            row.boosted_until = utcnow() + timedelta(hours=hours_boost)
            s.add(row)
            s.commit()
    return rid


def test_снятие_дежурным_и_новая_поездка_водителя_остаток_не_теряется(client, user_factory, monkeypatch):
    driver = user_factory("Гонка буст водитель", role=UserRole.driver)
    admin = user_factory("Гонка буст дежурный", role=UserRole.admin)
    rid = _ride(client, driver, hours_boost=6)
    with Session(engine) as s:   # прежний живой остаток — 2 ч
        u = s.get(User, driver["id"])
        u.boost_credit_sec, u.boost_credit_until = 2 * 3600, utcnow() + timedelta(days=5)
        s.add(u)
        s.commit()

    # Отмена держит строку водителя под замком и ждёт, пока публикация не упрётся в тот же замок.
    stashed, release = threading.Event(), threading.Event()
    real_stash = rides_router.stash_boost_credit

    def slow_stash(user, ride, now):
        moved = real_stash(user, ride, now)
        stashed.set()
        release.wait(10)
        return moved

    monkeypatch.setattr(rides_router, "stash_boost_credit", slow_stash)
    api = TestClient(app, raise_server_exceptions=False)
    out = {}
    cancel = threading.Thread(target=lambda: out.__setitem__("cancel", api.post(f"/rides/{rid}/cancel", headers=admin["auth"])))
    cancel.start()
    assert stashed.wait(10), "отмена не дошла до переноса остатка"
    publish = threading.Thread(target=lambda: out.__setitem__("publish", api.post("/rides", headers=driver["auth"], json={
        "from_city": "Сибай", "to_city": "Баймаҡ", "price": 300, "seats_total": 3,
        "depart_at": (utcnow() + timedelta(hours=8)).isoformat()})))
    publish.start()
    time.sleep(0.7)   # публикация успевает прочитать старый остаток и упереться в замок
    release.set()
    cancel.join(20)
    publish.join(20)
    assert out["cancel"].status_code == 200, out["cancel"].text
    assert out["publish"].status_code == 200, out["publish"].text

    now = utcnow()
    with Session(engine) as s:
        u = s.get(User, driver["id"])
        new_ride = s.get(Ride, out["publish"].json()["id"])
        applied = (new_ride.boosted_until - now).total_seconds() if new_ride.boosted_until else 0
        left = u.boost_credit_sec if (u.boost_credit_until and u.boost_credit_until > now) else 0
    assert abs((applied + left) - 8 * 3600) < 30, (
        f"остаток потерян: на новой поездке {applied:.0f} с, на счету {left} с, а было 2 ч + снятые 6 ч")
    with Session(engine) as s:
        assert s.get(User, admin["id"]).boost_credit_sec == 0, "дежурному на счёт — ничего"


def test_один_бонус_два_одновременных_подъёма_списывается_один_раз(client, user_factory):
    """Замок строки водителя без перечитывания отдавал старое число бонусов (объект из входа)."""
    driver = user_factory("Гонка бонус дважды", role=UserRole.driver)
    with Session(engine) as s:
        u = s.get(User, driver["id"])
        u.referral_credits = 1
        s.add(u)
        s.commit()
    rid = _ride(client, driver, hours_boost=0)
    api = TestClient(app, raise_server_exceptions=False)
    out = [None, None]
    barrier = threading.Barrier(2)

    def run(i):
        barrier.wait(10)
        out[i] = api.post("/boost/free", headers=driver["auth"], json={"ride_id": rid})

    threads = [threading.Thread(target=run, args=(i,)) for i in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    assert sorted(r.status_code for r in out) == [200, 400], [r.text for r in out]
    with Session(engine) as s:
        assert s.get(User, driver["id"]).referral_credits == 0
        ride = s.get(Ride, rid)
    assert ride.boosted_until is not None and ride.boosted_until < utcnow() + timedelta(hours=25), \
        "за один бонус — 24 ч подъёма, не 48"


def test_поднять_за_бонус_и_снять_поездку_без_взаимной_блокировки(client, user_factory):
    driver = user_factory("Гонка бонус водитель", role=UserRole.driver)
    admin = user_factory("Гонка бонус дежурный", role=UserRole.admin)
    with Session(engine) as s:
        u = s.get(User, driver["id"])
        u.referral_credits = 1
        s.add(u)
        s.commit()
    rid = _ride(client, driver, hours_boost=0)
    api = TestClient(app, raise_server_exceptions=False)
    out = [None, None]
    barrier = threading.Barrier(2)

    def run(i, fn):
        barrier.wait(10)
        out[i] = fn()

    threads = [
        threading.Thread(target=run, args=(0, lambda: api.post("/boost/free", headers=driver["auth"], json={"ride_id": rid}))),
        threading.Thread(target=run, args=(1, lambda: api.post(f"/rides/{rid}/cancel", headers=admin["auth"]))),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join(30)
    boost, cancel = out
    assert cancel.status_code == 200, cancel.text
    assert boost.status_code in (200, 400), boost.text   # 400 «Поездка неактивна» — отмена успела раньше
    with Session(engine) as s:
        ride = s.get(Ride, rid)
        u = s.get(User, driver["id"])
    assert ride.status.value == "cancelled"
    if boost.status_code == 200:
        # Подъём успел до отмены — отмена перенесла его остаток водителю, бонус не сгорел.
        assert ride.boosted_until is None and u.boost_credit_sec > 23 * 3600 and u.referral_credits == 0
    else:
        assert u.referral_credits == 1, "бонус списан за подъём снятой поездки"
