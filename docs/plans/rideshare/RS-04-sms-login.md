# RS-04 — Все способы входа и выключатели владельца

| | |
|---|---|
| Статус | не начато |
| Ждёт «да» Александра | да — миграции рабочей базы, политика данных, регистрация VK ID, платные СМС и WhatsApp, договоры, ключи (только `.env` сервера), включение СМС-уведомлений безопасности |
| Зависит от | RS-01 (`herr(code=)`, `login_methods.py`, `/config/login-methods`) |
| Не параллельно с | по общим файлам индекса: после RS-01, RS-02, RS-03; RS-05–RS-08, RS-10, RS-13, RS-14 — после RS-04 |
| Деление на PR | `[RS-04А1]` выключатели владельца, экран «Выключатели», `/config/login-methods` из них → `[RS-04А2]` защита СМС, один аккаунт, роль админа → `[RS-04Б]` VK ID → `[RS-04В]` WhatsApp (после выбора провайдера) → `[RS-04Г]` профиль, смена номера. Внутри части — сервер → сайт → Android |
| Проверка `yuldash-security-privacy` | да — каждая часть до слияния, вердикт в PR |
| Двигает сценарии приёмки | W1-E2E-05; W1-E2E-07 (живые СМС — по Q11 пока не проходим) |
| Зона файлов | `backend/app/`: новые `owner_switches.py`, `vk_id.py`, `whatsapp_otp.py`, `routers/admin_switches.py`; `login_methods.py`, `routers/{auth,health,__init__}.py`, `models.py`, `services.py`, `security.py`, `config.py`, `account.py`, `cleanup.py`; `alembic/versions/`; `.env.example` (имена); `tests/`. Android `…/yuldash/app/`: новые `AdminSwitchesScreen.kt`, `LoginMethodsProfileScreen.kt`; `LoginScreen.kt`, `LoginMethods.kt`, `data/ApiClient.kt`, `AppNavAdmin.kt`, `MainActivity.kt` (`enum Screen`), `SecondaryScreens.kt` (`AdminCabinetScreen`), `ProfileScreen.kt`, `YuldashApp.kt`/`AppNav*.kt`, `AndroidManifest.xml`; тесты. `webapp/src/`: новые `screens/{AdminSwitches,LoginVkReturn,LoginMethods}Screen.tsx`; `screens/{Login,Profile,AdminCabinet}Screen.tsx`, `api/auth.ts`, `utils/loginFlow.js` + `.d.ts`, `App.tsx`; `webapp/tests/`. Мозг: `docs/{architecture,decisions,tasks,lessons}.md` |
| Источник | `master/part-001.md` п. 1, 26; Q13, Q9, Q11 (24.10), Q2, Q11 (17.10); D1–D5, D8 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
- **Было → стало.** Тётя Гульназ из Акъяра не пользуется Telegram — войти ей нечем. Станет: первым на экране входа — «Номер телефона»; пришла СМС — телефон сам подставил код. Не пришла — через минуту «Отправить ещё раз» или «Прислать в WhatsApp». Telegram и VK — ниже.
- **Один человек — один аккаунт.** Рустам вчера входил через Telegram, сегодня по СМС с тем же номером — тот же аккаунт с поездками и отзывами.
- **Выключатели у Александра.** Экран «Выключатели»: СМС, Telegram, VK, WhatsApp. Без договора с sms.ru СМС не включить — переключатель серый и говорит, чего не хватает; перед выключением СМС — сколько людей не смогут войти. Выключенное люди не видят; видно, кто и когда переключал.
- **Защита.** Код не подобрать, номер не завалить СМС, админом по СМС не войти.

## 2. Что уже есть в коде
- `routers/auth.py:362-430` — `request-code`: замок номера (`:132-144`), ≤ 3 кода/мин (`:387-395`), код не хранится до отправки (`:418-426`). `:433-561` — `verify`: ≤ 5 попыток на код (`:508`), ≤ 15 на номер (`:502`), ≤ 100/сутки (`:44-60`), «номер сменил хозяина» (`:542-545`), аккаунт стора (`:451-480`).
- **Уборка:** вебхук Telegram стирает чистые строки `OtpCode` через ~6 мин (`auth.py:694-696`), с ошибками — через 25 ч (`:703-705`); ночная — старше суток (`cleanup.py:30`, `:152`). Часовой и суточный лимит сейчас не посчитать.
- **Админ по номеру:** `_maybe_promote_admin` (`auth.py:147-184`) повышает по `telegram_id` владельца и по `ADMIN_PHONES` (`:159`); вход по СМС зовёт его (`_complete_login` `:323-348`, вызов `:561`).
- Telegram (`auth.py:884-994`): номер обязателен (`:989-993`), поиск по `telegram_id`, потом по номеру (`:940-968`); номер у аккаунта с другим Telegram — застревание (`:958`, `:986-993`). `TgAuth.code`, `OtpCode.code` — открытым текстом (`models.py:195`, `:140-146`); `vk_id` удалён (`models.py:70`).
- `services.py:1247-1292` — `send_sms` (`"Yuldash: kod {code}"`, `:1255`; вне прода при сбое код — в журнал, `:1265`); `sms_channel_live()` — `:1105-1122`. `send_text` (`:1134`) — тем же каналом SOS, близкие, «новое устройство» (`antifraud.py:192-198`, `sos_escalate.py:102-105`, `routers/family.py:193`): живой sms.ru включит всё; «SMS пока нет» — `decisions.md:178`, `:145`.
- `config.py:39-46` — настройки СМС, `/auth/*` 20/мин на IP — `:914`; `security.py:68-93` — иностранные номера проходят; сайт `X-Device-Id` не шлёт.
- Журнал админа — строка лога (`logs.py:67-85`); удаление аккаунта обнуляет ссылки (`account.py:341`), сторож `test_account_deletion.py:26-34`; бессрочное — `cleanup.py:74`. Смены номера нет (`auth.py:1305`).
- Android: `LoginScreen.kt:534-564` — 429/503 при проверке кода → общий текст, таймера нет; `Color.White` — `:970`. Админка: `SecondaryScreens.kt:1526`, `AppNavAdmin.kt:23-46`, сайт `AdminCabinetScreen.tsx:98`.
- VK ID, WhatsApp-код, привязку — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
**А1. Выключатели владельца (D1).**
1. `owner_switches.py` — реестр: значения, умолчание (прод / вне прода), блокеры, правило группы. Ключи `login.sms|telegram|vk|whatsapp` (RS-08 добавит `rideshare_card_payments`). Умолчания: `telegram` — вкл.; `sms` — выкл. на проде, **вкл. вне прода** (17 тестов через `request-code`); `vk`, `whatsapp` — выкл.
2. `appsetting` (`key` PK, `value`, `updated_at`, `updated_by` nullable SET NULL) и `appsettingchange` (`key`, `old_value`, `new_value`, `admin_id` nullable SET NULL, `created_at`, `note`); строки ключей — из миграции, начальное значение — `NULL` = «по умолчанию из реестра».
3. Блокеры — имена недостающих переменных: `SMS_PROVIDER=smsru` + `SMS_RU_API_ID` (или smsdar); `TELEGRAM_BOT_TOKEN` + `TELEGRAM_WEBHOOK_SECRET`; `VK_ID_CLIENT_ID`, `VK_ID_REDIRECT_URIS`; `WHATSAPP_PROVIDER` + ключи.
4. Группа `login.*`: нельзя выключить последний действующий способ (`switch_last_login_method`) и `login.telegram` (вход админа, А2 п. 8); выключение `sms`, когда у `N > 0` аккаунтов не останется способа (нет `telegram_id`, `vk_id`, WhatsApp выкл.), — только с `confirm: true`, иначе 409 `switch_needs_confirm` + `affected`.
5. Запись — `SELECT … FOR UPDATE` по ключам группы в порядке ключей; то же значение — 200 без строки; иначе `appsettingchange` + `admin_action`. Чтение: `owner_switches.get(key, fresh=False)` — с кешем ≤ 10 с; при `fresh=True` — прямо из базы (нужно RS-08 для денег).
6. `routers/admin_switches.py` (только админ): `GET /admin/switches`, `POST /admin/switches/{key}` `{value, confirm?, note?}` → `{stored, effective, blockers, updated_at, updated_by_name, history[50]}`.
7. `available_methods()` (RS-01) = `login.*` с `effective`; видно людям ≤ 40 с.

**А2. СМС, один аккаунт, роль админа.**
1. Номер — только `+79\d{9}`, иначе 422 `phone_not_supported` (иностранные платные номера разоряют счёт).
2. Строки выдачи живут ≥ 24 ч: уборку `OtpCode`/`TgAuth` из вебхука (`auth.py:690-705`) убрать, остаётся ночная (`cleanup.py:152`).
3. Лимиты выдачи (под замком номера, все каналы вместе): номер — раз в 60 с, ≤ 5/час, ≤ 10/сутки; IP — ≤ 40/час (село за общим IP оператора — опора на лимит номера и потолок дня); устройство (`X-Device-Id`, Android) — ≤ 5/час; сервис — `SMS_DAILY_CAP`/сутки (300) → 503 `sms_daily_cap` + одно сообщение дежурному. Иначе → 429 `otp_rate_limited` + `retry_after_sec`.
4. `OtpCode` + `channel`, `ip_hash`, `device_hash` (HMAC с секретом сервера); `OtpCode.code`, `TgAuth.code` — HMAC, сверка `compare_digest` (живые коды при выкладке пропадут — допустимо).
5. Ответ один для любого номера: `{"sent": true, "resend_after_sec": 60, "expires_in_sec": 300}`; тело — `{phone, lang, channel}`. СМС — на языке экрана, ≤ 70 знаков, последней строкой `@app.yulbash.ru #<код>`.
6. Журнал: код — никогда, номер — `mask_phone`; код в лог при сбое — только `env == "dev"`.
7. `send_text` — отдельный `SMS_NOTIFY_ENABLED` (по умолчанию `false`), им же `sms_will_reach`: вход по СМС молча не включает СМС SOS и уведомлений (Q2, Q11).
8. **Роль админа (D8):** СМС, VK, WhatsApp → `_complete_login(promote_admin=False)`; аккаунт с ролью `admin` так не входит — 403 `admin_login_telegram_only`, только в `verify`, после успешной сверки и погашения кода; ответ `request-code` одинаков для любого номера (иначе можно узнать номера админов). Повышение до админа — только через Telegram: по `admin_telegram_chat_id` или по `ADMIN_PHONES`, если номер пришёл из Telegram-контакта (`contact.user_id == from.id`).
9. **Один аккаунт:** ключ — подтверждённый номер (`auth.py:989-993`). СМС/WhatsApp → по номеру. Telegram/VK → по своему id, иначе по номеру; слот пуст — привязать; занят другим id → 409 `method_linked_other`, второго аккаунта нет (вместо застревания `auth.py:958`); по id нашли, а номер у другого → 409 `phone_taken_other_account`, слияние — поддержка. Текст `method_linked_other` — из действующих способов, нет их — «напиши в поддержку».
10. Бот при выключенном Telegram: «Вход через Telegram сейчас выключен», без кода.

**Б. VK ID** (`vk_id.py`, `User.vk_id` уникальный): PKCE (S256), `code_verifier` не покидает устройство; `client_id`, `scope`, `redirect_uri` — в `/config/login-methods`. `POST /auth/vk/finish` `{code, code_verifier, vk_device_id, redirect_uri}`: `redirect_uri` — только из `VK_ID_REDIRECT_URIS`, наш `X-Device-Id` — из заголовка; код у VK меняет и профиль читает сервер (`vk_id` клиента не принимаем — старая дыра, `auth.py:1022-1026`). Берём `vk_id`, имя, подтверждённый номер; токен не храним; нет номера → 403 `phone_required_vk`; дальше — А2 п. 8–9.

**В. WhatsApp** (`whatsapp_otp.py`): тот же код другим каналом — общие `OtpCode`, лимиты, сверка. Только официальный WhatsApp Business API (шаблон «authentication») у провайдера с договором в России; личный номер и «серые» рассылки — нельзя. Адаптер `send_code(phone, code, lang) -> bool` под выбор Александра; доставка нестабильна — только запасной.

**Г. Профиль и смена номера:** `GET /me/login-methods` (номер маской, Telegram, VK); привязка — поток Telegram/VK с токеном сессии; `unlink` — если остаётся другой действующий способ. `refreshtoken.auth_time` — момент проверки кода; продление (`rotate_refresh`, `security.py:183`) его переносит, но не обновляет. Смена номера `POST /me/phone/request-code` → `/confirm`: `auth_time` ≤ 10 мин, номер свободен, раз в 7 дней (`user.phone_changed_at`); потом `revoke_all_refresh` и пуш.

### Сайт (`webapp/`)
1. `LoginScreen.tsx` + `loginFlow.js`: СМС доступен — поле номера первым; таймер из `resend_after_sec`, «Прислать в WhatsApp», «Изменить номер»; ошибки по `code`; `autocomplete="one-time-code"`, `inputmode="numeric"` + WebOTP, где есть.
2. VK — `LoginVkReturnScreen.tsx` (`app.yulbash.ru/login/vk`), `code_verifier`/`state` — `sessionStorage`.
3. `AdminSwitchesScreen.tsx` (раздел «Способы входа», RS-08 добавит «Оплата») + плитка + маршрут; `LoginMethodsScreen.tsx` + пункт в `ProfileScreen.tsx` (Г).

### Android (`android/`)
1. `LoginScreen.kt`: как сайт п. 1, ошибки по `detailCode`; конец таймера — в `rememberSaveable`; `Color.White` (`:970`) → `CanonOnFilled`.
2. Автоподстановка без `READ_SMS`: `contentType = ContentType.SmsOtpCode` (без библиотек; проверить в BOM 2026.06.00). SMS Retriever не берём — нужен хеш подписи в СМС, разный у отладочной и выпускной сборки. Запасной — SMS User Consent API: новая зависимость, только с обоснованием (§10).
3. VK: браузер (`Intent.ACTION_VIEW`), возврат — схема из кабинета VK ID в `AndroidManifest.xml`; сверить `state`; `code_verifier`/`state` — `rememberSaveable`.
4. `AdminSwitchesScreen.kt` (⭐ п. 4: `Screen.AdminSwitches`, `AppNavAdmin.kt`, плитка, `architecture.md`): состояние («Включён» / «Выключен» / «Не настроен: не хватает …»), переключатель (серый при блокере), «вкл. 24.10, 14:05 · Александр»; платное и выключение СМС — с подтверждением; журнал.
5. `LoginMethodsProfileScreen.kt` (⭐ п. 4: `Screen.LoginMethods`, ветка `YuldashApp.kt`/`AppNav*.kt`, пункт в `ProfileScreen.kt`, `architecture.md`).

## 4. База данных
Имена `<префикс>_<суть>_<дата>`, префикс — по индексу («Порядок миграций», 3–4) и `alembic heads`; одна голова; up → down → up на SQLite и PostgreSQL.
- А1 `…_owner_switches_…`: `appsetting`, `appsettingchange`, строки `login.*`. А2 `…_otp_issue_fields_…`: `otpcode` + `channel` (`'sms'`), `ip_hash`, `device_hash`, индекс `created_at`. Б `…_user_vk_id_…`: `user.vk_id`. Г `…_user_auth_time_…`: `refreshtoken.auth_time`, `user.phone_changed_at`.
- **Срок хранения и удаление аккаунта:** `appsetting`, `appsettingchange` — `KEEP_FOREVER` («настройки и журнал решений владельца»), `updated_by`/`admin_id` → `NULL` при удалении аккаунта (`account.py`); поля `otpcode` — ночная чистка (`cleanup.py:152`); `vk_id`, `phone_changed_at` — уходят с аккаунтом; `auth_time` — с токеном.
- Старые данные не меняются. **Ждёт «да»**: применение.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку»; СМС по-башкирски тоже ≤ 70 знаков.
- СМС: «Юлдаш: код 123456. Никому его не говори.» + `@app.yulbash.ru #123456`.
- Экран: «Номер телефона», «Получить код», «Отправить ещё раз через 0:59», «Прислать в WhatsApp», «Войти через VK».
- `phone_not_supported`: «Нужен российский мобильный номер: +7 9…»; `otp_rate_limited`: «Слишком часто. Новый код — через {мин} мин.»; `sms_daily_cap`: «СМС на сегодня закончились. Войди через Telegram или попробуй завтра.»
- `method_linked_other`: «Этот номер уже связан с другим аккаунтом {Telegram|VK}.» + «Войди {по СМС / через {способ}} и смени его в профиле.» или «Напиши в поддержку — поможем.»; `phone_taken_other_account`: «Этот номер уже у другого аккаунта. Напиши в поддержку — разберёмся.»; `phone_required_vk`: «В VK нет подтверждённого номера. Привяжи номер в VK или войди по СМС.»; `admin_login_telegram_only`: «Администратор входит только через Telegram.»
- «Выключатели»: «Способы входа», «Включён», «Выключен», «Не настроен: не хватает {имена}», «Каждый код стоит денег. Включить?», «Без СМС не смогут войти {N} человек. Выключить?», «Нельзя выключить последний способ.», «Telegram нельзя выключить: через него входит администратор.»
- Профиль: «Привязать», «Отвязать», «Сменить номер», «Нельзя отвязать: другого способа не останется.»

## 6. Состояния экрана
- Вход: «Отправляем код…», таймер; нет сети — «Нет связи. Проверь интернет и повтори»; 503 — «СМС сейчас не уходят» + другой способ; поворот и смерть процесса не теряют шаг. «Выключатели»: скелетон; ошибка — «Не удалось загрузить» + «Повторить»; отказ — переключатель назад с текстом сервера.
- Башкирский, крупный шрифт, 320dp, тёмная тема — `Canon*`; тач-цели ≥ 48dp, подписи иконок на двух языках.

## 7. Тесты
- `test_owner_switches.py`: умолчания (прод — Telegram, dev — и СМС); блокер → 409 с именами; последний способ или Telegram → 409; СМС с `affected > 0` без `confirm` → 409; не-админ → 403; то же значение — без строки; удаление админа → `NULL`; вход и `/config/login-methods` слушаются выключателя.
- `test_sms_login_limits.py`: иностранный номер → 422, `send_sms` не вызван; 60 с; **6-я выдача через 10 мин → 429**; 11-я за сутки → 429; 41-я с IP за час → 429; потолок дня → 503 + одно сообщение дежурному; ответ одинаков для любого номера; в базе HMAC (и у Telegram); `caplog` прода — без кода и номера; СМС ≤ 70 на двух языках; `send_text` молчит при `SMS_NOTIFY_ENABLED=false`; 10 параллельных запросов → не больше лимита.
- `test_login_linking.py`: Telegram → СМС тем же номером = один `user.id`; номер у аккаунта с другим Telegram → 409, второго аккаунта нет; без действующих способов — «напиши в поддержку»; **СМС с номером из `ADMIN_PHONES` → не админ; существующий админ по СМС → 403 `admin_login_telegram_only`.**
- `test_vk_login.py` (VK подменён): `vk_id` клиента, чужой `redirect_uri`, неверный `code_verifier` → отказ; без номера → 403. `test_whatsapp_login.py`: лимиты общие с СМС; сбой провайдера → 502, кода нет. `test_phone_change.py`: `auth_time` > 10 мин → 403 и после продления; номер занят → 409; другие сессии вышли.
- Android: unit — таймер, `detailCode`, PKCE; Compose — вход и «Выключатели». Сайт — те же (+ `run.mjs`), `check` + `build`. Миграции — `test_migrations_*`.
- Мутации: вернуть уборку в вебхук, убрать проверку `+79`, хранить код открыто, вернуть `promote_admin` для СМС — тест краснеет.

## 8. Приёмка
Агент другой «семьи» (Q9) + вердикт `yuldash-security-privacy` по каждой части.
- [ ] Прод-настройки без ключей: СМС, VK, WhatsApp — «Не настроен», не включить; люди видят только Telegram; Telegram не выключить.
- [ ] Dev: СМС включён по умолчанию; выключили — поле номера исчезло на эмуляторе без нового APK (≤ 1 мин), включили — вернулось; журнал — обе смены с именем.
- [ ] Эмулятор, RU/BA × светлая/тёмная: таймер, «Изменить номер», ошибки 422/429/503 словами сервера, шаг переживает поворот и смерть процесса.
- [ ] Telegram, потом СМС тем же номером — тот же `user.id`; номер из `ADMIN_PHONES` по СМС — не админ.
- [ ] 6-я попытка на код и 16-я на номер — 429; 6-я выдача за час — 429; иностранный номер — 422; в журнале нет кода и номера; выключение СМС — предупреждение с числом людей.
- [ ] Живые СМС/VK/WhatsApp — после договоров и «да» (W1-E2E-07, Q11); до того — mock.
- [ ] `/gotovo`; миграции up → down → up на копии.
- **Мозг:** `architecture.md` — два экрана; `decisions.md` — D1, D8, лимиты СМС; `tasks.md` — BA, что ждёт «да»; `lessons.md` — «уборка не стирает то, по чему считают лимиты».

## 9. Риски и границы
- Деньги: СМС платные, цену не знаю (тариф sms.ru); день ограничен `SMS_DAILY_CAP`.
- Выключение СМС отрезает людей без Telegram и VK — экран показывает их число.
- Telegram выключить нельзя, пока действует D8 (`docs/plans/README.md`): сознательное отступление от «каждый способ — переключателем». Вариант «выключатель прячет Telegram от людей, а админу вход остаётся» — вопрос Александру, до ответа действует замок.
- VK ID может требовать организацию (`LoginScreen.kt:762` — про ИНН) — проверить до Б; WhatsApp — договор и шаблон, без провайдера В не делается.
- Политика (`legal.ts:84`, `legal-content.ts:85`) до включения должна назвать sms.ru, VK, провайдера WhatsApp — «да» Александра.
- Аккаунт стора: без СМС — проверяющим Telegram. Не входит: слияние аккаунтов, почта.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан (Q13 уточнено) |
| 24.10.2026 | не начато | — | — | правки по приёмке (D1, D8, лимиты, деление) |
