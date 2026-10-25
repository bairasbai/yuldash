# Сценарии и проектная симуляция

## Что проверяет этот документ

Ниже выполнен текстовый разбор предложенных правил (`DESIGN_SIMULATION`, авторский tabletop). Это проверка логики проекта на бумаге, не запуск приложения, API, банка, теста или реального заказа. Все исполняемые тесты и runtime-сценарии остаются `NOT_EXECUTED`. Независимый проверяющий повторяет следы и отмечает найденные противоречия отдельно.

Каждый след обозначает W workflow, C custody, P деньги (в R2 всегда vector obligations/attempts, не один paid), O физическое обязательство, A полномочие. Старое P=not_started означает отсутствие verified receipts, не отсутствие договорного долга. Отсутствие банковской интеграции сохраняется как `P=manual_unverified/not_started`, а не выдуманный capture. Принятый план не даёт разрешение проводить реальные деньги.

## SIM-01 Два курьера принимают одновременно

1. W=searching, v10; C=sender; P=not_started; O=active; A=два eligibility-approved offers одной generation. Оба видят предложение, но ни один ещё не владеет вещью.
2. A принимает: row lock/CAS/ёмкость → W=assigned, v11, C=sender; assignment A. Событие/outbox/receipt одной транзакцией.
3. B доходит после lock: `VERSION_CONFLICT` или `OFFER_REVOKED`, без второй assignment/fee/message. C и P не меняются.
4. Ответ A потерян: прежний key → committed A, не повторный accept. Старый offer UI B не оживает после позднего push.

Закрываемая дыра: broad feed/CAS одного status недостаточно для отдельной lease и capacity нескольких режимов. CP-07/08; T-CON-01.

## SIM-02 Принятие отмена и получение борются за версию

1. W=assigned, v20, C=sender; клиент открыл cancel preview v20; курьер начинает pickup challenge v20.
2. Если cancel commit первым: W=canceled, v21, C=sender; challenge revoke, pickup получает conflict. Курьер не получает команду забрать отменённую вещь.
3. Если pickup proof commit первым: W=collected, v21, C=carrier; cancel v20 отклонён. Новый preview объясняет возврат, не «отмена выполнена».
4. O=return_required только после разрешённого request_return; P=компенсация по согласованным правилам, не автоматическое полное списание.

Невозможно гарантировать физический исход одним API: если вещь передана до потери ответа, курьер обязан сохранить custody и открыть reconcile/case, не бросить её из-за UI. CP-02/09/12; T-CON-02.

## SIM-03 Потеря ответа до и после commit

1. Create key K, quote Q; W не существует, C=sender, P=not_started.
2. Сбой до commit → ни заказа, ни outbox/charge; GET K не находит committed. GET=unseen ещё не исключает поздний original. Повтор K допустим только с точным body/hash и действующим intent; без body требуется atomic close C3.
3. Сбой после доказанного commit → заказ и receipt существуют. Рестарт восстанавливает K, GET K/заказ; повтор не создаёт twin.
4. K+другой quote → 409, новый смысл требует нового явно подтверждённого intent. Logout удаляет secrets/PII, сохраняет bounded unresolved metadata привязанным к actor anchor; поздний результат не рисуется другому аккаунту. Close и apply под одним lock дают applied либо closed_no_effect, не оба.

Существующий 60-second semantic dedup полезен, но не закрывает этот след после долгого offline. CP-07; T-OFF-01/02.

## SIM-04 Повторный чужой и поздний webhook

1. P=authorization_pending для payment X order1. Приходит event E дважды → одна проверенная запись, одно событие.
2. E с чужим merchant/account/currency/order → quarantine/security alert, никакого paid.
3. X outcome_unknown → резерв удержан, Y на тот же остаток запрещён. Если X ранее подтверждён failed_no_effect, Y разрешён; противоречивый late captured X → F12 bounded allocation, void/reconcile Y, external surplus отдельно refundable clearing, не второй courier payable.
4. Outbox повторился после worker crash → дедуп consumer, courier payable начислен один раз. O определяется выполнением, не webhook.

CP-14; T-PAY-01/02. Реальная схема закрыта provider gate.

## SIM-05 Получатель и отправитель недоступны

1. W=dropoff_arrived, C=carrier; P по утверждённой схеме; O=active. Контактные попытки/ожидание подтверждены, новый долг не превышает заранее принятого cap.
2. Невручение → delivery_exception; support/отправитель выбирает допустимый return; W=returning, O=return_required.
3. Отправитель тоже недоступен → case с responsible owner, next_action_at, approved safe location; C остаётся carrier до confirm_storage_handover. После authorized proof W=stored, C=storage_custodian, O=stored_pending_release, не returned. Дальнейший release требует K2 authorise/proof и нового leg.
4. Истекает open_days → alert/escalation, не доказательство передачи. Исторический auto-close выявляется миграцией как legacy_unverified.
5. Реальное возвращение → return proof → returned, C=return_receiver, O=resolved. P/refund/commission закрываются независимо.

CP-02/11/12/19; T-CUS-01. Нет поддержки/безопасного места → блокер запуска данного окна/коридора, а не груз «на усмотрение курьера».

## SIM-06 Покупка отмена и рост суммы

1. Shopping list approved cap G, substitution deny; курьер до принятия видит аванс/риск. Нет согласованного способа аванса → capability закрыта.
2. Цена выше G → amendment; покупка не выполняется до согласия. Молчание не принимается за +15%.
3. До оплаты authorize_purchase → start_purchase applied. Cancel-first отзывает authorized commitment и start denied; start-first оставляет in_progress/unknown после cancel_requested. Потерян чек/ответ: no release/redispatch, resolve F11. После verified success C устанавливается отдельным pickup/observation proof, O=return_or_purchase_resolution; чек один не доказывает holder.
4. Получатель не платит → debt/dispute по договору, не принудительное удержание/угрозы; delivery fee, goods reimbursement и merchant refund отдельны.
5. Магазин принимает возврат → receipt/refund evidence; товарный возврат не равен автоматическому возврату платы за выполненную поездку.

CP-13/14/19; T-BUY-01/02; LG-02/04/05.

## SIM-07 Повреждение частичная передача и несколько мест

1. Три места с item IDs приняты по pickup proof; C carrier на каждое место.
2. У получателя повреждено одно место; два принято → proof scope={1,2}. C1/2=recipient, C3=carrier; W=partially_completed, O=active. Весь заказ не delivered, если третье место не разрешено.
3. Третье возвращается → W=returning, после его return proof W=completed_mixed, O=resolved physical, C=mixed; спор отдельно. Leg и custody каждого предмета сохраняются. P partial refund/compensation лишь по решению и component allocations.
4. Quote не поддерживает частичную передачу → UI запрещает «частично delivered», открывает support, не теряет сведения о реально переданных предметах.

CP-09/12/18/19. До появления item-level схемы multi-piece partial delivery capability закрыта; единая посылка имеет явное правило отказа целиком. T-CUS-02.

## SIM-08 Мультиточка и передача другому курьеру

1. Route A pickup → B dropoff → C return; порядок связан с precedence, windows и capacity per leg.
2. B delivered закрывает только свой stop/items; не завершает C и не прекращает нужный GPS для другого заказа.
3. Курьер 1 сломался: остановка безопасно, support reassign proposal; старый assignment не освобождается до handoff proof.
4. Подтверждённая передача courier1 → courier2 создаёт CustodyTransfer; новый challenge generation, прежние invite/media scopes пересчитаны.
5. Частичный сетевой сбой → один владелец custody; неизвестная физическая передача фиксируется case, не два независимых «получил».

CP-18/09/10/12; T-CON-03. Hubs/партнёрские передачи закрыты до SOP/legal gate, не включаются обычной кнопкой reassignment.

## SIM-09 Возврат клиенту после выплаты курьеру

1. W=delivered, C=recipient, O=resolved, P=captured, settlement=paid.
2. Решение возврата 20000 коп → refund reservation, original charge remainder уменьшается доступным лимитом, не actual captured до provider result.
3. Дублирующий refund request → тот же result; второй другой запрос выше остатка отклонён.
4. Provider confirms → refund ledger; courier payout остаётся paid, adjustment отдельно по договору. Нет права recoup → расход платформы/решение владельца, не автоматический минус курьера.
5. Reconciliation сопоставляет charge/refund/payout; W и C не откатываются.

CP-14/19; T-PAY-03. Это дизайн, не обещание юридически разрешённого clawback.

## SIM-10 Адрес зона и время изменились после quote

1. Quote Q для села → райцентр, road distance verified, local windows с zone IDs; пользователь меняет дверь или регион.
2. Params hash отличается → Q stale. Сервер не принимает Q даже если UI сохранил цену.
3. Новый quote отражает длину дороги, дальнюю подачу, разрешённую территорию и UTC окна; неизвестный адрес/маршрут → unavailable/manual review, не centroid «точно».
4. После accept изменение маршрута → amendment обеим обязанным сторонам; старый lease/price нельзя использовать для новой территории.
5. Время в Татарстане/Удмуртии и Башкортостане показывается по месту точки; одинаковое число на часах не означает одинаковое UTC окно.

CP-03/04/05/17; T-GEO-01/02.

## SIM-11 Запоздалый код фото и отзыв ссылки

1. Pickup challenge P и dropoff D разных фаз; курьер пробует P в D → invalid, custody не меняется.
2. Получатель 1 заменён на 2: authority version увеличен, старые grants/challenges/contactless согласия отозваны атомарно; GET/replay/media закрыт. Действующий курьер со свежей state_version не может применить старый OTP1. Consume-first сохраняет уже состоявшийся transfer; revoke-first запрещает его, поздняя выдача старого кода discarded.
3. Старый курьер загружает фото после reassignment → denied, current waiting не останавливается, object не привязывается к чужому stop.
4. Старый dropoff response после открытия другого заказа → не применяется к экрану; terminal scope закрывает live location только завершённых items/order; другой активный order itinerary сохраняет свой разрешённый поток.

CP-09/10/21; T-SEC-01/02.

## SIM-12 Рейтинг смена статуса и работа без связи

1. Delivered; оценка отправлена keyR, ответ потерян; локально pending, не «спасибо, учли».
2. Рестарт → read own rating, затем reconcileR; одна оценка, никакого второго голоса.
3. Returning не показывается delivered; canceled не получает legacy квитанцию, для которой сервер отдаёт 409. Новый statement type отдельный, не подмена старого route.
4. История с cursor не пропускает новые строки при обновлении; последние 10 остаются «последние», не «вся история».

CP-20/07; T-UX-01.

## SIM-13 Погода травма и потеря статуса допуска

1. Новые опасные погодные условия/приостановка допуска закрывают новые offers и геозону.
2. Eligibility expiry не session revocation: fresh authenticated courier получает только K3 safe-resolution actions. При stolen/revoked session все его действия denied; reverified holder/support выбирает безопасную остановку/передачу. Уже полученная вещь и COD остаются в учёте. Нельзя удалить аккаунт и обнулить obligations.
3. SOS отделяет вызов экстренной помощи от обычного тикета; app не обещает самостоятельную отправку спасателей без сервиса.
4. Выплата заработанного не отменяется автоматически из-за изменения будущего допуска; hold/adjustment только по правомерной процедуре.

CP-03/12/19/21/23; T-OPS-01.

## SIM-14 Еда не готова температура нарушена

1. Merchant order согласован; courier arrival, кухня не готова → отдельное merchant wait и новая оценка окна.
2. Упаковка повреждена/требуемый режим не обеспечен → refuse pickup с причиной, не forced accept и не вина курьера автоматически.
3. Во время доставки превышен предел конкретного продукта → case/food SOP; не «ещё 10 минут всем можно».
4. Без возможности безопасного возврата еда не отправляется обратно автоматически; решение продавца/поддержки по согласованному правовому процессу, доказательства и деньги отдельно.

CP-15/11/12; LG-09; T-FOOD-01.

## SIM-15 Миграция смешанные версии и kill switch

1. Старый accepted заказ без quote/custody proof, M1 foundation включён, но v2 writes закрыты до K-CUTOVER. Матрица K5 сохраняет старую цену, unknown history, отдельные payment/dispute axes. После M1…M8 и writer fence/adapters/recovery разрешён только opt-in capability.
2. Старый клиент пытается записать terminal через legacy route → тот же state/ACL/proof policy, нет обхода нового gate.
3. Выключили новые заказы → старый collected продолжает доставку/возврат/поддержку; деньги сверяются, токены не пропадают раньше безопасного завершения.
4. Rollback к совместимому коду сохраняет snapshots/ledger/outbox. Если старая версия умеет некорректно auto-returned, откат к ней запрещён; forward fix.

CP-01/02/21/23; T-MIG-01/02.


## SIM-16 Нет body после restart atomic close и удаление аккаунта

1. A=живой actor anchor U, keyK; после отправки нет ответа и exact body, есть scope/target/hash. W/C/P/O пока неизвестны клиенту, не считаются отменёнными.
2. GET K=unseen. Клиент POST close тем же K/hash; original ещё в полёте. Под общим intent lock close-first создаёт closed_no_effect, original не изменяет W/C/P/O; apply-first создаёт applied и close возвращает его authoritative outcome.
3. UI показывает исход своей auth с current allowlist, не создаёт key2 для того же неизвестного намерения. Logout очищает secret/PII, но не dedup fact.
4. Account delete: auth/grants revoked; user association SET NULL, anchor+tombstone retained по approved policy/hold. Новая регистрация получает anchorV и не читает U. Поздний webhook reconcile отдельно по provider reference; receipt retention не даёт доступ бывшему token.

R2-INTENT; CP-07/21, K6. PG close/apply и account-delete/replay, crash during FK migration; runtime NOT_EXECUTED.

## SIM-17 Три плательщика частичные деньги и чужая выручка

1. W=delivering, C=carrier, O=active; P obligations: service sender → courier20000 коп, goods recipient → merchant100000 коп, commission courier → platform3000 коп. A каждого ограничено своим consent; числа синтетические, не тариф.
2. Verified service receipt20000 закрывает только service. COD collection claim 60000 сначала unverified; confirmed receiving courier+payer evidence создаёт goods allocation60000 и held COD60000. Summary partially_satisfied; goods remaining40000, commission 3000 открыта.
3. Merchant confirms remittance40000 → held 20000/remitted 40000. Не исчезает долг goods 40000 и commission 3000. Courier payout unrelated.
4. Goods refund 10000 после разрешённого решения/получателя → held 10000, refund 10000; conservation60000=remitted 40000+refund 10000+held 10000. Недостача 10000 → case, не автоматический минус/прощёный долг. W/C/O не меняются из денежного события.
5. Два IDs manual/bank для одного фактического service 20000 → economic_payment_group/reconcile и одна allocation; если это два реальных receipts, второй в surplus/refund, не двойной заработок.

R2-OBL/COD; CP-13/14/19, F10. Проверить роль, суммы/одновременные allocation и privacy каждого payer.

## SIM-18 Authorization expiry void и два внешних capture

1. W=searching с будущим scheduled window, C=sender, O=active; obligation due 20000 коп, attemptX authorized20000 до T. P paid0, reserved20000; A=действительный payer consent, не разрешение бессрочно списывать.
2. Scheduled pickup после T: confirmed expiry → expired/release reserve; новое consent/reauthorize при необходимости. Без него новое рискованное назначение blocked, но уже carrier custody всё равно разрешается безопасно.
3. В альтернативной ветке X capture_pending → cancel: void/request cancel не делает paid0-final. Worker reconcile с исходным key; unknown удерживает 20000, Y forbidden.
4. Provider доказал X no-effect, разрешён Y. Потом contradictory late captureX: под lock allocate только остаток debt; попытка void Y, неизвестный Y остаётся reconcile. Если оба реально captured, clearing 40000, allocation20000, surplus refund obligation20000; platform revenue/courier payable не удваиваются.
5. Concurrent refund requests резервируют remaining refundable, один полный surplus return. W/C/O не становятся canceled/delivered от webhook.

R2-ATTEMPT; CP-14/17, F12. Все внешний provider/PG tests NOT_EXECUTED; LIVE gate OPEN.

## SIM-19 Хранение повторная попытка и handoff

1. W=returning, C1/2=carrierA, O=return_required; обе стороны unavailable. A=support разрешённой роли, storage policy/consent current, P=отдельные approved costs/caps, не новая цена по молчанию.
2. propose_storage выдаёт holderS/location/next_action; C не меняется. Confirm storage transfer proof scope{1,2} → C=S, W=stored, O=stored_pending_release, old assignment item slots закрыты, goods money не remitted.
3. Sender согласовал redelivery нового окна/стоимости, courierB подтвердил terms; authorize_storage_release → proofS/B, atomic slots/capacity transfer → C=B, новая leg/attempt. Старый A proof/generation denied.
4. Из двух мест одно вручено, другое снова не вручено: W=partially_completed → returning, holder per item; нельзя complete весь itinerary. Если unknown physical transfer, affected item unknown+case, никакого второго active holder.
5. Нет approvedS/SOP или безопасного recovery → не запускать такую category/window/corridor. Timeout отправляет alert, не returned. Storage не легализует оставление вещи без согласия.

R2-STORAGE/HANDOFF; CP-12/18, K1/K2. Base pilot тоже обязан иметь такой recovery либо закрытый scope.

## SIM-20 Покупка внешняя оплата против отмены

1. W=assigned, C=sender, O=active; P=goods budget, no verified purchase. A=payer approved listL/merchantM/capG, commitment authorized; курьер ещё не вправе платить без applied start.
2. Cancel-first → revoked, start denied; receipt после самовольного расхода → unauthorized-expense case, не customer payable. Реальный товар всё равно учитывается observation/recovery, не игнорируется.
3. Start-first → in_progress commitment; cancel_requested прекращает будущие расходы, но не release/redispatch. External paid, response/receipt lost → outcome_unknown, P reserved exposure, C неизвестна пока нет evidence; support owner/next_action.
4. Verified merchant receipt ровно L/M/G + pickup proof → один purchase evidence/obligation и C=carrier; duplicate receipt no-effect. Старый L/amount>G не автоматически payable. Fail-no-effect proof разрешает завершить отмену, unknown timeout не разрешает.
5. Partial merchant return фиксирует item subset и refund allocation отдельно; оставшиеся item custody и расходы сохраняются. Offline start blocked; applied start recovery допускается без нового разрешения потратить повторно.

R2-PURCHASE; CP-13/14, F11. Денежная/правовая ответственность OWNER_PENDING, не назначается этим trace.

## SIM-21 Время миграция и первый региональный пилот

1. Previous-main accepted_at naive UTC и семь statuses; M1 K5 создаёт legacy logical item/assignment/minimal proof identity, unknown без подделки. M4 remuneration FK уже валиден, M3 amendments доступны quote; CP-21A не держит product files.
2. API window с +03/+05 преобразуется в naive UTC через K4, display сохраняет IANA stop. Offsetless input rejected; старый worker date comparison не TypeError и не timezone shift.
3. До K-CUTOVER v2 writes закрыты; после drain/adapters/fence старый cleanup не может сделать returned по age. Downgrade unsafe-worker forbidden, forward fix.
4. Pilot: город при подтверждённой supply может offer on-demand; село без supply только согласованное window/corridor либо unavailable. Pin/door+ориентир не centroid. Cutoff/capacity/backhaul/winter road и return/storage/support проверены фактическим pilot, пока NOT_EXECUTED.
5. Coverage paused → new accept denied, fresh-auth active return/storage разрешены; revoked session denied всегда. Auth expired payment отдельно F12. Коммерческий результат только после realroute supply/demand/unit economics и owner gate, не из карты семи субъектов.

R2-MIG/TIME/GATE/REGION; CP-02/03/04/17/21/23. Документные симуляции не заменяют полевой пилот.


## SIM-22 Ранние Stop FK и неизменная identity

1. M1 создаёт Stop identity до Item/Proof FK: logical pickup/dropoff принадлежат legacy order; W/C/P/O по K5, A=migration operator по разрешённому плану. Нет координат и выдуманного handover.
2. Upgrade только до M1 проверяет FK, nullable unknown historical proof.stop_id допустим; M4 assignment FK остаётся валидным.
3. M3 добавляет адрес/окно/зону/Leg к тем же stop IDs, unknown дверь остаётся NULL. Повтор backfill не создаёт новые stops/items и не меняет деньги.
4. Вплоть до K-CUTOVER новые commands закрыты, данные не принуждают пользователя к отсутствующему proof API.

R3-STOP, остаток A-B03. SQLite/PG upgrade каждой ступени и crash/resume будущие NOT_EXECUTED.

## SIM-23 Ручной попутный claim и отмена

1. W=searching, C=sender, P=planned net/terms, O=active; A=бытовой on-route actor по своему режиму. GET open requests даёт signed preview, но не assignment.
2. Два claim_on_route на один version: первый под lock создаёт внутренний self-offer и вызывает общий accept service, получает assigned; второй rejected без capacity/fee/notification effect. Ответ первого потерян → same key applied, не второй offer/assignment.
3. Cancel-first отзывает доступность preview, claim rejected; claim-first требует fresh cancel preview и соответствующие согласованные последствия, pickup всё ещё отдельный proof.
4. Stored professional delivery через on-route adapter denied, даже если caller подставил kind=poputka. Actor без профессионального допуска не получает эту capability.
5. Changed net/route/terms → CLAIM_PREVIEW_STALE; старый key и unsafe legacy client не создают новый intent. Поддерживаемый клиент сохраняет ручной выбор, old client получает upgrade/help.

R3-CLAIM, остаток A-B05. Проверить PG races и Android/PWA restore отдельно от design trace.

## SIM-24 Отмена и детерминированный итог

1. Один item у sender, нет purchase/collection/transfer; cancel-first → item.canceled, O=resolved, W=canceled, assignment/proof закрыты, денежная fee obligation при наличии отдельно. Повтор GET/projector/restart/backfill даёт тот же исход, не searching.
2. Pickup-first → C=carrier, cancel_requested → return/recovery, не canceled. Start_purchase-first/unknown аналогично не разрешает terminal cancel; revoke-before-start при доказанном no-effect разрешает.
3. Items1 delivered,2 ещё sender без shared commitment: согласованный scoped cancel2 → item 2 canceled, W=completed_mixed, C=mixed, O=resolved physical. История delivered1 не переписывается; деньги/спор отдельно.
4. Неразделимый unknown commitment связывает оба места → cancel2 terminal запрещён до reconcile. Таймер не делает эту связь исчезнувшей.

R3-CANCEL, A-R2-B01. Чёткий результат для all canceled, delivered+canceled и returned+canceled.

## SIM-25 Quote до заказа и два намерения

1. Quote Q purpose=create, immutable components L1/L2 с planned obligations; order отсутствует, collectible debt отсутствует. A=автор quote, P только предложение; expiry ничего не списывает.
2. Create K1 под quote lock materializes Order1, obligations O1/O2, immutable component mappings и QuoteUse. Same K1 replay возвращает их же. Create K2 с тем же Q → QUOTE_ALREADY_USED, даже если это другое явное намерение; нужна новая Q2.
3. Crash до commit откатывает всю группу, никаких orphan debt/consumed quote. Crash после commit восстанавливается C3 без второго долга. Cancel Order1 не освобождает Q для повторного заказа.
4. Amendment Q3 purpose=amendment привязан к Order1/base terms; POST orders rejected. Approved amendment фиксирует только delta/новые components с consent, не повторяет полную старую стоимость; double-apply rejected/replay.

R3-QUOTE, A-R2-B02. Physical custody не меняется от quote/create monetary mapping.

## SIM-26 Один cash pool для remittance и refund

1. W=delivered, C=recipient, O=resolved; P COD collected 60000, held 60000; A=подтверждённые merchant/courier roles. Remit intent 60000 reserved → available0; refund 60000 с иным key получает CASH_AVAILABLE_EXCEEDED, не external permission.
2. Remit started, ответ потерян → outcome_unknown, тот же резерв 60000 после restart. Ни новый remit, ни refund не используют его; timeout не no-effect.
3. Альтернатива: held 60000, remit40000 и refund 30000 одновременно. Если remit первым, refund denied/ожидает нового согласованного источника; если refund первым, remit denied. Не делать скрытый partial start на 20000 вместо разрешённых 30000. Сумма новых разрешений≤60000.
4. Два partial refunds проверяют одновременно account и original collection refund remainder; деньги другого merchant не подставляются.
5. Несмотря на guard, два реальных external success60000+60000 доказаны: immutable facts120000 сохранены, funded allocation≤60000, excess60000 в suspense/shortfall case. Никакого fake adjustment/скрытого минуса/автоматического долга курьера. Новый законный funding/ответственность отдельным решением, P=review_required, W/C/O не переписываются.

R3-CASH, S-R2-B01; RCASH-01/02. Числа синтетические; PostgreSQL и реальные денежные тесты NOT_EXECUTED.

## SIM-27 Channel grant machine worker и immediate capture

1. Replace recipient A → B создаёт pending authority generation 2 и verification-only invite. B подтверждает current channel → active generation 2, не 3 и не pending снова. Issue grant G1 → proof → consume достижим; old A code denied. Verify/revoke гонка даёт active либо revoked без оживления old proof.
2. Rotate G1 → G2 при той же authority 2: G1 и его unused proofs revoked; channel остаётся verified, receiving party не меняется, G2 разрешает новый scoped proof. Публичная ссылка не меняет payer/долг.
3. Worker service anchor S отправляет capture action K: restart использует S/K, одна операция; user с подставленным S forbidden, revoked worker grant не новый capture. Internal close не отменяет unknown external debit.
4. Two-stage provider требует valid authorization; approved immediate-capture provider с действительными consent/reserve/trigger проходит reserved → capture_pending без выдуманного authorization_id. Amount/unknown bounds F12 не ослаблены; неподтверждённый provider gate запрещает обе LIVE ветки.

R3-RECIPIENT/GRANT/WORKER/RPAY; A-R2-N02/N03, S-R2-N01/N02. DESIGN_SIMULATION, не runtime PASS.

## SIM-28 Передать по пути или заказать курьера

1. Sender выбрал «Заказать курьера»: Q/offer/order service_type=dedicated, approved fee policy и профессиональный capability. Supply нет → честно unavailable/новое окно, не автоматическое назначение бытового попутчика.
2. Sender отдельно выбрал «Передать по пути»: on_route preview показывает собственный маршрут/окно/крюк помощника, не dedicated ETA. Владелец подтвердил platform commission 0 при допустимой оплате водителю: UI различает эти суммы, minimum/ladder не удерживают fee. Это не разрешение LIVE; способы расчёта/цена водителя/provider/legal gates отдельно. Бесплатный поиск попутки не маркирует перевозку parcel бесплатно.
3. Caller меняет service_type в accept/create или подставляет professional order в бытовой claim → denied. Профиль rideshare не заменяет требуемый courier approval; общие custody/proof/support остаются обязательны.
4. Изменить режим можно новым quote/terms/согласиями и revalidated исполнителем. При C=carrier это не отменяет существующую физическую/финансовую обязанность; новый исполнитель только через подтверждённый handoff, если разрешён.
5. Customer receipt и courier net показывают тот же service_type/fee policy, что accepted snapshot. Unit economics считается отдельно: dedicated work против incremental on-route detour/time. Никакого вывода о прибыли без фактических cost inputs/pilot.
6. On-route gross 10000 коп. остаётся согласованной оплатой водителю без platform fee при любой ступени/minimum; service/goods payment ещё проверяется отдельно. Scheduled dedicated с тем же gross использует dedicated fee policy, а не zero fee по признаку «не срочно». Нельзя включить hidden service fee для покрытия acquisition costs.

PRODUCT-SEPARATION и OD-2026-10-25-01, CP-03/05/06/08/17/18/23; business model zero on-route fee APPROVED, dedicated rates и LIVE/legal/provider gates не закрыты; DRIVER_SET_PRICE утверждён позднейшим OD-04, runtime NOT_EXECUTED.

## SIM-29 Вещи Уфа Сибай опубликованная поездка

1. Driver публикует собственный Ride Уфа → Сибай с parcel toggle, vehicle/route/window versions и отдельными units/weight/dimensions. W доставки ещё нет; C=sender; P только price proposal; O ещё не принято; A=владелец Ride. Draft не searchable.
2. Sender ищет «Передать по пути», указывает сумки/конверт и выбирает подходящий published Ride/участок. Passenger seats и parcel capacity показаны отдельно. Parcel-only Ride без пассажиров находится в parcel search, не в passenger booking выдаче.
3. Driver price proposal и payer consent согласованы; create → searching, claim → assignment+RideParcelReservation одной транзакцией. Комиссия 0, оплата водителю отдельна; quote/поиск не бронировали место раньше.
4. До pickup C=sender; после scoped proof C=driver/carrying. Sealed documents не требуют фотографии содержимого. Отсутствие пассажиров не fraud verdict.
5. Unsupported item/service pair или неизвестная legacy связь не выдаёт новый допуск; старое active обязательство не теряется. Multi-item одного заказа требует CP-18, independent single-item orders уже могут делить Ride resource.

R4-RIDE; CP-03/04/08/09/17/18,11. Это design trace, не реальный маршрут/заказ.

## SIM-30 Пассажирский багаж против посылки

1. Ride имеет действительные seats/багажные обещания и ограниченную parcel capacity; A=разные authorized passenger/courier callers, разные intent keys. Свободный seat не равен свободному parcel unit.
2. Booking и parcel claim одновременно берут Ride-first locks/CAS. Winner резервирует свой фактический resource; loser перечитывает остатки и либо проходит, либо получает capacity conflict. Общая ёмкость не превышена, driver не теряет обещанный passenger baggage.
3. Третий sender принимает другой parcel на пересекающихся legs — те же cumulative checks; непересекающиеся legs не обнуляют ещё carrying item из-за failed dropoff.
4. Lost response → тот же C3 key; ни двойной slot, ни две оплаты. Старый booking writer без общего guard не допускает bridge activation.
5. Toggle off/уменьшение envelope ниже занятых ресурсов rejected; existing commitments остаются. Passenger data не раскрывается другим senders ради объяснения отказа.

R4-CAPACITY; PG барьеры между разными sessions, SQLite только отдельный CAS test; NOT_EXECUTED.

### SIM-30A Recovery hold и уже принятый следующий leg

Design/race tests A-R4-N01, **NOT_EXECUTED**:

1. P1 onboard на A → B; scope units/weight/envelope occupied. Recovery-first: request_on_route_recovery берёт Ride lock, state=recovery_required без physical transfer. Следующий overlapping claim/Booking видит прежний hold/unknown-span guard и отклоняется, а не получает «свободный» слот. W=recovery, C=прежний carrier/unknown по evidence, P неизменно, O active recovery, A=authorized command.
2. Accept-first: при прежних допустимых данных P2 был reserved на B → C; затем P1 не выгрузили в B. Recovery фиксирует overhang/conflict и блокирует несовместимую посадку/загрузку P2. P2 не удаляется и не объявляется выполненным; required participants согласуют безопасное исполнение/перенос/отмену, P отдельно по F. Два несовместимых физических груза одновременно не принимаются.
3. Unknown handoff/span: ни один таймер/предполагаемая смена holder не вычитает hold. Пока affected remaining legs нельзя ограничить, новые конфликтующие admissions Ride blocked. Support не подделывает proof ради разблокировки.
4. Действительный scoped handback/storage/handoff освобождает ровно доказанные items/ресурс и позволяет revalidate admissions. Duplicate proof/receipt не вычитает второй раз; непереданные items и уже существующие обязательства сохранены. A=K2/C3, C меняется только по evidence, деньги не следуют из occupancy status.
5. Legacy regression A-R4-N05: absent policy сохраняет обычный пассажирский Ride и guards старой parcel category; old edit с пропущенными flags не выключает/включает их, не снимает live Booking. Новый parcel-only false явный, не seats_total=0.

Это уточнение формулы будущей реализации, не доказанная авария текущего продукта. PG barriers/две sessions/rollback должны подтвердить оба порядка после реализации.

## SIM-31 Правка отмена поездки и передача

1. Driver меняет Ride route/window/vehicle с active Booking и parcel reservation. Existing RS-15 restrictions плюс 11 проверяют полный proposed scope; нет частичной записи Ride без parcel consent/ресурсов.
2. До pickup допустим agreed cancel/rebooking при no custody/purchase; release slot только доказанно, денежные последствия отдельно.
3. После pickup Ride cancel закрывает новые admissions, но C=driver, reservation=carrying/recovery_required, O=return/recovery. Timer не canceled/returned, пассажирская отмена не proof посылки.
4. Новый driver/Ride готов принять часть items: old/new Ride sorted locks, target capacity, bilateral handoff proof → один новый holder/slot. Unknown physical transfer → case, не два active holder.
5. Предложен dedicated rescue: новый quote/полная цена/fee policy и payer+executor consent; ранее выполненная on_route работа остаётся fee 0. Отказ от нового платного предложения не разрешает бросить сумки/документы; real safe return/storage/transfer gate обязателен.

R4-EDIT/CUSTODY; CP-08/12/18,11. Поддержка/хранитель должны реально существовать к запуску, сейчас NOT_OPERATIONALLY_READY.

## SIM-32 Обход комиссии и законный parcel-only

1. Новый driver публикует реальную собственную поездку без пассажиров и включает parcels: это допустимый сценарий, не автоматический fraud.
2. Caller подставляет dedicated order в on_route/чужой Ride или меняет versions → rejected по stored service_type/owner/binding, без нового fee и assignment.
3. Заявленный маршрут перестал совпадать с handover corridor, появились несовместимые commitments: объяснимый reason/manual review, возможность исправить/обжаловать, а не утверждение об умысле.
4. Село/доход/отсутствие пассажиров не risk proof. Постоянный GPS вне active scope и cross-account surveillance не включаются ради проверки.
5. Если own-route условия не соблюдены, можно предложить dedicated новый quote; без явного согласия не меняются цена/тип. Нельзя задним числом удержать commission за suspect on_route.

R4-ABUSE; thresholds/evidence/restrictions configurable OWNER_PENDING, полной защиты от обходов не обещаем.

## SIM-33 Экономика сельского направления

1. Cost sheet разделяет observed расходы и assumed ranges, city/rural/intercity; courier pickup/time/km/wait/backhaul и platform costs/subsidy не смешаны с goods/COD.
2. Contribution после variable costs положителен → scenario break-even по F14A; fixed/acquisition не посчитаны дважды. Нулевой/отрицательный contribution не делит на 0 и не обещает окупаемость ростом объёма.
3. Недостаточная supply/дорогой backhaul → scheduled/consolidated option только после gate, прозрачная subsidy/tariff decision либо no-launch. Нет hidden fee/negative courier payout.
4. On-route platform fee 0 сохраняется, acquisition cost видим отдельным бюджетом; driver reward не финансирует его скрытным вычетом.

R4-ECON; никакой тариф, прибыль или объём заказов этим примером не утверждён.

## SIM-34 Получатель и отключение ссылки

1. Sender заменил получателя A на B: authority pending новой generation. Сам sender не может выполнить verify вместо владельца B channel; negative command denied.
2. B подтверждает свой канал → active без self-revoke; issued G1 даёт только согласованный scope.
3. «Отключить ссылку» → revoke_recipient_grant G1 и его unused proofs; G1 denied, G2 не создана, receiving authority/channel не заменены. Для другой доступной verified-channel передачи сохраняются только её разрешённые proofs.
4. «Сменить получателя» остаётся отдельным broader operation с новым consent/channel. Пассажир той же поездки не получает чужой recipient/contact/document contents.

A-R3.1-N02/N03; C7/K2/CP-10, NOT_EXECUTED.

## SIM-35 Готовый заказ покупка и три плательщика

1. Merchant ready pickup не требует внешней покупки; buy_bring требует versioned list/budget/substitution policy и F11 authorized/start до расхода. Approved product scope не снимает gates конкретной категории.
2. Sender/recipient/merchant выбирается payer до acceptance. Только его доказанный consent на amount/terms делает obligation accepted; sender не создаёт долг получателю одним вводом телефона.
3. Payer unavailable/declined → нет accepted assignment/внешнего spend, можно предложить другого payer с новым consent. Recipient link открыта без consent → не paid и не debt.
4. Approved buy_bring → receipt/остаток/частичный возврат по F10/F11, service и commission отдельно. НПД/ККТ/provider/legal ещё OPEN, не разрешение любой покупки.

OD-2026-10-25-03; CP-13/14/15. All proposed runtime tests NOT_EXECUTED.

## SIM-36 Цена водителя совет платформы и встреча

| Шаг | W / C / P / O / A и ожидаемый исход |
|---|---|
| 1. Sender запрашивает цену для published Ride/items/window | W=pre-order request, C=sender, P=нет оплаты/долга, O=нет assignment, A=sender/уполномоченный merchant. Нет reservation; данные минимальны |
| 2. Платформа советует диапазон, водитель выбирает другую сумму | W=immutable DriverPriceProposal, C/P прежние, O=ещё нет перевозки, A=Ride owner. ADVISORY_ONLY не блокирует отличающуюся цену и не auto-applies её; fee 0 |
| 3. Sender и фактический payer согласуют сумму и meeting point | W=quote → searching после create, C=sender, P=F10 obligations due/не paid, O=заявка ещё не assigned, A=оба required consents. Advisory click не согласие на charge |
| 4. Два create previews одного proposal приходят одновременно | W=один order/QuoteUse.proposal_id, C=sender, P=один набор obligations, O=одна заявка, A=C3/quote-proposal lock. Второй key не создаёт дубликат заказа или долга |
| 5. Driver принимает при действующих Ride/context/capacity | W=assigned, C=sender до proof, P=не изменён фактом accept, O=действующее обещание перевозки/оплаты по условиям, A=canonical claim и Ride owner. Сумма не заменена советом |
| 6. Sender требует door detour или меняется маршрут | До согласия W/C/P/O прежние, A=driver+payer и применимые RS guards. Показать путь/время/цену; отказ/RS запрет не меняет договор. После принятия только versioned amendment |
| 7. Предложен dedicated вместо on-route | Старый mode/fee 0 не переписан. Новый quote с dedicated fee и явное согласие обязательны, включая scheduled. Нет согласия → нет нового заказа/платного обязательства; текущая custody всё равно требует безопасного resolution |

Negative: чужой Ride owner, expired proposal, иной item/context, новый quote с одной proposal после consumption, изменённый price body с тем же key, payer decline, hidden minimum10 руб. Все отвергаются без второго эффекта. OD-04 business policy APPROVED, proposed protocol/runtime NOT_EXECUTED; ставки dedicated/provider/legal остаются gated.

### SIM-36A Потерянный pre-order ответ

A-R4-N04, NOT_EXECUTED: request/proposal отправлен с actor-scoped key, ответ потерян до либо после commit; restart сохраняет scope/target/hash и nullable order_id. GET applied → ровно существующий safe ref, без второго запроса/суммы. GET unseen+body lost → atomic close; late original получает closed_no_effect. Другой actor/ref scope denied, result_gone не означает нового create. P=никаких оплат/obligations, C=sender, O=ещё нет assignment; после реального create прежние F2 single-use правила остаются.

## SIM-37 Пробка устаревший GPS и честный ETA

| Шаг | W / C / P / O / A и ожидаемый исход |
|---|---|
| 1. Assigned courier, forecast F1 pickup через 5 минут | W=assigned, C=sender, P=не paid по ETA, O=pickup obligation, A=current assignment/C9. UI показывает target/absolute window/generated_at и elapsed отдельно |
| 2. Через 7 минут arrival ещё нет; traffic ухудшился | W/C/P/O не меняются от времени, A=server forecast source. F2 новое окно, delay+elapsed видны. Нет вечной свежей пятиминутки, auto-arrive или paid wait |
| 3. Provider прислал старый fix сейчас, ETA timeout | W/C/O сохраняются, P без начислений, A=adapter provenance. received_at свежий, captured_at старый; location stale и ETA unknown, без вымышленного движения/причины |
| 4. Android ушёл offline/reboot, часы +2 часа; PWA online | W/C/P/O server truth прежние, A=role projections. Старый снимок подписан возрастом, elapsed lower bound после reboot; resync получает F2, clock не меняет деньги |
| 5. В Ride есть чужая passenger stop, затем handoff | W/C только от K2 physical commands, P отдельно, O item-scoped, A=current role/generation. ETA учитывает разрешённую остановку, но адрес/точная геометрия скрыты сервером; old generation event не возвращает прежний forecast |
| 6. Получатель недоступен, начат return | W=returning по C2/K2, C=current carrier до handback, P=отдельные approved return terms, O=return_required, A=уполномоченные стороны. Старому получателю не виден чужой путь; sender/return receiver видят разрешённый status/ETA. Live GPS не включён автоматически |

C9/CP-10/22; OWN_DESIGN_NOT_VENDOR_SPEC. Все числа — synthetic design inputs, не тариф/SLO и не проведённый заказ. Два физических телефона/сервер/traffic/permissions/provider доказательства ещё NOT_EXECUTED.

Дополнительный A-R4-N03 trace, NOT_EXECUTED: slow forecast A стартовал для generation g1 → route/assignment/этап изменился → fast B(g2) опубликован → A завершился позже. Сервер publication guard сохраняет B current; A только historic/stale. Первый GET нового Android/PWA клиента и websocket после resync видят B/g2, а не late A с более новым received time. Snapshot/event/outbox согласованы; W/C/P/O не меняются от forecast, A=server current generation и role ACL.

### ETA negative acceptance matrix

| ID | Негативный вход | Обязательный результат |
|---|---|---|
| ETA-N01 |5 минут, спустя 7 минут нет arrival | Сдвиг absolute window/elapsed либо expired/unknown, не свежая фиктивная строка |
| ETA-N02 | Old fix, new HTTP | captured_at не заменён received_at |
| ETA-N03 | Fresh GPS, old forecast | location fresh, ETA stale отдельно |
| ETA-N04 | Provider timeout/429/5xx | Bounded retry, stale/unknown, не default5 минут |
| ETA-N05 | Duplicate/out-of-order old generation | Seq/route/assignment guard, no rollback |
| ETA-N06 | Clock ±часы, timezone/DST | UTC instants/elapsed/деньги стабильны |
| ETA-N07 | Process death/reboot offline | Известный elapsed не сброшен, честный lower bound/resync |
| ETA-N08 | Stationary fresh vs stale point | Разные состояния, нет выдуманной причины/вины |
| ETA-N09 | Traffic/closed road/detour | Forecast может увеличиться, promised window не переписано |
| ETA-N10 | New route/stop order | Affected forecasts invalidated/recomputed |
| ETA-N11 | Merchant not ready | Queue учтена один раз, travel не full delivery |
| ETA-N12 | Passenger/other parcel stop | ETA отражает влияние, приватная геометрия/адреса не утекли |
| ETA-N13 | Wrong entrance inside GPS radius | Нет auto-arrival/POD/paid wait |
| ETA-N14 | Unknown accuracy units/teleport | Null/degraded, нет точного радиуса/обвинения |
| ETA-N15 | Forecast expired at zero remaining | Не arrived/delivered, явная просрочка |
| ETA-N16 | One client offline | Старый snapshot явно старый; resync canonical target forecast |
| ETA-N17 |5 → 20 минут со smoothing | Known worsening не скрыто, raw/published audit ограничен |
| ETA-N18 | Revoke/terminal/account switch | Scoped live access прекращён, cache закрыт; receipt не live permission |
| ETA-N19 | Browser closed/background denied | Stale честно, no false background promise, safe recovery доступен |
| ETA-N20 | Late/lost push after handoff | Canonical current state; old push не восстанавливает права |
| ETA-N21 | Return/foreign passenger leg | Role-specific status/ETA, private path not exposed |
| ETA-N22 | City calibration fails rural | Segment report и degraded confidence/launch gate, не средняя маскирующая точность |

Каждая строка NOT_EXECUTED. Retention, provider/data rights, numeric freshness/error/notification/SLO thresholds OWNER_PENDING, не одобрены примерами.

## Матрица исполнения будущих тестов

Каждая строка ниже — отдельный `NOT_EXECUTED` сценарий. Отчёт должен содержать target SHA, actor/роль, platform/build, precondition, команды/экранные шаги, expected/actual, exit code или видео/лог, outcome и независимый reviewer. Нельзя отмечать всю строку PASS по одному подусловию.

| ID | Класс | Проверка и критерий |
|---|---|---|
| T-POS-01 | positive | Пустая форма parcel-on-route → accept → pickup → dropoff четырьмя ролями; реальные коды, обе стороны видят согласованный итог |
| T-POS-02 | positive | Профессиональная заявка с pending → admin review → approved → online → заказ; не seed approved вместо начала |
| T-GEO-01/02 | negative/boundary | Неизвестная дверь/недоступная зона/пересечение региона; одинаковые local часы в разных zone; ни неверного тарифа, ни обещания |
| T-CON-01/02/03 | concurrent PG | accept/accept; cancel/pickup; two-mode capacity; handoff/revoke; один custody owner/assignment/эффект |
| T-OFF-01/02 | offline/restart | Потеря до и после доказанного commit; process death, logout, late callback; один intent, безопасное восстановление |
| T-SEC-01/02 | negative/security | IDOR order/media/grant, чужой proof, brute-force OTP, EXIF, forged GPS, stale auth, replay после revoke |
| T-CUS-01/02 | custody | No-answer на обоих концах, старый timeout; no auto returned; multi-piece partial не закрывает недоставленное |
| T-BUY-01/02 | purchase | Нет товара, замена запрещена, бюджет превышен, отмена после чека, отказ оплаты; согласие и деньги раздельны |
| T-PAY-01/02/03 | money | Webhook duplicate/cross-order/out-of-order; rounding/tier/promos; refund after payout; баланс и реальная сверка |
| T-FOOD-01 | capability | Не готово/неверная упаковка/temperature exception; запрещённый тип не назначен неподготовленному курьеру |
| T-UX-01 | client/restore | Все terminal/history/rating/receipt/return actions, error сохранение ввода, pagination без пропусков |
| T-OPS-01 | operations | Пропал курьер, ДТП, выключена зона, ночью нет поддержки; ответственный/следующее действие, не забытый груз |
| T-MIG-01/02 | migration/rollback | Previous-main synthetic all statuses → target; crash/backfill resume; mixed clients; rollback не теряет ledger/custody |
| T-A11Y-01 | accessibility | Android/PWA RU/BA×light/dark, большой текст, screen reader, клавиатура,48 dp, контраст≥4.5:1, слабая сеть |
| T-RT-01 | runtime | Два физических телефона, настоящий сервер/GPS/MapKit/background push/recipient/SOS и реальная передача; отдельно от emulator/API |

## Traceability и запрет ложной приёмки

Существующий обязательный состав `COURIER-E2E-01…10` из `docs/audit-traceability/courier-scope.md` сохраняется. CP и T/SIM расширяют его, а не уменьшают. Тесты других режимов и QA конкурентов не принимают Юлдаш. Новая capability имеет дополнительный профиль требований и явную связь с общими тестами по применимости.

Найденная дыра записывается `GAP-ID → SIM/requirement → CP/contract section → изменение → новый candidate hash → повторная независимая проверка`. Авторский разбор здесь не является независимой проверкой. Статус плана фиксируется отдельно от code implemented и production accepted; продуктовые «принято» только в существующем audit registry.
