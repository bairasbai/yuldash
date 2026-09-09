"""Два устройства одного пассажира не вызывают две машины одновременно."""
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from sqlalchemy import event
from sqlmodel import Session, select

from app import instant_service as isv
from app.db import engine
from app.models import InstantOrder, TextFlag
from test_instant import _order_body


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Нужны реальные PostgreSQL row locks")
@pytest.mark.parametrize("comment", ["", "Позвони +79991234567"], ids=["ordinary", "moderated"])
def test_two_devices_create_only_one_active_order(client, user_factory, monkeypatch, comment):
    passenger = user_factory("Два устройства пассажира")
    # Проверяем создание заказа; внешнюю диспетчеризацию оставляем на следующий шаг.
    monkeypatch.setattr(isv, "start_matching", lambda session, order: order)
    ready = threading.Barrier(2)
    first_read = threading.Event()

    def widen_empty_read_window(conn, cursor, statement, parameters, context, executemany):
        sql = statement.lower()
        if "insert into instantorder " in sql and not first_read.is_set():
            first_read.set()
            # Второй HTTP-запрос уже запущен. При исправном lock он ждёт commit первого;
            # при преждевременном commit оба успевают прочитать пустой список.
            time.sleep(0.5)

    def create():
        ready.wait(timeout=5)
        return client.post("/instant/orders", headers=passenger["auth"], json=_order_body(comment=comment))

    event.listen(engine, "before_cursor_execute", widen_empty_read_window)
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(create) for _ in range(2)]
            responses = [future.result(timeout=15) for future in futures]
    finally:
        event.remove(engine, "before_cursor_execute", widen_empty_read_window)
    assert first_read.is_set(), "Окно конкурентного чтения не было проверено"
    assert [response.status_code for response in responses] == [200, 200]
    ids = [response.json()["id"] for response in responses]
    with Session(engine) as session:
        active = session.exec(select(InstantOrder).where(
            InstantOrder.passenger_id == passenger["id"],
            InstantOrder.status.in_(isv.LIVE_ORDER_STATUSES),
        )).all()
        flags = session.exec(select(TextFlag).where(
            TextFlag.user_id == passenger["id"], TextFlag.place == "order_comment",
        )).all()
    assert len(active) == 1, f"Пассажиру созданы {len(active)} активных заказа: {ids}"
    assert ids[0] == ids[1] == active[0].id
    assert len(flags) == (1 if comment else 0), "Модерация сохраняется один раз для созданного заказа"
