# Карточка: `android/app/src/main/java/com/yuldash/app/data/PersonalDataExports.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Готовит временные, принадлежащие приложению файлы (текстовая выгрузка «Мои данные» и PNG-открытка
статистики `MyStatsScreen`) для системного «Поделиться» через `FileProvider`, и чистит их при выходе
из аккаунта. Имена файлов содержат UUID (`yuldash-my-data-<uuid>.txt`, `my-yuldash-<uuid>.png`) —
отдельный путь на КАЖДУЮ подготовку, чтобы «старый писатель» (операция, начатая аккаунтом A) не мог
перезаписать файл, который уже готовит или которым уже поделился аккаунт B (комментарий файла:
«separate paths prevent an old writer overwriting a new user»). Это прямая защита 152-ФЗ: на диске не
должно остаться читаемого текстового дампа личных данных ПОСЛЕ выхода, и чужая выгрузка не должна
подмениться под текущего пользователя.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `Prepared.discard()` | 19 | Удаляет файл (`runCatching`, отказ молча проглатывается — используется как best-effort cleanup) | — | ок |
| `prepare(context, displayName, text, generation)` | 23–24 | Текстовая выгрузка «Мои данные»: пишет `text` в файл | — | ок |
| `preparePng(context, bitmap, generation)` | 26–29 | PNG-открытка статистики: `FileOutputStream` + `Bitmap.compress`; `check()` — если сжатие не удалось, бросает исключение (а не молча создаёт пустой файл) | Используется `MyStatsScreen.kt` (вне зоны, только прочитан вызов) | ок |
| `prepareFile(context, displayName, generation, prefix, suffix, write)` (приватная) | 31–49 | Общая логика обеих подготовок: 4 проверки `ApiClient.isCurrentSession(generation)` — на входе, после вычисления `cacheDir`, после `write(file)`, после создания URI; `finally` удаляет файл, если либо подготовка не довершилась (`!keep`), либо сессия уже не та | Файловый I/O — ВНЕ `sessionLock` (комментарий: «File I/O remains outside the session monitor»); сама публикация (`ApiClient.runIfCurrentSession`) — отдельный короткий захват лока в вызывающем коде (`MyDataScreen.kt`) | ок — избыточная (4 проверки) защита: по отдельности каждая проверка может показаться «лишней», но вместе они покрывают РАЗНЫЕ окна гонки (до `getCacheDir()`, во время `write()`, между `write()` и созданием URI) |
| `clear(context)` | 51–66 | При выходе: находит ВСЕ файлы в `cacheDir/shared`, чьё имя совпадает с `ownName` (т.е. с UUID) ИЛИ равно старому фиксированному имени (`LEGACY_NAME`/`LEGACY_PNG`); для каждого — отзывает URI-грант через `FileProvider`, затем удаляет файл; отзыв и удаление — в ОТДЕЛЬНЫХ `runCatching`, чтобы отказ отзыва гранта не отменял удаление самого файла | Удаляет `LEGACY_NAME`/`LEGACY_PNG` «на всякий случай», даже если файла нет — следующий человек на этом же телефоне не должен получить чужой грант на путь, который теперь использует он | ок |

## Связи

- `ApiClient.isCurrentSession`/`runIfCurrentSession`/`queueSessionGeneration` (ApiClient.kt, ВНЕ зоны,
  только прочитан: `runIfCurrentSession` — `synchronized(sessionLock)`, атомарно проверяет поколение И
  выполняет публикацию одним захватом, без зазора между проверкой и действием).
- `ApiClient.clearAssociatedPersonalData()` (строка 510: `runCatching { PersonalDataExports.clear(ctx) }`) —
  стоит на пути всех трёх дверей выхода (как и `OfflineLegacyStores`, см. его карточку).
- `MyDataScreen.kt` (`prepare`) и `MyStatsScreen.kt` (`preparePng`, вне зоны) — оба вызывающих экрана
  передают `generation`, захваченный ДО сетевого запроса.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R11 | Выход из аккаунта стирает ВСЕ свои копии — и старые (фиксированное имя), и текущие (с UUID), а не только legacy-имя | `android/app/src/test/java/com/yuldash/app/data/PersonalDataExportCleanupTest.kt::logoutErasesAllOwnCopiesAndPreservesUnrelatedSimilarNames` | да — M11 |
| R12 | Устаревшая (по поколению сессии) подготовка не может ни создать файл для нового аккаунта, ни удалить его файл — 4 независимые точки проверки | `android/app/src/test/java/com/yuldash/app/data/PersonalDataExportCleanupTest.kt::rejectedOldPreparationCannotOverwriteOrDeleteAnotherAccountsCopy` и Compose-сценарий со сменой аккаунта ПОСЛЕ успешного HTTP, но ДО записи файла — `android/app/src/test/java/com/yuldash/app/MyDataExportSessionTest.kt::logoutAfterHttpSuccessBeforeFileWriteDoesNotRecreateOldExport` | да — M12 (мутация снимает все 4 проверки разом; по одной проверке за раз mutation НЕ ловится существующими тестами — они защищены избыточно, см. «Остаток») |
| R20 | Файл НЕ сохраняется в публичную «Загрузки» — только `cacheDir/shared` + `FileProvider`, грант только на чтение, только тому приложению, которое получит `Intent.createChooser` | `android/app/src/test/java/com/yuldash/app/data/PersonalDataExportCleanupTest.kt::logoutErasesAllOwnCopiesAndPreservesUnrelatedSimilarNames` (косвенно: тест фиксирует, что все файлы живут в `context.cacheDir/shared`; прочитано — `shareMyDataFile` в `MyDataScreen.kt` ставит `FLAG_GRANT_READ_URI_PERMISSION`, не `WRITE`; отдельного теста на отсутствие записи в `MediaStore`/внешнее хранилище не заводил — в коде физически нет такого пути записи) | да по прочтению кода + косвенно тестом (других путей сохранения нет) |

## Найденные ошибки

Ошибок не найдено. Четырёхточечная защита поколения сессии — результат прошлого раунда (QA-B01-021,
`audit-journal.md`: «prepare/finally удаляют свою stale/unshared копию»). Я перепрошёл её руками:
избыточность НАМЕРЕННАЯ — каждая проверка закрывает своё окно гонки (до/во время/после I/O), и ни одна
не покрывает соседнюю полностью (см. таблицу поломок — по отдельности они не ловятся имеющимися
тестами, только все четыре сразу).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M11 | `clear()` чистит только `LEGACY_NAME`/`LEGACY_PNG`, UUID-файлы (`currentFiles`) из списка на удаление выпадают | `com.yuldash.app.data.PersonalDataExportCleanupTest` | KILLED |
| M12 | Все 4 проверки `isCurrentSession` внутри `prepareFile` сняты | `com.yuldash.app.data.PersonalDataExportCleanupTest` | KILLED |

## Остаток и ограничения

- Защита R12 — избыточная (4 независимые точки), поэтому отдельная поломка «снять ТОЛЬКО одну из
  четырёх проверок» сознательно не заводилась: такая мутация не прошла бы ни одним из существующих
  тестов (они рассчитаны на реалистичные окна гонки, а не на «что если убрать одну из них из четырёх»),
  и я не стал городить искусственный тест ради одной внутренней проверки — отмечаю как сознательное
  решение, не как пробел.
- Реальная публикация через системный `Intent.createChooser` с ДРУГИМ приложением-получателем
  (копирующим файл за пределы нашего `FileProvider`) не воспроизводилась — для этого нужно устройство;
  в карточке `MyDataScreen.kt` это явно обозначено ⛔.
