# Общие контракты доставки

## C0 Статус договоров

Ниже — предлагаемый целевой договор для реализации CP, не утверждение о наличии API. Существующие имена и строки кода перечисляются отдельно в `07-source-register.md`; новые пути помечены как новые. Контракт сначала проходит независимый review, затем применяется по частям. Никакой пункт не разрешает писать деньги, включать сервис или менять рабочую базу без соответствующего gate.

Основной агрегат остаётся `ParcelDelivery`. Запрещено создавать второй независимый объект заказа с другими правами и балансом. Новые подтаблицы хранят версии, точки, custody, предложения, доказательства и денежные проводки этого же `parcel_id`. API нового поколения предлагается под `/courier/v2/`; старые `/courier` и `/parcels` обслуживаются через совместимые адаптеры, не через две бизнес-логики.

Общие механизмы RS переиспользуются только после проверки реально влитого кода: `herr(..., code=...)`, `ApiError.code`/`detailCode`, `CommandReceipt`, owner switches с журналом, receipts сообщений и outbox. Описание RS в PR211 не доказательство их реализации. Если механизм не готов, CP-01 фиксирует блокер или отдельный принятый predecessor, но не копирует его под новым именем.

## C1 Разделение состояний

Нельзя одним полем «завершён» одновременно утверждать передачу вещи, оплату, выплату и отсутствие спора.

| Ось | Предлагаемые значения | Владелец истины |
|---|---|---|
| `fulfillment_state` | `searching`, `assigned`, `pickup_arrived`, `collected`, `delivering`, `dropoff_arrived`, `partially_completed`, `delivery_exception`, `storage_pending`, `stored`, `returning`, `delivered`, `returned`, `completed_mixed`, `canceled`, `closed_exception` | Командный сервис и журнал переходов |
| `custody_state` | По каждому item: `sender`, `assigned_carrier`, `recipient`, `return_receiver`, `storage_custodian`, `unknown` + holder_ref; агрегат: то же при общем держателе, иначе `mixed`, при любом неизвестном факте `unknown` | Подтверждённый переход с доказательством, не таймер |
| `payment_summary` | Вычисляемая сводка массива payment_obligations; `not_due`, `due`, `partially_satisfied`, `all_satisfied`, `review_required` не заменяют состояния каждой обязанности | Общий ledger и obligation projections F10–F12; service/goods/commission никогда не склеиваются в один paid |
| `courier_settlement_state` | По конкретному payable: `not_due`, `accrued`, `held`, `payout_pending`, `paid`, `adjustment_due`, `unknown`; это не custody товарной выручки | Денежный журнал/провайдер F10–F12 |
| `dispute_state` | `none`, `opened`, `awaiting_evidence`, `under_review`, `resolved`, `appealed` | Поддержка с правами и журналом |
| `obligation_state` | По item: `active`, `return_required`, `safe_storage_required`, `stored_pending_release`, `resolved`; агрегат resolved только когда все item obligations resolved и нет неразрешённого физического outcome | Custody + договор решения, не факт оплаты |

`closed_exception` означает явное решение по утраченному/опасному/невозможному возврату, а не «успешно доставлено». Для него обязательны case, actor, причина, уведомлённые стороны и юридически разрешённое действие. Изъятие, утилизация и передача третьему лицу не разрешаются этим статусом.

Текущий `ParcelDelivery.status` (`created|accepted|in_transit|delivered|canceled|returning|returned`) сохраняется на этапе expand. Проекция: searching → created; assigned/pickup_arrived → accepted; collected/delivering/dropoff_arrived/delivery_exception → in_transit; returning → returning; delivered/returned/canceled → одноимённый. `partially_completed`, `storage_pending`, `stored`, `completed_mixed` и `closed_exception` не отправляются старому клиенту как delivered/returned: блокируется capability нового клиента, показывается legacy in_transit/returning + безопасное `requires_attention`, если старый клиент умеет это поле. Иначе для такого сценария минимальная версия обязательна. Точный адаптер проверяется CP-02 по каждому старому route/worker, без молчаливого fallback.

Старые terminal строки получают `custody_evidence_level=legacy_unverified`, если нет доказательства передачи. Из записи `returned` нельзя задним числом синтезировать код, фотографию или фактическое время возврата. Они отображаются как исторический статус, не как вновь подтверждённое вручение.

Item/stop finality, смешанные исходы и точная обратная legacy migration заданы в [K1/K5](09-integration-contracts.md). Эти правила обязательны для каждой строки C2: confirm_* относится только к зафиксированному item set и сам по себе не завершает весь заказ. До CP-18 новые multi-item/partial/handoff capabilities выключены; legacy logical parcel — один непрозрачный item, а не выдуманный набор мест.

## C2 Переходы и полномочия

| Команда | Предусловия внутри одной транзакции | Новый результат | Кто вправе |
|---|---|---|---|
| `create` | Действующий quote, покрытие/допуски, согласие с версией условий, право плательщика | searching, custody sender; QuoteUse и component → obligation mapping F2 atomic | Отправитель или авторизованный merchant |
| `accept_offer` | Действующая offer lease, свободная ёмкость, actor допущен, order version совпал, нет другой assignment | assigned; owner assignment зафиксирован | Предложенный курьер; claim_on_route для on-route через K2/K4 self-offer adapter |
| `claim_on_route` | Stored service_type=on_route/service_kind=parcel после явного legacy mapping, valid claim preview/intent, отдельные mode/route/coverage/capacity guards K4 | Internal self-offer + canonical accept, одна assignment; не pickup | Попутный помощник по своему допуску, не обход professional guard |
| `arrive_pickup` | assigned, актуальная assignment, время/место проверены | pickup_arrived; может стартовать wait session | Назначенный курьер |
| `confirm_pickup` | pickup_arrived/assigned, пригодное одноразовое proof, состав/состояние груза согласованы | collected, custody assigned_carrier | Назначенный курьер + подтверждение отправителя |
| `start_leg` | collected либо следующий разрешённый stop | delivering, актуальный leg | Назначенный курьер |
| `arrive_dropoff` | delivering, текущий stop, arrival допустим | dropoff_arrived; отдельная wait session | Назначенный курьер |
| `confirm_dropoff` | Текущая assignment/stop/version, item_set_hash и recipient_authority_version, proof полномочного получателя | Только подтверждённые items → recipient/resolved; aggregate пересчитать по K1, не безусловный delivered | Назначенный курьер + актуальное подтверждение получателя |
| `report_attempt_failed` | Активная доставка; причина, контактные попытки, elapsed по серверу | delivery_exception, custody неизменна | Курьер; поддержка проверяет спорный случай |
| `request_return` | Вещь у курьера; договор возврата и safe route, утверждённая цена/лимит | returning, return_required | Сервер по согласованной политике либо поддержка/отправитель по правам |
| `confirm_return` | returning, текущий return stop, immutable item set, authority принимающего | Только подтверждённые items → return_receiver/resolved; whole-order returned лишь по K1 | Курьер + отправитель/согласованный принимающий |
| `cancel` до передачи | searching/assigned/pickup_arrived; нет item у чужого держателя и purchase commitment с неизвестным/состоявшимся расходом | canceled только при доказанном отсутствии сторонних обязательств; иначе cancel_requested и recovery по F11 | Отправитель; будущие расходы прекращаются, прошлый unknown не стирается |
| `cancel` после передачи | custody assigned_carrier | Не canceled: request_return или support case | Никакое нажатие не снимает custody |
| `release` до передачи | Нет покупки/purchase commitment/денежной custody/товара/активного доказательства; assignment можно снять | searching с новой dispatch generation либо canceled | Курьер/поддержка, с причиной |
| `resolve_exception` | Case + role + reason + evidence + approved resolution | closed_exception или безопасный возврат | Уполномоченная поддержка, денежное решение отдельно |

Полный закрытый каталог всех domain writes, включая redelivery, wait, handoff, storage, purchase и recipient authority, — [K2](09-integration-contracts.md). Отдельные денежные provider transitions — F10–F12; они не скрыты за domain type. Таблица выше краткая проекция базовых команд, не урезанный enum.

Quote/order/offer/claim preview неизменно содержат service_type и fee_policy_id/version; mismatch отклоняется, клиент не может выбрать чужой eligibility lane. Смена on_route↔dedicated требует нового quote/terms/consents и revalidation исполнителя, не silent fallback. OD-2026-10-25-01 фиксирует on_route platform commission=0 и допускает оплату водителю; dedicated комиссионный, включая scheduled. DRIVER_SET_PRICE и ADVISORY_ONLY утверждены OD-04; конкретные dedicated rates/provider/legal implementation остаются gated; F1A задаёт zero branch до minimum/tier.

Каждая команда проверяет actor, order, assignment_generation, stop, item_set_hash, recipient_authority_version при необходимости, role, state, expected_version и разрешение по command×gate матрице K3 заново после row lock. Проверка только на клиенте, до ожидания lock или до модерации недостаточна. `state_version` растёт на каждое доменное изменение; `cancel_requested_at/reason` — отдельный флаг намерения, не новый terminal status. `terms_version` только на изменение маршрута/окон/цены/груза/опций/условий/полномочного получателя или его канала; GPS и просмотр версий не меняют. Отзыв скомпрометированного receiving authority применяется немедленно, не ждёт согласия отозванного лица; он блокирует неподтверждённую передачу. Новые платные/маршрутные обязательства при замене требуют amendment. Любое изменение принятых существенных условий требует amendment с согласием затронутых участников, а не перезаписи снимка.

Таймер может создать alert, истечь offer, перевести поиск в отсутствие предложения, предложить возврат или поставить case. Таймер не доказывает возврат, оплату, вручение и не превращает custody в `resolved`. Исправление существующих timeout/cleanup терминализаций — P0 CP-02 и CP-12.

## C3 Команды и повтор

Courier расширяет именно общий RS-05 CommandReceipt/intent lock. Он не создаёт второй receipt ledger или новые несовместимые названия состояний. Серверные состояния: `applied|rejected|closed_no_effect|unseen`; UI `prepared|sending|unknown` локальные, не authoritative outcome. applied хранит ссылки и code, current строится заново по актуальным правам. applied+result_gone не равно unseen.

Каждая мутация: Idempotency-Key `[A-Za-z0-9_-]{16,128}`, scope, target, payload_hash canonical JSON, expected_version. Тот же key с другим scope/target/body → 409 idempotency_key_reused. Общий порядок: principal+intent lock → связанные Ride IDs → активные Booking IDs → on-route price requests/proposals (sorted IDs, если применимы) → quotes (sorted IDs, если create/consumption) → orders (sorted IDs) → assignments/items (sorted IDs) → financial obligations (sorted IDs) → cash accounts → source collections/allocations → outbound intents/reservations (везде sorted IDs) → доменный savepoint; event/outbox/receipt commit единый. Промежуточные внутренние commits запрещены. Доказанный отказ без эффекта сохраняет rejected; 408/429/5xx/обрыв — unknown на клиенте, не окончательный отказ.

`GET /command-intents/{key}` возвращает общий RS-05 DTO с ref_kind=`parcel|purchase_commitment|payment_obligation|...` из зарегистрированного каталога. unseen означает только отсутствие записи; задержанный исходный пакет ещё может прийти. `POST /command-intents/{key}/close` с scope/target_id/payload_hash под тем же lock: applied/rejected вернуть; unseen атомарно превратить в closed_no_effect; другой fingerprint409. Поздний исходный запрос увидит закрытый key и не выполнит эффект. Close не отменяет уже созданную доставку/покупку/списание. После terminal applied/rejected/closed_no_effect человек отдельно подтверждает новый intent, если он нужен.

24 ч — окно обычного replay, не TTL защиты: после него POST → idempotency_key_expired и GET/close; прежний key никогда не становится новым. Рестарт с утраченным body: GET, при unseen явный close по сохранённым метаданным; только затем возможно новое согласованное действие. Нет durable storage → мутация не отправляется. Logout скрывает прежний account namespace и удаляет credentials, но не стирает unresolved metadata; новый пользователь его не читает, прежний после входа восстанавливает. Удаление аккаунта по K6, а не обычному logout.

Replay проверяет актуальные ACL и не возвращает сохранённый сырой адрес/телефон. После удаления аккаунта защита хранится по согласованному stable actor anchor K6; это явное расширение FK/retention общего RS-05, отдельный owner/legal gate M5, не предположение о существующем CASCADE. Внешние callbacks не нуждаются в сессии удалённого пользователя, но должны иметь собственную provider authority и causal references F12. Срок/hold не утверждены за владельца.

## C4 Предлагаемые API

Canonical service_type/service_kind enum, allowed pairs и legacy mapping заданы в 01; aliases parcel_on_route/parcel_dedicated не принимаются как service_kind. Ride binding/toggle/search API и capacity protocol описаны в 11; старые Ride.price/Booking суммы не смешиваются с courier копейками.

Все пути этого раздела новые; выбор префикса и окончательные DTO фиксируются в CP-02 до клиентов. Даты ISO 8601 UTC, деньги integer kopecks, расстояние integer metres, вес integer grams, размеры integer millimetres. Не принимать float цены, клиентский commission, paid или actor_id.

| Метод и путь | Вход/выход | Ключевые ограничения |
|---|---|---|
| `GET /courier/v2/capabilities` | version, service/geo/transport flags, причины недоступности, minimum client version | Не раскрывает приватное supply; effective gates вычислены сервером |
| `POST /courier/v2/quotes` | service_type, service_kind, transport requirements, stops, cargo, windows, payer_allocations/consents, options → immutable quote | Маршрут/цены/варианты; ещё не заказ и не hold денег |
| `GET /courier/v2/quotes/{quote_id}` | Снимок, expiry, stale reason | Только автор/разрешённый merchant |
| `POST /courier/v2/orders` | quote_id, service_type (совпадает с quote), accepted_terms_version, payment choice, recipient grant request → order | Требует Idempotency-Key; create не означает paid/assigned |
| `GET /courier/v2/orders/{id}` | Role/phase DTO + versions + allowed_actions | Не возвращает proof secrets всем ролям |
| `GET /courier/v2/orders` | cursor, bounded filters → page + next_cursor | Полная история постранично; 10 строк не «все» |
| `POST /courier/v2/orders/{id}/commands` | type из K2, expected_version, assignment_generation, payload → applied/current version | Все команды через один policy/state слой |
| `GET /command-intents/{key}`; `POST /command-intents/{key}/close` | Общий RS-05 applied/rejected/closed_no_effect/unseen + safe refs; close(scope, target, payload_hash) | Единственный ledger/lock C3; unknown только UI, no-effect доказывается atomic close |
| `GET /courier/v2/on-route/requests` | Selection context → open requests + signed claim_preview K4 | Не assignment/reservation; бытовой и профессиональный допуски разделены |
| `GET /courier/v2/offers` | offer_id, order_id, dispatch_generation, order_state_version, eligibility_version, lease_token, expires_at, item_set_hash, service_type, fee_policy_id/version, distance/time estimate, net preview | Только eligible courier; адрес/телефон скрыты |
| `POST /courier/v2/offers/{offer_id}/accept` | lease_token, dispatch_generation, expected_version, item_set_hash → assigned order с новым assignment_generation | CAS и блокировка ёмкости; отказ/timeout не штрафуют без принятой политики |
| `POST /courier/v2/orders/{id}/amendments` | change proposal, reason, expected_terms_version | Новый quote/consent, нельзя тихо увеличить цену |
| `POST /courier/v2/amendments/{id}/decision` | accept/decline | actor-bound, однократно; stale version конфликт |
| `POST /courier/v2/orders/{id}/proof-challenges` | phase, stop_id, item_ids/hash/versions, receiving_party_ref/recipient_authority_version → challenge delivery status | Сервер генерирует secret; курсор/текст не передаёт OTP курьеру |
| `POST /courier/v2/orders/{id}/proofs` | challenge ref + answer/photo token + phase/stop/itemset/authority/terms/assignment versions | Rate limit, one-use, replay safe; proof не equals payment |
| `POST /courier/v2/orders/{id}/recipient-grants` | type=issue_recipient_grant / rotate_recipient_grant, scopes, current authority/grant generation → one-use invitation | Mapping K2; новый invite не заменяет receiving party; rotation отзывает предыдущую grant, TTL ограничен |
| `POST /courier/v2/orders/{id}/recipient-grants/{grant_id}/revoke` | current grant/authority versions → revoked scope | revoke_recipient_grant K2, без нового G2/замены receiving party |
| `GET /courier/v2/recipient/{grant}/track` | recipient DTO, permitted proof channel, latest state | Без телефона отправителя/полного списка других stop; no indexing |
| `POST /courier/v2/orders/{id}/disputes` | category, message, evidence refs | Права после разрешённых terminal состояний; не останавливает custody |
| `GET /courier/v2/orders/{id}/statement` | service receipt, goods receipt refs, payment verification, ledger summary | Не называть нефискальную квитанцию кассовым чеком |
| `GET /courier/v2/couriers/me/earnings` | currency, accrued/held/paid, adjustments, cursor | Суммы воспроизводимы из журнала; не «чистая прибыль» |
| `POST /courier/v2/merchant/orders`; `POST /courier/v2/merchant/orders/{id}/ready` | external_order_id, items/packing/window; ready version | Merchant-scoped, external id dedup, ready не pickup |
| `POST /courier/v2/merchant/return-authorizations`; `GET /courier/v2/merchant/return-authorizations/{id}` | RMA, merchant/items/expiry/receiving conditions | Kind=merchant_return создаётся отдельно; seller received не означает refund |
| `POST /courier/v2/admin/cases/{id}/resolutions` | reason, expected_version, resolution, evidence refs | RBAC, audit, отдельные денежные полномочия |

Ошибки через `herr(status, ru, ba, code=...)`; единственный полный каталог HTTP/retry/безопасного DTO находится в K8. Старый alias IDEMPOTENCY_CONFLICT не вводится: общий RS-05 использует idempotency_key_reused. Приватный resource для постороннего —404 там, где 403 раскрывает существование. Клиенты ветвят по code, не по переведённой строке.

## C5 Dispatch и борьба за заказ

`CourierOffer`: order_id, courier_id, dispatch_generation, eligibility_version, lease_token_hash, expires_at, status (`offered|accepted|rejected|expired|revoked`), immutable net/terms preview. Lease не является назначением. Политика длительности/радиусов/скоринга конфигурируется и версионируется; запуск не зависит от номера строки в ленте.

Accept транзакционно соблюдает полный C3 порядок (для Ride-bound сначала Ride/Booking/price/quote, затем order) и блокирует активные capacity rows курьера, проверяет актуальные допуски/онлайн/занятость и CAS version, создаёт единственную active assignment для каждого назначаемого item, отзывает соседние offers и пишет outbox. PostgreSQL unique active assignment-item slot (item_id) защищает от двух custodians; до multi-item capability заказ имеет один logical item. Parcel.active_assignment_id лишь homogeneous projection, nullable при разных item holders; SQLite тест не заменяет PG race.

Блокировка такси/попутки учитывается общей политикой занятости. При нескольких режимах нельзя обещать один и тот же доступный объём дважды. Batching включается только после CP-18 с capacity per leg. Потеря GPS не означает offline автоматически; координата имеет measured_at/received_at/accuracy/source и лимит свежести. Старые/невозможные точки не повышают приоритет. Отказ в геоправе допускает ограниченный ручной режим, если это безопасно и явно поддержано, а не фиктивный nearest.

## C6 События, доставка уведомлений и координат

`CourierEvent`: event_id UUID, aggregate_id, aggregate_version, type, schema_version, occurred_at сервером, actor_ref, correlation_id, cause_command_id, safe payload. Уникальность aggregate_id+aggregate_version+type либо отдельный последовательный event_seq задаётся CP-02; версия одного события не должна путаться с версией строки. Рекомендуется один монотонный `event_seq` на заказ и уникальный event_id.

Типы: `order.created`, `offer.created/revoked/expired`, `assignment.accepted/released`, `redelivery.proposed/accepted/started`, `handoff.proposed/confirmed`, `storage.proposed/confirmed/released`, `purchase.authorized/started/result_recorded/reconciled`, `recipient_authority.changed/revoked`, `pickup.confirmed`, `leg.started`, `arrival.recorded`, `attempt.failed`, `return.required/started/confirmed`, `delivery.confirmed`, `order.canceled`, `amendment.proposed/accepted/rejected`, `goods.receipt_recorded`, `wait.started/stopped`, `payment.updated`, `settlement.updated`, `dispute.updated`, `recipient_grant.revoked`.

Domain outbox фиксируется до commit вместе с событием. Worker имеет lease/next_attempt_at и идемпотентного consumer по event_id+recipient+channel. Повторный webhook/FCM/websocket не создаёт второе событие. Push/HTTP200 от сервиса отправки не равны delivery/read. Delivered/read receipt относится к конкретным message_ids и actor/device policy, а не к открытию приложения.

Realtime — ускорение, не единственный источник истины. Переподключение с last_event_seq → replay доступных событий или `resync_required` и GET полного снимка. Пропущенная terminal запись прекращает tracking при следующем разрешённом snapshot; события старой generation/аккаунта не оживляют экран.

Координаты — отдельный поток с sequence, assignment_generation и измеренным временем. Server отсекает чужой assignment, post-terminal update, слишком старое время и аномальную частоту. Получателю выдаётся только необходимый участок/окно, а не все адреса маршрута. После разрешения конкретных items прекращается их разрешённый tracking scope; весь order stream закрывается лишь по aggregate K1 либо отзыву доступа, а service itinerary — когда нет иных разрешённых active scopes. Returning/stored не включают GPS автоматически вопреки прежней privacy policy; архивное доказательство хранится отдельно с меньшими правами.

## C7 Доказательство передачи и приватность

Pickup, dropoff и return используют разные challenges, связанные с order+stop+phase+assignment_generation+terms_version+canonical sorted item_ids/item_set_hash+recipient_authority_version и receiving_party_ref. Набор мест неизменяем, proof на другое подмножество отвергается. Challenge одноразовый, TTL/attempt budget серверные, secret хранится хешем, не попадает в логи, push preview, URL, аналитику и DTO курьера. Код одного заказа/этапа не подтверждает другой. Перевыдача отзывает старый challenge и требует подтверждения полномочий, не «подсказки» от курьера. Revoke/replace/change-contact получателя атомарно увеличивает recipient_authority_version, отзывает все затронутые unused challenges/contactless consents/grants и отменяет ещё не отправленные уведомления. Позднее уведомление не возвращает силу коду. Consume после lock проверяет актуальную authority; consume-first сохраняется как исторический факт, последующий revoke не отменяет передачу задним числом. Право замены/согласия/новый канал — K2.

Если получатель без аккаунта, invite и подтверждение владения каналом разделены. Скриншот страницы получателя и знание URL не дают права менять адрес/бюджет. Ссылка — bearer secret с минимальными scopes и возможностью отзыва; её token не хранить в referrer/аналитике. Подтверждённый контакт не равен установленной личности; интерфейс не обещает обратного.

Фото — дополнение: presigned upload ограничены order/phase/actor, размером и mime; проверка декодирования, malware policy, EXIF stripping, запрет публичных ACL. Фото без необходимого согласия или с людьми/документами не требуется. Нет сети/камеры/кода → case и безопасный альтернативный процесс с полномочиями, а не фиктивное delivered. «Оставить у двери» разрешается только предварительным согласием, точным местом, допустимой категорией и отдельным proof policy; высокоценное/требующее возраста не включается автоматически.

Role DTO строится одним allowlist для GET, list, commands, replay, websocket, push, support exports и receipt. До assignment нет телефонов и точного адреса. После assignment минимальный адрес/контакт виден назначенному исполнителю только пока нужен. При смене исполнителя старый grant/чат/медиа отзываются. Support доступ по case и purpose с audit; деньги/identity documents — отдельный уровень. Скачивание evidence журналируется без секрета. Политику retention/удаления задаёт CP-21 и утверждает legal gate.

Verification pending current authority → active без нового generation bump/самоотзыва. Grant issue/rotate не меняет receiving party; proof, выданный через grant, сохраняет issued_via_grant_id/generation, а rotation отзывает неиспользованные proofs этой grant. Полный lifecycle K2.

## C8 Offline и клиентское восстановление

Локальный intent хранит account_id, operation, key, order_id, expected_version, assignment_generation, payload_hash и состояние отправки. Секрет proof и фото не сохраняются в обычном незашифрованном кеше. При logout credentials стираются, account namespace скрывается и отправка блокируется, unresolved metadata сохраняются по C3; поздний ответ проверяет account+order+generation до обновления UI. Нажатие «Повторить» не меняет смысл intent.

Без связи можно читать минимальный локальный паспорт активной доставки и набрать явно разрешённый контакт. Нельзя офлайн принять предложение, доказать платёж, увеличить бюджет, выполнить банковское действие или отметить передачу как серверно завершённую. Подготовленный proof получает «ожидает подтверждения», а не зелёное «доставлено». После рестарта reconcile раньше повторной команды; при unseen и утраченном body выполняется общий atomic close C3, а не бесконечный GET и не новый UUID. При конфликте custody не теряется, открывается безопасный case.

## C9 Честные карта и прогноз времени

Это собственный договор Юлдаш, не спецификация конкурента и не обещание точности до минуты. Поиск исполнителя, прибытие на pickup, прибытие на dropoff, обслуживание/передача и конец всего рабочего маршрута — разные цели. До assignment показывается поиск и его elapsed, не выдуманная подача. Согласованное `promised_window`, первоначальный прогноз, текущий прогноз, фактическое arrival и handover не перезаписывают друг друга. Для межгорода окно рейса не становится точным минутным прогнозом без данных.

### C9A Один снимок с происхождением данных

Новый `GET /courier/v2/orders/{id}/tracking` и существующая proposed recipient track projection возвращают один role-filtered snapshot для соответствующей target stop. Он не создаёт параллельной истины order/custody. Тот же прогноз доступен курьеру, отправителю и разрешённому получателю, но не одинаковый объём приватных данных.

| Группа | Обязательные поля и смысл |
|---|---|
| Identity | snapshot_id, tracking_seq, order_id, assignment_id/generation, item scope, target_stop_id, target_leg_type, route_version, state_version; route binding из M3/11, не клиентская догадка |
| Server | server_now, snapshot_created_at, valid_until, policy_version; UTC в API по K4/C4/D0, IANA timezone соответствующей точки для UI |
| Position | position_id/seq, source, captured_at, received_at, quality/reason; lat/lon только разрешённой проекции; accuracy_m nullable, documented units/provenance |
| Forecast | forecast_id, generated_at nullable + provenance, prediction_as_of nullable, prediction_input_position_id, input_route_version, traffic_as_of/source, method/model_version, arrival_lower_at/arrival_upper_at nullable, forecast_valid_until, eta_state/reason |
| Components | preparation/queue, travel legs, intermediate service, destination access/handover: known/estimated/unknown и inclusion flags; не складывать повторно то, что provider уже включил |
| Change | previous_forecast_id, delta к предыдущему окну, late_against_promised_window, reason/source/confidence; unknown reason не становится «пробкой» без данных |
| Presentation | location_state, eta_state, privacy_mode, last_successful_sync_at, next_recompute_due_at, elapsed_anchor_event/time и накопленный известный elapsed |

Position и ETA имеют независимые fresh/stale/unknown. Дополнительные location states: unavailable, low_accuracy, implausible, privacy_limited. Новый HTTP response/snapshot_created_at не обновляет старые captured_at/generated_at; свежий GPS не доказывает свежесть ETA. Если provider не сообщает семантику/единицы accuracy или время расчёта, adapter отдаёт null/unknown с provenance и сниженным качеством, не accuracy=0 и не «сгенерировано сейчас». Собственный расчёт по старым данным может иметь свежий generated_at, но показывает возраст/неопределённость входов. Captured_at устройства проверяется на skew/порядок; received_at подтверждает только получение. Будущее время не делает точку свежей навсегда.

TrackingSnapshot/Forecast — bounded M7 projection с FK к существующим order/assignment/stop, input version hashes и отдельным tracking_seq; новое GPS-наблюдение не повышает terms_version и не создаёт domain transition. Значимое изменение публикует `tracking.updated`, `eta.changed`, `tracking.degraded/restored` через C6 с безопасным payload/ref. Клиент принимает только актуальные account/order/assignment/route/sequence. Gap → GET/resync; роль проверяется до replay. Отдельный local navigation ETA курьера явно подписан, не выдаётся за общий клиентский прогноз.

Перед публикацией current forecast сервер заново проверяет полный input-generation binding: order/assignment/target stop/leg/route и актуальный этап, computation generation и provenance входов. Новый расчёт не становится current только потому, что завершился последним. Применимое изменение входов/этапа инвалидирует generation; устаревший результат можно сохранить только historic/stale, не как current/fresh. Выбор current snapshot, tracking sequence и значимого event/outbox согласованы атомарной публикацией/эквивалентной сериализацией; конкретный CAS/lock способ фиксируется при реализации. Slow A старой generation после уже опубликованного B не перезаписывает B даже для первого GET нового клиента. Это уточнение существующего guard, не заявление о найденной runtime гонке.

### C9B Расчёт и показ без вечных пяти минут

Пересчёт/инвалидация обязательны при новом качественном fix, traffic/road closure, изменении разрешённого route/stop order, ready/pickup/arrival/handoff/return, существенном stationary interval, expiry или восстановлении сети. Bounded refresh/backoff и отдельные TTL fix/forecast/traffic/clock skew конфигурируются после provider/ops approvals; числа здесь не утверждены. Пока новый расчёт не готов, старый не называется актуальным. Тип транспорта/покрытие провайдера учитываются; городская калибровка не обещает сельскую точность.

Экран показывает абсолютное окно прибытия к названной точке, возраст прогноза и отдельно прошедшее ожидание. Remaining range вычисляется из абсолютного окна и доверенного server anchor, не хранится как бесконечная строка «5 минут». Если время прибытия прошло без arrival, показать просрочку/уточнение и доступный следующий шаг, не «0 минут», arrived или delivered. Повторный честный прогноз «5 минут» допустим при реальном изменении ситуации, но сдвиг абсолютного окна и уже прошедшее ожидание не скрываются. Known delay нельзя сглаживать в цепочку оптимистичных пятиминуток; raw/published predictions и reason сохраняются по ограниченной audit policy. Display error budget/latency/material notification thresholds требуют утверждения и проверки. Не выдавать некалиброванный confidence за «90%» и не завышать ETA скрытым постоянным запасом.

Elapsed от order request, этапа поиска и assignment обозначены отдельно; reassignment не обнуляет общее ожидание. В процессе используется monotonic clock от server anchor. После restart resync; после reboot offline без доверенного времени показывается известная нижняя граница, затем уточнение, не фальшивая точность. Device wall clock/timezone/DST не изменяют деньги или длительности. Платное ожидание только из F5 WaitSession/доказанного arrival; экранный elapsed не начисляет плату.

Map различает observed fix, допустимый map matching и planned route. Плановая линия не является пройденным треком. Короткая интерполяция допустима между уже полученными точками, не экстраполирует будущее движение; stale останавливает анимацию. Accuracy circle только при подтверждённых метрах/семантике. GPS proximity/нулевой ETA/одна фотография не доказывают правильный подъезд, handover, вину или деньги. Свежие одинаковые fixes и один повторённый stale position_id дают разные состояния; anomaly — повод проверки, не обвинение в GPS spoofing.

### C9C Приватность и полезная задержка

Чужие passenger/parcel stops, адреса, контакты и полная polyline не передаются отправителю/получателю. Скрыть подпись, сохранив точную траекторию остановки у чужой двери, недостаточно: сервер применяет разрешённое withholding/coarsening на чужом этапе и явно обозначает privacy_limited, сохраняя допустимый ETA своей точки. Полный рабочий маршрут виден только уполномоченному исполнителю. Фильтр действует в HTTP/websocket/push/cache/history/export, не только на карте. Нет always-on GPS вне необходимого active scope.

Возврат: получатель неудавшейся доставки не получает последующий чужой путь/адрес возврата. Уполномоченные отправитель/принимающий возврат/ответственный оператор могут получать scoped status и ETA своей return stop по правам, согласиям и договору. Это не автоматически даёт live GPS: существующая return-tracking privacy baseline сохраняется до отдельного role-specific решения. Нельзя ни открыть всем полный путь, ни скрыть разрешённый статус возврата от ответственной стороны под видом универсального запрета. Terminal/grant revoke/assignment change закрывают соответствующий live scope, не все другие активные items и не законный доступ к квитанции.

Материальная задержка/смена исполнителя/потеря достоверности отражается в canonical snapshot сразу, уведомление содержит новое окно/изменение/подтверждённую либо неизвестную причину и доступные действия. Rate-limit push не задерживает ухудшение на экране. Push/SMS best effort не равны read/consent. При долгом unknown создаётся case с owner/next_action_at/escalation; support SLO не обещается без реального staffing. Отмена/перенос/новая цена по прежним C/F/K, не автоматическое бесплатное избавление от custody.

### C9D Проверка и эксплуатационный gate

CP-10 A реализует projection/adapters/recompute, B/C одинаковые состояния Android/PWA; CP-22 измеряет отдельно возраст GPS/ETA, signed/absolute ETA error и width/coverage интервала, задержку публикации ухудшения, stale/unavailable denominator, split-brain после resync и privacy leaks по product/город/село/межгород/транспорту. Не смешивать фактический arrival с handover и не исключать неуспешные наблюдения ради красивой точности. Thresholds/SLO/provider contract/калибровка и региональный pilot остаются gates CP-23. SIM-37 и ETA-N01…22 в 05 — будущая приёмка, NOT_EXECUTED, не обещание безошибочного ETA.
