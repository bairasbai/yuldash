# Карточка: `backend/app/routers/wallet.py`

- Статус: verified
- Лист: leaf-1.2
- Проверял: Sonnet 5 (leaf-1.2); принимал: Opus 5.5; независимое ревью раунд 1: Opus 5.5 (2026-10-02, VERDICT: REQUEST_CHANGES — остаточная гонка и F1/F3/F6, закрыты); независимое ревью раунд 2: Opus 5.5 (2026-10-02, VERDICT: REQUEST_CHANGES — N1, закрыт этим коммитом)

## Назначение

Деньги v1 (Фаза 3, D3): оплата ЗАВЕРШЁННОЙ поездки/брони пассажиром (карта/СБП через ЮKassa
или «наличные» мимо нас) + кошелёк-ledger водителя (баланс, история, вывод на карту) + сверка.
Безнал начисляет водителю через append-only ledger (earn − комиссия); наличные идут мимо нас.
Анти-IDOR последовательно: платить может ТОЛЬКО пассажир-владелец, кошелёк/историю видит
ТОЛЬКО сам водитель по своему токену, сверка и реестр выплат — только админ.

## История этого листа (честно, по шагам)

**Первый проход.** Нашёл настоящую, но УЗКУЮ гонку: строка платежа заказа создавалась с
`method` = выбор клиента, и только СЛЕДУЮЩИМ коммитом (`_start_yookassa`) получала
`method="yookassa"`. Дедуп «уже есть висящий платёж» не узнавал строку ровно в миллисекунды
между этими двумя коммитами — а «сироту» (тот же вид строки, оставшийся навсегда после падения
процесса между ними) не узнавал вообще никогда. Исправил условие дедупа
(`if existing:` вместо проверки `provider_id`/`method`). Карточка на тот момент ОШИБОЧНО
утверждала, что этим гонка закрыта полностью — независимое ревью (Opus 5.5) эту гонку
подтвердило как реальную, но указало: условие дедупа помогает, только если ОДНА строка уже
существует к моменту, когда вторая её ищет. Если ДВА запроса проходят SELECT дедупа ДО того,
как любой из них вставил свою строку (оба видят «пусто»), оба заводят СВОЮ — ровно то, что
проверяет честный тест на гонку, а не псевдо-гонка с засеянным промежуточным состоянием.
Важная оговорка самого ревью про размер риска: ДВОЙНОЙ ТАП с одного экрана до сервера вообще
не доходит — кнопка блокируется флагом `busy` в `android/app/src/main/java/com/yuldash/app/
PayOnlineCard.kt`; а обычный СЕТЕВОЙ РЕТРАЙ (клиент не получил ответ и повторил запрос) уже и
ДО этой правки переиспользовал ту же строку — к моменту ретрая она давно `method="yookassa"`,
это покрыто `backend/tests/test_audit_fix_be06_retries.py::test_trip_timeout_retries_the_same_payment`.
То есть реальный жизненный сценарий для R1/R8 — это не «пользователь дважды тапнул», а
по-настоящему параллельные запросы (два устройства/вкладки одной сессии, или сам ретрай,
случившийся настолько быстро, что обе попытки оказались в полёте одновременно) — узкое, но
не нулевое окно, которое и проверяет PostgreSQL-тест ниже.

**Второй проход (этот коммит) — по независимому ревью.** Закрыл саму гонку (не только
полумеру), нашёл и исправил ещё одну подтверждённую денежную ошибку (F1) и четыре помельче
(F3, F5, F6, F7); F6 в ПЕРВОЙ версии чуть не сломал намеренно поддерживаемую историческую
функциональность — это тоже описано ниже как урок, а не спрятано.

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_guard_method` | 45–47 | Способ оплаты — только `cash`/`card`/`sbp` | 400 на прочее | ок |
| `_cancel_own_pending_cashless` | 51–73 | Оплата налом закрывает СВОЙ висящий безнал-платёж на тот же заказ/бронь | Иначе живая ссылка ЮKassa пережила бы нал — пассажир заплатил бы дважды (нал + поздняя карта); отмена — ОДИН атомарный `UPDATE ... WHERE status='pending'` (не SELECT+правка объектов), поэтому не задевает строку, которую конкурентный вебхук уже перевёл в `refund_due` (N1, повторное ревью) | ок |
| `_pay_cashless` | 69–193 | Общий безналичный поток (карта/СБП) через ЮKassa: dev/mock → succeeded сразу; yookassa → confirmation_url, начисление по webhook | Прод+не-yookassa → 503; дедуп pending ПО ЗАКАЗУ/БРОНИ, единым `session.commit()` ДО любого внешнего HTTP (освобождает lock заказа/брони — иначе deadlock с вебхуком, который берёт лок в обратном порядке); ветка «провайдер отменил» для ОБОИХ (заказ/бронь) перепроверяет `paid`/`done` под СВЕЖИМ lock и рекурсивно вызывает себя; `refund_due`/незнакомый статус строки НЕ создают третий счёт; после `_activate_payment` возвращает ФАКТИЧЕСКИЙ статус строки (F1, не жёсткий «succeeded») | исправлено (было ❌ в двух местах — см. «Найденные ошибки») |
| `pay_instant_order` | 196–239 `POST /instant/orders/{id}/pay` | Пассажир платит за ЗАВЕРШЁННЫЙ быстрый заказ | Заказ блокируется (`with_for_update`+`populate_existing`) СРАЗУ при входе — как и бронь; только владелец (403), только `done` со статусом/водителем, идемпотентно (`already_paid`); явный 0 к оплате (100% промо-скидка) блокируется ДО выбора способа (F3); путь «нал» проверяет ФАКТИЧЕСКИЙ результат `settle_instant_order`, а не слепо отвечает «paid» (F1b) | исправлено (было ❌ — см. «Найденные ошибки») |
| `_booking_amount_kop` | 241–246 | Сумма к оплате брони: `pay_amount` (если ЕСТЬ, в т.ч. явный 0) иначе `price` | `amount_kop<=0` → 409 «нет суммы к оплате» — явный 0 НЕ подменяется ценой (QA-B07-002) | ок |
| `pay_booking` | 249–273 `POST /bookings/{id}/pay` | Пассажир платит за ЗАВЕРШЁННУЮ бронь | Row-lock на Booking сразу при входе (анти-гонка «правка цены vs оплата», QA-B07-003); только владелец, только `done`, идемпотентно; путь «нал» тоже проверяет результат `settle_booking` (F1b) | исправлено (было ❌) |
| `wallet_balance` | 278–291 `GET /wallet/balance` | Баланс + сколько зарезервировано под долг платформе + сколько реально свободно | `payable_kop = max(bal-долг, 0)`; `reserved_kop` не может быть больше, чем реально лежит на балансе | ок |
| `wallet_ledger` | 293–306 `GET /wallet/ledger` | История начислений/комиссий/выплат — ТОЛЬКО свои (по токену) | `limit` зажат [1, 500] | ок |
| `admin_ledger_reconcile` | 309–328 `GET /admin/ledger/reconcile` | Сверка SUM(earn)↔SUM(успешных безналичных Payment) за период — ТОЛЬКО админ | `days` зажат [1, 366]; неверный ISO-8601 → 400, не 500 | ок |
| `_payout_profile` | 331–332 | Профиль водителя для выплат | — | ок |
| `wallet_payout_status` | 335–350 `GET /wallet/payout/status` | Доступны ли выплаты + баланс + сохранённые реквизиты | Границы (`min_kop`/`max_kop`) с сервера, не хардкод клиента | ок |
| `save_payout_requisite` | 362–379 `POST /wallet/payout/requisite` | Сохранить карту для выплат — ТОЛЬКО последние 4 цифры + токен провайдера | Полный номер (`card_number`) НЕ сохраняется и не логируется; < 12 цифр и без last4 → 400 | ок |
| `wallet_payout` | 388–410 `POST /wallet/payout` | Вывод с баланса на карту (Модель Б, выкл. по умолчанию) | `payouts_ready=False` → мягкое 503 «скоро» (не 500); без реквизита → 400; дальше — `ledger.request_payout` (идемпотентность/границы/резерв — вне зоны) | ок |
| `admin_payouts` | 413–437 `GET /admin/payouts` | Реестр выплат (ledger kind=payout), с пометкой отклонённых банком | Только админ; отменённая выплата остаётся записью, но ПОМЕЧЕНА `reversed` | ок |

## Связи

- `ledger.py` (вне зоны): `driver_balance`, `ledger_entries`, `owed_to_platform_kop`,
  `payable_balance`, `reconcile`, `request_payout`, `PayoutError`, `settle_instant_order`/
  `settle_booking` (через `_activate_payment`) — ОБА теперь зовутся с проверкой возврата (F1).
- `routers/payments.py`: `_activate_payment`, `_start_yookassa`, `_sync_provider_status` —
  используются напрямую (импорт); `_activate_payment` теперь тоже проверяет результат settle_*
  (F1, см. карточку `routers/payments.py.md`).
- `promo_ride.py`: `payable_kop` — сумма к оплате минус промо-скидка; может дать 0 (F3).
- `debt.py`/`routers/courier.py`: `owed_to_platform_kop` суммирует долг такси + комиссию курьера.
- Android: `ApiClient.kt` — `getPayoutStatus`/`savePayoutRequisite`/`requestPayout`/`payBooking`/
  `payInstantOrder`; DTO `PayoutStatusDto`/`PayoutResultDto`/`PayTripResultDto`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Гонка двух запросов на оплату ОДНОГО заказа не заводит два счёта/два реальных списания — НАСТОЯЩАЯ конкуренция, не только «сирота» | `backend/tests/walk/l1_2/test_l1_2_wallet_race.py::test_second_pay_call_reuses_in_flight_invoice_not_a_new_one` (сирота/узкое окно) + `backend/tests/walk/l1_2/test_l1_2_wallet_race_pg.py::test_pg_two_concurrent_pay_requests_before_either_insert_share_one_invoice` (настоящие параллельные потоки, реальная блокировка PostgreSQL, подтверждённая `pg_blocking_pids`) | да — M24, M31 (на PostgreSQL) |
| R2 | Явная сумма 0 (pay_amount брони) НЕ подменяется ценой поездки | `backend/tests/test_booking_payment_agreement_amount.py::test_explicit_zero_agreement_cannot_charge_the_original_positive_price` | да — M25 |
| R3 | Повторная оплата уже оплаченного заказа останавливается на верхнем гейте `order.paid`, не доходя до создания нового платежа | `backend/tests/walk/l1_2/test_l1_2_wallet_race.py::test_paying_an_already_paid_order_again_does_not_touch_anything` | да — M26 |
| R4 | Анти-IDOR: чужой заказ не оплатить (владелец ≠ плательщик → 403, начисления нет) | `backend/tests/test_ledger.py::test_cannot_pay_others_order`, `backend/tests/test_booking_payment_agreement_amount.py::test_only_actual_passenger_can_pay_agreed_booking` | да — M27 |
| R5 | Сверка ledger↔оплат — только админ | `backend/tests/test_ledger.py::test_reconcile_admin_only` | да — M28 |
| R6 | Вывод выключен по умолчанию — мягкое 503 «скоро» (не 500) | `backend/tests/test_payouts.py::test_payout_disabled_by_default` | да — M29 |
| R7 | Номер карты не хранится — только последние 4 цифры + токен провайдера | `backend/tests/test_payouts.py::test_requisite_stores_only_last4` | да — M30 |
| R8 | Остаточная гонка (найдено ревью): заказ блокируется (`with_for_update`) СРАЗУ при входе — вторая нить ждёт первую и переиспользует её строку | `backend/tests/walk/l1_2/test_l1_2_wallet_race_pg.py::test_pg_two_concurrent_pay_requests_before_either_insert_share_one_invoice` | да — M31 (PostgreSQL) |
| R9 | F1b: путь «наличными» проверяет ФАКТИЧЕСКИЙ результат settle_* — не отвечает «оплачено налом» поверх уже списанной карты | `backend/tests/walk/l1_2/test_l1_2_second_payment_refund_due.py::test_cash_after_card_already_settled_reports_already_paid_not_cash`, `::test_cash_branch_itself_checks_the_settle_result_not_just_the_outer_paid_flag` | да — M34 |
| R10 | Заказ с суммой 0 (100% промо-скидка) не уходит в оплату ни картой/СБП, ни налом | `backend/tests/walk/l1_2/test_l1_2_zero_amount_order.py::test_card_payment_on_a_fully_discounted_order_is_rejected_not_sent_as_zero`, `::test_cash_payment_on_a_fully_discounted_order_is_also_rejected` | да — M35 |
| R12 | Новая строка платежа ЗАКАЗА получает `method='yookassa'` СРАЗУ при создании (симметрично брони) — не бывает «сиротой» для ручной СБП-очереди | `backend/tests/walk/l1_2/test_l1_2_manual_queue_excludes_ride_booking.py::test_fresh_order_payment_is_tagged_yookassa_before_any_external_call`, `::test_legacy_manual_booking_payment_is_still_visible_and_confirmable` (контроль не-регресса — историческая ручная бронь ОБЯЗАНА остаться видна) | да — M37 |
| R17 | Оплата налом отменяет СВОЙ висящий безналичный платёж (не даёт заплатить дважды) | `backend/tests/walk/l1_2/test_l1_2_cancel_pending_does_not_clobber_refund_due.py::test_cancel_own_pending_still_cancels_a_genuinely_pending_row` | да — M44 |
| R18 | Прод-гейт: в проде без реального yookassa безнал отдаёт 503, а не фантомное начисление | `backend/tests/test_release_blockers.py::test_online_pay_blocked_in_sbp_manual_prod` | да — M45 |
| N1 | Отмена своего безнала — ОДИН атомарный `UPDATE ... WHERE status='pending'`, а не SELECT+безусловная правка объектов: не может затереть `refund_due`, который конкурентный вебхук успел поставить между чтением и commit (найдено повторным независимым ревью, Opus 5.5, 2026-10-02) | `backend/tests/walk/l1_2/test_l1_2_cancel_pending_does_not_clobber_refund_due.py::test_cancel_own_pending_does_not_touch_a_refund_due_row` | да — M47 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| BUG-l1_2-01 (R1, сужение) | Пассажир МОГ попасть в узкое окно гонки: дедуп не узнавал свою же свежую строку платежа заказа в миллисекунды между двумя `commit` (вставка → `_start_yookassa`). Двойной тап/ретрай в этом окне заводил ВТОРОЙ `Payment` с ДРУГИМ Idempotence-Key. | `test_l1_2_wallet_race.py` сеет строку-«в процессе» и зовёт `/pay` ещё раз | Условие дедупа упрощено до `if existing:` | До — `[1, 2] == [1]` падает; после — зелёный |
| F1 (R9, деньги, P1) | Независимая проверка (Opus 5.5): если по ОДНОМУ заказу/брони оказывалось ДВА `pending`-счёта у провайдера (из-за остаточной гонки R8 или из-за гонки между картой и «налом») и ОБА получали «succeeded» от ЮKassa — второй молча становился `succeeded` без начисления, без тикета, без сигнала админу. Деньги пассажира реально списаны у провайдера, а след — только скрытое расхождение в `ledger.reconcile`. Путь «нал» отдельно отвечал «оплачено налом» поверх уже списанной карты — человек отдал бы водителю наличные ВТОРОЙ раз. | `test_l1_2_second_payment_refund_due.py` сеет два `pending`-счёта на один заказ/бронь, активирует оба | `_pay_cashless` теперь возвращает ФАКТИЧЕСКИЙ статус строки после `_activate_payment` (не жёсткий «succeeded»); путь «нал» в `pay_instant_order`/`pay_booking` проверяет результат `settle_*` и отвечает `already_paid`, если не `"settled"`. Корневая часть исправления (`_activate_payment` перенаправляет в `_handle_unclaimed_payment`) — в `routers/payments.py.md` | До — второй платёж `succeeded`, нет тикета; после — `already_paid`/`refund_due`, ledger не задваивается |
| F3 (деньги, P3) | У заказа НЕ было защиты от суммы 0 (100% промо-скидка), которая уже была у брони. На ЮKassa «0.00» зависало в вечном `pending`; в mock — заказ молча «оплачивался» на 0 ₽. | `test_l1_2_zero_amount_order.py` зануляет цену полной скидкой, зовёт `/pay` | `if amount_kop <= 0: raise herr(409, ...)` сразу после расчёта суммы, симметрично `_booking_amount_kop` | До — 200/`succeeded` на 0 ₽; после — 409 |
| F6 (деньги/доступность, P2 — урок процесса) | Независимая проверка нашла пробел (ручная СБП-очередь могла показать «сироту» за заказ и начислить фантомные деньги). **Первая версия исправления** слепо исключила `purpose in (ride, booking)` из очереди/подтверждения — и СЛОМАЛА намеренно поддерживаемую историческую функциональность (`test_manual_payment_terminal_confirmation.py::legacy_booking` — старые ручные записи за бронь ОБЯЗАНЫ остаться видны и подтверждаемы). Поймано тем же комплексным прогоном теста, не угадано заранее. | `test_manual_payment_terminal_confirmation.py::test_pending_manual_confirmation_and_duplicates_match_real_effect[legacy_booking]` упал после первой версии правки | Исправление перенесено к корню: `method='yookassa'` ставится СРАЗУ при создании строки ЗАКАЗА (как у брони), а не отдельным шагом позже — значит НОВАЯ строка никогда не бывает «сиротой», а СТАРЫЕ исторические строки видны и подтверждаемы как и раньше | До (версия 1 фикса) — тест `legacy_booking` красный; после (версия 2) — весь `test_manual_payment_terminal_confirmation.py` зелёный (19/19 вместе с новыми) |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M24 | `_pay_cashless`: откат упрощения дедупа (снова не узнаёт висящую строку-сироту) | test_second_pay_call_reuses_in_flight_invoice_not_a_new_one | KILLED |
| M25 | `_booking_amount_kop`: `is not None` → truthy (0 снова подменяется ценой) | test_explicit_zero_agreement_cannot_charge_the_original_positive_price | KILLED |
| M26 | `pay_instant_order`: гейт `order.paid` отключён (`and False`) | test_paying_an_already_paid_order_again_does_not_touch_anything | KILLED |
| M27 | `pay_instant_order`: анти-IDOR инвертирован (`!=` → `==`) | test_cannot_pay_others_order | KILLED |
| M28 | `admin_ledger_reconcile`: админ-гейт инвертирован | test_reconcile_admin_only | KILLED |
| M29 | `wallet_payout`: гейт `payouts_ready` инвертирован | test_payout_disabled_by_default | KILLED |
| M30 | `save_payout_requisite`: сохраняется весь номер карты вместо last4 | test_requisite_stores_only_last4 | KILLED |
| M31 | `pay_instant_order`: блокировка заказа (`with_for_update`) снята | test_pg_two_concurrent_pay_requests_before_either_insert_share_one_invoice (**PostgreSQL**, настоящие потоки) | KILLED |
| M34 | Ветка «нал» заказа: проверка результата `settle_instant_order` отключена | test_cash_branch_itself_checks_the_settle_result_not_just_the_outer_paid_flag | KILLED |
| M35 | Защита суммы 0 заказа снята | test_card_payment_on_a_fully_discounted_order_is_rejected_not_sent_as_zero | KILLED |
| M37 | Новая строка заказа снова получает method клиента вместо 'yookassa' сразу | test_fresh_order_payment_is_tagged_yookassa_before_any_external_call | KILLED |
| M44 | `_cancel_own_pending_cashless`: атомарный UPDATE пишет `status="pending"` вместо `"canceled"` (не отменяет) | test_cancel_own_pending_still_cancels_a_genuinely_pending_row | KILLED |
| M45 | Прод-гейт `_pay_cashless` снят | test_online_pay_blocked_in_sbp_manual_prod | KILLED |
| M47 | N1: фильтр `Payment.status == "pending"` убран из UPDATE отмены — снова может затереть `refund_due` | test_cancel_own_pending_does_not_touch_a_refund_due_row | KILLED |

## Остаток и ограничения

M31 (настоящая гонка) прогнан на изолированном PostgreSQL с РЕАЛЬНЫМИ двумя потоками и
подтверждённой через `pg_blocking_pids` блокировкой — не имитация. Полный
`test_pay_agreement_payment_concurrency.py` (6/6, гонки брони) тоже прогнан на PostgreSQL —
без регрессии от правок этого листа; файл требует ОТДЕЛЬНОГО прогона (его собственный docstring:
«Root alone launches this file») и конфликтует по счётчику тестовых телефонов при совместном
запуске с ~500 другими тестами — это особенность межфайловой изоляции тестов, не моей правки.

Мультипоточная гонка на ВЫВОД (wallet_payout, два потока с разными идемпотентными ключами на
один и тот же баланс) не исследовалась в этом листе отдельно — сама логика резерва/ставки лежит
в `ledger.request_payout` (вне зоны), и `test_the_payout_that_never_left.py` уже проверяет её
идемпотентность по ключу; настоящую PG-конкуренцию для выплат не гонял.

F2 (гипотеза, НЕ подтверждена тестом, НЕ правил): независимая проверка отметила, что
`yookassa_webhook` (routers/payments.py) объявлен `async def`, но внутри делает синхронные
DB-запросы и синхронный HTTP (`fetch_payment`, до 15 с таймаут) — под несколькими ASGI-воркерами
это теоретически может задержать ДРУГИЕ запросы, ждущие тот же event loop, пока один медленный
вебхук выполняется. Я не нашёл способа честно ПОДТВЕРДИТЬ это тестом в имеющейся
инфраструктуре (TestClient гоняет ASGI-приложение синхронно, не через реальный многопоточный
event loop с конкурентными запросами) — значит, по правилу листа, не правлю, а оставляю
подозрением с этим рассуждением. Подробности и возможное решение (`run_in_threadpool` вокруг
`_sync_provider_status`) — в `routers/payments.py.md`, где живёт сам вебхук.
