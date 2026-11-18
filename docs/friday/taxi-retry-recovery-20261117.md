# Пятница: восстановление повторного поиска такси — 17.11.2026

## Граница работы
Только retry_waiting_orders. База: 061078247395fd55f6f9bf21a3c49030b44acf16.
Попутка, main и production не изменялись.

## План и результат
1. Воспроизвести реальный failed flush на первом ожидающем заказе: выполнено; до исправления PendingRollbackError прерывал очередь.
2. Восстановить Session и безопасно записать ошибку: выполнено. ID сохраняется заранее; rollback очищает неудачную транзакцию; лог содержит тип ошибки без SQL-параметров.
3. Проверить продолжение очереди: выполнено. Второй заказ проходит настоящий start_matching, первый восстанавливается без частичной записи.
4. Независимая проверка: свежий reviewer, блокирующих замечаний нет; независимо 15/15 PASS.

## Проверки
- Адресные тесты test_taxi_worker.py: 15 PASS, exit 0.
- Первый FULL: 6844 PASS, 119 skipped, 2 FAILED, exit 1. Два Sentry-теста не прошли: HTTPX наследовал SOCKS proxy, но socksio отсутствовал.
- Контроль неизменённой базы 06107824 с тем же окружением: те же 2 FAILED, 108 PASS в test_observability_scrub.py. Это не новое падение от taxi fix.
- Кандидат без proxy environment variables: observability_scrub — 110 PASS, exit 0.
- Повтор FULL на чистой SQLite, тот же venv и код, только proxy env сняты: 6846 PASS, 119 skipped, 804 warnings, exit 0, 621.39 секунды.
- ruff check backend/app --select F: PASS. git diff --check: PASS.
- TCP запрещён через pytest-socket, Unix sockets разрешены для asyncio. В SDK-пробах исходящий HTTP транспорт подменён тестами; боевые сервисы не вызывались.

## Воспроизведение
Из backend, после установки requirements.txt и pytest/pytest-socket/ruff в изолированное окружение:
```sh
mkdir -p /tmp/yuldash-taxi-check
env -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy \
  TMPDIR=/tmp/yuldash-taxi-check TZ=UTC DATABASE_URL=sqlite:////tmp/yuldash-taxi-check/test.db \
  python -m pytest tests -q --disable-socket --allow-unix-socket
ruff check app --select F
```
Для параллельного запуска использовать другой уникальный TMPDIR/SQLite путь.

## Ограничения
SQLite, не PostgreSQL; конкурентные workers, Android/UI, нагрузка и production не проверены.
119 skipped не считать пройденными. Предупреждения остаются.
Этот узкий фикс не означает готовность всех сценариев такси.
Проверенные backend blobs совпадают с локальным кандидатом 81fd33d2b7046de7b38d1098019a55086d0bc6b5:
- taxi_worker.py: 233c981a5446fa04f630fc35a6a592c16935adad
- test_taxi_worker.py: ec463f5e022b9d512d6986454c2e869c1c1c1607

## Дальше
После отдельной проверки на PostgreSQL оценить транзакционную изоляцию конкурентных циклов; не расширять этот фикс без воспроизводимого теста.

## Следующий пакет: не закрывать изменившееся ожидание

База 31e20c17; отдельная ветка fix/friday-taxi-expired-wait-recheck-20261117.
Сценарий: пока воркер получил кандидатов, пассажир продлил ожидание или заказ приняли.
Повторный SELECT ранее возвращал старые поля из identity map; срок под блокировкой не проверялся.
Две регрессии воспроизведены настоящей записью второй Session (до исправления 2 FAILED).
Изменение: populate_existing под блокировкой и повторная проверка wait_until.
После исправления 17 PASS адресно; Ruff и diff-check PASS.
Независимая приёмка 25478930: блокирующих замечаний нет, отдельный прогон 17 PASS, exit 0.
Ruff и diff-check PASS; detect-secrets проверил два изменённых Python-файла, находок нет.
Первый полный прогон прервался при смене окружения без итогового кода выхода; он не засчитан.
Повтор полного backend на неизменённом Python-коде 25478930: 6848 PASS, 119 skipped, 804 warnings, exit 0, 699.07 секунды. Команда и изоляция — как выше (отдельный TMPDIR, TCP запрещён).
Публикация фиксируется отдельно после проверки удалённой ветки.
SQLite-регрессия проверяет последовательное чередование двух Session, а не реальную блокировку PostgreSQL.

## Следующий пакет: не отменять изменившуюся поездку

База e736a1a313261dd235e5c52875f941e93263da09; ветка fix/friday-taxi-stuck-state-recheck-20261117.
close_stuck_orders повторно обновляет ORM-поля под блокировкой, чтобы не отменить поездку, которая уже продолжилась или завершилась.
Две регрессии до исправления: 2 FAILED; после исправления и независимо: 19 PASS, exit 0.
Полный backend на 76ce2ead97f2ca69bb7378902ef7dd02cc545c22: 6850 PASS, 119 skipped, 804 warnings, exit 0, 705.60 секунды.
Команда и изоляция как выше: pytest tests -q --disable-socket --allow-unix-socket, уникальный TMPDIR, TZ=UTC, без proxy vars.
Ruff, diff-check PASS; detect-secrets двух изменённых Python-файлов: находок нет. Независимая приёмка: блокирующих замечаний нет.
SQLite проверяет последовательное чередование сессий, не PostgreSQL-блокировки. UI, Android, нагрузка не проверены.
Прежний соседний риск без изменения в этом пакете: close_stuck_orders не восстанавливает Session после failed flush и выводит текст исключения. Это отдельный следующий сценарий, не объявлен исправленным.

## Восстановление закрытия зависших поездок после failed flush

База f4de1b1dc9a4e8803b0ebea4ba6340ad3c5dbf07; ветка fix/friday-taxi-stuck-rollback-20261117.
В close_stuck_orders добавлен rollback после ошибки записи закрытия и вторичных shares/push; уже сохранённое закрытие не откатывается. ID захвачен до try, warning содержит только тип исключения, без SQL-параметров.
Три регрессии вызывают настоящий NOT NULL failed flush: при закрытии, уведомлении близких, push. До исправления 3 FAILED; после и при независимом повторе 22 PASS, exit 0. Блокирующих замечаний reviewer нет.
Полный backend на 761773cc9ba42532fef6c807fb0c9809089c6d53: 6853 PASS, 119 skipped, 804 warnings, exit 0, 625.08 секунды. Команда и изоляция как выше; уникальный TMPDIR, TZ=UTC, без proxy vars, TCP запрещён.
Ruff, diff-check PASS; detect-secrets двух Python-файлов: находок нет.
Прежний риск failed Session в close_stuck_orders из предыдущего раздела исправлен только этим пакетом. PostgreSQL, реальные SMS/FCM, Android/UI и нагрузка не проверены. Это не полная приёмка режима такси.

## Завершение ожидания: восстановление после ошибки транзакции

База 27cfc841cb555221408cf43be277e304c35001cc; ветка fix/friday-taxi-wait-finish-rollback-20261117.
finish_expired_waits восстанавливает Session после ошибки завершения ожидания и push. Сохранённое закрытие остаётся в finished; несохранённое не получает возврат скидки. ID захвачен до try, warning без SQL-параметров.
Две регрессии вызывают настоящий NOT NULL failed flush. До: 2 FAILED; после и независимо: 24 PASS, exit 0. Reviewer также независимо воспроизвёл оба отказа старой функции в памяти процесса. Блокеров нет.
Полный backend на e041eeec54083c875c1cb2bbf83157ba7ff6eeec: 6855 PASS, 119 skipped, 804 warnings, exit 0, 722.89 секунды.
После смены облака зависимости заново установлены из requirements.txt в отдельный venv; версии проекта не изменялись. Команда и изоляция как выше: уникальный TMPDIR, TZ=UTC, без proxy vars, TCP запрещён.
Ruff, diff-check PASS; detect-secrets двух изменённых Python-файлов: находок нет.
SQLite, не PostgreSQL-конкурентность. Реальные push/SMS, Android/UI, нагрузка и production не проверены.

## Истёкшие предложения: восстановление после failed flush

База 26ba14e0e6955623bc4b73789d3957504acf2cca; ветка fix/friday-taxi-offer-rollback-20261117.
advance_stale_offers восстанавливает Session при сбое перехода одного заказа. ID берётся до try, warning без SQL-параметров.
Регрессия вызывает настоящий NOT NULL failed flush первого заказа; второй проходит реальный advance_after_no_accept и завершается expired при отсутствии Redis/водителей. До: 1 FAILED; после и независимо: 25 PASS, exit 0. Reviewer: блокеров нет.
Полный backend на ec491221b453f9b15d78ef23b7972ea1d6166de2: 6856 PASS, 119 skipped, 804 warnings, exit 0, 664.45 секунды.
Команда и изоляция как выше: уникальный TMPDIR, TZ=UTC, без proxy vars, TCP запрещён. Ruff, diff-check PASS; detect-secrets двух Python-файлов: находок нет.
SQLite, не PostgreSQL-конкурентность. Назначение реального следующего водителя, доставка push, Android/UI и нагрузка не проверены.

## Повторная проверка предложения перед переходом таймера

База 2b4a5bc46eb06237c91f2edfc1b9326927a39cec; ветка fix/friday-taxi-offer-state-recheck-20261117.
advance_stale_offers повторно читает статус и сроки под FOR UPDATE с populate_existing. Принятый заказ не попадает в отчёт переходов; продлённое или свежее предложение не снимается по старому снимку.
Три регрессии сохраняют изменение другой Session после первичной выборки: accepted/extended/new_offer. До: 3 FAILED; после и независимо: 28 PASS, exit 0. Reviewer: блокирующих замечаний нет.
Полный backend на fae089af7f99254c596c7451c0afda09adacf0b1: 6859 PASS, 119 skipped, 804 warnings, exit 0, 717.18 секунды. Прогон начат после освобождения тяжёлого слота Амио, не параллельно с его FULL.
Команда и изоляция как выше: уникальный TMPDIR, TZ=UTC, без proxy vars, TCP запрещён. Ruff, diff-check PASS; detect-secrets двух Python-файлов: находок нет.
SQLite подтверждает повторное чтение/условия, но не ожидание PostgreSQL row lock. Пропущенные после lock строки удерживают блокировку до следующего commit/rollback или закрытия Session; отдельный риск задержки. Ленивый reconcile_offer и другие пути этим пакетом не проверены. Android/UI, реальные push, нагрузка не проверены.

## Ленивый таймаут при чтении заказа

База 512c283d9c92a047b8b9f676ed36bdf7ba07b664; ветка fix/friday-taxi-lazy-offer-recheck-20261117.
reconcile_offer обновляет устаревшее состояние через refresh(with_for_update=True) и повторяет проверку статуса/срока до перехода. Продлённое предложение или предложение без срока не снимается; принятый заказ не вызывает лишний переход.
Три stale-snapshot регрессии до: 3 FAILED; контроль обычного таймаута PASS. После и независимо оба затронутых тестовых файла: 39 PASS, exit 0; reviewer без блокеров.
Полный backend на e86a9c1d1746cdf6b5775f83eb9470c15041a05b: 6863 PASS, 119 skipped, 804 warnings, exit 0, 761.03 секунды. Прогон начат после освобождения слота Амио.
Команда и изоляция как выше: уникальный TMPDIR, TZ=UTC, без proxy vars, TCP запрещён. Ruff, diff-check PASS; detect-secrets instant_service.py и test_taxi_status_never_resurrects.py: находок нет.
Проверены текущие два читающих обработчика. Если переход не нужен, row lock удерживается до конца запроса/Session; возможна небольшая задержка конкурирующего изменения. Широкого изменения транзакций нет, доказанного performance-дефекта нет. SQLite не доказывает PostgreSQL-блокировки; Android/UI, реальные push и нагрузка не проверены.

## Уведомление при повторном поиске: восстановление Session

База ce9f07de9aed87efaaa0e1ef47dce8e969394052; ветка fix/friday-taxi-retry-push-rollback-20261121; кандидат 265c288.
Пакет не содержит приостановленные изменения retry-state. При сбое push после commit повторного поиска выполняется rollback незавершённой транзакции уведомления; уже сохранённый offered и retry_count остаются.
Две регрессии: настоящий NOT NULL failed flush и исключение с pending некорректной ORM-записью. Обе до исправления FAILED (PendingRollbackError / IntegrityError), после исправления оба тестовых файла: 41 passed, exit 0. Начальная ошибочная команда из корня не нашла tests/ и не считается RED; продуктовый RED получен из backend/.
Команда: env -u DATABASE_URL -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY TZ=UTC /tmp/taxi-next-venv/bin/python -m pytest tests/test_taxi_worker.py tests/test_taxi_status_never_resurrects.py -q --disable-socket --allow-unix-socket.
Ruff app --select F и diff-check PASS, detect-secrets двух Python-файлов: 0 находок. Независимая приёмка свежим gpt-6.1-sol: принято, находок P0/P1/P2 нет, самостоятельные 41 passed. Полный backend на 989346dfa4b046824837ac4932c533522ea24411 (код 265c288): 6865 passed, 119 skipped, 804 warnings, exit 0, 656.41 секунды. Прогон после освобождения слота Амио, без наложения тяжёлых тестов. Команда: те же env и pytest-флаги, весь tests/, уникальный TMPDIR=/tmp/taxi-retry-push-full; также удалены строчные proxy vars. Локальные gate PASS; удалённый CI этим не подтверждён.
SQLite, push заменён тестовым обработчиком. PostgreSQL-конкурентность, реальная доставка push, Android/UI, нагрузка и production этим пакетом не проверены.

## Dry-run: контракт отсутствия побочных эффектов

Независимый test-only пакет da24fd3, база265d590 (tree опубликованного609a342e); ветка test/friday-taxi-dry-run-contract-20261121. Не содержит приостановленных retry-state и scheduled-recheck.
Пять параметров покрывают activate_due_scheduled, close_stuck_orders, retry_waiting_orders, finish_expired_waits, advance_stale_offers. Проверены ID кандидатов, отсутствие INSERT/UPDATE/DELETE и commit, отсутствие новых/грязных/удалённых ORM-объектов, отсутствие вызовов matching/push/shares/release_ids, неизменность model_dump заказа.
Новые5PASS, оба затронутых тестовых файла46PASS, exit0; Ruff app --select F и diff-check PASS; detect-secrets изменённого тестового файла:0 находок. Команды и изоляция как в предыдущем опубликованном разделе. Ошибка продукта не заявляется, production-код не менялся.
Полный backend повторно не запускался. Существующее доказательство6865PASS/119skipped относится к989346d; diff от265d590 затрагивает только тест и документы, backend/app и зависимости неизменны. Новые проверки прогнаны отдельно, а объединённый новый FULL не заявляется. Независимая приёмка свежим gpt-6.1-sol: принято, P0/P1/P2 нет, самостоятельные46PASS. Допустимо использовать предыдущий FULL как прежнее свидетельство, не как свежий полный прогон. Проверка не покрывает raw SQL и другие соединения; неизменность установленного окружения прежнего FULL отдельно не доказана.
SQLite, подмены side-effect методов. Реальные push, PostgreSQL locks, UI и production не проверены. Прерванный scheduled FULL не повторялся.

## Исследование: запоздалый отказ прежнего получателя оффера

Отдельная WIP-ветка test/friday-taxi-stale-decline-evidence-20261121 от локального81b773a (опубликованный test-only пакет17659ed). Продуктовый код не изменён.
Воспроизведён отказ: Session держит offered для водителя A; другая Session передаёт предложение B; decline_offer для A получает старый identity-map объект, затем advance_after_no_accept делает UPDATE с условием только status=offered. Сохранённый оффер B превращается в searching, получатель/срок очищаются, вызывается подбор. Router перед сервисом загружает объект через _order_for_view, поэтому устаревший ORM-снимок возможен между авторизацией и обработкой.
Регрессия test_stale_decline_preserves_reassigned_offer: 1 FAILED, exit1 (ожидался offered, фактически searching). SQLite, две последовательные Session, try_offer_next подменён только для запрета внешнего подбора. Команда: прежние безопасные env/TCP flags, pytest tests/test_taxi_status_never_resurrects.py -k stale_decline_preserves -q.
Это доказательство дефекта, НЕ исправление и НЕ готовый пакет. Не публиковать красную ветку как проверенное изменение. Следующий шаг: минимальное условное изменение только актуального оффера, включая защиту от смены получателя после проверки; отдельно рассмотреть commit записи reason до перехода. Простого refresh до проверки недостаточно для всей гонки. Нужны регрессии stale снимка и смены получателя между проверкой и записью, затем независимая приёмка и новый согласованный FULL. Приостановленные пакеты не затронуты и их FULL не повторялся.

### Исправление отказа прежнего получателя

Кандидат02fbfa6753710188adb893713d7bac959856340d. Добавлен keyword-only expected_driver_id к advance_after_no_accept; только decline_offer передаёт исходный аргумент driver_id. SQL UPDATE требует совпадения актуального получателя. Проигранный CAS не запускает подбор. Остальные вызывающие места сохраняют прежние условия.
Четыре регрессии до исправления FAILED: stale ORM и смена получателя непосредственно перед UPDATE, каждая с пустой/непустой причиной. После исправления85PASS, exit0: test_taxi_worker.py, test_taxi_status_never_resurrects.py, test_instant.py, test_instant_order_participants_only.py (включая штатный отказ и API-права).
Ruff app --select F и diff-check PASS, detect-secrets instant_service.py и test_taxi_status_never_resurrects.py:0 находок. Свежая независимая приёмка gpt-6.1-sol: принято в границах A→B, P0/P1/P2 нет, самостоятельные85PASS, exit0. Новый FULL на1875efd7ba265520cb71aef0cab7b317b2167592:6874 passed,119 skipped,804 warnings,exit0,660.00с. Запущен после освобождения слота Амио. Команда: env -u DATABASE_URL -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy TMPDIR=/tmp/taxi-decline-full TZ=UTC /tmp/taxi-next-venv/bin/python -m pytest tests -q --disable-socket --allow-unix-socket. Прежняя отметка WIP выше описывает исходный RED, текущий исправленный пакет прошёл локальные gate. Удалённый CI отдельно не подтверждён.
Границы: защищена смена получателяA→B, но два поколения оффера одномуA API без generation не различает. Запись аналитики причины может сохраниться при проигранномCAS; её семантика не менялась. SQLite-регрессии не доказывают реальную PostgreSQL-конкурентность, UI и доставку push. Прерванный scheduledFULL не повторялся, пакет от него не зависит.

## Исследование границы: новое предложение тому же водителю

WIP test/friday-taxi-same-driver-offer-evidence-20261118 от0058c2f (tree опубликованногоaad10c85). Продуктовый код не меняется. Два варианта теста old_decline_preserves_new_offer_to_same_driver, с причиной/без, воспроизводят RED2,exit1: после загрузки старого оффера другая Session меняет offered_at/offer_expires_at, сохраняя того же получателя. Отказ по старому снимку снимает новое предложение (searching). Подменён только дальнейший matching, две Session настоящие; SQLite, не PG-конкурентность. Лог /tmp/taxi-same-offer-red.log, команда прежняя безопасная адресная с -k old_decline_preserves_new.
Разделить два разных окна:
1. Новое предложение возникло ПОСЛЕ чтения заказа сервером: возможно узкое CAS сравнение снимка offered_at/offer_expires_at, захваченного до commit/rollback аналитики. Сам refresh недостаточен; обновлённый снимок утратит поколение старого отказа.
2. Запоздалый запрос дошёл ПОСЛЕ появления нового предложения: сервер уже видит новый снимок. DeclineIn содержит только reason; Android instantDecline и PWA declineOrder передают order id и необязательную причину, но не поколение оффера. Старый и актуальный запросы неотличимы. Для полного решения нужен согласованный API/client token и политика старых клиентов, не подмена маленькой backend-правкой.
Повторное предложение тому же получателю возможно по коду: _expire_no_drivers очищает tried, start_matching допускает следующий круг; также tried имеет TTL. Частота такого сценария в production не измерена.
Пакет содержит только воспроизводимую красную регрессию/исследование. Это не исправление и не готово к публикации. Приостановленные пакеты не затронуты, FULL не запускался, heavy слот у Амио.

### Узкая защита снимка после чтения сервером

Кандидат657d12d7f4de03f8aa85e42b20125603b23efad9 от0058c2f. decline_offer захватывает (offered_at,offer_expires_at) до commit/rollback аналитики, передаёт необязательным expected_offer_times в helper. UPDATE сравнивает оба поля и исходного получателя. None внутри tuple сравнивается как SQL IS NULL; отсутствие самого tuple сохраняет прежнее поведение остальных callers.
8RED до исправления: отдельно меняется offered_at или expiry, до decline или непосредственно перед UPDATE, с причиной/без. После:97PASS,exit0 в четырёх файлах (worker,status_never_resurrects,instant,instant_order_participants_only). Включены4положительных контроля текущего оффера сNULL/nonNULL временами и сreason/без; штатный API отказ и права участников тоже проходят. Ruff,diff-checkPASS; detect-secrets двух Python-файлов0. Свежая независимая приёмка gpt-6.1-sol: принято,97PASS, P0/P1/P2 нет. Отдельный API/client план также независимо принят как нереализованный. FULL наeadde08fb5d37bda05ed2da21fc98ce072ee26ad:6886passed,119skipped,804warnings,exit0,609.03с. Команда как в предыдущем FULL с TMPDIR=/tmp/taxi-generation-full, запущен после освобождения слота Амио. Локальные gate PASS; удалённый CI не подтверждён.
Совместимость: HTTP-модель, Android и PWA не изменены; сигнатура helper расширена только keyword-only optional, прежние callers используют defaultNone. Миграций/версий/зависимостей нет. Два timestamp — условие совпадения снимка, не уникальный номер поколения: полное совпадение времён при повторном создании не различается. Запрос, задержанный ДО серверного чтения, не защищён. Реальная PostgreSQL-конкурентность, UI и push не проверены.

### Отдельный план API/client для задержанного до чтения запроса (не реализован)

1. Согласовать явный непрозрачный offer token/монотонную revision на каждом новом предложении и правила её смены при продлении/повторе. Предпочесть отдельную revision перед временем: совпадающие timestamp не должны означать то же поколение. Инвентаризировать все пути выдачи оффера перед миграцией.
2. Добавить token в авторизованную выдачу оффера и необязательный expected token в decline. Проверять token в том же UPDATE, вместе с получателем/статусом. Старый/неподходящий token даёт безопасный no-op без нового matching. Аналитику проигранногоCAS явно помечать или не считать применённым отказом по отдельно выбранной семантике.
3. Сначала backend с обратной совместимостью, затем Android и PWA: сохранять token именно отображённого предложения и отправлять его со всеми ручными/автоматическими отказами и повторной отправкой. Не заменять сохранённый token свежим непосредственно перед запросом. Старые клиенты без token сохранят ограниченную защиту, это явно учитывать; обязательность token — отдельное решение с планом rollout, не молча ломать старые версии.
4. Проверки: два поколенияA→A иA→B, delayed request до чтения, повтор запроса/потерянный ответ, пустой/неправильный token, отсутствие token у старого клиента, reason commit/rollback, одновременный accept/decline, миграция/backfill. Нужны реальные PostgreSQL-гонки плюс Android/PWA тесты и UI-проверка четырёх языко-тематических сочетаний по правилам проекта.
5. Только после независимой приёмки и всех gate публиковать отдельные ветки. Main/production/изменение реальной БД требуют отдельного разрешения. Этот план не расширяет текущую гарантию snapshot-CAS и не является реализованным контрактом.

## Optional offer token: совместимая серверная база

База1bcc63c (tree опубликован9575cabe); кандидатdd46efaf31e2221287eea3806a42afbd1bd6758b; ветка fix/friday-taxi-optional-offer-token-20261118. План схемы/API независимо принят ДО реализации.
InstantOrder.offer_token nullableString32. При каждой новой выдаче в try_offer_next (единственная найденная запись status=offered для InstantOrder) uuid4.hex записывается в том же UPDATE. Продление времени того же предложения не меняет token. Это идентификатор предложения, НЕ credential и не авторизация; участники проверяются прежним способом.
Payload показывает token только текущему offered водителю, не пассажиру/прежнему actor_authorized. DeclineIn.expected_offer_token optional: ровно32 lowercasehex; invalid422. Переданный клиентом token неизменным участвует в CAS вместе сdriver/status/server snapshot. Пропущенный/null token сохраняет прежний путь. Несовпадение до analytics не записывает причину, а гонка после проверки может оставить запись причины, но не меняет заказ/не вызываетmatching.
Миграцияck_instant_offer_token отcj_request_traveler_origin: nullable, безbackfill. Upgrade/idempotentupgrade/downgrade проверены только на отдельной временнойSQLite с сохранением старой строки; legacytokenNULL. alembic heads: ровноck_instant_offer_token. Реальные БД не открывались и не изменялись.
2первичныхRED до реализации;22новыхPASS, все5scopedфайлов119PASS,exit0. ПокрытыUUIDrotation same-driver/same-timestamps, delayedoldtoken/currenttoken, продление срока, legacyNULL/no-body/empty/nullfield, invalidformat/type, privacy/access, CASrace после analyticscommit и настоящегоfailedflush/rollback, миграция. Команда: прежние безопасныеenv/TCPflags, pytest test_taxi_offer_token.py + четыре прежних файла. Ruff app и новыйtest/migration --select F, diff-checkPASS; detect-secrets5изменённыхPython-файлов0. Свежая независимая приёмка gpt-6.1-sol: принято, 119 PASS, P0/P1/P2 нет; docs и rollout отдельно приняты. FULL на 08950344feeb6ec92e32b839b9659efa6ffa5fdb: 6908 passed, 119 skipped, 804 warnings, exit 0, 603.54 секунды. Команда как в предыдущем FULL с TMPDIR=/tmp/taxi-token-full. Новый прогон после явного освобождения слота родителем, прерванные пакеты не повторялись. Локальные gate PASS; удалённый CI не подтверждён.

### Границы и порядок будущего rollout

API только добавлен, Android/PWA не изменены и token пока не отправляют. Отказ безtoken не получает защиты delayed запроса до серверного чтения. СтарыеNULLстроки не получают фиктивныйtoken задним числом; новый появится при новой выдаче. Никакой обязательности/feature enforcement не включено.
При отдельно разрешённой будущей выкладке: сначала additiveмиграция, затем ВСЕ API/workerwriters обновить до кода, меняющегоtoken при выдаче, лишь затем подключатьtoken-awareклиентов. В смешанном старом/новом serverfleet старыйwriter может переиспользовать прежнийtoken при новой выдаче: этот пакет не обещает такую rollout-гарантию. Приrollback на старыхwriters гарантию нового протокола нужно считать отключённой; колонку сохранять до остановки нового кода. Downgradeмиграции удаляет толькоtokenmetadata, не безопасен при работающем новом приложении.
UUIDv4 обеспечивает практическую уникальность, не математическое отсутствие коллизий/DBuniqueconstraint. Параллельные matcherwriters и PostgreSQL-гонки этим SQLite-набором не доказаны. Доставка push, UI, клиенты и production не проверены. Next: независимаяприёмка+FULL; затем отдельныйclientпакет сtoken отображённогоофера и повторами, PGconcurrency и согласованныйrollout. Прерванные пакеты не затронуты.

## Подготовка writers и совместимости перед rollout

Test-only кандидат fc9869463b63536371538577773b1a4df549d958 от 5f85e5c (tree опубликованного 2b7c8a48). Runtime, API, схема и зависимости не изменены. Шесть новых проверок выполняют initial/retry/next/scheduled/reassign/lazy входы сервисов; отдельная Session внутри наблюдателя _push_offer проверяет, что новый token уже сохранён вместе с получателем/status до уведомления. Подменены выбор кандидата и транспорт уведомления, не генерация/UPDATE/commit. Новые 6 PASS; пять адресных файлов 125 PASS, exit 0. Ruff и diff-check PASS, detect-secrets изменённого теста: 0. Новый FULL не запускался: прежний 6908 PASS/119 skipped на 0895034 остаётся историческим свидетельством неизменённого backend, не объявляется новым полным прогоном. Свежая независимая приёмка gpt-6.1-sol: принято, 125 PASS, exit 0, P0/P1/P2 нет. Inventory/checklist отдельно приняты по исходникам; повтор тестов для docs не требовался.

### Inventory по исходникам (не состояние реального сервера)

Read-only исследование обнаружило единственный app-writer нового InstantOrder.offered: instant_service.py try_offer_next, около строк 3147–3178. UUID, status, offered_at, recipient и expiry записываются одним UPDATE. Все найденные входы сходятся в него: create API; ручная/ленивая/фоновая активация предзаказа; фоновый retry; decline; ленивый и фоновый таймаут; переназначение после отмены водителя; смена адреса offered-заказа. RequestResponse.offered относится к другой модели. Альтернативного writer нового InstantOrder.offered в просмотренных app/migrations/scripts/tools не найдено, но внешние процессы не обследованы.
Остальные записи очищают recipient/expiry при accept/cancel/expire/searching/закрытии/удалении аккаунта. Старый token может оставаться в строке, но вне offered или для другого viewer не выдаётся; новая выдача заменяет его. Колонка nullable, генератора по умолчанию/DB-триггера/backfill нет.
Отдельный worker предусмотрен: backend/deploy/yuldash-taxi-worker.service запускает python -m app.taxi_worker с WorkingDirectory=/opt/yuldash, timer каждую минуту. Рестарт только API не доказывает обновления worker, включая уже выполняющийся oneshot. Наличие реальных cron/arq/контейнеров/других серверов и ручных runners не проверено.

### Проверяемые условия будущего обновления (ничего не выполнялось)

1. Зафиксировать принятую версию и полный реальный список API replicas/processes, taxi-worker timer/cron, долгих и ручных runners, старых deployment slots.
2. Только после отдельного разрешения на rollout: additive-миграция, проверка revision/схемы без чтения пользовательских данных; затем обновление ВСЕХ writers.
3. Проверить actual executable/CWD/image/revision каждого writer, завершение старых процессов. Отсутствие NULL у новых офферов недостаточно: старый writer может сохранить прежний nonNULL token.
4. На изолированном стенде проверить API и worker входы отдельно, повтор тому же водителю, совпадающие timestamps, old/current token, legacyNULL, old/new клиенты. Эти шесть service-тестов не заменяют полные HTTP/worker/PG интеграционные проверки всех входов.
5. Только после подтверждения единой версии writers включать token-aware клиентов. Принудительный token — отдельный rollout, здесь не включался. При rollback старых writers гарантия ротации теряется; колонку не удалять при работающем новом коде/клиентах.

### Отдельные непроверенные риски, не исправления этого пакета

try_offer_next UPDATE сейчас ограничен id, не статусом/поколением: token не доказывает race-safety всей машины состояний. Fallback более дешёвого класса очищает recipient/expiry без нового matching; возможное зависание требует отдельного воспроизведения, здесь не подтверждено. Нет утверждения о готовности production или обновлении его writers. Приостановленные пакеты, main и реальные БД не затрагивались.

## Новый независимый этап: race внутри writer подтверждён, исправления ещё нет

Ветка test/friday-taxi-offer-writer-race-20261118 основана на 34215f1, без непроверенных клиентских пакетов и без приостановленных изменений. Изолированный test_taxi_offer_writer_race.py воспроизводит штатную passenger cancel в другой Session внутри задержанного candidates. Cancel коммитит cancelled, затем try_offer_next безусловно пишет offered. Результат: RED, 1 failed / 1 warning / 1.22 s, assertion cancelled != offered. Первичная ошибка импорта Actor в самом тесте исправлена до этого прогона; она не считается доказательством дефекта.

Команда из backend: env -u DATABASE_URL -u HTTP_PROXY -u HTTPS_PROXY -u ALL_PROXY -u http_proxy -u https_proxy -u all_proxy TMPDIR=/tmp/taxi-writer-race TZ=UTC /tmp/taxi-next-venv/bin/python -m pytest tests/test_taxi_offer_writer_race.py -q --disable-socket --allow-unix-socket. Настоящие сервисы/БД не использовались; fake Redis и временная SQLite. Диагностический тест намеренно пока красный, не публиковать пакет как проверенное исправление.

Независимый read-only reviewer подтвердил реальное окно: start_matching/advance_after_no_accept коммитят searching до подбора, освобождая прежний row lock. try_offer_next UPDATE ограничен только id. Аналогичный безусловный writer есть в _expire_no_drivers. Минимальный будущий scope должен рассмотреть оба выхода, rowcount и отсутствие push/cleanup у проигравшего. Для полной защиты поколения searching одного status недостаточно: searching_at не обновляется каждым переходом, а Redis tried-set меняется до UPDATE. Нужно отдельно доказать ABA-поколение и побочные эффекты; исправление ещё не реализовано.

Клиентский recovery пакет создан отдельно: client-token-20261118.bundle, git bundle verify PASS; содержит Android cb378e1, PWA af56796 и late-accept 337c365, требует базу 34215f1. SHA256: 0895803b4cbe54baf9a67b089a4252542287c43c6922fa84399300c8f32e9006. Desktop остаётся offline, из локальных браузеров найден только ранее заблокированный Chromium; повторов его запуска не было. Полные UI gates не заменены DOM-тестом и не объявлены зелёными.

### Продолжение writer race: минимальное исправление принято scoped

Родитель поручил исправить подтверждённое окно. Runtime 41cdd447d289ca22d4956b0dd3bcbe5dbddac76c: try_offer_next и _expire_no_drivers теперь обновляют только status=searching, проверяют rowcount. Проигравший возвращает свежую строку без offer/expired push, cleanup или SADD. SADD успешного writer выполняется после SQL CAS, но до commit: блокировка строки не даёт параллельному decline увидеть offered раньше tried-set. Первая промежуточная версия послеcommitSADD уточнена до полного прогона.

15 новых writer-сценариев: две штатные отмены в другой Session во время candidates; 12 комбинаций changed-status cancelled/accepted/offered и выходов candidate/empty/no_redis/limit; observer проверяет SADD до публикации offered. Прежний token rotation тест теперь использует штатный advance_after_no_accept вместо прямого вызова writer на offered. Все исходные проверки token сохранены.

Scoped пяти файлов 133 PASS / 1 существующее warning / exit 0. Свежий независимый reviewer повторил 133 PASS, P0/P1/P2 в этом объёме не нашёл. Ruff app и новый тест, diff check, secrets scan трёх Python-файлов (0 findings) PASS. FULL ещё не выполнен; публикация до него не выполняется.

Границы: это status-CAS, не защита searching ABA. Redis-вызов теперь под DB row lock; риск Redis изменён, а commit БД не состоялся сохраняется. Атомарность двух хранилищ, отсутствие позднего push после успешного commit и настоящая PostgreSQL concurrency не доказаны. Приостановленные пакеты не повторялись.

### FULL writer CAS завершён

Новый полный backend прогон на 79c65b513d8382ec87daa9ec1b2440d99262e744 (runtime 41cdd447d289ca22d4956b0dd3bcbe5dbddac76c): 6929 passed, 119 skipped, 804 warnings, exit 0, 602.14 s. Команда как scoped, но pytest tests -q; TMPDIR=/tmp/taxi-writer-full, --disable-socket --allow-unix-socket. Запущен после явного освобождения слота Amio, завершён без прерывания; слот возвращён. Это локальный gate без coverage порога и без PostgreSQL; удалённый CI отдельно не подтверждён. Runtime после FULL не менялся. Готовится новая отдельная ветка; merge/deploy не выполнялись.

## Следующий независимый этап: cheaper fallback продолжает подбор

База ea7b7f5 (tree опубликованного 76905fc), ветка test/friday-taxi-fallback-offer-progress-20261118. Подтверждён isolated HTTP RED: POST alternatives при снижении цены очищал current_offer_driver_id/offer_expires_at, но сохранял status=offered. Ни caller, ни lazy reconcile, ни worker stale-offer не продолжали matching: они требуют непустой expiry. С worker заказ в итоге лишь закрывается stuck-cleanup (по умолчанию через 6 часов), без него может оставаться так бессрочно. Первая проба теста не снизила цену из-за дальности маршрута и не считается воспроизведением; после фикса fixture на короткий маршрут получен точный orphan-offered RED.

До реализации отдельный независимый reviewer принял минимальный план без изменения pricing semantics. Runtime 7dd217d7b1a8fa562bbaabb8b67040573b316745: fresh refresh под row-lock и повтор статуса внутри service; при реальном снижении цены offered атомарно переводится в searching вместе со снятием старого предложения. После commit/reclamp вызывается try_offer_next, не start_matching, поэтому счётчик/лимит текущего круга не сбрасывается. Без изменения цены оффер не перезапускается. Формулы, тарифы, расчёт базы/скидки, структура API-ответа не изменены.

7 регрессий: достижимый HTTP сценарий; кандидат/нет кандидатов с сохранением round; свежий token и old-token decline; unchanged-price offer; stale fallback после настоящих accept/cancel не меняет статус/деньги; старый водитель не принимает переотправленный другому оффер (строго recipient guard 403 с соответствующим текстом). Первоначальное ожидание 409 у последнего теста исправлено по реальному контракту _guard_actor, не ослаблено до произвольной ошибки.

Scoped 111 PASS, 2 warnings, exit0. Свежий reviewer независимо повторил первые110 PASS и отдельно финальные7 PASS; блокирующих замечаний нет. Ruff app/new test, diff check, secrets scan двухPythonфайлов0 PASS. FULL ещё не завершён, публикация до него не выполняется. Границы: SQLite не проверяет реальные PostgreSQL locks; delayed same-driver accept не получает token и не защищён этим пакетом. Никакой общей generation/ABA/distributed-atomicity гарантии. Main, production, клиенты и приостановленные пакеты не менялись.

### Fallback FULL завершён

Новый полный backend прогон на7a9a13e (runtime7dd217d): 6936 passed, 119 skipped, 804 warnings, exit0, 604.36s. Изолированная SQLite, TMPDIR=/tmp/taxi-fallback-full, pytest tests -q --disable-socket --allow-unix-socket; DATABASE_URL и proxy env удалены, TZ=UTC. Слот согласован с Amio и освобождён после фактического exit. Runtime после прогона не менялся. Это локальный FULL без coverage порога; PostgreSQL-конкурентность и удалённый CI отдельно не подтверждены. Готовится отдельная публикация без merge/deploy.
