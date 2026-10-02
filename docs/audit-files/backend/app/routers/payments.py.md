# Карточка: `backend/app/routers/payments.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5; независимое ревью раунд 1: Opus 5.5 (2026-10-02, VERDICT: REQUEST_CHANGES — F1/M23/M9/покрытие правил, закрыты); независимое ревью раунд 2: Opus 5.5 (2026-10-02, VERDICT: REQUEST_CHANGES — F2, закрыт; deadlock-путь через новый лок заказа в wallet.py, см. «Остаток»)

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
НЕВЕРНО. Раунд 1 независимой проверки (Opus 5.5) нашёл подтверждённую денежную ошибку (F1,
`_activate_payment` игнорировал результат `settle_*`), две мелкие дыры доступности (F5, F7) и
две тривиальные/неточные поломки (M23 «убивалась» исключением, а не поведением; M9 описана
неверно) — закрыто. Раунд 2 указал на доступность (F2: синхронный HTTP/БД вебхука в event loop;
я сначала не нашёл способа подтвердить это тестом и честно записал подозрением — ревью показало
конкретный deadlock-путь через новый лок заказа из того же раунда и более простой способ
подтвердить тестом, не гоняясь за настоящей конкурентностью) — тоже закрыто. Всё перечисленное
ниже исправлено и честно отражено в разделе «Найденные ошибки». (Отдельно, на стыке с листом
1.1 — известный, сознательно НЕ закрытый здесь остаток по `taxi_debt`, см. раздел «Остаток».)

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_handle_unclaimed_payment` | 42–100 | Деньги пришли, а применить некуда (платёж уже закрыт иначе) → статус `refund_due`, тикет в поддержку на языке человека, пуш, Telegram админу | Идемпотентно; теперь вызывается НЕ ТОЛЬКО из `_sync_provider_status`, но и из `_activate_payment`, когда `settle_instant_order`/`settle_booking` отвечают не `"settled"` (F1) | ок |
| `boost_plans` | 103–109 `GET /boost/plans` | Тарифы Boost с бэкенда (не хардкод клиента) | Источник — `payments.BOOST_PLANS` | ок |
| `_reuse_fresh_pending` | 112–129 | Тот же неоплаченный счёт вместо нового при двойном тапе (donate/support, НЕ boost — у него свой похожий запрос в `boost_create`) | Совпадение строгое: тот же человек+цель+СУММА, свежий (`BOOST_PENDING_REUSE_MIN`=30 мин) | ок |
| `_start_yookassa` | 132–151 | Создать платёж в ЮKassa с ключом, привязанным к НАШЕЙ строке | `payment.method="yookassa"` и commit — ДО похода наружу; `idempotence_key=f"yuldash-payment-{payment.id}"` — ЯДРО защиты от двойного списания при ретрае; исключение → 503, строка/ключ сохранены | ок |
| `_mark_succeeded` | 154–164 | Пометить `succeeded` + `settled_at` (момент ПРИМЕНЕНИЯ, не нажатия «оплатить») | Единая точка — сверка (`ledger.reconcile`) больше не красная сама по себе на ночных оплатах | ок |
| `_activate_payment` | 168–376 | Применить оплаченный платёж (идемпотентно, ТОЛЬКО из `pending`, под row-lock). Денежные эффекты (ride/booking) теперь ПРОВЕРЯЮТ результат `settle_instant_order`/`settle_booking`: не `"settled"` → `_handle_unclaimed_payment`, а не слепой `succeeded` (F1, исправлено); courier_commission — по причинному ID-снимку (`_courier_snapshot_ids`); taxi_debt — ПО ВРЕМЕНИ создания платежа, не по ID-снимку состава (известный остаток на стыке с листом 1.1, НЕ исправлено в этом листе — см. «Остаток»); оба коммитятся одной транзакцией со статусом | исправлено (было ❌ — см. «Найденные ошибки», F1); taxi_debt — известный остаток, см. «Остаток» |
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
| `yookassa_webhook` | 839–867 `POST /payments/yookassa/webhook` | Уведомление провайдера — телу НЕ доверяем, перепроверяем по id | Нерелевантно при не-yookassa → no-op; битый JSON → no-op (это ради него функция осталась `async def` с `await request.json()`); **валидный JSON неожиданной формы** ([], число, `object` не словарь, числовой id) тоже no-op (F7, было ❌ — падало 500); незнакомый id → no-op БЕЗ исходящего запроса (анти-DoS); сам поиск платежа и `_sync_provider_status` синхронные — переданы в `_process_yookassa_webhook` через `await run_in_threadpool(...)`, чтобы НЕ исполняться на потоке event loop (F2, было ❌ — исправлено) | исправлено (было ❌ — F2, F7) |
| `_process_yookassa_webhook` | 869–879 (не эндпоинт, вызывается из `yookassa_webhook` через `run_in_threadpool`) | Синхронная часть вебхука: найти платёж по `provider_id`, если не терминальный — сверить с провайдером | Неизвестный/терминальный платёж → ничего не делает, никакого исходящего запроса; иначе `_sync_provider_status` (может дойти до `_activate_payment`/`_handle_unclaimed_payment`) | ок |

## Связи

- `routers/wallet.py` импортирует `_activate_payment`, `_start_yookassa`, `_sync_provider_status` —
  ОБЩАЯ точка применения денег для ride/booking/boost/ad/partner_sub/donate/support/
  courier_commission/taxi_debt. F1-исправление в `_activate_payment` защищает ОБА файла разом.
- `debt.py` (лист 1.1): `_activate_payment` для taxi_debt вызывает `debt_mod.mark_all_paid`.
  Лист 1.1 в своём раунде добавил туда же `taxi_debt_snapshot`/`taxi_debt_snapshot_ids` —
  причинный ID-снимок состава счёта (по образцу `_courier_snapshot_ids`), которым
  `_activate_payment` для taxi_debt пока НЕ пользуется (известный остаток, см. «Остаток») —
  не моя правка, не мой файл, намеренно не трогаю до отдельной задачи ведущего.
- `payments.py` (клиент ЮKassa): `create_payment`/`fetch_payment`, тарифы `BOOST_PLANS`/`BOOST_WEIGHT`.
- `ledger.py`: `settle_instant_order`/`settle_booking` (из `_activate_payment`) — их возврат
  теперь ЧИТАЕТСЯ, а не игнорируется.
- `courier.py`: причинный ID-снимок суммы курьерской комиссии (`_courier_snapshot_ids`), которым
  `_activate_payment` реально пользуется. `debt.py` (на ветке листа 1.1, НЕ в этом чекауте —
  см. «Остаток») получил снимок того же вида для долга такси, но `_activate_payment` для
  taxi_debt его пока не читает и гасит долг по времени, как раньше.
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
| F2 | Синхронная часть вебхука (поиск платежа + `_sync_provider_status`) выполняется в threadpool, НЕ на потоке event loop — с 2 ASGI-воркерами и локом заказа (новым в этом раунде, `wallet.py`) иначе реален deadlock воркера | `backend/tests/walk/l1_2/test_l1_2_webhook_off_event_loop.py::test_webhook_body_runs_off_the_event_loop` | да — M46 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| F1 (R9, деньги, P1) | Независимая проверка (Opus 5.5): `_activate_payment` вызывал `ledger.settle_instant_order`/`settle_booking` и ИГНОРИРОВАЛ ответ. Ответ `"already"` означает: заказ/бронь уже оплачены ДРУГИМ платежом (или налом), пока этот шёл к провайдеру. Несмотря на это, платёж ВСЁ РАВНО помечался `succeeded` — деньги пассажира списывались у провайдера взаправду, а след оставался только как молчаливое расхождение в `ledger.reconcile`: ни тикета, ни пуша, ни сигнала админу. Это ровно тот случай, ради которого написан `_handle_unclaimed_payment` — но он был подключён только к пути «вебхук против локально отменённого платежа». | `test_l1_2_second_payment_refund_due.py`: два pending-счёта на один заказ/бронь, оба активируются подряд | `if result != "settled": _handle_unclaimed_payment(session, payment); return` в ОБЕИХ ветках (ride/booking) | До — второй платёж тоже `succeeded`, 0 тикетов; после — `refund_due`, 1 тикет, ledger не задвоен |
| F5 (доступность, P3) | `boost_create` при повторном тапе на уже заведённый у провайдера счёт звал `fetch_payment` БЕЗ `try/except` — в отличие от `_sync_provider_status` (кошелёк/долг/курьер). Сетевая ошибка/таймаут заваливала ВЕСЬ запрос в 500 вместо вежливого повтора через тот же Idempotence-Key. | `test_l1_2_boost_reuse_fetch_failure.py`: первый `/boost/create` заводит счёт, второй — с `fetch_payment`, бросающим `TimeoutError` | Обёрнуто в `try/except Exception: existing = None` — при неудаче просто повторяет `_start_yookassa` с тем же ключом (как и остальной код) | До — 500; после — `< 500` (нормальный повтор) |
| F7 (доступность, P3) | Вебхук с СИНТАКСИЧЕСКИ валидным, но НЕОЖИДАННЫМ JSON (`[]`, число, `{"object": "строка"}`, числовой `id`) падал 500 вместо честного no-op — карточка обещала «телу не доверяем», но это не распространялось на ФОРМУ тела, только на значения. | `test_l1_2_webhook_malformed_shapes.py`: 7 вариантов формы | `object_`/`provider_id` достаются через `isinstance`-проверки, не голый `.get()` | До — `AttributeError: 'int' object has no attribute 'get'` (500); после — `{"ok": true}` |
| F2 (доступность, P1 — повышено раундом 2) | Вебхук — `async def`, но тело (поиск платежа, `fetch_payment` до 15 с, синхронные Telegram/push) выполнялось ПРЯМО на потоке event loop. Раунд 1 я отметил это подозрением без теста. Раунд 2 нашёл конкретный deadlock: с новым локом заказа (`wallet.py`, `with_for_update`) ранний выход из `pay_instant_order` держит лок заказа до конца HTTP-сессии; вебхук на том же воркере, идя синхронно через `settle_instant_order`, встаёт в очередь на тот же лок БЕЗ `lock_timeout` — с 2 воркерами процесс виснет насмерть, не просто «тормозит». | `test_l1_2_webhook_off_event_loop.py`: заглушка `fetch_payment` проверяет изнутри `asyncio.get_running_loop()` | Тело после `await request.json()` вынесено в `_process_yookassa_webhook`, вызывается через `await run_in_threadpool(...)` — `async def` сохранён ради парсинга JSON (иначе битый JSON стал бы 422, ломая F7) | До — `get_running_loop()` НЕ бросает (поток event loop); после — бросает `RuntimeError` (поток пула) |
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
| M46 | Вебхук: `await run_in_threadpool(...)` снят — `_process_yookassa_webhook` зовётся прямо в теле `async def` | test_webhook_body_runs_off_the_event_loop | KILLED |

## Остаток и ограничения

Реальная ЮKassa не вызывалась (мок/заглушки).

**F2 — был пробел, исправлено во втором раунде независимого ревью (Opus 5.5, 2026-10-02).**
Изначально я отметил это подозрением, не найдя способа честно подтвердить тестом «зависание
ДРУГИХ запросов» через `TestClient` (он гоняет ASGI-приложение без настоящего конкурентного
event loop с несколькими воркерами). Ревью указало конкретный и куда более опасный путь:
с блокировкой строки заказа (leaf добавил её в `wallet.py` в этом же раунде аудита) ранний
выход из `pay_instant_order` держит `FOR UPDATE` на заказе до конца HTTP-сессии; если в ЭТО
же время вебхук (на одном из двух ASGI-воркеров) вызовет синхронный `fetch_payment`/`_sync_
provider_status` прямо в event loop, он сам встанет в очередь на тот же лок заказа (внутри
`settle_instant_order`) БЕЗ `lock_timeout` — и теперь уже не просто «подождут другие запросы
на этом воркере», а второй воркер виснет насмерть на неограниченном ожидании. Это не гипотеза
про нагрузку, это конкретный deadlock-путь, появившийся как побочный эффект моей же правки.

Подтвердил тестом не через конкурентность, а проще и надёжнее: заглушка `fetch_payment`
проверяет изнутри себя `asyncio.get_running_loop()` — в threadpool-потоке Python бросает
`RuntimeError` (там нет работающего event loop), на потоке event loop — не бросает. Тест был
красным ДО правки (подтверждает, что проблема настоящая, не домысел) и зелёным после.

Правка: `yookassa_webhook` остался `async def` ради `await request.json()` (битый JSON
по-прежнему тихо отвечает 200 — иначе тест на F7 сломался бы), но тело после разбора JSON
вынесено в `_process_yookassa_webhook(session, provider_id)` и вызывается через
`await run_in_threadpool(...)`. Поиск платежа, `_sync_provider_status` (HTTP к ЮKassa до 15 с)
и синхронные Telegram/push из `_handle_unclaimed_payment` теперь выполняются в потоке пула,
не на потоке event loop — не блокируют ни другие запросы на том же воркере, ни (что важнее
после этого раунда) не рискуют встать в бесконечную очередь за локом заказа изнутри event loop.

**taxi_debt vs причинный ID-снимок — известный остаток, НЕ исправлено в этом листе (найдено
на стыке с листом 1.1, 2026-10-02).** `_activate_payment` для `purpose == "taxi_debt"`
по-прежнему гасит долг водителя ПО ВРЕМЕНИ (`debt_mod.mark_all_paid(up_to=payment.created_at)`)
— ровно так же, как было до этого аудита. В ЭТОМ ЖЕ раунде лист 1.1 (своя карточка —
`debt.py.md`, его F2) добавил `taxi_debt_snapshot`/`taxi_debt_snapshot_ids`: точный ID-снимок
состава счёта в `Payment.tier`, по образцу уже существующего `_courier_snapshot_ids` для
курьерской комиссии. `_activate_payment` для courier_commission этим снимком ПОЛЬЗУЕТСЯ (гасит
ровно ID из снимка, см. выше в «Функции и разбор»); для taxi_debt — НЕТ, время-граница читает
только `created_at`, про `Payment.tier`/снимок не знает вообще.

Дыра времени-границы (а НЕ причинного снимка) в деньгах: пока счёт ЮKassa на оплату долга
висит `pending`, ничто не мешает админу простить один из долгов этого счёта
(`/admin/debts/{id}/forgive` — проверено, никакой защиты от этого нет ни до, ни после F2
листа 1.1, оно закрывает только кошелёк со стороны `settle_debt_from_wallet`). Когда банк
подтверждает оплату, время-граница молча гасит «все долги до счёта» — формально идемпотентно
(прощённый долг уже `paid`, второй раз его не тронет), но деньги за него СПИСАНЫ С КАРТЫ по
счёту целиком, а в кошелёк водителя разница не возвращается: тот же класс молчаливого
расхождения в `ledger.reconcile`, что чинил F1 (раунд 1) — просто с другой стороны.

Сознательно НЕ чиню это в этом коммите: правка требует трогать `taxi_debt_snapshot_ids`
(`debt.py`, чужой файл, не мой) и, скорее всего, `LedgerEntry` (`ledger.py`, тоже чужой) —
ведущий ведёт её отдельной небольшой задачей сразу после слияния этого листа, без слияния
чужих файлов в историю `leaf-1.2`. Тестов на этот остаток в этом листе намеренно нет.
