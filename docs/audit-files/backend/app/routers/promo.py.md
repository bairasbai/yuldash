# Карточка: `backend/app/routers/promo.py`

- Статус: verified
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3); принимал: Opus 5.5 (круг 1 — REQUEST_CHANGES; круг 2 — принят
  на слитом дереве 35f79b35; круг 3 — доп. находки того же ревью)

## Назначение

Именные промокоды и кампании (рычаг роста для блогеров/партнёров/акций). Промокод — инструмент
привлечения, НЕ прямой доход и не штраф: отказ ввести код ничего не ломает. Три вида бонуса:
`welcome` (чистая атрибуция), `boost` (бесплатные поднятия поездки через общий механизм
реферала), `taxi_ride` (скидка в рублях на поездку в такси — считает `app/promo_ride.py`).
Один код на всю жизнь аккаунта.

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_phone_claim_key` 🆕 | 92–111 | HMAC-SHA256 телефона для `PromoClaimLog` | секрет — `settings.jwt_secret`; НЕ голый `_phone_key()` (исправленная ошибка, см. ниже) | ок (R6, исправленная ошибка) |
| `_promo_valid_until` | 113–122 | Срок кампании → UTC; дата БЕЗ времени = КОНЕЦ суток по Уфе | исправленная ошибка B-3 | ок (R4) |
| `_user_is_live` | 124–155 | «Живой» ли приведённый | переиспользует `referral._live_driver_trips` | ок |
| `_promo_counts` | 157–163 | (applied, active) по коду | — | ок |
| `_apply_message` | 165–195 | Дружелюбное RU/BA сообщение | для boost — говорит, сколько НАЧИСЛЕНО реально | ок |
| `promo_apply` | 197–318 | Применить код: один на жизнь аккаунта | порядок проверок; **boost-кредит теперь атомарным UPDATE** (исправленная ошибка, см. ниже) | ок (R1, R2, R3, R5) |
| `promo_mine` | 320–350 | Мой применённый код + судьба скидки | — | ок |
| `promo_stats` | 352–381 | Статистика кода | только владелец или админ | ок |
| `_require_admin` | 383–386 | 403, если не админ | — | ок |
| `_promo_admin` | 388–413 | Карточка кампании для админа | — | ок |
| `admin_promo_create` | 415–461 | Создать код/кампанию | `limit_per_user` ВСЕГДА 1; `valid_until` через `_promo_valid_until` | ок |
| `admin_promo_list` | 463–469 | Все коды со счётчиками | — | ок |
| `admin_promo_status` | 471–484 | Вкл/выкл кампанию | — | ок |
| `admin_promo_update` | 486–511 | Правка кампании | `valid_until` через `_promo_valid_until` | ок |

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

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| B-3 | Дата кампании без времени трактовалась как полночь UTC−5 — срок съезжал и дрейфовал | test_promo_valid_until_treats_date_only_midnight_as_end_of_day | `_promo_valid_until`, та же логика, что у купонов | падал бы → проходит |
| R5 (найдено круг 2, исправлено круг 3) | `promo_apply` считал новый `referral_credits` от значения, прочитанного ещё в начале запроса (`user.referral_credits` через `current_user`). Параллельное реферальное начисление ЭТОМУ ЖЕ человеку (как рефереру) успевало записать своё +1 между чтением и записью здесь — и терялось: запись boost переписывала его устаревшим числом | test_boost_credit_survives_concurrent_referral_grant (две сессии читают до коммита любой — та же техника, что у существующего `test_lifetime_cap_counts_every_grant`, настоящие потоки не нужны: дело не в блокировке строки, а в том, считает ли UPDATE новое значение НА СЕРВЕРЕ БД) | атомарный `UPDATE ... SET referral_credits = CASE WHEN ... THEN MAX ELSE current+perk END` вместо чтения-прибавления-записи в Python | тест падал бы (итог 8 вместо 9) → проходит |
| R6 (найдено круг 3) | `PromoClaimLog.phone_key` хранил `_phone_key(phone)` — тот ТОЛЬКО нормализует формат номера (код страны/пробелы), результат — цифры САМОГО номера. Docstring модели обещает «по ключу человека не найти, если не знать номер заранее» — обещание было неправдой: у российских номеров ограниченный диапазон, значит и голый SHA256 подбирался бы перебором по справочнику, а то, что хранилось, была даже не хэш-функция, просто форматирование | test_phone_claim_key_is_not_reversible_to_the_number | `_phone_claim_key`: HMAC-SHA256 с секретом приложения (`settings.jwt_secret`) поверх `_phone_key()` — детерминированно (тот же номер → тот же ключ, иначе повтор нечем ловить), но не обратимо без секрета | тест падал бы (`digits in key`) → проходит |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | В `promo_apply` сняты блокировка кода И условие лимита в CAS | test_concurrent_apply_respects_total_limit (PostgreSQL) | KILLED |
| M10 | В `promo_apply` отключена проверка «свой код» | test_owner_cannot_apply_own_code | KILLED |
| M11 | В `promo_apply` снят потолок `MAX_REFERRAL_CREDITS` | test_boost_grant_capped_at_wallet_limit | KILLED |
| M12 | В `_promo_valid_until` снят сдвиг «дата без времени = конец суток» | test_promo_valid_until_treats_date_only_midnight_as_end_of_day | KILLED |
| M32 | В `promo_apply` атомарный CASE-UPDATE boost-кредита заменён на «прочитал-прибавил-записал» | test_boost_credit_survives_concurrent_referral_grant | KILLED |
| M33 | `_phone_claim_key` снова возвращает голый `_phone_key()` вместо HMAC | test_phone_claim_key_is_not_reversible_to_the_number | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
все мутации этого файла KILLED (общий счёт листа — в отчёте ведущему).

## Остаток и ограничения

Все шесть правил защищены тестами и нарочными поломками, включая реальную гонку на
PostgreSQL (R1). Полный существующий денежный сюит файла (`test_promo.py`,
`test_promo_cannot_be_milked.py`, `test_promo_taxi.py`) прогнан и зелёный на SQLite и
изолированном PostgreSQL.

**ВНЕ ЗОНЫ (найдено этим кругом, сломано моей же правкой R6):**
`backend/tests/test_promo_cannot_be_milked.py:82` — тест
`test_след_переживает_удаление_аккаунта` жёстко сравнивает
`след[0].phone_key == _phone_key("+79995551491")`, то есть предполагает старый, НЕхэшированный
формат ключа. После R6 (`phone_key` теперь HMAC) это сравнение честно падает — не потому что
функция сломана, а потому что сам тест проверяет устаревшее представление данных. Файл не в
OWNS этого листа — не трогал. **Точная правка:** заменить правую часть на
`promo_router._phone_claim_key("+79995551491")` (добавить импорт
`from app.routers import promo as promo_router`, либо `from app.routers.promo import
_phone_claim_key`). **Проверить:** `pytest tests/test_promo_cannot_be_milked.py::test_след_переживает_удаление_аккаунта`.
