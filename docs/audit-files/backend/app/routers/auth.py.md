# Карточка: `backend/app/routers/auth.py`

- Статус: verified
- Лист: leaf-2.1
- Проверял: Sonnet 5 (leaf-2.1); принимал: Opus 5.5; независимое ревью: Opus (REQUEST_CHANGES → доработано)

## Назначение

Главный роутер входа: телефон+SMS-OTP, Telegram-вход через бота (запрос кода → код в чат →
проверка), профиль `/me` (чтение/правка/экспорт/удаление данных), logout, регистрация push-токенов
(FCM и Web Push). Здесь же живёт автоматическое назначение/снятие роли admin по настройкам
(`ADMIN_TELEGRAM_CHAT_ID`/`ADMIN_PHONES`) и обработка inline-кнопок админа в Telegram. VK/WhatsApp —
заглушки-501 (сознательно отключены). Самый крупный и часто вызываемый файл листа: почти каждый
защищённый эндпоинт проекта начинается с токена, выданного отсюда.

**После независимого ревью (REQUEST_CHANGES) сюда добавлена существенная новая логика:**
долгая (24ч) память неудачных попыток кода сверх короткого 5-минутного бюджета, с пониженным
порогом для номеров из `ADMIN_PHONES`; отказ `request_code`/`verify` по SMS-коду, пока канал
объективно заморожен, без потери живого кода при сбое отправки; счётчик неудач для фикс-кода
проверочного аккаунта стора; бот отвечает на `/start` только в личных чатах.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `MAX_OTP_ATTEMPTS_PER_PHONE` | 44 | Потолок неверных попыток кода за время жизни кодов (5 минут) — 15, по всем живым кодам сразу | Короткий бюджет — обнуляется с каждым новым кодом | ок, тест R1/M7 |
| `MAX_OTP_FAILURES_PER_PHONE_PER_DAY`, `ADMIN_PHONE_MAX_OTP_FAILURES_PER_DAY`, `OTP_FAILURE_WINDOW` | 53–58 | Б-1 (независимое ревью): долгая память — 100 неудач/24ч на обычный номер, 20/24ч на номер из `ADMIN_PHONES` (дороже цель — угаданный код даёт права администратора) | NIST 800-63B §5.1.1.2 | ок, тесты R9/M22, R13/M29 |
| `_otp_failures_today` | 61–66 | Сумма `attempts` по ВСЕМ (не только живым) кодам номера за 24 часа | Требует, чтобы строки `OtpCode` переживали свои 5 минут жизни — см. правку окна очистки в `telegram_webhook` | ок |
| `_max_otp_failures_for` | 72–76 | Выбирает порог: 20 для номера из `ADMIN_PHONES` (через `_phone_key`), иначе 100 | Пустой `ADMIN_PHONES` → общий порог для всех | ок, тест R13/M29 |
| `_guard_otp_daily_budget` | 83–85 | 429, если суточные неудачи ≥ порога | Вызывается и в `verify` (R9), и в `request_code` (R11 — срочная правка P2) | ок |
| `_REVIEW_LOGIN_GUARD_CODE`, `_bump_review_login_failure` | 95–108 | Б-4: служебная строка-страж в `OtpCode` считает неудачи фикс-кода стора тем же механизмом, что обычный SMS-код (без лока на номер — см. «Остаток», живой сценарий гонки в тестах) | Код строки не похож на реальный ввод (с дефисами) | ок, тест R14/M24 |
| `_lock_otp_phone` | 111–123 | Сериализует выдачу/проверку OTP по номеру: Postgres — `pg_advisory_xact_lock`, SQLite — нулевой `UPDATE` ради writer-lock | Номер не уходит в лог/метаданные блокировки | ок |
| `_maybe_promote_admin` | 126–163 | Автовход в admin по точному совпадению Telegram-id ИЛИ телефона; обратная сторона — автоснятие, если номер убрали из настроек (ТОЛЬКО когда `admin_phones` непусто) | Каждое решение пишется в `admin_action` | ок, тесты R6/R7 |
| `_is_owner_telegram` | 166–180 | «Это владелец пишет боту?» — для инлайн-кнопок админа и текстовых команд | Пустой `ADMIN_TELEGRAM_CHAT_ID` не пускает НИКОГО | ок (тест вне листа, `test_telegram_owner_gate.py`) |
| `_norm_phone` | 183–195 | Телефон из Telegram-контакта → общая `normalize_phone` | — | ок |
| `_name_flag` / `_guard_display_name` / `_safe_display_name` | 198–244 | Модерация отображаемого имени: явная правка — честный отказ 422; имя из внешнего профиля Telegram — тихая замена на пусто | Телефон в имени — обход комиссии | ок |
| `_clean_avatar_url` | 245–254 | Аватар — только ссылкой на своё хранилище | — | ок |
| `_review_login_active` | 256–263 | Тестовый аккаунт модерации сторов активен ТОЛЬКО когда в `.env` заданы ОБА `review_phone`+`review_code` | Сравнение — через `normalize_phone` | ок |
| `_set_user_phone` | 265–287 | Сохранить реальный номер; если занят ДРУГИМ юзером — тихо не перезаписывает | Вторая дверь к тому же полю, что и вход | ок |
| `_complete_login` | 289–317 | Одна транзакция: блокировка юзера → автоадмин → запоминание устройства → согласия 152-ФЗ → выдача токенов → коммит; вторичные сигналы — ПОСЛЕ коммита | Единая точка завершения входа для SMS/Telegram/review | ок |
| `request_code`/`_request_code` (`POST /auth/request-code`) | 329–396 | Бан устройства → нормализация → (review: без реальной отправки) → лок номера → **суточный бюджет (R11)** → throttle ≤3/60с → генерация кода → **commit СРАЗУ** (R12: попытка видна throttle'у даже при сбое) → `send_sms`; при исключении — код НЕМЕДЛЕННО гасится отдельным коммитом (пустая строка, `expires_at` в прошлом) и ошибка пробрасывается | `dev_code` в ответе — ТОЛЬКО `env=dev` | исправлено, тесты R10/M20, R11/M27 |
| `verify`/`_verify_sms_login` (`POST /auth/verify`) | 398–537 | Бан устройства → review-ветка (суточный бюджет + счётчик неудач, см. Б-4) → лок номера → **канал жив? (R10)** → суточный бюджет (R9) → бюджет на номер (15/5мин) И на код (5) → `compare_digest` → атомарное потребление → поиск/заведение юзера → «номер похож на перешедший другому» → завершение входа | Повторная проверка после `IntegrityError` подхватывает уже созданного юзера | исправлено, тесты R1/R2/R3/R5/R9/R10/R14 |
| `TgStartOut`/`tg_start` (`POST /auth/tg/start`) | 544–553 | `TgAuth(status="waiting")` со случайным `request_id` (UUID) | — | ок |
| `telegram_webhook` (`POST /telegram/webhook`) | 560–680 | Секретный заголовок; битый JSON → `{"ok":true}`; контакт — только свой; **`/start` отвечает ТОЛЬКО в личных чатах (Б-5)**; чистка просроченных `TgAuth` сразу, `OtpCode` БЕЗ неудачных попыток — через 60с, С неудачными попытками — только через сутки+запас (нужно суточному бюджету выше) | Самозваный контакт не принимается | исправлено, тест R15/M25 |
| `_telegram_api` | 682–689 | HTTP-вызов Telegram Bot API, молча глотает исключения | — | ок |
| `_handle_admin_callback` | 692–820 | Inline-кнопки админа: только владелец, каждое решение — `admin_action`; общие активаторы (`set_driver_docs_verdict`, `_activate_payment`) | Карточный платёж НЕ активируется кнопкой | ок |
| `TgVerifyIn`/`tg_verify`/`_verify_telegram_login` (`POST /auth/tg/verify`) | 823–922 | Бан устройства → статусы 409/410/429/400 → атомарное потребление → поиск по `telegram_id`, затем по телефону → подстановка номера из бота → телефон обязателен | **Не проверяет «номер похож на перешедший другому» — см. найденную ошибку Б-2** | найдена ошибка (не чинил, см. ниже) |
| `RefreshIn`/`refresh` (`POST /auth/refresh`) | 925–947 | Пустой токен — честный 400; бан устройства (заголовок) → `rotate_refresh` | Формат `rotation_id` — `[0-9a-f]{64}` | ок (логика — в `security.py`) |
| `vk_callback`/`whatsapp_callback` | 956–966 | Заглушки 501 — вход через VK/WhatsApp сознательно отключён | — | ок, намеренно |
| `me` (`GET /me`) | 968–971 | Профиль + живой рейтинг | — | ок |
| `MeUpdateIn` | 973–983 | Модель правки профиля | — | ок |
| `_location_summary` | 986–1034 | Счётчик «сколько точных точек реально лежит в БД» для `/me/data`/`/me/export` | — | ок |
| `my_data` (`GET /me/data`) | 1036–1077 | «Что мы знаем» живыми числами и сроками ретеншена | — | ок |
| `export_my_data` (`GET /me/export`) | 1079–1215 | «Скачать мои данные» на RU/BA; ТОЛЬКО свои данные; объём обрезан с пометкой | — | ок |
| `update_me` (`POST /me/update`) | 1217–1248 | Правка имени/аватара/города/языка/пола | Телефон НЕ меняется | ок |
| `delete_me` (`POST /me/delete`) | 1250–1260 | `guard_can_delete` → `delete_user_account` | Необратимо; логика — в `account.py` | ок |
| `logout` (`POST /auth/logout`) | 1262–1288 | Гасит `tokens_valid_from`, отзывает ВСЕ refresh-токены, удаляет ВСЕ `DeviceToken`/`WebPushSubscription` юзера | — | ок, тест R8/M14 |
| `PushTokenIn`/`_guard_push_token_owner` | 1290–1310 | Перепривязка FCM-токена разрешена только при совпадении устройства | — | ок |
| `push_register` (`POST /push/register`) | 1312–1346 | Регистрация/перепривязка FCM-токена; гонка вставки — через `IntegrityError` | — | ок |
| `WebPushKeysIn`/`WebPushSubIn` | 1348–1365 | Модели подписки браузера | — | ок |
| `push_web_subscribe` (`POST /push/web/subscribe`) | 1367–1433 | Подписка браузера: обязательные ключи, `endpoint` только `https://` | — | ок |
| `push_web_unsubscribe` (`POST /push/web/unsubscribe`) | 1435–1455 | Отписка — только СВОЯ подписка | — | ок |
| `push_unregister` (`POST /push/unregister`) | 1457–1471 | Отвязка FCM-токена — только СВОЙ | — | ок |

## Связи

- `app/security.py`: `current_user`, `issue_tokens`, `rotate_refresh`, `lock_refresh_user`,
  `revoke_all_refresh`, `gen_otp`, `normalize_phone`, `is_placeholder_phone`.
- `app/antifraud.py`: `guard_device_not_banned`, `phone_looks_recycled`, `release_phone`,
  `remember_login_device`, `notify_login_device`, `notify_phone_release`, `normalize_device_id`.
- `app/account.py`: `guard_can_delete`, `delete_user_account`.
- `app/services.py`: `find_user_by_phone`, `guard_own_media_url`, `set_driver_docs_verdict`,
  `set_user_gender`, `user_rating`, `send_sms`, `sms_channel_live` (новый импорт, Б-1), `pick_lang`.
- `app/trust_service.py::record_login_consents` — согласия 152-ФЗ при каждом входе.
- Таблицы: `User`, `OtpCode`, `TgAuth`, `RefreshToken` (через `security.py`), `DeviceToken`,
  `WebPushSubscription`, `Consent`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Бюджет неверных попыток кода общий НА НОМЕР по всем живым кодам, атомарный под конкурентной проверкой | `backend/tests/test_otp_phone_concurrency.py::test_parallel_wrong_codes_cannot_cross_phone_wide_budget` | да — M7 |
| R2 | Код входа одноразовый: не принимается повторно — ни при обычном повторе, ни при настоящей одновременной проверке на PostgreSQL | `backend/tests/walk/l2_1/test_l2_1_otp_replay.py::test_same_code_cannot_log_in_twice`, `backend/tests/test_auth_consumption_atomicity.py::test_concurrent_verification_issues_only_one_token_pair` | да — M8 (реальная гонка на изолированном PostgreSQL) |
| R3 | Throttle выдачи SMS: ≤3 кода в минуту на номер | `backend/tests/test_otp_phone_concurrency.py::test_parallel_code_issuance_sends_at_most_three_sms_per_minute` | да — M9 |
| R4 | Номер, похожий на перешедший к другому человеку, получает НОВЫЙ аккаунт — **только на SMS-двери** (см. найденную ошибку Б-2: Telegram-дверь этой проверки не делает) | `backend/tests/test_a_recycled_number_is_a_new_person.py::test_новый_владелец_номера_получает_чистый_аккаунт` | да — M10 (SMS-дверь) |
| R5 | Фикс-код стора никогда не открывает/не меняет уже существующий ПРИВИЛЕГИРОВАННЫЙ аккаунт | `backend/tests/test_review_login_permissions.py::test_fixed_review_login_rejects_existing_privileged_account_without_mutating_it` | да — M11 |
| R6 | Автовыдача роли admin — только по ТОЧНОМУ совпадению настроенного Telegram-id или телефона | `backend/tests/test_audit_20260808.py::test_foreign_lookalike_number_does_not_get_admin` | да — M12 |
| R7 | Автоснятие роли admin при входе, если человек больше не значится в настройках | `backend/tests/test_every_admin_action_leaves_a_trace.py::test_права_снимаются_когда_номер_убрали_из_настроек` | да — M13 |
| R8 | Logout отвязывает FCM/Web-Push регистрации устройства | `backend/tests/walk/l2_1/test_l2_1_logout_clears_push_tokens.py::test_logout_removes_device_and_web_push_tokens` | да — M14 |
| R9 | Долгая (24ч/100) память неудач SMS-кода поверх короткого 5-минутного бюджета | `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_daily_failure_budget_survives_code_expiry`, `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_daily_failure_budget_does_not_trip_for_an_ordinary_day` | да — M22 |
| R10 | `request_code`/`verify` не оставляют и не принимают живой SMS-код, если канал объективно заморожен (Б-1, блокер) | `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_request_code_does_not_leave_a_live_code_when_the_channel_is_frozen`, `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_verify_refuses_an_sms_code_while_the_channel_is_frozen_even_if_one_exists` | да — M20, M21 |
| R11 | `request_code` сам проверяет суточный бюджет, не только `verify` | `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_request_code_itself_is_blocked_once_the_daily_budget_is_spent` | да — M27 |
| R12 | Неудачная отправка кода всё равно считается попыткой для throttle «3/мин» (строка коммитится СРАЗУ, а не теряется при откате) | `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_failed_sends_still_count_toward_the_per_minute_issuance_throttle` | тест проходит, но см. «Остаток» — отдельная изолирующая поломка не найдена (два коммита в функции подстраховывают друг друга) |
| R13 | Номер из `ADMIN_PHONES` — пониженный суточный порог (20 вместо 100) | `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_admin_phone_has_a_lower_daily_failure_budget`, `backend/tests/walk/l2_1/test_l2_1_frozen_sms_door.py::test_a_non_admin_phone_keeps_the_general_budget` | да — M29 |
| R14 | Фикс-код стора считает неудачи и упирается в суточный бюджет | `backend/tests/walk/l2_1/test_l2_1_review_login_and_group_chat_guards.py::test_review_login_brute_force_hits_the_daily_budget`, `backend/tests/walk/l2_1/test_l2_1_review_login_and_group_chat_guards.py::test_review_login_a_few_typos_do_not_trip_the_budget` | да — M24 |
| R15 | Бот отвечает на `/start` только в личных чатах — код входа не попадает в группу | `backend/tests/walk/l2_1/test_l2_1_review_login_and_group_chat_guards.py::test_start_command_in_a_group_chat_does_not_deliver_a_code`, `backend/tests/walk/l2_1/test_l2_1_review_login_and_group_chat_guards.py::test_start_command_in_a_private_chat_still_works` | да — M25 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| Б-1 | В проде `sms_provider=mock` по умолчанию — SMS не уходят. Код сохранялся и коммитился ДО вызова отправки: человеку канал отвечал 503, а живой код 5 минут лежал в базе, и `verify` его принимал. Короткий бюджет попыток забывал ошибки старше 5 минут → ~15 попыток/5мин, ~4300/сутки на номер — заметный шанс подобрать код за месяц. Номер из `ADMIN_PHONES` при угадывании сразу давал права администратора | `client.post("/auth/request-code", ...)` при `env=prod, sms_provider=mock` → раньше 503 + живой `OtpCode` в базе | 1) commit только после успешной отправки, при сбое — немедленная нейтрализация кода; 2) `verify` отдельно отказывает по SMS-коду, пока канал заморожен; 3) суточный бюджет 100(/20 для ADMIN_PHONES) неудач за 24ч поверх короткого; 4) `request_code` тоже проверяет суточный бюджет; 5) неудачная попытка всё равно коммитится — throttle «3/мин» её видит | `test_l2_1_frozen_sms_door.py` (8 тестов): RED → GREEN; M20/M21/M22/M27/M29 KILLED |
| Б-2 | **Не исправлено — решение за Александром.** Вход через Telegram может открыть ЧУЖОЙ SMS-аккаунт по номеру, которым человек когда-то (возможно, год назад) поделился в боте, — в обход правила R4 («перешедший номер получает новый аккаунт»): проверка `phone_looks_recycled` стоит ТОЛЬКО на SMS-двери (`_verify_sms_login`), а `_verify_telegram_login` её не делает вовсе. Telegram подтверждает номер один раз, при регистрации, и больше не перепроверяет | U (номер X, `telegram_id=None`, `last_device_id="old"`, не заходил 200 дней) → `tg/start` → вебхук `/start <id>` → вебхук `contact` (от самого отправителя, номер X) → `tg/verify` с `X-Device-Id="new"` → 200, `user.id == U.id` (должен был получиться НОВЫЙ аккаунт по правилу R4) | Не чинил: решение не только техническое (варианты ниже меняют UX входа через Telegram), а с правовыми/продуктовыми последствиями. **Варианты для Александра** (из независимого ревью): (1) не привязывать молча аккаунт без `telegram_id`, если его `last_device_id` отличается от текущего устройства, — отправлять в поддержку; (2) поставить ту же проверку `phone_looks_recycled`, что на SMS-двери; (3) привязывать Telegram только из уже вошедшего аккаунта | — |
| Б-4 | Фикс-код проверочного аккаунта стора не считал неудачные попытки вовсе — только общий лимит по IP (20/мин) стоял между перебором и пассажирским входом без SMS | 100+ запросов `/auth/verify` с неверным фикс-кодом на `review_phone` — раньше ни разу не 429 | Служебная строка-счётчик в `OtpCode`, суточный бюджет (общий механизм) | `test_l2_1_review_login_and_group_chat_guards.py` (4 теста): RED → GREEN; M24 KILLED |
| Б-5 | `/start@bot <id>`, написанный в ЛЮБОМ чате (включая группу), отвечал в тот же чат — код входа видели ВСЕ участники группы. Для аккаунта, уже привязанного к Telegram, одного кода достаточно, чтобы войти вместо хозяина | POST на `/telegram/webhook` с `chat.type="group"` и текстом `/start <id>` → раньше код уходил в группу | Бот отвечает на `/start` только когда `chat.type` явно НЕ group/supergroup/channel (отсутствие поля — как у упрощённых тестовых payload — считается личным чатом: настоящий Telegram это поле шлёт всегда) | `test_l2_1_review_login_and_group_chat_guards.py` (2 теста): RED → GREEN; M25 KILLED |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M7 | `MAX_OTP_ATTEMPTS_PER_PHONE = 15` → `1500` | `test_parallel_wrong_codes_cannot_cross_phone_wide_budget` | KILLED |
| M8 | Потребление кода `.values(code="", expires_at=utcnow())` → `.values(code=verified_code)` | `test_concurrent_verification_issues_only_one_token_pair` (реальная гонка на PostgreSQL) | KILLED |
| M9 | Throttle `len(recent) >= 3` → `>= 300` | `test_parallel_code_issuance_sends_at_most_three_sms_per_minute` | KILLED |
| M10 | `if user and phone_looks_recycled(...)` → `if False and …` | `test_новый_владелец_номера_получает_чистый_аккаунт` | KILLED |
| M11 | `if not user or user.role != UserRole.passenger:` → `if not user:` | `test_fixed_review_login_rejects_existing_privileged_account_without_mutating_it` | KILLED |
| M12 | `заслужил = by_tg or by_phone` → `заслужил = True` | `test_foreign_lookalike_number_does_not_get_admin` | KILLED |
| M13 | `elif user.role == UserRole.admin and not заслужил and admin_keys:` → `elif False:` | `test_права_снимаются_когда_номер_убрали_из_настроек` | KILLED |
| M14 | Цикл удаления `DeviceToken` в `logout` заменён на `pass` | `test_logout_removes_device_and_web_push_tokens` | KILLED |
| M20 | Нейтрализация кода при сбое отправки убрана (`except Exception: raise` без очистки) | `test_request_code_does_not_leave_a_live_code_when_the_channel_is_frozen` | KILLED |
| M21 | Проверка «канал жив» убрана из `verify` | `test_verify_refuses_an_sms_code_while_the_channel_is_frozen_even_if_one_exists` | KILLED |
| M22 | Суточный бюджет в `verify` убран | `test_daily_failure_budget_survives_code_expiry` | KILLED |
| M24 | Суточный бюджет для review-кода убран | `test_review_login_brute_force_hits_the_daily_budget` | KILLED |
| M25 | Гейт «только личный чат» отключён (`is_group_chat = False`) | `test_start_command_in_a_group_chat_does_not_deliver_a_code` | KILLED |
| M27 | Суточный бюджет в `request_code` убран | `test_request_code_itself_is_blocked_once_the_daily_budget_is_spent` | KILLED |
| M29 | Пониженный порог для ADMIN_PHONES поднят до 100000 | `test_admin_phone_has_a_lower_daily_failure_budget` | KILLED |

Полный прогон: `python audit_mutation.py --root . replay --spec docs/audit-mutations/leaf-2.1.json`
→ `MUTATIONS KILLED 28/28` (см. также карточки `security.py` и `account.py`).

## Остаток и ограничения

- **R12 (неудача всё равно считается попыткой) без изолирующей поломки.** В `_request_code`
  строка коммитится ОДИН раз СРАЗУ (до отправки), а на сбой реагирует отдельный коммит-нейтрализация
  в `except`. Экспериментально проверил: ослабление ПЕРВОГО коммита (`commit→flush`) не ломает
  наблюдаемое поведение теста `test_failed_sends_still_count_toward_the_per_minute_issuance_throttle`
  — второй коммит (в `except`) всё равно сохраняет (уже нейтрализованную) строку, и throttle её
  по-прежнему видит. Это ЗАЩИТА, не пробел: убрать «лишний» коммит из `except` отдельно ломает
  ДРУГОЕ свойство (R10, пойман M20). Две поломки по ОДНОМУ правилу подряд не нашёл — честно
  оставляю тест без M-поломки, аналогично R1 в карточке `security.py`.
- **Б-2 блокирует карточку, не статус.** Правило README для 🟩 требует воспроизведённую и
  либо исправленную, либо осознанно принятую ошибку; Б-2 — именно такой случай (решение вне
  компетенции этого листа). Статус файла остаётся `verified`, потому что подтверждённая ошибка
  задокументирована с репродукцией и вариантами, а не скрыта; `--require-status` самопроверки
  листа не допускает статус `bug` для этого инструмента приёмки (только verified/inactive/blocked).
- `_is_owner_telegram`/`_phone_key` (из `config.py`) вне OWNS этого листа — проверил их
  ИСПОЛЬЗОВАНИЕ в `auth.py`, не их собственный код.
- Полный обход `_handle_admin_callback` и `_bump_review_login_failure` при настоящем IP-лимите
  на реальном Telegram не делал — секреты и реальные боты не трогаю по условиям листа.
- Башкирские черновики этого файла: фраза для Б-3/Б-1 уведомлений человеку указана в карточке
  `security.py` (там же функция `_revoke_family_on_reuse`); отдельных новых строк в `auth.py`,
  видимых пользователю, в этой правке не добавлялось (все отказы — уже существующие `herr`-пары).
