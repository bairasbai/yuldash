"""Booking WS retries share the durable REST receipt, including after reconnect."""
from datetime import timedelta
from threading import Event

import pytest
from sqlmodel import Session, select
from starlette.websockets import WebSocketDisconnect

from app.db import engine
from app.models import Block, Booking, BookingStatus, ChatMessageRequest, Message, Ride, User
from app.timeutil import utcnow
from test_location_privacy import _confirmed_booking


@pytest.fixture
def case(client, user_factory, monkeypatch):
    from app.routers import chat
    driver, passenger, bid = _confirmed_booking(client, user_factory, 'WsRetry')
    events = []
    push_done = Event()

    async def broadcast(channel, frame):
        events.append(('ws_live', frame['id']))
        await chat.manager.local_broadcast(channel, frame)

    monkeypatch.setattr(chat.manager, 'broadcast', broadcast)
    def push(*args, **kwargs):
        events.append(('ws_push', args[2]))
        push_done.set()

    monkeypatch.setattr(chat, 'send_push', push)
    monkeypatch.setattr(chat, 'notify_chat_message', lambda *a, **kw: events.append(('rest_live', a[1]['id'])))
    monkeypatch.setattr(chat, 'push_notification', lambda *a, **kw: events.append(('rest_push', a[1])))
    return driver, passenger, bid, events, push_done


def connect(client, case, user=None):
    return client.websocket_connect(f'/ws/bookings/{case[2]}')


def auth(ws, case, user=None):
    ws.send_json({'type': 'auth', 'token': (user or case[1])['token']})


def send(ws, key='shared-request', text='same logical message', **extra):
    ws.send_json({'type': 'message', 'text': text, 'request_id': key, 'temp_id': -7, **extra})
    return ws.receive_json()


def rest(client, case, key='shared-request', text='same logical message'):
    return client.post(f'/bookings/{case[2]}/messages',
                       headers={**case[1]['auth'], 'Idempotency-Key': key}, json={'text': text})


def rows(case):
    with Session(engine) as session:
        return session.exec(select(Message).where(Message.booking_id == case[2])).all()


def test_ws_retry_after_reconnect_returns_original_id_without_rebroadcast(client, case):
    with connect(client, case) as ws:
        auth(ws, case)
        first = send(ws)
        assert first['request_id'] == 'shared-request'
    before = list(case[3])
    with connect(client, case) as ws:
        auth(ws, case)
        replay = send(ws)
        assert replay['id'] == first['id']
        assert replay['request_id'] == 'shared-request'
    assert case[3] == before
    assert len(rows(case)) == 1
    with Session(engine) as session:
        assert len(session.exec(select(ChatMessageRequest).where(ChatMessageRequest.booking_id == case[2])).all()) == 1


@pytest.mark.parametrize('first_transport', ['ws', 'rest'])
def test_ws_and_rest_share_one_receipt(client, case, first_transport):
    if first_transport == 'rest':
        response = rest(client, case)
        assert response.status_code == 200, response.text
        first = response.json()
        before = list(case[3])
        with connect(client, case) as ws:
            auth(ws, case)
            second = send(ws)
    else:
        with connect(client, case) as ws:
            auth(ws, case)
            first = send(ws)
        before = list(case[3])
        response = rest(client, case)
        assert response.status_code == 200, response.text
        second = response.json()
    assert second['id'] == first['id']
    assert len(rows(case)) == 1
    assert case[3] == before


def test_ws_replay_goes_only_to_sender_and_separate_keys_allow_same_text(client, case):
    with connect(client, case) as sender, connect(client, case) as recipient:
        auth(sender, case)
        auth(recipient, case, case[0])
        first = send(sender)
        assert recipient.receive_json()['id'] == first['id']
        assert send(sender)['id'] == first['id']
        second = send(sender, key='different-request')
        assert second['id'] != first['id']
        # A duplicate broadcast would be ahead of this genuinely new message.
        assert recipient.receive_json()['id'] == second['id']
    assert len(rows(case)) == 2
    assert [x[0] for x in case[3]].count('ws_live') == 2
    assert [x[0] for x in case[3]].count('ws_push') == 2


def test_ws_same_key_different_payload_is_rejected_without_disconnect(client, case):
    with connect(client, case) as ws:
        auth(ws, case)
        first = send(ws)
        rejected = send(ws, text='different payload')
        assert rejected == {'type': 'rejected', 'reason': 'request_conflict',
                            'status_code': 409, 'temp_id': -7, 'request_id': 'shared-request'}
        assert send(ws)['id'] == first['id']
    assert len(rows(case)) == 1


@pytest.mark.parametrize('key', ['', 'x' * 129, None, 3, [], {}])
def test_ws_invalid_request_key_is_rejected_and_connection_remains_usable(client, case, key):
    with connect(client, case) as ws:
        auth(ws, case)
        rejected = send(ws, key=key)
        assert rejected['type'] == 'rejected'
        assert rejected['status_code'] == 422
        assert rejected['temp_id'] == -7
        assert send(ws)['type'] == 'message'
    assert len(rows(case)) == 1


@pytest.mark.parametrize('text,status', [(None, 422), (2, 422), ([], 422), ({}, 422),
                                        ('x' * 4001, 422), ('', 400), ('  ', 400)])
def test_ws_bad_or_empty_text_does_not_crash_or_create_message(client, case, text, status):
    with connect(client, case) as ws:
        auth(ws, case)
        rejected = send(ws, text=text)
        assert rejected['type'] == 'rejected'
        assert rejected['status_code'] == status
        assert send(ws)['type'] == 'message'
    assert len(rows(case)) == 1


@pytest.mark.parametrize('payload', [None, [], 12, 'not an object'])
def test_ws_non_object_json_does_not_end_connection(client, case, payload):
    with connect(client, case) as ws:
        auth(ws, case)
        ws.send_json(payload)
        assert send(ws)['type'] == 'message'
    assert len(rows(case)) == 1


def test_ws_accepted_replay_ignores_new_message_flood_limit(client, case, monkeypatch):
    from app.routers import chat
    monkeypatch.setattr(chat.settings, 'flood_chat_per_min', 1)
    with connect(client, case) as ws:
        auth(ws, case)
        first = send(ws)
        assert send(ws)['id'] == first['id']
        rejected = send(ws, key='new-request')
        assert rejected['type'] == 'rejected' and rejected['status_code'] == 429
    assert len(rows(case)) == 1


def test_ws_accepted_replay_ignores_closed_window_but_cannot_create_new_message(client, case):
    with connect(client, case) as ws:
        auth(ws, case)
        first = send(ws)
        with Session(engine) as session:
            booking = session.get(Booking, case[2])
            ride = session.get(Ride, booking.ride_id)
            booking.status = BookingStatus.done
            ride.depart_at = utcnow() - timedelta(days=30)
            session.add(booking)
            session.add(ride)
            session.commit()
        assert send(ws)['id'] == first['id']
        rejected = send(ws, key='new-request')
        assert rejected['type'] == 'rejected' and rejected['status_code'] == 409
    assert len(rows(case)) == 1


@pytest.mark.parametrize('access_change', ['blocked', 'participant', 'revoked'])
def test_ws_replay_rechecks_current_access_before_returning_receipt(client, case, user_factory, access_change):
    foreign = user_factory('NewWsOwner') if access_change == 'participant' else None
    with connect(client, case) as ws:
        auth(ws, case)
        send(ws)
        assert case[4].wait(timeout=2), 'Initial send push must finish before measuring replay side effects'
        before = list(case[3])
        with Session(engine) as session:
            if access_change == 'blocked':
                session.add(Block(user_id=case[0]['id'], blocked_user_id=case[1]['id']))
            elif access_change == 'participant':
                booking = session.get(Booking, case[2])
                booking.passenger_id = foreign['id']
                session.add(booking)
            else:
                user = session.get(User, case[1]['id'])
                user.tokens_valid_from = utcnow() + timedelta(seconds=1)
                session.add(user)
            session.commit()
        with pytest.raises(WebSocketDisconnect) as error:
            send(ws)
        assert error.value.code == 1008
        assert case[3] == before
    assert len(rows(case)) == 1


@pytest.mark.parametrize('deleted', [False, True])
def test_ws_replay_returns_current_edited_or_deleted_message(client, case, deleted):
    with connect(client, case) as ws:
        auth(ws, case)
        first = send(ws)
        with Session(engine) as session:
            message = session.get(Message, first['id'])
            message.text = '' if deleted else 'edited later'
            message.edited = not deleted
            message.deleted = deleted
            session.add(message)
            session.commit()
        replay = send(ws)
        assert replay['id'] == first['id']
        assert replay['text'] == ('' if deleted else 'edited later')
        assert replay['deleted'] is deleted
        assert replay['edited'] is not deleted
    assert len(rows(case)) == 1


def test_ws_legacy_keyless_frames_remain_separate_messages(client, case):
    with connect(client, case) as ws:
        auth(ws, case)
        ws.send_json({'type': 'message', 'text': 'legacy'})
        first = ws.receive_json()
        ws.send_json({'type': 'message', 'text': 'legacy'})
        second = ws.receive_json()
        assert first['id'] != second['id']
        assert 'request_id' not in first and 'request_id' not in second
    assert len(rows(case)) == 2


def test_ws_unique_conflict_recovers_winner_without_broadcast_or_push(client, case, monkeypatch):
    from app.routers import chat
    assert rest(client, case).status_code == 200
    before = list(case[3])
    first_id = rows(case)[0].id
    real_replay = chat._replay_message
    calls = 0

    def miss_once(*args):
        nonlocal calls
        calls += 1
        return None if calls == 1 else real_replay(*args)

    monkeypatch.setattr(chat, '_replay_message', miss_once)
    with connect(client, case) as ws:
        auth(ws, case)
        replay = send(ws)
        assert replay['id'] == first_id
    assert calls == 2
    assert case[3] == before
    assert len(rows(case)) == 1
