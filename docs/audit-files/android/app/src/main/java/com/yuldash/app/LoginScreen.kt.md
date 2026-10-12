# Карточка: `android/app/src/main/java/com/yuldash/app/LoginScreen.kt`

- Статус: verified
- Лист: leaf-2.2
- Проверял: Sonnet 5 (leaf-2.2); принимал: Opus 5.5

## Назначение

Экран входа: выбор Telegram (рабочий, основной) или SMS (заморожен на проде, форма жива за
build-флагом `BuildConfig.SMS_LOGIN_ENABLED`), ввод 6-значного кода, все ошибки входа, согласие
18+/оферта, геро-баннер с переключателем языка. Разрезан на «умную» обёртку `LoginFormCard`
(состояние + сеть + Telegram-intent) и чистый рендер `LoginFormContent`/`LoginSmsSection`
(тестируются на JVM без сети). 1330 строк — большая часть файла (≈850 строк) это геро-баннер,
фичи-витрина, переключатель языка и согласие: декоративный/юридический слой вокруг небольшого
ядра состояния на 170 строк (`LoginFormCard`).

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `TelegramLoginBot` | 273–276 | Имя Telegram-бота; тест подставляет своё через `testName`, минуя `BuildConfig` (пусто в CI без `local.properties`) | | ок |
| `isLoginPhoneValid` | 283 | Телефон «валиден» ⇔ после `trim()` ≥ 5 символов | НЕ проверяет формат (+7/8, цифры) — это осознанно сохранённое прежнее поведение; реальная нормализация — на сервере (`normalize_phone`). SMS-форма скрыта в проде по умолчанию (флаг `false`) | ок, как было |
| `isLoginCodeValid` | 286 | Код «валиден» ⇔ после `trim()` ровно 6 символов | ввод и так отфильтрован цифрами (см. ниже) | ок |
| `sanitizeLoginCodeInput` **(НОВОЕ)** | 297 | Фильтр поля кода: оставляет цифры, режет до 6 | вынесено из инлайна в `onCodeChange`, чтобы было чем протестировать вставку (п. «Фокус» задания: «код — вставка, только цифры») | ок, тест новый |
| `loginRequestErrorText` **(НОВОЕ)** | 308–309 | Текст ошибки сети: если `Throwable` — `ApiException` (сервер ответил), берёт его `.message` (уже двуязычный, собран `ApiClient.errorMessage`); иначе — свой `fallback` | чинит Б1 (см. «Найденные ошибки») | ок, тест новый |
| `LoginScreen` | 331–367 | Верхний экран: `Surface`+`Column` со скроллом, геро (`BrandHero`) внахлёст с карточкой формы (`LoginFormCard`), анимация въезда карточки | | ок |
| `LoginFormCard` (state holder, private) | 396–567 | Держит `step/phone/code/name/loading/error/tgMode/tgRequestId/needPhone/freshCodeRequired` (`rememberSaveable` — переживает выгрузку процесса при уходе в Telegram), строит колбэки, зовёт `ApiClient` | см. разбор колбэков ниже | см. «Найденные ошибки» |
| ⤷ `onPhoneChange`/`onCodeChange`/`onNameChange` | 454–456 | Обновляют поля; `onCodeChange` теперь через `sanitizeLoginCodeInput` | `onNameChange` режет до 120 символов | ок |
| ⤷ `onTelegramStart` | 458–477 | Гард `if (!loading)`; `tgStart()` → открывает `t.me/<bot>?start=<id>`; отказ → `errTgStart` (не менял — см. «Остаток») | `TelegramLoginBot.name.isBlank()` → `tgSoon` без сетевого вызова | ок |
| ⤷ `onTgVerify` | 478–511 | Гард `if (!loading)` + `freshCodeRequired` + `isLoginCodeValid`; `tgVerify()`; ошибка — явная карта статусов 403/409/410/429/400, `else` — фиксированный `errVerify` (НЕ трогал — защищён существующим тестом, см. «Найденные ошибки») | `SessionPersistenceException` — отдельная ветка (`errSaveLogin`, блокирует повтор использованного кода до нового запроса) | ок |
| ⤷ `onTelegramOpen` | 512–529 | `needPhone` → чат без `?start` (поделиться номером); иначе — новая сессия (новый `request_id`+код), не переоткрывает старый | отказ → `errTgStart` (не менял) | ок |
| ⤷ `onBackFromTg`/`onToggleSmsForm`/`onChangePhone` | 530–532 | Сброс локального состояния шага | | ок |
| ⤷ `onSmsPrimary` | 534–565 | Шаг 0: `isLoginPhoneValid`→`requestCode()`, отказ → **теперь** `loginRequestErrorText` (было: всегда `errSendFail`). Шаг 1: `isLoginCodeValid`→`verifyCode()`, отказ — `SessionPersistenceException`/400/`else` (`else` не трогал) | | исправлено (Б1) |
| `LoginFormContent` | 575–801 | Чистый рендер формы (оба потока), `internal` → тестируется напрямую | кнопки disabled при `loading` (гард двойного тапа на UI-уровне) | ок |
| `LoginErrorBanner` | 803–850 | Баннер ошибки: текст + «Нет интернета или код не пришёл?» + «Повторить»; `shownText` держит последний непустой текст, чтобы анимация выхода не схлопывалась пустой | `internal`, анимация появления/исчезновения | ок |
| `LoginLoadingHint` | 852–869 | Подпись «Открываем Telegram…»/«Проверяем код…» на время запроса | | ок |
| `LoginSmsSection` | 871–986 | Замороженная SMS-форма (видна за флагом в проде; в unit-тестах флаг всегда `false`, поэтому функция `internal` и тестируется НАПРЯМУЮ, минуя флаг) | тумблер, шаг телефона/кода, «Изменить номер», primary-кнопка disabled при `loading` | ок |
| `LoginDivider` | 988–1014 | Разделитель «или» | | ок |
| `BrandHero` | 1016–1141 | Геро-фото, градиенты, бренд-текст, фичи-витрина, лого, переключатель языка — три независимые анимации въезда (фото/бренд/фичи) | `contentDescription` лого — двуязычный; фото — декоративное (`null`, текст рядом несёт смысл) | ок |
| `LoginHeroFeatures`/`LoginHeroFeature` | 1142–1189 | Три пункта доверия (без пароля / без спама / данные защищены) | иконки декоративные (`contentDescription=null`) — заголовок рядом дублирует смысл | ок |
| `LoginLangToggle`/`LoginLangChip` | 1190–1235 | Переключатель РУС/БАШ, тач-цель `defaultMinSize(64.dp, 48.dp)`, `role=RadioButton`+`selected` | `onClickLabel` через `appText(...)` — читает `LocalAppLanguage`, который `YuldashApp.kt:1011` провайдит актуальным языком на весь экран (проверено — НЕ дефолт `Ru`) | ок |
| `SafetyFooter` | 1236–1266 | Подпись «Безопасность поездок — наш приоритет» + «Юлдаш заботится о тебе», bilingual, Canon* | **не вызывается НИГДЕ в продакшен-коде** — см. «Остаток и ограничения» | ⚠ подозрение, не ошибка |
| `LoginConsent` | 1267–1330 | Согласие 18+ в ОДНОЙ строке с офертой (не чекбокс) + ссылки «Условия»/«Политика» на `yulbash.ru`, тач-цель ссылок ≥48dp по вертикальному паддингу | ссылки открывают `Intent.ACTION_VIEW` через `runCatching` (отсутствие браузера не роняет экран) | ок |

## Связи

`ApiClient.{tgStart,tgVerify,requestCode,verifyCode,fireUpdateName}` (сеть/сессия — карточка вне
этого листа), `AuthStorePolicy`/`SessionKeys` (куда ложится токен при успехе — см. их карточки),
`YuldashApp.kt` (`onContinue` → `destinationAfterLogin`, вне зоны), `AppText.kt`
(`appText`/`appTextFor`/`LocalAppLanguage`), `CanonTokens.kt` (все цвета/кегли/отступы/длительности
— `CanonSourceGuardTest` держит зелёным), `BuildConfig.{SMS_LOGIN_ENABLED,TELEGRAM_BOT}`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Телефон/код — базовая непустая валидация перед отправкой (helpers) | `android/app/src/test/java/com/yuldash/app/LoginFormContentTest.kt::phoneValidation_emptyShortAndBlank_areInvalid_andRealPhoneValid`, `android/app/src/test/java/com/yuldash/app/LoginFormContentTest.kt::codeValidation_emptyShortAndFiveDigits_areInvalid_andSixDigitsValid` | да — существующий |
| R2 | **Вставка в поле кода — только цифры, максимум 6** (SMS-автозаполнение/вставка целой фразы) | `android/app/src/test/java/com/yuldash/app/walk/l2_2/LoginScreenPureHelpersTest.kt::sanitizeLoginCodeInput_realisticSmsAutofillText` (НОВЫЙ, + 4 соседних метода того же класса) | да — M1, KILLED |
| R3 | Двойное нажатие «Войти»/«Войти через Telegram»/SMS-кнопки не шлёт второй запрос, пока первый не завершился (кнопка `disabled` при `loading`) | `android/app/src/test/java/com/yuldash/app/LoginFormContentTest.kt::telegramButton_whenLoading_isDisabled_guardsDoubleTap`, `android/app/src/test/java/com/yuldash/app/LoginFormContentTest.kt::tgVerifyButton_whenLoading_isDisabled_guardsDoubleTap`, `android/app/src/test/java/com/yuldash/app/LoginDeep2ContentTest.kt::smsPrimary_whenLoading_isDisabled_guardsDoubleTap` | да — M2, KILLED |
| R4 | Каждый статус 400/403/409/410/429 на шаге Telegram-кода получает СВОЙ понятный двуязычный текст и путь дальше (не общий «ошибка») | `android/app/src/test/java/com/yuldash/app/LoginDeepContentTest.kt::error_expiredCode_isShownToUser_ru`, `android/app/src/test/java/com/yuldash/app/LoginDeepContentTest.kt::error_tooManyAttempts_isShownToUser_ru`, `android/app/src/test/java/com/yuldash/app/LoginDeepContentTest.kt::error_codeNotYet_isShownToUser_ru`, `android/app/src/test/java/com/yuldash/app/LoginFailureRecoveryTest.kt::actualWrongCodeRetainsSpecificWarningAndDoesNotLogIn` (реальный HTTP) | да — существующий |
| R5 | Транзиентная ошибка верификации (503 и подобные БЕЗ готового bilingual-текста от сервера) не выдаёт себя за «неверный код» — честное «не удалось проверить, попробуй снова», тот же код можно повторить | `android/app/src/test/java/com/yuldash/app/LoginFailureRecoveryTest.kt::temporaryServerFailureCanRetrySameCodeWithoutFalseInvalidCodeWarning` (реальный MockWebServer) | да — существующий |
| R6 | **Сервер мог прислать готовый, более полезный ответ (например 503 «SMS временно не работает — зайди через мессенджер»), и его нельзя подменять обобщённым «проверь интернет»** | `android/app/src/test/java/com/yuldash/app/walk/l2_2/LoginScreenPureHelpersTest.kt::loginRequestErrorText_usesServerMessage_whenApiExceptionCarriesOne` (НОВЫЙ, + 2 соседних метода того же класса) | да — M3, KILLED |
| R7 | Отказ сохранить сессию локально (`SessionPersistenceException`) не пускает в приложение и блокирует повтор УЖЕ использованного кода до нового запроса | `android/app/src/test/java/com/yuldash/app/LoginFailureRecoveryTest.kt::localPersistenceFailureExplainsFreshCodeAndRecoversWithNewTelegramRequest` (реальный HTTP+диск) | да — существующий |
| R8 | После успешного входа `onContinue()` зовётся ровно один раз, ни разу — при отказе | `android/app/src/test/java/com/yuldash/app/LoginFailureRecoveryTest.kt::temporaryServerFailureCanRetrySameCodeWithoutFalseInvalidCodeWarning` (считает `continuations`, + 2 соседних метода того же класса) | да — существующий |
| R9 | Два языка на каждом видимом тексте экрана (заголовки, ошибки, баннер «нужен номер», согласие, ссылки) | `android/app/src/test/java/com/yuldash/app/LoginDeepContentTest.kt::header_bashkir_showsTitleAndSubtitle`, `android/app/src/test/java/com/yuldash/app/LoginDeep2ContentTest.kt::toggle_bashkir_showsPhoneLoginButton`, `android/app/src/test/java/com/yuldash/app/LoginScreenContentTest.kt::heroFeatures_bashkir_showsAllThreeTitles` (+ десятки соседних пар RU/BA по всем 4 тестовым файлам экрана) | да — существующий |

Правило «переключатель языка доступен для TalkBack на актуальном языке» (структурно подтверждено
чтением кода — `LocalAppLanguage` провайдится в `YuldashApp.kt:1011`) в эту таблицу не включаю:
отдельного теста на `onClickLabel` нет, см. «Остаток и ограничения».

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| Б1 | Пассажир переключает приложение на вход по номеру телефона (флаг `SMS_LOGIN_ENABLED` включат для QA/переходного периода) и жмёт «Получить код». Сервер честно отвечает 503 с готовым двуязычным текстом «Вход по SMS временно не работает. Зайди через мессенджер 💚» (`routers/auth.py`/`services.send_sms`, канал заморожен на проде — так и есть сейчас). Экран ПОКАЗЫВАЕТ СОВСЕМ ДРУГОЕ: «Не получилось отправить код. Проверь интернет и повтори.» — человек с полным интернетом послушно проверяет связь и жмёт «Повторить» СНОВА И СНОВА, каждый раз получая тот же 503, и никогда не узнаёт, что нужно просто открыть Telegram. Тот же провал — и на шаге ввода SMS-кода (`onSmsPrimary`, шаг 1, ветка `else`), если частично выданный код всё ещё где-то существует. | Юнит: `requestCode()` падает с `ApiException(503, "Вход по SMS временно не работает...")`; экран до правки показывал жёстко `errSendFail`, игнорируя `e.message`, хотя `ApiClient.errorMessage()` уже собрал готовый двуязычный текст из `detail={ru,ba}` (та же конструкция `herr()`, что и у 429 «Слишком часто»). | `onSmsPrimary`'s шаг 0 `onFailure`: `error = errSendFail` → `error = loginRequestErrorText(it, errSendFail)` — предпочесть `(e as? ApiException)?.message`, свой текст — только когда ответа от сервера не было вовсе (это уже правильный совет). Тот же паттерн используется в ~50 других экранах проекта (`(it as? ApiException)?.message ?: fallback`) — этот файл был единственным исключением для данной ветки. | `walk/l2_2/LoginScreenPureHelpersTest.loginRequestErrorText_usesServerMessage_whenApiExceptionCarriesOne` — до правки такой функции не существовало (код просто не компилировался бы без неё, функция написана вместе с исправлением); после — зелёный. Мутация M3 подтверждает: откат на «только fallback» = KILLED. |

**Шаг 1 (verify) НЕ исправлен специально** — см. «Остаток»: там генерический `else`-текст защищён
существующим тестом `LoginFailureRecoveryTest.temporaryServerFailureCanRetrySameCodeWithoutFalseInvalidCodeWarning`
по ДРУГОЙ, тоже правильной причине (не показывать «сырой» английский текст инфраструктурного сбоя
как «неверный код»), и это НЕ тот же случай: тест написан для Telegram-ветки, но по аналогии
риск-профиль SMS-verify идентичен (оба проходят через `herr()`, бизнес-отказы ВСЕГДА `{ru,ba}`,
но генерик-500 может прийти голой строкой). Трогать защищённую тестом ветку без согласования с
ведущим не стал — см. «ВНЕ ЗОНЫ».

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `sanitizeLoginCodeInput`: `raw.filter { it.isDigit() }.take(6)` → `raw.take(6)` (буквы больше не отфильтровываются) | `walk/l2_2/LoginScreenPureHelpersTest.sanitizeLoginCodeInput_keepsOnlyDigits` | KILLED (см. отчёт листа) |
| M2 | `LoginFormContent`: кнопка «Войти через Telegram» — `enabled = !loading` → `enabled = true` (не блокируется во время запроса) | `LoginFormContentTest.telegramButton_whenLoading_isDisabled_guardsDoubleTap` | KILLED (см. отчёт листа) |
| M3 | `loginRequestErrorText`: `(e as? ApiException)?.message ?: fallback` → `fallback` (серверный текст больше не используется никогда) | `walk/l2_2/LoginScreenPureHelpersTest.loginRequestErrorText_usesServerMessage_whenApiExceptionCarriesOne` | KILLED (см. отчёт листа) |

## ВНЕ ЗОНЫ

- **`onTgVerify`'s `else` и `onSmsPrimary`'s шаг-1 `else`** (строки ~507 и ~562): та же логика, что
  в Б1 (сервер мог прислать более полезный текст для неожиданного кода ответа), но с риском,
  которого НЕТ у `requestCode`: `LoginFailureRecoveryTest` намеренно фиксирует для
  Telegram-verify, что «сырой» текст инфраструктурного сбоя (в тесте — англ. `"temporarily
  unavailable"`, имитация голого, не-`herr()`-ответа) НЕ должен попадать на экран как есть —
  вместо него правильный, уже выбранный разработчиками текст «Не удалось проверить код. Попробуй
  ещё раз.». Реальный бэкенд при НЕОЖИДАННОМ 5xx тоже возвращает безопасный текст (глобальный
  `unhandled_exception_handler` в `middleware.py` отдаёт `{"detail": "Внутренняя ошибка сервера"}`,
  не сырой стек), так что риск на проде ниже, чем в тестовом сценарии — но менять защищённую
  тестом ветку БЕЗ согласования с ведущим не стал (файл теста вне моего OWNS, а переписывать чужой
  пройденный тест — грубое нарушение правил листа). Если ведущий согласится, что риск одинаков:
  предлагаемая правка — та же `loginRequestErrorText(e, errVerify)` в обеих `else`-ветках;
  проверить тестом «реальный 500 с `{"detail":"Внутренняя ошибка сервера"}` (как отдаёт
  `middleware.py`) всё ещё показывает её, а НЕ \"temporarily unavailable\"-подобный сырой текст».
- **`onTelegramStart`/`onTelegramOpen`'s `onFailure`** (строки 476, 528): тоже жёсткий `errTgStart`
  без `loginRequestErrorText`. НЕ исправил: `POST /auth/tg/start` (`routers/auth.py:552`) не имеет
  НИ ОДНОГО `herr()`-отказа (только создание строки в БД) — единственный реалистичный отказ здесь
  это инфраструктурный (сеть/5xx/общий rate-limit), для которого текущий текст «Не получилось
  связаться с сервером. Проверь интернет и повтори.» и так корректен. Правка дала бы нулевую
  пользу при том же риске показать сырой текст, поэтому не стал трогать без находки.

## Остаток и ограничения

- Accessibility переключателя языка подтверждена ЧТЕНИЕМ кода (провайдер `LocalAppLanguage`
  в `YuldashApp.kt`, `defaultMinSize` в `LoginLangChip`), но не отдельным новым Robolectric-тестом
  с `SemanticsActions`/`onClickLabel` — не успел поставить его надёжно без риска написать
  тест-пустышку, который прошёл бы и до, и после гипотетической поломки.
- `SafetyFooter` (строки 1236–1266) — рабочий, двуязычный, Canon-совместимый композабл, но НЕ
  вызывается НИ ИЗ `LoginScreen`, НИ откуда-либо ещё в продакшен-коде (проверено `grep` по всему
  `src/main`); вызывается только из теста `LoginScreenContentTest`. Верхний комментарий файла
  (строка 3) перечисляет его как часть экрана («LoginScreen + BrandHero/TrustCard/LoginFormCard/
  SafetyFooter») — `TrustCard` в файле тоже не существует. Похоже на след более раннего дизайна,
  осиротевший при разрезке `MainActivity.kt`. НЕ стал самостоятельно включать подпись на экран —
  это видимое изменение продукта (новый блок текста на самом чувствительном экране приложения),
  а не исправление ошибки; передаю на решение: это сознательно убранный текст или забытый кусок
  доверия, который стоит вернуть?
- Полный `:app:testDebugUnitTest` прогонялся один раз (см. отчёт листа, раздел 5) — этот файл
  менял поведение (Б1), так что это не только «добавил тесты».
- Сервер/боевой `yulbash.ru` не вызывались (правило листа).
