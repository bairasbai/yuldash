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
| Зона файлов | `backend/app/`: новые `owner_switches.py`, `vk_id.py`, `whatsapp_otp.py`, `routers/admin_switches.py`; `login_methods.py`, `routers/auth.py`, `routers/health.py`, `routers/__init__.py`, `models.py`, `services.py`, `security.py` (`auth_time`), `config.py`, `account.py`, `cleanup.py`; `alembic/versions/`; `.env.example` (имена); `tests/` (раздел 7). Android `…/yuldash/app/`: новые `AdminSwitchesScreen.kt`, `LoginMethodsProfileScreen.kt`; `LoginScreen.kt`, `LoginMethods.kt`, `data/ApiClient.kt`, `AppNavAdmin.kt`, `MainActivity.kt` (`enum Screen`), `SecondaryScreens.kt` (`AdminCabinetScreen`), `ProfileScreen.kt`, ветка в `YuldashApp.kt`/`AppNav*.kt`; `AndroidManifest.xml`; тесты. `webapp/src/`: новые `screens/AdminSwitchesScreen.tsx`, `LoginVkReturnScreen.tsx`, `LoginMethodsScreen.tsx`; `screens/LoginScreen.tsx`, `ProfileScreen.tsx`, `AdminCabinetScreen.tsx`, `api/auth.ts`, `utils/loginFlow.js` + `.d.ts`, `App.tsx`; `webapp/tests/`. Мозг: `docs/architecture.md`, `decisions.md`, `tasks.md`, `lessons.md` |
| Источник | `master/part-001.md` п. 1, 26, «Что нельзя…» п. 4; решения 24.10 Q13, Q9, Q11; 17.10 Q2, Q11; координатор D1–D5, D8 |
| Сверено с кодом | `main` `0ef03f4`, 24.10.2026 |

## 1. Что получит человек
- **Было → стало.** Тётя Гульназ из Акъяра не пользуется Telegram — войти ей нечем. Станет: первым на экране входа — «Номер телефона»; пришла СМС — телефон сам подставил код. Не пришла — через минуту «Отправить ещё раз» или «Прислать в WhatsApp». Telegram и VK — ниже.
- **Один человек — один аккаунт.** Рустам вчера входил через Telegram, сегодня по СМС с тем же номером — тот же аккаунт с поездками и отзывами.
- **Выключатели у Александра.** Экран «Выключатели»: СМС, Telegram, VK, WhatsApp. Без договора с sms.ru СМС не включить — переключатель серый и говорит, чего не хватает. Перед выключением СМС экран скажет, сколько людей не смогут войти. Выключенное люди не видят; видно, кто и когда переключал.
- **Защита.** Код не подобрать, номер не завалить СМС, права администратора по СМС не получить.

## 2. Что уже есть в коде
- `routers/auth.py:362-430` — `request-code`: замок номера (`:132-144`), ≤ 3 кода в минуту (`:387-395`), код не сохраняется до отправки (`:418-426`), `dev_code` только `env == "dev"` (`:428-429`). `:433-561` — `verify`: ≤ 5 попыток на код (`:508`), ≤ 15 на номер (`:502`), ≤ 100/сутки, `ADMIN_PHONES` ≤ 20 (`:44-60`), атомарное погашение (`:514-535`), «номер сменил хозяина» (`:542-545`), аккаунт стора (`:451-480`).
- **Уборка:** вебхук Telegram стирает чистые строки `OtpCode` через ~6 мин (`auth.py:694-696`), с ошибками — через 25 ч (`:703-705`); ночная — `otpcode` старше суток (`cleanup.py:30`, `:152`). Часовой и суточный лимит по `OtpCode` сейчас не посчитать.
- **Админ по номеру:** `_maybe_promote_admin` (`auth.py:147-184`) повышает по `telegram_id` владельца и по `ADMIN_PHONES` (`:159`); вход по СМС зовёт его (`_complete_login` `:323-348`, вызов `:561`).
- Telegram (`auth.py:884-994`): номер обязателен (`:989-993`), поиск по `telegram_id`, затем по номеру (`:940-968`); номер у аккаунта с другим Telegram — застревание на «поделись номером» (`:958`, `:986-993`). `TgAuth.code` и `OtpCode.code` — открытым текстом (`models.py:195`, `:140-146`); `vk_id` удалён миграцией 0002 (`models.py:70`).
- `services.py:1247-1292` — `send_sms`, текст `"Yuldash: kod {code}"` (`:1255`); вне прода при сбое код — в журнал (`:1265`, `:1270`); `sms_channel_live()` — `:1105-1122`. `send_text` (`:1134`) — тем же каналом SOS, близкие, «новое устройство» (`antifraud.py:192-198`, `sos_escalate.py:102-105`, `routers/safety.py:459`, `routers/family.py:193`): живой sms.ru включит их все; «SMS пока нет» — `docs/decisions.md:178` (Q2), `:145` (Q11).
- `config.py:39-46` — настройки СМС; проверка ключей — `:1092-1097`; `/auth/*` 20/мин на IP — `:914`. `security.py:68-93` — `normalize_phone` пропускает иностранные номера. Сайт `X-Device-Id` не шлёт (поиском не нашёл).
- Образец переключателя — города такси (`routers/taxi.py:891-910`); журнал админа — строка лога (`logs.py:67-85`). Удаление аккаунта обнуляет ссылки (`account.py:341` и др.), сторож `tests/test_account_deletion.py:26-34`; бессрочное — `cleanup.KEEP_FOREVER` (`cleanup.py:74`). Смены номера нет (`auth.py:1305`).
- Android: `LoginScreen.kt:534-564` — при проверке кода 429/503 → общий текст, таймера нет; `Color.White` — `:970`. Админка: `SecondaryScreens.kt:1526`, `AppNavAdmin.kt:23-46`, `MainActivity.kt:534-597`; сайт — `AdminCabinetScreen.tsx:98`, `App.tsx:985`.
- VK ID, WhatsApp-код, привязку способов — не нашёл.

## 3. Что сделать
### Сервер (`backend/`)
**А1. Выключатели владельца (D1).**
1. `owner_switches.py` — реестр ключей: значения, по умолчанию (прод / вне прода), блокеры, правило группы. Ключи `login.sms|telegram|vk|whatsapp` (RS-08 добавит `rideshare_card_payments`). По умолчанию: `telegram` — вкл.; `sms` — выкл. на проде, **вкл. вне прода** (17 тестов ходят через `request-code`); `vk`, `whatsapp` — выкл.
2. Таблицы `appsetting` (`key` PK, `value`, `updated_at`, `updated_by` nullable, ON DELETE SET NULL) и `appsettingchange` (`key`, `old_value`, `new_value`, `admin_id` nullable SET NULL, `created_at`, `note`); строки ключей заводит миграция, `value = NULL` = «по умолчанию».
3. Блокеры — имена недостающих переменных: `sms` — `SMS_PROVIDER=smsru` + `SMS_RU_API_ID` (или smsdar); `telegram` — `TELEGRAM_BOT_TOKEN` + `TELEGRAM_WEBHOOK_SECRET`; `vk` — `VK_ID_CLIENT_ID`, `VK_ID_REDIRECT_URIS`; `whatsapp` — `WHATSAPP_PROVIDER` + ключи.
4. Группа `login.*`: нельзя выключить последний действующий способ (`switch_last_login_method`) и `login.telegram` (через него входит админ, А2 п. 8); выключение `sms`, если у `N > 0` аккаунтов не останется способа (нет `telegram_id`, `vk_id`, WhatsApp не действует), — только с `confirm: true`, иначе 409 `switch_needs_confirm` + `affected`.
5. Запись — `SELECT … FOR UPDATE` по ключам группы в порядке ключей; то же значение — 200 без строки; иначе `appsettingchange` + `admin_action`. Чтение — кеш ≤ 10 с.
6. `routers/admin_switches.py`, только `role == admin`: `GET /admin/switches`, `POST /admin/switches/{key}` `{value, confirm?, note?}` → `{stored, effective, blockers, updated_at, updated_by_name, history[50]}`.
7. `available_methods()` (RS-01) = `login.*` с `effective`. Людям видно ≤ 40 с (кеш 10 с + `max-age=30`) и сразу при открытии экрана входа.

**А2. СМС, один аккаунт, роль админа.**
1. Номер — только `+79\d{9}`, иначе 422 `phone_not_supported` (иностранные платные номера — главный способ разорить счёт).
2. Строки выдачи живут ≥ 24 ч: из вебхука убрать уборку `OtpCode` и `TgAuth` (`auth.py:690-705`), остаётся ночная `cleanup.py:152`.
3. Лимиты выдачи (под замком номера, все каналы вместе): номер — раз в 60 с, ≤ 5/час, ≤ 10/сутки; IP — ≤ 40/час (село за общим IP оператора; опора — лимит номера и потолок дня); устройство (`X-Device-Id`, только Android) — ≤ 5/час; сервис — `SMS_DAILY_CAP`/сутки (по умолчанию 300) → 503 `sms_daily_cap` + одно сообщение дежурному. Превышение → 429 `otp_rate_limited` + `retry_after_sec`.
4. `OtpCode` + `channel`, `ip_hash`, `device_hash` (HMAC с секретом сервера); `OtpCode.code` и `TgAuth.code` — HMAC, сверка `compare_digest` (живые коды при выкладке пропадут — 5 мин, допустимо).
5. Ответ один для любого номера: `{"sent": true, "resend_after_sec": 60, "expires_in_sec": 300}`. Тело — `{phone, lang, channel: "sms"|"whatsapp"}`. СМС — на языке экрана, ≤ 70 знаков кириллицы, последней строкой `@app.yulbash.ru #<код>`; отправитель `SMS_FROM`.
6. Журнал: код — никогда, номер — `mask_phone`; код в лог при сбое — только `env == "dev"`.
7. `send_text` — отдельный `SMS_NOTIFY_ENABLED` (по умолчанию `false`), им же `sms_will_reach`: вход по СМС молча не включает СМС SOS и уведомлений (Q2, Q11).
8. **Роль админа (D8):** СМС, VK, WhatsApp → `_complete_login(promote_admin=False)`; аккаунт с ролью `admin` так не входит — 403 `admin_login_telegram_only`. Повышение — только входом через Telegram.
9. **Один аккаунт:** ключ — подтверждённый номер (`auth.py:989-993`). СМС/WhatsApp → по номеру. Telegram/VK → по своему id, иначе по номеру провайдера; слот пуст — привязать; занят другим id → 409 `method_linked_other` без второго аккаунта (и вместо застревания `auth.py:958, 986-993`); нашли по id, а номер у другого аккаунта → 409 `phone_taken_other_account`, слияние — поддержка. Текст `method_linked_other` сервер собирает из действующих способов; нет ни одного — «напиши в поддержку».
10. Бот при выключенном Telegram отвечает «Вход через Telegram сейчас выключен» без кода.

**Б. VK ID** (`vk_id.py`, `User.vk_id` уникальный): PKCE (S256) на клиенте, `code_verifier` не покидает устройство; `client_id`, `scope`, `redirect_uri` — в `/config/login-methods`. `POST /auth/vk/finish` `{code, code_verifier, vk_device_id, redirect_uri}`: `redirect_uri` — только из `VK_ID_REDIRECT_URIS`; наш `X-Device-Id` — только из заголовка; код у VK меняет сервер и сам читает профиль (по документации VK ID). `vk_id` от клиента не принимаем (старая дыра, `auth.py:1022-1026`). Берём `vk_id`, имя (`_safe_display_name`), подтверждённый номер; токен VK не храним; нет номера → 403 `phone_required_vk`. Дальше — А2 п. 8–9.

**В. WhatsApp** (`whatsapp_otp.py`): тот же код другим каналом — общие `OtpCode`, лимиты, сверка. Только официальный WhatsApp Business API (шаблон «authentication») у провайдера с договором в России; личный номер и «серые» рассылки — нельзя. Адаптер `send_code(phone, code, lang) -> bool` + реализация под выбор Александра. Доставка в России нестабильна — только запасной.

**Г. Профиль и смена номера:** `GET /me/login-methods` (номер маской, Telegram, VK); привязка — поток Telegram/VK с токеном сессии; `unlink` — если остаётся другой действующий способ. `refreshtoken.auth_time` — момент проверки кода, переносится при продлении (`rotate_refresh`, `security.py:183`) и им не обновляется. Смена номера: `POST /me/phone/request-code` → `/me/phone/confirm` — `auth_time` ≤ 10 мин, новый свободный номер, раз в 7 дней (`user.phone_changed_at`); потом `revoke_all_refresh` и пуш.

### Сайт (`webapp/`)
1. `LoginScreen.tsx` + `loginFlow.js`: СМС доступен — поле номера первым; таймер из `resend_after_sec`, «Прислать в WhatsApp», «Изменить номер»; ошибки по `code`; `autocomplete="one-time-code"`, `inputmode="numeric"` + WebOTP, где есть.
2. VK — `LoginVkReturnScreen.tsx` (`https://app.yulbash.ru/login/vk`), `code_verifier`/`state` — `sessionStorage`.
3. `AdminSwitchesScreen.tsx` (раздел «Способы входа»; RS-08 добавит «Оплата») + плитка + маршрут в `App.tsx`; `LoginMethodsScreen.tsx` + пункт в `ProfileScreen.tsx` (Г).

### Android (`android/`)
1. `LoginScreen.kt`: как сайт п. 1, ошибки по `detailCode`; конец таймера — в `rememberSaveable`; `Color.White` (`:970`) → `CanonOnFilled`.
2. Автоподстановка без `READ_SMS`: `contentType = ContentType.SmsOtpCode` у поля кода (без библиотек; проверить в BOM 2026.06.00). SMS Retriever не берём — нужен хеш подписи в тексте СМС, у отладочной и выпускной сборки он разный. Запасной — SMS User Consent API (`play-services-auth-api-phone`): новая зависимость, только с обоснованием (§10).
3. VK: браузер (`Intent.ACTION_VIEW`), возврат — схема из кабинета VK ID в `AndroidManifest.xml`; сверить `state`; `code_verifier`/`state` — `rememberSaveable`.
4. `AdminSwitchesScreen.kt` (⭐ п. 4: `Screen.AdminSwitches`, `AppNavAdmin.kt`, плитка, `architecture.md`): название, состояние («Включён» / «Выключен» / «Не настроен: не хватает …»), переключатель (серый при блокере), «вкл. 24.10, 14:05 · Александр»; платное и выключение СМС — подтверждение с числом людей; журнал.
5. `LoginMethodsProfileScreen.kt` (⭐ п. 4: `Screen.LoginMethods`, ветка `YuldashApp.kt`/`AppNav*.kt`, пункт в `ProfileScreen.kt`, `architecture.md`).

## 4. База данных
Имена — `<префикс>_<суть>_<дата>`, префикс и `down_revision` — по индексу («Порядок миграций», очередь 3–4) и `alembic heads` свежего `main`; одна голова; up → down → up на SQLite и PostgreSQL.
- А1 `…_owner_switches_…`: `appsetting`, `appsettingchange`, строки `login.*`. А2 `…_otp_issue_fields_…`: `otpcode` + `channel` (`'sms'`), `ip_hash`, `device_hash`, индекс `created_at`. Б `…_user_vk_id_…`: `user.vk_id`. Г `…_user_auth_time_…`: `refreshtoken.auth_time`, `user.phone_changed_at`.
- **Срок хранения и удаление аккаунта:** `appsetting`, `appsettingchange` — `KEEP_FOREVER` («настройки и журнал решений владельца»), `updated_by`/`admin_id` → `NULL` при удалении аккаунта (`account.py`); поля `otpcode` — ночная чистка (`cleanup.py:152`); `vk_id`, `phone_changed_at` — уходят с аккаунтом; `auth_time` — с токеном.
- Старые данные не меняются. **Ждёт «да»**: применение на рабочей базе.

## 5. Надписи
Русский; башкирский — черновик `yuldash-bashkir-linguist` в `docs/tasks.md`, «Переводы на проверку»; СМС по-башкирски тоже ≤ 70 знаков.
- СМС: «Юлдаш: код 123456. Никому его не говори.» + `@app.yulbash.ru #123456`.
- Экран: «Номер телефона», «Получить код», «Код из СМС», «Отправить ещё раз через 0:59», «Прислать в WhatsApp», «Войти через VK».
- `phone_not_supported`: «Нужен российский мобильный номер: +7 9…»; `otp_rate_limited`: «Слишком часто. Новый код — через {мин} мин.»; `sms_daily_cap`: «СМС на сегодня закончились. Войди через Telegram или попробуй завтра.»
- `method_linked_other`: «Этот номер уже связан с другим аккаунтом {Telegram|VK}.» + «Войди по СМС и смени его в профиле.» / «Войди через {способ} и смени его в профиле.» / «Напиши в поддержку — поможем.»; `phone_taken_other_account`: «Этот номер уже у другого аккаунта. Напиши в поддержку — разберёмся.»; `phone_required_vk`: «В VK нет подтверждённого номера. Привяжи номер в VK или войди по СМС.»; `admin_login_telegram_only`: «Администратор входит только через Telegram.»
- «Выключатели»: «Способы входа», «Включён», «Выключен», «Не настроен: не хватает {имена}», «Каждый код стоит денег. Включить?», «Без СМС не смогут войти {N} человек. Всё равно выключить?», «Нельзя выключить последний способ — никто не войдёт.», «Telegram нельзя выключить: через него входит администратор.»
- Профиль: «Способы входа», «Привязать», «Отвязать», «Сменить номер», «Нельзя отвязать: другого способа войти не останется.»

## 6. Состояния экрана
- Вход: «Отправляем код…», таймер; нет сети — «Нет связи. Проверь интернет и повтори»; 503 — «СМС сейчас не уходят» + другой способ; поворот и смерть процесса не теряют шаг.
- «Выключатели»: скелетон; ошибка — «Не удалось загрузить» + «Повторить»; переключатель ждёт сервер, отказ — назад с текстом сервера.
- Башкирский, крупный шрифт, 320dp, тёмная тема — `Canon*`; тач-цели ≥ 48dp, `contentDescription` на двух языках.

## 7. Тесты
- `test_owner_switches.py`: значения по умолчанию (прод — только Telegram, dev — и СМС); включить с блокером → 409 с именами; выключить последний способ или Telegram → 409; выключение СМС с `affected > 0` без `confirm` → 409; не-админ → 403; то же значение — без строки; удаление аккаунта админа → `NULL` (`test_account_deletion.py` зелёный); вход и `/config/login-methods` слушаются выключателя.
- `test_sms_login_limits.py`: иностранный номер → 422, `send_sms` не вызван; 60 с; **6-я выдача через 10 мин → 429**; 11-я за сутки → 429; 41-я с IP за час → 429; потолок дня → 503 + одно сообщение дежурному; одинаковый ответ для нового и существующего номера; в базе HMAC (и у Telegram); `caplog` прода — без кода и полного номера; СМС на двух языках ≤ 70; `send_text` молчит при `SMS_NOTIFY_ENABLED=false`; 10 параллельных запросов → не больше лимита (как `test_otp_phone_concurrency.py`).
- `test_login_linking.py`: Telegram → СМС тем же номером = один `user.id`; номер у аккаунта с другим Telegram → 409, второго аккаунта нет; без действующих способов — «напиши в поддержку»; **СМС с номером из `ADMIN_PHONES` → не админ; существующий админ по СМС → 403 `admin_login_telegram_only`.**
- `test_vk_login.py` (VK подменён): `vk_id` клиента игнорируется; `redirect_uri` вне списка → отказ; без номера → 403; неверный `code_verifier` → отказ; токен не сохранён. `test_whatsapp_login.py`: лимиты общие с СМС; провайдер упал → 502, кода нет. `test_phone_change.py`: `auth_time` старше 10 мин → 403 и после продления; номер занят → 409; другие сессии вышли.
- Android: unit — таймер, `detailCode`, PKCE; Compose — вход и «Выключатели»; `*GuardTest`. Сайт — тесты входа и «Выключателей» (+ `run.mjs`), `check` + `build`. Миграции — `test_migrations_reach_production.py`, `test_migrations_match_the_models.py`.
- Мутации: вернуть уборку в вебхук, убрать проверку `+79`, хранить код открыто, вернуть `promote_admin` для СМС — тест краснеет.

## 8. Приёмка
Агент другой «семьи» (Q9) + вердикт `yuldash-security-privacy` по каждой части.
- [ ] Прод-настройки без ключей: СМС, VK, WhatsApp — «Не настроен», не включить; люди видят только Telegram; Telegram не выключить.
- [ ] Dev: СМС включён по умолчанию; выключили — поле номера исчезло на эмуляторе без нового APK (≤ 1 мин), включили — вернулось; журнал — обе смены с именем.
- [ ] Эмулятор, RU/BA × светлая/тёмная: таймер, «Изменить номер», ошибки 422/429/503 словами сервера; поворот и смерть процесса не теряют шаг.
- [ ] Telegram, потом СМС тем же номером — тот же `user.id`; номер из `ADMIN_PHONES` по СМС — не админ.
- [ ] 6-я попытка на код и 16-я на номер — 429; 6-я выдача за час — 429; иностранный номер — 422; в журнале нет кода и полного номера.
- [ ] Выключение СМС при людях «только с номером» — предупреждение с числом.
- [ ] Живые СМС/VK/WhatsApp — после договоров и «да» (W1-E2E-07, по Q11 пока нет); до того — mock и подменённые провайдеры.
- [ ] `/gotovo`; миграции up → down → up на копии базы.
- **Мозг:** `architecture.md` — «Выключатели», «Способы входа»; `decisions.md` — выключатели (D1), админ только через Telegram (D8), лимиты СМС; `tasks.md` — BA-черновики, что ждёт «да»; `lessons.md` — «уборка не должна стирать то, по чему считают лимиты».

## 9. Риски и границы
- Деньги: каждая СМС платная, цену не знаю (тариф sms.ru); день ограничен `SMS_DAILY_CAP`; уведомления — отдельным `SMS_NOTIFY_ENABLED`.
- Выключение СМС отрезает людей без Telegram и VK: экран показывает их число и просит подтверждение; вернуть вход — снова включить СМС.
- Telegram выключить нельзя, пока он — единственный вход администратора (D8): сознательное отступление от «каждый способ — переключателем».
- VK ID может требовать регистрацию организации (комментарий `LoginScreen.kt:762` — про ИНН) — проверить до части Б; WhatsApp — договор и шаблон, без провайдера часть В не делается.
- Политика (`webapp/src/legal.ts:84`, `web/components/legal-content.ts:85`) до включения должна назвать sms.ru, VK, провайдера WhatsApp — «да» Александра.
- Аккаунт стора: форма номера видна только при включённом СМС — иначе проверяющим Telegram. Не входит: слияние аккаунтов, почта.

## 10. Журнал статуса
| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 24.10.2026 | не начато | — | — | план написан (Q13 уточнено) |
| 24.10.2026 | не начато | — | — | правки по приёмке: D1, D8, лимиты на строках ≥ 24 ч, деление А1/А2/Б/В/Г |
