# RS-05 — Повтор при плохой связи не создаёт вторую поездку или бронь

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (таблица `commandreceipt`), выкладка сервера |
| Зависит от | RS-02 (общая зона брони); с RS-06 общие `models.py`, `ApiClient.kt`, `cleanup.py`, `account.py`, миграции — по очереди |
| Двигает сценарии приёмки | W1-E2E-04 (главный); W1-E2E-02, W1-E2E-03 («нет второго рейса/брони») |
| Зона файлов | `backend/app/`: `idempotency.py` (новый), `models.py` (`CommandReceipt`), `routers/rides.py` (`create_ride`, `cancel_ride`, `complete_ride`), `routers/bookings.py` (`book`, `confirm_booking`, `_cancel_booking`, `mark_no_show`, `driver_status`, `_mark_boarded`), `routers/family.py` (`set_trip_status`), `cleanup.py`, `account.py`; `backend/alembic/versions/<префикс>_command_receipt_<дата>.py`; `backend/tests/test_command_idempotency{,_postgres}.py`. `android/app/src/main/java/com/yuldash/app/`: `data/CommandIntent.kt` (новый), `data/ApiClient.kt`, `data/TripPass.kt` (`Outbox`), `data/SessionKeys.kt`, `CreateRideScreen.kt`, `YuldashApp.kt` (ветка брони), `BookingActiveTripScreen.kt` (`updateTripStatus`); тесты в `android/app/src/test/…/app/`: `data/CommandIntentTest.kt`, `data/ApiClientCommandKeyTest.kt`, `CreateRideLostResponseTest.kt`, `BookingLostResponseTest.kt`. `webapp/src/`: `utils/commandIntent.ts` (новый), `api/driver.ts`, `api/bookings.ts`, `api/rides.ts`, `api/family.ts`, `utils/outbox.ts`, `screens/CreateRideScreen.tsx`, `screens/ActiveTripScreen.tsx` (`sendPhase`), `components/RideSheet.tsx`; `webapp/tests/command-intent-test.mjs`. Мозг: `docs/tasks.md`, `docs/decisions.md` |
| Источник | `c5be55a:docs/research/apps/master/part-002.md` «Минимальные контракты надёжности», `master/part-001.md` п. 4, 10, 28; решения 24.10: Q9, Q10, Q11 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Водитель из Акъяра на трассе в Сибай, связь — одна полоска. Жмёт «Опубликовать» — «нет связи», хотя сервер поездку записал. Через минуту жмёт снова — в ленте две одинаковые поездки, пассажиры бронируют разные копии одной машины.
Пассажирка из Ургазы бронирует место, ответ потерялся, водитель тем временем отказал. Она жмёт ещё раз — появляется **новая** бронь, хотя она лишь хотела узнать, дошла ли первая.
Водитель в Баймаке без сети нажал «Сел», ошибся, нажал «Отменить» — а очередь позже снова отправила старое «Сел».

**Стало.** У каждого нажатия свой «номер намерения». Сервер помнит, что по нему сделано, и на повтор отвечает тем же итогом, ничего не делая второй раз. Экран честно пишет: «Связь пропала — не знаем, дошло ли. Нажми ещё раз: второй поездки не будет». После перезапуска — тот же номер; новое нажатие после успеха — новый.

## 2. Что уже есть в коде
- `backend/app/routers/rides.py:52-54` окно `_DUPLICATE_WINDOW_SEC = 60`, `:175-194` `_recent_twin_ride`, `:305-317` возврат «близнеца» — позже 60 с повтор даёт вторую поездку; комментарий `:306-307` («ApiClient повторяет POST») устарел. `:242-259` тело меняется на месте; `:325-341` «регулярная» — ещё 4 рейса; `:1097-1105`, `:1169-1176` снять/завершить рейс — повтор по состоянию.
- `backend/app/routers/bookings.py:529-535` повтор возвращает только **активную** бронь (`:449-453`), `:421-446` 409 `booking_seats_conflict` и др. с `booking_id`; после отказа/отмены повтор создаёт новую бронь. `:472-474` `guard_burst` — до проверки повтора.
- `bookings.py:1412-1416` подтверждение уже отменённой — 400, хотя подтверждение прошло. Повтор по состоянию: отмена `:1473-1489`, неявка `:1709-1715`, «done» `:1047-1069`, «Сел» `:1279-1300`; `:1332` `/boarded/undo`; «done» пассажира — `backend/app/routers/family.py:475-508`.
- Образцы ключа: чат `backend/app/routers/chat.py:700-757` (другой хеш → 409, квитанция в той же транзакции, разбор `IntegrityError`), `:779-781` заголовок; `backend/app/models.py:649-657`; SOS `backend/app/routers/safety.py:253-263` (формат `[A-Za-z0-9_-]{16,128}`), `:289-298` окно 15 мин; миграция `cb_sos_retry_intent_20261021.py` — голова на 24.10.
- `backend/app/main.py:130` CORS пропускает `Idempotency-Key`; `cleanup.py:69-75` + `test_nothing_grows_forever.py`; `test_account_deletion.py:26-34`.
- Android `data/ApiClient.kt:3532-3536` попутка без скрытого повтора, `:3582-3601` заголовок поддержан, `:3636-3642` ключ переживает обновление токена. Без ключа: `:967-1026`, `:1078-1102`, `:2342-2361`, `:2390-2395`, `:1657-1659`; `:1613-1630` очередь — ключ только у `message`.
- `data/TripPass.kt:253-260` поле `requestKey`, `:426-440` ставит его только `newMessage`, `:429-434` производный ключ, `:444-445` статус живёт 6 ч; `BookingActiveTripScreen.kt:1623-1643` при обрыве — новое действие без ключа попытки.
- `data/SosRetryIntent.kt:8-16` (на диске ключ и хеш), `data/SessionKeys.kt:23-32`; `CreateRideScreen.kt:405-430`, `YuldashApp.kt:1405-1430` — защита только в памяти, текст «Не удалось забронировать» не говорит, что бронь могла пройти.
- Сайт: `webapp/src/api/client.ts:407-423` `apiPost` умеет `idempotencyKey`, `:340-356` обрыв = `ApiError(0)`; `webapp/src/utils/outbox.ts:152-166` ключ только у сообщений; `webapp/src/utils/sosIntent.ts:113-133` образец; без ключа — `api/driver.ts:60-62,129-151`, `api/bookings.ts:191-193,237-239,390-407,442-448`, `api/rides.ts:143`, `api/family.ts:103`.
- `docs/audit-android-offline-durable-write-2026-09-19.md:44`: повтор при потере ответа не доказан. Общего ключа команд — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
1. **Модель** `CommandReceipt` (`commandreceipt`): `id`, `user_id` (FK `user.id`, `ondelete=CASCADE`, индекс), `request_key` (128), `scope` (40), `payload_hash` (64), `ref_kind` (`ride`/`booking`), `ref_id`, `created_at` (индекс); `UniqueConstraint("user_id","request_key", name="uq_command_receipt_owner_key")`. Тел запроса/ответа **не храним** (в брони бывает телефон взрослого).
2. **`app/idempotency.py`:** `parse_key` — нет заголовка → `None`, плохой формат → 422 `idempotency_key_invalid`; `fingerprint(scope, target, body)` — SHA-256 канонического JSON (`sort_keys`) из `scope`, id пути и `body.model_dump()`, **до** правок тела; `replay` — нет квитанции → `None`, другой хеш → 409 `idempotency_key_reused`, старше `REPLAY_HOURS = 24` → 409 `idempotency_key_expired`; `record` — только `session.add`. Ошибки — `HTTPException(code, {"ru","ba","code"})`, как `rides.py:140-143`.
3. **Порядок:** владелец/участник → `replay` → остальное (`ensure_active`, `guard_burst`, сроки, места): успех не «пропадает» из-за паузы или потолка, наступивших позже.
4. **Повтор — тот же итог без побочек:** текущее представление того же объекта (`public_ride_payload(ride_out(...), full=True)`, `_booking_out`, `_boarding_out`, `{"ok":True,"status":...}`), без пушей, `notify_map_changed`, `notify_route_watchers`, `trip_wake.poke`, бонусов, SMS. Объекта нет — 409 `idempotency_result_gone`.
5. **Квитанция в той же транзакции** на каждом пути 2xx (новое действие, «близнец» `rides.py:315-317`, `_booking_retry`, повтор по состоянию). `IntegrityError` по ключу → `rollback` → `replay` (как `chat.py:749-755`); в `book` отличать от гонки переднего места. Отказы (4xx) не запоминаем.
6. **Ручки:** `POST /rides` (ref — первый рейс серии), `/rides/{id}/cancel`, `/rides/{id}/complete`, `POST /bookings`, `/bookings/{id}/confirm`, `/cancel` (тело `{reason}`), `/no-show`, `/driver-status` (все статусы), `/trip-status`.
7. **Без ключа — прежнее поведение**; окно 60 с остаётся и с ключом.
8. **Срок:** правило `cleanup._rules` «квитанции команд >2д» (`COMMAND_RECEIPT_DAYS = 2`, `_ALLOWED_TABLES`); `account.py` удаляет квитанции человека. Окно сервера 24 ч > срока намерения клиента (≤ 6 ч). Ключ и тело в логи не писать.

### Android (`android/`)
1. `data/CommandIntent.kt` по образцу `SosRetryIntent.kt`: слоты `scope → {key, fingerprint, created}` в ящике `yuldash`, ключ `command_pending_intents`, без текста и ПДн. `prepare(scope, body)`: тот же `scope` и хеш в сроке → тот же ключ, иначе новый UUID. Срок: публикация/бронь 15 мин, статусы 6 ч; до 64 слотов; привязка ко входу; ключ — в `SessionKeys.CLEARED_ON_LOGOUT`.
2. `ApiClient.kt`: каждая команда п. 6 → `prepare` (`ride.publish`, `booking.create:<rideId>`, `booking.confirm:<id>`…) → `call(..., idempotencyKey = key)`. 2xx или окончательный 4xx → `acknowledge`; обрыв, таймаут, 408/429/5xx → намерение живёт (`isUnknownOutcome(e)`).
3. `TripPass.kt`: `newDriverStatus`/`newTripStatus` с `requestKey`, общий `Outbox.requestKey(action)`, `sendQueuedAction` шлёт ключ статусов; `updateTripStatus` кладёт в очередь ключ первой попытки.
4. `CreateRideScreen.kt`: неизвестный итог — карточка `CanonWarnBg` (§5), «Опубликовать ещё раз» тем же ключом; живое намерение при открытии — «Прошлая публикация могла дойти» и «Мои поездки» (`AnimatedVisibility`, `CanonMotion`). `YuldashApp.kt` (бронь): текст §5.
5. Публикацию и бронь в очередь **не ставим** и в фоне не шлём — повтор только по нажатию.

### Сайт (`webapp/`)
1. `utils/commandIntent.ts` по образцу `sosIntent.ts`: `localStorage` `yuldash.command.pending`, владелец `getSessionGeneration()`, сроки как на Android; нет хранилища — ключ в памяти вкладки.
2. `api/driver.ts`, `api/bookings.ts`, `api/rides.ts`, `api/family.ts`: команда сама берёт ключ (`{ idempotencyKey }`); `ApiError(0)`, 408/429/5xx — неизвестный итог.
3. `utils/outbox.ts` `run()`: ключ `String(a.id)` и для статусов; `ActiveTripScreen.tsx` `sendPhase` при `status === 0` — `enqueue(..., id = ключ попытки)`.
4. `CreateRideScreen.tsx`, `RideSheet.tsx`: тексты §5; черновик не очищать до успеха.

## 4. База данных
Миграция `<следующий префикс>_command_receipt_<ГГГГММДД>`: `down_revision` — голова на момент работы (24.10 — `cb_sos_retry_intent_20261021`; если RS-02 добавит свою — на неё). Создаёт `commandreceipt` с уникальным ограничением и индексами; повторный запуск безопасен (`inspect`, как в SOS-миграции). Старые данные не трогает; `downgrade` удаляет таблицу. **Ждёт «да»** на рабочей базе.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- «Связь пропала — не знаем, дошла ли публикация. Нажми ещё раз: второй поездки не будет.»
- «Связь пропала — не знаем, дошла ли бронь. Нажми ещё раз: второй брони не будет.»
- «Связь пропала — не знаем, дошло ли. Нажми ещё раз: дважды не сработает.» (подтверждение, отмена, «Сел», «Не пришёл», завершение)
- «Прошлая публикация могла дойти. Проверь «Мои поездки», прежде чем менять.»; кнопки «Опубликовать ещё раз», «Мои поездки».
- Сервер: `idempotency_key_reused` — «Это нажатие уже было с другими данными. Обнови экран и попробуй снова.»; `idempotency_key_expired` — «Повтор устарел. Обнови экран — проверим, что уже сохранилось.»; `idempotency_result_gone` — «Это уже недоступно. Обнови экран.»; `idempotency_key_invalid` — «Не получилось распознать повтор. Обнови приложение.»

## 6. Состояния экрана
Новых экранов нет. Публикация и бронь: отправка (спиннер, кнопка недоступна) → успех / отказ словами сервера / **неизвестный итог** (предупреждение + повтор) / нет сети до отправки (прежний текст). Длинный башкирский переносится на 360 dp; тёмная тема — `Canon*`; тач-цель ≥ 48 dp.

## 7. Тесты
**Сервер** (`pytest`, `ruff check app/ --select F`), `test_command_idempotency.py`:
1. Каждая ручка п. 6: два запроса с одним ключом → один объект, одна `Notification`, тот же `id`.
2. Публикация: повтор через 61 с — одна поездка (без ключа — две); «регулярная» — 5 рейсов, не 10.
3. Бронь → отказ водителя → повтор: новой брони нет. 4. Подтверждение → отмена пассажиром → повтор: 200, `cancelled`, без пуша. 5. «Сел» → `/boarded/undo` → повтор «Сел»: `confirmed`.
6. Другое тело/ручка → 409 `idempotency_key_reused`; чужой пользователь независим; >24 ч → `idempotency_key_expired`; плохой формат → 422. 7. Пауза аккаунта после успеха → повтор 200.
8. Зелёные прежние (`test_double_tap_publish.py`, `test_booking_multiseat_retry.py`, `test_booking_done_network_retry.py`, `test_nothing_grows_forever.py`, `test_account_deletion.py`, `test_migrations_match_the_models.py`); миграция вверх/вниз.
9. `test_command_idempotency_postgres.py` (пропуск не на PostgreSQL, как `test_chat_postgres_concurrency.py:15`): 8 потоков, один ключ → один объект.
Мутации: убрать `record` в `book` → красный п. 3; не сравнивать хеш → п. 6; `replay` после `ensure_active` → п. 7.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `CommandIntentTest`; `ApiClientCommandKeyTest` (`MockWebServer`, `SocketPolicy.DISCONNECT_AFTER_REQUEST`: повтор с тем же ключом, очередь — ключ попытки, старая очередь — производный); Compose `CreateRideLostResponseTest`, `BookingLostResponseTest`; все `*GuardTest`, включая `SessionKeysGuardTest`.
**Сайт:** `npm run check` (`tests/command-intent-test.mjs`: тот же ключ после обрыва и перезагрузки, новый после успеха), `npm run build`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] 9 ручек п. 6: повтор с ключом — тот же объект без побочек (база, `Notification`); другое тело — 409 с кодом; без ключа — как раньше.
- [ ] Квитанция в той же транзакции, отказы не запоминаются, ПДн нет, таблица в чистке и удалении аккаунта, миграция обратима.
- [ ] Android и сайт: повтор после потери ответа — тот же ключ, после успеха — новый.
- [ ] Эмулятор и сайт с локальным сервером (Q11); «потеря ответа» — прокси проверки в `test-results/` (рвёт ответ) или Playwright `route.fetch()`+`route.abort()`: публикация → повтор через 70 с; бронь → отказ → повтор; «Сел» без сети → «Отменить» → сеть. Одна поездка, одна бронь, верная стадия.
- [ ] Смерть процесса: `adb shell am force-stop` сразу после нажатия → повтор → без дубля; сайт — перезагрузка вкладки.
- [ ] Надписи §5: RU/BA × светлая/тёмная, узкий экран, крупный шрифт; BA — черновик в `docs/tasks.md`.
- [ ] Двигает W1-E2E-04 (обе платформы). Остаются: заявки/отклики, живые телефоны (W1-E2E-07), BA носителем. Статус — только `docs/audit-blocks.md`.

## 9. Риски и границы
- Изменил поле после неизвестного итога → новое намерение, возможна вторая поездка (лишь окно 60 с) — отсюда карточка «могла дойти».
- Ключ привязан к `user_id`, права проверяются до повтора — чужой ключ ничего не даёт.
- Не входит: заявки/отклики (`requests.py`), правка поездки, договорённость об оплате; денег нет (Q5). SQLite не держит `FOR UPDATE` — гонку ловит тест на PostgreSQL.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
