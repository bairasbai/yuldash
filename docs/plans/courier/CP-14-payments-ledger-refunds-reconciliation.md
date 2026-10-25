# CP-14 — Расчёты возвраты и сверка

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | деньги, провайдер/банк/ККТ договоры, миграция, новые финансовые обязательства и production |
| Зависит от | CP-02/05/06/07/11/12; LG-01…05; CP-13A1 только для shopping DTO; foundation не ждёт 13A2 COD activation |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,03,04,05,07 |
| Зона файлов | backend/app/ledger.py, debt.py, routers/{payments, courier, parcels}.py, models.py; Android/PWA earnings/receipt; новые backend/app/courier_settlement.py и financial integration tests |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Не теряются оплаты и выплаты, повтор не списывает дважды, возврат клиента не превращается в бесследный минус курьера. Видно что подтверждено, а что только обещано.

## 2. Что уже есть в коде

courier.py1924–1990 Payment комиссии yookassa/manual СБП со snapshot; payments.py217–250 закрывает causal delivery IDs, повтор защищён. Прямой service/goods payment курьеру:1432. Это не marketplace checkout/split/escrow.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Единый журнал обязательств/проводок и reconciliation, отдельный manual unverified режим и provider-gated live flow. Не переписывать существующую защиту Payment snapshot и не создавать второй кошелёк.

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Сервер

1. Реализовать F7/F8 поверх existing ledger/Payment: immutable entries, balanced transactions, unique external refs.
2. Provider interface sandbox: create/authorize/capture/void/status/refund/payout с capability support, без выдуманного escrow API.
3. Webhook verify signature/account/order/amount/currency + dedup; unknown reconcile.
4. Refund allocation/reservation prevents over-refund; payout and dispute holds separate, no unauthorized clawback.
5. Manual payment verification audit; daily unmatched/mismatch report и recovery queue, fiscal events separate.

### B Android

Statement gross/commission/goods/expenses/accrued/held/paid/refund pending. User mark paid не отображается как verified receipt; банк unavailable объясняется спокойно.

### C PWA

Те же статусы, пагинируемая выписка/export по ACL, no fake online-pay button до gate. Admin не правит balances напрямую.

### Межмодульный договор R2

F10/F12 нормативны: per-component payer/payee obligations, partial/direct/manual payments, bounded allocations и economic payment grouping, отдельная COD custody. Authorize≠capture≠income≠payout. Pending/unknown резервирует доступный долг; новый attempt не превышает remaining−reserved−unknown. Void/capture races, auth expiry scheduled orders, late capture X при Y и external surplus refund имеют явные состояния; никогда второй courier payable. Capture trigger/provider terms остаются OWNER_PENDING; до утверждения LIVE closed.

### Уточнение R3

F13 outbound cash guard отдельный от F8 банковских refunds и F12 inbound collection. Общие account+collection reservations исключают cross-kind reuse, unknown сохраняется. Immediate capture разрешён только approved capability+consent/reserve/trigger, two-stage требует valid authorization. Worker principal K6, не user actor.

### Уточнение R4

OD-2026-10-25-03 подтверждает product scope ready pickup+buy_bring и payer sender/recipient/merchant. Это не разрешает исполнителю покупать до F11 start, не подтверждает долг третьей стороны и не закрывает legal/НПД/фискальные/provider gates. Consent конкретного payer до acceptance; goods/service/commission отдельно.

### API и события

F7/F8 provider adapter contract, immutable settlement event refs, errors PAYMENT_RECONCILIATION_REQUIRED/REFUND_LIMIT_EXCEEDED. Payout consent/identity handled provider.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M9 allocations/attempts/reservations/COD movements + ledger/refund/payout/reconciliation, расширяет M3 obligation identity; Payment causal snapshot не перезаписывать. Old manual paid vs verified различать источником. Financial retention и legal hold, no destructive downgrade after external movement.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Перевод пока не подтверждён». «Возврат отправлен, ждём подтверждения банка». «Начислено», «Доступно к выплате», «Выплачено» раздельно.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Pending/authorized/captured/failed/unknown/refund pending/partial/paid/held/adjustment, reconciliation mismatch, provider outage/fiscal failure.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-35: готовый заказ vs список, все три payer роли с/без consent; recipient link не платёжное согласие; бюджет/замена/чек/остаток не склеены с delivery fee.

R3-CASH/RPAY/WORKER: remittance/refund races, partial collection/account bounds, foreign merchant rejected, late two-real-outflows → shortfall; one-stage без fake auth ID; service grant spoof/revoke denied.

R2-PAY: service paid/goods partial/commission due не становятся all_paid; manual+bank double evidence одного debt, two real captures → surplus clearing+refund, unknown X запрещает Y; cancel/void/capture и scheduled auth expiry, refund after payout, concurrency of reserved refunds.

SIM-04/09; duplicate/cross-order/forged webhook, crash after external success, currency/amount mismatch, partial concurrent refund, payout fail then success, manual+bank duplicate, accounting invariants. Sandbox is not live acceptance.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Каждое начисление/списание/возврат/выплата воспроизводимо и связано с provider or manual evidence. Zero unmatched не принимается без external statement. LIVE gate закрыт до владельца/юриста/банка.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не принимать деньги «на свой кошелёк» как разрешённый транзит. Не считать Payment commission готовым агрегаторским эквайрингом. Не хранить card secrets/не обходить provider confirmation.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
