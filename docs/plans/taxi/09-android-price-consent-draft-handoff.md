# ЧЕРНОВИК. НЕ ПРОВЕРЕНО НА ЭМУЛЯТОРЕ. НЕ МЕРЖИТЬ / НЕ ВКЛЮЧАТЬ

Публикация разрешена только для передачи следующему разработчику. Это не приёмка выпуска, не разрешение включать режим пользователям и не подтверждение Android runtime. Предыдущий запрет публикации checkpoint заменён только разрешением этой draft-передачи; ограничения проверки и включения сохраняются.

Точный исходный checkpoint: `5aec07aea2549ef289f9aaf26e0dae040710ee08`, единственный parent `2ae7e0ec5c1329bde30cf5dfa8ed7a9c45159804`, tree `8b6a1d15d96bf319aa155b2bceba461b903ca8cf`. Четырнадцать исходных файлов сохранены без изменений. Эта заметка — отдельный docs-only коммит поверх checkpoint. PWA, TF10 и новые серверные ветки не подмешаны; будущая интеграция с актуальным main требует отдельной проверки.

## Что действительно проверено до передачи

Эти проверки выполнены ранее на точном checkpoint; при публикации они не запускались заново.

| Проверка | Фактический результат |
|---|---|
| `:app:assembleDebug`, обычный default OFF | BUILD SUCCESSFUL, exit0 |
| `:app:assembleDebug`, локальный opt-in ON | BUILD SUCCESSFUL, exit0 |
| Целевые JVM/state/HTTP: TaxiQuoteConsentTest, TaxiConsentControllerTest, TaxiConsentSavedStateTest, TaxiConsentTransportTest | 29 run, 0 failures, 0 ignored, exit0 |
| Автономные исходниковые Guard-классы | 43 класса, 146 run, 0 failures, exit0; это не полный Gradle Guard |
| Штатный Gradle `:app:testDebugUnitTest` с Guard и целевыми тестами, offline | exit1 **до выполнения тестов**: нет официального cached `org.jacoco:org.jacoco.agent:0.8.12` |

Целевой HTTP — настоящий ApiClient/OkHttp → синтетический MockWebServer на JVM. Это не APK → настоящий backend/SQL/dispatch. Проверены счётчики confirmed POST и отсутствие автоматического refresh/repost при401, явный exact-body retry после неизвестного ответа и срока, варианты UTC/Asia-Yekaterinburg, копейки/скидка450 при600−150 и контроль600, входы/опции и state. Android SharedPreferences в части HTTP-контроля предоставлены memory seam; реальное Android-хранилище этим не проверено.

Кроме JaCoCo недоступны официальные Robolectric `android-all-instrumented:14-robolectric-10818077-i7` и JAR `nativeruntime-dist-compat:1.0.16`. Известные403 не повторять и не обходить; не отключать coverage/Guard ради зелёного статуса.

APK не устанавливались и не запускались. Нет доказательства Compose UI, настоящего Activity recreation/process death/force-stop, deep sleep/elapsedRealtime, RU/BA × тем, фокуса/крупного шрифта/screen reader, реального сохранения маркера, двух Android-инстанций с API, waiting-card/driver-left/replay SQL/dispatch и legacy/scheduled клиентского E2E. В использованной среде отсутствовали KVM, emulator и system images. Сборка и JVM не заменяют эти проверки. Автоматический CI от обычного push также не является эмуляторной приёмкой.

## Режим выключен по умолчанию

Обычный debug и release — OFF. Только изолированная DEBUG QA-конфигурация с `YULDASH_DEBUG_API_BASE_URL=http://127.0.0.1:8000`, `YULDASH_NATIVE_QA_PINNED_API=true`, `YULDASH_TAXI_QUOTE_CONSENT_LOCAL=true`. Никаких реальных пользователей, заказов, платежей, ключей провайдеров или production API.

Этот Android checkpoint не подключает key-only lookup. Его точная серверная база `2ae7e0ec` такого endpoint не содержит; отдельные более поздние сервер/PWA пакеты сюда не включены. После перезапуска он не восстанавливает создающий POST из маркера. Истечение,401/404/пустой список не доказывают «заказ не создан». Даже после получения недостающих зависимостей включение пользователям остаётся отдельным решением.

## Следующий безопасный прогон

1. Использовать только доверенный авторизованный стенд с официальным JDK21/SDK, доступным KVM/emulator/system image и недостающими официальными test artifacts. Подтвердить `javac -version` и `jdk.compiler`; один Gradle за раз. Не менять системные/сетевые разрешения и не использовать компьютер пользователя.
2. На точном исходнике запустить штатные assembleDebug, compileDebugAndroidTestKotlin, testDebugUnitTest с Guard и релевантными экранными тестами без отключения JaCoCo. Сохранить реальные exit codes. Обе прежние assembleDebug — не сборка инструментальных тестов.
3. Поднять отдельный backend точно `2ae7e0ec` с новыми временными синтетическими БД/Redis, mock SMS/платежами/маршрутом/погодой; не наследовать DATABASE_URL или секреты. API только собственный loopback127.0.0.1:8000; внешний трафик deny до первого входа. Проверить INTERNET в фактических app/test APK. Секретные конфиги не читать/копировать.
4. Только собственные emulator/ADB server, уникальный socket/port и явный serial. Если используется adb reverse — лишь между своим изолированным emulator и своим API. Сначала измерить свежий успешный авторизованный quote-запрос, затем проверять ноль создающих POST; отсутствие связи не является успехом.
5. Проверить RU/BA × светлая/тёмная,450/600 в первом и повторном подтверждении, доступные имена/фокус/48dp/крупный шрифт/Back. Все10 options, класс, A/Б, порядок остановок, round-trip/wait, поздний ответ другого ввода или входа. Controlled test time вместо системных часов; expiry/resume только обновляет цену и ждёт нового действия; настоящий deep sleep отдельно.
6. На реальном синтетическом API проверить две UI-инстанции,409 price_pending без create/очистки прежнего ожидания, driver-left после полной цены с показом фактического ID/статуса/суммы. После SQL commit потерять ответ и дождаться expiry: явный retry теми же байтами/UUID только в живом прежнем входе; из другой DB-сессии один заказ и один подбор, тот же ID после terminal.401 с доступным refresh не вызывает скрытый повтор; после UNKNOWN любой отказ сохраняет неопределённость.
7. Отдельно Activity recreation и process death/force-stop→restart, реальное Android-хранилище без тела/координат/контактов/токенов/отпечатков и без автоматического POST. Logout/newlogin тем же/другим пользователем и смена API не возобновляют старое тело или callback. Проверить legacy/scheduled при OFF и scheduled при локальномON. Сохранить счётчики API, SQL, source SHA/tree, exit codes и получить отдельную независимую runtime-приёмку.

До такого прогона: **НЕ МЕРЖИТЬ / НЕ ВКЛЮЧАТЬ**. Деплой, ручной Actions и запуск эмулятора не входят в эту черновую передачу.
