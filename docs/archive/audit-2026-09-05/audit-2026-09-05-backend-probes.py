"""Офлайн-пробы BE-01..BE-08. Запуск из audit/backend: python ../docs/audit-2026-09-05-backend-probes.py.
Новая SQLite в памяти; реальные сервисы и ключи не нужны, сетевых вызовов нет.
"""
import os
import sys
from pathlib import Path
from datetime import timedelta

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'backend'))
sys.stdout.reconfigure(encoding='utf-8')
os.environ.update(ENV='dev', DATABASE_URL='sqlite://', JWT_SECRET='audit-only-secret',
                  SEED_DEMO='false', RATE_LIMIT_ENABLED='false', STORAGE_BACKEND='local',
                  TELEGRAM_BOT_TOKEN='', FIREBASE_CREDENTIALS='', VAPID_PRIVATE_KEY='',
                  VAPID_PUBLIC_KEY='', SENTRY_DSN='', REDIS_URL='')
from sqlmodel import SQLModel, Session, create_engine, select
from fastapi import HTTPException
from fastapi.testclient import TestClient
from app.models import User, UserRole, Ride, Payment, RideRequest, RequestResponse, WebPushSubscription, DeviceToken
from app.security import issue_tokens, _decode, _token_revoked
from app.routers.auth import logout, push_web_subscribe, WebPushSubIn, push_register, PushTokenIn
from app.storage import S3Storage, reset_storage
from app.main import create_app
from app.timeutil import utcnow
from app.routers import payments as payments_router
from app.automatch import automatch_once
import app.services as services

engine = create_engine('sqlite://')
SQLModel.metadata.create_all(engine)
services.push_notification = lambda *a, **kw: None
with Session(engine) as s:
    u = User(phone='+79990000001', name='Audit')
    v = User(phone='+79990000002', name='Other', role=UserRole.driver)
    s.add(u); s.add(v); s.commit(); s.refresh(u); s.refresh(v)
    old = issue_tokens(s, u.id)['access_token']
    logout(u, s)
    print('BE-01 after logout revoked:', _token_revoked(_decode(old), u))
    issue_tokens(s, u.id)
    print('BE-01 after login OLD token revoked:', _token_revoked(_decode(old), u))
    sub = WebPushSubIn(endpoint='https://127.0.0.1:8443/internal', keys={'p256dh': 'auditkey', 'auth': 'auditkey'})
    print('BE-05 private endpoint accepted:', push_web_subscribe(sub, u, s, ''))
    logout(u, s)
    print('BE-03 web subscriptions after logout:', len(s.exec(select(WebPushSubscription).where(WebPushSubscription.user_id == u.id)).all()))
    s.add(DeviceToken(user_id=u.id, token='audit-victim-token', device_id='a'*32)); s.commit()
    print('BE-04 rebind without device:', push_register(PushTokenIn(token='audit-victim-token'), v, s, ''))
    print('BE-04 new owner:', s.exec(select(DeviceToken).where(DeviceToken.token == 'audit-victim-token')).first().user_id)
    ride = Ride(driver_id=v.id, from_city='A', to_city='B', depart_at=utcnow(), seats_total=3, seats_left=3, price=300)
    s.add(ride); s.commit(); s.refresh(ride)
    pay = Payment(user_id=v.id, purpose='boost', ride_id=ride.id, tier='quick', amount_kop=2000)
    s.add(pay); s.commit(); s.refresh(pay)
    pid, rid = pay.id, ride.id
    req = RideRequest(passenger_id=u.id, from_city='A', to_city='B')
    s.add(req); s.commit(); s.refresh(req)
    s.add(RequestResponse(request_id=req.id, driver_id=v.id, price=300, created_at=utcnow()-timedelta(minutes=5))); s.commit()
    print('BE-08 WebPush-only automatch:', automatch_once(s, dry_run=True))
    s.add(DeviceToken(user_id=u.id, token='audit-own-fcm')); s.commit()
    print('BE-08 same person + FCM automatch:', automatch_once(s, dry_run=True))
with Session(engine) as stale, Session(engine) as fresh:
    old_payment = stale.get(Payment, pid)
    payments_router._activate_payment(fresh, fresh.get(Payment, pid))
    with Session(engine) as check:
        first = check.get(Ride, rid).boosted_until
    payments_router._activate_payment(stale, old_payment)
    with Session(engine) as check:
        second = check.get(Ride, rid).boosted_until
    print('BE-07 extra boost hours:', (second-first).total_seconds()/3600)
with Session(engine) as s:
    pay = Payment(user_id=1, purpose='donate', amount_kop=10000)
    s.add(pay); s.commit(); s.refresh(pay); pid = pay.id
    accepted = []
    def remote_then_timeout(*a, **kw):
        accepted.append(kw.get('idempotence_key'))
        raise TimeoutError('simulated response lost AFTER remote accepted')
    payments_router.create_payment = remote_then_timeout
    try:
        payments_router._start_yookassa(s, pay, 'Audit', '+79990000001')
    except HTTPException as e:
        print('BE-06 response:', e.status_code)
    print('BE-06 accepted keys:', accepted, 'local Payment exists:', s.get(Payment, pid) is not None)
class FakeS3:
    def generate_presigned_url(self, op, Params, ExpiresIn):
        return 'https://example.invalid/'+Params['Key']+'?signed=1'
reset_storage(S3Storage(bucket='audit', client=FakeS3()))
client = TestClient(create_app())  # без lifespan: app DB и сиды не запускаются
for prefix in ('docs', 'evidence', 'carphoto'):
    r = client.get('/media/'+prefix+'/1_test.jpg', follow_redirects=False)
    print('BE-02 private route:', prefix, r.status_code, r.headers.get('location'))
