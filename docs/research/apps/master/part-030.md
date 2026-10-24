Methods передаются при открытии модали как захваченная ссылка, а selected method поступает отдельно через StateBuilder. Это не глубокая копия и не доказательство неизменности коллекции. Обновление selected state само по себе не заменяет methods/is_valid; list build перебирает переданные platform/other collections и вызывает itemBuilder, не читая current preview. Внешняя замена widget или мутация shared object не исключены и не доказаны, поэтому runtime stale modal из этой границы не следует.

В локальных initState/dispose находятся onShow/onClose, без собственного preview subscription, selected reset или отмены updateSelectedMethod. Это не отсутствие дочернего/framework/route cleanup. Ручная selection-цепь не содержит прямого pop/dismiss, но внешние listeners могут влиять на закрытие; утверждение «модаль всегда остаётся открытой» не установлено.

Promo и bottom-action callbacks на нормальном пути сначала вызывают Navigator dispatch, затем строковый handler без Await. Для указанного candidate receiver GDT связывает dispatch с pop, но фактический receiver и принятие pop не доказаны. Возврат из dispatch не означает завершённого transition/dispose; исключение может прервать последующий handler. Bottom action читает link из текущего widget.methods при вызове, не из нового preview-holder.

Самостоятельные предложения для Юлдаша: явно определить обновление коллекции открытой модали отдельно от выделения и повторно проверять eligibility/version при подтверждении. Непроведённые тесты: methods M1→M2 с изменением is_valid, selected-only rebuild, замена ссылки против shared mutation, ручной выбор и отмена, pop accepted/rejected, delayed dispose и handler exception. Runtime stale selection, обход UI gate, race, серверная авторизация и финансовый результат не установлены; APK/API и реальные выплаты не запускались.

## Go C93 Вход email ссылки не означает подтверждение адреса

Ограниченный статический final report получил независимый PASS: 21 полное тело и 3 фрагмента, 467 выбранных записей = 455 новых + 12 reused. Ещё 652 raw records исключены из семантического зачёта; два selected payload не являются бизнес-сценариями. Это не frozen checkpoint, не полный email audit и не пересчёт глобального покрытия.

Локальный matcher сравнивает Uri authority с email без учёта регистра. Выбранная ветвь создаёт пустой marker без извлечения token, адреса или query и без записи verified state. Scheme/path/fragment этим matcher не проверяются, но upstream intent/routing/auth могут ограничивать достижимость: произвольный URI не объявляется допустимым входом. Authority не подменяется понятием host.

Email handler сначала запрашивает refresh из C92, затем ждёт отдельное startup/lifecycle условие и передаёт navigation action. Он не ждёт GET result или refresh=false, не читает email status и не проверяет token. Normal return typed handler, refresh request и navigation request поэтому не означают EmailVerified или показ экрана. Producer startup и downstream navigation остаются открытыми.

Оба прочитанных status consumer используют локальный cache: для непустого email только status=1 даёт обычное представление без требования подтверждения. Старый consumer читает email/status отдельно; новый mapper также учитывает profile mode и отдельный boolean, но renderer permissions и атомарность combine не установлены. Blank email и неизвестный статус не являются одним состоянием. Профильный refresh имеет собственный capability predicate; отсутствие именно этого gate у link handler не доказывает обход upstream/server authorization.

Самостоятельные предложения для Юлдаша: разделить LinkParsed, RefreshRequested, NavigationRequested и авторитетный EmailVerified; определить URI/token/account contract и account-scoped snapshot с revision/freshness/outcome. Непроведённые тесты: регистр authority и upstream scheme/path allowlist, active refresh/offline/cancel при link entry, старый cache, все status при blank/nonblank email, смена аккаунта и поздний ответ, разные профильные capability gates. Реальная verification-ссылка может обрабатываться сервером до возврата в приложение; token expiry/replay, ownership, доставка и retention из этих тел не восстановлены. Реальные ссылки/token/API не открывались, APK и отправка писем не выполнялись.

## Go C94 Локальное прерывание заказа при долге

Ограниченный статический final report получил независимый PASS: 22 полных тела и 4 фрагмента, 406 выбранных записей = 368 новых + 38 reused. Четыре payload не являются отдельными сценариями; 1275 прочих raw records исключены из семантического зачёта. Это не frozen checkpoint, не полный аудит заказа и не пересчёт глобального покрытия.

Проверенный gate относится к одному клиентскому этапу. Вне PaymentState DEBT/PROCESSING он возвращает false. При включённом локальном model flag исключениями служат preorder.f0 и DebtsState.PROGRESS. Происхождение flag как experiment или серверного разрешения не установлено; несколько чтений flow не образуют доказанный атомарный snapshot.

Null и empty rules различаются: при null list fallback допускает прерывание для оплаты, не равной CASH; при nonnull list неизвестный resolved type и пустой список дают false. Иначе требуется rule boolean=true и exact type code, а для nonnull selected id ещё совпадение id. Null selected id пропускает сравнение id, пустая непустая по null-проверке строка сравнивается обычно. Эти локальные правила не определяют серверную авторизацию заказа.

При true caller не вызывает continuation.m и запускает асинхронную подготовку debt UI. Перед navigation action coroutine ждёт геоконтекст; нормальное завершение подготовки, freshness/accuracy location и фактический показ UI не гарантируются самим return true. В выбранных телах подготовки нет собственного catch/finally, но runtime зависание этим не доказано.

Callback при включённом flag пишет preorder.f0=true для результата, отличного от tl90, ещё до дальнейшей классификации. Это исключение для последующего gate, не запись «долг оплачен». Разные result types ведут к разным continuation callbacks; их полный смысл, b6r0.b и происхождение результатов остаются открытыми. Reset callback ownership стоит после вызова на нормальных путях, не в finally; реальная ошибка или гонка не установлены.

Самостоятельные предложения для Юлдаша: разделить ClientPrecheck, ServerEligibility, UIRequested/UIShown, решение пользователя и финансовый outcome; связывать callbacks с попыткой и гарантировать cleanup. Непроведённые тесты: DEBT/PROCESSING×flag×f0, null/empty rules, unknown type и null/empty id, изменение snapshot, geo/cancel/navigation failure, все result types, callback exception и повторный preorder. Полный pipeline/DI, server acceptance и погашение долга не доказаны; APK/API, реальные геозапросы и платежи не выполнялись.

## BlaBlaCar Представление условий отмены

Ограниченная независимая QA PASS_WITH_SCOPE_LIMITATIONS: 8 новых полных тел / 471 instruction units, из них 6 бизнес-тел / 401 и 2 конструктора / 70. Два прежних init / 52 units остаются zero-credit; namespace не расширен, глобальное покрытие не пересчитано. Исходные DEX bindings и полные байты проверены, novelty ограничена ledger/REA корпусом; общий decoder не является второй независимой декомпиляцией.

Mapper переносит тексты, пассажира и сегмент из navigation-модели, не рассчитывая стоимость отмены. Header добавляется всегда; между сегментами и между условиями внутри сегмента используются разные разделители. Basic paragraphs идут затем без собственного добавления разделителей. Name=null выбирает paragraph, тогда как любое nonnull имя, включая пустую строку, выбирает info. Сортировки, дедупликации и денежного расчёта в выбранных телах нет.

Для сегмента берётся carriers[0] без локальной проверки размера: используются имя первого перевозчика и nullable logo.lightUrl, не остальные перевозчики или darkUrl. Это локальное предусловие, не доказательство достижимого пустого списка или runtime crash. Brand tag light/dark преобразуются независимо; цветовые строки передаются без парсинга. Upstream invariants, renderer fallback и доступность остаются открытыми.

В начальной модели комментария title/placeholder/CTA используют fallback только при null, не при пустой строке. Awareness=null задаёт danger=true, nonnull — false без чтения его полей. Comment max переносится без локального clamp; defaults задают error=null, loader=false и пустой comment. Эти поля не доказывают input enforcement, обязательное предупреждение, согласие или отправку отмены.

Самостоятельные предложения для Юлдаша: явно определить контракт carriers, связь условий с пассажиром/сегментом и различия null/empty; отделить danger-стиль от подтверждения последствий. Непроведённые тесты: 0/1/2 carriers, пустые/повторные условия и разделители, name=null/empty, awareness null/non-null, отрицательный/нулевой max, nullable темы и некорректные цвета. Context helper, CTA logic, observer/DI lifecycle и renderer не замкнуты; успешная отмена, цена, возврат и settlement не доказаны. APK/JVM/Android и продуктовые API не запускались.

## Яндекс Про Обновление реквизитов после события

Ограниченный статический разбор получил независимый PASS: 12 новых полных локальных тел / 2636 native bytes, 153 direct branches и 4137 механических проверок. Generic broker, forwarders, navigation и прежние payment-цепи остаются zero-credit. Novelty ограничена проверенным корпусом, route objects сверены с hash-bound PP source; глобальный coverage не меняется.

Завершение Launcher.launch в проверенных promo/bottom handlers само по себе не вызывает local refresh или событие. Отдельный typed FintechInstantV4PayoutRefreshRequisitesEvent запускает refresh при соответствующей подписке. Найден конкретный V4 emitter, но цепь от произвольного deeplink/возврата приложения до него не замкнута. Launch completion, регистрация реквизита и обновление preview не тождественны.

Event callback не Await-ит refresh. Balance listener запускает его только при Selected, тогда как сам refresh после Await account-flag читает текущий balance и допускает Selected либо LoadingWithData. При иных состояниях следует ранний выход без task/navigation/story. Initial preview использует отдельный optional request argument, остальные состояния передают null; этот аргумент не назван суммой без schema binding. Snapshot account flag/ids передаётся в task, но scheduler/generation и runtime identity остаются открытыми.

Task выдаёт Loading, затем Success response → Data, ApiResponseError 409 → Conflict, другой ApiResponseError → Error. Неизвестный тип response локально не даёт дополнительного terminal emit; внешняя error policy и допустимые типы не закрыты, поэтому вечный spinner не заявляется. После нормального Await task route /check приводит к запросу reset /preview, не к подтверждению выплаты. Последующий story handler заново читает текущий Data и optional link; уникальность launch и принадлежность тому же ответу не гарантированы этим чтением.

Dispose последовательно ждёт cancellation-dispatch каждой subscription и очищает поле, затем ждёт state manager dispose. Собственного finally и сбора всех ранее запущенных callback Futures в выбранном теле нет, но runtime race или отсутствие cleanup в зависимостях не доказаны.

Самостоятельные предложения для Юлдаша: отдельные состояния для launch, operation-completed event, preview refresh и банковского ACK; определить correlation/invalidation и coalescing refresh. Непроведённые тесты: возврат без события, duplicate event/init, событие до init/после dispose, смена balance во время Await, success/409/error/unknown/throw, /check против других routes и story link. Upstream emitter, URL policy, scheduler и серверная авторизация открыты; APK/API и реальные выплаты не запускались.

## Go C95 Действия окна долга и продолжение заказа

Ограниченный статический final report получил независимый PASS: 13 полных тел и один фрагмент, 209 выбранных записей = 181 новая + 28 reused. Analytics prefix, topup implementation и прочие исключённые записи не получают семантического зачёта; полный dispatcher Qf не объявлен закрытым. Это не frozen checkpoint и не обновление глобального покрытия.

CONTINUE_CREATE_ORDER производит vl90 отдельно от PAY_DEBT. В контексте C94 vl90 при локальном flag=true может вызвать continuation.m, но не содержит receipt оплаты. При flag=false выбирается другое продолжение. Значение preorder.f0 после результата остаётся локальным исключением gate, не подтверждением погашения или серверного допуска.

SwitchPaymentMethodAction создаёт ul90 с payment snapshot: null или неизвестный type в выбранной ветви дают CASH fallback, известный type сохраняет id. CASH snapshot отличается от отдельного unspecified snapshot с null type. Допустимость наличных, UI-достижимость такого action и согласие пассажира этим не установлены; downstream b6r0.b не объявляется финансовой операцией.

CLOSE и dismiss производят sl90; выбранная PLUS_TOGGLE ветвь также отправляет sl90 без доказанной subscription mutation. Общий result dispatcher сначала вызывает callback, затем запрашивает закрытие. Собственного finally нет: исключение callback может прервать close request в этом теле, но фактическое зависание или повторная доставка не доказаны. Request закрытия не равен завершённому dismiss и не отменяет уже отправленную платёжную команду.

Дополнительные выбранные actions открывают payment methods, историю или URL; navigation не означает финансовый ACK. В локальной URL-ветви Uri.parse/ACTION_VIEW не доказывают полный allowlist, однако upstream/platform validation не закрыта и уязвимость не заявляется. Topup, полный modal wiring, continuation m/n, account ownership и server eligibility остаются открытыми.

Самостоятельные предложения для Юлдаша: typed outcomes для ContinueWithDebt, смены оплаты, dismiss и SettlementConfirmed; явное подтверждение CASH fallback, operation-scoped callbacks и recovery закрытия. Непроведённые тесты: Continue против PayDebt, known/null/unknown method, CASH против unspecified, callback null/throw/normal, repeated/late result, unsupported action и полный URL allowlist. APK/API, реальные ссылки, платежи и профильные действия не выполнялись.

## Яндекс Про Курсор обновления реквизитов

Ограниченный статический разбор получил независимый PASS: 10 новых полных локальных тел / 1944 native bytes, 106 direct branches и 4035 механических проверок. Supporting forwarders, generic handle и прежний V3/V4 emitter остаются zero-credit; novelty ограничена проверенным корпусом. Enum/index и native comparison сверены с исходными метаданными; глобальный coverage не меняется.

План refresh представлен nullable enum-cursor view(0), list(1), instantPayout(2), а не тремя независимыми dirty-флагами. NeedsRefresh(page) требует ненулевой cursor и current.index≤page.index. MarkNeedsRefresh ставит view; markRefreshed(page) выбирает следующий enum относительно переданного page, после instantPayout — null, без перечитывания текущего cursor. Монотонность при произвольном порядке pending tasks не установлена.

Внутренний refresh выбирает одну приоритетную ветвь: доступный view, затем list, затем instantPayout, с исключением closedPage и ранним return после выбранной ветви. Во всех трёх путях продвижение cursor вызывается и Await-ится до downstream dependency resolution/refresh. Локального rollback при последующей ошибке в этом теле нет, но потеря обновления или runtime race не доказаны: scheduler, identity и другие producers остаются открытыми.

Public refresh сначала отмечает view, затем запускает план. Dispose view/list обращаются напрямую к внутреннему refresh с closedPage, не выставляя dirty заново, и не Await-ят cleanup/refresh в собственных телах. Закрываемая страница исключается даже при ещё available scope; завершённый dispose или очистка всех зависимостей из этого не следуют.

В payout-ветви cursor становится null до вызова ранее изученного manager, посылающего V4 refresh event. Null означает локальное продвижение плана, не доставку события, новый preview или успешное изменение реквизитов. Отдельный subscriber Future и банковский outcome этим advance не подтверждаются.

Самостоятельные предложения для Юлдаша: отдельно учитывать бизнесоперацию, dirty-plan, доставку invalidation и подтверждённый fresh preview; определить retry/ACK смысл продвижения cursor. Непроведённые тесты: полная cursor/page матрица, scope priorities и closedPage, ошибка после advance, queued markNeedsRefresh/markRefreshed, delayed/throwing cleanup, отсутствие subscriber и failed preview. Все edit/delete/set-default producers, queue policy, DI identity, process death и backend eligibility не замкнуты. APK/API/runtime и выплаты не запускались.

## BlaBlaCar обновление сессии и границы совместного ожидания

Статически проверены 6 новых полных coroutine тел, 506 instruction units; 5 прежних тел на 166 units и 7 helper/accessor тел имеют нулевой новый кредит. Независимая проверка PASS_WITH_SCOPE_LIMITATIONS сверила исходные DEX identities и байты; общий decoder не является второй независимой декомпиляцией. Новизна ограничена сопоставленным корпусом, глобальные показатели и namespace inventory не изменены.

В одном экземпляре репозитория refreshSessionDeferred повторно используется только при non-null, r()==false и isCancelled()==false; иначе создаётся новый объект, который захватывает producer. Освобождение mutex вызывается до ожидания выбранного deferred. Очистка continuation slots не обнуляет поле репозитория. Точная полная семантика предикатов, scheduler, mutex и deferred остаётся открытой: это не доказательство глобального single-flight, fairness или завершения при любой отмене.

Producer передаёт нормальный результат и Exception разными путями, игнорируя возвращённые Boolean. Handler ловит Exception, а не любой Throwable; отдельной ветви CancellationException в нём нет. Эти локальные границы не доказывают deadlock, вечное ожидание или гарантированный повтор.

Public-session ветвь выбирается до запроса при null/blank refresh token. Ошибка refresh endpoint в проверенном wrapper не переключает выполнение на public fallback. updateSession вызывается только после нормального возврата выбранной ветви и её mapping, до передачи нормального результата ожидающим. В этом wrapper не найдено сравнение с исходным account/version перед записью; ownership и guards SessionStateProvider, logout, persistence, DI lifetime и другие writers не замкнуты. Account-switch race или результат серверной авторизации не установлены.

Самостоятельные предложения для Юлдаша: явно определить scope совместного refresh, политику public fallback, завершение ожидающих при ошибке и account/version guard при применении сессии; исключать credentials и token values из логов. Непроведённые тесты: reuse/replacement матрица, Exception и cancellation, отказ запуска producer, ошибки mapping/updateSession, смена аккаунта и несколько repository instances. Секреты и реальные token values не извлекались; API и приложение не запускались.

## Яндекс Про ответы изменения реквизитов и отметка обновления

Ограниченная статическая проверка: 12 новых полных локальных тел, 2652 native bytes и 141 direct branch target; независимая QA PASS с 4177 механическими проверками, не runtime-тестами. Новизна ограничена проверенным корпусом; прежние ModalManager, cursor и edit-state context имеют нулевой новый кредит, глобальный coverage не пересчитан.

В проверенных edit, set-default и delete response handlers ApiResponseSuccess сначала приводит к success dialog. Dirty/refresh находится в callback кнопки onTap, а не автоматически в завершении transport. Переданный isDismissible=false сам по себе не доказывает поведение всех системных Back и accessibility путей или фактический показ диалога.

