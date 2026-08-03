# -*- coding: utf-8 -*-
"""Гварды запуска (разбор №2, 2026-08-03).

Два вида проверок и почему они разные:

* `validate_production()` — сервис РАБОТАЕТ НЕПРАВИЛЬНО. Падаем на старте.
  Такси без Redis выглядит живым (экраны открываются, заказ создаётся), но presence лежит
  только в Redis GEO — без него каждый заказ мгновенно «рядом никого», и это неотличимо
  от «в городе нет свободных машин». Неделю искали бы причину не там.
* `launch_warnings()` — сервис работает, но ВЫПУСК не состоится (стор отклонит, обновление
  некому раскатить). Ронять прод из-за этого нельзя, поэтому печатаем предупреждение.
"""
import pytest

from app.config import settings


def _prod_ok(monkeypatch):
    """Минимально валидный прод: дальше тест ломает ровно одну вещь и смотрит, поймали ли."""
    monkeypatch.setattr(settings, "env", "prod")
    monkeypatch.setattr(settings, "jwt_secret", "x" * 40)
    monkeypatch.setattr(settings, "cors_origins", "https://yulbash.ru")
    monkeypatch.setattr(settings, "seed_demo", False)
    monkeypatch.setattr(settings, "database_url", "postgresql://u:p@localhost/yuldash")
    monkeypatch.setattr(settings, "media_base_url", "https://yulbash.ru/media")
    monkeypatch.setattr(settings, "sms_provider", "mock")
    monkeypatch.setattr(settings, "telegram_bot_token", "")
    monkeypatch.setattr(settings, "payments_provider", "mock")
    monkeypatch.setattr(settings, "payouts_enabled", False)
    monkeypatch.setattr(settings, "driver_autoapprove_enabled", False)
    monkeypatch.setattr(settings, "storage_backend", "")
    monkeypatch.setattr(settings, "taxi_enabled", False)
    monkeypatch.setattr(settings, "redis_url", "")
    monkeypatch.setattr(settings, "min_app_version_code", 0)
    monkeypatch.setattr(settings, "app_store_url", "")
    settings.validate_production()   # базовая конфигурация обязана проходить


def test_taxi_without_redis_is_rejected(monkeypatch):
    """Включить такси без Redis = запустить мёртвое такси. Ловим на старте, а не в первом заказе."""
    _prod_ok(monkeypatch)
    monkeypatch.setattr(settings, "taxi_enabled", True)
    with pytest.raises(RuntimeError) as e:
        settings.validate_production()
    assert "REDIS_URL" in str(e.value)
    # С Redis — проходит: гвард не мешает нормальной конфигурации.
    monkeypatch.setattr(settings, "redis_url", "redis://127.0.0.1:6379/0")
    settings.validate_production()


def test_courier_without_redis_is_allowed(monkeypatch):
    """Доставка не зависит от presence: у неё свой список заказов в БД. Гвард не должен её трогать."""
    _prod_ok(monkeypatch)
    monkeypatch.setattr(settings, "courier_enabled", True)
    settings.validate_production()


def test_force_update_without_store_url_is_rejected(monkeypatch):
    """Экран «Обнови приложение» без ссылки = человек заперт: пользоваться нельзя, обновиться некуда."""
    _prod_ok(monkeypatch)
    monkeypatch.setattr(settings, "min_app_version_code", 5)
    with pytest.raises(RuntimeError) as e:
        settings.validate_production()
    assert "APP_STORE_URL" in str(e.value)
    monkeypatch.setattr(settings, "app_store_url", "https://play.google.com/store/apps/details?id=com.yuldash.app")
    settings.validate_production()


def test_store_review_account_is_a_warning_not_a_crash(monkeypatch):
    """Без тестового аккаунта стор отклонит сборку — но сервис исправен, ронять его нельзя."""
    _prod_ok(monkeypatch)
    monkeypatch.setattr(settings, "review_phone", "")
    monkeypatch.setattr(settings, "review_code", "")
    settings.validate_production()                     # не падает — это про выпуск, не про работу
    warns = " ".join(settings.launch_warnings())
    assert "REVIEW_PHONE" in warns
    monkeypatch.setattr(settings, "review_phone", "+79990000000")
    monkeypatch.setattr(settings, "review_code", "424242")
    assert "REVIEW_PHONE" not in " ".join(settings.launch_warnings())


def test_force_update_off_is_warned(monkeypatch):
    """Выключенное принудительное обновление — не ошибка, но о нём должны знать до релиза."""
    _prod_ok(monkeypatch)
    assert any("MIN_APP_VERSION_CODE" in w for w in settings.launch_warnings())


# ------------------------------ оферта vs денежные флаги ------------------------------
def test_money_flag_without_docs_update_warns():
    """Оферта обещает «комиссия не берётся». Включили деньги — предупреждаем, что документ врёт."""
    from app.config import Settings
    s = Settings(env="prod", taxi_enabled=False, redis_url="", parcel_fee_enabled=True)
    warns = " ".join(s.launch_warnings())
    assert "PARCEL_FEE_ENABLED" in warns
    assert "legal-content" in warns


def test_no_money_flags_no_offer_warning():
    """Флаги выключены — оферта правдива, лишним предупреждением не шумим."""
    from app.config import Settings
    s = Settings(env="prod", taxi_enabled=False, redis_url="",
                 parcel_fee_enabled=False, payouts_enabled=False, tips_money_enabled=False)
    assert not any("legal-content" in w for w in s.launch_warnings())
