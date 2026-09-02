"""Платежи Юлдаша — ЮKassa v3 (самозанятый: монетизация СВОИХ услуг — Boost/реклама).

Юр.рамка: самозанятый принимает оплату за собственные услуги платформы (поднятие
объявления, платное размещение рекламы), а НЕ деньги пассажиров за проезд. Оплата
проезда между людьми идёт мимо приложения.

Безопасность вебхука: телу запроса от ЮKassa НЕ доверяем (его может подделать любой) —
по `id` из вебхука перепроверяем статус через API ЮKassa (`fetch_payment`). Это
рекомендованный ЮKassa паттерн, не требует разбора подписей/IP-allowlist.

Без ключей (`PAYMENTS_PROVIDER=mock`) — dev-режим: платёж сразу «succeeded» (без денег).
В проде mock запрещён на уровне эндпоинта (boost вернёт 503), реальные деньги — только yookassa.
"""
import uuid

from .config import settings

# Тарифы Boost — на бэкенде (CLAUDE.md: цены Boost → не хардкод на клиенте).
# tier → (название, цена в копейках, длительность буста в часах).
BOOST_PLANS: dict[str, tuple[str, int, int]] = {
    "quick":  ("Быстрое поднятие", 2000, 2),    # 20 ₽ / 2 ч
    "day":    ("День вверху",      5000, 24),    # 50 ₽ / 24 ч
    "urgent": ("Срочная поездка",  7000, 6),     # 70 ₽ / 6 ч
}

# Насколько «заметный» каждый уровень поднятия — по цене. Нужен, чтобы докупка дешёвого
# не понижала уже оплаченный дорогой уровень (волна 151): человек, взявший «Срочную поездку»
# и добавивший два часа «Быстрым», должен остаться срочным, а не наоборот.
BOOST_WEIGHT: dict[str, int] = {tier: kop for tier, (_t, kop, _h) in BOOST_PLANS.items()}

YOOKASSA_API = "https://api.yookassa.ru/v3/payments"
YOOKASSA_PAYOUT_API = "https://api.yookassa.ru/v3/payouts"


def _rub(amount_kop: int) -> str:
    """Копейки → строка «20.00» (формат ЮKassa)."""
    return f"{amount_kop // 100}.{amount_kop % 100:02d}"


def _receipt(amount_kop: int, description: str, customer_phone: str) -> dict | None:
    """Чек для самозанятого (54-ФЗ): ЮKassa передаёт его в «Мой налог» автоматически.
    Без контакта покупателя чек не сформировать → возвращаем None (платёж без авто-чека)."""
    digits = "".join(c for c in (customer_phone or "") if c.isdigit())
    if not digits:
        return None
    return {
        "customer": {"phone": digits},          # ЮKassa отправит чек на этот номер
        "items": [{
            "description": description[:128],
            "quantity": "1.00",
            "amount": {"value": _rub(amount_kop), "currency": "RUB"},
            "vat_code": 1,                       # 1 = без НДС (самозанятый/НПД)
            "payment_subject": "service",        # услуга
            "payment_mode": "full_payment",      # полная предоплата
        }],
    }


def create_payment(amount_kop: int, description: str, metadata: dict, customer_phone: str = "",
                   idempotence_key: str = "") -> dict:
    """Создать платёж. Возврат: {provider_id, confirmation_url, status, mock}.

    mock-режим (нет провайдера/ключей): возвращает фиктивный платёж со status='succeeded'
    (в проде эндпоинт это не вызовет — там 503). yookassa: реальный POST с redirect-URL
    и чеком для самозанятого (авто-фискализация через «Мой налог»).

    `idempotence_key` — защита ЮKassa от повторной отправки ОДНОГО И ТОГО ЖЕ запроса.
    Раньше сюда шёл свежий uuid на каждый вызов, то есть защита была выключена: оборвалась
    сеть на ответе, повторили запрос — у ЮKassa два платежа на один наш счёт. Ключ должен
    быть привязан к нашей строке Payment, тогда повтор попадает в тот же платёж.
    Пусто → случайный (для вызовов, где своей строки нет)."""
    if settings.payments_provider != "yookassa" or not (settings.yookassa_shop_id and settings.yookassa_secret_key):
        return {"provider_id": f"mock_{uuid.uuid4().hex}", "confirmation_url": "", "status": "succeeded", "mock": True}
    import httpx
    body = {
        "amount": {"value": _rub(amount_kop), "currency": "RUB"},
        "capture": True,
        "confirmation": {"type": "redirect", "return_url": settings.payment_return_url},
        "description": description[:128],
        "metadata": metadata,
    }
    receipt = _receipt(amount_kop, description, customer_phone)
    if receipt:
        body["receipt"] = receipt
    r = httpx.post(
        YOOKASSA_API, json=body,
        auth=(settings.yookassa_shop_id, settings.yookassa_secret_key),
        headers={"Idempotence-Key": idempotence_key or uuid.uuid4().hex},
        timeout=15,
    )
    r.raise_for_status()
    data = r.json()
    return {
        "provider_id": data.get("id", ""),
        "confirmation_url": (data.get("confirmation") or {}).get("confirmation_url", ""),
        "status": data.get("status", "pending"),
        "mock": False,
    }


def fetch_payment(provider_id: str) -> dict:
    """Перепроверить платёж по id в ЮKassa (для вебхука — не доверяем телу).
    Возврат: {status, metadata, confirmation_url}. mock — всегда succeeded."""
    if settings.payments_provider != "yookassa" or not (settings.yookassa_shop_id and settings.yookassa_secret_key):
        return {"status": "succeeded", "metadata": {}, "confirmation_url": ""}
    import httpx
    r = httpx.get(
        f"{YOOKASSA_API}/{provider_id}",
        auth=(settings.yookassa_shop_id, settings.yookassa_secret_key),
        timeout=15,
    )
    r.raise_for_status()
    data = r.json()
    return {
        "status": data.get("status", "pending"),
        "metadata": data.get("metadata") or {},
        # Если платёж ещё ждёт оплаты — URL страницы ЮKassa, чтобы повторно открыть (дедуп pending).
        "confirmation_url": (data.get("confirmation") or {}).get("confirmation_url", ""),
    }


# ============================ Выплаты водителям (Модель Б, Фаза 3 v2) ============================
# ГОТОВНОСТЬ: обёртка над ЮKassa Payout API рядом с create/fetch платежей. Реальные выплаты
# идут только когда Александр оформит ИП + бизнес-ЮKassa + ключи выплат (payouts_ready=True).
# Без ключей выплат — mock: фиктивная успешная выплата (для dev/готовности; денег не двигает).
def payout_keys_present() -> bool:
    """Есть ли реальные ключи выплат ЮKassa? Нет → работаем в mock (dev/готовность)."""
    return bool(settings.yookassa_payout_agent_id and settings.yookassa_payout_secret_key)


def create_payout(amount_kop: int, payout_token: str, description: str, metadata: dict,
                  idempotence_key: str = "") -> dict:
    """Отправить выплату водителю на привязанную карту. Возврат: {payout_id, status, mock}.

    Полного номера карты у нас НЕТ — платим по `payout_token` (токен карты у провайдера,
    получен через виджет привязки). mock-режим (нет ключей выплат): status='succeeded' без
    реальных денег. Idempotence-Key передаём в ЮKassa → повторная отправка не задваивает выплату."""
    if not payout_keys_present():
        return {"payout_id": f"mock_payout_{uuid.uuid4().hex}", "status": "succeeded", "mock": True}
    import httpx
    body = {
        "amount": {"value": _rub(amount_kop), "currency": "RUB"},
        "payout_token": payout_token,          # токен карты (НЕ PAN) — полный номер у ЮKassa, не у нас
        "description": description[:128],
        "metadata": metadata,
    }
    r = httpx.post(
        YOOKASSA_PAYOUT_API, json=body,
        auth=(settings.yookassa_payout_agent_id, settings.yookassa_payout_secret_key),
        headers={"Idempotence-Key": idempotence_key or uuid.uuid4().hex},
        timeout=30,
    )
    r.raise_for_status()
    data = r.json()
    return {"payout_id": data.get("id", ""), "status": data.get("status", "pending"), "mock": False}
