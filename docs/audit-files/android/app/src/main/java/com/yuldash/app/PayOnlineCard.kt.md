# Карточка: `android/app/src/main/java/com/yuldash/app/PayOnlineCard.kt`

- Статус: verified
- Лист: leaf-1.4
- Проверял: Sonnet 5 (leaf-1.4); принимал: ожидает ревью

## Назначение

Переиспользуемая карточка «Оплатить онлайн» завершённой поездки картой/СБП через ЮKassa, за
флагом провайдера. Используется и для брони (`payBooking`), и для быстрого заказа
(`payInstantOrder`) — вызывающий передаёт готовую suspend-функцию `pay`. Видимость карточки
решает ТОЛЬКО ответ `/health` (`OnlinePayGate`): пока его нет — карточки нет вовсе; если явно
«выключено» — нет до конца сессии. 503 именно на ПОПЫТКЕ оплаты — другое дело (временный сбой
провайдера, не «выключено»): карточка остаётся, повтор работает.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `OnlinePayGate` | 61–72 | Синглтон-память на сессию: `unavailable` (health явно сказал «выключено»), `checked` (ответ ПРИШЁЛ, успешный или нет), `asked` (уже спрашивали) | `checked` — НОВОЕ поле (правка по ревью): отделяет «ещё не узнали» от «выключено», раньше оба выглядели как `unavailable=false` | ок (после правки) |
| `PayOnlineCard` (гейт) | 84–99 | Перед показом спрашивает `paymentsOnlineEnabled()` ОДИН раз за сессию; рисует контент, только когда `checked=true` и `unavailable=false` | П2 (ревью Opus) ИСПРАВЛЕНО: раньше `unavailable` стартовал `false`, и карточка с рабочей кнопкой показывалась ДО ответа сервера — на медленной сети/выключенном провайдере пассажир мог нажать и получить 503. Сетевой сбой самого health-check НЕ считается «выключено навсегда» (`.onFailure { unavailable = false }`) | ок (после правки), R2 |
| `startPay()` | ~119–151 | Шлёт `pay(method)`, разбирает исход | `busy` — защита от повтора (R3); `res.isPaid` → сразу «Оплачено»; есть `confirmationUrl` → открыть браузер + «Ждём подтверждения»; 503 → Toast «скоро», карточка ОСТАЁТСЯ (П2 ИСПРАВЛЕНО — раньше ставила `unavailable=true` навсегда); прочие ошибки → Toast с текстом сервера, карточка остаётся | ок (после правки), R2, R3, R4 |
| `checkPayment()` | ~153–166 | «Проверить оплату» — поллинг статуса после возврата из браузера | `paymentId==null` → откат в Idle (не виснет); `succeeded` → «Оплачено»; иначе — Toast «ещё не подтверждена», остаётся Waiting | ок |
| UI Idle-стадия | ~176–215 | Сумма (ОДНА и та же строка на чеке и на кнопке), выбор карта/СБП, кнопка | `amountKop` — КОПЕЙКИ (документировано в kdoc, исторический баг — рубли через `kop/100` — упомянут явно) | ок, R1 |

## Связи

- `ApiClient.paymentsOnlineEnabled()` → `GET /health` (вне зоны, не трогал).
- `ApiClient.getPaymentStatus(id)` → `GET /payments/{id}/status` (вне зоны).
- `pay: suspend (String) -> Result<PayTripResultDto>` — инжектируется вызывающим
  (`TripReceiptScreen`/`RideshareCompletedScreen`, вне зоны): `ApiClient.payBooking`/
  `payInstantOrder`.
- `kopToRub` (CouponsScreen.kt, вне зоны, только чтение) — общий форматтер, используется и в
  шапке, и на кнопке.
- Сервер (`backend/app/routers/payments.py`, вне зоны, только чтение): временный сбой ЮKassa
  ТОЖЕ отвечает 503 с текстом «Попробуй ещё раз» и нарочно сохраняет строку платежа для
  безопасного повтора — ровно поэтому клиент больше не вправе считать любой 503 равным
  «фичи нет».

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Сумма на кнопке и в шапке — ОДНА строка `kopToRub(amountKop)`, копейки не теряются | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::headerAndButton_showExactlySameAmount` | да — M9 |
| R2 | Карточка видна ТОЛЬКО после ответа `/health`; явное «выключено» — нет до конца сессии; временный 503 на оплате НЕ прячет навсегда, повтор работает | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::cardStaysHidden_untilHealthCheckResponds_thenAppearsByItself`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::healthCheckNetworkFailure_stillShowsCard_notPermanentlyBlank`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::healthSaysOff_cardNeverAppears_notEvenForAMoment`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::paySays503_doesNotHideCardForever_retryAfterTransientOutageSucceeds`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::otherFailure_doesNotHideCard_canRetry` | да — M10, M39, M40 |
| R3 | Двойное нажатие «Оплатить» не шлёт второй платёж (кнопка выключена, пока первый ответ летит) | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::doubleTap_whileFirstRequestInFlight_doesNotSendASecondPayment` | да — M11 |
| R4 | Переход стадий: `confirmationUrl` → «Ждём подтверждения» (не сразу «Оплачено»); `paid`/`already_paid`/`succeeded` → сразу «Оплачено»; «Проверить оплату» переводит Waiting→Paid по `succeeded` | `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::successWithConfirmationUrl_goesToWaiting_notStraightToPaid`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::successAlreadyPaid_goesStraightToPaid_withoutWaitingStage`, `android/app/src/test/java/com/yuldash/app/walk/l1_4/PayOnlineCardTest.kt::checkPayment_succeeded_movesFromWaitingToPaid` | косвенно через R1–R3; отдельной поломки на R4 не заводил (переход состояний UI, не денежный риск) |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E4 (P2, ревью Opus) | Карточка появлялась С ПЕРВОГО КАДРА, ДО того как сервер вообще ответил, включена ли онлайн-оплата: `unavailable` по умолчанию `false`, вопрос уходил из `LaunchedEffect` уже ПОСЛЕ первой отрисовки. На выключенном провайдере пассажир на долю секунды видел живую кнопку «Оплатить N ₽», мог успеть нажать и получить 503 — ту самую «кнопку-обманку», от которой карточка обязана защищать. | Health отвечает `{"payments":"off"}` с задержкой — карточка успевала отрисоваться до ответа | Добавлено поле `OnlinePayGate.checked`; контент рисуется только при `checked=true` | `cardStaysHidden_untilHealthCheckResponds_thenAppearsByItself` — карточки нет, пока latch на `/health` держит ответ; `healthSaysOff_cardNeverAppears_notEvenForAMoment` — ответ `off` приходит сразу, кнопки нет ни на миг |
| E5 (P2, ревью Opus) | Любой 503 именно на ПОПЫТКЕ оплаты считался «оплата выключена навсегда»: `OnlinePayGate.unavailable=true`. Но сервер отвечает 503 ТАКЖЕ при временном сбое ЮKassa (`payments.py`, «Попробуй ещё раз», платёж сохранён для безопасного повтора). Пассажир терял кнопку оплаты навсегда из-за одного временного сбоя провайдера. | `pay()` возвращает `ApiException(503, ...)` на попытке оплаты при РАБОЧЕМ health-check | Строка `OnlinePayGate.unavailable = true` убрана из обработчика 503 в `startPay()` — остаётся только Toast, карточка видна, кнопка снова нажимаема | `paySays503_doesNotHideCardForever_retryAfterTransientOutageSucceeds` — до правки кнопка исчезала бы после первого 503 (тест `serverSays503_hidesCardForTheRestOfTheSession` это закреплял); после — кнопка остаётся, повторное нажатие после исчезновения причины 503 проходит успешно |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | Кнопка снова считает `amountKop/100` вместо `kopToRub` (возврат старого бага) | `headerAndButton_showExactlySameAmount` | KILLED |
| M10 | Возврат старого бага: временный 503 на попытке оплаты снова прячет карточку навсегда | `paySays503_doesNotHideCardForever_retryAfterTransientOutageSucceeds` | KILLED |
| M11 | `busy` никогда не ставится в `true` в `startPay()` — обе защиты от двойной оплаты обесточены разом | `doubleTap_whileFirstRequestInFlight_doesNotSendASecondPayment` | KILLED |
| M39 | Карточка снова рисуется ДО ответа health-check (`checked` не проверяется) | `cardStaysHidden_untilHealthCheckResponds_thenAppearsByItself` | KILLED |
| M40 | health-check, явно ответивший «выключено», перестаёт прятать карточку | `healthSaysOff_cardNeverAppears_notEvenForAMoment` | KILLED |

## Остаток и ограничения

Настоящий браузер/ЮKassa (`startActivity(ACTION_VIEW, confirmationUrl)`) не открывается в
unit-тестах — проверено только то, что приложение ПЫТАЕТСЯ его открыть (`runCatching`, не
падает, если браузера нет) и корректно переходит в «Ждём подтверждения» независимо от этого.
Реальная оплата картой/СБП — вне доступного инструментария (нет эмулятора с настоящим
провайдером на этой машине).

**P3, не блокирует (из отчёта ведущему):** поздний ответ `/health` «включено» после закрытия
карточки ПРЕЖНИМ 503-навсегда мог бы снова её открыть — после удаления строки
`unavailable=true` из обработчика 503 эта конкретная гонка больше не воспроизводится (нечего
«переоткрывать», раз 503-на-оплате больше ничего не закрывает), но желательно: серверу стоит
различать машинным кодом 503 «выключено» и 503 «временно» (см. ВНЕ ЗОНЫ, раздел (в) отчёта
ведущему).
