Уточнена ранее открытая интерпретация has_last_ride/navigate_back конкретным result consumer. При requestCode 200 и результате -1 только Boolean.TRUE в has_last_ride выбирает локальный PaymentFlowDisplayed; false, missing/null/wrong type и null Intent выбирают PaymentSucceeded. Следовательно, ранее описанный producer -1/true соответствует displayed, а не succeeded. Ни имя локального состояния, ни Android RESULT_OK не являются финансовым подтверждением.

При result 0 typed navigate_back=true вызывает Back dispatcher; остальные значения ведут к setResult(0) и finish. Result 3637 обрабатывается как ошибка лишь при non-null Intent и error String. Переходы displayed/succeeded проверяют первоначальный InProgress до update loop, но не повторяют type guard внутри него: атомарность перехода именно из InProgress при interleaving из этого не следует. Реальная доставка Android result и конкурентный дефект не воспроизводились.

Независимая статическая рецензия обоих пакетов PASS: 1 786 assertions, original DEX identities/register lists и resource requestCode 200 проверены отдельно. Manifest Start 28/28, consumers 6/6 совпали. Start добавляет 15 полных тел и 630 instruction units, включая supporting getters/bridges/tables, а не 15 бизнес-функций; два прежних VM тела исключены из нового кредита. Все семь consumer/producer тел повторно связаны с прежними доказательствами и имеют нулевой новый credit. Глобальные totals не пересчитаны. Исторический следующий пробел Start в consumer-отчёте закрыт этим совместным дополнением, но создание стартового Intent, конкретный launcher/DI, backend null/empty semantics, UUID operation identity и финансовый исход остаются открытыми.

Самостоятельные предложения для Юлдаша: типизированный outcome вместо двусмысленного Boolean; явная schema validation обязательных полей, различение invalid и missing UUID, политика null/empty и mixed-type координат, finite/range проверки на собственной доверенной границе. Account/flow authorization должна быть отдельной от клиентского source enum.

Непроведённые тесты: missing/wrong-type поля, valid/malformed UUID, mixed/all-invalid координаты, NaN/infinity/range, матрицы has_last_ride и navigate_back, повторные и поздние результаты, interleaving между guard и update. Приложение, JVM, SDK и API не запускались; доказательств оплаты или серверного дефекта этот статический разбор не даёт.

## Go C82 получение статуса долга и остановка опроса

Повторный опрос продолжается только для SBP_AWAITING_TOKEN, SBP_AWAITING_USER_INTERACTION и PROCESSING. Остальные значения завершают выбранный цикл; null в accessor состояния становится UNKNOWN и тоже завершает опрос, но не подтверждает оплату. Для продолжения используется delay из ответа либо 5 000 мс при null, затем lifecycle helper. В выбранном теле нет clamp и лимита попыток; реальные интервалы и ограничения транспорта этим не установлены.

Успешные fetch/poll выполняют обработку ответа и mapping, затем запускают отдельную coroutine сохранения без ожидания её завершения. Поэтому возврат fetch не подтверждает применение или показ нового состояния. Ветка ошибки сохраняет текущий enum, очищает DebtFlow и заменяет отдельный список пустым, а не устанавливает NO_DEBT; несколько current-read не образуют доказанный атомарный snapshot. Poll error branch журналирует ошибку, передаёт локальное обновление и выходит без немедленного повтора внутри этого цикла. CancellationException пробрасывается. Границы try у однократного запроса не охватывают всё получение параметров и cast, поэтому не любая ошибка превращается в Result.Failure.

Локальный start guard проверяет активность сохранённого Job, но не доказывает атомарную дедупликацию одновременных стартов. Отдельный эффект после остановки условен по YB_WALLET, Boolean provider и порядковому сравнению локального enum с PROCESSING; его нельзя называть подтверждением погашения. Чтение этого состояния не связано доказанным await с только что запущенной записью ответа.

При состоянии, отличном от PROCESSING, выбранный обработчик очищает локальные ключи active order и прежнего URL, в том числе для UNKNOWN. Для PROCESSING новый непустой URL сначала запоминается, затем передаётся в канал без проверки возвращённого результата. Запоминание или enqueue не подтверждают открытие браузера и платёж; потребитель канала и восстановление доставки остаются открытыми.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 16 полных тел и один ограниченный фрагмент, 571 новая выбранная запись, 0 повторно зачтённых и 54 исключённые записи уведомительного контекста. Report/QA pins и шесть manifest entries совпали. Original DEX identities и байты проверены независимо, но общий decoder не является независимой декомпиляцией; новизна ограничена предоставленными прежними manifests. Global coverage не пересчитан. Transport, parameter provider, десериализация, lifecycle helper, внешний restart, корреляция ответов и финансовый серверный результат не замкнуты.

Самостоятельные предложения для Юлдаша: различать остановку опроса, UNKNOWN, отмену и подтверждённое погашение; применять ответ по payment-attempt id и revision с явным завершением записи; задать single-flight/retry policy и раздельные этапы URL enqueue, приёма, открытия и оплаты.

Восемь групп тестов enum/null, исключений и отмены, delay/lifecycle, поздней записи, error snapshot, одновременного старта, URL delivery и очистки при UNKNOWN только предложены, не выполнены. Runtime race, реальная частота опроса и финансовый исход не доказаны; APK и API не запускались.

## Яндекс Про handler отмены проверки допуска к выплате

Уточнена связь payout API с networkManagerProvider: прежний OrderCore Proxy-контракт нельзя переносить сюда по совпадению dispatch-селекторов. При конкретном SDK receiver и проверенном constructor API получает обычный YXDioRequestCancelHandler. Его ранее изученный create повторно использует существующий неотменённый token для ключа; cancel сначала удаляет ключ, затем отменяет найденный wrapper. Это условный контракт выбранной реализации, не доказательство фактического runtime выбора, single-flight или защиты от устаревшего ответа.

В legacy-ветке handler приходит из controller: constructor сохраняет injected handler, если он передан, иначе создаёт обычный fallback. Происхождение конкретного upstream injected значения не закрыто, поэтому legacy нельзя объявлять всегда generic или всегда Proxy. Provider overrides, scope sharing, нижние эффекты transport cancellation и уже доставленные ответы остаются границами.

Конкретная retry-кнопка Error UI связана с manager.onRefresh; Loading строит progress indicator, а не ту же retry-кнопку. Это уточняет достижимость повторного запроса, но не исключает повторный tap до rebuild, несколько страниц/scopes или запоздалый ответ. Отдельный holder guard не является проверкой generation для всех navigation callbacks. Runtime overlap, гонка, повторный перевод и финансовый дефект не установлены.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 14 новых полных локальных тел, 2 504 native bytes, 109 прямых переходов/вызовов и четыре GDT binding; 14 прежних records имеют нулевой новый credit. GDT сверена с хэшированным static dispatch export, а не заново построена из исполняемого snapshot; PP/class labels зависят от hash-bound Blutter annotations без независимого object-pool decoder. Новизна ограничена корпусом автора, не полным независимым пересмотром истории; глобальное покрытие не пересчитывается.

Самостоятельные предложения для Юлдаша: разделить lifetime владельца, generation операции и transport cancellation; явно выбрать single-flight либо latest-generation. Непроведённые тесты: быстрый повтор retry, две страницы, ответы A/B в обратном порядке, закрытие до/после transport completion и перед навигацией, повторный вход, reusable-token против replace-token. Отмена UI и смена маршрута не должны подменять финансовый статус. APK, API и платежи не запускались.

## Go C83 владелец попытки оплаты и запуск URL

В выбранном теле после нормального возврата transport helper записывается захваченный order id в ACTIVE_ORDER_ID, без проверки содержимого ответа на order/revision/receipt. Запись ограничена пятью типами: SBP_BIND_TOKEN, KASPI, CLICK_WALLET, EASYPAISA_WALLET и JAZZCASH_WALLET. Это локальная привязка будущего URL, не подтверждение платежа. Строка из request provider не доказана как уникальный payment-attempt id; blank order/payment id отводятся от API-ветви, но поведение diagnostic helpers не раскрыто.

При пустом списке отдельный interactor очищает owner и сохранённый URL, отменяя собственный job, а не job sender в этом теле. Отсутствие явной отмены захваченного запроса не доказывает позднюю запись owner после очистки: общие DI instances, lifetime, overlap и cancellation semantics ещё не установлены. Восстановление непустого ACTIVE_ORDER_ID из preferences не доказывает account isolation или durable write.

Найдены входы в startPolling после успешной ветви локального Result, из deeplink и выбранной launch-ветви. Это не банковский outcome и не доказанный автоматический retry после сетевой ошибки. В em90 case0 выполняются callback и возврат через router, а не restart. Default-ветвь вызывает polling, но её creator/UI reachability в независимо проверенной области остаются открытыми. Непроверенные дополнительные wiring-наблюдения не снимают эту границу.

URL-путь доведён до попытки Android startActivity с VIEW. ActivityNotFoundException лишь журналируется, метод возвращает void без acknowledgement или rollback отметки URL; прочие исключения этим catch не охвачены. Ранее установленное сохранение OPENED_URL до отправки и подавление того же URL не подтверждают открытие внешнего приложения или оплату. Producer/consumer совместимы по типам и полям, но тождественность их hd instance и фактическая доставка канала ещё требуют DI/lifecycle проверки.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 18 методов, из них 17 полных и один ограниченный фрагмент; 578 новых выбранных записей включают два switch payload, четыре записи другого discriminator исключены, повторный credit нулевой. Report/QA pins и семь manifest entries совпали. Original DEX identities, байты, SQLite и reference indexes проверены; общий decoder не является второй независимой декомпиляцией. Новизна ограничена предоставленными прежними manifests, global coverage не пересчитан. Transport/response contract, provider-id lifetime и runtime concurrency остаются открытыми.

Самостоятельные предложения для Юлдаша: отдельный attempt id с order/revision и проверкой актуальности перед owner write; явное владение задачами при очистке и смене аккаунта; раздельные enqueue, dispatch и успешное открытие URL с восстанавливаемой ошибкой; single-flight и restart policy, не подменяющие финансовое подтверждение.

Восемь групп тестов входных id, пяти payment types, обратного порядка ответов, очистки при подвешенном запросе, UI reentry, ActivityNotFoundException, смены collector/account и URI policy только предложены. Поздняя запись, гонка, уязвимость и финансовый исход не воспроизведены; APK, API и платежи не запускались.

## BlaBlaCar создание стартового payment Intent

Paywall и dashboard вызывают navigator с default mask 12: flowId и coordinates становятся null, source/type/requestCode сохраняются. Null здесь означает отсутствие переданного идентификатора, а не доказанное создание нового UUID или нового серверного flow. В выбранных callers и двух NavigatorImpl launcher UUID не генерируется; политика repository при null этим дополнением не устанавливается.

Publication использует существующую non-null String extra_flow_id, source PUBLICATION и DRIVER_SUBSCRIPTION. Null список координат остаётся null, пустой становится пустым массивом; остальные элементы последовательно cast/toNav без фильтрации чужих/null элементов, установленной ранее в стартовом consumer. Формат UUID producer не проверяет. markPaymentFlowAsLaunched вызывается только после нормального возврата navigator, но это локальная отметка, не доказательство фактического открытия UI или оплаты.

В новых callers requestCode сначала является raw R$integer resource ID. Ранее проверенный navigation-context helper разрешает его через Resources.getInteger перед Android launch и в Activity-, и во Fragment-ветви: payment соответствует 200, dashboard соответствует 210. Противоречия с прежними consumer-сравнениями нет; старые разделы не требуют исправления. Конкретный runtime receiver/lifecycle этим не подтверждается.

Paywall mappings сохраняют пять source variants, включая OTHER как явное значение, не fallback; DRIVER_FEES преобразуется в FEES, subscription variants соответствуют своим типам. Dashboard фиксирует SUBSCRIPTION_DASHBOARD. Неизвестные switch-коды уходят к error helpers, а NoSuchFieldError guards таблиц не являются бизнес-fallback. Default With применяет только обнуление flowId и не принимает координатный массив.

Независимая проверка PASS: 1 264 assertions, original DEX bindings/bytes/register lists и resource values сверены; финальное пояснение о нижнем helper принято reviewer. 12 новых полных тел, 312 code units: два caller-сценария и supporting dependencies, не 12 бизнес-функций. Шесть прежних DEX и два complete REA тела, а также связующий navigation helper имеют нулевой новый credit. Report/review pins и manifest проверены. Global coverage не пересчитан; server idempotency, repository cache policy, actual navigation, production DI и финансовый исход не доказаны.

Самостоятельные предложения для Юлдаша: явный контракт new/resume вместо предположения null = новая операция; формат UUID валидировать с предсказуемым восстановлением; различать producer validation и consumer filtering. Непроведённые тесты: masks 0/4/8/12, enum mappings, missing/malformed publication arguments, null/empty/mixed координаты, launch failure и фактический resolved requestCode. APK, Android/JVM, API и платежи не запускались.

## BlaBlaCar уточнение кеша flowId

Прежние repository-доказательства уточняют producer null-path: non-null UUID сохраняется, а null не очищает уже сохранённый ID. In-memory getFlowId лениво создаёт UUID только при отсутствии кешированного и возвращает сохранённое значение при следующих чтениях. Отдельный clear очищает ID и контекст; его вызов при каждом выходе не установлен. Поэтому null у paywall/dashboard не означает новый UUID при каждом старте, но кеш не доказывает серверную идемпотентность или сохранность после process death.

Независимая финальная приёмка PASS подтвердила prior REA/DEX bindings и эти узкие утверждения. Новых методов и units нет; прежний structural setter не повышен до нового full review. Самостоятельное предложение для Юлдаша: проверить new/resume/clear/process recreation и связь ID с операцией, отдельно от финансового результата. Эти тесты не выполнены; lifecycle-гарантия и runtime defect не заявляются.

## Яндекс Про уточнение legacy injection и payout override

Закрыт ранее открытый источник handler в конкретной AppScope factory: результат createCancelTokenHandler передаётся в legacy NetworkProvider. По ранее разобранной функции это Proxy с Regular/WeakRef strategy, а не default generic handler. Вывод условен на использование этой factory и original/legacy branch. SDK constructor по-прежнему отдельно создаёт generic handler с переиспользованием неотменённого token; прежний SDK-вывод не отменяется.

Выбранная taxi factory получает network manager, сохраняет его в payout controller, а createContainer передаёт это значение как override networkManagerProvider, который читает payout API factory. Это source-bound factory contract, не доказательство live branch, provider evaluation, единого lifetime или фактического выполнения всей цепи. Replace-token и reuse-token стратегии сами по себе не определяют актуальность ответа; overlap, гонка, банковская отмена и повторная выплата не доказаны.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS: 0 новых full bodies, четыре partial parent bodies, восемь выбранных диапазонов, 788 native bytes и 28 branch/call checks. Сканирования stack slots и дополнительные контекстные чтения имеют нулевой credit; весь объём крупных factories не засчитан. Blutter PP/class/static-provider labels и GDT опираются на хэшированные prior metadata, не свежий snapshot decoder. Проверены report/QA pins и manifest; global coverage не пересчитан. Альтернативные entrypoints, overrides, aliasing/unwind, transport cancellation и runtime scheduling остаются открытыми.

Самостоятельные предложения для Юлдаша: независимо задавать token ownership и generation, проверять override isolation и поздние ответы для обеих token-стратегий; UI spinner не должен быть единственной защитой. Тесты повторного запроса, закрытия/повторного входа и разных feature scopes только предложены. Подписи документов, API-запросы, выплаты и runtime-тесты не выполнялись.

## Go C84 список долгов и агрегация локальных результатов

Перед оплатой busy=true ведёт к раннему возврату. Для external-payment типа и сохранённого ACTIVE_ORDER_ID другая ранняя ветвь запускает polling без новой оплаты и без сравнения owner с переданным списком order ids. Это не доказывает ошибочную оплату чужого заказа или runtime race. Затем список выбирается по приоритету: непустой input, иначе cached list, иначе fetch статуса. Для GOOGLE_PAY и пяти external types непустой первый элемент ограничивает выбранный список одним заказом; сам GooglePay token/UI flow здесь не разобран.

Single path нормальным завершением возвращает Unit; предусмотренные Exception превращаются в Failure, CancellationException пробрасывается. Batch path возвращает первый Failure в порядке полученного списка результатов либо Unit при отсутствии Failure. Это не временной порядок ошибок и не вектор частичных успехов. Unit и отсутствие Failure не являются финансовым ACK, серверной atomic transaction или доказательством погашения всех долгов.

Вызов sbx.t расположен внутри цикла до добавления возвращённого handle. Его тело и нижние coroutine helpers не входят в разбор: список handles не доказывает параллельность платежей, порядок завершения, соответствие порядка результатов входным ids или точную propagation cancellation. Нельзя описывать эту ветвь как доказанный параллельный batch.

Связанный callback очищает busy и cached list после нормального возврата операции, затем запускает прежнюю обработку Result. Это normal-path reset, не finally: исключение или отмена могут не пройти через него. Пустой список отводится в отдельную close-screen lambda, её cleanup не установлен. Внешние writers/lifecycle остаются открытыми, поэтому permanent lockout и залипание не доказаны.

Независимая статическая приёмка PASS: 2 824 проверки; 21 выбранное полное тело, 733 записи = 724 новых + 9 прежних firstOrNull, 46 GooglePay context records исключены. Report/review pins и пять manifest entries совпали. Самостоятельная сверка DEX bindings, байтов, регистров, branch targets и exception tables не является вторым полным дизассемблером или runtime-тестом. Global coverage не пересчитан; финансовые исходы, DI scope, helper scheduling и внешняя очистка не замкнуты.

Самостоятельные предложения для Юлдаша: сохранять per-order/attempt outcome и безопасно сверять partial failure; явно сообщать ограничение одним долгом; связывать restart с active attempt; определять cleanup при normal/error/cancel и политику очередности отдельно от idempotency. Восемь групп тестов busy/owner, приоритета списков, singleton, single exceptions, смешанных исходов, helper cancellation, empty/callback failure и reconciliation лишь предложены. APK, API, платежи и runtime-тесты не запускались.

