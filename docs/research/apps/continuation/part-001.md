Продолжение ограниченного статического исследования BlaBlaCar, Яндекс Про и Go. [Основной документ и прежняя история](https://chatgpt.com/space/page_1c4aeb6e3b748191883bd4dcf104e5dd) сохранены без изменения: контрольная версия seq152, 2619 блоков. Здесь только следующие проверенные результаты; это не полный аудит APK, runtime или серверов. Собственные требования Юлдаша и непроведённые тесты отделены от установленных клиентских фактов.

# Яндекс Про Ввод суммы пополнения

Ограниченная статическая проверка: 7 новых полных тел на 1604 байта и 64 прямых перехода; отдельно 2 диапазона одного partial screen на 180 байт и 3 перехода. Независимая QA PASS. Прежние MoneyInput/controller/formatter и interactor/calculation имеют нулевой повторный кредит; весь screen и нижний input pipeline не объявлены покрытыми.

Callback изменения читает текущий controller.value.text в момент выполнения, не String аргумент framework и не snapshot builder. Clear сначала вызывает controller.clear, затем явно передаёт пустую строку в VM. VM передаёт строку без локального trim/parsing/rounding в прежний interactor; его текущий initialization guard ограничивает calculate, но находится после записи controller. Количество listener callbacks и exactly-once этим порядком не доказаны.

Конкретный State создаёт controller без mask/validator параметров. В проверенной начальной конфигурации null-mask fast path общего formatter возвращает входной TextEditingValue без преобразования текста; наличие default regex filter не превращает этот путь в цифровой фильтр. Это уточняет прежнюю открытую границу recommendation: при сохранении конфигурации setMaskText(raw) не применяет payout-маску из max_amount. Возможные последующие изменения mask и нижняя обработка ввода не исключены.

Currency и formatted limits передаются для отображения, не как numeric max или validator. Error title зависит от null, а не от пустоты строки. Общий widget при initialization убирает change adapter и ограничивает clear UI, но это не гарантия всех gesture/keyboard/accessibility путей. Typing читает текущий controller text, recommendation передаёт исходный raw после программной записи, clear передаёт literal empty; calculation action и init amount_data остаются отдельными уровнями.

Самостоятельные предложения для Юлдаша: явно определить денежный формат и согласованность controller, callback и server calculation. Непроведённые тесты: locale/пробелы/Unicode/paste/IME, clear при разных busy states, listener order, recommendation raw против label и смена preview при сохранённом State. AOT/PP bindings проверены статически; runtime, поздние мутации, серверное принятие суммы и зачисленный баланс открыты. Приложение, API и реальные денежные операции не запускались.

# BlaBlaCar Возврат проверки банковского метода

Ограниченная статическая проверка: 4 новых полных business тела на 195 DEX instruction units. Observer bindings, прежняя navigation implementation и helpers отдельно имеют нулевой новый кредит; namespace и глобальный знаменатель не меняются. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: исходные DEX identities/bytes проверены, shared decoder не является второй независимой декомпиляцией.

SuccessEvent сначала задаёт кнопке SUCCESS. При endFlow=true вызываются setResult(-1) без Intent payload и finish, затем условно открываются сведения о банковском методе. При endFlow=false вызывается phone/IBAN validation contract. Последующий onActivityResult сначала вызывает super; только совпадение requestCode с разрешённым через Resources.getInteger кодом и resultCode=-1 вызывает текущий VM.pinCheckSuccess. Остальные результаты задают DEFAULT, а result Intent локально не читается.

Прежний pinCheckSuccess берёт текущую VM форму и literal true, не повторяет server ACTIVE и не выполняет PIN-проверку сам. Это отличает его от предыдущего producer, использовавшего банковский объект ответа. При non-null Intent отсутствующий info extra имеет default true; null Intent не является безопасным fallback false. Достижимость null или stale VM, повторная доставка result, runtime DI и успешный запуск после finish не установлены.

Самостоятельные предложения для Юлдаша: явно разделить навигационный результат, подтверждение владельца и серверную активацию; определить snapshot policy после validation. Непроведённые тесты: endFlow×info, request/result matrix, extra missing/true/false, восстановление VM и поздний result. Событие и RESULT_OK не подтверждают фактическую PIN-проверку, владение, сохранение счёта или перевод. Downstream VerifyPhone producer и lifecycle открыты; реальные PIN/реквизиты, API и APK не использовались.

# Яндекс Про Текст баланса и повторная загрузка формы

Ограниченная статическая проверка: 6 новых полных тел, 1752 байта и 60 прямых переходов; отдельно partial screen на 172 байта и 7 переходов. Независимая QA PASS. Прежние DTO/map/reset/load и общие UI helpers имеют нулевой повторный кредит, весь screen не засчитан.

Balance section и её spacer добавляются только при non-null balance_info; пустые строки внутри объекта этот guard не исключает. Title/subtitle копируются в текстовый slot без вычисления before+topup или проверки ledger. Отдельный локализованный заголовок секции не является balance_info.title. Эти данные описывают preview presentation, а не доказанный доступный или зачисленный баланс.

Проверенная ErrorScreen кнопка связана с captured VM.retry и далее с _loadPreview. Этот путь сбрасывает calculation/personal state и повторно загружает preview; он отличается от прежнего init-error dialog callback, вызывающего onTopupTap. Поэтому recovery после calculation Error может перезагрузить preview, а не только повторить последнюю введённую сумму. Сохранение пользовательских edits не гарантируется.

Новый retry сам не передаёт amount, transaction ID или payment status; отсутствие собственного generation/de-dup guard не означает отсутствие защиты ниже. Runtime доставка tap, scope/lifecycle и отмена поздних ответов открыты. Для result-page manager доступны metadata без method bodies: это не доказательство отсутствия polling или reconciliation. Связь web close с авторитетным обновлением баланса данным пакетом не закрыта; интерпретация PP/CID остаётся статической.

Самостоятельные предложения для Юлдаша: отделить preview text от финансового статуса и различать preview retry и повтор init. Непроведённые тесты: null/empty/long balance strings, stale preview, initial против calculation Error, reset input/personal/action, повторный tap и смена provider/scope, отдельная сверка после web close. Приложение, API и реальные балансы не использовались; исчезновение ошибки и новый текст не являются банковским подтверждением.

# BlaBlaCar Добавление PayPal в transfer method flow

Ограниченная статическая проверка: одно новое полное coroutine body на 234 instruction units; 3 прежних тела на 67 units и 9 helpers на 40 units имеют нулевой повторный кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: исходные DEX identities/bytes проверены, shared decoder не является второй независимой декомпиляцией. Namespace и глобальный знаменатель не меняются.

Constructor захватывает email и VM. На label0 coroutine создаёт input entity с favorite=true/status=null, сохраняет его в VM и перечитывает перед первым suspension. Это отличается от банковского wrapper, заранее записывавшего общую форму. Локального trim/format/pending guard в выбранном wrapper нет, но UI validation и достижимость повторного submit не установлены; гонка или смешение аккаунтов не доказаны.

Только непустой ApiV3 violations список даёт FormErrorState с первым message, без фильтра по key и без собственного generic fallback для null/blank этой violation. Null/empty violations и любая non-ApiV3 Failure идут в общий ErrorState, где null/blank message заменяется generic текстом. Это отличается от проверенной банковской ветви: единую error policy для двух экранов предполагать нельзя.

Success сравнивает response status с ACTIVE и строит navigation payload из response PayPal account. Ответ не записывается обратно в VM input entity. Прежний pinCheckSuccess использует текущую VM entity и literal true, а не повторную PIN-проверку или server ACTIVE. Null/unknown Result и resume exceptions не имеют локального try/catch преобразования в этом теле. Consumer, repository exception policy и lifecycle остаются открыты.

Самостоятельные предложения для Юлдаша: определить error matrix и источник nav после validation, отдельно проверять серверную активацию и владение. Непроведённые тесты: violations null/empty/multiple, разные null/blank сообщения двух ветвей, ACTIVE/иной/null status, response email против input и repeated-submit UI. Событие не подтверждает PIN, владение PayPal, сохранение счёта или перевод. Реальные email/аккаунты, API, APK и финансовые операции не использовались.

# Яндекс Go C112 Активность общего способа оплаты и недоступная группа

Ограниченный final report, не frozen checkpoint: 12 полных тел и 4 фрагмента, 217 новых выбранных records; 1765 raw records исключены. Независимая QA PASS. Структурные подписи и прежний контекст не получают повторного кредита, глобальное покрытие не пересчитывается.

В выбранном memberAccounts mapping каждый элемент передаётся mapper без фильтра по isActive или role. isActive определяет description: active использует description, inactive использует errorDescription либо fallback при blank. Отдельный Availability helper задаёт disabled text: null/разрешающий флаг даёт null, запрещающий флаг с отсутствующей причиной даёт non-null пустую строку. Эти два сигнала независимы; совпадение ID из C111 их не заменяет.

Для элемента, дошедшего до UNAVAILABLE branch, inactive shared wrapper извлекается из рабочего списка в отдельную группу. Active продолжает к ea5.e: это абстрактный virtual method класса, не interface method; конкретная реализация в этом пакете не установлена. Поэтому active не означает безусловную доступность. Caller сначала обрабатывает SELECTED_OPTION_UNAVAILABLE, затем общую группу на том же списке: не всякий inactive обязательно окажется именно в общей группе. removeAll меняет локальный список, не серверный аккаунт или selected payment reference.

Самостоятельные требования Юлдаша: разделить membership, active, disabled reason и финансовую авторизацию; определить null/empty reason и приоритет выбранного недоступного способа, повторять проверку на action/server границе. Непроведённые тесты: комбинации active/Availability, owner/member, selected priority, empty/nonempty group и разные исходы predicate. UI tap enforcement, concrete binding, свежесть response, доставка C110 event и серверное разрешение платежа остаются открыты. Реальные аккаунты, API, runtime и платежи не использовались.

# Яндекс Go C113 Приоритет выбранного недоступного способа

Ограниченный final report, не frozen checkpoint: 4 полных тела и 3 фрагмента, 265 выбранных records = 249 новых + 16 повторных; 385 raw records исключены. Независимая QA PASS. Прежний C112 caller и общий контекст не получают нового кредита, глобальный ledger не пересчитывается.

Конкретный constructor связывает MENU при config.i=false с domain/d, остальные случаи с domain/m. d.e всегда true; m.e допускает wrapper status D либо oia0, остальные отвергает. Это не чтение disabled text и не финансовая eligibility. Общий C112 consumer отдельно проверяет isActive. Grouping strategy выбирается отдельно: нельзя переносить этот predicate на все MENU/RIDE pipelines или считать глобальную DI установленной.

SELECTED_OPTION_UNAVAILABLE при MENU пропускает extraction. В проверенном non-MENU проходе SHARED сравнивается по primary ID, не по полному type+ID+actor/revision. При совпадении status!=D ведёт к extraction; для D требуется дополнительный c() check, а узкий sentinel path exact domain/m возвращает значение, исключающее extraction. Подходящие дубликаты сами по себе не устраняются. Null comparison route не доказывает достижимость null SHARED ID или реальных межтиповых коллизий.

Проверенный caller сначала извлекает selected-unavailable и мутирует рабочий список, затем передаёт остаток общей UNAVAILABLE группе. Альтернативный RIDE pipeline и полный status producer не закрыты. Эта классификация не изменяет серверный аккаунт, не подтверждает action blocking, отзыв прав или оплату; доставка C110 event, свежесть и корреляция ответа остаются открыты.

Самостоятельные предложения для Юлдаша: согласовать policies экранов, определить состав selected identity и приоритет причин недоступности, повторять eligibility на action/server границе. Непроведённые тесты: MENU/config/RIDE matrix, D/oia0/прочие статусы, primary ID и дубликаты, null target, selected extraction перед общей группой. API, runtime, реальные аккаунты и платежи не использовались.

# Яндекс Про Контракт экрана результата пополнения

Ограниченная статическая проверка: 10 новых полных тел на 3008 байт; отдельно 4 partial метода, 5 диапазонов на 508 байт. Независимая QA PASS. Полные V1 API/repository/request/error lifecycle не засчитаны; generic navigation/DI и прежние V2 anchors имеют нулевой повторный кредит.

Найденный конкретный producer относится к V1 topup, не доказанному продолжению V2 web close. Union decoder различает screen, web_link, deeplink и pcidss_topup_id; неизвестный/отсутствующий type бросает исключение. Screen требует heading/text/button как строки без локального запрета пустоты. Эти поля копируются через domain и navigation в текст и кнопку; renderer не определяет success/error по отдельному банковскому статусу и не пересчитывает баланс. Смысл серверного текста этим не подтверждён и не опровергнут.

В выбранной screen ветви close предшествует open без await между ними; внешний await относится к локальному helper, не к доказанному закрытию нового route. successInitTopup telemetry происходит ещё до открытия экрана, поэтому не подтверждает показ или зачисление. Concrete host push/replace и effects остаются открыты.

Кнопка результата читает manager через provider и вызывает доступное общее compiled closePage body. Factory связывает его поле с HostNavigationManager: отсутствие отдельного ASM тела у result manager не означает отсутствие close-реализации. Имя другого класса в аннотации shared body не доказывает иной бизнес-сценарий. Дальнейший host dispatch, overrides, route observers и refresh баланса не замкнуты; отсутствие локального balance/status call не означает отсутствие reconciliation во всём приложении.

Самостоятельные предложения для Юлдаша: разделить server presentation payload, route shown, init completion и авторитетный финансовый статус; разнести V1 screen, web/deeplink и V2 ожидания. Непроведённые тесты: union/type/null/empty строки, передача трёх полей, close→open ordering, effective provider и host effects, версии UI и поздний финансовый outcome. AOT/PP bindings проверены статически; runtime, полный exception lifecycle, idempotency и зачисление не доказаны. API, приложение и реальные деньги не использовались.

# BlaBlaCar Ввод и действия экрана AddPaypal

Ограниченная статическая проверка: 9 новых business тел на 326 instruction units. Прежний inputChanged, observer bindings и helpers отдельно имеют нулевой новый кредит; независимая QA PASS_WITH_SCOPE_LIMITATIONS. Исходные DEX identities/bytes проверены, shared decoder не является второй независимой декомпиляцией; generic dispatcher вне выбранных ветвей и глобальное покрытие не расширяются.

Реальный watcher передаёт ввод в прежний inputChanged: null/blank даёт false, nonblank true. LoadedState управляет VISIBLE/GONE кнопки; listener устанавливается независимо от этого флага. Это условие видимости, не email-format validation и не доказанный обход скрытой кнопки. Проверенный click очищает ошибку, задаёт LOADING, затем читает текущий Input.getText и вызывает addPaypal. Локальный callback не повторяет blank/pending guard, но блокировка повторного нажатия общим widget не исследована.

Error и FormError возвращают DEFAULT и показывают соответственно общее сообщение или ошибку поля, не заменяя введённый текст. Success всегда задаёт SUCCESS: endFlow=true ведёт к RESULT_OK без Intent payload, finish и условному info; false вызывает phone/PayPal validation contract. Отсутствующий info extra при non-null Intent имеет default true, а null Intent не является безопасным false fallback.

Только совпавший разрешённый resource request code вместе с resultCode=-1 вызывает прежний pinCheckSuccess; остальные сочетания задают DEFAULT. Result Intent локально не читается. Это замыкает конкретный caller к текущей VM input entity, но не доказывает её runtime изменение, PIN-проверку или владение PayPal. Числовое значение PIN request code не предполагается.

Самостоятельные предложения для Юлдаша: отдельно задать видимость, формат, pending interaction и серверную eligibility. Непроведённые тесты: null/blank/nonblank ввод, порядок click и snapshot email, поверхности ошибок, endFlow×info, request/result matrix, repeated click и lifecycle return. Runtime widget enforcement, DI/navigation, серверное сохранение и перевод открыты; реальные email/PIN/аккаунты, API и APK не использовались.

# Яндекс Go C114 Локальная цена Auction и состояния кнопок

Ограниченный final report, не frozen checkpoint: 23 полных тела и 2 фрагмента, 461 новый выбранный record; 225 raw records исключены. Независимая статическая QA PASS, хэши комплекта сверены. Это не новый полный аудит, глобальный ledger не пересчитывается.

Выбранная цена хранится в instance map по паре verticalId и второго строкового поля тарифа; его смысл не расширяется до server tariff revision. Отсутствующее значение возвращает default без вставки. Изменение AuctionInfo по equality четырёх числовых полей и displaySettings сбрасывает цену к initialPrice; при равенстве цена сохраняется. Оба пути записывают state и пытаются отправить notification. Это не receipt доставки, disk durability или атомарный read-modify-write.

Уменьшение ограничено снизу minPrice, увеличение сверху maxPrice. Это односторонние clamps, не полная нормализация некорректного диапазона. Analytics выполняется до вычисления и записи. Сам handler не проверяет loading/enabled, finite числа, положительный step или равенство captured и текущего AuctionInfo; достижимость некорректных данных и обход UI не доказаны. Callback захватывает тарифный объект, но при нажатии снова читает map по его ключу.

LOADED с допустимым входным info строит UI из сохранённого state; кнопки enabled только при возможности полного следующего шага. Conditional display settings выбираются по первой удовлетворяющей записи списка, не по максимальному порогу. Non-LOADED с сохранённым info даёт loading shell, обе кнопки disabled и callback=null; sentinel-ветви скрывают view. Enabled действительно передаётся в View.setEnabled, null listener и detach очищают callbacks, но hidden сам не вызывает detach. Порядок reset/composer, debounce и полный lifecycle остаются открыты.

Самостоятельные требования Юлдаша: разделить предложенную цену, принятую серверную quote и финансовую сумму; определить денежный формат, server bounds/revision/expiry и повторную проверку handler. Непроведённые тесты: reset при смене ключа/info, края диапазона и некорректные числа, порядок порогов, loading/hidden/detach, исключение analytics и повторные события. Request consumer выбранной цены ещё не проверен; server auction, рыночный минимум, quote lock, назначение водителя, списание и runtime race не установлены. APK, API, реальные цены и платежи не использовались.

# BlaBlaCar Локальная конфигурация поиска и fallback

