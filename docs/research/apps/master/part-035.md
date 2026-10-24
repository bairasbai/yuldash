CARD_ADD и CARD_SELECT проходят через разные callbacks в родительские команды. Проверенный external parent направляет их в разные маршруты добавления карты и способов оплаты; native parent использует разные navigation payload после общей предварительной dispatch-команды. Эта команда не названа доказанным dismiss. Навигационный запрос не означает, что карта привязана, верифицирована или выбрана.

В выбранных callbacks не передаются promo code, card ID или attempt ID и не обнаружена прямая result→activation continuation. Native helper всё же передаёт общий singleton callback: его непроверенная логика, общий observer или последующая активация пользователем не исключены. Поэтому локальный результат нельзя расширять до утверждения, что приложение вообще не обрабатывает возврат из card UI.

Самостоятельные предложения для Юлдаша: хранить account-scoped promo intent и operation ID отдельно от перехода, повторно проверять карту и eligibility кода, различать cancel/error/unknown и принятый сервером discount. Непроведённые fake-тесты: ADD/SELECT routing, external/native payload, возврат после ошибки или отмены, смена аккаунта/кода и общий callback. Navigation, analytics и отсутствие ошибки dispatch не являются ACK активации или скидки. Runtime, реальные карты, коды, deeplink переходы и API не использовались.

## Яндекс Про валидация персональных полей пополнения

Ограниченная статическая проверка: 13 новых полных тел, 2188 native bytes и 97 direct branch checks; независимая QA PASS. Прежний submit, shared DTO и generic helpers имеют нулевой новый кредит; глобальное покрытие не пересчитывается.

Factory заменяет initial value на пустую строку только при null. Empty value валидно ровно при required=false, до обращения к regex; whitespace не считается empty этой проверкой. Для непустой строки выполняется regex match без добавления anchors, trim или сравнения конца совпадения с длиной ввода. Выбранные группы исключений дают false, другие перебрасываются; полный EH scope не замкнут, blanket catch-all не заявляется.

Callback передаёт id/value, игнорируя дополнительный UI bool. Interactor блокирует update при initialization в момент входа; queued worker позже читает текущую personal Data и не повторяет этот guard. Update сохраняет model и правила, неизвестный id не создаёт поле; все совпавшие id обновляются, но production duplicates не установлены. Это не доказательство runtime race или обхода.

Сборщик request values переносит id/value без повторной validation, trim и удаления optional empty. Повторный ключ оставляет последнее значение в result map. Однако прежний submit отдельно проверяет current states, busy, every(isValid) и InitAction до сборщика. Отсутствие collector validation не означает отсутствие caller guard; map view не объявлен immutable snapshot всего экрана.

Самостоятельные предложения для Юлдаша: явно определить required/regex/unique-ID контракт и разделить UI bool, доменную validation и server acceptance. Непроведённые fake-тесты: optional empty, malformed/anchored pattern, substring match, same/unknown/duplicate id, изменение state между enqueue и worker, invalid field перед обычным submit. Input formatting, outer initialization, scheduling, серверные правила и balance reconciliation открыты. Реальные поля, API, приложение и платежи не использовались.

## BlaBlaCar изменение выбора страны и способа выплат

Ограниченная статическая проверка: 2 новых полных тела на 300 instruction units; 2 прежних caller на 34 units и 4 helpers на 134 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS; общий decoder не является второй независимой декомпиляцией. Глобальный знаменатель не меняется.

Выбор страны копирует все записи и задаёт selected по равенству code, без trim/case normalization. Все совпавшие code становятся selected; неизвестный code снимает выбор со всех. Списки методов и их прежние flags сохраняются. showCta определяется непустым allowedMethods только первой совпавшей исходной страны, а не наличием выбранного метода; null/empty или отсутствие совпадения дают false.

Выбор метода рассматривает все страны, не проверяя Country.isSelected. В каждой стране, содержащей заданный enum, он выбирает все совпадающие записи метода и снимает выбор с остальных; страны без этого enum сохраняет. showCta при этом не пересчитывается. Поэтому единственность выбранной страны и метода требует инвариантов входных данных, а не следует безусловно из этих функций. Прежний submit использует первую selected страну и первый selected метод внутри неё.

Самостоятельные предложения для Юлдаша: определить duplicate policy и различать непустой allowed list, выбранный метод, CTA и server eligibility. Непроведённые unit-тесты: unknown/duplicate code, методы в выбранных и невыбранных странах, duplicate enum, country→method→country и сохранение showCta/snackbar. Initial mapper, non-null list invariant, actual UI gates и pending interaction здесь не закрыты; runtime bug, серверный допуск и финансовый outcome не заявлены. Аккаунты, реквизиты, API и приложение не использовались.

## Яндекс Про сброс и инициализация формы пополнения

Ограниченная статическая проверка: 13 новых полных тел, 2624 native bytes и 140 direct branch checks; независимая QA PASS. Прежние helpers и generic queue/debounce не получают нового кредита, глобальное покрытие не пересчитывается.

Public init вызывает загрузку preview без await, поэтому completion его Future не является признаком готовности формы. Внутренний путь ждёт reset calculation и personal managers, затем preview; null прекращает путь, ненулевой результат передаёт action/payment-info и personal fields двум initialize и ждёт их. Это порядок ожиданий, не атомарная публикация трёх состояний и не доказанный generation barrier.

Reset workers эмитируют Initial; calculation reset также вызывает прежнюю отмену расчёта. Initialize создаёт Data из захваченных preview аргументов, с calculating=false и без merge прежних edits в этих workers. Начальный action приходит из preview без нового calculate API. Cancellation delivery, queue ordering и возможность смешанных поколений остаются открытыми; runtime race не заявляется.

Preview manager сохраняет result до success telemetry и emit TopupData. Обычный ApiResponseError ведёт в Error; прочие non-success не имеют этой ветви. Exception landing не очищает сохранённый result, но полный EH scope не восстановлен, поэтому расхождение return/state после ошибки не объявлено воспроизведённым дефектом. Returned preview и emitted state нельзя считать одним атомарным результатом; вечный Loading после cancel не доказан.

Самостоятельные предложения для Юлдаша: выделить readiness и generation contract, различать preview модели и авторитетный денежный статус. Непроведённые fake-тесты: ожидание обоих reset, null/error/cancel preview, ошибки до/после result store, повторная загрузка вместе с edits и optional sections. PreviewSuccess и balance-info mapping не доказывают зачисление; transport/DTO, полный EH, lifecycle и status reconciliation открыты. API, APK, URL и реальные данные не использовались.

## Яндекс Go C109 выбор локального промокода и обновление списка

Ограниченный final report, не frozen checkpoint: 588 выбранных записей = 579 новых и 9 повторных; 18 полных тел и 2 фрагмента, 480 записей исключены. Независимая QA PASS, 5011 проверка. Прежние activation/API и исключённые consumer диапазоны не получают нового кредита; глобальный ledger не меняется.

Приоритет getter: любой non-null override, затем подходящий непустой TRAP code, затем выбранный элемент zone cache. Флаг m блокирует только последний fallback, не ранние источники. Blank selectedCode сохраняет прежние selected flags; nonblank отмечает все совпадающие code без повторной проверки статуса. UID equality ограничивает выдачу cache, но не является TTL или доказательством полной account isolation.

Reset очищает override и конкретный preference editor, затем ставит m=true; он не очищает TRAP, cache модели или существующий Preorder.z этим телом. Refresh зоны сначала исключает её entries из нового snapshot, затем отменяет прежний job без join и запускает новый. Actualize ждёт лишь свой путь запуска, не завершение refresh; его catch не покрывает будущую ошибку job. Returned Failure внутри job отдельно не анализируется, а прежний refresh может включать legacy activation, поэтому не объявлен чистым read-only.

Выбранный preorder consumer сначала проверяет локальный predicate, затем отдельным чтением получает code и записывает Preorder.z; после этого очищает override flow. При false записи z в этом фрагменте нет, но весь reset/serialization lifecycle не исследован. Отдельные чтения не доказывают runtime race, client assignment не означает принятую сервером скидку.

Самостоятельные предложения для Юлдаша: задать приоритет и account/zone scope источников, точный reset contract, freshness/version и различие started/completed/failed refresh. Непроведённые fake-тесты: null/empty/blank override, TRAP после reset, UID mismatch, пустая zone entry, late response отменённого job и consumer read/assign/clear. Server redemption, budget, цена, полный DI/account lifecycle и runtime остаются открытыми; реальные коды, профиль и API не использовались.

## BlaBlaCar начальный выбор страны и способа выплат

Уточнение предыдущих mutation и submit разделов: 3 новых полных тела на 231 instruction units; 2 прежних fetch тела на 149 units и 2 helpers на 31 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS; общий decoder не является второй независимой декомпиляцией. Глобальный знаменатель не меняется.

Initial mapper сохраняет порядок, code, name и country.isSelected без дедупликации или нормализации. В каждой стране создаёт новый ArrayList методов и выбирает только позицию 0, включая невыбранные страны. При нормальном успешном возврате этого producer все output method lists non-null, а в каждом непустом списке ровно один начальный selected item. Null input list не заменяется пустым; все остальные producers/restoration этим инвариантом не охвачены.

showCta берётся по непустому списку методов первой selected исходной страны, без перехода к следующей при пустом списке. Поэтому нормальный output этого mapper не создаёт случай nonempty list без selected method, хотя общий mutation-контракт ранее допускал такие входы. Country uniqueness не обеспечивается. Дубликаты enum первоначально выбираются только по позиции, но последующая enum mutation отмечает все совпадения, так что единственность после mutation требует отдельного инварианта.

Самостоятельные предложения для Юлдаша: явно задать позиционный default, duplicate policy и поведение initial→mutation→request. Непроведённые unit-тесты: перестановка методов, пустая первая selected страна, несколько selected countries, duplicate code/enum и повторный mapping. Actual UI gates, input nullability, restoration и server eligibility открыты. Default selection не означает согласие, проверку аккаунта или выплату; API, приложение и реальные данные не использовались.

## BlaBlaCar UI выбора страны и способа выплат

Ограниченная статическая проверка: 8 новых полных UI business/binding тел на 2216 instruction units; 17 helpers, 2 выбранные generic dispatcher ветви и enum table имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS, без второй независимой декомпиляции. Остальные dispatcher ветви и shared widgets не объявлены покрытыми; глобальный знаменатель не меняется.

Success UI передаёт showCta в условие создания кнопки с фактическим callback sendStep. Loading/Error имеют отдельные ветви, но момент recomposition и судьба ранее поставленных событий не установлены. Country selector сохраняет порядок и дубликаты code как item.id; callback передаёт этот id в selectCountry. Отображаемый selected item определяется первым совпадением ID с первой selected страной, а не уникальной object identity.

Методы берутся только из первой selected страны. Отсутствующий/null/empty список ведёт к пустому списку и warning без поиска следующей непустой страны. Radio mapper сохраняет каждый isSelected. Callback читает enum из захваченных списка и index, игнорируя входной Boolean, и передаёт enum в selectPaymentMethod, не account ID. Достижимость stale index или повторного события в runtime не доказана.

Самостоятельные предложения для Юлдаша: согласовать showCta, first-match и uniqueness policy с initial→mutation→UI→request. Непроведённые fake/UI-тесты: showCta true/false, duplicate code/enum, пустая первая selected страна, radio true/false и позднее событие после Loading. Widget clickability, DI/lifecycle, pending interaction и server eligibility открыты; кнопка, иконка и next-step не подтверждают согласие, проверку аккаунта или выплату. Реальные данные, API и приложение не использовались.

## Яндекс Про API предварительной формы пополнения

Ограниченная статическая проверка закрывает ранее открытую границу getPreview: 10 новых полных тел, 4180 байт и 175 прямых переходов; прежние manager, mapper, action/input decoders и общие helpers имеют нулевой повторный кредит. Независимая QA PASS. Это не полный аудит приложения и не изменение глобального покрытия.

Provider identifier передаётся в query; cancellation context получается отдельно. В проверенном request body нет суммы и input_fields_data. Конкретный transport/provider binding и HTTP verb не установлены. Нормальный возврат Success следует только после requireData и декодирования: выделение wrapper до decoder не означает ранний успех. Выбранная exception landing обрабатывает определённую группу Dio ошибок, остальные перебрасывает; полные protected regions и все исходы декодирования не восстановлены.

Root требует amount_limits, currency, input_fields, payment_info и topup_action соответствующих типов. Элементы списков проходят Map cast, собственной ветки пропуска неверного элемента нет. balance_info/provider_info могут отсутствовать; recommendations null остаётся null именно в DTO, а не автоматически пустым списком. В присутствующем balance_info title/subtitle обязательны как строки. amount_limits читает только formatted_value: это отображаемый текст, не числовые min/max и не доказательство отсутствия серверных лимитов.

ProviderInfo содержит отдельный id, но выбранные decoders не сравнивают его с request identifier. Recommendation.value и formatted_value также отдельные строки без локальной проверки числовой эквивалентности. Эти границы не доказывают подмену provider или отсутствие downstream/server validation. Передача balance title/subtitle через прежние mappers не вычисляет зачисление; renderer и авторитетная сверка платёжного статуса остаются открыты. Интерпретация PP/CID опирается на привязанные к хэшам аннотации AOT; runtime, API и реальные значения не использовались.

Самостоятельные предложения для Юлдаша: разделить presentation strings, числовые ограничения и авторитетный финансовый статус; явно задать provider identity invariant и recovery при malformed payload. Непроведённые тесты: missing/null/wrong type против empty, неверный элемент списка, расхождение provider ID и recommendation value/formatted_value, requireData/decode exception и изменение balance text без транзакции. Ни форма preview, ни typed Success не подтверждают зачисленный баланс.

## Яндекс Go C110 Удаление общего платёжного аккаунта

Ограниченный final report, не frozen checkpoint: 14 полных тел и 3 фрагмента, 511 выбранных records = 505 новых + 6 повторных из C50; 200 records исключены. Независимая QA PASS в статических границах, глобальный ledger не пересчитывается.

Две проверенные ветви подтверждения передают shared account ID в remove repository, не UID пассажира или номер карты. В details отсутствие аккаунта в аналитическом lookup логируется, но само по себе не блокирует launch. В settings сохраняется прежний флаг состояния перед запуском coroutine; собственной проверки уже выполняющегося запроса в выбранном runnable нет. Это не доказательство доступности операции без прав, фактического согласия или двойного запроса в runtime; полная DI identity и role visibility открыты.

После нормального завершения сетевого helper repository очищает payment-list LruCache и пытается отправить refresh event, игнорируя Boolean результата. Свежий список и reconciliation выбранной оплаты здесь не ожидаются. Отдельный server deletion receipt не разбирается. Исключение до normal return пропускает эту последовательность; ошибка cache/event продолжения после normal remote return может не дать repository вернуть Unit. Локальная инвалидация не подтверждает отзыв финансовых прав, отмену заказа или удаление аккаунта пассажира.

UI navigation и success message входят в проверенные try regions: их ошибка может привести к error UI уже после нормального remove return. Details cleanup вызывается после normal/обработанной error ветви, но не является finally; cancellation перебрасывается до него, ошибка error handler также может его пропустить. Settings error восстанавливает захваченное прежнее значение флага, а не безусловную константу; cancellation минует этот reset. Это границы локальных ветвей, не доказанный зависший spinner или гарантированно закрытый экран.

Самостоятельные требования Юлдаша: различать удаление общего платёжного профиля, отзыв доступа и удаление пассажирского аккаунта; проверять роль на сервере, отдельно сверять payment list и order snapshots, не повторять изменение автоматически из-за ошибки UI после remote completion. Непроведённые fake-тесты: positive/negative/dismiss, missing analytics lookup, normal/throw/cancel, event true/false без consumer, ошибка cache/navigation после нормального ответа и lifecycle cleanup. Реальное удаление, API, платежи и runtime не выполнялись; серверный отзыв, durable результат и выбранная оплата остаются неподтверждёнными.

## Яндекс Про Рекомендованная сумма и ввод для расчёта

Ограниченная статическая проверка: 7 новых полных тел, 1556 байт и 58 прямых переходов; отдельно 2 partial bodies, 3 диапазона на 464 байта и 13 переходов. Независимая QA PASS. Полные screen/section layout не засчитаны; прежние formatter, mapper, calculation и gesture helpers имеют нулевой повторный кредит.

Карточка отображает formatted_value и description, но callback передаёт отдельный raw value захваченной recommendation. При initialization=true карточке передаётся null onTap; это не тот же флаг, что calculation busy. Пустой список и hidden layout отдельно исключают создание секции/карточек в выбранных диапазонах. Runtime доставка событий, accessibility и остальная раскладка не установлены.

Screen сначала вызывает setMaskText(raw), затем передаёт тот же исходный String в текущий VM и interactor. Он не перечитывает controller.text или unmaskedText после formatter. Общий setMaskText действительно применяет formatter, поэтому результат контроллера нельзя автоматически считать равным raw input; конкретная topup formatter configuration и listener effects здесь открыты. Interactor сначала вызывает telemetry, затем onAmountChanged, ведущий к calculation, не непосредственно к init/payment.

Прежний onAmountChanged проверяет текущее initialization перед calculate, но этот guard находится после программной записи controller. Обычный initializing snapshot получает null callback; достижимость сохранённого callback после смены состояния не доказана. Нельзя объявлять ни runtime обход, ни гарантированную блокировку всех изменений. Raw value, текст карточки, controller output, calculation action.amount и зачисленный баланс остаются разными контрактами.

