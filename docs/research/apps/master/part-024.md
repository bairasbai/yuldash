Другие terminal events вызывают YourRides, Search либо передают Uri.parse результата в universal-link navigator. Новый consumer не проверяет booking/payment status и не выполняет commit/cancel/refund. Это не утверждение об отсутствии проверок у получателя или backend; Uri.parse сам по себе не доказывает доверенность redirect.

## Доставка событий и границы

Event подключён через lifecycle helper с аргументом STARTED. В выбранном composable ненулевое значение передаётся в handleEvent без локального event acknowledgment или generation guard. Реальная повторная навигация при recomposition не доказана: полная семантика helper и runtime timing не исследованы.

Concrete runtime navigator, leaf widgets, destination status fetching и server booking/payment/refund outcome остаются открытыми. Это не полный E2E confirmation protocol и не runtime-дефект.

## Проверка и учёт

Независимая bytes/claims/novelty QA PASS; report/manifest pins и 33 хэша совпали. Новое: 3 полных consumer business bodies, 469 DEX code units. Отдельно 15 supporting/wiring тел, 209 units; они не превращаются в 15 продуктовых функций. Один прежний REA wrapper и 9 prior-full тел используются с нулевым новым credit. Source-binding 28 тел не является итогом новых бизнес-методов. Aggregate/global coverage не изменена; code units не source LOC.

## Собственные требования Юлдаша и непроведённые проверки

Разделять авторитетный booking outcome, локальное представление и одноразовую навигационную команду. Типизировать bookingId/tripOfferId/flowId/paymentAttemptId. Дополнительная форма не делает бронь paid или verified; navigation side effect должен иметь event identity и поколение flow, а частичный успех двух переходов должен обрабатываться отдельно.

Предложены, но не выполнены проверки выбора content; повторной доставки/recomposition/recreation; позднего события другого flow; разных и некорректных ID; ошибки каждого navigator; допустимости redirect; перечитывания authoritative статуса после перехода к списку. APK, JVM, SDK и внешние API не запускались; код Юлдаша не менялся.

# BlaBlaCar Граница дальнейшего booking reconciliation

Сверка ближайших кандидатов после success/navigation не дала нового бизнес-чтения: 20 кандидатов состоят из 17 prior-full DEX тел, двух прежних REA-reviewed тел с уточнённой DEX-привязкой и одной no-code declaration. Независимая QA сверки PASS; 14 хэшей комплекта совпали. Новых semantic bodies и global coverage credit нет. Пробел exact index сам по себе не означает непрочитанный код.

Открыты связь navigator с фактическим destination/lifecycle, точная interactor/repository/DI-связка, свежесть и версия authoritative snapshot, runtime-доставка, серверная идемпотентность, конкурентные confirm/cancel/expiry и финансовый settlement. Это ограничение текущего доказательства, не новый feature finding, не 100% покрытия и не доказанный дефект.

Собственное требование Юлдаша: отделять response loaded от authoritative outcome и settlement, связывая восстановление с account, booking ID, operation ID и revision. Проверки lost-response, позднего refresh, конкурентных операций и устаревшего offline snapshot лишь предложены, не выполнены.

# Go C73 Конкретный получатель результата отмены

## Проверенные локальные переходы

Для modal-входа C68 доказан конкретный receiver fv10. Ошибочный callback возможен ещё до отправки нижней cancel-команды: при недопустимом историческом DriveState либо отсутствующем userId. Поэтому такой callback не равен серверному отказу.

Уточнение Pending-операций C72: создание объекта Pending не означает добавление в список. Начальный TaxiOrder.a добавляет запись; TaxiOrder.J на завершении/error удаляет первое совпадение по predicate. Это локальный cleanup, не новая отправка отмены.

Успешный callback проходит ещё один Zone-gate, затем проверяет HashSet по текущему объекту TaxiOrder. Raw class metadata подтверждают отсутствие переопределённых equals/hashCode: при стандартной семантике HashSet это identity экземпляра, не orderId/operationId-дедупликация. Два объекта одного заказа не обязаны считаться одним. Продолжение после успешного нового добавления в HashSet здесь не разобрано; rollback, срок жизни и persistence множества не доказаны.

Error callback сначала удаляет запись map по текущему orderId, затем под monitor меняет localData через C(false), после чего выбирает прежний consent-flow либо error-dialog. Удаление map-записи не является удалением заказа из durable storage. Возврат в consent не доказывает автоматический resend или новое платное согласие.

Выбор consent использует текущие CancelRules и order-state; dialog дополнительно учитывает переданный исторический DriveState. Эти состояния читаются отдельно. Точный смысл copy-флага и действие positive-button не установлены; отсутствие fragment может закончить обработчик без показа dialog.

## Проверка и границы

Независимая QA PASS, финальный report pin и семь manifest entries совпали. Bounded final report, не frozen checkpoint: 14 выбранных методов, 12 полных локальных тел, 2 фрагмента и 358 instruction records. Raw capture 18 тел/1 805 records не равен прочитанному объёму. Прежние checkpoints и ledger C01–67 не пересчитаны.

Доказаны receiver, guards и локальный порядок вызовов, но не серверная комиссия/отмена, вся fresh-success ветвь, runtime-гонка или восстановление после restart. APK, API и SDK не запускались.

## Собственные требования Юлдаша и непроведённые проверки

Связывать финализацию с orderId+operationId и revision, а не идентичностью объекта. Различать RemoteOutcomeUnknown, CancelConfirmed и LocalFinalizationPending; UI error не превращает Unknown в Rejected. Подтверждённая отмена запускает повтор только локальной финализации, а изменённая цена требует нового согласия.

Десять сценариев предложены, не выполнены: callbacks с одним/разными объектами; смена holder/revision; отказ локального guard до отправки; исключение после STARTED; ошибка callback; Zone-gate; правила null/free/unknown; историческое против текущего состояния; ошибка после map-remove при отсутствии fragment; новый quote и старая кнопка.

# Go C74 Кнопка ошибки: monitoring вместо прямого resend

## Проверенные статические факты

Кнопка error-dialog из C73 получает owner и holder через контейнер qcr, а не вызывает метод qcr19. Её Runnable сначала обрабатывает текущее состояние заказа, затем заново читает holder. Для состояния вне FAILED/CANCELLED/EXPIRED/COMPLETE вызывается восстановление monitoring с нулевой задержкой и текущим orderId. Прямого cancel-send в этой выбранной nonterminal-цепочке нет.

Boolean результата monitoring здесь игнорируется. Регистрация локального tracking и вызов resumeMonitoring не гарантируют свежий серверный snapshot или успешный reconciliation. Это не replay того же cancel request и не подтверждение нового исхода отмены.

Terminal-ветви заканчиваются вызовами popup либо ранее разобранной локальной finalization-цепочки; дальнейшие эффекты здесь не раскрыты. На них нельзя переносить отрицательный вывод о cancel-send из nonterminal-ветви. Локальный HashSet использует экземпляр TaxiOrder, не orderId/operationId; жизненный цикл множества и rollback добавления не установлены.

Связанный dialog-handler исполняет Runnable прежде условного dismiss. Вокруг run нет catch/finally: исключение может прервать дальнейшее закрытие. Default auto-dismiss, debounce timing и фактическая доставка клика не проверены. Закрытие dialog не означает успех отмены.

## Проверка и ограничения

Независимая QA PASS; report pin и все шесть manifest entries проверены. Bounded final report: 23 выбранных метода, 20 полных локальных тел, 3 фрагмента, 396 instruction records. Raw extraction 884 records не объявляется изученным целиком. Прежние C12/C72/C73 используются без повторного зачёта; cumulative ledger не изменён. Это не frozen checkpoint, runtime-доказательство или durable recovery. APK, API и SDK не запускались.

## Собственные требования Юлдаша и непроведённые проверки

Разделить acknowledgement ошибки, RetryCancel и ReconcileOrder. Unknown, operationId/orderId/revision сохраняются независимо от dialog; возобновление наблюдения не должно автоматически повторять платную команду. Terminal outcome запускает повторяемую локальную финализацию с дедупликацией по операции, не объекту.

Восемь сценариев предложены, не выполнены: nonterminal и terminal состояния; смена holder до клика; false/exception monitoring; отсутствующий persisted order после регистрации; повторный объект/клик; исключение Runnable при разных auto-dismiss; новая цена при Unknown.

# BlaBlaCar Уточнение generated-DI границы refresh

Для VM, создаваемой исследованным generated Hilt provider, теперь замкнута статическая цепочка refresh → RidePlanPassengerInteractor → RidePlanPassengerRepositoryImpl. Проверены конкретный provider id 157, его сохранение в поле, выбранная factory-ветвь, dependency-аргумент VM и repository slot interactor. Это уточняет одну ранее открытую wiring-границу, а не вывод по имени интерфейса.

Связь success navigator с фактически выбранным экраном и lifecycle по-прежнему не доказана. Generated construction не подтверждает запуск refresh, свежесть snapshot, runtime-доставку, ownership/version, бронь, оплату или серверную идемпотентность. Прежний Result.Success не становится confirmed/paid/settled.

Независимая QA PASS; report/manifest pins и 27 хэшей проверены. Отдельный локальный объём: 8 полных wiring bodies и 4 wiring fragments; 6 prior-full DEX и 1 prior REA body переиспользованы без нового зачёта. Новых бизнес-функций и global coverage credit нет; остальные границы предыдущей заметки сохраняются.

Для Юлдаша предложен один отдельный production-DI тест: refresh consumer получает ожидаемый repository adapter, context/direction не подменяются, результат не повышается самовольно до authoritative outcome. Тест не реализован и не выполнен; код Юлдаша и runtime приложения не исследовались.

# Go C75–C77 Локальное завершение отмены и финансовая граница

## C75 Terminal callback и локальная деактивация

Выбранный terminal callback подставляет CANCELLED, в отличие от исторического DriveState обычного callback C73. После object-identity guard прослежены маршруты до прежнего local deactivate-контракта C58 и отправки UI-уведомления order_cancel_confirmed. Это не новая server-cancel команда, не доказательство физического удаления из БД и не финансовое подтверждение. Часть popup/feedback-продолжений остаётся за границей.

Числовой predicate проверяет CANCELLED и значение, не близкое к нулю: zero означает abs(x)<0.000001; при zero(u) выбирается k, иначе u. Это не проверка положительности или оплаты: отрицательные значения вне порога, NaN и infinity не считаются zero в выбранном bytecode. Поступление таких значений не доказано. Разные ранние ветви обходят общий UI-хвост, а ошибки после локальных мутаций не доказывают rollback.

Объём C75: 17 выбранных методов, 15 полных локальных тел и 2 фрагмента, 340 instruction records. Прежние deactivation/Zone-контракты повторно не зачтены.

## C76 Точные cost/final_cost и потеря признака отсутствия

Generated serializer связывает k с cost, u с final_cost. Это отдельные поля, не cost_left_to_pay и не discount. При необработанном соответствующем decoder index результирующее primitive double становится +0.0; после успешного создания DTO отсутствовавшее значение и явно декодированный ноль в этих полях неразличимы.

В consumer C75 epsilon-ноль final_cost включает fallback к cost. Поэтому fallback не различает отсутствие final_cost и явный ноль; малое ненулевое значение тоже может попасть в эту ветвь. Encoder использует другое, точное Double.compare с +0.0 либо ответ F(), а не epsilon. Это generated contract, не установленная политика конкретного JSON runtime.

Названия cost/final_cost не доказывают комиссию, оплаченную сумму, долг, возврат или settlement. Единицы, валюта, серверная трактовка, null/nonfinite и duplicate/unknown-key политика не установлены.

Объём C76: 8 методов, 3 полных малых тела и 5 фрагментов, 487 выбранных records. Механическая проверка переносов регистров в большом decoder не является полным семантическим аудитом 5 741 raw records.

## C77 Payment reference и reason не заменяют финансовый итог

В выбранной cancellation-цепочке отдельный подтверждённый финансовый итог не установлен. Исследован соседний first-party consumer payment_changes: он ищет reason.code=DEBT_ALLOWED и дополнительно проверяет локальный Boolean заказа. Это конкретный клиентский predicate, не доказанный долг или settlement. Отсутствие match означает false этого predicate, не отсутствие реального долга.

Payment DTO содержит type, payment_method_id, withdrawAmount и has_limits. Строка withdrawAmount не доказывает списание; в этом четырёхпольном serializer нет отдельного typed transaction-status. Это не утверждение о всех моделях приложения.

Соседний consumer списка прекращает обработку при terminal predicate, включая CANCELLED, и поэтому не является доказанным post-cancel settlement источником. Его порог длины списка не является operation ID или server revision; Runnable и фактические последствия dispatch не исследованы.

Объём C77: 16 методов, 14 полных локальных тел и 2 фрагмента, 342 выбранных records. Пять triage-only кандидатов исключены. Upstream списка, смысл локальных флагов, конкретный decoder и authoritative финансовый provider/ledger остаются открытыми.

## Проверка и самостоятельные требования Юлдаша

Все три final bounded reports имеют независимый QA PASS; финальные report hashes совпали с RELEASE и QA, все manifest entries проверены. Это не новые frozen checkpoints, не whole-app/runtime аудит и не cumulative unique прирост: ledger C01–67 сохранён без пересчёта. APK, API и SDK не запускались.

Для Юлдаша предлагается разделить RideCancellationOutcome, LocalDeactivationPending, PaymentReference и FinancialOperationOutcome. Missing, Present(0) и Present(amount) должны различаться; валюта, точная денежная шкала, источник и revision явны. Подтверждённая отмена при неизвестном финансовом исходе не повторяется: отдельно сверяется та же финансовая операция, а локальные эффекты завершаются идемпотентно по orderId+operationId.

В каждом отчёте предложено по восемь сценариев, ни один не выполнен: terminal/UI ветви и частичные мутации; missing/zero/epsilon и codec boundaries; DEBT_ALLOWED/local flags, terminal suppression и поздний финансовый результат. Это проектные предложения, не описание или изменение кода Юлдаша.

# Pro Детализация дохода: локальные parser-контракты

В шести прочитанных телах with_count независимо извлекает обязательные total_price, count и price_per_item, без локального умножения или сверки. income_total и bonuses_total также поступают отдельно; bonuses_details обязателен как List, null не заменяется пустым списком. Эти parsers не суммируют детали для проверки итогов. Это не утверждение об отсутствии проверки downstream или на сервере и не доказательство денежного дефекта.

Dispatcher выбирает with_count, with_time либо Other. Other сохраняет неизвестный/пустой строковый type; null получает other, non-string не проходит String? cast. Дополнительные поля неизвестного варианта этим parser не сохраняются.

count и time_in_seconds принимаются как num, затем числовой helper формирует значение для целочисленного поля. Конкретные округление, переполнение и non-finite handling не установлены. Type cast не равен проверке целочисленности, финансовой согласованности или settlement.

Объём: шесть новых full-local dependency-limited parser тел, 2 896 bytes; три прежних тела переиспользованы с нулевым новым credit. Новизна ограничена проверенным набором prior claims, общая coverage не пересчитана. Независимая семантическая claims-проверка прочитала шесть тел и подтвердила выводы; полного независимого native-byte/novelty QA не было. Техническая byte-проверка выполнена автором исследования, 13 хэшей финального комплекта совпали. Эти уровни проверки не смешиваются.

Собственные требования Юлдаша: отдельно задавать отображаемые суммы, авторитетный расчёт и payment status; явные валюту/версию/округление, unknown-variant политику и допустимые count/time. Формулу count×unit=total или отношение бонусов к доходу нельзя навязывать без собственного бизнес-контракта скидок и округлений.

Предложены, не выполнены проверки вариантов type, обязательных полей/списка, дробных и граничных count/time, допустимых и недопустимых расхождений totals/details, устаревших версий и валют. Downstream отображение, числовые helpers и серверный расчёт остаются открытыми. APK, API и SDK не запускались, код Юлдаша не менялся.

# BlaBlaCar Завершение экрана отмены подписки

В состоянии Content CTA получает closeDashboard callback. Ранее проверенная VM публикует локальное событие; новые consumers фрагмента и общей VM доводят его до Activity finishWithSuccess: setResult(-1), затем finish(), без Intent payload. Это локальный экранный результат, не доказательство серверной отмены, прекращения автопродления или возврата денег.

Retry имеет отдельный callback и передаёт прежний UUID в получение confirmation context, не в новую cancel-команду. Back отдельно вызывает OnBackPressedDispatcher и становится no-op при отсутствии Activity/dispatcher. Показ Content, повтор чтения context и завершение Activity нельзя объединять в один серверный успех.

Producer/consumer types статически связаны, но регистрация observer Activity, lifetime событий, concrete DI и caller activity-result handling не закрыты. Не доказаны обязательная доставка, однократность, фактическое закрытие экрана или получение caller актуального subscription snapshot. Shared widgets и effect/recomposition policy тоже остаются границами.

Независимая QA: PASS_WITH_SCOPE_LIMITATIONS. Проверены 35 DEX identities; новый first-party scope разделён на 9 полных business/consumer тел и 17 supporting тел. Три prior-full тела имеют нулевой credit; три R8 constructors и три fragment-only dispatch учитываются отдельно, без first-party credit. Это один сценарий, не 26 новых продуктовых функций; global index не изменён.

Report и independent-QA pins, 44 хэша комплекта совпали. QA использовала тот же supplemental decoder с независимой проверкой raw class_data, instruction hashes и switch slots; exhaustive независимого поиска всех альтернативных недокументированных prior reviews не было. Это не runtime или whole-app проверка.

Собственные требования Юлдаша: различать cancel mutation, context read и закрытие информационного экрана; RESULT_OK не повышает финансовый исход до confirmed/refunded. Caller должен отдельно сверять нужный account/subscription context, а callbacks close/retry/back иметь разные контракты.

Предложены, не выполнены проверки Content/Loading/Error; UUID A/B и effect key; ошибки context после совершённой mutation; stop/start/recreation и повторного CTA; RESULT_OK без financial payload; отсутствующей Activity при Back. APK, SDK, JVM и API не запускались, код Юлдаша не менялся.

# Go C78 Учёт уведомления о смене оплаты

Диалог и альтернативное уведомление учитываются по-разному. После обработки диалоговой модели localData.v получает captured размер payment_changes, в том числе когда текущая модель признана совпадающей и новая не поставлена в очередь. Это не подтверждение показа, прочтения или согласия пользователя.

Повторный guard capturedSize>v расположен до monitor и внутри него не повторяется. После локального присваивания вызывается прежняя enqueue/save boundary, не доказанный durable commit. Реальная гонка или уменьшение счётчика в runtime не установлены.

