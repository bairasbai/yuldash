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


# Force-update (B9b-1). Тон — тёплый, без обвинений; черновик BA → docs/tasks.md.
MSG_FORCE_UPDATE = {
    "ru": "Обнови Юлдаш 🙌 Вышла новая версия — эта уже не поддерживается",
    "ba": "Юлдашты яңырт 🙌 Яңы версия сыҡты — быныһы инде эшләмәй",
}


@router.get("/version/min")
def version_min():
    """Минимальная поддерживаемая версия приложения (force-update, B9b-1).
    min_version_code=0 → проверка выключена, клиент никого не блокирует.
    Клиент: versionCode < min → блокирующий экран «Обнови Юлдаш» + кнопка в стор.
    Офлайн/ошибка ручки на клиенте → пропускаем и НЕ блокируем (мягкая деградация)."""
    return {
        "min_version_code": settings.min_app_version_code,
        "message": MSG_FORCE_UPDATE,
        "store_url": settings.app_store_url,
    }
