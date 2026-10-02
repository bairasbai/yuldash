# Карточка: `backend/app/routers/coupons.py`

- Статус: verified
- Лист: leaf-1.3
- Проверял: Sonnet 5 (leaf-1.3 / leaf-1.3b — последняя открытая ошибка файла закрыта этим
  отдельным листом); принимал: Opus 5.5 (круг 1 — REQUEST_CHANGES;
  круг 2 — принят на слитом дереве 35f79b35; круг 3 — правило проекта «подтверждённая ошибка
  держит файл не 🟩»; круг 4 (ревью листа 1.3, N-1/N-2) — идемпотентность и статус брони;
  leaf-1.3b — журнал `CouponClaimLog` закрыл последнюю открытую ошибку, статус → `verified`)

## Назначение

Партнёрский слой + купонный маркетплейс «Скидки по пути» (M1). Купон — РЕАЛЬНАЯ скидка от
бизнеса (кафе/АЗС/шиномонтаж и т.п.), честно: срок/лимит/скидка на виду, без скрытых наценок.
Платформа зарабатывает на БИЗНЕСЕ (подписка за место в витрине + 10 ₽ за каждое погашение),
НЕ на пассажирах. Приватность: при погашении бизнес видит только код и максимум имя держателя
— не телефон, не гео.

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Условия, входы, ошибки | Вердикт |
|---|---|---|---|---|
| `_reservation_active` 🆕 | 84–97 | Занимает ли бронь лимит ПРЯМО СЕЙЧАС | `redeemed` — всегда; `reserved` — пока не истекла (срок купона, а для бессрочного — `RESERVATION_TTL_DAYS` от `reserved_at`) | ок (R8, исправленная ошибка) |
| `_csv` | 135–137 | Строка CSV → список | — | ок |
| `moderate_and_clamp_reason` | 139–145 | Текст жалобы: обрезать до 500, проверить модерацией | — | ок |
| `_moderate_storefront` | 147–163 | Проверить текст, который уйдёт в публичную витрину | метка '', 'warn', 'contact', 'abuse' | ок |
| `_partner_text` | 165–167 | Снимок видимых полей партнёра | — | ок |
| `_requeue_partner_after_edit` | 169–190 | Правка видимого текста → снова на модерацию | только при РЕАЛЬНОМ изменении текста | ок |
| `_gen_code` | 192–201 | Уникальный 6-символьный код погашения | без похожих символов; до 20 попыток | ок |
| `_sub_active` | 203–206 | Подписка бизнеса оплачена и не истекла | — | ок |
| `_partner_has_premium` | 208–212 | Право на premium-метку купона | — | ок |
| `_in_window` | 214–220 | Купон сейчас в сроке действия | `valid_from ≤ now < valid_until` | ок (R4) |
| `_coupon_valid_until` | 222–239 | Срок действия → UTC; дата БЕЗ времени = КОНЕЦ суток по Уфе | исправленная ошибка B-3 | ок (R4) |
| `_total_exhausted` | 241–243 | Общий лимит исчерпан (по факту ПОГАШЕНИЙ, для видимости в витрине) | — | ок |
| `_coupon_visible` | 245–262 | Виден ли купон в ПУБЛИЧНОЙ витрине | партнёр active+подписка, купон active, review ok, в окне, лимит не исчерпан | ок |
| `_partner_public` | 264–276 | Публичные поля бизнеса | без приватного | ок |
| `_coupon_public` | 278–311 | Публичная сериализация купона | `active_count` (reserved+redeemed, с учётом истечения) для честного `remaining` | ок (R6) |
| `coupons_list` | 314–355 | Витрина: активные видимые купоны | один батч-запрос + фильтр `_reservation_active` на выдачу | ок (R6, R8) |
| `coupon_redeem` | 357–437 | Бизнес гасит код у себя | чужой→404; срок истёк→422; снят админом→409; бронь истекла→409 (R8); уже погашён/не действует→409; row-lock+атомарный CAS; **пишет `CouponClaimLog` по HMAC телефона держателя — при НАСТОЯЩЕМ погашении, не при брони** (R11, leaf-1.3b) | ок (R1, R2, R5, R8, R11) |
| `coupon_detail` | 440–456 | Деталь купона | честный `remaining` с учётом истечения | ок (R6, R8) |
| `coupon_activate` | 458–556 | Пассажир бронирует погашение → код | идемпотентность берёт САМУЮ СВЕЖУЮ АКТИВНУЮ бронь (R9); лимит на человека = `CouponClaimLog` по номеру (прошлые, в т.ч. от удалённых аккаунтов) + текущие НЕпогашенные брони этого аккаунта, без двойного счёта уже погашенных (R11, leaf-1.3b); row-lock купона | ок (R3, R8, R9, R11, исправленная ошибка) |
| `_activation_out` | 559–566 | Сериализация брони | — | ок |
| `my_coupons` | 569–601 | Мои брони (активные + история) | забытая истёкшая reserved-бронь отдаётся статусом `expired`, не сырым `reserved` (R10) | ок (R10, исправленная ошибка) |
| `partner_plans` | 605–612 | Тарифы подписки | без авторизации | ок |
| `_partner_mine` | 614–636 | Сериализация СВОЕГО бизнеса | — | ок |
| `partner_register` | 638–672 | Зарегистрировать бизнес | дубль→409; пустое имя/город→422 | ок |
| `partner_me` | 674–692 | Мой бизнес + statement | — | ок |
| `_own_partner` | 694–708 | Достать СВОЙ бизнес или 404 | — | ок |
| `partner_subscribe` | 710–764 | Создать платёж подписки | идемпотентно при том же тарифе | ок |
| `_coupon_mine` | 766–796 | Сериализация СВОЕГО купона | — | ок |
| `_my_active_partner` | 798–812 | Мой бизнес, должен быть active | — | ок |
| `partner_coupons` | 814–824 | Мои купоны, все статусы | — | ок |
| `partner_coupon_create` | 826–858 | Создать купон (draft) | **row-lock партнёра** сериализует лимит 50/бизнес (R7, исправлено) | ок (R7) |
| `_coupon_flag` | 860–870 | Метка модерации по тексту | — | ок |
| `_apply_review` | 872–885 | Пересчитать review по тексту | — | ок |
| `_tell_admin_coupon_held` | 887–898 | Уведомить админа | best-effort | ок |
| `_own_coupon` | 900–911 | Достать СВОЙ купон или 404 | — | ок |
| `partner_coupon_update` | 913–954 | Правка своего купона | `valid_until` через `_coupon_valid_until` | ок |
| `partner_coupon_status` | 956–985 | Сменить статус | включить можно только чистый текст | ок |
| `partner_coupon_stats` | 987–1008 | Статистика купона | — | ок |
| `partner_update` | 1010–1062 | Правка бизнеса | правка текста → снова на модерацию | ок |
| `_require_admin` | 1064–1067 | 403, если не админ | — | ок |
| `_partner_admin` | 1069–1089 | Карточка бизнеса для очереди | — | ок |
| `admin_partners` | 1096–1109 | Все бизнесы, pending сверху | — | ок |
| `admin_partner_approve` | 1111–1133 | Одобрить бизнес | — | ок |
| `admin_partner_reject` | 1135–1165 | Отклонить бизнес | — | ок |
| `_coupon_admin` | 1175–1195 | Карточка купона для очереди | — | ок |
| `admin_moderation_queue` | 1197–1231 | Единая очередь модерации | — | ок |
| `admin_coupon_approve` | 1233–1260 | «Посмотрел, всё в порядке» | — | ок |
| `admin_coupon_block` | 1262–1289 | Снять купон с витрины | — | ок |
| `report_coupon` | 1291– | Жалоба «обещали не то» | лимит 10/час; UNIQUE(coupon,user) | ок |

**Вне этого файла, но той же R11 истории:** `backend/alembic/versions/coupon_claim_log_20261003.py`
🆕 (leaf-1.3b) — создаёт `CouponClaimLog` и переносит уже накопленные погашения ЖИВЫХ людей
(HMAC их номера) задним числом, с тем же предохранителем от слабого секрета, что у
`promo_claim_hmac_20261002.py` (не изобретён заново). Идемпотентна по РАЗНИЦЕ счётчиков на
пару (coupon_id, phone_key) — не по факту «пара встречалась», иначе законные повторные
погашения одного номера (limit_per_user>1) схлопнулись бы в одну запись и занизили счёт.

## Связи

Экраны: `CouponsScreen.kt`, `PartnerCabinetScreen.kt`, админ-экраны модерации. Таблицы:
`Partner`, `Coupon`, `CouponRedemption`, `CouponReport`, `Payment` (purpose=partner_sub),
`CouponClaimLog` 🆕 (leaf-1.3b — переживает удаление аккаунта, см. R11). Делит анти-абуз
модель («HMAC номера», `_phone_claim_key`, переиспользован импортом) с `PromoClaimLog`
из `promo.py` (см. R6/R7/R8/R9 в `promo.py.md`) — формула HMAC одна на оба журнала.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R1 | Купон нельзя погасить дважды, даже двумя одновременными запросами (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_concurrent_redeem_only_one_wins | да — M1 |
| R2 | Бизнес не может погасить чужой купон | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_business_cannot_redeem_foreign_coupon | да — M2 |
| R3 | Лимит «один купон на человека» не превышается под гонкой (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_concurrent_activation_respects_per_user_limit | да — M5 |
| R4 | Срок действия: часовой пояс Уфы + дата без времени = конец суток (B-3) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_window_respects_ufa_timezone, ::test_coupon_valid_until_treats_date_only_midnight_as_end_of_day, ::test_coupon_valid_until_leaves_explicit_time_alone, ::test_coupon_deadline_survives_repeated_date_only_resaves | да — M6, M7 |
| R5 | Просроченный/снятый админом купон не гасится | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_redeem_rejects_expired_coupon, ::test_redeem_rejects_blocked_coupon | да — M3, M4 |
| R6 | «Осталось N» учитывает незавершённые брони | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_storefront_remaining_counts_outstanding_reservations | да — M8 |
| R7 | Лимит 50 купонов/бизнес не пробивается под гонкой (PostgreSQL) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_concurrent_coupon_creation_respects_fifty_limit | да — M29 |
| R8 | Забытая бронь истекает (срок купона либо `RESERVATION_TTL_DAYS`) и перестаёт держать лимит/гаситься | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_expired_unbounded_reservation_frees_the_limit_slot, ::test_expired_reservation_cannot_be_redeemed | да — M30, M31 |
| R9 | Идемпотентность `coupon_activate` берёт самую свежую АКТИВНУЮ бронь, не любую (не спотыкается о забытую истёкшую) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_activate_idempotency_returns_active_code_not_stale_expired_one | да — M39 |
| R10 | «Мои купоны» отдают статус `expired` для забытой истёкшей брони, не сырой `reserved` | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_my_coupons_reports_expired_status_for_stale_reservation | да — M40 |
| R11 | Лимит «один купон на человека» переживает удаление аккаунта (`CouponClaimLog`, HMAC номера, пишется при погашении) | backend/tests/walk/l1_3/test_l1_3_coupons_money.py::test_claim_log_blocks_reapply_after_account_deletion, ::test_claim_log_allows_exactly_one_more_after_one_past_redemption, ::test_claim_log_ignores_expired_reservation_of_deleted_account, ::test_claim_log_does_not_double_count_live_accounts_own_redemption, ::test_claim_log_does_not_affect_different_phone_number, ::test_coupon_claim_log_migration_backfills_live_redemptions_and_is_idempotent, ::test_coupon_claim_log_migration_refuses_weak_secret_when_rows_need_backfill | да — M41, M42, M43, M44 |

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
| R11 (leaf-1.3b — закрывает последнюю открытую ошибку листа 1.3) | «Один купон на человека» держался только на `CouponRedemption.user_id` — удаление аккаунта (`account.py`, затронут ТОЛЬКО чтением) стирает её вместе с историей, регистрация заново с тем же номером проходила проверку заново. Для промокодов эту дыру закрыли `PromoClaimLog` — для купонов аналога не было вовсе | test_claim_log_blocks_reapply_after_account_deletion и ещё 6 тестов (R11) | `CouponClaimLog(coupon_id, phone_key, created_at)`, БЕЗ FK на user (удаление не требует правок); пишется в `coupon_redeem` при НАСТОЯЩЕМ погашении (не при брони — забытая истёкшая бронь не копится навечно); `coupon_activate` считает лимит = журнал по номеру (прошлые + чужие удалённые аккаунты) + НЕпогашенные брони текущего (без двойного счёта уже погашенных); миграция `coupon_claim_log_20261003.py` переносит исторические погашения живых людей, тот же предохранитель от слабого секрета, что у `promo_claim_hmac` | тесты падали бы (лимит обнулялся удалением аккаунта, или двойной счёт пробивал лимит в другую сторону) → проходят |

Статус файла теперь `verified` — открытых ошибок не осталось. 152-ФЗ: `CouponClaimLog` хранит
HMAC номера, не сам номер — то же основание, что у `PromoClaimLog` (анти-абуз, не вторая копия
персональных данных в базе), см. docstring модели в `models.py`.

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
| M30 | В `_reservation_active` снято истечение по умолчанию (бессрочный купон = бронь никогда не истекает) | test_expired_unbounded_reservation_frees_the_limit_slot | KILLED |
| M31 | В `coupon_redeem` снята проверка истечения брони | test_expired_reservation_cannot_be_redeemed | KILLED |
| M39 | В `coupon_activate` идемпотентность снова без сортировки/фильтра активности | test_activate_idempotency_returns_active_code_not_stale_expired_one (PostgreSQL) | KILLED |
| M40 | В `my_coupons` снята подмена статуса на `expired` | test_my_coupons_reports_expired_status_for_stale_reservation (PostgreSQL) | KILLED |
| M41 | В `coupon_activate` лимит на человека снова считает только текущий аккаунт (`claimed_by_phone` убран) | test_claim_log_blocks_reapply_after_account_deletion | KILLED |
| M42 | В `coupon_redeem` снята запись в `CouponClaimLog` при погашении | test_claim_log_blocks_reapply_after_account_deletion | KILLED |

`python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.3.json` →
все мутации этого файла KILLED (общий счёт листа — в отчёте ведущему). M43 (перенос снова
схлопывает несколько законных погашений одной пары в одну запись) и M44 (предохранитель от
слабого секрета отключён) — в том же JSON, но `file` —
`backend/alembic/versions/coupon_claim_log_20261003.py`, не этот роутер (та же логика, что у
M37/M38 — `check-cards` сверяет поломку с файлом, который она ломает).

## Остаток и ограничения

Открытых ошибок не осталось — все одиннадцать денежных правил (R1–R11, плюс границы R4)
защищены тестами и нарочными поломками, включая четыре реальные проверки гонки на PostgreSQL
(R1, R3, R7) и перенос исторических данных миграцией (R11). Полный существующий сюит файла
(`test_coupons.py`, `test_coupons_journey.py`) прогнан и зелёный на SQLite и на изолированном
PostgreSQL.

**Ограничение переноса (leaf-1.3b, осознанное, не баг).** Миграция `coupon_claim_log_20261003.py`
переносит прошлые погашения только ЖИВЫХ на момент выкладки людей — у кого аккаунт уже удалён
ДО этой миграции, `CouponRedemption` вместе с номером уже стёрт самим удалением, восстановить
их историю неоткуда. Это не прежняя дыра (та защищала бы и будущих, и прошлых одинаково никак),
а честная граница: защита включается с этой выкладки, прошлое до неё восстановить физически
нельзя — тот же компромисс, что был у `promo_claim_hmac_20261002.py` для промокодов.

`account.py` в leaf-1.3b затронут ТОЛЬКО чтением (одобренный лист 2.1) — журнал устроен так,
что удаление аккаунта не требует ни единой правки там: запись уже сделана заранее, при самом
погашении, и не ссылается на `user.id`.

**Смена `JWT_SECRET` необратимо обнуляет и ЭТУ защиту для прошлых людей** — тот же факт, что
у `PromoClaimLog` (см. `promo.py.md`, исправленный N-4 докстринг `_phone_claim_key`, которую
`coupon_redeem` переиспользует импортом): HMAC необратим, пересчитать старые ключи после смены
секрета нечем. Отдельный секрет не вводим (решение ведущего для `PromoClaimLog`, то же — для
купонов), держим в голове при любой будущей ротации `JWT_SECRET`.

«Кольцо из трёх» и месячный лимит/граceful-ошибка водительского бонуса — это `referral.py`,
не этот файл (см. его карточку).

ВНЕ ЗОНЫ: не затронуто этим кругом.
