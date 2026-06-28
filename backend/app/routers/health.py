"""Health / version — лёгкие пробы для мониторинга и деплоя."""
from fastapi import APIRouter
from sqlalchemy import text

from ..config import settings
from ..db import engine

router = APIRouter(tags=["health"])

API_VERSION = "0.1.0"


@router.get("/health")
def health():
    """Живость сервиса + доступность БД. Всегда 200 (чтобы деплой-проба отвечала),
    поле `db` показывает реальное состояние подключения."""
    db_ok = True
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
    except Exception:  # noqa: BLE001 — БД упала: сервис жив, но деградирован
        db_ok = False
    return {"status": "ok" if db_ok else "degraded", "env": settings.env, "db": "ok" if db_ok else "down"}


@router.get("/version")
def version():
    """Версия API — для проверки, что задеплоилась нужная сборка."""
    return {"version": API_VERSION, "env": settings.env}
