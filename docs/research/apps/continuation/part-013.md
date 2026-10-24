GO-C144-FAVORITE-DECORATOR-RU.md SHA256 40bf6331e5a24fe04e0b60ada37774b43af46985b10145085364dc1cca248e6e; C144-INDEPENDENT-REVIEW-RU.md SHA256 37d58d2b34bc43da8c191fded33a7248d19c25380b24cb4cb81b039826048681. Независимый PASS2212 assertions,38 branch operands,270 pool references, raw exception tables и identity traces. Ожидание QA в исходном отчёте закрыто отдельным PASS. Не второй декомпилятор, не runtime тесты.

Охват13 методов=11 FULL+2 FRAGMENT;285 selected decoded records=250bounded-local-new+35reused;451raw/166excluded. Не строки и не глобальный процент. Новизна относительно доступных SOURCES/prior-context до C143/C67; scan1008JSON без source.text не вся история. Byte/register проверки между выбранными fragments не расширяют semantic credit.

Уточнён C143 hook только для стандартных FavoriteEditFragment и FavoritesFragment factories: оба создают FAVORITE/cqq0(false), передают hbo.b в g7g; hbo.b оборачивает v031. Его fbo.a содержит только return входного builder, не читает source и не меняет поля. Coordinator getClass не означает передачу coordinator как decorator. Constructor fragment сохраняет hbo в g7g.w; selected factory trace w→hbo.a→nvb0 fbo→favorites presenter проверен без clobber регистров, nvb0 сохраняет fbo в e. Другие DI операции не объявляются разобранными.

Это identity для двух проверенных factory paths, а не всех configurations/overrides. Снимается именно неопределённость mutable hook из C143 на этих входах; positional index не становится server-stable ID между списками и глобальные alias/mutation гарантии не появляются.

Полный AdditionalSuggest helper записывает groupId→y, marker m4w0.a→x (не current-location l4w0), d()→B, t=false. Nullable action при наличии создаёт mo0(ActionType,Uri): null/empty string→null, непустая→Uri.parse. Отсутствие action пропускает E write, не очищает существующий E. Первый nullable text container переносит a→c и b→g только при nonnull b; второй a→d и b→e, включая null. Отсутствующие containers не blanket-clear.

Два image containers дают i/j: null/empty string→null, непустая→converter d5y0.a; metadata через suu.g→z/A. Converter validation/cache/network semantics не исследованы. Nullable button создаёт ul0 с текстом, resource/style integer, size integer и action; отсутствие nested action даёт null ActionType/Uri. XS/S/M/L→0/1/2/3, null/unmapped→0, это UI-коды, не физические размеры. ACTION→controlMain, прочее/null→controlMinor. Enum initializer ловит только NoSuchFieldError, не все ошибки. Nullable kl0.a переносится в C; при отсутствии записи нет.

В helper нет writes builder.a(id) или builder.v(Address), собственного catch, URI execution или scheme allowlist. Это не уязвимость: downstream executor не разобран. Constructors mo0/ul0 сохраняют аргументы, не выполняют actions. В стандартном C143 mapper builder свежий на каждом item; skip-write отличается от очистки reused builder. Runtime nullability/parser exceptions не проверены.

Собственные требования/тесты Юлдаш OWN_DESIGN_NOT_VENDOR_SPEC: явная factory/decorator identity, раздельные fresh/reused defaults, URI parsing отдельно от разрешённого выполнения, nullable field policy, size/style mapping отдельно от rendering. Реализация NOT_VERIFIED, приёмка NOT_EXECUTED.

Все10 proposed tests NOT_EXECUTED: T01 два default identity paths; T02 alternative decorator без identity assumption; T03 absent action skip E на fresh/reused builders; T04 null/empty/nonempty URI и отдельный execution gate; T05 различие nullable text writes; T06 image empty/converter failure; T07 size XS/S/M/L/null; T08 ACTION/main vs other/minor; T09 helper сохраняет id/Address; T10 независимая scheme/route policy и runtime acceptance.

Открыты downstream click/action executor, ActionType routing/scheme validation, kb1.c ro0→AdditionalSuggest, UI binding и динамическая достижимость. APK/product API/runtime не запускались; безопасность, successful delivery и server-stable ID не заявляются.

## BlaBlaCar: repository implementation и Result/error mapping · 23.10.2026

REPOSITORY-RESULT-RU.md SHA256 41bcc834e8bd5c815388f4375eb611de66a91d1bc368566f84590f270f973d6a; INDEPENDENT-QA-RU.md SHA256 84825769fd88d310b46331f1c6a16cb477934fa23e1b0e3f05c6bb892e443cdc. Независимый PASS1711 статических проверок, fresh decode/class_data binding, hashes и implements evidence; общий decoder, не второй независимый декомпилятор. Отдельный PASS закрывает ожидание QA в исходном отчёте.

13 полных тел /1220 instruction units полностью повторные, новый business/support credit0. Проверены1512 prior ledgers и11597 source.text. Structural implements evidence не новое body coverage; namespace com/searchform и denominator неизменны.

SearchRepositoryImpl реализует SearchRepository по DEX interfaces. Constructor сохраняет datasource, query/response mappers и DomainExceptionMapper; datasource хранит endpoint. Это не active runtime DI binding SearchInteractor к этому экземпляру. Цепь реализации: domain query mapper→datasource→SearchEndpoint.search interface→response mapper→Result.Success. Resume search(null,continuation) использует continuation branch, null здесь не новый бизнес-input.

Repository try ranges [48,51) и[63,100) покрывают resumed failure helper, initial query mapping, datasource, response cast/mapping и Success construction. CancellationException явно rethrow; остальные caught Throwable→Failure. Initial failure helper60, повторная Success упаковка118, DomainExceptionMapper136 и handleError140 вне этих ranges: нельзя обещать превращение любого исключения в recoverable Failure. Mapping errors не обязательно сетевой отказ; неизвестный Result вариант error path, не fallback success.

Special mapping требует APIError,HTTP400,nonnull content, затем unchecked Edge cast. Через два p71.N читаются type/code, но полный алгоритм списка helper не разобран. malformed_request+forbidden_intra_city_carpooling→ForbiddenCityException; malformed_request+same_from_and_to→SameDepartureArrivalException с прежним message; прочее passthrough исходной DomainException. Wrong subtype условно может бросить, но реальная parser-достижимость не доказана; DomainExceptionMapper/Edge cast ошибки вне repository catch scope.

Query mapper: null/length0 хотя бы одного place ID включает ОБА address аргумента, оба непустых ID дают null addresses; IDs не сбрасываются. Whitespace не blank/trim. Nullable dates передаются DatesParser без доказанного timezone/wire format. Передаются seats/supply/cursor/UUID/range; отдельная LinkedHashMap filters сохраняет values, ключ строит helper с f[ и ], точный arbitrary-key stringify OPEN. Passengers сначала selected-only, затем неразобранный mapPassengers; null передаётся helper. Journey/outbound поля отдельно. showNativeAd null→false; feature keys ad_placements_displayed,stay-supply,supply-choice-V2,search-fake-ab-test через mapFeatures, окончательные null/string правила OPEN. Прямых getters координат/country/domain.searchRange в этом теле нет, не глобальное неиспользование.

Datasource передаёт data fields и decode outbound source в default endpoint helper с mask2048: 12-й аргумент searchOrigin=null. Bit8192 не включён, supplied journeyStep НЕ заменяется ONE_WAY. Последний вызов интерфейсный; endpoint annotations,implementation/baseURL,serialization,auth,retry/cache и реальный HTTP не установлены.

Response mapper сохраняет UUID, порядок tabs и один add на successful iteration без local filter/dedup/default tab; resultsContent маппится, tracking передаётся ссылкой без отправки, featuredTrip/details nullable. Facets guard отсутствует перед getDetails. Supply All/Carpooling/Bus/Train→ALL/CARPOOL/BUS/TRAIN, subtype Default→DEFAULT; unknown supply error path, не default; local count range validation нет. ResultsContent независимо маппит nullable results и emptyState, локального XOR нет; parser и внешние model invariants OPEN. Response mapping exceptions могут войти в repository catch, но это не гарантия recoverable UI.

Собственные требования Юлдаш, implementation/acceptance NOT_VERIFIED: сохранять cancellation, различать mapper и network failures, безопасно обрабатывать subtype/unknown/multiple errors; определить null/empty/whitespace и paired-address policy; отдельно date/timezone, selected passengers, feature/filter encoding; задать empty tabs/unknown supply/facets/results invariants. Result.Success не rendering/tracking/booking success.

Все10 proposed tests NOT_EXECUTED: T01 mock normal/suspended success; T02 cancellation rethrow vs caught Throwable; T03 error mapper/handleError throw вне catch; T04 HTTP400 пары и subtype/empty/multiple entries; T05 id null/empty/whitespace/valid и оба адреса; T06 null dates/timezones; T07 passengers/filters/features helpers; T08 mask2048 и сохранённый journeyStep; T09 tabs/unknown supply/count/facets; T10 results+emptyState обе null/non-null и malformed date. Без реальной сети.

Открыты active DI, DomainExceptionMapper/Edge subtype, date/passenger/feature serializers, endpoint wire contract и downstream UI. APK/HTTP/API/runtime/rendering не запускались; реальные server responses и дефекты не заявляются.

## Яндекс Про: Taxi callback → parent holder drop → container disposal · 23.10.2026

REPORT-RU.md SHA256 75b8f889531f20d2928a13d8864eb9bb553e2d1a879d71996e5e0be0bac986db; AUTOPAYOUT-PARENT-TEARDOWN-INDEPENDENT-RU.md SHA256 23937d4a316573170e6b66325508b725f671760bb66e7f5c887b331a0f6c0391. Независимый PASS5841 assertions,34 native branch/call decodes, ELF/hash/fields/callback epilogues и dispatch bindings. Ожидание QA в исходном отчёте закрыто отдельным PASS; это не повторное независимое Dart snapshot декодирование и не runtime тесты.

5 новых full-local dependency-limited тел /552B: parent.dispose236B, Taxi rawAsyncDep declaration160B, dispose/init callbacks по48B, late getter60B. По5232 frozen prior-файлам exact address/hash hits0 без drift. Base disposal и generic yx_scope ранее полные, credit0. Большой Taxi initializeQueue только selected ownership edge как контекст без нового full/partial byte credit; Master/Eats/остальные modules не расширены.

В выбранном Taxi product path ServiceScopeModule регистрирует factory и явные init/dispose closures через rawAsyncDep, не default dynamic adapters. Dispose callback вызывает конкретный parent.dispose и возвращает неизменённый Future; init аналогично возвращает parent.init. Отсутствие собственного await в callback не fire-and-forget. Generic forwarding сохраняет callbacks в AsyncDep init/dispose fields; конкретные dispatch bindings проверены, shared accessor/hook semantics не угадываются по имени.

Late getter ServiceScopeModule.1c3 инициализирует dependency только при Sentinel, не создаёт controller при каждом чтении. Selected Taxi queue edge получает module/getter, добавляет dependency в Set и queue. Это конкретное host scope ownership, но внешний пользовательский event полного Taxi shutdown не установлен.

Parent.dispose читает controller.7; при null создаёт provider container и сохраняет его, затем в обеих ветвях читает holder provider, вызывает _drop, явно await-ит и только после normal completion вызывает base RiverpodFeatureController.dispose. Available-only guard отсутствует, в отличие от autopayout child. Early/repeated dispose не гарантированно harmless no-op. Направление доказанной цепи holder drop→provider-container disposal, НЕ provider disposal→holder drop.

Локального catch/finally к base cleanup при ошибке create/read/drop нет; внешняя обработка OPEN. Generic _drop при Disposing/None возвращает сразу: await своей invocation не join уже идущего другого disposal. Initializing default path ждёт изменения state с прежними условиями; это не child Available/non-null guard. Даже normal completion не гарантирует успешную очистку каждого dependency при разных failure policies.

Reused base.dispose сохраняет old container, публикует null в stream, обнуляет controller.7, затем при old nonnull вызывает hook, children cleanup и ProviderContainer.dispose. Исключения hook/child/stream могут прервать normal path, полной cleanup гарантии нет. Старый holder factory не содержит local onDispose(holder.drop); bounded grep другого onDispose в CurtainManager не доказывает отсутствие всех иных registrations.

Уточнённая цепь: Taxi dependency disposal→Future-returning parent callback→await holder._drop→base provider disposal. В parent drop зарегистрирован autopayout child с прежним Available-only dispose и manager.close. Page.cancel, parent.dispose, scope teardown, result delivery и server rollback не взаимозаменяемы; реальная race/финансовый результат не доказаны.

Предложения Юлдаш,6 тестов, все NOT_EXECUTED; implementation/acceptance NOT_VERIFIED: T01 drop до container cleanup; T02 exception до base cleanup; T03 null-container early/repeated dispose; T04 concurrent drop при Disposing, без ложного join; T05 Initializing parent vs Available-only child; T06 Future propagation через host callback. Код Юлдаш и production app не менялись.

Конкретный callback/order ограниченно закрыт. Открыты внешний Taxi shutdown trigger, scheduling относительно init, hook/children failure integration. Дальнейшее generic DI расширение не требуется ради объёма. APK/UI/product API/финансовые операции не выполнялись; runtime/production guarantees отсутствуют.

## Яндекс Go C145: shallow ro0→AdditionalSuggest и разные идентификаторы · 23.10.2026

GO-C145-FAVORITE-ADDITIONAL-CONVERSION-RU.md SHA256 dcdbb73beaccfb0d8d06a1f87dcacf3ce94ae3ac125eb07377a0ed15ff213936; C145-INDEPENDENT-REVIEW-RU.md SHA256 fb0bc67fede60a925a0587eb1f9fd7394ebbd4e0d894359913dd10c554735ec8. Независимый PASS863 assertions,53 pool references, byte/field/register transfer checks. Отдельный PASS закрывает ожидание QA в frozen report. Не второй декомпилятор и не runtime тесты.

Охват22 FULL тела /85 selected=85bounded-local-new, raw85/reused0/excluded0, fragments0. Из22 методов19 двухинструкционные getters, остальные converter и2 constructors: это НЕ22 бизнес-сценария. Novelty только доступные ledgers до C144/C67 и1008JSON без source.text, не вся история и не глобальный процент. C143/C144 contextual mapper bodies не входят в85 новых records.

kb1.c создаёт новый outer AdditionalSuggest, получает10 ссылочных значений через getters и передаёт constructor без branches, explicit null checks, catch, sorting, normalization или deep copy. Null входной ro0 разыменовывается первым virtual getter; сохранение nullable payload не определяет допустимость null самого input.

Точное соответствие: ro0.a(field)→id; b→itemIndex; c→groupId; e→action; d→analytics; f→lead; g→leadBody; h→trailBody; i→trailButton; j→trail. Все10 ro0 getters и9 выбранных AdditionalSuggest getters напрямую читают/возвращают reference. AdditionalSuggest.d() именно String id, не display label. Null nested values остаются null без defaults; outer allocation не immutable snapshot вложенных данных.

Связь с C143/C144: mapper создаёт builder с локальным индексом обхода; helper переносит AdditionalSuggest.id→builder.B и groupId→y, не читает itemIndex и не пишет positional builder.a. String id, nullable исходные itemIndex/groupId и локальный positional index различны. Сохранение itemIndex не доказывает его downstream/UI использование, index не stable business key при reorder.

Собственные требования Юлдаш OWN_DESIGN_NOT_VENDOR_SPEC: не смешивать идентификаторы, явно задать shallow identity/null policy, не переставлять lead/trail и body fields, отдельно гарантировать reorder selection и разрешённое action execution. Реализация NOT_VERIFIED, приёмка NOT_EXECUTED.

Все6 proposed tests NOT_EXECUTED: T01 различие String id/upstream itemIndex/groupId/local index; T02 новый outer/same nested references; T03 nullable payload без defaults; T04 lead/trail и body mapping; T05 reorder без positional stable-key assumption; T06 независимая URI/action/button policy и runtime acceptance.

Открыты источник/serialization ro0, action/button executor и validation, UI reordering/selection correctness. Converter не выполняет сеть/action и не валидирует адрес. APK/API/runtime не запускались; server keys, signed data, immutable snapshot, UI reachability и стабильность id не гарантированы.

## Яндекс Go C146: ITEM/TRAIL callbacks и условный no-op избранного · 23.10.2026

GO-C146-FAVORITE-ADDITIONAL-ACTION-RU.md SHA256 19ca8dc10a49da832bcfac5d0728028def17dd85c008b5d78eb83e3297bc2b99; C146-INDEPENDENT-REVIEW-RU.md SHA256 4b1b8745eaede42e64237833254b15b8d57e6b27fc5162599b7114fcce023ff2. Независимый PASS6327 assertions,112 branch operands,928 pool references и raw class_defs/class_data virtual-resolution checks. Ожидание QA в отчёте закрыто отдельным PASS. Не второй декомпилятор и не runtime тесты.

16 методов=11 FULL+5 FRAGMENT;199 selected decoded records=187bounded-local-new+12reused;1346raw/1147excluded. Synthetic bodies не засчитаны целиком. Novelty только доступные ledgers до C145/C67 и1008JSON без source.text, не глобальный процент. Exploratory другой presenter i исключён, его action execution не приписывается favorites.

ITEM selected holder path регистрирует hc discriminator6 через setDebounceClickListener. Arm читает captured model.D:mo0; null→return без L6. Nonnull→текущий holder.Q, captured model.A/B и TapArea.ITEM→L6. Это чтение callback в момент Runnable, не гарантия свежести и не race proof. Debounce framework/интервал/exactly-once не разобраны.

TRAIL: model.M true уходит superclass, эта ветвь OPEN. Иначе после очистки trail при model.C:ul0 и nonnull title запускается suspend lambda; null C уходит в исключённый image/text path, null title возвращает. Full lambda после успешного formatted-text conversion создаёт ButtonComponent, title/margins, size либо0 и nonnull background resource. Нижний renderer и расписание/scope не гарантируют показ. При null action Runnable не устанавливается, но button добавляется в trail; это НЕ доказанный disabled state. Nonnull action регистрирует k9 discriminator3, который передаёт captured action/id/type через текущий Q и TapArea.TRAIL в L6.

После suspension в выбранной lambda нет local rebind-generation check или собственного catch. Это ограниченная граница, не установленная stale-binding ошибка; lifecycle/cancellation rendering остаются OPEN. Listener constructor сохраняет Q; o05 хранит view и без подмены четырёх аргументов передаёт L6→onAdditionalItemSelected.

AddressSearchView читает текущий presenter и виртуально вызывает lg. Подтверждённая DEX hierarchy favorites.b→h81→search.presenter.f: первые два lg не определяют, base f.lg состоит только из return-void. Поэтому ЕСЛИ данная holder wiring используется с этим favorites presenter, callback заканчивается без action в inherited lg. Concrete holder factory/selection и server/runtime выдача AdditionalSuggest в favorites пока не установлены. Это не доказательство видимой неработающей кнопки/UX-дефекта; исполняющий override другого presenter не заменяет этот conditional path.

Не смешивать builder/model: builder.E:mo0→model.D; builder.D:ul0→model.C. Callback использует String model.A/B как additionalId/additionalTypeId, не positional int model.a. Старые constructor mapping bodies остаются reused context.

Собственные требования Юлдаш OWN_DESIGN_NOT_VENDOR_SPEC: различать ITEM/TRAIL и их nullable actions; доказать конкретную holder/presenter wiring перед выводом об execution; владеть async binding и callback identity; отдельно задавать enabled appearance и runtime reachability. Реализация NOT_VERIFIED, приёмка NOT_EXECUTED.

Все9 proposed tests NOT_EXECUTED: T01 null ITEM без dispatch; T02 nonnull ITEM identity/area; T03 absent TRAIL model/title без button через selected path; T04 null TRAIL action без Runnable, но возможный button; T05 TRAIL id/type/area; T06 favorites base no-op отдельно от search override; T07 holder binding после suspend; T08 отсутствие listener не disabled appearance; T09 отдельная runtime/server acceptance. APK/API/runtime не запускались; delivery/action/rendering guarantees не заявляются.

Открыты concrete holder factory/adapter selection favorites, runtime AdditionalSuggest выдача, другой search action handler и lifecycle formatted-text conversion. Generic UI framework не расширен ради счётчика.

## Яндекс Про: toggle rule, disable-confirm и retry · 23.10.2026

REPORT-RU.md SHA256 0c4f8ccf8ae6646d7f41d44a594685b193ecc7958cd8c5447e015320c84ea46b; AUTOPAYOUT-TOGGLE-CONFIRMATION-INDEPENDENT-RU.md SHA256 5364b31a9a37a3e782ada54bd1c3445a3265e83069461005af034890a324a901. Независимый PASS7184 assertions,132 native branch/call byte checks, source/ELF/stack/boolean bindings. Ожидание QA закрыто отдельным PASS. Это настройка правила автовывода, не перевод денег; не повторное Dart snapshot декодирование и не runtime приёмка.

Новый охват5 full-local dependency-limited тел /3120B: public toggle620B, private mutation/result1772B, retry212B, success action304B, confirm212B. По5264 frozen prior-файлам5 address hits относятся лишь к enclosing annotation общего старого callback, не full body/review public toggle; новых annotation-hash hits0. Старые interactor/state-manager/API/delete/common callback zero-credit, framework/context не переучитываются.

Public toggle читает VM input→Autopayout.is_enabled. False прямо вызывает private mutation и await; true строит disable-confirm и ждёт modal/route Future. Cancel только закрывает слой, не mutation. Confirm закрывает legacy/new слой, затем вызывает PRIVATE mutation; retry закрывает error dialog, но вызывает PUBLIC toggle и заново выбирает confirm/direct по текущему input. Оба callbacks явно заменяют возвращённый x0 на null: mutation Future вызывающему не передают/не await-ят. Это отличается от ReturnAsync propagation, но не доказывает необработанный runtime error.

Context хранит VM reference, не immutable bool/id/revision snapshot; private позднее читает VM input снова. Из этого не следуют ни transactional confirmation binding, ни доказанная смена input в runtime. Confirm args dismissible true и legacy onWillPop читает flag; modal completion не успешная mutation.

Private сначала telemetry, затем interactor(input) и await. Старый manager shallow-copy инвертирует bool, сохраняя id/properties references, и обновляет config; interactor refresh entry-point запускает только при success, не ждёт его завершения перед возвратом результата. Success проверяется как ApiResponseSuccess<Autopayout>; null payload ведёт явному NullError. Nonnull returned is_enabled определяет enable/disable success UI и success telemetry, не исходное направление tap. Это response config, не future payout/refresh readiness/server immutability.

Обе success ветви используют один void callback и dialog flag=false; legacy явно isDismissible=false/onWillPop, но общий new-route renderer здесь не переаудирован, все platform dismissal paths не запрещены доказательством. Callback один раз сохраняет VM.navigation wrapper/CID: сначала legacy dispatch либо popWhere, затем тот же wrapper dispatch либо canPop→pop/_popRoot. Вложенные поля могут перечитываться, deep snapshot нет; это не ровно два снятых экрана и не подтверждённая навигация.

