# Карточка: `android/app/src/main/java/com/yuldash/app/WalletScreen.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: Opus 5.5 (ревью денег)

## Назначение

«Кошелёк» водителя: баланс + история операций (ledger) + вывод на карту (Модель Б, за
флагом `enabled` с сервера). Деньги показываются в копейках→рублях без потери копеек
(`fmtRub`/`kopToRub`); вывод защищён идемпотентностью и двойным подтверждением.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `WalletScreen` / `load()` | 66–190 | Грузит баланс+историю+статус выплат, рисует состояния | `error` только если ОБА (баланс И история) упали; `stale` — хоть один упал, но данные уже были; payout грузится и падает НЕЗАВИСИМО (не роняет весь экран) | ок |
| `WalletBalanceCard` | 196–246 | Крупная сумма баланса + подпись про доступность вывода | `kopToRub` — копейки не теряются; подпись зависит от `payoutEnabled` (null/true/false — три разных честных текста) | ок |
| `WalletLedgerRow` | 249–283 | Строка истории: направление, сумма со знаком и цветом | `amountKop>=0` → зелёный «приход», иначе приглушённый; `note.ifBlank{fallback}` — пустую подпись сервера не показываем как есть | ок |
| `PayoutSoonCard` | 288–317 | Честная заглушка, пока `enabled=false` | Без кнопок-обманок | ок |
| `PayoutCard` | 324–515 | Живой вывод: карта, сумма, границы с сервера, двойное подтверждение | См. R2–R4 ниже | ок, R2–R4 |
| `PayoutCardDialog` | 521–589 | Диалог «Карта для выплат» | Полный номер НЕ логируется и НЕ уходит на сервер — только последние 4 цифры, посчитанные локально (`digits.takeLast(4)`) | ок, приватность |
| `fmtRub` | 611 | Целые рубли с разрядом-пробелом | Единственный денежный форматтер приложения (рубли); `kopToRub` (CouponsScreen.kt, вне зоны) — для копеек | ок, R1 |

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
| R5 | Экран «Кошелёк»: пусто / ошибка+повтор / устаревшие-данные-не-ошибка / честный обрыв списка / честная заглушка выплат | `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::emptyLedger_showsFriendlyEmptyState`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::bothFail_showsFullScreenErrorWithWorkingRetry`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::dataAlreadyShown_refreshFails_showsStaleStripNotError`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::ledgerAtLimit_showsHonestCutoffNotice`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::payoutsDisabled_showsHonestSoonCard_noFakeButtons`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/WalletScreenStatesTest.kt::payoutsEnabled_noCardYet_offersToAddCard` | косвенно — подтверждает состояния, отдельной денежной мутации не заводил (не про суммы, про UI-состояния) |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E2 | Кнопка «Отмена» и «Да, вывести» в диалоге подтверждения во время отправки ПРОДОЛЖАЛИ выглядеть и объявляться экранным диктором как обычные нажимаемые кнопки — защита от второго клика была только ВНУТРИ обработчика (`if (busy) return`), а не видна человеку/вспомогательным технологиям. Функционально повторный клик был безопасен (второй запрос не уходил), но это везение программиста, а не гарантия интерфейса — то же самое упущение, которое уже один раз стоило переделки в этом же файле (идемпотентность, волна 219 в соседнем комментарии). | Открыть диалог вывода, нажать «Да, вывести» — кнопки диалога остаются `enabled` (видно в дереве семантики: `assertIsNotEnabled()` падал) | Добавлен `enabled = !busy` обеим кнопкам диалога (были только внутренние guard'ы) | `WalletPayoutFlowTest::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` — до правки `assertIsNotEnabled()` на `payout_confirm` падал (кнопка оставалась `enabled`), после — проходит; мутация M4 (снять `enabled=!busy`) подтверждает, что тест действительно это проверяет |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `fmtRub` перестаёт разбивать разряды пробелом | `WalletMoneyFormatTest::fmtRub_groupsThousandsWithSpace` и др. | KILLED: тест упал |
| M2 | `canPayout` игнорирует `amountError` (`== null` → `true`) | `WalletPayoutFlowTest::amount_belowMinimum_disablesButtonAndExplainsWhy` (и ещё 1 тест ловит ту же мутацию) | KILLED: тест упал |
| M3 | Условие провайдера инвертировано (`!=` → `==` "provider_unclear") | `WalletPayoutFlowTest::ambiguousFailure_providerUnclear_keepsSameIdempotencyKeyOnRetry` | KILLED: тест упал |
| M4 | Снят `enabled = !busy` у кнопки «Да, вывести» | `WalletPayoutFlowTest::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` | KILLED: тест упал |
| M5 | `busy` никогда не ставится в `true` при подтверждении — видимая и внутренняя защита от повтора обесточены разом | `WalletPayoutFlowTest::doubleConfirm_whileFirstRequestInFlight_sendsExactlyOnePayout` | KILLED: тест упал |

## Остаток и ограничения

**ВНЕ ЗОНЫ (см. отчёт ведущему, раздел «ВНЕ ЗОНЫ» — подробности и точная правка там):**
сервер (`backend/app/routers/wallet.py`, `GET /wallet/balance` и `GET /wallet/payout/status`)
уже считает и отдаёт `payable_kop`/`owed_kop`/`reserved_kop` — сколько из баланса реально
можно вывести за вычетом долга платформе по комиссии (с объяснением — «Доступно к выводу
X ₽: Y ₽ на балансе зарезервировано под неоплаченную комиссию»). Но `WalletBalanceDto` и
`PayoutStatusDto` в `ApiClient.kt` (не в зоне этого листа) эти поля НЕ читают, поэтому
`PayoutCard` в этом файле проверяет границы и считает «Всё» от СЫРОГО баланса, а не от
доступного остатка. У водителя с долгом по комиссии это выглядит как рабочая кнопка
«Вывести 600 ₽», которая после confirm вернёт отказ с кодом `debt` — человеческий текст
ошибки от сервера пользователь всё же увидит (Toast), но это происходит ПОСЛЕ лишнего шага
подтверждения, а не ДО него, как могло бы, покажи экран остаток честно сразу. Без правки
`ApiClient.kt` исправить в этом файле нельзя (там живут обе DTO и их парсинг) — поэтому
только задокументировано, не исправлено.

Пул-ту-рефреш (настоящий жест пальцем) не эмулировался — состояние "жду обновления" же
проверено через ровно тот же код (`load()`), который вызывает и кнопка «Повторить» на
полосках ошибок. Реальная ЮKassa/SMS/эмулятор — не в доступе на этой машине.
