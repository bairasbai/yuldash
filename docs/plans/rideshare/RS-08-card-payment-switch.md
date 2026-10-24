# RS-08 — Выключатель оплаты картой (заготовка)

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | **да — деньги** (оплата картой), миграция (две таблицы и два поля), выкладка |
| Зависит от | RS-07 (таблица `driverpaysettings`, блок «Как принимаю оплату», `/driver/pay-settings`) |
| Двигает сценарии приёмки | — (заготовка; W1-E2E-07 «реальные платежи не выполняются» не нарушается) |
| Зона файлов | сервер: `config.py`, `models.py`, новый `card_switch.py`, `routers/payments.py`, `routers/drivers.py`, `routers/wallet.py`, `routers/bookings.py`, `schemas.py`, `services.py`, новая миграция, `tests/conftest.py`, новый `tests/test_rs08_card_switch.py` + 8 тестов оплаты (шаг 10); Android: новый `AdminCardPaymentsScreen.kt`, `MainActivity.kt` (`enum Screen`), `AppNavAdmin.kt`, `YuldashApp.kt`, `SecondaryScreens.kt` (`AdminCabinetScreen`), `ProfileScreen.kt`, `BookingTermsCard.kt` (из RS-07), `BookingActiveTripScreen.kt`, `RideshareCompletedScreen.kt`, `Domain.kt`, `data/ApiClient.kt`, `data/BookingDtos.kt`, тесты; сайт: новый `screens/AdminCardPaymentsScreen.tsx`, `App.tsx`, `AdminCabinetScreen.tsx`, `DriverCabinetScreen.tsx`, `components/PayTripCard.tsx`, `TripReceiptScreen.tsx`, `components/BookingTerms.tsx`, `api/admin.ts`, `api/rides.ts`, `tests/`; мозг: `docs/architecture.md`, `docs/decisions.md`, `docs/tasks.md` |
| Источник | исследование: `master/part-001.md` п. 20 «Онлайн-оплата», п. 21 «Выплата водителю»; решения 24.10 (Q5, Q8 в, Q9) |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
**Сейчас** за попутку платят только водителю напрямую — наличными или переводом (решение Q5). Картой через
приложение нельзя: нет договора с банком.

**Станет (только заготовка).** В админке у Александра — строка «Оплата картой». Пока договора с банком нет, она
серая с подписью «нужен договор с банком», и включить её не даст сам сервер. Когда договор будет, ключи банка
стоят на сервере и выплаты водителям работают — Александр включит её одним касанием. Тогда водитель из Сибая
в кабинете увидит «Принимаю оплату картой», а пассажир — кнопку «Картой» после поездки только у таких водителей.
Каждое включение и выключение записано: кто и когда. Настоящие деньги, возвраты и выплаты — отдельный проект с банком.

## 2. Что уже есть в коде
- `backend/app/config.py:9` `PAYMENTS_PROVIDERS`; `:147` `payments_provider = "mock"`; `:1001` неизвестное — сервер не стартует; `:1074` `validate_production` (ЮKassa `:1108`, выплаты `:1119`); `:198` `payouts_enabled = False`, `:1026` `payouts_ready`; `:183` комиссия попутки 0%.
- `payments.py:1-5` — ЮKassa как самозанятый для своих услуг (Boost, реклама), «оплата проезда… мимо приложения»; `:25` `payments_closed()`, `:36` `_yookassa_live()`.
- `routers/wallet.py:41` `_METHODS` cash/card/sbp; `:84` в проде безнал только при `yookassa`, иначе 503; `:258` `/bookings/{id}/pay` — пассажир, только `done`; `cash` — отметка, `card`/`sbp` — через банк.
- `routers/health.py:83` `/health.payments`; `:144` `/config/features` — флаги из `.env`, без базы.
- Android `PayOnlineCard.kt:65` `OnlinePayGate` прячет карту после 503 (`:165`); `ApiClient.kt:2766` считает «включено» и `sbp_manual`, хотя прод пропускает только `yookassa`. Вызовы — `BookingActiveTripScreen.kt:2108`, `RideshareCompletedScreen.kt:361`. Сайт: `PayTripCard.tsx:46`, `:74`; `TripReceiptScreen.tsx:324`.
- Выключателя в админке нет: `routers/admin*.py` нет, админ-ручки по роутерам (`payments.py:775` — отказ только по-русски; двуязычный — `debt.py:32`). Android: `Admin*Screen.kt` (16), кабинет `SecondaryScreens.kt:1526`, `AppNavAdmin.kt:23`, `YuldashApp.kt:1182`; сайт `AdminCabinetScreen.tsx`, `App.tsx:827` (`RequireAdmin`).
- Настроек «на ходу» в базе нет (68 таблиц `models.py`, не нашёл); журнал админа — строка в логе `logs.py:67`. Админ — номер из `ADMIN_PHONES` (`auth.py:147`).

## 3. Что сделать
### Сервер (`backend/`)
1. **Готовность банка.** `config.py` у `:198`: `ride_card_bank_contract: bool = False` — «договор с банком на приём оплаты ПОЕЗДОК подписан» (`.env`, ставит Александр; ключи Boost-ЮKassa этого не значат). `validate_production` (`:1074`): флаг при `payments_provider != "yookassa"` — проблема запуска (как `:1119`).
2. **`app/card_switch.py`.** `bank_blockers()` → коды `bank_contract` (флаг выключен), `provider` (прод — не `_yookassa_live()`, dev — не `mock`/`yookassa`), `payouts` (`not payouts_ready`) + тексты `{ru, ba}`. `switch_stored`, `switch_effective = stored and not blockers`, `card_drivers(session, ids) -> set[int]` (не действует — пусто без запроса). Без кеша.
3. **Модели.** `AppSetting` (`key` PK, `value`, `updated_at`, `updated_by` FK) и `AppSettingChange` (`id`, `key`, `old_value`, `new_value`, `admin_id` FK, `created_at` index, `note` ≤ 200). Белый список ключей: `rideshare_card_payments` ∈ {`on`, `off`}, нет строки = `off`. В `DriverPaySettings` (RS-07): `accepts_card = False`, `accepts_card_at`.
4. **Админ-ручки** (`routers/payments.py` у `:775`, отказы двуязычные как `debt.py:32`, не админ — 403): `GET /admin/payments/card-switch` → `{stored, effective, blockers, updated_at, updated_by_name, history[50]}`; `POST {enabled, note?}`: включить при блокерах — 409 `card_switch_bank_not_ready` с `blockers`, ничего не пишется; выключить — всегда; строка под `with_for_update` (новая — при `IntegrityError` перечитать); то же значение — 200 без журнала; иначе одной транзакцией `AppSetting` + `AppSettingChange` + `admin_action(...)` (`logs.py:67`).
5. **Водитель** (`/driver/pay-settings` RS-07): GET + `card_switch_on`, `accepts_card`; PUT `accepts_card: true` при недействующем — 409 `card_switch_off`, `false` — всегда.
6. **Итог для рейса:** `card_payment: bool = False` в `RideOut` (пакетно в `rides_out`, `services.py:1608`), `BookingDetailsOut` (`bookings.py:370`), квитанции (`bookings.py:848`).
7. **Замок.** `pay_booking` (`wallet.py:258`), `card`/`sbp`, водитель не в `card_drivers` → **503** `card_not_available`, `Payment` не создаётся (503 — старые клиенты по нему прячут карту). `cash` — без изменений.
8. **Выключили на ходу:** новые оплаты — сразу 503; начатый в банке платёж вебхук проводит как раньше — записать в `docs/decisions.md`.
9. **Не трогать:** такси `/instant/orders/{id}/pay`, `/health`, `/config/features`, комиссию, выплаты, вебхук.
10. **Старые тесты** с `/bookings/{id}/pay` картой: `test_booking_payment_agreement_amount.py`, `test_pay_agreement_payment_concurrency.py`, `test_pay_agreement_only_driver_lowers.py`, `test_audit_fix_be35.py`, `test_pay_agreement_after_charge.py`, `test_ledger.py`, `test_audit_fix_be06_retries.py`, `test_promises.py`. Фикстура `card_on` в `tests/conftest.py` (не autouse): подмена флагов договора и выплат, `on`, водителю `accepts_card`. Подключить, где `method != "cash"`; проверки тестов не менять.

### Android (`android/`)
1. **Новый экран** `AdminCardPaymentsScreen.kt` (⭐ п.4): `Screen.AdminCardPayments` в `enum Screen` (`MainActivity.kt`, блок админских ~`:534-597`, замок §11), ветка в `AppNavAdmin.kt:23`, строка в списке `YuldashApp.kt:1182`, пункт и параметр `onCardPayments` в `AdminCabinetScreen` (`SecondaryScreens.kt:1526`), строка в `docs/architecture.md` («Навигация разрезана»).
2. Экран: статус, `Switch` (≥ 48dp); при блокерах и выключенном — `enabled = false`, «Нужен договор с банком» и причины с сервера. Вкл/выкл — `AlertDialog` с последствиями и заметкой. Журнал — `LazyColumn` с `key = id`, `appearIn`; смена статуса — `AnimatedContent`; только `Canon*`.
3. **Водитель:** в карточке RS-07 «Как принимаю оплату» (`ProfileScreen.kt:1373`) — переключатель «Принимаю оплату картой», только при `card_switch_on`; 409 — текст с сервера.
4. **Кнопка оплаты:** `BookingDtos.kt:89` — `cardPayment`; `PayOnlineCard` показывать только при `cardPayment == true` (`BookingActiveTripScreen.kt:2108`, `RideshareCompletedScreen.kt:361`). `OnlinePayGate` оставить (такси). `Domain.kt` / `toRideDto` — `cardPayment`; в `BookingTermsCard` — строка «Картой в приложении — после поездки», только при `true`.

### Сайт (`webapp/`)
1. `screens/AdminCardPaymentsScreen.tsx` — то же, что Android; маршрут `/admin/card-payments` в `App.tsx` (рядом с `:827`, под админ-гардом); плитка в `AdminCabinetScreen.tsx`; запросы в `api/admin.ts`.
2. `DriverCabinetScreen.tsx` — переключатель в блоке RS-07, только при `card_switch_on`.
3. `PayTripCard.tsx` — проп `cardAllowed?: boolean`; `false` — карта/СБП скрыты с начала (`onlineOff`, `:46`), наличные остаются. `TripReceiptScreen.tsx:324` передаёт `card_payment === true`.
4. `api/rides.ts` `card_payment?: boolean`; `BookingTerms` — та же строка, что на Android.

## 4. База данных
Миграция `cd_card_switch_<ГГГГММДД>.py` (после миграции RS-07): таблицы `appsetting`, `appsettingchange`; в `driverpaysettings` — `accepts_card` (bool, `server_default false`), `accepts_card_at` (nullable). Данные не переносятся: нет строки — выключено, никто карту не принимает. `downgrade` — удалить поля и таблицы. `down_revision` — голова по `alembic heads`.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- Админ: «Оплата картой» · «Пассажиры платят картой через банк — только водителям, которые это включили. Перевод водителю напрямую не меняется.» · «Выключено» / «Включено» / «Включено, но не работает» · «Нужен договор с банком».
- Причины: «Нет договора с банком на приём оплаты поездок» · «На сервере не настроен приём карт» · «Выключены выплаты водителям».
- Включить: «Включить оплату картой?» · «Водители смогут принять карту, пассажиры — заплатить после поездки. Деньги пойдут через банк.» · «Включить» / «Отмена». Выключить: «Выключить оплату картой?» · «Новые оплаты картой остановятся сразу. Уже начатые в банке завершатся.» · «Выключить».
- «Заметка (необязательно)» · «Журнал» · «Ещё не меняли» · «{дата, время} · {имя} · включил / выключил».
- Ошибки: «Включить нельзя: нужен договор с банком.» · «Не получилось загрузить. Повторить?» · «Не сохранилось. Повторить?»
- Водитель: «Принимаю оплату картой» · «Пассажир сможет заплатить картой в приложении после поездки.» · 409: «Оплата картой сейчас выключена.» Пассажир: «Картой в приложении — после поездки» · 503: «Оплата картой для этой поездки недоступна. Рассчитайтесь, как договорились.» Слова про зачисление водителю финализирует проект с банком.

## 6. Состояния экрана
- Админ-экран: загрузка (скелетон), ошибка / нет сети («Повторить»), журнал пуст — «Ещё не меняли», блокеры — серый выключатель + причины, повторное нажатие во время запроса — кнопки неактивны.
- Водитель: выключатель скрыт при `card_switch_on == false`; ошибка сохранения — откат переключателя и «Повторить».
- Пассажир: нет `card_payment` (старый сервер) — карты нет, наличные/договорённость как раньше.
- Длинный башкирский, крупный шрифт, 320dp — перенос строк; тёмная тема — `Canon*` (`ContrastGuardTest`).

## 7. Тесты
- Сервер `tests/test_rs08_card_switch.py`: по умолчанию выключено, блокер `bank_contract`; не админ — 403; включить с блокерами — 409, журнал пуст; с подменой — 200 и строка журнала (`admin_id`, время), повтор — без строки; выключить — всегда; прод + `mock` + договор — блокер `provider`; `accepts_card` при выключенном — 409; `card_payment` только при «выключатель + водитель», после выключения — `false`, выбор водителя цел; `/pay` `card` без разрешения — 503 и ноль `Payment`, `cash` работает; лента +1 запрос при включённом, 0 — при выключенном; два параллельных POST — итог = последняя строка журнала (PostgreSQL — как `tests/walk/l1_2/test_l1_2_wallet_race_pg.py`).
- Регрессия: 8 файлов шага 10 зелёные, `test_wave_launch_switches.py` не менялся. Мутации: убрать замок в `pay_booking` или разрешить включение без банка — красный.
- Android: Compose-тест экрана (серый выключатель с причинами, диалоги, журнал); `PayOnlineCard` не показан при `cardPayment=false` (`walk/l1_4/PayOnlineCardTest.kt` или тест вызова); `ApiClientEndpointContractTest` — новые ручки; все `*GuardTest`; `:app:assembleDebug`.
- Сайт: `tests/card-switch-test.mjs`, `pay-test.mjs` (`cardAllowed=false` — только наличные); `npm run check`, `npm run build`.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято»:
- [ ] Прямым запросом админ не включает выключатель без договора, ключей ЮKassa и выплат (409); интерфейс тут ни при чём.
- [ ] Журнал: каждое изменение — кто, когда, было/стало; повтор того же значения строку не добавляет.
- [ ] Водитель не включит «принимаю карту», пока выключатель не действует; переключатель скрыт.
- [ ] `card_payment` считает сервер; клиенты не показывают «Картой», если `false`; `/bookings/{id}/pay` с `card`/`sbp` без разрешения — 503 и ни одной строки `Payment`.
- [ ] В diff нет: настоящего списания, возвратов, выплат, правок вебхука, такси, комиссии; `payments_provider` и `payouts_enabled` по умолчанию прежние.
- [ ] Новый экран: `enum Screen` + `AppNavAdmin.kt` + `YuldashApp.kt` + `docs/architecture.md`; все надписи двуязычные.
- [ ] Эмулятор: админ-экран (серый и включённый через подмену в dev), кабинет водителя, оплата после поездки — RU/BA × светлая/тёмная. Нет эмулятора — так и написать. Сайт — те же экраны.
- [ ] Вливать и выкладывать — только после «да» Александра (деньги). Включать на проде — отдельное решение после договора с банком.

## 9. Риски и границы
- **Не входит:** договор и подключение банка, настоящая оплата поездки картой, возвраты, споры, выплаты, чеки за проезд, комиссия — отдельный будущий проект с банком (исследование п. 20–21: идемпотентность, вебхук, сверка, возврат).
- ЮKassa сейчас — самозанятый для своих услуг (`payments.py:1-5`), деньги за проезд через неё нельзя — поэтому отдельный флаг договора, а не «есть ключи».
- «Владелец» = любой админ из `ADMIN_PHONES`; помощники появятся — отдельная роль, отдельное решение.
- `paymentsOnlineEnabled` (`ApiClient.kt:2766`) остаётся для такси. Общие файлы (`models.py`, `bookings.py`, `MainActivity.kt`, `YuldashApp.kt`, `conftest.py`) — «один файл — один агент», после RS-07.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан |
