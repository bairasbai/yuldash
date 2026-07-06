"""Health / version — лёгкие пробы для мониторинга и деплоя.

- `/health`     — живость + состояние компонентов (db/redis/fcm). Всегда 200, поля
                  показывают реальный статус (мониторинг сам решает, что делать).
- `/health/ready` — гейт готовности для деплоя/оркестратора: 200 если БД доступна,
                  иначе 503 (не пускать трафик на инстанс без БД).
- `/version`    — версия сборки.

Ни одна проба не бросает исключение: недоступный компонент → его статус «fail»,
а не 500. Секретов в ответ не кладём.
"""
from fastapi import APIRouter
from fastapi.responses import JSONResponse
from sqlalchemy import text

from ..config import settings
from ..db import engine

router = APIRouter(tags=["health"])

API_VERSION = "0.1.0"


def _check_db() -> bool:
    try:
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return True
    except Exception:  # noqa: BLE001 — БД упала: сервис жив, но деградирован
        return False


def _check_redis() -> str:
    """'ok' — пинг прошёл; 'fail' — задан, но не отвечает; 'off' — Redis не настроен."""
    if not settings.redis_url:
        return "off"
    try:
        import redis
        client = redis.from_url(settings.redis_url, socket_connect_timeout=2, socket_timeout=2)
        client.ping()
        return "ok"
    except Exception:  # noqa: BLE001 — Redis недоступен: не роняем пробу
        return "fail"


def _check_fcm() -> str:
    """'configured' — путь к ключу Firebase задан и файл на месте; иначе 'not'."""
    cred = (settings.firebase_credentials or "").strip()
    if not cred:
        return "not"
    try:
        import os
        return "configured" if os.path.exists(cred) else "not"
    except Exception:  # noqa: BLE001
        return "not"


@router.get("/health")
def health():
    """Живость сервиса + состояние компонентов. Всегда 200 (чтобы деплой-проба
    отвечала); поля показывают реальное состояние.

    Совместимость: сохраняем плоские `status`/`db` (их парсит monitor.sh и старые
    тесты), добавляем `redis`/`fcm` и агрегат `components`."""
    db_ok = _check_db()
    redis_status = _check_redis()
    fcm_status = _check_fcm()
    db_status = "ok" if db_ok else "fail"
    return {
        "status": "ok" if db_ok else "degraded",
        "env": settings.env,
        "version": API_VERSION,
        "db": db_status,
        "redis": redis_status,
        "fcm": fcm_status,
        "components": {"db": db_status, "redis": redis_status, "fcm": fcm_status},
    }


@router.get("/health/ready")
def health_ready():
    """Гейт готовности для деплоя: 200 если БД доступна, иначе 503.
    Оркестратор/скрипт выкатки не переключает трафик, пока не 200."""
    if _check_db():
        return {"ready": True, "db": "ok"}
    return JSONResponse({"ready": False, "db": "fail"}, status_code=503)


@router.get("/version")
def version():
    """Версия API — для проверки, что задеплоилась нужная сборка."""
    return {"version": API_VERSION, "env": settings.env}
