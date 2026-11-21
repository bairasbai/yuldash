"""Real separate PostgreSQL sessions/barriers; SQLite cannot prove these locks."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from sqlalchemy import event, text
from sqlmodel import Session, select

from app import instant_service as isv, promo_ride, taxi_quote_consent as consent
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, LedgerEntry, PromoRedemption, SafetyProfile
from app.timeutil import utcnow
from test_taxi_quote_consent import (  # noqa: F401
    CONFIRM, body_for, confirm, consent_market, live_driver, orders, promo_user, quote, set_base,
)

pytestmark = pytest.mark.skipif(engine.dialect.name != "postgresql", reason="requires real PostgreSQL row locks")


def observe_dispatch(monkeypatch):
    calls = []
    original = isv.start_matching

    def dispatch(session, order, *args, **kwargs):
        with Session(engine) as observer:
            saved = observer.get(InstantOrder, order.id)
            assert saved is not None and saved.consent_snapshot_json
        calls.append(order.id)
        return original(session, order, *args, **kwargs)
    monkeypatch.setattr(isv, "start_matching", dispatch)
    return calls


def test_same_key_waits_for_real_passenger_lock_and_replays(client, user_factory, consent_market, monkeypatch):
    live_driver(client, user_factory)
    user = user_factory("SyntheticPGConsentReplay")
    # A refreshable safety profile used to make the default guard commit early.
    with Session(engine) as session:
        profile = SafetyProfile(user_id=user["id"], standing="suspended", strikes=0,
                                suspended_until=utcnow() - timedelta(days=1))
        session.add(profile)
        session.commit()
    data = quote(client, user)
    dispatches = observe_dispatch(monkeypatch)
    barrier = threading.Barrier(2)
    inserting, release = threading.Event(), threading.Event()
    paused = []
    pids = set()

    def before(connection, cursor, statement, parameters, context, many):
        sql = statement.upper()
        if "FOR UPDATE" in sql and 'FROM "USER"' in sql:
            pids.add(connection.connection.driver_connection.get_backend_pid())
        if sql.lstrip().startswith("INSERT INTO INSTANTORDER") and not paused:
            paused.append(True)
            inserting.set()
            assert release.wait(15), "test release never arrived"

    def send():
        barrier.wait(10)
        return confirm(client, user, data)

    event.listen(engine, "before_cursor_execute", before)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            a, b = pool.submit(send), pool.submit(send)
            try:
                assert inserting.wait(10), "first admission never reached INSERT"
                blocked = False
                for _ in range(100):
                    with Session(engine) as observer:
                        blocked = observer.exec(text(
                            "SELECT EXISTS (SELECT 1 FROM pg_stat_activity "
                            "WHERE wait_event_type='Lock' AND query LIKE '%FOR UPDATE%' "
                            "AND cardinality(pg_blocking_pids(pid)) > 0)"
                        )).scalar()
                    if blocked and len(pids) == 2:
                        break
                    time.sleep(0.03)
                assert blocked and len(pids) == 2, "second separate session did not wait on row lock"
            finally:
                release.set()
            results = [a.result(20), b.result(20)]
    finally:
        release.set()
        event.remove(engine, "before_cursor_execute", before)
    assert [r.status_code for r in results] == [200, 200], [r.text for r in results]
    ids = {r.json()["order"]["id"] for r in results}
    assert len(ids) == 1 and dispatches == list(ids)
    assert len(orders(user["id"])) == 1


def test_different_keys_cannot_admit_two_live_orders(client, user_factory, consent_market, monkeypatch):
    live_driver(client, user_factory)
    user = user_factory("SyntheticPGConsentTwoKeys")
    a, b = quote(client, user), quote(client, user)
    barrier = threading.Barrier(2)
    dispatches = observe_dispatch(monkeypatch)

    def send(data):
        barrier.wait(10)
        return confirm(client, user, data)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [f.result(20) for f in [pool.submit(send, a), pool.submit(send, b)]]
    assert sorted(r.status_code for r in results) == [200, 409], [r.text for r in results]
    assert len(orders(user["id"])) == 1 and len(dispatches) == 1


def test_promo_cas_loser_rolls_back_order_receipt_wait_and_effects(
    client, user_factory, consent_market, monkeypatch,
):
    """Legacy consume really wins between the protected read and its actual CAS."""
    set_base(600)
    user = promo_user(client, user_factory)
    until = utcnow() + timedelta(minutes=30)
    with Session(engine) as session:
        old_wait = InstantOrder(passenger_id=user["id"], status=S.expired, wait_until=until,
                                price_estimate=600, ride_price=600)
        # This is the legacy gap: its order committed before consume(), no user lock now.
        legacy = InstantOrder(passenger_id=user["id"], status=S.done, price_estimate=600, ride_price=600)
        session.add(old_wait)
        session.add(legacy)
        session.commit()
        wait_id, legacy_id = old_wait.id, legacy.id
        initial_ids = {o.id for o in orders(user["id"])}
        ledger_count = len(session.exec(select(LedgerEntry)).all())
    data = quote(client, user)
    assert (data["price_kop"], data["promo_discount_kop"], data["payable_kop"]) == (60000, 15000, 45000)
    dispatches = observe_dispatch(monkeypatch)
    cas_reached, winner_committed = threading.Event(), threading.Event()
    actual_cas_rowcounts = []
    loser_thread = []

    def is_loser():
        return loser_thread and threading.get_ident() == loser_thread[0]

    def before(connection, cursor, statement, parameters, context, many):
        if is_loser() and statement.upper().lstrip().startswith("UPDATE PROMOREDEMPTION"):
            cas_reached.set()
            assert winner_committed.wait(15), "legacy winner never committed"

    def after(connection, cursor, statement, parameters, context, many):
        if is_loser() and statement.upper().lstrip().startswith("UPDATE PROMOREDEMPTION"):
            actual_cas_rowcounts.append(cursor.rowcount)

    # TestClient's sync endpoint runs on an AnyIO worker, not the caller thread.
    original_claim = __import__("app.taxi_quote_consent", fromlist=["claim_promo"]).claim_promo

    def observed_claim(*args, **kwargs):
        loser_thread.append(threading.get_ident())
        return original_claim(*args, **kwargs)
    monkeypatch.setattr("app.taxi_quote_consent.claim_promo", observed_claim)
    event.listen(engine, "before_cursor_execute", before)
    event.listen(engine, "after_cursor_execute", after)
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(confirm, client, user, data)
            try:
                assert cas_reached.wait(10), "protected request did not reach real CAS"
                # A different session cannot see loser INSERT or cleared wait before commit.
                with Session(engine) as observer:
                    assert {o.id for o in observer.exec(select(InstantOrder).where(
                        InstantOrder.passenger_id == user["id"])).all()} == initial_ids
                    assert observer.get(InstantOrder, wait_id).wait_until == until
                with Session(engine) as winner:
                    legacy = winner.get(InstantOrder, legacy_id)
                    assert promo_ride.consume(winner, user["id"], legacy) == 15000
                winner_committed.set()
            finally:
                winner_committed.set()
            response = future.result(20)
    finally:
        winner_committed.set()
        event.remove(engine, "before_cursor_execute", before)
        event.remove(engine, "after_cursor_execute", after)
    assert response.status_code == 409, response.text
    assert actual_cas_rowcounts == [0], "must prove a real losing UPDATE, not an earlier rejection"
    with Session(engine) as observer:
        saved = observer.exec(select(InstantOrder).where(InstantOrder.passenger_id == user["id"])).all()
        assert {o.id for o in saved} == initial_ids
        assert not any(o.consent_request_key for o in saved)
        assert observer.get(InstantOrder, wait_id).wait_until == until
        assert observer.get(InstantOrder, wait_id).promo_discount_kop == 0
        assert observer.get(InstantOrder, legacy_id).promo_discount_kop == 15000
        red = observer.exec(select(PromoRedemption).where(PromoRedemption.user_id == user["id"])).one()
        assert red.used_order_id == legacy_id and red.used_at is not None
        assert len(observer.exec(select(LedgerEntry)).all()) == ledger_count
    assert dispatches == []
    assert confirm(client, user, data).status_code == 409  # consumed pair never re-admits


@pytest.mark.skipif(engine.dialect.name!='postgresql',reason='real PostgreSQL locks only')
def test_same_waiting_promo_lock_order(client,user_factory,consent_market,monkeypatch):
 set_base(600);user=promo_user(client,user_factory);until=utcnow()+timedelta(minutes=30)
 with Session(engine) as s:
  old=InstantOrder(passenger_id=user['id'],status=S.expired,wait_until=until,price_estimate=600,ride_price=600,promo_discount_kop=15000)
  winner=InstantOrder(passenger_id=user['id'],status=S.done,price_estimate=600,ride_price=600)
  s.add(old);s.add(winner);s.commit();old_id,winner_id=old.id,winner.id
  red=s.exec(select(PromoRedemption).where(PromoRedemption.user_id==user['id'])).one()
  red.used_order_id=old_id;red.used_at=utcnow()-timedelta(minutes=1);s.add(red);s.commit()
  ledger_before=len(s.exec(select(LedgerEntry)).all())
 initial_ids={x.id for x in orders(user['id'])};data=quote(client,user)
 assert data['payable_kop']==45000
 a_ready,b_locked=threading.Event(),threading.Event();a_tid=[];b_tid=[];rowcounts=[]
 original=consent.claim_promo
 def observe_claim(*args,**kw):
  a_tid.append(threading.get_ident());return original(*args,**kw)
 monkeypatch.setattr(consent,'claim_promo',observe_claim)
 def before(conn,cursor,statement,parameters,ctx,many):
  sql=statement.upper()
  if a_tid and threading.get_ident()==a_tid[0] and sql.lstrip().startswith('UPDATE PROMOREDEMPTION'):
   a_ready.set();assert b_locked.wait(10),'legacy did not lock redemption'
 def after(conn,cursor,statement,parameters,ctx,many):
  sql=statement.upper()
  if b_tid and threading.get_ident()==b_tid[0] and 'FROM PROMOREDEMPTION' in sql and 'FOR UPDATE' in sql:b_locked.set()
  if a_tid and threading.get_ident()==a_tid[0] and sql.lstrip().startswith('UPDATE PROMOREDEMPTION'):rowcounts.append(cursor.rowcount)
 def legacy_consume():
  b_tid.append(threading.get_ident())
  with Session(engine) as s:return promo_ride.consume(s,user['id'],s.get(InstantOrder,winner_id))
 event.listen(engine,'before_cursor_execute',before);event.listen(engine,'after_cursor_execute',after)
 try:
  with ThreadPoolExecutor(max_workers=2) as pool:
   a=pool.submit(confirm,client,user,data)
   assert a_ready.wait(10),'protected did not reach CAS'
   b=pool.submit(legacy_consume)
   try:
    winner_discount=b.result(15);response=a.result(15)
   finally:b_locked.set()
 finally:
  b_locked.set();event.remove(engine,'before_cursor_execute',before);event.remove(engine,'after_cursor_execute',after)
 assert winner_discount==15000 and response.status_code==409,response.text
 assert rowcounts==[0]
 with Session(engine) as observer:
  current=observer.exec(select(InstantOrder).where(InstantOrder.passenger_id==user['id'])).all()
  assert {x.id for x in current}==initial_ids and not any(x.consent_request_key for x in current)
  assert observer.get(InstantOrder,old_id).wait_until==until
  assert observer.get(InstantOrder,old_id).promo_discount_kop==0
  assert observer.get(InstantOrder,winner_id).promo_discount_kop==15000
  red=observer.exec(select(PromoRedemption).where(PromoRedemption.user_id==user['id'])).one();assert red.used_order_id==winner_id
  assert len(observer.exec(select(LedgerEntry)).all())==ledger_before



def test_wait_worker_wins_before_closure_rejects_new_admission(
    client, user_factory, consent_market, monkeypatch,
):
    user = user_factory("SyntheticWorkerWins")
    until = utcnow() + timedelta(minutes=20)
    with Session(engine) as session:
        old = InstantOrder(passenger_id=user["id"], status=S.expired, wait_until=until,
                           searching_at=utcnow() - timedelta(hours=1), price_estimate=450, ride_price=450,
                           from_lat=52.591, from_lng=58.317, to_lat=52.716, to_lng=58.664)
        session.add(old)
        session.commit()
        old_id = old.id
    live_driver(client, user_factory)
    data = quote(client, user)
    ready, committed = threading.Event(), threading.Event()
    observed_live, admission_finished = threading.Event(), threading.Event()
    worker_session = []
    original = consent.claim_promo

    def before_closure(*args, **kwargs):
        value = original(*args, **kwargs)
        ready.set()
        assert observed_live.wait(15), "target LIVE state was not independently observed"
        return value
    monkeypatch.setattr(consent, "claim_promo", before_closure)

    def after_commit(session):
        if worker_session and session is worker_session[0]:
            committed.set()
            assert admission_finished.wait(15), "admission did not finish"

    def worker():
        with Session(engine) as session:
            worker_session.append(session)
            # Same real activation primitive as retry_waiting_orders, scoped to this
            # selected old_id. Other tests' waiting orders cannot trigger the barrier.
            return isv.start_matching(session, session.get(InstantOrder, old_id), notify=False)

    event.listen(Session, "after_commit", after_commit)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            admission = pool.submit(confirm, client, user, data)
            try:
                assert ready.wait(10), "admission did not reach closure boundary"
                retry = pool.submit(worker)
                assert committed.wait(10), "target worker did not commit searching"
                with Session(engine) as observer:
                    target = observer.get(InstantOrder, old_id)
                    assert target.status == S.searching and target.wait_until == until
                    visible = observer.exec(select(InstantOrder).where(
                        InstantOrder.passenger_id == user["id"])).all()
                    assert [o.id for o in visible] == [old_id]
                observed_live.set()
                response = admission.result(20)
            finally:
                observed_live.set()
                admission_finished.set()
            retry.result(20)
    finally:
        observed_live.set()
        admission_finished.set()
        event.remove(Session, "after_commit", after_commit)
    assert response.status_code == 409, response.text
    assert response.json()["detail"]["code"] == "taxi_consent_active_order"
    with Session(engine) as observer:
        saved = observer.exec(select(InstantOrder).where(InstantOrder.passenger_id == user["id"])).all()
        assert [o.id for o in saved] == [old_id] and not saved[0].consent_request_key
        assert saved[0].status in isv.LIVE_ORDER_STATUSES and saved[0].wait_until == until
