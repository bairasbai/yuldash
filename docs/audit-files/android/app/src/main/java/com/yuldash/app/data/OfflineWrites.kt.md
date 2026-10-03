# Карточка: `android/app/src/main/java/com/yuldash/app/data/OfflineWrites.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Общий механизм «подтверждённой» записи одного ключа в `SharedPreferences`, которым пользуются и
паспорт поездки, и очередь исходящих, и (через снимок миграции) переезд plain→secure. Главная идея
вынесена в комментарий файла: «A successful return means the write completed, not merely that apply
was scheduled» — то есть везде используется `commit()` (синхронный, с результатом), а не `apply()`
(асинхронный, без результата). Вторая идея — откат: если `commit()` вернул `false`, код обязан помнить
прежнее значение ключа и не позволять читать «наполовину записанное» состояние, пока откат либо не
подтверждён на диске, либо явно не признан невозможным.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `commitOfflineString(prefs, key, value, scope=prefs)` | 7–9 | Публичная обёртка записи одного ключа | `scope` — стабильный «якорь» на тот случай, когда `prefs` — временная secure-обёртка поверх стабильного plain (миграция); по умолчанию `scope=prefs` | ок |
| `readOfflineString(prefs, scope, key)` | 11–12 | Публичная обёртка чтения с учётом незавершённого отката | — | ок |
| `recoverOfflineWrites(plain, secure)` | 14–15 | Пытается завершить незакрытый откат для ЭТОЙ пары хранилищ; `false` — означает «состояние всё ещё неподтверждено, миграция/отправка должны подождать» | Зовётся на входе в `OfflineMigration.open` и в начале `Outbox.flush` | ок |
| `discardOfflineWriteRecovery(plain, keys=null)` | 18–19 | Снимает отслеживание отката — ТОЛЬКО после подтверждённого durable-стирания (logout) или физического удаления конкретных ключей (`TripPassDeletion.reconcile`) | Комментарий явно предупреждает: «never merely on reinitialization» | ок |
| `OfflineWriteRecovery.restore(scope, prefs)` (приватная) | 30–43 | Пытается дважды (`repeat(2)`) записать прежние значения обратно; при успехе снимает `pending[scope]` | `entry.encrypted != (prefs !== scope)` — защита от применения чужого отката не к тому хранилищу (plain-откат не может случайно попасть в secure и наоборот) | ок |
| `OfflineWriteRecovery.commit(scope, prefs, key, value)` | 45–54 | 1) сначала пытается закрыть ЛЮБОЙ висящий откат для `scope`; 2) читает текущее значение `key` (это и есть «прежнее» на случай отката); 3) пишет новое; 4) при отказе — запоминает `Pending(encrypted, {key: previous})` и сразу пробует откатить | Если шаг 1 (`restore`) не удался — весь `commit` возвращает `false`, НЕ пытаясь писать новое значение поверх неподтверждённого старого состояния | ок |
| `OfflineWriteRecovery.read(scope, prefs, key)` | 56–61 | Если для `key` есть незакрытый откат — отдаёт запомненное «прежнее» значение (в т.ч. `null`, если ключа раньше не было); иначе — читает диск напрямую | Комментарий подчёркивает: `containsKey` обязателен — подтверждённое отсутствие (`null`) не должно провалиться в чтение «грязной» памяти | ок |
| `OfflineWriteRecovery.recover(plain, secure)` | 63–67 | Если для `plain` есть незакрытый откат — определяет цель (`secure`, если откат относится к зашифрованному хранилищу, иначе `plain`) и пытается `restore` | `entry.encrypted && secure==null` → `false` (секьюр-откат невозможен без секьюр-хранилища) | ок |
| `OfflineWriteRecovery.discard(plain, keys)` | 69–75 | Снимает ИЗ ЗАПОМНЕННОГО отката только указанные ключи (или весь откат, если `keys==null`) | Используется `TripPassDeletion.reconcile` точечно — удалённые брони не должны блокировать откат для ДРУГИХ ключей | ок |

## Связи

- `TripPassStore.save`/`load` и `Outbox.writeAll`/`readAll` — все пишут/читают ТОЛЬКО через
  `commitOfflineString`/`readOfflineString` (кроме самого журнала миграции в `OfflineMigration.kt`,
  который пишет напрямую — см. его карточку, отдельное обоснование).
- `OfflineMigration.open` зовёт `recoverOfflineWrites` ПЕРВЫМ делом — незакрытый откат блокирует
  миграцию целиком (не просто один ключ).
- `TripPassDeletion.reconcile` зовёт `discardOfflineWriteRecovery(plain, deletedKeys)` точечно.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R9 | Неудачная запись обязана запомнить прежнее значение ключа для отката — иначе следующее чтение может вернуть «грязное» (наполовину записанное) значение | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/OfflineWritesRollbackTest.kt::failedWriteRestoresThePreviousValueNotJustAbsence` | да — M9 |
| R10 | `recoverOfflineWrites`/`recover()` обязаны честно докладывать об отказе, пока откат реально не подтверждён на диске — ложный «успех» разрешил бы писать/отправлять поверх неподтверждённого состояния | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/OfflineWritesRollbackTest.kt::whenRollbackAlsoFailsRecoveryHonestlyReportsItUntilItReallySucceeds` | да — M10 |
| R11w | Подтверждённое отсутствие ключа (`null` в откате) не проваливается в чтение диска — `containsKey`, а не просто truthy-проверка значения | `android/app/src/test/java/com/yuldash/app/data/TripPassSaveRetryTest.kt::explicitRetryResumesMigrationAndSurvivesDiskRestart` (косвенно, через `TripPassStore`); отдельной точечной поломки именно этой строки в этом листе не заводил | не перепроверял поломкой в этом листе (см. «Остаток») |
| R12w | Откат одного хранилища (plain) не может случайно примениться не к тому физическому хранилищу (secure vs plain) — проверка `entry.encrypted != (prefs !== scope)` | `android/app/src/test/java/com/yuldash/app/data/OfflineMigrationWriteTest.kt::failedPassportCopyPreservesSourceAndDefersNewWrites` | да (существующий тест; не мутировал отдельно) |

## Найденные ошибки

Ошибок не найдено. Механизм отката уже прошёл независимую проверку в прошлом раунде (QA-B01-020,
`audit-journal.md`: «Two limited recovery attempts не заменяют проверку результата commit»). В этом
листе добавлены ДВЕ прямые unit-проверки самого `OfflineWriteRecovery` (минуя `TripPassStore`/`Outbox`),
которых раньше не было — раньше контракт проверялся только косвенно.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | `commit()` перестаёт запоминать откат при неудачной записи (`if (!saved) {...}` удалён) | `com.yuldash.app.walk.l2_3.OfflineWritesRollbackTest` | KILLED |
| M10 | `recover()` всегда возвращает `true`, даже если сам откат не прошёл | `com.yuldash.app.walk.l2_3.OfflineWritesRollbackTest` | KILLED |

## Остаток и ограничения

- Правило R11w (`containsKey` вместо truthy) не получило СОБСТВЕННОЙ нарочной поломки в этом листе —
  воспроизвести именно эту строку как самостоятельный тест не успел (мешает побочный эффект фейковой
  `MemoryDiskPreferences`: любая попытка «испортить» эту строку в моих экспериментах либо не компилировалась,
  либо ловилась ДРУГИМИ, более ранними проверками раньше, чем доходило до этой строки). Правило верно
  по прочтению кода и комментария автора; честно помечаю как непроверенное поломкой.
- Холодный `process death` между `pending[scope] = Pending(...)` (в памяти) и следующим запуском —
  открытый случай, как и в карточке `OfflineStoreReset.kt`: это НЕ durable-журнал, а RAM-защита
  текущего процесса (сказано в комментарии файла явно).
