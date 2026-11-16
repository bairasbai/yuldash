"""Temporary diagnostic: actual HTTP/ASGI WS, isolated SQLite; no release acceptance."""
from datetime import timedelta
from threading import Event
import json
import socket
import pytest
from sqlmodel import Session, select
from app.db import engine
from app.models import InstantOrder, InstantOrderStatus, Message, UserRole
from app.timeutil import utcnow
from app.routers import chat

@pytest.fixture(autouse=True)
def no_io(monkeypatch):
    def denied(*a, **kw):
        raise AssertionError('External socket I/O forbidden by diagnostic')
    monkeypatch.setattr(socket.socket, 'connect', denied)
    monkeypatch.setattr(socket.socket, 'connect_ex', denied)
    monkeypatch.setattr(chat, 'send_push', lambda *a, **kw: None)
    monkeypatch.setattr(chat, 'push_notification', lambda *a, **kw: None)
    monkeypatch.setattr(chat, 'notify_chat_message', lambda *a, **kw: None)


def test_taxi_lost_item_ws_matches_rest(client, user_factory, monkeypatch):
    driver=user_factory('Diagnostic Taxi', role=UserRole.driver)
    pax=user_factory('Diagnostic Passenger')
    with Session(engine) as s:
        order=InstantOrder(passenger_id=pax['id'], driver_id=driver['id'], status=InstantOrderStatus.done, lost_item_until=utcnow()+timedelta(hours=1))
        s.add(order); s.commit(); s.refresh(order); oid=order.id
    control=client.post(f'/instant/orders/{oid}/messages', headers=pax['auth'], json={'text':'REST lost item control'})
    assert control.status_code == 200, control.text
    registered=Event(); disconnected=Event()
    original_register=chat.manager.register; original_disconnect=chat.manager.disconnect
    key=chat._order_chat_key(oid)
    def register(channel, ws):
        original_register(channel, ws)
        if channel == key: registered.set()
    def disconnect(channel, ws):
        original_disconnect(channel, ws)
        if channel == key: disconnected.set()
    monkeypatch.setattr(chat.manager, 'register', register)
    monkeypatch.setattr(chat.manager, 'disconnect', disconnect)
    with client.websocket_connect(f'/ws/instant/{oid}/chat') as ws:
        ws.send_json({'type':'auth','token':pax['token']})
        assert registered.wait(2), 'WS auth did not register within bounded wait'
        ws.send_json({'type':'message','text':'WS lost item message'})
        assert disconnected.wait(2), 'Handler did not terminate within bounded wait; no long wait attempted'
    with Session(engine) as s:
        texts=[m.text for m in s.exec(select(Message).where(Message.order_id==oid)).all()]
    print('OBSERVATION', json.dumps({'case':'taxi-lost-item','rest_status':control.status_code,'ws_registered':registered.is_set(),'handler_disconnected':disconnected.is_set(),'persisted_texts':texts}))
    assert 'WS lost item message' in texts, 'Accepted lost-item WS drops message despite REST write window'
