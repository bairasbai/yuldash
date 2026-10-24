Уточнение C121: synthetic response с null payload и пустыми errors идёт в UNKNOWN validation failure, не success. При непустых errors обрабатывается только первый, с приоритетом над success payload. TRANSFER_NOT_FOUND/TRIP_NOT_FOUND сохраняют текст в отдельной failure-модели; BAD_REQUEST/EXPLICIT_GREETING формируют REQUEST, NOT_RETRYABLE_ERROR формирует RETURN, UNKNOWN формирует REQUEST и дополнительный analytics call. REQUEST/RETURN здесь данные модели, не выполненный запрос или navigation.

При пустых errors и подходящем train payload success helper записывает captured string и payload string без дополнительной проверки строки. Mask123 меняет только result/button поля, сохраняя дату/время и остальные поля текущего draft. Captured string не сравнивается с текущим ключом, датой, тарифом или generation в выбранных consumers. Это статическая граница correlation, не доказанная доставка устаревшего ответа.

Обновление m(null,new) пропускает сравнение expected с actual: это не CAS по ранее прочитанному draft. Equality actual/new может дать no-op, иначе выполняется set; returned boolean вызывающие тела игнорируют. Observer delivery и атомарность всей read-copy-write цепочки не установлены. Analytics до update может прервать normal path при исключении, собственного catch здесь нет.

Самостоятельные требования Юлдаша: различать synthetic failure и server errors, определить multi-error precedence и проверять revision до применения ответа. Непроведённые тесты: null/empty response, success вместе с errors, шесть кодов, current draft против captured key, equality update и analytics failure. Actual button actions, renderer, wire/endpoint, global ownership и runtime cancellation открыты. Локальная success-модель не подтверждает билет, бронирование, назначение машины или финансовый ACK; APK, API и реальные поездки не использовались.

## BlaBlaCar готовность функций при запуске

В проверенной цепочке notification data результат Success с непустыми данными сначала отмечает готовность, затем запускает фоновое обновление без ожидания. Success с null ожидает refresh-and-mark; Failure сначала логируется, затем ожидает тот же путь. Выброшенное исключение не равнозначно Result.Failure. Фоновая coroutine обновляет данные, но повторно готовность не отмечает.

В прежнем проверенном helper обычный Result.Failure может быть залогирован и завершиться Unit. После такого нормального завершения refresh-and-mark тоже отмечает готовность. Для feature flags готовыми считаются оба состояния Loaded и InError. Finish требует одновременно два флага готовности; отсутствие одноразового ограничителя в выбранном теле не доказывает повторную навигацию в реальном запуске.

Готовность здесь не означает свежесть notification data, успешное удалённое обновление или применение rollout. Жизненный цикл подписок, отмена, владение scope и семантика repository cache остаются открытыми. Устаревшие данные, гонка или повторный Finish в работающем приложении не воспроизводились.

Независимая проверка: PASS_WITH_SCOPE_LIMITATIONS. Новые 2 полных тела / 207 DEX units; прежние 8 тел / 286 units и 8 helpers / 37 units имеют нулевой повторный credit. Исходные DEX identities и байты проверены, но общий decoder не заменяет вторую независимую декомпиляцию; novelty ограничена предоставленными источниками. Namespace и глобальный знаменатель не расширены. Отчёт SHA256: d438dc2156d4c023a93a7b4e11500983301c1c89759e7437992ddc04b2573307.

Для Юлдаша отдельно предлагается определить, допускается ли запуск с готовностью после ошибки и нужен ли однократный Finish. Непроведённые тесты: матрица non-null/null/Failure/throw, исключение логирования, порядок mark и refresh, таблица двух флагов и повторные события полного жизненного цикла. Это собственные требования и тестовые предложения, не свойства исследованного приложения.

## Go C123 действия кнопки завершения transfer

В ограниченном TRAIN пути mapper сохраняет action модели, а callback захватывает его независимо от подписи кнопки. REQUEST при выполнении читает текущее состояние: для моделей tbm/wbm повторяет check updater из C121, для ubm переходит к отдельному request пути, для остальных выбранных состояний завершает Unit. Поэтому REQUEST из ошибки C122 не равен отправке нового transfer request.

В ubm сначала отменяется прежняя job, затем проверяются дата и две непустые строки TRAIN; отдельного отсеивания пробелов здесь нет. При допустимых входах обновляется локальная модель и вызывается другой API-метод с форматированными датой и временем. Его transport и callback результата остаются открытыми. RETURN выполняет analytics, ставит локальный флаг и вызывает close; исключение analytics может прервать последовательность. Это не серверная отмена или подтверждение бронирования. Progress и debounce binding не доказывают блокировку повторного нажатия.

Независимая проверка PASS: 8 полных тел и 2 фрагмента, 281 новый выбранный record; 557 records исключены. AVIA, полный renderer и результат submit не покрыты. Это bounded final report, не frozen checkpoint и не обновление глобального ledger. Отчёт SHA256: 62d523b2b0c4e63ce5972f94752f99bc11cb8dcca88cd965531fdc85f80a1c8e.

Для Юлдаша отдельно предлагается разделить RetryCheck, SubmitTransfer и Close и связать action с revision состояния. Непроведённые тесты: смена состояния после захвата action, повторный click, исключение analytics, отсутствующие входы и suspended request. Runtime гонка, назначение машины и финансовый результат не установлены.

## Яндекс Про V1 обновление options через API

Null из проверенного updateOptions API не означает только отмену. Для распознанного диапазона exception-классов cancel возвращает null; другая ошибка логируется и при нормальном возврате logger тоже возвращает null. Нераспознанный класс перекидывается дальше. Это отличается от прежнего init, где распознанная не-cancel ошибка после логирования перекидывается. Полные protected-PC/EH regions не восстановлены: нельзя относить сюда любое исключение decoder или serializer.

Запрос передаёт amount без дополнительного trim или округления в выбранном serializer, отдельно передаёт provider identity и cancelToken. Нормальный DTO требует payment_info как List и button как Map; элементы требуют строковые id/name/value, description допускает null. Button использует прежние обязательные name и available. Пустые строки и уникальность id этими телами не проверяются; это не утверждение об отсутствии иных проверок.

Прежний worker при null сохраняет options и снимает loading. Поэтому нормальный logged error может оставить прежний button.available, но это не доказательство доступного stale tap, обхода UI или принятия суммы сервером. DTO обновляет представление, не подтверждает зачисление. Runtime DI, transport implementation, доставка stream-событий, очередь и cancellation correlation остаются открытыми; PP/GDT используются как привязанные к хэшам аннотации и таблицы.

Независимая проверка PASS: 4 новых полных dependency-limited тела / 1652 native bytes / 68 прямых переходов; 4891 механическая проверка не равна числу сценариев. Прежние worker, serializer, manager и UI повторного кредита не дают. Отчёт SHA256: 7123092364af60fa7e5425f9b5789c8e8a87c7ab8d22666dcb01d5578c5677d1.

Для Юлдаша отдельно предлагается различать Success, Cancelled, Error и Unknown расчёта и привязывать options к provider, amount и generation. Непроведённые тесты: прежний available=true после ошибки нового расчёта, одинаковая сумма после неуспеха, logger throw и malformed DTO. Реальные выплаты и runtime проверки не выполнялись.

## BlaBlaCar граница legacy login без подтверждённого caller

В одном новом теле legacyLogin захваченный пароль передаётся методу с именем encryptPassword, затем формируется LOGIN_PASSWORD request и вызывается token с захваченным attemptId. Нормальное завершение делегата сводится к Unit; локального catch нет. Имя encryptPassword не устанавливает алгоритм или криптостойкость, а отличие от modern call-site не доказывает передачу modern пароля открытым текстом.

Ограниченный direct-invoke scan не нашёл caller legacyLogin. Это не доказательство dead code: reflection и динамический вызов остаются открытыми. Поэтому 1 новое прочитанное тело / 106 DEX units не засчитывается как доказанный достижимый бизнес-сценарий; reachable business credit равен нулю. Прежние 2 тела / 52 units и 6 helpers / 88 units повторного кредита не дают. Independent QA: PASS_WITH_SCOPE_LIMITATIONS, исходные DEX identities и байты проверены, общий decoder не заменяет независимую вторую декомпиляцию. Отчёт SHA256: daa2c0a80be02c68b7cf75c0cea359425bc937e4ba6b4dee036b2abcdd0033e0.

Для Юлдаша отдельно предлагается сначала доказать caller и смысл token-параметров, затем проверять mapping и immediate/suspended/throw исходы на mocks. Эти тесты не выполнены. Серверный вход, запись session, актуальный UI путь и безопасность аккаунта не подтверждены; реальные credentials и значения tokens не извлекались.

## Go C124 локальное сохранение transfer draft

Уточнение C123: REQUEST на ubm вызывает draft API, а не финальное создание поездки. Exception в проверенных try regions преобразуется в ответ с null payload и пустыми errors, который прежняя policy C122 считает UNKNOWN failure. Начальная проверка coroutine вне этих regions; доставка callback после cancellation не гарантирована.

TRAIN consumer сначала применяет error policy. Для подходящего payload затем нужны непустой identifier и два успешно разобранных Calendar. Пробелы отдельно не отсекаются, строгая грамматика дат, их хронологический порядок и соответствие captured input или revision этим gate не устанавливаются.

Нормальный local commit последовательно ставит flow-флаг, обновляет первый snapshot условным циклом k(expectedOld,new), записывает Transfer в preorder.U, сохраняет преобразованное captured состояние во втором snapshot отдельным условным циклом и только затем вызывает close callback. Конструкторы сохраняют ссылки, не глубокие неизменяемые копии. Это не одна транзакция с preorder, не доказательство durability и не сравнение request generation.

При invalid gate выполняются logging и преобразование текущего состояния, без записи preorder.U и без success callback. В success используется captured состояние. Callback вычисляет текущий TRAIN flag для analytics, затем ставит локальный флаг и вызывает close; TRAIN flag здесь не gate, запрещающий закрытие. Исключение после предшествующих записей не сопровождается локальным rollback в выбранном commit body. Фактическое закрытие UI, booking acceptance, назначение машины и финансовый ACK не подтверждены.

Независимая статическая QA и финальное редакционное уточнение: PASS. 14 полных тел и 2 фрагмента, 319 новых выбранных records, включая 2 payload; 458 records исключены. Это bounded final report, не frozen checkpoint и не перерасчёт глобального ledger. AVIA, downstream использование preorder.U, reset lifecycle, global DI и concurrency остаются открытыми. Отчёт SHA256: 86993e00742781491e3854a8a2be4aa758eeb26de250b5eee9153242e3e60a48.

Для Юлдаша отдельно предлагается различать LocalDraftSaved и авторитетное подтверждение заказа, определить revision policy и восстановление между стадиями commit. Непроведённые тесты: synthetic failure, invalid даты, несовпадение captured/current, оба conditional-update retry, ошибки между записями и при analytics/close. Runtime гонка и серверный результат не воспроизводились.

## BlaBlaCar поиск сообщения Braze по ID

Проверенный request lookup ожидает event, преобразует поддерживаемые cards и возвращает первое совпадение захваченного ID с ID mapped message. Отсутствие совпадения вызывает Throwable, но не доказывает отсутствие card на сервере: фильтрация предшествует поиску. Это иной контракт, чем прежний SDK Card lookup, возвращающий Card или null.

В определённых try ranges два typed исключения, network failure и SDK not activated, заменяются новым Throwable с текстом без исходного cause. Текст о timeout не устанавливает фактическую причину каждого пойманного network failure. Иные исключения этим typed catch не заменяются.

Подтверждённый статический caller сначала ожидает lookup, затем logViewed с тем же input ID и только после нормального завершения возвращает Success с сохранённой entity. Исключение, вышедшее из logViewed в защищённую область caller, направляется в FailureMapper; это не утверждение о любых внутренних ошибках или их подавлении внутри logViewed, который здесь не разобран. Сам вызов не доказывает показ пользователю, согласие или доставку analytics.

Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 1 новое полное тело / 113 DEX units; прежние 3 тела / 222 units и 3 helpers / 15 units имеют нулевой повторный credit. Исходные identities и байты проверены, общий decoder не является второй независимой декомпиляцией; novelty ограничена предоставленным корпусом. Глобальный знаменатель не изменён. Свежесть event, runtime DI, account scoping, SDK lifecycle и renderer остаются открытыми. Отчёт SHA256: 83d202e9e822a8016a29d961b0ecf3a3a3cbce0cf7dd7fa2a7bc6fd8f505cc94.

Для Юлдаша отдельно предлагается различать запрос detail, tracking call, доставку SDK, render и прочтение человеком. Непроведённые тесты на fake events: отсутствие и дубли ID, отфильтрованная card, typed/generic exceptions, нормальный и выбрасывающий исключение logging delegate. Настоящие ID, SDK и сеть не использовались.

## Яндекс Про V1 восстановление preview после ошибки

В проверенном getPreview API распознанный cancel возвращает null без error logger; иной распознанный error логируется и перекидывается дальше. Нераспознанный exception-класс также перекидывается. Это отличается от updateOptions, где нормальный возврат logger ведёт к null. Полные EH protected-PC regions не восстановлены, поэтому исход любого decoder или helper exception здесь не гарантирован.

Прежний manager выставляет Loading до await. Нормальный null завершает конкретную операцию без нового Data/Error emission; это не доказательство постоянного spinner, поскольку инициатор отмены, другие события и уход со страницы не установлены. Nonnull обрабатывается при допустимом lifecycle. Его error landing различает Exception и другие Error/Throwable: Error UI не гарантирован для любого malformed DTO.

Проверенный selector выбирает Loading, Data или Error, а неизвестный subclass выбрасывает StateError. Error widget использует локализованные сообщения, не payload ошибки. Его retry через captured State читает текущие widget и manager и вызывает preview без await; amount и персональные поля в кнопку не захватываются. Это повтор загрузки конфигурации формы, не init и не повтор денежной операции.

Запрос preview передаёт provider identity и cancelToken, без data/amount в выбранном transport descriptor. Нормальный DTO не подтверждает зачисление. Runtime DI, HTTP implementation, callback scheduling, state delivery, cancellation producer и поведение generic кнопки остаются открытыми; привязка PP/GDT опирается на проверенные native данные и хэшированные аннотации.

Независимая проверка PASS: 8 новых полных dependency-limited тел / 1588 native bytes / 63 прямых перехода; 4964 механические проверки не равны runtime сценариям. Прежние manager, decoder и shared forwarder повторного кредита не дают. Отчёт SHA256: c4c19491ae47da4b63ff85c6b688c24ffe42c81c64e32ad6c6f12b3f7cdf390c.

Для Юлдаша отдельно предлагается определить переход после отмены на активной странице и различать cancel, error и отсутствие нового state. Непроведённые тесты: fake preview/cancel/error/type error/logger throw, lifecycle=false, замена текущего widget/manager, repeated retry и ответ после disposal. Runtime гонка, бесконечная загрузка и финансовые последствия не установлены.

## Яндекс Про V1 остановка и адресная отмена запросов

Проверенный фрагмент provider регистрирует manager.stop как onDispose callback. На нормальном синхронном пути manager сначала вызывает repository.stop, затем освобождает composite подписок и amount controller. Repository отменяет debounce, закрывает и обнуляет loading/options controllers, затем вызывает API.cancelAll. Futures закрытия не ожидаются; раннее синхронное исключение может прервать дальнейшие шаги.

API последовательно направляет четыре cancel-dispatch по ключам preview, calculate, init и topup/url. Для последнего фактический аргумент задан явной строкой, а не результатом вызванного getter. Совпадение ключей с прежними cancelToken resource bindings показывает адресацию отмены, но implementation resource manager, runtime identity и соблюдение отмены transport здесь не закрыты. Это не подтверждение прекращения HTTP, отсутствия позднего ответа или rollback серверного init.

В выбранных stop телах нет записи lifecycle flag. False-write находится в отдельном прежнем StateNotifier.dispose; порядок этого пути относительно provider hook не установлен. Поэтому stop не равен немедленному mounted=false. Обнулённые controllers препятствуют конкретным будущим setter add, но не исключают уже доставленные события. Holder обнуляет controller, не очищая input-state map; возможность создать controllers при новом start не доказывает безопасный runtime restart.

Независимая проверка PASS: 5 новых полных dependency-limited тел / 772 native bytes / 29 прямых переходов и один DI фрагмент / 28 bytes / 2 перехода. Полная DI factory и generic disposal не получают нового кредита. Отчёт SHA256: a1404ece47cc6a4bcfc4f46e7487070e9c163565bddc10ac5ab8b919016ff3a2.

Для Юлдаша отдельно предлагается определить порядок lifecycle и отмены, обработку ошибок teardown и правила повторного start. Непроведённые тесты: throw на каждом шаге, ignored future failure, отмена до и после запуска worker, поздний ответ, queued events и соответствие token/key в fake transport. Реальные запросы и финансовые действия не выполнялись.

## Go C125 временное исключение transfer и полный reset

Проверенный selected-tariff consumer различает временное обнуление preorder.U и уничтожение сохранённого snapshot. При неподдерживаемой requirement/option он обнуляет только U; при поддерживаемой присваивает сохранённый pn11.a, включая null. Predicate берёт первую requirement с совпавшим именем: если её тип неподходящий, вторую одноимённую не ищет. Затем проверяет наличие option по точному nullable equality, без trim, проверки дат или revision.

Полный локальный reset отдельно записывает snapshot из четырёх null полей, сбрасывает flow-флаг и обнуляет U. Это последовательные записи без общей транзакционной гарантии. В выбранном deletion consumer reset вызывается только при a=true и b=false; a=null трактуется false, а null-инвариант b остаётся открытым. Прежний supported-requirements callback при true выполняет reset и затем другой helper; это не доказательство удаления пассажирских данных или аккаунта.

Внешняя связь selected-tariff callback подтверждена узким контекстом caller: создание, constructor и передача в flow/e.k. Она имеет нулевой семантический credit, не является полным аудитом caller и не устанавливает upstream installation или непрерывную runtime subscription. Поэтому нельзя обещать очистку при каждом переключении тарифа. Reset также не доказывает остановку уже запущенного draft callback C124 или безопасное восстановление сохранённого transfer.

