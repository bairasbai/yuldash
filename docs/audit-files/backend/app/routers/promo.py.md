# Карточка: `backend/app/routers/promo.py`

- Статус: verified
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3); принимал: Opus 5.5 (REQUEST_CHANGES на первом круге — см. «Остаток»)

## Назначение

Именные промокоды и кампании (рычаг роста для блогеров/партнёров/акций). Промокод — инструмент
привлечения, НЕ прямой доход и не штраф: отказ ввести код ничего не ломает. Три вида бонуса:
`welcome` (чистая атрибуция), `boost` (бесплатные поднятия поездки через общий механизм
реферала), `taxi_ride` (скидка в рублях на поездку в такси — считает `app/promo_ride.py`).
Один код на всю жизнь аккаунта.

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_promo_valid_until` 🆕 | 90–100 | Срок кампании → UTC; дата БЕЗ времени = КОНЕЦ суток по Уфе, та же логика, что у купонов | исправленная ошибка B-3 | ок (R4, исправленная ошибка) |
| `_user_is_live` | 101–133 | «Живой» ли приведённый — сделал ≥1 реальную поездку | такси/попутка как пассажир, или живая поездка как водитель (переиспользует `referral._live_driver_trips`) | ок |
| `_promo_counts` | 134–141 | (applied, active) по коду | — | ок |
| `_apply_message` | 142–173 | Дружелюбное RU/BA сообщение об активации | для boost — говорит, сколько НАЧИСЛЕНО реально (с учётом потолка) | ок |
| `promo_apply` | 174–277 | Применить код: один на жизнь аккаунта | порядок: нет/выключен→404; окно дат→422; уже активировал (БД и телефон/устройство)→409; свой код→409; лимит исчерпан→409 (быстрая проверка + атомарный CAS-счётчик); boost — под общим потолком | ок (R1, R2, R3) |
| `promo_mine` | 280–307 | Мой применённый код + судьба скидки на такси | — | ок |
| `promo_stats` | 312–337 | Статистика кода: applied/active/vanished | доступ — только владелец кода или админ | ок |
| `_require_admin` | 342–345 | 403, если не админ | — | ок |
| `_promo_admin` | 347–372 | Карточка кампании для админа | — | ок |
| `admin_promo_create` | 375–420 | Создать код/кампанию | `limit_per_user` ВСЕГДА 1; `valid_until` теперь через `_promo_valid_until` | ок |
| `admin_promo_list` | 423–428 | Все коды со счётчиками | — | ок |
| `admin_promo_status` | 431–443 | Вкл/выкл кампанию | — | ок |
| `admin_promo_update` | 446–471 | Правка кампании (кроме `limit_per_user`) | `valid_until` теперь через `_promo_valid_until` | ок |

## Связи

Экраны: `PromoCodeScreen.kt`, `AdminPromoScreen.kt`. Таблицы: `PromoCode`, `PromoRedemption`
(UNIQUE user_id), `PromoClaimLog` (след по телефону/устройству). Делит механизм начисления и
потолок `MAX_REFERRAL_CREDITS` с `routers/referral.py` (boost). Скидку на такси считает и
списывает `app/promo_ride.py` — своей денежной логики здесь нет, только активация/лимиты кода.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Общий лимит кампании не превышается даже когда два РАЗНЫХ человека одновременно применяют последний код (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_concurrent_apply_respects_total_limit | да — M9 |
| R2 | Владелец кампании не может активировать свой же код | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_owner_cannot_apply_own_code | да — M10 |
| R3 | Boost-бонус не выдаётся сверх общего потолка бонусов на руках | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_boost_grant_capped_at_wallet_limit | да — M11 |
| R4 (B-3) | Срок кампании: дата БЕЗ времени = конец суток по Уфе, а не начало; повторное сохранение той же даты не сдвигает срок | backend/tests/walk/l1_3/test_l1_3_promo_money.py::test_promo_valid_until_treats_date_only_midnight_as_end_of_day, ::test_promo_valid_until_leaves_explicit_time_alone, ::test_promo_deadline_survives_repeated_date_only_resaves | да — M12 |

Срок действия кампании (общий хелпер `promo_ride.in_window`) и «нельзя погасить дважды с
одного номера после удаления аккаунта» (`PromoClaimLog`) уже плотно покрыты существующим
сюитом (`test_promo.py`, `test_promo_cannot_be_milked.py`, 23 теста, прогнаны на SQLite и
PostgreSQL) — отдельной новой мутацией не дублировал.

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| B-3 (независимое ревью) | Та же ошибка, что у купонов (см. `coupons.py.md`): форма админа шлёт срок кампании ДАТОЙ без времени, старый код трактовал полночь как точный момент и терял сутки при конвертации в UTC, а повторное сохранение той же даты сдвигало срок ещё дальше | `test_promo_valid_until_treats_date_only_midnight_as_end_of_day`, `test_promo_deadline_survives_repeated_date_only_resaves` | `_promo_valid_until` — та же логика «полночь без пояса → конец суток ДО перевода в UTC», что у `coupons.py::_coupon_valid_until` | круговой тест падал бы при повторном сохранении → после правки срок стабилен |

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M9 | В `promo_apply` сняты блокировка строки промокода И условие лимита в атомарном UPDATE счётчика | test_concurrent_apply_respects_total_limit (PostgreSQL, с принудительной задержкой перед счётчиком) | KILLED |
| M10 | В `promo_apply` отключена проверка «свой код» | test_owner_cannot_apply_own_code | KILLED |
| M11 | В `promo_apply` снят потолок `MAX_REFERRAL_CREDITS` при начислении boost | test_boost_grant_capped_at_wallet_limit | KILLED |
| M12 | В `_promo_valid_until` снят сдвиг «дата без времени = конец суток» (регрессия к B-3) | test_promo_valid_until_treats_date_only_midnight_as_end_of_day | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
`MUTATIONS KILLED 28/28` (весь лист).

## Остаток и ограничения

Карточка переписана после первого круга независимого ревью (Opus, VERDICT: REQUEST_CHANGES):
добавлена и защищена тестами B-3 (дата-без-времени для срока кампании), убраны
overclaim-формулировки.

Все четыре правила защищены тестами и нарочными поломками, включая реальную гонку на
PostgreSQL (R1). Полный существующий денежный сюит файла (`test_promo.py`,
`test_promo_cannot_be_milked.py`, `test_promo_taxi.py` — делит с ним `promo_apply`) прогнан и
зелёный на SQLite и на изолированном PostgreSQL.
