# RS-05 — Повтор при плохой связи не создаёт вторую поездку или бронь

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграция рабочей базы (таблица `commandreceipt`), выкладка сервера |
| Зависит от | RS-02 (общая зона брони и публикации); с RS-06 общие `models.py`, `ApiClient.kt`, `cleanup.py`, `account.py`, голова миграций — по очереди |
| Двигает сценарии приёмки | W1-E2E-04 (главный); W1-E2E-02, W1-E2E-03 («нет второго рейса/брони») |
| Зона файлов | `backend/app/`: `idempotency.py` (новый), `models.py` (`CommandReceipt`), `routers/rides.py` (`create_ride`, `cancel_ride`, `complete_ride`), `routers/bookings.py` (`book`, `confirm_booking`, `_cancel_booking`, `mark_no_show`, `driver_status`, `_mark_boarded`), `routers/family.py` (`set_trip_status`), `cleanup.py`, `account.py`; `backend/alembic/versions/<префикс>_command_receipt_<дата>.py`; `backend/tests/test_command_idempotency.py`, `test_command_idempotency_postgres.py`. `android/app/src/main/java/com/yuldash/app/`: `data/CommandIntent.kt` (новый), `data/ApiClient.kt`, `data/TripPass.kt` (`Outbox`), `data/SessionKeys.kt`, `CreateRideScreen.kt`, `YuldashApp.kt` (ветка брони), `BookingActiveTripScreen.kt` (`updateTripStatus`); тесты `android/app/src/test/java/com/yuldash/app/`: `data/CommandIntentTest.kt`, `data/ApiClientCommandKeyTest.kt`, `CreateRideLostResponseTest.kt`, `BookingLostResponseTest.kt`. `webapp/src/`: `utils/commandIntent.ts` (новый), `api/driver.ts`, `api/bookings.ts`, `api/rides.ts`, `api/family.ts`, `utils/outbox.ts`, `screens/CreateRideScreen.tsx`, `screens/ActiveTripScreen.tsx` (`sendPhase`), `components/RideSheet.tsx`; `webapp/tests/command-intent-test.mjs`. Мозг: `docs/tasks.md`, `docs/decisions.md` |
| Источник | `c5be55a:docs/research/apps/master/part-002.md` «Минимальные контракты надёжности» (команда, результат, транзакция, восстановление), `master/part-001.md` п. 4, 10, 28; решения 24.10: Q9, Q10, Q11 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Было.** Водитель из Акъяра на трассе в Сибай, связь — одна полоска. Жмёт «Опубликовать», колесо крутится, «нет связи». Сервер поездку записал, ответ до телефона не дошёл. Через минуту водитель жмёт снова — в ленте две одинаковые поездки, пассажиры бронируют разные копии одной машины.
Пассажирка из Ургазы бронирует место, ответ потерялся, водитель за это время отказал. Она жмёт «Забронировать» ещё раз — и появляется **новая** бронь, хотя она хотела лишь узнать, дошла ли первая.
Водитель в Баймаке без сети нажал «Сел», понял, что ошибся, нажал «Отменить» — а очередь позже снова отправила старое «Сел».

**Стало.** У каждого нажатия свой «номер намерения». Сервер помнит, что по этому номеру уже сделано, и на повтор отвечает тем же итогом, ничего не делая второй раз. Экран честно пишет: «Связь пропала — не знаем, дошло ли. Нажми ещё раз: второй поездки не будет». Перезапустил телефон — тот же номер. Новое осознанное нажатие после успеха — новый номер.

## 2. Что уже есть в коде
- `backend/app/routers/rides.py:52-54` окно `_DUPLICATE_WINDOW_SEC = 60`; `:175-194` `_recent_twin_ride`; `:305-317` замок водителя и возврат «близнеца» — позже 60 с повтор даёт вторую поездку. Комментарий `:306-307` («ApiClient повторяет POST») устарел.
- `rides.py:242-259` тело меняется на месте (клампы, `depart_at` в UTC); `:325-341` «регулярная» создаёт ещё 4 рейса; `:1097-1105`, `:1169-1176` снять/завершить рейс — повтор по состоянию.
- `backend/app/routers/bookings.py:529-535` повтор возвращает только **активную** бронь (`_active_booking`, `:449-453`); `:421-446` `_booking_retry` — 409 `booking_seats_conflict` и др. с `booking_id`. После отказа/отмены повтор создаёт новую бронь. `:472-474` `guard_burst` стоит до проверки повтора.
- `bookings.py:1412-1416` подтверждение: если бронь уже отменили — 400 «уже нельзя подтвердить», хотя подтверждение прошло. Повтор по состоянию: отмена `:1473-1489`, неявка `:1709-1715`, «done» `:1047-1069`, «Сел» `:1279-1300`; `:1332` `/boarded/undo`; пассажирское «done» — `backend/app/routers/family.py:475-508`.
- Образец ключа — чат: `backend/app/routers/chat.py:700-714` (другой хеш → 409), `:717-757` квитанция в той же транзакции и разбор `IntegrityError`, `:779-781` заголовок `Idempotency-Key`; `backend/app/models.py:649-657` `ChatMessageRequest`.
- Образец SOS: `backend/app/routers/safety.py:253-263` формат `[A-Za-z0-9_-]{16,128}` и хеш тела, `:289-298` окно 15 мин, `:370-379` гонка; миграция `backend/alembic/versions/cb_sos_retry_intent_20261021.py` — голова на 24.10.
- `backend/app/main.py:130` CORS пропускает `Idempotency-Key`. `backend/app/cleanup.py:69-75` `KEEP_FOREVER` + сторож `tests/test_nothing_grows_forever.py`; `backend/tests/test_account_deletion.py:26-34` ищет все ссылки на `user.id`.
- Android `data/ApiClient.kt:3532-3536` попутка — один выстрел без скрытого повтора; `:3582-3601` заголовок `Idempotency-Key` поддержан; `:3636-3642` ключ сохраняется после обновления токена.
- `ApiClient.kt:967-1026` `publishRide`, `:1078-1102` бронь, `:2342-2361` отмена/неявка/подтверждение, `:2390-2395` рейс, `:1657-1659` `driverStatus` — **без ключа**; `:1613-1630` очередь шлёт ключ только у `message`.
- `data/TripPass.kt:253-260` поле `requestKey` у `OutboxAction`; `:426-440` ключ ставит только `newMessage`; `:429-434` производный ключ старой очереди; `:444-445` статус живёт 6 ч. `BookingActiveTripScreen.kt:1623-1643` при обрыве кладёт в очередь новое действие без ключа первой попытки.
- `data/SosRetryIntent.kt:8-16` на диске только ключ и хеш, 15 мин; `data/SessionKeys.kt:23-32` `sos_pending_intent` стирается при выходе.
- `CreateRideScreen.kt:405-430`, `YuldashApp.kt:1405-1430` — защита от двойного нажатия только в памяти; текст «Не удалось забронировать. Повтори.» не говорит, что бронь могла пройти.
- Сайт: `webapp/src/api/client.ts:407-423` `apiPost` умеет `idempotencyKey`, `:340-356` обрыв = `ApiError(0)`; `webapp/src/utils/outbox.ts:152-166` ключ только у сообщений; `webapp/src/utils/sosIntent.ts:113-133` образец намерения; команды в `api/driver.ts:60-62,129-151`, `api/bookings.ts:191-193,237-239,390-407,442-448`, `api/rides.ts:143`, `api/family.ts:103` — без ключа.
- `docs/audit-android-offline-durable-write-2026-09-19.md:44` — повторная доставка при потере ответа не доказана.
- Общего ключа для публикации, брони, отмены, подтверждения, «Сел», «Не пришёл», завершения — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
1. **Модель** `CommandReceipt` (`commandreceipt`): `id`, `user_id` (FK `user.id`, `ondelete=CASCADE`, индекс), `request_key` (128), `scope` (40), `payload_hash` (64), `ref_kind` (`ride`/`booking`), `ref_id`, `created_at` (индекс); `UniqueConstraint("user_id","request_key", name="uq_command_receipt_owner_key")`. Тел запроса и ответа **не храним** (в брони бывает телефон взрослого).
2. **`app/idempotency.py`:** `parse_key(raw)` — нет заголовка → `None`, плохой формат → 422 `idempotency_key_invalid`; `fingerprint(scope, target, body)` — SHA-256 канонического JSON (`sort_keys`, компактно) из `scope`, id пути и `body.model_dump()`, **до** правок тела; `replay(...)` — нет квитанции → `None`, другой хеш → 409 `idempotency_key_reused`, старше `REPLAY_HOURS = 24` → 409 `idempotency_key_expired`; `record(...)` — только `session.add`. Ошибки — `HTTPException(code, {"ru","ba","code"})`, как `rides.py:140-143`.
3. **Порядок:** проверка владельца/участника → `replay` → остальное (`ensure_active`, `guard_burst`, сроки, места): успех не должен «пропасть» из-за паузы или потолка, наступивших после него.
4. **Повтор — тот же итог без побочек:** текущее представление того же объекта (`public_ride_payload(ride_out(...), full=True)`, `_booking_out`, `_boarding_out`, `{"ok":True,"status":...}`); без пушей, `notify_map_changed`, `notify_route_watchers`, `trip_wake.poke`, бонусов, SMS. Объекта нет — 409 `idempotency_result_gone`.
5. **Квитанция в той же транзакции** на каждом пути 2xx: новое действие, «близнец» (`rides.py:315-317`), `_booking_retry`, повтор по состоянию. `IntegrityError` по `uq_command_receipt_owner_key` → `rollback` → `replay` (как `chat.py:749-755`); в `book` отличать от гонки переднего места.
6. **Отказы (4xx) не запоминаем** — повтор отказанного безопасен, клиент берёт новый ключ.
7. **Ручки (`scope`):** `POST /rides` (`ride.publish`, ref — первый рейс серии), `/rides/{id}/cancel`, `/rides/{id}/complete`, `POST /bookings` (`booking.create`), `/bookings/{id}/confirm`, `/cancel` (тело `{reason}`), `/no-show`, `/driver-status` (все статусы), `/trip-status`.
8. **Без ключа — прежнее поведение** (окно 60 с, активная бронь, повтор по состоянию). Окно 60 с остаётся и с ключом.
9. **Срок:** правило `cleanup._rules` «квитанции команд >2д» (`COMMAND_RECEIPT_DAYS = 2`, таблица в `_ALLOWED_TABLES`); `account.py` — удалять квитанции человека. Окно сервера (24 ч) больше срока намерения клиента (≤ 6 ч). Ключ и тело в логи не писать.

### Android (`android/`)
1. `data/CommandIntent.kt` по образцу `SosRetryIntent.kt`: слоты `scope → {key, fingerprint, created}` в ящике `yuldash`, ключ `command_pending_intents`; без текста, телефонов, координат. `prepare(scope, body)`: тот же `scope` и хеш в сроке → тот же ключ, иначе новый UUID. Срок: публикация/бронь 15 мин, статусы 6 ч. До 64 слотов, привязка ко входу, ключ — в `SessionKeys.CLEARED_ON_LOGOUT`.
2. `ApiClient.kt`: каждая команда из п. 7 → `prepare(scope)` (`ride.publish`, `booking.create:<rideId>`, `booking.confirm:<id>`…) → `call(..., idempotencyKey = key)`. 2xx или окончательный 4xx → `acknowledge`; обрыв, таймаут, 408/429/5xx → намерение остаётся (помощник `isUnknownOutcome(e)`).
3. `TripPass.kt`: `newDriverStatus`/`newTripStatus` принимают `requestKey`; общий `Outbox.requestKey(action)`; `sendQueuedAction` шлёт ключ для статусов. `BookingActiveTripScreen.kt` `updateTripStatus`: в очередь — ключ первой попытки.
4. `CreateRideScreen.kt`: неизвестный итог — карточка на `CanonWarnBg` (§5) и «Опубликовать ещё раз» тем же ключом; при открытии формы с живым намерением — «Прошлая публикация могла дойти» и «Мои поездки». Появление — `AnimatedVisibility`, длительность `CanonMotion`.
5. `YuldashApp.kt` (бронь): неизвестный итог — текст §5; 409 с `booking_id` открывает свою бронь (как сейчас).
6. Публикацию и бронь в очередь **не ставим** и в фоне не шлём — повтор только по нажатию.

### Сайт (`webapp/`)
1. `utils/commandIntent.ts` по образцу `sosIntent.ts`: `localStorage` `yuldash.command.pending`, владелец `getSessionGeneration()`, сроки как на Android; хранилище недоступно — ключ в памяти вкладки.
2. `api/driver.ts`, `api/bookings.ts`, `api/rides.ts`, `api/family.ts`: команда сама берёт ключ и передаёт `{ idempotencyKey }`; `ApiError(0)`, 408/429/5xx — неизвестный итог.
3. `utils/outbox.ts` `run()`: ключ `String(a.id)` и для статусов; `ActiveTripScreen.tsx` `sendPhase`: при `status === 0` — `enqueue(..., id = ключ попытки)`.
4. `CreateRideScreen.tsx`, `RideSheet.tsx`: тексты §5; черновик формы не очищать до успеха.

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
Новых экранов нет. Публикация и бронь: отправка (спиннер, кнопка недоступна) → успех / отказ словами сервера / **неизвестный итог** (предупреждение + повтор) / нет сети до отправки (прежний текст). Длинный башкирский переносится, на 360 dp кнопки не уезжают; тёмная тема — `Canon*` (сайт — переменные темы); тач-цель ≥ 48 dp.

## 7. Тесты
**Сервер** (`pytest`, `ruff check app/ --select F`), `test_command_idempotency.py`:
1. Каждая ручка п. 7: два запроса с одним ключом → один объект, одна `Notification`, тот же `id`.
2. Публикация: повтор через 61 с с ключом — одна поездка (без ключа — две); «регулярная» — 5 рейсов, не 10.
3. Бронь → водитель отказал → повтор с ключом: новой брони нет.
4. Подтверждение → пассажир отменил → повтор: 200, `cancelled`, без пуша.
5. «Сел» → `/boarded/undo` → повтор «Сел»: бронь `confirmed`.
6. Другое тело/ручка с тем же ключом → 409 `idempotency_key_reused`; чужой пользователь — независим; >24 ч → `idempotency_key_expired`; плохой формат → 422.
7. Пауза аккаунта после успеха → повтор 200.
8. Прежние тесты без ключа зелёные: `test_double_tap_publish.py`, `test_booking_multiseat_retry.py`, `test_booking_done_network_retry.py`, `test_bad_connection_does_not_double_things.py`; миграция вверх/вниз; `test_nothing_grows_forever.py`, `test_account_deletion.py`, `test_migrations_match_the_models.py`.
9. `test_command_idempotency_postgres.py` (пропуск не на PostgreSQL, как `test_chat_postgres_concurrency.py:15`): 8 потоков, один ключ → один объект.
Мутации: убрать `record` в `book` → красный п. 3; не сравнивать хеш → п. 6; `replay` после `ensure_active` → п. 7.

**Android** (`:app:testDebugUnitTest`, `:app:assembleDebug`): `CommandIntentTest` (тот же ключ/новый после успеха, отказа, срока; выход стирает); `ApiClientCommandKeyTest` (`MockWebServer`, `SocketPolicy.DISCONNECT_AFTER_REQUEST`: повтор несёт тот же `Idempotency-Key`, очередь — ключ первой попытки, старая очередь — стабильный производный); Compose `CreateRideLostResponseTest`, `BookingLostResponseTest`. Сторожа `*GuardTest` зелёные, включая `SessionKeysGuardTest`.
**Сайт:** `npm run check` (`tests/command-intent-test.mjs`: тот же ключ после обрыва и перезагрузки, новый после успеха), `npm run build`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] 9 ручек п. 7 принимают ключ; повтор — тот же объект без побочек (сверить по базе и `Notification`).
- [ ] Тот же ключ с другим телом — 409 с кодом; без ключа — прежнее поведение.
- [ ] Квитанция в той же транзакции; отказы не запоминаются; ПДн в квитанции нет; таблица в чистке и удалении аккаунта; миграция обратима.
- [ ] Android и сайт: ручной повтор после потери ответа — тот же ключ, после успеха — новый.
- [ ] Эмулятор Android и сайт с локальным сервером (Q11), «потеря ответа» — прокси проверки в `test-results/` (пропускает запрос, рвёт ответ) или Playwright `route.fetch()` + `route.abort()`: публикация → повтор через 70 с; бронь → отказ водителя → повтор; «Сел» без сети → «Отменить» → сеть. Итог: одна поездка, одна бронь, верная стадия.
- [ ] Смерть процесса: `adb shell am force-stop` сразу после нажатия → повтор → без дубля; сайт — перезагрузка вкладки.
- [ ] Надписи §5: RU/BA × светлая/тёмная, узкий экран, крупный шрифт; BA — черновик в `docs/tasks.md`.
- [ ] Двигает W1-E2E-04 (обе платформы). Остаются: заявки/отклики, живые телефоны (W1-E2E-07), BA носителем. Статус — только `docs/audit-blocks.md`.

## 9. Риски и границы
- Изменил поле после неизвестного итога → новое намерение, возможна вторая поездка (лишь окно 60 с) — отсюда карточка «могла дойти».
- Вторую активную бронь на ту же поездку сервер и так не даст; ключ закрывает повтор после отказа/отмены.
- Ключ привязан к `user_id`: чужой ключ ничего не даёт. Права проверяются до повтора.
- Окно сервера 24 ч > срока клиента 6 ч — иначе старая очередь создаст новое действие.
- Не входит: заявки/отклики (`requests.py`), правка поездки, договорённость об оплате; денег нет (Q5). SQLite не держит `FOR UPDATE` — гонку ловит тест на PostgreSQL.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
