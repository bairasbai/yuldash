# Посылка в опубликованной попутке

Статус: **R4.1_FINAL_CLOSURE_PENDING**. Это необходимое уточнение courier stage 2 по сценарию владельца «зимние вещи Уфа → Сибай» и перевозке документов. Не реализация и не правка RS-211. R3.1 и R4 получили bounded documentation PASS по своим точным bytes/scope; пять NB уточнены здесь в R4.1 и ждут отдельного final diff/hash closure. Runtime **NOT_EXECUTED**.

## R1 Решения владельца и незакрытые параметры

Подтверждён продуктовый смысл: в опубликованной поездке водитель может дополнительно предложить перевозку посылок, указать количество мест, общий вес и габариты; отправитель ищет подходящий маршрут. Поездка может состояться без пассажиров, только с посылками. On-route комиссия платформы равна 0 при допустимой оплате водителю по OD-01. Dedicated — отдельный режим забора из квартиры, офиса, магазина или кафе и доставки в B; он не требует опубликованной поездки и остаётся комиссионным, включая scheduled работу.

Пожелание «между городами и, возможно, сёлами» фиксируется как **предлагаемая initial on-route policy intersettlement**, не как окончательный юридический/продуктовый запрет внутри города. Владелец утверждает классификатор населённых пунктов, коридоры и фазу запуска. Dedicated может работать внутри города и между сёлами: сама география, срочность и отсутствие пассажиров не определяют service_type.

OD-2026-10-25-03 подтверждает продуктовый охват ready pickup и buy-and-bring, плательщиков sender/recipient/merchant. Это не согласие конкретного лица оплатить конкретный заказ и не разрешение НПД, ККТ, банка, покупки любых товаров или LIVE. Численные масса/размеры/крюк/частота, dedicated ставки остаются отдельными owner/provider/legal gates. OD-04 утверждает DRIVER_SET_PRICE и ADVISORY_ONLY, не автоматическую цену или принудительный диапазон.

OD-2026-10-25-04: **DRIVER_SET_PRICE**. Водитель сам указывает итоговую оплату; приблизительный диапазон Юлдаш — **ADVISORY_ONLY**, без обязательной рыночной цены, автоматического выбора/списания или скрытой комиссии. Отправитель и каждый фактический плательщик подтверждают сумму до бронирования. Необязательный совет явно подписан и отделён от согласованной цены.

По поручению владельца выбран предлагаемый UX по умолчанию: **встреча у согласованной точки собственного маршрута**, не обязательная доставка до двери. Заезд к двери допускается только до принятия после согласия водителя, показа добавочного пути/времени/стоимости и проверки RS passenger guards. Численные пределы крюка configurable и OWNER_PENDING; безопасность и действующие пассажирские обещания не отменяются согласием одного отправителя.

Предложение recovery, ещё **NOT_OPERATIONALLY_READY**: до accept известны возвратный адрес, доступный контакт/резервный принимающий и плательщик согласованных расходов. При недоступности получателя действуют связь/ожидание → согласованный возврат; если возврат невозможен, support назначает следующий безопасный transfer/storage с владельцем ответственности и доказательством передачи. Поддержка сама по себе не является местом хранения. Включение зоны требует реально доступного безопасного возврата/партнёра хранения/передачи, договоров и SOP; собственный склад не обязателен. Для еды и документов нужны специальные допустимые условия. Вопрос владельца о способе решения не считается наймом поддержки, подписанным договором или одобрением любых расходов.

## R2 Что существует и что добавляется

Pinned main уже имеет `Ride` с driver_id, маршрутом, depart_at, seats_total/seats_left, baggage boolean, waypoints и status. `Ride.category=parcel` имеет receiver_name/parcel_size. `ParcelDelivery` — другая существующая модель самостоятельной заявки. Эти факты не доказывают совместный резерв мест/багажа/посылок или связь двух жизненных циклов.

Цель — сохранить ParcelDelivery как единственный delivery aggregate с C/F/K, а Ride использовать как источник собственной поездки, маршрута, окна, водителя и совместной транспортной ёмкости. Не создавать посылку одновременно как passenger Booking и ParcelDelivery. Legacy Ride.category=parcel не мигрирует автоматически в completed/accepted ParcelDelivery и не получает выдуманных получателей, proofs или денежных обязательств.

Ride.price/Booking цены остаются в legacy рублях согласно RS-02/D7, ParcelDelivery/quotes/driver reward — в копейках. Новая связь не копирует price одного в другое и не меняет сохранённые денежные единицы.

Новые writers требуют фактически реализованные и проверенные shared contracts RS-02 (segment seats), RS-05 (receipt), RS-14 (vehicle identity) и RS-15 (trip edit/version) либо отдельно принятого совместимого predecessor. Документы PR211 не считаются реализацией. Их текст/статусы этим пакетом не меняются.

### R2A Отсутствующее поле и legacy passenger semantics

Если RideParcelPolicy отсутствует, сохраняются прежние category/visibility/seat/booking guards этого Ride: отсутствие новой policy не выключает обычные пассажирские поездки и не открывает legacy category=parcel для пассажирского Booking. Parcel capability при этом false. Не выводить passenger_booking_enabled из seats_left, baggage или совпадения городов.

Для существующей policy отсутствующее поле passenger_booking_enabled/parcel_enabled в legacy edit означает **не изменять сохранённое значение**, а не false/true по умолчанию. Shared edit adapter всё равно применяет occupancy/version guards; несовместимый старый writer блокирует bridge activation либо получает capability/version error, не обходит guard. Backfill/old DTO не снимают живые Booking и не обнуляют reservations. Новая parcel-only поездка получает explicit passenger_booking_enabled=false только через новый валидированный DTO; переключение не меняет seats_total/физическую конфигурацию и не отменяет существующие пассажирские обещания.

## R3 Публикация и два поиска

В Android/PWA форме существующей поездки появляется необязательный переключатель **«Возьму посылки по пути»**. При включении открывается дополнительный блок:

- принимаемые item kinds: обычная упакованная посылка/сумка и запечатанные документы; restricted categories отдельно закрыты;
- максимальное одновременное количество parcel units, общий вес в граммах, максимальные габариты одного unit в миллиметрах, доступное багажное пространство и условия упаковки;
- согласованное окно передачи, допустимые точки/участки собственного маршрута и заявленные условия дополнительного крюка;
- **«Беру пассажиров»** независимо от parcel toggle. Выключение пассажиров не означает отсутствие реальной собственной поездки, мошенничество или освобождение уже забронированных пассажирских мест.

Никакого произвольного значения «обычная сумка=одно пассажирское место». Count и общий вес обязательны; несколько сумок одного отправителя — несколько DeliveryItems/units в его заказе, конверт — отдельный учтённый unit по принятой policy, не невидимый бесплатный слот. Для нескольких units действует CP-18 item-level capability; до неё поддерживается один unit на order, а не фиктивное полное вручение нескольких сумок.

Draft/preview не публикует parcel capacity. Только successful Ride publication с действующим route/vehicle/version, eligibility и положительным parcel policy validation делает предложение searchable. Включение toggle после публикации допустимо как versioned edit, не доказательство злого умысла. Если backend не поддерживает опцию, публикация не должна молча обещать приём посылок. Lost publish response восстанавливается общим intent, не создаёт второй Ride/offer.

Пассажирская карточка показывает независимо: свободные пассажирские места по выбранному участку и «Берёт посылки: условия». Parcel-only Ride исключён из passenger booking выдачи, но виден в поиске посылок даже при нуле доступных пассажирских мест. Не устанавливать seats_total=0 для обхода legacy validator: proposed passenger_booking_enabled=false хранится отдельно от физической конфигурации машины и существующих броней.

Отправитель выбирает **«Передать по пути»**, населённые пункты/участок, окно, число units/массу/размеры и получает подходящие опубликованные Ride parcel offers. Карточка показывает route, время по точкам, доступную parcel capacity, условия передачи и отдельно оплату водителю/комиссию Юлдаш 0. Точные частные адреса и пассажирские данные не раскрываются публично. **«Заказать курьера»** остаётся отдельным entry point для dedicated, без обязательного Ride и без подмены при пустой on-route выдаче.

## R4 Ride binding версия и API

Proposed `RideParcelPolicy`: ride_id FK Ride UNIQUE, parcel_enabled, passenger_booking_enabled, parcel_terms_version, accepted_item_kinds, max_units, max_total_weight_g, per_unit_bounds_mm, cargo_space_policy_ref, own_trip_route_snapshot_hash, detour_policy_version, handover_window_policy, eligibility_policy_version. `RideParcelReservation`: parcel_id/assignment_id, ride_id, ride_terms_version, parcel_terms_version, vehicle_identity_version, route_span/from-to refs, item_ids/hash, reserved_units/weight/envelope, resource_snapshot_version, state=reserved|carrying|released|recovery_required, last_event_id. Идентичность водителя берётся из Ride и текущего authorized actor, не из клиентского courier_id.

`Ride.terms_version` существующего RS договора покрывает общий маршрут/время/машину/пассажирские условия. `parcel_terms_version` покрывает только обещания посылок и toggle. Изменение свободного остатка от новой брони не переписывает условия; общий `resource_snapshot_version`/CAS отражает ресурсные изменения. Quote/offer/claim snapshot содержит все применимые версии и immutable route/capacity/fee context.

Предлагаемые API adapters, не существующие endpoints:

| API / operation | Договор |
|---|---|
| Existing create/edit Ride DTO extension | optional parcel_policy, passenger_booking_enabled, expected ride/parcel versions; тот же publish/edit service, C3 key, не второй Ride publisher |
| `POST /courier/v2/rides/{ride_id}/parcel-policy` / `set_ride_parcel_policy` | Только владелец Ride; toggle/capacity/item kinds; current route/vehicle/versions, occupancy guards, no internal commit; применяется через общий trip edit adapter |
| `GET /courier/v2/on-route/rides` | origin/destination settlement/route span/window/item summary → safe published Ride offers; cursor; passenger seats не фильтр parcel availability |
| `GET /courier/v2/on-route/rides/{ride_id}/parcel-quote` | Только preview результата; финальная immutable quote создаётся POST quotes с ride binding. GET не резервирует capacity/не создаёт долг |
| POST quotes/orders extensions | service_type=on_route, service_kind=parcel, ride_id/versions/span/items; payer consent до принятия. ParcelDelivery в searching — запрос, не бронирование ёмкости и не обещание водителя |
| `claim_on_route` | Только тот водитель, которому принадлежит выбранный Ride либо отдельно предложенный eligible Ride того же actor; C3/preview/versions, общий self-offer accept из K4; атомарный RideParcelReservation |
| `propose_on_route_trip_change`, `decide_on_route_trip_change` | Отдельный proposed amendment для связанных parcels/обязанных сторон; не изменяет Ride/Book/Parcel до проверки всех обязательных ограничений и consent |
| `request_on_route_recovery` | Отказ/отмена поездки/авария, сохраняет current custody и переводит reservation в recovery_required; support/return/storage/handoff по K2, не auto release вещей |

Новая on-route выдача/accept требует опубликованный active Ride с parcel_enabled, а не один свободный текст «я тоже еду». Legacy standalone ParcelDelivery без ride_id остаётся историей/active recovery; миграция не создаёт выдуманный Ride. Для новой сделки в этом lane водитель публикует собственную поездку/parcel-only Ride и получает новый preview, без ретроактивной комиссии на старый заказ. До внедрения bridge capability закрыта, старые принятые обязательства продолжают безопасный legacy recovery adapter.

### R4A Цена водителя до бронирования

Proposed `OnRoutePriceRequest` — ограниченный pre-order запрос отправителя к конкретному Ride: id, sender_ref, ride_id/versions, immutable item/route/window/handover context hash, expected payer refs, created_at/expires_at. Это не ParcelDelivery, не бронь, не денежное обязательство и не публичная витрина адресов. `DriverPriceProposal`: id/request_id, Ride owner actor_ref, integer driver_amount_kop, RUB, context hash, created_at/expires_at, proposal hash. Proposal immutable и применяется только к pre-order create; новая сумма до create — новый proposal. После create изменения только через существующий F2 amendment с target_order/base_terms и required consents, не второй create proposal. Recommendation snapshot хранит диапазон/допущения/version отдельно и никогда не становится amount по умолчанию. Числовую рыночную границу этим ТЗ не утверждаем; технический integer overflow и недопустимая отрицательная сумма отклоняются.

`POST /courier/v2/on-route/price-requests` / `request_on_route_price` создаёт sender-scoped request с C3 receipt. Только отправитель/его уполномоченный merchant и владелец выбранного Ride видят допустимую проекцию; до assignment скрыты точные частные адреса/телефоны. `POST /courier/v2/on-route/price-requests/{id}/proposals` / `propose_on_route_price` разрешён только текущему владельцу Ride, при действующих версиях/expiry. Ответ показывает его сумму и отдельно совет платформы; никаких денег/резерва capacity.

Sender создаёт immutable F2 quote с request_id/proposal_id и тем же context hash. Цена услуги/водительское вознаграждение берутся только из proposal, platform commission=0; состав согласованных возможных wait/return расходов виден отдельно, не скрыт в совете. Create проверяет неизменность Ride/context, срок request/proposal/quote, явное согласие отправителя и каждого плательщика на соответствующие строки. QuoteUse остаётся единственным create consumption, а proposal не может породить более одного ParcelDelivery: UNIQUE proposal_id в QuoteUse, проверяемый под quote/proposal lock. Несколько previews одного proposal не создают несколько резервов/заказов. Действующий proposal — согласие водителя с суммой, но не обещание свободной машины: assignment только после canonical claim/capacity transaction. Claim сохраняет ту же цену/fee snapshot; если условия устарели, отказ/new proposal/quote/consent, не автоматическая подстановка рекомендации.

Общий порядок C3 для этого lane: Ride/Booking → price request/proposal IDs → quote IDs → orders и последующие ресурсы. Нет позднего захвата Ride из already locked order. Immutable proposals не отзывают уже созданные обязательства; expiry блокирует новые create, не отменяет уже созданный order и не снимает custody. После create claim проверяет актуальный stored order/claim preview, а не истечение прежнего quote/proposal как повод молча отозвать договор. TTL/retention/anti-spam численные настройки отдельные gates. M6R добавляет обе таблицы и single-use binding; CP-08A2 использует уже готовый F2/CP-05 quote foundation, без обратной зависимости CP-05 от Ride bridge.

### R4B Общий receipt до появления order

Новые операции регистрируются в единственном RS-05/C3 каталоге, без второго journal:

| Operation | Scope / target | Applied refs / current |
|---|---|---|
| request_on_route_price | scope=courier.on_route.price_request.create, target_kind=ride, target_id=Ride ID, creator stable actor anchor | ref_kind=on_route_price_request + request_id; actor-scoped safe current request status/expiry/context hash, без сырого адреса/телефона/item-private payload |
| propose_on_route_price | scope=courier.on_route.price_proposal.create, target_kind=on_route_price_request, target_id=request_id, creator Ride-owner actor anchor | ref_kind=driver_price_proposal + proposal_id; safe current amount/currency/context/expiry и permitted refs; не чужой sender profile |

order_id в durable intent/receipt ref для этих операций **nullable до create**; scope/target/key/hash и actor namespace обязательны. Applied/result_gone остаётся доказанным applied, не unseen. Replay/GET проверяет актуальные права creator и phase; контекст для другой роли получается отдельным разрешённым resource read, не чтением чужого receipt. Удаление/retention используют K6 stable anchor/hold и не превращают old key в новый. Receipt не хранит raw private request body.

Lost response → restart → GET /command-intents/{key} с прежним actor/key. Applied возвращает safe ref/current либо result_gone; unseen с утраченным body → общий atomic close по сохранённым scope/target/hash, затем только новое явное намерение. Поздний original после close не создаёт второй request/proposal. Не требовать выдуманный order_id и не менять key ради отсутствия ответа. M6R расширяет enum/DTO mapping общего registry; CP-07 clients переиспользуют nullable resource intent contract.

## R5 Совместная ёмкость без смешения единиц

Пассажирские seats, обещанный пассажирский багаж, личный груз водителя и parcel units/weight/dimensions — разные ресурсы. Ограничения пассажирских мест RS-02 и обещанных опций не ослабляются. Свободный seat не становится разрешением заполнить его посылкой, если это нарушает безопасность/согласованную конфигурацию. Несколько независимых ParcelDelivery могут делить Ride capacity, но не имеют общего paid/recipient/proof.

По каждому traversed leg проверяются: passenger seat occupancy ≤ разрешённые seats; passenger baggage commitments и личный груз оставляют объявленный parcel space; сумма всех nonreleased reserved/carrying/recovery_required parcel units и веса по R5A укладывается в parcel policy и безопасную допустимую загрузку транспорта. Проверка per-unit габаритов и packing/крепления обязательна; один только суммарный объём не доказывает, что коробки физически помещаются. Точные numeric load envelopes/багажные allowances утверждаются по транспорту и обещаниям, не выводятся из baggage=true. Вес/здоровье пассажиров не собираются ради этого механизма: используется одобренный безопасный capacity envelope. Недостаточные данные → нельзя обещать дополнительную parcel capacity, manual review/уменьшение предлагаемой ёмкости до согласования.

Общий lock order для связанных операций расширяет C3 и совместим с RS-15: principal+intent → Ride IDs sorted → active Booking IDs sorted → price request/proposal IDs → quote IDs → ParcelDelivery IDs → assignments/items → obligations → cash/source/intent locks по C3. Все пассажирские booking/edit/cancel и parcel accept/release/transfer writers, затрагивающие общую ёмкость, проходят этот же guard/CAS. Сначала read IDs, затем lock/refresh/revalidate binding; изменение связей после read означает retry/reconcile, не поздний захват Ride после Parcel lock. Если shared writer ещё не интегрирован, Ride-parcel capability не включается.

Passenger booking и parcel accept сериализуются на Ride/resource snapshot: первая успешная операция фиксирует свой ресурс, вторая перечитывает остатки и принимает либо отклоняет без двойной ёмкости. Изменение резервов, assignment, event/outbox и receipt атомарно. Query/quote/предварительный интерес не резервируют ресурс. Accepted parcel резервируется по своим legs; pickup меняет reserved → carrying, не возвращает capacity в ленту. Освобождение только по подтверждённому handover/cancel-before-custody/approved recovery. Return/storage может удерживать часть physical resource дольше исходного dropoff: невозможный следующий leg не принимается оптимистично.

Уменьшить policy ниже занятых/обещанных ресурсов, выключить passenger booking при живой броне или подменить машину на недостаточную нельзя тихо. Увеличение parcel promise не отнимает пассажирский багаж/комфорт. Quote/net не пересчитываются молча после конфликтующей booking.

### R5A Явный ресурсный hold при recovery

`occupied_resource` включает и коммерчески зарезервированную, и фактически занятую ёмкость. Это вычисление из того же RideParcelReservation и подтверждённого item custody, не второй журнал физической истины. Закрытие новых admissions не означает освобождение существующего hold.

| Reservation state / доказательство | Что удерживается | Когда освобождается |
|---|---|---|
| reserved, pickup ещё не подтверждён | Declared units/weight/envelope на всех согласованных traversed legs; физическое отсутствие у водителя не делает обещанный ресурс доступным другому | Подтверждённый cancel/release до custody с прежними C/K guards либо доказанный transfer в другой допустимый ресурс |
| carrying | Реально перевозимый item занимает units/weight/envelope до подтверждённого unloading/transfer; плановое время/конец dropoff leg не освобождают | Scoped proof delivery/return/storage/handoff или явно разрешённое физическое решение K1/K2 |
| recovery_required | Сохраняются последний неосвобождённый physical/reservation hold и все применимые ресурсы; смена статуса не вычитает capacity | Только соответствующее доказанное physical resolution; если pickup не состоялся, разрешённый no-custody cancellation после проверки, не автоматическое снятие |
| unknown holder/span либо failed dropoff с overhang | Nonreleased hold остаётся; для неопределённого продолжения консервативно удерживается affected resource на потенциально затрагиваемых оставшихся legs. Если их нельзя безопасно ограничить, новые несовместимые admissions/расширения для этого Ride заблокированы до resolution | Уполномоченный recovery подтверждает holder/span и безопасное следующее действие; само истечение окна/lease/таймера не доказательство |
| released | Только уже доказанно освобождённый конкретный item/resource scope не входит в active sum; запись хранится как история | Повтор того же receipt/proof не освобождает дополнительные units/legs и не меняет чужие reservations |

`unknown` здесь quality/hold reason существующей reservation/custody, не новое terminal состояние. Capacity predicate учитывает **все nonreleased states: reserved, carrying, recovery_required**, а неизвестный span дополнительно блокирует потенциально конфликтующее admission. Нельзя реализовать сумму только reserved/carrying и потерять recovery_required. При частичном transfer освобождается ровно переданное подмножество; остальные items/holds сохраняются.

Если на следующем leg уже принято другое обязательство, внезапный overhang не стирает его Booking/ParcelDelivery и не объявляет выполнимым. Под тем же Ride lock фиксируется conflict/recovery, блокируется несовместимая новая посадка/загрузка и расширение. Оператор/участники получают affected commitments и согласуют допустимый перенос/другой ресурс/отмену по существующим договорам; деньги и ответственность не переписываются автоматически. Нельзя одновременно физически загрузить оба несовместимых объёма ради сохранения статусов «принято». Все изменения ресурсов/proofs/events/receipt атомарны по C3.

## R6 Trip edit cancel и физическая ответственность

RS-15 ограничения на route/time/seats при живых пассажирских Booking сохраняются: parcel integration не расширяет разрешённые пассажирские правки. С parcel reservations маршрут/окно/машина/передача требуют пересчёта каждой затронутой reservation, quote delta и необходимых consents; атомарный apply только если также разрешён существующим RS contract. Если право изменения отсутствует, предлагается отдельный recovery/rebooking flow, не частичная запись.

До pickup допустим отзыв конкретного parcel commitment с уведомлением, освобождением его resource после доказанного no-purchase/no-custody, применимой денежной отменой и новым добровольным поиском. После pickup cancel Ride закрывает новые admissions, но не делает parcel canceled/returned и не освобождает carrying slot. Остаются current holder, responsibility, следующий безопасный шаг и support owner. Вещь возвращается/хранится/передаётся по отдельному scoped proof; банковская ошибка или отсутствие пассажиров этого не меняют.

При поломке/смене исполнителя новый Ride/курьер сначала проходит capacity/eligibility/условия; old/new Ride и assignments блокируются в одном порядке, K2 handoff атомарно переносит только доказанные items/capacity. Неизвестная физическая передача → recovery_required, не двойное active custody. Если предлагается dedicated спасение on-route заказа, новые деньги/исполнитель/fee policy требуют отдельного quote и явного согласия; старая выполненная on-route работа не становится комиссионной задним числом. Отказ от платного предложения не разрешает бросить посылку.

## R7 Документы и несколько сумок

Canonical service_kind остаётся parcel; `item_kind=sealed_documents|clothes_bag|ordinary_package` уточняет содержимое на уровне разрешённой декларации. Документы — запечатанный конверт/пакет, количество, внешние размеры/вес, целостность/опциональный seal reference и полномочный получатель. Нельзя требовать фото/скан страниц, паспортных данных или содержимого ради proof; фото упаковки без читаемых чувствительных сведений по согласию достаточно как дополнение к разрешённому POD. Не вскрывать конверт для классификации. Подозрение на запрещённую/опасную категорию → отказ/безопасная поддержка по SOP, не нелегальный досмотр.

Обычные документы не означают автоматического допуска наличных, ценных бумаг на предъявителя, государственных секретов или иных специальных документов. Точный restricted list/ценность/страхование/компенсация и legal applicability остаются gates. Объявленная ценность не обещает восстановление утраченного уникального документа. Адрес/телефон получателя приватны до допустимой фазы; пассажир не видит содержимое чужих отправлений.

Несколько сумок/конвертов перечислены отдельными item IDs и входят в declared weight/capacity. Частичное вручение либо повреждённая упаковка одного item не завершает остальные; K1/K2 POD и mixed projection сохраняются. Изменение числа/веса/габаритов у двери требует safety check/amendment, не молчаливой перегрузки и не автоматической «штрафной» цены.

## R8 Защита от обхода без ложной гарантии

Сервис не может достоверно доказать внутреннюю цель поездки. Предлагаемые защитные сигналы: опубликованный собственный маршрут/окно до claim, совместимость точек и направления, версия машины/capacity, явно согласованный крюк, несовместимые пересекающиеся обязательства, повторные отмены/смены маршрута после accepted parcel и подтверждённые жалобы. Пороги расстояния/времени/частоты/риска, ограничение intersettlement и действия по сигналу утверждаются отдельно; количеств здесь не назначаем.

Отсутствие пассажиров, один parcel-only Ride, новый аккаунт, село проживания или низкий доход не являются доказательством обхода и не дают автоматического штрафа. Не строить дискриминационный профиль и не отслеживать сторонние аккаунты/устройства либо постоянную геолокацию вне необходимого активного scope. Не требовать всегда включённый GPS ради «доказательства честности».

Понятные reason codes: route mismatch, stale/changed route, capacity conflict, eligibility missing, manual review required. User видит конкретную исправимую причину, помощь и обжалование human review; риск-сигнал не объявляется фактом мошенничества. До решения можно остановить новые предложения по согласованной policy, но active custody/возврат/заработанная работа и доступ к безопасной поддержке сохраняются. Ни риск-скор, ни appeal не создают новый денежный долг.

Dedicated A → B нельзя отправить в zero-fee lane подменой service_type/ride_id. Если own-route условия не подходят, показывается отдельное добровольное предложение dedicated с полной ценой/комиссией и согласиями, а не hidden fee или принудительная переклассификация. Цель — затруднить и обнаруживать нарушения с минимизацией данных, а не обещание полного отсутствия обходов.

## R9 Данные очередь совместимость и приёмка

Новая описательная migration **M6R**, после M6 и принятых RS prerequisites, до включения Ride bridge, но не обязательный predecessor dedicated M7: RideParcelPolicy/Reservation и OnRoutePriceRequest/DriverPriceProposal с UNIQUE QuoteUse.proposal_id, nullable ParcelDelivery ride binding, policy/resource versions, unique active reservation per assignment/item/leg и FK к существующему Ride. Backfill legacy не выставляет parcel_enabled=true, не придумывает вместимость из seats_left/baggage и не связывает чужие сущности по одному совпадению городов. Старый active parcel без Ride не теряет custody/деньги и идёт в recovery. Down/up и rollback сохраняют reservations и действующие обязательства; старый booking writer без общего guard не допускается после bridge activation.

CP-08 делится: A1 existing offer/capacity foundation M6 → A2 Ride bridge M6R после RS-02/05/14/15 actual acceptance; Android B1/PWA C1 generic → B2/C2 toggle/search/card/edit flows после A2. CP-09 и on-route pilot ждут применимый A2; dedicated без Ride не получает лишнюю зависимость на этот bridge. CP-18 позже добавляет оптимизацию batch/multi-item/handoff; независимые single-item parcel orders одного Ride используют общую capacity уже в A2, не сливая их в один order. Multi-item per order не включается до CP-18.

Общие существующие файлы требуют отдельного lock: backend models/schemas, routers rides/bookings/parcels/courier, принятый shared ride_seats service; Android CreateRideScreen/CreateRideStops/Domain/ApiClient и route cards; PWA CreateRideScreen, api rides/bookings/parcels/courier, RideCard/RideSheet. Точные текущие пути/владельцы сверяются перед кодом; новые modules названы proposed, чужие RS PR не редактируются в этой документационной работе. Source refs в 07, очередь D4.

Обязательные будущие acceptance: опубликованный Ride с пассажирами+двумя независимыми parcels; parcel-only без пассажиров; одинаковые units/weight limits на Android/PWA/API; document envelope без contents photo; passenger baggage booking против parcel accept в PG; trip edit/cancel до/после pickup; old/new Ride handoff; lost publish/claim response; route mismatch/appeal; allservice_type/fee/payer snapshots; legacy no-binding recovery. Каждый шаг сохраняет W/C/P/O/A, точный build/SHA и expected/actual. Все эти тесты NOT_EXECUTED; intersettlement policy, numeric limits и запуск требуют своих gates.

## R10 Карта собственной посылки без пассажирских адресов

C9 относится и к on-route: согласованное окно поездки не становится точным ETA без наблюдений; учитываются разрешённые промежуточные остановки и handover. Отправителю объясняется влияние очереди, но не передаются пассажирские pickup/dropoff/полная polyline/историческая остановка у частного дома. На чужом leg сервер выдаёт privacy_limited проекцию и допустимый ETA своей точки. Receiver link не открывает весь Ride. Изменение маршрута/поездки/handoff инвалидирует соответствующий forecast. Возвратный статус/ETA доступны только своим уполномоченным ролям, live GPS отдельно gated.
