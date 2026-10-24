# RS-01 — Приложение не обещает того, чего нет

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | нет — тексты, кнопки, закрытые двери; деньги, база, выкладка не меняются |
| Зависит от | — (RS-04 строит на этом переключатели входа) |
| Двигает сценарии приёмки | W1-E2E-05, W1-E2E-06 (`docs/audit-traceability/rideshare-scope.md`) |
| Зона файлов | `backend/app/`: `login_methods.py` (новый), `routers/health.py`, `routers/auth.py`, `routers/requests.py`; `backend/tests/`: `test_login_methods_honest.py` (новый), `test_auth_edges.py`. `android/app/src/main/java/com/yuldash/app/`: `LoginScreen.kt`, `LoginMethods.kt` (новый), `data/ApiClient.kt`, `RideshareBoarding.kt`, `BookingActiveTripScreen.kt`, `RideshareCompletedScreen.kt`, `PayOnlineCard.kt`, `SecondaryScreens.kt` (только `PaymentInfoScreen`), `ModeSwitchHome.kt`; `android/app/build.gradle.kts` (только флаги входа); тесты: `HonestPromisesGuardTest.kt` (новый), `BoardingCodeVisibilityTest.kt`, `BookingActiveTripContentDeepTest.kt`, `Login*Test.kt`. `webapp/src/`: `screens/LoginScreen.tsx`, `api/auth.ts`, `utils/loginFlow.js` + `.d.ts`, `screens/ActiveTripScreen.tsx`, `components/BoardingWait.tsx`, `components/PayTripCard.tsx`, `screens/TripReceiptScreen.tsx`, `components/ModeSwitch.tsx`, `screens/HomeScreen.tsx`, `api/requests.ts`, `legal.ts`; `webapp/tests/`: `honest-promises-test.mjs` (новый), `run.mjs`, `rideshare-local-regression-test.mjs`, `login-flow-test.mjs`. `web/components/legal-content.ts`. Мозг: `docs/decisions.md`, `docs/tasks.md` |
| Источник | `master/part-001.md`: «Что нельзя считать готовым» п. 3–6, чек-лист п. 1, 18–20, 29; решения 24.10: Q5, Q13 (уточнено), Q14 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
- **Вход.** Бабушка из Ургазы видит только способ входа, который правда работает (сейчас — Telegram), без кнопок «скоро». Включит Александр СМС — кнопка «Войти по номеру» появится сама, без нового приложения (RS-04). Первый экран не обещает «не шлём SMS», соглашение не уверяет, что код приходит в СМС.
- **Код посадки.** Сейчас водителю из Сибая написано «Сошлись — сажай», пассажирке — «Это та самая машина». Но сервер код не проверяет (решение 17.10), а «сошлось» может сказать кто угодно. Станет: это **сверка** — водитель называет код, пассажирка сравнивает его со своим экраном вместе с номером и цветом машины. Ни слова «защищает» или «без кода не посадят».
- **Оплата.** После поездки Акъяр → Сибай на сайте видны «Картой» и «СБП», а нажатие даёт «в твоём городе ещё не включена». Онлайн-оплаты в попутке нет (Q5) — останется только «как договорились».
- **Режимы.** Вкладки «Такси» и «Курьер» ведут на «Скоро». Станет: выключен режим — нет вкладки.
- **Лента.** Водитель из Баймака не теряет заявки «по пути» молча: просмотрено не всё — внизу «Показаны не все — уточни село или дату».

## 2. Что уже есть в коде
- `backend/app/routers/auth.py:1022-1036` — `/auth/vk-callback`, `/auth/whatsapp-callback` → 501 «…Войди по номеру телефона» (такого входа на проде нет). 501 закрепляет `tests/test_auth_edges.py:417-418`; `test_rate_limit.py:60`, `test_api.py:105` берут `vk-callback` для проверки лимита (ждут только 429).
- `LoginScreen.kt:762-781` — кнопок VK/WhatsApp нет, СМС-форма за `BuildConfig.SMS_LOGIN_ENABLED`; устаревший комментарий «VK/WhatsApp — «скоро»» — `:713`; «Вход через Telegram скоро» без имени бота — `:429` (сайт `LoginScreen.tsx:87`).
- `android/app/build.gradle.kts:108-123` — `vkAppId` («Пусто → кнопка показывает «скоро»») и `smsLoginEnabled` (по умолчанию `false`), поля `:202`, `:204`; `VK_APP_ID` в коде не используется (поиском не нашёл). Сайт: `api/auth.ts:80-81` (`VITE_SMS_LOGIN_ENABLED`), форма — `LoginScreen.tsx:192`.
- `config.py:40` — `sms_provider = "mock"`; `services.py:1105-1122` — `sms_channel_live()`; `services.py:1286-1291` — прод с mock → 503 на вход по СМС.
- Первый экран: «Не звоним и не шлём SMS» — `LoginScreen.kt:1153`, `LoginScreen.tsx:147`; «Шифруем и не передаём третьим» — `LoginScreen.kt:1158`, `LoginScreen.tsx:154` (политика `legal.ts:84` сама перечисляет Firebase, Яндекс, Sentry, Telegram). Соглашение «код в SMS» — `webapp/src/legal.ts:158`, `web/components/legal-content.ts:158` (дата — `:17`).
- Код посадки: `BookingActiveTripScreen.kt:2988-2993`, `RideshareBoarding.kt:338-340` и `:478-483`, `BoardingWait.tsx:26`, `ActiveTripScreen.tsx:847`. «На сервере не сверяем» — `docs/decisions.md:184-190` (Q4). Код отдаёт `bookings.py:829-833`. Слов «защищает», «без кода не посадят» поиском не нашёл.
- Оплата: `BookingActiveTripScreen.kt:2108-2116`, `RideshareCompletedScreen.kt:361-371` показывают `PayOnlineCard`; она видна до ответа проверки и при её сбое (`PayOnlineCard.kt:118-128`), отказ «…скоро появится. Пока рассчитайтесь…» (`:135-138`, на «вы»). Проверка — `/health` `payments` (`ApiClient.kt:2766-2768`, `health.py:83`): при `sbp_manual` карточка видна, а `wallet.py:84` отвечает 503. Сайт: `PayTripCard.tsx:118-140` — «Картой»/«СБП» без проверки, отказ «в твоём городе…» (`:74-81`), вызов — `TripReceiptScreen.tsx:324`. «Скоро: оплата картой…» — `SecondaryScreens.kt:983` (`PaymentInfoScreen`).
- Режимы: `features.py:43-55`; `ModeSwitchHome.kt:288-300` всегда рисует три режима (такси → `TaxiComingSoonCard`, `InstantOrderScreen.kt:1799`; курьер → «Скоро», `ModeSwitchHome.kt:245`); сайт — `ModeSwitch.tsx:23-25`. Двери уже сторожат `FeatureGateWiringGuardTest.kt`, `taxi-flag-hides-test.mjs`, `test_wave_launch_switches.py`.
- Лента: `requests.py:540-569` — `_SCAN_MAX` = 2000 (`visibility.py:285`), обрыв молча; `/requests/feed` (`:626-649`), `/match/rides` (`:1456-1484`); задача — `docs/tasks.md:234`. `/rides/near` отдаёт `total` (`rides.py:1022-1027`) — не прячет.
## 3. Что сделать
### Сервер (`backend/`)
1. `app/login_methods.py`: `ORDER = ("sms", "telegram", "vk", "whatsapp")`; `available_methods()`: `telegram` — заданы `telegram_bot_token` и `telegram_webhook_secret`; `sms` — `sms_channel_live()` или (`not is_prod` и mock — эмулятор с `dev_code`); `vk`, `whatsapp` — никогда (RS-04). `refuse_disabled(method)` → `HTTPException(403, detail={"ru","ba","code":"login_method_disabled","method"})`. RS-04 добавит сюда переключатели.
2. `routers/health.py`: `GET /config/login-methods` → `{"methods": [...]}`, `Cache-Control: public, max-age=60`, без токена. Не в `/config/features` (`test_wave_launch_switches.py:80` сверяет ответ целиком) и не под `/auth/*` (строгий лимит, `middleware.py:42`).
3. `routers/auth.py`: `vk_callback`, `whatsapp_callback` → `refuse_disabled` вместо 501 (пути остаются отказом и после RS-04). В начале `request_code`, `verify` (после ветки аккаунта стора — не трогать), `tg_start`, `tg_verify`: метод выключен → 403, без `OtpCode` и отправки. 503 — только сбой провайдера при включённом методе.
4. `routers/requests.py`: чанкеры отмечают упор в `_SCAN_MAX`; `requests_feed`, `match_rides` ставят `X-Yuldash-Partial: 1`. Тело не меняется.

### Android (`android/`)
1. `ApiClient.kt`: `loginMethods()` без токена. `LoginMethods.kt`: последний ответ в своём `SharedPreferences`, незнакомые имена отбросить; нет ответа и кеша → `["telegram"]`. Обновлять при открытии входа и возврате в приложение.
2. `LoginScreen.kt`: Telegram — если в списке и имя бота не пустое; СМС — если `sms` в списке; пустой список → «Вход временно недоступен» + «Повторить». `login_method_disabled` → текст сервера и перезапрос списка. Убрать `BuildConfig.SMS_LOGIN_ENABLED` и комментарии `:713`, `:762-763`.
3. `build.gradle.kts`: удалить `smsLoginEnabled`/`SMS_LOGIN_ENABLED` и мёртвые `vkAppId`/`VK_APP_ID`, больше ничего (§10, §12).
4. Первый экран (`:1153`, `:1158`) — тексты раздела 5.
5. Код посадки (`BookingActiveTripScreen.kt:2988-2993`, `RideshareBoarding.kt:338-340, 478-483`): водитель **называет**, пассажир **сверяет** — только так сверка говорит пассажиру что-то о машине. `BoardingCodeRules.kt` не менять.
6. Оплата: `PayOnlineCard` из двух экранов попутки убрать (Q5; вернёт только выключатель RS-08). В `PayOnlineCard.kt` (такси) — видна только после ответа «включено», сбой = скрыто, текст отказа — раздел 5. `PaymentInfoScreen` — строка о договорённости вместо «Скоро…».
7. `ModeSwitchBar`: только включённые (`Feature.Taxi`, `deliveryOn`); один режим — переключателя нет, содержимое поднимается (`animateContentSize`, `CanonMotion`); `mode_last` выключенного → `Pooling`. `ComingSoon` остаётся для старых ссылок и пушей.
8. Лента (`ApiClient.kt:1410, 1445`): узкий вызов с заголовками; `X-Yuldash-Partial` → подпись под списком.
9. Везде `appText(ru, ba)`, `Canon*`, `CanonMotion`, тач-цели ≥ 48dp.

### Сайт (`webapp/`)
1. `api/auth.ts`: `fetchLoginMethods()` (`auth: false`), кеш в `localStorage` под try/catch, фолбэк `["telegram"]`; убрать `SMS_LOGIN_ENABLED`.
2. `LoginScreen.tsx`, `loginFlow.js`/`.d.ts`: как Android п. 2, ошибка `methodDisabled`, без «скоро»; первый экран (`:147`, `:154`).
3. Код посадки — `ActiveTripScreen.tsx:847`, `BoardingWait.tsx:26`.
4. `PayTripCard.tsx`: `kind="booking"` — без «Картой»/«СБП» (остаются «Наличными» и «это отметка, а не платёж»); такси — текст 503 из раздела 5.
5. `ModeSwitch.tsx`, `HomeScreen.tsx` (и «Чем отличается?») — как Android п. 7.
6. `api/requests.ts:143, 204` — `X-Yuldash-Partial` → подпись.
7. Соглашение (`legal.ts:158`, `web/components/legal-content.ts:158`) — текст раздела 5, новая дата. Diff — отдельным пунктом в PR (информировать: обещание сужается).

## 4. База данных
Не меняется.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку».
- `login_method_disabled`: «Этот способ входа сейчас выключен. Выбери другой на экране входа.»
- Нет способов: «Вход временно недоступен. Попробуй чуть позже.» + «Повторить».
- Первый экран: «Никакого спама» — «Не звоним и не шлём рекламу»; «Данные под защитой» — «Номер скрыт, пока поездка не подтверждена».
- Код, водитель: «Назови пассажиру этот код — он сверит его со своим экраном». Пассажир: «Водитель назовёт код — сверь его, номер и цвет машины». Ожидание: «Сверь машину и код с водителем. Когда сядешь, водитель отметит посадку в приложении.»
- Такси: «Онлайн-оплата сейчас недоступна. Рассчитайся, как договорились.»; сайт: «Оплата картой сейчас недоступна. Отдай наличными или переведи водителю.»
- `PaymentInfoScreen`: «Оплата — как договоришься с водителем: наличными или переводом.»
- Лента: «Показаны не все — уточни село или дату».
- Соглашение: «Вход в сервис — по одноразовому коду. Доступные способы входа (например, через Telegram или по SMS) показаны на экране входа. Вы обязуетесь указывать достоверные данные и не передавать доступ к аккаунту третьим лицам.»

## 6. Состояния экрана
- Вход: список грузится — скелетон на месте кнопок; нет сети и кеша — Telegram + обычная ошибка сети; пустой список — карточка с «Повторить».
- Код посадки: «загружается / не удалось / Повторить» (`RideshareBoarding.kt:488-502`) не трогать.
- Квитанция и главная с одним режимом — без пустых мест; длинный башкирский переносится, крупный шрифт, 320dp, тёмная тема — `Canon*`.

## 7. Тесты
- Сервер, `test_login_methods_honest.py`: прод + mock → `["telegram"]`; живой `smsru` → `["sms","telegram"]`; без бота — без `telegram`; `vk`/`whatsapp` не появляются; старые callback → 403 `login_method_disabled` на двух языках; выключенный метод в `request-code`/`verify`/`tg/*` → 403, `OtpCode` не создан, `send_sms` не вызван; аккаунт стора цел (`test_review_login_permissions.py`); 2001+ кандидатов → `X-Yuldash-Partial`. `test_auth_edges.py:417-418` → 403.
- Android: `HonestPromisesGuardTest.kt` (по образцу `SosNoSmsPromiseGuardTest.kt`, только строки кода): нет «Сошлись — сажай», «Это та самая машина», «защищ», «без кода», «Онлайн-оплата скоро», «Скоро: оплата картой», «Не звоним и не шлём SMS», `SMS_LOGIN_ENABLED`, `VK_APP_ID`, `PayOnlineCard(` в экранах попутки. Unit `LoginMethods`; Compose: `LoginFormContent` с `["telegram"]`, `["sms","telegram"]`, `[]`, `ModeSwitchBar` с 1 и 3 режимами. Все `*GuardTest` зелёные.
- Сайт: `honest-promises-test.mjs` — те же запреты по `webapp/src` и `legal-content.ts`, запись в `run.mjs`. `npm run check` + `build`; лендинг — `lint` + `build`.
- Мутации: вернуть 501, «Сошлись — сажай», `PayOnlineCard(` в попутку или `SMS_LOGIN_ENABLED` на сайт — соответствующий тест краснеет.

## 8. Приёмка
Агент другой «семьи» (Q9), по умолчанию «не принято».
- [ ] Сторожа п. 7 зелёные (вывод приложен); запрещённых слов поиск не находит.
- [ ] Настройки прода (mock СМС, бот задан): `/config/login-methods` → только `telegram`; `/auth/vk-callback` → 403 `login_method_disabled`.
- [ ] Эмулятор, RU/BA × светлая/тёмная: на входе одна кнопка Telegram, без «скоро» и «по номеру»; с dev-сервером (mock) — «Войти по номеру» есть, вход по `dev_code` проходит.
- [ ] Подтверждённая бронь: водитель — «Назови пассажиру этот код…», пассажир — «Водитель назовёт код…»; о защите ни слова.
- [ ] Завершённая попутка (эмулятор и сайт): нет «Оплатить онлайн», «Картой», «СБП».
- [ ] Такси и курьер выключены — на главной только попутка, без пустой полосы; включили такси на dev — вкладка появилась без нового APK.
- [ ] База с 2001+ заявками — под «по пути» подпись «Показаны не все…».
- [ ] Соглашение — новый текст и дата, diff в PR отдельно; крупный шрифт и 320dp — без обрезки; BA-черновики в `docs/tasks.md`.
- [ ] `/gotovo`: `:app:assembleDebug` + unit + `*GuardTest`; `ruff check app/ --select F` + `pytest`; `webapp` `check` + `build`; `web` `lint` + `build`.
- Двигает W1-E2E-05, -06. Остаётся: BA носителем, RS-04, W1-E2E-07 (Q11).

## 9. Риски и границы
- Не входит: включение СМС/VK/WhatsApp и переключатели — RS-04; «оплата картой» — RS-08; серверная проверка кода посадки — нет (Q14).
- Скрытие вкладок меняет главную, принятую 13.10 («Скоро в Юлдаше»): записать в `docs/decisions.md` (выключенное не показываем). Приглашение «стать таксистом/курьером заранее» в профиле не удалять.
- Направление сверки (говорит водитель) — только тексты; найдёт принимающий довод против — вернуть направление, но не «Сошлись — сажай» и «Это та самая машина».

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан с уточнением Q13: выключенный способ входа не показываем, сервер отвечает `login_method_disabled` |
