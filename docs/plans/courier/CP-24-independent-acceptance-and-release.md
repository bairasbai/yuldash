# CP-24 — Независимая приёмка полного режима

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | реальный сервер/телефоны/провайдерские прогоны, выпуск/включение; отдельно по scope |
| Зависит от | Все реализованные CP для заявленной capability; CP-23 readiness definition (не enable); исходный COURIER scope |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A полнота плана и tabletop до кода → B стенд/автоматические проверки → C реальные клиентские цепочки → D independent verdict → E owner release |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | COURIER-E2E-01…10 целиком плюс новые capability profiles |
| Зона файлов | docs/audit-traceability/courier-scope.md/courier.json и audit-blocks по правилам; tools/audit_traceability/check.py используется read-only; evidence outside product tree; product fixes отдельными CP |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

В конце есть доказательство работающего сервиса для отправителя, получателя, курьера и поддержки, а не только большой план или зелёный список unit-тестов.

## 2. Что уже есть в коде

courier-scope.md:1–68 содержит 10 обязательных групп/четыре роли; current manifest unknown без implementations/tests/acceptances. Existing automated tests/CI inventory не сегодняшний PASS. Реестр audit-blocks текущими tools полностью не прочитан, статусы не выдуманы.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Два уровня независимой проверки: план completeness/consistency отдельно; затем реализация/runtime/production. Симуляции SIM помогают найти дыры, но не заменяют тесты.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Независимая проверка документа

1. До кода fresh reviewer сверяет scope → CP → contracts → gates → migration/queue → tests, economics и SIM. Blockers исправлены и повторно проверены на final document hash.

### B Автоматические и стендовые доказательства

2. Код target SHA/build provenance, автоматические pytest/guards/migrations/PG races, failure injection before/after commit, security/data lifecycle.
3. Actual app flows from empty forms, not preseed happy path; all roles and Android↔PWA pairs.
4. Traceability chain evidence with hashes, exact commands/exitcodes/env, unknown/skip honest.
5. Discovered product bug returns to owning CP, new SHA invalidates affected proof; no pass-by-editing-test-only.

### C Физические клиентские цепочки Android

RU/BA×themes, layouts/accessibility, emulator test corpus, затем два физических телефона real GPS/MapKit/background push/SOS/recipient link/physical handover. FCM fixture не real push.

### C Физические клиентские цепочки PWA

PWA foreground/background limits, cross-browser supported matrix, reconnect/restart/login changed, screenshot evidence of states, receiver no-account flow. API only не PWA acceptance.

### D Независимый verdict реализации

Только выполненные применимые доказательства target SHA; требуемая formal cross-family проверка отдельно от авторских тестов и same-family docs review. Blockers возвращают работу владельцу CP.

### E Решение владельца о выпуске

После независимого verdict и owner/legal/provider gates, с границами pilot/rollout. Ни автор, ни reviewer не включают LIVE своим статусом.

### Межмодульный договор R2

R2 closure matrix принимает только specification fixes, не product test PASS. Independent documentation audit отдельно от formal cross-family acceptance проекта: последняя PENDING до требуемого reviewer family и первичных артефактов. Implementation/runtime NOT_EXECUTED; legal/provider/owner release NOT_APPROVED. Даже положительный same-family аудит не переименовывается в formal cross-family.

### Текущие границы доказательств — REV-EVID-01

Это план, а не акт приёмки локальной implementation-задачи. Сообщённые частичные наборы 58/37/26 pass не переносятся в CP-24: без проверенных target SHA, полного scope и первичных артефактов они не подтверждают ни весь CP, ни provider/device acceptance. Локальные неопубликованные поправки не source of truth и не исправление published/main. Android compilation сообщалась BLOCKED из-за DNS `services.gradle.org`; это не PASS и не доказанный дефект приложения. Физические устройства не проверены; provider и общая test acceptance не доказаны. Итоговая browser-попытка FAILED на selector кнопки «Мои» до tracking: это не evidence работы карты. Дополнительные backend/PG/PWA прогоны из stop-отчёта остаются частичными; финальный dirty diff не прошёл независимый повторный review после авторских поправок к промежуточным queue-дефектам.

Открытые implementation gaps из stop-отчёта: возврат без подтверждения отправителя (CP-09/12), state versioning/retries (CP-02/07), route parcel capacity (CP-08 и документ 11), immutable quote/commission (CP-05/06), deletion/custody P1 (CP-02/21). Эти CP не закрыты локальными опытами; список не заменяет полный acceptance scope.

Отчёт будущего исполнителя должен отдельно указывать: (1) code readiness на exact SHA, (2) staging/synthetic evidence, (3) provider/legal/operations gates, (4) physical Android/PWA acceptance, (5) независимый verdict с family, (6) owner activation конкретного scope. Для каждого уровня нужны собственные status/evidence/blocker, не общий зелёный итог. Pilot city, часы/ответственный поддержки и доступный storage/recovery пока без ответов; LG-11/CP-12/23 остаются OPEN, operational promises запрещены. Formal cross-family review PENDING.

### API и события

Контрактный diff exact routes/errors/units/permissions/versions reviewed. Provider sandbox and production controlled verification separated. No real charge merely to fill report.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

Every M migration previous-main synthetic fixtures SQLite+PG, forward/rollback crash recovery and amounts/ACL preserved. Production backup/restore/deploy только owner authorization. No destructive test on live or copied personal data.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

Отчёт: «План проверен», «Реализовано на SHA…», «Проверено на стенде», «На физических телефонах не проверено», «Выпуск разрешён» отдельными строками; никаких общего «всё готово» до всех условий.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

NOT_EXECUTED/BLOCKED/FAILED/PASSED_WITH_EVIDENCE отдельно от plan_review_status. Partial capability rollout не blanket acceptance.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

R2-REVIEW: сверить точные frozen bytes, все 13 blocking/8 nonblocking finding mappings, links/part DAG, runtime labels и open owner gates; отдельно будущая formal cross-family code acceptance по AGENTS.

Весь 05 scenarios/T matrix и COURIER-E2E01…10. Mutation tests защита от removed role/quote/version/proof/ledger/timeout guards. Reviewer воспроизводит критические P0 trails независимо. Каждый skipped test блокирует соответствующее обязательное требование.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Свежий принимающий другой семьи по AGENTS/PR211, не автор; если такой недоступен, review remains BLOCKED, same-family audit полезен, но не официальная приёмка. Owner подтверждает release отдельно. Требование/код/тест/приёмка связаны одним target SHA.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не выдавать проверку Markdown, статический source audit, дизайн-симуляцию, green CI tools или чужой APK за готовый Юлдаш. Не уменьшать существующие must подусловия, не auto-mark accepted в новом index.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
