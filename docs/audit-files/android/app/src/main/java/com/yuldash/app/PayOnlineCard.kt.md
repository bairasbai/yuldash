# Карточка: `android/app/src/main/java/com/yuldash/app/PayOnlineCard.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: Opus 5.5 (ревью денег)

## Назначение

Переиспользуемая карточка «Оплатить онлайн» завершённой поездки картой/СБП через ЮKassa, за
флагом провайдера. Используется и для брони (`payBooking`), и для быстрого заказа
(`payInstantOrder`) — вызывающий передаёт готовую suspend-функцию `pay`. Сервер — источник
правды: провайдер выключен → 503 → карточка прячется НАВСЕГДА в рамках сессии
(`OnlinePayGate`), без кнопок-обманок.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `OnlinePayGate` | 60–65 | Синглтон-память на сессию: спрашивали ли сервер, выключена ли оплата | Переживает композиции конкретного экрана (объект, не `remember`) — тест это явно проверяет (R2) | ок |
| `PayOnlineCard` (гейт) | 83–89 | Перед показом кнопки спрашивает `paymentsOnlineEnabled()` ОДИН раз за сессию | `!asked` — не спрашивает повторно; `unavailable` → `return` (ничего не рисует) | ок |
| `startPay()` | 109–138 | Шлёт `pay(method)`, разбирает исход | `busy` — защита от повтора (R3); `res.isPaid` → сразу «Оплачено» (cash/already_paid/succeeded); есть `confirmationUrl` → открыть браузер + «Ждём подтверждения»; 503 → `OnlinePayGate.unavailable=true` НАВСЕГДА; прочие ошибки → Toast, карточка остаётся | ок, R2, R3, R4 |
| `checkPayment()` | 140–153 | «Проверить оплату» — поллинг статуса после возврата из браузера | `paymentId==null` → откат в Idle (не виснет); `succeeded` → «Оплачено»; иначе — Toast «ещё не подтверждена», остаётся Waiting | ок |
| UI Idle-стадия | 163–202 | Сумма (ОДНА и та же строка на чеке и на кнопке), выбор карта/СБП, кнопка | `amountKop` — КОПЕЙКИ (документировано в kdoc, исторический баг — рубли через `kop/100` — упомянут явно) | ок, R1 |

## Связи

- `ApiClient.paymentsOnlineEnabled()` → `GET /health` (вне зоны, не трогал).
- `ApiClient.getPaymentStatus(id)` → `GET /payments/{id}/status` (вне зоны).
- `pay: suspend (String) -> Result<PayTripResultDto>` — инжектируется вызывающим
  (`TripReceiptScreen`/`RideshareCompletedScreen`, вне зоны): `ApiClient.payBooking`/
  `payInstantOrder`.
- `kopToRub` (CouponsScreen.kt, вне зоны, только чтение) — общий форматтер, используется и в
  шапке, и на кнопке.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Сумма на кнопке и в шапке — ОДНА строка `kopToRub(amountKop)`, копейки не теряются | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::headerAndButton_showExactlySameAmount` | да — M9 |
| R2 | 503 от сервера прячет карточку навсегда в рамках сессии (переживает перерисовку с новой суммой) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::serverSays503_hidesCardForTheRestOfTheSession` | да — M10 |
| R3 | Двойное нажатие «Оплатить» не шлёт второй платёж (кнопка выключена, пока первый ответ летит) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::doubleTap_whileFirstRequestInFlight_doesNotSendASecondPayment` | да — M11 |
| R4 | Переход стадий: `confirmationUrl` → «Ждём подтверждения» (не сразу «Оплачено»); `paid`/`already_paid`/`succeeded` → сразу «Оплачено»; «Проверить оплату» переводит Waiting→Paid по `succeeded` | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::successWithConfirmationUrl_goesToWaiting_notStraightToPaid`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::successAlreadyPaid_goesStraightToPaid_withoutWaitingStage`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::checkPayment_succeeded_movesFromWaitingToPaid` | косвенно через R1–R3; отдельной поломки на R4 не заводил (не денежный риск, а переход состояний UI) |

## Найденные ошибки

Ошибок не найдено. Исторический баг «кнопка в рублях (`kop/100`) расходится с копеечным
чеком», упомянутый в kdoc файла, уже исправлен в текущем коде (обе надписи используют
`kopToRub`) — тест `headerAndButton_showExactlySameAmount` защищает именно это.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | Кнопка снова считает `amountKop/100` вместо `kopToRub` (возврат старого бага) | `headerAndButton_showExactlySameAmount` | KILLED: тест упал |
| M10 | 503 перестаёт ставить `OnlinePayGate.unavailable=true` | `serverSays503_hidesCardForTheRestOfTheSession` | KILLED: тест упал |
| M11 | `busy` никогда не ставится в `true` в `startPay()` — видимая и внутренняя защита от двойной оплаты обесточены разом | `doubleTap_whileFirstRequestInFlight_doesNotSendASecondPayment` | KILLED: тест упал |

## Остаток и ограничения

Настоящий браузер/ЮKassa (`startActivity(ACTION_VIEW, confirmationUrl)`) не открывается в
unit-тестах — проверено только то, что приложение ПЫТАЕТСЯ его открыть (`runCatching`, не
падает, если браузера нет) и корректно переходит в «Ждём подтверждения» независимо от этого.
Реальная оплата картой/СБП — вне доступного инструментария (нет эмулятора с настоящим
провайдером на этой машине).
