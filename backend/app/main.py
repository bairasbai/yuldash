"""Юлдаш API — точка входа (фабрика приложения).

Раньше это был плоский монолит (~1400 строк, ~50 роутов в одном файле).
Теперь — тонкая сборка: домены вынесены в `app/routers/*`, общая бизнес-логика
в `app/services.py`, схемы в `app/schemas.py`. Поведение 1:1.

Совместимость с живым клиентом: каждый роутер монтируется ДВАЖДЫ — на корень
(`/rides`, как сейчас у задеплоенной беты) и под версионным префиксом
(`/api/v1/rides`, на будущее). Старые URL не ломаются, новые доступны сразу.

Энтрипоинт прежний: `app.main:app` (systemd `yuldash-api`, uvicorn).
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlmodel import Session

from .config import settings
from .db import engine, init_db
from .middleware import (
    AccessLogMiddleware, RateLimitMiddleware, SecurityHeadersMiddleware,
    unhandled_exception_handler,
)
from .routers import all_routers
from .routers.health import API_VERSION
from .services import MEDIA_DIR, init_chat_redis, seed_demo

API_V1_PREFIX = "/api/v1"


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.validate_production()
    init_db()
    with Session(engine) as session:
        if settings.seed_demo:
            seed_demo(session)
        from .instant_service import seed_tariffs
        seed_tariffs(session)   # тарифы «Быстрого заказа» нужны и в проде (не под seed_demo)
    await init_chat_redis()   # WS pub/sub между воркерами (если есть Redis), иначе локально
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="Yuldash API", version=API_VERSION, lifespan=lifespan)
    # Порядок: последний add_middleware — внешний (выполняется первым).
    # Хотим: лимит запросов отсекает раньше всего → добавляем его последним.
    app.add_middleware(AccessLogMiddleware)
    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_methods=["GET", "POST", "OPTIONS"],   # API использует только их
        allow_headers=["Authorization", "Content-Type"],
    )
    app.add_middleware(RateLimitMiddleware)
    app.add_exception_handler(Exception, unhandled_exception_handler)
    # Медиа: голосовые — публично; документы водителя отдаются отдельно (/secure/docs, см. drivers.py).
    app.mount("/media", StaticFiles(directory=MEDIA_DIR), name="media")
    # Каждый роутер — на корень (совместимость) и под /api/v1 (версионирование).
    for r in all_routers:
        app.include_router(r)
        app.include_router(r, prefix=API_V1_PREFIX)
    return app


app = create_app()
