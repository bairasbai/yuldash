Уточнение нейтральных имён C157: localData.y соответствует trackingOnlyFeedback / isInTrackingOnlyFeedbackFlow, A — walkingRouteClosed, соседний z — walkingRouteActive. Имена привязаны к descriptor/constructor/labels, не угаданы по obfuscation. Descriptor indices 25/27 не приравниваются к constructor bits 24/26: полный decoder mapping не пересматривался. Absent выбранных bits даёт false, present сохраняет входное значение.

F() снимает bit24 в copy mask и устанавливает y=true, сохраняя выбранный A; W(value) снимает bit26 и меняет A, сохраняя y. Dataflow до constructor проверен, но остальным полям полного credit нет. F не объявляется единственным writer/reset y; внешние load/reset пути открыты.

f0 true только при AlternativeDestination nonnull, y=false и A=true. a0 сначала вызывает controller.j, затем при true под monitors заменяет localData через F, вызывает h/g и holder.f. False branch условно чистит affiliate cache и вызывает provider.d, не F; последующая multi-order/view policy определяет h0/return. Это порядок локальных действий, не успешное закрытие экрана или server action. Cleanup monitors не даёт rollback уже выполненного присваивания.

t0h.k сравнивает current A с аргументом под monitor: равенство возвращает без g, различие заменяет localData=W(value) под order monitor и вызывает g. Это локальное сохранение, не disk durability/ACK. Status fragment при absent AlternativeDestination пропускает k, не сбрасывая A. При nonnull и override COMPLETE вместе с e<=0 пишет false; иначе true. Полю e не присвоены придуманные единицы/бизнес-смысл; fragment зависит от upstream registers.

Cancel coroutine ожидает f.a(orderId). Returned Result Failure пропускает k/callback и идёт к logger; nonfailure сначала ставит A=false у текущего holder order, затем ожидает onSuccess callback. Thrown exception/cancellation локально не пойманы; callback failure после записи не откатывает A. Контракт f.a/network открыт: nonfailure и имя onSuccess не являются server ACK.

Delayed reset читает AlternativeDestination.e либо 0, переводит int→long и умножает на 1000, ожидает delay. После resume читает local active flag: false пропускает запись, true ставит A=false. AlternativeDestination/generation в этом теле не перечитываются; это ограниченное наблюдение, не доказанная race. Negative-delay semantics, scheduler и owner lifecycle открыты.

C157 теперь читается как not tracking-only → synthetic COMPLETE merge; tracking-only и closed=true → reactivate; tracking-only и closed=false → skip. Локальные флаги не подтверждают геометрию, завершение поездки или серверную отмену. Открыты f.a outcome, delayed job ownership, полная persistence/decoder policy и другие reset/load paths.

Для Юлдаш: OWN_DESIGN_NOT_VENDOR_SPEC, implementation/acceptance NOT_VERIFIED. Все 12 тестов NOT_EXECUTED: T1 F без подмены route-active; T2 W сохраняет tracking-only; T3 equal A без g; T4 три f0 predicates; T5 absent AlternativeDestination; T6 COMPLETE/e<=0; T7 noncomplete/e>0; T8 returned Failure; T9 callback throw после A=false; T10 delayed inactive; T11 changed generation; T12 persistence failure после assignment. Runtime/API/реальные заказы не запускались.

Источники: GO-C158-WALKING-ROUTE-FLAGS-RU.md SHA256 379d974a42fc1967c31c645aeb442754e0ed33d44b2fbe9c06567d4a22a1383b; C158-INDEPENDENT-REVIEW-RU.md SHA256 ec2d6709029ab921d234aa3462a6c5b233357df76a8bf2467b77d845721d84c8.

## BlaBlaCar цена попутки и условия checkout

Статический разбор BBC-F080 от 23 октября 2026: 23 полных тела / 1764 DEX instruction units. Новые UI/business 3/995 составляют один связный сценарий; support 18/745 (7 Pixar constructors/247 и 11 checkout helpers/498) учитываются отдельно, прежние R8 constructors 2/24 имеют zero-credit. Local-new 21/1740 не означает 21 бизнес-фичу. Два частичных R8 snapshot суммарно 1458 units исключены из полного покрытия. Независимая QA PASS: 3201 статическая проверка; общий DEX decoder не является вторым независимым декомпилятором.

Приоритет цены: nonnull tokenizedPrice выбирает Price независимо от priceLabel. Основная цена получает size L, strike=false и contentDescription=null; только при nonnull discount и initialPrice добавляется metadata-price size M, strike=true с a11yLabelInitialPrice. Это переданные props, не наблюдаемое зачёркивание. В text/null ветке скидка не участвует: nonnull label, включая пустую строку, становится Text; null label возвращает null. Semantics вычисляется до выбора цены; ACCENT→ACCENT, DEFAULT→NEUTRAL, неизвестный enum ведёт к terminal helper, но достижимость такого входа не установлена.

Token mapper переносит integer, decimal, currencySymbol и isCurrencyLeft без parsing, округления или расчёта скидки. Constructors/default masks сохраняют соответствующие priceMeta, semantics, strike и description; проверки nonnull не подтверждают денежную валидность. Generic mapper использует size M, neutral addon без metadata и не имеет локального tokenizedPrice null fallback. Отсутствие денежной проверки в renderer само по себе не уязвимость.

Sticky summary независимо выбирает label, sublabel и addon: carpool nonnull, иначе generic. При отсутствии выбранного carpool label и generic модель summary не создаётся в этой ветке; остальные отсутствующие fallback дают null. На условных входах возможна комбинация carpool label и generic addon, но фактический producer, достижимость и ошибочность цены не доказаны. Optional disclaimer проверяется только на null; выбранные поля захватываются callback и передаются ContentUIModel/ItemDefault/PixarItem. Сам disclaimer renderer, фактическая видимость и полнота прочитанных условий остаются открытыми.

Breakdown перебирает priceItems в исходном порядке без суммирования или сортировки. Nonnull additionalInfo выбирает navigation item даже при addon=null, иначе обычный item. Callback захватывает item, затем заново читает additionalInfo и вызывает supplied Function1; конечный consumer, открытие условий и доставка жеста не установлены. В accessibility props входят label, subLabel и ctaA11yLabel, но speech не проверялся.

FreeFeesBanner выбирается по наличию модели, а не по вычисленной нулевой комиссии; mapper переносит label, sublabel, price, priceSecondary. Notes передаются в Secondary, иконка необязательна: INFORMATION→INFO_CIRCLE, COINS→COINS, SECURITY→SHIELD_CHECK, unknown→terminal helper. Наличие banner или CTA не устанавливает право на скидку, отмену или возврат.

Граница результата: подтверждены UI selection, mapping, defaults и локальная передача callback, не источник/актуальность quote, backend arithmetic, допустимость оплаты без цены, actual wrong price, settlement/refund или Compose delivery. Следующий содержательный вопрос: upstream выбор аргументов sticky summary и их связь с одной актуальной версией quote; не расширять generic helpers ради счётчика.

Для Юлдаш предлагается единая quote identity/version и отдельная producer/server проверка денежных полей, согласованности сборов, скидок и условий; это собственные требования, не спецификация BlaBlaCar. Все 9 тестов NOT_EXECUTED: tokenized/label priority и null/empty/blank; discount/initial combinations и a11y; token/currency формат и знак; mixed-source fallback; sticky/breakdown после обновления quote; additionalInfo при addon=null и смене item; banner при nonzero fee fixture; null/empty notes/disclaimer; unknown enums и nullable combinations с проверкой достижимости. Реализация и приёмка не отмечены; APK/UI/API/платежи не выполнялись.

Источники: CARPOOL-PRICE-RU.md SHA256 ed46036c221d520fc6a84b7311f1d4ff9abca1ab766fa4ec2de0e24833e627f3; recovery-checkout-carpool-price-20261023/INDEPENDENT-QA-RU.md SHA256 afe0a9619c9544b0fc77973515d437713b559096cedd775c9f33c1360c85a18c.

## Яндекс Про повтор удаления и обновление summary

Уточнение от 23 октября 2026 к прежнему autopayout delete: новое покрытие 0 full/partial/fragment и 0 байт. Четыре ранее полных тела / 2124 native bytes / 90 immediate branches повторно сверены без нового кредита. Frozen census 5502 файлов, 37 совпадений подтверждает прежние адреса/hashes. Независимая QA PASS: 6784 статические проверки; это локальная native/ASM сверка, не независимое декодирование snapshot и не выполненные UI-тесты.

Public delete создаёт confirm/cancel dialog. После ApiResponseError private _delete создаёт error dialog; Confirm и Retry используют один адрес кода closure 0x7c2e33c, но через разные pool slots и AllocateClosure. Одинаковый code address не означает один экземпляр closure. Callback закрывает modal, прямо вызывает private _delete 0x7c2e410 и явно возвращает null. На этом прямом Retry-пути public delete и повторная confirmation не вызываются; глобальная или серверная политика подтверждения не установлена.

Отличие от toggle: ранее проверенный retry 0x7c26e48 после закрытия modal вызывает PUBLIC toggleEnabled 0x7c23f48. Поэтому он снова проходит проверку входного enabled и условно может открыть disable confirmation. Это различие статических bindings, не рекомендация обходить подтверждение в Юлдаш; null-return callback не означает ожидания завершения private запроса.

Interactor.delete ожидает manager.delete и сохраняет исходный result. Только на Success вызывает entrypoint.refresh, затем восстанавливает delete result и выполняет ReturnAsync именно для него. Между refresh и возвратом нет Await: отдельный refresh Future этим кодом не ожидается. Это не обобщённая гарантия fire-and-forget поведения всего приложения.

VM._delete ожидает interactor и при Success строит success dialog, но не дожидается отдельного refresh. Переиспользованный callback закрытия/выхода ожидания не добавляет. Поэтому success UI не подтверждает актуальность entrypoint summary, а успешный ответ удаления настройки не устанавливает фактическое прекращение будущих или уже принятых выплат. Runtime, серверные финансовые свойства и generic navigation здесь не закрыты.

Для Юлдаш: отдельно определить retry policy, состояние операции удаления и состояние обновления summary. Четыре предложенных теста NOT_EXECUTED: direct delete Retry; отличие от public toggle Retry; задержанный refresh при успешном delete result; refresh failure после delete Success. implemented=false, accepted=false. Новых API/финансовых действий, APK/UI execution или реализации Юлдаш не было.

Источники: recovery-autopayout-delete-closure-20261023/REPORT-RU.md SHA256 ca7336caf55681f1bb904dfba2aa145321d44bd8c973b9372a11eb6d7bc6a448; AUTOPAYOUT-DELETE-CLOSURE-INDEPENDENT-RU.md SHA256 a8dfaf5caa0c852f7fa8b46dae962635e38fca98083d35ab3c491547985be221.

## Яндекс Go отмена walking route и владельцы задержки

C159 от 23 октября 2026: 26 FULL + 2 fragments, 440 selected instruction units = 366 bounded-local-new + 74 reused; 593 raw, 153 excluded. Распознавание 404 и scope stop имеют prior overlap; большие host lifecycle тела не засчитаны целиком. Census ограничен доступными 1008 JSON и 0 source.text, не всей историей. Независимая QA PASS: 3442 статические проверки, положительные архивные совпадения сверены; полный отрицательный scan 1008 повторно не выполнялся. Это не второй декомпилятор и не runtime-тесты.

Cancel repository создаёт p48(orderId), вызывает lazy AltpinApi.a и await network adapter. Normal direct/resumed return даёт Unit без анализа payload или состояния транзакции. В точных try regions [42,45) и [57,82) CancellationException повторно бросается; другой Exception превращается в Unit при распознанном 404, иначе в Result.Failure. 404 распознаётся как GoApiHttpException.c()==404 либо Retrofit HttpException.a()==404; Error и ошибки вне этих regions не объявляются нормализованными.

Continuation возвращает COROUTINE_SUSPENDED без упаковки, иначе оборачивает результат в Kotlin.Result. Поэтому success-ветка C158, локальный сброс walkingRouteClosed и onSuccess могут достигаться как после normal adapter return, так и после 404-normalization. Это локальное решение клиента, не server ACK и не отмена самой поездки; endpoint, auth и backend outcome остаются открытыми.

Processor хранит scope d. Attach сначала создаёт/записывает scope x, затем запускает attach coroutine; launch handle в этом теле не сохраняется. Создание scope не отменяет прежний x этим телом: локальный reattach guard не установлен, но duplicate attach в runtime не доказан. Цепочка: provider holders flow → outer flatMapLatest → для каждого holder его flow → filter → mapLatest callback → merge/emitAll. Callback захватывает emitted TaxiOrder и processor; это object capture, не доказанный keyed job registry или общая DI identity.

Filter пропускает downstream только при nonnull AlternativeDestination.InfoBlock, effective override COMPLETE и e>0. Он расположен ДО per-holder mapLatest: непроходящее следующее состояние не отправляется этому latest operator. Поэтому один такой emission не доказывает отмену прежней задержки; outer-list replacement, scope cancellation и другие эффекты могут вмешиваться. Реальная race или неверный reset не установлены. C158 delay-body переиспользован; mapLatest/flatMapLatest wiring подтверждено до internal.g с SUSPEND/context, но worker cancel/join и scheduling пустого merge не разобраны.

Два конкретных host stop fragments вызывают f4q0.b: main presenter.If после super через R→processor.d и order presenter.If через v0.common.f.d→processor.d. Для второго механически проверено сохранение регистра между выбранными ranges, но исключённые lifecycle действия или их ошибки могут помешать достижению вызова. При x nonnull b вызывает cancellation helper, не обнуляет x и не делает локальный join/await. Cancel request не означает завершение jobs; два пути не доказывают двух одновременно активных owners.

Для Юлдаш: OWN_DESIGN_NOT_VENDOR_SPEC, implementation NOT_VERIFIED. Разделить нормализацию ответа, локальный reset, серверный исход и completion barrier; явно определить повторный attach и account/order/revision ownership. Все 12 тестов NOT_EXECUTED: normal adapter return; GoApi 404; Retrofit 404; другой Exception; CancellationException; callback error после reset; eligible holder delay; следующий filtered emission; outer-list replacement; host detach cancel request; repeated attach; независимое отслеживание завершения worker. Runtime reachability, current account/revision fence, фактическая доставка/отмена и приёмка не установлены; APK/API не запускались.

Источники: GO-C159-WALKING-ROUTE-CANCEL-OWNER-RU.md SHA256 76274460549eb41c01e93d71b63588984ca256508ea4028d4a1944f2bdd857ac; C159-INDEPENDENT-REVIEW-RU.md SHA256 0c792318632198f98cac4c5cdfe3159a56390c9d49894bd138abc9712d8afec0.

## BlaBlaCar подтверждение карточной формы и выбор провайдера

Разбор от 23 октября 2026: 18 FULL / 709 DEX instruction units, без partial. First-party 15/687: новое business тело coroutine 1/80, прежние 14/607 zero-credit. External helpers 3/22 отдельно: прежние 2/12 и новый throw-on-failure helper 1/10, не first-party/business credit. Local-new 2/90 не означает два новых платежных сценария. Novelty проверена по 11597 source.text и ledgers; старый полный wrapper не содержал нового invokeSuspend. Независимая QA PASS: 13328 статических assertions, общий DEX decoder не второй независимый декомпилятор.

Caller читает cardFormInput один раз; null/invalid возвращает без dispatch. Valid устанавливает Loading, захватывает ссылки input/VM/Activity и запускает coroutine. Constructor/create не делают deep copy. Собственного in-flight gate в этом handler нет, но renderer gating, сериализация и серверная идемпотентность этим не опровергнуты. Повторное списание не установлено.

Новая coroutine использует captured input, но CardNav выбирает из текущих flowNav/currentStep/context/cardFormType при initial execution. Для provider требуется ровно один соответствующий NativePaymentMethodNav, не первый совпавший: дубликат вызывает IllegalArgumentException, отсутствие ведёт в прежний terminal helper. Input здесь не перечитывается и повторно не валидируется. Это разные моменты чтения input и context, не доказанная race, mutation или ошибочная цена.

State 0 выполняет выбор; state 1 проверяет failure-wrapper и завершает Unit без повторного CardNav. Перед Gr4vy вызовом L$0 нового dispatch явно равен null, label=1: nav в нём не сохранён. COROUTINE_SUSPENDED возвращается как есть, normal return/resume ведёт к Unit. Adyen вызывает синхронный клиентский dispatch. Иное continuation state и null/unsupported CardNav ведут к IllegalStateException, но production достижимость этих путей не установлена. Локальных catch/finally/reset loader в новом теле нет; поведение внешнего scope/handler не раскрыто.

Прежний Gr4vy callee, без нового кредита, отдельно сохраняет provider nav/input и восстанавливает их после suspend. AuthCanceledByUser result сбрасывает Loading, но не равен CancellationException. Error создаёт RestartFlowEvent с текущим VM.flowNav без прямого reset в этой ветке; downstream restart остаётся границей. Success вызывает payAfterGr4vyTokenization с сохранёнными nav/input. Exceptions здесь не превращаются автоматически в Error value, try regions отсутствуют.

Прежний Adyen callee передаёт mapped input encryption interactor: Result.Failure идёт в error handler, Success передаёт encrypted entity, исходный input и nav дальше. setButtonLoading(false) читает текущую форму дважды для label/enabled, не captured input старой операции. onActivityDestroyed вызывает gateway.dispose, но не доказывает завершение каждой coroutine или отсутствие позднего callback.

Valid form означает допуск к dispatch; tokenization/encryption success означает следующий клиентский этап; Unit означает normal completion выбранного тела. Ни одно не равно авторизации платежа, settlement или подтверждённой брони. SDK, криптография, HTTP, server idempotency, Activity lifetime и downstream terminal outcome не установлены. Отсутствие локального reset/catch не доказывает зависание loader всего приложения. Карт, секретов, SDK/API calls, APK execution и даже synthetic payment execution не было.

Для Юлдаш предлагается operation/quote identity, явное восстановление UI и раздельные состояния токенизации, платежа и брони; это собственная спецификация. Все 8 тестов NOT_EXECUTED, реализация/приёмка не подтверждены: null/invalid/valid form через mocks; смена input/flow до coroutine start; ноль/одно/два provider matches и unsupported; Gr4vy synchronous/suspend/failure-wrapper/cancellation; поздний Success/Error после смены flow; double-click и неопределённый outcome с отдельной server idempotency; destroy/dispose и late callback; Adyen/Gr4vy intermediate success отдельно от terminal payment/booking. Реальные реквизиты и платежи не требуются.

Источники: PAYMENT-CONFIRM-RU.md SHA256 e7ad7e1f144e6b7e985be4779567902a6990397876abb47594ba668eae1eb8ce; recovery-payment-confirm-dispatch-20261023/INDEPENDENT-QA-RU.md SHA256 8684417d52ae9076f93204cfeebf7aea754298c7f0bffbf2e12494a2405d1229.

## Яндекс Про выбор опоздания и отмены рабочей смены

Локальный статический разбор от 23 октября 2026: 21 новое полное тело / 3616 native bytes, 145 прямых переходов; 0 partial/fragment. Frozen novelty по 5535 прежним файлам даёт 0 address/hash hits. Shared UI/framework и прежние manager/API тела имеют нулевой новый credit. Независимая QA PASS: 7820 assertions по ASM/SQLite/ELF и binding, не независимое декодирование Dart snapshot. Строка о pending QA в sealed исходном отчёте предшествует этому отдельному PASS.

Sheet передаёт callbacks content/actions/wrapper/theme; wrapper создаёт IsolatedProviderScope с пустыми overrides. Вновь созданный nullable selection holder начинается с null; Body и Actions используют общий provider. Это не обещание нового holder при каждом reopen. Body оборачивает lateness payloads и отдельно добавляет CancelOption после списка, поэтому пустой список диапазонов сам по себе не исключает вариант отмены.

Radio передаёт входной option, row tap собственный variant. onChanged(null) возвращает без очистки предыдущего выбора; nonnull вызывает соответствующий setter. Setter создаёт новую обёртку с прежней ссылкой payload и emit, не deep copy. Сам выбор не вызывает reportLateness или cancelShift.

Actions захватывает context и selection. Null selection даёт null callback, хотя BNGButtonState имеет значение enabled; при nonnull создаётся submit closure. Конкретный shared StateButton обнуляет callback для loading/disabled, а для enabled использует переданный; opacity зависит от effective callback. Это конструкция UI gate, не запросный mutex или гарантия доставки жеста/exactly-once.

Submit через Navigator.of передаёт captured selection в pop; selector -0xea5 разрешён для NavigatorState CID87306, не для всех runtime subclasses. Closure не перечитывает holder и не вызывает manager/API. Данные относятся к последнему построению callback; delayed tap и смена selection требуют runtime проверки, а не автоматически считаются stale-selection ошибкой.

Прежний domain consumer ждёт nullable результат: null dismiss заканчивает без команды; nonnull сначала отправляет UI analytics, затем заново читает current shiftId и отдельно прекращает путь при null ID. Аналитическое событие не подтверждает команду. Варианты формы и свежий ID прочитаны в разные моменты, но фактическое рассогласование не доказано. Pattern map различает lateness/cancel, неожиданная subclass вызывает StateError, не default команду.

