# Карточка: `backend/app/payments.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5

## Назначение

Низкоуровневый клиент ЮKassa: создать платёж (`create_payment`), перепроверить его статус
(`fetch_payment`) и отправить выплату водителю (`create_payout`). Файл НЕ принимает решений
о деньгах (не трогает БД, не решает кому и сколько) — он только переводит наши данные в формат
ЮKassa и обратно. Решения (кому начислить, идемпотентность по нашей строке, что делать при
pending/succeeded/canceled) живут выше — в `routers/payments.py`, `routers/wallet.py`,
`ledger.py`. Без ключей провайдера (`PAYMENTS_PROVIDER=mock` или пустые `yookassa_shop_id`/
`yookassa_secret_key`) обе платёжные функции — безопасная заглушка: мгновенный фиктивный успех,
без единого похода в сеть. Это и есть весь прод сегодня: монетизация выключена мастер-флагами
(`payments_provider="mock"`), реальные деньги нигде не двигаются.

Тарифы Boost (`BOOST_PLANS`, `BOOST_WEIGHT`) тоже тут — источник истины для цены поднятия,
чтобы не хардкодить её на клиенте (CLAUDE.md).

## Функции и разбор

| Функция | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_rub` | 35–37 | Копейки → строка «20.00» для ЮKassa | Целочисленное деление/остаток; при отрицательных копейках даст мусор, но вызывающий код (выше) не пускает отрицательные суммы сюда | ок |
| `_receipt` | 40–56 | Собирает чек для самозанятого (54-ФЗ) из суммы/описания/телефона | Без цифр в телефоне — `None` (платёж уйдёт без авто-чека, не упадёт); описание обрезается до 128 символов; `vat_code=1` (без НДС) | ок |
| `create_payment` | 59–98 | Создать платёж. Mock (нет ключей/провайдер≠yookassa) → мгновенный `succeeded` без сети. Yookassa → POST с redirect-confirmation и опциональным чеком | `idempotence_key` привязан к НАШЕЙ строке `Payment.id` вызывающим кодом — повтор запроса после обрыва сети не создаёт второй платёж у провайдера; пустой ключ → случайный (fallback для вызовов без своей строки); `raise_for_status()` пробрасывает HTTP-ошибку наружу (ловит вызывающий код, напр. `_start_yookassa`) | ок |
| `fetch_payment` | 101–119 | Перепроверить статус платежа по id — вызывается из вебхука/поллинга, НЕ доверяющих телу запроса | Mock → всегда `succeeded`; `confirmation_url` в ответе — чтобы дедуп pending-счёта мог повторно открыть ту же ссылку | ок |
| `payout_keys_present` | 126–128 | Есть ли реальные ключи выплат (оба: agent_id и secret_key) | И то, и другое обязательно — выплаты полностью готовности, Александр их ещё не завёл | ок |
| `create_payout` | 131–155 | Отправить выплату водителю по `payout_token` (НЕ по номеру карты — полного PAN у нас нет). Mock → фиктивный `succeeded` без денег | `Idempotence-Key` передаётся в ЮKassa — повтор с тем же ключом не задваивает выплату; тело содержит только токен провайдера, никогда сам номер карты | ок |

## Связи

- `routers/payments.py`: `_start_yookassa` зовёт `create_payment`; `_sync_provider_status`/вебхук
  зовут `fetch_payment`; `BOOST_PLANS`/`BOOST_WEIGHT` читаются в `boost_create`/`_activate_payment`.
- `routers/wallet.py`: `_pay_cashless`/`_start_yookassa` (импортирован оттуда же) используют
  `create_payment` тем же путём, что и boost/donate.
- `ledger.py`: `request_payout` зовёт `create_payout` ВНЕ row-lock (после короткого резерва
  списания), получает mock/настоящий ответ и либо коммитит выплату, либо откатывает резерв.
- Настройки: `config.settings.payments_provider`, `yookassa_shop_id/secret_key`,
  `yookassa_payout_agent_id/secret_key`, `payment_return_url`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Копейки → рубли без потерь точности (`_rub`) | `backend/tests/test_money_invariants.py::test_копейки_превращаются_в_рубли_без_потерь` | да — M1 |
| R2 | Без ключей/провайдера — мгновенный `succeeded` БЕЗ сети (dev/боевой фолбэк) | `backend/tests/test_payment_provider.py::test_mock_payment_provider_is_immediate_success` | да — M2 |
| R3 | ЮKassa-платёж уходит с реальным чеком (телефон→цифры) и своим Idempotence-Key | `backend/tests/test_payment_provider.py::test_yookassa_create_payment_sends_receipt_and_returns_confirmation` | да — M3 |
| R4 | Без телефона чек НЕ отправляется (не роняем платёж из-за недостающего контакта) | `backend/tests/test_payment_provider.py::test_yookassa_create_payment_without_phone_omits_receipt` | да — M4 |
| R5 | Реальная выплата несёт сумму в рублях, `payout_token` и Idempotence-Key (пробел обхода — раньше `create_payout` не проверялся напрямую ни одним тестом, все подменяли саму функцию) | `backend/tests/walk/l1_2/test_l1_2_payments_payout_client.py::test_real_payout_sends_amount_token_and_idempotence_key`, `::test_mock_payout_without_keys_is_immediate_success_no_network`, `::test_payout_without_idempotence_key_still_gets_one` | да — M5 |

## Найденные ошибки

Ошибок в этом файле не найдено. Найденная в этом листе ошибка (гонка двух запросов на оплату
одного быстрого заказа) живёт в `routers/wallet.py` — см. его карточку.

Проверено отдельно и признано нормальным (не ошибка): `_rub(amount_kop)` при отрицательных
копейках даёт некорректную строку (Python round-towards-negative-infinity на `//`), но ни один
вызывающий код во всём дереве не передаёт сюда отрицательную сумму — она отсекается раньше
(в `_pay_cashless`/`_booking_amount_kop`/валидации donate/support). Остаётся в «Остаток».

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `_rub`: `%100:02d` → `%100:01d` (теряется цифра копеек) | test_копейки_превращаются_в_рубли_без_потерь | KILLED |
| M2 | mock-платёж отдаёт `status="pending"` вместо `"succeeded"` | test_mock_payment_provider_is_immediate_success | KILLED |
| M3 | `_receipt`: `isdigit()` → `isalpha()` (из телефона не достаются цифры) | test_yookassa_create_payment_sends_receipt_and_returns_confirmation | KILLED |
| M4 | `_receipt`: `if not digits: return None` → подставляет фиктивные цифры вместо отказа | test_yookassa_create_payment_without_phone_omits_receipt | KILLED |
| M5 | `create_payout`: тело теряет `payout_token` (отправляется пустым) | test_l1_2_payments_payout_client.py::test_real_payout_sends_amount_token_and_idempotence_key | KILLED |

## Остаток и ограничения

Реальная сеть ЮKassa не вызывалась нигде (только заглушки/моки — как и требует задание).
`_rub` с отрицательным `amount_kop` не защищена сама по себе (отрицательные копейки дадут
«-1.-1»-подобный мусор), но недостижима на всех известных путях вызова — риск низкий,
отдельного теста на этот угловой случай не заводил (не подтверждённая ошибка, а подозрение).
Выплаты (`create_payout`) по-прежнему в режиме готовности — Александр ещё не завёл боевые
ключи, реальный проход через настоящую ЮKassa Payout API не проверялся и не может быть
проверен без реальных денег.
