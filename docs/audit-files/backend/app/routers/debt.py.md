# Карточка: `backend/app/routers/debt.py`

- Статус: verified
- Лист: leaf-1.1
- Проверял: Sonnet 5 (leaf-1.1); принимал: Opus 5.5

## Назначение

HTTP-фасад над `backend/app/debt.py` (вся логика — там, здесь только: достать пользователя из
токена, разобрать тело запроса, вызвать функцию, оформить ответ/ошибку, отправить пуш/запись в
админ-журнал). Два лагеря эндпоинтов: водитель (свой долг, «я оплатил», список поездок) и админ
(очередь на подтверждение, подтвердить/отклонить/простить).

## Функции и разбор

| Функция / эндпоинт | Строки | Что делает | Входы, условия, ошибки, побочные действия | Вердикт |
|---|---|---|---|---|
| `_require_admin(user)` | 31–37 | гейт роли admin, общий для всех админских ручек | не admin → `403`, **двуязычный** (`herr`, исправлено в этом раунде — было `HTTPException` только по-русски, см. «Найденные ошибки») | испр. (P3) |
| `GET /driver/debt` → `my_debt` | сдвинулось | сводка долга водителя | по СВОЕМУ токену (`current_user`); СНАЧАЛА гасит долг деньгами из кошелька (`settle_debt_from_wallet` — теперь с учётом F2-снимка, см. `debt.py.md`), ПОТОМ отдаёт сводку | ок |
| `POST /driver/debt/paid` → `declare_paid` | сдвинулось | оплата долга: картой (ЮKassa) или «Я оплатил» по СБП | прод + `payments_provider=mock` → `503`; сначала гасит из кошелька; `yookassa` → сумма и ID снимка теперь ОДНИМ запросом (`debt.taxi_debt_snapshot`, правка F2 — раньше `debt_summary` отдельно, риск разъехаться с новым начисленным долгом), снимок пишется в `Payment.tier` при создании СЧЁТА; дедуп висящего `pending`-платежа переиспользует Payment-строку; `owed<=0`→`succeeded` без похода к банку; иначе (по умолчанию) → `declare_paid` в debt.py, Telegram админу при сумме>0 **БЕЗ ТЕЛЕФОНА** (исправлено в этом раунде, см. «Найденные ошибки») | испр. (F2, P3) |
| `GET /admin/debts` → `admin_debts` | сдвинулось | очередь pending-долгов, сгруппированная по водителю | только admin; `representative debt_id` на группу — по нему работают confirm/reject; `driver_phone` в ответе — это НЕ утечка (ответ только админу, авторизованному видеть телефон для сверки СБП-перевода) | ок |
| `DebtActionIn` | 153–155 | тело запроса confirm/reject: опциональная `amount_kop` — то, что админ ВИДИТ на экране | — | ок |
| `_debt_changed(e)` | 158–164 | оформляет `409` с человеческим (двуязычным) текстом по `DebtChanged` | не тупиковая ошибка — «обнови список и сверь» | ок |
| `POST /admin/debts/{id}/confirm` → `admin_confirm` | 167–197 | подтвердить перевод: весь pending водителя → paid | только admin; долг не найден → `404`; `DebtChanged` → `409`, НИЧЕГО не меняется; при реальном погашении — `admin_action` в журнал + пуш «Долг подтверждён» | ок |
| `GET /driver/taxi-rides` → `my_taxi_rides` | 200–207 | список своих завершённых такси-поездок с расшифровкой денег | по СВОЕМУ токену; `limit` зажат внутри `debt.driver_rides` | ок |
| `ForgiveIn` | 210–211 | тело запроса прощения долга: `reason` (до 300 симв.) | — | ок |
| `POST /admin/debts/{id}/forgive` → `admin_forgive` | 214–244 | списать долг по-человечески (пассажир не заплатил/спор), БЕЗ выдумывания несуществующей оплаты | только admin; долг не найден → `404`; уже `paid` → настоящий no-op (`{"already": true}`, без повторной записи/пуша); иначе → `paid` + `note="Списан админом: …"` (`WRITTEN_OFF_PREFIX`) + `admin_action` + пуш | ок |
| `POST /admin/debts/{id}/reject` → `admin_reject` | 247–277 | деньги не пришли: pending водителя → обратно unpaid, тратит «слово» | только admin; долг не найден → `404`; `DebtChanged` → `409`; при реальном возврате — `admin_action` + пуш «Оплата не найдена» | ок |

## Связи

- `backend/app/debt.py` (этот же лист) — вся денежная логика: `settle_debt_from_wallet`,
  `debt_summary`, `declare_paid`, `admin_confirm`, `admin_reject`, `driver_rides`, `DebtChanged`.
- `backend/app/routers/payments.py` (вне OWNS) — `_activate_payment`, `_start_yookassa`,
  `_sync_provider_status` (импортируются функцией, не модулем — чтобы не плодить циклический
  импорт `payments.py`↔`debt.py`).
- `backend/app/logs.py::admin_action` (вне OWNS) — журнал каждого пишущего админского действия
  (`debt.confirm`/`debt.reject`/`debt.forgive`), только идентификаторы и суммы, не содержимое.
- `backend/app/services.py::notify_admin_telegram`/`push_notification` (вне OWNS) — уведомления.
- `backend/app/security.py::current_user` (вне OWNS) — источник `user.id`/`user.role`.

## Важные правила и тесты

| ID | Правило | Тесты | Ловит поломку? |
|---|---|---|---|
| R-access-1 | Все `/admin/debts*` — только роль admin | backend/tests/test_debt.py::test_admin_debts_requires_admin | да — M31 |
| R-access-2 (IDOR) | `/driver/debt`, `/driver/debt/paid`, `/driver/taxi-rides` — только по СВОЕМУ токену | backend/tests/test_debt.py::test_debt_is_per_token_no_idor, backend/tests/test_driver_money_gaps.py::test_driver_rides_are_private | покрыто существующим набором; структурная защита (`user.id` из токена, нет параметра «чей долг») — отдельную мутацию не заводил |
| R-money-1 | Повторное прощение уже списанного долга — настоящий no-op, без второй записи в журнал/второго пуша | backend/tests/test_driver_money_gaps.py::test_admin_can_forgive_debt | да — M32 |
| R-money-2 | Повтор оплаты картой при зависшем платеже переиспользует ту же строку `Payment` (тот же idempotence-key к ЮKassa), не плодит новые на каждый ретрай | backend/tests/test_audit_fix_be06_retries.py::test_debt_timeout_retries_the_same_payment | да — M33 |
| R-money-3 | Просмотр своего долга сначала гасит его деньгами из кошелька, и только потом показывает сводку | backend/tests/test_the_wallet_works_and_the_ride_is_free.py::test_кошелёк_гасит_долг_по_комиссии | да — M34 |
| R-money-4 | Сумма изменилась между «админ увидел» и «нажал confirm/reject» → `409`, ничего не меняется | backend/tests/test_the_admin_confirms_what_he_saw.py::test_the_admin_sees_the_declared_sum, backend/tests/test_the_admin_confirms_what_he_saw.py::test_confirming_a_stale_row_does_not_forgive_new_debt, backend/tests/test_the_admin_confirms_what_he_saw.py::test_rejecting_a_stale_row_is_guarded_too | покрыто мутацией M27 в `debt.py` (логика сверки живёт в `_batch_admin_saw`, роутер лишь транслирует исключение в HTTP-ответ) |
| R-privacy-1 | Отказ «Только для админа» — на ДВУХ языках | backend/tests/walk/l1_1/test_l1_1_privacy_and_bilingual.py::test_r1_admin_only_refusal_is_bilingual | да — M51 |
| R-privacy-2 | Уведомление админу о «Я оплатил» (СБП) НЕ содержит телефон водителя (Telegram — сторонний зарубежный сервис; шапка `debt.py` обещает «суммы не логируем с привязкой к персоне — только id») | backend/tests/walk/l1_1/test_l1_1_privacy_and_bilingual.py::test_r2_declare_paid_telegram_message_has_no_phone | да — M52 |

## Найденные ошибки

**Первая сдача этого листа сказала «ошибок не найдено» и даже «двуязычие текстов ошибок
проверено» — второе было прямой ошибкой карточки, не только кода.** Независимое ревью Opus
нашло обе проблемы ниже (P3, не блокируют, но карточка не должна была писать «проверено»,
когда не проверяла).

| ID | Что было (по-человечески) | Как воспроизвести | Исправление | Тест: до → после |
|---|---|---|---|---|
| E-ba | `_require_admin` бросал голый `HTTPException(403, "Только для админа")` — без башкирского текста, в отличие от ВСЕХ остальных отказов в этом же файле (`herr(ru, ba)`). Башкироязычный не-админ (гипотетически) увидел бы отказ без перевода. Сейчас админ один и русскоязычный — цена ошибки низкая, но карточка первой сдачи заявляла «двуязычие проверено», хотя эту конкретную ручку не проверяла. | `tests/walk/l1_1/test_l1_1_privacy_and_bilingual.py::test_r1_admin_only_refusal_is_bilingual` — дернуть `/admin/debts` чужим токеном, ожидать `detail` словарём с непустыми `ru`/`ba` | `raise HTTPException(403, "Только для админа")` → `raise herr(403, "Только для админа", "Тик админ өсөн генә")` (BA — черновик модели, см. отчёт ведущему) | до правки `detail` был голой строкой (`isinstance(body, dict)` падал), после — словарь с обоими языками |
| E-phone | «Я оплатил» по СБП слал в Telegram ИМЯ, ТЕЛЕФОН и сумму. Telegram — сторонний зарубежный сервис; шапки `debt.py`/`ledger.py` прямо обещают «суммы не логируем с привязкой к персоне — только id». Для сверки перевода админу хватает id (телефон он и так видит в `/admin/debts` по тому же id). | `tests/walk/l1_1/test_l1_1_privacy_and_bilingual.py::test_r2_declare_paid_telegram_message_has_no_phone` — подменить `notify_admin_telegram`, проверить текст сообщения | `f"Кто: {user.name} ({user.phone})"` → `f"Кто: {user.name} (id {user.id})"` | до правки телефон (`tg-test-N`) был в тексте сообщения, после — только имя и id |

Кроме этих двух P3-находок, ошибок не нашёл. Файл — тонкий фасад (291 строка после правок F2),
вся арифметика живёт в `debt.py` (проверен отдельно). Проверил HTTP-специфичные вещи: права,
идемпотентность на уровне роутера (дедуп платежа), честные ответы на `404`/`409`/`503`.

## Проверка нарочной поломкой

| ID | Что сломали | Тест | Результат |
|---|---|---|---|
| M31 | `_require_admin` пропускает всех | test_admin_debts_requires_admin | KILLED |
| M32 | Гейт «уже paid → already» в `admin_forgive` отключён | test_admin_can_forgive_debt | KILLED |
| M33 | Дедуп висящего yookassa-платежа отключён | test_debt_timeout_retries_the_same_payment | KILLED |
| M34 | `my_debt` не гасит кошелёк перед показом сводки | test_кошелёк_гасит_долг_по_комиссии | KILLED |
| M51 | Башкирский текст отказа «Только для админа» заменён на пустую строку | test_r1_admin_only_refusal_is_bilingual | KILLED |
| M52 | Телефон водителя возвращён в текст Telegram-уведомления | test_r2_declare_paid_telegram_message_has_no_phone | KILLED |

Прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.1.json --only M31,M32,M33,M34,M51,M52` → `MUTATIONS KILLED 6/6`.

## Остаток и ограничения

`DebtChanged`→`409` защищён мутацией на уровне `debt.py::_batch_admin_saw` (M27), а не здесь —
роутер лишь ловит исключение и переводит его в HTTP-ответ (`try/except` в `admin_confirm`/
`admin_reject`), собственной денежной логики в этом месте нет. Отдельно не проверял реальный
HTTP-поход к ЮKassa (мокается на уровне `payments.py`, вне OWNS) — только то, что роутер
корректно ветвится по её ответу.

Не завёл отдельную строку правила/мутацию на `if settings.is_prod and settings.payments_provider
== "mock": raise herr(503, …)` (строка 56–57) — однострочная проверка конфига, читал и убеждён,
что верна (прод без настоящего провайдера не должен притворяться, что принял оплату), но
отдельного теста на именно эту комбинацию флагов в существующем наборе не нашёл, а заводить
новый тест ради одной строки с низким риском ошибки посчитал непропорциональным остальной
работе. Отмечаю честно, а не выдаю за проверенное.
