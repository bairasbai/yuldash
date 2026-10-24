Самостоятельные предложения для Юлдаша: различать cancellation, payment error и normal completion; отдельно определить владельца cleanup и подтверждённый серверный outcome. Непроведённые fake-block тесты: immediate/resumed Success, CancellationException в обоих guarded участках, иной Throwable, ошибка mapper и cleanup при cancellation. Scheduler/runtime и идемпотентность не доказаны; API, приложение и реальные платежи не запускались, значения токенов и реквизитов не извлекались.

## Яндекс Про повтор операции с захваченным ID реквизита

Ограниченная статическая проверка: 6 новых полных локальных тел, 876 native bytes и 44 direct branch targets; независимая QA PASS с подтверждённой финальной редакцией. Прежние request/response/helper тела имеют нулевой новый кредит. Новизна ограничена проверенным корпусом, глобальный coverage не изменён; native/PP bindings не заменяют runtime.

После returned ApiResponseError set-default/delete сохраняют callback с аргументом предыдущей попытки. Retry запрашивает pop, затем вызывает эту callback, которая повторяет ту же операцию с тем же целевым ID. Текущий выбор реквизита здесь не перечитывается. Это отличается от edit retry, повторяющего submit текущей формы; но не доказывает byte-for-byte replay всего запроса, поскольку provider/options читаются downstream заново.

Shared retry читает receiver из context.f и navigator из receiver.b. Между pop и callback нет Await, future операции также не ожидается; порядок вызовов не означает завершение закрытия до запроса. Исключение pop может прервать callback. Cancel только вызывает pop, не читает target callback и не запускает retry, dirty или refresh. Это не rollback и не отмена уже отправленной серверной операции.

Самостоятельные предложения для Юлдаша: явно выбрать same-target или current-selection retry и определить reconciliation неоднозначного исхода. Непроведённые fake-API тесты: цель A→ошибка→выбор B→retry, повторная ошибка, cancel, null/empty message и delayed/throwing pop. Актуальность ID, initial confirmation, права, exactly-once, runtime double-submit и server commit/rollback не доказаны. Реальные реквизиты не извлекались; API, приложение и реквизитные действия не выполнялись.

## Яндекс Про начальное подтверждение удаления реквизита

Ограниченная статическая проверка: 5 новых полных локальных тел, 768 native bytes, 44 direct branch targets; независимая QA PASS с 4198 механическими проверками. Прежние API, retry и generic modal имеют нулевой новый кредит. Новизна ограничена проверенным корпусом, глобальный coverage не изменён.

Оба проверенных View handlers читают current state и вызывают flow только для Data, извлекая целевой ID в момент вызова. Non-Data возвращается без операции. Это не проверка фактической доступности widget, прав сервера или всех upstream входов.

Public delete сохраняет ID до открытия confirmation dialog и не вызывает удаление непосредственно. Confirm запрашивает pop и вызывает сохранённую target callback: используется прежний ID, без перечитывания current Data. Cancel только запрашивает pop. Подтверждающий текст на данном callsite берётся из getters без параметра ID; видимый перевод, фон и достаточность информации пользователю не проверены. Это локальный confirmation gate данного entry, не универсальное доказательство consent для всех внутренних callers.

Public set-default после telemetry прямо вызывает внутреннюю операцию, без собственного второго confirmation dialog именно в этом теле. Из этого не следует отсутствие предварительного согласия во всём UI или недостаточная авторизация. Pop→callback не означает завершённое закрытие, Await операции в общей callback нет.

Самостоятельные предложения для Юлдаша: явно определить подтверждение цели и поведение при её изменении, отделяя handler Data guard от UI availability и серверных прав. Непроведённые fake-state тесты: Non-Data, Data(A), delete(A)→смена Data(B)→confirm, cancel, labels/callback mapping, delayed pop и повторный жест. Актуальность ID, lifecycle, server commit/rollback и идемпотентность не доказаны; API, приложение и реальные реквизитные операции не выполнялись.

## BlaBlaCar 3DS2 результат SDK и результат confirm

Ограниченная статическая проверка: одно новое полное business body, 136 instruction units; 4 прежних тела на 201 units и constructor binding на 9 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила исходные DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Namespace и глобальные показатели не изменены.

Typed ThreeDS2Result.Success запускает confirm, а SDK Error передаётся как ThreeDS2SdkError. Это не HPP resultCode и не подтверждение оплаты. Auth result захватывается constructor до запуска; Nav читается из текущего VM при label0 coroutine. Confirm получает token из этого Nav, captured auth result и nullable fraud ID. После ответа callback holder снова читается из текущего VM. Локального correlation/version guard в новом теле нет, но достижимость смены контекста и runtime race не доказаны.

Interactor Failure передаётся как PaymentExceptionError общему handler; только Result.Success<Unit> вызывает success callback. Parent consumed не ждёт confirm. Локальных try/catch и Loading/Loaded writes здесь нет. Прежний shared Result helper повторно не засчитывается: его cancellation/rethrow правило ограничено собственными protected ranges, не отменой серверной операции.

Самостоятельные предложения для Юлдаша: отдельно определять SDK outcome, confirm completion и финансовый результат; связывать auth result, request context и owner позднего ответа. Непроведённые fake-interactor тесты: SDK Error/Success, confirm Failure/Success/throw, delayed label0, смена Nav/callback, повтор SDK события и nullable preconditions. Producer correlation, nested confirm action/repository, DI и callback delivery остаются открытыми. Проверка личности, settlement, booking и безопасность контекста не доказаны; SDK/API/приложение не запускались, реальные auth/token values не извлекались.

## Go C102 допустимые действия поддержки и локальные маршруты

Ограниченный final report, не frozen checkpoint и не полный аудит поддержки. Независимая QA PASS: 17 полных тел и 5 фрагментов, 436 selected records = 418 новых + 18 reused; 2390 записей исключены. 12553 механические проверки, включая excluded byte identity, не являются runtime-тестами или новым семантическим покрытием исключённых ветвей. Глобальный ledger не изменён.

ConvertAction отклоняет DeeplinkAndCancelAction и четыре singleton варианта, возвращая null; прежняя C101 фильтрация удаляет такие пункты. Поддерживаемые варианты создают локальные action-модели, неизвестный вариант бросает NoWhenBranchMatchedException, а не тихо игнорируется. ModalViewAction передаётся отдельному mapper, чьи внутренности здесь не замкнуты.

Deeplink-ветвь передаёт строку в Uri.parse и router; отсутствие локального scheme/host guard не доказывает отсутствие downstream validation или уязвимость. DriverCall читает текущий TaxiOrder из captured holder при выполнении callback и передаёт его вместе со строкой action дальше в delegate. Возможное несоответствие старой строки и нового holder не воспроизведено и не объявлено runtime defect.

Остальные выбранные switch-ветви вызывают modal, reorder/cancel или chat маршруты. В одной chat ветви очистительная часть выполняется до проверки флага заказа; последующий showChat зависит ещё и от ненулевого fragment. RideSupportChat сохраняет holder/строку в callback, который при вызове передаёт их cw70; факт вызова и конечный результат не установлены. Это не звонок, ticket, доставка сообщения, серверная отмена или refund.

Самостоятельные предложения для Юлдаша: определить допустимые actions каждого surface, связывать действие с order/version и различать маршрут, callback, экран и подтверждённый результат. Непроведённые fake-тесты: rejected/unknown DTO, смена holder, deeplink validation/error, chat без callback и отсутствующий fragment. Modal mapper, cw70, router policy, lifecycle/DI identity и достижимость stale click C101 остаются открытыми. API, приложение, URL-переходы, звонки, обращения и платежи не выполнялись.

## Go C103 вложенные кнопки modal поддержки

Ограниченный final report, не frozen checkpoint и не полный аудит renderer: независимая QA PASS, 7 полных тел и 2 фрагмента, 324 новых selected records, 638 записей исключены. 4412 механических проверок не являются runtime-тестами; прежние filter/dispatch не получают нового кредита, глобальный ledger не изменён.

Arrangement читается после преобразования кнопок: null даёт null modal, неизвестный mapping не заменяется default layout. Null converted actions дают null buttons и отфильтровываются; валидный arrangement допускает создание модели с пустым итоговым списком. Text deferred создаётся до null-check action, но в null-action ветви явного await текста нет. Отмена или отсутствие влияния этого deferred не доказаны.

Строка кнопки для аналитики вычисляется из имени типа action, а не является уникальным button ID. Callback передаёт label/from в analytics, затем читает непосредственно captured action и вызывает локальный dispatch, без lookup по label. Поэтому одинаковый тип двух кнопок не означает last-wins подмену из другой карты C101. Актуальность captured action после смены заказа отдельно не доказана.

Analytics вызывается раньше dispatch; если этот вызов бросит, последующий dispatch на данном пути не достигается. Фактическое исключение не наблюдалось. Название debounce setter не доказывает exactly-once, а analytics и dispatch не являются delivery receipts. Вложенная modal структура также не доказывает runtime overflow.

Самостоятельные предложения для Юлдаша: определить пустой modal и unknown arrangement, различать instance ID и action type, ограничить вложенность и проверять актуальность действия. Непроведённые fake-тесты: unsupported action с созданным text deferred, одинаковые labels с разными payload, смена order/version и analytics failure. Lifecycle/DI, UI delivery, downstream chat/deeplink policy, ticket/refund и финансовый ACK остаются открытыми. API, приложение, ссылки, звонки, обращения и платежи не выполнялись.

## Яндекс Про доступность действий просмотра реквизитов

Ограниченная статическая проверка: 5 новых полных тел на 1684 native bytes и 3 диапазона partial build на 752 bytes, 103 direct branch targets; независимая QA PASS. Полный build, generic кнопки и прежние handlers не получают нового полного кредита. Новизна ограничена проверенным корпусом, глобальный coverage не изменён.

is_delete_available=true создаёт delete icon с callback; false даёт null вместо этого action. Callback дополнительно проверяет loading модели, сохранённой в screen, и при true не вызывает delete handler. Для set-default is_default=true выбирает disabled независимо от loading; иначе loading=true даёт loading, оба false дают enabled. Callback создаётся во всех вариантах, но прежняя generic цепочка блокирует собственный gesture path для disabled/loading.

Оба флага декодируются как обязательные non-null bool без локального fallback; missing/null/wrong type не равны false. Это контракт ответа, не серверная проверка прав. Screen использует свою модель для флагов, тогда как downstream handler отдельно читает current state и ID. Две точки чтения установлены, но stale callback, неверная цель или runtime race не доказаны.

Самостоятельные предложения для Юлдаша: согласовать screen gates и current target, отделить mandatory schema от авторизации и обработать parse failure явно. Непроведённые fake/UI-тесты: availability/loading/default матрицы, неверные bool, смена Data между build и click, keyboard/accessibility и уже начатый жест. Полный rebuild lifecycle, icon gestures, общий exception recovery и server commit/rollback открыты. API, приложение и реальные реквизитные действия не выполнялись.

## Яндекс Go C104 вход в чат поддержки

Ограниченный final report, не frozen checkpoint: 10 полных тел, 217 новых записей декодера; 50 контекстных записей исключены. Независимая QA PASS, 1326 проверок. Это продолжение chat consumer C102, без пересчёта глобального покрытия.

Consumer сначала читает ID заказа из текущего holder, затем локальный client id. Null client id останавливает путь, пустая строка отдельно не отвергается. Язык, client id и ID заказа последовательно подставляются в шаблон; проверенный helper делает строковые замены без собственного URL encoding или host validation. Наличие обязательных placeholders здесь не проверяется; нижележащая политика не исследована.

Router до запуска coroutine проверяет наличие WebContainerModalView и при ненулевом результате возвращается. После ожидания токена повторной проверки в выбранном теле нет. Result.Failure превращается в null и останавливает открытие; thrown exception не равен этому результату и собственной catch не перехватывается. URL и ненулевой токен передаются в разные поля конфигурации, токен здесь не дописывается к URL. Последний доказанный шаг только запрос открытия modal, не передача auth, показ окна, авторизация, сообщение или ticket.

Самостоятельные предложения для Юлдаша: структурная сборка адреса и проверка назначения, согласование order и identity context, отдельные состояния открытия и отправки обращения. Непроведённые mock-тесты: null/blank, отсутствующие placeholders, Failure/throw, смена контекста при await и повторный вход. Runtime race, host policy, web transport и серверный outcome не установлены. Реальные токены и адреса не извлекались и не публикуются; API и приложение не запускались.

## BlaBlaCar результат PIX и запуск подтверждения

Ограниченная статическая проверка регионального PIX: 2 новых полных business-тела, 191 instruction units; 3 прежних тела на 103 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Новизна ограничена проверенным корпусом, namespace и глобальный знаменатель не изменены.

Native result decoder различает -1→Ok, 0→Canceled, 100→Error только при ненулевом extra_error_message и 300→TimeoutStartNewPayment. Пустое сообщение принимается как non-null. Неизвестный код или отсутствие сообщения вызывает precondition helper, а не общий гарантированный restart. Его runtime-семантика здесь не установлена. Canceled и Timeout ведут к restart; Error сначала вызывает displayPaymentError, затем restart, поэтому отсутствие или исключение callback не даёт гарантии перезапуска.

Ok только запускает отдельный confirmPix. Nav читается на label0 coroutine, не в момент wrapper launch; purchase token и nullable fraud ID передаются в confirm. Failure уходит в error handler, а только Result.Success<Unit> вызывает callback, заново прочитанный из текущей VM после ответа. Parent не ждёт confirm; собственного catch, записи state и проверки версии контекста после await в выбранном теле нет. Это не доказательство гонки, settlement или безопасной повторной оплаты.

Самостоятельные предложения для Юлдаша: сохранить различия HPP, PIX и 3DS, не приравнивать native Ok к оплате и связывать confirm с исходным контекстом. Непроведённые fake-тесты: все result codes, null/empty message, callback null/throw, delayed Success/Failure и замена контекста. PIX не обязательная функция Юлдаша. Nested confirm action, repository, runtime DI, cancellation delivery, idempotency и серверный outcome открыты; реальные платёжные данные, API и приложение не использовались.

## BlaBlaCar отправка формы банковского счёта для выплат

Ограниченная статическая проверка: одно новое полное business-тело на 177 instruction units; 6 прежних тел на 297 units и 6 helpers на 71 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила DEX identities и байты, но общий decoder не является второй независимой декомпиляцией. Глобальный знаменатель не изменён.

Coroutine читает Content на label0; иной тип state завершает путь без запроса. Request строится до очистки snackbar. Проверенные copy masks сохраняют поля, title и CTA, поэтому очистка сообщения не является переходом в Loading. Собственного CTA/validation/pending guard в этом теле нет, но отсутствие UI-валидации или возможность повторного нажатия во всём приложении не установлены.

Исходный Content сохраняется через await. Failure записывает его копию с error.message, не перечитывая текущую форму; Success отображает response в следующий stepType и публикует событие, а не объект зарегистрированного счёта. Прежний mapper берёт первый step без локального empty guard. Saved-state API не подтверждает durable storage или серверную регистрацию. Это границы статического контракта, не доказанная потеря правок, runtime race или перевод денег.

Самостоятельные предложения для Юлдаша: определить контракт позднего ответа относительно изменённой формы и разделить next-step event, локальное сохранение и серверную регистрацию. Непроведённые fake-тесты: Content/non-Content, mapper exception до очистки snackbar, сохранение CTA, поздний Failure после замены формы и empty/unknown steps. UI gates, upstream validation, event delivery, framework restoration и server persistence открыты. Реальные реквизиты, API и приложение не использовались.

## Яндекс Про инициализация пополнения и закрытие web страницы

Ограниченная статическая проверка: 8 новых полных тел, 2516 native bytes и 140 direct branch targets; независимая QA PASS. Старый interactor, model copy и generic navigation context не получают нового кредита. Глобальное покрытие не пересчитывается.

Queued task допускает только текущий Data с initializing=false, затем устанавливает true перед API. Успешный init outcome содержит URL, полученный напрямую либо через отдельный запрос по PcidssId, а не зачисленный баланс. Success сохраняет initializing=true; другие outcomes сбрасывают его только при current Data и true. Проверенные exception landings не дают безусловной гарантии cleanup при сбое logger, emitter или самого cleanup; полные EH protected ranges и scheduler semantics не восстановлены.

openWebLink ожидает preventScreenshotOn, но результат dynamic navigation вызова не ожидает и не возвращает как Future результата навигации. Поэтому последующие completeTopupInitialization и success telemetry не подтверждают показ или закрытие страницы и тем более оплату. Complete только сбрасывает initializing в текущем Data, если флаг ещё true; баланс и receipt этим кодом не проверяются.

Отдельный onClose ожидает preventScreenshotOff и вызывает closePage; его exception landing также вызывает closePage перед ReThrow. Это локальные вызовы очистки, не гарантия платформенной защиты или успешного закрытия при исключении. Concrete navigation provider, доставка callback, URI policy, серверная сверка и финансовый outcome остаются открытыми.

Самостоятельные предложения для Юлдаша: различать получение URL, запуск web UI, завершение initialization и подтверждённое зачисление. Непроведённые fake-тесты: admission/outcome матрицы, незавершённый navigation Future, условный reset current state, ошибки protection/cleanup и поздний onClose. Runtime race, повторное списание и зачисление не заявляются; URL не открывались, API и приложение не запускались.

## Яндекс Go C105 состояние диалога удаления сохранённой поездки

Ограниченный final report, не frozen checkpoint: 16 полных тел и 1 фрагмент, 357 новых выбранных записей; 1144 контекстные записи исключены. Независимая QA PASS, 6991 проверка. API удаления из C26 используется без повторного кредита, глобальный ledger не пересчитывается.

