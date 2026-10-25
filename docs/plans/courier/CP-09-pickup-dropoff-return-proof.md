# CP-09 — Подтверждение каждой передачи

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | proof policy/ПД/сроки/поддержка/миграция; SMS provider отдельно |
| Зависит от | CP-02/07/08; CP-21A; LG-06/08 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 01,02,04,07,08,10 |
| Зона файлов | backend/app/routers/parcels.py, models.py, uploads/access helpers; новые backend/app/courier_proof.py/tests/test_courier_proof_v2.py; Android ParcelHandover dialogs/CourierScreen; PWA ParcelPhoto/ParcelProblemActions/CourierScreen |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Видно кто передал и кто принял конкретную вещь. Код вручения не подходит для забора, соседней посылки или возврата.

## 2. Что уже есть в коде

parcels.py:1260–1360 server delivery code; return-done1624–1655 без sender proof; models один confirm_code. Фото и receipt существуют, это не full custody chain.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Раздельные pickup/dropoff/return challenges и evidence с phase/stop/assignment binding. Существующий server-verified delivery code сохранить совместимым, не ослабить.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Challenges C7 hashed one-use, TTL/attempt/rate limits, no secret to courier.
2. Proof consume и transition atomic; повтор возвращает прежний result, чужой/старый proof отвергается.
3. Media ACL/type/size/content check/EXIF purge, uploads never public; pending upload не transfer.
4. Нет кода/сети/доступности: case и разрешённая альтернативная двухсторонняя процедура, а не admin bypass без причины.
5. Return доказательство отправителя/уполномоченного лица; contactless только explicit prior consent и допустимая категория.

### B Android

Проверка phase и order на dialog, clipboard/screenshot/privacy policy; ввод не исчезает при сетевой ошибке, но секрет не persist plaintext. Camera denied альтернативный процесс.

### C PWA

PWA sender/recipient/courier separate UI, фото status upload/accepted/rejected, старый completion callback не закрывает другое окно.

### Межмодульный договор R2

K1/C7 proof привязан к sorted item_ids/hash+item_versions и receiving_party_ref/recipient_authority_version. confirm_dropoff/return обновляет только scope предметов и затем пересчитывает aggregate, не безусловный delivered/resolved. K2 storage/handoff используют отдельный transfer proof с old/new holders. Recipient revoke/replace/channel change и consume сериализуются под одним order/authority/challenge lock.

### Уточнение R3

M1 Stop identity уже является целью minimal proof FK; M3 не меняет identity. Grant-origin proof содержит issued_via_grant_id/generation и invalidates при rotation; channel verification не повышает authority generation и не отзывает сама себя.

### Уточнение R4

Sealed documents в 11: proof внешнего конверта/целостности и authorized recipient, без фото содержимого/документных персональных данных. Несколько bags/docs — item IDs; partial proof не заканчивает весь заказ.

### API и события

proof-challenges/proofs и confirm_* commands из C4/C7; PROOF_INVALID/PROOF_EXPIRED/PROOF_ALREADY_USED/ASSIGNMENT_CHANGED. Successful challenge delivery≠physical transfer.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M1 содержит minimal ProofChallenge/CustodyTransfer/Evidence identity; M7 расширяет policies/recipient authority/item bindings, UNIQUE consume. Старый confirm_code мигрирует только как legacy dropoff, не pickup/return; historical returned remains unverified.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Назови код только когда передаёшь вещь». «Это код другой передачи». «Не получилось подтвердить. Вещь остаётся у тебя, свяжись с поддержкой».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Challenge requested/channel unavailable/invalid/locked/expired/proof pending/confirmed/unknown custody; no code not dead end.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-29/31: documents content photo не требуется; sealed damaged item → case; item subset proof/receiver authority/return intact, privacy обоих endpoints.

R3-RECIPIENT: replace → pending → verify → active → issue proof → consume; verify/revoke обе очередности; rotation old grant proof denied, новый valid proof достижим.

R2-PROOF: два из трёх items handed → partially_completed/mixed custody; чужой itemset отклонён. Код A → revoke A → verify B → submit old A fail; consume-first сохраняет реальное событие и case, revoke-first исключает передачу; contactless consent такой же versioned authority.

SIM-02/07/11; OTP brute force/replay/crossphase/crossorder, race consume/return, reused photo, foreign media does not stop wait, revocation during upload, late response. Physical test T-RT-01 отдельно.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждому custody transfer соответствует реальное применимое доказательство либо explicit exception case; server verified код не выдаётся за абсолютную юридическую гарантию личности.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не перепутать rideshare manual boarding code с courier server proof. Не требовать фото лиц/паспорта для обычной посылки. Нельзя считать георадиус/фото/HTTP200 доказанной передачей без договора.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
