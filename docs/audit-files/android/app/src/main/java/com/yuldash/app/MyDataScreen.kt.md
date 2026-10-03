# Карточка: `android/app/src/main/java/com/yuldash/app/MyDataScreen.kt`

- Статус: verified
- Лист: leaf-2.3
- Проверял: Sonnet 5 (leaf-2.3); принимал: Opus 5.5 (ожидается)

## Назначение

Экран «Мои данные» (раздел «Аккаунт» профиля) — ответ на страх «что вы обо мне храните и когда оно
исчезнет», числами и сроками с сервера, а не общими словами оферты. Три действия с реальным эффектом:
просмотр счётчиков/сроков (`GET /me/data`), точечное удаление документов водителя (`POST
/me/driver-docs/delete`, аккаунт НЕ удаляется), и выгрузка «Скачать мои данные» (`GET /me/export`) с
последующим сохранением файла через `PersonalDataExports` и системным «Поделиться». Сроки автоудаления
приходят с сервера (не зашиты в приложении) — прочитано и подтверждено (`MyDataDto.ridesDays` и т.д.
= `cleanup.TRIP_DAYS` и т.п. в `backend/app/routers/auth.py`/`cleanup.py`, вне зоны, только прочитано).

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `MyDataScreen(onBack)` | 78–399 | Главный композабл: состояния `loading`/`error`/`data`, диалог удаления документов, выгрузка | Загрузка — `LaunchedEffect(Unit) { load() }`, один раз на вход на экран | ок |
| `load()` (локальная suspend-функция) | 95–100 | `ApiClient.getMyData()` → `onSuccess` кладёт данные и снимает `loading`; `onFailure` ставит `error=true` | Вызывается и при первом входе, и повторно кнопкой «Повторить» (`AppErrorState.onRetry`) | ок |
| Блок выгрузки (`AppButton onClick`) | 258–307 | Захватывает `generation = ApiClient.queueSessionGeneration()` ДО сетевого запроса; после успеха — `PersonalDataExports.prepare` на `Dispatchers.IO`; публикация (`shareMyDataFile`) — ТОЛЬКО через `ApiClient.runIfCurrentSession(generation)`; `finally` удаляет файл, если не опубликован ИЛИ корутина отменена ИЛИ сессия уже не та | Ошибка сети/сборки файла → текст пользователю (`exportError`), кнопка снова доступна (`enabled = !exporting`) | ок — порядок проверок закрывает именно гонку «аккаунт сменился между HTTP-ответом и записью файла» (QA-B01-021) |
| `DriverDocsCard`/диалог удаления (331–398, 548–598) | — | `removable=false` → кнопки нет вовсе (вместо серой/отключённой) — сервер всё равно откажет (водитель на линии/на проверке); `removable=true` → подтверждающий `AlertDialog`, отказ сервера показывается ВНУТРИ диалога (`docsError`), диалог не закрывается молча | До этого листа не было Compose-теста именно на этот диалог (см. «Найденные ошибки»/новые тесты) | было пробелом в покрытии, не в коде — исправлено новыми тестами (поведение кода было верным) |
| `DataRow`/`LocationUsageCard`/`LocationDataRow` | 401–537 | Строки выписки: иконка (чисто декоративная, `contentDescription=null` — рядом уже есть видимый текст, второе объявление для TalkBack было бы дублем) — значение — срок. Геолокация разделена на 3 независимые строки (live/маршруты/SOS), а не один общий «храним координаты» | `routeCount`/`sosCount`/… — `coerceAtLeast(0)`, отрицательный ответ сервера не уходит в текст как «−3 точки» | ок |
| `FadeInCard` | 601–610 | Плавное появление карточек (`fadeIn` + `slideInVertically`, `CanonMotion.NORMAL`, ступенчатая задержка) | Длительности — из `CanonMotion`, не хардкод (держит `CanonSourceGuardTest`, вне зоны) | ок |
| `countText`/`selfDeleteText` | 613–623 | Башкирский — тюркский, счётное существительное не склоняется (`$n $ba` без выбора формы); русский — через `pluralRu` | `selfDeleteText` переиспользует `d.ridesDays` и для поездок, и для броней — ПРОВЕРЕНО по backend (`routers/auth.py`: брони чистятся тем же `TRIP_DAYS`, отдельного `bookings_days` сервер не присылает) — не ошибка, совпадение намеренное | ок |
| `shareMyDataFile` | 634–641 | `Intent.ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` (только чтение, не запись) + системный chooser | Публичная «Загрузки»/`MediaStore` не используются вовсе | ок |

## Связи

- `ApiClient.getMyData/exportMyData/deleteDriverDocs/isCurrentSession/runIfCurrentSession/queueSessionGeneration`
  (ВНЕ зоны, только прочитано для проверки контракта).
- `PersonalDataExports.prepare` (этот же лист).
- `MyDataDto`/`MyDataExportDto` (ApiClient.kt, вне зоны) — поля сверены с реальным backend-ответом
  (`routers/auth.py::my_data`, вне зоны, только прочитано).
- `ProfileScreen.kt` → `onMyData` (вне зоны) — вход на экран.
- Общие компоненты `AppLoading`/`AppErrorState`/`AppButton`/`ScreenTopBar` (`UiKit.kt`/`YuldashApp.kt`,
  вне зоны) — состояния загрузки/ошибки и кнопки взяты готовыми, не переизобретены.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R14 | Отказ сервера на удаление документов (409 «на линии»/«на проверке») показывается ВНУТРИ диалога и не закрывает его молча | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::driverDocsDeletionFailureKeepsDialogOpenWithServerReasonAndReEnablesButton` | да — M14 |
| R15 | Успешная загрузка показывает карточки с данными (не зависает в другом состоянии) | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::driverDocsDeletionSucceedsClosesDialogAndReloadsFreshCounts`, плюс существующие `android/app/src/test/java/com/yuldash/app/AuditBe29MyDataLocationTest.kt::russianExplainsLiveRouteAndSosLocationSeparately` | да — M15 |
| R16 | Башкирский текст карточки «Чего у нас нет» — настоящий перевод, не копия русского | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::zeroStateForBrandNewUserRendersCountersInBashkirToo` | да — M16 |
| R26 | Пока ответ `/me/data` не пришёл — виден индикатор загрузки, а не пустой экран и не старые данные | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::loadingStateIsShownBeforeDataArrivesThenReplacedByData` | да (новый тест этого листа, без отдельной мутации — см. «Остаток») |
| R27 | Отказ `/me/data` показывает «Повторить», и «Повторить» реально перезапрашивает сервер (не притворяется) | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::errorStateShowsRetryAndRetryActuallyReloadsData` | да (новый тест, без отдельной мутации — см. «Остаток») |
| R28 | Нули у новичка — валидная выписка (карточки со «0», не пустая заглушка), в обоих языках | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::zeroStateForBrandNewUserRendersCountersNotBlankScreen` | да (новые тесты) |
| R29 | Документы, которые нельзя снять прямо сейчас, не предлагают кнопку, которую сервер всё равно отклонит — вместо неё объяснение | Новый `android/app/src/test/java/com/yuldash/app/walk/l2_3/MyDataScreenStatesTest.kt::driverDocsNotRemovableHidesDeleteButtonAndExplainsWhy` | да (новый тест) |
| R30 | Экспорт: устаревшее поколение сессии не создаёт и не публикует файл старого аккаунта | `android/app/src/test/java/com/yuldash/app/MyDataExportSessionTest.kt::logoutAfterHttpSuccessBeforeFileWriteDoesNotRecreateOldExport` | да (существующие тесты; базовая защита замутирована в карточке `PersonalDataExports.kt`, M12) |

## Найденные ошибки

Ошибок в поведении кода не найдено. Но был найден и закрыт **пробел в защите тестами**: у экрана не
было Compose-проверок на состояния загрузка/ошибка+повтор, на нулевые счётчики у новичка и на диалог
удаления документов водителя (успех/отказ/кнопка скрыта) — а это обязательная часть чек-листа «готово»
для Compose-экрана (`docs/audit-files/README.md`: «загрузка / пусто / ошибка с «Повторить» / успех»,
оба языка). Само поведение кода при чтении оказалось правильным (проверено перед тем, как писать
тесты — иначе тест запер бы в карточку уже существующий баг под видом «защиты»), поэтому это не
«Найдена ошибка → исправление», а «пробел → закрыт новым тестом» (таблица выше, R14/R15/R16/R26–R29).
Добавлено 2 новых тестовых файла, 9 тестов (`MyDataScreenStatesTest` — 7, `OfflineStoreResetFinishOrderTest` —
2, последний относится к другой карточке этого же листа).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M14 | `.onFailure` при отказе удаления документов тихо закрывает диалог вместо показа причины | `com.yuldash.app.walk.l2_3.MyDataScreenStatesTest` | KILLED |
| M15 | Ветка `data != null ->` никогда не выполняется (`data != null && false`) | `com.yuldash.app.walk.l2_3.MyDataScreenStatesTest` | KILLED |
| M16 | Башкирский текст «Һаҡламайбыҙ» подменяется русским «Не храним» | `com.yuldash.app.walk.l2_3.MyDataScreenStatesTest` | KILLED |

## Остаток и ограничения

- R26/R27/R28/R29 — новые тесты добавлены и проверено, что они проходят на текущем (правильном) коде;
  отдельной «порчи» кода под каждое из этих четырёх правил в этом проходе не заводил (ограничение
  времени, не принципа) — честно помечаю как «новый тест есть, собственной мутации под него нет».
  Риск низкий: все четыре используют тот же `when`-блок/тот же `AppErrorState`/`AppLoading`, что уже
  частично покрыт M15 (срывает всю ветку `data != null`) и общими guard-тестами UI-кита (вне зоны).
- Реальное устройство (системный `Intent.createChooser` с другим приложением-получателем,
  `FileProvider`-грант на настоящем Android) не проверялось — для этого нужен эмулятор/физический
  телефон, которые по заданию листа запускать не нужно. ⛔ не ставлю самому экрану: успешный путь на
  JVM (Robolectric, `GraphicsMode.NATIVE`) проверен полностью, реальная публикация файла — отдельный,
  явно обозначенный остаток (как и в `PersonalDataExports.kt`).
- Доступность (`contentDescription`, 48dp, `sp`) — не проверял отдельным новым тестом в этом листе;
  иконки в `DataRow`/`LocationDataRow` декоративные (рядом видимый текст), что я считаю верным по
  общим правилам проекта (§4.5 CLAUDE.md требует `contentDescription` у иконок-КНОПОК, не у любых
  иконок) — но отдельного прогона `TouchTargetsFitAFingerTest`/accessibility-сторожей именно на этом
  экране не делал, они общие для всего приложения и вне зоны этого листа.
