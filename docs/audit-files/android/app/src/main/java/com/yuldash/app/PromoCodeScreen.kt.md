# Карточка: `android/app/src/main/java/com/yuldash/app/PromoCodeScreen.kt`

- Статус: verified
- Лист: leaf-1.5
- Проверял: Sonnet 5 (leaf-1.5); принимал: Opus 5.5 (REQUEST_CHANGES на первом проходе — см. «Остаток»)

## Назначение

Один промокод на аккаунт (вход: Профиль → «Промокод»). Если код уже применён (`getMyPromo`) —
постоянный экран «Твой промокод CODE» с тем, что он дал (`welcome` — приветствие, `boost` — N
бесплатных поднятий, `taxi_ride` — скидка на ОДНУ поездку такси, которая срабатывает сама в цене
заказа и оплачивается Юлдашем из комиссии, а не водителем). Если кода ещё нет — поле ввода
(заглавные, моноширинно, не длиннее серверного предела) + «Применить» (`applyPromo`). Успех —
анимированная галочка + серверное сообщение на языке интерфейса. Код по желанию: отказ ничего
не блокирует (честный дисклеймер виден на всех состояниях, кроме экрана успеха).

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `normalizePromoCodeInput` | 77–78 | Ввод → что уйдёт на сервер: заглавные, без пробелов (в т.ч. внутренних), предел 32 символа | ок | ок |
| `PromoCodeScreen` | 81–136 | Загрузка `getMyPromo`, ветвление загрузка/ошибка/успех-применения/уже-применён/ввод | **R4** (ошибка первой загрузки) | ок |
| `PromoInputCard` | 141–219 | Поле ввода + «Применить» | **R1** (двойной тап) и **R9 — была ошибка** (409 после таймаута), см. ниже | ок (R9 исправлено) |
| `PromoSuccessCard` | 224–241 | Экран успеха: галочка, серверное сообщение, что дал код, скидка на такси | — | ок |
| `PromoTaxiDiscountNote` | 265–282 | Поясняет скидку на такси на экране успеха | **R3** (money-правило: скрывать блок при `discountKop <= 0`) | ок |
| `PromoAppliedCard` | 305–322 | Постоянный экран «уже применён»: код, что дал, судьба скидки | — | ок |
| `PromoPerkChip` | 359–385 | Подпись выгоды одной строкой | **R10 — была ошибка**: показывал неподтверждённую сумму для `taxi_ride`+нулевая скидка. Исправлено | ок (исправлено) |
| `PromoDiscountStatus` | 391–440 | Три честных состояния скидки: ждёт / сработала / не действует | **R3b** (тот же money-паттерн, что в `PromoTaxiDiscountNote`) | ок |
| `PromoHonestNote` | 444–453 | Дисклеймер «код по желанию» | — | ок |

## Связи

- Сеть (`data/ApiClient.kt`, вне зоны — только читал): `getMyPromo`, `applyPromo` (сам ещё раз
  делает `trim().uppercase()` перед отправкой — защита дублируется, не противоречит).
- Сервер: `backend/app/routers/promo.py` (эндпоинт `/promo/apply`, модель запроса `ApplyIn`),
  `PromoCode`/`ApplyIn` в `backend/app/models.py`/`backend/app/routers/promo.py`
  (`max_length=32` — источник предела длины в `normalizePromoCodeInput`). `/promo/apply` отвечает
  409 в ТРЁХ разных ситуациях (уже активировал любой код; этот код уже брали с этого номера; свой
  же код) — все три с `herr()` без машиночитаемого кода причины, только `detail.ru/ba`; различать
  их на клиенте можно только текстом, который фиксировать на стороне Android ненадёжно. R9 поэтому
  реагирует на сам факт 409 у `/promo/apply` и сверяет `getMyPromo()` реальное состояние, а не
  разбирает текст причины. Серверная логика проверена листом 1.3 — переаудировал только то, как
  Android с ней разговаривает.
- `kopToRub`, `couponLastAcceptedDay`/`ufaIsoDate` — из `CouponsScreen.kt` (тот же модуль, `internal`).
- Скидка `taxi_ride` реально применяется на экране заказа такси (вне зоны этого листа) — здесь
  только объясняется пассажиру и показывается её судьба.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | «Применить» не шлёт второй запрос, пока первый не завершился (двойной тап) | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeScreenWalkTest.kt::applyButtonSendsExactlyOneRequestEvenWhenTappedTwiceInARow` | да — M9 |
| R2 | Ввод кода: заглавные, без пробелов (в т.ч. внутренних), предел длины = серверному `max_length=32`, башкирские буквы не теряются | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeInputNormalizationTest.kt::tooLongInputIsCappedAtServerLimit` (и соседние методы) | да — M10 |
| R3 | Скидка на такси на экране успеха показывается верной суммой и ПОЛНОСТЬЮ прячется при `discountKop <= 0` (не «0 ₽») | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeScreenWalkTest.kt::successScreenShowsDiscountNoteWithCorrectRubAmount` (и `::successScreenHidesDiscountNoteWhenThereIsNoDiscount`) | да — M11 |
| R3b | Та же защита на постоянном экране «уже применён» (`PromoDiscountStatus`) | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeScreenWalkTest.kt::appliedPermanentCardShowsWaitingDiscountWithCorrectAmount` (и `::appliedPermanentCardHidesDiscountStatusWhenThereIsNoDiscount`) | да — M12 |
| R4 | Ошибка первой загрузки «моего промокода» — понятный текст + рабочее «Повторить» | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeScreenWalkTest.kt::initialLoadErrorShowsRetryThenRecovers` | да — M13 |
| R9 | 409 на «Применить» сверяет реальное состояние (`getMyPromo`) и ведёт на «применён», а не оставляет на форме с отказом | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeScreenWalkTest.kt::applyTimeoutFollowedBy409RecoversToAlreadyAppliedState` | да — M14 |
| R10 | Значок выгоды показывает ТОЛЬКО то, что сервер подтвердил суммой в этом же ответе — ни «0 ₽», ни устаревшее число | `android/app/src/test/java/com/yuldash/app/walk/l1_5/PromoCodeScreenWalkTest.kt::successScreenDoesNotClaimAnUnconfirmedTaxiDiscountAmount` | да — M15 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| B1 | Поле ввода промокода не имело предела длины. Вставленный длинный текст уходил на сервер целиком — гарантированный отказ по длине (сервер хранит код максимум 32 символа, `ApplyIn`/`PromoCode.code`) вместо того, чтобы поле просто не дало напечатать лишнее | `normalizePromoCodeInput("A".repeat(50))` — возвращал 50 символов вместо 32 | Добавлен `.take(32)` — предел тот же, что `PromoCode.code max_length=32` на сервере | `PromoCodeInputNormalizationTest::tooLongInputIsCappedAtServerLimit`: 50 → 32. Подтверждено мутацией M10 |
| B2 | Если первое нажатие «Применить» ушло в таймаут, сервер мог код всё-таки принять, а ответ — потеряться по дороге. Повтор утыкался в 409 «Ты уже активировал промокод», и человек оставался на пустой форме ввода, не понимая, что код у него на самом деле уже есть | `/promo/apply` отвечает 409 на первой попытке, `/promo/mine` при этом знает о реально применённом коде — старый код просто показывал текст отказа на той же форме | При 409 от `/promo/apply` экран сам перезапрашивает `getMyPromo()`; если промокод нашёлся — сразу показывает постоянный экран «применён», а не отказ | `applyTimeoutFollowedBy409RecoversToAlreadyAppliedState`: до фикса — текст отказа на форме → после — «Промокод активен» с верной суммой. Подтверждено мутацией M14 |
| B3 | Значок выгоды (`PromoPerkChip`) для `kind="taxi_ride"` с `discountKop=0` (сервер разрешает `perk_value=0`, либо общий потолок `promo_ride_max_discount_rub` снижен до 0) всё равно включал ветку «скидка на такси» и показывал `"$perkValue ₽"` — число, которого ЭТОТ ответ сервера не подтверждал: либо честные «0 ₽ скидки на такси», либо устаревшее значение вроде «300 ₽», хотя сервер в этом же ответе пишет «добро пожаловать» | `PromoPerkChip(kind="taxi_ride", perkValue=300, discountKop=0)` — старый код показывал «300 ₽ скидки на такси» | `isDiscount` теперь зависит ТОЛЬКО от `discountKop > 0` (убран `|| kind == "taxi_ride"`) — нулевая/неподтверждённая скидка уходит в честный «Приветственный бонус» | `successScreenDoesNotClaimAnUnconfirmedTaxiDiscountAmount`: до фикса видно «300 ₽ скидки на такси» → после — нет ни «скидки на такси», ни «₽» на экране. Подтверждено мутацией M15 |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | `if (busy \|\| code.isBlank()) return@AppButton` → `if (false)` (снята защита от двойного тапа) | `PromoCodeScreenWalkTest` | KILLED: тест упал |
| M10 | `.take(32)` → `.take(320)` (снят предел длины кода) | `PromoCodeInputNormalizationTest` | KILLED: тест упал |
| M11 | `if (discountKop <= 0) return` → `< 0` (блок скидки не прячется при нулевой скидке) | `PromoCodeScreenWalkTest` | KILLED: тест упал |
| M12 | То же, что M11, на постоянном экране (`if (m.discountKop <= 0) return` → `< 0`) | `PromoCodeScreenWalkTest` | KILLED: тест упал |
| M13 | Ошибка первой загрузки проглатывается молча (`.onFailure { }`) | `PromoCodeScreenWalkTest` | KILLED: тест упал |
| M14 | Распознавание 409 отключено (`if (apiEx?.status == 409)` → `if (false)`) | `PromoCodeScreenWalkTest` | KILLED: тест упал |
| M15 | `isDiscount` снова зависит от `kind == "taxi_ride"` (возврат старого OR-условия) | `PromoCodeScreenWalkTest` | KILLED: тест упал |

Полный лог прогона — см. отчёт ведущему (раздел 5 BRIEF).

## Остаток и ограничения

- Независимое ревью (Opus) вернуло лист на первом проходе с двумя пунктами по этому файлу (R9,
  R10 выше) плюс замечание про `PromoApplyIn`/`ApplyIn` в докстринге (исправлено — класс в
  `backend/app/routers/promo.py` называется `ApplyIn`, без префикса `Promo`).
- R9 реагирует на ЛЮБОЙ 409 от `/promo/apply`, не разбирая причину текстом (сервер не даёт
  машиночитаемого кода ошибки в `herr()`, см. «Связи») — если `getMyPromo()` после 409 вернёт
  `null` (409 был по ДРУГОЙ причине, например «этот код уже брали с этого номера» без личного
  промокода), экран просто покажет исходный текст отказа на форме — не хуже, чем было, не лучше.
  Это сознательный компромисс при отсутствии машиночитаемой причины на сервере.
- Башкирских черновиков в этом файле не добавлял — новых пользовательских строк не было (только
  логика); единственный новый черновик листа — в `CouponsScreen.kt::reportErrDefault`, см. его
  карточку и отчёт.
- Эмулятор не запускал (по правилам листа — дорого, достаточно Robolectric).
