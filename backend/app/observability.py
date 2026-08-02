"""Наблюдаемость Юлдаша: инициализация Sentry.

Правило: без `SENTRY_DSN` — полный no-op. Ничего не импортируется тяжёлого,
ничего не шлётся наружу, приложение работает ровно как раньше. DSN — секрет,
только из env (`.env`/переменные окружения), в git не попадает.

Отдельный модуль (не в main.py), чтобы инициализацию можно было позвать и из
gunicorn-воркеров, и из тестов, и чтобы отсутствие пакета `sentry-sdk` не роняло
импорт приложения (мягкий фолбэк).
"""
from .config import settings
from .logs import log

_sentry_ready = False


def init_sentry() -> bool:
    """Поднять Sentry, если задан DSN. Возвращает True, если реально включили.

    Идемпотентна: повторный вызов не пересоздаёт клиента. Любая ошибка инициализации
    (нет пакета, кривой DSN) — тихо логируется и НЕ роняет приложение."""
    global _sentry_ready
    if _sentry_ready:
        return True
    dsn = (settings.sentry_dsn or "").strip()
    if not dsn:
        return False  # no-op: DSN не задан
    try:
        import sentry_sdk
        integrations = []
        try:
            # Фоновые корутины (WS pub/sub, задачи) — их исключения иначе не попадают в Sentry.
            from sentry_sdk.integrations.asyncio import AsyncioIntegration
            integrations.append(AsyncioIntegration())
        except Exception:  # noqa: BLE001 — интеграция опциональна, не роняем init
            pass
        sentry_sdk.init(
            dsn=dsn,
            environment=settings.env,
            traces_sample_rate=settings.sentry_traces_sample_rate,
            integrations=integrations,
            # Не тащим тела запросов/куки/ip в Sentry — там телефоны и токены (152-ФЗ).
            send_default_pii=False,
        )
        _sentry_ready = True
        log.info(f"[SENTRY] инициализирован (env={settings.env})")
        return True
    except Exception as e:  # noqa: BLE001 — наблюдаемость не должна ронять прод
        log.warning(f"[SENTRY] init skipped: {type(e).__name__}: {e}")
        return False


def sentry_enabled() -> bool:
    return _sentry_ready


def capture(exc: BaseException) -> None:
    """Отправить исключение в Sentry, если он включён (иначе тихий no-op). Безопасно —
    сама наблюдаемость не должна ронять код (фоновые задачи, обработчик 500, WS-циклы)."""
    if not _sentry_ready:
        return
    try:
        import sentry_sdk
        sentry_sdk.capture_exception(exc)
    except Exception:  # noqa: BLE001
        pass
