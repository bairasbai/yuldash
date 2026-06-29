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
from .timeutil import utcnow

# Расширение → mimeType Yandex OCR. webp и прочее OCR не поддерживает → пропускаем OCR.
_OCR_MIME = {"jpg": "JPEG", "jpeg": "JPEG", "png": "PNG", "pdf": "PDF"}

# Водительское удостоверение РФ: 10 цифр, обычно «12 34 567890» или «1234 567890».
_LICENSE_RE = re.compile(r"(?<!\d)(\d{2}\s?\d{2}\s?\d{6})(?!\d)")
_DATE_RE = re.compile(r"\b(\d{2})\.(\d{2})\.(\d{4})\b")


def _doc_path(url: str) -> str | None:
    """URL защищённого документа → путь файла в DOC_DIR (только имя — анти path-traversal)."""
    if not url:
        return None
    name = os.path.basename(url.split("?")[0].rstrip("/"))
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


def check_driver_docs(license_url: str, car_photo_url: str) -> dict:
    """Главная: вернуть {'result','score','data'}.

    result: pass | needs_human | reject | error. Исключений не бросает.
    reasons в data — машинные коды (клиент рисует на двух языках через appText).
    """
    data: dict = {"reasons": [], "ocr_used": False}
    path = _doc_path(license_url)
    if not path:
        return {"result": "error", "score": 0.0, "data": {"reasons": ["doc_not_found"]}}

    ext = os.path.splitext(path)[1].lstrip(".").lower()
    try:
        with open(path, "rb") as f:
            img = f.read()
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
    today = utcnow().date()
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

    if has_number and future_ok and score >= settings.driver_autocheck_min_score:
        data["reasons"].append("ok")
        return {"result": "pass", "score": score, "data": data}

    if not has_number:
        data["reasons"].append("no_license_number")
    if not expiry:
        data["reasons"].append("no_expiry_date")
    return {"result": "needs_human", "score": score, "data": data}
