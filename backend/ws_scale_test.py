"""Прод-проверка WS-чата под gunicorn (несколько воркеров) + Redis pub/sub.
Открывает несколько реальных WS-соединений к localhost:8000. Часть соединений
попадёт на ДРУГОЙ воркер (gunicorn распределяет accept). Отправляем сообщение от
одного — все остальные должны получить. Если получают все → Redis раздаёт между
воркерами. Если только часть → pub/sub не работает.

Запуск на сервере: cd /opt/yuldash && .venv/bin/python ws_scale_test.py
"""
import asyncio
import json
import sys
from datetime import datetime, timedelta

import websockets
from sqlmodel import Session, select

from app.db import engine
from app.models import Booking, Ride, RideStatus, User, UserRole
from app.security import make_token

URL = "ws://127.0.0.1:8000/ws/bookings/{bid}"
N_RECEIVERS = 6   # запас, чтобы соединения гарантированно легли на оба воркера


def pick_or_make():
    """Вернуть (booking_id, passenger_token, driver_token, cleanup_ids|None)."""
    with Session(engine) as s:
        b = s.exec(select(Booking)).first()
        if b:
            ride = s.get(Ride, b.ride_id)
            return b.id, make_token(b.passenger_id), make_token(ride.driver_id), None
        # нет броней → создаём временные сущности, потом удалим
        drv = User(phone="+70000000091", name="WSDrv", role=UserRole.driver)
        psg = User(phone="+70000000092", name="WSPsg", role=UserRole.passenger)
        s.add(drv); s.add(psg); s.commit(); s.refresh(drv); s.refresh(psg)
        ride = Ride(driver_id=drv.id, from_city="A", to_city="B",
                    depart_at=datetime.utcnow() + timedelta(days=1), status=RideStatus.active)
        s.add(ride); s.commit(); s.refresh(ride)
        bk = Booking(ride_id=ride.id, passenger_id=psg.id)
        s.add(bk); s.commit(); s.refresh(bk)
        return bk.id, make_token(psg.id), make_token(drv.id), (bk.id, ride.id, drv.id, psg.id)


async def main():
    bid, ptok, dtok, cleanup = pick_or_make()
    url = URL.format(bid=bid)
    receivers = []
    # Несколько получателей под токеном водителя (один юзер, много соединений — допустимо).
    for _ in range(N_RECEIVERS):
        ws = await websockets.connect(url)
        await ws.send(json.dumps({"type": "auth", "token": dtok}))
        receivers.append(ws)
    # Отправитель — пассажир.
    sender = await websockets.connect(url)
    await sender.send(json.dumps({"type": "auth", "token": ptok}))
    await asyncio.sleep(0.5)  # дать подпискам встать

    marker = f"WSSCALE-{datetime.utcnow().timestamp()}"
    await sender.send(json.dumps({"type": "message", "text": marker}))

    got = 0
    for ws in receivers:
        try:
            while True:
                raw = await asyncio.wait_for(ws.recv(), timeout=4)
                m = json.loads(raw)
                if m.get("type") == "message" and m.get("text") == marker:
                    got += 1
                    break
        except asyncio.TimeoutError:
            pass
        finally:
            await ws.close()
    await sender.close()

    print(f"receivers={N_RECEIVERS} got_message={got}")
    ok = got == N_RECEIVERS
    print("WS SCALE OK (cross-worker раздача работает)" if ok else "WS SCALE FAIL (часть воркеров не получила — pub/sub не раздаёт)")

    if cleanup:
        bk_id, ride_id, drv_id, psg_id = cleanup
        with Session(engine) as s:
            from app.models import Message
            for msg in s.exec(select(Message).where(Message.booking_id == bk_id)).all():
                s.delete(msg)
            s.delete(s.get(Booking, bk_id))
            s.delete(s.get(Ride, ride_id))
            s.delete(s.get(User, drv_id))
            s.delete(s.get(User, psg_id))
            s.commit()
        print("cleanup done")
    sys.exit(0 if ok else 1)


asyncio.run(main())
