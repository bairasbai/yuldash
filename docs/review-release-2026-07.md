# 🔍 Аудит `release-2026-07` + ядра — 2026-07-17

> Senior code-review силами 6 параллельных субагентов по зонам + верификация лида по коду.
> Ядро (`YuldashApp/ViewModel`) ревьюилось отдельно (3 ревьюера) → фиксы в PR #77.
> Метод: чтение `git diff origin/main...origin/release-2026-07` и `git show`, read-only.

## ⚠️ Критичный контекст — флаги (без него severity читается неверно)
- Прод сейчас: **`PAYMENTS_PROVIDER=sbp_manual`**, **`PAYOUTS_ENABLED=false`** (оплата «на доверии», реального эквайринга нет).
- **`release-2026-07` ещё НЕ в проде** (draft-агрегат, 63 из 76 неслитых веток, +60k строк).
- Часть находок «за флагом» — не стреляют в текущем `main`, но **сработают при выкатке релиза и/или флипе `yookassa`/выплат**. Это блокеры именно РЕЛИЗА, а не действующего прода.

---

## 🔴 БЛОКЕРЫ РЕЛИЗА — закрыть до выката `release-2026-07`

### B1 · P0 — Фантомная онлайн-оплата поездки [ПОДТВЕРЖДЕНО ЛИДОМ по коду, оба конца]
**Файлы:** `backend/app/routers/wallet.py:48-70` (`_pay_cashless`), `backend/app/payments.py:60-61` (`create_payment`), клиент `InstantOrderScreen.kt:454` + `ApiClient.kt:3140` (`OnlinePayGate`).
**Суть:** гейт в `_pay_cashless` ловит только `provider == "mock"` (строка 53), а ветки `sbp_manual` нет (у boost/donate/support она есть). При `sbp_manual` `create_payment` (`provider != "yookassa"`) возвращает `status="succeeded", mock=True` без денег → `_activate_payment` начисляет ledger. Клиент прячет кнопку оплаты **только по 503**, а сервер в `sbp_manual` отдаёт не 503, а `succeeded` → кнопка показана.
**Сценарий:** после выката релиза пассажир на завершённом такси-заказе жмёт «Оплатить картой» → сервер `succeeded` → `order.paid=True`, ledger водителя `+earn/−fee` за неоплаченные деньги. Водитель видит «оплачено» → не берёт нал → везёт бесплатно; ledger копит фантом, который выведется реальными деньгами при включении выплат. `reconcile` слеп (earn и payments раздуты одинаково → diff=0).
**Фикс:** в `_pay_cashless` — `if settings.payments_provider != "yookassa": raise HTTPException(503, "Оплата скоро будет доступна")` (тогда клиентский `OnlinePayGate` корректно прячет карту).

### B2 · P1 — Двойная комиссия + фантомный долг на такси-заказе [агент A, высокая уверенность]
**Файлы:** `backend/app/routers/instant.py:439` (`accrue_for_order` на `done`) ⟷ `backend/app/ledger.py:150-166` (`settle_instant_order`).
**Суть:** на `done` всегда создаётся `CommissionDebt` (Модель А — «водитель взял нал, должен комиссию»). Если тот же заказ оплачен картой (Модель Б), `settle_instant_order` пишет `earn/fee` в ledger, но долг по `order_id` не гасится. Комиссия удерживается дважды + фантомный `unpaid`-долг капает к порогу блокировки (`debt_block_threshold_kop`) → **честного водителя на онлайн-оплате блокирует такси**. Reachable при `PAYMENTS_PROVIDER=yookassa`.
**Фикс:** при безналичной оплате instant-заказа гасить/не заводить долг (`void_debt_for_order(order_id)` в `settle_instant_order` для `_CASHLESS`).

### B3 · P1 — Утечка PII (доверенные контакты) между аккаунтами [ПОДТВЕРЖДЕНО ЛИДОМ]
**Файл:** `YuldashApp.kt:661-668` (загрузчик контактов в release-версии).
**Суть:** релиз сменил ключ на `LaunchedEffect(sessionVersion)` (перечитывает при входе), но оставил `if (list.isNotEmpty())` — при пустом ответе список НЕ чистится. Соседи (`rides`, `partnerAds`, `localRequests`) чистят безусловно; контакты — исключение.
**Сценарий:** A вышел → B без контактов вошёл в том же процессе → `getContacts()` пусто → `clear()` не вызван → **B видит имена+телефоны экстренных контактов A**. При SOS/шаринге у B уйдёт чужой `contactId` (спасает только серверная проверка владельца).
**Фикс:** убрать `isNotEmpty`-guard — `clear()` + `addAll()` безусловно. Плюс страховка в `onLogout`. (В старом `main` эта же дыра закрыта иначе — см. PR #77 `clearUserData()`; в релизе живёт в другой форме и НЕ покрыта фиксом PR #77.)

### B4 · P1 (152-ФЗ, граничит с P0) — Документы таксиста не стираются при удалении аккаунта [ПОДТВЕРЖДЕНО ЛИДОМ]
**Файл:** `backend/app/account.py:65-74` (сбор `media_urls`).
**Суть:** `media_urls` собирает медиа `DriverProfile`/`CourierApplication`/голосовые, но НЕ `TaxiApplication`. Строка `TaxiApplication` удаляется из БД, а её файлы в `/secure/docs` остаются навсегда: `selfie_url` (лицо+права), **`criminal_record_url` (справка о несудимости)**, `osago_url`, `permit_photo_url`. Ретеншен-чистка их не трогает. «Необратимое удаление» (обещание 152-ФЗ) не выполнено для самой чувствительной категории.
**Фикс:** добавить `media_urls += [ta.selfie_url, ta.permit_photo_url, ta.osago_url, ta.criminal_record_url]` для `TaxiApplication` (рядом со сбором `ca`). Вторично — `Ad.image_url`.

### B5 · P1 — Отмена посылки после закупки товара курьером [ПОДТВЕРЖДЕНО ЛИДОМ]
**Файл:** `backend/app/routers/parcels.py:252-273` (`parcel_cancel`) + `courier.py:716-739` (`courier_goods_cost`).
**Суть:** `parcel_cancel` разрешает отмену при любом статусе кроме `delivered/canceled` и не смотрит на `goods_actual_kop`/`buy_bring`. Курьер закупает товар своими деньгами (до 5000 ₽), а отправитель отменяет → курьер с товаром и без денег; автовозврата нет, только ручной спор. Серийно жжёт курьеров.
**Фикс:** блокировать отмену для `buy_bring` при `goods_actual_kop > 0` → 409 «Курьер уже купил товар — только через спор».

---

## 🟡 ВАЖНОЕ — до включения соответствующих фич

| # | P | Находка | Файл | Фикс |
|---|---|---|---|---|
| V1 | P1 | `kopToRub`/`fmtRub` отбрасывают копейки (floor) → курьеры/партнёры видят неверные суммы, «переведи X по СБП» занижает | `CouponsScreen.kt:93`, `WalletScreen.kt:~346`, ~30 вызовов | форматировать рубли+копейки |
| V2 | P2 | Гонка активации купонов пробивает `limit_total`/`limit_per_user` (нет `UniqueConstraint`) | `coupons.py:257-316`, `models.py:1032` | `with_for_update()` + частичный unique-индекс |
| V3 | P2 | Гонка промокодов: двойная активация «одного на жизнь» → двойные кредиты | `promo.py:141-177`, `models.py:1074` | `UniqueConstraint(user_id)` + перехват `IntegrityError` |
| V4 | P2 | Deadlock: `cancel_ride` (Ride→Booking) vs `cancel_booking` (Booking→Ride) — обратный порядок локов | `rides.py:440`, `bookings.py:346-364` | единый порядок: сначала Ride, потом Booking |
| V5 | P2 | `GET /rides/{id}` без auth обходит `only_trusted`/`blocked` → аноним скрапит закрытые рейсы | `rides.py:493-498` | `current_user_optional` + фильтры + 404 на не-shareable статусы |
| V6 | P2 | Парсинг в `.map{}` после `call()` кидает мимо `Result` → краш вместо ошибки | `ApiClient.kt:2223` (`getSettlementPopularRoutes`), системно | `opt*`+`mapNotNull` точечно; парсеры → `mapCatching` |
| V7 | P2 | Идемпотентность выплаты обходится при пустом ключе (нет DB-unique на `ext_id`) | `ledger.py:98`, `wallet.py:221` | отклонять пустой ключ + частичный unique |
| V8 | P2 | `request_payout` держит row-lock по `User` через 30-сек HTTP ЮKassa → истощение пула | `ledger.py:93-128` | вынести вызов провайдера за лок |
| V9 | P2 | SMS-флуд на `/stuck` и `winter-check` (нет кепа/идемпотентности) — **латентно, SMS в проде `mock`** | `safety.py:527,578-607` | кеп как у `/sos` + флаг «уже эскалировал» |
| V10 | P2 | Вся прод-защита завязана на точное `env=prod` — опечатка → fail-open (webhook без секрета) | `config.py:294`, `main.py:39` | требовать распознанный `ENV`, иначе `RuntimeError` |
| V11 | P2 | `redeem_invite` не перепроверяет уровень пригласившего → разжалованный L2 плодит L3 | `trust.py:81-111` | проверять `trust_level(owner)` при активации |
| V12 | P2 | `verifyCode` не чистит `respCache` (усиливает B3); `localRequests` держит заявки прошлого при сбое сети | `ApiClient.kt:353`, `YuldashApp.kt:636` | чистить кеш на логине; reset-then-load |
| V13 | P2 | Бан устройства по client-controlled `X-Device-Id` — обход сменой заголовка (комментарии переоценивают) | `antifraud.py` | привязать к Play Integrity; убрать «обход закрыт» из комментов |

---

## 🟢 Мелочи / P3
- Тач-цель `SaveAsChip` 40dp < 48dp (`SavedPlacesScreen.kt:214`).
- Односторонняя «мин» на пине карты (`InstantOrderScreen.kt:304`) — незаметно (в BA так же).
- Сплэш затирает deep-link пуша чата/SOS на холодном старте (`YuldashApp.kt:358,366` — нет guard как у соседей).
- `pending→done` минуя `confirmed` (`bookings.py:277`); `respond_to_request` дубль без индекса (`requests.py:354`).
- Ленивый бэкфилл токена live-ссылки без `expires_at` (`family.py:34`) — гео не течёт (finished без координат).
- `/r/{id}` перечисление поездок по id — паритет с существующим `/rides/{id}`, не регресс.
- Мёртвый конфиг `promo.limit_per_user`; метрика `collected_fee_kop` завышает собранное; floor копеек ожидания (`instant_service.py:509`).
- Магические ARGB-константы маршрута/статистики (нативный Canvas, не §11-нарушение).

---

## ✅ Проверено ЧИСТЫМ (сильные стороны релиза — не перепроверять)
- **Публичная live-ссылка `/t/{token}`** — уровень Signal: 128-бит токен, TTL 24ч, отзыв, `done/cancelled` без координат, только имя, no-referrer, XSS закрыт, перебор нереален.
- **WS-гео** (trip/instant/parcel): участник ресурса, реаутентификация каждые 15 кадров, координаты не в БД и не в логах, TTL 120с.
- **Деньги-примитивы**: цена/сурж считает сервер (клиенту не верят), комиссия `Decimal ROUND_HALF_UP`, сурж ≤1.5, цена ≥ `min_price`. Клиент шлёт только `method`, не сумму.
- **Идемпотентность**: вебхук ЮKassa (guard `succeeded` под `FOR UPDATE`), `CommissionDebt UNIQUE(order_id)`, `settle_*` под row-lock+флаг `paid`, вывод денег (UUID-ключ+busy-guard).
- **Овербукинг/гонки**: `book`/`cancel_booking`/`accept_response`/`redeem_invite`/`parcel_accept` — везде `SELECT FOR UPDATE`. 
- **Авторизация/IDOR**: чат, отзывы, заявки, поездки, кошелёк, поддержка, жалобы — scope по участнику/владельцу; admin-ручки серверно 403.
- **OTP/refresh/webhook**: `compare_digest`, кеп 5, троттл 3/мин, код не логируется; refresh — one-time ротация под `FOR UPDATE`; Telegram-webhook fail-closed в проде.
- **Клиент**: 0 `Log/println`, auth-флаги корректны, 401/refresh single-flight `Mutex`, ретрай идемпотентно-безопасен.
- **Новые экраны**: двуязычие (0 сырых русских), состояния загрузка/пусто/ошибка+повтор, ключи списков, доступность, защита от крашей — покрыты системно через общий кит.
- **Удаление аккаунта**: очень широкий охват таблиц в FK-безопасном порядке, координаты стёрты везде (кроме дыры B4 с файлами).

---

## 🔗 Связь с PR #77 (фиксы ядра)
PR #77 закрыл 3 находки ядра в старом `main`: приватность контактов (`clearUserData`), глушение GPS при выходе, `Intro` в back-stack. CI (`backend-tests`) зелёный, draft. **Важно:** контакты в `release-2026-07` реализованы иначе (`sessionVersion`) и дыра там осталась в форме `isNotEmpty`-guard (B3) — фикс PR #77 её НЕ покрывает, нужна отдельная правка при слиянии релиза.

## 📋 Рекомендация по порядку
1. **До выката релиза:** B1 (P0), B3, B4, B5 — приватность/деньги/152-ФЗ, чинятся тривиально.
2. **До флипа `yookassa`/выплат:** B2, V7, V8.
3. **Ближайшим спринтом:** V1–V6, V9–V13.
4. Мелочи — по мере касания файлов.

_Правок в код релиза не вносил (ветка не моя, read-only). Файл — reference для агента-исполнителя._
