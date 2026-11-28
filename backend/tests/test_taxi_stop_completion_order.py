"""Independent sessions exercise stop mutations around automatic completion."""
from datetime import timedelta

import pytest
from fastapi import HTTPException
from sqlmodel import Session

from app import cleanup, instant_service as isv
from app.config import settings
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S, UserRole
from app.timeutil import utcnow
from test_taxi_worker import _order


@pytest.mark.parametrize("initially_stopped,completion_first", [
    (False, True),
    (True, True),
    (True, False),
])
def test_stop_does_not_change_a_completed_fare(
    client, monkeypatch, user_factory, initially_stopped, completion_first,
):
    now = utcnow().replace(microsecond=0)
    old = now - timedelta(hours=settings.taxi_stale_hours + 1)
    monkeypatch.setattr(settings, "fee_tier1_percent", 3.0)
    monkeypatch.setattr(settings, "wait_fee_rub_per_min", 5)  # Verified below via tariff helper.
    monkeypatch.setattr("app.services.send_push", lambda *a, **kw: None)
    pax = user_factory("StopRacePassenger")
    drv = user_factory("StopRaceDriver", role=UserRole.driver)
    started = now - timedelta(minutes=20) if initially_stopped else None
    oid = _order(pax["id"], driver_id=drv["id"], status=S.onboard,
                 created_at=old, onboard_at=old, price_estimate=400,
                 waiting_fee_kop=2000, stop_started_at=started)

    with Session(engine, expire_on_commit=False) as delayed:
        stale = delayed.get(InstantOrder, oid)
        assert stale.status == S.onboard
        delayed.commit()
        if completion_first:
            assert cleanup.close_stale_orders(now) == 1
            with pytest.raises(HTTPException) as err:
                isv.toggle_stop(delayed, stale, now=now)
            assert err.value.status_code == 409
            expected_wait = 2000
        else:
            expected_wait = isv.capped_waiting_kop(2000, started, now)
            assert expected_wait > 2000
            isv.toggle_stop(delayed, stale, now=now)
            assert cleanup.close_stale_orders(now) == 1

    expected_price = 400 + expected_wait // 100
    with Session(engine) as fresh:
        final = fresh.get(InstantOrder, oid)
        assert final.status == S.done and final.price_final == expected_price
        assert final.waiting_fee_kop == expected_wait
        assert final.stop_started_at == (started if completion_first else None)
    for actor in (pax, drv):
        response = client.get(f"/instant/orders/{oid}/receipt", headers=actor["auth"])
        assert response.status_code == 200
        receipt = response.json()
        assert receipt["amount_kop"] == expected_price * 100
        assert receipt["waiting_fee_kop"] == expected_wait
        if actor is drv:
            assert receipt["driver_fee_kop"] == expected_price * 3
