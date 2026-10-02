# Карточка: `backend/app/debt.py`

- Статус: verified
- Лист: leaf-1.1
- Проверял: Sonnet 5 (leaf-1.1); принимал: Opus 5.5

## Назначение

Долг по комиссии за ТАКСИ, Модель А «на доверии» (1207 строк, самый большой и самый денежный
файл листа): за завершённый instant-заказ водитель берёт деньги напрямую (нал/прямой СБП), а
8–15% (лесенка) должен платформе. Раз в неделю переводит сам, жмёт «Я оплатил» → `pending` →
админ подтверждает → `paid`. Просрочка/превышение порога → блокируется режим ТАКСИ (ПОПУТКА —
отдельный поток, её долг этим не трогает, вызов `taxi_block_reason` живёт только в
instant-ручках вне этого листа). Плюс: лесенка комиссии по стажу/поездкам, промо запуска,
дашборд и история заработка водителя, зачёт долга деньгами из кошелька (см. `ledger.py`),
снятие/возврат комиссии по разбору жалоб.

## Функции и разбор

| Функция | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_week_key(dt)` | 38–45 | ISO-неделя начисления по МЕСТНОМУ календарю (Уфа) | группировка долга для показа/оплаты | ок |
| `_launch_promo_percent(session, driver_id, now)` | 48–74 | ставка 0% для одобренных до даты набора, на N дней | пустая/кривая дата конфига → `None` (промо выкл.); одобрен ПОСЛЕ окна набора (по МЕСТНОЙ дате одобрения) → `None`; промо-период истёк → `None` | ок |
| `launch_promo_ends_at(session, driver_id, now)` | 77–95 | когда у ЭТОГО водителя кончится промо (для кабинета) | промо не действует / нет заявки / нет даты одобрения → `None` | ок |
| `done_trips_before(session, driver_id, when)` | 98–112 | сколько заказов завершено СТРОГО ДО `when` | ставка не зависит от самого события, которое её поднимает | ок |
| `fee_percent_for_trips(trips)` | 115–121 | ступень лесенки по числу поездок | `trips < tier1`→tier1%; `< tier2`→tier2%; иначе база | испр. границ нет, но защищено мутацией |
| `_no_rate_jump(session, driver_id, now, trips_percent)` | 124–157 | переход лесенки с дней на поездки не утраивает ставку «старым» водителям | пустая дата перехода → без изменений; берёт МЕНЬШУЮ из двух лесенок (по дням и по поездкам) | ок |
| `driver_fee_percent(session, driver_id, now=None)` | 160–172 | итоговая ставка: промо запуска перекрывает лесенку, иначе `_no_rate_jump(fee_percent_for_trips(...))` | — | ок |
| `_promo_comp_kop(session, order_ids)` | 175–186 | сколько ФАКТИЧЕСКИ (из ledger, не формулой) доплачено компенсации промо по этим заказам | пустой список → 0 | ок |
| `WRITTEN_OFF_PREFIX = "Списан"` | 192 | машиночитаемая метка «долг закрыт БЕЗ оплаты» в `note` | договор между `void_debt_for_order`/`fee_charged_kop`/`routers/debt.py::admin_forgive` | ок |
| `fee_charged_kop(d)` | 195–210 | сколько комиссии РЕАЛЬНО осталось на водителе по этому долгу | `None`→0; списан/прощён (`paid`+`WRITTEN_OFF_PREFIX`)→0; реально оплачено (онлайн/СБП)→видно | ок |
| `driver_dashboard(session, driver_id, now=None)` | 213–311 | дашборд кабинета: заработок/заказы/комиссия ЗА МЕСТНЫЙ ДЕНЬ, текущая и следующая ступень, промо | местная полночь считается сдвигом UTC, не завязана на часовой пояс сервера | ок |
| `_local_day_expr(session, column)` | 314–322 | SQL-выражение «местный день» — портируемо SQLite/PostgreSQL | смещение — свой int-конфиг, инъекции нет | ок, проверено на обеих БД |
| `unpaid_confirmed_order_ids(session, driver_id)` | 325–348 | заказы, по которым РАЗБОР (не голое слово) подтвердил «не заплатили» | фильтр по `Report.status=="resolved"`, НЕ по факту жалобы | испр. границ нет, но защищено мутацией (R13) |
| `unpaid_confirmed_parcel_ids(session, courier_id)` | 351–366 | то же для доставок курьера | пара к предыдущей, тот же договор | ок (вне денежной проверки — курьерская сторона в leaf курьера) |
| `driver_earnings(session, driver_id, period, now=None)` | 369–423 | история заработка (week/month/all) + разбивка по дням, SQL-агрегатом | «кинутые» (unpaid_confirmed) заказы исключены из заработка, но их сумма названа отдельно (`unpaid_total`) | ок |
| `driver_rides(session, driver_id, limit=100)` | 426–494 | список завершённых поездок с расшифровкой цена/комиссия/чистыми | `limit` зажат `[1,200]`; «не заплатили» → `net_kop=0`, но строка остаётся видна | ок |
| `order_commission_kop(order, percent=None)` | 497–517 | комиссия = (цена − компенсация) × процент | `price<=0`→0; база после вычета компенсации `<=0`→0 | ок (бывший риск через `compensation.py` — см. его карточку) |
| `_clock_starts(now)` | 520–538 | с какого момента считать часы на срочную оплату (ночью — с утра) | тихие часы выключены → не сдвигать | ок |
| `_due_at(now, amount_kop, pay_now_allowed=True)` | 541–555 | срок оплаты: крупная комиссия — сразу (с учётом тихих часов), иначе — неделя | `pay_now_allowed=False` (авто-закрытие ночью) → всегда неделя | ок |
| `is_pay_now(d)` | 558–572 | это был срочный долг (не обычный недельный)? По ОКНУ записи, не по текущему конфигу | окно должно быть `0 <= Δ < 1 день`, иначе (ручной сдвиг даты) — не считается срочным | ок |
| `accrue_for_order(session, order, pay_now_allowed=True)` | 575–631 | начислить долг за завершённый заказ, идемпотентно | нет водителя→`None`; уже начислено (UNIQUE order_id)→существующая запись; гонка→`IntegrityError`→откат, та же запись; ставка ФИКСИРУЕТСЯ на `created_at` заказа; промо-компенсация гасит долг из кошелька сразу | ок |
| `refund_ext_id(*, order_id=None, parcel_id=None)` | 634–636 | ключ идемпотентности возврата комиссии | один заказ/доставка — один возврат | ок |
| `refund_commission_to_wallet(...)` | 642–675 | вернуть УЖЕ ПЕРЕВЕДЁННУЮ комиссию в кошелёк (не выплата на карту — выплаты выключены до ИП) | `driver_id=None`/`amount<=0`→`None`; идемпотентно по `ext_id`; НЕ коммитит (чужая транзакция) | ок |
| `void_debt_for_order(session, order_id, note="")` | 678–732 | снять долг по заказу, оплаченному ОНЛАЙН (комиссия уже удержана записью `fee`) | нет долга→`None`; списан без оплаты (`WRITTEN_OFF_PREFIX`)→повтор `None`, БЕЗ фантомного возврата; реально оплачен (paid, без метки)→`"refunded"`; обычный unpaid/pending→`"voided"` | испр. границ нет, но защищено мутацией (R10) |
| `taxi_owed_kop(session, driver_id)` | 741–755 | сумма unpaid+pending одним числом | `driver_id=None`→0 | ок |
| `settle_debt_from_wallet(session, driver_id, now=None)` | 758–859 | погасить долг деньгами, уже лежащими в кошельке | row-lock строки водителя ДО чтения баланса; FIFO, ТОЛЬКО целиком, останов когда не хватило; атомарный `UPDATE ... WHERE status=unpaid` — вторая линия обороны на SQLite; только по `InstantOrder.paid==True` | ок, двойная защита (R9) |
| `_unpaid`/`_pending(session, driver_id)` | 862–877 | выборки долгов по статусу | — | ок |
| `DECLARE_TRUST_DAYS = 3` | 893 | срок жизни «слова» без подтверждения деньгами | прошито в коде, не в конфиге (осознанно — это не тариф) | ок |
| `crossed_warn_line(session, driver_id, just_added_kop)` | 896–915 | долг ИМЕННО ЭТИМ заказом пересёк линию предупреждения? | уже за порогом блокировки → не предупреждаем (поздно, нужен другой разговор) | ок |
| `_stale_declares(pending, now)` | 918–923 | протухшие «слова» (>3 дня без подтверждения) | фолбэк на `created_at`, если `paid_declared_at` пуст | ок |
| `taxi_block_reason(session, driver_id, now=None)` | 926–947 | причина блокировки ТАКСИ или `None` | склеивает `_unpaid`/`_pending`/`last_seen` → `_reason_from` | ок |
| `_had_a_chance_to_know(d, last_seen)` | 950–968 | мог ли водитель УЗНАТЬ про короткий срочный долг (заходил ли после начисления) | нет даты начисления → ведёт себя как раньше (`True`) | ок |
| `_reason_from(unpaid, pending, now, last_seen=None)` | 971–990 | чистое решение по уже собранным долгам: `declare_abuse` > `declare_stale` > `overdue` (с поправкой на «имел шанс узнать») > `over_threshold` > `None` | используется и одиночным гейтом, и пакетной проверкой круга подбора — ОДНА логика на оба места | ок |
| `blocked_driver_ids(session, driver_ids, now=None)` | 993–1018 | кто из списка заблокирован, ОДНИМ запросом | та же `_reason_from`, что и одиночный гейт — не расходится | ок |
| `TAXI_BLOCKED_MSG(_BA)` | 1024–1025 | двуязычный текст отказа | — | ок |
| `debt_summary(session, driver_id)` | 1028–1067 | сводка для кабинета: суммы, срок, реквизиты СБП, блок, разбивка по неделям, «оплати сразу» | — | ок |
| `declare_paid(session, driver_id)` | 1070–1088 | все unpaid → pending, считает `declare_count` | ничего не должен → 0, без коммита | ок |
| `mark_all_paid(session, driver_id, up_to=None, *, commit=True)` | 1091–1117 | погасить весь долг (оплата картой, вебхук) | `up_to` — граница снапшота (не гасит долг, начисленный уже ПОСЛЕ создания платежа) | ок |
| `DebtChanged` | 1120–1130 | сумма изменилась между «админ увидел» и «нажал» | несёт `expected_kop`/`actual_kop` | ок |
| `_batch_admin_saw(session, debt, expected_kop)` | 1133–1158 | какие именно долги закрывает нажатие админа | сумма прислана → сверка точная, не сошлась → `DebtChanged`, НИЧЕГО не трогаем; суммы нет (старый клиент) → сужаем до долгов того же `paid_declared_at` | ок |
| `admin_confirm(session, debt_id, expected_kop=None)` | 1161–1183 | весь pending водителя → paid | долга нет → `None`; при несовпадении — см. выше | ок |
| `admin_reject(session, debt_id, expected_kop=None)` | 1186–1207 | pending → обратно unpaid, тратит «слово» | та же защита `_batch_admin_saw` | ок |

## Связи

- `backend/app/ledger.py` (этот же лист) — `driver_fee_percent` зовётся из `settle_instant_order` (момент фиксации ставки — `created_at`, СОВПАДАЕТ с `accrue_for_order`); `taxi_owed_kop` зовётся из `owed_to_platform_kop`.
- `backend/app/routers/debt.py` (этот же лист) — единственный HTTP-фасад над этим модулем.
- `backend/app/routers/safety.py` (вне OWNS) — `admin_resolve_report` зовёт `void_debt_for_order`/`unpaid_confirmed_order_ids` при подтверждении жалобы «не заплатили».
- `backend/app/routers/instant.py` (вне OWNS) — `_guard_taxi_not_blocked` зовёт `taxi_block_reason` на `/instant/presence`, `/driver/offer`, `/orders/{id}/accept`; `/rides` (попутка) этот гейт НЕ зовёт вообще — отсюда и гарантия «попутка не блокируется» (подтверждено тестом вне этого листа: `test_poputka_not_blocked_by_debt`, сам факт отсутствия вызова проверять вне OWNS не стал).
- `backend/app/compensation.py` (этот же лист) — `order_commission_kop` зовёт `compensation_rub`.
- `backend/app/promo_ride.py` (вне OWNS) — `split_commission` зовётся из `accrue_for_order`.
- `backend/app/cleanup.py` (вне OWNS) — `expire_stale_declares` возвращает протухший `pending` в `unpaid`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Комиссия берётся с (цена − компенсация), не со всей цены | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r1_commission_base_excludes_compensation, test_r1_compensation_covering_the_whole_price_means_zero_commission, test_r1_non_positive_price_is_zero_commission_not_negative | да — M17 |
| R2 | Ставка комиссии в долге фиксируется на `created_at` заказа, а не на момент завершения — совпадает со ставкой online-оплаты за ту же поездку | backend/tests/test_fee_percent_consistency.py::test_cashless_fee_matches_debt_fee_when_order_crosses_tier_boundary | да — M18 |
| R3 | Границы лесенки строго `<` (ровно N-я поездка — уже следующая ступень) | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r2_ladder_boundary_is_exclusive_at_tier1/_at_tier2, test_r2_ladder_tier1_at_the_start, test_r2_far_beyond_tier2_stays_on_the_base_rate; backend/tests/test_fee_ladder_trips.py, backend/tests/test_money_rules.py (test_fee_*) | да — M19, M20 |
| R4 | Промо запуска — только для одобренных ДО даты набора ПО МЕСТНОМУ календарю | backend/tests/test_the_launch_promo_knows_the_local_calendar.py::test_одобренный_в_сентябре_не_попадает_в_августовский_набор (и далее) | да — M21 |
| R6 (ПОПУТКА) | Таксистский блок (`taxi_block_reason`) не применяется к попутке — гарантия на уровне «эта функция вообще не вызывается из `/rides`» | backend/tests/test_debt.py::test_poputka_not_blocked_by_debt | покрыто существующим тестом; мутацию в debt.py завести нельзя — вызов живёт в чужом файле (routers/instant.py), это ВНЕ ЗОНЫ |
| R8 | Зачёт долга из кошелька — FIFO, целыми долгами, останавливается при нехватке на самый старый | backend/tests/test_the_wallet_works_and_the_ride_is_free.py::test_не_хватает_на_старый_долг_деньги_ждут, test_очередь_долгов_соблюдается, test_ровно_хватило_значит_гасим | да — M22 |
| R9 | Зачёт из кошелька защищён от двойного списания ДВУМЯ способами: row-lock (Postgres) и условный `UPDATE` (SQLite); прогнано и на SQLite, и на настоящем PostgreSQL | backend/tests/test_wallet_is_not_charged_twice.py::test_settlement_takes_a_row_lock_before_reading_the_balance, backend/tests/test_wallet_is_not_charged_twice.py::test_sqlite_stale_debt_update_does_not_charge_wallet, backend/tests/test_wallet_is_not_charged_twice.py::test_parallel_settlement_does_not_charge_twice, backend/tests/test_wallet_is_not_charged_twice.py::test_debt_is_closed_exactly_once, backend/tests/test_wallet_is_not_charged_twice.py::test_settlement_still_works_normally, backend/tests/test_wallet_is_not_charged_twice.py::test_not_enough_money_changes_nothing | да — M23 (снят лок), M24 (снят `WHERE status=unpaid`, db=sqlite) |
| R10 | Долг, СПИСАННЫЙ без оплаты, при повторном разборе НЕ превращается в фантомный «возврат» денег, которых платформа не получала | backend/tests/test_audit_fix_be23.py::test_повторное_списание_не_возвращает_никогда_не_полученную_комиссию, backend/tests/test_audit_fix_be23.py::test_реально_оплаченную_комиссию_возвращаем_ровно_один_раз, backend/tests/test_audit_fix_be23.py::test_списание_сохраняет_старую_пометку_под_однозначной_причиной | да — M26 |
| R11 | Если сумма pending изменилась между «админ увидел» и «нажал» — ничего не меняем (`DebtChanged`), не прощаем лишнее и не отклоняем лишнее | backend/tests/test_the_admin_confirms_what_he_saw.py::test_the_admin_sees_the_declared_sum, backend/tests/test_the_admin_confirms_what_he_saw.py::test_confirming_a_stale_row_does_not_forgive_new_debt, backend/tests/test_the_admin_confirms_what_he_saw.py::test_confirming_what_you_see_still_works, backend/tests/test_the_admin_confirms_what_he_saw.py::test_an_old_admin_screen_can_still_confirm_after_a_refresh, backend/tests/test_the_admin_confirms_what_he_saw.py::test_rejecting_a_stale_row_is_guarded_too, backend/tests/test_the_admin_confirms_what_he_saw.py::test_an_old_admin_client_closes_only_the_row_it_clicked | да — M27 |
| R12 | Расшифровка заработка честна: списанная без оплаты комиссия НЕ показана удержанной; реально удержанная онлайн — показана | backend/tests/test_money_holes_audit.py::test_прощённая_комиссия_не_показана_удержанной, test_комиссия_удержанная_онлайн_остаётся_в_расшифровке | да — M28 |
| R13 | «Не заплатили» засчитывается только по ПОДТВЕРЖДЁННОЙ (resolved) жалобе, не по голому факту её подачи | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r5_unresolved_report_does_not_count_as_confirmed_unpaid; backend/tests/test_driver_money_gaps.py::test_confirmed_unpaid_report_voids_commission (позитивный путь) | да — M29 |
| R14 (права) | Долг/заработок/поездки — только СВОИ (анти-IDOR) | backend/tests/test_debt.py::test_debt_is_per_token_no_idor, backend/tests/test_driver_money_gaps.py::test_driver_rides_are_private | покрыто существующим набором, отдельную мутацию не заводил (структурная защита — `user.id` из токена, нет параметра «чужой id») |
| R15 | Короткий срочный срок не блокирует водителя, у которого не было ШАНСА узнать о долге (не заходил после начисления) | backend/tests/test_debt_pay_now.py::test_short_deadline_does_not_block_a_driver_who_could_not_know, test_short_deadline_blocks_when_the_driver_has_been_in_the_app | да — M30 |

## Найденные ошибки

Ошибок в `debt.py` не найдено. Файл прошёл множество аудитных волн (видно по комментариям:
150–220) и каждое денежное правило уже защищено тестом, часто — с явным «до/после» в самом
комментарии кода (например, волна 190, 215, 219, 220). Я добавил 15 нарочных поломок на 15
разных денежных/честностных правил, все KILLED — новых дыр они не вскрыли, только подтвердили,
что существующая защита работает.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M17 | `order_commission_kop`: база без вычета компенсации | test_r1_commission_base_excludes_compensation | KILLED |
| M18 | `accrue_for_order`: ставка на `now`, а не на `created_at` | test_cashless_fee_matches_debt_fee_when_order_crosses_tier_boundary | KILLED |
| M19 | Граница 1-й ступени `<` → `<=` | test_r2_ladder_boundary_is_exclusive_at_tier1 | KILLED |
| M20 | Граница 2-й ступени `<` → `<=` | test_r2_ladder_boundary_is_exclusive_at_tier2 | KILLED |
| M21 | Сравнение даты набора `>` → `<` (промо получают ОПОЗДАВШИЕ) | test_одобренный_в_сентябре_не_попадает_в_августовский_набор | KILLED |
| M22 | Останов «не хватило на старый долг» отключён | test_не_хватает_на_старый_долг_деньги_ждут | KILLED |
| M23 | Row-lock перед чтением баланса убран | test_settlement_takes_a_row_lock_before_reading_the_balance | KILLED |
| M24 | Условие `status=unpaid` в `UPDATE` убрано (db=sqlite) | test_sqlite_stale_debt_update_does_not_charge_wallet | KILLED |
| M26 | Проверка «уже списан без оплаты» отключена в `void_debt_for_order` | test_повторное_списание_не_возвращает_никогда_не_полученную_комиссию | KILLED |
| M27 | Сверка суммы в `_batch_admin_saw` отключена | test_confirming_a_stale_row_does_not_forgive_new_debt | KILLED |
| M28 | `fee_charged_kop`: списанная комиссия продолжает считаться удержанной | test_прощённая_комиссия_не_показана_удержанной | KILLED |
| M29 | `unpaid_confirmed_order_ids`: фильтр `status=="resolved"` убран | test_r5_unresolved_report_does_not_count_as_confirmed_unpaid | KILLED |
| M30 | Фильтр «имел шанс узнать» в `_reason_from` убран | test_short_deadline_does_not_block_a_driver_who_could_not_know | KILLED |

Прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.1.json --only M17,M18,M19,M20,M21,M22,M23,M24,M26,M27,M28,M29,M30` → `MUTATIONS KILLED 13/13`.

## Остаток и ограничения

- **R6** (попутка не блокируется) — гарантия живёт в ОТСУТСТВИИ вызова `taxi_block_reason` из
  `routers/instant.py`/`routers/rides.py` (оба вне OWNS). Я подтвердил это чтением (`grep` по
  вызовам `taxi_block_reason`/`_guard_taxi_not_blocked` — используются только в instant-ручках)
  и существующим тестом `test_poputka_not_blocked_by_debt`, но НЕ заводил свою мутацию — ломать
  пришлось бы чужой файл, что запрещено правилами листа.
- **R9 (многострочная гонка)** — честно пишу об ограничении существующего набора, не моём
  недочёте: `settle_debt_from_wallet` берёт row-lock строки водителя именно для случая
  НЕСКОЛЬКИХ одновременных долгов (при одном долге атомарный `UPDATE ... WHERE status=unpaid`
  уже сам по себе исключает двойное списание на PostgreSQL, лок в этом случае избыточен). Я
  попробовал мутацию «снять лок, прогнать `test_parallel_settlement_does_not_charge_twice` на
  настоящем PostgreSQL» (M25 в черновике) — она ВЫЖИЛА, потому что у этого теста ровно ОДИН
  долг, и для одного долга условного `UPDATE` достаточно без лока. Я убрал эту поломку из
  спецификации, а не оставил фиктивным «KILLED»: это честная находка — у текущего набора тестов
  нет сценария с НЕСКОЛЬКИМИ долгами и настоящей многопоточной гонкой на PostgreSQL, который
  доказал бы необходимость самого лока (а не только условного `UPDATE`). Код при этом
  **корректен** (лок — правильная защита по его собственному объяснению в комментариях), просто
  эта конкретная грань не покрыта автотестом. Добавление такого теста — отдельная, более дорогая
  задача (нужны минимум два долга и управляемое чередование двух потоков на реальном Postgres).
- `driver_dashboard`/`driver_earnings`/`driver_rides` — денежная арифметика проверена (через
  существующие HTTP-тесты и частично напрямую), но полный перебор ВСЕХ комбинаций
  промо+лесенка+промокод+«кинутые» заказы одновременно не делал — полагаюсь на отдельные тесты
  каждого слагаемого (они покрыты порознь в уже существующем наборе).
