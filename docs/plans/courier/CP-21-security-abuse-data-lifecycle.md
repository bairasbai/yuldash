# CP-21 — Безопасность защита данных и злоупотреблений

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | политика ПД/retention/проверки/санкции/провайдеры, удаление данных; LG-02/06/08/10 |
| Зависит от | 21A docs contract после CP-01 и до M1; 21B-core после M5 до cutover; 21B-features после соответствующих APIs; 21C-Android/PWA после B; 21D перед CP-23 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A контракт угроз/данных/ACL до кода → B сервер hardening → C оба клиента → D независимая security/privacy приёмка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 03,05,07,08,09,10 |
| Зона файлов | backend/app/security.py/account.py/cleanup.py, routers courier/parcels/share/chat/incidents, upload/livepos helpers; Android/PWA caches/auth/outbox; новые courier security/retention tests |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Телефон и адрес не остаются у посторонних после заказа, чужой код/ссылка не управляют посылкой, мошенническая схема не превращается в списание.

## 2. Что уже есть в коде

User/security/access, visibility/block, private upload, Incident/SOS/share уже есть. strict courier identity config 508 defaultfalse. Приватность существующего return GPS намеренно ограничена.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Согласовать threat model courier, role/phase serializers во всех каналах, data inventory+retention/account deletion и abuse signals с human review. Не создавать универсальный «KYC решает всё».

Инженерный договор ниже предлагается для реализации после review. Численные продуктовые параметры, договоры и production включение не утверждаются этим планом; открытые решения указаны в [gates](06-launch-gates.md).

### A Контракт до кода

Data inventory, threat model, purposes/retention/ACL и K3/K6 договоры без product edits. Эта часть predecessor CP-02/M1, не зависит от будущих CP features. Решения сроков/санкций OWNER_PENDING.

### B Сервер

1. Threat model: IDOR, token theft, account takeover, fake GPS/proof, colluding sender-courier, promo abuse, ghost delivery, cash/COD fraud, oversized media, webhook spoof.
2. One serializer allowlist per role/phase для GET/list/commands/replay/websocket/push/admin/export.
3. Rate limit by account/order/device/network with appeal/safe fallback, server trust not device clock/GPS alone.
4. Secrets hash, upload limits/EXIF, signed short media access, least privilege support.
5. Retention D3 for each new table+blob+backup; delete/revoke/hold scenario verified, no unbounded PII logs.
6. Incident response owner, containment+notification workflow по актуальным нормам; выводы по сигналу не guilt.

### C-Android Клиент Android

Account boundary удаляет secrets/PII caches, но сохраняет bounded pseudonymous unresolved intent metadata по C3/K6; не wipe всех dedup keys. Encrypted minimal active passport/outbox, foreground GPS policy, sensitive notifications redacted; permission denied/withdrawn safe, no forced always-on permission.

### C-PWA Клиент PWA

Token no referrer/indexing, CSP/cookie/session controls per architecture, cross-tab logout, logs/analytics redaction, no receipt/phone in public cache.

### D Независимая проверка

Security/privacy review после feature coverage. Core review обязателен до cutover; feature review до capability rollout, formal cross-family и runtime evidence отдельно.

### Уточнение R3

K6 typed user/service anchors с generation и scoped machine grants не предоставляют человеку finance роль. Grant rotation не меняет receiving authority, однако отзывает attributable unused proof. Сроки/credentials/legal approvals по-прежнему отдельные gates.

### Уточнение R4

Защита классификации 11 минимизирует данные: no always-on GPS, cross-account tracking или вывод о fraud из отсутствия пассажиров/села/низкого дохода. Explainable reason codes, ограниченные lawful signals, human appeal и safe custody recovery обязательны.

### API и события

K3 gate matrix и K6 actor anchor deletion; revoked session всегда denied, expiry eligibility оставляет только reauthenticated safe resolution. C7 actor/phase enforcement, no client actor/admin/paid fields. Recipient bearer scopes минимальны; auth revoked rechecked on every mutating command/replay.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

21A фиксирует purpose/TTL/hold contract до M1, не утверждает срок вместо владельца/юриста. 21B-core интегрирует auth/gates/actor-anchor после M5 до K-CUTOVER; 21B-features делает ACL с владельцем соответствующего CP, не создаёт dependency cycle. Constraints/indexes только через D2. Account deletion scheduled state with unresolved custody/legal obligations, audited later anonymisation.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Доступ к этой доставке закрыт». «Не сообщай код до передачи вещи». «Для этого шага нужны…» без запугивания и фальшивых гарантий.

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Denied/revoked/expired/blocked/under review/no permission/delete pending/legal hold explanation; support remains accessible where lawful.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-32: parcel-only легален, suspicious route требует reason/review, не auto fee/fraud verdict; no surveillance permission expansion; revoked auth всё равно denied.

R3-WORKER/GRANT: revoked machine grant не capture; public spoof denied; recipient rotation отзывает старый scope без самопроизвольной смены лица.

SIM-11/13/15; exhaustive role×phase×channel tests; crossorder media/key/proof, GPS spoof/stale, token revoke replay, PII log scan, storage/backup TTL, delete active parcel, admin session downgrade, abuse falsepositive appeal.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Threat model and data inventory reviewed независимым security/privacy reviewer; критические IDOR/secret leak/custody bypass отсутствуют в выполненных тестах. Законные сроки не выдуманы.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не раскрывать адрес «для удобства матчинга». Не строить биометрическую идентификацию/скоринг без отдельного задания/основания. Нельзя считать рейтинг/НПД гарантией честности.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
