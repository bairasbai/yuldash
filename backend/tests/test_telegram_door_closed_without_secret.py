"""Telegram — единственная дверь входа на проде, и без секрета она заперта, а не распахнута.

Приёмка волны 1 (16.10.2026). Вебхук /telegram/webhook проверял секрет, только если он задан.
`validate_production` требует секрет лишь при заданном TELEGRAM_BOT_TOKEN — значит, прод без
токена и без секрета стартовал, а вебхук принимал апдейты от кого угодно. Апдейт «поделился
номером» несёт телефон и id отправителя — подделать оба ничего не стоит, и это прямой путь
привязать чужой номер к своей сессии входа.

Правило: на проде вебхук без настроенного секрета отвечает 403 на всё. В разработке и тестах
(секрет пуст намеренно) — работает как раньше. Предупреждения запуска называют то, без чего
SOS и вход не работают.
"""
from __future__ import annotations

from app.config import Settings, settings

FAKE_CONTACT = {"message": {"from": {"id": 4242}, "chat": {"id": 4242},
                            "contact": {"user_id": 4242, "phone_number": "+79170000999"}}}


def test_прод_без_секрета_вебхук_заперт(client, monkeypatch):
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "telegram_webhook_secret", "")

    r = client.post("/telegram/webhook", json=FAKE_CONTACT)

    assert r.status_code == 403, f"прод без секрета принял поддельный апдейт с чужим номером: {r.status_code}"


def test_прод_с_секретом_пускает_только_telegram(client, monkeypatch):
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "telegram_webhook_secret", "s3cret-webhook-value")

    чужой = client.post("/telegram/webhook", json=FAKE_CONTACT)
    свой = client.post("/telegram/webhook", json={"update_id": 1},
                       headers={"X-Telegram-Bot-Api-Secret-Token": "s3cret-webhook-value"})

    assert чужой.status_code == 403, чужой.text
    assert свой.status_code == 200, свой.text


def test_разработка_без_секрета_работает_как_раньше(client, monkeypatch):
    monkeypatch.setattr(settings, "env", "dev")
    monkeypatch.setattr(settings, "telegram_webhook_secret", "")

    r = client.post("/telegram/webhook", json={"update_id": 1})

    assert r.status_code == 200, r.text


def test_предупреждения_запуска_называют_sos_и_sms():
    s = Settings(env="prod", telegram_bot_token="", admin_telegram_chat_id="", sms_provider="mock")
    текст = " ".join(s.launch_warnings())

    assert "ADMIN_TELEGRAM_CHAT_ID" in текст and "SOS" in текст, f"не предупредили, что SOS некуда слать: {текст}"
    assert "SMS_PROVIDER" in текст, f"не предупредили, что SMS близким не уходят: {текст}"


def test_настроенный_прод_без_лишних_предупреждений_про_sos():
    s = Settings(env="prod", telegram_bot_token="123:abc", telegram_webhook_secret="x" * 20,
                 admin_telegram_chat_id="1001", sms_provider="smsru", sms_ru_api_id="key")
    текст = " ".join(s.launch_warnings())

    assert "ADMIN_TELEGRAM_CHAT_ID" not in текст and "SMS_PROVIDER" not in текст, текст
