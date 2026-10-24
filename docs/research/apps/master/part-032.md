Выбранный order-chain fragment сначала записывает ссылку contact snapshot в Preorder.N, затем очищает repository selection. Этот reset не уничтожает уже записанную ссылку; deep copy, транзакция и прохождение всей upstream цепочки не доказаны. При построении draft непустой ExtraPhoneContact.phone имеет приоритет над OrderForAnother; иначе используется последний, либо null/null при отсутствии обоих. Phone и name независимо преобразуются из blank/null в null на входе DTO. Связь сохранения значений через suspend/resume и записи двух полей DTO проверена, но wire serializer, фактическая отправка и получение водителем остаются открытыми.

Локальная recent-map использует phone как ключ. Совпавшие phone и name дают no-op, даже если FormedFrom изменился; иначе выполняется put и удаление через iterator, пока размер больше пяти на нормальном пути. Пустой ключ локально не отфильтрован. Это не доказанное LRU, backend deletion, account isolation или crash-safe persistence. Повторно проверенный caller обновляет recent-map только при новом успешном add в локальный set, не при add=false; исключение callback может прервать последующие шаги, а освобождение monitor не равно rollback.

Самостоятельные предложения для Юлдаша: отдельно хранить draft selection и согласованный snapshot заказа, явно определить приоритет контактов, DTO null semantics и retention/account isolation. Непроведённые тесты: copy перед reset, оба источника и пустой extra phone, suspend/resume, независимые blank phone/name, одинаковое имя при смене FormedFrom, cap/order и add=false. Consent третьего лица, верификация телефона, server/driver ACK и долговечность не доказаны. Реальные номера и контакты не читались и не передавались; API и приложение не запускались.

## BlaBlaCar загрузка способов оплаты и варианты ответа

Ограниченная статическая проверка: одно новое полное business coroutine body, 259 instruction units; 2 прежних тела на 72 units и delay helper имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила исходные DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Новизна ограничена проверенным корпусом, глобальные показатели не изменены.

Ожидание условно: при nonblank fraud-tracking ID вычисляется signed long duration = creationTimestamp − currentTimeMillis + 3000, с машинной long арифметикой, не безграничными целыми. Null/blank ID пропускает ожидание; helper при duration<=0 возвращается без задержки. Это не гарантированная пауза на три секунды, не проверка корректности antifraud и не доказательство фактического времени scheduler.

Request строится из captured PaymentBlockNav. List outcome записывает преобразованный paymentMethodsContext и передаёт в display все элементы в исходном порядке, включая пустой список, без локальной фильтрации/дедупликации. PaymentSuccess outcome вместо этого вызывает текущий success callback, не показывая список и не записывая новый context в этой ветви. Failure оборачивает PaymentException и передаёт error handler; восстановление previous loaded state и retry остаются downstream политикой.

В новом теле нет собственного сравнения request version после ожидания, а callbacks читаются из текущего VM; локальных try/catch нет. Это не доказательство достижимой stale-response race или двойной оплаты. Mapper/interactor, display/error handler, callback delivery, scheduler и UI visibility не замкнуты. Локальный success callback не подтверждает settlement, server eligibility или antifraud outcome.

Самостоятельные предложения для Юлдаша: специфицировать источник времени, смысл List/Success, обработку пустого списка и ownership ответа. Непроведённые тесты: fake clock и границы duration, пустой/повторяющийся список, mapper failure, смена Nav, отсутствующие callbacks, Failure и неизвестный outcome. Реквизиты, fraud ID values и credentials не извлекались; API и приложение не запускались.

## Яндекс Про декодирование правил реквизитов

Ограниченная статическая проверка связывает response с ранее проверенными managers: 11 новых полных локальных тел, 4216 native bytes, 200 direct branch targets; независимая QA PASS с 4435 механическими проверками. Прежние API/factory/validators/enum helper имеют нулевой новый кредит. Новизна ограничена проверенным корпусом; глобальные ledgers не изменены, native/PP проверки не заменяют runtime и полный EH анализ.

Attribute union принимает только type=text или selector; unknown/null/missing приводит к CheckedFromJsonException, не fallback на text. Root требует строковые requisite_type/title/subtitle и список attributes. Внутри именно decoder requisite_type проверяется cast, но не сохраняется и не сравнивается с запрошенным типом; это не доказательство отсутствия такой проверки на других уровнях. Selector требует типизированные поля/items, но локально не проверяет уникальность ID и membership initial selection; последующие lookup/validation остаются отдельными стадиями.

Text keyboard_type допускает text/number/phone/datetime/email; missing/unknown не получает автоматического text fallback. mask_filter при наличии требует строковые key/value, без stringify. Validation_rules missing/null превращается в пустой список, тогда как неверный тип списка или элемента не получает этот fallback. Пустой список отключает дополнительные regex rules, но сохраняет прежний независимый not_empty gate.

Rule decoder не читает discriminator: каждый элемент требует строки value и error_message и трактуется как regex rule. Неизвестный дополнительный rule.type не эквивалентен неизвестному attribute.type. Regex syntax здесь не компилируется, шаблоны используются позднее manager; decoder success не гарантирует validation success. Возможность ошибки шаблона не является воспроизведённым crash или атакой.

Самостоятельные предложения для Юлдаша: отделять повреждённую конфигурацию от допустимых defaults, связывать форму с requested type/version и определять обработку regex/decoder ошибок на внешнем уровне. Непроведённые fixture/mock-тесты: missing/null/wrong type, unknown attribute/keyboard, empty против malformed rules, rule extra type, duplicate/not-member selector ID и decoder exception после transport completion. Outer recovery, generic regex/collections, серверная авторизация и commit остаются открытыми. API, приложение и реальные реквизитные действия не выполнялись.

## Яндекс Про ошибки формы реквизитов и повтор

Ограниченная статическая проверка: 8 новых полных локальных тел, 1164 native bytes и 61 direct branch target; независимая QA PASS с 4222 механическими проверками. Прежние submit/load/modal helpers не получают нового кредита; глобальный coverage не изменён. AOT/PP bindings и native проверки не заменяют runtime и полный framework EH анализ.

Load ModelError имеет конкретный ErrorScreen consumer с retry callback: reloadData вызывает onReload и повторно запускает прежний load task. Это загрузка конфигурации, не отправка реквизитов. Связь thrown decoder/type/regex exceptions с ModelError этим не доказана; returned error и исключение нельзя считать одной ветвью восстановления.

Returned submit ApiResponseError открывает диалог: fallback сообщения применяется при null, не при пустой строке. Retry вызывает pop, затем новый submit без Await результата и без ожидания завершения закрытия. Захвачен interactor, не прежний payload: submit заново читает current state, требует Data и повторяет validation managers перед API. Это новая попытка на текущем состоянии, не byte-for-byte replay и не обход all-valid gate.

Cancel callback только вызывает pop, без submit, dirty или refresh. Это не отмена серверного эффекта и не rollback. Порядок pop→submit не гарантирует завершение navigation; исключение pop может прервать следующий вызов. Сообщение об ошибке само по себе не подтверждает отсутствие server-side эффекта.

Самостоятельные предложения для Юлдаша: разделять retry загрузки и сохранения, явно определить new-attempt/replay и reconciliation неизвестного исхода. Непроведённые mock-тесты: Error→reload→Data, decoder exception, null/empty message, изменённая/невалидная форма и non-Data на retry, cancel, double tap и delayed pop/dispose. Outer recovery, gesture delivery, queue, exactly-once и server commit/rollback остаются открытыми. API, приложение, реальные реквизитные действия и runtime-тесты не выполнялись.

## Go C100 удаление из недавних контактов

Ограниченный final report, не frozen checkpoint и не полный privacy audit. Независимая QA и финальное редакционное закрытие PASS: 13 полных тел и 4 фрагмента, 293 selected records = 284 новых + 9 reused storage; 576 записей исключены. 4076 механических проверок не являются runtime-тестами; глобальный ledger не изменён.

UI-список строится из отдельной ArrayList-копии values, не immutable snapshot. Обратный обход и новая map дедуплицируют по нормализованному phone; при повторном ключе значение заменяется без перестановки позиции ключа. Это не доказанная сортировка по последней поездке, LRU или безусловный выбор новейшего alias. Строка UI сохраняет raw phone и получает новый случайный UUID, не стабильный business ID и не токен удаления.

Remove использует exact input key без собственной нормализации и запроса удаления всех normalized aliases. Под monitor сначала меняется локальная recent-map, затем вызывается storage request даже при отсутствующем ключе; возвращённый Boolean request игнорируется. Освобождение monitor не откатывает map при ошибке и не доказывает durable запись. Выбранный метод не очищает текущего пассажира, Preorder snapshot или данные backend.

Проверенный EDIT callback передаёт raw phone строки в remove, затем дважды перечитывает список для разных UI/presenter обновлений. Эти чтения не являются одним атомарным snapshot; исключение предыдущего этапа может прервать последующие, но runtime race не воспроизведена. Общая DI identity всех экранов, полный renderer и доставка click остаются открытыми.

Самостоятельные предложения для Юлдаша: различать удаление из недавних, очистку selection и удаление персональных данных; согласовать ключи/aliases, стабильный identity и account isolation. Непроведённые тесты: existing/missing key, normalized aliases, повторное построение списка, EDIT/NORMAL, storage failure после mutation и restart. UI refresh не является durable receipt; backend erasure, fsync, consent и account fencing не доказаны. Настоящие контакты не читались и не удалялись; API и приложение не запускались.

## BlaBlaCar уточнение выбора способа оплаты по DEX

Уточнение прежнего unresolved статуса, который сохраняется в истории: повреждённая Java-декомпиляция displayPaymentMethods теперь сверена по DEX. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: semantic promotion одного прежнего тела на 130 instruction units, first-time new body credit 0. Четыре прежних тела на 247 units и helper на 17 units имеют нулевой новый кредит. Глобальные показатели не изменены; общий decoder не является второй независимой декомпиляцией.

Совпавший selected reference в исходном Nav-списке сохраняется, без перезаписи default. При отсутствии совпадения берётся reference первого saved метода; если элемента или его reference нет, используется первый other, иначе null. Остальные saved/other не перебираются в поиске пригодного reference; пустая строка не равна null. Это исправляет потерянную ветвь совпадения и инвертированный guard в декомпиляции, а не устанавливает runtime bug приложения. Совпадение reference не подтверждает payment eligibility.

Повторная сверка error handling ограничивает прежние ожидания: StopLoading вызывает restore перед error callback, причём восстановление меняет состояние только при LoadingState с non-null previousLoadedState. Restart вызывает error callback и новый fetch с refreshed/current Nav; LaunchRideDetails идёт в отдельный callback, не в restore. Безусловное восстановление после любой ошибки не доказано.

Самостоятельные предложения для Юлдаша: явно задать default priority, null/empty semantics и различать restore/restart/navigation. Непроведённые тесты: matched/removed reference, первый saved с null при пригодном втором, пустые списки и отсутствие previousLoadedState. Partition/zipper, callback delivery, lifecycle и серверный outcome остаются открытыми. Реальные платёжные данные и токены не извлекались; API и приложение не запускались.

## Яндекс Про кнопка отправки реквизитов и ограничение жеста

Узкое статическое дополнение: одно новое полное callback тело на 72 native bytes и два диапазона одного partial build суммарно 208 bytes, 14 direct branch targets. Независимая финальная QA PASS; provenance повторно сверена после исправления двух промежуточных hash entries. Полный build и generic button chain не получают нового полного кредита; глобальный coverage не изменён.

Save-кнопка сначала проверяет isButtonDisabled: true выбирает disabled независимо от loading. Иначе isLoading=true выбирает loading, а оба false дают enabled. Поэтому прежний reset disabled=false при loading=true не делает кнопку enabled. Callback submit создаётся во всех трёх вариантах, но его наличие не доказывает доступный tap.

Повторно связанная generic цепочка передаёт state/callback до внутреннего disable. Disabled и loading приводят к ветви, пропускающей создание собственных recognizers/RawGestureDetector. Это ограничение именно проверенного gesture path, не только визуальный стиль. Enabled допускает построение этой ветви, но не гарантирует доставку события. Callback вызывает interactor.submit без повторного UI bool guard и без Await; прежняя проверка current Data и all-valid validation всё равно остаётся отдельной границей до API.

Самостоятельные предложения для Юлдаша: раздельно специфицировать UI gate, повторную бизнесвалидацию и серверную авторизацию; не считать сохранённый callback обходом без проверки его доступности. Непроведённые тесты: матрица disabled/loading, reset во время loading, accessibility/keyboard, жест до смены состояния, быстрые повторные taps и callback после закрытия. Полный build, recognizer lifecycle, очереди, runtime double-submit, идемпотентность и server commit не доказаны. API, приложение и реальные реквизитные действия не выполнялись.

## Go C101 меню поддержки и карта действий

Ограниченный final report, не frozen checkpoint и не полный аудит поддержки. Независимая QA PASS: 18 полных тел и 1 фрагмент, 546 новых selected records, 441 запись исключена. Остальные merged branches не получают семантического зачёта; 4619 механических проверок не являются runtime-тестами. Глобальный ledger не изменён.

Отсутствующий order_support_notification возвращает null до построения карты действий; выбранный collector последовательно пишет null в обе UI-flow, но сохранённый deferred map не очищает. Другой исход, present wrapper с null результатом mapper, создаёт новый deferred для пустой карты и публикует Pair с пустым UI-маркером, а не null Pair. Это статическая асимметрия состояния, не доказанный достижимый stale click или межзаказное действие.

Пункты с null converted action исключаются. Карта использует строковый item ID: duplicate ID заменяет предыдущее действие последующим, тогда как отдельный UI-список этой map-дедупликации не проходит. Lookup ждёт сохранённый deferred и ищет по ID без собственного current-order/revision сравнения до или после Await. Null результат пропускает dispatch; найденное действие передаётся локально presenter-ом, не подтверждая исполнение.

Проверенный error callback передаёт ошибку дальше в logging, без собственного reset/retry. Это не доказательство поведения всех ошибок/cancellation внутренних Flow operators. ConvertAction, полный dispatcher, UI lifecycle, общая DI identity и доставка событий остаются открытыми; ticket, сообщение поддержке, refund или финансовый ACK не установлены.

Самостоятельные предложения для Юлдаша: связывать action map с order/session/revision, согласовать очистку UI и map, явно обрабатывать duplicate IDs и неподдерживаемые actions. Непроведённые тесты: notification absent против present/null mapper, смена версии при delayed lookup, null converted action, duplicate IDs и ошибка deferred. API, приложение, реальные обращения, платежи и передача персональных данных не выполнялись.

## BlaBlaCar HPP возврат и результат confirm

Ограниченная статическая проверка HPP: одно новое полное business coroutine body, 134 instruction units; 4 прежних тела на 243 units и 4 bridge/helper на 13 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила исходные DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Глобальные показатели не изменены, PIX и 3DS не включены в новый охват.

HPP result -1 только запускает confirm coroutine; 0 вызывает restart, неизвестный код сначала логируется, затем также вызывает restart. Parent consumed callback зависит от наличия обработанного события и не ждёт завершения confirm. Ни -1, ни consumed не являются подтверждением оплаты.

Nav читается из текущего VM при выполнении label0 coroutine, а не захватывается как Nav-снимок в момент wrapper launch. Из него извлекаются purchaseToken и nullable fraud ID для confirmRedirect. После ответа Nav повторно не сравнивается; success callback holder читается снова из текущего VM только в ветви Result.Success<Unit>. Failure передаёт PaymentExceptionError общему handler, не вызывая success. Локальных try/catch, state writes и generation guard в новом теле нет; семантика interactor Result conversion и cancellation остаётся открытой.

Самостоятельные предложения для Юлдаша: различать UI result, confirm completion и финансовый outcome, определить owner позднего ответа и повторной доставки HPP result. Непроведённые fake-interactor тесты: отложенное начало label0, смена Nav/callback между этапами, null preconditions, Success/Failure/throw и parent-consumed до confirm completion. Происхождение activity result, callback delivery, runtime context safety и серверная идемпотентность не доказаны. Settlement, booking и двойная оплата не установлены; настоящие токены, identifiers и реквизиты не извлекались, API и приложение не запускались.

## BlaBlaCar HPP граница Result и cancellation

Ограниченное дополнение к HPP confirm: одно новое полное business body на 117 instruction units; 3 прежних тела на 73 units и 2 bridges на 6 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила исходные DEX identities, байты и try table; общий decoder не является второй независимой декомпиляцией. Глобальные показатели не изменены.

В точных protected invoke/resume участках CancellationException перебрасывается тем же объектом, без преобразования в PaymentException. Остальные попавшие туда Throwable сначала становятся Failure, затем передаются PaymentExceptionMapper. Сам mapper и создание итогового mapped Failure находятся вне этих catch ranges: ошибка mapper не перехватывается тем же локальным handler. Это не catch всего метода и не гарантия доставки любой системной cancellation либо отмены серверной операции.

Нормальный immediate/resumed результат block становится Success без вызова mapper. Повторно связанный HPP action после normal repository.confirmRedirect возвращает Unit, не интерпретируя финансовую сущность. Поэтому Success<Unit> означает нормальное завершение клиентского action, а не settlement, booking или server ACK. Конкретная mapper implementation/DI, внешняя обработка исключений и серверный смысл repository response остаются открытыми.

