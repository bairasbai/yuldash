"""Незаданная настройка не должна открывать админские команды бота (аудит 2026-08-07).

Инлайн-кнопки в Telegram («одобрить водителя», «одобрить объявление», «подтвердить платёж»)
проверяют, что нажал именно владелец: `from.id` из апдейта сравнивается с
`ADMIN_TELEGRAM_CHAT_ID`. Сравнение шло в лоб, обе стороны через `str(...)`. Если настройка
не задана, справа получается пустая строка — и апдейт с `from.id = ""` с ней СОВПАДАЕТ.
То есть подделанный апдейт проходил как владелец.

Дыра была второго эшелона: сам вебхук закрыт секретным заголовком, а прод без него не
стартует (`config.validate_production` требует `TELEGRAM_WEBHOOK_SECRET` при заданном токене
бота). Но настройка, которой нет, должна закрывать дверь, а не открывать её: гейт на старте
проверяет один сценарий, а этот код обязан быть верным сам по себе.
"""
from app.config import settings
from app.routers.auth import _is_owner_telegram


def test_empty_setting_lets_nobody_in(monkeypatch):
    """Главное: пустая настройка = никто не владелец, что бы ни прислали."""
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "")
    for frm in ({"id": ""}, {"id": None}, {}, {"id": 0}, {"id": "0"}, {"id": "  "}):
        assert _is_owner_telegram(frm) is False, f"пустая настройка пустила {frm}"


def test_owner_is_recognised(monkeypatch):
    """Обратная сторона: настоящий владелец узнаётся, кнопки работают."""
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "123456789")
    assert _is_owner_telegram({"id": 123456789}) is True     # Telegram шлёт число
    assert _is_owner_telegram({"id": "123456789"}) is True   # а иногда строку


def test_stranger_is_rejected(monkeypatch):
    monkeypatch.setattr(settings, "admin_telegram_chat_id", "123456789")
    for frm in ({"id": 987654321}, {"id": ""}, {}, {"id": "12345678"}):
        assert _is_owner_telegram(frm) is False
