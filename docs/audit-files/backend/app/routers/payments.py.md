# Карточка: `backend/app/routers/payments.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5

## Назначение

Платежи за СОБСТВЕННЫЕ услуги платформы самозанятого (НЕ проезд между людьми — тот идёт мимо
приложения): поднятие объявления (Boost), платное размещение рекламы, донат, добровольная
поддержка «Поддержать Юлдаш», закрытие недельной комиссии такси/курьера картой. Единая точка
применения оплаты (`_activate_payment`) и единая точка сверки с провайдером
(`_sync_provider_status`) используются также и `routers/wallet.py` для оплаты поездок/броней —
поэтому этот файл фактически ядро всей денежной активации в проекте, не только Boost/рекламы.
Прод сегодня: `PAYMENTS_PROVIDER=mock` (0 ₽, все деньги фиктивны) с планом перехода на
`sbp_manual` (перевод по номеру, подтверждает админ в Telegram) и затем `yookassa` (код готов,
ждёт публикации/ключей).

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_handle_unclaimed_payment` | 42–100 | Деньги пришли, а применить некуда (платёж уже закрыт иначе) → статус `refund_due`, тикет в поддержку на языке человека, пуш, Telegram админу | Идемпотентно (гейт по статусу у обеих дверей-вызывающих); ошибка создания тикета — best-effort, отметка `refund_due` не теряется | ок |
| `boost_plans` | 103–109 `GET /boost/plans` | Тарифы Boost с бэкенда (не хардкод клиента) | Источник — `payments.BOOST_PLANS` | ок |
| `_reuse_fresh_pending` | 112–129 | Тот же неоплаченный счёт вместо нового при двойном тапе (donate/support/boost) | Совпадение строгое: тот же человек+цель+СУММА, свежий (`BOOST_PENDING_REUSE_MIN`=30 мин) | ок |
| `_start_yookassa` | 132–151 | Создать платёж в ЮKassa с ключом, привязанным к НАШЕЙ строке | `payment.method="yookassa"` и commit — ДО похода наружу (повтор переживёт обрыв сети, попадёт в тот же Idempotence-Key); исключение → 503 человеку, строка/ключ сохранены для безопасного повтора | ок |
| `_mark_succeeded` | 154–164 | Пометить `succeeded` + `settled_at` (момент ПРИМЕНЕНИЯ, не нажатия «оплатить») | Единая точка — сверка (`ledger.reconcile`) больше не красная сама по себе на ночных оплатах | ок |
| `_activate_payment` | 167–351 | Применить оплаченный платёж (идемпотентно, ТОЛЬКО из `pending`, под row-lock). Денежные эффекты (ride/booking/courier_commission/taxi_debt) коммитятся ОДНОЙ транзакцией с финальным статусом; аддитивные (boost/ad/partner_sub/donate/support) — статус первым в памяти, затем эффект, один общий commit | Повторный вызов на не-pending — no-op; boost/ad/partner_sub добавляют СРОК К ОСТАТКУ (не обрезают) и не понижают уже оплаченный уровень; courier_commission/taxi_debt гасят ТОЛЬКО то, что вошло в причинный снимок суммы | ок |
| `_sync_provider_status` | 354–389 | Сверить локальный платёж с достоверным статусом ЮKassa под блокировкой строки перед terminal-переходом | Только `succeeded`/`canceled` меняют локальные деньги; `pending`/ошибка сети — не трогают; повторно закрытый локально, но «оплаченный» у провайдера → `_handle_unclaimed_payment` | ок |
| `_tell_about_payment` | 409–450 | Сказать человеку, чем кончился перевод (успех/отказ), с переходом в нужный экран | `ride`/`booking` молчат намеренно (видно по самой поездке, другой флоу — `routers/wallet.py`); вид без текста (`payment`) не отправляется — поймано сторожем `test_notifications_lead_somewhere` | ок |
| `_notify_new_payment` | 453–474 | Telegram админу о новой заявке СБП с кнопками confirm/reject | Best-effort (не роняет создание платежа) | ок |
| `boost_free` | 481–508 `POST /boost/free` | Поднять свою поездку бесплатно за реферальный бонус | Только живая (`active`) поездка; списание бонуса под row-lock на `User` (не задвоится гонкой); срок — к остатку, не обрезает | ок |
| `boost_create` | 516–584 `POST /boost/create` | Оплатить поднятие: sbp_manual → заявка админу; mock/yookassa → мгновенный succeeded или confirmation_url | Прод+mock → 503 (не выдаёт бесплатный буст); дедуп свежего pending по (user, поездка, тариф); другой тариф — осознанно новый счёт | ок |
| `donate_create` | 591–625 `POST /donate` | Донат на платформу (та же инфра, что Boost) | Границы 10–100000 ₽ валидирует сервер | ок |
| `support_donate` | 638–679 `POST /support/donate` | «Поддержать Юлдаш» — доход платформы, ledger не трогаем | Границы 10–5000 ₽ (целые копейки во входе — `amount_kop: int`) | ок |
| `payment_status` | 680–701 `GET /payments/{id}/status` | Статус СВОЕГО платежа; если pending+yookassa — сама перепроверяет и активирует | Чужой платёж → 404 (не 403 — не раскрываем существование) | ок |
| `_require_admin` | 705–707 | Общая проверка роли админа | 403 иначе | ок |
| `admin_pending_payments` | 710–739 `GET /admin/payments/pending` | Очередь СБП-переводов на подтверждение | Платежи у провайдера (provider_id≠'' ИЛИ method=='yookassa') НЕ попадают в очередь — их подтверждает только вебхук | ок |
| `admin_confirm_payment` | 742–764 `POST /admin/payments/{id}/confirm` | Подтвердить СБП-перевод → активировать | `succeeded` → идемпотентный повторный успех; не-`pending` (canceled/refund_due/…) → 409 «уже закрыт»; у провайдера → 409 «подтвердится автоматически»; после активации статус ОБЯЗАН стать `succeeded`, иначе 409 (честный ответ, не ложный успех) | ок |
| `admin_reject_payment` | 767–783 `POST /admin/payments/{id}/reject` | Отклонить (деньги не нашли) | Разрешено ТОЛЬКО из `pending`/`canceled` (идемпотентно для повторного reject); `succeeded` → 409, деньги нельзя «отменить» этой кнопкой; отказ уведомляет человека | ок |
| `admin_payments_summary` | 786–799 `GET /admin/payments/summary` | Сводка подтверждённых донатов/буста/поддержки | Агрегация в SQL (count/sum), не тянет строки в память | ок |
| `yookassa_webhook` | 802–825 `POST /payments/yookassa/webhook` | Уведомление провайдера — телу НЕ доверяем, перепроверяем по id | Нерелевантно при не-yookassa провайдере → no-op; битый JSON/нет id → no-op; НЕЗНАКОМЫЙ provider_id → no-op БЕЗ исходящего запроса (анти-амплификация/DoS); уже terminal (`succeeded`/`refund_due`) → no-op | ок |

## Связи

- `routers/wallet.py` импортирует `_activate_payment`, `_start_yookassa`, `_sync_provider_status` —
  это ОБЩАЯ точка применения денег для ride/booking/boost/ad/partner_sub/donate/support/
  courier_commission/taxi_debt.
- `payments.py` (клиент ЮKassa): `create_payment`/`fetch_payment`, тарифы `BOOST_PLANS`/`BOOST_WEIGHT`.
- `ledger.py`: `settle_instant_order`/`settle_booking` (из `_activate_payment`).
- `courier.py`/`debt.py`: снимки суммы комиссии/долга (`_courier_snapshot_ids`, `mark_all_paid`).
- `services.notify_admin_telegram`/`push_notification`, `models.Ad/Ride/Payment/User`.
- Android: `ApiClient.kt` методы оплаты/буста; `PayTripResultDto`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | `_activate_payment` применяет эффект ТОЛЬКО из `pending` (идемпотентность confirm/webhook/poll) | `backend/tests/test_manual_payment_terminal_confirmation.py::test_pending_manual_confirmation_and_duplicates_match_real_effect` | да — M16 |
| R2 | Отменённый (canceled) платёж нельзя задним числом подтвердить в ложный «успех» | `backend/tests/test_manual_payment_terminal_confirmation.py::test_rejected_manual_invoice_cannot_report_false_confirmation` | да — M17 |
| R3 | Оплаченный (succeeded) платёж нельзя отклонить кнопкой reject | `backend/tests/test_manual_payment_terminal_confirmation.py::test_pending_manual_confirmation_and_duplicates_match_real_effect` (проверка `after_paid_reject`) | да — M18 |
| R4 | Платёж у провайдера (provider_id ИЛИ method='yookassa') нельзя вручную подтвердить админом — фантом без денег | `backend/tests/test_manual_payment_terminal_confirmation.py::test_provider_identified_invoice_stays_out_of_manual_queue_and_confirmation` (оба параметра: provider_id/method) | да — M19 |
| R5 | Докупка Boost добавляет срок К ОСТАТКУ и не понижает уже оплаченный уровень | `backend/tests/test_paid_time_and_open_doors_have_limits.py::test_докупка_добавляет_время_а_не_обрезает`, `::test_докупка_не_понижает_уровень` | да — M20 |
| R6 | Поздние деньги (провайдер подтвердил, у нас уже закрыто иначе) не растворяются — `refund_due` + уведомления | `backend/tests/test_late_payment_is_not_lost.py::test_оплата_по_старой_ссылке_не_растворяется`, `::test_повторный_вебхук_не_плодит_обращения`, `::test_вторая_дверь_проверка_статуса_тоже_замечает_поздние_деньги` | да — M21 |
| R7 | Денежный эффект и финальный статус — ОДНА транзакция; сбой перед записью статуса откатывает и эффект | `backend/tests/test_manual_payment_atomicity.py::test_failed_status_sql_rolls_back_effect_and_retry_applies_once` | да — M22 |
| R8 | Вебхук не ходит наружу за НЕЗНАКОМЫМ provider_id (анти-амплификация/DoS); битый JSON — no-op | `backend/tests/test_payments_edges.py::test_yookassa_webhook_bad_json_and_fetch_failure_are_noop`, `backend/tests/walk/l1_2/test_l1_2_webhook_amplification.py::test_unknown_provider_id_never_triggers_outbound_fetch`, `::test_missing_object_id_never_triggers_outbound_fetch` | да — M23 |

## Найденные ошибки

Ошибок не найдено. Все восемь денежных правил уже защищены — восемь из них были закрыты
прошлыми аудитами (QA-B07-004, волна 26/84/125/145/151/164), причём с избыточной защитой в
отдельных местах (напр. `_activate_payment`'s внутренний гейт `status != "pending"` — забрало
даже на случаях, которые уже отсекаются вызывающим кодом выше). Один пробел покрытия закрыл
новым тестом (R8: вебхук по незнакомому id — раньше проверялся только путь с ИЗВЕСТНЫМ id).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M16 | `_activate_payment`: гейт инвертирован (`== "pending"` вместо `!=`) | test_pending_manual_confirmation_and_duplicates_match_real_effect | KILLED |
| M17 | `admin_confirm_payment`: ранний возврат расширен на `canceled` (ложный успех) | test_rejected_manual_invoice_cannot_report_false_confirmation | KILLED |
| M18 | `admin_reject_payment`: гейт пропускает `succeeded` на отклонение | test_pending_manual_confirmation_and_duplicates_match_real_effect | KILLED |
| M19 | `admin_confirm_payment`: условие провайдера `or` → `and` (пропускает «наполовину провайдерский» платёж) | test_provider_identified_invoice_stays_out_of_manual_queue_and_confirmation | KILLED |
| M20 | Boost: `основа + timedelta` → `сейчас + timedelta` (обрезает остаток) | test_докупка_добавляет_время_а_не_обрезает | KILLED |
| M21 | `_handle_unclaimed_payment`: `REFUND_DUE` → `"succeeded"` | test_оплата_по_старой_ссылке_не_растворяется | KILLED |
| M22 | `settle_instant_order(..., commit=False)` → `commit=True` (разрывает транзакцию) | test_failed_status_sql_rolls_back_effect_and_retry_applies_once | KILLED |
| M23 | Вебхук: гейт `not payment or ...` → `payment and ...` (ходит наружу за чужим id) | test_unknown_provider_id_never_triggers_outbound_fetch | KILLED |

## Остаток и ограничения

Реальная ЮKassa не вызывалась (мок/заглушки — как требует задание); все HTTP-подмены на уровне
`httpx`/`create_payment`/`fetch_payment`, бизнес-код выполняется по-настоящему. Нагрузочное
поведение (много одновременных вебхуков на РАЗНЫЕ платежи, пул соединений БД) не измерялось —
вне фокуса денежной корректности этого листа.
