# Карточка: `android/app/src/main/java/com/yuldash/app/data/OfflineLegacyStores.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Файл существует только ради одного обещания 152-ФЗ: на телефоне никогда не должны остаться
читаемые данные под именами хранилищ **v1** (`yuldash_trippass` / `yuldash_outbox` и их secure-пары),
которые были актуальными до перехода на v2 (`_v2`-суффикс, волна QA-B01-010). Это НЕ путь обновления
старых данных — переноса из v1 в v2 нет и не будет (комментарий в коде: «Old stores have no owner.
Never import them into the active v2 stores», поскольку у формата v1 нет поля-владельца, и неизвестно,
какому именно человеку эти записи принадлежали). Единственная операция — durable-стирание по той же
схеме, что у рабочих v2-хранилищ (`OfflineStoreReset`), чтобы отказ очистки не делал старые данные
читаемыми молча.

Вызывается из `ApiClient.clearAssociatedPersonalData()` (`ApiClient.kt:501`) — одной из дверей общей
очистки сессии (`clearLocalSession`), которую используют выход, удаление аккаунта и «сессия истекла».

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `clear(context)` | 10–26 | Для каждого из двух legacy-имён (`yuldash_trippass`, `yuldash_outbox`) открывает plain-хранилище и пытается поднять его secure-пару (`<имя>_secure`, тот же `MasterKey`/`EncryptedSharedPreferences`, что у v2), затем зовёт `clearStores`. Keystore недоступен → `secure=null`, очистка идёт только по plain | Обёрнуто `runCatching` на уровне КАЖДОГО имени — отказ по `yuldash_trippass` не прерывает попытку для `yuldash_outbox`. Секретов/ключей не печатает | ок |
| `clearStores(plain, secure)` | 29–30 | Тонкая обёртка: вся логика стирания — в `OfflineStoreReset.clear`, тот же durable-протокол (quarantine → физический `clear()` → снятие `secure_clear_pending`), что у рабочих v2-хранилищ | `internal`, вызывается напрямую из тестов и из `clear(context)` | ок |

## Связи

- Вызывает: `OfflineStoreReset.clear` (данные этого же листа, `OfflineStoreReset.kt`).
- Вызывается из: `ApiClient.clearAssociatedPersonalData()` → `ApiClient.clearLocalSession()` →
  три двери (`logout`, `deleteAccount`, истечение сессии через `YuldashApp.endSession`). Правка ApiClient.kt
  вне зоны — только прочитано для проверки, что вызов стоит на пути ВСЕХ трёх дверей (подтверждено,
  `clearAssociatedPersonalData` — общая функция, не завязана на конкретную дверь).
- Имена v1 (`yuldash_trippass` / `yuldash_outbox`) зеркалят рабочие v2-константы
  (`TripPassStore.PREF_PLAIN="yuldash_trippass_v2"`, `Outbox.PREF="yuldash_outbox_v2"`) минус суффикс —
  согласованность имён проверена чтением обоих файлов, разночтений нет.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R13 | Очистка legacy-хранилища обязана реально дойти до диска, а не просто доложить об успехе | `android/app/src/test/java/com/yuldash/app/data/LegacyOfflineOwnerTest.kt::legacyCleanupHandlesUnavailableSecureThenRecovery` | да — M13 (новая мутация этого листа) |
| R13b | Валидная сессия НЕ видит «ничейные» legacy-записи (старый паспорт/очередь без владельца не подмешиваются в текущий аккаунт) | `android/app/src/test/java/com/yuldash/app/data/LegacyOfflineOwnerTest.kt::validSessionMustNotExposeUnattributedLegacyOfflineData` | да — уже проходит на текущем коде (проверено прогоном в этом листе); отдельной новой мутации на этот конкретный аспект не заводил, полагаюсь на существующий тест-сьют прежнего аудита (QA-B01-010) |
| R13c | Отказ очистки (plain/secure недоступны) не делает старые данные снова читаемыми после рестарта | `android/app/src/test/java/com/yuldash/app/data/LegacyOfflineOwnerTest.kt::failedLegacyCleanupCannotReExposeDataAfterRestart` | да (существующий тест; отдельно не мутировал) |

## Найденные ошибки

Ошибок не найдено. Логика — тонкая обёртка над уже проверенным в этом же листе `OfflineStoreReset.clear`
(см. его карточку); дублирования или рассинхронизации имён v1/v2 не обнаружено.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M13 | `clearStores` всегда возвращает `true`, не вызывая реальную очистку (`OfflineStoreReset.clear(plain, secure)` → `true`) | `com.yuldash.app.data.LegacyOfflineOwnerTest` | KILLED (см. `test-results` прогона `audit_mutation.py replay` этого листа) |

## Остаток и ограничения

- Реальное поднятие `EncryptedSharedPreferences` для legacy secure-имён (строки 14–22) не выполняется в
  Robolectric (нет настоящего Keystore) — как и у рабочих v2-хранилищ, это ограничение среды, не этого
  файла; логика стирания (`clearStores`) тестируется напрямую инъекцией fake-хранилищ.
- Не проверял реальное устройство — не требовалось (локальные хранилища, не сеть/эмулятор-специфика).
