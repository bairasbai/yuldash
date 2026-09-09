> Итог9сентября: полный статический проход кода завершён на main317b0976. [Итоговый документ](C:/Users/Bayra/Yuldash/docs/audit-main-result.md). Ниже сохранена история этапов; старые записи об ожидании не являются текущим статусом.
# Аудит main Юлдаша — продолжение 6 сентября 2026

[Единый рабочий документ: все сохранённые разделы аудита](audit-main-full-report.md). Проверка продолжается 7 сентября; новая замеченная вершина main — `317b0976`.

**Проверка продолжается, полный аудит ещё не завершён.** Код продукта не исправлялся. Основной снимок — `3349472c`; main за время работы продвинулся до `317b0976`. Разницу проверяем отдельно, результаты старых запусков новой вершине не приписываем.

## Документы с ошибками и исправлениями

- [Короткая очередь исправлений](audit-main-fix-order.md).
- [Основной отчёт и метод проверки](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-main.md).
- [Android](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-android.md).
- [Сервер и деньги](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-backend.md).
- [Веб-приложение, сайт, проморолики](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-web.md).
- [Скрипты и старый Flutter](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/docs/audit-2026-09-05-tools-legacy.md).
- [Новое: сбой отката миграции SQLite](../artifacts/full-main-audit-2026-09-06/migrations.md).
- [Android: дополнительные ошибки AND14–17](../artifacts/full-main-audit-2026-09-06/android-next.md).
- [Android: проверка ещё 20 тестовых файлов](../artifacts/full-main-audit-2026-09-06/android-batch3.md).
- [Доставка: ошибки BE09–12 с локальными пробами](../artifacts/full-main-audit-2026-09-06/backend-next.md).
- [Веб: контракты ленты и сохранения карты](../artifacts/full-main-audit-2026-09-06/web-next.md).
- [Веб: повтор выплаты и следующая порция файлов](../artifacts/full-main-audit-2026-09-06/web-batch3.md).
- [Сервер: повторная готовность водителя и продление рекламы](../artifacts/full-main-audit-2026-09-06/backend-batch3.md).
- [Разбор предупреждений Android Lint](../artifacts/full-main-audit-2026-09-06/lint-review.md).
- [Android: конфиги и XML-ресурсы](../artifacts/full-main-audit-2026-09-06/android-config-review.md).
- [Изменения main во время аудита и новый тест](../artifacts/full-main-audit-2026-09-06/main-delta-review.md).
- [Android: следующая порция экранов](../artifacts/full-main-audit-2026-09-06/android-batch4.md).
- [Веб: разблокировки, фильтры, обратный звонок](../artifacts/full-main-audit-2026-09-06/web-batch4.md).
- [Сервер: даты подписок и CSV](../artifacts/full-main-audit-2026-09-06/backend-batch4.md).
- [Сервер: блокировка фото, GPS в аудио, прогноз](../artifacts/full-main-audit-2026-09-06/backend-batch5.md).
- [Развёртывание и резервные копии](../artifacts/full-main-audit-2026-09-06/ops-review.md).

## Дополнительные результаты

На отдельной копии `317b0976` успешно выполнены `assembleDebug` и `testDebugUnitTest`: 1579 тестов, 0 ошибок, 0 пропусков по сохранённым XML ([итог](../artifacts/full-main-audit-2026-09-06/main317-full-test-summary.json), [лог](../artifacts/full-main-audit-2026-09-06/android-main317-build-test.log)). Также собран APK инструментальных тестов, но запуск на устройстве этим не подтверждается ([лог](../artifacts/full-main-audit-2026-09-06/android-main317-instrumentation-build.log)).

Отдельные проверки реальных экранов подтвердили потерю кнопки согласования адреса во время поездки и зависание кнопки после восстановления экрана ([DELTA01–02](../artifacts/full-main-audit-2026-09-06/android-main-delta-findings.md)), а также исчезновение доверенного контакта из интерфейса без удаления с сервера ([AND43 и контрольный сценарий](../artifacts/full-main-audit-2026-09-06/android-lead-addendum.md)). Сеть в этих пробах заменена локальным тестовым сервером.

[Объединённый реестр Android](../artifacts/full-main-audit-2026-09-06/android-unified-coverage.tsv) отдельно отмечает полное чтение, частичное чтение, проверку структуры и непроверенные файлы.

[Ресурсы Android](../artifacts/full-main-audit-2026-09-06/android-resource-review.md): прочитаны31 XML, декодированы и просмотрены32 изображения, проверены таблицы символов2 шрифтов и целостность1 JAR-архива. Эти ограниченные проверки бинарных файлов выделены отдельными статусами. [Дополнительный проход30 серверных тестов](../artifacts/full-main-audit-2026-09-06/backend-tests-lead-review.md) содержит BE29: обещание «точную геолокацию не храним» расходится с записью координат SOS в базе.

[Сверка крупных Android-изменений317](../artifacts/full-main-audit-2026-09-06/android-batch10.md) отдельно отмечает исправленные в новой версии находки и новые ошибки. Старые описания базы не означают, что каждая ошибка сохраняется в текущем main.

Продолжение 7 сентября: [подбор и смена адреса такси, INS01–04](../artifacts/full-main-audit-2026-09-06/instant-router-lead-review.md), [уведомления и SMS, SVC01–03](../artifacts/full-main-audit-2026-09-06/services-lead-review.md), [ошибки обновлённого Android](../artifacts/full-main-audit-2026-09-06/android-main-delta-findings.md), [конфигурация сервера, BE26–27](../artifacts/full-main-audit-2026-09-06/backend-gaps.md), [публикация сайта, WEB29–30](../artifacts/full-main-audit-2026-09-06/web-batch9.md).

Реестры различают ручной проход и автоматические проверки: [сервер](../artifacts/full-main-audit-2026-09-06/backend-unified-coverage.txt), [веб](../artifacts/full-main-audit-2026-09-06/web-cumulative-coverage.tsv). Все6 крупных изменений production-кода Android до317 и все базовые Android-тесты дочитаны. Остаются серверные тесты и ограниченные проверки ресурсов; полный аудит пока не завершён.

`lintDebug` и `assembleRelease` без подписи завершились успешно на снимке3349472c: [лог](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/artifacts/audit-2026-09-05/android-lint-release-retry.log). APK без подписи использовался только для проверки сборки. Первый запуск не состоялся из-за моей передачи Gradle-параметра без кавычек в PowerShell (`Task '.unsignedRelease' not found`); это ошибка команды аудита, не дефект проекта. В повторе параметр передан одной строкой.

Четыре отдельные пробы Android подтверждают ошибочное поведение в очереди сообщений и смене сессии: [XML: 4 теста, 0 падений](C:/Users/Bayra/Yuldash-main-audit-2026-09-05/artifacts/audit-2026-09-05/android-evidence-results.xml). Они утверждают наблюдаемые баги; зелёный результат не означает исправления.

После смены разрешений среды новые результаты сохраняются в `artifacts/full-main-audit-2026-09-06/` основной рабочей папки. Исходная копия аудита читается без изменений. Журналы охвата сохраняют различие между глубоким чтением, автоматическим анализом и ещё не проверенным кодом.


[Указатель находок](C:/Users/Bayra/Yuldash/docs/audit-main-findings-index.md) группирует повторные доказательства по номеру. [Общий реестр](C:/Users/Bayra/Yuldash/artifacts/full-main-audit-2026-09-06/all-files-coverage.tsv) сверяется с git334:1772 пути, каждый имеет запись. Наличие записи не означает полное чтение: отдельные статусы показывают оставшиеся проверки, справочники и ресурсы.
