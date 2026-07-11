"""Наблюдаемость Юлдаша: инициализация Sentry.

Правило: без `SENTRY_DSN` — полный no-op. Ничего не импортируется тяжёлого,
ничего не шлётся наружу, приложение работает ровно как раньше. DSN — секрет,
только из env (`.env`/переменные окружения), в git не попадает.

Отдельный модуль (не в main.py), чтобы инициализацию можно было позвать и из
gunicorn-воркеров, и из тестов, и чтобы отсутствие пакета `sentry-sdk` не роняло
импорт приложения (мягкий фолбэк).
"""
from .config import settings

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
        sentry_sdk.init(
            dsn=dsn,
            environment=settings.env,
            traces_sample_rate=settings.sentry_traces_sample_rate,
            # Не тащим тела запросов/куки/ip в Sentry — там телефоны и токены (152-ФЗ).
            send_default_pii=False,
        )
        _sentry_ready = True
        print(f"[SENTRY] инициализирован (env={settings.env})")
        return True
    except Exception as e:  # noqa: BLE001 — наблюдаемость не должна ронять прод
        print(f"[SENTRY] init skipped: {type(e).__name__}: {e}")
        return False


def sentry_enabled() -> bool:
    return _sentry_ready
