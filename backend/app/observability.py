"""Наблюдаемость Юлдаша: инициализация Sentry.

Правило: без `SENTRY_DSN` — полный no-op. Ничего не импортируется тяжёлого,
ничего не шлётся наружу, приложение работает ровно как раньше. DSN — секрет,
только из env (`.env`/переменные окружения), в git не попадает.

Отдельный модуль (не в main.py), чтобы инициализацию можно было позвать и из
gunicorn-воркеров, и из тестов, и чтобы отсутствие пакета `sentry-sdk` не роняло
импорт приложения (мягкий фолбэк).
"""
import re

from .config import settings
from .logs import log

_sentry_ready = False

# ------------------------------ вычистка личных данных ------------------------------
# `send_default_pii=False` запрещает Sentry ПРИКЛАДЫВАТЬ тела, куки и IP — но не спасает,
# если телефон попал ВНУТРЬ текста ошибки, в адрес запроса или в хлебную крошку. Пример:
# HTTPException(422, f"Номер {phone} занят") улетит в облако вместе с номером.
# Поэтому чистим на выходе — последний рубеж перед отправкой наружу (§8 CLAUDE.md).
#
# Чистим осознанно грубо: лучше затереть лишнее в тексте ошибки, чем отправить чужой телефон.
# Разработчику для отладки нужен вид сбоя и стек, а не персональные данные из него.
_SCRUB = (
    # телефон в любом написании — та же логика, что в antifraud: разделителем считаем
    # только пробел/дефис/точку/скобки, буква цепочку рвёт
    (re.compile(r"(?<!\d)(?:\+?7|8)[ \-.()]{0,3}(?:\d[ \-.()]{0,3}){9}\d(?!\d)"), "<телефон>"),
    # координаты в query-строке: /rides?lat=54.05&lng=58.31 — это местоположение человека
    (re.compile(r"\b(lat|lng|lon|latitude|longitude)=-?\d+\.\d+", re.IGNORECASE), r"\1=<коорд>"),
    # JWT: три base64-куска через точку. Токен = доступ к аккаунту
    (re.compile(r"\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"), "<токен>"),
    # Live-ссылка близкому (/t/{token}): токен в ПУТИ — это и есть ключ к живым координатам
    # поездки. Access-лог его маскирует, алерт админу тоже (middleware.py), а Sentry
    # прикладывает полный адрес запроса САМ — и `send_default_pii=False` тут не помогает,
    # потому что URL не считается персональными данными (аудит 2026-08-08). Маскируем здесь.
    (re.compile(r"/t/[A-Za-z0-9_-]{16,}"), "/t/***"),
    # почта: попадает и в логи, и в аналитику («написал на марат@example.com»). Личное —
    # маскируем везде одинаково (аудит 2026-08-12, волна 45). `\w`, а не только латиница:
    # имя до собаки бывает кириллицей, и именно так его пишет человек в свободном поле.
    (re.compile(r"[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}", re.UNICODE), "<почта>"),
    # секреты в параметрах
    (re.compile(r"\b(token|access_token|refresh_token|code|otp|password|secret|api_key|key)=[^&\s\"']+",
                re.IGNORECASE), r"\1=<скрыто>"),
)
_SCRUB_MAX_DEPTH = 12


def scrub_text(s: str) -> str:
    for rx, repl in _SCRUB:
        s = rx.sub(repl, s)
    return s


def scrub_exc(exc: BaseException) -> str:
    """Текст исключения со стеком, очищенный от персональных данных — для ЛОКАЛЬНОГО лога.

    Скруб писался для Sentry, но ровно та же беда есть в логе своего сервера: SQLAlchemy
    вкладывает в текст `IntegrityError` параметры запроса, а там телефон человека:

        (IntegrityError) UNIQUE constraint failed: user.phone
        [parameters: ('+79991234567', 'Айгуль Хабибуллина', 'Баймак')]

    Логи читает не только разработчик: они лежат на диске, попадают в выгрузки и в чужие руки
    при разборе инцидента. §8 CLAUDE.md и 152-ФЗ говорят прямо — чувствительное не логируем
    (аудит 2026-08-08, волна 14). Стек оставляем целиком: для отладки нужен именно он.
    """
    import traceback
    text = "".join(traceback.format_exception(type(exc), exc, exc.__traceback__))
    return scrub_text(text)


def _scrub(value, depth: int = 0):
    """Рекурсивно вычистить строки в событии. Глубина ограничена: событие Sentry —
    чужая структура, зацикливаться на ней наблюдаемость права не имеет."""
    if depth > _SCRUB_MAX_DEPTH:
        return value
    if isinstance(value, str):
        return scrub_text(value)
    if isinstance(value, dict):
        return {k: _scrub(v, depth + 1) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        out = [_scrub(v, depth + 1) for v in value]
        return type(value)(out) if isinstance(value, tuple) else out
    return value


def before_send(event, hint):   # noqa: ARG001 — hint нужен по контракту Sentry
    """Последний рубеж перед отправкой. Любая ошибка чистки → событие НЕ отправляем:
    лучше потерять отчёт о сбое, чем отправить наружу чужой телефон."""
    try:
        return _scrub(event)
    except Exception:  # noqa: BLE001
        return None


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
            # Второй рубеж: чистим то, что просочилось в текст ошибки или в адрес.
            before_send=before_send,
            before_send_transaction=before_send,   # у трейсов в имени лежит URL
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
