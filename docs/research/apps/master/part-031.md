Edit callback вызывает modal.pop, внешний hook, затем refreshRequisites; set-default вызывает pop, затем refreshRequisites. Delete вызывает pop, внешний hook, затем только markNeedsRefresh, без публичного refreshRequisites. Эти callbacks не ожидают Future обновления. Вызов pop/hook не означает завершённый dispose; receiver внешнего hook и достижимый порядок queued mark остаются открыты. Потеря dirty или runtime race не доказаны.

Для set-default/delete общий API wrapper превращает нормальный возврат transport Future в ApiResponseSuccess<void>, не читая body/status в success ветви. Это не означает успех любого HTTP status: transport policy может сначала породить exception. Для edit происхождение response ограничено StateManager.submit и не связано здесь с этим wrapper. В default/delete setLoading(false) стоит после нормального Await, не в finally; вечный loading из этого не следует.

Самостоятельные предложения для Юлдаша: различать ответ команды, подтверждение уведомления, invalidation, fresh preview и серверную пригодность реквизита; определить, должна ли invalidation зависеть от кнопки success dialog. Непроведённые тесты: normal/exception transport, response dispatch, отсутствие и повтор onTap, pop/hook errors, delayed queue и восстановление loading. Validation edit, transport/provider binding, scheduling и серверный commit открыты. API, приложение и реальные изменения реквизитов не выполнялись.

## Go C96 доступность ещё одного заказа и снятие busy

Ограниченный final report, не frozen checkpoint и не полный аудит создания заказов. Независимая QA PASS: 15 полных тел и 3 фрагмента, 744 selected records = 568 новых + 176 повторно использованных C01; 65 context records исключены. 3705 механических проверок не являются runtime-тестами; прежний глобальный ledger не пересчитывается.

Клиент разделяет последнее разрешение CanMakeMoreOrders, busy отправителя и вычисленный кеш доступности: a && !busy. UNMODIFIED сохраняет политику, ALLOWED устанавливает true, DISALLOWED false. Reset busy сохраняет прежнюю политику и не равен разрешению нового заказа; обновление status через c(flag) само busy не снимает. При recompute кеш записывается до уведомлений, только если значение изменилось; результат отправки в flow игнорируется и доставка не подтверждена.

Повторно связанный sender C01 ставит busy до send, без собственной policy admission проверки; cancellation перебрасывает без локального finally-reset. Commit проверяет непустой order ID раньше reset: non-null response flag применяется вместе со снятием busy, null заменяется DISALLOWED. Пустой ID бросает до этой очистки. Внешний cleanup и достижимое зависание этим не установлены.

Выбранные error callers снимают busy неодинаково: HTTP ветвь зависит от guard, parallel exception сбрасывает после logging, отдельные IOException/token/unauthorized пути не содержат прямого reset. Recovery callback discriminator 0 вызывает reset, default нет; HTTP429 использует default после уже выполненного reset. Предварительный callback или logging может прервать нормальную последовательность, а регистрация callback не доказывает его выполнение.

Проверенный фрагмент mapper только для create_order_button отдельно читает policy и busy и блокирует эту ветвь при !policy или busy. Это не общий доменный admission, атомарная проверка всех входов или серверная защита от двойного заказа. Общая DI identity, renderer, account/restart reset, внешние owners и полная cancellation cleanup остаются открытыми.

Самостоятельные предложения для Юлдаша: отдельно хранить policy, ownership попытки и derived availability; освобождать busy своей попытки, проверять admission на доменной границе, серверные лимиты и идемпотентность специфицировать отдельно. Непроведённые тесты: policy/busy матрица, status без unlock, null flag и пустой ID, cancellation, error/callback failures, несколько входов создания и недоставленные уведомления. Runtime race, дубли заказов или назначения водителя и финансовый результат не доказаны; API и приложение не запускались.

## Go C97 ранняя подготовка заказа и потребление pending

Ограниченный final report, не frozen checkpoint и не полный consent или order audit. Независимая QA PASS: 7 полных тел и 2 фрагмента, 392 selected records = 274 новых + 118 C01 reuse; 694 записи вне выбранных scopes исключены из семантического зачёта. 4824 механические проверки не являются runtime-тестами; глобальный ledger не изменён.

Start требует включённого experiment gate, непустых zone/tariff/preorder.c, отсутствия RawOrderdraftInfo и payment type не GOOGLE_PAY. Проверка строк использует длину, не trim. Обычный gate=false возвращается без изменения существующего pending. Реальная конфигурация experiment и полный пользовательский consent path не установлены.

Ключ reuse содержит только три указанные строки. Под monitor same key возвращается без проверки active/completed/failed состояния deferred. При другом ключе новый deferred создаётся до запроса отмены старого, затем сохраняется новая пара. Ошибка cancel может прервать запись; порядок инструкций не доказывает параллельные запросы или серверную отмену. Upstream guards значимых полей вне ключа остаются открытыми.

Await строит ключ из переданного zoneName и текущих tariff/preorder.c, заменяя null последних двух строк пустыми. Отсутствующий slot даёт null; mismatch по нормальному пути отменяет job, очищает slot и возвращает null. При совпадении slot очищается и monitor освобождается до await. Второй последовательный await без нового start не получает ту же пару, но это не глобальная exactly-once гарантия или durable consume.

В конкретных await/result try regions CancellationException и ErrorDuringCommitButOrderCreatedException перебрасываются; другие Throwable логируются и дают null при нормальном возврате logger. Monitor/mismatch области освобождают monitor и перебрасывают ошибку, а не используют тот же null fallback. Очищенный slot не восстанавливается этим методом. Повторно связанный sender C01 различает null response и ненулевой response без ID: последний приводит к MissingOrderIdException, не обычному fallback. Большой промежуток confirm caller не прочитан в этом пакете и не подтверждает момент согласия пассажира.

Самостоятельные предложения для Юлдаша: явно разделить разрешённую предварительную подготовку, согласие и создание бизнес-заказа; определить ключ/revision, ownership, consume и account/process reset. Непроведённые тесты: gate=false с pending, same-key active/failed/completed, create-new/cancel-old failures, mismatch/match и повтор await, исключения каждого try region, malformed response, смена аккаунта и поздний ответ. API и приложение не запускались; backend acceptance, cancellation, идемпотентность, общий DI scope и runtime concurrency не доказаны.

## BlaBlaCar снимок legacy CPF формы и применение ответа

Это отдельная legacy V1 ветвь, не расширение прежнего вывода о V2 и не обязательная функция Юлдаша. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: одно новое полное business coroutine body, 185 instruction units; 4 прежних тела на 269 units повторно сверены без нового кредита. Raw DEX identities и байты проверены, общий decoder не является второй независимой декомпиляцией. Новизна ограничена сопоставленным корпусом; namespace и глобальные показатели не изменены.

Caller захватывает исходную Form до обновления CTA в LOADING. Coroutine строит запрос из prefilledValue этого снимка, заменяя null пустой строкой. В данном теле нет нормализации или проверки формата перед запросом; это не отсутствие validation во всём продукте. Отдельный guard против повторного вызова при LOADING в caller не найден, но UI suppression и повторный реальный запрос не установлены.

Success перечитывает текущую Form для CTA SUCCESS, без сравнения с отправленным input/version. FinishFlowWithSuccess расположен вне первого guard Form и возможен при другом текущем state; после положительного guard отдельная null-ветвь второго чтения может завершить метод раньше события. Failure меняет состояние только если оно сейчас Form: InvalidCpfError направляет message в ошибку поля, прочую DomainException в корневую ошибку, обнуляя альтернативный message. Это статическая асимметрия, не воспроизведённая гонка или обход проверки личности.

Прежний typed usecase преобразует категорию BadRequest в InvalidCpfError. Такое клиентское имя не устанавливает конкретную ошибку формата или достоверность личности. Локальное событие успеха не доказывает принятие идентификатора сервером, persistence или успешную проверку профиля. Активность legacy версии для страны/аккаунта, ввод и фильтры UI, renderer, lifecycle и consumer события остаются открытыми.

Самостоятельные предложения для Юлдаша относятся к общему контракту формы, а не к сбору CPF: связывать ответ со снимком/version, определить завершение вне формы, правила повторного submit и типизированные ошибки. Непроведённые тесты: поздний success/failure после редактирования или смены state, два чтения state, отсутствующий message и повтор confirm с fake usecase. Реальные и синтетические идентификаторы не создавались, не считывались и не отправлялись; API и приложение не запускались.

## Яндекс Про валидация формы реквизитов и create update

Ограниченная статическая проверка закрывает ранее открытый edit submit task: 19 новых полных локальных тел, 4352 native bytes, 181 direct branch target. Независимая QA PASS с 4405 механическими проверками; прежние wrapper, generic controller и API helper не получают нового кредита. Новизна ограничена проверенным корпусом, глобальный coverage не изменён. PP/GDT и ASM bindings не заменяют runtime, полный EH или полный дизассемблер.

Submit захватывает ссылку на Data-модель, а строки читает из её managers уже внутри task. Это не immutable snapshot значений. Две карты объединяются через copy и addAll, без deep copy managers. В нормальном обходе validate вызывается для каждого manager; любой false делает итог null, частичная карта не отправляется. Getter вызывается лишь пока общий accumulator true; null/empty значения не добавляются. Пустой набор даёт пустую ненулевую карту, но достижимость и допустимость такой конфигурации не установлены.

При null результате валидации task эмитит isButtonDisabled=true и возвращается без API. Иначе эмитит loading, вызывает submitRequisites, после Await сохраняет response и эмитит loading=false из captured Data. Reset не finally; внешняя очередь, reconcile и cleanup не замкнуты. Это не доказательство stale UI, доступного повторного submit или вечного spinner.

Для проверенного Text manager null/empty отклоняется; trim в этом validator нет, но последующие rules могут отклонить пробелы. Rules используют переданный RegExp и наличие match, без отдельного whole-string length guard: полный формат может задаваться самим шаблоном. Некорректный regex локально не перехватывается. Selector manager проверяет наличие selected object и отдельно ставит обновление ошибки в очередь без Await; наличие объекта не доказывает непустой ID или серверную пригодность. Полный набор runtime manager типов, factories, formatter и происхождение rules открыты.

Ненулевой requisiteId выбирает update, null выбирает create. Serializer формирует id/type/attributes с code/value и включает id даже при null; update также передаёт тот же ID в query. Обе ветви связаны с прежним normal-Future→Success<void> helper. Локальная all-valid проверка и эта классификация ответа не подтверждают server commit, владение счётом или выплату; HTTP verb/provider, transport policy и серверная авторизация остаются открытыми.

Самостоятельные предложения для Юлдаша: валидировать согласованный snapshot, явно определить duplicate codes, пустые наборы и нормализацию, отделить selection от пригодности реквизита и подтверждённое сохранение от local Success. Непроведённые mock-тесты: invalid в каждой позиции, пустые getters, смена значений до task, null/space/malformed regex, queued selector error, create/update ID и exceptions/dispose при Await. Реальные банковские поля не исследовались; API, приложение, изменения реквизитов и выплаты не выполнялись.

## Go C98 контакт другого пассажира и предложение выбора

Ограниченный final report, не frozen checkpoint и не полный аудит заказа для другого человека. Независимая QA и финальное редакционное закрытие PASS: 19 полных тел и 2 фрагмента, 524 новых selected records, 110 исключённых context records. 3071 механическая проверка не является runtime-тестом; глобальный ledger не изменён.

Выбранный контакт преобразуется в snapshot phone/name/FormedFrom. Blank phone обнуляет name перед заменой null name на пустую строку; даже пустой ввод создаёт объект и не равен reset, который публикует null и UNKNOWN. Reset контакта не очищает проверенные геополя и latch. Нормализация строки не доказывает валидность номера, OTP, принадлежность человеку или его согласие; полные contacts permissions и UI не замкнуты.

Route refresh проверяет address!=null отдельно от значения address.v(): если ненулевой address возвращает значение, отличное от прежнего j, обновляются j и location-change latch, в том числе возможен j=null. Null address сохраняет прежнее j. Затем запрашивается user position; ошибка в выбранной await/result области может оставить прежнюю позицию после обновления route/latch, cancellation перебрасывается. Route-read ошибки вне этих catch не объявляются обработанными так же.

Предложение выбора требует включённого локального gate, отсутствия блокирующего флага, подходящего ненулевого тарифа, обеих точек, установленного latch, строгого превышения distance над выражением порога и отсутствия contact snapshot. Равенство порогу не проходит. Единицы, свежесть/точность локации и серверная политика не установлены. Router сбрасывает latch до вызова навигации и возвращает true без ожидания выбора или закрытия: true не подтверждает видимость окна, consent или создание заказа.

Самостоятельные предложения для Юлдаша: разделить заказчика, пассажира, контактный снимок и подтверждённый канал; определить минимизацию данных, account/context reset и смысл latch показа. Непроведённые тесты: пустой snapshot против null, каждый location gate, address.v()==null, route change с failed position refresh, navigation failure после reset и поздний выбор. Snapshot→preorder/API, доставка водителю, все reset producers и lifecycle остаются открытыми. Реальные контакты и геоданные не читались и не передавались; API и приложение не запускались.

## Яндекс Про создание managers и повторный допуск submit

Ограниченная статическая проверка: 23 новых полных локальных тела, 4308 native bytes, 207 direct branch targets; независимая QA PASS с 4558 механическими проверками. Новизна ограничена проверенным корпусом; прежние constructor/controller/submit и контекст API не получают нового кредита. Глобальный coverage не изменён; native/PP bindings не заменяют полный runtime и EH анализ.

Конкретная successful-load ветвь создаёт отдельные карты TextAttributeManager и SelectorAttributeManager по subtype атрибутов; неизвестный subtype бросает StateError. Начальные loading=false и isButtonDisabled=true не являются результатом all-valid проверки. Локального duplicate-code rejection нет; межтиповой duplicate при последующем объединении text→selector отдаёт приоритет selector. Достижимость и допустимость таких дубликатов, decoder и авторитетность rules остаются открытыми.

Text focus=false вызывает validation, focus=true очищает отображаемую ошибку; затем focus читается повторно и передаётся странице. Только true запускает helper, который вопреки имени _setButtonDisabled записывает isButtonDisabled=false, не валидируя всю форму. Task использует captured Data и сохраняет loading/maps/payload. Это допуск повторной попытки, не доказательство валидности, фактической кликабельности или обхода повторной submit-валидации.

Selector разделяет применённый item и draft: открытие восстанавливает draft из selected, изменение draft само не применяет выбор и не сбрасывает page disabled. Apply ищет item по текущему draft, затем вызывает emission выбранного состояния, page reset и pop-запрос именно в таком порядке; reset Future не ожидается. Неуспешный firstWhere/typecheck может прервать последующие вызовы. Порядок вызовов не подтверждает завершение очереди, закрытие modal или runtime race.

Самостоятельные предложения для Юлдаша: отделить возможность повторить submit от валидности, явно обрабатывать конфигурационные ошибки и duplicate codes, сохранять границу draft/apply и защищать queued updates от устаревшего capture. Непроведённые mock-тесты: начальные flags, gain/lose focus, reload между capture/task, disabled=false вместе с loading=true, null/unknown selector ID, cancel/reopen/apply и ошибки поиска. Actual gesture gates, очередь/dispose, firstWhere, правила/decoder и серверный commit не замкнуты. Реальные реквизиты не извлекались; API, приложение и финансовые действия не выполнялись.

## BlaBlaCar закрытие legacy CPF и контракт результата

Компактное продолжение legacy V1 submit: независимая QA PASS_WITH_SCOPE_LIMITATIONS, 2 новых полных тела на 59 instruction units, из них business handler 35 и observer binding 24. Шесть прежних тел на 149 units, helpers и выбранная R8 dispatch ветвь имеют нулевой новый кредит. Остальные merged arms не объявлены изученными; общий decoder не является второй независимой декомпиляцией. Глобальные показатели не изменены.

Статическая связь observer с обработчиком замкнута. FinishFlow только вызывает finish, не устанавливая результат в этой ветви: безусловный RESULT_CANCELED из этого не следует. FinishFlowWithSuccess вызывает setResult(-1), затем finish, без Intent/payload. Обработчик не сопоставляет request ID или исходную Form. Это локальный result contract, не доказательство доставки события, верификации личности, server acceptance или долговременного сохранения.

Повторно проверенные onCpfChange/withCpfValue передают строку под guard Form без собственной нормализации; другие UI filters этим не исключены. Launch wrappers передают entrypoint и resource identifier, но конечный числовой request code и конкретный возвращающий caller здесь не замкнуты. Активность legacy flow для страны/аккаунта и смысл -1 для caller остаются открытыми.

Самостоятельные предложения для Юлдаша: различать закрытие, успешную отправку и подтверждение личности, задавать явный payload/result contract и единую границу нормализации. Непроведённые тесты: обе event-ветви, ранее установленный result, lifecycle/повтор события, resource resolution и caller interpretation с fake state. Идентификаторы и credentials не читались, не создавались и не передавались; API и приложение не запускались.

## Go C99 контактный снимок в draft и недавние контакты

Ограниченный final report, не frozen checkpoint и не полный аудит builder: независимая QA PASS, 3 полных тела и 3 фрагмента, 526 selected records = 446 новых + 80 reused; 970 записей исключены. Большой builder остаётся фрагментом; проверка сохранения регистров в исключённой середине не добавляет семантический кредит. 7108 механических проверок не являются runtime-тестами, глобальный ledger не изменён.

