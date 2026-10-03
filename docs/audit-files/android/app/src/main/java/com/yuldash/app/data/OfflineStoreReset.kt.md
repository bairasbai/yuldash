# Карточка: `android/app/src/main/java/com/yuldash/app/data/OfflineStoreReset.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Общий, durable-протокол «стереть офлайн-хранилище при выходе», которым пользуются и паспорт поездки
(`TripPassStore`), и очередь исходящих (`Outbox`), и legacy-хранилища (`OfflineLegacyStores`). Главная
сложность — Keystore (secure-хранилище) на некоторых прошивках бывает временно недоступен именно в
момент выхода из аккаунта: нужно стереть то, что доступно СЕЙЧАС (plain), и честно запомнить
«secure ещё должен быть стёрт», а не считать уборку законченной. Отметка `secure_clear_pending`
(`PENDING`) — этот долг; `incomplete` (`WeakHashMap`, в памяти процесса) — защита от ситуации, когда
сам commit этой отметки на диск не прошёл (комментарий в коде: «A failed commit can remove the flag
from memory while it remains on disk»).

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `finish(plain, secure)` (приватная) | 12–22 | Пытается закрыть долг: сначала физически стирает `secure` (`secure.edit().clear().commit()`), и ТОЛЬКО если это получилось — снимает `PENDING` с `plain`. Отказ снять `PENDING` после успешного стирания secure пытается восстановить флаг (best-effort) и тоже возвращает `false` | `incomplete[plain]=true` ставится ДО попытки — переживает отказ commit | ок; порядок «сначала стереть secure, потом снять флаг» — именно то, что не даёт объявить уборку законченной раньше времени |
| `availableSecure(plain, secure)` | 24–28 | Возвращает `secure`, только если на нём НЕТ незавершённого долга по очистке (или долг только что успешно закрыт через `finish`); иначе `null` — вызывающий код (`TripPassStore`/`Outbox.initStores`) просто не получает secure и работает в plain-режиме, пока долг не погашен | `secure==null` → `null` сразу. `pending` — ИЛИ в памяти (`incomplete`), ИЛИ на диске (`PENDING`, с дефолтом `true` при ошибке чтения — отказ чтения трактуется как «долг есть», не «долга нет») | ок |
| `clear(plain, secure)` | 30–38 | Полный цикл выхода: 1) `OfflineMigration.quarantine(plain)` — карантин ставится ДО попытки физической очистки; 2) `plain.edit().clear().putBoolean(PENDING,true).commit()` — стирает ВСЁ plain одним commit и сразу ставит флаг долга; при отказе — `return false`, карантин остаётся; 3) `discardOfflineWriteRecovery(plain)` — сбрасывает незавершённые RAM-откаты записи; 4) `OfflineMigration.resetCommitted` + `TripPassDeletion.resetCommitted` — снимают карантин/маркеры удаления ТОЛЬКО теперь, когда plain реально стёрт; 5) если `secure!=null` — пробует `finish` | Возврат — `true`, только если и plain стёрт, и (`secure==null` ИЛИ `finish` успешен) | ок |

## Связи

- `OfflineMigration.quarantine/resetCommitted` (тот же лист) — не даёт `TripPassStore.save`/`Outbox.enqueue`
  писать, пока неясно, точно ли стёрт предыдущий аккаунт.
- `TripPassDeletion.resetCommitted` (`data/TripPassDeletion.kt`, ВНЕ зоны — только прочитан) — отметки
  «паспорт N удалён» привязаны к конкретному аккаунту и не должны пережить logout как «удалённые брони»
  нового человека.
- `discardOfflineWriteRecovery` (`OfflineWrites.kt`, этот же лист).
- Вызывается из `TripPassStore.initStores`/`clearAll`, `Outbox.initStores`/`clearAll`,
  `OfflineLegacyStores.clearStores` — все карточки этого листа.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R7 | Отказ физической очистки **plain** не может доложить об успехе и обязан оставить карантин (иначе `save()`/`load()` снова доверяют хранилищу, которое не стёрто) | `android/app/src/test/java/com/yuldash/app/data/TripPassSaveRetryTest.kt::saveRetryCannotReleaseUnconfirmedLogoutQuarantine` и новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/OfflineStoreResetFinishOrderTest.kt::clearFailureOnPlainNeverReportsSuccessOrLiftsQuarantine` | да — M7 |
| R8 | Метка `secure_clear_pending` не может сняться раньше, чем **secure** реально стёрт — иначе данные предыдущего аккаунта остаются физически на диске, а приложение считает уборку законченной | новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/OfflineStoreResetFinishOrderTest.kt::secureClearFailureKeepsPendingFlagSoNextAttemptRetries` | да — M8 |
| R8b | Отказ чтения флага `PENDING` трактуется как «долг есть» (дефолт `true`), а не «долга нет» | `android/app/src/test/java/com/yuldash/app/walk/l2_3/OfflineStoreResetFinishOrderTest.kt::secureClearFailureKeepsPendingFlagSoNextAttemptRetries` (косвенно; `availableSecure`'s `runCatching {...}.getOrDefault(true)` — отдельной новой поломки именно на этот дефолт не заводил) | не перепроверял поломкой в этом листе |
| R8c | Долг может быть погашен только для ТОЙ ЖЕ пары `plain`/`secure`, что и значился в карантине; `clear()` ставит карантин ДО попытки стереть, чтобы отказ ранней стадии не давал ложного «чисто» | `android/app/src/test/java/com/yuldash/app/data/TripPassDeletionFailuresTest.kt::logoutDuringPendingMigrationAndDeletionPreservesNewAccountWhenSecureReturns` | да (существующий тест; см. также карточку `TripPass.kt`) |

## Найденные ошибки

Ошибок не найдено. Логика `finish`/`availableSecure`/`clear` — корректная и была уже проверена
косвенно (через `TripPassStore`/`Outbox`) в прежних раундах (QA-B01-015/020, `audit-offline-migration-journal-2026-09-20.md`).
В этом листе добавлены ДВЕ новые прямые проверки (`OfflineStoreResetFinishOrderTest`), подтвердившие,
что `finish()` действительно соблюдает порядок «стереть → снять флаг», а `clear()` действительно не
докладывает успех при отказе commit — раньше это не было протестировано НАПРЯМУЮ (только как побочный
эффект сценариев `TripPassStore`).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M7 | `clear()` игнорирует отказ commit очистки plain (`if (!...) return false` → безусловный вызов без проверки результата) | `com.yuldash.app.data.TripPassSaveRetryTest` | KILLED |
| M8 | `finish()` игнорирует отказ физической очистки secure и всё равно пытается снять `PENDING` | `com.yuldash.app.walk.l2_3.OfflineStoreResetFinishOrderTest` | KILLED |

(Оба подтверждены прогоном `audit_mutation.py replay --spec docs/audit-mutations/leaf-2.3.json` в этой копии.)

## Остаток и ограничения

- Холодный физический сбой диска МЕЖДУ `finish()`'s двумя commit (secure стёрт, но процесс убит до
  commit снятия `PENDING`) — не воспроизводился реальным process death/реальным диском; защищён только
  тем, что при следующем запуске `PENDING` всё ещё `true`, и `finish` просто повторит (идемпотентно,
  `secure.edit().clear()` на уже пустом secure — безопасно). Логически корректно, но не проверено на
  устройстве.
- Не проверял реальный Keystore/EncryptedSharedPreferences (ограничение Robolectric, общее для всего
  листа — см. карточку `TripPassTest`/`OutboxTest`).
