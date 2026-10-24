В decoder нижние value/formatted обязательны как строки, верхние nullable; числовая согласованность не проверяется этим decoder. Upper используется для маски из символов # по длине integer string, в обычном положительном finite случае по числу цифр. Это длина ввода, не numeric clamp, не amount<=upper и не денежное округление. Parsed lower локально не используется далее для numeric gate, но отсутствие min validation в других consumers этим не доказано.

При неизменной маске controller сохраняется; при изменении dispose старого предшествует чтению прежнего text и созданию нового с initialText/mask. Это не доказанный use-after-dispose, не гарантия сохранения selection/listeners. Ошибка между последовательными holder updates не означает автоматически rollback; полные EH regions и runtime достижимость частичного состояния открыты.

Самостоятельные требования Юлдаша: определить initial validation timing, duplicate-id policy, сохранение edits при preview и отдельную числовую eligibility. Непроведённые тесты: required/regex/empty defaults, initial callback до submit, дубликаты, длина маски против upper, parser edge cases, controller rebind и ошибки после первого update. Runtime bypass, exactly-once, серверное принятие и credited balance не установлены. APK, API и реальные реквизиты/суммы не использовались.

# Яндекс Go C118 Разрешённые интервалы и календарная проекция

Ограниченный final report с независимой bounded QA PASS, не frozen checkpoint: 3 полных тела и 2 фрагмента, 386 выбранных records = 380 новых + 6 консервативно zero-credit из прежнего context-only. Эти 6 не являются ранее зачтённым selected semantic coverage; 188 raw records исключены. True-arm другого timetable, ASAP appearance и полный state/confirm consumer не включены, глобальный ledger не пересчитывается.

Конкретный caller выбирает первый AllowedTimeInfo по nullable строковому ключу текущего тарифа, иначе singleton fallback, и передаёт false в проверенную ветвь генератора. Положительный step сохраняется, иначе используется 5; timezone берётся из provider либо default. Null endpoints пропускаются, после нормализации остаются только end>start. Сортировки/merge/dedup здесь нет; округление start/end имеет отдельную арифметику, milliseconds не объявлены обнулёнными, overflow и произвольные шаги не признаны безопасными.

Допустимые интервалы и отображаемые days хранятся раздельно. При пустых интервалах строится synthetic projection от локального now на два дня, но эта пара не добавляется к разрешённым интервалам. Поэтому непустые колёса picker не означают доступность; отдельный predicate C116 продолжает проверять интервалы.

В выбранном group builder end проверяется только перед новым днём. Внутренние циклы продолжаются до смены числового часа/дня, поэтому при обычном advancing step последняя дневная проекция может выходить за end. Это не разрешение подтвердить запрещённое время. Каждая фактически добавленная группа normal path содержит хотя бы одну вложенную запись; такой invariant не распространён на все альтернативные producers. Termination, DST и Calendar normalization для произвольного шага не доказаны.

Самостоятельные требования Юлдаша: отделить display projection от authoritative availability, ограничить step/iterations и задать rounding/zone policy. Непроведённые тесты: empty ranges, null/reversed/equal endpoints, end посреди дня, seconds/millis, overlapping ranges и DST/границы года. Полная confirm-связь, server freshness, booking acceptance, резерв водителя и отсутствие runtime зависания остаются открыты. APK, API и реальные пользовательские даты не использовались.

# Яндекс Go C119 Time-only picker и локальный результат OK

Ограниченный final report с независимой bounded QA PASS, не frozen checkpoint: 12 полных тел и один фрагмент, 231 новый выбранный record; 477 raw records исключены. Общий result sink уже проверен ранее и имеет нулевой новый кредит. Другие router arms, configure/framework и downstream subscriber не включены; глобальный ledger не пересчитывается.

Этот modal выбирает время, а не подтверждает доступность scheduled order. Constructor клонирует входной Calendar и создаёт часы 0–23 и минуты 0–59 на его основе. Явно меняются только hour/minute поля, поэтому labels не гарантируют уникальность или допустимость instant на DST границе. Synthetic interval начинается с исходного instant и заканчивается add(day,1)−1 ms, не midnight-to-midnight и не гарантированными 24 часами.

Listener присваивает Calendar выбранному state без дополнительного clone. OK упаковывает текущую ссылку в in8 с discriminator=2 и вызывает прежний общий result sink; в полном выбранном OK теле нет interval/current-time/server check. Close/back идёт отдельным путём и этот result не создаёт. Имя debounce не доказывает exactly-once, а вызов sink не подтверждает доставку подписчику или изменение checkout.

Самостоятельные требования Юлдаша: различать wall-time selection и подтверждение слота, определить seconds/millis/zone, immutable-result policy и downstream revision validation. Непроведённые тесты: исходное время не в полночь, DST, выбор часа до начального, mutation ссылки, OK против close и повторный callback. Subscriber, booking acceptance, резерв водителя и runtime lifecycle остаются открыты. APK, API и реальные даты не использовались.

# BlaBlaCar Запрос восстановления пароля и локальный результат

Ограниченная статическая проверка: одно новое полное body на 48 instruction units; 6 прежних тел на 415 units и observer/helpers имеют нулевой повторный кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS. Novelty ограничена доступными полными source.text: 11597 из 12731 result-файла; остальные не объявлены прочитанными исходными телами. DEX identities/bytes проверены, shared decoder не является второй независимой декомпиляцией. Глобальное покрытие не пересчитывается.

Coroutine создаёт request из captured email без локальной нормализации и вызывает passwordResetStart. Нормальное завершение сводится к Unit, suspension сохраняется; локального response branching, session write или catch нет. Исключение не превращается в Unit этим телом. Это контракт клиентского вызова, не подтверждение доставки письма, существования аккаунта или смены пароля.

Прежняя связанная VM допускает submit из Empty/Filled/Error после validator, но не из Loading/Success/null. На Success(Unit) она сохраняет captured email в Success state и emits event. Проверенный Activity observer на этот event ставит RESULT_OK без payload, запускает login и закрывается. Этот порядок не означает успешный вход; Failure/null/unknown и внешняя cancellation имеют отдельные границы, не глобальную гарантию восстановления.

Самостоятельные требования Юлдаша: разделить reset-request, доставку и фактическую смену пароля, определить cancellation/stale-response policy. Непроведённые тесты: immediate/suspended/exception completion, state guards, null/blank error message и observer ordering на заглушках. DI/lifecycle, сериализация, server response semantics, rate limits и account enumeration не проверены. Реальные email, credentials и tokens не использовались; отправка письма, reset, вход, API и APK не выполнялись.

# Яндекс Про V1 Переоценка initial validation через UI

Ограниченная статическая проверка с независимой QA PASS: 8 новых полных тел на 1688 байт и 83 перехода; отдельно 5 диапазонов двух partial UI parents на 288 байт и 7 переходов. Полные parent chunks, scheduler и прежние holder/submit/helpers не получают нового полного кредита; глобальный ledger не пересчитывается.

Уточняется предыдущий initial=true: конкретный finance-input widget создаёт controller и регистрирует post-frame callback, а не синхронно уведомляет holder. Callback читает текущий controller text, вычисляет required/regex bool и передаёт current widget callback id/bool/text. Проверенные legacy/redesigned bindings ведут к holder.updateInputState. Значит initial=true может быть заменён false после notification; порядок этой доставки относительно доступного submit ещё не установлен.

Null/empty text даёт !required; whitespace не trim-ится. Nonempty проверяется RegExp текущей модели, true означает non-null match без дополнительной full-match-length проверки. Строгость зависит от самого pattern; видимый error landing не является catch-all. Notify захватывает State, а затем читает текущие controller/widget, не immutable snapshot initial DTO. Реальные model replacement и lifecycle mismatch не доказаны.

Отдельный widget flag управляет текстом ошибки, не отключает validation bool: при false validation result всё равно создаётся, но message может быть null. OnTextChanged сначала уведомляет holder, затем условно запускает visual revalidation при прежнем error marker. Отсутствие видимого текста не означает valid=true; mask может отличать controller text от raw default.

Самостоятельные требования Юлдаша: определить initial callback timing, согласованность id/text/model и отдельные правила boolean и visual validation. Непроведённые тесты: required/optional, empty/whitespace, regex anchors и ошибки, masked default, notification до submit, model replacement/dispose. Scheduler delivery, actual button gate, все keyboard/IME paths и серверная валидация остаются открыты. Runtime bypass/race, exactly-once и финансовый результат не установлены; APK, API и реальные данные не использовались.

# Яндекс Go C120 Consumer времени поезда и локальный draft

Ограниченный final report с независимой QA PASS, не frozen checkpoint: 15 полных тел и один фрагмент, 246 новых выбранных records; 107 raw records исключены. Downstream updater остаётся context-only, прежние Calendar helpers/result producer не получают нового кредита; глобальный ledger не пересчитывается.

Уточняется C119: конкретный result consumer относится к train-time transfer requirements, не прямому подтверждению обычного scheduled order. Проверенный row caller открывает picker только при восстановленном non-null Calendar и связывает callback с тем же presenter. Discriminator=2 попадает в соответствующий default arm и запускает coroutine в presenter scope; runtime доставка и активность scope не доказаны.

Consumer перечитывает текущий draft. Совпадение дня/года/часа/минуты даёт no-op без setter/updater; seconds/millis/timezone-ID отдельно не сравниваются. При изменении копии Calendar образуют date component с hour/minute/second/millisecond=0, отдельно сохраняются выбранные hour/minute. Mask252 заменяет только дату и время, перенося остальные поля по ссылке. Это не deep snapshot и не универсальная DST/midnight гарантия.

Новый draft записывается в локальный flow до вызова дальнейшего updater. В выбранной coroutine нет rollback после ошибки/отмены updater, но конечное состояние других observers этим не установлено. Flow setter не является дисковой записью, server commit или бронированием. Одно-null путь и возможное различие instant при равных wall fields остаются тестовыми границами, не доказанным crash/race.

Самостоятельные требования Юлдаша: типизировать train wall-time отдельно от instant/zone, определить null/equality policy и pending/error recovery локального draft. Непроведённые тесты: same fields при другой зоне, changed time и mask preservation, null combinations, downstream failure/cancel и late result. Полная listener installation, updater request/response, persistence и server acceptance открыты. Билет, резерв трансфера, назначение водителя и финансовый ACK не доказаны; API, APK и реальные поездки не использовались.

# BlaBlaCar Post-login configuration и конкретный consumer

Ограниченная статическая проверка: одно новое полное body на 89 instruction units; прежние связи и helpers имеют нулевой новый кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS. Один prior wrapper ранее имел только structural-pattern verification, не индивидуальное instruction review: нынешняя byte-проверка не повышает его кредит. Novelty ограничена доступным корпусом, shared decoder не является второй независимой декомпиляцией; глобальное покрытие не пересчитывается.

Новый inner сначала ожидает refreshUserSession, затем отдельно читает текущую session, не используя refresh return как business result. При non-null идут trackLogin → changeUser из этой session → schedulePushNotificationSync; при null все три пропускаются, но возвращается тот же Success(Unit). Это условная локальная ветвь, не доказанная runtime достижимость null, свежесть профиля или серверная аутентификация.

Собственного catch у inner нет. Прежний wrapper в проверенных диапазонах перехватывает ошибку, затем вызывает reset перед mapper и Failure; reset/mapper сами находятся вне этих try. Это не атомарный rollback ранних side effects и не глобальная cancellation policy. Конкретный LoginFlow VM consumer игнорирует нормально возвращённый Result и завершает Unit без локального Success/Failure branching или navigation; этот вывод не распространяется на все consumers APK.

Самостоятельные требования Юлдаша: определить null-session outcome, желаемую обработку позднего configuration failure и согласованность current session с account generation. Непроведённые тесты: refresh/getter/side-call order, throw на каждом этапе, reset/mapper failure и Result в конкретном VM. Runtime DI, refresh/getter implementation, lifecycle, другие consumers и доставка push/tracking открыты. API, SDK, APK, реальные идентификаторы, login и reset не использовались.

# Яндекс Go C121 Попытка проверки train transfer

Ограниченный final report с независимой bounded QA PASS, не frozen checkpoint: 13 полных тел и один фрагмент, 287 выбранных records = 225 новых + 62 консервативно zero-credit из прежнего context-only C120. Эти 62 не являются ранее зачтённым selected semantic coverage; 127 raw records исключены. AVIA, UI state transforms и response status consumer не включены; глобальный ledger не пересчитывается.

Проверенный caller сначала отменяет прежнюю попытку, затем проверяет дату, TRAIN experiment и две nonempty строки; whitespace не отсекается length guard. Param собирается из последовательных текущих чтений draft, адреса/точки, тарифов и timezone, не атомарного snapshot. Это клиентская модель, не доказанная wire delivery или server eligibility.

Старый job отменяется без join; новая попытка создаёт child job и заменяет сохранённую ссылку. Это не гарантия завершения старого job, exclusive execution или очистки slot при normal completion. В callCheck только точные Exception-covered invoke/await/resume диапазоны преобразуют ошибку в synthetic TransferCheckResponse с null payload и пустым списком. Initial check вне этих диапазонов; synthetic результат не является настоящим server empty response или ACK.

Отдельного CancellationException rethrow в этом handler нет, но runtime propagation и достижимость callback после отмены не установлены. Normal/resume bridge вызывает captured callback, затем выбранный train adapter передаёт response и captured string дальше. Это не receipt доставки и не доказательство отсутствия stale/status guard в downstream consumer, который пока открыт.

Самостоятельные требования Юлдаша: различать transport failure/cancel и server response, определить revision ownership и согласованный snapshot параметров. Непроведённые тесты: cancel/replace, Exception внутри/вне try, synthetic result, null draft fields, callback failure и поздний ответ. Полный loading/error lifecycle, status interpretation, runtime late acceptance, резерв водителя и server booking не доказаны. APK, API, реальные поездки/адреса и платежи не использовались.

# BlaBlaCar Startup-проверка обязательного обновления

Ограниченная статическая проверка: одно новое полное business body на 64 instruction units; 4 прежних тела на 234 units и helpers имеют нулевой повторный кредит. Независимая QA PASS_WITH_SCOPE_LIMITATIONS: исходные DEX bytes и try ranges проверены, shared decoder не является второй независимой декомпиляцией. Namespace и глобальный знаменатель не меняются.

Coroutine ожидает isForceUpdateEnabled: true отправляет MandatoryUpdate без вызова следующего шага в этом теле, false вызывает executeNextStep. Конкретный selector использует HARD_UPDATE, не soft-update. Собственного catch у нового тела нет; observer/Boolean/dispatcher ошибки не получают здесь fallback.

Прежняя policy сравнивает repository enum с требуемым значением; проверенные java.lang.Exception диапазоны при нормальном завершении logger дают false. Это не catch всех Throwable и не глобальная cancellation policy: logger вне этих try. Прежний dispatcher удаляет первый шаг до logging/dispatch; presenter при MandatoryUpdate вызывает force-update navigation только при non-null navigator. Ни emit, ни отсутствие next step в одной ветви не доказывают глобальную блокировку или обход приложения.

Самостоятельные требования Юлдаша: явно определить fallback при недоступности проверки и отделить startup state от серверной политики. Непроведённые тесты: true/false, enum mismatch, Exception против Error, logger failure, empty/unknown step и nullable navigator. Repository/cache freshness, все entry paths, DI/lifecycle и actual force-update screen открыты. API, APK, установка и обновление приложения не выполнялись.

# Яндекс Про V1 Изменение суммы, options и состояние кнопки

Ограниченная статическая проверка с независимой QA PASS: 21 новое полное тело на 4292 байта и 199 переходов; отдельно 4 диапазона двух partial UI parents на 248 байт и 8 переходов. Прежние submit/preview, generic debounce/button и полный API contract нового кредита не получают; глобальный ledger не пересчитывается.

После replaceAll и parser handler сравнивает parsed amount с сохранённым значением: равенство пропускает updateOptions, неравенство записывает cache до вызова repository. Regex initializer и parser grammar здесь не установлены, поэтому имя regex не означает гарантированное удаление всех нецифр. Локальный handler cache после ошибки не откатывает; реальный retry/reentry outcome ещё не доказан.

Repository передаёт worker захваченный double через debounce, а не будущий current controller text. Worker формирует строку double.toString и ожидает API. Non-null normal result отправляет options, null сохраняет прежний payload; затем loading=false. Видимая error landing также пытается отправить loading=false и rethrow, но полные EH/cleanup гарантии не установлены. Значение null ещё не истолковано как cancel или успех, post-await generation comparison в выбранном worker нет.

Options и loading идут по отдельным broadcast streams, не атомарным combined snapshot. Setters проверяют null/closed controller; manager принимает event только при current Data и lifecycle guard. Options заменяет payment/button payload, loading меняет отдельный flag, сохраняя init flag и прежнюю кнопку. Вызов add не является subscriber ACK.

Конкретный UI gate отдаёт loading при любом из двух Data flags; иначе enabled/disabled определяется button.available. Required fields проверяются отдельно прежним primary submit. Callback остаётся назначенным, но это не делает disabled кнопку кликабельной: generic enforcement имеет prior zero-credit. Forwarding читает current widget/manager и вызывает onTopupTap; несогласованность cached amount, options и текущего ввода не объявлена достижимой runtime гонкой.

Самостоятельные требования Юлдаша: определить retry/dedup после ошибки, correlation options с суммой и приоритет UI gates. Непроведённые тесты: повтор одинаковой суммы, A→B ответы, null/error/cancel API, streams/dispose и loading×available×required flags. API mapping, debounce overlap, доставка, actual gesture/accessibility и server reconciliation открыты. Available=true и loading=false не подтверждают зачисление; APK, API и реальные суммы/реквизиты не использовались.

# Яндекс Go C122 Локальная обработка transfer-check response

Ограниченный final report с независимой bounded QA PASS, не frozen checkpoint: 12 полных тел и 2 фрагмента, 282 выбранных records = 265 новых + 17 консервативно zero-credit из прежнего context-only C121. Эти 17 не являются ранее зачтённым selected semantic coverage; 81 raw record исключён. Baggage enum и framework observer/monitor tails не включены; глобальный ledger не пересчитывается.

