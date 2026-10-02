# Карточка: `backend/app/routers/payments.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5; независимое ревью: Opus 5.5 (2026-10-02, VERDICT: REQUEST_CHANGES — F1/M23/M9/покрытие правил, все закрыты этим коммитом)

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

## Что изменилось в этом листе (по независимому ревью)

Первый проход карточки ставил всем восьми правилам «ок» и писал «Ошибок не найдено» — это было
НЕВЕРНО: независимая проверка (Opus 5.5) нашла подтверждённую денежную ошибку (F1,
`_activate_payment` игнорировал результат `settle_*`), две мелкие дыры доступности (F5, F7) и
две тривиальные/неточные поломки (M23 «убивалась» исключением, а не поведением; M9 описана
неверно). Всё перечисленное ниже исправлено и честно отражено в разделе «Найденные ошибки».

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_handle_unclaimed_payment` | 42–100 | Деньги пришли, а применить некуда (платёж уже закрыт иначе) → статус `refund_due`, тикет в поддержку на языке человека, пуш, Telegram админу | Идемпотентно; теперь вызывается НЕ ТОЛЬКО из `_sync_provider_status`, но и из `_activate_payment`, когда `settle_instant_order`/`settle_booking` отвечают не `"settled"` (F1) | ок |
| `boost_plans` | 103–109 `GET /boost/plans` | Тарифы Boost с бэкенда (не хардкод клиента) | Источник — `payments.BOOST_PLANS` | ок |
| `_reuse_fresh_pending` | 112–129 | Тот же неоплаченный счёт вместо нового при двойном тапе (donate/support, НЕ boost — у него свой похожий запрос в `boost_create`) | Совпадение строгое: тот же человек+цель+СУММА, свежий (`BOOST_PENDING_REUSE_MIN`=30 мин) | ок |
| `_start_yookassa` | 132–151 | Создать платёж в ЮKassa с ключом, привязанным к НАШЕЙ строке | `payment.method="yookassa"` и commit — ДО похода наружу; `idempotence_key=f"yuldash-payment-{payment.id}"` — ЯДРО защиты от двойного списания при ретрае; исключение → 503, строка/ключ сохранены | ок |
| `_mark_succeeded` | 154–164 | Пометить `succeeded` + `settled_at` (момент ПРИМЕНЕНИЯ, не нажатия «оплатить») | Единая точка — сверка (`ledger.reconcile`) больше не красная сама по себе на ночных оплатах | ок |
| `_activate_payment` | 167–363 | Применить оплаченный платёж (идемпотентно, ТОЛЬКО из `pending`, под row-lock). Денежные эффекты (ride/booking) теперь ПРОВЕРЯЮТ результат `settle_instant_order`/`settle_booking`: не `"settled"` → `_handle_unclaimed_payment`, а не слепой `succeeded` (F1, исправлено); courier_commission/taxi_debt — коммитятся одной транзакцией со статусом | исправлено (было ❌ — см. «Найденные ошибки», F1) |
| `_sync_provider_status` | 366–421 | Сверить локальный платёж с достоверным статусом ЮKassa под блокировкой строки перед terminal-переходом | Только `succeeded`/`canceled` меняют локальные деньги; `pending`/ошибка сети — не трогают; повторно закрытый локально, но «оплаченный» у провайдера → `_handle_unclaimed_payment` | ок |
| `_tell_about_payment` | 421–462 | Сказать человеку, чем кончился перевод (успех/отказ), с переходом в нужный экран | `ride`/`booking` молчат намеренно (другой флоу — `routers/wallet.py`); вид без текста (`payment`) не отправляется | ок |
| `_notify_new_payment` | 465–486 | Telegram админу о новой заявке СБП с кнопками confirm/reject | Best-effort (не роняет создание платежа) | ок |
| `boost_free` | 493–520 `POST /boost/free` | Поднять свою поездку бесплатно за реферальный бонус | Только живая (`active`) поездка; списание бонуса под row-lock на `User`; срок — к остатку, не обрезает | ок |
| `boost_create` | 528–601 `POST /boost/create` | Оплатить поднятие: sbp_manual → заявка админу; mock/yookassa → мгновенный succeeded или confirmation_url | Прод+mock → 503; дедуп свежего pending по (user, поездка, тариф); перепроверка уже заведённого у провайдера счёта теперь в `try/except` (F5, было ❌ — сетевая ошибка роняла весь запрос в 500) | исправлено (было ❌) |
| `donate_create` | 609–643 `POST /donate` | Донат на платформу (та же инфра, что Boost) | Границы 10–100000 ₽ валидирует сервер | ок |
| `support_donate` | 656–697 `POST /support/donate` | «Поддержать Юлдаш» — доход платформы, ledger не трогаем | Границы 10–5000 ₽ (целые копейки во входе) | ок |
| `payment_status` | 698–719 `GET /payments/{id}/status` | Статус СВОЕГО платежа; если pending+yookassa — сама перепроверяет и активирует | Чужой платёж → 404 (не 403 — не раскрываем существование) | ок |
| `_require_admin` | 723–725 | Общая проверка роли админа | 403 иначе | ок |
| `admin_pending_payments` | 728–764 `GET /admin/payments/pending` | Очередь СБП-переводов на подтверждение | Платежи у провайдера (provider_id≠'' ИЛИ method=='yookassa') НЕ попадают; исторические ручные строки за бронь/заказ (до правки method-при-создании в `wallet.py`) по-прежнему видны намеренно (см. карточку `wallet.py.md`, F6) | ок |
| `admin_confirm_payment` | 767–789 `POST /admin/payments/{id}/confirm` | Подтвердить СБП-перевод → активировать | `succeeded` → идемпотентный повторный успех; не-`pending` → 409; у провайдера → 409; после активации статус ОБЯЗАН стать `succeeded`, иначе 409 | ок |
| `admin_reject_payment` | 792–808 `POST /admin/payments/{id}/reject` | Отклонить (деньги не нашли) | Разрешено ТОЛЬКО из `pending`/`canceled`; `succeeded` → 409 | ок |
| `admin_payments_summary` | 811–824 `GET /admin/payments/summary` | Сводка подтверждённых донатов/буста/поддержки | Агрегация в SQL, не тянет строки в память | ок |
| `yookassa_webhook` | 827–854 `POST /payments/yookassa/webhook` | Уведомление провайдера — телу НЕ доверяем, перепроверяем по id | Нерелевантно при не-yookassa → no-op; битый JSON → no-op; **валидный JSON неожиданной формы** ([], число, `object` не словарь, числовой id) теперь ТОЖЕ no-op (F7, было ❌ — падало 500); незнакомый id → no-op БЕЗ исходящего запроса (анти-DoS); `async def`, но тело синхронное — см. «Остаток», F2 (подозрение, не подтверждено тестом) | исправлено (было ❌ — F7) |

## Связи

- `routers/wallet.py` импортирует `_activate_payment`, `_start_yookassa`, `_sync_provider_status` —
  ОБЩАЯ точка применения денег для ride/booking/boost/ad/partner_sub/donate/support/
  courier_commission/taxi_debt. F1-исправление в `_activate_payment` защищает ОБА файла разом.
- `payments.py` (клиент ЮKassa): `create_payment`/`fetch_payment`, тарифы `BOOST_PLANS`/`BOOST_WEIGHT`.
- `ledger.py`: `settle_instant_order`/`settle_booking` (из `_activate_payment`) — их возврат
  теперь ЧИТАЕТСЯ, а не игнорируется.
- `courier.py`/`debt.py`: снимки суммы комиссии/долга.
- `services.notify_admin_telegram`/`push_notification`, `models.Ad/Ride/Payment/User`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | `_activate_payment` применяет эффект ТОЛЬКО из `pending` | `backend/tests/test_manual_payment_terminal_confirmation.py::test_pending_manual_confirmation_and_duplicates_match_real_effect` | да — M16 |
| R2 | Отменённый платёж нельзя задним числом подтвердить в ложный «успех» | `backend/tests/test_manual_payment_terminal_confirmation.py::test_rejected_manual_invoice_cannot_report_false_confirmation` | да — M17 |
| R3 | Оплаченный (succeeded) платёж нельзя отклонить кнопкой reject | `backend/tests/test_manual_payment_terminal_confirmation.py::test_pending_manual_confirmation_and_duplicates_match_real_effect` | да — M18 |
| R4 | Платёж у провайдера нельзя вручную подтвердить админом | `backend/tests/test_manual_payment_terminal_confirmation.py::test_provider_identified_invoice_stays_out_of_manual_queue_and_confirmation` | да — M19 |
| R5 | Докупка Boost добавляет срок К ОСТАТКУ, не понижает уровень | `backend/tests/test_paid_time_and_open_doors_have_limits.py::test_докупка_добавляет_время_а_не_обрезает`, `::test_докупка_не_понижает_уровень` | да — M20 |
| R6 | Поздние деньги не растворяются — `refund_due` + уведомления | `backend/tests/test_late_payment_is_not_lost.py::test_оплата_по_старой_ссылке_не_растворяется`, `::test_повторный_вебхук_не_плодит_обращения`, `::test_вторая_дверь_проверка_статуса_тоже_замечает_поздние_деньги` | да — M21 |
| R7 | Денежный эффект и финальный статус — ОДНА транзакция | `backend/tests/test_manual_payment_atomicity.py::test_failed_status_sql_rolls_back_effect_and_retry_applies_once` | да — M22 |
| R8 | Вебхук не ходит наружу за НЕЗНАКОМЫМ provider_id (поведенческая проверка — см. ниже) | `backend/tests/walk/l1_2/test_l1_2_webhook_amplification.py::test_unknown_provider_id_never_triggers_outbound_fetch`, `::test_missing_object_id_never_triggers_outbound_fetch` | да — M23 (переделана, см. «Найденные ошибки») |
| R9 | `_activate_payment` проверяет результат `settle_instant_order`/`settle_booking` — не `"settled"` → `_handle_unclaimed_payment`, а не слепой succeeded | `backend/tests/walk/l1_2/test_l1_2_second_payment_refund_due.py::test_second_succeeded_payment_on_an_already_paid_order_becomes_refund_due`, `::test_second_succeeded_payment_on_an_already_paid_booking_becomes_refund_due` | да — M32, M33 |
| R11 | `boost_create` переживает сетевую ошибку перепроверки уже заведённого счёта | `backend/tests/walk/l1_2/test_l1_2_boost_reuse_fetch_failure.py::test_boost_create_retry_survives_fetch_payment_network_error` | да — M36 |
| R13 | Вебхук с валидным JSON неожиданной формы — no-op, не 500 | `backend/tests/walk/l1_2/test_l1_2_webhook_malformed_shapes.py::test_malformed_but_valid_json_shapes_are_a_noop` (7 форм) | да — M39 |
| R14 | `_start_yookassa`: Idempotence-Key привязан к `Payment.id` — ядро защиты от двойного списания при ретрае (пробел покрытия — тест уже существовал) | `backend/tests/test_double_tap_does_not_pay_twice.py::test_yookassa_gets_a_key_tied_to_the_payment_row` | да — M41 |
| R15 | Вебхук — no-op при не-yookassa провайдере (пробел покрытия) | `backend/tests/test_flows.py::test_yookassa_webhook_only_known_payment` | да — M42 |
| R16 | `payment_status`: чужой платёж → 404 (пробел покрытия) | `backend/tests/test_boost_yookassa.py::test_boost_status_poll_is_owner_only` | да — M43 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| F1 (R9, деньги, P1) | Независимая проверка (Opus 5.5): `_activate_payment` вызывал `ledger.settle_instant_order`/`settle_booking` и ИГНОРИРОВАЛ ответ. Ответ `"already"` означает: заказ/бронь уже оплачены ДРУГИМ платежом (или налом), пока этот шёл к провайдеру. Несмотря на это, платёж ВСЁ РАВНО помечался `succeeded` — деньги пассажира списывались у провайдера взаправду, а след оставался только как молчаливое расхождение в `ledger.reconcile`: ни тикета, ни пуша, ни сигнала админу. Это ровно тот случай, ради которого написан `_handle_unclaimed_payment` — но он был подключён только к пути «вебхук против локально отменённого платежа». | `test_l1_2_second_payment_refund_due.py`: два pending-счёта на один заказ/бронь, оба активируются подряд | `if result != "settled": _handle_unclaimed_payment(session, payment); return` в ОБЕИХ ветках (ride/booking) | До — второй платёж тоже `succeeded`, 0 тикетов; после — `refund_due`, 1 тикет, ledger не задвоен |
| F5 (доступность, P3) | `boost_create` при повторном тапе на уже заведённый у провайдера счёт звал `fetch_payment` БЕЗ `try/except` — в отличие от `_sync_provider_status` (кошелёк/долг/курьер). Сетевая ошибка/таймаут заваливала ВЕСЬ запрос в 500 вместо вежливого повтора через тот же Idempotence-Key. | `test_l1_2_boost_reuse_fetch_failure.py`: первый `/boost/create` заводит счёт, второй — с `fetch_payment`, бросающим `TimeoutError` | Обёрнуто в `try/except Exception: existing = None` — при неудаче просто повторяет `_start_yookassa` с тем же ключом (как и остальной код) | До — 500; после — `< 500` (нормальный повтор) |
| F7 (доступность, P3) | Вебхук с СИНТАКСИЧЕСКИ валидным, но НЕОЖИДАННЫМ JSON (`[]`, число, `{"object": "строка"}`, числовой `id`) падал 500 вместо честного no-op — карточка обещала «телу не доверяем», но это не распространялось на ФОРМУ тела, только на значения. | `test_l1_2_webhook_malformed_shapes.py`: 7 вариантов формы | `object_`/`provider_id` достаются через `isinstance`-проверки, не голый `.get()` | До — `AttributeError: 'int' object has no attribute 'get'` (500); после — `{"ok": true}` |
| M23 (процесс, не денежная ошибка) | Независимая проверка: мутация M23 «убивалась» исключением (`_sync_provider_status(session, None)` падает на `None.status`) РАНЬШЕ, чем код доходил до `fetch_payment` — то есть тест ловил поломку компилятора/рантайма, а не ПОВЕДЕНИЕ (реальный внешний вызов). | — | Мутация переписана: вставляет РЕАЛЬНЫЙ вызов `fetch_payment(provider_id)` ДО поиска своей строки — теперь тест ловит именно факт внешнего обращения (`calls == []` нарушается), а не побочное исключение | Старая M23 «убивалась» всегда (даже без изменения кода вокруг); новая M23 проверяет ровно то поведение, которое описывает R8 |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M16 | `_activate_payment`: гейт инвертирован (`== "pending"` вместо `!=`) | test_pending_manual_confirmation_and_duplicates_match_real_effect | KILLED |
| M17 | `admin_confirm_payment`: ранний возврат расширен на `canceled` (ложный успех) | test_rejected_manual_invoice_cannot_report_false_confirmation | KILLED |
| M18 | `admin_reject_payment`: гейт пропускает `succeeded` на отклонение | test_pending_manual_confirmation_and_duplicates_match_real_effect | KILLED |
| M19 | `admin_confirm_payment`: условие провайдера `or` → `and` | test_provider_identified_invoice_stays_out_of_manual_queue_and_confirmation | KILLED |
| M20 | Boost: `основа + timedelta` → `сейчас + timedelta` (обрезает остаток) | test_докупка_добавляет_время_а_не_обрезает | KILLED |
| M21 | `_handle_unclaimed_payment`: `REFUND_DUE` → `"succeeded"` | test_оплата_по_старой_ссылке_не_растворяется | KILLED |
| M22 | `settle_instant_order(..., commit=False)` → `commit=True` | test_failed_status_sql_rolls_back_effect_and_retry_applies_once | KILLED |
| M23 | Вебхук: реальный вызов `fetch_payment` ВСТАВЛЕН до поиска своей строки (поведенческая версия) | test_unknown_provider_id_never_triggers_outbound_fetch | KILLED |
| M32 | `_activate_payment` (ride): проверка результата settle отключена (`if False`) | test_second_succeeded_payment_on_an_already_paid_order_becomes_refund_due | KILLED |
| M33 | `_activate_payment` (booking): то же | test_second_succeeded_payment_on_an_already_paid_booking_becomes_refund_due | KILLED |
| M36 | `boost_create`: `try/except` вокруг `fetch_payment` снят | test_boost_create_retry_survives_fetch_payment_network_error | KILLED |
| M39 | Вебхук: `isinstance`-проверки формы тела сняты (возврат к голому `.get()`) | test_malformed_but_valid_json_shapes_are_a_noop | KILLED |
| M41 | `_start_yookassa`: ключ теряет `-payment-` (не привязан к Payment.id по формату) | test_yookassa_gets_a_key_tied_to_the_payment_row | KILLED |
| M42 | Вебхук: гейт `payments_provider != "yookassa"` снят | test_yookassa_webhook_only_known_payment | KILLED |
| M43 | `payment_status`: проверка владельца снята | test_boost_status_poll_is_owner_only | KILLED |

## Остаток и ограничения

Реальная ЮKassa не вызывалась (мок/заглушки). F2 (ГИПОТЕЗА, НЕ исправлено в этом листе):
`yookassa_webhook` объявлен `async def`, но тело — синхронные DB-запросы и синхронный HTTP
(`fetch_payment` внутри `_sync_provider_status`, до 15 с таймаут) и синхронные
Telegram/push-уведомления в `_handle_unclaimed_payment`. Под несколькими ASGI-воркерами
(`backend/Dockerfile`) медленный ответ ЮKassa теоретически может задержать ОБРАБОТКУ других
запросов, ждущих тот же event loop. Я искал способ подтвердить это тестом (per правило листа:
правлю только подтверждённое, иначе — подозрение с рассуждением) и не нашёл практичного:
`TestClient` выполняет ASGI-приложение без настоящего конкурентного event loop с несколькими
воркерами, так что «зависание ДРУГИХ запросов» этим инструментом не воспроизвести честно. Если
ведущий считает риск достаточным, предлагаемое решение — обернуть `_sync_provider_status` в
`await run_in_threadpool(...)` внутри `yookassa_webhook`, сохранив `async def` (и, значит,
нынешнее поведение `except Exception` на `await request.json()` для битого JSON).
