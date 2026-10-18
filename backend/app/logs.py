"""Единый логгер Юлдаша. Раньше по коду был размазан print() — не фильтруется по уровню,
не уходит в stderr, не дружит с uvicorn/сборщиками логов. Здесь один именованный логгер
`yuldash` с уровнями (info/warning/error). Уровень — из LOG_LEVEL (env), по умолчанию INFO.

Использование:
    from .logs import log
    log.info("[SMS] отправлено")
    log.warning("[GEO] PostGIS недоступен: %s", e)
    log.exception("[ERR] необработанное")   # внутри except — со стектрейсом

CLI-скрипт cleanup.py осознанно печатает отчёт через print() — это консольный вывод, не логи."""
import logging
import os
import re


class _HttpxUrlPrivacyFilter(logging.Filter):
    """Mask URL secrets before HTTPX records reach any handler.

    HTTPX пишет на INFO полный адрес каждого исходящего запроса. Сейчас корневой логгер под
    uvicorn стоит на WARNING и эти строки не печатаются, но достаточно включить INFO целиком
    (basicConfig, другой запускатор) — и в журнал уходят ключ бота Telegram в пути, а в строке
    запроса: `api_id` sms.ru, номер, код входа и текст SOS с координатами, `apikey` и точки
    геокодера Яндекса (18.10.2026). Поэтому из строки запроса оставляем только имена параметров.
    """
    _telegram = re.compile(r"(https?://api\.telegram\.org/bot)[^/\s?#\"']+", re.IGNORECASE)
    _query = re.compile(r"(https?://[^\s?#\"']+)\?([^\s#\"']*)", re.IGNORECASE)

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        safe = self._telegram.sub(r"\1<скрыто>", message)
        safe = self._query.sub(lambda m: f"{m.group(1)}?{_query_names_only(m.group(2))}", safe)
        if safe != message:
            # Change this record only; HTTPX Request/payload and logger levels stay intact.
            # Clear args too, so structured handlers cannot retain the original URL.
            record.msg = safe
            record.args = ()
        return True


def _query_names_only(query: str) -> str:
    """«a=1&b=2&flag» → «a=<скрыто>&b=<скрыто>&flag» (как `observability._query_names_only`)."""
    return "&".join(part.split("=", 1)[0] + ("=<скрыто>" if "=" in part else "")
                    for part in query.split("&") if part)


_httpx_log = logging.getLogger("httpx")
_filter_name = "yuldash.httpx-url-privacy"
if not any(getattr(f, "name", None) == _filter_name for f in _httpx_log.filters):
    _httpx_log.addFilter(_HttpxUrlPrivacyFilter(_filter_name))

log = logging.getLogger("yuldash")

# Настраиваем один раз. Под uvicorn корневой логгер уже сконфигурирован — тогда просто
# наследуем его хендлеры (propagate=True). Если хендлеров нигде нет (тесты, cron, голый
# python) — вешаем свой в stderr, чтобы сообщения не пропали.
if not log.handlers and not logging.getLogger().handlers:
    _h = logging.StreamHandler()  # stderr по умолчанию
    _h.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    log.addHandler(_h)

log.setLevel(getattr(logging, os.environ.get("LOG_LEVEL", "INFO").upper(), logging.INFO))


def admin_action(admin_id: int | str, what: str, **fields) -> None:
    """След админского действия: КТО и ЧТО сделал.

    Зачем (аудит 2026-08-06). Из 41 пишущего админского действия след оставляли два.
    Общий лог запросов пишет только «POST /admin/debts/7/forgive -> 200» — то есть ЧТО,
    но не КТО. Пока админ один, это незаметно; но модерация водителей ручная, помощник
    рано или поздно появится — и вся прошлая история окажется без авторства задним числом.
    Особенно это про деньги (прощение долга, подтверждение платежа), про ограничение
    человека (бан устройства, пауза) и про действия ЗА человека.

    Что НЕ пишем (§8): телефоны, точные координаты, тексты переписки. Только идентификаторы
    и суммы — по ним всё поднимается из базы, а сам лог остаётся безопасным.

    Почему строкой в лог, а не таблицей. Полноценный журнал с экраном в админке — отдельная
    задача с решениями «сколько хранить» и «кому показывать» (см. docs/tasks.md). Строка в
    логе закрывает главное: вопрос «кто это сделал» перестаёт быть без ответа.
    """
    tail = " ".join(f"{k}={v}" for k, v in fields.items() if v is not None)
    log.info(f"[ADMIN] admin={admin_id} {what}{(' ' + tail) if tail else ''}")
