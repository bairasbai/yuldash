"""Границы одновременной работы одного исполнителя, реальные PostgreSQL-сессии."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import timedelta

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus as S
from app.timeutil import utcnow
from test_instant import _offered_order, _create_order, fake_redis  # noqa: F401
from test_courier import _apply, _approve, _order, _courier_on, _make_courier  # noqa: F401
from app.models import UserRole, ParcelDelivery


def test_existing_delivery_can_coexist_but_taxi_blocks_taking_next(client, user_factory, fake_redis):
    driver, passenger, taxi = _offered_order(client, user_factory, fake_redis)
    admin = user_factory("Админ двух режимов", role=UserRole.admin)
    application = _apply(client, driver)
    assert application.status_code == 200, application.text
    assert _approve(client, admin, application.json()["id"]).status_code == 200
    assert client.post("/courier/online", headers=driver["auth"], json={"zone": "region"}).status_code == 200
    sender = user_factory("Отправитель двух режимов")
    first = _order(client, sender)
    assert first.status_code == 200, first.text
    first_id = first.json()["id"]
    assert client.post(f"/parcels/{first_id}/accept", headers=driver["auth"]).status_code == 200
    oid = taxi["id"]
    assert client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"]).status_code == 200
    second = _order(client, sender, description="Другая доставка после такси")
    assert second.status_code == 200, second.text
    second_id = second.json()["id"]
    assert second_id != first_id
    blocked = client.post(f"/parcels/{second_id}/accept", headers=driver["auth"])
    assert blocked.status_code == 409, blocked.text
    for action in ("arrived", "onboard", "done"):
        response = client.post(f"/instant/orders/{oid}/{action}", headers=driver["auth"])
        assert response.status_code == 200, response.text
    assert client.post(f"/parcels/{second_id}/accept", headers=driver["auth"]).status_code == 200


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Нужны независимые PostgreSQL row locks")
def test_one_driver_cannot_accept_two_different_offers(client, user_factory, fake_redis):
    driver, passenger, first = _offered_order(client, user_factory, fake_redis)
    other_passenger = user_factory("Второй пассажир двух офферов")
    second = _create_order(client, other_passenger)
    # Два оффера одному водителю: допустимая исходная гонка матчера, которую accept
    # прямо обещает остановить. Подготавливаем её, не подменяя проверяемые HTTP accept.
    with Session(engine) as session:
        order = session.get(InstantOrder, second["id"])
        order.status = S.offered
        order.current_offer_driver_id = driver["id"]
        order.offer_expires_at = utcnow() + timedelta(minutes=1)
        session.add(order)
        session.commit()
    start = threading.Barrier(2)
    widened = threading.Event()

    def before_update(conn, cursor, statement, parameters, context, executemany):
        sql = statement.lower()
        if sql.startswith("update instantorder set") and not widened.is_set():
            widened.set()
            time.sleep(0.5)

    def accept(oid):
        start.wait(timeout=5)
        return client.post(f"/instant/orders/{oid}/accept", headers=driver["auth"])

    event.listen(engine, "before_cursor_execute", before_update)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            jobs = [pool.submit(accept, item["id"]) for item in (first, second)]
            results = [job.result(timeout=15) for job in jobs]
    finally:
        event.remove(engine, "before_cursor_execute", before_update)
    assert widened.is_set()
    assert sorted(result.status_code for result in results) == [200, 409], [r.text for r in results]
    with Session(engine) as session:
        active = session.exec(select(InstantOrder).where(
            InstantOrder.driver_id == driver["id"],
            InstantOrder.status.in_([S.accepted, S.arriving, S.onboard]),
        )).all()
    assert len(active) == 1


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Нужны независимые PostgreSQL row locks")
def test_two_couriers_cannot_take_the_same_delivery(client, user_factory):
    couriers = [_make_courier(client, user_factory) for _ in range(2)]
    sender = user_factory("Отправитель одной коробки")
    created = _order(client, sender)
    assert created.status_code == 200, created.text
    pid = created.json()["id"]
    start = threading.Barrier(2)

    def accept(courier):
        start.wait(timeout=5)
        response = client.post(f"/parcels/{pid}/accept", headers=courier["auth"])
        return courier["id"], response

    with ThreadPoolExecutor(max_workers=2) as pool:
        jobs = [pool.submit(accept, courier) for courier in couriers]
        results = [job.result(timeout=15) for job in jobs]
    assert sorted(response.status_code for _, response in results) == [200, 409]
    winner = next(uid for uid, response in results if response.status_code == 200)
    with Session(engine) as session:
        parcel = session.get(ParcelDelivery, pid)
        assert parcel.status == "accepted" and parcel.courier_id == winner
