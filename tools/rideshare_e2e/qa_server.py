"""Local QA entry point for the existing Windows launcher."""
import ipaddress
import os
from pathlib import Path
import socket
import sys
from uuid import uuid4

def loopback(host):
    if host in (None, "", "localhost"):
        return True
    try:
        return ipaddress.ip_address(host).is_loopback
    except ValueError:
        return False


def guard(original, get_host):
    def checked(*args, **kwargs):
        if not loopback(get_host(*args, **kwargs)):
            raise OSError("Only loopback connections are allowed in the test stand")
        return original(*args, **kwargs)
    return checked


def host_argument(host, *args, **kwargs):
    return host


def connection_host(sock, address):
    return address[0] if sock.family in (socket.AF_INET, socket.AF_INET6) else None


socket.getaddrinfo = guard(socket.getaddrinfo, host_argument)
socket.gethostbyname = guard(socket.gethostbyname, host_argument)
socket.gethostbyname_ex = guard(socket.gethostbyname_ex, host_argument)
socket.socket.connect = guard(socket.socket.connect, connection_host)
socket.socket.connect_ex = guard(socket.socket.connect_ex, connection_host)

import uvicorn
from fastapi import Request

from app.config import settings
from app.db import engine
from app.main import app

database = Path(sys.argv[1]).resolve()
port = int(sys.argv[2])
telegram_secret = os.environ.get("RSACC_TELEGRAM_SECRET", "")
telegram_token = "synthetic-local-qa-bot" if telegram_secret else ""
if (
    settings.env != "dev"
    or settings.sms_provider != "mock"
    or settings.payments_provider != "mock"
    or any((settings.firebase_credentials, settings.sentry_dsn, settings.redis_url))
    or settings.telegram_bot_token != telegram_token
    or settings.telegram_webhook_secret != telegram_secret
    or engine.dialect.name != "sqlite"
    or Path(engine.url.database).resolve() != database
    or database.exists()
):
    raise RuntimeError("The local stand requires a fresh SQLite database and mock providers")

run_id = uuid4().hex


@app.middleware("http")
async def identify_probe(request: Request, call_next):
    response = await call_next(request)
    if request.url.path == "/config/features":
        response.headers["X-Yuldash-QA-Run"] = run_id
    return response


@app.get("/__qa/stand", include_in_schema=False)
def qa_stand():
    with engine.connect() as connection:
        current = next(row.file for row in connection.exec_driver_sql("PRAGMA database_list") if row.name == "main")
    if Path(current).resolve() != database:
        raise RuntimeError("The stand is connected to a different database")
    return {
        "run_id": run_id, "database": str(database), "sms": "mock",
        "external": "blocked", "telegram_mock": bool(telegram_secret),
    }


uvicorn.run(app, host="127.0.0.1", port=port, access_log=False)
