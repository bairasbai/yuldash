# Карточка: `backend/app/routers/auth.py`

- Статус: verified
- Лист: leaf-2.1
- Проверял: Sonnet 5 (leaf-2.1); принимал: Opus 5.5

## Назначение

Главный роутер входа: телефон+SMS-OTP, Telegram-вход через бота (запрос кода → код в чат → проверка),
профиль `/me` (чтение/правка/экспорт/удаление данных), logout, регистрация push-токенов (FCM и
Web Push). Здесь же живёт автоматическое назначение/снятие роли admin по настройкам
(`ADMIN_TELEGRAM_CHAT_ID`/`ADMIN_PHONES`) и обработка inline-кнопок админа в Telegram (модерация
водителей/рекламы/платежей/откликов). VK/WhatsApp — заглушки-501 (сознательно отключены до
безопасной реализации, см. комментарий в коде). Самый крупный и самый часто вызываемый файл листа:
почти каждый защищённый эндпоинт проекта начинается с токена, выданного отсюда.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `MAX_OTP_ATTEMPTS_PER_PHONE` | 42–44 | Потолок неверных попыток кода — 15, считается НА НОМЕР по всем живым кодам сразу | Запрос нового кода не обнуляет счётчик (иначе 15 попыток/код × 3 кода/мин = перебор) | ок, тест R1/M7 |
| `_lock_otp_phone` | 47–59 | Сериализует выдачу/проверку OTP по номеру: Postgres — `pg_advisory_xact_lock` по хешу номера, SQLite — нулевой `UPDATE` ради writer-lock | Номер никогда не уходит в лог/метаданные блокировки; падает `RuntimeError` на незнакомом диалекте | ок |
| `_maybe_promote_admin` | 62–99 | Автовход в admin по точному совпадению Telegram-id ИЛИ телефона (через `_phone_key`); обратная сторона — автоснятие, если номер убрали из настроек | Снятие — ТОЛЬКО когда `admin_phones` непусто (иначе один неверно прочитанный `.env` обнулил бы всех админов); каждое решение пишется в `admin_action` | ок, тесты R6/R7 |
| `_is_owner_telegram` | 102–116 | «Это владелец пишет боту?» — для инлайн-кнопок админа и текстовых команд | Пустой `ADMIN_TELEGRAM_CHAT_ID` не пускает НИКОГО (историческая дыра волны 2026-08-07) | ок (тест в чужом файле `test_telegram_owner_gate.py`, вне листа) |
| `_norm_phone` | 119–131 | Телефон из Telegram-контакта → `normalize_phone` (общее правило, не своя копия) | Третья копия нормализации номера раньше ошибалась (`+89991234567`) — теперь одна точка | ок |
| `_name_flag` / `_guard_display_name` / `_safe_display_name` | 134–178 | Модерация отображаемого имени: явная правка профиля — честный отказ 422 (телефон/ссылка/мат в имени); имя из внешнего профиля Telegram — тихая замена на пусто (не ломаем вход) | Телефон в имени — это объявление в обход комиссии такси/курьера | ок |
| `_clean_avatar_url` | 181–189 | Аватар — только ссылкой на своё хранилище (`guard_own_media_url`) | Чужая ссылка в карточке палит IP/город смотрящего хозяину внешнего сервера | ок |
| `_review_login_active` | 192–198 | Тестовый аккаунт модерации сторов активен ТОЛЬКО когда в `.env` заданы ОБА `review_phone`+`review_code` | Сравнение телефонов — через `normalize_phone` с обеих сторон | ок |
| `_set_user_phone` | 201–222 | Сохранить реальный номер; если номер уже занят ДРУГИМ юзером — тихо не перезаписывает | Вторая дверь к тому же полю, что и вход — без `normalize_phone` тут была бы та же дыра задвоения аккаунтов | ок |
| `_complete_login` | 225–250 | Одна транзакция: блокировка юзера → автоадмин → запоминание устройства → согласия 152-ФЗ (без коммита) → выдача токенов → коммит; вторичные сигналы (лог админ-действия, уведомление о новом устройстве/освобождении номера) — ПОСЛЕ коммита, ошибка одного не рушит вход | Единая точка завершения входа для SMS/Telegram/review — согласия и устройство не теряются при откате | ок |
| `request_code` (`POST /auth/request-code`) | 264–297 | Бан устройства → нормализация номера → (review-номер: ответ без реальной отправки) → лок номера → throttle ≤3 кода/60с → генерация+сохранение OTP → отправка SMS | `dev_code` в ответе — ТОЛЬКО `env=dev` | ок, тест R4/M9 |
| `verify` / `_verify_sms_login` (`POST /auth/verify`) | 300–410 | Бан устройства → review-ветка (фикс-код только для `role=passenger`, см. ниже) → лок номера → бюджет попыток на номер (15) И на код (5) → `compare_digest` → атомарное потребление кода (`code=""`, `expires_at=now`) → поиск/заведение юзера → проверка «номер похож на перешедший другому» → завершение входа | Повторная проверка после `IntegrityError` (параллельная регистрация тем же номером) подхватывает уже созданного юзера, не падает | ок, тесты R1/R2/R3/R5 |
| `TgStartOut` / `tg_start` (`POST /auth/tg/start`) | 427–439 | Заводит `TgAuth(status="waiting")` со случайным `request_id` (UUID, не угадать) | — | ок |
| `telegram_webhook` (`POST /telegram/webhook`) | 442–544 | Секретный заголовок от Telegram (`compare_digest`); битый JSON — не 500, а `{"ok":true}` (без ретраев от Telegram); контакт из кнопки — принимается ТОЛЬКО свой (`contact.user_id==from.id`); `/start <request_id>` → код + `reply_markup` «Поделиться номером»; чистка просроченных `TgAuth`/`OtpCode` | Самозваный контакт (пересланный чужой) не принимается | ок |
| `_telegram_api` | 547–554 | HTTP-вызов Telegram Bot API, молча глотает исключения | Не про вход/личные данные напрямую | ок |
| `_handle_admin_callback` | 557–685 | Inline-кнопки админа: только владелец (`_is_owner_telegram`), каждое решение — `admin_action` в журнал; ветки `drv`/`ad`/`resp`/оплата используют ОБЩИЕ активаторы (`set_driver_docs_verdict`, `_activate_payment`), а не свою логику | Карточный платёж НЕ активируется кнопкой — ждёт вебхук провайдера (иначе «подтверждение» без реальных денег) | ок |
| `TgVerifyIn` / `tg_verify` / `_verify_telegram_login` (`POST /auth/tg/verify`) | 688–787 | Бан устройства → статусы 409/410/429/400 по порядку → атомарное потребление кода → поиск юзера по `telegram_id`, затем по телефону (дедуп через `normalize_phone`) → подстановка реального номера из бота → **телефон обязателен**: без него код НЕ помечается `used`, логин не завершается (403 `phone_required`) | Код можно предъявить повторно ПОСЛЕ «поделиться номером», не теряя попытку | ок, тест R2 (общий с SMS-веткой через `_verify_sms_login`-подобный паттерн) |
| `RefreshIn` / `refresh` (`POST /auth/refresh`) | 790–812 | Пустой токен — честный 400; бан устройства (по заголовку, первая половина) → `rotate_refresh` (вторая половина — по памяти сервера, см. карточку `security.py`) | Формат `rotation_id` — `[0-9a-f]{64}` | ок (глубокая логика — в `security.py`) |
| `vk_callback` / `whatsapp_callback` | 820–829 | Заглушки 501 — вход через VK/WhatsApp сознательно отключён (прежние версии принимали непроверенный id/номер — угон аккаунта) | — | ок, намеренно не реализовано |
| `me` (`GET /me`) | 832–835 | Профиль + живой рейтинг | — | ок |
| `MeUpdateIn` | 838–845 | Модель правки профиля: имя/аватар/город/язык/пол | — | ок |
| `_location_summary` | 851–897 | Честный счётчик «сколько точных точек реально лежит в БД» для `/me/data`/`/me/export` — поездки (попутка+такси), SOS с точкой на карте | Технический `(0,0)` в `InstantOrder` не считается «реальной точкой» | ок |
| `my_data` (`GET /me/data`) | 900–940 | «Что мы знаем» живыми числами и сроками ретеншена (из `cleanup.py`, не продублировано вручную) | — | ок |
| `export_my_data` (`GET /me/export`) | 943–1078 | «Скачать мои данные» читаемым текстом на RU/BA; ТОЛЬКО свои данные (чужие сообщения/телефоны/координаты не попадают); объём обрезан (300 записей на раздел) с явной пометкой об этом | Сообщения — только отправленные мной | ок |
| `update_me` (`POST /me/update`) | 1081–1111 | Правка имени (модерация)/аватара (свой хостинг)/города/языка (только ru/ba)/пола (через `set_user_gender` — гасит подтверждение «женщина за рулём» при смене) | Телефон НЕ меняется этой ручкой | ок |
| `delete_me` (`POST /me/delete`) | 1114–1123 | `guard_can_delete` (честные гейты, см. карточку `account.py`) → `delete_user_account` | Необратимо; 152-ФЗ | ок, глубокая логика в `account.py` |
| `logout` (`POST /auth/logout`) | 1126–1152 | Гасит `tokens_valid_from`, отзывает ВСЕ refresh-токены, удаляет ВСЕ `DeviceToken`/`WebPushSubscription` юзера — «выйти со всех устройств» буквально | Сценарий «общий телефон в семье»: следующий вошедший не получает чужие пуши | ок, тест R8/M14 |
| `PushTokenIn` / `_guard_push_token_owner` | 1155–1173 | Перепривязка FCM-токена к новому юзеру разрешена ТОЛЬКО если устройство совпадает (или у старой записи устройство не отмечено ВМЕСТЕ с иным владельцем — тогда 409) | Лог предупреждения при попытке забрать чужую запись (без утечки токена) | ок |
| `push_register` (`POST /push/register`) | 1176–1210 | Регистрация/перепривязка FCM-токена; гонка двух одновременных вставок одного токена — `IntegrityError` → перепривязка, не падение | — | ок |
| `WebPushKeysIn` / `WebPushSubIn` | 1213–1229 | Модели подписки браузера (RFC 8291) | — | ок |
| `push_web_subscribe` (`POST /push/web/subscribe`) | 1231–1296 | Подписка браузера: обязательные ключи шифрования, `endpoint` только `https://`; идемпотентно, гонка двух вкладок — так же через `IntegrityError` | — | ок |
| `push_web_unsubscribe` (`POST /push/web/unsubscribe`) | 1299–1318 | Отписка — только СВОЯ подписка по `user_id` | Идемпотентно (нечего удалять → `ok:true`) | ок |
| `push_unregister` (`POST /push/unregister`) | 1321–1336 | Отвязка FCM-токена — только СВОЙ | Идемпотентно | ок |

## Связи

- `app/security.py`: `current_user`, `issue_tokens`, `rotate_refresh`, `lock_refresh_user`,
  `revoke_all_refresh`, `gen_otp`, `normalize_phone`, `is_placeholder_phone`.
- `app/antifraud.py`: `guard_device_not_banned`, `phone_looks_recycled`, `release_phone`,
  `remember_login_device`, `notify_login_device`, `notify_phone_release`, `normalize_device_id`.
- `app/account.py`: `guard_can_delete`, `delete_user_account` (вызываются из `delete_me`).
- `app/trust_service.py::record_login_consents` — согласия 152-ФЗ при каждом входе.
- `app/services.py`: `find_user_by_phone`, `guard_own_media_url`, `set_driver_docs_verdict`,
  `set_user_gender`, `user_rating`, `send_sms`, `pick_lang`.
- Таблицы: `User`, `OtpCode`, `TgAuth`, `RefreshToken` (через `security.py`), `DeviceToken`,
  `WebPushSubscription`, `Consent` (через `trust_service`).
- Вызывается Android/веб-клиентом на каждом экране входа; Telegram — реальным ботом (вебхук).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Бюджет неверных попыток кода общий НА НОМЕР по всем живым кодам, атомарный под конкурентной проверкой | `backend/tests/test_otp_phone_concurrency.py::test_parallel_wrong_codes_cannot_cross_phone_wide_budget` | да — M7 |
| R2 | Код входа одноразовый: после успеха не принимается повторно — ни при обычном повторе, ни при настоящей одновременной проверке на PostgreSQL | `backend/tests/walk/l2_1/test_l2_1_otp_replay.py::test_same_code_cannot_log_in_twice`, `backend/tests/test_auth_consumption_atomicity.py::test_concurrent_verification_issues_only_one_token_pair` | да — M8 (реальная гонка на изолированном PostgreSQL) |
| R3 | Throttle выдачи SMS: ≤3 кода в минуту на номер | `backend/tests/test_otp_phone_concurrency.py::test_parallel_code_issuance_sends_at_most_three_sms_per_minute` | да — M9 |
| R4 | Номер, похожий на перешедший к другому человеку (молчал долго + новое устройство), получает НОВЫЙ аккаунт, а не чужую историю | `backend/tests/test_a_recycled_number_is_a_new_person.py::test_новый_владелец_номера_получает_чистый_аккаунт` | да — M10 |
| R5 | Фикс-код стора никогда не открывает и не меняет уже существующий ПРИВИЛЕГИРОВАННЫЙ (не-пассажирский) аккаунт | `backend/tests/test_review_login_permissions.py::test_fixed_review_login_rejects_existing_privileged_account_without_mutating_it` | да — M11 |
| R6 | Автовыдача роли admin — только по ТОЧНОМУ совпадению настроенного Telegram-id или телефона | `backend/tests/test_audit_20260808.py::test_foreign_lookalike_number_does_not_get_admin` | да — M12 |
| R7 | Автоснятие роли admin при входе, если человек больше не значится в настройках (роль не вечна) | `backend/tests/test_every_admin_action_leaves_a_trace.py::test_права_снимаются_когда_номер_убрали_из_настроек` | да — M13 |
| R8 | Logout отвязывает FCM/Web-Push регистрации устройства, а не только ключи входа | `backend/tests/walk/l2_1/test_l2_1_logout_clears_push_tokens.py::test_logout_removes_device_and_web_push_tokens` | да — M14 |

## Найденные ошибки

Ошибок не найдено. Отдельно проверено и подтверждено НЕ ошибкой (разобрано нарочной поломкой,
действующий код ловит): R1–R8 выше — каждое правило воспроизведено и защищено. Предыдущие
исправления сессии Codex 01.10 (QA-B01-012..018 — атомарный вход, бюджет попыток, проверочный
аккаунт стора, выдача OTP) живы и работают: прогнал их собственные тесты
(`test_auth_attempt_concurrency.py`, `test_otp_phone_concurrency.py`,
`test_auth_consumption_atomicity.py`, `test_otp_issuance_history.py`, `test_refresh_replay.py`,
`test_refresh_rotation_failure.py`) — 119 тестов зелёные на SQLite, 11 — на изолированном
PostgreSQL (`test_refresh_postgres_concurrency.py`, `test_refresh_replay_postgres.py`), без
единого изменения в самих тестах.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M7 | `MAX_OTP_ATTEMPTS_PER_PHONE = 15` → `1500` | `test_parallel_wrong_codes_cannot_cross_phone_wide_budget` | KILLED |
| M8 | Потребление кода `.values(code="", expires_at=utcnow())` → `.values(code=verified_code)` (код не гасится) | `test_concurrent_verification_issues_only_one_token_pair` (реальная гонка на PostgreSQL) | KILLED |
| M9 | Throttle `len(recent) >= 3` → `>= 300` | `test_parallel_code_issuance_sends_at_most_three_sms_per_minute` | KILLED |
| M10 | `if user and phone_looks_recycled(...)` → `if False and …` (проверка отключена) | `test_новый_владелец_номера_получает_чистый_аккаунт` | KILLED |
| M11 | `if not user or user.role != UserRole.passenger:` → `if not user:` (роль больше не проверяется) | `test_fixed_review_login_rejects_existing_privileged_account_without_mutating_it` | KILLED |
| M12 | `заслужил = by_tg or by_phone` → `заслужил = True` (admin всем подряд) | `test_foreign_lookalike_number_does_not_get_admin` | KILLED |
| M13 | `elif user.role == UserRole.admin and not заслужил and admin_keys:` → `elif False:` (снятие роли отключено) | `test_права_снимаются_когда_номер_убрали_из_настроек` | KILLED |
| M14 | Цикл удаления `DeviceToken` в `logout` заменён на `pass` | `test_logout_removes_device_and_web_push_tokens` | KILLED |

Полный прогон: `MUTATIONS KILLED 18/18` (см. карточку `security.py` — там же общая команда).

## Остаток и ограничения

- **Чужие коды верификации (`verify_code`-подобное) НЕ в этом файле.** Проверочные коды посадки в
  попутке/такси — отдельная механика в других роутерах, вне этого листа; здесь проверен только код
  ВХОДА (SMS и Telegram).
- Полный обход `_handle_admin_callback` на реальном Telegram (не заглушке `httpx`) не делал —
  секреты и реальные боты не трогаю по условиям листа; проверено по коду и существующим тестам
  (`test_telegram_owner_gate.py`, `test_every_admin_action_leaves_a_trace.py`).
- `_is_owner_telegram`/`_phone_key` (из `config.py`) сами по себе вне OWNS этого листа (не
  `auth.py`/`security.py`/`account.py`) — проверил их ИСПОЛЬЗОВАНИЕ в `auth.py`, не их собственный
  код построчно.
- Башкирский текст в этом файле не менялся (правок кода не потребовалось за пределами уже
  переведённых сообщений `herr`) — черновиков на проверку из этого файла нет.
