# RS-01 — Приложение не обещает того, чего нет

| | |
|---|---|
| Статус | в работе локально, Codex, ветка `codex/rs-01-honest-promises-20261024` |
| Ждёт «да» Александра | да — только часть Г: деньги (серверный замок онлайн-оплаты брони) и текст соглашения; части А–В — нет |
| Зависит от | — (вводит единый вид ошибок с кодом для всех планов; RS-04 строит на этом выключатели входа) |
| Не параллельно с | RS-02–RS-05, RS-07, RS-08, RS-12–RS-15 по общим файлам — RS-01 первым (индекс, «Очередь по общим файлам») |
| Деление на PR | `[RS-01А]` сервер: ошибки с кодом, способы входа, лента → `[RS-01Б]` сайт → `[RS-01В]` Android → `[RS-01Г]` замок онлайн-оплаты брони + текст соглашения (ждёт «да») |
| Проверка `yuldash-security-privacy` | да — двери входа (А) и замок оплаты (Г), коротко |
| Двигает сценарии приёмки | W1-E2E-05, W1-E2E-06 (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | `backend/app/`: `errors.py`, `login_methods.py` (новый), `routers/health.py`, `routers/auth.py`, `routers/requests.py`, `routers/wallet.py` (только `pay_booking`, `booking_cashless_allowed`), `main.py` (только CORS); `backend/tests/` — новые из раздела 7, `test_auth_edges.py` и 8 тестов публичного запрета оплаты брони. `android/app/src/main/java/com/yuldash/app/`: `LoginScreen.kt`, `LoginMethods.kt` (новый), `data/ApiClient.kt`, `BookingActiveTripScreen.kt`, `RideshareCompletedScreen.kt`, `PayOnlineCard.kt`, `SecondaryScreens.kt` (только `PaymentInfoScreen`); `android/app/build.gradle.kts` (только флаги входа); тесты: `HonestPromisesGuardTest.kt` (новый), `BookingActiveTripContentDeepTest.kt`, `Login*Test.kt`. `webapp/src/`: `api/client.ts`, `api/auth.ts`, `api/requests.ts`, `screens/LoginScreen.tsx`, `utils/loginFlow.js` + `.d.ts`, `components/PayTripCard.tsx`, `screens/TripReceiptScreen.tsx`, `legal.ts`; `webapp/tests/`: `honest-promises-test.mjs` (новый), `run.mjs`, `login-flow-test.mjs`, `rideshare-local-regression-test.mjs`. `web/components/legal-content.ts`. Мозг: `docs/decisions.md`, `docs/tasks.md` |
| Источник | `c5be55a:docs/research/apps/master/part-001.md`: «Что нельзя считать готовым» п. 3–6, п. 1, 18–20, 29; решения 24.10: Q5, Q13 (уточнено), Q14 а; решения координатора D2, D6 (общие решения, `docs/plans/README.md`) |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

Уточнение зоны по независимой проверке 24.10: признак неполной выдачи также требует
`RequestsFeedScreen.kt`, `RidesRequestsChatScreens.kt` и PWA
`screens/RequestsFeedScreen.tsx`, `screens/MyRequestsScreen.tsx`, `screens/RequestResponsesScreen.tsx`.
Это потребители указанных выше `getRequestsFeed`/`matchRides`, без расширения функции.

## 1. Что получит человек
- **Вход.** Бабушка из Ургазы видит только способ входа, который правда работает (сейчас — Telegram), без кнопок «скоро». Включит Александр СМС — кнопка «Войти по номеру» появится сама, без нового приложения (RS-04). Первый экран не обещает «не шлём SMS», соглашение не уверяет, что код приходит в СМС.
- **Код посадки.** Сейчас водителю из Сибая написано «Сошлись — сажай», пассажирке — «Это та самая машина». Но сервер код не проверяет (решение 17.10), и совпавший код не доказывает, что машина та. Станет: как и раньше, пассажир называет код, водитель сверяет — но без слов-гарантий; пассажирке подсказка сверить ещё и номер машины.
- **Оплата.** После поездки Акъяр → Сибай на сайте видны «Картой» и «СБП», а нажатие даёт «в твоём городе ещё не включена». Онлайн-оплаты в попутке нет (Q5) — останется «как договорились». Сервер не примет оплату брони card/sbp ни при каких настройках RS-08: это каркас, снять запрет может только отдельный принятый банковский проект.
- **Лента.** Водитель из Баймака не теряет заявки «по пути» молча: просмотрено не всё — внизу «Показаны не все — уточни село или дату».
- Такси и курьер — как есть, «Скоро в Юлдаше» (`docs/decisions.md:6930`).

## 2. Что уже есть в коде
- Ошибки: `backend/app/errors.py:11-13` — `herr(status, ru, ba)` без машинного кода; коды есть только в ручных `HTTPException`, образец — `_pickup_refusal` (`routers/bookings.py:300-303`). Android уже читает `detail.code` — `detailCode` (`ApiClient.kt:205-207`, `ApiException` — `:5446`). Сайт — нет: `ApiError` хранит только статус и текст (`webapp/src/api/client.ts:150-158`, разбор ответа — `:383-391`).
- Клиенты считают любой 403 на `/auth/tg/verify` «нужен номер»: Android `LoginScreen.kt:497`, сайт `utils/loginFlow.js:116`; сервер шлёт голую строку `HTTPException(403, "phone_required")` (`routers/auth.py:993`).
- `routers/auth.py:1022-1036` — `/auth/vk-callback`, `/auth/whatsapp-callback` → 501 «…Войди по номеру телефона» (такого входа на проде нет). 501 закрепляет `tests/test_auth_edges.py:417-418`; `test_rate_limit.py:60`, `test_api.py:105` берут `vk-callback` для проверки лимита (ждут только 429).
- Аккаунт проверки стора: `request_code` выходит раньше всех проверок (`auth.py:381-382`), `verify` — своя ветка (`:451-480`).
- `LoginScreen.kt:762-781` — кнопок VK/WhatsApp нет, СМС-форма за `BuildConfig.SMS_LOGIN_ENABLED`; устаревший комментарий «VK/WhatsApp — «скоро»» — `:713`; «Вход через Telegram скоро» без имени бота — `:429` (сайт `LoginScreen.tsx:87`).
- `android/app/build.gradle.kts:108-123` — `vkAppId` («Пусто → кнопка показывает «скоро»») и `smsLoginEnabled` (`false`), поля `:202`, `:204`; `VK_APP_ID` нигде не используется. Сайт: `api/auth.ts:80-81` (`VITE_SMS_LOGIN_ENABLED`), форма — `LoginScreen.tsx:192`.
- `config.py:40` — `sms_provider = "mock"`; `sms_channel_live()` — `services.py:1105-1122`; прод с mock → 503 (`:1286-1291`).
- Первый экран: «Не звоним и не шлём SMS» — `LoginScreen.kt:1153`, `LoginScreen.tsx:147`; «Шифруем и не передаём третьим» — `LoginScreen.kt:1158`, `LoginScreen.tsx:154`. Соглашение «код в SMS» — `webapp/src/legal.ts:158`, `web/components/legal-content.ts:158` (дата — `:17`).
- Код посадки: гарантии — только `BookingActiveTripScreen.kt:2988-2993` («Сошлись — сажай», «Это та самая машина»); остальные (`RideshareBoarding.kt:338-340, 478-483`, `BoardingWait.tsx:26`, `ActiveTripScreen.tsx:847`) нейтральны. «На сервере не сверяем» — `docs/decisions.md:184-190` (Q4). Слов «защищает», «без кода не посадят» поиском не нашёл.
- Оплата: `BookingActiveTripScreen.kt:2108-2116`, `RideshareCompletedScreen.kt:361-371` показывают `PayOnlineCard`; она видна до ответа проверки и при её сбое (`PayOnlineCard.kt:118-128`), отказ «…скоро появится. Пока рассчитайтесь…» (`:135-138`, на «вы»). Проверка — `/health` `payments` (`ApiClient.kt:2766-2768`, `health.py:83`). Сервер: `POST /bookings/{id}/pay` (`routers/wallet.py:257-283`) картой/СБП идёт в `_pay_cashless` — при `yookassa` с ключами оплата брони заработает без отдельного решения (`wallet.py:84`). Сайт: `PayTripCard.tsx:118-140` — «Картой»/«СБП» без проверки, отказ «в твоём городе…» (`:74-81`), вызов — `TripReceiptScreen.tsx:324`. «Скоро: оплата картой…» — `SecondaryScreens.kt:983` (`PaymentInfoScreen`).
- Лента: `routers/requests.py:540` — `_SCAN_MAX = FEED_MAX * 10` (`FEED_MAX = 200`, `visibility.py:285`), т. е. 2000 строк; чанкеры `:543-569` обрываются молча; это `/requests/feed` (`:626-649`) и `/match/rides` (`:1456-1484`); задача — `docs/tasks.md:234`. Android оборачивает список в `{"items": …}` и заголовков наружу не отдаёт (`ApiClient.kt:3628-3633`). CORS без `expose_headers` (`main.py:127-131`). `/rides/near` отдаёт `total` (`rides.py:1022-1027`) — не прячет.

## 3. Что сделать
### Сервер (`backend/`) — `[RS-01А]`
1. **Первым — ошибки с кодом (D2).** `errors.py`: `herr(status, ru, ba, *, code=None, **extra)` → `detail = {"ru", "ba"}` + `"code"` и `extra`, если заданы (как `_pickup_refusal`). Старые вызовы не меняются; новые отказы всех планов — только так.
2. `app/login_methods.py`: `ORDER = ("sms", "telegram", "vk", "whatsapp")`; `available_methods()`: `telegram` — заданы `telegram_bot_token` и `telegram_webhook_secret`; `sms` — `sms_channel_live()` или (`not is_prod` и mock — эмулятор с `dev_code`); `vk`, `whatsapp` — никогда (RS-04). `refuse_disabled(method)` → `herr(403, …, code="login_method_disabled", method=method)`. RS-04 заменит источник на выключатели владельца.
3. `routers/health.py`: `GET /config/login-methods` → `{"methods": [...]}`, `Cache-Control: public, max-age=30`, без токена. Не в `/config/features` (`test_wave_launch_switches.py:80` сверяет ответ целиком) и не под `/auth/*` (строгий лимит, `middleware.py:42`).
4. `routers/auth.py`: `vk_callback`, `whatsapp_callback` → `refuse_disabled` вместо 501 (пути остаются отказом и после RS-04). Проверка способа в `request_code` и `verify` — **после** ветки `_review_login_active` (`:381`, `:451`), в `tg_start`, `tg_verify` — первой строкой; выключен → 403 без `OtpCode` и отправки. `auth.py:993` → `herr(403, …, code="phone_required")` (тексты — как у клиентов сейчас). 503 — только сбой провайдера при включённом способе.
5. `routers/requests.py`: чанкер, дойдя до `_SCAN_MAX`, дочитывает ещё одну строку (`limit(1)`) — обрыв только если она есть (ровно 2000 — не обрыв); `requests_feed`, `match_rides` ставят `X-Yuldash-Partial: 1`, тело не меняется. `main.py`: `expose_headers=["X-Yuldash-Partial"]` в CORS.

### Сайт (`webapp/`) — `[RS-01Б]`
1. `api/client.ts`: `ApiError(status, message, code = "")`, `code` — из `body.detail.code` (`:383-391`).
2. `api/auth.ts`: `fetchLoginMethods()` (`auth: false`), кеш в `localStorage` под try/catch, фолбэк `["telegram"]`; убрать `SMS_LOGIN_ENABLED`.
3. `LoginScreen.tsx`, `loginFlow.js`/`.d.ts`: кнопки — только из списка; пустой список → «Вход временно недоступен» + «Повторить»; нет бота — нет кнопки, а не «скоро». Отказы ветвить по `code`: `phone_required` → «нужен номер», `login_method_disabled` → текст сервера и перезапрос списка; 403 без кода — как сейчас «нужен номер» (старый сервер). Тексты первого экрана (`:147`, `:154`).
4. `PayTripCard.tsx`: при `kind="booking"` без «Картой»/«СБП» (остаются «Наличными» и «это отметка, а не платёж»); такси — текст 503 из раздела 5.
5. `api/requests.ts:143, 204` — читать `X-Yuldash-Partial`, подпись под списком.

### Android (`android/`) — `[RS-01В]`
1. `ApiClient.kt`: `loginMethods()` без токена. В `call` (`:3628-3633`) при обёртке списка, если `conn.getHeaderField("X-Yuldash-Partial") == "1"`, добавить `"_partial": true` рядом с `items`; `getRequestsFeed`, `matchRides` (`:1410`, `:1445`) возвращают признак вместе со списком.
2. `LoginMethods.kt`: последний ответ — в своём `SharedPreferences`, незнакомые имена отбросить; нет ответа и кеша → `["telegram"]`. Обновлять при открытии входа и возврате в приложение.
3. `LoginScreen.kt`: Telegram — если в списке и имя бота не пустое; СМС — если `sms` в списке; пустой список → «Вход временно недоступен» + «Повторить». В `onTgVerify` (`:496-503`) ветвить по `detailCode`: `phone_required` или пустой код при 403 → «нужен номер», `login_method_disabled` → текст сервера и перезапрос списка. Убрать `BuildConfig.SMS_LOGIN_ENABLED`, комментарии `:713`, `:762-763`; тексты первого экрана (`:1153`, `:1158`).
4. `build.gradle.kts`: удалить `smsLoginEnabled`/`SMS_LOGIN_ENABLED` и мёртвые `vkAppId`/`VK_APP_ID`, больше ничего (§10, §12).
5. Код посадки: только `BookingActiveTripScreen.kt:2988-2993` — тексты раздела 5; кто называет и кто сверяет — не менять (Q14 а). `BoardingCodeRules.kt` и остальные тексты кода не трогать.
6. Оплата: `PayOnlineCard` из двух экранов попутки убрать (Q5); RS-08 не возвращает действующую кнопку, потому что `effective=false` до отдельного банковского проекта. В `PayOnlineCard.kt` (такси) — видна только после ответа «включено», сбой = скрыто, текст отказа — раздел 5. `PaymentInfoScreen` — строка о договорённости вместо «Скоро…».
7. Лента: признак `_partial` → подпись под списком. Везде `appText(ru, ba)`, `Canon*`, `CanonMotion`, тач-цели ≥ 48dp.

### Деньги и соглашение — `[RS-01Г]`, ждёт «да»
1. `routers/wallet.py`: `booking_cashless_allowed(session, booking) -> bool` всегда `False`, в том числе после RS-08. `pay_booking`: после проверки участника, но **до** paid-replay, создания `Payment`, `_pay_cashless` и провайдера, `card`/`sbp` → 503 `herr(..., code="booking_online_pay_off")` при любом провайдере. Наличные и такси не меняются. Восемь существующих тестов публичного пути оплаты брони адаптировать к запрету (перечень в RS-08), не подменять predicate на True в endpoint-тестах. Нужные проверки общего `_pay_cashless` оставить отдельными изолированными тестами с поддельным провайдером, без HTTP и без банковских ключей. Новый негативный тест: все флаги True → 503, ноль новых Payment, ноль provider calls. Production и реальные деньги не запускаются.
2. Соглашение (`legal.ts:158`, `web/components/legal-content.ts:158`) — текст раздела 5, новая дата редакции; diff — отдельным пунктом в PR.

## 4. База данных
Не меняется. Новых данных и сроков хранения нет; `account.py` и `cleanup.py` не трогаются.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- `login_method_disabled`: «Этот способ входа сейчас выключен. Выбери другой на экране входа.»
- Нет способов: «Вход временно недоступен. Попробуй чуть позже.» + «Повторить».
- `phone_required` — прежний текст клиентов «Для безопасности нужен номер…» (`LoginScreen.kt:422`).
- Первый экран: «Никакого спама» — «Не звоним и не шлём рекламу»; «Данные под защитой» — «Номер скрыт, пока поездка не подтверждена».
- Код посадки (`BookingActiveTripScreen.kt`), водитель: «Спроси у пассажира код и сверь с этими цифрами.»; пассажир: «Назови код водителю и сверь номер машины.»
- `booking_online_pay_off`: «Оплата поездки — как договорились с водителем: наличными или переводом.»
- Такси: «Онлайн-оплата сейчас недоступна. Рассчитайся, как договорились.»; сайт: «Оплата картой сейчас недоступна. Отдай наличными или переведи водителю.»; `PaymentInfoScreen`: «Оплата — как договоришься с водителем: наличными или переводом.»
- Лента: «Показаны не все — уточни село или дату».
- Соглашение: «Вход в сервис — по одноразовому коду. Доступные способы входа (например, через Telegram или по SMS) показаны на экране входа. Вы обязуетесь указывать достоверные данные и не передавать доступ к аккаунту третьим лицам.»

## 6. Состояния экрана
- Вход: список грузится — скелетон; нет сети и кеша — Telegram + обычная ошибка сети; пустой список — карточка с «Повторить».
- Код посадки: «загружается / не удалось / Повторить» (`RideshareBoarding.kt:488-502`) не трогать.
- Квитанция — без пустот на месте кнопок оплаты; подпись ленты — под списком; башкирский переносится, крупный шрифт, 320dp, тёмная тема — `Canon*`.

## 7. Тесты
Все сценарии этого раздела — **NOT_EXECUTED**, пока исполнитель не приложил реальный вывод к конкретному SHA.
- Сервер: `test_errors_have_codes.py` — `herr(..., code=)` даёт `{"ru","ba","code"}`, без `code` — прежний вид. `test_login_methods_honest.py`: прод + mock → `["telegram"]`; живой `smsru` → `["sms","telegram"]`; без бота — без `telegram`; `vk`/`whatsapp` не появляются; старые callback → 403 `login_method_disabled` на двух языках; выключенный способ в `request-code`/`verify`/`tg/*` → 403, `OtpCode` нет, `send_sms` не вызван; аккаунт стора работает при выключенном СМС (`test_review_login_permissions.py`); `phone_required` с кодом; ровно 2000 кандидатов — без заголовка, 2001 — `X-Yuldash-Partial: 1`. `test_booking_online_pay_locked.py`: `yookassa` с ключами → бронь картой и СБП 503 `booking_online_pay_off`, ноль новых Payment и provider calls (также при всех флагах True), наличные — 200, такси — как раньше. `test_auth_edges.py:417-418` → 403 с кодом.
- Android: `HonestPromisesGuardTest.kt` (по образцу `SosNoSmsPromiseGuardTest.kt`, только строки кода): нет «Сошлись — сажай», «Это та самая машина», «Telegram скоро», «Онлайн-оплата скоро», «Скоро: оплата картой», «Не звоним и не шлём SMS», `SMS_LOGIN_ENABLED`, `VK_APP_ID`; в файлах кода посадки — «защищ», «без кода не»; `PayOnlineCard(` в `BookingActiveTripScreen.kt`, `RideshareCompletedScreen.kt` — запрещён на всём протяжении RS-01/RS-08; соседний клиентский флаг не исключение. Unit: `LoginMethods`, разбор `_partial`; Compose: `LoginFormContent` с `["telegram"]`, `["sms","telegram"]`, `[]`, отказ `login_method_disabled` не включает «нужен номер». Все `*GuardTest` зелёные.
- Сайт: `honest-promises-test.mjs` — те же запреты по `webapp/src` и `legal-content.ts` («Картой»/«СБП» в `PayTripCard` для `booking` запрещены, соседний `cardAllowed` не исключение), запись в `run.mjs`; `login-flow-test.mjs` — ветвление по `code`. `npm run check` + `build`; лендинг — `lint` + `build`.
- Мутации: вернуть 501, убрать `code` из `herr`, вернуть «Сошлись — сажай» или действующий `PayOnlineCard(` в попутке, убрать замок `pay_booking` — тест краснеет.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] Сторожа п. 7 зелёные (вывод приложен), запрещённых слов поиск не находит.
- [ ] Настройки прода (mock СМС, бот задан): `/config/login-methods` → только `telegram`; `/auth/vk-callback` → 403 `login_method_disabled`.
- [ ] Эмулятор, RU/BA × светлая/тёмная: на входе одна кнопка Telegram, без «скоро» и «по номеру»; с dev-сервером (mock) — «Войти по номеру» есть, вход по `dev_code` проходит; Telegram без номера — «нужен номер», как раньше.
- [ ] Подтверждённая бронь: у водителя «Спроси у пассажира код…», у пассажира «Назови код водителю и сверь номер машины»; кто называет код — как раньше; о защите ни слова.
- [ ] Завершённая попутка (эмулятор и сайт): нет «Оплатить онлайн», «Картой», «СБП»; запрос оплаты брони картой на dev с ключами `yookassa` → 503 `booking_online_pay_off`.
- [ ] Тестовая база с 2001+ заявками — под «по пути» подпись «Показаны не все…»; сайт видит заголовок (CORS).
- [ ] Соглашение — новый текст и дата, diff в PR отдельно; крупный шрифт и 320dp — без обрезки; BA-черновики в `docs/tasks.md`.
- [ ] `/gotovo`: `:app:assembleDebug` + unit + `*GuardTest`; `ruff check app/ --select F` + `pytest`; `webapp` `check` + `build`; `web` `lint` + `build`.
- Двигает W1-E2E-05, -06. Остаётся: BA носителем, RS-04, W1-E2E-07 (Q11).
- **Мозг:** `decisions.md` — ошибки с кодом (D2), замок оплаты брони, код посадки без гарантий; `tasks.md` — BA-черновики, отметка `:234`; `architecture.md` — экранов нет; `lessons.md` — «ветвить по `code`, не по статусу».

## 9. Риски и границы
- Не входит: включение СМС/VK/WhatsApp и выключатели — RS-04; RS-08 лишь каркас карты, hard lock сохраняется; реальный банк — отдельный проект. Серверная проверка кода посадки не вводится (Q14 а), вкладки такси и курьера не трогаем (D6).
- Пока нет RS-04, СМС на проде включится сам, как только канал станет «живым» (`sms_channel_live`: `SMS_PROVIDER=smsru` и ключ). Ключ sms.ru до RS-04 на сервер не класть.
- Старое приложение при выключенном Telegram (403 `login_method_disabled` на `tg/verify`) покажет «поделись номером» — неверно, но безопасно: входа не будет. Лечится обновлением.
- Аккаунт стора работает на сервере всегда, но форма номера видна только при включённом СМС — до RS-04 проверяющим стора давать Telegram.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан (Q13 уточнено) |
| 24.10.2026 | не начато | — | — | правки по приёмке: D2 первым шагом, ветвление по `code`, проверка после аккаунта стора, вкладки и направление сверки не меняем (D6), замок оплаты брони — часть Г |
| 24.10.2026 | в работе локально | Codex | `codex/rs-01-honest-promises-20261024` | Поручение Александра; А/Б/В в своей папке; Г после проверки плана, только в ветке; push/merge/production не разрешены |
| 31.10.2026 | в работе, доводка | Kimi | та же ветка | Codex оставил реализацию А–В (+подготовку Г) незакоммиченной. Доведено: `loginMethods` на `mapCatching` (битый ответ ронял вход), ящик `login_methods` записан в SessionKeys (переживает выход, публичное), логика способов входа и баннер ошибки вынесены в `LoginMethodsGate.kt` (храповик размеров), один баннер «Повторить» вместо двух, ожидания узлов в тестах оплаты/входа, `test_health_hint_stays_private` изолирован уникальными городами от потолка ленты 200 в общей базе, мёртвые `VK_APP_ID`/`SMS_LOGIN_ENABLED` убраны из `build.gradle.kts`. Полные проверки — перед коммитом |
| 31.10.2026 | аудит P1/P2 закрыты | Kimi (перехват после зависшей сессии) | `3218a4c2` | Доводка закоммичена; свежие прогоны этого состояния: `:app:assembleDebug` BUILD SUCCESSFUL, `testDebugUnitTest` — 4480 тестов, 0 падений (2 пропущены — live-backend), все 40 `*GuardTest` зелёные; полный pytest после фиксов 6+2 — 6529 passed, 0 failed (было 6 failed + 2 errors). Эмулятор Pixel_API35: вход RU/BA × светлая/тёмная, dev-список `["sms"]` — кнопка «по номеру»; офлайн-фолбэк — одна кнопка Telegram, скелетон, баннер с «Повторить». Скриншоты: `artifacts/rs-01-emulator-2026-10-31/` (основная папка). Осталось после выкладки: живые прод-проверки `/config/login-methods` на yulbash.ru |
