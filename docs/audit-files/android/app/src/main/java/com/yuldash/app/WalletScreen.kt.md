# Карточка: `android/app/src/main/java/com/yuldash/app/WalletScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: ожидает ревью

## Назначение

«Кошелёк» водителя: баланс + история операций (ledger) + вывод на карту (Модель Б, за
флагом `enabled` с сервера). Деньги показываются в копейках→рублях без потери копеек
(`fmtRub`/`kopToRub`); вывод защищён идемпотентностью и двойным подтверждением.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `WalletScreen` / `load()` | 88–112 | Грузит баланс+историю+статус выплат, рисует состояния | П1+П2 (ревью Opus) ИСПРАВЛЕНО: `balanceFailed`/`ledgerFailed` — НЕЗАВИСИМЫЕ флаги (раньше общий `error` требовал падения ОБОИХ сразу, и частичный сбой на первом открытии не показывался никак); `stale` — ТОЛЬКО если ДО этого вызова уже было что показать именно по этой части (раньше считал «есть ли сейчас хоть что-то», и пустая история на фоне свежего баланса тоже засчитывалась как «устарело»); payout грузится и падает НЕЗАВИСИМО | ок (после правки), R5, R6 |
| `WalletBalanceCard` | ~224–297 | Крупная сумма баланса + честная ошибка + подпись про доступность/долг | П1 (ревью Opus) ИСПРАВЛЕНО: при `failed && balance==null` показывает «Баланс не узнали» + «Повторить», раньше молча рисовала `kopToRub(0)`; подпись про долг — «ВНЕ ЗОНЫ» леада, исполнено: `owedKop = balanceKop - payableKop`, при `owedKop>0` текст честно называет, сколько уходит на долг, вместо «Доступно к выводу» под ПОЛНЫМ балансом | ок (после правки), R5, R8 |
| `WalletLedgerRow` | ~314–350 | Строка истории: направление, сумма со знаком и цветом | `amountKop>=0` → «+» и зелёный «приход», иначе приглушённый минус через `kopToRub` (типографский «−», не ASCII); `note.ifBlank{fallback}` | ок, R7 |
| `PayoutSoonCard` | ~355–384 | Честная заглушка, пока `enabled=false` | Без кнопок-обманок | ок |
| `PayoutCard` | ~391–600 | Живой вывод: карта, сумма, границы с сервера, объяснение долга, двойное подтверждение | См. R2–R4, R8 ниже | ок, R2–R4, R8 |
| `PayoutCardDialog` | ~613–683 | Диалог «Карта для выплат» | Полный номер НЕ логируется и НЕ уходит на сервер — только последние 4 цифры, посчитанные локально (`digits.takeLast(4)`) | ок, приватность |
| `fmtRub` | ~700 | Целые рубли с разрядом (узкий неразрывный пробел, U+202F) | П3 (ревью Opus, исполнено): был обычный пробел — место переноса строки, «1 234 ₽» на крупном шрифте мог перенестись на «1»/«234 ₽»; единственный денежный форматтер приложения (рубли), `kopToRub` (CouponsScreen.kt, вне зоны) — для копеек, не трогал (не в зоне) | ок (после правки), R1 |

## Связи

- `ApiClient.getWalletBalance/getWalletLedger/getPayoutStatus/requestPayout/savePayoutRequisite`
  (`data/ApiClient.kt`, вне зоны, только чтение) — `GET /wallet/balance`, `GET /wallet/ledger`,
  `GET /wallet/payout/status`, `POST /wallet/payout`, `POST /wallet/payout/requisite`.
- `fmtRub` переиспользуется в `IncomeCalculatorScreen.kt` и `DriverEarningsScreen.kt` (оба в
  этом же листе) — единый формат по всему приложению.
- Приватность подтверждена ЧТЕНИЕМ бэкенда (`backend/app/routers/wallet.py` — вне зоны):
  `/wallet/balance` и `/wallet/ledger` фильтруют по `current_user`, чужой кошелёк не достать.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | `fmtRub` — разряд-пробел, минус не отрывается на отрицательных суммах | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletMoneyFormatTest.kt::fmtRub_groupsThousandsWithSpace`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletMoneyFormatTest.kt::fmtRub_negative_minusStaysGluedToDigits` | да — M1 |
| R2 | Сумма вывода вне границ (мин/макс/баланс) — кнопка выключена, причина объяснена человеку | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::amount_belowMinimum_disablesButtonAndExplainsWhy`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::amount_aboveBalance_disablesButtonAndShowsBalance`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::allButton_fillsWholeAvailableBalance` | да — M2 |
| R3 | Ключ идемпотентности: неоднозначный отказ (`provider_unclear`) переживает повтор С ТЕМ ЖЕ ключом; явный отказ — со СВЕЖИМ | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::ambiguousFailure_providerUnclear_keepsSameIdempotencyKeyOnRetry`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::definiteFailure_min_usesFreshIdempotencyKeyOnRetry` | да — M3 |
| R4 | Двойное нажатие «Да, вывести» не отправляет второй вывод (кнопки видимо выключены + состояние `busy`, от которого зависят обе защиты) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` | да — M4, M5 |
| R5 | Экран «Кошелёк»: честная ошибка баланса (не «0 ₽») / честная ошибка истории (не «Пока операций нет» при чужом сбое) / устаревшие-данные-не-ошибка (строго по признаку «было раньше») / честный обрыв списка / честная заглушка выплат | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::emptyLedger_showsFriendlyEmptyState`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::bothFail_showsFullScreenErrorWithWorkingRetry`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::balanceFailsAlone_ledgerSucceeds_showsHonestBalanceErrorNotStaleOrZero`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerFailsAlone_balanceSucceeds_showsHonestLedgerErrorNotFalseEmpty`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::dataAlreadyShown_refreshFails_showsStaleStripNotError`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerAtLimit_showsHonestCutoffNotice`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::payoutsDisabled_showsHonestSoonCard_noFakeButtons`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::payoutsEnabled_noCardYet_offersToAddCard` | да — M26, M27 |
| R6 | «Протухло» (`stale`) — ТОЛЬКО если ДО этого запроса уже было что показать именно по этой части, не на первом открытии | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::balanceFailsAlone_ledgerSucceeds_showsHonestBalanceErrorNotStaleOrZero`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerFailsAlone_balanceSucceeds_showsHonestLedgerErrorNotFalseEmpty` | да — то же M26/M27 (один код, одна причина) |
| R7 | Знак в истории: приход — «+» и зелёный; списание — приглушённый типографский минус (не ASCII) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerRow_signsIncomeWithPlus_debitWithTypographicMinus` | да — M28 |
| R8 (ВНЕ ЗОНЫ леада — исполнено) | Вывод («Всё», границы, подпись под балансом) считается от ДОСТУПНОГО остатка (`payableKop`), не от сырого баланса; при долге — честное объяснение «X доступно, Y уходит на долг» | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::driverWithDebt_payoutBoundsComeFromPayableNotRawBalance`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::noDebt_payableEqualsBalance_noDebtBannerShown`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::balanceCard_withDebt_explainsPayableVsOwed_notFullBalanceCaption`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::amount_aboveMaximum_disablesButtonAndExplainsWhy` | да — M29, M30, M31, M32 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E2 | Кнопка «Отмена» и «Да, вывести» в диалоге подтверждения во время отправки ПРОДОЛЖАЛИ выглядеть и объявляться экранным диктором как обычные нажимаемые кнопки — защита от второго клика была только ВНУТРИ обработчика. | Открыть диалог вывода, нажать «Да, вывести» — кнопки остаются `enabled` | Добавлен `enabled = !busy` обеим кнопкам диалога | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` — до правки `assertIsNotEnabled()` падал, после — проходит |
| E11 (P1, ревью Opus) | При сбое `/wallet/balance` карточка молча показывала `kopToRub(0)` — «Баланс кошелька 0 ₽». Выглядит как «деньги пропали», хотя на деле просто сеть подвела. Если история при этом пришла — внизу список операций, а наверху выдуманный ноль; если статус выплат пришёл — `PayoutCard` снизу писал «на балансе 600 ₽», т.е. на одном экране ДВЕ разные суммы. | Баланс отвечает 500, история — 200 | `balanceFailed = balRes.isFailure && balance == null`; при `true` карточка показывает «Баланс не узнали» + «Повторить» вместо суммы | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::bothFail_showsFullScreenErrorWithWorkingRetry` (дополнен), `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::balanceFailsAlone_ledgerSucceeds_showsHonestBalanceErrorNotStaleOrZero` (новый) — до правки оба показали бы «0 ₽», после — честная ошибка |
| E12 (P2, ревью Opus) | `stale` вычислялся ПОСЛЕ того, как частичный ответ уже записан — при первом открытии (баланс пришёл, история нет) получалось `error=false, stale=true`, и история вместо честной ошибки показывала «Пока операций нет», хотя на деле просто неизвестно, есть операции или нет. | Баланс отвечает 200, история — 500, оба — ПЕРВЫЙ запрос за сессию | `ledgerFailed`/`balanceFailed` — отдельные флаги; `stale` считается от `hadBalance`/`hadLedger`, снятых ДО запроса | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerFailsAlone_balanceSucceeds_showsHonestLedgerErrorNotFalseEmpty` (новый) — до правки «Пока операций нет», после — «Что-то пошло не так» |
| E13 (ВНЕ ЗОНЫ леада, раздел 3 отчёта — исполнено) | Вывод («Всё», границы, подпись «Доступно к выводу через СБП») считался от СЫРОГО `balanceKop`, хотя сервер (`wallet.py`) уже отдаёт `payable_kop`/`owed_kop` — сколько реально свободно за вычетом долга платформе. Водитель с долгом видел рабочую кнопку «Вывести 600 ₽» на деньги, которые ему не принадлежат, и получал отказ ТОЛЬКО ПОСЛЕ подтверждения. | Баланс 600 ₽, из них 400 ₽ — долг (`payable_kop: 20000`) | `WalletBalanceDto`/`PayoutStatusDto` (ApiClient.kt, зона расширена ведущим) получили `payableKop`/`owedKop`; `PayoutCard`/`WalletBalanceCard` считают от них `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::driverWithDebt_payoutBoundsComeFromPayableNotRawBalance` — до правки «Всё» подставило бы 600 и пропустило бы 300 как валидные; после — 200 и честный отказ на 300 |
| E18 (P3, ревью Opus, исполнено) | Разряды в `fmtRub` разделялись ОБЫЧНЫМ пробелом — местом переноса строки. На крупном системном шрифте «1 234 ₽» (34sp в кошельке/заработке) мог перенестись между разрядами на две строки и на миг читаться как два разных числа. | Крупный шрифт в настройках Android + любая сумма ≥1000 ₽ (не проверялось на эмуляторе — нет доступа) | `.replace(',', ' ')` → `.replace(',', ' ')` (узкий неразрывный пробел) | `WalletMoneyFormatTest::fmtRub_groupsThousandsWithSpace`/`fmtRub_negative_minusStaysGluedToDigits` — проверяют точный символ U+202F; M1 теперь ломает ИМЕННО его |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `fmtRub` перестаёт разбивать разряды пробелом | `WalletMoneyFormatTest::fmtRub_groupsThousandsWithSpace` и др. | KILLED |
| M2 | `canPayout` игнорирует `amountError` | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::amount_belowMinimum_disablesButtonAndExplainsWhy` | KILLED |
| M3 | Условие провайдера инвертировано | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::ambiguousFailure_providerUnclear_keepsSameIdempotencyKeyOnRetry` | KILLED |
| M4 | Снят `enabled = !busy` у кнопки «Да, вывести» | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` | KILLED |
| M5 | `busy` никогда не ставится в `true` при подтверждении | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` | KILLED |
| M26 | Честная ошибка баланса отключена — снова «0 ₽» | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::bothFail_showsFullScreenErrorWithWorkingRetry` | KILLED |
| M27 | Частичный сбой истории больше не считается ошибкой | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerFailsAlone_balanceSucceeds_showsHonestLedgerErrorNotFalseEmpty` | KILLED |
| M28 | Знак прихода («+») пропадает | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerRow_signsIncomeWithPlus_debitWithTypographicMinus` | KILLED |
| M29 | Ветка «максимум за раз» никогда не срабатывает | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::amount_aboveMaximum_disablesButtonAndExplainsWhy` | KILLED |
| M30 | «Всё» снова считает от сырого баланса | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::driverWithDebt_payoutBoundsComeFromPayableNotRawBalance` | KILLED |
| M31 | Граница «выше доступного» снова проверяется по сырому балансу | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletPayoutFlowTest.kt::driverWithDebt_payoutBoundsComeFromPayableNotRawBalance` | KILLED |
| M32 | Подпись под балансом снова обещает полный вывод при долге | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::balanceCard_withDebt_explainsPayableVsOwed_notFullBalanceCaption` | KILLED |

## Остаток и ограничения

Пул-ту-рефреш (настоящий жест пальцем) не эмулировался — состояние «жду обновления» проверено
через ровно тот же код (`load()`), который вызывает и кнопка «Повторить». Реальная ЮKassa/
SMS/эмулятор — не в доступе на этой машине.

**P3, не блокирует (из отчёта ведущему):**
- «Отмена» в `PayoutCardDialog` — без `enabled=!busy` (сама отправка карты защищена только
  внутренним guard'ом, как было раньше у кнопок вывода).
- Явные цвета текста (`CanonGreen2`/`CanonMuted`) на кнопках диалога перекрывают визуальное
  затемнение disabled-состояния — на вид кнопка не меняется, хотя TalkBack теперь объявляет
  её правильно (см. правку E2/M4 — это была её единственная заявленная цель).
- Ключ идемпотентности — в `remember`, не `rememberSaveable`: пересоздание Activity посреди
  отправки (смена темы/языка системы/нехватка памяти — поворот не страшен, ориентация
  портретная) теряет ключ, повтор уйдёт с НОВЫМ ключом.
- Параллельные `load()` (жест + смена периода у водителя/курьера) не отменяют друг друга.
- Разряд-пробел — обычный, не неразрывный: теоретическая угроза переноса суммы на крупном
  шрифте.
