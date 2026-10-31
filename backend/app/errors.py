"""Двуязычные ошибки API (RU/BA). Клиент показывает detail по языку интерфейса.

Обычные HTTPException(status, "русский текст") оставляем как есть — клиент для них даёт
понятную ОБЩУЮ ошибку по HTTP-коду на языке пользователя. Через herr(...) переводим точечно
самые пользовательские сообщения (онбординг таксиста, заказ, бронь, правка поездки), чтобы
башкир видел не общий, а конкретный текст.
"""
from fastapi import HTTPException


def herr(status: int, ru: str, ba: str, *, code: str | None = None, **extra) -> HTTPException:
    """Двуязычная ошибка; необязательный стабильный code предназначен для логики клиентов."""
    detail = {**extra, "ru": ru, "ba": ba}
    if code is not None:
        detail["code"] = code
    return HTTPException(status_code=status, detail=detail)
