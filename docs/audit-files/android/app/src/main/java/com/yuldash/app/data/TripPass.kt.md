# Карточка: `android/app/src/main/java/com/yuldash/app/data/TripPass.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Два независимых офлайн-механизма на 458 строк: **паспорт поездки** (`TripPass`/`TripPassStore`) —
локальный снимок брони (маршрут, водитель, ЕГО ТЕЛЕФОН, код посадки, госномер), который показывается,
когда сервер недоступен на трассе; и **очередь исходящих** (`OutboxAction`/`Outbox`) — действия
(сообщение в чат, статус «сел»/«доехал»), которые нужно отправить, когда связь появится. Общее
у обоих: оба хранят персональные данные (телефон водителя; текст переписки), оба используют
shared-механизм `OfflineMigration`/`OfflineStoreReset`/`OfflineWrites` (карточки рядом) для
plain↔secure переезда и durable-очистки при выходе.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `TripPass.toJson`/`fromJson` | 48–86 | Сериализация снимка брони | `fromJson` — ВСЕ поля через `opt*` с разумными дефолтами (урезанный/старый JSON не роняет чтение); `seats` по умолчанию `1`, а не `0` | ок |
| `TripPassStore.init(context)` | 103–118 | Поднимает secure (`EncryptedSharedPreferences`, `MasterKey`) с мягким падением на `null`, затем `initStores` | Keystore недоступен → `secure=null`, работаем в plain (с риском, принятым осознанно — см. комментарий класса) | ок |
| `TripPassStore.initStores(plain, secure)` | 120–136 | Определяет доступность secure через `OfflineStoreReset.availableSecure`, запускает/продолжает перенос через `OfflineMigration.open`, затем `TripPassDeletion.reconcile` — физически чистит уже помеченные удалёнными брони, если миграция завершена | `internal` — зовётся тестами напрямую с фейковыми хранилищами | ок |
| `TripPassStore.migratePlain` | 144–145 | Тестовый «вход» в тот же `open()`, что и `initStores`, но с явными параметрами хранилищ (комментарий: иначе ветка `secure != null` никогда не выполнится в тестовой среде без настоящего Keystore — «тест на переезд был бы зелёным на пустоте») | `internal` | ок |
| `TripPassStore.ensureResetCommitted()` | 154–158 | Если логаут не был подтверждён (`OfflineMigration.resetUnconfirmed`) — повторяет `clearAll()`; используется `ApiClient.persistNewSession` ПЕРЕД записью нового токена | Не даёт записать новую сессию поверх недостёртой старой | ок |
| `TripPassStore.save(context, pass, retryMigration, expectedGeneration)` | 161–180 | Пишет снимок брони. Порядок проверок: поколение сессии → заблокирована ли бронь удалением (`TripPassDeletion.blocks`) → опциональный ретрай миграции (но НЕ если logout ещё не подтверждён) → `migration?.writable` → собственно запись через `commitOfflineString` | `@Synchronized`, весь `runCatching` — любое исключение (битый JSON у `pass.toJson()` не бывает, но защита на будущее) → `false`, не краш | ок |
| `TripPassStore.load(context, bookingId)` | 183–187 | Читает снимок по ID брони; СНАЧАЛА проверяет, не помечена ли бронь удалённой | Чужой `bookingId` → `null` (нет такого ключа) | ок |
| `TripPassStore.updateBoardingCode` | 190–198 | Дочитывает текущий паспорт, меняет код посадки, пишет заново тем же `save()` — остальные поля (телефон, имя) не теряются | Пустой код → `false` сразу, без похода в хранилище | ок |
| `TripPassStore.requestRemoval`/`remove` | 203–217 | `requestRemoval` различает `CLEARED` (стёрто физически из обоих хранилищ) и `DEFERRED` (отметка «удалено» поставлена и УЖЕ скрывает бронь от `load`, но физическая зачистка ждёт, пока миграция/secure не станут доступны); `remove` — совместимый `Boolean` (`true` только для `CLEARED`) | Устаревшее поколение сессии → `NOT_SAVED`, ничего не меняется | ок |
| `TripPassStore.clearAll()` | 224–233 | Выход из аккаунта: `OfflineStoreReset.clear`; при отказе — переходит на голый `plain` (данные СКРЫТЫ как минимум локальным стиранием, пусть и не подтверждённым полностью), затем пересобирает `migration` | Комментарий у класса явно привязывает это к 152-ФЗ: «следующий вошедший не должен их видеть» | ок |
| `Outbox.init`/`initStores` | 279–303 | Аналогично `TripPassStore`, но очередь шифруется из-за ТЕКСТА переписки (комментарий: «переписка ничем не менее личная», чем телефон водителя) | `queueGeneration++` при каждом `initStores`/`clearAll` — поколение очереди, НЕ поколение аккаунта (`ApiClient.queueSessionGeneration`) — используется, чтобы остановить `flush`, если очередь была заменена/очищена ПОКА шёл сетевой запрос | ок |
| `Outbox.readAll(context)` | 318–333 | `null` — нечитаемо (ошибка чтения ИЛИ невалидный JSON); пустой список — ТОЛЬКО если ключа реально нет. Различие критично: `enqueue`/`writeAll` отказываются писать, если `readAll` вернула `null` | Комментарий: «null means unreadable data; only an absent key represents an empty queue» | ок — и это именно тот инвариант, который чинили в QA-B01-019 |
| `Outbox.writeAll(context, list)` | 335–345 | Пишет весь список одним JSON; увеличивает `version` (Compose-наблюдаемый счётчик) ТОЛЬКО при успехе | — | ок |
| `Outbox.count`/`hasPending` | 348–352 | И то, и другое используют `?: 0`/`?: false` при `null` от `readAll` — повреждённая очередь визуально показывается как «пусто», а не как ошибка (см. «Остаток») | — | известное ограничение (см. «Остаток»), НЕ новая ошибка — задокументировано в `architecture.md` |
| `Outbox.enqueue` | 354–359 | Устаревшее поколение аккаунта ИЛИ нечитаемая очередь (`readAll`→`null`) → `false`, ничего не меняется | — | ок |
| `Outbox.clearAll()` | 366–377 | Выход из аккаунта: та же схема, что у `TripPassStore.clearAll`, плюс `queueGeneration++` (останавливает фоновые `flush`) и бамп `version` (UI узнаёт о пустой очереди) | — | ок |
| `Outbox.messageRequestKey` | 382–387 | Стабильный ключ идемпотентности: из явного `requestKey`, а для legacy-записей (без него) — детерминированный UUID из `(id, bookingId, createdAt, payload)`, НЕ из одного текста (повтор с тем же текстом, но другим `id`, получает другой ключ) | — | ок |
| `Outbox.flush(context)` | 407–457 | Под `flushMutex` (не блокирует хранилище целиком, только последовательность отправки): снимает протухшие СТАТУСЫ (не сообщения) старше `STATUS_MAX_AGE_MS`=6ч; на каждой итерации перепроверяет поколение очереди И поколение сессии; временный HTTP-отказ (408/429/5xx) или сетевая ошибка — останавливается, оставляя очередь; окончательный отказ — снимает действие | Слияние с «живой» очередью перед удалением отправленного элемента — сообщения, добавленные ПОКА шёл HTTP, не перезаписываются | ок |

## Связи

- `OfflineMigration`/`OfflineStoreReset`/`OfflineWrites` (этот же лист) — весь plain↔secure переезд и
  durable-очистка.
- `TripPassDeletion` (`data/TripPassDeletion.kt`, ВНЕ зоны — только прочитан для понимания контракта):
  отметки «бронь N удалена», с которыми `TripPassStore.load`/`save`/`clearAll` обязаны считаться.
- `ApiClient` (ВНЕ зоны): `queueSessionGeneration`/`sendQueuedAction`/`ApiException` — поколение
  сессии и фактическая отправка по сети.
- Экраны (вне зоны, не трогал): `ActiveTripScreen`/`BookingScreen` читают `TripPassStore` для офлайн-
  показа брони; `RidesRequestsChatScreens` кладёт сообщения/статусы в `Outbox`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Протухший (>6ч) отложенный СТАТУС выбрасывается из очереди при `flush`, а не уходит с враньём о давно закончившейся поездке | `android/app/src/test/java/com/yuldash/app/OutboxStaleStatusTest.kt::"вчерашний статус не уезжает пассажиру"` | да — M1 |
| R2 | Отложенное СООБЩЕНИЕ никогда не выбрасывается по возрасту (в отличие от статуса) | `android/app/src/test/java/com/yuldash/app/OutboxStaleStatusTest.kt::"сообщение не выбрасываем никогда"` | да — M2 |
| R3 | Паспорт поездки сохраняется/читается строго по ID брони — чужая бронь не может получить чужой паспорт | `android/app/src/test/java/com/yuldash/app/TripPassTest.kt::"чужой номер брони не отдаёт паспорт"` | да — M3 |
| R4 | `load()` обязан скрывать удалённую бронь даже тогда, когда снимок/физическая копия ещё физически не зачищены (deferred-удаление при незавершённой миграции) | `android/app/src/test/java/com/yuldash/app/data/OfflineMigrationCleanupTest.kt::repeatedInitKeepsSnapshotReadableAndBlocksDeleteUntilRecovery` | да — M4 (первая попытка сослаться на `TripPassDeletionTest` НЕ ловила поломку — см. «Найденные ошибки»/«Остаток»: та проверка каждый раз переинициализирует хранилища через `.restarted()`, а это сбрасывает инъекцию отказа и заодно доводит `reconcile()` до конца ДО вызова `load()`, так что чтение в любом случае не достаёт до помеченной записи) |
| R21 | Выход из аккаунта стирает ОБА хранилища паспорта — следующий человек на этом телефоне не видит имя/телефон предыдущего пассажира/водителя | `android/app/src/test/java/com/yuldash/app/TripPassTest.kt::"удаление стирает паспорт — телефон не остаётся на устройстве"` и `android/app/src/test/java/com/yuldash/app/data/LegacyOfflineOwnerTest.kt::logoutClearsLegacyAndActiveStores` | да (существующие тесты; механизм очистки отдельно замутирован в карточке `OfflineStoreReset.kt`, M7/M8) |
| R22 | Выход из аккаунта выбрасывает ВСЮ очередь исходящих — неотправленное сообщение прошлого человека не может уйти от имени следующего | `android/app/src/test/java/com/yuldash/app/data/OutboxConcurrencyTest.kt::logoutDuringHttpPreservesNextAccountsQueue` | да (существующие тесты) |
| R23 | Устаревшее поколение сессии не может ни добавить действие в очередь, ни начать его отправку | `android/app/src/test/java/com/yuldash/app/data/OutboxConcurrencyTest.kt::staleSessionCannotEnqueueAfterNewLogin` | да (существующие тесты) |
| R24 | Повреждённая/нечитаемая запись на диске не подменяется пустой очередью (ни на `enqueue`, ни на `flush`), переживает перезапуск процесса | `android/app/src/test/java/com/yuldash/app/data/OutboxCorruptStorageTest.kt::malformedQueueDoesNotPermitReplacementOrHttp` | да (существующие тесты прошлого раунда QA-B01-019) |
| R25 | Параллельные `flush()` не дублируют отправку; добавление/очистка/замена хранилища ПОКА идёт HTTP не ломают очередь следующего владельца | `android/app/src/test/java/com/yuldash/app/data/OutboxConcurrencyTest.kt::simultaneousFlushesDoNotDuplicateMessages` | да (существующие тесты) |

## Найденные ошибки

Ошибок в коде не найдено. Файл уже прошёл интенсивный прошлый аудит (QA-B01-019, волна 74/109/115/117
в `architecture.md`) и имеет ~40 существующих тестов (`OutboxTest`, `OutboxConcurrencyTest`,
`OutboxCorruptStorageTest`, `OutboxHttpFailureTest`, `OutboxMessageRetryTest`, `OutboxStaleStatusTest`,
`TripPassTest`, `TripPassDeletionTest`/`TripPassDeletionFailuresTest`, `TripPassRemovalSessionTest`,
`TripPassSaveRetryTest`, `LegacyOfflineOwnerTest`). Я прочитал файл целиком, прошёл руками через
КАЖДУЮ функцию и сверился с этим тест-сьютом — новых расхождений с задокументированным поведением
не нашёл. Четыре новые мутации (M1–M4) целят в правила, которые раньше не были защищены НАРОЧНОЙ
поломкой именно в этом месте (хотя и покрыты тестами по итоговому поведению).

**Методическая находка при проверке M4 (не баг продукта, а урок для тестов этого и соседних листов).**
Первая версия M4 (снять проверку `TripPassDeletion.blocks` в `load()`) ссылалась на
`TripPassDeletionTest` и реально прогонялась — поломка НЕ была поймана (`SURVIVED`, exit 0). Причина:
в сценариях этого класса `load()` всегда вызывается ПОСЛЕ свежего `TripPassStore.initStores(plain.restarted(), secure.restarted())`.
`MemoryDiskPreferences.restarted()` (`android/app/src/test/java/com/yuldash/app/data/MemoryDiskPreferences.kt`)
копирует только содержимое диска — флаги отказа (`failRemovalOf`/`failWriteOf`) в новый объект НЕ
переносятся. Поэтому на «рестарте» отложенная ранее миграция/реконсиляция (`TripPassDeletion.reconcile`,
вызывается изнутри каждого `initStores`) успешно ДОВОДИТСЯ до конца и физически стирает запись
ДО того, как тест вообще позовёт `load()` — значит, снятая в мутации проверка `blocks()` внутри `load()`
просто не успевает понадобиться: подстраховка этого теста работает на уровне `reconcile()`, а не
`load()`. Нашёл правильный тест (`OfflineMigrationCleanupTest::repeatedInitKeepsSnapshotReadableAndBlocksDeleteUntilRecovery`),
который зовёт `load()` СРАЗУ после `requestRemoval()` на ТЕХ ЖЕ (не рестартованных) объектах, где
отказ записи ещё активен и `reconcile()` гарантированно не успел зачистить secure, — на нём M4 поймана
(см. ниже). Итог: `load()`-проверка — не мёртвый код, а отдельный, самостоятельно нужный рубеж защиты
(иначе между неудавшейся физической зачисткой и следующим удачным `initStores` отображался бы чужой
телефон водителя), но доказывать это нужно тестом, где `reconcile()` гарантированно не успел отработать
первым.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | Протухший статус считается «свежим» всегда (`\|\| now - it.createdAt <= STATUS_MAX_AGE_MS` → `\|\| true`) | `com.yuldash.app.OutboxStaleStatusTest` | KILLED |
| M2 | Сообщения ТОЖЕ начинают протухать по возрасту (убрано исключение `it.kind == "message" \|\|`) | `com.yuldash.app.OutboxStaleStatusTest` | KILLED |
| M3 | Паспорт пишется под общим ключом `KEY_PREFIX` без `pass.bookingId` | `com.yuldash.app.TripPassTest` | KILLED |
| M4 | `load()` не проверяет `TripPassDeletion.blocks` | `com.yuldash.app.data.OfflineMigrationCleanupTest` | KILLED (первая попытка — `TripPassDeletionTest` — была SURVIVED, см. «Найденные ошибки») |

## Остаток и ограничения

- `Outbox.count`/`hasPending` не различают снаружи «очередь пуста» и «очередь повреждена» (оба дают
  0/`false`) — это ИЗВЕСТНОЕ, уже задокументированное в `architecture.md` ограничение прошлого раунда
  (QA-B01-019): видимое для пользователя восстановление/индикация повреждённого хранилища — отдельная,
  ещё не закрытая задача. Это НЕ утечка личных данных (наоборот — безопасный дефолт «считать пустым»,
  не «считать отправленным»), поэтому не завожу как новую ошибку, только фиксирую как есть.
- Реальный Keystore/`EncryptedSharedPreferences.create` (строки `init()`) не выполняется в Robolectric —
  общее для всего листа ограничение среды; логика самого переезда проверяется через `migratePlain`
  и фейковые хранилища (`OfflineMigrationWriteTest`).
- Поведение на реальном устройстве (process death ровно между коммитами, настоящий диск «кончилось
  место») не проверялось — я не запускал эмулятор (не требовалось по заданию; ⛔ не ставлю, так как
  это явно отмеченная и ранее задокументированная граница, а не новый открытый вопрос этого листа).
