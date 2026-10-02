# Карточка: `backend/app/routers/promo.py`

- Статус: verified
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3); принимал: Opus 5.5 (круг 1 — REQUEST_CHANGES; круг 2 — принят
  на слитом дереве 35f79b35; круг 3 — доп. находки того же ревью; круг 4 — ведущий поймал, что
  HMAC из круга 3 без переходного плана обнулил бы лимит для всех, кто уже брал код)

## Назначение

Именные промокоды и кампании (рычаг роста для блогеров/партнёров/акций). Промокод — инструмент
привлечения, НЕ прямой доход и не штраф: отказ ввести код ничего не ломает. Три вида бонуса:
`welcome` (чистая атрибуция), `boost` (бесплатные поднятия поездки через общий механизм
реферала), `taxi_ride` (скидка в рублях на поездку в такси — считает `app/promo_ride.py`).
Один код на всю жизнь аккаунта.

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_PHONE_CLAIM_HMAC_DOMAIN` 🆕 | 100 | Доменный префикс сообщения HMAC (не версия алгоритма) | развести назначения ОДНОГО секрета `jwt_secret` (круг 4) | ок (R8) |
| `_phone_claim_key` 🆕 | 103–127 | HMAC-SHA256(jwt_secret, домен+телефон) для `PromoClaimLog` | НЕ голый `_phone_key()` (исправленная ошибка R6); смена `jwt_secret` требует пересчёта — см. «Остаток» | ок (R6, R8, исправленная ошибка) |
| `_promo_valid_until` | 131–140 | Срок кампании → UTC; дата БЕЗ времени = КОНЕЦ суток по Уфе | исправленная ошибка B-3 | ок (R4) |
| `_user_is_live` | 142–173 | «Живой» ли приведённый | переиспользует `referral._live_driver_trips` | ок |
| `_promo_counts` | 175–181 | (applied, active) по коду | — | ок |
| `_apply_message` | 183–213 | Дружелюбное RU/BA сообщение | для boost — говорит, сколько НАЧИСЛЕНО реально | ок |
| `promo_apply` | 216–344 | Применить код: один на жизнь аккаунта | порядок проверок; след ищется ПО ОБОИМ видам ключа на время перехода (R8); boost-кредит атомарным UPDATE (R5) | ок (R1, R2, R3, R5, R8) |
| `promo_mine` | 346–376 | Мой применённый код + судьба скидки | — | ок |
| `promo_stats` | 378–406 | Статистика кода | только владелец или админ | ок |
| `_require_admin` | 408–411 | 403, если не админ | — | ок |
| `_promo_admin` | 413–439 | Карточка кампании для админа | — | ок |
| `admin_promo_create` | 441–487 | Создать код/кампанию | `limit_per_user` ВСЕГДА 1; `valid_until` через `_promo_valid_until` | ок |
| `admin_promo_list` | 489–495 | Все коды со счётчиками | — | ок |
| `admin_promo_status` | 497–510 | Вкл/выкл кампанию | — | ок |
| `admin_promo_update` | 512–537 | Правка кампании | `valid_until` через `_promo_valid_until` | ок |

**Вне этого файла, но той же R6/R7/R8 истории:** `backend/alembic/versions/promo_claim_hmac_20261002.py` 🆕
(круг 4) — переводит СТАРЫЕ (до круга 3) открытые ключи `PromoClaimLog.phone_key` в HMAC задним
числом на уже накопленных данных (152-ФЗ: нельзя оставить номера открытым текстом до
естественной чистки по сроку кампании). Импортирует и зовёт ровно `_phone_claim_key` отсюда —
формула HMAC не продублирована второй раз.

## Связи

Экраны: `PromoCodeScreen.kt`, `AdminPromoScreen.kt`. Таблицы: `PromoCode`, `PromoRedemption`
(UNIQUE user_id), `PromoClaimLog` (след по HMAC телефона/устройству). Делит механизм
начисления и потолок `MAX_REFERRAL_CREDITS` с `routers/referral.py` (boost). Скидку на такси
считает и списывает `app/promo_ride.py`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Общий лимит кампании не превышается под гонкой (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_concurrent_apply_respects_total_limit | да — M9 |
| R2 | Владелец кампании не может активировать свой же код | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_owner_cannot_apply_own_code | да — M10 |
| R3 | Boost-бонус не выдаётся сверх общего потолка на руках | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_boost_grant_capped_at_wallet_limit | да — M11 |
| R4 | Срок кампании: часовой пояс Уфы + дата без времени = конец суток (B-3) | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_promo_valid_until_treats_date_only_midnight_as_end_of_day, ::test_promo_valid_until_leaves_explicit_time_alone, ::test_promo_deadline_survives_repeated_date_only_resaves | да — M12 |
| R5 | Boost-кредит не теряет параллельное реферальное начисление тому же человеку | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_boost_credit_survives_concurrent_referral_grant | да — M32 |
| R6 | `PromoClaimLog.phone_key` — HMAC телефона, не сам номер цифрами | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_phone_claim_key_is_not_reversible_to_the_number | да — M33 |
| R7 | Миграция `promo_claim_hmac` переводит старые открытые ключи в HMAC, не трогает уже-HMAC строки, переживает повторный прогон | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_hmac_migration_converts_legacy_keys_and_is_idempotent | да — M37 |
| R8 | `promo_apply` ловит повтор И по старому открытому, И по новому HMAC-ключу (переходный период до миграции данных) | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_legacy_plaintext_claim_still_blocks_reapply_before_and_after_migration | да — M36 |

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| B-3 | Дата кампании без времени трактовалась как полночь UTC−5 — срок съезжал и дрейфовал | test_promo_valid_until_treats_date_only_midnight_as_end_of_day | `_promo_valid_until`, та же логика, что у купонов | падал бы → проходит |
| R5 (найдено круг 2, исправлено круг 3) | `promo_apply` считал новый `referral_credits` от значения, прочитанного ещё в начале запроса (`user.referral_credits` через `current_user`). Параллельное реферальное начисление ЭТОМУ ЖЕ человеку (как рефереру) успевало записать своё +1 между чтением и записью здесь — и терялось: запись boost переписывала его устаревшим числом | test_boost_credit_survives_concurrent_referral_grant (две сессии читают до коммита любой — та же техника, что у существующего `test_lifetime_cap_counts_every_grant`, настоящие потоки не нужны: дело не в блокировке строки, а в том, считает ли UPDATE новое значение НА СЕРВЕРЕ БД) | атомарный `UPDATE ... SET referral_credits = CASE WHEN ... THEN MAX ELSE current+perk END` вместо чтения-прибавления-записи в Python | тест падал бы (итог 8 вместо 9) → проходит |
| R6 (найдено круг 3) | `PromoClaimLog.phone_key` хранил `_phone_key(phone)` — тот ТОЛЬКО нормализует формат номера (код страны/пробелы), результат — цифры САМОГО номера. Docstring модели обещает «по ключу человека не найти, если не знать номер заранее» — обещание было неправдой: у российских номеров ограниченный диапазон, значит и голый SHA256 подбирался бы перебором по справочнику, а то, что хранилось, была даже не хэш-функция, просто форматирование | test_phone_claim_key_is_not_reversible_to_the_number | `_phone_claim_key`: HMAC-SHA256 с секретом приложения (`settings.jwt_secret`) поверх `_phone_key()` — детерминированно (тот же номер → тот же ключ, иначе повтор нечем ловить), но не обратимо без секрета | тест падал бы (`digits in key`) → проходит |
| R7/R8 (найдено ведущим при ревью круга 3, почина — круг 4) | Переход на HMAC в круге 3 сам по себе — дыра: на проде уже лежат строки со СТАРЫМ открытым ключом; новая проверка их не находит → лимит «один код на номер» молча обнулился бы для КАЖДОГО, кто уже когда-то брал промокод. Плюс сам HMAC брал `jwt_secret` напрямую, без разделения назначений | test_legacy_plaintext_claim_still_blocks_reapply_before_and_after_migration, test_hmac_migration_converts_legacy_keys_and_is_idempotent | (R8) `promo_apply` ищет совпадение ключа ПО ОБОИМ видам разом; (R7) миграция `promo_claim_hmac` переводит все старые строки в HMAC задним числом (152-ФЗ), идемпотентно, downgrade — честный no-op; HMAC теперь поверх `_PHONE_CLAIM_HMAC_DOMAIN + digits`, не голых цифр | тесты падали бы (старая строка молча переставала ловить повтор; повторный прогон миграции портил уже сконвертированные строки) → проходят |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | В `promo_apply` сняты блокировка кода И условие лимита в CAS | test_concurrent_apply_respects_total_limit (PostgreSQL) | KILLED |
| M10 | В `promo_apply` отключена проверка «свой код» | test_owner_cannot_apply_own_code | KILLED |
| M11 | В `promo_apply` снят потолок `MAX_REFERRAL_CREDITS` | test_boost_grant_capped_at_wallet_limit | KILLED |
| M12 | В `_promo_valid_until` снят сдвиг «дата без времени = конец суток» | test_promo_valid_until_treats_date_only_midnight_as_end_of_day | KILLED |
| M32 | В `promo_apply` атомарный CASE-UPDATE boost-кредита заменён на «прочитал-прибавил-записал» | test_boost_credit_survives_concurrent_referral_grant | KILLED |
| M33 | `_phone_claim_key` снова возвращает голый `_phone_key()` вместо HMAC (без домена) | test_phone_claim_key_is_not_reversible_to_the_number | KILLED |
| M36 | В `promo_apply` условие сужено до только нового HMAC-ключа (убран старый открытый) | test_legacy_plaintext_claim_still_blocks_reapply_before_and_after_migration (PostgreSQL) | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
все мутации этого файла KILLED (общий счёт листа — в отчёте ведущему). M37 (R7, миграция
`promo_claim_hmac` снимает проверку «уже HMAC» — повторный прогон портит данные, ловит
`test_hmac_migration_converts_legacy_keys_and_is_idempotent`) — в том же JSON, но `file` —
`backend/alembic/versions/promo_claim_hmac_20261002.py`, не этот роутер: `check-cards`
сверяет поломку с ТЕМ файлом, который она ломает, а не с тем, что его вызывает. У миграций в
этом проекте нет прецедента отдельной карточки (`docs/audit-files/` не знает alembic ни разу) —
её правка документирована прозой здесь и в самом файле миграции, а не отдельной карточкой.

## Остаток и ограничения

Все восемь правил защищены тестами и нарочными поломками, включая реальную гонку на
PostgreSQL (R1) и две проверки перехода на HMAC на изолированном PostgreSQL (R7, R8). Полный
существующий денежный сюит файла (`test_promo.py`, `test_promo_cannot_be_milked.py`,
`test_promo_taxi.py`) прогнан и зелёный на SQLite и изолированном PostgreSQL.

**Смена `jwt_secret` требует пересчёта (важно для эксплуатации).** `_phone_claim_key` детерминирован
только ПОКА секрет не менялся: смена `jwt_secret` (ротация, компрометация) делает ВСЕ уже
сохранённые ключи в `PromoClaimLog` несовпадающими — `promo_apply` перестанет находить строки,
записанные до смены, и лимит «один код на номер» для них молча обнулится, как и было с открытым
ключом до круга 3. Если `jwt_secret` когда-нибудь сменится — нужна ЕЩЁ ОДНА миграция по образцу
`promo_claim_hmac` (пересчитать HMAC старым секретом невозможно постфактум, так что либо хранить
историю секретов для разового пересчёта в момент ротации, либо принять разовый сброс этого
конкретного анти-абуз-счётчика как цену ротации — решение за ведущим, не изобретаю здесь).

**ВНЕ ЗОНЫ, но отфлагованное этим кругом в круге 3 — почина в круге 4:**
`backend/tests/test_promo_cannot_be_milked.py:82` принадлежал не-OWNS файлу, но ведущий расширил
зону на круге 4 — теперь исправлено: сравнение заменено на
`promo_router._phone_claim_key("+79995551491")`, добавлена проверка «самого номера в ключе нет».
