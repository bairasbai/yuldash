# RS-01 — Приложение не обещает того, чего нет

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | нет — только тексты, кнопки и закрытые двери; деньги, база, выкладка не меняются |
| Зависит от | — (RS-04 строит на этом выключатели входа) |
| Двигает сценарии приёмки | W1-E2E-05 (смена входа, личные данные), W1-E2E-06 (два языка и экран) |
| Зона файлов | сервер: `backend/app/login_methods.py` (новый), `backend/app/routers/health.py`, `backend/app/routers/auth.py`, `backend/app/routers/requests.py`, `backend/tests/test_login_methods_honest.py` (новый), `backend/tests/test_auth_edges.py`; Android: `LoginScreen.kt`, `LoginMethods.kt` (новый), `data/ApiClient.kt`, `RideshareBoarding.kt`, `BookingActiveTripScreen.kt`, `RideshareCompletedScreen.kt`, `PayOnlineCard.kt`, `SecondaryScreens.kt` (только `PaymentInfoScreen`), `ModeSwitchHome.kt`, `android/app/build.gradle.kts` (только флаги входа), тесты `HonestPromisesGuardTest.kt` (новый), `BoardingCodeVisibilityTest.kt`, `BookingActiveTripContentDeepTest.kt`, `Login*Test.kt`; сайт: `webapp/src/screens/LoginScreen.tsx`, `webapp/src/api/auth.ts`, `webapp/src/utils/loginFlow.js` + `.d.ts`, `webapp/src/screens/ActiveTripScreen.tsx`, `webapp/src/components/BoardingWait.tsx`, `webapp/src/components/PayTripCard.tsx`, `webapp/src/screens/TripReceiptScreen.tsx`, `webapp/src/components/ModeSwitch.tsx`, `webapp/src/screens/HomeScreen.tsx`, `webapp/src/api/requests.ts`, `webapp/src/legal.ts`, `webapp/tests/honest-promises-test.mjs` (новый), `webapp/tests/run.mjs`, `webapp/tests/rideshare-local-regression-test.mjs`; лендинг: `web/components/legal-content.ts`; мозг: `docs/decisions.md`, `docs/tasks.md` |
| Источник | исследование `master/part-001.md`: «Что нельзя считать готовым» п. 3–6, чек-лист п. 1, 18, 19, 20, 29; решения Александра 24.10: Q5, Q13 (уточнено 24.10), Q14 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
- **Вход.** Бабушка из Ургазы открывает приложение и видит только тот способ входа, который правда работает (сейчас — Telegram). Не бывает кнопки, которая ведёт в «скоро» или в «не подключено». Когда Александр позже включит СМС, кнопка «Войти по номеру» появится сама, без нового приложения (переключатели — RS-04). Мелкий текст на первом экране больше не обещает «не шлём SMS», а пользовательское соглашение не уверяет, что код приходит в СМС, пока СМС выключены.
- **Код посадки.** Сейчас водителю из Сибая написано «Сошлись — сажай», а пассажирке — «Это та самая машина». Но сервер код не проверяет (так решили 17.10), а «да, сошлось» может сказать кто угодно. Станет честно: это **сверка** — водитель называет код, пассажирка сравнивает со своим экраном вместе с номером и цветом машины. Ни одна надпись не обещает, что код «защищает» или что «без кода не посадят».
- **Оплата.** После поездки Акъяр → Сибай пассажир на сайте видит кнопки «Картой» и «СБП», жмёт — и читает «в твоём городе ещё не включена». Онлайн-оплаты в попутке нет (решение Q5). Станет: только «как договорились» — наличными или переводом водителю, без кнопок-обманок.
- **Режимы.** На главной вкладки «Такси» и «Курьер» сейчас ведут на «Скоро». Пока режим выключен, вкладки нет; включит Александр — появится сама.
- **Лента.** Водитель из Баймака в ленте «по пути» не теряет заявки молча: если сервер просмотрел не всё, внизу видно «Показаны не все — уточни село или дату».

## 2. Что уже есть в коде
- `backend/app/routers/auth.py:1022-1036` — `/auth/vk-callback` и `/auth/whatsapp-callback` отвечают 501 «…пока не подключён. Войди по номеру телефона» — советуют вход, которого на проде нет.
- `backend/tests/test_auth_edges.py:417-418` — тест закрепляет 501; `test_rate_limit.py:60` и `test_api.py:105` берут `vk-callback` как дешёвую ручку `/auth/*` для проверки лимита (проверяют только наличие 429).
- `android/.../LoginScreen.kt:762-781` — кнопок VK/WhatsApp на экране уже нет (только комментарий); СМС-форма за `BuildConfig.SMS_LOGIN_ENABLED`. Устаревший комментарий «VK/WhatsApp — «скоро»» — `LoginScreen.kt:713`.
- `android/app/build.gradle.kts:108-123` — `vkAppId` («Пусто → кнопка показывает «скоро»») и `smsLoginEnabled` из `local.properties`, по умолчанию `false`; поля — строки 202 и 204. `VK_APP_ID` в коде приложения не используется (поиском не нашёл).
- `webapp/src/api/auth.ts:80-81` — `SMS_LOGIN_ENABLED` из `VITE_SMS_LOGIN_ENABLED`; `webapp/src/screens/LoginScreen.tsx:192` — СМС-форма за этим флагом; `LoginScreen.tsx:87` — «Вход через Telegram скоро», если имя бота пустое (то же `LoginScreen.kt:429`).
- `backend/app/config.py:40` — `sms_provider = "mock"`; `backend/app/services.py:1105-1122` — `sms_channel_live()`; `services.py:1286-1291` — на проде при mock вход по СМС отвечает 503.
- Первый экран: «Не звоним и не шлём SMS» — `LoginScreen.kt:1153`, `webapp/src/screens/LoginScreen.tsx:147`; «Шифруем и не передаём третьим» — `LoginScreen.kt:1158`, `LoginScreen.tsx:154` (а политика `webapp/src/legal.ts:84` перечисляет Firebase, Яндекс, Sentry, Telegram, водитель видит номер после подтверждения).
- Соглашение: «Вход… по номеру телефона: на него приходит одноразовый код в SMS» — `webapp/src/legal.ts:158`, `web/components/legal-content.ts:158`.
- Код посадки: `BookingActiveTripScreen.kt:2988-2993` — «Спроси код у пассажира… Сошлись — сажай» / «Назови водителю — сверят. Это та самая машина»; `RideshareBoarding.kt:478-483` — «Попроси пассажира назвать код» / «Назови код водителю перед посадкой»; `RideshareBoarding.kt:338-340` и `webapp/src/components/BoardingWait.tsx:26` — «Сверь машину и назови код…»; `webapp/src/screens/ActiveTripScreen.tsx:847` — «Назови его водителю при посадке». Решение «на сервере не сверяем» — `docs/decisions.md:184-190` (Q4, 17.10). Код выдаёт `backend/app/routers/bookings.py:829-833`.
- Слов «защищает», «без кода не посадят» поиском по `android/`, `webapp/src`, `web/`, `backend/app` не нашёл.
- Онлайн-оплата в попутке: Android — `BookingActiveTripScreen.kt:2108-2116` и `RideshareCompletedScreen.kt:361-371` показывают `PayOnlineCard`; карточка видна, пока не пришёл ответ проверки, а при сбое проверки остаётся (`PayOnlineCard.kt:118-128`), текст отказа «Онлайн-оплата скоро появится. Пока рассчитайтесь…» (`PayOnlineCard.kt:135-138`, «вы» — против тона §9). Проверка — по `/health` `payments` (`ApiClient.kt:2766-2768`, `health.py:83`): при `sbp_manual` карточка видна, а `wallet.py:84` всё равно отвечает 503. Сайт — `webapp/src/components/PayTripCard.tsx:118-140` рисует «Картой»/«СБП» без проверки, отказ — «в твоём городе ещё не включена» (`PayTripCard.tsx:74-81`); вызов из квитанции попутки — `TripReceiptScreen.tsx:324`. «Скоро: оплата картой прямо в приложении» — `SecondaryScreens.kt:983` (`PaymentInfoScreen`, строка 917).
- Режимы: `backend/app/features.py:43-55` — сводка флагов; Android `ModeSwitchHome.kt:288-300` всегда рисует «Попутка/Такси/Курьер», такси выключено — `TaxiComingSoonCard` (`InstantOrderScreen.kt:1799`), курьер — `FeatureGate` → «Скоро» (`ModeSwitchHome.kt:245`); сайт `webapp/src/components/ModeSwitch.tsx:23-25` — те же три. Сторожа дверей уже есть: `FeatureGateWiringGuardTest.kt`, `webapp/tests/taxi-flag-hides-test.mjs`, `backend/tests/test_wave_launch_switches.py`.
- Лента: `backend/app/routers/requests.py:540-569` — `_SCAN_MAX = FEED_MAX * 10` (= 2000, `visibility.py:285`), порции обрываются молча; используют `/requests/feed` (`requests.py:626-649`) и `/match/rides` (`requests.py:1456-1484`); ответ — голый список. Задача — `docs/tasks.md:234`. `/rides/near` отдаёт `total` и `hidden_unverified` (`rides.py:1022-1027`) — молча не прячет.
- Образец сторожа «не обещать»: `android/app/src/test/java/com/yuldash/app/SosNoSmsPromiseGuardTest.kt`, `webapp/tests/sos-no-sms-promise-test.mjs`.

## 3. Что сделать
### Сервер (`backend/`)
1. Новый `app/login_methods.py`: порядок показа `ORDER = ("sms", "telegram", "vk", "whatsapp")`; `available_methods() -> list[str]` — `telegram`, если заданы `telegram_bot_token` и `telegram_webhook_secret`; `sms`, если `sms_channel_live()` или (`not settings.is_prod` и `sms_provider == "mock"` — для эмулятора с `dev_code`); `vk` и `whatsapp` — никогда (их сделает RS-04). `refuse_disabled(method)` → `HTTPException(403, detail={"ru", "ba", "code": "login_method_disabled", "method": method})`. Одна функция — один источник правды; RS-04 добавит в неё переключатели владельца.
2. `routers/health.py`: `GET /config/login-methods` → `{"methods": [...]}` (только доступные, в порядке `ORDER`), `Cache-Control: public, max-age=60`, без токена, без личных данных. Не в `/config/features` (её ключи постоянные, `test_wave_launch_switches.py:80` сверяет ответ целиком) и не под `/auth/*` (строгий лимит 20/мин на IP, `middleware.py:42`, съел бы бюджет входа).
3. `routers/auth.py`: `vk_callback` и `whatsapp_callback` → `refuse_disabled("vk"|"whatsapp")` вместо 501 (старым клиентам — понятный отказ; пути остаются отказом и после RS-04). В начале `request_code`, `verify` (после ветки проверочного аккаунта стора — её не трогать), `tg_start`, `tg_verify`: метод не в `available_methods()` → `refuse_disabled`. Текст 503 «Вход по SMS временно не работает…» остаётся только для сбоя провайдера при включённом методе.
4. `routers/requests.py`: `_ride_chunks`/`_request_chunks` сообщают, что упёрлись в `_SCAN_MAX` (флаг в маленьком объекте-счётчике, без смены формы ответа); `requests_feed` и `match_rides` ставят заголовок `X-Yuldash-Partial: 1`. Список в теле не меняется — старые клиенты не ломаются.
5. Ошибки — двуязычные, тексты из раздела 5; BA — черновик лингвиста.

### Android (`android/`)
1. `ApiClient.kt`: `suspend fun loginMethods(): Result<List<String>>` → `GET /config/login-methods` без токена. Новый `LoginMethods.kt`: последний ответ хранится в своём ящике `SharedPreferences` (не секрет); незнакомые имена отбрасываются; нет ни ответа, ни кеша → `["telegram"]` (единственный способ, работавший всегда; без сети нажатие и так покажет «нет интернета»). Обновление при открытии экрана входа и при возврате в приложение.
2. `LoginScreen.kt`: Telegram-кнопка — если `telegram` в списке и имя бота не пустое; СМС-раздел — если `sms` в списке. Пустой список → карточка «Вход временно недоступен…» + «Повторить» (а не «Вход через Telegram скоро»). Отказ `login_method_disabled` → текст сервера и перезапрос списка. Удалить зависимость от `BuildConfig.SMS_LOGIN_ENABLED` и устаревшие комментарии (`LoginScreen.kt:713, 762-763`).
3. `build.gradle.kts`: убрать `smsLoginEnabled` + поле `SMS_LOGIN_ENABLED` и мёртвые `vkAppId` + `VK_APP_ID` (строки 108-123, 202, 204). Больше ничего в файле не трогать (§10, §12: конфиг сборки — ни с кем параллельно).
4. Первый экран: «Не звоним и не шлём SMS» и «Шифруем и не передаём третьим» — заменить текстами раздела 5 (`LoginScreen.kt:1153, 1158`).
5. Код посадки (`BookingActiveTripScreen.kt:2988-2993`, `RideshareBoarding.kt:338-340, 478-483`): водитель **называет** код, пассажир **сверяет** — только так сверка что-то говорит пассажиру о машине; тексты — раздел 5. Видимость карточки (`BoardingCodeRules.kt`) не менять.
6. Онлайн-оплата в попутке: в `BookingActiveTripScreen.kt:2108-2116` и `RideshareCompletedScreen.kt:361-371` `PayOnlineCard` не показывать (Q5; вернётся только через выключатель RS-08). В `PayOnlineCard.kt` (остаётся для такси) — показывать только после ответа «включено», сбой проверки = скрыто; текст отказа — раздел 5. `PaymentInfoScreen` (`SecondaryScreens.kt:983`) — убрать «Скоро: оплата картой…», вместо — строка о договорённости.
7. `ModeSwitchHome.kt`: в `ModeSwitchBar` только включённые режимы (`Taxi` — `features.isOn(Feature.Taxi)`, `Courier` — `features.deliveryOn`); один режим — переключателя нет совсем (место не пустует — содержимое поднимается с `animateContentSize`); запомненный `mode_last` выключенного режима → `Pooling`. `ComingSoon` остаётся для старых ссылок и пушей.
8. Лента `/requests/feed?on_route=true` и `/match/rides` (`ApiClient.kt:1410, 1445`): узкий вариант запроса, который отдаёт заголовок; при `X-Yuldash-Partial` — подпись под списком (раздел 5).
9. Всё — `appText(ru, ba)`, `Canon*`, появление/исчезновение — `AnimatedVisibility` с `CanonMotion`; тач-цели ≥ 48dp.

### Сайт (`webapp/`)
1. `api/auth.ts`: `fetchLoginMethods()` (`auth: false`), кеш в `localStorage` под try/catch, фолбэк `["telegram"]`; удалить `SMS_LOGIN_ENABLED`.
2. `LoginScreen.tsx` + `utils/loginFlow.js` (+ `.d.ts`): как Android п. 2; новая ошибка `methodDisabled`; «botMissing» не показывает «скоро» — кнопки просто нет.
3. Первый экран (`LoginScreen.tsx:147, 154`) — тексты раздела 5.
4. Код посадки: `ActiveTripScreen.tsx:847`, `BoardingWait.tsx:26` — как Android п. 5.
5. `PayTripCard.tsx`: проп `kind="booking"` → без «Картой»/«СБП», остаётся «Наличными» и пояснение «это отметка, а не платёж»; для такси — текст 503 из раздела 5.
6. `ModeSwitch.tsx` + `HomeScreen.tsx` (подсказка «Чем отличается?»): только включённые режимы, как Android п. 7.
7. `api/requests.ts:143, 204` — читать `X-Yuldash-Partial`, подпись под списком.
8. Соглашение: `webapp/src/legal.ts:158` и `web/components/legal-content.ts:158` — текст раздела 5, обновить дату редакции (`UPDATED` в `legal-content.ts:17`). Это юридический текст: в PR — diff отдельным пунктом для Александра (информировать, «да» не требуется: обещание сужается, а не расширяется).

## 4. База данных
Не меняется.

## 5. Надписи
Русский; башкирский — черновик агента `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку». Сам не сочинять.
- Отказ `login_method_disabled`: «Этот способ входа сейчас выключен. Выбери другой на экране входа.»
- Нет ни одного способа: «Вход временно недоступен. Попробуй чуть позже.» + «Повторить».
- Первый экран: «Никакого спама» — «Не звоним и не шлём рекламу»; «Данные под защитой» — «Номер скрыт, пока поездка не подтверждена».
- Код посадки, водитель: «Назови пассажиру этот код — он сверит его со своим экраном».
- Код посадки, пассажир: «Водитель назовёт код — сверь его, номер и цвет машины».
- Ожидание посадки (пассажир): «Сверь машину и код с водителем. Когда сядешь, водитель отметит посадку в приложении.»
- Такси, отказ онлайн-оплаты: «Онлайн-оплата сейчас недоступна. Рассчитайся, как договорились.»; сайт: «Оплата картой сейчас недоступна. Отдай наличными или переведи водителю.»
- `PaymentInfoScreen`: «Оплата — как договоришься с водителем: наличными или переводом.»
- Лента: «Показаны не все — уточни село или дату».
- Соглашение: «Вход в сервис — по одноразовому коду. Доступные способы входа (например, через Telegram или по SMS) показаны на экране входа. Вы обязуетесь указывать достоверные данные и не передавать доступ к аккаунту третьим лицам.»

## 6. Состояния экрана
- Экран входа: список грузится — кнопки скелетоном (не мигают «скоро»); нет сети и кеша — Telegram + обычная ошибка сети; пустой список — карточка с «Повторить».
- Код посадки: прежние «загружается / не удалось / Повторить» (`RideshareBoarding.kt:488-502`) не трогать.
- Квитанция попутки без онлайн-оплаты: блок «Как оплатил?» без пустых мест.
- Главная с одним режимом: без пустой полосы над шапкой.
- Длинный башкирский текст переносится (без `maxLines` на подписях), крупный шрифт, узкий экран 320dp, тёмная тема — только `Canon*`.

## 7. Тесты
- Сервер, новый `test_login_methods_honest.py`: `/config/login-methods` на проде с mock → `["telegram"]`; с живым `smsru` → `["sms","telegram"]`; без токена бота → без `telegram`; `vk`/`whatsapp` не появляются никогда; `/auth/vk-callback`, `/auth/whatsapp-callback` → 403 `code=login_method_disabled`, оба языка; `request-code`/`verify`/`tg/start`/`tg/verify` при выключенном методе → 403 с кодом, `OtpCode` не создаётся, `send_sms` не вызывается; проверочный аккаунт стора не сломан (`test_review_login_permissions.py`). Лента: 2001+ кандидатов → `X-Yuldash-Partial: 1`, меньше — заголовка нет. В `test_auth_edges.py:417-418` — 501 → 403 с кодом.
- Android, новый `HonestPromisesGuardTest.kt` (по образцу `SosNoSmsPromiseGuardTest.kt`, только строки кода): нет «Сошлись — сажай», «Это та самая машина», «защищ», «без кода», «Онлайн-оплата скоро», «Скоро: оплата картой», «Не звоним и не шлём SMS», «скоро» рядом с VK/WhatsApp; нет `SMS_LOGIN_ENABLED`, `VK_APP_ID`; `PayOnlineCard(` не встречается в `BookingActiveTripScreen.kt` и `RideshareCompletedScreen.kt`. Unit: `LoginMethods` (кеш, незнакомое имя, фолбэк). Compose: `LoginFormContent` с `["telegram"]`, `["sms","telegram"]`, `[]`; `ModeSwitchBar` с одним и тремя режимами. Обновить `BoardingCodeVisibilityTest.kt`, `BookingActiveTripContentDeepTest.kt`, `Login*Test.kt`. Все `*GuardTest` зелёные.
- Сайт: новый `tests/honest-promises-test.mjs` (те же запреты по `webapp/src` и `web/components/legal-content.ts`) — записать в `tests/run.mjs`; обновить `rideshare-local-regression-test.mjs`, `login-flow-test.mjs`. `npm run check`, `npm run build`; лендинг — `npm run lint`, `npm run build`.
- Мутации: вернуть 501 в `vk_callback` → красный серверный тест; вернуть «Сошлись — сажай» → красный `HonestPromisesGuardTest`; вернуть `PayOnlineCard(` в экран попутки → красный; вернуть `SMS_LOGIN_ENABLED` в `LoginScreen.tsx` → красный `honest-promises-test`.

## 8. Приёмка
Принимает агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] Поиском по `android/app/src/main`, `webapp/src`, `web/components`, `backend/app` нет обещаний из п. 7 (сторожа зелёные — приложить вывод).
- [ ] `GET /config/login-methods` на настройках прода (mock СМС, бот задан) → только `telegram`; `/auth/vk-callback` → 403 `login_method_disabled`, не 501.
- [ ] Эмулятор (RU/BA × светлая/тёмная): на экране входа одна кнопка Telegram, нет «скоро», нет «по номеру»; на dev-сервере с mock — появляется «Войти по номеру», вход по `dev_code` проходит.
- [ ] Эмулятор: подтверждённая бронь — у водителя «Назови пассажиру этот код…», у пассажира «Водитель назовёт код…»; ни слова о защите.
- [ ] Эмулятор и сайт: завершённая попутка — нет «Оплатить онлайн», «Картой», «СБП»; есть «Наличными / как договорились».
- [ ] Главная при выключенных такси и курьере: только попутка, без переключателя, без пустой полосы; включили такси на dev → вкладка появилась без нового APK.
- [ ] Лента «по пути» на тестовой базе с 2001+ заявками: подпись «Показаны не все…».
- [ ] Соглашение на сайте и лендинге: новый текст, новая дата; diff в PR отдельным пунктом.
- [ ] Крупный шрифт и экран 320dp: тексты не обрезаны; BA-черновики записаны в `docs/tasks.md`.
- [ ] `/gotovo`: `:app:assembleDebug`, unit-тесты и все `*GuardTest`; `ruff check app/ --select F` + `pytest`; `webapp` `check` + `build`; `web` `lint` + `build`.
- Двигает: W1-E2E-05 (честный вход), W1-E2E-06 (тексты RU/BA × темы). Остаётся: BA носителем, вход по СМС/VK/WhatsApp (RS-04), W1-E2E-07 (Q11).

## 9. Риски и границы
- Не входит: включение СМС, VK, WhatsApp и экран переключателей — RS-04; выключатель «оплата картой» — RS-08; серверная проверка кода посадки — нет и не будет без нового решения (Q14).
- Старые приложения без списка методов всё ещё покажут СМС-форму, если её собрали с флагом; сервер ответит 403 `login_method_disabled` — честно, без входа.
- Скрытие вкладок такси/курьера меняет вид главной, принятый 13.10 («Скоро в Юлдаше»): записать решение в `docs/decisions.md` с причиной (Q13/Q5: выключенное не показываем). Приглашение «стать таксистом/курьером заранее» остаётся в профиле, если оно там есть, — не удалять.
- Смена направления сверки кода (говорит водитель) — только тексты; если принимающий найдёт довод против, вернуть направление, но слова «Сошлись — сажай» и «Это та самая машина» не возвращать.
- Лента: заголовок не меняет тело ответа; кеш HTTP посредников заголовок не теряет (ответы с токеном не кешируются).

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан; учтено уточнение Q13 (выключенный способ входа не показываем, сервер отвечает `login_method_disabled`) |
