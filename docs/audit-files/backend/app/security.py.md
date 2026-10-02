# Карточка: `backend/app/security.py`

- Статус: verified
- Лист: leaf-2.1
- Проверял: Sonnet 5 (leaf-2.1); принимал: Opus 5.5; независимое ревью: Opus (REQUEST_CHANGES → доработано)

## Назначение

Единая точка входа для всего, что касается «кто этот человек и можно ли ему верить»: нормализация
телефона (один человек — одна запись), выпуск/ротация/отзыв access- и refresh-токенов, защита
refresh-токена от повторного использования (кражи, включая отзыв ВСЕЙ семьи сессий при обнаружении),
серверная проверка каждого входящего JWT (REST через `current_user`/`current_user_optional`,
WebSocket через `authenticate_ws`), генерация одноразовых кодов (SMS-OTP, реферальный код).
Импортируется почти всеми роутерами (auth, rides, instant, chat, admin и т.д.) как зависимость
`Depends(current_user)`; `app/routers/auth.py` — главный вызывающий код для входа/ротации/логаута,
`app/account.py` читает `User`, которого отдаёт этот модуль.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_SESSION_STALE_RU/BA` | 26–27 | Один человеческий текст «Сессия устарела. Войди заново.» на разные внутренние причины 401 | Независимое ревью: жаргон «Refresh-токен» на экране запрещён правилом тона §9 | ок |
| `_BilingualBearer` | 30–52 | Подкласс `HTTPBearer`: ловит исключение библиотеки (нет заголовка/не Bearer-схема) и бросает СВОЙ 401 с `detail={"ru","ba"}` и `WWW-Authenticate: Bearer` | На разных версиях FastAPI библиотека сама отвечала то 401 "Not authenticated" (англ.), то 403 — независимо от версии теперь всегда 401 двуязычно | исправлено (пункт «в»), тест R5/M26 |
| `bearer`, `bearer_optional` | 55–56 | Схемы для `Depends`: обязательная (`_BilingualBearer`) и опциональная (для анонимных списков, не тронута) | — | ок |
| `is_placeholder_phone` | 63–65 | Телефон ещё не задан, или это Telegram-плейсхолдер `tg<id>` | regex `^tg\d+$` | ок |
| `normalize_phone` | 68–98 | Один человек — одна запись номера независимо от формы ввода (`8…`/`7…`/`9…`/`+7 999…`) | Иностранные номера не трогает сверх очистки разделителей; плейсхолдер/пусто — как есть | ок |
| `make_token` | 101–117 | Выпускает access-JWT с `iat`/`exp`; если `issued_after` задан и «сейчас» не позже него — сдвигает `iat` на +1 мкс строго ПОСЛЕ границы | Закрывает гонку login→logout→login в одну и ту же миллисекунду | ок, тест R6/M6 |
| `_hash_refresh` | 120–121 | SHA-256 сырого refresh-токена — в БД хранится только хеш | — | ок |
| `issue_tokens` | 127–160 | Выдаёт пару access+refresh, пишет `RefreshToken` (хеш), обновляет `last_seen_at`; для `review_session` добавляет префикс `review.` к raw-токену | `user is None` или `review_session` для не-пассажира → 401 (R3) | ок |
| `lock_refresh_user` | 165–174 | Единый порядок блокировок User→RefreshToken; на SQLite — трюк с нулевым `UPDATE` ради захвата writer-lock | Используется входом/рефрешем/логаутом/recovery | ок |
| `_recovery_token` | 177–180 | HMAC-производный «дочерний» raw-токен окна восстановления ротации, привязан к `rotation_id` | Сохраняет префикс `review.`, если он был у родителя | ок |
| `rotate_refresh` | 183–288 | Ротация refresh-токена: 1) формат `rotation_id`; 2) владелец по хешу; 3) бан устройства (по памяти сервера); 4) окно восстановления 120с; 5) при провале — РАЗДЕЛЬНО: гонка честного клиента (нонс не подошёл, но ВНУТРИ окна) просто 401, а предъявление вне окна С rotation_id — ещё и отзыв всей семьи сессий (Б-3); 6) атомарное сожжение и выпуск новой пары | 401 (двуязычно) на каждом шаге; гонка двух одновременных ротаций ОДНИМ токеном — ровно одна побеждает | ок, тесты R1/R2/R7 |
| `revoke_all_refresh` | 291–299 | Logout «со всех устройств»: помечает все неотозванные refresh-токены юзера отозванными | — | ок |
| `_revoke_family_on_reuse` | 302–323 | Б-3: при обнаружении кражи — граница `tokens_valid_from=now` + отзыв всех refresh-токенов + лог + best-effort уведомление (push+SMS) | Коммитит сам, до `raise`; ошибка уведомления не мешает ответить 401 | ок, тест R7/M23 |
| `_decode` | 340–342 | `jwt.decode` с обязательными `exp`/`sub` (`_JWT_OPTIONS`) | Токен без срока жизни не живёт вечно (исторический фикс) | ок |
| `verify_token` | 345–350 | Декодирует JWT → `user_id`. Докстринг: не проверяет ревокацию/существование юзера | Только там, где это явно уместно; для соединений — `authenticate_ws` | ок (само по себе); кто и как его вызывает по проекту — не проверял (вне своих файлов) |
| `authenticate_ws` | 353–362 | Аутентификация WebSocket: декодирует + проверяет юзера и `_token_revoked` | Отозванный logout токен не открывает чат до истечения JWT | ок |
| `_token_revoked` | 365–379 | Недействителен, если: (а) `review_session`, а роль уже не `passenger`; (б) `iat <= tokens_valid_from` (нестрогое ≤); (в) `iat` отсутствует при наличии границы | Сравнение именно `<=` — токен, выпущенный в ту же миллисекунду, что logout, тоже гасится | ок, тесты R3/R6 |
| `gen_otp` | 382–386 | 6-значный код через `secrets.choice` (не `random`) | `10^6` вариантов; CSPRNG | ок |
| `gen_referral_code` | 389–392 | 6 символов без `0/O/1/I` через `secrets.choice` | Не про вход — вне фокуса листа | ок |
| `current_user` | 395–412 | Декодирует токен → находит юзера → `_token_revoked` → требует НЕ-плейсхолдер телефон (403 `phone_required`, код для клиента, не текст человеку) | Отказы — двуязычно через `herr` | ок, тест R5/M5 |
| `current_user_optional` | 415–431 | Как `current_user`, но без токена/при ошибке — тихо `None` | Для публичных списков | ок |

## Связи

- Вызывается `Depends(...)` почти во всех роутерах — `current_user`/`current_user_optional`;
  WebSocket-хендлеры — `authenticate_ws`.
- `app/routers/auth.py`: `request_code`/`verify`/`tg_verify`/`refresh`/`logout` — вызывающие
  `issue_tokens`/`rotate_refresh`/`revoke_all_refresh`/`lock_refresh_user`.
- `app/antifraud.py`: `rotate_refresh` локально импортирует `guard_device_not_banned`.
- `_revoke_family_on_reuse` локально импортирует `app/services.py::push_notification, send_text`.
- Таблицы: `User` (`tokens_valid_from`, `last_device_id`, `role`, `phone`), `RefreshToken`
  (`token_hash`, `revoked`, `rotation_id_hash`, `rotated_at`, `expires_at`).
- `app/errors.py::herr` — источник двуязычного `detail={"ru","ba"}` для всех отказов этого файла.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Финальная ротация refresh-токена завершается только для ещё НЕ сожжённого (`revoked=False`) токена — базовый гейт завершения, на котором держится одноразовость | `backend/tests/test_flows.py::test_refresh_rotation` | да — M1 (см. «Остаток»: одной поломкой изолированно НЕ проверяется защита именно от ПАРАЛЛЕЛЬНОГО повтора — она тройная) |
| R2 | Окно восстановления ротации ограничено 120 секундами и привязано к конкретному `rotation_id` | `backend/tests/test_refresh_replay.py::test_recovery_window_boundary` | да — M2 |
| R3 | Проверочная (review) сессия стора навсегда остаётся пассажирской, даже если роль юзера потом изменилась | `backend/tests/test_review_session_scope.py::test_store_session_cannot_gain_admin_after_verified_telegram_login` | да — M3 |
| R4 | Бан устройства блокирует и продление сессии (`/auth/refresh`), даже если клиент перестал слать заголовок `X-Device-Id` | `backend/tests/test_a_ban_that_ends_at_the_door.py::test_ban_works_even_if_the_app_stops_sending_the_header` | да — M4 |
| R5 | Отказы 401 звучат на двух языках — включая случай ПОЛНОСТЬЮ отсутствующего заголовка Authorization (пункт «в» независимого ревью: раньше тут отвечала сама библиотека FastAPI, по-английски или кодом 403 в зависимости от версии) | `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_invalid_token_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_token_of_missing_user_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_session_ended_by_logout_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_dead_refresh_token_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_missing_authorization_header_speaks_both_languages_and_names_the_scheme` | да — M5, M26 |
| R6 | Граница logout нестрогая (`<=`): токен, выпущенный РОВНО в миг выхода, тоже отозван; новый токен после выхода выпускается строго позже границы | `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_token_issued_exactly_at_the_boundary_is_revoked`, `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_new_token_is_issued_strictly_after_the_logout_boundary`, `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_token_issued_after_the_boundary_survives`, `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_token_without_iat_is_revoked_once_a_boundary_exists`, `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_no_boundary_means_nothing_is_revoked_yet` | да — M6 |
| R7 | Повтор уже сожжённого refresh-токена с НЕПОДХОДЯЩИМ rotation_id ВНЕ 120-секундного окна восстановления — сигнал кражи (RFC 9700 §4.14.2): отзывается вся семья сессий человека, а не только эта попытка | `backend/tests/walk/l2_1/test_l2_1_refresh_reuse_revokes_family.py::test_replay_with_a_wrong_rotation_id_outside_the_window_revokes_the_family` (отзыв семьи вне окна), `backend/tests/walk/l2_1/test_l2_1_refresh_reuse_revokes_family.py::test_plain_replay_without_a_rotation_id_does_not_touch_other_sessions` (обычный повтор без nonce НЕ трогает семью), `backend/tests/walk/l2_1/test_l2_1_refresh_reuse_revokes_family.py::test_a_wrong_nonce_inside_the_recovery_window_is_not_treated_as_theft` (нонс-гонка ВНУТРИ окна тоже НЕ трогает семью) | да — M23 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E1 | Человек с башкирским интерфейсом, у которого истекла/завершилась сессия (самый частый отказ в приложении), читал «Неверный токен» / «Сессия завершена...» / отказ `/auth/refresh` только по-русски | `client.get("/me", headers={"Authorization": "Bearer garbage"})` → `detail` была строкой, не словарём | 9 мест в `current_user`/`issue_tokens`/`rotate_refresh` переведены на `herr(...)`. По итогам независимого ревью жаргон «Refresh-токен» убран: 6 мест используют уже существующую в файле человеческую пару «Не получилось продлить вход. Войди заново.» (не новый черновик), «Неверный токен» заменён на новую пару `_SESSION_STALE_RU/BA` | `test_l2_1_bilingual_401s.py`: RED (строка вместо словаря) → GREEN; M5 KILLED |
| E2 | Пункт «в» независимого ревью: при ПОЛНОСТЬЮ отсутствующем заголовке Authorization отвечала сама библиотека FastAPI — 401 "Not authenticated" по-английски на одной версии пакета, 403 на другой. Карточка листа ранее ошибочно относила это «вне зоны» — `bearer` объявлен в этом же файле | `client.get("/me")` без заголовка вовсе | `_BilingualBearer` — подкласс `HTTPBearer`, перехватывает и отвечает 401 + `{"ru","ba"}` + `WWW-Authenticate: Bearer` независимо от версии FastAPI | `test_missing_authorization_header_speaks_both_languages_and_names_the_scheme`: RED (401/403 англ. текстом) → GREEN; M26 KILLED |
| E3 | Б-3 независимого ревью: повтор украденного refresh-токена ловился (401), но ничего не исправлял — если вор продлевал сессию первым, его собственная (уже отдельная) сессия продолжала жить все 90 дней, пока хозяин сам не нажмёт «выйти со всех устройств» | Honest-ротация с `rotation_id=A` → успех; повтор СТАРОГО токена с `rotation_id=B` ПОЗЖЕ 120с окна → раньше просто 401, семья жила дальше | `_revoke_family_on_reuse`: при обнаружении — `tokens_valid_from=now` + отзыв всех refresh + лог + уведомление. Специально НЕ ловит: обычный повтор без `rotation_id` (частый штатный случай, см. `test_refresh_rotation`) и честную гонку двух запросов ОДНОГО клиента внутри окна (см. `test_refresh_replay_postgres.py::test_parallel_recovery_intents_have_one_child[False]` — две настоящие параллельные транзакции на PostgreSQL) | `test_l2_1_refresh_reuse_revokes_family.py`: RED (чужая сессия и новый преемник оставались рабочими) → GREEN; M23 KILLED |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `revoked == False` → `revoked == True` в финальном `UPDATE` ротации. Честно: ломает завершение ЛЮБОГО (не только повторного) refresh — это проверка самого гейта завершения, не изолированная проверка анти-replay (см. «Остаток») | `test_refresh_rotation` | KILLED |
| M2 | Верхняя граница окна восстановления `REFRESH_RECOVERY_SECONDS` заменена на `999999999` | `test_recovery_window_boundary` | KILLED |
| M3 | Проверка `review_session и роль ≠ passenger` в `_token_revoked` отключена (`if False:`) | `test_store_session_cannot_gain_admin_after_verified_telegram_login` | KILLED |
| M4 | Вызов `guard_device_not_banned(session, owner.last_device_id)` в `rotate_refresh` заменён на `None` | `test_ban_works_even_if_the_app_stops_sending_the_header` | KILLED |
| M5 | Один `herr(...)` в `current_user` откатён обратно на одноязычный `HTTPException` | `test_session_ended_by_logout_speaks_both_languages` | KILLED |
| M6 | `<=` заменено на `<` в финальном сравнении `_token_revoked` | `test_token_issued_exactly_at_the_boundary_is_revoked` | KILLED |
| M23 | Условие `rotation_id is not None and outside_window` перед `_revoke_family_on_reuse` заменено на `False` | `test_replay_with_a_wrong_rotation_id_outside_the_window_revokes_the_family` | KILLED |
| M26 | `bearer = _BilingualBearer(auto_error=True)` откатан на `bearer = HTTPBearer(auto_error=True)` | `test_missing_authorization_header_speaks_both_languages_and_names_the_scheme` | KILLED |

Полный прогон: `python audit_mutation.py --root . replay --spec docs/audit-mutations/leaf-2.1.json`
→ `MUTATIONS KILLED 29/29` (все поломки листа — см. также карточки `routers/auth.py` и `account.py`).

## Остаток и ограничения

- **R1/M1 — честно про предел одной поломки.** Экспериментально (отдельно от сдаваемого набора,
  методом `audit_mutation.py replay` на черновике) проверил: защита от НАСТОЯЩЕГО параллельного
  повторного использования одного refresh-токена на PostgreSQL прикрыта СРАЗУ тремя независимыми
  механизмами — блокировкой строки `User`, блокировкой строки `RefreshToken` и атомарным условием
  в финальном `UPDATE`. Поочерёдное отключение любого ОДНОГО из них не ломает результат гонки
  (`test_refresh_postgres_concurrency.py::test_two_connections_rotate_once_even_if_first_issuer_fails`)
  — ломаются все три сразу. Поэтому M1 проверяет сам гейт `revoked=False` более прямым путём
  (успешный refresh вообще перестаёт работать), а не пытается изолированно доказать защиту от
  гонки одной мутацией — это физически невозможно при такой тройной избыточности без одновременной
  правки всех трёх мест. Настоящая параллельная гонка с реальным PostgreSQL в этом листе
  ДОКАЗАНА для соседнего правила — R2 `routers/auth.py` (M8, одновременная проверка OTP-кода).
- Решение сузить R7 (не ловить гонку ВНУТРИ окна восстановления и обычный повтор без nonce) —
  осознанный компромисс: более широкая версия (ловить ЛЮБОЙ нонс-мисматч) ломала существующие,
  не входящие в OWNS этого листа тесты `test_flows.py::test_refresh_rotation`,
  `test_refresh_rotation_failure.py::test_lost_committed_rotation_response_is_not_recoverable_with_old_token`
  и `test_refresh_replay_postgres.py::test_parallel_recovery_intents_have_one_child[False]` —
  они намеренно и обоснованно проверяют, что честная гонка/повтор СВОЕГО клиента не наказывается.
  Правил их не трогал (чужая зона).
- `verify_token` — проверил, что сам по себе он корректно документирует своё ограничение. Кто и
  как его вызывает по всему проекту — не проверял (вне трёх файлов листа).
- `gen_referral_code`/`normalize_phone`/`gen_otp` не про вход/личные данные впрямую или уже
  многократно покрыты тестами вне этого листа — отдельной нарочной поломки не заводил.
