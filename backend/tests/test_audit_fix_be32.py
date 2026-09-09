"""BE32: dry-run фонового робота не расходует и не возвращает промоскидку."""
from datetime import timedelta

import pytest
from sqlmodel import Session

from app import promo_ride, taxi_worker
from app.db import engine
from app.models import (
    InstantOrder,
    InstantOrderStatus as S,
    PromoCode,
    PromoRedemption,
    UserRole,
)
from app.timeutil import utcnow


def _seed_discounted_order(
    passenger_id: int,
    *,
    status: S,
    suffix: str,
    driver_id: int | None = None,
    old: bool = False,
    waiting_expired: bool = False,
) -> tuple[int, int, int]:
    now = utcnow()
    moved_at = now - timedelta(days=2) if old else now
    with Session(engine) as session:
        promo = PromoCode(
            code=f"BE32{suffix}{passenger_id}".upper(),
            title="BE32",
            kind=promo_ride.KIND,
            perk_value=100,
        )
        session.add(promo)
        session.commit()
        session.refresh(promo)

        order = InstantOrder(
            passenger_id=passenger_id,
            driver_id=driver_id,
            status=status,
            created_at=moved_at,
            accepted_at=moved_at if status == S.accepted else None,
            searching_at=moved_at,
            wait_until=(now - timedelta(minutes=1)) if waiting_expired else None,
            price_estimate=500,
            promo_discount_kop=10_000,
        )
        session.add(order)
        session.commit()
        session.refresh(order)

        redemption = PromoRedemption(
            promo_id=promo.id,
            user_id=passenger_id,
            discount_kop=10_000,
            used_order_id=order.id,
            used_at=now - timedelta(hours=1),
        )
        session.add(redemption)
        session.commit()
        session.refresh(redemption)
        return order.id, redemption.id, promo.id


def _row_snapshot(model, row_id: int) -> dict:
    """Все колонки, чтобы dry-run не спрятал побочное изменение в соседнем поле."""
    with Session(engine) as session:
        row = session.get(model, row_id)
        assert row is not None
        return {column.name: getattr(row, column.name) for column in model.__table__.columns}


def _snapshot(order_id: int, redemption_id: int, promo_id: int) -> dict:
    return {
        "order": _row_snapshot(InstantOrder, order_id),
        "redemption": _row_snapshot(PromoRedemption, redemption_id),
        "promo": _row_snapshot(PromoCode, promo_id),
    }


def test_dry_run_keeps_every_order_and_promo_field(
    client, user_factory, monkeypatch,
):
    passenger = user_factory("BE32 dry passenger")
    driver = user_factory("BE32 dry driver", role=UserRole.driver)
    stuck = _seed_discounted_order(
        passenger["id"], status=S.accepted, suffix="DRYA", driver_id=driver["id"], old=True,
    )
    waited = _seed_discounted_order(
        user_factory("BE32 dry wait")["id"], status=S.expired, suffix="DRYW",
        waiting_expired=True,
    )
    before = {"stuck": _snapshot(*stuck), "waited": _snapshot(*waited)}

    # Если dry-run ошибочно полезет в отправки, тест всё равно не выйдет наружу.
    monkeypatch.setattr(taxi_worker, "push_notification", lambda *_a, **_k: None)
    with Session(engine) as session:
        assert stuck[0] in taxi_worker.close_stuck_orders(session, dry_run=True)
        assert waited[0] in taxi_worker.finish_expired_waits(session, dry_run=True)

    after = {"stuck": _snapshot(*stuck), "waited": _snapshot(*waited)}
    assert after == before


@pytest.mark.parametrize("entrypoint", ["release", "release_ids"])
def test_release_refuses_an_active_order_even_if_its_id_is_passed_by_mistake(
    client, user_factory, entrypoint,
):
    passenger = user_factory(f"BE32 active {entrypoint}")
    ids = _seed_discounted_order(
        passenger["id"], status=S.accepted, suffix=entrypoint.upper(),
    )
    before = _snapshot(*ids)

    if entrypoint == "release":
        with Session(engine) as session:
            changed = promo_ride.release(session, session.get(InstantOrder, ids[0]))
        assert changed is False
    else:
        assert promo_ride.release_ids([ids[0]]) == 0

    assert _snapshot(*ids) == before


def test_real_worker_releases_only_orders_it_really_closed_and_is_idempotent(
    client, user_factory, monkeypatch,
):
    driver = user_factory("BE32 real driver", role=UserRole.driver)
    stuck = _seed_discounted_order(
        user_factory("BE32 real stuck")["id"], status=S.accepted, suffix="REALA",
        driver_id=driver["id"], old=True,
    )
    waited = _seed_discounted_order(
        user_factory("BE32 real wait")["id"], status=S.expired, suffix="REALW",
        waiting_expired=True,
    )
    fresh = _seed_discounted_order(
        user_factory("BE32 real fresh")["id"], status=S.accepted, suffix="REALF",
        driver_id=driver["id"], old=False,
    )
    fresh_before = _snapshot(*fresh)

    monkeypatch.setattr(taxi_worker, "push_notification", lambda *_a, **_k: None)
    monkeypatch.setattr(taxi_worker.isv, "_notify_order_shares", lambda *_a, **_k: None)
    with Session(engine) as session:
        closed = taxi_worker.close_stuck_orders(session)
        finished = taxi_worker.finish_expired_waits(session)

    assert stuck[0] in closed
    assert waited[0] in finished
    assert _snapshot(*stuck)["order"]["status"] == S.cancelled
    assert _snapshot(*waited)["order"]["status"] == S.expired
    for ids in (stuck, waited):
        state = _snapshot(*ids)
        assert state["order"]["promo_discount_kop"] == 0
        assert state["redemption"]["used_order_id"] is None
        assert state["redemption"]["used_at"] is None

    assert fresh[0] not in closed
    assert _snapshot(*fresh) == fresh_before

    # Повтор не возвращает уже возвращённую скидку и не трогает живой контроль.
    assert promo_ride.release_ids([stuck[0], waited[0], fresh[0]]) == 0
    assert _snapshot(*fresh) == fresh_before
