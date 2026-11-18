# Driver reservation / admission: protocol diagnostics before implementation

База local e0e78a8 / remote 2200b7c95590b68ef5a94ccc6f61ef43182db812. Отдельная test-only ветка, без activation/wiring/production migration. План требует независимой оценки до тестов.

## Разные гарантии

Storage publication атомарно пишет offer и committed exclusion, но не резервирует водителя между orders. Предыдущий реальный PG подтвердил, что две публикации разных orders для одного driver возможны. Допуск к работе шире reservation: действующий matcher учитывает presence/GEO, eligibility и ranking; accept отдельно вызывает guard_driver_ready. Нельзя заменить эти правила фактом User lock, existing user ID или `eligible=True`.

Текущий accept (`routers/instant.py:752–777`) после первоначального guard берёт User FOR NO KEY UPDATE, проверяет другие accepted/arriving/onboard, затем transition берёт Order FOR UPDATE. Matcher `busy_driver_ids` проверяет active и offered обычными SELECT, без User lock. Значит единый порядок для будущей cooperative reservation следует исследовать как User→Order, не добавлять Order→User в storage primitive. Existing User lock не означает, что все изменяемые источники admission автоматически защищены им.

## Предлагаемый безопасный срез: только PG counterfactual

Никакого app helper в этом пакете. Только тестовый wrapper в PG tests:
1. Existing active caller Connection, только fixtures.
2. Lock реального User row FOR NO KEY UPDATE.
3. Затем lock target Order FOR UPDATE. После получения обоих locks выполнить fresh busy query по active driver_id и offered current_offer_driver_id, исключая target order. Offered считать занятым независимо от expiry: истёкший статус должен reconcile отдельно, не скрытая TTL-резервация.
4. При busy бросить локальный test-only Busy exception без publication.
5. Вызвать настоящий `publish_sql_offer_lab`, который берёт Order lock и пишет offer/history в том же outer transaction.
6. Caller commit/rollback явный; никакого Redis/push/Session wrapper или product routing.

Это counterfactual дисциплины cooperative writers, не готовый reservation API. Не копировать eligibility в тест; synthetic fixtures и отдельная надпись no admission guarantee. Протокол не подключён к production. Операции с User всегда до Order; нельзя вызывать wrapper, уже удерживая Order из initializer в той же transaction. Test setup initializer commit отдельно. Future real init+reservation orchestration должен выбрать порядок до любого owner lock, не инвертировать его через reentrant вызов.

## Проверяемые гипотезы и controls

- READ COMMITTED, два разных initialized orders / один настоящий driver User / два физических connections: первый User lock и publication удерживаются, второй реально ждёт (pg_blocking_pids). После первого commit второй видит busy и не публикует; exact owner/history второго неизменны. После первого rollback второй успешно публикует. Проверять distinct backend PID и фактический blocker, не sleep-only.
- Offered и active accepted/arriving/onboard учитываются. Своё target order не является чужой занятостью, но storage own guard всё равно строгий. Missing User не разрешает публикацию.
- Mixed writer counterexample: пока cooperative transaction держит User lock, прежний storage-only writer другого order без этого lock может опубликовать. Нужно реальное конкурентное выполнение и committed SQL readback обоих. Это доказательство обязательного all-writer drain, не допустимая production схема.
- REPEATABLE READ hypothesis: waiter заранее установил snapshot, User lock сам не меняет User row, после ожидания busy SELECT может остаться на старом snapshot. Наблюдаемый double publication либо 40001 сохранить точно; не подгонять тест под желаемое. READ COMMITTED positive control рядом. Если double publication подтверждается, будущий narrow protocol требует явного READ COMMITTED или другой проверенной сериализации, не обещать correctness на всех isolation levels.
- Admission counterexample: изменение DriverProfile.online другим connection без User lock может пройти пока reservation lock удерживается. Это лишь конкретное доказательство, что User lock не защищает все eligibility tables; не общий аудит всей политики и не runtime fix.
- Не вводить искусственный deadlock в production. В тесте допустим bounded local lock-order evidence, но первый пакет может ограничиться inventory и отсутствием inversions в самом test wrapper. Deadlock retry semantics и всех writers order остаются блокерами.

## Что потребуется до реализации/активации

Полный writer inventory: matcher/manual assignment/accept/cancel/reassign/timeout/fallback/retry, все paths создающие или меняющие driver ownership; отдельный review глобального lock order. Два FK driver/current_offer поля в одной таблице не дают простой общей unique constraint без нормализованной reservation representation. Таблица reservation — отдельная миграция/TTL/reconcile/ownership/retention задача, не добавляется сейчас.

Допуск: mutable profile/application/permit/quality/rest/debt/blocklist/user fields и time-expiry не замораживаются одним User lock. Нужно определить конкретную точку authoritative recheck, общий transaction/API boundary и допустимую неизбежную гонку после него; все существующие policy helpers сохранить. Не держать DB locks во время GEO/network/push. Ограниченный candidate budget, свежий snapshot criteria и exact cycle mode обязательны.

All-writer participation, isolation contract, rollback entire business unit, own immutable snapshot до commit, guarded dispatch после commit, uncertain commit/no blind retry, legacy Redis path и SQL cycles drain/rollback plan остаются обязательными. Mixed fleet не заявляется безопасным. No main/prod/real DB/paused actions.

## Gates

Независимый pre-code review → test-only author static и guarded real PG → independent static/PG → сохранение отдельной ветки. Runtime app код не меняется; предыдущий FULL publication применим только к неизменённому app, не считается прогоном новых tests. Новые PG tests запускаются отдельно с полным существующим PG regression, ресурс согласуется с Amio. При обнаруженном runtime defect сначала зафиксировать evidence и отдельный план, не расширять пакет молча.

## Read-only inventory уточнение

Исследование подтвердило: matcher instant_service:3402–3426 без User lock; accept routers/instant:752–777 User→Order; direct service transition не воспроизводит router gate. Cancel/reassign/advance/route/fallback/worker освобождают или меняют owner с Order-first; нельзя вкладывать в них новый User lock. account.py:658–659 чистит recipient при удалении пользователя, rides.py:1417 тоже использует User driver lock: межрежимный граф и deletion требуют отдельного аудита. Manual assignment в просмотренных paths не найден, полного доказательства отсутствия нет. Eligibility writers/profile online не имеют универсального User-lock контракта; истечение документов и Redis presence меняются даже без SQL writer. Эти ограничения остаются activation blockers.

## Принятые pre-code условия journal

PASS test-only плана `6e20b99`. RC busy SELECT — отдельный statement после фактического User wait и Order lock, не объединённая snapshot-query. Setup init commit отдельно, distinct PID/pg_blocking_pids, Busy оставляет exact owner/history второго без изменений, caller outer rollback явный. RR isolation устанавливается до SQL; старый snapshot реально закрепить busy/history чтением до первого commit, User row не обновлять. Первый исследовательский запуск фиксирует реальный outcome; итоговая regression не допускает catch-any/любой исход PASS: double publication либо точный SQLSTATE/statement-stage, без universal RR proof. Mixed bypass должен завершить реальную publication и commit пока User lock удерживается; independent readback обоих. Profile online real UPDATE+commit/readback while lock held, не полная проверка admission. Дополнительно expired offered всё ещё busy, terminal свободен, missing User reject, target исключён из busy при сохранении strict storage guards. Предварительно удерживаемый Order lock запрещён контрактом test wrapper; deadlock/crossmode/deletion аудит остаётся неполным.

## Авторские результаты

Tests `ecd76bc`: 13 cases collected, exit 0, 0.61 с; independent collection 13, exit 0, 0.41 с. Ruff F/bash/diff PASS. App/runtime diff пустой относительно `e0e78a8`.

Author actual PG regression: **205 PASS**, 11.11 с, exit 0 (`/tmp/taxi-reservation-protocol-pg.log` / `.exit`); временный cluster очищен. Новый suite подтвердил конкретные outcomes: RC cooperative commit→Busy, rollback→второй winner; при RR старый snapshot действительно дал две committed publication, не 40001. Mixed bypass committed до освобождения User lock, профиль online тоже реально изменился и прочитан до unlock. Эти PASS означают воспроизведение ограничений, не их устранение. Independent PG ожидается.

## Независимая финальная приёмка

Independent actual PG: **205 PASS**, 10.21 с, actual exit 0 (`/tmp/taxi-driver-protocol-independent-pg.log` / `.exit`), временные кластеры отсутствуют. Финальная независимая приёмка `ecd76bc` PASS, открытых P0/P1/P2 нет. Ресурс передан Amio. Runtime app diff пуст; новый FULL не запускался, прежний publication FULL не объявляется прогоном этих новых тестов. Новые 13 PG случаев и весь прежний PG regression действительно выполнены дважды.

Следующий допустимый шаг — отдельный дизайн all-writer/isolation/admission/lock-order boundary с учётом этих counterexamples, не включение текущего wrapper. User→Order lock сам по себе не устраняет RR/mixed-fleet/profile races; production protocol не готов.
