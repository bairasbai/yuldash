# Журнал воспроизведения CI и переносимости стенда — 01.11.2026

Это первичные доказательства отдельного локального этапа. Текущий статус находится в
[едином реестре](../../audit-blocks.md#audit-ci-e2e-20261101).

## Версии

- База main: `1b02947b56aa279f7721f475d26c0dd8018cda75`.
- Ветка: `audit/e2e-portable-20261101`.
- Исправление стенда: `e5fa7b22ece429f5e0a429441e4e2167fd30998a`.
- Исправление тестов: `1e5fbfe4cb6c41420d5da0036980ba3fa37817b6`.
- Java Temurin 21.0.13+9, проектные Gradle 8.13 / Android API 36,
  Robolectric API 34; Node 24.22.1, Playwright 1.64.1.

## Настоящий CI и локальное воспроизведение

[Исходный запуск](https://github.com/bairasbai/yuldash/actions/runs/41198117093) завершился
с двумя падениями FindRideButtonTest. Остальные пять задач прошли. Скачанный ZIP отчёта
android-unit-test-report, artifact 13041484568, проверен по SHA-256:
`1b9b628f3b722a6ea5e7b2c181f0f07ddf00c0499a7215d36e5cfa0b01be234b`.
Здесь сохранён текст только относящегося к этому тесту HTML.

На точной исходной базе:

```sh
cd android
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace \
  --tests 'com.yuldash.app.FindRideButtonTest'
```

Из двух тестов один упал после ожидания 15 секунд; весь набор занял 24,694 секунды.
Сохранённый XML показывает начальный запрос
активной поездки Уфа → Белорецк, затем запрос без городов. Популярная карточка загружалась
независимо, а исходный тест принимал первый запрос списка за готовность выбора.

После исправления та же команда выполнила 3 теста без падений. Добавленный тест удерживает
HTTP-ответ популярного маршрута; раннее и позднее нажатия дают:

```text
CONTROLLED_EARLY_CLICK=[GET /rides/near?verified_only=true&limit=5]
CONTROLLED_READY_CLICK=[GET /rides/near?from_city=Баймаҡ&to_city=Сибай&verified_only=true&limit=5]
```

Затем выполнен обязательный общий гейт изменённой Android-части этой ветки:

```sh
./gradlew :app:assembleDebug :app:compileDebugAndroidTestKotlin \
  :app:testDebugUnitTest --no-daemon --stacktrace \
  --tests '*GuardTest' --tests 'com.yuldash.app.FindRideButtonTest'
```

BUILD SUCCESSFUL, 142 теста, 0 падений. Повтор целевых трёх тестов включён в 142,
не прибавляется повторно. Полный набор Android/удалённый CI здесь не запускался.

## Чистая проверка стенда

Драйвер и manifest скопированы в отдельный временный checkout без node_modules.
Использован отдельный изначально пустой npm-кеш:

```sh
npm --prefix tools/rideshare_e2e ci --ignore-scripts --no-audit --no-fund
npm --prefix tools/rideshare_e2e run check
npm --prefix tools/rideshare_e2e run smoke
```

Установка завершилась с кодом 0; конфигурация 3/3. Chromium действительно открыл
синтетическую страницу; проверены нажатие, сохранение и разделение профилей, fresh reset.
Приложение, аккаунты пользователей и платёжные провайдеры в smoke не используются.
Команды выше — способ воспроизведения через npm scripts. В первичном журнале smoke
вызван напрямую как `node /tmp/yuldash-clean-za9ld3u2/checkout/tools/rideshare_e2e/tests/browser-smoke.mjs`
из каталога вне checkout, с явным `RSACC_CHROMIUM_PATH`.

До исправления три проверки конфигурации падали, а исходный драйвер сообщал MODULE_NOT_FOUND.
Этот первичный RED наблюдался в выводе инструмента; отдельный файл лога тогда не сохранялся.
Поздний пересказ не выдаётся за первичный raw-журнал; повтор только ради такого файла не выполнялся.

Стандартная команда `npm run install:browser` не смогла скачать ZIP: Playwright CDN
вернул 195 байт HTML «Site Unavailable» со статусом HTTP 200. Та же версия Chrome for Testing
156.0.8078.4 скачана из
[официального Google Storage](https://storage.googleapis.com/chrome-for-testing-public/156.0.8078.4/linux64/chrome-linux64.zip)
и передана через `RSACC_CHROMIUM_PATH`. Успех стандартной установки браузера не заявляется.
PowerShell в окружении отсутствует: изменения .ps1 прочитаны и приняты в ревью, но не исполнены.

## Состав доказательств

Исходные байты журналов/XML сохранены в `raw-evidence.zip`. Читаемые рядом копии приведены
к LF, без хвостовых пробелов и лишних пустых строк в конце, чтобы не засорять Git diff.
Команды, результаты и сообщения не переписаны. `evidence-normalization.json` связывает
каждую исходную и читаемую копию отдельными SHA-256; исходные журналы не заменены пересказом.

| Файл | Что подтверждает |
|---|---|
| main-findride-baseline.xml | Реальное падение на исходном main |
| main-findride-green.xml | Три успешных сценария и два управляемых запроса |
| android-main-final-check.log | Итоговая сборка и 142 проверки этой ветки |
| ci-findride-original.txt | Выдержка из скачанного оригинального HTML-отчёта CI |
| e2e-clean-verification.log | Чистая npm-установка и настоящий Chromium smoke |
| github-state.json | Сверка SHA, конфликтов PR и отсутствующих проверок |
| snapshot.json | Хэши изменённых исходников и независимая приёмка |

Java в облачном окружении использовала системное доверенное хранилище TLS.
Robolectric SDK JAR скачаны с Maven Central и проверены по опубликованным SHA-512;
использован offline dependency directory из-за ограничений DNS внутри JVM.
Версии проекта и проверки TLS не менялись. Эмулятор и /dev/kvm недоступны.
Ни публикации, ни слияния, ни выкладки этим этапом не выполнялось.
