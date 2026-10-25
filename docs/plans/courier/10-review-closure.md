# Независимый аудит и история исправлений

Статус текущего пакета: **R4.1_FINAL_CLOSURE_PENDING**. R1 выявил 13 blockers/8 nonblockers; R2 оставил два остатка и три новых blocker. Ниже сохранена история корневых причин и авторских исправлений. Итоговый R3.1 получил два ограниченных независимых documentation PASS 25.10.2026, включая адресованные R2 причины; точные scope/hash/model metadata приведены ниже. R4 также получил два bounded documentation PASS; все ранее выявленные blocking и три NB R3.1 закрыты в проверенной спецификации. Пять NB R4 адресованы узким R4.1 patch и ещё **ADDRESSED_PENDING_FINAL_CLOSURE**, не выданы за проверенные новые bytes. Formal cross-family acceptance **PENDING**. Код, SQL migration, PostgreSQL races, физические устройства/provider/runtime **NOT_EXECUTED**. Product release/legal/provider не одобрены; отдельные утверждённые бизнес-решения перечислены явно.

Идентификаторы A относятся к архитектурному обзору, S к сценарному. Строка фиксирует корневую причину, нормативное исправление и конкретный regression след, а не обещание «уточнить позже». Номера R1 строк не переносятся на изменённый текст: ниже стабильные ссылки на разделы R2. Точные bytes/хеши проверяет повторный независимый обзор.

## Блокирующие замечания

| ID | Исправленный договор и связанные части | Regression / ожидаемый исход |
|---|---|---|
| A-B01 | [K7](09-integration-contracts.md#k7-части-cp-21-и-граф), D4, CP-21:21A только docs до M1; 21B-core после M5 до cutover; 21B-features после своих API; 21C-Android/PWA,21D. Специальные CP-23/24 заголовки также согласованы со своими parts | SIM-21; dependency/queue review: нет 21A в хвосте product files,21B не Android; core не ждёт 15…20; нет 23↔24 whole-task cycle |
| A-B02 | [K1](09-integration-contracts.md#k1-места-посылки-и-итог-всего-заказа), C1/C2/C7, D1/M1/M10, CP-09/18: per-item holder/proof/item version, deterministic aggregate, mixed state, stop/itinerary tracking scope | SIM-07/08/19:2 delivered+1 returned → completed_mixed; unknown → exception; code для иного itemset denied; первый stop не завершает itinerary |
| A-B03 | [D1/D2](04-data-migrations-and-queue.md#d2-порядок-миграций), CP-02/04/05/06/08/09/13: minimal assignment/item/proof M1, amendment/consent/obligation identity M3, remuneration M4, offers M6, proof extension M7 | SIM-21; migration FK test: M4 не ссылается на ещё не существующий M6, quote amendments не ждут M8, old assignment не дублируется; merchant expansion M10 не создаёт M8↔M9 цикл |
| A-B04 | [K4](09-integration-contracts.md#k4-время-и-handshake-предложения), D0, CP-04/17: preserve backend naive UTC, explicit offset API adapter, per-stop IANA | SIM-10/21: offsetless input422; +03/+05 same instant; oldworker+newAPI без TypeError/shift; SQLite и PG roundtrip |
| A-B05 | [K2](09-integration-contracts.md#k2-полный-каталог-изменяющих-операций), C2/C4, CP-11/12/18: полный type catalog wait/redelivery/storage/handoff, actor/versions/locks/atomic capacity | SIM-08/19: двухфазный handoff не release+accept; one active item slot, interrupted physical outcome → case; новый attempt только agreed terms |
| A-B06 | [K5/K-CUTOVER](09-integration-contracts.md#k5-legacy-backfill-и-cutover), D2, CP-02/09/12/23: матрица всех осей/legacy statuses и active write barrier M1…M8, updated adapters/workers+fence | SIM-15/21: canceled-with-goods не release; historical auto-returned не proof; после cutover старый worker не пишет terminal; unsafe rollback forbidden |
| A-B07 | [C3](02-domain-api-contracts.md#c3-команды-и-повтор), CP-07: общий RS-05 applied/rejected/closed_no_effect/unseen, GET/atomic close, lost body recovery, no blind new key | SIM-03/16: close-first blocked late original; apply-first returns applied; result_gone не unseen; logout не стирает unresolved intent identity |
| A-B08 | [K6](09-integration-contracts.md#k6-actor-anchor-и-удаление-аккаунта), D3/M5, CP-07/21: явная shared FK migration actor anchor, user SET NULL/receipt RESTRICT, approved retention/hold | SIM-16: delete/recreate не открывает старый key/DTO; callback учитывает исходный reference; no CASCADE erase dedup, no eternal PII default |
| S-B01 | C7, [K2 recipient authority](09-integration-contracts.md#k2-полный-каталог-изменяющих-операций), CP-09/10: party+authority_version связывают channel/grant/OTP/contactless; replace/revoke atomically invalidate | SIM-11: A code → revoke → verifyB → oldA denied даже fresh courier; consume-first факт сохраняется, revoke-first не пропускает; late callback discarded |
| S-B02 | [F11](03-money-pricing-contracts.md#f11-покупка-до-чека-и-отмена-во-время-расхода), K2, M8, CP-13: authorize/start до external spend, bounded commitment, unknown/cancel boundary, offline start denied | SIM-06/20: cancel-before/start-before, lost receipt, unauthorized oldlist receipt не customer payable; duplicate receipt no second debt; partial merchant return отдельно |
| S-B03 | [F10](03-money-pricing-contracts.md#f10-обязательства-плательщиков-и-custody-чужой-выручки), C1/F2/M3/M9, CP-13/14: независимые payer/payee obligations/partial allocations; COD held/remit/refund/shortfall; direct service не platform payout | SIM-17: service paid+goods partial+commission due сохраняются; COD conservation60000=40000+10000+10000; manual/bank same debt не twice allocation |
| S-B04 | [K1/K2](09-integration-contracts.md), M8, CP-12/18/23: storage holder/proposal/proof/release/next owner и stored_pending_release; base pilot recovery gate | SIM-05/19: storage proof не returned, no holder → unknown, timeout не handback; нет approved storage/SOP → scope launch blocked |
| S-B05 | [F12](03-money-pricing-contracts.md#f12-денежные-attempts-authorize-capture-void-и-неизвестный-исход), M9, CP-14/17: authorization/expiry/capture/void/unknown, obligation reservations, capture timing owner gate | SIM-04/18: unknownX blocksY; cancel vs capture/void; auth expired before scheduled day; actual double capture → bounded allocation+surplus refund, не duplicate income |

## Неблокирующие уточнения

| ID | Исправление | Regression / ограничение |
|---|---|---|
| A-N01 | [K3](09-integration-contracts.md#k3-gate-и-право-безопасно-разрешить-уже-возникшее-обязательство), CP-03/23: command×gate matrix | SIM-13/21: active safe resolution не blanket bypass auth/legal; новые offers запрещены |
| A-N02 | [K8](09-integration-contracts.md#k8-единые-ошибки-и-статус-проверки): единые machine codes/HTTP/retry/safe DTO/RU; BA отдельно проверяет носитель | Negative DTO/error parity Android/PWA; unknown code не success; 408/429/5xx reconcile, не new key |
| A-N03 | [K4](09-integration-contracts.md#k4-время-и-handshake-предложения), C4, CP-08: issued lease token/dispatch generation/version/itemhash/net и server-issued assignment generation | SIM-01: полный GET → accept; stale token/generation/net rejected, пользователь не изобретает assignment generation |
| A-N04 | K8, CP-24, index; README удалён лишний конечный пустой абзац | Docs audit отдельно, formal cross-family PENDING, runtime NOT_EXECUTED, release NOT_APPROVED. Минимальный README diff проверяется по pinned PR211 |
| S-N01 | [F1](03-money-pricing-contracts.md#f1-проверенная-арифметика-текущих-defaults), CP-06: max(base,0), pct≤0 → fee 0, positive minimum branch отдельно | Negative base/zero rate/promo/return synthetic arithmetic, не новая ставка и не runtime boundary PASS |
| S-N02 | K3, CP-03/12/21/23: eligibility expiry vs compromise/revoked auth | SIM-13/17/21: fresh-auth bounded safe action допустим по закону; stolen token denied, COD/вещь остаются recovery obligations |
| S-N03 | [LG-07 clarification](06-launch-gates.md#уточнение-lg-07-после-сценарного-аудита): только применимый экспедитор post-registry checklist45 дней/3 месяца | Owner+юрист определяют применимость/ответственного/доказательства; OPEN, не обязанность всех курьеров; официальный FAQ повторно прочитан |
| S-N04 | [F5](03-money-pricing-contracts.md#f5-ожидание-отмены-возврат), D1, CP-05/11: cap_scope+accumulator, components inclusion, reserved/consumed | Return cap350+wait7 не 357 при обещанном total cap350; pause/newattempt не reset. Scope/rate changes OWNER_PENDING |

## Следующая независимая проверка

1. Проверить реальные bytes R2, согласованность C/F/D/K/CP и все 21 finding outcomes. Отдельно проверить, что уточнения не создали новый cycle, второй receipt ledger или скрытый денежный capture.
2. Повторить text-only SIM-01…21 с явными W/C/P/O/A, negative/concurrent branches. Результат такого tabletop не объявлять runtime PASS.
3. Сохранить отдельно verdict документа, formal cross-family acceptance и будущую implementation/production acceptance. Не переносить docs verdict в courier runtime audit registry.
4. До implementation обновить main/PR/AGENTS и точную очередь; до LIVE получить применимые owner/legal/provider approvals и выполненные доказательства. Новая цена/регион/договор не одобряется публикацией Markdown.


## R2 → R3 точечное закрытие

Ниже исторический список пяти оставшихся blocker и пяти неблокирующих уточнений на момент авторского R3: тогда ADDRESSED_PENDING_REVIEW. Последующий bounded R3.1 review подтвердил соответствующие closures; это документационное закрытие, не исправленный runtime. Исходные отчёты/хеши R2/R3 не переписываются.

| Finding | Конкретное исправление | Проверяемый след |
|---|---|---|
| A-B03 residual | D1/D2: M1 minimal Stop columns/FKs перед Item/Proof, M3 только enrichment+Leg; CP-02/04/09 | SIM-22, отдельный upgrade M1 и M3, unknown без поддельных дверей |
| A-B05 residual | K2/K4 claim_on_route, внутренний self-offer → тот же accept service/receipt, distinct stored-mode guards, legacy adapter; CP-08 | SIM-23, два claim/claim-cancel/response lost/stale preview/mode bypass denied |
| A-R2-B01 | K1 item canceled, all canceled → canceled, mixed delivered/returned/canceled → completed_mixed, unknown commitment не terminal; C1/C2, CP-02/18 | SIM-24, repeat projector/GET/restart/backfill, обе очередности pickup/start purchase vs cancel |
| A-R2-B02 | F2 immutable quote_component_id, create-only single-use QuoteUse, atomic OrderQuoteComponent → obligation; amendment purpose/target/delta отдельно; D1/M3, CP-05 | SIM-25, quote without debt, concurrent create/rollback/replay, second key rejected, amendment не double full charge |
| S-R2-B01 | F13 общий CashDisbursementIntent/reserve и account+collection bounds для remit/refund/unknown; closed K2, M9, CP-13/14 | SIM-26/RCASH-01/02, competing60000/60000 и 40000/30000, no timeout release, реальные два расхода → immutable facts+shortfall без auto-recoup |
| A-R2-N01 | Proofreading естественного текста: разделены слова/числа/ID, пользовательские строки читаемы; технические IDs/URLs/quoted source не изменяются | Все 36 Markdown, local links/anchors/table structure повторно сверяются; значения ставок не меняются |
| A-R2-N02 | K6 typed user/service anchors, worker key namespace/scoped machine grants, shared ledger/internal reconcile, public spoof denied; C3, D1/M5, CP-07/14/21 | SIM-27 worker restart/revoke/user spoof; close не стирает external unknown |
| A-R2-N03 | K2 отдельные issue_recipient_grant/rotate_recipient_grant, C4 endpoint mapping, proof origin grant binding; CP-09/10 | SIM-27 G1 → G2, same authority/channel, old link/proof revoked |
| S-R2-N01 | K2 replace → pending generation; revoke → revoked; verify current pending → active без bump/self-revoke; C7, CP-10 | SIM-27 полный happy path и verify/revoke race, не только rejection старого OTP |
| S-R2-N02 | F12 capture guard: valid two-stage authorization ИЛИ approved immediate capability+consent/reserve/trigger; no fake authorization ID; CP-14 | SIM-27 обе ветки при CLOSED/LIVE gates, одна операция и прежние amount bounds |

Эти contracts не выбирают ставки, аванс курьера, ответственность за недостачу, provider или legal role. Для R3.1 и R4 bounded docs re-review выполнен; новые R4.1 bytes требуют final closure; formal cross-family PENDING, implementation/runtime NOT_EXECUTED, owner/legal/provider release NOT_APPROVED.

## Дополнение по уточнению владельца

PRODUCT-SEPARATION: scope/C2/F14 и CP-03/08/17/18 разделяют on_route/dedicated, их UI, eligibility, dispatch и fee policy; SIM-28 проверяет no silent substitution. Это дополнительный R3 договор, впоследствии проверенный в ограниченном R3.1 review; последующие R4 изменения требуют новой проверки. После R3 freeze владелец явно подтвердил OD-2026-10-25-01: водитель может получать оплату, комиссия платформы on-route 0; dedicated комиссионный, включая scheduled. На момент R3.1 driver price-setting оставался открытым; OD-04 ниже утверждает DRIVER_SET_PRICE. Concrete dedicated rates/LIVE/legal/provider gates остаются открытыми.


## R3 → R3.1 подтверждённое решение владельца

R3.1 не изменяет уже подготовленные fixes пяти blockers R2. Узкое обновление scope/F1A/F14/C2/K4/CP-06/08/17/23 и SIM-28 фиксирует OD-2026-10-25-01, отменяя прежний pending вопрос о zero platform commission. Acceptance: нулевая ветка до minimum/tier, доход водителю без platform deduction, отсутствие hidden mandatory service fee, scheduled dedicated комиссионный, no silent substitution/repricing. On-route acquisition/subsidy cost отражается отдельно; нет обещания прибыльности. Источник — прямое решение владельца в source register, не авторское предположение. Исторический R3.1 bounded docs PASS указан ниже; текущий R4.1_FINAL_CLOSURE_PENDING, formal cross-family PENDING, implementation/runtime NOT_EXECUTED, owner/legal/provider release NOT_APPROVED.


## Историческая независимая проверка R3.1

25.10.2026 оба независимых reviewer дали **bounded documentation PASS** для aggregate `9b3ea7879ac7acc344d027d160dbe574f019ee131fca803215c3a41d6ac46ee7` (36 файлов,639748 bytes). Это не приёмка нового R4 и не гарантия полноты продукта. Модель обоих reviewer — gpt-6.1-sol по metadata их запуска; это не доказательство cross-family, которая остаётся PENDING.

| Область | Проверенный результат | SHA-256 отчёта |
|---|---|---|
| Architecture R3.1 | Четыре открытых архитектурных пункта R2 закрыты в спецификации; новых blockers в проверенном scope нет; три NB вынесены в R4 | `8111df16453050baeca51942be96297f0f911fb538828c889ebfea3dfac22d07` |
| Scenario/money R3.1 | S-R2-B01/N01/N02 закрыты, прежние money/custody и OD-01 regressions просмотрены; новых blockers в заявленном scope нет | `d5af389010717fb38910b04ebd347f048443a3f43e72180c327af407099c5406` |

## R4 ограниченное дополнение и уточнения

| Причина | Изменённый договор | Regression / текущий статус |
|---|---|---|
| A-R3.1-N01 |01 canonical enum/allowed pairs/aliases и legacy mapping, C4/D1 link | SIM-29/32; CLOSED_IN_R4_BOUNDED_REVIEW |
| A-R3.1-N02 | CP-10 verify actor только доказанный владелец канала | SIM-34 sender cannot verify recipient; CLOSED_IN_R4_BOUNDED_REVIEW |
| A-R3.1-N03 | K2/C4 pure revoke_recipient_grant, no G2/no authority change | SIM-34; CLOSED_IN_R4_BOUNDED_REVIEW |
| OD-02 Ride publication/parcel-only/documents |11, CP-03/04/08/09/12/17/18/21, M6R и shared-file queue; no RS211 edits | SIM-29/30/31/32, R4_BOUNDED_DOCS_PASS |
| OD-03 ready pickup/buy_bring/three payer roles | scope/F contracts/CP-13/14/15; конкретный payer consent и legal gates сохранены | SIM-35, R4_BOUNDED_DOCS_PASS |
| OD-04 driver price/handoff/recovery | F1A/R4A в 11, CP-05/06/08/12/17/23, M6R single-use proposal; route meeting, real recovery gate | SIM-36, R4_BOUNDED_DOCS_PASS |
| Honest tracking request | C9/M7, CP-10/22 и затронутые CP, role-specific return/passenger privacy | SIM-37/ETA-N01…22, R4_BOUNDED_DOCS_PASS |
| Regional cost goal | F14A/CP-06/23, observed vs assumed costs, contribution/break-even/no hidden subsidy | SIM-33, R4_BOUNDED_DOCS_PASS |

R4 дизайн-сценарии остаются NOT_EXECUTED как продуктовые тесты; bounded independent docs review завершён. Для R4.1 diff/hash final closure ещё требуется; formal cross-family PENDING, implementation/runtime NOT_EXECUTED, release/legal/provider NOT_APPROVED. Исторические PASS и отрицательные R1/R2 отчёты не переписываются.

## Проверка R4 и узкое закрытие R4.1

25.10.2026 оба независимых reviewer дали **BOUNDED DOCUMENTATION PASS** по своим R4 scope/regressions для aggregate `e83983e5396c046e32eca8be3a4d5d81ff99408af8621dd89ed58f96ef922e9b` (37 файлов/754958 байт), manifest `577f8afdc6db853126f9a8a5cd5f888c8e1fbe7899ff63e48c452b530f309db8`. Before/after hashes совпали. Модель обоих — gpt-6.1-sol по launch metadata, не доказательство cross-family. Полнота всех возможных сценариев/реализация не утверждаются.

| Область | Результат | SHA-256 отчёта |
|---|---|---|
| Architecture R4 | Новых blockers не установлено; три NB R3.1 CLOSED; пять NB ниже | `2b7a89c872715cfffec79f5d0a31c3cb09a4c0a99b782777b58ca4612070ad7c` |
| Scenario R4 | Новых собственных blockers нет; A-R4-N01 квалифицирован и требует явной map, N02 editorial | `97725faf7092b29066886c4345dd692fdd81a586a109a919a4198ff1bd394c48` |

| Finding | Узкий R4.1 fix | Regression и статус |
|---|---|---|
| A-R4-N01 IMPORTANT |11 R5A explicit nonreleased state → occupied map, unknown-span admission block; старые next-leg obligations сохраняются, incompatible loading blocked | SIM-30A recovery-first/accept-first/unknown handoff/already-reserved next leg/scoped duplicate proof; ADDRESSED_PENDING_FINAL_CLOSURE |
| A-R4-N02 editorial | C9 UTC cross-reference заменён D7 → K4/C4/D0, временной договор не изменён | Review ссылки; ADDRESSED_PENDING_FINAL_CLOSURE |
| A-R4-N03 | C9 server input-generation publication guard и согласованные snapshot/event/outbox | SIM-37 slow-A/fast-B, первый GET/resync не stale overwrite; ADDRESSED_PENDING_FINAL_CLOSURE |
| A-R4-N04 |11 R4B exact shared receipt scope/target/ref_kind/current, nullable order_id/actor ACL/GET-close; CP-07 | SIM-36A lost request/proposal response/restart/late original; ADDRESSED_PENDING_FINAL_CLOSURE |
| A-R4-N05 |11 R2A absent-policy/absent-field legacy semantics и explicit parcel-only; CP-08 | SIM-30A old DTO/backfill/live Booking/category guards; ADDRESSED_PENDING_FINAL_CLOSURE |

R4.1 не вводит новых features/rates/прав и не исправляет доказанный runtime bug: уточняет уже предусмотренные guards и исторические статусы. Все ранее выявленные blocking закрыты в документационных проверках; пять NB ждут короткой проверки этого точного diff/hash. Formal cross-family PENDING; product/runtime NOT_EXECUTED; dedicated rates/legal/provider/реальная recovery сеть/production release остаются OPEN/NOT_APPROVED.
