Ограниченная статическая проверка: одно новое полное coroutine body на 128 instruction units; 4 прежних тела на 539 units и helpers имеют нулевой повторный кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: исходные DEX identities/bytes и четыре bundled assets проверены; shared decoder не является второй независимой декомпиляцией. Namespace и глобальный знаменатель не меняются.

Два захваченных feature flags выбирают grouped/split конфигурацию со STAY или без него. Loader читает выбранный локальный JSON asset в Map, не делает locale lookup и не запрашивает remote flags. Типизированный IOException handler логирует и повторно выбрасывает ошибку в проверенных try regions; это не fallback в пустую Map и не гарантия логирования всех parse ошибок. Cleanup/rethrow отделён от бизнес-fallback; прежний wrapper отвергает null результат.

Каждый из четырёх файлов содержит 42 locale keys, не 42 страны или доступных рынка. После удаления STAY grouped-пары совпадают, но split-пары отличаются ещё и is_new у 17 locale keys. В ru_RU все четыре файла оставляют один grouped transport пункт. Поэтому отключение STAY нельзя моделировать только удалением одного элемента универсальной конфигурации. Это bundled metadata данной версии, не доказанный UI badge, runtime availability или продуктовый дефект.

Прежний repository захватывает точный locale до suspension; отсутствующий key даёт Failure без собственного language fallback. Отдельный верхний interactor при Failure возвращает carpool-only вариант. На Success он отдельно фильтрует BLABLAHOME и STAY: post-filter boolean не тождествен captured stay flag loader. Эти prior связи не получают нового audit credit.

Самостоятельные предложения для Юлдаша: явно разделить выбор конфигурации, locale fallback, post-filters и серверную eligibility. Непроведённые тесты: матрица двух flags, точный/неизвестный locale, is_new различия, IOException против malformed JSON/null/cancellation, независимые STAY и BLABLAHOME фильтры. Remote rollout, runtime DI, Gson adapters/schema/duplicate-key policy, доставка логов, UI persistence и provider navigation открыты. APK, API и внешние providers не запускались.

# Яндекс Про V1 Init пополнения, ошибки и повтор

Ограниченная статическая проверка с независимой QA PASS: 5 новых полных тел на 624 байта и дочитанные complement-диапазоны трёх прежних тел на 2984 байта. Прежние 344 байта исключены из повторного кредита; суммарный новый byte delta 3608, не полный размер дочитанных тел. Проверен 121 прямой переход в новых диапазонах. Это не полный exception/runtime аудит, общий ledger не пересчитывается.

V1 manager захватывает double amount и ссылку на коллекцию полей, затем требует текущий тип Data. Из начального Data создаётся loading=true; id/value собираются без локального trim/regex. Repository превращает double в amount_data через Double.toString и сериализует input_fields_data. Это не денежное округление и не прежний V2 контракт server action.amount; источник и validation видимого ввода здесь не замкнуты. Захваченная коллекция не является глубоким immutable snapshot.

API отдельно связывает query values, request data и resource cancel token. Нормальный ответ проходит requireData и union decoder. В проверенных error landings распознанный Dio cancel даёт null; прочие ошибки логируются/rethrow либо переводятся repository в localized error из response Map. Message допускает null, но не произвольный тип. Полные protected EH regions не восстановлены, поэтому эти ветви не распространяются на каждую serializer/decoder ошибку. Null cancellation не доказывает rollback или успех.

Manager обрабатывает Exception, не любое Object, и передаёт error dialog callback с первоначальными amount и collection reference. Retry напрямую снова вызывает внутренний _onTopup без await, не внешний onTopupTap и не новое чтение суммы из UI. Data-type gate повторяется, но отдельной проверки Data.loading и сравнения captured amount с актуальным вводом в выбранном теле нет. Фактическая доступность retry, upstream guards и серверная идемпотентность остаются открыты; обход UI или повторное списание не доказаны.

При нормальном nullable результате или обработанном Exception cleanup под lifecycle guard восстанавливает семь начальных payload fields с loading=false, без перечитывания текущего state и generation comparison. Отдельная landing также допускает restore перед rethrow, но это не доказанный finally для всех ошибок telemetry/dialog/emit. Lifecycle guard не равен freshness guard. Success telemetry предшествует navigation, а screen, loading=false и нормальный возврат не подтверждают зачисление.

Самостоятельные требования Юлдаша: разнести V1/V2 amount contracts, определить денежный формат, политику captured retry и сверку авторитетного статуса. Непроведённые тесты: изменения суммы/коллекции перед retry, cancel/timeout/response-type matrix, null/invalid localized message, dispose и изменение state во время await, сбои telemetry/dialog/emit. Runtime concurrency, actual dialog gesture, HTTP provider/verb, полные EH tables, commit/rollback и settlement не установлены. Реальные token/provider values, API, APK и денежные операции не использовались.

# Яндекс Go C115 Выбранная цена Auction в Preorder

Ограниченный final report, не frozen checkpoint: один фрагмент, 73 выбранных records = 50 новых + 23 повторных; 1250 raw records исключены. Независимая QA PASS. Прежний request consumer имеет нулевой новый кредит; весь producer, network builder и его непрочитанный tail не объявлены покрытыми.

Уточняется открытая граница C114: producer сначала очищает строку цены Preorder.c0, затем отдельно читает tariff snapshot и локальную цену. Запись происходит только при наличии snapshot, разрешающем флаге и цене !=0. Это не проверка положительности, finite числа, bounds или равенства AuctionInfo. Analytics предшествует String.valueOf(double) и записи; исключение между clear и write не означает rollback. Собственного UI loading/enabled guard в выбранном фрагменте нет, но достижимость обхода или гонки не установлена.

Прежний consumer при non-null c0 создаёт FullAuctionParam(c0,d0), при null оставляет параметр отсутствующим; empty string этот null gate не исключает. Новый фрагмент d0 не меняет. Эта связка закрывает локальный переход цены в клиентскую модель, не сериализацию/отправку, принятую сервером quote, назначение водителя или финансовую сумму. Отдельные чтения тарифа и Preorder не доказывают согласованный snapshot.

Самостоятельные требования Юлдаша: перед commit проверять bounds, actor/tariff/revision и денежный формат; определить clear-before-validation и exception policy. Непроведённые тесты: null snapshot, false flag, zero/negative/nonfinite цена, analytics failure, смена snapshot и null/empty c0 consumer. Полная freshness/DI identity, wire delivery, server auction и runtime races остаются открыты. API, APK, реальные цены и платежи не использовались.

# BlaBlaCar Техническая граница autocomplete utility

Независимая QA PASS_WITH_SCOPE_LIMITATIONS подтверждает 2 новых utility-тела на 131 instruction units, но бизнес-кредит равен нулю. Ограниченный direct-invoke scan не нашёл внешнего caller; это не доказательство dead code и не закрытая пользовательская цепочка поиска. Reflection, registration и runtime остаются открыты; исходные DEX bytes проверены, shared decoder не является второй независимой декомпиляцией. Глобальный знаменатель не меняется.

Watcher сравнивает текст после trim с текущим значением: равенство не запускает новую работу. Debounce=false вызывает listener сразу; true запускает отдельную coroutine с captured text и номинальным delay 300. После ожидания проверяется равенство captured/current text и только исключение lifecycle DESTROYED, затем listener получает заново прочитанное current значение. Это equality guard, не generation token; локальной отмены предыдущего job здесь нет. Модель A→B→A не доказывает фактический duplicate callback, request или runtime bug.

Самостоятельные предложения Юлдашу и непроведённые тесты: сначала установить реальный consumer, затем проверить одинаковый текст, A→B→A, lifecycle и внешнюю cancellation. Этот utility не подтверждает поиск, отмену сетевого запроса или защиту от поздних результатов; приложение и API не запускались.

# Яндекс Go C116 Допустимость даты в DuePicker

Ограниченный final report, не frozen checkpoint: 4 полных тела и 3 фрагмента, 283 новых выбранных records; 434 raw records исключены. Независимая QA PASS. Прежние C42/C43, formatting, полный renderer и кнопка подтверждения не получают повторного кредита; глобальное покрытие не пересчитывается.

Локальный predicate принимает Calendar, если его instant находится хотя бы в одном интервале, включая обе границы. Пустой список даёт false; сортировки и объединения нет. Это не проверка wall clock, minute step, timezone/DST, revision или capacity. Calendar читается дважды, а модель сохраняет ссылку без defensive copy; runtime конкурентное изменение не установлено.

При true формируется VALID_DATE с предложенным Calendar независимо от blank/nonblank description, хотя списки описаний различаются. При false и пустых интервалах UNAVAILABLE сохраняет прежний Calendar; при false и непустых интервалах INVALID_DATE сохраняет предложенный. Это не автоматический clamp к ближайшему слоту и не резервирование поездки.

Для стартового m4n Calendar presenter использует getAndSet(true): только первая подходящая обработка экземпляра предпочитает стартовую дату, последующие используют текущую non-null дату. Флаг меняется до validation, исключение его локально не откатывает. Wiring отменяет прежний job без join; нижние flow ordering/error policy открыты. На normal path новый state записывается в J до view.render(old,new), поэтому вызов render не является receipt успешного отображения.

Самостоятельные требования Юлдаша: разделить instant validation, availability revision, reservation и order confirmation; явно определить bounds, DST и сохранение invalid input. Непроведённые тесты: границы ±1 ms, empty/overlap intervals, три status/Calendar ветви, однократная стартовая дата, исключение после getAndSet, late availability и render failure. Actual picker events, confirm/date consumer, server acceptance и доступность водителя остаются открыты. APK, API и реальные пользовательские даты не использовались.

# BlaBlaCar Нормальный возврат loginAndSaveSession

Ограниченная статическая проверка: одно новое business coroutine body на 74 instruction units; 4 прежних тела на 529 units и helpers имеют нулевой повторный кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: исходные DEX identities/bytes и try ranges проверены, shared decoder не является второй независимой декомпиляцией. Namespace и глобальное покрытие не расширяются.

Установлена конкретная prior-цепочка LoginWithEmailViewModel → attemptLogin → wrapper → новое тело. Локальная validation пропускает вызов только при пустом списке ошибок. Coroutine передаёт захваченные аргументы repository и при нормальном завершении создаёт Success(Unit), не анализируя отдельный возвращённый status. Название loginAndSaveSession и этот Success сами не доказывают сохранение сессии, серверную аутентификацию или владение аккаунтом.

Catch-all охватывает только проверенные resume и invocation/success диапазоны. Initial resume check и операции errorMapper/mapLoginError находятся вне локального catch; ошибка mapper может выйти наружу. Отдельного CancellationException rethrow перед mapping здесь нет, но это не доказательство подавления cancellation во внешнем framework или гарантированной UI Failure. Прежний VM вызывает handleFailure перед reset либо success hook; reset после исключения hook не гарантирован данным normal path.

Самостоятельные требования Юлдаша: определить стадию готовности сессии при normal return и отдельно проверить validation, invocation/resume/mapper exceptions и cancellation. Непроведённые тесты: failure→reset ordering, captured attempt correlation и account-switch generation. Repository DI, запись/атомарность сессии, downstream hooks и runtime lifecycle открыты. Никакие credentials, идентификаторы или tokens не извлекались и не создавались; вход, сброс пароля, API и приложение не запускались.

# BlaBlaCar Password repository и порядок обновления сессии

Ограниченная статическая проверка: одно новое полное business body на 64 instruction units; 4 прежних тела на 322 units и helpers имеют нулевой повторный кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: исходные DEX identities/bytes проверены, shared decoder не является второй независимой декомпиляцией. Namespace и глобальный знаменатель не меняются.

Concrete repository coroutine строит request из captured login/password и связывает captured attemptId с выбранным endpoint callback. После нормального завершения execute helper возвращает Unit; собственного try/catch, session write или повторного чтения формы здесь нет. Это уточняет implementation за предыдущей границей, но не доказывает выбор данного DI instance в runtime, уникальность attemptId или успешную аутентификацию.

Связанный прежний response handler при non-null ответе выполняет sessionMapper → updateSession → legacy mapper → Unit. При null пропускает update и также возвращает Unit. Допустимость такого null от transport producer не установлена, поэтому это не доказанный обход входа. Update предшествует последнему mapper; его исключение не означает автоматический rollback, но и фактическое частичное сохранение аккаунта не доказано. Синхронный вызов updateSession не является durable receipt.

Самостоятельные требования Юлдаша: определить nullable/normal-return контракт и порядок persistence/error recovery, отдельно проверять request correlation и generation. Непроведённые тесты: исключения mapper/update/legacy mapper, null producer policy, cancellation и account switch. Provider internals, атомарность, endpoint defaults, response/cancellation callbacks и runtime DI остаются открыты. Credentials, tokens и реальные идентификаторы не извлекались; вход, сброс пароля, API и приложение не запускались.

# Яндекс Go C117 Calendar из выбора дня и времени

Ограниченный final report с независимой bounded QA PASS, не frozen checkpoint: 13 полных тел, 558 выбранных records, из них 539 новых. Остальные 19 консервативно имеют нулевой новый кредит как прежний context-only C116, а не ранее зачтённое selected semantic coverage. Ещё 6 raw records исключены; глобальный ledger не пересчитывается.

Ручной выбор ищет ближайшие числовые поля часа/минуты, сохраняя первый элемент при равенстве расстояния. ScrollToDate сначала требует допустимый instant и найденные день/час, затем выбирает ближайший instant только среди минут этого часа. Это не глобальный поиск ближайшего слота. Manual setters меняют DAY_OF_YEAR/час/минуту в зависимости от действия; отсутствие явного YEAR setter само не доказывает календарный runtime дефект, поскольку generator и Calendar normalization открыты.

SetValues с пустым списком дней возвращается до записи и сохраняет прежние days/selectedDate. При непустом списке совпадение по дню/году/часу/минуте приводит к копированию предложенного Calendar, включая его seconds/millis/instant, а не объекта найденной минуты; без совпадения берётся первый день. Этот membership check не заменяет интервальный predicate C116. Инварианты непустых дочерних списков ещё не установлены.

Fallback setOldPickerVal не вызывает widget setter: он делает вложенный onValueChange(old,old,true), поэтому внешнее отклонение не исключает вложенный listener/haptic эффект. Listener вызывается при idle всех трёх picker и получает отдельный Calendar с тем же instant/timezone, не clone всех настроек. Haptic на normal path отдельно зависит от флага. Реальная доставка, full init/framework и связь listener→confirm остаются открыты.

Самостоятельные требования Юлдаша: согласовать instant/zone и nearest/tie policy, обработать empty updates и отделить восстановление widget от уведомления. Непроведённые тесты: невалидные индексы и вложенный fallback, пустые дочерние списки, границы года/DST, одинаковые wall fields при разных instant, seconds/millis и idle flags. Server booking, резерв водителя, freshness и воспроизводимый календарный баг не доказаны. API, APK и реальные пользовательские даты не использовались.

# Яндекс Про V1 Первичная проверка submit и кнопка retry

Ограниченная статическая проверка: 12 новых полных тел на 2116 байт и 99 прямых переходов. Независимая семантическая QA PASS; финальная редакционная правка о прямом вызове firstWhereOrNull принята reviewer. Прежние inner init/retry, generic button/close и shared helpers имеют нулевой повторный кредит; глобальное покрытие не пересчитывается.

Первичный onTopupTap последовательно читает список текущих holder state objects и controller text. Это не глубокий immutable snapshot. После parser проверяются null и ровно zero, но grammar parser и достижимость отрицательных/nonfinite значений не установлены. Для сохранённого validation flag=false handler ищет первую модель по id: только required=true блокирует submit. Optional/не найденная модель не блокируют этот локальный gate; completeness holder и нижняя field validation открыты. Submit не запускает regex заново и не доказывает серверную eligibility.

Конкретные legacy/redesigned UI callbacks передают id/bool/value в holder, заменяющий map item. После успешного gate вызывается внутренний _onTopup с прочитанными amount/list без await. Отсутствие проверки Data.loading именно здесь не доказывает обход UI. Primary использует Data cast, тогда как ранее проверенный inner retry имеет отдельный Data-type guard.

Привязка init-error modal замкнута: close вызывает только closePage; retry сначала closePage, затем captured callback без await завершения route. Callback повторяет исходные amount/list через _onTopup, не перечитывает holder и не проходит первичную validation заново. Это различие контрактов primary/retry, не доказательство ошибки или повторного списания. Null message получает default, non-null empty сохраняется; shared content body не объединяет V1 retry с V2 preview reload.

Самостоятельные требования Юлдаша: определить, повторяет retry исходную попытку или текущий ввод, и разнести UI validation, серверное принятие и финансовый статус. Непроведённые тесты: required/flag/id matrix, missing holder items, parser/formatter, изменения полей между попытками, null/empty message, pop/callback exceptions и repeated tap. Actual modal lifecycle, parser grammar, completeness, runtime concurrency и server idempotency/settlement остаются открыты. API, APK и реальные суммы/реквизиты не использовались.

# Яндекс Про V1 Начальное заполнение holder и маска суммы

Ограниченная статическая проверка с независимой QA PASS: 6 новых полных тел на 3316 байт, 138 прямых переходов. Generic parser/formatter, прежний submit и shared decoders не получают повторного кредита. Хэши комплекта сверены; глобальный ledger не пересчитывается.

После preview Await нормальный non-null результат при разрешающем lifecycle guard последовательно заполняет holder, обновляет amount controller и выпускает Data с той же коллекцией input_fields. Null response не выполняет этот reset/Data путь. Начальное заполнение очищает map и создаёт state для каждого id: null initial value становится пустой строкой, validation flag всегда true, без чтения required/regex. Duplicate id оставляет последний state, тогда как прежний submit ищет первую модель. Это уточняет initial completeness по различным id, не доказывает корректность начальных значений или достижимость bypass до UI callbacks.

