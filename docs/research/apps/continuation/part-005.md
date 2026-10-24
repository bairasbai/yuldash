Независимая QA и final closure: PASS. 172 выбранных records = 152 новых + 20 повторных из C98; 68 исключены. Дополнительная caller-связь не меняет этот счёт. Это bounded final report, не frozen checkpoint и не обновление глобального ledger. Server cancellation, disk durability, global DI identity и runtime race не установлены. Отчёт SHA256: 234762a6998f3b650b876a3e9662a6354a92a896c5d7e5fbd9b5c5d8c85f1a86.

Для Юлдаша отдельно предлагается различать временное исключение из checkout и удаление local draft, определить срок годности и revision при восстановлении. Непроведённые тесты: duplicate requirement names, unsupported→supported на подтверждённом instance, null-поля deletion event, ошибки между reset-записями, subscription lifecycle и поздний draft response. API и реальные удаления не выполнялись.

## BlaBlaCar внутренний исход logViewed

Уточнение предыдущего Braze detail раздела: logViewed повторно ищет raw SDK Card по захваченному ID, а не использует уже полученную mapped entity. При null impression не вызывается и возвращается Unit. При nonnull вызывается datasource, чей прежний call-site Card.logImpression игнорирует boolean результата. Повторный getBrazeEvents сам по себе не доказывает сетевую перезагрузку или тот же либо другой snapshot.

Прежний raw lookup может обработать network/not-activated исключения через logging и вернуть null. Новый inner отдельно ловит только NotActivated в определённых ranges, логирует и при нормальном возврате logger завершает Unit. Следовательно detail Success с сохранённой entity возможен без impression call и без положительного SDK receipt. В FailureMapper caller попадает необработанное исключение, в том числе вышедшее из logger, а не всякая внутренняя ошибка.

Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 1 новое полное тело / 87 DEX units; прежние 4 тела / 319 units и 4 helpers / 20 units повторного кредита не дают. Проверены исходные identities, байты и catch ranges; общий decoder не заменяет вторую независимую декомпиляцию. Глобальный знаменатель не изменён. SDK instance, cache, account generation, consent, lifecycle и persistence не установлены. Отчёт SHA256: 99795d5973ace0b45b360936de2a903ab14f70f10fb6975a342eae9743b9811f.

Для Юлдаша отдельно предлагается не считать normal Unit подтверждением render, человеческого просмотра или доставки analytics. Непроведённые mock-тесты: null Card, true/false SDK return, handled typed errors, unhandled/logger throw и привязка impression к реально отображённому snapshot. Настоящие ID и вызовы SDK/API не использовались; runtime дефект или нарушение consent не заявляются.

## BlaBlaCar click tracking отдельно от перехода

Проверенный logClickButtonOnBrazeContentCardMessage повторно ищет raw SDK Card по захваченному ID. Null завершает Unit без click call; nonnull вызывает datasource, где boolean результата Card.logClick игнорируется. Typed NotActivated в выбранных ranges логируется и при нормальном возврате logger тоже завершается Unit. Это attempt contract, не receipt SDK или analytics.

Прежний detail VM при существующем currentMessage и nonnull URL сначала emits OpenUrl, затем запускает tracking coroutine с захваченным message; ID читается из этого объекта при старте coroutine. При null URL tracking всё равно запускается, если message существует; при null message нет обоих действий. Emission раньше launch не доказывает, что URL уже открыт до SDK call. Исключение emission может остановить последующий launch.

Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 1 новое полное тело / 87 DEX units; прежние 7 тел / 310 units и 4 helpers / 20 units повторного кредита не дают. Исходные identities/байты проверены, общий decoder не является второй независимой декомпиляцией. Найденный search upstream в отдельном namespace оставлен discovery с нулевым credit, его семантика сюда не переносится. Отчёт SHA256: 6ad9e6e5c1215448d8edd64f6c52b736c5b5f64a12b5bac8d643deef12b8bf6e.

Для Юлдаша отдельно предлагается различать tracking attempt, navigation event и подтверждённое пользовательское действие. Непроведённые mock-тесты: null message/URL/Card, captured ID, SDK false, handled и unhandled errors, порядок emission и launch. Реальные URL/ID/SDK не использовались; human click, навигация, consent, доставка и свежесть данных не доказаны.

## Яндекс Про V1 получение URL пополнения

V1 и ранее проверенный V2 оба используют query key pcidss_topup_id. Совпадение ключа не объединяет их DTO, implementations и error contracts. В V1 значение поступает из отдельной init union-ветви и передаётся без преобразования; выбранный descriptor содержит queryParameters и cancelToken, без data/amount. Runtime transport и DI не установлены.

Decoder требует строковый ID и строковый url; пустая строка проходит именно типовую проверку. Проверок scheme, host, expiry и payment status в выбранном URL decoder нет, но это не вывод об отсутствии downstream/server policy. После await nonnull response преобразуется в Weblink с полем +b=false; смысл этого поля в конкретном navigator здесь не установлен.

Распознанный cancel возвращает null, другая распознанная ошибка логируется и перекидывается; прочие классы перекидываются. Полные EH regions и обработка любого decode/logger exception не гарантированы. При null mapper не создаёт Weblink, а прежний outer пропускает normal successInitTopup/open и идёт в cleanup. Ни URL, ни telemetry, ни cleanup не подтверждают зачисление или rollback.

Независимая QA и финальное редакционное уточнение PASS: 5 новых полных dependency-limited тел / 1308 native bytes / 54 прямых перехода. Прежние outer, constants и V2 comparison имеют нулевой новый credit. Отчёт SHA256: 65765e1d50f151a8ce6bdf0c74738fa45cccb3f56419fb42fa80163a6a72682e.

Для Юлдаша отдельно предлагается проверять actual navigation policy и авторитетный финансовый статус независимо от получения ссылки. Непроведённые тесты: missing/null/wrong-type/empty ID и URL, запрещённый host/scheme, cancel/error, поздний response и lifecycle guards. Реальные ссылки и пополнения не выполнялись.

## Go C126 применение ближайшего адреса подачи

Проверенный путь начинается только при наличии activity и view, затем ставит локальный LOADING, отменяет прежнюю job без join и запускает новую цепочку. Диагностика пустой геоточки сама по себе не прекращает этот путь. При включённом guard predicate supported означает лишь Address.Q() != null и непустую строку; пробелы проходят. Это не географическая безопасность, доступность машины или подтверждение зоны обслуживания.

Успех сначала запускает условную GEO clarification coroutine без ожидания, затем выполняет адресные UI-записи и timestamp. Только при наличии initial component достигаются cache, LOADED и nullable callback; при отсутствии component этот хвост пропускается. Clarification не является await-guard адресного применения. LOADED предшествует callback и не подтверждает его доставку.

Error classification снимает один уровень GoApiOtherException и относит IOException, кроме SSLException, к отдельной ветви. Unsupported вызывает virtual hook перед локальным UNSUPPORTED; базовый no-op не исключает overrides. Прочие ошибки используют другой UI-путь, где null component может не допустить terminal ERROR tail. Общий хвост не finally: исключения helpers могут прервать последовательность. Это границы выбранных тел, не доказанные runtime сбои или отсутствие глобального recovery.

Независимая bounded static QA: PASS, 6303 механические проверки. 22 полных тела и 1 фрагмент, 447 выбранных records = 432 новых + 15 повторных; 921 исключён. Прочие switch arms не получают семантический credit. Это bounded final report, не frozen checkpoint и не перерасчёт глобального ledger. Отчёт SHA256: b1c8025167516c58d3cf24bd96015943027f1d4a46d4b8e0dc13c731bda45e3b.

Для Юлдаша отдельно предлагается различать локально применимый адрес и обслуживаемую точку посадки, задавать attempt/revision и независимый terminal status. Непроведённые тесты: Q null/empty/whitespace, single/double wrapper, SSLException, null component, helper throw, late emission и suspended clarification. Producer, server parsing, processPickupPoints, retry listener и global ownership остаются открытыми. Реальные адреса, координаты и API не использовались; доставка водителю и физическая безопасность посадки не подтверждены.

## Яндекс Про V1 закрытие web flow и защита снимков экрана

Уточнение предыдущего Weblink +b=false: выбранная ветвь ожидает preventScreenshotOn и затем вызывает dynamic navigator с URL, source и onClose. Возвращённый navigator Future здесь не сохраняется и не ожидается. Поэтому прежний await _openTopUpPage не означает ожидания закрытия web flow или завершения оплаты.

Отдельный onClose ожидает preventScreenshotOff и только после нормального завершения вызывает closePage, не ожидая его возможного Future. Ошибка screenshotOff прерывает этот нормальный путь к closePage. Callback не получает payment result и не проверяет credited amount. MethodChannel вызовы не доказывают физическое включение или снятие защиты на устройстве, а вызов closePage не является подтверждением закрытия страницы.

Независимая проверка PASS: одно новое полное callback-тело / 120 native bytes / 8 переходов и два partial range / 156 bytes / 6 переходов. Прежний общий prefix, screen-result branch и shared wrappers повторного кредита не дают; true Weblink branch и весь navigator не покрыты. Отчёт SHA256: 57489b7636553f9d0a8c347159c5cd62de26bad19a6f8f3c6755c875126d059b.

Для Юлдаша отдельно предлагается разделять navigation dispatch, web close, protection completion и авторитетный финансовый статус. Непроведённые тесты: незавершённый navigator Future, sync throw/async failure, недоставленный или поздний onClose, ошибка screenshotOff и host close. Host policy, callback delivery, lifecycle и global cleanup остаются открытыми. Отсутствие локальной finally-пары не доказывает утечку или отсутствие cleanup во всём приложении; runtime и реальные платежи не выполнялись.

## BlaBlaCar search banner и копирование voucher

Проверенный Fragment callback передаёт URL, ID и voucher state из одной модели. VM продолжает только при текущем DefaultDisplayState. Available обновляет отображение до Copied при подходящем promo content, emits CopyToClipboardEvent и запускает delayed restore; clipboard event и timer запускаются даже если promo content не допускает UI-copy. Эта ветвь не переходит к analytics/navigation. Copied завершает no-op.

Для другого voucher state нужны nonnull URL и ID; VM вызывает postValue navigation event перед launch tracking coroutine. Это порядок вызовов, не гарантия доставки события или фактической навигации раньше analytics. Empty strings отдельно не отсекаются. Tracking использует captured ID и прежний Braze bridge; Unit не подтверждает SDK delivery.

Timer получает delay=5000 ms, но не гарантирует точный wall-clock срок. После resume он строит target из захваченного исходного DefaultDisplayState и voucher, не из нового текущего состояния. Helper использует CAS retry с фиксированным готовым target, а не пересобирает его из свежего state. Это статическая граница корреляции, не воспроизведённое stale overwrite или доказательство параллельных timers.

Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 4 новых полных тела / 323 DEX units в отдельном com/searchform namespace; прежний bridge / 14 units и 10 helpers / 168 units имеют нулевой повторный credit. Старый глобальный знаменатель не изменён. Проверены исходные identities и байты, общий decoder не является второй независимой декомпиляцией; novelty ограничена локальным корпусом. Отчёт SHA256: a5b8cd9c68c15bd0705b9ea555c0390018c70d58ef2e170d7a1f2d970f39210e.

Для Юлдаша отдельно предлагается определить generation/current-state merge при восстановлении banner и разделять Copied UI, clipboard receipt и валидность скидки. Непроведённые тесты: матрица voucher/current state, новый banner во время delay, сохранённый Available callback и порядок event/analytics. Полная регистрация UI callbacks, clipboard и navigation consumers, lifecycle и серверная валидность voucher остаются открытыми. Реальные коды, URL и clipboard не использовались.

## Go C127 pickup snapshot и экранный выбор точки

Подготовка pickup points запускается при разрешающем virtual guard и ненулевом scope. Низкий zoom может скрыть overlay, но не прекращает сам launch. Image preload не ожидается как guard подготовки. Default tag или null bitmap дают default image supplier с сохранением переданного списка; исключение loader не равно null-bitmap fallback. Фактическая загрузка изображения не доказана.

Commit сначала записывает snapshot, затем вызывает callbacks и очищает очередь только после их нормального завершения. Ошибка callback может оставить уже записанный snapshot и неочищенную очередь. В проверенных try regions CancellationException перекидывается, другие Throwable логируются; локального rollback после записи нет. Runtime partial failure и overlap preparation jobs не воспроизводились.

Выбор работает с экранной дистанцией между ScreenPoint, не географическими метрами. Display cutoff строго zoom>14; threshold зависит от zoom и dimension resources. Среди eligible points выбирается строго меньшая дистанция, поэтому равенство threshold исключается, а равные лучшие расстояния сохраняют первую точку. Дополнительное подтверждение использует distance<1.0 в экранных единицах.

Если одна worldToScreen projection отсутствует, helper возвращает 0. Этот ноль не доказывает совпадение геопозиций; возможность пройти числовой predicate не устанавливает runtime достижимость дефекта. Filter сохраняет порядок и ссылки в отдельном ArrayList, не immutable copy. Итоговый boolean остаётся локальным состоянием, не подтверждением безопасной посадки или доставки водителю.

Независимая bounded static QA: PASS, 6086 механических проверок. 25 полных тел и 2 фрагмента, 517 выбранных records = 501 новый + 16 повторных; 797 исключены. Analytics tail и остальные switch arms не получают credit. Это bounded final report, не frozen checkpoint и не обновление глобального ledger. Отчёт SHA256: a41f2e8cc4e10ec6056c46cf76e7d1a9ae5e9cea407fecb1bfd1388cd22374cb.

Для Юлдаша отдельно предлагается различать unknown projection и нулевую дистанцию, документировать единицы порогов и revision snapshot. Непроведённые тесты: image null/throw, callback failure/cancellation, zoom boundaries, first tie, null projection и порог 1.0. Scope lifecycle, producer semantics, renderer и физическая доступность точек остаются открытыми. Реальные адреса, координаты и сетевые изображения не использовались.

## Яндекс Про создание настройки автовыплат и retry

Проверенный create-result handler для Success выбирает navigation к экрану создания настройки, с отдельным route guard, который может не допустить переход. Error открывает cancel/retry диалог; null message заменяется fallback, empty string сохраняется. Иные response classes, включая Cancelled, заканчивают этот handler без этих success/error действий. Это локальная обработка настройки правила, не подтверждение будущей выплаты или серверного commit.

Retry сначала инициирует закрытие диалога, затем вызывает telemetry и сохранённую create closure. Ни завершение закрытия, ни Future повторного create здесь не ожидаются. Синхронное исключение close dispatch или telemetry может прервать путь до retry. Cancel binding только инициирует закрытие, не отключает правило и не отменяет серверное задание.

Threshold и schedule retry повторно передают исходные captured arguments/references, не текущий текст UI. Это не deep copy и не snapshot сериализованного запроса: прежние wrappers заново парсят строки, а nullable/defaulted параметры могут повторно получить defaults/config. Повтор не доказан байт-в-байт равным первому запросу. Прежний API путь и его idempotency здесь не получают нового кредита; server duplicate rule или двойная выплата не установлены.

Независимая проверка PASS: 4 новых полных dependency-limited тела / 1304 native bytes / 52 прямых перехода. Generic renderer, scheduler, host implementation, async error sink и runtime guards остаются открытыми. Отчёт SHA256: 90a080b7d6308c5b5ac7fa55cd732c46bbd38fec4d8c0786650a10f4ed23324d.

Для Юлдаша отдельно предлагается различать успешную настройку и исполнение выплаты, определить captured intent versus актуальные defaults и reconciliation неизвестного исхода. Непроведённые тесты: Success/Error/Cancelled, изменение UI/config между попытками, close/telemetry/retry failures, timeout после acceptance и поздний callback. API, изменения правил и денежные действия не выполнялись.

## Go C128 повтор nearest lookup и обновление при resume

Обе ветви проверенного NetworkChangesReceiver сходятся в один retry guard, в том числе отрицательный исход transport-проверки. Он требует накопленный error flag, resumed Fragment и отсутствие конкретного блокирующего router-состояния. Transport check не подтверждает валидированный интернет или доступность nearest endpoint.

При разрешении error flag очищается до чтения текущего центра карты и downstream вызова. Запускается новая MAP попытка с текущим центром, reason auto и null pickup object, не replay исходного GEO запроса. Downstream C126 ещё может выйти из-за отсутствия activity/view, уже после потребления флага. Это не гарантия доставки retry или доказанный runtime lockout; последующая IOException может снова поставить flag.

Регистрация receiver условна наличием provider и его результата. Локальное поле receiver может быть присвоено даже без вызова registration helper, поэтому ненулевая ссылка не является receipt регистрации. Аналогично cleanup поля и фактический unregister различаются; полный parent lifecycle и доставка OS events не закрыты.

Отдельный resume путь при разрешающем virtual guard всегда вызывает MAP refresh. Только age>900000 ms предварительно очищает addressInfo и выполняет связанные UI-вызовы; ровно 900000 не очищает, но refresh остаётся. Возраст вычисляется wall-clock subtraction, не monotonic freshness contract. Этот prefix не проверяет и не сбрасывает error flag retry listener.

Независимая bounded static QA: PASS, 1740 механических проверок. 17 полных тел и 2 фрагмента, 217 новых выбранных records, 110 исключены. Это bounded final report, не frozen checkpoint и не обновление глобального ledger. Runtime DI, lifecycle cleanup, server retry и успешность запроса остаются открытыми. Отчёт SHA256: 96c5e7580b1c8f13de8924e5f169ce32df5bc8a05ec1f1aa692d04ace02ac9f9.

Для Юлдаша отдельно предлагается различать retry eligibility, attempted dispatch, started request и успешный ответ, а TTL очистки отделить от обязательности refresh. Непроведённые тесты: оба transport outcomes, flag/resumed/router guards, null downstream view, provider/registration throw и возраст 899999/900000/900001. Реальные адреса, broadcasts и API не использовались.

## BlaBlaCar получатели событий search banner

Подтверждена регистрация event observer с viewLifecycleOwner и конкретная цепочка bridge до Fragment consumer. Узкий onViewCreated tail вызывает регистрацию после предшествующих restore-операций; это не гарантия регистрации при их ошибке, replay или exactly-once доставки. Отдельная observer coroutine и полный renderer здесь не закрыты.

Clipboard consumer при null context или ClipboardManager возвращается без записи. При наличии manager передаёт voucher в newPlainText и setPrimaryClip, без readback, acknowledgement или rollback VM. Поэтому ранее выставленный Copied остаётся оптимистичным состоянием, не receipt платформы. Null-service ветвь не доказывает её фактическую достижимость на устройстве.

Navigation consumer читает только URL события и через helper вызывает BrowserNavigator.launchBrowser; ID в этой ветви не используется, analytics остаётся отдельной coroutine. Вызов не доказывает открытие браузера, доставку analytics или действие человека. Конкретная navigator implementation, URI policy, платформенная успешность и lifecycle replay остаются открытыми.

