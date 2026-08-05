"""Юлдаш API — точка входа (фабрика приложения).

Раньше это был плоский монолит (~1400 строк, ~50 роутов в одном файле).
Теперь — тонкая сборка: домены вынесены в `app/routers/*`, общая бизнес-логика
в `app/services.py`, схемы в `app/schemas.py`. Поведение 1:1.

Совместимость с живым клиентом: каждый роутер монтируется ДВАЖДЫ — на корень
(`/rides`, как сейчас у задеплоенной беты) и под версионным префиксом
(`/api/v1/rides`, на будущее). Старые URL не ломаются, новые доступны сразу.

Энтрипоинт прежний: `app.main:app` (systemd `yuldash-api`, uvicorn).
"""
import sys
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlmodel import Session

from .config import settings
from .db import engine, init_db
from .digest import DailyDigestMiddleware
from .middleware import (
    AccessLogMiddleware, RateLimitMiddleware, SecurityHeadersMiddleware,
    unhandled_exception_handler,
)
from .observability import init_sentry
from .routers import all_routers
from .routers.health import API_VERSION
from .services import MEDIA_DIR, init_chat_redis, seed_demo, seed_pickup_points
from .storage import StorageError, get_storage

API_V1_PREFIX = "/api/v1"


def _say(line: str) -> None:
    """Печать, которая не роняет запуск сервера.

    Консоль Windows по умолчанию cp1251 и не умеет ни «₽», ни «—». Обычный print на такой
    консоли бросает UnicodeEncodeError, а это происходит внутри lifespan — сервер не стартует
    вообще. Терять запуск из-за значка рубля в предупреждении нельзя, поэтому непечатаемые
    символы заменяем на «?». На проде (UTF-8) видно как было."""
    try:
        print(line, flush=True)
    except UnicodeEncodeError:
        enc = getattr(sys.stdout, "encoding", None) or "ascii"
        print(line.encode(enc, "replace").decode(enc, "replace"), flush=True)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.validate_production()
    for _w in settings.launch_warnings():
        _say(f"[launch] ВНИМАНИЕ: {_w}")
    init_db()
    with Session(engine) as session:
        if settings.seed_demo:
            seed_demo(session)
            from .routers.medical import seed_medical_partners  # F22: справочник клиник (идемпотентно)
            seed_medical_partners(session)
        from .instant_service import seed_tariffs
        seed_tariffs(session)   # тарифы «Быстрого заказа» нужны и в проде (не под seed_demo)
        from .geo import seed_settlements, seed_villages
        seed_settlements(session)   # справочник НП (география, волна 2) — тоже нужен в проде
        seed_villages(session)      # деревни РБ из app/data/villages_rb.json (пустой → no-op)
        # Ориентиры точек сбора (F14) — публичный справочник, нужен и на проде
        # (не под флагом SEED_DEMO). Идемпотентно: повторный старт не дублирует.
        seed_pickup_points(session)
    await init_chat_redis()   # WS pub/sub между воркерами (если есть Redis), иначе локально
    yield
    # H4: аккуратная остановка на SIGTERM/редеплое — снять pub/sub задачу, закрыть Redis и пул БД
    # (иначе на каждом рестарте течём соединениями при небольшом pool_size).
    try:
        from .services import close_chat_redis
        await close_chat_redis()
    except Exception:  # noqa: BLE001
        pass
    try:
        engine.dispose()
    except Exception:  # noqa: BLE001
        pass


def create_app() -> FastAPI:
    # Sentry — до создания приложения (чтобы перехват стоял с первого запроса каждого
    # воркера). Без SENTRY_DSN это полный no-op — прод работает как раньше.
    init_sentry()
    # В проде прячем интерактивную схему (/docs, /redoc, /openapi.json): она раскрывает
    # карту всех приватных admin/payment/moderation-эндпоинтов. В dev — доступна для удобства.
    doc_urls = {"docs_url": None, "redoc_url": None, "openapi_url": None} if settings.is_prod else {}
    app = FastAPI(title="Yuldash API", version=API_VERSION, lifespan=lifespan, **doc_urls)
    # Порядок: последний add_middleware — внешний (выполняется первым).
    # Хотим: лимит запросов отсекает раньше всего → добавляем его последним.
    app.add_middleware(DailyDigestMiddleware)   # дневная сводка админу (B9b-3): дешёвый гейт, отправка в фоне
    app.add_middleware(AccessLogMiddleware)
    app.add_middleware(SecurityHeadersMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origin_list,
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],   # DELETE — админ-разбан устройства
        allow_headers=["Authorization", "Content-Type"],
    )
    app.add_middleware(RateLimitMiddleware)
    app.add_exception_handler(Exception, unhandled_exception_handler)

    # Хранилище медиа недоступно (S3 отвалился) → мягкая двуязычная 503, а не 500 «краш».
    async def _storage_unavailable(request, exc):   # noqa: ANN001
        return JSONResponse(
            status_code=503,
            content={"detail": {"ru": "Не удалось загрузить файл. Попробуй ещё раз.",
                                 "ba": "Файлды йөкләп булманы. Тағы бер тапҡыр ҡабатла."}},
        )
    app.add_exception_handler(StorageError, _storage_unavailable)
    # Медиа: голосовые/фото чата — публично; документы водителя отдаются отдельно (/secure/docs, см. drivers.py).
    # Локально — раздаём с диска (StaticFiles). В S3-режиме файлов на диске нет:
    # тот же путь /media/... редиректит на подписанный (presigned) URL объекта.
    storage = get_storage()
    if storage.is_remote:
        @app.get("/media/{path:path}", name="media")
        def media_redirect(path: str):
            return RedirectResponse(storage.url(path))
    else:
        app.mount("/media", StaticFiles(directory=MEDIA_DIR), name="media")
    # Каждый роутер — на корень (совместимость) и под /api/v1 (версионирование).
    for r in all_routers:
        app.include_router(r)
        app.include_router(r, prefix=API_V1_PREFIX)
    return app


app = create_app()
