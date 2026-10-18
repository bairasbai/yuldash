# 🚕 «Быстрый заказ» (такси-режим) — статус БЭКЕНДА (Фаза 2)

> Ветка `feat/instant-order`. Флагман Фазы 2 (см. `product-plan-2026-07.md`, разделы Фаза 2 и D1–D4 — на ветке `docs/product-plan`).
> Этот файл — «что сделано / что осталось» по бэкенду, чтобы лид свёл его в product-plan при мерже.
>
> 🔄 **Актуализация 2026-10-10 (сверено с кодом `main`).** Документ — снимок момента создания ветки; ниже статусы приведены к коду. Весь описанный бэкенд живёт в `main` (`backend/app/routers/instant.py` — 43 route-декоратора, `backend/app/instant_service.py`, `backend/app/pricing.py`, `backend/app/taxi_worker.py`). Что изменилось с тех пор:
> - состояний теперь 10: добавлен `scheduled` (предзаказ «на время», `InstantOrderStatus` в `models.py`); эндпоинтов намного больше (`/instant/schedule`, `/instant/scheduled`, `/instant/orders/{id}/wait`, `/waypoints`, `/destination*`, `/payment*`, `/cash-received`, `/lost-item`, `/stuck`, `/winter-check`, чат `ws/instant/{id}/chat`, трек `ws/instant/{id}/location` и др.);
> - цена: тариф `Tariff` усложнён (ночной `night_k`, сурж `SURGE_*`, дальняя подача отдельной строкой, погода/пробки через Яндекс — `pricing.py`, `TAXI_EXTERNAL_PRICING_ENABLED`); формула «база + км + мин» ниже — основа, а не полный расчёт;
> - фоновый воркер **есть**, но не arq, а `python -m app.taxi_worker` по systemd-таймеру (`backend/deploy/yuldash-taxi-worker.{service,timer}`); ленивый reconcile при чтении заказа остаётся;
> - Android-UI такси реализован (`Screen.InstantOrder`, `InstantDriverTrip`, `InstantChat`, `TaxiOnboarding`, админ `AdminTaxi`/`AdminTaxiPulse` и др. в `MainActivity.kt`);
> - деньги (комиссия, долги, кошелёк) реализованы: `ledger.py`, `debt.py`, модели `LedgerEntry`, `CommissionDebt`; `women_only` в matcher реализован (`InstantOrder.women_only` + проверка `_verified_female_driver`). Подробнее — [payments-ledger-backend.md](payments-ledger-backend.md), [monetization.md](monetization.md).
> - Такси выключено глобально флагом `TAXI_ENABLED` (по умолчанию `False`); при `ENV=prod` и `TAXI_ENABLED=true` без `REDIS_URL` сервис не стартует (`validate_production`).

## ✅ Что сделано (бэкенд целиком, с тестами)

### D1. Presence (Redis GEO)
- `POST /instant/presence` — heartbeat координат водителя «на линии»: `GEOADD presence <lng> <lat> driver:{id}` + `SET presence:hb:{id} EX 60`. Переиспользует существующий redis `services._cache_client()`.
- Без Redis — **graceful**: heartbeat возвращает `ok:false`, заказ просто не находит водителей (→ `expired`), сервис не падает.
- Координаты **не логируем** и в БД **не пишем** (presence эфемерный).

### D2. Тариф (сервер считает сам, клиенту не верит)
- Модель `Tariff(zone, category, base, per_km, per_min, min_price, k, active)`.
- `POST /instant/estimate` — `price = max(min_price, base + per_km·dist + per_min·eta)·k`, округление до 10 ₽; `dist = haversine × road_k`; `eta = dist / avg_speed`. В схеме запроса **нет поля цены** — подделать нельзя.
- Зоны: «город» / «межгород» по порогу дистанции (`instant_intercity_km`, дефолт 40 км).
- Сид тарифов (город + межгород) в `lifespan` (`seed_tariffs`) — **всегда** при пустой таблице (нужны и в проде, в отличие от `seed_demo`).

### D1/D4. Заказ + машина состояний
- Модель `InstantOrder` (все поля D4 + таймстампы каждого перехода).
- Состояния: `created → searching → offered → accepted → arriving → onboard → done`; терминальные `cancelled` / `expired` (выхода нет). *(Позже добавлен ещё `scheduled` — предзаказ «на время», до активации ко времени подачи.)*
- Эндпоинты переходов: `/accept /decline /arrived /onboard /done /cancel` — под row-lock (`with_for_update`, паттерн из `bookings.py`) **+ атомарный условный UPDATE** (`WHERE status=<ожидаемый>`), поэтому гонка двух `accept` даёт второму **409** корректно и на Postgres, и на SQLite.
- Идемпотентность: повторный тап `arrived/onboard/done/cancel` не ломает (accept — намеренно НЕ идемпотентен, чтобы ловить гонку).
- Приватность: телефоны водителя/пассажира раскрываются **только после accept** (как в бронях)
  и закрываются через **48 ч после `done`** (дольше — пока заявлена «забытая вещь»):
  `instant_service.phones_open`. Имя, машина и госномер в истории остаются — по ним разбирают спор.

### Правки аудита 2026-08-07 (что изменилось в поведении API)
- **Отмена заказа из очереди «подожду машину»** (`expired` + `wait_until`) реально отменяет:
  раньше `/cancel` отвечал 200 и не делал ничего, а воркер всё равно присылал водителя.
  Ответ теперь `status=cancelled`, `wait_until` снимается при любой отмене.
- **Новый заказ гасит очередь старого**: `POST /instant/orders` снимает `wait_until` со всех
  `expired`-заказов пассажира. Иначе «Заказать заново» давало два живых заказа и двух водителей.
- **`start_matching` / `advance_after_no_accept` пишут статус условно** (CAS, как `transition`):
  фоновая пачка больше не воскрешает отменённые, принятые и завершённые заказы.
  `search_round` обнуляется на каждом круге — лимит `instant_max_offers` теперь «на круг»,
  а не «на всю жизнь заказа» (иначе очередь ожидания молча умирала после 8 офферов).
- **Гео-проверка «Я на месте» заработала**: читался ключ `livepos:instant:{id}`, а WS-трек
  пишет `livepos:order:{id}` — проверка не срабатывала никогда. Позиция из кэша учитывается,
  только если она свежая (≤60 с): у водителя, потерявшего связь на подъезде, кнопка не отбивается.
  Клиенту стоит слать `{lat,lng}` в теле `/arrived` — сейчас Android шлёт пустое тело.
- **Заказ, застрявший в `created`**, закрывается воркером по `order_stuck_hours` (раньше не
  закрывался никем и навсегда блокировал пассажиру кнопку «Заказать»).

### D1. Matcher
- `GEOSEARCH` с расширением радиуса **3→7→15 км** (первый непустой круг подходящих).
- Фильтры: online, verified, не занят другим активным заказом, не в чёрном списке пассажира, не сам пассажир. *(Позже добавлены: класс авто/опции салона, `women_only` — только проверенная женщина-водитель.)*
- Скоринг: `w_dist/дистанция + w_rating·рейтинг` (веса в конфиге).
- Оффер top-1 через `send_push` (**data-payload** — полноэкранная карточка на клиенте) с таймаутом (`instant_offer_ttl_sec`, дефолт 20с).
- decline/timeout → следующий кандидат; кандидатов нет → пассажиру «рядом никого» (заказ `expired`, `send_push`).
- **Таймаут оффера ленивый** (reconcile при чтении заказа / поллинге оффера) — работает без фонового воркера.

## 🧪 Тесты
`backend/tests/test_instant.py` — 25 тестов на момент написания (сейчас в файле 28 `test_*`-функций; плюс `test_instant_scheduled.py`, `test_instant_order_participants_only.py`, `test_instant_search_clock.py` и др.). Прогон «206 passed, 1 skipped» — **на момент ветки**; сейчас `backend/tests/` — 529 файлов, CI гоняет весь набор на SQLite и на Postgres (`.github/workflows/ci.yml`).
Покрыто: тариф (сервер считает, клиент не влияет, зоны), presence (+без Redis), matcher (ближайший, расширение радиуса, фильтры unverified/busy/blocked, пусто→expired), полная машина состояний, запрет неверных переходов, из терминала выхода нет, гонка двух accept (детерминированно + потоками), таймаут оффера, decline→следующий, отмены обеих сторон, приватность телефонов, доступ чужого → 403, заказ без Redis не падает.

Alembic: `upgrade head` на чистой БД проходит + идемпотентен (проверены оба пути: baseline-create_all no-op и прод-create_table с индексами).

## ⚙️ Конфиг (новые ключи `config.py`, дефолты рабочие)
`instant_road_k=1.3`, `instant_avg_speed_kmh=40`, `instant_intercity_km=40`, `instant_offer_ttl_sec=20`, `presence_ttl_sec=60`, `instant_w_dist=1.0`, `instant_w_rating=0.4`, `instant_max_offers=8`.
Для реальной работы matcher **нужен Redis** (`REDIS_URL` в `.env`) — на проде он уже есть (WS-чат/кэш). Тарифы правятся в БД (таблица `tariff`).

## 🔜 Что осталось (не входит в эту ветку)
- ~~**UI** (Android): экран пассажира «Куда едем?» → цена → «Ищем машину» → «Водитель едет» (трек), экран водителя (входящий оффер с таймером → навигация → приехал/посадил/завершил). Отдельная ветка следом.~~ **Сделано** (экраны `InstantOrder`, `InstantDriverTrip`, `InstantChat`, `TaxiReceipt`, `MyTaxiTrips` и др. в `MainActivity.kt`).
- **arq-воркер** (опционально): честный фоновый таймаут офферов и авто-протухание вместо ленивого reconcile. *Статус 2026-10-10:* arq не используется; вместо него сделан `app/taxi_worker.py` (systemd-таймер: активация предзаказов, закрытие зависших заказов, перезапуск поиска у «подожду машину», сдвиг протухших офферов); ленивый reconcile сохранён.
- ~~**women_only** в matcher — отложено: у `User` нет поля пола (появится в уровнях доверия, Фаза 4).~~ **Сделано:** `InstantOrder.women_only`, фильтр в matcher (`_verified_female_driver` → `safety_logic.is_verified_female_driver`).
- ~~**Деньги** (комиссия/ledger) — Фаза 3, отдельно.~~ **Сделано** отдельно: `ledger.py`, `debt.py` ([payments-ledger-backend.md](payments-ledger-backend.md)).
- Александру: убедиться, что `REDIS_URL` задан в проде (он есть), проверить/поправить стартовые тарифы в таблице `tariff` под реальные цены.

## 🌐 Переводы (RU/BA) — на проверку Александру
Бэкенд-строки пушей/ошибок сейчас только RU (это серверные тексты, не UI-надписи). Двуязычие — на UI-слое (`appText`) при реализации экранов. Черновой башкирский для будущих UI-строк «Быстрого заказа» соберём в `tasks.md` на этапе UI. *(Актуализация 2026-10-10: ошибки API уже двуязычные — хелпер `herr(status, ru, ba)` из `app/errors.py` возвращает `detail={ru, ba}`, в том числе в `routers/instant.py`; про тексты пушей по коду не проверялось.)*
