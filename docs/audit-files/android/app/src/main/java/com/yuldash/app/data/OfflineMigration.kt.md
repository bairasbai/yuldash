# Карточка: `android/app/src/main/java/com/yuldash/app/data/OfflineMigration.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Redo-журнал «открытое → шифрованное» для разового переноса данных, которые когда-то (Keystore не
поднялся) легли в обычные `SharedPreferences`, а теперь шифрование доступно. Используется и
паспортом поездки (ключи `pass_*`), и очередью исходящих (ключ `queue`). Комментарий файла — сразу
предупреждение: «no mutations are allowed between preparing the snapshot and retiring its source» —
между снимком и удалением исходника ничего больше не должно меняться, иначе можно либо потерять
записи, либо продублировать их. Журнал (`offline_migration_v1`, JSON `{version, data}`) — защита
именно от обрыва МЕЖДУ копированием и удалением: если процесс убьют после записи в secure, но до
стирания plain, при следующем запуске журнал говорит «вот что уже скопировано», и удаление исходника
довершается, не копируя повторно.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `Selection.read(key)` | 20 | Если хранилище нечитаемо (`readable=false`) — всегда `null`; иначе сначала смотрит в снимок (`snapshot`, уже в памяти), и только если там нет — читает физически через `readOfflineString` | `recoveryScope` — ВСЕГДА стабильный `plain` (не временная secure-обёртка), иначе параллельный откат записи после отказа мог бы примениться не к тому объекту | ок |
| `quarantine(plain)` / `resetUnconfirmed(plain)` | 23–24 | Карантин — чисто в памяти (`WeakHashMap`), переживает отказ `OfflineStoreReset.clear`'а записать `PENDING` на диск | Синхронизировано на уровне всего объекта (`@Synchronized`) | ок |
| `resetCommitted(plain)` | 27–30 | Снимает И незавершённый снимок (`pending`), И карантин — зовётся `OfflineStoreReset.clear` ТОЛЬКО после подтверждённого durable-стирания plain | — | ок |
| `open(plain, secure, accepts)` | 32–78 | Главная машина состояний (разбор по шагам ниже) | `@Synchronized` — весь метод атомарен относительно других вызовов `open`/`quarantine` | см. ниже |

### `open()` по шагам (строки 32–78)

1. **Карантин** (39): если `plain` в карантине (идёт/не подтверждён выход) — вернуть `Selection(plain, writable=false, readable=false)`. Ни читать, ни писать нельзя.
2. **Незакрытый откат** (41): `recoverOfflineWrites(plain, secure)==false` → вернуть `writable=false` без снимка — миграция не имеет права трогать данные, пока неясно, что реально на диске.
3. **Есть журнал, но ещё нет снимка в памяти** (45–54): прочитать JSON, проверить `version==1`, проверить, что КАЖДЫЙ ключ проходит фильтр `accepts` (т.е. журнал не подсовывает чужой ключ), положить в `pending[plain]`.
4. **Secure недоступен** (55): остаться в `plain`, `writable = (snapshot == null)` — т.е. если журнал/снимок уже есть (миграция начата, но не закончена) — `false` (не писать поверх незакрытой миграции); если миграции никогда не было — `true` (обычный plain-режим).
5. **Снимка всё ещё нет, журнала не было** (56–62): это ПЕРВЫЙ заход миграции — собрать снимок из `plain.all.filterKeys(accepts)`; если пусто — просто переехать на `secure` (нечего копировать, `writable=true`).
6. **Собственно перенос** (63–73): записать журнал в `plain` → скопировать значения в `secure` → (`copied=true`) → удалить ключи+журнал из `plain`. Каждый шаг при отказе `commit()` возвращает `Selection` с `writable=false` и ТЕКУЩИМ состоянием (plain или secure — смотря что уже успело пройти), сохраняя снимок для следующей попытки.
7. **catch(Exception)** (74–77): любое исключение (битый JSON, `require` не прошёл, неверный тип значения) — трактуется как «неопределённость», НЕ как «можно переписать»: хранилище = `secure`, если копирование уже подтвердилось (`copied`), иначе `plain`; `writable=false` всегда; `readable = snapshot != null` (если снимок удалось построить раньше — хотя бы читать можно).

## Связи

- `TripPassStore.initStores`/`migratePlain`, `Outbox.initStores`/`clearAll` — оба вызывают `open` с
  разными `accepts` (`it.startsWith("pass_")` и `it == "queue"` соответственно).
- `OfflineWrites.kt` — `recoverOfflineWrites`/`readOfflineString` (этот же лист).
- `TripPassDeletion.reconcile` (ВНЕ зоны, только прочитан) — читает `migrationFinished` (= `writable`)
  из результата `open`, чтобы не чистить физическую копию, пока снимок ещё не ретирован.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R5 | Перенос в `secure` не может считаться завершённым (`copied=true`), если сама запись в `secure` не подтверждена commit'ом — иначе следующий шаг безопасно удалит исходник из `plain`, и данные исчезнут из ОБОИХ хранилищ | `android/app/src/test/java/com/yuldash/app/data/OfflineMigrationCleanupTest.kt::failedPreparationDoesNotCopyAndFailedSecureWriteCanResume` | да — M5 |
| R6 | Повреждённый/нечитаемый журнал (`JSONException`, `require` не прошёл) обязан вернуть `writable=false` — новые данные нельзя писать, пока неясно, что на самом деле случилось со старым снимком | `android/app/src/test/java/com/yuldash/app/data/OfflineMigrationCleanupTest.kt::malformedJournalBlocksMutationsAndDoesNotOverwriteSecure` | да — M6 |
| R17 | Снимок строится ТОЛЬКО из ключей, прошедших `accepts` — очередь не может случайно утащить в себя паспорт поездки и наоборот (общий журнал/карантин — один на объект `OfflineMigration`, разные `plain`-инстансы у `TripPassStore` и `Outbox`) | `android/app/src/test/java/com/yuldash/app/data/OfflineMigrationWriteTest.kt::failedQueueCopyPreservesSourceAndDefersNewWrites` (оба сценария — паспорт и очередь — проверены параллельно, не смешиваются) | да (существующий тест) |
| R18 | Незакрытый откат записи (`OfflineWriteRecovery`) блокирует ЛЮБОЕ движение миграции, а не только конкретный ключ | новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/OfflineMigrationGuardsTest.kt::unresolvedRollbackOfAnUnrelatedKeyBlocksSavingAnyBooking` (ключ с незакрытым откатом НЕ связан с бронью, которую пытаются сохранить, — показывает, что блокируется весь `open()`, а не точечный ключ) | да — M19 |
| R19 | Повторный запуск миграции безопасен (идемпотентен): если журнал уже скопирован, но исходник не стёрт — повтор `open()` довершает удаление, не копируя повторно и не теряя записи | `android/app/src/test/java/com/yuldash/app/data/OfflineMigrationCleanupTest.kt::repeatedInitKeepsSnapshotReadableAndBlocksDeleteUntilRecovery` | да (существующий тест, многократно вызывает `initStores` подряд) |

## Найденные ошибки

Ошибок не найдено. Машина состояний `open()` была уже предметом отдельного прошлого раунда
(`audit-offline-migration-journal-2026-09-20.md`, `audit-offline-migration-open-cases-2026-09-20.md`).
Я прочитал файл целиком, прошёл руками через каждую ветку (включая взаимодействие с
`TripPassDeletion.reconcile` и `OfflineStoreReset`) и не нашёл новых расхождений с комментариями
в коде — логика соответствует описанному поведению. По итогам независимого ревью R18 из
«подтверждено косвенно» стал полноценной строкой с M19 (новый тест ловит ключ, НЕ относящийся
к сохраняемой брони, чтобы доказать, что блокируется весь `open()`, а не точечный ключ).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M5 | Перенос в secure помечается `copied=true` БЕЗ проверки, что commit записи в secure реально прошёл | `com.yuldash.app.data.OfflineMigrationCleanupTest` | KILLED |
| M6 | Поломанный журнал в catch-ветке получает `writable=true` вместо `false` | `com.yuldash.app.data.OfflineMigrationCleanupTest` | KILLED |
| M19 | Снята проверка `if (!recoverOfflineWrites(...)) return select(...)` целиком | `com.yuldash.app.walk.l2_3.OfflineMigrationGuardsTest` | KILLED |

## Остаток и ограничения

- Холодный обрыв ровно между двумя `commit()` внутри одного вызова `open()` (строки 66–70) — защищён
  атомарностью `@Synchronized`, но реальный `process death` между этими двумя системными вызовами
  (не между вызовами функции) не воспроизводился и невоспроизводим на JVM-уровне; это общая для всего
  листа граница, указанная и в прежнем аудите.
