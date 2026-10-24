Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 2 новых полных тела / 159 DEX units в отдельном namespace; прежний producer / 160 units, 8 helpers / 69 units и узкий caller range имеют нулевой новый credit. Старый глобальный знаменатель не изменён. Исходные identities и байты проверены; общий decoder не является второй независимой декомпиляцией. Отчёт SHA256: 157805b0bab3716d9b36b12200246294b5aa491c7b7346d1ec644ca4436fa5ee.

Для Юлдаша отдельно предлагается определить receipt либо явно оптимистичную семантику Copied и правила replay после пересоздания view. Непроведённые mock-тесты: null/throwing clipboard service, normal write, URL без ID, ошибка restore и lifecycle recreate. Реальные clipboard, URL, SDK и API не использовались; скидка и eligibility не подтверждены.

## Яндекс Про экран результата настройки автовыплат

Проверенный CreateSuccessPage route в новой navigation реализации связан с константным success screen. Экран использует локализованные title, subtitle и button, не читая amount, rule ID, баланс или исполнение schedule. Legacy host route-to-widget binding отдельно не замкнут. Это UI результата настройки, не доказательство будущего перевода денег.

Кнопка разрешает navigation dependency из захваченного scope и вызывает pop. Для двух известных implementations установлены условные targets: legacy делегирует host close; новая реализация при canPop=true вызывает обычный pop, иначе пытается выйти из root route. Если нужный root не найден, этот путь возвращается. Ни один из этих вызовов не доказывает фактическое закрытие host, достижение root screen или завершение refresh списка.

В выбранном callback нет повторного create, собственного refresh API или чтения ledger. Это не глобальное отсутствие refresh или повторного create в приложении. Реальная DI implementation, generic navigation semantics, delivery tap и async outcome остаются открытыми.

Независимая проверка PASS: 4 новых полных dependency-limited тела / 616 native bytes / 22 прямых перехода. Прежние create handler, shared widgets, DI access и pop implementations имеют нулевой новый credit. Отчёт SHA256: a4cf30b9e122f0e22d3f1ad71d9e3062de5d1ed9ca088589974db42c762dc69c.

Для Юлдаша отдельно предлагается различать «правило создано» и «выплата выполнена» и явно проверять root refresh, если он требуется. Непроведённые тесты: route binding, обе DI реализации, canPop true/false, root found/missing, dependency failure и host close outcome. Реальные правила, UI, API и денежные операции не выполнялись.

## BlaBlaCar модель кнопки search banner

Renderer различает None, Available и Copied. Для None модель кнопки создаётся только при nonnull ctaLabel с ненулевой длиной, без trim и URL-проверки. Available передаёт voucher code, COPY и a11y label; Copied передаёт label и CHECK. Визуальный CHECK не подтверждает clipboard receipt.

Все три ветви передают callback в PromoBanner, включая None без модели кнопки. Подтверждены конкретные gv7 bindings от setContent к renderer и qj7 discriminator 19 к прежнему Fragment callback. Callback захватывает исходную модель и Fragment, затем прежний consumer читает её URL, ID и voucher state. Это не доказательство своевременной рекомпозиции, runtime клика или свежести captured данных.

Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 2 новых полных тела / 273 DEX units в отдельном namespace; прежний callback / 22 units, 7 helpers / 130 units и выбранный dispatcher fragment имеют нулевой бизнес-credit. Остальные qj7 arms не получают полный credit, старый глобальный знаменатель не изменён. Исходные identities и байты проверены, общий decoder не является второй независимой декомпиляцией. Отчёт SHA256: 49f1d678190e60ed2b700b73a809bbd7e85d5aeab47a34c4b6df8e8791aab383.

Generic PromoBanner gesture, hit areas и disabled semantics этим пакетом не установлены. Поэтому None без CTA нельзя объявлять кликабельным или некликабельным, а Copied no-op в VM не означает disabled UI. Для Юлдаша отдельно предлагается определить whitespace/CTA policy и соответствие callback отображённой модели. Непроведённые тесты: null/empty/whitespace label, Available/Copied text/icons, смена модели, reuse slot и точный dispatcher binding. Реальные UI, clipboard, URL и SDK не использовались.

## Go C129 верхнее преобразование final address

Проверенный mapper возвращает null при отсутствующем или пустом списке кандидатов. Для альтернатив выбираются первые совпадения group/option keys, без dedup; если выбранная пара не найдена, используется первый исходный кандидат. Пустые mapped группы сохраняются. Это локальный fallback, не подтверждение корректности каждого адреса непустого ответа.

Nonnull conditional-actions источник имеет приоритет даже при пустом списке или пустом результате фильтрации; только null допускает legacy fallback. Пустые вложенные списки не удаляются, исполнение actions этим преобразованием не установлено. Нижние address converters остаются за рамкой.

Уточнение C126: для конкретного создаваемого PlainAddress метод Q читает zoneName. Верхний mapper задаёт её только при соответствующем true flag; null/empty не проходят прежний length predicate, whitespace проходит. Это не универсальный контракт всех Address implementations и не серверная eligibility или доступность машины.

Pickup элементы не отфильтровываются как malformed: null ID становится пустой строкой, null coordinate получает sentinel, неизвестный type становится UNKNOWN, nullable booleans имеют defaults. Конструктор безусловно ставит n=true, используемый C127 как один из фильтров; это не внешняя валидация координат. Null image tag получает default, empty сохраняется. Отдельно созданный yac передаётся constructor, но тот не сохраняет этот параметр, поэтому создание объекта не доказывает выполнение action.

Независимая bounded static QA: PASS, 3570 механических проверок. 25 полных тел, 772 выбранных records = 761 новый + 11 повторных; lower converters и zero-credit context не расширяют этот счёт. Это bounded final report, не frozen checkpoint и не обновление глобального ledger. Отчёт SHA256: 26ed79405047e2af43fda3ede84de8fb1a9bcbf3aa40c5ac94a45b1bfe8bd550.

Для Юлдаша отдельно предлагается различать zone label и авторитетную доступность, проверять pickup defaults до admission и документировать deterministic fallback. Непроведённые тесты: duplicate/missing keys, null versus empty actions, zone flag/whitespace, sentinel координаты и effective constructor fields. Wire serialization, driver delivery, физическая безопасность и runtime outcome не доказаны; реальные адреса и API не использовались.

## BlaBlaCar обновление истории поиска

Подтверждён путь от прежнего recent-stay saved event через Fragment callback к refresh. Coroutine получает список с feature flags, затем последовательно обрабатывает каждый элемент: отдельно читает mapper и flags, ожидает supply-type selector, преобразует и добавляет запись. В этом цикле нет сортировки, dedup или пропуска элементов; внутренности interactor, selector и mapper остаются открытыми.

Только после завершения всех преобразований читается текущий экранный state. Для DefaultDisplayState заменяется history с сохранением текущих title и promo; другой subtype завершает no-op. Это отличается от прежнего delayed banner restore из captured state, но не даёт общей атомарности или гарантии порядка нескольких refresh. В выбранных телах нет generation guard или отмены прежней попытки.

Пустой нормальный список становится локализованным Empty, непустой передаётся в DisplayContent. Ошибка list fetch, selector или mapper не преобразуется здесь в Empty: локального catch/fallback нет. Событие saved и нормальное применение UI не подтверждают серверную persistence или freshness.

Independent QA: PASS_WITH_SCOPE_LIMITATIONS. 2 новых полных тела / 288 DEX units отдельного namespace; 10 helpers / 55 units и прежний alias / 4 units имеют нулевой бизнес-credit. Старый глобальный знаменатель не изменён. Исходные identities и байты проверены; общий decoder не является второй независимой декомпиляцией. Отчёт SHA256: a3cae331c1adeb2ce47a26b190549a36c81255aa6a825d2fd77622b6b513488f.

Для Юлдаша отдельно предлагается определить fresh-result ordering и внешнюю error policy, не обозначая ошибку пустой историей. Непроведённые mock-тесты: empty/nonempty, suspension каждого элемента, смена subtype/title/promo, mapper throw и обратный порядок завершения refresh. Runtime гонка и изменяемость flags не доказаны; реальные маршруты, история и API не использовались.

## Яндекс Про ввод параметров автовыплат

Init создаёт три отдельных денежных controller с mask/maskFilter, не передавая config defaults как initialText. Маска задаёт формат до десяти позиций, не денежный upper limit и не числовой clamp. Денежные defaults читаются отдельными getters и передаются в display path отдельно от raw controller text. Их совпадение с более поздними defaults при submit не гарантировано этим кодом.

Начальный type берётся первым из списка, а при пустом списке выбирается schedule; это default UI, не серверное разрешение. Payout time получается отдельно. Submit callback читает текущие type, time и controller.value.text из захваченного State, а не build-time snapshot, и вызывает прежний threshold либо schedule wrapper без await.

Перед callback используется throttle с интервалом один миллион микросекунд. Он не является блокировкой до завершения API и не доказывает денежную идемпотентность. В выбранном worker нет собственной required/numeric-range проверки, но это не отсутствие проверки formatter, UI или backend и не доказательство обхода loading gesture gate. Прежние wrappers заново выполняют int.tryParse и могут использовать config fallback при null.

Независимая проверка PASS: 8 новых полных dependency-limited тел / 2004 native bytes / 104 прямых перехода. Shared mask, throttle, controllers, wrappers и большой UI build не получают повторного полного кредита. Реальные DI, formatter acceptance, server validation и lifecycle timing остаются открытыми. Отчёт SHA256: 8212aded22d19c9d366e3407d51ed21e6bc7544785be7860450e6323340df985.

Для Юлдаша отдельно предлагается различать показанный default и введённое значение, задавать согласованность config между display и submit и отдельно проверять API-in-flight gate. Непроведённые тесты: empty/clear/paste/programmatic input, длина и формат числа, смена type/time/text, defaults между попытками и controlled API future. Реальные правила, банковские настройки и выплаты не изменялись.

## BlaBlaCar: выбор supply и представление сохранённого поиска

Ограниченная статическая проверка добавила 4 полных тела / 561 DEX units из нового namespace; прежний refresh и вспомогательный enum mapping повторно не учитываются. Старый глобальный denominator не изменён. Независимая QA: PASS_WITH_SCOPE_LIMITATIONS; проверены исходные DEX identities, байты и локальная новизна, но общий decoder не является вторым независимым декомпилятором.

При выключенном флаге selector возвращает null; при включённом берёт первый тип, отличный от STAY, без транспортного whitelist и дедупликации. Сохранённый transportMode имеет приоритет. Fallback supply влияет на выбор иконки только при отсутствии сохранённого mode; payload отдельно строится из исходной записи. Иконка не доказывает транспорт в отправляемом запросе. Флаг обратной поездки управляет стрелкой заголовка, тогда как диапазон дат в описании зависит от наличия обеих дат.

Локальный history-date predicate возвращает false для null и сравнивает дату строго с текущим временем, у которого обнулена только MINUTE: часы, секунды и миллисекунды здесь не сбрасываются. Mapper вызывает predicate дважды, отдельно для описания и итогового past-флага. Это другой класс, чем ранее разобранная нормализация даты без времени с null→true; прежний вывод не отменяется. Несовпадение на временной границе не воспроизводилось и не объявляется runtime-дефектом.

Границы: внутренности payload mappers, форматирование дат, источник конфигурации и время жизни флага остаются открыты. Past-флаг не доказывает блокировку поиска, серверную допустимость дат, доступность или цену. APK, API и реальные пользовательские маршруты не запускались.

Собственные предложения для Юлдаша, не требования найденного приложения: явно определить смысл «поиск в прошлом» и отделить иконку от фактического режима запроса. Непроведённые тесты: первый non-STAY при разных конфигурациях; приоритет сохранённого mode; null и точное равенство порогу; переход часа, дня и timezone/DST; два чтения часов; обратная дата при выключенном return-trip; неизменность payload при выборе иконки.

## Go C130: преобразование адреса и контактных metadata

Ограниченный final report, не frozen checkpoint и не полный аудит адресной цепочки. Независимая статическая QA: PASS; 12 полных тел, 1071 selected records = 1049 новых + 22 повторных из C100. Проверены исходные DEX, ссылки, ветвления и запись 42 аргументов конструктора; глобальный ledger не пересчитывается.

При отсутствии исходной координаты mapper подставляет sentinel только в основной результат. Panorama требует исходную координату и ненулевой id; sentinel не заменяет этот guard, а пустой id локально не исключается. Ненулевой override имеет приоритет, включая пустую строку. Contact override сохраняется ссылкой; иначе контакт из suggest создаётся при ненулевом phone, без проверки непустоты в этой ветви. UUID не подтверждает личность, согласие или доставку контакта. Синтетический путь всегда передаёт null override, в отличие от прямого вызова.

Наличие metadata не равно разрешению действия: group Integer копируется независимо от visibility flag. Неизвестный enum нельзя считать безопасно проигнорированным, поскольку valueOf вызывается без локального catch, хотя его результат далее не сохраняется. DTO выбирает CLEAR только для точного CLEAR, иначе EDIT; fallback ADDRESS относится к null результату делегированного parser, а не к любому исключению. PlainAddress сохраняет тот же DTO reference, не доказанную deep copy. Favorite enrichment установлен как вызовы helpers, но их persistence и network effects остаются открыты.

Границы: не доказаны географическая пригодность точки, серверный допуск, consent, передача водителю, durable favorite update или runtime-достижимость всех сочетаний. Собственные требования Юлдаша: отдельно определить null/empty override, происхождение и право передачи контакта, проверку координат и обработку неизвестных enum. Непроведённые тесты: sentinel против panorama gate; null/empty overrides; visibility=false при group ID; parser null против throw; прямой и синтетический пути; ошибки favorite helpers. Реальные контакты и адреса не использовались.

## Яндекс Про: выбор типа и времени автоплатежа

Независимая QA: PASS в ограниченном статическом объёме. Новые 10 полных локальных тел / 1840 байт / 80 прямых native-переходов; прежние submit, API, serializer и общие helpers не начислены повторно. Эти проверки не заменяют полного анализа исключений, зависимостей и runtime.

Смена типа записывает выбранный type в текущий State, не пересоздавая денежные controllers или время. При открытии picker захватывает текущее время для начальных значений. Однако hour/minute callbacks каждый раз читают уже текущий State.payoutTime и заменяют только свою компоненту, сохраняя другую текущую компоненту. Обычный UI предлагает часы 0–23 и минуты 0–59; это не доказательство range enforcement для произвольного callback input.

Известные callbacks меняют State до кнопки закрытия sheet. Footer вызывает навигационное закрытие, а не отдельный commit времени или backend update. Глобальная семантика cancel/back и rollback остаётся открытой. Внешний UI callback не ожидает showPayoutTimePicker; при этом legacy-ветвь сохраняет возвращаемое значение до async return, а новая ветвь заменяет его null, поэтому отсутствие Await нельзя превращать в утверждение об отброшенном legacy Future. Доставка callbacks и завершение modal не доказаны.

Прежний submit читает текущее время и сериализует hours/minutes как конфигурацию правила, не timestamp выплаты. Собственные требования Юлдаша: явно выбрать live-edit либо commit/rollback, разделить display, часовой пояс scheduler и финансовый результат. Непроведённые тесты: hour→minute и обратный порядок; повторное открытие; backdrop/back/cancel; смена schedule/threshold; недопустимые callback значения; timezone/DST и callbacks после dispose. Реальные правила и выплаты не создавались; runtime-безопасность и серверное исполнение не установлены.

## Яндекс Про: нормализация default config автоплатежа

Независимая QA финальной редакции: PASS. Ограниченный новый объём: 6 полных локальных тел / 1316 байт / 56 прямых native-переходов; общий config framework, numeric runtime и прежние consumers не начислены повторно. Это не полный аудит приложения и не runtime-тест.

Decoder требует числовые значения для пяти полей. Missing/null, строковые числа и bool не получают локальный per-field default. Для обычных конечных double в проверенном fast path дробная часть сначала отсекается к нулю, затем часы ограничиваются диапазоном 0–23, минуты 0–59. Это truncate→clamp, не округление к ближайшему, не modulo и не timestamp. Поведение NaN, Infinity и значений вне fast path не установлено. Три денежных default значения преобразуются в integer и записываются без аналогичного clamp или cross-field проверки в этом decoder; отсутствие ограничений во всей системе из этого не следует.

Конкретная factory регистрирует decoder и fallback callback с пятью нулевыми значениями. Но наличие fallback не доказывает его выбор при каждой ошибке декодирования: generic recovery остаётся открытым. Кэш сравнивает reference исходного config body; это не immutable snapshot и не гарантия единой версии между display, начальным временем и submit fallback. Нормализация default времени не переносится автоматически на ранее проверенные callbacks выбора часов и минут.

Собственные требования Юлдаша: явно определить recovery для malformed config, допустимые денежные диапазоны и версионную согласованность чтений. Непроведённые тесты: типы и отсутствующие поля; границы часов/минут и конечные дробные значения; negative/zero/large денежные defaults; numeric slow paths; absent config против decode error; изменение версии между чтениями. Реальные настройки и правила не менялись, API не вызывались. Серверная допустимость, исполнение scheduler и финансовый результат не доказаны.

## Go C131: редактор избранного до запуска сохранения

Ограниченный final report, не frozen checkpoint. Независимая статическая QA: PASS; 22 полных тела и 3 фрагмента, 871 selected records = 860 новых + 11 повторных, 67 raw records исключены. Context-only подготовка DTO и ещё не исследованные request bodies не получают semantic credit; глобальный ledger не пересчитывается.

Подтверждена связка save listener с выбором New/Existing strategy. Пустое имя блокирует dispatch только при одновременно пустом нормализованном имени и ненулевом непустом тексте ошибки, полученном при включённом configuration flag. Нормализация обрезает крайние whitespace/space characters, не внутренние пробелы. Это условный локальный guard, не универсальная обязательность имени. При блокирующем условии null holder/button могут убрать UI effect, но не открыть request arm.

