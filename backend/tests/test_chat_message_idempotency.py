import pytest
from sqlmodel import Session, select
from app.db import engine
from app.models import Message
from test_location_privacy import _confirmed_booking

@pytest.fixture
def chat_case(client, user_factory, monkeypatch):
    from app.routers import chat
    driver, passenger, bid = _confirmed_booking(client, user_factory, 'IdempotentChat')
    events = []
    monkeypatch.setattr(chat, 'notify_chat_message', lambda *a, **kw: events.append(('live', a)))
    monkeypatch.setattr(chat, 'push_notification', lambda *a, **kw: events.append(('push', a)))
    return passenger, bid, events

def post(client, case, key='fixture-key', text='fixture message', user=None):
    passenger, bid, _ = case
    headers = dict((user or passenger)['auth'])
    if key is not None: headers['Idempotency-Key'] = key
    return client.post(f'/bookings/{bid}/messages', headers=headers, json={'text': text})

def test_lost_response_retry_returns_same_message_once(client, chat_case):
    first = post(client, chat_case)
    assert first.status_code == 200, first.text
    before = len(chat_case[2])
    retry = post(client, chat_case)
    assert retry.status_code == 200, retry.text
    assert retry.json()['id'] == first.json()['id']
    assert len(chat_case[2]) == before
    with Session(engine) as s:
        assert len(s.exec(select(Message).where(Message.booking_id == chat_case[1])).all()) == 1

def test_same_key_different_payload_conflicts(client, chat_case):
    assert post(client, chat_case).status_code == 200
    assert post(client, chat_case, text='changed').status_code == 409

def test_foreign_user_cannot_replay(client, user_factory, chat_case):
    assert post(client, chat_case).status_code == 200
    response = post(client, chat_case, user=user_factory('ForeignIdempotency'))
    assert response.status_code == 403

@pytest.mark.parametrize('key', ['', 'x'*129])
def test_invalid_key(client, chat_case, key):
    assert post(client, chat_case, key=key).status_code == 422

def test_without_key_preserves_new_message_behavior(client, chat_case):
    a = post(client, chat_case, key=None)
    b = post(client, chat_case, key=None)
    assert a.status_code == b.status_code == 200
    assert a.json()['id'] != b.json()['id']

def test_key_scoped_to_sender_and_booking(client, user_factory, chat_case):
    first = post(client, chat_case)
    with Session(engine) as s:
        from app.models import Booking, Ride
        booking = s.get(Booking, chat_case[1])
        driver_id = s.get(Ride, booking.ride_id).driver_id
    # An independent booking can use the same opaque key.
    _, passenger2, bid2 = _confirmed_booking(client, user_factory, 'SecondIdempotency')
    second = post(client, (passenger2, bid2, []))
    assert first.status_code == second.status_code == 200
    assert first.json()['id'] != second.json()['id']
    # The other participant on this same booking also owns a distinct key namespace.
    from app.security import make_token
    driver_headers = {'Authorization': 'Bearer ' + make_token(driver_id)}
    other = client.post(f'/bookings/{chat_case[1]}/messages', headers={**driver_headers, 'Idempotency-Key': 'fixture-key'}, json={'text':'fixture message'})
    assert other.status_code == 200, other.text
    assert other.json()['id'] != first.json()['id']


def test_blocked_participant_cannot_replay(client, chat_case, monkeypatch):
    assert post(client, chat_case).status_code == 200
    monkeypatch.setattr('app.routers.chat.is_blocked', lambda *args: True)
    assert post(client, chat_case).status_code == 403


def test_original_payload_receipt_survives_message_edit(client, chat_case):
    first = post(client, chat_case)
    with Session(engine) as s:
        message = s.get(Message, first.json()['id'])
        message.text = 'edited later'
        s.add(message); s.commit()
    retry = post(client, chat_case)
    assert retry.status_code == 200
    assert retry.json()['id'] == first.json()['id']
    assert retry.json()['text'] == 'edited later'
    assert post(client, chat_case, text='edited later').status_code == 409


def test_transcript_part_of_payload(client, chat_case):
    assert post(client, chat_case).status_code == 200
    response = client.post(f'/bookings/{chat_case[1]}/messages', headers={**chat_case[0]['auth'], 'Idempotency-Key':'fixture-key'}, json={'text':'fixture message','transcript':'different'})
    assert response.status_code == 409


def test_database_rejects_duplicate_receipt(client, chat_case):
    from app.models import ChatMessageRequest
    from sqlalchemy.exc import IntegrityError
    assert post(client, chat_case).status_code == 200
    with Session(engine) as s:
        receipt = s.exec(select(ChatMessageRequest).where(ChatMessageRequest.booking_id == chat_case[1])).one()
        data = receipt.model_dump(exclude={'id'})
        s.add(ChatMessageRequest(**data))
        with pytest.raises(IntegrityError): s.commit()
        s.rollback()


def test_cors_allows_idempotency_header(client):
    from app.config import settings
    assert settings.cors_origin_list
    response = client.options('/bookings/1/messages', headers={'Origin':settings.cors_origin_list[0], 'Access-Control-Request-Method':'POST', 'Access-Control-Request-Headers':'Authorization,Content-Type,Idempotency-Key'})
    assert response.status_code == 200, response.text
    assert 'idempotency-key' in response.headers['access-control-allow-headers'].lower()


def test_migration_upgrades_existing_schema_without_losing_message():
    import importlib.util
    from pathlib import Path
    from sqlalchemy import create_engine, inspect, text
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    path = Path(__file__).parents[1] / 'alembic/versions/bt_chat_message_retry.py'
    spec = importlib.util.spec_from_file_location('chat_retry_migration', path)
    migration = importlib.util.module_from_spec(spec); spec.loader.exec_module(migration)
    db = create_engine('sqlite://')
    with db.begin() as connection:
        connection.execute(text('CREATE TABLE message (id INTEGER PRIMARY KEY, text TEXT)'))
        connection.execute(text("INSERT INTO message VALUES (7, 'fixture')"))
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
            migration.upgrade()  # dynamic baseline compatibility
        assert connection.execute(text('SELECT text FROM message WHERE id=7')).scalar_one() == 'fixture'
        assert inspect(connection).get_unique_constraints('chatmessagerequest')[0]['column_names'] == ['sender_id', 'booking_id', 'request_key']
    db.dispose()


def test_unique_conflict_recovers_winner_without_duplicate_side_effects(client, chat_case, monkeypatch):
    from app.routers import chat
    first = post(client, chat_case)
    assert first.status_code == 200
    before = len(chat_case[2])
    real_replay = chat._replay_message
    calls = 0
    def miss_then_read(*args):
        nonlocal calls
        calls += 1
        # Model the initial SELECT running before the other transaction commits.
        return None if calls == 1 else real_replay(*args)
    monkeypatch.setattr(chat, '_replay_message', miss_then_read)
    retry = post(client, chat_case)
    assert retry.status_code == 200, retry.text
    assert retry.json()['id'] == first.json()['id']
    assert calls == 2
    assert len(chat_case[2]) == before
    with Session(engine) as s:
        assert len(s.exec(select(Message).where(Message.booking_id == chat_case[1])).all()) == 1


def test_receipt_is_deleted_with_its_message(client, chat_case):
    from app.models import ChatMessageRequest
    from sqlalchemy import delete, inspect
    result = post(client, chat_case)
    assert result.status_code == 200
    with Session(engine) as session:
        receipt = session.exec(select(ChatMessageRequest).where(ChatMessageRequest.message_id == result.json()['id'])).one()
        receipt_id = receipt.id
        assert any(index['column_names'] == ['message_id'] for index in inspect(engine).get_indexes('chatmessagerequest'))
        session.execute(delete(Message).where(Message.id == result.json()['id']))
        session.commit()
    with Session(engine) as session:
        assert session.get(ChatMessageRequest, receipt_id) is None


def test_receipt_index_migration_preserves_existing_data():
    import importlib.util
    from pathlib import Path
    from sqlalchemy import create_engine, inspect, text
    from alembic.migration import MigrationContext
    from alembic.operations import Operations
    path = Path(__file__).parents[1] / 'alembic/versions/bu_chat_receipt_index.py'
    spec = importlib.util.spec_from_file_location('receipt_index_migration', path)
    migration = importlib.util.module_from_spec(spec); spec.loader.exec_module(migration)
    db = create_engine('sqlite://')
    with db.begin() as connection:
        connection.execute(text('CREATE TABLE chatmessagerequest (id INTEGER PRIMARY KEY, message_id INTEGER)'))
        connection.execute(text('INSERT INTO chatmessagerequest VALUES (1, 7)'))
        with Operations.context(MigrationContext.configure(connection)):
            migration.upgrade()
            migration.upgrade()
        assert connection.execute(text('SELECT message_id FROM chatmessagerequest WHERE id=1')).scalar_one() == 7
        assert inspect(connection).get_indexes('chatmessagerequest')[0]['column_names'] == ['message_id']
    db.dispose()
