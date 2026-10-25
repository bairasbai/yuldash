# CP-06 — Доход курьера и честная комиссия

| | |
|---|---|
| Статус | не начато; описанные проверки NOT_EXECUTED |
| Ждёт «да» Александра | изменение момента фиксации/базы/ставок комиссии, миграция и показ обещанного вознаграждения |
| Зависит от | CP-05; F1/F3/F9 и economics review; LG-03 |
| Не параллельно с | Частями, затрагивающими перечисленные файлы; точная очередь в [D4](04-data-migrations-and-queue.md#d4-очередь-общих-файлов) |
| Деление на PR | A сервер/схема → B Android и C PWA после договора A → D независимая проверка |
| Проверка security/privacy | да: роли, личные данные, повтор или деньги |
| Двигает приёмку | 02,03,07,09 |
| Зона файлов | backend/app/routers/courier.py, ledger.py, models.py; Android CourierScreen.kt/CourierEarningsScreen.kt; PWA CourierScreen.tsx/CourierEarningsScreen.tsx; новые tests/test_courier_remuneration_snapshot.py |
| Источник | Проверенный main и PR211, source register; целевые договоры — OWN_DESIGN_NOT_VENDOR_SPEC |
| Сверено с кодом | main 0ef03f4194012431e0996be4d8e748d37e34d63d, 25.10.2026; перед кодом обновить |

## 1. Что получит человек

Курьер до принятия видит сколько заработает за работу, что заберёт платформа и какие расходы/риски остаются его. Цена клиента не подменяется обещанием чистой прибыли.

## 2. Что уже есть в коде

courier.py:339–401 lifetime delivered comparator<30/<100, minimum10 ₽ и defaults3/8/15%; finalize404–415 при завершении. Текущий delivery может войти в count после autoflush; точный ordinal boundary требует runtime. Pickup/weather исключены из базы, goods отдельно.

Это read-only сведения, не результат запуска. [Реестр источников](07-source-register.md) разделяет существующий код, старый план, исследование и предлагаемое изменение.

## 3. Что сделать

### Разница и решения

Сохранить существующую экономику как baseline; предложить immutable remuneration на accept. OWNER_PENDING: выбор tier basis, threshold semantics, min ceiling, component exceptions и funding promo. Никаких молчаливых новых процентов.

До implementation денежной части владелец выбирает: сохраняем текущую формулу и фиксируем на accept или меняем модель после shadow-анализа. До решения допустим только shadow ledger/preview, не обещание новой выплаты.

### A Сервер

1. Детерминированный estimator исходной модели, вывод counter_basis/value и effective fee; golden boundary29/30/31,99/100/101 с current-inclusive/exclusive.
2. Snapshot F3 привязать к assignment, freeze rate/min/promo/exclusions на принятие по approved policy.
3. Клиентский promo финансируется указанной стороной; сумма promised net не меняется после config edit.
4. Amendment пересчитывает новые условия и получает согласие, не переоценивает старую выполненную часть.
5. Минимум/rounding/refund/return exceptions едины в offer, earnings, debt, payment snapshot.

### B Android

Карточка оффера: услуга, комиссия, гарантировано до налогов/расходов, время/км/подача/возврат/аванс. Не прятать существенный минимум в tooltip.

### C PWA

Тот же DTO; подробный расчёт доступен до accept, а не только после delivered. История показывает исходный снимок, не текущий тариф.

### Межмодульный договор R2

Remuneration M4 FK ссылается на assignment identity M1, не будущие offers M6. F1 clamp base≥0 и pct≤0 → fee 0 проверяются отдельно от положительного minimum. Snapshot freeze не исправляет 40 km cliff и не утверждает новый процент. F10 direct payment не создаёт вторую платформенную выплату.

### Решение владельца OD-2026-10-25-01

On-route platform fee=0 до проверки minimum/ladder, при допустимой согласованной оплате водителю; dedicated commission применяется и к scheduled работе. Zero fee не paid услуги/товара и не бесплатная перевозка. Не удерживать скрытый service fee. DRIVER_SET_PRICE с ADVISORY_ONLY советом платформы утверждён OD-04; dedicated ставки отдельно OWNER_PENDING, применение в production требует своих gates. Бесплатные режимы имеют acquisition/subsidy costs, нет гарантии самоокупаемости.

### Уточнение R4

F14A задаёт cost inputs/contribution/break-even отдельно city/rural/intercity и free-mode acquisition budget. Нет гарантии покрытия расходов; невыгодный маршрут scheduled/consolidated/declined по gates, а не скрытая доплата. Passenger Ride.price в legacy рублях не courier amount в копейках.

### Решение OD-04 и договор цены

DRIVER_SET_PRICE, ADVISORY_ONLY, sender/payer consent до create; request/proposal → F2 quote → single-use order по R4A в 11. Рекомендация не обязательный диапазон и не fallback сумма. Ride meeting по умолчанию, door detour только prior consent/route-time context и RS guards. Никакой скрытой комиссии; SIM-36 проверяет stale proposal, duplicate create и mode switch.

### API и события

Offer/remuneration DTO: gross_kop, commission_base_kop, commission_kop, guaranteed_courier_kop, policy_version, counter_basis/value, exclusions, conditional extras. Unknown snapshot не изображать как guarantee.

Общий контракт [C1–C9](02-domain-api-contracts.md) обязателен: actor/phase allowlist, версии, денежные единицы, durable intent, replay и удаления. Новые DTO не расширяют права автоматически.

## 4. База данных

M4 immutable CourierRemunerationSnapshot, UNIQUE assignment. Existing completed сохраняет фактическую старую комиссию; accepted legacy terms требуют отдельной reconciliation/consent политики.

Проверки previous-main → target, synthetic legacy rows, SQLite + PostgreSQL, backfill/crash/resume, downgrade/up и runtime rollback выполняются по [D2](04-data-migrations-and-queue.md#d2-порядок-миграций). Новые персональные поля имеют purpose/retention/account deletion по D3; неизвестный срок блокирует production, не заменяется вечным хранением.

## 5. Надписи

«Тебе за доставку… до налогов и расходов». «Комиссия… включая минимальный сбор…». «Товар и возмещаемые расходы отдельно».

Все строки через appText(ru, ba)/соответствующий PWA словарь. Башкирский — черновик для носителя, не автоматический linguistic PASS. Денежные строки только через общий форматтер копеек; секреты не попадают в preview уведомлений.

## 6. Состояния экрана

Доход известен/ожидает уточнения/изменение условий/promo завершилось/старый заказ без снимка/спор удержания.

Обязательны loading/empty/error/offline/retry, сохранённый ввод, длинный BA, light/dark, крупный шрифт,48 dp и контраст≥4.5:1. Состояния requested/pending/applied/unknown/conflict различимы; optimistic UI не доказывает завершение.

## 7. Тесты

SIM-33: negative contribution не объявляет конечную break-even volume; on-route subsidy cost не превращается в commission; units rub/kop не смешаны.

SIM-28/F1A: on-route paid driver + platform fee 0 при любом minimum/tier, scheduled dedicated не zero branch, no hidden fee, quote/statement/net parity; mode change только consent.

R2-FEE: отрицательная source base, zero/negative rate, promo/return, base меньше minimum; comparator/autoflush boundary отдельный runtime case. Ни clamp, ни rounding не используются для тайной смены тарифа.

Табличные min/tier/rounding/zero/promo/return; race two deliveries finish around threshold; snapshot unchanged after config/count change; refund reverses once; mutation recompute at finish — red. Отдельно E-02 из 08: weather присутствует в offered total, но исключён final; при одинаковых условиях базы должны совпасть. Factual payroll not inferred from UI.

Все тесты из этого раздела NOT_EXECUTED. Исполнитель сохраняет точный SHA, команды/exit codes, платформу, expected/actual, первичные артефакты. Для затронутого сервера ruff/pytest и migration guards; Android unit/Compose/GuardTest + assembleDebug; PWA check/build и целевые тесты. PostgreSQL race, два физических телефона и реальный provider не подменяются SQLite/emulator/mock.

## 8. Приёмка

Показанный offer net воспроизводим из immutable snapshot, ledger не меньше promise без согласованного adjustment. Таблица трёх сторон reviewed владельцем, все новые ставки остаются невыбранными пока нет решения.

Независимый принимающий сверяет код, права/контракты и первичные доказательства, не только список тестов. Принятие текста не принимает реализацию. Продуктовый статус «принято» только в существующем audit registry; [COURIER-E2E](../../audit-traceability/courier-scope.md) не сокращается.

### Мозг

После разрешённой реализации: architecture — изменённые экраны/модули/связи; decisions — реальные решения владельца и принятые договоры; tasks — прогресс и BA на проверку; lessons — воспроизводимые ошибки. Общие docs имеют одного писателя. Этот документационный пакет не проставляет runtime PASS.

## 9. Риски и запрещённые сокращения

Не считать done месячным, не цитировать старые 3/5/8 comments как current. НПД налог не автоматически с net после комиссии. Без owner approval нет коммерческого изменения.

Не выдавать default за production config, plan за реализацию, локальный callback за серверный commit, HTTP success за физическое вручение или платёж. Не запускать реальные заказы/платежи ради теста без отдельного разрешения. Новые зависимости/SDK/права не добавлять без обоснования по AGENTS.

## 10. Журнал статуса

| Дата | Статус | Кто | PR / коммит | Заметка |
|---|---|---|---|---|
| 25.10.2026 | не начато | — | — | ТЗ подготовлено; код, миграции и runtime не выполнялись |
