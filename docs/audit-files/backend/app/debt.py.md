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
| `driver_dashboard(session, driver_id, now=None)` | 213–311 | дашборд кабинета: заработок/заказы/комиссия ЗА МЕСТНЫЙ ДЕНЬ, текущая и следующая ступень, промо | местная полночь считается сдвигом UTC, не завязана на часовой пояс сервера; `net_today_kop = max(gross − disc − fee + comp, 0)` — ТА ЖЕ формула, что `driver_rides`, иначе два экрана показывают разные деньги за один день | испр. границ нет, но защищено мутацией (M47) |
| `_local_day_expr(session, column)` | 314–322 | SQL-выражение «местный день» — портируемо SQLite/PostgreSQL | смещение — свой int-конфиг, инъекции нет | ок, проверено на обеих БД |
| `unpaid_confirmed_order_ids(session, driver_id)` | 325–348 | заказы, по которым РАЗБОР (не голое слово) подтвердил «не заплатили» | фильтр по `Report.status=="resolved"`, НЕ по факту жалобы | испр. границ нет, но защищено мутацией (R13) |
| `unpaid_confirmed_parcel_ids(session, courier_id)` | 351–366 | то же для доставок курьера | пара к предыдущей, тот же договор | ок (вне денежной проверки — курьерская сторона в leaf курьера) |
| `driver_earnings(session, driver_id, period, now=None)` | 369–423 | история заработка (week/month/all) + разбивка по дням, SQL-агрегатом | «кинутые» (unpaid_confirmed) заказы исключены из заработка, но их сумма названа отдельно (`unpaid_total`) | ок |
| `driver_rides(session, driver_id, limit=100)` | 426–494 | список завершённых поездок с расшифровкой цена/комиссия/чистыми | `limit` зажат `[1,200]`; «не заплатили» → `net_kop=0`, но строка остаётся видна; `net_kop = price·100 − disc_kop − fee_kop + comp_kop` (каждый знак отдельно защищён мутацией) | испр. границ нет, но защищено мутацией (M46) |
| `order_commission_kop(order, percent=None)` | 497–517 | комиссия = (цена − компенсация) × процент | `price<=0`→0; база после вычета компенсации `<=0`→0 | ок (бывший риск через `compensation.py` — см. его карточку). **С правкой F1 (см. `ledger.py.md`) эту же функцию теперь зовёт и `ledger.settle_instant_order`** — одна формула на нал и на карту |
| `_clock_starts(now)` | 520–538 | с какого момента считать часы на срочную оплату (ночью — с утра) | тихие часы выключены → не сдвигать | ок |
| `_due_at(now, amount_kop, pay_now_allowed=True)` | 541–555 | срок оплаты: крупная комиссия — сразу (с учётом тихих часов), иначе — неделя | `pay_now_allowed=False` (авто-закрытие ночью) → всегда неделя | ок |
| `is_pay_now(d)` | 558–572 | это был срочный долг (не обычный недельный)? По ОКНУ записи, не по текущему конфигу | окно должно быть `0 <= Δ < 1 день`, иначе (ручной сдвиг даты) — не считается срочным | ок |
| `accrue_for_order(session, order, pay_now_allowed=True)` | 575–631 | начислить долг за завершённый заказ, идемпотентно | нет водителя→`None`; уже начислено (UNIQUE order_id)→существующая запись; гонка→`IntegrityError`→откат, та же запись; ставка ФИКСИРУЕТСЯ на `created_at` заказа; промо-компенсация гасит долг из кошелька сразу | ок |
| `refund_ext_id(*, order_id=None, parcel_id=None)` | 634–636 | ключ идемпотентности возврата комиссии | один заказ/доставка — один возврат | ок |
| `refund_commission_to_wallet(...)` | 642–690 (сдвинулось правкой F3) | вернуть УЖЕ ПЕРЕВЕДЁННУЮ комиссию в кошелёк (не выплата на карту — выплаты выключены до ИП) | `driver_id=None`/`amount<=0`→`None`; идемпотентно по `ext_id` ПОСЛЕДОВАТЕЛЬНО (ранний `if prev is not None`) И под настоящей гонкой (вставка под `session.begin_nested()` — SAVEPOINT, ловит `IntegrityError` от частичного `uq_ledgerentry_refund` в `ledger.py`); НЕ коммитит (чужая транзакция), откат гонки — только локальный (savepoint), не трогает чужие изменения в той же транзакции | испр. (F3) — было: гонка ловилась только последовательной проверкой в коде, без БД |
| `void_debt_for_order(session, order_id, note="")` | ~693–747 | снять долг по заказу, оплаченному ОНЛАЙН (комиссия уже удержана записью `fee`) | нет долга→`None`; списан без оплаты (`WRITTEN_OFF_PREFIX`)→повтор `None`, БЕЗ фантомного возврата; реально оплачен (paid, без метки)→`"refunded"`; обычный unpaid/pending→`"voided"`; note онлайн-оплаты сохраняется ДОСЛОВНО (не подменяется на «Списан») | испр. границ нет, но защищено мутацией (M41) |
| `TAXI_DEBT_SNAPSHOT_NAMESPACE`/`taxi_debt_snapshot(session, driver_id)`/`make_taxi_debt_snapshot_tier(ids)`/`taxi_debt_snapshot_ids(payment)` | добавлено в этом листе (F2) | снимок точного состава НЕ оплаченного долга (сумма+ID одним запросом), маркер для `Payment.tier`, разбор маркера обратно (fail-closed на повреждении) | зеркало `courier._courier_snapshot_ids` (волна 218); используется `routers/debt.py` (запись снимка при создании счёта) и `settle_debt_from_wallet` ниже (чтение снимка) | испр. (F2, новое) |
| `taxi_owed_kop(session, driver_id)` | сдвинулось | сумма unpaid+pending одним числом | `driver_id=None`→0 | ок |
| `settle_debt_from_wallet(session, driver_id, now=None)` | сдвинулось | погасить долг деньгами, уже лежащими в кошельке | row-lock строки водителя ДО чтения баланса; FIFO, ТОЛЬКО целиком, останов когда не хватило; атомарный `UPDATE ... WHERE status=unpaid` — вторая линия обороны на SQLite; только по `InstantOrder.paid==True` (не трогает долг по НЕОПЛАЧЕННОМУ заказу — способ оплаты ещё не известен); **новое (F2): исключает долги из снимка ЛЮБОГО `pending`-счёта ЮKassa на оплату долга** — иначе кошелёк и карта могли погасить одну и ту же комиссию дважды | испр. (F2) — см. «Найденные ошибки»; R9 (двойное списание ОДНОГО долга) защищено двумя способами, но лок для случая НЕСКОЛЬКИХ одновременных долгов теперь доказан поведенческим тестом на PostgreSQL (M53) |
| `_unpaid`/`_pending(session, driver_id)` | сдвинулось | выборки долгов по статусу | фильтр СТРОГО по `driver_id` (анти-IDOR) | испр. границ нет, но защищено мутацией (M50) |
| `DECLARE_TRUST_DAYS = 3` | 893 | срок жизни «слова» без подтверждения деньгами | прошито в коде, не в конфиге (осознанно — это не тариф) | ок |
| `crossed_warn_line(session, driver_id, just_added_kop)` | 896–915 | долг ИМЕННО ЭТИМ заказом пересёк линию предупреждения? | уже за порогом блокировки → не предупреждаем (поздно, нужен другой разговор) | ок |
| `_stale_declares(pending, now)` | 918–923 | протухшие «слова» (>3 дня без подтверждения) | фолбэк на `created_at`, если `paid_declared_at` пуст | ок |
| `taxi_block_reason(session, driver_id, now=None)` | 926–947 | причина блокировки ТАКСИ или `None` | склеивает `_unpaid`/`_pending`/`last_seen` → `_reason_from` | ок |
| `_had_a_chance_to_know(d, last_seen)` | 950–968 | мог ли водитель УЗНАТЬ про короткий срочный долг (заходил ли после начисления) | нет даты начисления → ведёт себя как раньше (`True`) | ок |
| `_reason_from(unpaid, pending, now, last_seen=None)` | 971–990 | чистое решение по уже собранным долгам: `declare_abuse` > `declare_stale` > `overdue` (с поправкой на «имел шанс узнать») > `over_threshold` > `None` | используется и одиночным гейтом, и пакетной проверкой круга подбора — ОДНА логика на оба места | ок |
| `blocked_driver_ids(session, driver_ids, now=None)` | 993–1018 | кто из списка заблокирован, ОДНИМ запросом | та же `_reason_from`, что и одиночный гейт — не расходится | ок |
| `TAXI_BLOCKED_MSG(_BA)` | 1024–1025 | двуязычный текст отказа | — | ок |
| `debt_summary(session, driver_id)` | 1028–1067 | сводка для кабинета: суммы, срок, реквизиты СБП, блок, разбивка по неделям, «оплати сразу» | — | ок |
| `declare_paid(session, driver_id)` | сдвинулось | все unpaid → pending, считает `declare_count` | ничего не должен → 0, без коммита; `declare_count` растёт на КАЖДОЕ заявление (иначе `declare_abuse` никогда не сработает) | испр. границ нет, но защищено мутацией (M42) |
| `mark_all_paid(session, driver_id, up_to=None, *, commit=True)` | сдвинулось | погасить весь долг (оплата картой, вебхук) | `up_to` — граница снапшота (`created_at <= up_to`, не гасит долг, начисленный уже ПОСЛЕ создания платежа) | испр. границ нет, но защищено мутацией (M36). **См. F2 ниже — у ТАКСИ (в отличие от курьера) снимок для `mark_all_paid` по-прежнему временно́й, не по ID; правка в `routers/payments.py`, вне зоны** |
| `DebtChanged` | сдвинулось | сумма изменилась между «админ увидел» и «нажал» | несёт `expected_kop`/`actual_kop` | ок |
| `_batch_admin_saw(session, debt, expected_kop)` | сдвинулось | какие именно долги закрывает нажатие админа | сумма прислана → сверка точная, не сошлась → `DebtChanged`, НИЧЕГО не трогаем; суммы нет (старый клиент) → сужаем до долгов того же `paid_declared_at` | ок |
| `admin_confirm(session, debt_id, expected_kop=None)` | сдвинулось | весь pending водителя → paid | долга нет → `None`; при несовпадении — см. выше | ок |
| `admin_reject(session, debt_id, expected_kop=None)` | сдвинулось | pending → обратно unpaid, тратит «слово» | та же защита `_batch_admin_saw`; сбрасывает `paid_declared_at` (иначе протухание СЛЕДУЮЩЕГО заявления считалось бы от старой, уже отклонённой даты) | испр. границ нет, но защищено мутацией (M43) |

## Связи

- `backend/app/ledger.py` (этот же лист) — `driver_fee_percent` зовётся из `settle_instant_order` (момент фиксации ставки — `created_at`, СОВПАДАЕТ с `accrue_for_order`); `taxi_owed_kop` зовётся из `owed_to_platform_kop`.
- `backend/app/routers/debt.py` (этот же лист) — единственный HTTP-фасад над этим модулем.
- `backend/app/routers/safety.py` (вне OWNS) — `admin_resolve_report` зовёт `void_debt_for_order`/`unpaid_confirmed_order_ids` при подтверждении жалобы «не заплатили».
- `backend/app/routers/instant.py` (вне OWNS) — `_guard_taxi_not_blocked` зовёт `taxi_block_reason` на `/instant/presence`, `/driver/offer`, `/orders/{id}/accept`; `/rides` (попутка) этот гейт НЕ зовёт вообще — отсюда и гарантия «попутка не блокируется» (подтверждено тестом вне этого листа: `test_poputka_not_blocked_by_debt`, сам факт отсутствия вызова проверять вне OWNS не стал).
- `backend/app/compensation.py` (этот же лист) — `order_commission_kop` зовёт `compensation_rub`.
- `backend/app/promo_ride.py` (вне OWNS) — `split_commission` зовётся из `accrue_for_order`.
- `backend/app/cleanup.py` (вне OWNS) — `expire_stale_declares` возвращает протухший `pending` в `unpaid`.
- `Payment` (`models.py`, вне OWNS) — новое (F2): `settle_debt_from_wallet` читает `Payment(purpose="taxi_debt", status="pending")` своего водителя и его поле `tier` (снимок), чтобы не трогать долги, уже выставленные в счёте ЮKassa. `routers/debt.py` (этот же лист) пишет этот снимок при создании счёта.
- `backend/app/routers/payments.py::_activate_payment` (вне OWNS) — при успешной оплате долга картой зовёт `mark_all_paid(up_to=payment.created_at)`; ПО ВРЕМЕНИ, не по ID снимка — остаточный разрыв с F2, см. «Остаток» и отчёт (раздел ВНЕ ЗОНЫ).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Комиссия берётся с (цена − компенсация), не со всей цены | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r1_commission_base_excludes_compensation, test_r1_compensation_covering_the_whole_price_means_zero_commission, test_r1_non_positive_price_is_zero_commission_not_negative | да — M17 |
| R2 | Ставка комиссии в долге фиксируется на `created_at` заказа, а не на момент завершения — совпадает со ставкой online-оплаты за ту же поездку | backend/tests/test_fee_percent_consistency.py::test_cashless_fee_matches_debt_fee_when_order_crosses_tier_boundary | да — M18 |
| R3 | Границы лесенки строго `<` (ровно N-я поездка — уже следующая ступень) | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r2_ladder_boundary_is_exclusive_at_tier1/_at_tier2, test_r2_ladder_tier1_at_the_start, test_r2_far_beyond_tier2_stays_on_the_base_rate; backend/tests/test_fee_ladder_trips.py, backend/tests/test_money_rules.py (test_fee_*) | да — M19, M20 |
| R4 | Промо запуска — только для одобренных ДО даты набора ПО МЕСТНОМУ календарю | backend/tests/test_the_launch_promo_knows_the_local_calendar.py::test_одобренный_в_сентябре_не_попадает_в_августовский_набор (и далее) | да — M21 |
| R6 (ПОПУТКА) | Таксистский блок (`taxi_block_reason`) не применяется к попутке — гарантия на уровне «эта функция вообще не вызывается из `/rides`» | backend/tests/test_debt.py::test_poputka_not_blocked_by_debt | покрыто существующим тестом; мутацию в debt.py завести нельзя — вызов живёт в чужом файле (routers/instant.py), это ВНЕ ЗОНЫ |
| R8 | Зачёт долга из кошелька — FIFO, целыми долгами, останавливается при нехватке на самый старый | backend/tests/test_the_wallet_works_and_the_ride_is_free.py::test_не_хватает_на_старый_долг_деньги_ждут, test_очередь_долгов_соблюдается, test_ровно_хватило_значит_гасим | да — M22 |
| R9 | Зачёт из кошелька защищён от двойного списания ДВУМЯ способами: row-lock (Postgres) и условный `UPDATE` (SQLite); прогнано и на SQLite, и на настоящем PostgreSQL. **Лок нужен именно при НЕСКОЛЬКИХ одновременных долгах** (при одном — условного `UPDATE` достаточно, см. «Остаток» первой сдачи) — это теперь доказано отдельным поведенческим тестом на PostgreSQL с двумя долгами (M53), а не только структурной проверкой (M23) | backend/tests/test_wallet_is_not_charged_twice.py::test_settlement_takes_a_row_lock_before_reading_the_balance, backend/tests/test_wallet_is_not_charged_twice.py::test_sqlite_stale_debt_update_does_not_charge_wallet, backend/tests/test_wallet_is_not_charged_twice.py::test_parallel_settlement_does_not_charge_twice, backend/tests/test_wallet_is_not_charged_twice.py::test_debt_is_closed_exactly_once, backend/tests/test_wallet_is_not_charged_twice.py::test_settlement_still_works_normally, backend/tests/test_wallet_is_not_charged_twice.py::test_not_enough_money_changes_nothing, backend/tests/walk/l1_1/test_l1_1_wallet_lock_two_debts.py::test_r1_lock_prevents_overdraw_when_two_debts_fit_a_stale_balance | да — M23 (снят лок, структурно, sqlite), M24 (снят `WHERE status=unpaid`, sqlite), **M53 (снят ТОТ ЖЕ лок, поведенчески, два долга, db=postgres — новое)** |
| R-F2 | Пока у водителя висит счёт ЮKassa на оплату долга картой, кошелёк НЕ гасит долги из ЭТОГО счёта (снимок на `Payment.tier`) — новые долги гасит как обычно | backend/tests/walk/l1_1/test_l1_1_debt_card_vs_wallet.py::test_r1_wallet_does_not_touch_debts_billed_by_a_pending_card_invoice, test_r2_a_new_debt_after_the_invoice_is_still_settled_from_wallet, test_r3_after_invoice_is_gone_wallet_settles_normally_again, test_r4_repeated_pay_click_with_a_live_invoice_keeps_quoting_the_same_amount | да — M54 (заведена дополнительно к до/после из «Найденные ошибки»: первая версия карточки не завела формальную поломку на САМУ проверку `if в_оплате is not None:` — пробел, не замеченный до перечитывания спецификации перед сдачей) |
| R-F2b | Зачёт кошельком не трогает долг по НЕОПЛАЧЕННОМУ заказу (способ оплаты ещё не известен) | backend/tests/walk/l1_1/test_l1_1_debt_card_vs_wallet.py::test_r5_wallet_never_settles_a_debt_on_an_unpaid_order | да — M35 |
| R16 | `declare_count` растёт на КАЖДОЕ заявление «Я оплатил» | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r11_declare_count_increments_on_each_declare_paid_call | да — M42 |
| R17 | `admin_reject` сбрасывает `paid_declared_at` | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r12_admin_reject_clears_paid_declared_at | да — M43 |
| R18 | `mark_all_paid(up_to=...)` — граница `<=`, не гасит долг младше снапшота платежа | backend/tests/test_release_hardening.py::test_taxi_debt_window_not_forgiven | да — M36 |
| R19 | `refund_commission_to_wallet` идемпотентен и БЕЗ гонки (последовательный повтор) | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r9_sequential_refund_is_idempotent_without_any_race | тест зелёный, но ЧЕСТНО без своей поломки — см. «Остаток»: единственная осмысленная мутация (снять ранний `if prev is not None`) ВЫЖИЛА, потому что теперь её же дублирует частичный индекс `uq_ledgerentry_refund` (F3) |
| R20 | `void_debt_for_order` сохраняет note онлайн-оплаты ДОСЛОВНО, не подменяет служебной пометкой | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r10_online_payment_note_is_preserved_not_overwritten_as_written_off | да — M41 |
| R21 | `driver_rides.net_kop` = цена − скидка − комиссия + компенсация (каждый знак) | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r13_driver_rides_net_kop_formula_is_price_minus_discount_minus_fee_plus_comp | да — M46 |
| R22 | `driver_dashboard.net_today_kop` — ТА ЖЕ формула, что R21, для «сегодня» | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r14_driver_dashboard_net_today_formula_matches_driver_rides | да — M47 |
| R23 | Порог «оплатить сразу» — `amount_kop >= порога` (не строго `>`) | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r15_pay_now_threshold_boundary_is_not_greater_or_equal | да — M48 |
| R24 | Окно «срочного» долга — строго `< 1 день` (не `<=`) | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r16_is_pay_now_window_boundary_is_strictly_less_than_a_day | да — M49 |
| R25 (IDOR) | `_unpaid` фильтрует СТРОГО по своему `driver_id` | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r17_unpaid_never_returns_another_drivers_debt, backend/tests/test_debt.py::test_debt_is_per_token_no_idor | да — M50 |
| R26 | Границы порогов блокировки строго `>`/`<` (не `>=`/`<=`): `declare_abuse`, `declare_stale`, `over_threshold` — и КАЖДЫЙ обязан победить НИЖЕСТОЯЩУЮ причину, когда условия обеих истинны одновременно | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r6_declare_abuse_boundary_is_strictly_greater_not_greater_or_equal, test_r7_declare_stale_boundary_is_strictly_less_not_less_or_equal, test_r8_over_threshold_boundary_is_strictly_greater_not_greater_or_equal, test_r4_priority_abuse_outranks_stale_even_when_both_are_true, test_r4_priority_stale_outranks_overdue_even_when_both_are_true, test_r4_priority_overdue_outranks_over_threshold_even_when_both_are_true | да — M37, M38, M39 |
| R10 | Долг, СПИСАННЫЙ без оплаты, при повторном разборе НЕ превращается в фантомный «возврат» денег, которых платформа не получала | backend/tests/test_audit_fix_be23.py::test_повторное_списание_не_возвращает_никогда_не_полученную_комиссию, backend/tests/test_audit_fix_be23.py::test_реально_оплаченную_комиссию_возвращаем_ровно_один_раз, backend/tests/test_audit_fix_be23.py::test_списание_сохраняет_старую_пометку_под_однозначной_причиной | да — M26 |
| R11 | Если сумма pending изменилась между «админ увидел» и «нажал» — ничего не меняем (`DebtChanged`), не прощаем лишнее и не отклоняем лишнее | backend/tests/test_the_admin_confirms_what_he_saw.py::test_the_admin_sees_the_declared_sum, backend/tests/test_the_admin_confirms_what_he_saw.py::test_confirming_a_stale_row_does_not_forgive_new_debt, backend/tests/test_the_admin_confirms_what_he_saw.py::test_confirming_what_you_see_still_works, backend/tests/test_the_admin_confirms_what_he_saw.py::test_an_old_admin_screen_can_still_confirm_after_a_refresh, backend/tests/test_the_admin_confirms_what_he_saw.py::test_rejecting_a_stale_row_is_guarded_too, backend/tests/test_the_admin_confirms_what_he_saw.py::test_an_old_admin_client_closes_only_the_row_it_clicked | да — M27 |
| R12 | Расшифровка заработка честна: списанная без оплаты комиссия НЕ показана удержанной; реально удержанная онлайн — показана | backend/tests/test_money_holes_audit.py::test_прощённая_комиссия_не_показана_удержанной, test_комиссия_удержанная_онлайн_остаётся_в_расшифровке | да — M28 |
| R13 | «Не заплатили» засчитывается только по ПОДТВЕРЖДЁННОЙ (resolved) жалобе, не по голому факту её подачи | backend/tests/walk/l1_1/test_l1_1_debt_pure_rules.py::test_r5_unresolved_report_does_not_count_as_confirmed_unpaid; backend/tests/test_driver_money_gaps.py::test_confirmed_unpaid_report_voids_commission (позитивный путь) | да — M29 |
| R14 (права) | Долг/заработок/поездки — только СВОИ (анти-IDOR) | backend/tests/test_debt.py::test_debt_is_per_token_no_idor, backend/tests/test_driver_money_gaps.py::test_driver_rides_are_private | да — косвенно, через M50 (см. R25: `_unpaid` — внутренняя функция, которую зовут и `debt_summary`, и `taxi_block_reason`; её собственная фильтрация по `driver_id` и есть техническая реализация этого права) |
| R15 | Короткий срочный срок не блокирует водителя, у которого не было ШАНСА узнать о долге (не заходил после начисления) | backend/tests/test_debt_pay_now.py::test_short_deadline_does_not_block_a_driver_who_could_not_know, test_short_deadline_blocks_when_the_driver_has_been_in_the_app | да — M30 |

## Найденные ошибки

**Первая сдача этого листа сказала «ошибок не найдено» — это было неверно.** Независимое ревью
Opus нашло две денежные ошибки (F2 — P1, F3 — P2), которые я пропустил при первом проходе.
Фиксирую честно.

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| F2 | Водитель включает оплату долга картой (ЮKassa). Пока банк думает (счёт `pending`), «Завершил» новую поездку или просто открывает кабинет — а `settle_debt_from_wallet` ничего не знал о выставленном счёте и гасил ИЗ КОШЕЛЬКА те же самые долги, которые уже выставлены в счету. Если в кошельке в этот момент были деньги платформы (например, компенсация промокода), спустя пару секунд банк подтверждает перевод — и человек заплатил за одну комиссию ДВАЖДЫ: один раз кошельком, один раз картой. Повторное нажатие «оплатить» к тому же отдавало счёт со СТАРОЙ суммой, хотя кошелёк уже часть долга погасил. У курьера эта дверь была закрыта волной 218 (снимок ID доставок); у такси — нет. | `tests/walk/l1_1/test_l1_1_debt_card_vs_wallet.py::test_r1_wallet_does_not_touch_debts_billed_by_a_pending_card_invoice` — выставить счёт на 2 долга по 100 ₽, положить в кошелёк 200 ₽ (имитация прихода компенсации), позвать `settle_debt_from_wallet` — ожидать 0 | Зеркало курьерской защиты: новые `debt.taxi_debt_snapshot`/`make_taxi_debt_snapshot_tier`/`taxi_debt_snapshot_ids` — точный снимок состава долга пишется в `Payment.tier` при создании счёта (`routers/debt.py`), `settle_debt_from_wallet` исключает долги из снимка ЛЮБОГО `pending`-счёта | 4 теста в `test_l1_1_debt_card_vs_wallet.py` (R1–R4): все красные на коде до правки (проверено откатом и повторным прогоном), зелёные после |
| F3 | Админ (или два админа) кликают «подтвердить жалобу о неоплате» дважды почти одновременно на поездку, за которую водитель УЖЕ перевёл комиссию (долг `paid`). `refund_commission_to_wallet` проверял «уже возвращали?» только в коде («нашёл запись → не пишу») — без замка и без уникальности в БД. Обе проверки видят «возврата нет», обе пишут свою запись — водителю возвращают одну и ту же комиссию ДВАЖДЫ, деньги платформы уходят в никуда без следа. Воспроизводится и на SQLite — замков в этом пути не было совсем. | `tests/walk/l1_1/test_l1_1_refund_race.py::test_r1_concurrent_report_confirmations_refund_the_commission_exactly_once` — через `before_flush`-перехват (тот же приём, что уже используется в `test_money_holes_audit.py` для гонки компенсации промокода) второй «admin» дописывает и коммитит свой возврат РОВНО в момент flush первого | Частичный уникальный индекс `uq_ledgerentry_refund` (`ledger.py`, по образцу `uq_ledgerentry_promo_comp`) + вставка в `refund_commission_to_wallet` под `session.begin_nested()` (SAVEPOINT) — проигравшая сторона ловит `IntegrityError` и откатывает ТОЛЬКО свою вставку | До правки: `assert исход is None` падал (`'refunded' is None` — обе стороны получали «refunded»). После: проходит, записей возврата ровно одна, баланс не задвоен |

Кроме F1 (см. `ledger.py.md`), F2 и F3, новых ошибок не нашёл. Файл прошёл множество аудитных
волн (видно по комментариям: 150–220) и каждое денежное правило уже защищено тестом, часто — с
явным «до/после» в самом комментарии кода (например, волна 190, 215, 219, 220). Я добавил
нарочные поломки на денежные/честностные правила, включая новые из этого раунда — все KILLED.

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
| M35 | Фильтр `InstantOrder.paid==True` в зачёте кошельком снят (матчит и True, и False) | test_r5_wallet_never_settles_a_debt_on_an_unpaid_order | KILLED |
| M36 | `mark_all_paid`: `created_at <= up_to` → `>= up_to` (противоположное окно) | test_taxi_debt_window_not_forgiven | KILLED |
| M37 | `declare_abuse`: `>` → `>=` | test_r6_declare_abuse_boundary_is_strictly_greater_not_greater_or_equal, test_r4_priority_abuse_outranks_stale_even_when_both_are_true | KILLED |
| M38 | `_stale_declares`: `<` → `<=` | test_r7_declare_stale_boundary_is_strictly_less_not_less_or_equal, test_r4_priority_stale_outranks_overdue_even_when_both_are_true | KILLED |
| M39 | `over_threshold`: `>` → `>=` | test_r8_over_threshold_boundary_is_strictly_greater_not_greater_or_equal, test_r4_priority_overdue_outranks_over_threshold_even_when_both_are_true | KILLED |
| M41 | `void_debt_for_order`: сохранение note онлайн-оплаты отключено | test_r10_online_payment_note_is_preserved_not_overwritten_as_written_off | KILLED |
| M42 | `declare_paid`: инкремент `declare_count` убран | test_r11_declare_count_increments_on_each_declare_paid_call | KILLED |
| M43 | `admin_reject`: сброс `paid_declared_at` убран | test_r12_admin_reject_clears_paid_declared_at | KILLED |
| M46 | `driver_rides.net_kop`: знак у `fee_kop` перевёрнут | test_r13_driver_rides_net_kop_formula_is_price_minus_discount_minus_fee_plus_comp | KILLED |
| M47 | `driver_dashboard.net_today_kop`: тот же сдвиг знака | test_r14_driver_dashboard_net_today_formula_matches_driver_rides | KILLED |
| M48 | Порог «оплатить сразу»: `>=` → `>` | test_r15_pay_now_threshold_boundary_is_not_greater_or_equal | KILLED |
| M49 | `is_pay_now`: окно `< 1 день` → `<= 1 день` | test_r16_is_pay_now_window_boundary_is_strictly_less_than_a_day | KILLED |
| M50 | `_unpaid`: фильтр `driver_id` убран (IDOR) | test_r17_unpaid_never_returns_another_drivers_debt, test_debt_is_per_token_no_idor | KILLED |
| M53 | Тот же row-lock, что M23, но тест — поведенческий, ДВА долга, db=postgres | test_r1_lock_prevents_overdraw_when_two_debts_fit_a_stale_balance | KILLED |
| M54 | Снимок висящего счёта ЮKassa (F2): `if в_оплате is not None:` инвертирован на `is None` — исключение долгов из зачёта кошельком включается НЕ тогда, когда счёт висит | test_r1_wallet_does_not_touch_debts_billed_by_a_pending_card_invoice | KILLED |

Прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.1.json --only M17,M18,M19,M20,M21,M22,M23,M24,M26,M27,M28,M29,M30,M35,M36,M37,M38,M39,M41,M42,M43,M46,M47,M48,M49,M50,M53,M54` → `MUTATIONS KILLED 27/27`.

## Остаток и ограничения

- **R6** (попутка не блокируется) — гарантия живёт в ОТСУТСТВИИ вызова `taxi_block_reason` из
  `routers/instant.py`/`routers/rides.py` (оба вне OWNS). Я подтвердил это чтением (`grep` по
  вызовам `taxi_block_reason`/`_guard_taxi_not_blocked` — используются только в instant-ручках)
  и существующим тестом `test_poputka_not_blocked_by_debt`, но НЕ заводил свою мутацию — ломать
  пришлось бы чужой файл, что запрещено правилами листа.
- **R9 (многострочная гонка) — ЗАКРЫТО в этом раунде.** Первая сдача оставила это честной
  находкой без доказательства: мутация «снять лок, прогнать `test_parallel_settlement_does_not_charge_twice`
  на PostgreSQL» (M25 в черновике) выживала, потому что у ТОГО теста ровно один долг, а для
  одного долга атомарного `UPDATE ... WHERE status=unpaid` достаточно и без лока. Независимое
  ревью Opus подтвердило анализ и прислало рецепт детерминированного (без `sleep`, без реальных
  потоков) теста с ДВУМЯ долгами: кошелёк 300 ₽, долги D1=200₽/D2=200₽; пока зачёт A держит
  лок и уже прочитал баланс, через monkeypatch `driver_balance` вклинивается зачёт B на том же
  водителе с коротким `lock_timeout`. Без лока B проходит одновременно с A, и вместе они спишут
  400 ₽ с баланса 300 ₽ (кошелёк в минус); с локом B честно ждёт/отказывает. Тест —
  `test_l1_1_wallet_lock_two_debts.py::test_r1_lock_prevents_overdraw_when_two_debts_fit_a_stale_balance`,
  поломка M53. Проверил оба направления вручную (откатывал лок — тест красный, `b_blocked=False`;
  возвращал — зелёный, `b_blocked=True`) ДО того, как завёл M53 в спецификацию.
- **F2, остаточный разрыв (ВНЕ ЗОНЫ).** Снимок `taxi_debt_snapshot` закрывает зачёт кошельком,
  но `routers/payments.py::_activate_payment` (не мой файл) при успешной оплате картой всё ещё
  зовёт `mark_all_paid(up_to=payment.created_at)` — ПО ВРЕМЕНИ, не по ID снимка. Если за время
  ожидания банка админ вручную простил один из снимка долгов (`admin_forgive`), `mark_all_paid`
  молча закроет МЕНЬШЕ, чем оплачено картой, и разницу никто не вернёт в кошелёк. Точная правка:
  на активации сравнить закрытую `mark_all_paid` сумму с `payment.amount_kop` и при недостаче
  вернуть разницу записью `refund:taxi_debt:{payment_id}` (функция для этого уже есть в этом
  листе — `refund_commission_to_wallet`, можно звать из чужого файла). Сообщил бы и leaf-1.2
  (`routers/payments.py.md`), но мне не выдан доступ что-то в нём писать — только в отчёте.
- `driver_dashboard`/`driver_earnings`/`driver_rides` — денежная арифметика проверена (через
  существующие HTTP-тесты, прямые тесты формул R21/R22 и частично напрямую), но полный перебор
  ВСЕХ комбинаций промо+лесенка+промокод+«кинутые» заказы одновременно не делал — полагаюсь на
  отдельные тесты каждого слагаемого (они покрыты порознь в уже существующем наборе).
- **R19 (возврат комиссии, последовательный повтор) — честная находка при финальном прогоне.**
  Снял раннюю проверку `if prev is not None:` в `refund_commission_to_wallet` (мутация, рабочее
  название M40) — тест `test_r9_sequential_refund_is_idempotent_without_any_race` остался
  ЗЕЛЁНЫМ. Причина не в слабом тесте: правка F3 в этом же раунде добавила частичный уникальный
  индекс `uq_ledgerentry_refund` (`ledger.py`) и вставку под SAVEPOINT — и на ВТОРОМ
  последовательном вызове (без всякой ранней проверки) код всё равно пытается вставить запись
  с уже занятым `ext_id`, ловит `IntegrityError` от индекса и возвращает `None` — РОВНО тот же
  результат, что давала убранная проверка. Ранняя проверка осталась в коде (экономит один
  неудачный INSERT), но для ЭТОГО теста она больше не единственная линия обороны, и поломка на
  неё одну саму по себе не различает «защищено» от «не защищено». Я не стал оставлять фиктивный
  «KILLED» и не стал удалять сам тест (правило R19 верно и доказано — просто не ЭТОЙ мутацией) —
  убрал мутацию из спецификации, честно, по тому же принципу, что и M25 в прошлом раунде.
- **Мелкие находки независимого ревью Opus (P3, не блокируют, не чинил в этом раунде — записываю,
  как и просило ревью):**
  - **P3-a. Долг `pending` при поздней оплате картой.** Водитель уже перевёл комиссию по СБП, а
    пассажир позже заплатил картой — `void_debt_for_order` (`:716-732`) ставит такому долгу `paid`,
    а перевод по СБП остаётся у платформы без следа; админ увидит только 409 «сумма изменилась».
  - **P3-b. Начисление после оплаты картой.** `accrue_for_order` (`:585-591`) не проверяет, что
    заказ уже оплачен картой; `transition` коммитит `done` раньше начисления
    (`instant_service.py:2667-2673`, вне OWNS) — в этом узком окне долг Модели А появится уже
    после оплаты картой и снят не будет → двойная комиссия.
  - **P3-c. Дашборд «сегодня» не исключает «не заплатили».** Поездки с подтверждённой жалобой
    остаются в заработке дашборда (`:226-249`), хотя в `driver_earnings`/`driver_rides` они уже
    исключены (`:397-399`, `:471-473`) — правило волны 190 не доведено до третьего экрана.
  - **P3-d. Закрытый долг может «воскреснуть».** `declare_paid` и `admin_*` пишут статус
    безусловным ORM-UPDATE, без `WHERE status=…`; окно — параллельный зачёт кошельком между
    чтением и записью в `declare_paid` (миллисекунды).
  - **Кросс-лист (вне OWNS, только сообщаю).** `routers/safety.py::admin_resolve_report`
    (`:738-849`, не мой файл, упомянут выше как связь) не проверяет, что жалоба уже `resolved`, и
    при каждом вызове зовёт денежную побочку этого листа (`void_debt_for_order`/
    `refund_commission_to_wallet`) заново — последовательно безопасно (идемпотентность моей
    стороны это покрывает), но ДВА одновременных клика на эту ручку — ровно сценарий F3. Мой F3
    (частичный UNIQUE + SAVEPOINT) защищает деньги уже отсюда, со стороны `debt.py`/`ledger.py`,
    но сама проверка «уже разобрана» в `routers/safety.py` отсутствует — решение и правка за
    тем, кто ведёт этот файл (не выдан мне доступ).
