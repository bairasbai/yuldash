"""Opt-in controls for the synthetic bank in the isolated acceptance stand."""

import hmac
from pathlib import Path
import re
import sys
from threading import Lock
from uuid import uuid4

import httpx
from cryptography.fernet import Fernet
from fastapi import Depends, Header, HTTPException, Request
from pydantic import BaseModel, StrictBool
from sqlmodel import Session, select

from app.card_payments import CARD_PAYMENTS_SWITCH
from app.config import settings
from app.models import AppSettingChange, DriverPaySettings, UserRole
from app.owner_switches import PAID_SWITCH, login_settings
from app.rideshare_bank_booking import BankRuntime
from app.rideshare_bank_models import RideshareBankCheckout, RideshareBankOperation, RideshareBankRecipient
from app.rideshare_bank_provider import BankResource, YooKassaSafeDeal
from app.rideshare_bank_queue import run_bank_queue
from app.rideshare_bank_recipient import save_bank_recipient
from app.routers.rideshare_bank import receipt_bank_runtime
from app.security import lock_refresh_user
from app.timeutil import utcnow


class BankSwitches(BaseModel):
    enabled: StrictBool


def mount_synthetic_bank(app, engine, control_key: str) -> None:
    if not re.fullmatch(r"[a-f0-9]{64}", control_key):
        raise ValueError("The synthetic bank control key must be 64 hex digits")
    if settings.env != "dev" or engine.dialect.name != "sqlite" or not engine.url.database:
        raise RuntimeError("The synthetic bank requires a local dev SQLite stand")
    database = Path(engine.url.database)
    if not database.is_absolute() or database.exists():
        raise RuntimeError("The synthetic bank requires a fresh absolute SQLite path")

    test_sources = Path(__file__).resolve().parents[2] / "backend" / "tests"
    sys.path.insert(0, str(test_sources))
    try:
        from bank_synthetic_provider import SyntheticBank
    finally:
        sys.path.pop(0)

    runtime = BankRuntime(
        True, Fernet(Fernet.generate_key()), lambda number: f"https://qa.booking.invalid/trips/{number}/receipt",
    )
    bank = SyntheticBank(engine)
    bank_lock = Lock()
    app.dependency_overrides[receipt_bank_runtime] = lambda: runtime

    def control(request: Request, x_qa_bank_key: str = Header(default="")) -> None:
        if request.client is None or request.client.host != "127.0.0.1" or not hmac.compare_digest(
                control_key.encode("ascii"), x_qa_bank_key.encode("utf-8")):
            raise HTTPException(403, "Forbidden")

    def find_checkout(session: Session, booking_id: int) -> RideshareBankCheckout:
        row = session.exec(select(RideshareBankCheckout).where(
            RideshareBankCheckout.booking_id == booking_id, RideshareBankCheckout.test_mode.is_(True),
            RideshareBankCheckout.closed_at.is_(None),
        )).one_or_none()
        if row is None:
            raise HTTPException(404, "Synthetic checkout not found")
        return row

    @app.post("/__qa/bank/switches", dependencies=[Depends(control)], include_in_schema=False)
    def bank_switches(body: BankSwitches):
        with Session(engine) as session:
            switches = login_settings(session, lock="update")
            for key in (PAID_SWITCH, CARD_PAYMENTS_SWITCH):
                row = switches[key]
                if row.value is not body.enabled:
                    session.add(AppSettingChange(key=key, old_value=row.value, new_value=body.enabled))
                    row.value = body.enabled
                    row.updated_at = utcnow()
            session.commit()
        return {"enabled": body.enabled}

    @app.post("/__qa/bank/drivers/{driver_id}", dependencies=[Depends(control)], include_in_schema=False)
    def register_recipient(driver_id: int):
        with Session(engine) as session:
            driver = lock_refresh_user(session, driver_id)
            if driver is None or driver.role != UserRole.driver or not driver.verified:
                raise HTTPException(404, "Verified synthetic driver not found")
            consent = session.exec(select(DriverPaySettings).where(DriverPaySettings.user_id == driver_id)).one_or_none()
            if consent is None:
                consent = DriverPaySettings(user_id=driver_id)
                session.add(consent)
            consent.accepts_card = True
            consent.accepts_card_at = utcnow()
            previous = session.get(RideshareBankRecipient, driver_id)
            save_bank_recipient(session, driver_id, f"synthetic-{uuid4().hex}",
                                expected_version=previous.version if previous else None, cipher=runtime.cipher, scope=runtime.recipient_scope)
            session.commit()
        return {"registered": True}

    @app.post("/__qa/bank/bookings/{booking_id}/step", dependencies=[Depends(control)], include_in_schema=False)
    def bank_step(booking_id: int):
        with bank_lock:
            with Session(engine) as session:
                checkout = find_checkout(session, booking_id)
                checkout_id = checkout.id
                checkout.next_bank_check_at = None
                session.commit()
            with httpx.Client(transport=httpx.MockTransport(bank.handle), trust_env=False) as client:
                provider = YooKassaSafeDeal(client, shop_id="synthetic", secret_key="synthetic", test_mode=True)
                work = run_bank_queue(engine, runtime=runtime, provider=provider, limit=1, checkout_id=checkout_id)
        return {"state": work[0].state if work else None}

    @app.post("/__qa/bank/bookings/{booking_id}/approve", dependencies=[Depends(control)], include_in_schema=False)
    def bank_approve(booking_id: int):
        with bank_lock:
            with Session(engine) as session:
                checkout = find_checkout(session, booking_id)
                payment_id = session.exec(select(RideshareBankOperation.id).where(
                    RideshareBankOperation.checkout_id == checkout.id,
                    RideshareBankOperation.resource == BankResource.payment.value,
                    RideshareBankOperation.state == "pending",
                )).one_or_none()
            if payment_id is None or payment_id not in bank.by_key:
                raise HTTPException(409, "Synthetic payment is not ready")
            try:
                bank.finish_payment(payment_id)
            except ValueError:
                raise HTTPException(409, "Synthetic payment is not ready") from None
        return {"approved": True}
