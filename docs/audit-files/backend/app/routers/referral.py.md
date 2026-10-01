# Карточка: `backend/app/routers/referral.py`

- Статус: verified
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3); принимал: Opus 5.5

## Назначение

Реферальная программа «позови своего»: свой код, кто пригласил, бонусы (1 бонус = 1 бесплатное
поднятие поездки, по прайсу 200 ₽). Два вида бонуса: за ввод чьего-то кода (обоим по одному) и
водителю-пригласившему за то, что приглашённый реально «раскатался» (≥3 живых поездки с ≥3
разными пассажирами). Два потолка: `MAX_REFERRAL_CREDITS` (остаток на руках) и
`MAX_REFERRAL_BONUS_LIFETIME` (пожизненный — ограда фермы фейковых приглашений), плюс
месячный потолок водительских бонусов на одного пригласившего.

`grant_referral_credit` — единственная точка начисления, её зовут обе двери: `/referral/redeem`
и `reward_driver_referral` (хуки на `done` в такси/попутке). Используется также `routers/promo.py`
(boost-бонус — тот же механизм и кэп).

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `granted_driver_bonuses_this_month` | 44–56 | Сколько водительских бонусов пригласивший получил в ЭТОМ МЕСТНОМ (уфимском) календарном месяце | `local_month_start(now)`, не серверная полночь | ок (R4) |
| `grant_referral_credit` | 59–100 | Начислить пригласившему ровно один бонус, под двумя потолками сразу | единственная точка начисления; ОБА потолка и оба счётчика — внутри одного атомарного UPDATE; не коммитит сама | ок (R3) |
| `_live_driver_trips` | 103–128 | Сколько «живых» done-поездок у водителя и с какими пассажирами | такси: дистанция>1км ИЛИ onboard→done>5мин; попутка: маршрут>1км | ок |
| `reward_driver_referral` | 131–170 | Выдать пригласившему бонус за «раскатавшегося» приглашённого водителя | один бонус на приглашённого (uniqueness-проверка), месячный кэп, ≥3 живых поездки с ≥3 разными пассажирами, зовёт `grant_referral_credit` | ок |
| `_ensure_code` | 173–185 | Лениво сгенерить уникальный реферальный код | до 10 попыток на коллизию | ок |
| `referral_me` | GET /referral/me, 188–197 | Мой код, сколько привёл, бонусов, вводил ли уже | — | ок |
| `referral_redeem` | POST /referral/redeem, 204–263 | Ввести чей-то код: приглашение + бонус обоим | свой код → 400; уже введён → 400; кольцо A↔B → 400; row-lock себя и реферера; атомарный захват приглашения + потолок «на руках» отдельным условным UPDATE | ок (R1, R2) |

## Связи

Экраны: `RidesRequestsChatScreens.kt::InviteDriverCallout` (`/referral/me`, доля ссылки с кодом).
Эндпоинты: `/referral/me`, `/referral/redeem`. Таблицы: `User.referral_code/referred_by/
referral_credits/referral_bonus_lifetime`, `ReferralBonus` (uniqueness на `invited_user_id`+kind).
Хуки: `instant.done`, `bookings.driver-status`, `family.trip-status` → `reward_driver_referral`.
Общая точка начисления с `routers/promo.py` (boost-бонус, `MAX_REFERRAL_CREDITS`).

## Важные правила и тесты

Три правила из четырёх уже защищены отдельным, очень плотным существующим набором тестов
(`backend/tests/test_referral_farm_cannot_be_raced.py`, волна 202, и `backend/tests/test_the_calendar_is_the_humans_not_the_servers.py`,
волна 203) — дублировать их новым кодом было бы лишним весом, а не защитой. Для листа 1.3
добавлен один новый тест (R3 — гонка на лимите пожизненного потолка, которую НИЧЕМ, кроме
собственного атомарного UPDATE внутри `grant_referral_credit`, не защищает ни один внешний
замок: `reward_driver_referral` читает реферера БЕЗ `with_for_update`, в отличие от входа по коду).

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Бонус не начисляется самому себе (свой код и взаимный обмен A→B→A) | backend/tests/test_referral_farm_cannot_be_raced.py::test_own_code_and_swap_are_still_refused | да — M14 |
| R2 | Бонус не начисляется дважды — гонка ДВУХ РАЗНЫХ кодов одного нового юзера, в т.ч. на PostgreSQL | backend/tests/test_referral_farm_cannot_be_raced.py::test_two_codes_at_once_give_only_one_bonus (настоящая гонка потоков на Postgres + honest-эквивалент на SQLite) | да — M15 |
| R3 | Пожизненный потолок РЕФЕРЕРА не пробивается при ДВУХ ОДНОВРЕМЕННЫХ начислениях (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_referral_money.py::test_concurrent_grants_respect_lifetime_cap | да — M16 |
| R4 | Месячный потолок водительского бонуса считается по календарю Уфы, не UTC/сервера | backend/tests/test_the_calendar_is_the_humans_not_the_servers.py::test_monthly_bonus_cap_counts_the_humans_month, ::test_last_months_bonuses_do_not_count_against_this_month | да — M17 |

## Найденные ошибки

Ошибок не найдено. Файл уже прошёл несколько предыдущих волн аудита (202, 203, 165, 25 —
видно по комментариям в коде и по названиям существующих тестов) — все денежные дыры, которые
проверялись по чек-листу листа 1.3 (самому себе, дважды, гонки, календарь), на момент этого
листа уже закрыты и покрыты тестами. Я добавил только недостающее: PostgreSQL-гонку на
пожизненном потолке через `reward_driver_referral`'s путь (R3), которую предыдущие тесты не
воспроизводили напрямую через реальные параллельные потоки.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M14 | В `referral_redeem` убрана проверка «свой код» (`referrer.id == user.id`) | backend/tests/test_referral_farm_cannot_be_raced.py::test_own_code_and_swap_are_still_refused | KILLED |
| M15 | В `referral_redeem` сняты блокировка строки пользователя И условие «код ещё не введён» в атомарном захвате | backend/tests/test_referral_farm_cannot_be_raced.py::test_two_codes_at_once_give_only_one_bonus (PostgreSQL) | KILLED |
| M16 | В `grant_referral_credit` сняты ОБА потолка из атомарного UPDATE | backend/tests/walk/l1_3/test_l1_3_referral_money.py::test_concurrent_grants_respect_lifetime_cap (PostgreSQL) | KILLED |
| M17 | В `granted_driver_bonuses_this_month` календарь месяца взят у сервера (UTC) напрямую, в обход `local_month_start` | backend/tests/test_the_calendar_is_the_humans_not_the_servers.py::test_last_months_bonuses_do_not_count_against_this_month | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
`MUTATIONS KILLED 17/17`.

## Остаток и ограничения

Все четыре правила защищены тестами (три — уже существовавшими до этого листа, один — новый)
и нарочными поломками, включая две проверки на реальном PostgreSQL. Полный существующий сюит
файла (`test_referral_farm_cannot_be_raced.py` + `test_the_calendar_is_the_humans_not_the_servers.py`
+ `test_flows.py::test_referral_flow/test_driver_referral_bonus/test_driver_referral_no_inviter_no_bonus`)
прогнан и зелёный и на SQLite, и на изолированном PostgreSQL.
