# -*- coding: utf-8 -*-
"""ФГИС «Такси»: проверка разрешения перевозчика по госномеру (580-ФЗ).

ЗАЧЕМ. Закон обязывает службу заказа передавать заказы ТОЛЬКО тем, кто есть в реестре
легковых такси, и проверять это. До 29.08.2026 водитель вписывал номер разрешения руками,
а мы верили на слово. Цена доверия: солидарная ответственность за вред пассажиру, если
заказ ушёл нелегалу, штраф до 50 000 ₽ для ИП и — при систематических нарушениях —
исключение нас самих из реестра служб заказа.

ЧТО БЕРЁМ ИЗ РЕЕСТРА. Статус разрешения, срок его действия и характеристики машины
(марка, модель, год, цвет). Этого достаточно и для допуска, и для того, чтобы не спрашивать
у человека то, что государство уже знает: регистрация сводится к вводу госномера.

ЧЕГО НЕ БЕРЁМ СОЗНАТЕЛЬНО. VIN, ИНН и ОГРН перевозчика реестр отдаёт, но мы их не храним.
Искать в ФГИС можно по госномеру, который у нас и так есть, а любой лишний идентификатор
в базе — это то, что придётся защищать и что можно потерять.

ВСЁ ПАДАЕТ МЯГКО. Нет токена, таймаут, 429, кривой JSON — функция возвращает None, и это
означает «реестр не ответил», а не «разрешения нет». Решение о допуске принимает вызывающий
код (см. `taxi.fgis_permit_ok`), и молчание чужого сервера не должно снимать водителя
с линии: это наша проблема, а не его.
"""
from __future__ import annotations

import logging
from datetime import date
from typing import Optional

import httpx

from .config import settings
from .timeutil import local_date, utcnow

log = logging.getLogger("yuldash")

# Клиент для тестов: подменяется, чтобы не ходить в сеть (тот же приём, что в weather_warn).
_client_override = None


def enabled() -> bool:
    """Проверка включена и работоспособна.

    Пустой токен = выключено. Иначе включённый флаг без ключа означал бы, что каждый
    водитель получает «реестр не ответил» — и на проде это выглядело бы как массовый сбой.
    """
    return bool(settings.fgis_check_enabled and settings.fgis_api_token.strip())


def normalize_plate(raw: Optional[str]) -> str:
    """Госномер к виду, который понимает реестр: без пробелов и дефисов, в верхнем регистре.

    Люди пишут «а123бв 102», «А123БВ-102», «а 123 бв 102» — для реестра это один номер.
    Латиницу в кириллицу НЕ переводим: подмена символов молча превратила бы один номер
    в другой, а ошибиться тут — значит пустить на линию чужую машину.
    """
    if not raw:
        return ""
    return "".join(ch for ch in str(raw).upper() if ch.isalnum())


def _parse_date(raw) -> Optional[date]:
    """Дата из ответа реестра. Формат не гарантирован — кривую дату считаем отсутствующей."""
    if not raw:
        return None
    text = str(raw).strip()[:10]
    try:
        return date.fromisoformat(text)          # 2029-03-12
    except ValueError:
        pass
    части = text.split(".")                       # 12.03.2029
    if len(части) == 3 and all(p.isdigit() for p in части):
        д, м, г = части
        try:
            return date(int(г), int(м), int(д))
        except ValueError:
            return None
    return None


def _truthy(raw) -> bool:
    """Реестр отвечает по-разному: true, "true", 1, "Действует". Приводим к одному."""
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)):
        return bool(raw)
    return str(raw).strip().lower() in {"true", "1", "yes", "да", "действует", "актуально"}


def lookup(plate: str, *, client=None) -> Optional[dict]:
    """Спросить реестр про машину по госномеру.

    Возврат:
        dict — реестр ответил. `permit_ok` говорит, есть ли ДЕЙСТВУЮЩЕЕ разрешение.
        None — спросить не удалось (выключено, нет токена, сеть, кривой ответ).

    Разница между `None` и `{"permit_ok": False}` принципиальная: первое означает «мы не
    знаем», второе — «мы знаем, что разрешения нет». Смешать их значило бы снимать людей
    с линии из-за собственного таймаута.
    """
    number = normalize_plate(plate)
    if not number or not enabled():
        return None
    params = {"type": "taxi", "number": number, "token": settings.fgis_api_token.strip()}
    try:
        getter = (client or _client_override or httpx).get
        response = getter(settings.fgis_api_url, params=params,
                          timeout=settings.fgis_http_timeout_sec)
        if response.status_code != 200:
            return None
        data = response.json()
    except Exception as e:  # noqa: BLE001 — сбой реестра не должен ронять регистрацию
        log.warning("[FGIS] запрос по номеру не удался: %s", type(e).__name__)
        return None
    if not isinstance(data, dict):
        return None
    # Обёртки отдают полезное то в корне, то во вложенном объекте — принимаем оба вида.
    body = data.get("result") if isinstance(data.get("result"), dict) else data
    if not isinstance(body, dict):
        return None
    статус = body.get("status") or body.get("isActual") or body.get("permit_status")
    until = _parse_date(body.get("permit_until") or body.get("dateEnd") or body.get("date_end"))
    ok = _truthy(статус)
    # Срок в прошлом перевешивает любой «действует» в ответе: дата конкретнее слова.
    # Дата — по МЕСТНОМУ календарю (Уфа), а не по серверному: разрешение, истекающее
    # «сегодня», не должно гаснуть на пять часов раньше срока из-за часового пояса сервера.
    if until is not None and until < local_date(utcnow()):
        ok = False
    return {
        "permit_ok": ok,
        "permit_until": until,
        "permit_number": str(body.get("permit_number") or body.get("number") or "")[:60],
        # Характеристики машины — чтобы не спрашивать у человека то, что реестр уже знает.
        "car_model": str(body.get("model") or body.get("car_model") or "")[:80],
        "car_brand": str(body.get("brand") or body.get("mark") or "")[:80],
        "car_year": int(body["year"]) if str(body.get("year") or "").isdigit() else None,
        "car_color": str(body.get("color") or "")[:40],
    }
