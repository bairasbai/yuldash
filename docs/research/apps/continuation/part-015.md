Прежний toggle handler emits loading=true до API await, false после нормального ответа; собственного finally нет. Abnormal exception/cancellation не гарантируют восстановление UI, внешнее recovery не исключено. Открытие disable-confirm само не означает mutation/loading и не превращает gate в модальный single-flight lock. Duplicate request, race и финансовый ущерб не установлены.

Для Юлдаш нужны явный input/version contract, domain admission отдельно от UI, abnormal recovery и различение delete/disable. Все 8 тестов NOT_EXECUTED, implemented=false, accepted=false: input/loading separation; rebuilt loading gate; rapid taps до/после rebuild на mocks; stale captured delete VO и общий timer между entities; normal/error/throw/cancel completion; confirm без mutation; повторный вход/ownership; properties/formatting. Происхождение navigation args и shared view-manager ownership остаются открытыми. UI indicator не заменяет server idempotency или финансовую приёмку.

Источники: recovery-autopayout-view-producer-20261023/REPORT-RU.md SHA256 96a9d9dcc9516ed94c7ae1d43dc7564bab04137ee3b175012444bcdac2767b31; AUTOPAYOUT-VIEW-PRODUCER-INDEPENDENT-RU.md SHA256 d6273bc138245bd71dd4b546746723bee1e1dd984a7c8bdd729ed7f2656cce3c.

## BlaBlaCar Stay history payload и сохранение

Независимая QA PASS: 1779 assertions. 19 FULL / 927 DEX instruction units: новый com/searchform business mapper 1/96; новый support 2/11 отдельно; повторный core 16/820 с нулевым новым credit. Старый core denominator не меняется. Проверены 1516 prior ledgers и 11597 непустых source.text; прежние startsWith/null-check helpers не добавлены в новые тела. Общий decoder использован повторно, это не второй декомпилятор и не процент полного аудита.

Mapper создаёт новый RecentSearchPlace из восьми arrival fields и задаёт source SEARCH_HISTORY. Date/returnDate, seats и redirectUrl берутся из captured Nav без пересборки URL, обращения к текущей VM или deep copy Date. Constructor требует arrival/date/URL nonnull, но не даёт local seats>0 guard. Redirect ранее строился из текущей VM отдельно от captured save; новый mapper не согласует эти источники. Реальный snapshot mismatch не воспроизведён.

Обычный addSearch читает map, для Stay берёт incoming arrival.address как case-sensitive startsWith prefix и удаляет совпавшие entries только при ненулевой date строго раньше now. Now equality и null date пропускаются, returnDate не участвует; now вычисляется внутри цикла, не единым snapshot. Это prefix matching, не структурное равенство места. Затем nonnull exact-key match удаляется и новая модель помещается в конец insertion-order. Альтернативный addSearchViolatingInvariants делает прямой put без cleanup/remove; его production reachability здесь не доказана.

Stay key: пустой prefix + address + cityName + millis начала локального check-in дня, без separators и фиксированной timezone. Checkout, seats, URL, coordinates, id и source не входят. Поэтому одинаковые address/city/day условно заменяют модель целиком даже при иных этих полях. Это может быть намеренная дедупликация, а не подтверждённая потеря истории. Null-date fallback helper условен, поскольку новый Stay constructor требует date.

Deserialize создаёт insertion-order LinkedHashMap, читает Gson payload и пересчитывает keys. Общий cap size>5 действует на Stay и transport вместе. Exception ловится только внутри parse/populate диапазона; map может быть частичной, logger вне catch и сам может бросить. getString этим catch не покрыт. Serialize передаёт values → RecentSearches → Gson; Stay subtype получает всю модель с type=stay, exclusion касается именно base-class fields. Фактические JSON bytes/date format не исполнялись.

save вызывает putString recent_searches_datastore_v1 и apply без commit/await: normal save event не доказывает disk durability. No-local-lock в read-modify-write не доказывает lost-update, внешняя защита неизвестна. Preference filename, encryption/backup, account/logout separation, UI удаления и внешняя доставка логов остаются открытыми. Реальная история и сохранённый URL не читались/не открывались.

Для Юлдаш: явно выбрать identity/checkout/seats policy, структурные keys и точный cleanup, timezone/expiration, согласование redirect/save snapshot, atomic updates и durability contract, retention/privacy/account/logout и отдельное пользовательское удаление. Это собственные проектные требования, не спецификация поставщика; реализация и приёмка NOT_VERIFIED.

Все 10 тестов NOT_EXECUTED: T01 mapper payload и references; T02 same key/different checkout,seats,URL,id и ordering; T03 concatenation collisions/prefix neighbours; T04 null/equal/past/future date и checkout policy; T05 midnight/timezone/DST; T06 sixth mixed entry/max5 и repeat ordering; T07 corrupt JSON/late malformed element/throwing logger; T08 concurrent mocked updates/apply failures; T09 VM change между redirect/save; T10 synthetic privacy/account transition/clear-history. Следующая граница: restore/click consumer сохранённого Stay URL и guards, не generic Gson/HTTP ради счётчика.

Источники: STAY-PERSISTENCE-RU.md SHA256 e770ebf2240a8b59cbeb1557b3ed80988a66e11a2b82874de12386095703f22a; INDEPENDENT-QA-RU.md SHA256 b905134e5cc392016439d0ed2b3b72d8d59583df158a33362351fd5e45f0d630.

## Go C152 Defaults вложенных action и trail button

Независимая QA PASS: 1247 assertions, 28 переходов и 108 pool references. 10 FULL, 234 selected/raw = 234 bounded-local-new, reused/excluded 0. C144/C146/C149 служат проверенным zero-credit контекстом. Новизна ограничена доступными SOURCES/prior-context/C67; QA не является вторым декомпилятором или независимым повтором scan всех 1008 JSON.

Action descriptor объявляет optional type/deeplink; trail button descriptor — title/style/size/action. Decoder использует соответствующие serializers, presence bits и прежние field values, завершает structure при index −1 и передаёт mask в constructor. Другой возвращённый index идёт в error helper. Это не полная политика unknown JSON keys, duplicate keys, coercion или explicit null: concrete format decoder остаётся за границей. Собственного catch нет.

Для action проверены все 4 presence masks, для button все 16: отсутствующие поля null, присутствующие сохраняют входные references. No-arg constructors также дают null. Нет local cross-field требования title implies valid action, scheme/nonempty validation или обязательного enum значения. Lazy initializers задают PUBLICATION и discriminators 23/24/25; enum factory bodies, wire names/unknown-enum handling и concurrent guarantees не раскрыты.

Отсутствующий item action отличается от существующего action object с отсутствующим type. Первый в C144 пропускает builder.E, на изученном fresh builder остаётся null, и C146 ITEM callback не вызывает L6. Второй даёт nonnull mo0(null,null): callback может вызвать L6, но ordinary presenter C149 возвращается по null ActionType. Favorites использует свой прежний inherited no-op C146; ordinary executor ему не приписывается.

При отсутствии trail title выбранная C146 ветка button creation не начинается. При существующем title и отсутствующем nested action C144 всё равно создаёт nonnull mo0(null,null) wrapper и передаёт его в ul0. Поэтому после успешного text conversion C146 устанавливает TRAIL listener, а ordinary action executor условно возвращается по null type. Это не generic ul0.d==null ветка, где listener не устанавливается; setTrailView возможен и без listener.

Наличие title не гарантирует непустой rendered text, успешный renderer, показ или tap. Отсутствующие style/size дают controlMinor/0, а не отсутствие button model; это локальный resource/int выбор, не визуальная гарантия. Реальные server payload combinations, достижимость неполного action и runtime дефект не установлены. Callback no-op нельзя автоматически называть disabled UI, сломанной кнопкой или vulnerability.

Для Юлдаш: OWN_DESIGN_NOT_VENDOR_SPEC, implementation/acceptance NOT_VERIFIED. Явно выбрать hidden/disabled/error поведение неполного action и cross-field validation для действительно actionable кнопки. Все 8 proposed tests NOT_EXECUTED: T01 absent object против missing type; T02 отсутствующий trail title; T03 title с отсутствующим action и nonnull wrapper; T04 defaults style/size отдельно от action validity; T05 unknown enum/explicit-null на concrete decoder; T06 no-op отдельно от disabled UI; T07 cross-field valid action requirement; T08 rendering, listener registration и tap как отдельные этапы приёмки.

Источники: GO-C152-ADDITIONAL-ACTION-BUTTON-CONTRACT-RU.md SHA256 6d08640c562cb458d6d5ab2ff1afbe5da8fe52e793b0e21e74f03bfedd1ceb75; C152-INDEPENDENT-REVIEW-RU.md SHA256 620b03ef7482b6ed1db434128f1a378e997f0225173c298bfaa01fd6e430171f. APK/API/runtime tests не запускались.

## Яндекс Про Preview input и отдельное обновление entrypoint

Независимая QA PASS: 6603 assertions. 8 новых full-local dependency-limited тел, 1472 native bytes, 93 direct branch/call инструкции. 5348 frozen prior файлов перепроверены: 6 address references относятся к end-exclusive границе, callsites и callback annotations, не прежнему покрытию новых тел; annotation hash matches 0. Старый root и API/JSON/router context имеют нулевой новый credit. Независимое повторное Dart snapshot decoding и runtime исполнение не выполнялись.

Preview manager возвращает результат handle. Handler сначала emits Empty, ожидает getAutopayout, затем success payload reference помещает в Data; ApiResponseError даёт Error(error). Для другого subtype дальнейшая terminal emission в локальном теле отсутствует, собственного catch/finally нет. Прежний root после await читает shared manager state, не напрямую request payload: только Data model допускает subtype selection, PreviewData ведёт к ViewPage, PreviewEmpty к CreatePage. Неизвестный preview subtype даёт локальный null page, не гарантированный безопасный fallback. Post-await shared-state read сам по себе не доказывает гонку.

Legacy builder переносит исходный preview reference в pageView. Новый route mapper создаёт map с ключом preview и route autopayout-view, но reference не клонирует и не сериализует. Route builder читает extra, отвергает null и проверяет AutopayoutPreviewData type, затем сохраняет ту же ссылку. Reload по id или network fallback в выбранном builder отсутствует. Через прежние outer page → inner widget → factory связи preview доходит до VM.input. Route admission/pushReplacement не доказывают фактическое открытие; полный host/list/deeplink click contract остаётся вне этого пакета.

После успешного toggle старый caller запускает entrypoint.refresh, но возвращает сохранённый mutation response, не ожидая завершения refresh. Сам refresh возвращает delegated getEntryPoint через ReturnAsync, manager сохраняет handle result: его контракт нельзя называть fire-and-forget. Mutation completion и entrypoint refresh completion различны.

EntryPoint handler emits Loading до запроса. Success envelope проходит чтение payload и telemetry, затем explicit null guard; null payload не означает usable success. Только is_available=true и status nonnull дают Data(status); false либо null status дают Empty. ApiResponseError после onLoadError снова emits именно Loading, не Error state. Иной response subtype выходит без дополнительной emission, local catch/finally нет. Без consumer/runtime это не доказанный вечный spinner, потеря доступа или финансовый дефект.

Preview и entrypoint имеют разные typed emitters и API calls. В выбранных телах refresh обновляет entrypoint summary, не preview manager и не существующий VM.input; прежний view mapper отдельно читает input autopayout и view loading. Это локальная граница потоков, не абсолютное отсутствие внешних subscribers: новый вход, закрытие/пересоздание host или listeners могут обновить экран. Stale-input и cross-request race не установлены. Ни status, ни mutation success не подтверждают банковский settlement.

Для Юлдаш: собственные проектные требования, не спецификация поставщика. Нужны явные input identity/version и lifecycle policy, отдельные preview/summary запросы, определённое обновление открытого view после mutation и error/recovery без ложной финансовой приёмки. Все 7 тестов NOT_EXECUTED, implemented=false, accepted=false: preview reference в обеих routes; матрица preview result/subtypes; absent/null/wrong-type route args; available/status/null-payload gate; error/unknown response consumer с virtual time; delayed refresh отдельно от mutation и повторного входа; достижимые shared-manager interleavings/cancellation.

Открытая следующая граница: EntryPoint Data/Loading/Empty consumer, click/deeplink и повторный preview после refresh. Generic router не расширялся. Источники: recovery-autopayout-view-input-20261023/REPORT-RU.md SHA256 c7dbd2f3847f312b5f72f40f6ff4647072ad9eefde326446883d3f746f357baf; AUTOPAYOUT-VIEW-INPUT-INDEPENDENT-RU.md SHA256 d59978af9ac8a96dc55461403a8221be09e01fb5165828c2d7e2eb6a38410156.

## Go C153 Имена enum в фабриках сериализаторов

Независимая QA PASS: 3748 assertions, 489 pool references, raw switch targets и порядок аргументов проверены. 18 методов: 15 FULL + 3 FRAGMENT, 209 selected = 209 bounded-local-new, reused 0; raw 765, excluded 556. Исключённые arms не становятся семантическим покрытием от побайтовой проверки. Новизна ограничена доступными SOURCES/prior-context/C67; дополнительный авторский scan 1008 JSON целиком независимо не повторялся. Это не второй декомпилятор, не строки исходников и не процент продукта.

Замкнута C152 цепочка lazy nested action/button serializer → enum Companion wrappers → cached delegate → фабрика. Discriminators 23/24/25 выбирают соответственно ActionType, AdditionalButtonStyle, ActionButtonSize; отдельный arm26 строит style serializer. Enum initializers формируют VALUES и PUBLICATION lazy cu(15), z10(26), mq(27). Generic lazy implementation и её concurrency guarantees не расширялись.

Проверен контракт аргументов tqz.k: ActionType VALUES [DEEPLINK, SEARCH_IN_GOOGLE] сопоставлены names [deeplink, search_in_google]; AdditionalButtonStyle [DEFAULT, ACTION] сопоставлены [default, action]; ActionButtonSize [XS, S, M, L] сопоставлены [xs, s, m, l]. Для size порядок установлен по registers filled-new-array, а не по порядку загрузки строк m,l,xs,s. Аннотации передаются массивами null соответствующей длины; values() возвращает clone VALUES. Тела clone и tqz.k не включены в покрытие.

Это имена и порядок, переданные локальной фабрике, не полный wire decoder contract. Missing optional field из C152 нельзя приравнивать unknown enum token. Не доказаны превращение неизвестного значения в null/default/exception, explicit-null acceptance, coercion или concrete JSON configuration. Реальные server payload и runtime effects остаются открытыми.

Ранее проверенный контекст C144/C146/C149 не пересчитан: ACTION выбирает controlMain, другой/null style minor; XS/S/M/L выбирают 0/1/2/3; null action type в ordinary presenter возвращается без действия. Эти связи не доказывают успешное deeplink открытие, Google request, визуальный дефект или пользовательскую ошибку.

Для Юлдаш: собственные proposed acceptance requirements, не vendor runtime specification; implementation NOT_VERIFIED. Все 7 тестов NOT_EXECUTED: T1 known action tokens; T2 style tokens; T3 size order; T4 unknown token отдельно от absent field; T5 explicit-null policy; T6 null type без unsupported navigation; T7 registration именно нужного domain enum. Concrete decoder/server contracts открыты; generic serialization infrastructure не расширялась.

Источники: GO-C153-ADDITIONAL-ENUM-WIRE-RU.md SHA256 1105c13ba13e86bf65507017e423585677c3d5a9bd67ac652ff5f920a71ebad0; C153-INDEPENDENT-REVIEW-RU.md SHA256 04a6e2729d5f23c7b454e99b706281828d0e8172d0fa85c247eaee65d28d8004. APK/API/runtime и приёмка Юлдаш не выполнялись.

## Go C154 Аналитика нажатия после action dispatch

Независимая QA PASS: 1264 assertions, 28 raw DEX branch displacements, 140 pool references. 11 FULL, 226 selected/raw = 151 bounded-local-new + 75 reused, fragments/excluded 0. В ordinary i.lg хвост PC110–257 содержит 73 новых семантически разобранных records, prefix/return 55 reused; context getter целиком reused 20. Полное тело уже существовало в raw exports C146/C149, хвост был явно исключён из семантического покрытия C149. Это новая проверка хвоста, не первая выгрузка или открытие метода. Остальная novelty ограничена доступным corpus/ledgers; дополнительный scan 1008 JSON полностью независимо не повторён.

Null ActionType завершает метод до action и telemetry. DEEPLINK с null URI пропускает URI handler, но может дойти до analytics delegate при непустом id. Поиск через Yf и URI delegation стоят до аналитической ветки; метод не ждёт server result или acknowledgment открытия и не имеет собственного catch. Событие не является подтверждением результата действия. Этот ordinary presenter путь нельзя переносить на favorites inherited no-op C146.

Analytics object getClass проверяется до id guard. Null либо length-zero id прекращает только последующую аналитику, не отменяет уже вызванное действие. Пробельная строка этой проверкой не отсекается. Analytics id_type сравнивается с ride, затем order, иначе выбирается element, включая null/неизвестную строку; это не ActionType. TapArea getter item/trail сравнивается с Item, иначе выбирается Trail. Local null guard до getter нет, но null в штатных callbacks не доказан.

Хвост читает контекстные поля, currentTimeMillis, cqq0.c и boolean пустоты одного поля, затем получает базовую HashMap от m.t. Два context fields добавляются только nonnull; далее timestamp/context/id/id_type/boolean/tap_area. Нераскрытым именам контекста не присвоен бизнес-смысл. URI не передаётся напрямую в выбранных put, но содержимое base map и других контекстов неизвестно: отсутствие чувствительных данных во всём событии не установлено.

Создаётся отдельная пустая HashMap, вызывается ypo.a(event,map,1,emptyMap). Это граница локального delegate, не доказательство отправки, consent, retention, очереди, exactly-once или доставки. Reused cqq0.c возвращает пригодный h; иначе создаёт IllegalStateException для logger и вызывает currentTimeMillis → b. В этом теле нет throw созданного объекта, но logger/b остаются внешними зависимостями, поэтому безошибочный fallback не гарантируется.

Для Юлдаш: OWN_DESIGN_NOT_VENDOR_SPEC, implementation/acceptance NOT_VERIFIED. Разделять tap intent, action dispatch и подтверждённый результат; не использовать наличие telemetry как приёмку успеха. Все 7 тестов NOT_EXECUTED: T1 null type; T2 null URI не success; T3 empty id не отменяет действие; T4 unknown analytics type fallback; T5 item/trail; T6 dependency exception без ложной delivery; T7 search dispatch отдельно от server result. Upstream analytics converter остаётся отдельной границей; generic analytics framework не расширялся.

Источники: GO-C154-ADDITIONAL-TAP-ANALYTICS-RU.md SHA256 b8d3ee94c33f7b9ae4c2d1a60f234d1eb2436ccf5100fcc1777609d4d24e0fe1; C154-INDEPENDENT-REVIEW-RU.md SHA256 c41b3da68b875ef98f32e3c792eb65c09c7eeed76997a8b04e659bcecfe0eb46. APK/API/runtime не запускались; runtime дефект или успешное внешнее действие не доказаны.

## Go C155 Строковый analytics id_type

Независимая QA PASS: 324 assertions, 6 raw branch displacements и 25 pool references. 4 FULL, 54 selected/raw = 54 bounded-local-new, reused/excluded 0. Это четыре domain тела, не четыре новых бизнес-сценария. Новизна ограничена доступными SOURCES/prior-context/C67; дополнительный авторский scan 1008 JSON полностью независимо не повторён. Второй декомпилятор и runtime не применялись.

Nested analytics descriptor объявляет одно optional поле id_type. Decoder использует строковый serializer wpv0, передаёт previous value, cast String и presence bit 1. Index −1 завершает structure, 0 читает поле, иной возвращённый index идёт в error helper. Политика unknown JSON keys до возврата decoder index этим не установлена.

Constructor при отсутствующем bit сохраняет null, при присутствующем — переданный String reference; no-arg также null. Локальных whitelist, enum conversion, trim или casefold нет. Это nullable String, не ActionType enum C153. Если decoder вернул неизвестную строку, constructor её сохраняет; отсюда не следует принятие произвольного JSON, explicit-null coercion или конкретная decoder configuration.

Reused цепочка C151/C145/C144/C143/C146 сохраняет nested reference → kl0.a → builder.C → model.B → analytics type callback. При null analytics mapper пропускает запись; общий reset reused builder не доказан, но C143 использует fresh builder на элемент. Прежний C154 сравнивает строку с ride/order, иначе выбирает element. Это аналитическая нормализация, не разрешение ActionType; downstream связи не получают нового coverage credit. Ordinary analytics нельзя переносить на favorites inherited no-op.

Для Юлдаш: OWN_DESIGN_NOT_VENDOR_SPEC, implementation NOT_VERIFIED. Отделить analytics metadata от исполняемого action, явно определить absent/unknown fallback и whitespace/case policy. Все 6 тестов NOT_EXECUTED: T1 missing id_type; T2 ride/order; T3 unknown fallback; T4 whitespace/case; T5 explicit-null на concrete decoder; T6 metadata не авторизует действие. Реальные server values, доставка analytics и runtime acceptance остаются открытыми; дефект не доказан.

Источники: GO-C155-ADDITIONAL-ANALYTICS-CONTRACT-RU.md SHA256 4a06eb6c180f76804832957aaf6aae42cfe2dcba360c1878595485349796bb0a; C155-INDEPENDENT-REVIEW-RU.md SHA256 647d688d4cd54ea19a87c4f54df519fe377e210a2a299870e4191b01f8d853ab.

## BlaBlaCar Выбор строки истории и граница удаления

Независимая QA PASS: 1806 assertions. Full review 21 тело / 1412 DEX instruction units: новые com/searchform UI/business 6/957; support 9/141 отдельно, из них com/searchform 7/97 и generated fv7 2/44; reused 6/314 с нулевым новым credit. Reused включает namespace VM click, не исключительно старый core. Два dispatcher snapshots / 1192 units проверены только в prologue, нужном case и switch payload: они partial, не full coverage и не новый credit. Старый core denominator и глобальный процент не меняются.

