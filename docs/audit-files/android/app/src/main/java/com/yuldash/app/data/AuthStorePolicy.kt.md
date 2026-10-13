# Карточка: `android/app/src/main/java/com/yuldash/app/data/AuthStorePolicy.kt`

- Статус: verified
- Лист: leaf-2.2
- Проверял: Sonnet 5 (leaf-2.2); принимал: Opus 5.5

## Назначение

Решает, какое из ДВУХ хранилищ сессии (обычные `SharedPreferences` «plain» или
`EncryptedSharedPreferences` «secure», когда Android Keystore доступен) сейчас действующее, и
переносит данные между ними без потери/подмены аккаунта при прерванной записи. Ключевая идея —
явная отметка `auth_store_state` в ОБЫЧНОМ (всегда доступном) ящике `yuldash`: `plain` / `secure` /
`pending` / `logout` / отсутствует. Вызывается из `ApiClient.init` (какое хранилище читать после
запуска) и `ApiClient.persistNewSession`/`logout` (куда и как писать новую сессию).

## Функции и разбор

| Функция | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `commit` (private) | 10–11 | `runCatching` вокруг `edit()+write+commit()` → `Boolean`, `false` при любом исключении | единая точка перехвата отказа записи | ок |
| `state`/`clear` (private) | 13–16 | `state`: пишет `STATE_KEY`. `clear`: стирает `SessionKeys.CLEARED_ON_LOGOUT` в данном ящике | оба идут через `commit` → не кидают исключений наружу | ок |
| `requireSaved` (private) | 17 | `check(ok) { ... }` — превращает `false` в `IllegalStateException`, ловится ТОЛЬКО на границе (`ApiClient.init`'s `runCatching`) | осознанный «fail loud внутри, fail safe снаружи» — см. R4 | ок |
| `read` (private) | 20–34 | Читает строковое поле; если значение `null`, но ключ `contains()` — отличает «обычный null» от «не-строка в EncryptedSharedPreferences» (кидает `InvalidSavedSession`); ловит `ClassCastException`/`SecurityException` (частичный отказ расшифровки) | единая точка «повреждённое значение» для ВСЕХ последующих проверок | ок |
| `resolve` | 36–50 | Публичная точка входа: выбирает источник, при `InvalidSavedSession` — принудительный logout (а не «тихий» null) | отказ самого logout (R4) пробрасывается дальше через `requireSaved` | ок |
| `resolveSource` (private) | 52–96 | Ветвление по марке `STATE_KEY`: `secure`/`plain`(миграция)/`logout`,`pending`(сброс)/`null`(унаследованный конфликт — 4 подслучая)/иное(неизвестная марка → сброс) | `null`-ветка — самая опасная: сверяет ОБА токена И оба refresh-токена; расхождение → полный logout (R1) | ок |
| `migrate` (private) | 98–109 | Переносит `CLEARED_ON_LOGOUT`-поля plain→secure: СНАЧАЛА читает всё, ПОТОМ пишет (битое значение не портит частично) | `secure == null` → просто помечает `plain` как источник (fallback уже применён раньше в `ApiClient.init`) | ок |
| `writeSession` | 111–117 | Запись новой сессии: `pending` → commit в целевой ящик → (если secure) очистить plain → финальная марка. Любой шаг не прошёл → `false`, марка остаётся `pending` | НЕ бросает исключений — возвращает `Boolean`, вызывающий (`ApiClient.persistNewSession`) сам решает, что показать пользователю (`SessionPersistenceException`) | ок |
| `logout` | 119–125 | Пишет марку `logout` ДО попытки стереть все доступные ящики (plain/secure/current), даже если сама марка не записалась — отказ марки не блокирует синхронную очистку того, что ещё пишется | `false`, если ЛЮБОЙ шаг (включая саму марку) не удался — но очистка всё равно выполнена по всем доступным ящикам | ок |

## Связи

`SessionKeys.CLEARED_ON_LOGOUT` (список полей сессии). Вызывается `ApiClient.init` (resolve),
`ApiClient.persistNewSession`/`commitAuth` (writeSession), `ApiClient.logout`/`clearLocalSession`
(logout). Косвенно — весь экран входа: если `resolve`/`writeSession` вернут «нет сессии»/`false`,
`LoginScreen.kt` получает `SessionPersistenceException` и показывает `errSaveLogin` (см. карточку
`LoginScreen.kt.md`).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Унаследованный конфликт (разные токены ИЛИ разные refresh-токены в plain/secure одновременно) → полный logout обеих копий, ни одна не побеждает молча | `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::ambiguousLegacyAccountsFailClosed`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::sameAccessButDifferentRefreshIsAlsoAmbiguous`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::identicalLegacyTokensKeepSecureIdentity` (контрольный случай — одинаковые токены НЕ считаются конфликтом) | да — M9, KILLED |
| R2 | Марка `secure` никогда не позволяет устаревшему `plain` взять верх, даже если secure временно недоступен | `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::secureMarkerNeverRollsBackToStalePlain`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::missingSecureDoesNotReadStalePlain` | да — существующий тест |
| R3 | Повреждённое значение (неверный тип в EncryptedSharedPreferences, отказ расшифровки одного поля) требует НОВОГО входа, а не крэша и не «тихого» null | `android/app/src/test/java/com/yuldash/app/data/AuthStorageCorruptionTest.kt::corruptSecureRoleNeverPublishesHalfAnAccount`, `android/app/src/test/java/com/yuldash/app/data/AuthStorageCorruptionTest.kt::unreadableEncryptedEntryIsRemovedWithoutRestoringPlainAccount` (не в зоне этого листа, но покрывает `read`/`InvalidSavedSession`) | да — существующий тест |
| R4 | **Отказ записи не создаёт полу-состояние**: `writeSession` либо доводит ВСЕ 3 коммита до конца, либо марка остаётся `pending` и следующий `resolve` трактует это как «перенос не завершён» → сброс | `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::writePersistsPendingBeforeTouchingTokens`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::failedWriteLeavesPendingAndRestartClearsOldSession`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::failedPendingMarkerDoesNotTouchSecureSession`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::failedTokenCommitLeavesPending`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::failedFinalMarkerLeavesPendingAndCannotRestoreCommittedSession` (5 тестов — отказ на КАЖДОМ из 3 шагов) | да — M10, KILLED |
| R5 | `logout()` стирает все ДОСТУПНЫЕ ящики синхронно, даже если сама отметка `logout` не записалась (частичный, но реальный disk failure не должен оставить токен читаемым) | `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::logoutWhileSecureUnavailableSurvivesRecovery`, `android/app/src/test/java/com/yuldash/app/data/AuthStorePolicyTest.kt::logoutKeepsDeviceSettings` | да — существующий тест |

## Найденные ошибки

Ошибок не найдено. 16 существующих тестов (`AuthStorePolicyTest`) + `AuthStorageCorruptionTest`
покрывают отказ на каждом шаге миграции/записи/логаута; прогнаны на моей копии — зелёные.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | `resolveSource`: условие конфликта `plainToken != secureToken \|\| read(plain, "refresh_token") != read(existingSecure, "refresh_token")` → `false` (конфликт никогда не определяется — побеждает secure молча, даже когда токены разные) | `AuthStorePolicyTest.ambiguousLegacyAccountsFailClosed` | KILLED (см. отчёт листа) |
| M10 | `writeSession`: `if (!commit(selected.prefs, write)) return false` → `commit(selected.prefs, write)` (отказ записи токена больше не останавливает — марка финализируется, будто запись удалась) | `AuthStorePolicyTest.failedTokenCommitLeavesPending` | KILLED (см. отчёт листа) |

## Остаток и ограничения

- R2/R3/R5 защищены существующими тестами (`AuthStorePolicyTest`/`AuthStorageCorruptionTest`, вне
  зоны OWNS этого листа), не перемутированы заново — приоритет отдан R1/R4: это два правила, где
  молчаливый отказ буквально означает «чужой аккаунт остался», соответственно самые дорогие по
  цене ошибки среди всех пяти.
- Прогнаны ВСЕ 16 тестов `AuthStorePolicyTest` + `AuthStorageCorruptionTest` на моей копии при
  самопроверке (раздел 5 отчёта) — все зелёные после моих правок в `LoginScreen.kt` (этот файл я
  не менял).
