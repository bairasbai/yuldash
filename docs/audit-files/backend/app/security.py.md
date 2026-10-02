# Карточка: `backend/app/security.py`

- Статус: verified
- Лист: leaf-2.1
- Проверял: Sonnet 5 (leaf-2.1); принимал: Opus 5.5

## Назначение

Единая точка входа для всего, что касается «кто этот человек и можно ли ему верить»: нормализация
телефона (один человек — одна запись), выпуск/ротация/отзыв access- и refresh-токенов, защита
refresh-токена от повторного использования (кражи), серверная проверка каждого входящего JWT
(REST через `current_user`/`current_user_optional`, WebSocket через `authenticate_ws`), генерация
одноразовых кодов (SMS-OTP, реферальный код). Импортируется почти всеми роутерами (auth, rides,
instant, chat, admin и т.д.) как зависимость `Depends(current_user)`; `app/routers/auth.py` —
главный вызывающий код для входа/ротации/логаута, `app/account.py` читает `User`, которого
отдаёт этот модуль.

## Функции и разбор

| Функция / участок | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `bearer`, `bearer_optional` | 21–22 | Схемы `HTTPBearer`: обязательная и опциональная (для анонимных списков) | `bearer` сам бросает 403 `"Not authenticated"` при отсутствии заголовка — это текст библиотеки FastAPI, **не** `herr`, одноязычный | см. «Остаток» |
| `is_placeholder_phone` | 29–31 | Телефон ещё не задан, или это Telegram-плейсхолдер `tg<id>` | regex `^tg\d+$` | ок |
| `normalize_phone` | 34–64 | Один человек — одна запись номера независимо от формы ввода (`8…`/`7…`/`9…`/`+7 999…`) | Иностранные номера не трогает сверх очистки разделителей; плейсхолдер/пусто — как есть | ок |
| `make_token` | 67–83 | Выпускает access-JWT с `iat`/`exp`; если `issued_after` задан и «сейчас» не позже него — сдвигает `iat` на +1 мкс строго ПОСЛЕ границы | Закрывает гонку login→logout→login в одну и ту же миллисекунду (дешёвые часы Windows) | ок, тест R6/M6 |
| `_hash_refresh` | 86–87 | SHA-256 сырого refresh-токена — в БД хранится только хеш | — | ок |
| `issue_tokens` | 93–124 | Выдаёт пару access+refresh, пишет `RefreshToken` (хеш), обновляет `last_seen_at`; для `review_session` добавляет префикс `review.` к raw-токену | Если `user is None` или `review_session` запрошен для не-пассажира — 401 бабл (R3) | ок, тест R1/R3 |
| `lock_refresh_user` | 130–139 | Единый порядок блокировок User→RefreshToken; на SQLite — трюк с нулевым `UPDATE` ради захвата writer-lock (FOR UPDATE там no-op) | Используется входом/рефрешем/логаутом/recovery | ок |
| `_recovery_token` | 142–145 | HMAC-производный «дочерний» raw-токен окна восстановления ротации, привязан к `rotation_id` | Сохраняет префикс `review.`, если он был у родителя | ок |
| `rotate_refresh` | 148–224 | Ротация refresh-токена: 1) проверка формата `rotation_id`; 2) поиск владельца по хешу; 3) бан устройства (по `User.last_device_id`, не только по заголовку); 4) окно восстановления 120с при повторной попытке с тем же `rotation_id`; 5) атомарное «сожжение» (`UPDATE … WHERE revoked=False`) и выпуск новой пары | На каждом шаге — 401/422 с двуязычным `herr`; гонка двух одновременных ротаций ОДНИМ токеном: ровно одна побеждает | ок, тесты R1/R2/R4 |
| `revoke_all_refresh` | 227–235 | Logout «со всех устройств»: помечает все неотозванные refresh-токены юзера отозванными | — | ок |
| `_decode` | 252–254 | `jwt.decode` с обязательными `exp`/`sub` (`_JWT_OPTIONS`) | Токен без срока жизни отныне не живёт вечно (исторический фикс, волна 42) | ок |
| `verify_token` | 257–262 | Декодирует JWT → `user_id`. ПРЕДУПРЕЖДЕНИЕ в докстринге: не проверяет ревокацию/существование юзера | Только для мест, которым это явно подходит; для соединений — `authenticate_ws` | ок (само по себе); см. «Остаток» про вызывающих |
| `authenticate_ws` | 265–274 | Аутентификация WebSocket (нет `Depends`/`HTTPBearer`): декодирует + проверяет юзера и `_token_revoked` | Отозванный logout токен не открывает чат до истечения JWT | ок |
| `_token_revoked` | 277–291 | Токен недействителен, если: (а) это `review_session`, а роль уже не `passenger`; (б) `iat <= tokens_valid_from` (нестрогое ≤ — та самая граница logout); (в) `iat` отсутствует при наличии границы | Сравнение именно `<=`, не `<` — токен, выпущенный в ту же миллисекунду, что logout, тоже гасится | ок, тесты R3/R6 |
| `gen_otp` | 294–298 | 6-значный код через `secrets.choice` (не `random`) | `10^6` вариантов; CSPRNG — код нельзя предсказать по выборке | ок |
| `gen_referral_code` | 301–304 | 6 символов без `0/O/1/I` через `secrets.choice` | Не про безопасность входа — не в фокусе листа | ок |
| `current_user` | 307–324 | Главная REST-зависимость: декодирует токен → находит юзера → проверяет `_token_revoked` → требует НЕ-плейсхолдер телефон (403 `phone_required`) | До исправления этого листа три из четырёх отказов уходили одноязычным `HTTPException(status.HTTP_401_UNAUTHORIZED, "…")` — см. «Найденные ошибки» | исправлено, тест R5/M5 |
| `current_user_optional` | 327–343 | Как `current_user`, но без токена/при любой ошибке — тихо `None` (не бросает) | Для публичных списков: залогиненному прячем заблокированных, аноним видит всё | ок |

## Связи

- Вызывается `Depends(...)` почти во всех роутерах (`auth`, `rides`, `instant`, `chat`, `payments`,
  `admin`, …) — `current_user`/`current_user_optional`; WebSocket-хендлеры — `authenticate_ws`.
- `app/routers/auth.py`: `request_code`/`verify`/`tg_verify`/`refresh`/`logout` — основные вызывающие
  `issue_tokens`/`rotate_refresh`/`revoke_all_refresh`/`lock_refresh_user`.
- `app/antifraud.py`: `rotate_refresh` локально импортирует `guard_device_not_banned` (вторая половина
  защиты бана устройства — читает `User.last_device_id`, а не клиентский заголовок).
- Таблицы: `User` (`tokens_valid_from`, `last_device_id`, `role`, `phone`), `RefreshToken`
  (`token_hash`, `revoked`, `rotation_id_hash`, `rotated_at`, `expires_at`).
- `app/errors.py::herr` — источник двуязычного `detail={"ru","ba"}` для всех отказов этого файла.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Refresh-токен одноразовый: сожжённый не может снова стать годным (атомарный `UPDATE … WHERE revoked=False`) | `backend/tests/test_flows.py::test_refresh_rotation` | да — M1 |
| R2 | Окно восстановления ротации ограничено 120 секундами и привязано к конкретному `rotation_id` | `backend/tests/test_refresh_replay.py::test_recovery_window_boundary` | да — M2 |
| R3 | Проверочная (review) сессия стора навсегда остаётся пассажирской, даже если роль юзера потом изменилась | `backend/tests/test_review_session_scope.py::test_store_session_cannot_gain_admin_after_verified_telegram_login` | да — M3 |
| R4 | Бан устройства блокирует и продление сессии (`/auth/refresh`), а не только вход, даже если клиент перестал слать заголовок `X-Device-Id` | `backend/tests/test_a_ban_that_ends_at_the_door.py::test_ban_works_even_if_the_app_stops_sending_the_header` | да — M4 |
| R5 | Отказы 401 из `current_user`/`issue_tokens`/`rotate_refresh` звучат на двух языках | `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_invalid_token_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_token_of_missing_user_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_session_ended_by_logout_speaks_both_languages`, `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py::test_dead_refresh_token_speaks_both_languages` | да — M5 |
| R6 | Граница logout нестрогая (`<=`): токен, выпущенный РОВНО в миг выхода, тоже считается отозванным; новый токен после выхода выпускается строго позже границы | `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_token_issued_exactly_at_the_boundary_is_revoked`, `backend/tests/walk/l2_1/test_l2_1_token_logout_boundary.py::test_new_token_is_issued_strictly_after_the_logout_boundary` | да — M6 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E1 | Человек с башкирским интерфейсом, у которого истекла/завершилась сессия (самый частый отказ в приложении — проверяется на КАЖДОМ защищённом запросе), читал «Неверный токен» / «Пользователь не найден» / «Сессия завершена. Войди заново.» и отказ `/auth/refresh` только по-русски — клиент на одноязычный `detail`-строку обычно показывает общую заглушку вместо перевода | `client.get("/me", headers={"Authorization": "Bearer garbage"})` → `detail` была голой строкой, не `{"ru","ba"}` (см. `backend/tests/walk/l2_1/test_l2_1_bilingual_401s.py`, до фикса — RED) | 4 места в `current_user`/`issue_tokens`/`rotate_refresh` переведены на `herr(...)`, который всегда отдаёт `{"ru","ba"}`. Башкирский — черновой перевод модели (кроме «Ҡулланыусы табылманы», уже используемого в `app/routers/antifraud.py` — взято оттуда дословно для единообразия); на проверку Александру как носителю (см. отчёт) | `test_l2_1_bilingual_401s.py` (4 теста): RED (строка вместо словаря) → GREEN после правки; поломка M5 проверена: откат одной строки назад — тест падает |

Остальные правила файла (R1–R4, R6) — ошибок не найдено: каждое воспроизведено нарочной поломкой,
и действующий код её ловит (см. ниже). Отдельно проверено и подтверждено НЕ ошибкой, а
избыточной (в хорошем смысле) защитой: гонка одновременной ротации ОДНИМ refresh-токеном на
PostgreSQL (`test_refresh_postgres_concurrency.py`) прикрыта СРАЗУ тремя независимыми
механизмами — блокировкой строки `User`, блокировкой строки `RefreshToken` и атомарным условием
в финальном `UPDATE`; поочерёдное экспериментальное отключение любого ОДНОГО из них (методом
`tools/audit_mutation.py replay` на черновике, не вошедшим в сдаваемый `leaf-2.1.json`) не ломает
результат — ломается только все три сразу. Поэтому M1 проверяет этот `UPDATE` не гонкой, а более
прямым путём (сожжённый токен обязан оставаться сожжённым), а настоящую параллельную гонку с реальным
PostgreSQL для соседнего правила видно в карточке `routers/auth.py` (R2/M8 — одновременная проверка
OTP-кода).

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | `revoked == False` → `revoked == True` в финальном `UPDATE` ротации (сожжённый токен никогда не признаётся сожжённым) | `test_refresh_rotation` | KILLED |
| M2 | Верхняя граница окна восстановления `REFRESH_RECOVERY_SECONDS` заменена на `999999999` (окно фактически бесконечно) | `test_recovery_window_boundary` | KILLED |
| M3 | Проверка `review_session и роль ≠ passenger` в `_token_revoked` отключена (`if False:`) | `test_store_session_cannot_gain_admin_after_verified_telegram_login` | KILLED |
| M4 | Вызов `guard_device_not_banned(session, owner.last_device_id)` в `rotate_refresh` заменён на `None` | `test_ban_works_even_if_the_app_stops_sending_the_header` | KILLED |
| M5 | Один `herr(...)` в `current_user` откатён обратно на одноязычный `HTTPException` | `test_session_ended_by_logout_speaks_both_languages` | KILLED |
| M6 | `<=` заменено на `<` в финальном сравнении `_token_revoked` | `test_token_issued_exactly_at_the_boundary_is_revoked` | KILLED |

Полный прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-2.1.json`
→ `MUTATIONS KILLED 18/18` (все 18 поломок листа, включая три из этого файла — см. карточки
`routers/auth.py` и `account.py`).

## Остаток и ограничения

- **ВНЕ ЗОНЫ — находка в чужом файле.** `tests/test_every_refusal_speaks_both_languages.py` (корень
  `tests/`, не в OWNS этого листа) ищет одноязычные отказы по литеральному трёхзначному коду сразу
  после `HTTPException(` (`HTTPException(403, "…")` или константа). Символьная форма
  `HTTPException(status.HTTP_401_UNAUTHORIZED, "…")` — единственная такая во всём `app/` (была
  только в этом файле, до правки этого листа) — регексом не ловится вовсе: ни один из трёх шаблонов
  функции `_отказы_на_одном_языке` не видит `\d{3}` на месте `status.HTTP_401_UNAUTHORIZED`. Это
  слепое пятно самого сторожа, а не моя находка E1 (ту я уже исправил). Ситуация человека: новый
  одноязычный отказ в таком виде в ЛЮБОМ файле `app/` проедет мимо сторожа незамеченным. Предлагаемая
  правка: в `_отказы_на_одном_языке` добавить третий шаблон
  `HTTPException\(\s*status\.[A-Z_0-9]+\s*,\s*["\']([^"\']{4,})` рядом с существующими двумя.
  Тест на правку: расширить `test_сторож_отличает_админскую_ручку_от_обычной` случаем
  `HTTPException(status.HTTP_401_UNAUTHORIZED, "Текст")`.
- **ВНЕ ЗОНЫ — не моё, но рядом.** `bearer = HTTPBearer(auto_error=True)` (строка 21): если
  заголовок `Authorization` отсутствует ВООБЩЕ, FastAPI сам бросает `HTTPException(403, "Not
  authenticated")` ДО входа в `current_user` — это текст библиотеки, по-английски, в обход `herr`.
  Не правил намеренно: лечится только заменой на `auto_error=False` + ручной проверкой внутри
  `current_user`/`current_user_optional`, а эта зависимость используется буквально везде — цена
  ошибки при самостоятельной правке общей точки входа выше, чем у четырёх точечных строк E1.
  Предлагаемая правка и тест — тому, кто возьмёт это отдельной, более широкой задачей (нужна
  регрессионная проверка по всем роутерам, а не одному листу).
- `verify_token` — проверил, что сам по себе он корректно документирует своё ограничение
  (не проверяет ревокацию/существование юзера). Кто и как его вызывает по всему проекту — не
  проверял (вне своих трёх файлов); при использовании не по назначению (для чего-то похожего на
  REST/WS авторизацию вместо `current_user`/`authenticate_ws`) это было бы дырой, но её наличие
  не подтверждено.
- `gen_referral_code`/`normalize_phone`/`gen_otp` формально проверены на корректность (формат,
  источник случайности по докстрингу), но не отдельной нарочной поломкой — они не про вход/личные
  данные впрямую (реферальный код) или уже многократно покрыты существующими тестами проекта
  (`normalize_phone` — несколькими файлами вне этого листа, напр. `test_phone_is_one_person.py`).
