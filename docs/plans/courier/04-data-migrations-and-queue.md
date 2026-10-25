# Данные миграции и очередь общего кода

## D0 Имена и границы

Это предлагаемые новые сущности и поля. Физические имена выбираются в миграции единообразно с SQLModel/Alembic проекта; они не заявляются существующими. `ParcelDelivery`, `CourierApplication`, `CourierProfile`, `Payment`, `Incident`, Message/TripShare/ledger уже существуют и расширяются без второй системы истины.

Денежные поля BIGINT в копейках с CHECK≥0 там, где значение не знаковая проводка. UUID/opaque tokens не заменяют ACL. Существующий backend/timeutil сохраняет naive UTC; новые courier timestamps в БД также naive UTC через проверенный K4 adapter. На API только явный offset/Z; local wall time без зоны отвергается. Общий utcnow() и taxi сравнения не менять в courier migration. Отсутствие исторического времени остаётся NULL. Enum изменение не должно ломать старые сериализаторы. JSON используем для immutable versioned snapshots, но searchable FK/state/amount/expiry/unique keys остаются нормализованными столбцами.

## D1 Минимальная целевая схема

| Группа | Обязательные поля и ограничения |
|---|---|
| ParcelDelivery extension | `state_version BIGINT NOT NULL default0`, `terms_version BIGINT NOT NULL default0`, `fulfillment_state`, `custody_state`, `custody_evidence_level`, `last_transition_at`, `cancel_requested_at/reason`, `capability_version`, `service_type`, `service_kind`, `fee_policy_id/version`, `active_assignment_id nullable`, `quote_id nullable`; CHECK projection и разрешённые комбинации проверяются сервисом+тестом |
| CoveragePolicy/Capability | immutable policy_version, stable area_id/type, polygon/dataset_version, service_kind, transport_class, effective_from/to, status, reason, owner_change_id; UNIQUE version+area+kind+transport |
| CourierEligibility | courier_id, capability, transport_identity/version, evidence_ref, status, valid_until, decided_by, reason; no self-approve; active capability query по индексу |
| CourierQuote/QuoteLine | поля F2; UNIQUE id+version, lines position unique; quote_component_id UNIQUE(quote_id, version, component_id); planned payer/payee/consent requirements, НЕ obligation FK; total сверяется с суммой строк; expiry index; источник fallback сохраняется |
| QuoteUse/OrderQuoteComponent M3 | Для create quote: QuoteUse UNIQUE(quote_id, version) и UNIQUE(order_id); component mapping UNIQUE(order_id, quote_component_id), obligation_id UNIQUE FK PaymentObligation; create atomic, rollback no orphan debt, same-key returns mapping |
| CourierRemunerationSnapshot | order/assignment_id UNIQUE, gross/commission/guaranteed/reimbursement amounts, fee_base, rate_numerator/denominator, policy_version, counter_basis/value, accepted_at, terms_hash; immutable |
| DeliveryStop identity M1 | stop_id PK, parcel_id FK ParcelDelivery, sequence, kind pickup/dropoff/return, identity_origin=legacy_logical / declared, UNIQUE(parcel_id, sequence); no invented geo/address/time |
| DeliveryStop extension/Leg M3 | M1 stop получает nullable address private ref, exact geo/quality, IANA zone, UTC window; Leg(id, parcel_id, from_stop_id FK M1 Stop, to_stop_id FK M1 Stop, metrics); no new Stop identity replacement |
| CourierAssignment/Offer | assignment generation UNIQUE parcel_id+generation; active assignment-item slot UNIQUE item_id; scalar order assignment только homogeneous projection K1; offer UNIQUE parcel+courier+dispatch_generation, expiry/status/token hash; capacity reservation linked to assignment and affected legs |
| CourierCommandReceipt | Общая RS сущность если реализована; actor_anchor/key UNIQUE, operation/body hash, applied / rejected / closed_no_effect, safe outcome refs, created/committed/retention; actor anchor user/service kind с взаимно исключающими ссылками, FK RESTRICT, user association SET NULL по K6; отдельный повторный ledger запрещён |
| RideParcelPolicy/Reservation + OnRoutePriceRequest/DriverPriceProposal M6R | Ride FK, independent passenger_booking_enabled/parcel_enabled, ride/parcel/resource versions, item scope/units/weight/dimensions/leg reservations по 11; no conversion seats↔parcels; legacy binding nullable, default parcel toggle false |
| CourierEvent/Outbox | event_id UNIQUE, parcel_id/event_seq UNIQUE, aggregate_version, schema_version, cause_command_id; outbox event+consumer UNIQUE, lease_until, attempts, next_attempt_at, last_error_code, processed_at |
| CustodyTransfer/ProofChallenge/ProofEvidence | parcel/stop/phase/assignment_generation/terms_version, sorted item_ids/hash+item_versions, receiving_party_ref+recipient_authority_version, issued_via_grant_id/generation nullable для proof из grant, from/to role, evidence kind, hash/opaque media ref, created/consumed/revoked_at; одно consumption challenge UNIQUE; evidence immutable и actor-bound |
| RecipientGrant | parcel_id, token_hash UNIQUE, scopes, receiving_party_ref, recipient_authority_version, verified_channel_ref/version, expires/revoked_at, issued_by; отсутствие открытого phone/token в журналах |
| WaitSession/DeliveryAttempt | parcel+stop+phase, server times, rate snapshot, free/charged seconds, amount cap, cap_scope/policy/accumulator FK, component inclusion set; consumed+reserved bound под lock; один active wait; attempt ordinal UNIQUE, failed_reason, contact summary refs, next_action_at |
| OrderAmendment/Consent | order, old/new terms_version, proposed_by, change/quote hash, expiry/status; consent unique amendment+required_actor; accepted amendment quote UNIQUE(id, version) с purpose/target/base_terms guard; не применять частично согласованный change |
| PurchaseCommitment | F11 actor/list/consent/merchant/cap/expiry, assignment generation, status/version, unique external_operation_ref; start-before-spend, unknown blocks release; M8 |
| StorageTransfer | item-set/version, from/to holder identity, storage location private ref, authorized policy/consents, proof refs, next_action_at/owner, expiry/release status; M8, not terminal handback |
| DeliveryItem/AssignmentItem | K1 item_version/holder/proof/obligation; UNIQUE active item slot, legacy logical item not fabricated pieces; M1 |
| PaymentObligation foundation | F10 component/payer/payee/consent/currency/due/version; M3 immutable identity, legacy_unknown allowed; M9 allocation and provider effects, not paid by migration |
| PaymentAllocation/CollectionAttempt/CashCustody | F10/F12 obligation+receipt FK, amount/currency/bounds, reservation and external unknown, authorization expiry/void, economic payment group, COD immutable movements + единые CashDisbursementIntent/Reservation для remittance/refund/unknown и source collection allocations F13; M9 |
| ShoppingRequest/Line/PurchaseEvidence | approved list/version, quantities/caps/substitutions, receipt refs, actual amount, consent; immutable purchase evidence, total integer and limits |
| MerchantOrder extension | external_order_id+merchant UNIQUE, seller identity/version, prep window, ready_at, packing/temperature policy, handover/return authorisation; no menu/cart scope implied |
| Ledger/Refund/Payout/Reconciliation extensions | оригинальные transactions referenced, unique provider+account+external_id+operation, balance invariants F7/F8, reconciliation case owner/status; no editable paid flag as sole truth |
| SupportCase extension | reuse Incident(parcel_id); custody risk, SLA/next_action_at, assigned owner, reason/decision version, evidence refs, participants notified; non-financial vs financial permissions separated |

Schema-only expand может сливаться до полного feature API после фиксации его договора; это не требует завершить все будущие CP части и не включает capability. M8 не ждёт CP-15/14: merchant schema перенесена в M10 после M9. Новые migrations не вводят всё разом. Каждый expand добавляет nullable/default поля, затем shadow/backfill/валидацию; только после совместимости — constraints и переключение чтения. При историческом отсутствии route/quote/evidence пишем `legacy_unknown`, не достраиваем «фактические» координаты из центра города.

## D2 Порядок миграций

До кода перечитать свежий main, открытые RS/CP PR и фактическую одну голову Alembic. На исходной базе найден revision `cb_sos_retry_intent_20261021`, но это не зарезервированный будущий down_revision. Имена ниже описательные, без буквенных префиксов/дат/SHA будущих ревизий. Слияние ревизии означает её применение на следующем deploy, поэтому каждая требует отдельного owner gate.

| Очередь | Часть | Смысл | Синтетические previous-main строки и ожидаемый перенос | Откат |
|---|---|---|---|---|
| M1 | CP-02A | State/version/Stop identity/item/custody/event + minimal assignment identity, assignment-item, proof/transfer foundations; timeout safety adapter | Все 7 legacy statuses по K5; создать единственный legacy assignment/item без повторного назначения/уведомления; неизвестная custody не повышена | Только до новых команд можно убрать поля; после custody events предпочтительно forward fix |
| M2 | CP-03A | Coverage/capability/transport eligibility | car/cargo approved/pending/rejected, unknown locality; допуск не повышается автоматически | Остановить новые типы/зоны, сохранить исторические snapshots |
| M3 | CP-04A/CP-05A | Расширение M1 Stop адресами/окнами, новые Leg, immutable quotes/QuoteUse/component mapping, общие OrderAmendment/Consent и PaymentObligation identity | Два текстовых адреса/centroid, nullable exact point, старые цены в копейках | Legacy чтение допустимо с честным unknown; не терять принятые quotes |
| M4 | CP-06A | Courier remuneration snapshot | delivered/accepted/in_transit разных комиссий: завершённые не переоценивать, активные пометить legacy terms | Снимки не удалять после обещания; откат только совместимого кода |
| M5 | CP-07A | RS-05 durable commands/outbox + CommandActorAnchor FK migration K6 | Старые semantic twins и неопределённые retries: не придумывать исходные keys; новых duplicate side effects нет | Tombstones и outbox сохранить; rollback к blind retry запрещён |
| M6 | CP-08A | Offers/dispatch leases/capacity; расширение существующего M1 assignment | M1 assignment сохраняет identity; не создавать второй при backfill, capacity conflicts → case | Снять только неполученные offers, сохранить custody assignment |
| M6R | CP-08A2 после M6 и реально принятых RS-02/05/14/15 | Ride parcel toggle/binding, price request/proposal single-use binding и joint resource guard по 11 | Legacy Ride.category=parcel и standalone ParcelDelivery не связываются по совпадению городов; baggage=true не вес/места; historical/active flows сохраняются | Disable new bridge admissions; carrying reservations/обязательства не удалять, старый writer без общего guard не возвращать |
| M7 | CP-09A/CP-10A/CP-11A | Расширение M1 proof: policies/recipient authority/wait/attempt | Старый confirm_code не становится pickup или return proof; legacy links не расширяются | Новые secrets revoke; закрывать текущие передачи безопасным процессом |
| M8 | CP-12A/CP-13A1 | Return/storage exceptions, PurchaseCommitment, shopping extension общих M3 amendments | Старые purchase/canceled-with-custody/returns не теряют суммы и case; food category пока не создаёт merchant; merchant extension ждёт M10 | Неудовлетворённые обязательства и receipt refs сохраняются |
| M9 | CP-14A | PaymentAllocation/CollectionAttempt/COD custody, settlement/refund/reconciliation extensions | pending/paid commission Payment со snapshots, manual unverified; no fake customer capture/payout | Банковский журнал не удалять; provider state reconcile, forward fix |
| M10 | CP-15A schema/CP-18A/CP-19A | Merchant extension и batch/history/ops indexes | Unbatched orders remain independent, all returning included, paginated ordering stable | Можно выключить batch, но не разобрать путь с вещами без custody transfer |

**Точный M1 → M3 shape:** M1 сначала создаёт DeliveryStop identity с перечисленными выше columns/FKs, затем DeliveryItem с pickup_stop_id/destination_stop_id FK на существующий Stop, assignment/assignment-item и minimal ProofChallenge/CustodyTransfer с nullable stop_id FK для неизвестного исторического proof. Для legacy logical parcel создаются стабильные logical pickup/dropoff identity без координат; отдельный return stop только как declared legacy target, не доказательство посещения. Отсутствующий historical proof не синтезируется. M3 добавляет к тому же stop_id address/window/zone/quality columns и таблицу Leg с FK на M1 Stop, заполняя только доказанные значения; unknown остаётся NULL/legacy_unknown. M1 и M3 по отдельности проходят FK validation, а новые proof commands по-прежнему закрыты до cutover.

**FK/predecessor проверка:** M4 remuneration.assignment ссылается на M1 assignment, не на будущую M6. M3 amendment/consent и obligation identity существуют до CP-05/06 и M8 purchase. M8 commitment не делает bank attempt до M9; неизвестная оплата не выдаётся за paid. M7 расширяет уже существующий M1 minimal transfer/proof. Foreign keys не откладываются на «добавим потом». Поддержка каждого сценария активируется только после всех его schema и API prerequisites; наличие таблицы не включает capability.

M6R — prerequisite только включаемого Ride/on-route bridge; dedicated без Ride не ждёт его. Это описательное имя расширения, не обязательный линейный predecessor M7: если dedicated M7 уже влит, M6R создаётся от фактического актуального Alembic head с теми же prerequisites. Не создавать competing heads или обходить общую очередь моделей/миграций. CP-08A1 generic foundation завершает M6, CP-08A2 bridge завершает M6R; нет зависимости назад от CP-18. Multi-item одного заказа остаётся CP-18, independent single-item orders одного Ride уже используют joint capacity в A2.

**Active legacy cutover:** нормативная матрица K5 переносит все шесть осей, включая canceled-with-goods, unknown service/goods payment и открытый Incident. K-CUTOVER запрещает новые v2 writes до M1…M8, CP-02A/07A/09A/12A/21B-core, обновления всех legacy HTTP/worker/admin writers и staffed recovery. До M7 новые custody команды не активируются; M1 timeout adapter лишь останавливает выдуманные terminal переходы. Admission pause → drain → writer generation fence → final backfill → validation → single command service. Старый writer на mixed deployment не имеет разрешения state mutation, а старый клиент получает compatible single-item adapter либо upgrade/help. Нельзя откатить worker к auto-returned после начала новых событий.

Если общая RS migration уже содержит entity, CP расширяет её, а не создаёт дубликат. Любая новая schema потребность в CP-16/17/20/21/22 сначала добавляется в очередь с predecessor, retention и rollback, не «небольшим ALTER вне плана».

Каждая M: fixture из полного SHA непосредственно предыдущего main через его migrations, затем старые синтетические данные, target upgrade, backfill/constraints, down/up на временной SQLite и PostgreSQL. Нельзя строить old fixture target `create_all`, применять production/копию с личными данными или выдавать SQLite за PG lock proof. Проверять одну голову, обе migration guards, row counts, amounts, FKs, role/privacy и запрет повторных уведомлений. Crash during backfill/resume и mixed-version clients входят в DoD. Отдельно измерить lock duration и batch size на репрезентативном синтетическом объёме.

C9 TrackingSnapshot/Forecast входят в M7 как ограниченные role-filtered projections с assignment/stop/route refs, input provenance и tracking_seq. Они не заменяют order/event/receipt ledger и не повышают terms_version при каждом GPS fix. Retention/quality thresholds до production согласуются с CP-21/LG-06; миграция старых GPS не придумывает captured_at/accuracy/generated_at.

## D3 Retention и удаление аккаунта

У каждой таблицы/поля автор migration указывает purpose, owner, retention policy ID, TTL trigger, deletion/anonymisation, dispute/legal hold и backup handling в `cleanup.py`/`account.py`. Нет утверждённого срока → категория не идёт в production, не `KEEP_FOREVER` по умолчанию. Юрист/владелец утверждают сроки по цели и закону; разработчик не придумывает «всё хранить 5 лет».

| Данные | Требуемое поведение |
|---|---|
| Live GPS, exact route, phone | Только активная необходимость; terminal/revocation закрывает доступ немедленно, физическое удаление по утверждённому TTL; return tracking отдельно по privacy решению |
| OTP/challenge/invite token | Hash вместо secret; consumed/revoked закрывается сразу; минимальный antifraud tombstone без восстановления доступа |
| Фото/receipt/identity | Раздельные ACL и сроки; удаление blob и ссылок согласованно, hold только по case и основанию |
| Quote/order/events | Сохранять договорные суммы/версии для спора; редактировать/анонимизировать адрес/контакт по policy, не ломать доказательство обязательства |
| Ledger/payment/refund | Не удалять финансовое доказательство по обычной кнопке; минимизация/псевдонимизация по законному сроку, запрос пользователя объясняется |
| Command receipts/outbox | K6 меняет общий RS user-CASCADE на actor anchor RESTRICT + user SET NULL до courier live; минимальный pseudonymous tombstone остаётся по утверждённому purpose/TTL/hold. Deletion отзывает auth, но не возрождает key; новый аккаунт получает иной anchor. Unresolved effect не забывается |
| Support/consent | Срок по цели и спору; доступ после удаления аккаунта только уполномоченному, не восстановленная публичная ссылка |

Удаление аккаунта с вещью/долгом/спором не равно молчаливому отказу навсегда: запрос принят, доступ остановлен где безопасно, обязательство передано управляемому процессу, пользователю дано понятное состояние. Не стирать контакты так, чтобы посылку нельзя было вернуть, без разрешённой альтернативы. После законного завершения отложенная анонимизация исполняется и проверяется.

## D4 Очередь общих файлов

Ниже пути существуют по inventory; предлагаемые новые файлы явно называются в CP. При реализации полный путь и part tag регистрируется до правки. Один файл — один исполнитель; `append-only`, новая миграция, общий test runner и документация тоже требуют очереди. Предшественник по договору не исчезает при уступке файла. Замок — разрешённый draft PR/локальный протокол из AGENTS; план не даёт новых прав push/merge.

| Общая зона | Очередь CP частей после обязательных RS predecessors |
|---|---|
| Security foundation в `account.py`/`cleanup.py`/shared command auth | CP-21A docs contract до M1; CP-21B-core после M5 и 02A/07A/09A/12A, до K-CUTOVER, только auth/anchor/gate foundation. Feature ACL в том же B последовательно вместе с CP-09…20, не prerequisite для собственных будущих фич |
| `backend/app/routers/rides.py`, `bookings.py`, `schemas.py`, принятый `ride_seats.py` | Сначала actual accepted RS-02/05/14/15; затем CP-08A2 M6R joint guard/DTO; затем только затрагивающие CP-17/18/21B-features. Не редактировать одновременно с RS owner и не менять RS211 документы |
| Android `CreateRideScreen.kt`, `CreateRideStops.kt`; PWA `screens/CreateRideScreen.tsx`, `components/RideCard.tsx`, `RideSheet.tsx`, `api/rides.ts` | RS predecessors → CP-08B2/C2 toggle/route parcel cards после 08A2 → CP-17/18 применимые flows; один writer каждого файла |
| `backend/app/models.py`, `backend/alembic/versions/` | M1 → M2 → M3 → M4 → M5 → M6 → M7 → M8 → M9 → M10; optional M6R сериализуется после M6 + actual RS и до bridge activation, не блокирует dedicated M7 |
| `backend/app/routers/courier.py` | 01A → 02A → 03A → 04A → 05A → 06A → 07A → 08A → 11A → 21B-core → 13A1 → 14A → 13A2 → 15A → 16A → 17A → 18A → 19A → 20A |
| `backend/app/routers/parcels.py` | 01A → 02A → 04A → 07A → 08A → 09A → 10A → 11A → 12A → 21B-core → 13A1 → 14A → 13A2 → 15A → 18A → 19A → 20A → 21B-features |
| `backend/app/config.py`, `geo.py` | 03A → 04A → 05A → 06A → 08A → 11A → 12A → 21B-core → 13A1 → 13A2 → 15A → 16A → 17A → 18A → 21B-features → 22A → 23A |
| `cleanup.py`, `account.py`, `taxi_worker.py` | По M1…M10; timeout custody fix02A перед новыми workers; затем 21B-features. Не менять общие taxi правила без регрессии |
| `ledger.py`, `debt.py`, `routers/payments.py`, `instant_service.py` | 05A → 06A → 11A → 12A → 21B-core → 13A1 → 14A → 13A2; любые общие taxi изменения с отдельным scope и regressions |
| `routers/share.py`, `family.py`, `chat.py`, `incidents.py`, live location modules | 09A → 10A → 12A → 19A → 20A → 21B-features; точный path подтверждается CP-01 перед edit |
| Android `data/ApiClient.kt` | 02B → 03B → 04B → 05B → 06B → 07B → 08B → 09B → 10B → 11B → 12B → 13B → 14B → 15B → 16B → 17B → 18B → 19B → 20B → 21C-Android |
| Android `MainActivity.kt`, `YuldashApp.kt`, `AppNav*.kt`, `Domain.kt` | Только части с новым route/DTO, по номеру CP, один navigation owner; не «все CP держат файл всё время» |
| Android `ParcelsScreen.kt`, `CourierScreen.kt` | По номеру CP B части; выделение новых экранов поэтапно, не параллельное разрезание гиганта |
| Android `CourierLocationService.kt`, `data/FcmService.kt` | 07B → 10B → 18B → 21C-Android; return GPS policy не менять молча |
| PWA `src/api/courier.ts`, `src/api/parcels.ts`, `src/api/client.ts`, `src/utils/outbox.ts` | По номеру CP C части; исключение CP-21C-PWA после соответствующих 21B API; client/outbox только фактически затрагивающие части |
| PWA `ParcelSendWizard`, `CourierScreen`, `ParcelsScreen`, `ParcelTrackMap` | По номеру CP C части, CP-21C-PWA явно; shared styles/test runner отдельно |
| `AndroidManifest.xml`, Gradle configs, Canon/theme, PWA `ui.css`, tests runner | Отдельный единичный lock, не параллельно с затрагивающей сборкой/редактированием |
| `docs/{architecture,decisions,tasks,lessons}.md`, audit registry | Один писатель; статусы audit меняет только независимая приёмка с доказательствами |

Обычные части обозначены A сервер/схема, B Android, C PWA, D ops/provider/приёмка. Специальные operational parts CP-23/24 определены их шапками и не означают B=Android/C=PWA. CP-21: 21A только docs contract до M1; 21B-core сервер foundation после M5 перед cutover; 21B-features серверные ACL после feature API; 21C-Android и 21C-PWA клиенты; 21D review. Нельзя ставить 21A в хвост product-file queue или называть 21B Android. Полный DAG K7; 21B-core не ждёт CP-18/19 features. B/C могут идти параллельно после merged/API freeze A, если не делят документацию и общие ресурсы. CP-24 не меняет продуктовые файлы: выявленный дефект возвращается соответствующему CP с новым review.

Статусы занятия: `active`, `paused-released`, `blocked-dependency`, actor, part tag, exact files. Уступивший перечитывает свежий diff перед возвратом. Сборка Android и эмулятор по одному на компьютер. `main`/PR211 перечитываются перед публикацией; старые line refs не разрешают затереть новые изменения.
