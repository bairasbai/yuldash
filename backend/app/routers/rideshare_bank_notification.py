"""Receive a bank notification through the authenticated reverse-proxy route."""

import hmac
import json
import re

import anyio
import httpx
from fastapi import APIRouter, Request, Response
from starlette.concurrency import run_in_threadpool
from starlette.requests import ClientDisconnect

from ..config import settings
from ..db import engine
from ..rideshare_bank_provider import BankNotification, bank_notification
from ..rideshare_bank_queue import reconcile_bank_notification_once
from ..rideshare_bank_runtime import RideshareBankConfiguration, configure_rideshare_bank


router = APIRouter(include_in_schema=False)
_TOKEN_HEADER = "x-yuldash-bank-proxy-token"
_MAX_BODY_BYTES = 64 * 1024


def _process_notification(configuration: RideshareBankConfiguration, hint: BankNotification) -> bool:
    timeout = httpx.Timeout(5.0, connect=2.0, pool=1.0)
    with httpx.Client(timeout=timeout, follow_redirects=False, trust_env=False) as client:
        return reconcile_bank_notification_once(engine, hint, runtime=configuration.runtime,
                                                provider=configuration.provider(client))


@router.post("/payments/rideshare-bank/webhook")
async def rideshare_bank_notification(request: Request) -> Response:
    expected = settings.rideshare_bank_webhook_proxy_token.get_secret_value()
    received = request.headers.getlist(_TOKEN_HEADER)
    if (not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", expected) or len(received) != 1
            or not hmac.compare_digest(expected.encode("ascii"), received[0].encode("utf-8"))):
        return Response(status_code=404)
    configuration = configure_rideshare_bank(settings)
    if configuration is None:
        return Response(status_code=404)
    body = bytearray()
    try:
        with anyio.fail_after(3):
            async for chunk in request.stream():
                if len(body) + len(chunk) > _MAX_BODY_BYTES:
                    return Response(status_code=413)
                body.extend(chunk)
        hint = bank_notification(json.loads(body))
    except TimeoutError:
        return Response(status_code=408)
    except (ValueError, RecursionError, ClientDisconnect):
        return Response(status_code=400)
    if hint is None:
        return Response(status_code=200)

    slots = request.app.state.rideshare_bank_notification_slots
    acquired = False
    with anyio.move_on_after(0.5):
        await slots.acquire()
        acquired = True
    if not acquired:
        return Response(status_code=503)
    try:
        observed = await run_in_threadpool(_process_notification, configuration, hint)
    finally:
        slots.release()
    return Response(status_code=200 if observed else 503)
