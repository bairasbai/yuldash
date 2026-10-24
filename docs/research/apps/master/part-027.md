## BlaBlaCar пассажиры и скидки при переносе формы поиска

В проверенном Entity→Nav предикате CTA добавления скидок используется первая совпавшая возрастная категория. Без неё или непустых discount groups CTA=false; при разном количестве discounts и groups CTA=true; при равном количестве true даёт наличие хотя бы одного unselected discount, иначе false. Соответствие кодов скидок группам внутри этого предиката не проверяется. Видимость CTA не доказывает право на льготу, применение скидки или цену.

Nav→UI устанавливает displayEdit=false только для текущей позиции 0, non-null saved passenger details и пустого discounts. Во всех иных сочетаниях true. Это позиция обхода, не сохранённый Nav.index; passenger.isSelected и discountOptions в guard не участвуют. Вывод относится к полю UI model, не доказывает фактическое скрытие всех способов редактирования.

Полные списки passengers, discounts и discountOptions переносятся с исходным порядком и selected=false; отдельный список для discountsLabel фильтруется по selected=true, не изменяя передаваемые данные. Точный R8 callback dispatch и строковое форматирование не закрыты. maxPassengers и nullable ageRange переносятся, но выбранные mappers не являются доказанными валидаторами числа пассажиров, возрастных диапазонов или saved identity.

Прежние VM bodies связывают mappers с реальными callers без нового кредита. SearchForm init выбирает Nav→UI лишь при statutory-discount feature и наличии Nav; иначе доступен иной путь получения формы. Поэтому выводы не распространяются на все режимы поиска и не доказывают последовательное исполнение обоих mappers в одной runtime-сессии.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 19 новых полных тел, 1 316 code units; три прежних полных тела и 490 units имеют нулевой новый credit. Это один составной сценарий с supporting dependencies, не 19 бизнес-функций. Original DEX bindings/bytes проверены, но общий decoder не является второй независимой декомпиляцией; transitive model/library bodies, фактический UI, серверная льгота, цена и persistence остаются открытыми. Report/QA pins и manifest совпали; global coverage не пересчитан.

Самостоятельные предложения для Юлдаша: отделить UI guard от бизнес-валидации льгот; определить уникальность age codes и роль позиции/index; сохранять unselected данные при roundtrip. Непроведённые тесты: CTA count/selected matrix, дубли age codes и неверные group codes, displayEdit после перестановки, selected=false, null/empty, maxPassengers/ageRange и feature off/on с Nav absent/present. APK, Android/JVM и API не запускались; runtime defect и ошибочная цена не заявляются.

## Go C85 вход в повторную оплату и конкретный cleanup

PayDebtAction сохраняет список order ids до проверки выбранного способа. Без способа вызывается маршрут выбора; платёжный interactor повторно читает current selection и при null возвращается, сохраняя непустой cache. Empty input в этом interactor сам по себе cache не очищает. Поэтому запись pending списка, выбор способа и запуск оплаты являются разными стадиями, а два current-read не образуют доказанную атомарную операцию.

Уточнён конкретный открытый участок C84: closeScreenWithNoDebts сбрасывает busy=false и cache=empty до UI callback. Это не универсальный finally и не финансовое подтверждение отсутствия долга. Другой проверенный UI callback e.I отменяет сохранённый GooglePay observer job и очищает cache, но прямо busy не сбрасывает. Его точный lifecycle момент и внешние эффекты cleanup не установлены; global lockout или залипание не доказаны.

В выбранном GooglePay caller Boolean результата запуска не читается, затем сохраняется observer job без явной отмены старого job в этом теле. Это не доказывает успешный UI launch или несколько активных подписок; token/result-flow остаётся открытым. Девять контекстных записей sbx.t не закрывают нижнюю coroutine semantics, поэтому ограничения C84 на выводы о параллельности и cancellation сохраняются.

Независимая статическая приёмка PASS, 1 352 проверки: пять полных тел и один фрагмент, 142 новых записи, 0 reuse; 225 raw context/other-branch записей исключены. Report/review pins и шесть manifest entries совпали. Это узкий вход/cleanup, не полный recovery audit и не прирост глобального процента. DI lifetime, внешняя сериализация и полный cancellation lifecycle открыты.

Самостоятельные предложения для Юлдаша: явно определить empty/fallback policy, перепроверять selection и revision списка перед attempt, согласовать cleanup busy/cache/observer для normal/error/cancel. Шесть групп тестов action/selection, второго null-read, empty со старым cache, fresh-empty reset, отмены и повторного GooglePay launch только предложены. Runtime race, финансовый outcome и продуктовые тесты не установлены; APK/API не запускались.

## BlaBlaCar перенос пассажиров перед редактированием и возврат формы

Уточнён этап UI→Nav: найденный VM caller использует этот mapper перед открытием подробного редактора, при DisplayState, statutory-discount feature и non-STAY. Mapper сохраняет всех пассажиров, оба списка скидок, selected-флаги и saved identity, переиндексирует текущий порядок и переносит CTA без пересчёта. displayEdit и discountsLabel обратно не передаются. Это не доказательство полного roundtrip через selector: при возврате работает ранее разобранный Nav→UI.

Return handler требует result -1 и requestCode, сравниваемый с Resources.getInteger ресурса passenger selection. Числовое значение этого ресурса здесь не декодировано и не приравнивается к payment 200/210. Непустая fallback-failed строка имеет приоритет даже при наличии Nav. Иначе основной путь требует Intent/extras, выполняет cast Parcelable и передаёт только ненулевой Nav; nullable проверка fallback не делает весь путь null-safe. Достижимость malformed result и runtime crash не установлены.

VM считает selected-записи без дедупликации identity: пустой список даёт 0, не автоматический fallback 1. initView записывает новую in-memory searchRequest, а Nav→UI заново вычисляет displayEdit. Постоянное сохранение пассажира и серверный save из этого не следуют. При соответствующих условиях initView вызывает onChangesApplied(true), который выходит до события OnChangesApplied; явный non-STAY submit вызывает false и может отправить событие. Ни возврат редактора, ни событие не подтверждают выполненный сетевой поиск.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: пять новых полных mapper/helper тел, 503 code units; девять прежних тел, из них шесть DEX и три complete REA, имеют нулевой новый credit. Report/QA pins и manifest 27/27 совпали. Original DEX bindings/bytes проверены, но общий decoder не является второй независимой декомпиляцией. Сам selector, storage, result producer, navigator implementation, event delivery, server search/price/eligibility и transitive helpers остаются открытыми; global coverage не пересчитан.

Самостоятельные предложения для Юлдаша: отделить UI transport, локальное изменение формы, постоянное сохранение и поиск; явно определить duplicate/selected count и UI-only поля. Непроведённые тесты: roundtrip с unselected/duplicate/unknown данными, перестановка, state/feature/STAY matrix, result/fallback/null/cast, selected count, init против submit и downstream delivery. APK, Android/JVM и API не запускались.

## Яндекс Про документы перед допуском к выплате

В проверенной sign_offers цепочке пустой список документов переводит локальное состояние в signCompleted без запроса принятия оферты. Для непустого списка UI enabled зависит от every(toggles) и isInputValid, а manager onSignButtonTap повторно проверяет документ и input, но не toggles. Это различие уровней проверки, не доказанный обход согласия: фактическая доступность callback, YXButton tap policy и порядок событий renderer не установлены.

Input predicate проверяет непустой текст и сохранённый valid flag для ключей текущего документа. Инициализация задаёт valid=true, последующие text/validation events меняют поля отдельно; нормализация generic renderer и backend здесь не закрыта. Переход к следующему документу сохраняет input map, а payload проецирует всю map в строки без локальной фильтрации ключей по текущему документу. Общие поля могут быть допустимы серверным контрактом, поэтому утечка или ошибочная передача данных не доказаны.

Запрос передаёт id, rev и data. Нормальное завершение transport Future продвигает локальный индекс либо ведёт к payout; отдельного response-body ACK parser в этих телах нет, но нижняя transport validation не исключается. Ни переход, ни завершение Future не подтверждают юридическую подпись, согласие банка или выплату. Retry возвращается к manager-вызову, а offer_already_signed не превращается в автоматический success в проверенном коде.

State guards не устанавливают post-await generation protection. Полные EH protected ranges не декодированы: landing-pad фильтр нельзя считать доказательством перехвата всех parse errors. Rendering, validation/event order, reentrancy, транспорт, backend/idempotency и юридический эффект остаются открытыми; race и обход согласия не заявляются.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 30 новых полных локальных тел, 9 108 native bytes и 389 immediate branch/call checks; 15 supporting ranges и девять GDT строк имеют нулевой credit. Report/QA pins и manifest 40/40 совпали. Full-local не включает весь transitive framework; shared Capstone и hash-bound Blutter PP/class metadata не являются свежим независимым snapshot decoder. Global coverage не пересчитан.

Самостоятельные предложения для Юлдаша: проверять согласие и актуальность document/revision в точке отправки, связывать текст с validation snapshot, явно ограничить scope payload и различать transport completion, server acceptance и юридический статус. Непроведённые тесты: toggles/retry, порядок text/validation events, несколько документов с общими/разными ключами, normal/error/timeout/already-signed, malformed schema и lifecycle. Реальных подписаний, принятия договоров, API-запросов, выплат и запуска APK не было.

## Go C86 GooglePay result и повторный вход в оплату долга

GooglePayTokenResult.SUCCESS запускает отдельную coroutine, которая заново читает current payment reference, а не подтверждает погашение долга. ERROR вызывает navigation error route, фактический показ которого не установлен. CANCEL возвращает Unit из текущего emit без запуска оплаты и без записи busy/cache; завершение всей collect-подписки этим не доказано. Корреляция SDK-события с attempt/revision остаётся открытой, а повторное current-read само по себе не доказывает гонку.

Для GOOGLE_PAY с non-null id ненулевой длины l.e передаёт управление прежнему payDebts с EmptyList, допускающим cached/fresh fallback C84. Null/empty id выбирает card-id flow; null reference или другой type ведут к диагностическому logger без прямого throw в этом теле. Поведение logger и flow error-handler boundaries не закрыты: log-only handler не доказывает глобального подавления ошибок или cancellation.

В выбранном discriminator 29 callback входная String не используется как payment id. При наличии модели x9u в текущем списке создаётся GOOGLE_PAY reference с b=null, записывается как current и передаётся обратно l.e; без x9u лишь журналирование и Unit. Локальный reentry доказан, но producer suspension/completion, mutation списка и lifetime потока не закрыты. Бесконечный цикл, повторное списание и даже фактический финансовый вызов на этом callback-пути не установлены.

Независимый статический QA PASS, 5 309 проверок; финальная редакция ограничивает CANCEL именно текущим emit. 16 полных тел и один фрагмент, 346 новых записей, включая один payload, 0 reuse; 1 202 raw записи исключены. 46 записей l.e впервые получают semantic credit здесь: в C84 они были context-only excluded. Report/review pins и пять manifest entries совпали. Это не бизнес-тесты и не global coverage; wrapper delivery/replay, token producer, payment-list mutation и backend settlement остаются открытыми.

Самостоятельные предложения для Юлдаша: различать token acquisition, привязку способа и финансовое погашение; связывать result с attempt/revision и управлять reentry/cleanup явно. Семь групп тестов SUCCESS/ERROR/CANCEL, смены current, id gates, card-id с/без модели, producer completion/error, cancellation и последующего debt Failure только предложены. APK/SDK/API не запускались, реальные токены и платежи не получались и не выполнялись.

## Go C87 producer card id и граница проверки привязки

Profile-ветвь принимает ответ только при точном status success и non-null payment_method id ненулевой длины. Иной ответ через проверенный helper бросает IllegalArgumentException, а не штатно возвращает null. Blank/trim этот guard не проверяет; trust_payment_id используется в диагностике, не как условие принятия. Принятый id передаётся в emit, но успех привязки не подтверждает погашение долга или банковский receipt.

Profile request создаёт новый UUID на данном проходе, что само по себе не устанавливает стабильный debt-attempt id или серверную идемпотентность. Domain-ветвь отдельно проверяет tokenization type и соответствие нормализованного контекста выбранному backend, затем передаёт JSON через tokenize и verification к terminal domain/a.c. Значение terminal результата, challenge/URL и mutation payment list здесь не закрыты.

Producer после нормального возврата выбранной ветви передаёт String downstream. Это уточняет происхождение строки C86, но не отменяет установленное там её неиспользование как id нового reference. В выбранных телах нет прямой записи общего payment list/current reference; транзитивные вызовы остаются открытыми. Бесконечный цикл, повторная оплата или финансовое погашение не доказаны. Analytics Bind.Succeeded не заменяет terminal ACK; область wrapper catch/cancellation также не установлена.

Независимая приёмка PASS: 2 674 проверки, 21 полное выбранное тело, 725 новых records, 0 reuse. Report/review pins и пять manifest entries совпали. Сверены original DEX, registers, branches и try tables; это не второй полный декомпилятор, не runtime-тесты и не процент APK. C86 context не пересчитан. Terminal a.c, data/verification helpers, wrappers, DI/lifecycle и серверное исполнение остаются открытыми.

Самостоятельные предложения для Юлдаша: различать token acquired, card bound, payment submitted и debt settled; нормализовать id, связывать result с attempt/revision и назначить владельца обновления reference. Восемь групп тестов status/id, optional trust id, network/decode errors, domain context/JSON, смены selection, несовпадения emitted/current id, cancellation и повтора после неизвестного исхода только предложены. API, APK/JVM/SDK и платежи не запускались, реальные токены не получались.

## BlaBlaCar selector пассажиров и результат validation

В дополнительном namespace com/searchform, отсутствовавшем в прежнем first-party inventory, найден ещё один UI→Nav caller: после validatePassengers Success. Прежний вывод о pre-edit остаётся верен для проверенного SearchForm caller, но не для всех callers APK. Selector передаёт repository список, построенный из формы до suspension; после Success перечитывает текущую форму, меняет только summary и преобразует её в result Nav. В выбранном теле нет version comparison между отправленным списком и текущей формой. Это условие для теста stale-response, не воспроизведённая гонка или доказательство принятой сервером иной скидки.

Activity различает payload, а не только resultCode: ValidatePassengersEvent возвращает Nav с RESULT_OK, ErrorFallbackInitEvent возвращает fallback-error строку тоже с RESULT_OK. Обычная validation Failure показывает feedback без success finish в проверенной ветви. Непустой fallback имеет приоритет у прежнего return consumer; пустое сообщение не заменяется автоматически fallback-текстом во всех звеньях и требует отдельного теста. Exactly-once event delivery не установлена.

Повторный вызов init с non-null Nav заново заменяет локальную форму; сохранность при реальном recreation этим не доказана. Check и option изменения применяются по позиционным индексам, а canAddPassengers сравнивает общее число записей с maxPassengers, не только selected. Изменение discountOptions не заменяет discounts и не подтверждает серверное сохранение. При внутренних edit results два присутствующих payload могут вызвать edited, затем deleted; не-OK пропускает эти callbacks, но не доказывает rollback записей дочернего экрана.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 48 новых полных namespace-тел и 1 563 units, из них 15 primary и 33 supporting; отдельно одно новое core-support тело и 29 units. Два прежних тела и 110 units имеют нулевой credit. Report/QA pins и manifest 66/66 совпали. Namespace inventory перепроверен по original DEX, но discovery не означает полный semantic audit namespace; прежний глобальный denominator и totals не изменены. Общий decoder не является второй независимой декомпиляцией.

Repository validation/eligibility, child saved-passenger edit/delete, mapper dependencies, DI/lifecycle, persistence и реальная доставка результата остаются открытыми. Самостоятельные предложения для Юлдаша: snapshot/revision для validation, явные typed result/fallback, отдельные контракты parent selection и child save/cancel. Тесты обратного порядка ответов, duplicate validate, повторного init, stale indices, total/selected лимита, null/empty errors и одновременных edit/delete payload лишь предложены. Runtime race, rollback и server acceptance не заявляются; APK, Android/JVM и API не запускались.

## Яндекс Про расчёт перед выплатой и его lifecycle

CalculationInfo относится к расчёту экрана, не к переводу денег. Future calculate завершается после постановки debounce, а не получения API-ответа; полный await находится внутри queued задачи. Точное Duration не установлено. Проверенная factory использует встроенный последовательный default handler, но runtime override не исключён: параллельные API внутри одной default очереди не доказаны.

Перед запросом прежний Data сохраняет payload и меняет отдельный boolean; Success создаёт Data с false, HTTP 409 переводится в Conflict, Cancel в None. Другие ошибки сохраняют выбранные method/amount в Error. Retry повторяет расчёт только из Error с этими сохранёнными параметрами, а не новым amount state; Conflict не попадает в эту ветвь. Отдельный preview producer также может установить CalculationInfo либо clear без нового API-расчёта.

Clear лишь ставит задачу эмиссии None и в выбранных телах не отменяет debounce. Dispose отменяет подписки, вызывает отмену запросов обеих API, debounce по ключу calculation_info и закрытие state manager. Это клиентский cleanup, не server rollback. После await hasYaBankAccount нет повторной сверки captured amount/method/balance в выбранном теле; актуальность action consumer и конфигурация очереди не замкнуты, поэтому разрешение выплаты по устаревшим данным или runtime race не доказаны.

Запрос переносит method/amount и сведения о выбранном парке, контрагенте и YaBank; масштаб валюты и округление здесь заново не установлены. Response Map передаётся decoder, полнота его валидации не разобрана. EH protected ranges и все динамические transport receiver не закрыты, поэтому landing-pad нельзя распространять на любую ошибку decoder/API.

Независимая приёмка PASS: 843 проверки, 28 новых полных локальных тел, 5 924 native bytes, 312 immediate branches/calls; пять context bodies имеют нулевой credit. Report/review pins и manifest совпали. Это не полный независимый disassembler, не полный payout audit и не global coverage; UI delivery, action gate, runtime DI и финансовый исход остаются открытыми.

