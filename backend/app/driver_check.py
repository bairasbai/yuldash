"""Авто-проверка документов водителя (Tier-1): локальные гейты + Yandex Vision OCR.

Цель — снять с админа рутину: сервер сам читает права, помечает заявку
(pass / needs_human / reject) и кладёт распознанные поля в autocheck_data.
Человек остаётся финальной кнопкой (см. settings.driver_autoapprove_enabled).

Дёшево: OCR Yandex Vision ~0,13 ₽/фото. Локальные проверки (есть ли текст,
не просрочены ли права) — бесплатны. Без ключа OCR всё уходит к человеку
(водителя за это не наказываем — статус остаётся pending).
"""
import base64
import os
import re
from datetime import date

from .config import settings
from .services import DOC_DIR
from .storage import get_storage
from .timeutil import local_date, utcnow

# Расширение → mimeType Yandex OCR. webp и прочее OCR не поддерживает → пропускаем OCR.
_OCR_MIME = {"jpg": "JPEG", "jpeg": "JPEG", "png": "PNG", "pdf": "PDF"}

# Водительское удостоверение РФ: 10 цифр, обычно «12 34 567890» или «1234 567890».
_LICENSE_RE = re.compile(r"(?<!\d)(\d{2}\s?\d{2}\s?\d{6})(?!\d)")
_DATE_RE = re.compile(r"\b(\d{2})\.(\d{2})\.(\d{4})\b")


def _doc_name(url: str) -> str | None:
    """URL защищённого документа → безопасное имя файла (только basename — анти path-traversal)."""
    if not url:
        return None
    name = os.path.basename(url.split("?")[0].rstrip("/"))
    return name or None


def _doc_path(url: str) -> str | None:
    """URL защищённого документа → путь файла в DOC_DIR (локальный диск), если файл есть."""
    name = _doc_name(url)
    if not name:
        return None
    path = os.path.join(DOC_DIR, name)
    return path if os.path.isfile(path) else None


def _extract_full_text(payload: dict) -> str | None:
    """Достать распознанный текст из ответа OCR (защитный разбор разных форм ответа)."""
    if not isinstance(payload, dict):
        return None
    ann = (payload.get("result") or {}).get("textAnnotation") or {}
    full = ann.get("fullText")
    if isinstance(full, str) and full.strip():
        return full
    # запасной путь: собрать из блоков/строк/слов
    parts: list[str] = []
    for block in ann.get("blocks") or []:
        for line in block.get("lines") or []:
            txt = line.get("text")
            if txt:
                parts.append(txt)
            else:
                parts.extend(w.get("text", "") for w in (line.get("words") or []))
    text = " ".join(p for p in parts if p).strip()
    return text or None


def _ocr_text(image_bytes: bytes, ext: str) -> str | None:
    """Распознать текст через Yandex Vision OCR. None — если OCR недоступен/ошибка/формат."""
    if not settings.yandex_vision_key:
        return None
    mime = _OCR_MIME.get(ext.lower())
    if not mime:
        return None  # формат не поддержан OCR (напр. webp) → к человеку
    headers = {
        "Authorization": f"Api-Key {settings.yandex_vision_key}",
        "x-data-logging-enabled": "false",
    }
    # x-folder-id нужен для ключей сервис-аккаунта; ключи AI Studio привязаны к
    # каталогу и работают без него (проверено вживую 2026-06-29). Шлём только если задан.
    if settings.yandex_vision_folder_id:
        headers["x-folder-id"] = settings.yandex_vision_folder_id
    try:
        import httpx
        r = httpx.post(
            "https://ai.api.cloud.yandex.net/ocr/v1/recognizeText",
            headers=headers,
            json={
                "mimeType": mime,
                "languageCodes": ["ru", "en"],
                "model": "page",
                "content": base64.b64encode(image_bytes).decode("ascii"),
            },
            timeout=30,
        )
        if r.status_code != 200:
            return None
        return _extract_full_text(r.json())
    except Exception:
        return None  # сеть/разбор упали → деградируем к человеку, не роняем заявку


def _parse_license(text: str) -> dict:
    """Вытащить из распознанного текста номер прав и срок действия."""
    flat = text.replace("\n", " ")
    num_m = _LICENSE_RE.search(flat)
    license_number = re.sub(r"\s", "", num_m.group(1)) if num_m else None
    dates: list[date] = []
    for d, mo, y in _DATE_RE.findall(flat):
        try:
            dates.append(date(int(y), int(mo), int(d)))
        except ValueError:
            continue
    # Срок действия прав = самая поздняя дата (выдача + 10 лет даёт максимум).
    expiry = max(dates) if dates else None
    return {
        "license_number": license_number,
        "dates_found": [d.isoformat() for d in sorted(dates)],
        "expiry": expiry.isoformat() if expiry else None,
        "_expiry_obj": expiry,
    }


def check_driver_docs(license_url: str, car_photo_url: str,
                      birth_date: "date | None" = None) -> dict:
    """Главная: вернуть {'result','score','data'}.

    result: pass | needs_human | reject | error. Исключений не бросает.
    reasons в data — машинные коды (клиент рисует на двух языках через appText).

    `birth_date` — дата рождения ИЗ ЗАЯВКИ. Без неё проверка отвечала на вопрос «похоже ли это
    на действующие права», но не на «его ли это права»: скачанное из интернета фото чужих прав
    набирало максимальный балл и получало `pass` (проверено пробой, аудит 2026-08-14, волна 67).
    Пока авто-одобрение выключено, это стоило бы админу лишнего внимания; с включённым флагом —
    выдало бы допуск к перевозке людей человеку с чужим документом.

    Дату рождения выбрали намеренно, а не ФИО: она есть в заявке как отдельное поле, печатается
    на правах и уже вытаскивается из текста. ФИО в заявке таксиста нет вовсе, а имя профиля
    человек пишет как хочет («Марат», «Марат Такси»).

    Не сошлось — отдаём ЧЕЛОВЕКУ, а не отказываем: OCR путает цифры и плохо читает мятые права.
    Отказ по подозрению обиднее лишней минуты модератора."""
    data: dict = {"reasons": [], "ocr_used": False}
    name = _doc_name(license_url)
    if not name:
        return {"result": "error", "score": 0.0, "data": {"reasons": ["doc_not_found"]}}

    ext = os.path.splitext(name)[1].lstrip(".").lower()
    # Байты документа берём из хранилища (диск или S3) — единая точка чтения.
    try:
        img = get_storage().load(f"docs/{name}")
    except FileNotFoundError:
        return {"result": "error", "score": 0.0, "data": {"reasons": ["doc_not_found"]}}
    except OSError:
        return {"result": "error", "score": 0.0, "data": {"reasons": ["doc_read_error"]}}

    text = _ocr_text(img, ext)
    if text is None:
        # OCR недоступен (нет ключа / формат / сеть) → решает человек, водителя не наказываем.
        data["reasons"].append("ocr_unavailable")
        return {"result": "needs_human", "score": 0.0, "data": data}

    data["ocr_used"] = True
    data["text_len"] = len(text)
    parsed = _parse_license(text)
    expiry = parsed.pop("_expiry_obj")
    data.update(parsed)

    has_text = len(text.strip()) >= 20
    has_number = bool(parsed["license_number"])
    has_dates = bool(parsed["dates_found"])
    today = local_date(utcnow())
    expired = bool(expiry and expiry < today)
    future_ok = bool(expiry and expiry >= today)

    score = 0.0
    if has_text:
        score += 0.30
    if has_number:
        score += 0.35
    if future_ok:
        score += 0.35
    score = round(score, 2)

    # Авто-отказ ТОЛЬКО на явный мусор: текста почти нет или совсем не похоже на права.
    if not has_text or (not has_number and not has_dates):
        data["reasons"].append("not_a_license")
        return {"result": "reject", "score": score, "data": data}

    # Просрочка → к человеку (вдруг OCR ошибся в дате — не режем валидные права автоматом).
    if expired:
        data["reasons"].append("license_expired")
        return {"result": "needs_human", "score": score, "data": data}

    # Чьи это права. Дату рождения из заявки ищем среди распознанных дат: её там не оказалось —
    # либо документ чужой, либо OCR не разобрал. Оба случая решает человек (волна 67).
    if birth_date is not None:
        data["birth_date_checked"] = True
        if birth_date.isoformat() not in parsed["dates_found"]:
            data["reasons"].append("birth_date_mismatch")
            return {"result": "needs_human", "score": score, "data": data}
        data["birth_date_match"] = True

    if has_number and future_ok and score >= settings.driver_autocheck_min_score:
        data["reasons"].append("ok")
        return {"result": "pass", "score": score, "data": data}

    if not has_number:
        data["reasons"].append("no_license_number")
    if not expiry:
        data["reasons"].append("no_expiry_date")
    return {"result": "needs_human", "score": score, "data": data}
