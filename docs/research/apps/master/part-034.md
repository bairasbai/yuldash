Инициализация модели и подписка на ResponseState запускаются отдельно, без доказанного порядка завершения. LOADING выключает обе кнопки и задаёт positive loading=true. ERROR сначала запускает уведомление, затем включает обе кнопки и задаёт positive loading=false. Copy masks сохраняют прежний negative loading, а не безусловно сбрасывают его. При null UI-модели эти ветви не создают начальную модель; обновления используют CAS с повторным чтением.

LOADED отправляет analytics с захваченным template id, затем запускает dismiss без ожидания завершения. Observer получает nullable ResponseState без ID/revision и не сравнивает его с captured id; исключение analytics может прервать достижение dismiss в этой ветви, но реальный такой сбой не установлен. LOADED не подтверждает durable deletion, cache invalidation или отмену действующей поездки.

Локальный hook записывает null в response flow, а не в UI flow. Collector игнорирует null, поэтому эта запись сама не сбрасывает кнопки и не отменяет API job. Полный lifecycle hook и достижимость позднего ответа другой операции открыты; runtime mismatch и global single-flight не доказаны.

Самостоятельные предложения для Юлдаша: связать статус удаления с operation ID и revision, отдельно моделировать подтверждение хранилища, progress, уведомление и закрытие. Непроведённые fake-тесты: LOADING до начальной модели, LOADING→ERROR с сохранением negative loading, reset null, поздний ответ после смены payload и ошибки analytics/notification. Реальных удалений, API и запуска приложения не было.

## Яндекс Go C106 сигнал обновления списка избранного

Ограниченный final report, не frozen checkpoint: 13 полных тел и 1 фрагмент, 589 выбранных записей = 531 новая и 58 повторных из C25; 91 запись исключена. Независимая QA PASS, 3232 проверки. Прежний combined fetch не получает повторного кредита, глобальный ledger не меняется.

Предикат delete/save возвращает true, если хотя бы одно ResponseState равно LOADED; фильтр пропускает true, mapper превращает его в Unit, а конкретный presenter collector вызывает refresh. Собственного сравнения operation ID, версии или перехода из прежнего значения нет; upstream emission/conflation не исследованы, поэтому число повторных событий не гарантируется. Refresh отменяет старый job без join, строит новую цепочку и сохраняет новый job. getFavorites вызывается при поступлении upstream значения, не синхронно самим refresh.

При включённом gate combined модель принимается только при наличии обоих sections и несовпадении проверяемого header с singleton FormattedText.c по identity. Это не универсальная blank-проверка. Непринятый ответ даёт null, а не address-only partial model; при выключенном gate действует отдельная address-only ветвь.

Null результат передаётся downstream, минуя запись presenter.J, и этим путём прежний snapshot J не очищается. Ненулевая модель сохраняется в J до downstream.emit; ошибка доставки сама не означает rollback. Это не доказательство отображения удалённого шаблона или runtime stale UI. Сигнал refresh, отмена job и LOADED не подтверждают свежий список, transport cache invalidation или durable deletion.

Самостоятельные предложения для Юлдаша: различать mutation receipt, refresh signal и авторитетную версию списка; явно определить partial/null/error политику и version fencing. Непроведённые fake-тесты: пары delete/save states, отсутствующий section и sentinel header, успешный snapshot затем null, ошибка после записи J и поздний ответ отменённой загрузки. Renderer, lifecycle, account fencing, transport cache и repeat-launch остаются открытыми. API, приложение и реальные удаления не выполнялись.

## Яндекс Про контракт API и DTO инициализации пополнения

Ограниченная статическая проверка: 7 новых полных тел, 2132 native bytes и 74 direct branch targets; независимая QA PASS, 4390 механических проверок. Прежние manager/mapper и generic helpers имеют нулевой новый кредит; глобальное покрытие не пересчитывается.

amount_data переносится в request без локального числового преобразования; дополнительные поля сериализуются как id/value. Единицы суммы, подпись, срок действия и соответствие отображённой сумме этим не установлены. payment_system_provider_id и token передаются отдельно в query. Запрос получения URL использует pcidss_topup_id без повторного amount_data в этом callsite. Эти имена не доказывают idempotency, ownership или серверную авторизацию; реальные значения не извлекались и не публикуются.

Возвращаемый Success требует normal transport completion, non-null response data и успешный decoder. Раннее выделение объекта Success до decode не означает успешный результат при parse exception. Выбранные классы исключений идут через общий error mapper, остальные перебрасываются; полная shared classification и EH ranges не закрыты.

Init decoder принимает два wire type, web_link и pcidss_topup_id, без unknown fallback; url и pcidss_topup_id являются nullable String. Он не требует ровно одного непустого payload, но прежний mapper отдельно обрабатывает null выбранного поля. GetURL decoder требует non-null String url; empty string проходит проверку типа. Это не URL allowlist и не доказательство отсутствия downstream policy. DTO не подтверждает credited balance или payment receipt.

Самостоятельные предложения для Юлдаша: связывать amount context, provider и correlation ID; различать transport, parsing, navigation и authoritative financial outcome. Непроведённые fake-тесты: nullable/empty/unknown DTO, requireData failure, выбранный payload null, correlation второго запроса и accepted exception против rethrow. Actual transport, token lifecycle, URL policy и серверный commit остаются открытыми. API, приложение, переходы по URL и финансовые действия не выполнялись.

## Яндекс Про введённая сумма и payload расчёта пополнения

Ограниченная статическая проверка: 11 новых полных тел, 2848 native bytes и 134 direct branch targets; независимая QA PASS. Новизна подтверждена в ограниченном корпусе, прежний submit/init и generic helpers не получают повторного кредита, глобальное покрытие не пересчитывается.

Captured input передаётся в calculation API как amount: пустая строка заменяется на "0", непустая не преобразуется этим task. Отдельная серверная строка topup_action.amount становится payload InitAction; старый submit передаёт именно этот payload как amount_data, а не заново сериализует текущий ввод. Числовое равенство, подпись, expiry и соответствие показанной сумме этим не установлены.

Action decoder переводит неизвестный non-null type в none, но missing/null required type вызывает ошибку. Nullable или неизвестный reason даёт null. Для init_topup null amount приводит к NoneAction, а non-null, включая empty String, создаёт InitAction. Calculation DTO требует payment_info List и topup_action Map; элементный payment-info decoder здесь не закрыт.

Calculate захватывает текущую Data до queue, отменяет debounce и endpoint calculation, ждёт mark-task, затем ставит новый debounce. Mark-task сохраняет captured старые paymentInfo/action под calculating=true; прежний submit gate отклоняет этот флаг. После API await response-task не сравнивает current input или generation перед emit новой Data. Это локальная граница, не доказанная runtime race: cancellation delivery, queue ordering, overlap и lifecycle открыты. Отсутствие отдельного reset для прочих non-success не доказывает вечный busy.

Самостоятельные предложения для Юлдаша: определить проверяемую связь server action payload с вводом, provider и сроком действия; отдельно задать latest-result policy. Непроведённые fake-тесты: empty input, unknown против missing type, null/empty amount, сохранённый action при calculating, поздний ответ после смены ввода и отмена расчёта. Calculation success и InitAction не являются банковским ACK. Runtime, API, URL и финансовые операции не выполнялись.

## BlaBlaCar UI проверки формы банковского счёта

Уточнение предыдущего submit-разбора: отсутствие validation в coroutine не означает доступность пустой формы через обычный экран. Новый охват разделён на 2 validation-тела на 329 instruction units и 6 UI binding-тел на 2254 units. Независимая QA PASS_WITH_SCOPE_LIMITATIONS; helpers и выбранные диапазоны shared dispatchers имеют нулевой новый кредит, остальные dispatcher branches не объявлены прочитанными. Глобальный знаменатель не меняется.

В проверенном Compose content кнопка не строится, пока bank name, first name, last name и оба поля выбранного варианта IBAN/BIC либо account number/sort code не проходят nonblank. Проверка учитывает whitespace/space characters, но не переписывает строки. Она не проверяет checksum, банковский формат или errorMessage; server eligibility не установлена.

Реальная цепочка Screen→View→Content передаёт callback регистрации VM в условно создаваемый PixarButton. DEFAULT, LOADING и DONE выбирают разные button models, но проходят тот же nonblank gate. Наличие callback в памяти не доказывает обход. Boolean InputField выбирает IBAN visual transformation, а не disabled/pending состояние; change adapter передаёт исходную строку callback без собственного преобразования.

Самостоятельные предложения для Юлдаша: раздельно проверять наличие кнопки, её интерактивность и банковскую валидацию. Непроведённые fake/UI-тесты: blank/nonblank обеих форм, malformed nonblank и field errors, DEFAULT/LOADING/DONE, actual callback binding и изменение формы при pending. Shared widget clickability, overlays, lifecycle и достижимость повторных кликов остаются открытыми; runtime bug или безопасность повторной отправки не доказаны. Приложение, API и реальные реквизиты не использовались.

## BlaBlaCar отправка формы PayPal для выплат

Ограниченная статическая проверка: одно новое полное business-тело на 168 instruction units; 4 прежних тела на 93 units и 6 helpers на 45 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила исходные DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Глобальный знаменатель не меняется.

Content читается на label0 coroutine. Request строится до очистки snackbar; copy mask сохраняет title, email model и CTA, без перехода в Loading. Исходный Content сохраняется через await. Failure записывает его копию с nullable error.message без перечитывания текущей формы, generic fallback и field-error mapper. UI-валидация email и доступность редактирования или повторного submit этим не установлены; bank UI guards нельзя автоматически переносить на PayPal.

Success отдельно проверяется после Failure и публикует mapped first stepType, а не зарегистрированный аккаунт. В отличие от bank coroutine, null либо иной совместимый Result, не прошедший оба instanceof, завершает Unit без отдельного throwing else. Реальная выдача такого Result не доказана. Собственного catch нет; исключения mapper, resume или event delivery не превращаются здесь в snackbar.

Самостоятельные предложения для Юлдаша: явно определить email validation, pending-политику и связь ответа с revision формы. Непроведённые fake-тесты: Content/non-Content, сохранение CTA, поздний Failure после смены формы, null message, empty steps и допустимые null/unknown Results. Saved-state запись и next-step event не доказывают принадлежность аккаунта, durable registration или выплату. Lifecycle, UI reachability, event consumption и серверный outcome открыты; реальные адреса, аккаунты, API и runtime не использовались.

## Яндекс Go C107 передача выбранной точки из избранного

Ограниченный final report, не frozen checkpoint: 481 выбранная запись = 405 новых и 76 повторных; 20 полных тел и 3 фрагмента, 107 записей исключены. Независимая QA PASS, 2931 проверка. Старые share/replacement helpers не получают повторного кредита; глобальное покрытие не пересчитывается.

Выбор избранного передаёт FavoriteAddress.point, основной пункт использует точку, захваченную при создании presenter, без нового чтения GPS. Два числа форматируются с Locale.US до пяти дробных знаков, затем удаляются конечные нули и точка. Это не гарантия свежести, точности или анонимизации. Текст состоит из локализованного префикса, пробела и ссылки с заменёнными координатами; fallback выбирается только для строки шаблона нулевой длины, не для whitespace.

Проверенный путь создаёт ACTION_SEND с EXTRA_TEXT и text/plain через chooser. Этот caller передаёт requestCode=0 и не ожидает результат выбора получателя. Dismiss следует за обычным возвратом share helper, но не является receipt или доказательством доставки. Config gate не является согласием на распространение геоданных; локальное отсутствие host/encoding проверок не доказывает отсутствие upstream policy.

Загрузка избранного при non-cancellation ошибке в выбранных защищённых участках передаёт исходный пустой список, а не частично построенный результат; CancellationException перебрасывается. Ошибка последующего showFavorites вне этих try regions ими не покрывается.

Самостоятельные предложения для Юлдаша: показывать точный передаваемый текст и происхождение точки, валидировать шаблон и координаты, различать chooser request и доставку. Непроведённые fake-тесты: captured point после смены location, округление и специальные числа, blank/custom templates, ошибка преобразования списка, analytics/helper exception и отмена chooser. Реальная конфигурация, lifecycle, получатель, backend и delivery открыты. Реальные адреса, координаты, sharing, URL и API не использовались.

## Яндекс Про разрешение кнопки пополнения

Ограниченная статическая проверка: 7 новых полных тел на 2944 native bytes и отдельно 2 partial build диапазона на 352 bytes; 105 full и 11 partial direct branch checks. Независимая QA PASS. Полный build и прежние generic gesture helpers не получают нового полного кредита, глобальное покрытие не пересчитывается.

ViewObject объединяет topup, calculation и personal states без доказанной атомарности снимка. Initial/Loading topup имеет приоритет над calculation error. При Data calculation busy ИЛИ initialization busy даёт loading; иначе enabled требует InitAction и положительный personal-fields isValid, остальные варианты disabled. Индивидуальные validators здесь не закрыты. Subtitle reason и inline invalid-amount error имеют отдельные условия, поэтому loading не сводит все сообщения к одному флагу.

Конкретный footer переводит topup enum в BNG state явным mapping, не копированием ordinal. Disabled/loading связаны с ранее проверенным исключением собственных gesture recognizers, а не только цветом. Callback сохраняется при любом state, но его наличие не доказывает обход. Keyboard/accessibility, уже начатый жест и прочие входы остаются вне этой гарантии.

Tap сначала снимает focus, затем читает текущий widget VM и вызывает interactor без await. Сам callback не повторяет UI predicate, но прежний interactor отдельно перечитывает current states и проверяет Data, busy, personal validity и InitAction. Различие UI snapshot и current submission gate не доказывает runtime race. Enabled означает локальный допуск к попытке, не оплату или credited balance.

Самостоятельные предложения для Юлдаша: отдельно моделировать calculation и initialization busy, сохранить current-state проверку при submit и явно сопоставлять enum. Непроведённые fake/UI-тесты: матрица трёх states, оба busy flags, reason/error условия, смена state между build и tap, gesture против прямого callback. Individual validation, freshness, lifecycle, полный EH и серверный outcome открыты. API, APK, URL и реальные данные не использовались.

## BlaBlaCar UI проверки формы PayPal

Ограниченная статическая проверка: 7 новых полных тел на 1606 instruction units, из них gate/error 2 тела на 402 units и UI binding 5 тел на 1204 units. Независимая QA PASS_WITH_SCOPE_LIMITATIONS. Прежний edit handler, 14 helpers и 3 выбранные dispatcher ветви имеют нулевой новый кредит; полная raw выгрузка dispatcher не означает семантический аудит остальных ветвей. Глобальный знаменатель не меняется.

Кнопка строится только при !isBlank(email text), независимо от errorMessage и после выбора DEFAULT/LOADING/DONE модели. Это nonblank gate, не проверка email формата, принадлежности аккаунта или серверной допустимости. Любой non-null errorMessage, включая blank, создаёт error model отдельно от кнопки; null выбирает default error model.

Конкретные Screen callbacks с тем же VM проходят через View/Content в submit и edit. Submit связан с ранее проверенным registerPaypalAccount; edit передаёт ту же строку без trim/lowercase. Прежний Content-only edit заменяет text, сохраняя field error, CTA и snackbar. Наличие сохранённого callback не доказывает доступность пользовательского действия при pending.

Самостоятельные предложения для Юлдаша: разделить непустоту, формат и ownership, определить очистку старой ошибки и pending/revision политику. Непроведённые fake/UI-тесты: blank × CTA model × errorMessage, actual callback chain, смена формы перед поздним Failure и Content/Loading/Error routing. Shared-widget clickability, lifecycle, повторный submit и pending edit reachability остаются открытыми. Email verification, server acceptance, account save и runtime bug не доказаны; реальные адреса, аккаунты, API и приложение не использовались.

## BlaBlaCar отправка выбора страны и способа выплат

Ограниченная статическая проверка: 5 новых полных тел на 333 instruction units; 5 прежних тел на 94 units и 10 helpers на 145 units имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS сверила DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Глобальный знаменатель не меняется.

Wrapper захватывает state до установки Loading и создания coroutine. Request строится из этого snapshot, не из текущего state на старте coroutine. Mapper выбирает первую selected country, затем первый selected method только внутри неё: если метода нет, к следующей стране не переходит. Payload содержит country code и enum BANK_ACCOUNT/PAYPAL_ACCOUNT, не ID сохранённого аккаунта или реквизиты. Нулевые и множественные selections описывают ветви mapper, но не доказанную UI-достижимость или server acceptance.

Failure восстанавливает захваченный state с заменой snackbar; только null message заменяется generic текстом, blank сохраняется. Для non-Success исходного state error zipper возвращает его без нового error state. Success публикует mapped first stepType без assignState в этой ветви. Null/unknown Result вызывает throwing helper, в отличие от PayPal register Unit fallback. Собственного catch и generation comparison нет, но runtime зависание или race этим не установлены.

Самостоятельные предложения для Юлдаша: определить инвариант выбора и precondition до Loading, связать ответ с revision, разделить next-step event и сохранение способа выплат. Непроведённые fake-тесты: первая страна без метода, несколько selected, отсутствующий выбор, delayed Failure и null/blank message, empty steps и mapper exception. Selection zippers, actual UI gates, lifecycle и серверная eligibility открыты. Назначение аккаунта, ownership, persistence или выплата не подтверждены; реальные данные, API и приложение не использовались.

## Яндекс Go C108 действия карты из промокода

Ограниченный final report, не frozen checkpoint: 362 выбранные записи = 297 новых и 65 повторных из C33; 14 полных тел и 8 фрагментов, 2362 записи исключены. Независимая QA PASS, 12421 механическая проверка, включая selected switches и совместимость interfaces. Остальные merged ветви и глобальная DI identity не объявлены проверенными; общий ledger не меняется.

