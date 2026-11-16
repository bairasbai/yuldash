"""Taxi REST/WS writable-window parity, with unchanged order participants."""
from datetime import timedelta
from threading import Event

import pytest
from sqlmodel import Session, select
from starlette.websockets import WebSocketDisconnect

from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, Message, UserRole
from app.routers import chat
from app.timeutil import utcnow


@pytest.fixture
def case(client, user_factory, monkeypatch):
    driver = user_factory('LostItemDriver', role=UserRole.driver)
    passenger = user_factory('LostItemPassenger')
    with Session(engine) as session:
        order = InstantOrder(passenger_id=passenger['id'], driver_id=driver['id'],
                             status=InstantOrderStatus.done,
                             lost_item_until=utcnow() + timedelta(hours=1))
        session.add(order)
        session.commit()
        session.refresh(order)
        oid = order.id
    registered, disconnected, pushed = Event(), Event(), Event()
    pushes, broadcasts = [], []
    original_register = chat.manager.register
    original_disconnect = chat.manager.disconnect
    key = chat._order_chat_key(oid)

    def register(channel, ws):
        original_register(channel, ws)
        if channel == key:
            registered.set()

    def disconnect(channel, ws):
        original_disconnect(channel, ws)
        if channel == key:
            disconnected.set()

    async def broadcast(channel, frame):
        broadcasts.append((channel, frame))
        await chat.manager.local_broadcast(channel, frame)

    def push(session, recipient, title, body, data):
        pushes.append((recipient, data))
        pushed.set()

    monkeypatch.setattr(chat.manager, 'register', register)
    monkeypatch.setattr(chat.manager, 'disconnect', disconnect)
    monkeypatch.setattr(chat.manager, 'broadcast', broadcast)
    monkeypatch.setattr(chat, 'send_push', push)
    monkeypatch.setattr(chat, 'push_notification', lambda *a, **kw: None)
    monkeypatch.setattr(chat, 'notify_chat_message', lambda *a, **kw: None)
    return dict(oid=oid, driver=driver, passenger=passenger, registered=registered,
                disconnected=disconnected, pushed=pushed, pushes=pushes, broadcasts=broadcasts)


def change(case, **values):
    with Session(engine) as session:
        order = session.get(InstantOrder, case['oid'])
        for name, value in values.items():
            setattr(order, name, value)
        session.add(order)
        session.commit()


def rows(case):
    with Session(engine) as session:
        return session.exec(select(Message).where(Message.order_id == case['oid'])).all()


def auth(ws, case, actor='passenger'):
    ws.send_json({'type': 'auth', 'token': case[actor]['token']})
    assert case['registered'].wait(2), 'Participant was not registered'


@pytest.mark.parametrize('actor', ['driver', 'passenger'])
@pytest.mark.parametrize('status', [InstantOrderStatus.done, InstantOrderStatus.cancelled])
def test_lost_item_message_saved_once_broadcast_and_correct_push(client, case, actor, status):
    change(case, status=status)
    oid = case['oid']
    rest = client.post(f'/instant/orders/{oid}/messages', headers=case[actor]['auth'],
                       json={'text': 'REST control'})
    assert rest.status_code == 200
    case['pushes'].clear()
    case['pushed'].clear()
    with client.websocket_connect(f'/ws/instant/{oid}/chat') as ws:
        auth(ws, case, actor)
        ws.send_json({'type': 'message', 'text': 'WS lost item'})
        assert case['pushed'].wait(2), 'WS message was not pushed within bounded wait'
        frame = ws.receive_json()
        assert frame['type'] == 'message' and frame['text'] == 'WS lost item'
    saved = rows(case)
    assert [m.text for m in saved].count('WS lost item') == 1
    assert len(saved) == 2
    assert case['broadcasts'] == [(chat._order_chat_key(oid), frame)]
    peer = 'driver' if actor == 'passenger' else 'passenger'
    assert case['pushes'] == [(case[peer]['id'], {'type': 'order_chat', 'id': oid})]
    assert all(m.booking_id is None and m.parcel_id is None for m in saved)


@pytest.mark.parametrize('window', [None, -1, 0])
def test_no_or_expired_window_refuses_handshake_and_rest(client, case, monkeypatch, window):
    now = utcnow()
    monkeypatch.setattr(chat, 'utcnow', lambda: now)
    change(case, lost_item_until=None if window is None else now + timedelta(seconds=window))
    oid = case['oid']
    assert client.post(f'/instant/orders/{oid}/messages', headers=case['passenger']['auth'],
                       json={'text': 'closed'}).status_code == 409
    with client.websocket_connect(f'/ws/instant/{oid}/chat') as ws:
        ws.send_json({'type': 'auth', 'token': case['passenger']['token']})
        with pytest.raises(WebSocketDisconnect) as err:
            ws.receive_json()
        assert err.value.code == 1008
    assert not rows(case) and not case['pushes'] and not case['registered'].is_set()


@pytest.mark.parametrize('finish_with_window', [True, False])
def test_order_finishes_after_handshake(client, case, finish_with_window):
    change(case, status=InstantOrderStatus.accepted, lost_item_until=None)
    with client.websocket_connect(f"/ws/instant/{case['oid']}/chat") as ws:
        auth(ws, case)
        change(case, status=InstantOrderStatus.done,
               lost_item_until=utcnow() + timedelta(hours=1) if finish_with_window else None)
        ws.send_json({'type': 'message', 'text': 'after finish'})
        event = case['pushed'] if finish_with_window else case['disconnected']
        assert event.wait(2), 'No bounded completion after state transition'
        if finish_with_window:
            assert ws.receive_json()['text'] == 'after finish'
    assert len(rows(case)) == int(finish_with_window)
    assert len(case['pushes']) == int(finish_with_window)


def test_window_expires_after_handshake(client, case, monkeypatch):
    now = utcnow()
    monkeypatch.setattr(chat, 'utcnow', lambda: now)
    change(case, lost_item_until=now + timedelta(seconds=1))
    with client.websocket_connect(f"/ws/instant/{case['oid']}/chat") as ws:
        auth(ws, case)
        now += timedelta(seconds=1)
        ws.send_json({'type': 'message', 'text': 'too late'})
        assert case['disconnected'].wait(2)
    assert not rows(case) and not case['pushes'] and not case['broadcasts']


def test_active_order_without_lost_item_window_still_works(client, case):
    change(case, status=InstantOrderStatus.onboard, lost_item_until=None)
    with client.websocket_connect(f"/ws/instant/{case['oid']}/chat") as ws:
        auth(ws, case)
        ws.send_json({'type': 'message', 'text': 'active'})
        assert case['pushed'].wait(2)
        assert ws.receive_json()['text'] == 'active'
    assert len(rows(case)) == 1


def test_outsider_cannot_use_lost_item_window(client, case, user_factory):
    outsider = user_factory('NotInThisTaxi')
    with client.websocket_connect(f"/ws/instant/{case['oid']}/chat") as ws:
        ws.send_json({'type': 'auth', 'token': outsider['token']})
        with pytest.raises(WebSocketDisconnect) as err:
            ws.receive_json()
        assert err.value.code == 1008
    assert not rows(case) and not case['registered'].is_set()


def test_block_after_handshake_drops_message(client, case, monkeypatch):
    checked = Event()
    real_is_blocked = chat.is_blocked
    def observed_block(session, a, b):
        blocked = real_is_blocked(session, a, b)
        if blocked:
            checked.set()
        return blocked
    monkeypatch.setattr(chat, 'is_blocked', observed_block)
    from app.models import Block
    with client.websocket_connect(f"/ws/instant/{case['oid']}/chat") as ws:
        auth(ws, case)
        with Session(engine) as session:
            session.add(Block(user_id=case['driver']['id'], blocked_user_id=case['passenger']['id']))
            session.commit()
        ws.send_json({'type': 'message', 'text': 'blocked'})
        assert checked.wait(2)
    assert not rows(case) and not case['pushes'] and not case['broadcasts']
