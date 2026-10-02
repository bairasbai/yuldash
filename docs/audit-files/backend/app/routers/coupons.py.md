# Карточка: `backend/app/routers/coupons.py`

- Статус: bug
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3); принимал: Opus 5.5 (круг 1 — REQUEST_CHANGES; круг 2 — принят
  на слитом дереве 35f79b35; круг 3 — правило проекта «подтверждённая ошибка держит файл не 🟩»);
  доработка после круга 5 (подсчёт занятых слотов в базе, R11) — Opus 5.5

## Назначение

Партнёрский слой + купонный маркетплейс «Скидки по пути» (M1). Купон — РЕАЛЬНАЯ скидка от
бизнеса (кафе/АЗС/шиномонтаж и т.п.), честно: срок/лимит/скидка на виду, без скрытых наценок.
Платформа зарабатывает на БИЗНЕСЕ (подписка за место в витрине + 10 ₽ за каждое погашение),
НЕ на пассажирах. Приватность: при погашении бизнес видит только код и максимум имя держателя
— не телефон, не гео.

**Почему статус `bug`, а не `verified`.** Правило «один купон на человека» (см. «Открытая
ошибка» ниже) ломается после удаления аккаунта: удаление стирает `CouponRedemption` вместе с
историей, а новая регистрация с ТЕМ ЖЕ номером телефона проходит проверку заново. Чинить в этом
листе нельзя — нужна общая модель (HMAC-ключ телефона) и миграция вне зоны листа (см. «Остаток
и ограничения»). Все одиннадцать правил таблицы ниже (R1–R11) защищены тестами и нарочными
поломками — проект отмечен `bug`, а не `verified`, по прямому правилу: «файл с открытой
подтверждённой ошибкой не может быть 🟩», даже если эта ошибка на весь файл одна.

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_reservation_active` 🆕 | 83–93 | Занимает ли бронь лимит ПРЯМО СЕЙЧАС (одна бронь: погашение, «Мои купоны», идемпотентность) | `redeemed` — всегда; `reserved` — пока не истекла (срок купона, а для бессрочного — `RESERVATION_TTL_DAYS` от `reserved_at`) | ок (R8, исправленная ошибка) |
| `_slot_held_sql` 🆕 | 96–124 | SQL-двойник `_reservation_active`: условие «бронь занимает слот прямо сейчас» сразу для нескольких купонов | граница `now − RESERVATION_TTL_DAYS` считается в Python и сравнивается с `reserved_at` готовой датой (без арифметики дат в SQL, одинаково на SQLite и PostgreSQL); срок купона — одно сравнение на купон; совпадение с `_reservation_active` по каждой строке, включая бронь ровно на границе, держит тест | ок (R8, R11) |
| `_slots_taken` 🆕 | 127–139 | Сколько броней сейчас держат слот: `{coupon_id: число}` одним `COUNT … GROUP BY` | пустой список → `{}`; `user_id` → личный счёт; в Python приходит строка на купон, а не на бронь | ок (R11) |
| `_csv` | 180–181 | Строка CSV → список | — | ок |
| `moderate_and_clamp_reason` | 184–189 | Текст жалобы: обрезать до 500, проверить модерацией | — | ок |
| `_moderate_storefront` | 192–200 | Проверить текст, который уйдёт в публичную витрину | метка '', 'warn', 'contact', 'abuse' | ок |
| `_partner_text` | 210–211 | Снимок видимых полей партнёра | — | ок |
| `_requeue_partner_after_edit` | 214–234 | Правка видимого текста → снова на модерацию | только при РЕАЛЬНОМ изменении текста | ок |
| `_gen_code` | 237–245 | Уникальный 6-символьный код погашения | без похожих символов; до 20 попыток | ок |
| `_sub_active` | 248–250 | Подписка бизнеса оплачена и не истекла | — | ок |
| `_partner_has_premium` | 253–256 | Право на premium-метку купона | — | ок |
| `_in_window` | 259–264 | Купон сейчас в сроке действия | `valid_from ≤ now < valid_until` | ок (R4) |
| `_coupon_valid_until` | 267–283 | Срок действия → UTC; дата БЕЗ времени = КОНЕЦ суток по Уфе | исправленная ошибка B-3 | ок (R4) |
| `_total_exhausted` | 286–287 | Общий лимит исчерпан (по факту ПОГАШЕНИЙ, для видимости в витрине) | — | ок |
| `_coupon_visible` | 290–306 | Виден ли купон в ПУБЛИЧНОЙ витрине | партнёр active+подписка, купон active, review ok, в окне, лимит не исчерпан | ок |
| `_partner_public` | 309–320 | Публичные поля бизнеса | без приватного | ок |
| `_coupon_public` | 323–353 | Публичная сериализация купона | `active_count` (reserved+redeemed, с учётом истечения) для честного `remaining` | ок (R6) |
| `coupons_list` | 358–386 | Витрина: активные видимые купоны | остаток — один `COUNT … GROUP BY` в базе на всю выдачу (`_slots_taken`), брони в Python не грузятся (исправленная ошибка R11) | ок (R6, R8, R11) |
| `coupon_redeem` | 389–463 | Бизнес гасит код у себя | чужой→404; срок истёк→422; снят админом→409; **бронь истекла (30 дней, бессрочный купон)→409** (R8); уже погашён/не действует→409; row-lock+атомарный CAS | ок (R1, R2, R5, R8) |
| `coupon_detail` | 466–476 | Деталь купона | честный `remaining` с учётом истечения — тем же подсчётом в базе, что витрина и активация (R11) | ок (R6, R8, R11) |
| `coupon_activate` | 479–557 | Пассажир бронирует погашение → код | идемпотентность берёт САМУЮ СВЕЖУЮ АКТИВНУЮ бронь, игнорируя истёкшую старую (исправленная ошибка R9, круг 5); лимиты — подсчётом в базе (`_slots_taken`: общий и личный), без загрузки всех броней (R11); row-lock купона | ок (R3, R8, R9, R11, исправленная ошибка) |
| `_activation_out` | 560–566 | Сериализация брони | — | ок |
| `my_coupons` | 569–600 | Мои брони (активные + история) | забытая истёкшая reserved-бронь отдаётся статусом `expired`, не сырым `reserved` (исправленная ошибка R10, круг 5) | ок (R10, исправленная ошибка) |
| `partner_plans` | 605–612 | Тарифы подписки | без авторизации | ок |
| `_partner_mine` | 615–635 | Сериализация СВОЕГО бизнеса | — | ок |
| `partner_register` | 638–671 | Зарегистрировать бизнес | дубль→409; пустое имя/город→422 | ок |
| `partner_me` | 674–692 | Мой бизнес + statement | — | ок |
| `_own_partner` | 695–707 | Достать СВОЙ бизнес или 404 | — | ок |
| `partner_subscribe` | 710–762 | Создать платёж подписки | идемпотентно при том же тарифе | ок |
| `_coupon_mine` | 767–796 | Сериализация СВОЕГО купона | — | ок |
| `_my_active_partner` | 799–811 | Мой бизнес, должен быть active | — | ок |
| `partner_coupons` | 814–823 | Мои купоны, все статусы | — | ок |
| `partner_coupon_create` | 826–858 | Создать купон (draft) | **row-lock партнёра** сериализует лимит 50/бизнес (R7, исправлено) | ок (R7) |
| `_coupon_flag` | 861–864 | Метка модерации по тексту | — | ок |
| `_apply_review` | 873–885 | Пересчитать review по тексту | — | ок |
| `_tell_admin_coupon_held` | 888–898 | Уведомить админа | best-effort | ок |
| `_own_coupon` | 901–910 | Достать СВОЙ купон или 404 | — | ок |
| `partner_coupon_update` | 913–953 | Правка своего купона | `valid_until` через `_coupon_valid_until` | ок |
| `partner_coupon_status` | 956–984 | Сменить статус | включить можно только чистый текст | ок |
| `partner_coupon_stats` | 987–1005 | Статистика купона | — | ок |
| `partner_update` | 1010–1059 | Правка бизнеса | правка текста → снова на модерацию | ок |
| `_require_admin` | 1065–1067 | 403, если не админ | — | ок |
| `_partner_admin` | 1070–1089 | Карточка бизнеса для очереди | — | ок |
| `admin_partners` | 1096–1108 | Все бизнесы, pending сверху | — | ок |
| `admin_partner_approve` | 1111–1132 | Одобрить бизнес | — | ок |
| `admin_partner_reject` | 1135–1156 | Отклонить бизнес | — | ок |
| `_coupon_admin` | 1176–1194 | Карточка купона для очереди | — | ок |
| `admin_moderation_queue` | 1197–1230 | Единая очередь модерации | — | ок |
| `admin_coupon_approve` | 1233–1259 | «Посмотрел, всё в порядке» | — | ок |
| `admin_coupon_block` | 1262–1286 | Снять купон с витрины | — | ок |
| `report_coupon` | 1291–1333 | Жалоба «обещали не то» | лимит 10/час; UNIQUE(coupon,user) | ок |

## Связи

Экраны: `CouponsScreen.kt`, `PartnerCabinetScreen.kt`, админ-экраны модерации. Таблицы:
`Partner`, `Coupon`, `CouponRedemption`, `CouponReport`, `Payment` (purpose=partner_sub).
Делит анти-абуз модель («хэш номера» — см. R6 в `promo.py.md`) с `PromoClaimLog`, но для
купонов ТАКОЙ модели нет вовсе (см. открытую ошибку ниже).

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Купон нельзя погасить дважды, даже двумя одновременными запросами (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_concurrent_redeem_only_one_wins | да — M1 |
| R2 | Бизнес не может погасить чужой купон | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_business_cannot_redeem_foreign_coupon | да — M2 |
| R3 | Лимит «один купон на человека» не превышается под гонкой (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_concurrent_activation_respects_per_user_limit | да — M5 |
| R4 | Срок действия: часовой пояс Уфы + дата без времени = конец суток (B-3) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_window_respects_ufa_timezone, ::test_coupon_valid_until_treats_date_only_midnight_as_end_of_day, ::test_coupon_valid_until_leaves_explicit_time_alone, ::test_coupon_deadline_survives_repeated_date_only_resaves | да — M6, M7 |
| R5 | Просроченный/снятый админом купон не гасится | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_redeem_rejects_expired_coupon, ::test_redeem_rejects_blocked_coupon | да — M3, M4 |
| R6 | «Осталось N» учитывает незавершённые брони — тем же числом и тем же подсчётом в базе (`_slots_taken`), что и лимит активации, в том числе на купоне с долгой историей погашений | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_storefront_remaining_counts_outstanding_reservations, backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_many_old_redemptions_change_query_cost_not_behavior | да — M8 |
| R7 | Лимит 50 купонов/бизнес не пробивается под гонкой (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_concurrent_coupon_creation_respects_fifty_limit | да — M29 |
| R8 | Забытая бронь истекает (срок купона либо `RESERVATION_TTL_DAYS`) и перестаёт держать лимит/гаситься; подсчёт слотов в базе (`_slot_held_sql`) совпадает с `_reservation_active` по каждой строке — для купона со сроком, с истёкшим сроком и бессрочного, включая бронь ровно на границе 30 дней | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_expired_unbounded_reservation_frees_the_limit_slot, backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_expired_reservation_cannot_be_redeemed, backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_slot_count_in_db_matches_reservation_rule_row_by_row | да — M30, M31, M41, M42, M43 |
| R9 | Идемпотентность `coupon_activate` берёт самую свежую АКТИВНУЮ бронь, не любую (не спотыкается о забытую истёкшую) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_activate_idempotency_returns_active_code_not_stale_expired_one | да — M39 |
| R10 | «Мои купоны» отдают статус `expired` для забытой истёкшей брони, не сырой `reserved` | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_my_coupons_reports_expired_status_for_stale_reservation | да — M40 |
| R11 | Цена витрины, детали купона и активации не растёт с историей погашений: занятые слоты (общий и личный счёт) считает база одним `COUNT … GROUP BY`, ни одна бронь не загружается в Python | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_many_old_redemptions_change_query_cost_not_behavior, backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_slot_count_in_db_matches_reservation_rule_row_by_row | да — M44, M45, M46, M47 |

Понятный ответ человеку (бильингвальность `herr`) проверен точечно в R1/R2/R5/R8 и в целом
репо-вайд сторожем `backend/tests/test_bilingual_errors_guard.py`.

## Найденные ошибки

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| R5 | `coupon_redeem` не проверял срок/снятие купона — бизнес получал +10 ₽ за недействующую скидку | test_redeem_rejects_expired_coupon, test_redeem_rejects_blocked_coupon | добавлены проверки `_in_window`/`review=="blocked"` | падали бы (200 вместо 422/409) → проходят |
| R6 | «Остаток» считался только по факту погашения — витрина обещала места, которых нет | test_storefront_remaining_counts_outstanding_reservations | `_coupon_public` принимает `active_count` | падал бы → проходит |
| B-3 | Дата купона без времени трактовалась как полночь UTC−5 — срок съезжал на сутки и дрейфовал при каждом сохранении | test_coupon_valid_until_treats_date_only_midnight_as_end_of_day, круговой тест | `_coupon_valid_until`: полночь без пояса → конец суток ДО перевода в UTC | падали бы → проходят |
| R7 (круг 2→3) | 50 купонов/бизнес считались `count-then-insert` без лока — граница лимита пробивалась на 1–2 под гонкой | test_concurrent_coupon_creation_respects_fifty_limit (PostgreSQL) | row-lock на Partner перед COUNT | падал бы (51–52 вместо 50) → проходит, 429 на втором |
| R8 (круг 2→3, «вечная бронь») | Забытая `reserved`-бронь держала лимит НАВСЕГДА — ни один код/воркер её не истекал | test_expired_unbounded_reservation_frees_the_limit_slot, test_expired_reservation_cannot_be_redeemed | `_reservation_active`: бронь истекает со сроком купона или через `RESERVATION_TTL_DAYS=30` после `reserved_at` (решение ведущего, круг 3); истёкшая не держит лимит и не гасится | падали бы (чужой слот навсегда занят / код гасится спустя годы) → проходят |
| R9 (независимое ревью круга 4, N-1 — R8 задним числом добавил эту ошибку) | `coupon_activate` искал идемпотентную бронь БЕЗ сортировки — у человека с забытой истёкшей (R8) и новой активной бронью `.first()` обычно возвращал СТАРУЮ: 409 «уже воспользовался» при `limit_per_user=1`, лишний код при `limit_per_user≥2` | test_activate_idempotency_returns_active_code_not_stale_expired_one | Берём самую свежую (`order_by(id.desc())`) бронь, прошедшую `_reservation_active` | тест падал бы (409 или лишний код вместо действующего) → проходит |
| R10 (независимое ревью круга 4, N-2 — тоже задним числом от R8) | `my_coupons` отдавал сырой `status="reserved"` для забытой истёкшей брони — приложение рисовало код и «Покажи код в заведении», касса отвечала 409 | test_my_coupons_reports_expired_status_for_stale_reservation | Статус `expired`, когда `_reservation_active(...)` ложно | тест падал бы (`reserved` вместо `expired`) → проходит |
| R11 (независимое ревью leaf-1.3 — «мелкое, не блокирует», сознательно отложено из кругов 4–5; тоже задним числом от R8) | С приходом истечения брони (круг 3) витрина, деталь купона и активация стали загружать в Python ВСЕ брони купона (reserved+redeemed) и фильтровать их `_reservation_active` вместо прежнего COUNT. Погашённые строки копятся вечно — у популярного купона открытый без входа запрос дорожал без потолка | test_many_old_redemptions_change_query_cost_not_behavior: 200 погашений год назад + забытая и свежая брони | `_slot_held_sql` + `_slots_taken`: то же правило одним `COUNT … GROUP BY` в базе; граница 30 дней — готовой датой из Python, без арифметики дат в SQL; построчное совпадение с `_reservation_active` держит test_slot_count_in_db_matches_reservation_rule_row_by_row | на старом коде тест падал ровно на цене: «сервер загрузил в Python 1217 броней», а остаток и лимиты совпадали → проходит, 0 строк |

### Открытая ошибка (НЕ исправлена в этом листе — статус `bug`)

**Удаление аккаунта обнуляет лимит «один купон на человека».**

- **Сценарий.** Пассажир активирует купон (или несколько, если `limit_per_user` позволяет) →
  удаляет аккаунт (`account.py` стирает его `CouponRedemption` вместе со всей историей) →
  регистрируется заново С ТЕМ ЖЕ номером телефона → `coupon_activate` смотрит только
  `CouponRedemption.user_id` текущего (нового) аккаунта, прежних записей не находит →
  купон, рассчитанный на одного человека, достаётся тому же номеру повторно. Для
  промокодов (`routers/promo.py`) эту же дыру закрыли `PromoClaimLog` (волна 149, и его
  ключ телефона в круге 3 этого листа дополнительно защищён HMAC — см. `promo.py.md`, R6).
  Для купонов АНАЛОГИЧНОЙ модели нет вовсе.
- **Почему не чиню в этом листе.** Нужна: (1) новая таблица/модель уровня `PromoClaimLog`
  для купонов (миграция схемы — `models.py` вне зоны листа), (2) решение, на каком уровне
  действует анти-абуз — на купон, на партнёра или глобально, (3) синхронизация с тем, как
  уже живёт `PromoClaimLog`, чтобы не завести ВТОРОЙ отдельный, рассинхронизированный
  механизм хранения телефонных ключей. Это архитектурное решение шире одной правки в файле.
- **Предлагаемое решение.** Завести `CouponClaimLog(coupon_id, phone_key, device_id,
  created_at)` по образцу `PromoClaimLog`, писать в неё при КАЖДОЙ активации (не только
  при удалении — тогда она переживёт его естественно), ключ телефона — **HMAC-SHA256**
  (`hmac.new(settings.jwt_secret, digits, sha256)`), **НЕ** голый `_phone_key()` — ровно та
  же ошибка, что чинилась в `promo.py` этим же кругом (см. `promo.py.md`, R6), не стоит
  повторять её во второй таблице. Проверять в `coupon_activate` наравне с лимитом на
  текущего пользователя. **Две вещи, которые придётся сделать ТОГДА же, не потом (независимое
  ревью круга 4):** (1) миграция, переводящая уже накопленные строки с открытым ключом в
  HMAC задним числом, ОБЯЗАНА отказываться запускаться при слабом/дефолтном/коротком
  секрете — см. предохранитель в `promo_claim_hmac_20261002.py` как образец, не изобретать
  заново; (2) смена `JWT_SECRET` после этого делает старые ключи `CouponClaimLog`
  несопоставимыми НАВСЕГДА (HMAC необратим — пересчитать нечем, исходных цифр номера нигде
  больше нет) — решать ДО первого прогона в проде, отдельный секрет не вводим (решение
  ведущего для `PromoClaimLog`, см. `promo.py.md`), держим в голове при проектировании.
- **Серьёзность: средняя.** Цена одного обхода — цена ОДНОЙ скидки у ОДНОГО бизнеса (не
  деньги платформы напрямую — комиссия берётся с партнёра за ПОГАШЕНИЕ, которое всё равно
  происходит честно), а трение (удалить аккаунт, завести новый с тем же номером) отсекает
  массовость. Это и держит статус `bug`, а не `verified`, по правилу проекта.

Прочие мелкие проблемы (не денежные правила, не блокируют): PromoClaimLog-подобной модели
для купонов нет (см. выше) — единственная открытая проблема. Лимит-50 (R7) и вечная бронь
(R8) из круга 2 — **исправлены** в этом круге, см. таблицу выше.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M1 | В `coupon_redeem` сняты блокировка кода И условие «ещё reserved» в CAS | test_concurrent_redeem_only_one_wins (PostgreSQL) | KILLED |
| M2 | В `coupon_redeem` отключена проверка владельца бизнеса | test_business_cannot_redeem_foreign_coupon | KILLED |
| M3 | В `coupon_redeem` отключена проверка срока действия | test_redeem_rejects_expired_coupon | KILLED |
| M4 | В `coupon_redeem` отключена проверка `review=="blocked"` | test_redeem_rejects_blocked_coupon | KILLED |
| M5 | В `coupon_activate` снята блокировка строки купона | test_concurrent_activation_respects_per_user_limit (PostgreSQL) | KILLED |
| M6 | В `_in_window` сравнение перевёрнуто (`<=`→`>=`) | test_window_respects_ufa_timezone | KILLED |
| M7 | В `_coupon_valid_until` снят сдвиг «дата без времени = конец суток» | test_coupon_valid_until_treats_date_only_midnight_as_end_of_day | KILLED |
| M8 | В `_coupon_public` «остаток» снова только по факту погашения | test_storefront_remaining_counts_outstanding_reservations | KILLED |
| M29 | В `partner_coupon_create` снята блокировка строки партнёра | test_concurrent_coupon_creation_respects_fifty_limit (PostgreSQL) | KILLED |
| M30 | В `_reservation_active` снято истечение по умолчанию (бессрочный купон = бронь никогда не истекает) | test_expired_reservation_cannot_be_redeemed (до R11 ловил test_expired_unbounded_reservation_frees_the_limit_slot; теперь лимит считает база через `_slot_held_sql`, а `_reservation_active` решает погашение, «Мои купоны» и идемпотентность — ту же поломку в базе ловит M41) | KILLED |
| M31 | В `coupon_redeem` снята проверка истечения брони | test_expired_reservation_cannot_be_redeemed | KILLED |
| M39 | В `coupon_activate` идемпотентность снова без сортировки/фильтра активности | test_activate_idempotency_returns_active_code_not_stale_expired_one (PostgreSQL) | KILLED |
| M40 | В `my_coupons` снята подмена статуса на `expired` | test_my_coupons_reports_expired_status_for_stale_reservation (PostgreSQL) | KILLED |
| M41 | В `_slot_held_sql` у бессрочного купона снята граница `RESERVATION_TTL_DAYS` — забытая бронь в подсчёте базой снова держит слот вечно | test_expired_unbounded_reservation_frees_the_limit_slot | KILLED |
| M42 | В `_slot_held_sql` забыт общий срок купона — бронь купона СО сроком живёт 30 дней, как у бессрочного | test_slot_count_in_db_matches_reservation_rule_row_by_row | KILLED |
| M43 | В `_slot_held_sql` граница нестрогая (`>=`) — бронь ровно на отметке 30 дней база считает живой, `_reservation_active` — нет | test_slot_count_in_db_matches_reservation_rule_row_by_row (PostgreSQL) | KILLED |
| M44 | `coupons_list` снова грузит в Python все брони купонов витрины | test_many_old_redemptions_change_query_cost_not_behavior | KILLED |
| M45 | `coupon_detail` снова грузит в Python все брони купона | test_many_old_redemptions_change_query_cost_not_behavior | KILLED |
| M46 | `coupon_activate`: общий лимит снова считается загрузкой всех броней купона | test_many_old_redemptions_change_query_cost_not_behavior | KILLED |
| M47 | `_slots_taken`: личный счёт без фильтра по человеку (чужие брони засчитываются ему) | test_slot_count_in_db_matches_reservation_rule_row_by_row | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
все мутации этого файла KILLED (общий счёт листа — в отчёте ведущему). После R11 все 20 поломок
этого файла (M1–M8, M29–M31, M39–M47) перепроверены на новом коде: `--only` по ним →
MUTATIONS KILLED 20/20.

## Остаток и ограничения

Статус `bug` — см. «Открытая ошибка» выше: удаление аккаунта обнуляет лимит «один купон на
человека». Это ЕДИНСТВЕННАЯ открытая проблема файла; все правила R1–R11 защищены тестами и
нарочными поломками, включая три реальные проверки гонки на PostgreSQL (R1, R3, R7). Полный
существующий сюит файла (`test_coupons.py`, `test_coupons_journey.py`) прогнан и зелёный на
SQLite и на изолированном PostgreSQL. После R11 — вместе с `test_l1_3_coupons_money.py` и ещё
13 файлами тестов, которые задевают купоны: SQLite 205 + 3 пропуска (гонки — только
PostgreSQL), PostgreSQL 208/208.

Подсчёт R11 по-прежнему идёт по строкам ВНУТРИ базы: `COUNT` обходит брони купона по индексу
`ix_couponredemption_coupon_id`, просто не тащит их в Python. Для «популярного» купона в
тысячи погашений это миллисекунды; если когда-нибудь понадобится O(1) — держать счётчик
занятых слотов в самой строке купона, но это уже новая схема, а не правка подсчёта.
`tools/audit_file_status.py run-tests` берёт тесты только из 🟩-карточек, а у этого файла
статус 🟥 — купонные тесты листа гоняются отдельно, прямым `pytest`.

«Кольцо из трёх» и месячный лимит/граceful-ошибка водительского бонуса — это `referral.py`,
не этот файл (см. его карточку).

ВНЕ ЗОНЫ: не затронуто этим кругом.
