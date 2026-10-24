SILENT либо запрещающий локальный predicate направляет в альтернативный канал, а не безусловно запрещает все уведомления. Эта ветка добавляет в HashSet ключ из capturedOrder и Reason(code,text); при новом ключе вызывает notification helper. Она не записывает v. Ключ не доказан как server event ID или orderId; helper return не подтверждает доставку. Add происходит до helper, локального rollback при его ошибке здесь нет, но потеря уведомления не воспроизводилась.

Коды причины выбирают presentation/resources, а не банковский исход. Caller terminal guard из C77 остаётся до scheduling; внутри выбранного callback повторяется size guard, не terminal predicate. Нажатия кнопок, точный queue predicate, доставка, очистка HashSet и финансовый ledger остаются открытыми.

Независимая source/semantic рецензия завершена с редакционными оговорками; два точечных замечания к порядку вызовов и месту создания callback исправлены в финальном отчёте. Final report pin и пять manifest entries совпали. Объём: 10 выбранных методов, 7 полных тел и 3 фрагмента; 508 records = 487 новых выбранных + 21 reuse. Четыре switch-payload records входят в 487. Это не новые бизнес-функции, source LOC или cumulative/global semantic coverage.

Для Юлдаша предлагается разделить Received/Queued/Presented/Acknowledged, пользовательское решение и финансовый outcome; дедуплицировать по eventId/orderId/revision, а не длине массива или тексту. Ошибка UI-доставки не повторяет денежную операцию. Двенадцать сценариев предложены, не выполнены: guards, SILENT, причины, queue duplicates, HashSet, helper failure, terminal/holder смена, обратный порядок commit и restart после save failure. APK, API и SDK не запускались.

# Pro Детализация дохода в telemetry, не в UI

Проверенный consumer ServerRideDetail приводит к MetricaCompleteDataParams, а не к экранным строкам. В этой projection user_details и driver_details оба строятся через один userDetails getter: для Full он читает поле +0x1b. Отдельное входное driver_details в +0x1f здесь не используется. Это локальный кандидат на дефект атрибуции телеметрии, не доказанный дефект денег, начислений или отображения.

Не установлено, различаются ли реальные списки, вызывается ли путь в нужном production-сценарии, доставляется ли telemetry и как её использует получатель. Адаптеры копируют totals/count/unit/time и преобразуют представление, не пересчитывая итог в выбранных телах. Другие валидаторы и сервер не проверены.

Вариант выбирается по CID, а не сохранённой строке type. Unknown String type прежнего Other не переносится в ServerRideDetailParams/его JSON, где ключа type нет. Это потеря discriminator в конкретной telemetry projection, не доказательство потери исходной модели или UI.

Объём: 15 новых full-local dependency-limited тел, 3 852 bytes; прежние parsers/shared getters не зачтены повторно. Report и independent-QA pins, 23 хэша комплекта совпали. Независимая QA получила PASS_WITH_SCOPE_LIMITATIONS: проверены native control/dataflow и 165 branch/call targets, но PP literal/closure labels и CID names опираются на hash-bound Blutter annotations; независимый Dart snapshot/object-pool decoder не запускался. Транзитивные SDK/helpers и runtime не закрыты; новый global credit не применялся.

Собственные требования Юлдаша: явно связывать telemetry поля с customer/driver источником; тестировать проекцию на намеренно разных списках; отдельно проверять UI, telemetry и финансовую валидацию. Lossy projection неизвестного варианта должна быть документирована, metric не должна менять payment status.

Предложены, не выполнены проверки разных customer/driver деталей, unknown type, null/Preview, пустых и смешанных списков, больших count/time, расходящихся totals и смены версии. APK/native target и API не запускались, код Юлдаша не менялся.

# BlaBlaCar Разные return paths подписки

Явный RESULT_OK (-1) → reload dashboard context относится к driver payment flow с requestCode 200, не к subscription dashboard с requestCode 210. Значения связаны с DEX initial resource fields и packaged resources.arsc, а не угаданы по resource ID. Runtime overlay и неизменность mutable static fields этим не доказаны.

В двух рассмотренных result consumers отдельный cancellation-success reload не установлен. Dashboard-specific ветвь MainActivity обрабатывает result 173 с сообщением ошибки. Общий success forwarding зависит от nonnull Intent, тогда как прежний cancellation finish возвращает -1 без Intent payload. Это ограничение конкретных тел, не отсутствие refresh во всём приложении.

Важное дополнение из прежнего full-review PUI-03: PrivateProfile.onStart уже вызывает initObservers и getPrivateProfile. Lifecycle refresh существует и не получает нового credit. Однако actual navigation context, вызов этого onStart после конкретного dashboard return и свежесть subscription snapshot не связаны данным проходом. Нельзя утверждать ни «refresh отсутствует», ни «каждая отмена гарантированно обновляет подписку».

Независимая QA return-пакета: PASS_WITH_SCOPE_LIMITATIONS; report/QA pins и 28 хэшей совпали. Семь новых полных тел, 196 DEX code units; шесть prior-full DEX и один prior REA wrapper переиспользованы без зачёта. Lifecycle-уточнение также zero-credit. Global index не изменён. Class_data и ресурсы проверены отдельно; для instruction rendering использован существующий decoder, exhaustive независимый novelty rescan не проводился.

Для Юлдаша предлагается типизировать payment-return и subscription-return отдельно, явно сверять account/subscription после закрытия независимо от optional Intent и определять lifecycle refresh. RESULT_OK не является финансовым подтверждением. Проверки request 200/210 × result -1/0/173/3637 × null/non-null Intent, UUID, navigation stack и return-to-start лишь предложены, не выполнены. APK, SDK и API не запускались; GitHub и код Юлдаша не менялись.

# Go C79 Диалог смены оплаты: совпадение и две кнопки

Уточнён диалоговый predicate C78: после проверки совместимости runtime-класса он использует gx80.equals, то есть совместное равенство заказа и Reason(code,text), не server event ID. Изменение текста может изменить ключ; фактические дубли этим не доказаны.

Постановка модели допускает очередь при занятом current dialog, deferred путь без Activity и addFirst при null результате factory. Нормальный возврат не подтверждает показ. Поэтому продвижение v из C78 нельзя называть подтверждением просмотра или согласия. Lifecycle drain/cleanup и rendering остаются открытыми.

Positive-кнопка связана с openOrderPaymentMethods(holder). Это открытие выбора, не доказанная смена способа или платёж; timing superclass dismiss callback не установлен. Negative callback только для DEBT_ALLOWED/DEBT_USER устанавливает localData.u=true у текущего заказа и пересчитывает локальный DebtsState. Иные/null коды не выполняют эту запись, независимо от подписи кнопки.

Это один writer флага, который C77 читает вместе с DEBT_ALLOWED. DEBT_USER writer не доказывает наличие DEBT_ALLOWED в списке; u=true и UX-состояния не подтверждают банковский долг, оплату или settlement. Negative action выполняется перед dismiss, исключение callback может прервать закрытие.

Независимая семантическая рецензия PASS; редакционное уточнение provenance C60/C73/C78 внесено, report/review pins и пять manifest entries совпали. 29 выбранных методов: 22 полных тела и 7 фрагментов, 396 records = 357 новых + 39 reuse. Raw 2 814 records и несвязанные merged cases не считаются изученными целиком; payload records не бизнес-сценарии. Global ledger не пересчитан.

Собственные требования Юлдаша: различать queued/deferred/presented/acknowledged и финансовый outcome, связывать решение с event/order/revision, не текстом. Picker и defer decision должны быть отдельными командами. Двенадцать сценариев type/equality guards, queue, кнопок, разных reasons, повторного callback, исключений, смены holder и restart лишь предложены, не выполнены. APK/API не запускались, код Юлдаша не менялся.

# Pro Constructor UI: отдельные driver/user секции

Новая фактическая UI-цепочка использует price_details_ui, а не предыдущую telemetry projection. Driver и user извлекаются из разных полей (+7/+b), передают собственные headers и item lists. Найденное ранее telemetry-дублирование user→driver на эту ветку не переносится.

Header содержит обязательный String title и nullable subtitle: title передаётся в Text, subtitle в YXListItem. В выбранных телах нет пересчёта заголовка из числовых totals или валютного форматтера. При обоих headers строятся user header/items, затем driver header/items; без user-header кнопка раскрытия может находиться в driver-header. Это display-контракт, не финансовое подтверждение.

Items передаются общему UiConstructorFactory.buildList. Wrapper не суммирует детали, но generic UiItem parsers/renderers не исследованы транзитивно: нельзя объявлять отсутствие нормализации во всех строках. Реальное расхождение UI с начислениями, корректность денег и backend settlement не установлены.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS; report/QA pins и 15 хэшей совпали. Девять новых full-local dependency-limited тел, 6 384 bytes, 293 branch/call targets проверены. Critical native loads/stores/call targets сверены независимо, но object-pool имена/классы/functions опираются на hash-bound Blutter annotations, без нового independent snapshot decoder. Novelty ограничена авторской выборкой; global coverage не пересчитана.

Открыты lifecycle, весь Page routing, другие redesign/offline ветви, generic items, реальные payload/скриншоты и сервер. Для Юлдаша предложены отдельные UI/telemetry тесты на различающихся секциях, null/empty headers/items, локалях и шрифте, расхождении чисел/строк и обновлении раскрытой секции. Они не выполнены. APK/native target и API не запускались; код Юлдаша не менялся.

# BlaBlaCar Локальные исходы DriverPaymentFlow

Concrete consumer различает Success, который открывает success screen, и SuccessfulEndOfFlow, который завершает Activity через RESULT_OK. StartTrial, PayAsYouGo, SubscribeLater и PublishLastRide сходятся в один результат: -1 с Intent has_last_ride=true. Поэтому этот Boolean не доказывает наличие опубликованной поездки или состоявшуюся оплату.

Canceled возвращает 0 без Intent. CancelAndNavigateBack возвращает 0 с navigate_back=true: это result для caller, не непосредственный вызов Back в изученном теле. ErrorEndOfFlow возвращает 3637 со строковым error message. Переход к subscription dashboard сначала вызывает launcher и затем завершает текущий payment Activity, не ожидая результата dashboard.

Установлены локальный dispatch и result payloads, но не финансовый outcome, refund, server cancellation или успешная навигация. Регистрация observer не гарантирует once-only delivery и отсутствие повторного finish. Concrete caller интерпретация has_last_ride/navigate_back, runtime DI, navigation defaults и display helpers остаются открытыми.

Независимая QA PASS_WITH_SCOPE_LIMITATIONS; report/QA pins и 32 хэша совпали. 19 новых полных тел, 439 DEX code units, плюс 3 prior-full zero-credit. Это один bounded сценарий, не 19 новых функций; global index не изменён. Использован общий decoder с независимой class_data/raw hash/string-pool сверкой, без exhaustive независимого поиска всей альтернативной истории чтений.

Собственные требования Юлдаша: разделить screen-success, flow-completed, trial/deferred payment и settlement; типизировать result вместо неоднозначного Boolean, привязать его к flow/account/operation. Предложены, не выполнены тесты всех вариантов, четырёх одинаковых success payloads, cancel/back, error code/message, navigation exception и повторной delivery/recreation. APK, JVM, SDK и API не запускались, GitHub и код Юлдаша не менялись.

# Go C80 Альтернативное уведомление: posting не receipt

Статически прослежен путь до Android notification API: notify либо side-channel queue. Это вызовы в bytecode, не выполненные во время аудита. Возврат Notification/notify не доказывает показ, прочтение или финансовый результат.

Child и summary отправляются последовательными попытками; возвращается первый объект. Между попытками не доказана транзакция или rollback, а успешный возврат первого объекта не удостоверяет два видимых уведомления. Permissions, channels и OS scheduling не проверены.

Для выбранного пути свежий NotificationShareData builder получает только ID, а summary ещё tag. Build требует другие nonnull поля и возвращает null; потому выбранный generic SHOWN block пропускается. Само имя SHOWN нельзя использовать как receipt этого локального события.

HashSet h создаётся пустым в constructor. Direct field-reference graph содержит constructor write и прежний callback read/add; прямой clear/remove/restore для поля не найден. Это не доказательство отсутствия indirect/reflection lifecycle: DI lifetime, reset и persistence остаются открытыми.

Различаются composite HashSet key(order,Reason), platform integer ID=hash(orderId)+type.id и PendingIntent requestCode. Они не являются доказанным server revision/financial operation ID. Synthetic mask задаёт long=0 и finalBoolean=false; object i=null задаётся безусловно, не mask.

Независимая semantic review PASS, редакционное уточнение mask/null внесено. Report/review pins и шесть manifest entries совпали. 28 выбранных методов, 27 полных тел и 1 фрагмент, 490 новых selected records; raw 616 и служебные/triage records не приравнены к новому охвату. Это не независимый disassembler, runtime или whole-app аудит; global ledger не пересчитан.

Для Юлдаша предлагается отдельный delivery ledger с eventId/orderId/revision, retryable failure и explicit cleanup. Queued/posted/shown/opened/acknowledged и деньги должны быть разными состояниями. Десять сценариев duplicate/recreation, ID collision, failure после add, partial child/summary posting, blocked channel, null ShareData, cleanup и stale PendingIntent лишь предложены, не выполнены. APK/API не запускались; GitHub и код Юлдаша не менялись.

# Go C81 Автовосстановление прежнего payment reference

Gated save-путь при переходе прежний тип != CASH → новый CASH и локальном DEBT/PROCESSING сохраняет прежний reference. Оба references должны быть nonnull, но unknown/null enum старого reference тоже не равен CASH: это не обязательно распознанный безналичный способ. Save читает debt-state после await gate; restore использует state, captured до await.

Restore при своём локальном gate читает сохранённую строку, декодирует и вызывает прежний C50 selection helper. Перед применением в выбранном обработчике нет повторного current-selection/CASH/revision/age guard. Это ограниченное наблюдение, не доказанный нежелательный restore или отсутствие проверок ниже. Целью является local default/preorder contract, не доказанная смена оплаты активной поездки.

Absent string даёт no-op. Decoded null возвращается без удаления; nonnull reference после нормального local apply ведёт к удалению ключа. Catch охватывает decode/cast, не первоначальное чтение, selection helper или remove. Decode error логируется и затем удаляет запись, но ошибка logger может прервать delete; ошибка apply не достигает следующего remove. SharedPreferences apply не является подтверждением durable записи.

NO_DEBT и sentinel-equality разрешают локальную ветку, но не являются банковским ACK. Runtime feature value, collector timing, codec config, storage namespace/durability и authoritative финансовый outcome остаются открытыми.

Независимая semantic/raw проверка PASS, 2 350 assertions; report/review pins и пять manifest entries совпали. 26 selected full bodies, 600 records = 475 новых +125 C50 reuse; 29 context-only records исключены. Это не бизнес-сценарии, полный второй disassembler или cumulative/global coverage. Реестры не пересчитаны.

Для Юлдаша предлагается RestoreCandidate с account/selection revision и приоритетом последующего явного выбора; после await нужна повторная eligibility/snapshot проверка. Saved/DecodeFailed/Applied/Cleared и финансовый outcome должны быть раздельны. Двенадцать сценариев gates, переходов, unknown enum, overwrite, смены выбора, decode/null/error, partial apply и restart лишь предложены, не выполнены. APK/API/SDK не запускались, GitHub и код Юлдаша не менялись.

## Яндекс Про допуск к мгновенной выплате

В проверенной цепочке ответ Ok ведёт к маршруту payout, а SignOffers к signDocument. Первый означает команду открыть экран выплаты, не перевод денег; второй открывает документы, не подтверждает подпись или согласие. OpenDeeplink сначала вызывает закрытие текущего маршрута, затем launch без ожидания результата в выбранной обёртке: успешное открытие ссылки не установлено.

Dispatcher обычного JSON различает четыре варианта ответа; неизвестный, отсутствующий, null или нетекстовый discriminator ведёт к исключению, а не автоматически к Failure. В проверенном обработчике Dio отмена пропускает analytics.error, после чего создаётся Failure; другие подходящие Dio-ошибки сначала регистрируются. За пределами проверенного диапазона типов исключение пробрасывается. Таблица защищённых exception ranges не восстановлена, поэтому обработчик не считается универсальным перехватом ошибок всей async-цепочки.

Refresh включает Loading и запускает проверку. Между await и обработкой ответа в выбранном теле manager нет повторной проверки поколения запроса или disposed. При закрытии страницы вызывается отмена по тому же ключу запроса; Error и Loading отдельно защищены boolean-флагом holder. Конкретный request handler, порядок уничтожения holder и эффект отмены не установлены: гонка, навигация после закрытия и двойная выплата не доказаны.

Независимая проверка PASS_WITH_SCOPE_LIMITATIONS подтвердила 25 полных локальных source bindings, 3 200 native bytes и 168 прямых переходов/вызовов; 32 записи manifest совпали. Имена PP literals, enum и классов зависят от связанных хэшами Blutter metadata, независимый decoder object pool не запускался. Новизна опирается на ограниченный корпус автора, а не независимый полный пересмотр истории. Эти числа не увеличивают автоматически глобальное семантическое покрытие. Backend eligibility, фактический перевод, provider ACK и settlement не проверены.

Самостоятельные предложения для Юлдаша: разделить допуск, открытие UI, принятие операции сервером и подтверждение банка; определить single-flight либо generation policy для refresh и разрешённые цели deeplink с fallback. Мгновенные выплаты не становятся обязательной функцией базовой попутки только из-за этого разбора.

Непроведённые тесты: ответы A/B в обратном порядке, закрытие страницы между запросом и ответом, отмена на разных стадиях, неизвестный discriminator, отсутствие данных, parse exception и неудачный launch. Это тест-план, не воспроизведённые дефекты. Приложение, API и платёжные операции не запускались.

## BlaBlaCar стартовый контекст оплаты и чтение результата

Стартовый Intent требует source и monetization_type конкретных Nav-типов. Missing/null/wrong type направляются в отдельные error helpers, не к VM.sendStartStep из этих ветвей; точное восстановление после ошибок helpers не установлено. Двенадцать source variants преобразуются в одноимённые значения; OTHER является явным вариантом, не fallback. FEES преобразуется в DRIVER_FEES, два subscription-типа сохраняют соответствие; неизвестное switch-значение не становится тарифом по умолчанию.

Optional flow_id принимается только как String: отсутствие или иной тип дают null UUID, а непустой либо пустой String передаётся UUID.fromString. Неверный формат не заменяется null или новым UUID в этом Activity; локального catch нет. Это не доказательство общей crash policy или серверной идемпотентности. Coordinates различают null и empty: неверный контейнер даёт null; из Parcelable[] сохраняются только элементы нужного типа в прежнем порядке, поэтому all-invalid массив даёт пустой массив. В выбранных mapper/getter/constructor тела double latitude/longitude переносятся без finite/range проверки; валидация других границ не исключается.

