# W1-01 — Права доступа и IDOR по бэкенду Юлдаша

Аудит: только чтение. Прод (`yulbash.ru`) не трогался. Разобрано 350 маршрутов в 41 роутере
(`backend/app/routers/*`) + сервисные слои (`instant_service.py`, `services.py`, `quality.py`,
`safety_logic.py`, `security.py`, `middleware.py`).

Три из четырёх находок доказаны исполняемым PoC на локальной SQLite (файлы удалены после
прогона, репозиторий чист). Вывод PoC приведён дословно.

---

## ПОДТВЕРЖДЁННЫЕ ДЫРЫ

### [P0] IDOR в `POST /instant/orders/{order_id}/decline` — телефон водителя и точная точка подачи любому вошедшему

- **Файл:строка**:
  - `backend/app/routers/instant.py:563-568` (эндпоинт `decline`)
  - `backend/app/instant_service.py:1047-1054` (`decline_offer` — «тихий» ранний return)
  - `backend/app/instant_service.py:1246-1250, 1348-1372` (`order_payload` — раскрытие полей)

- **Что не так**: `decline` не проверяет вообще ничего — ни участие, ни оффер. Он сразу зовёт
  `decline_offer`, а тот при «оффер не твой» не бросает 403, а **молча возвращает объект заказа**
  (сознательно: чтобы двойной тап водителя не был ошибкой). Возвращённый объект уходит в
  `order_payload`, где роль вычисляется как «водитель, если id совпал, иначе **пассажир**».
  Посторонний получает роль `passenger` и видит витрину пассажира целиком: телефон водителя,
  его имя/машину/госномер, точные координаты подачи и текст адреса. Все остальные ручки
  `instant.py` защищены (`_order_for_view`, `transition`→`_guard_actor`, явные сравнения) —
  `decline` единственная выпавшая.

- **Как эксплуатируется**: любой зарегистрированный пользователь (обычный пассажир, регистрация
  по SMS/Telegram), перебором `order_id` от 1:
  ```
  POST /instant/orders/{id}/decline
  Authorization: Bearer <любой валидный токен>
  {"reason": "far"}
  ```
  - заказ в статусе `accepted/arriving/onboard/done` → в ответе **`driver_phone`** реального
    водителя;
  - заказ в статусе `searching/offered` (то есть **до подтверждения поездки**) → в ответе
    **точные `from_lat`/`from_lng` и `from_text`** пассажира. Это прямое нарушение правила
    §4/§7 «геолокация скрыта до подтверждения поездки»: `blur_from` включается только для
    роли `driver`, а атакующему присвоена роль `passenger`, поэтому округления не происходит.

  Побочно: `decline_offer` в этой ветке не пишет `OfferDecline` и не двигает статус — атака
  не оставляет следа в данных заказа и не ломает поездку, то есть тихая.

- **Доказательство** (код):
  ```python
  # app/routers/instant.py:563
  @router.post("/instant/orders/{order_id}/decline")
  def decline(order_id: int, body: DeclineIn | None = None,
              user: User = Depends(current_user), session: Session = Depends(get_session)):
      reason = ((body.reason if body else "") or "").strip().lower()
      if reason and reason not in _DECLINE_REASONS:
          reason = "other"
      order = isv.decline_offer(session, order_id, user.id, reason=reason)   # ← прав не проверяет
      return isv.order_payload(session, order, user)                          # ← отдаёт витрину

  # app/instant_service.py:1052
      if order.status != S.offered or order.current_offer_driver_id != driver_id:
          # оффер уже не актуален (принят/протух/отдан другому) — не ошибка
          return order              # ← постороннему возвращается ОБЪЕКТ, а не 403

  # app/instant_service.py:1248
      role = "driver" if (order.driver_id == viewer.id
                          or order.current_offer_driver_id == viewer.id) else "passenger"
      unlocked = order.status in UNLOCKED                       # accepted/arriving/onboard/done
  # app/instant_service.py:1359
      "driver_phone": (driver.phone if (unlocked and driver and role == "passenger") else ""),
  ```

- **Доказательство** (PoC, вывод дословно):
  ```
  STATUS: 200
  driver_phone   = '+79990001122'
  driver_name    = 'PocDrv'
  from_lat/lng   = 52.591 58.317
  from_text      = 'Баймак, ул. Ленина 12'
  to_text        = 'Сибай'
  passenger_phone= ''

  STATUS(searching): 200          # ← заказ ещё ищет машину, поездка НЕ подтверждена
  from_lat/lng = 52.591 58.317
  from_text    = 'Баймак, ул. Ленина 12'
  ```
  (посторонний аккаунт, никакого отношения к заказу; заказ №1, водитель и пассажир — другие люди)

- **Как чинить** (корневая причина, не заплатка): в `decline` поставить тот же гейт участия,
  что у соседей — заказ должен принадлежать вызывающему как текущему кандидату:
  ```python
  order = session.get(InstantOrder, order_id)
  if not order or user.id not in (order.driver_id, order.current_offer_driver_id):
      raise herr(404, "Заказ не найден", "Заказ табылманы")   # 404, не 403 — не раскрываем существование
  ```
  Дополнительно закрыть класс ошибок целиком: `order_payload` не должен доверять тому, что
  вызывающий — участник. Сейчас `role` вычисляется как «иначе пассажир», то есть посторонний
  автоматически получает пассажирскую витрину. Правильнее считать роль явно и для
  не-участника бросать/возвращать пустую витрину:
  ```python
  if viewer.id == order.passenger_id:      role = "passenger"
  elif viewer.id in (order.driver_id, order.current_offer_driver_id): role = "driver"
  else: raise HTTPException(403, ...)      # витрину постороннему не собираем вообще
  ```
  Регрессия-сторож: тест по образцу `tests/test_admin_gate_everywhere.py`, который для каждой
  ручки `/instant/orders/{id}/*` дёргает её посторонним аккаунтом и требует 403/404.

---

### [P1] `POST /reports` — любой вошедший ставит такси любого водителя на паузу ~10 лет, без единой совместной поездки

- **Файл:строка**:
  - `backend/app/routers/safety.py:432-465` (`create_report`)
  - `backend/app/routers/safety.py:296-324` (`_report_counterparty` — ветка без привязки)
  - `backend/app/quality.py:156-173` (`escalate_severe`), `backend/app/quality.py:75`
    (`REVIEW_PAUSE_DAYS = 3650`)

- **Что не так**: если в теле нет `order_id`/`booking_id`, `_report_counterparty` **берёт
  `target_user_id` из запроса как есть**, без проверки, что жалующийся вообще пересекался с
  этим человеком. Дальше `escalate_severe` для «тяжёлых» категорий немедленно вызывает
  `pause_taxi(..., hours=None)`, что означает `REVIEW_PAUSE_DAYS = 3650` — пауза на 10 лет
  «до разбора», снимаемая только вручную админом. Ни `ensure_active`, ни почасового лимита на
  этой ручке нет. Соседняя реализация того же продуктового смысла — `incidents.py` — эту дыру
  уже закрыла (`create_incident`, `incidents.py:215-221`: «Анти-харассмент: обычная жалоба
  привязывается к ОБЩЕЙ сущности… иначе можно завалить инцидентами любого, с кем не
  пересекался») плюс `incidents_last_hour`. В `safety.py` тот же барьер не поставлен.

- **Как эксплуатируется**: любой зарегистрированный пользователь, один запрос на жертву:
  ```
  POST /reports
  Authorization: Bearer <любой валидный токен>
  {"category": "safety_threat", "reason": "...", "target_user_id": <id водителя>}
  ```
  `target_user_id` не секрет — он публично отдаётся, например, в `/rides` (`driver_id` в
  карточке поездки) и в `/drivers/{id}/public`. Итог: конкурента можно выбить из такси
  подчистую, перебрав id водителей в своём городе. Дедуп (`_dedup_report`) считает
  «(автор, цель, категория)» — он не мешает пройтись по разным целям, а одной жалобы на цель
  уже достаточно. Категории с этим эффектом: `safety_threat`, `kicked_out`, `dangerous_driving`
  (`quality.py:50`).

- **Доказательство** (код):
  ```python
  # app/routers/safety.py:322 — ветка «без привязки к поездке»
      if body.target_user_id is None:
          raise HTTPException(400, "Укажи, на кого жалоба, или поездку")
      return body.target_user_id          # ← ни одной проверки отношений с целью

  # app/quality.py:156
  def escalate_severe(session, report, reporter) -> None:
      if report.category not in SEVERE_CATEGORIES:
          return
      pause_taxi(session, report.target_user_id, hours=None, reason=PAUSE_REASON_REVIEW)

  # app/quality.py:75
  REVIEW_PAUSE_DAYS = 3650
  ```

- **Доказательство** (PoC, вывод дословно; между атакующим и жертвой нет ни поездки, ни заказа):
  ```
  STATUS: 200 {"id":1,"category":"safety_threat","status":"new","created_at":"2026-08-07T09:27:47"}
  taxi_paused_until = 2036-08-04 09:27:47
  taxi_pause_reason = review
  ```

- **Как чинить**: перенести уже написанное правило из `incidents.py` в `safety.py` — то есть
  сделать его общим, а не копией:
  1. в `create_report` требовать привязку к общей сущности (`order_id`/`booking_id`/`parcel_id`)
     для всех категорий, кроме случаев, где сигнал важнее (как `SEVERE_TYPES` в incidents), —
     и **для этих случаев не давать автоматического наказания**: тяжёлая жалоба без общей
     поездки должна поднимать флаг админу (Telegram + очередь `/admin/reports`), но не
     дёргать `pause_taxi` сама;
  2. добавить почасовой потолок жалоб на автора — тот же `settings.safety_incidents_per_hour`,
     что уже используется в `incidents.py:213`;
  3. добавить `ensure_active(session, user.id)` — сейчас приостановленный аккаунт свободно
     подаёт жалобы, хотя докстринг `safety_logic.ensure_active` относит жалобы к закрытым
     действиям (в `incidents.py:269` этот вызов есть, в `safety.py` — нет).

  Отдельно стоит пересмотреть `REVIEW_PAUSE_DAYS = 3650`: «до разбора» на 10 лет означает, что
  любая ошибка модерации = пожизненный бан без срока. Разумнее конечное окно (например 72 ч)
  с авто-эскалацией админу.

---

### [P2] Гео-оракул в `POST /instant/orders/{order_id}/arrived` — точка подачи чужого заказа с точностью ~500 м

- **Файл:строка**: `backend/app/routers/instant.py:582-611`

- **Что не так**: гео-проверка «ты на месте подачи?» выполняется **раньше** проверки прав
  (права проверяет только `isv.transition` → `_guard_actor`, вызываемая последней строкой).
  Поэтому ответ на запрос постороннего зависит от того, попал ли он координатами в радиус
  500 м от точки подачи чужого заказа. Разные тексты ответа = бинарный оракул.

- **Как эксплуатируется**: любой вошедший шлёт `POST /instant/orders/{id}/arrived` с
  подобранными `lat`/`lng` и по ответу сужает поиск (grid search / бинарный поиск). Полностью
  восстанавливается точка подачи любого активного заказа с точностью до 500 м, без участия в
  заказе. Ограничение — общий IP-лимит 300 запросов/мин; на восстановление одной точки при
  разумной сетке этого хватает.

- **Доказательство** (PoC, один и тот же посторонний, один и тот же чужой заказ, меняются
  только координаты):
  ```
  FAR : 409 {"detail":{"ru":"Ты ещё не на месте подачи — ожидание начнётся, когда подъедешь", ...}}
  NEAR: 409 {"detail":"Нельзя перейти expired→arriving"}
  ```
  Код:
  ```python
  # app/routers/instant.py:592
      order = session.get(InstantOrder, order_id)
      if not order:
          raise herr(404, ...)
      ...                                   # ← прав НЕ проверяем
      if lat is not None and lng is not None and order.from_lat and order.from_lng:
          if haversine_km(lat, lng, order.from_lat, order.from_lng) > _ARRIVED_RADIUS_KM:
              raise herr(409, "Ты ещё не на месте подачи — …")     # ← утечка через разный ответ
      order = isv.transition(session, order_id, isv.Actor.driver, S.arriving, user.id)  # ← права здесь
  ```

- **Как чинить**: поднять проверку участия в начало ручки, до любых вычислений над данными
  заказа:
  ```python
  order = session.get(InstantOrder, order_id)
  if not order or order.driver_id != user.id:
      raise herr(404, "Заказ не найден", "Заказ табылманы")
  ```
  Общее правило для всего роутера: сначала «кто ты этому объекту», потом бизнес-проверки.
  Сейчас `arrived` — единственное место в `instant.py`, где порядок обратный.

- **Побочно (не безопасность, но рядом)**: фолбэк координат в этой же ручке читает
  `livepos.livepos_get("instant", order_id)` (`instant.py:601`), а пишется позиция такси как
  `livepos_set("order", order_id, ...)` (`location.py:222`). Namespace не совпадает → фолбэк
  никогда не срабатывает, и гео-проверка «Я на месте» молча отключена для клиентов, которые
  не шлют координаты в теле.

---

### [P2] Пустой `ADMIN_TELEGRAM_CHAT_ID` открывает админ-действия на `POST /telegram/webhook` вообще без аутентификации

- **Файл:строка**: `backend/app/routers/auth.py:314` (гейт), `auth.py:196-200` (проверка секрета),
  `auth.py:304-413` (`_handle_admin_callback`)

- **Что не так**: гейт админа в Telegram-callback написан как сравнение строк без проверки на
  пустоту:
  ```python
  if str(frm.get("id")) != str(settings.admin_telegram_chat_id):
      ...отказ...
  ```
  Если `admin_telegram_chat_id` не задан (дефолт `""`), а злоумышленник пришлёт `"from": {"id": ""}`,
  то `"" != ""` → False, и гейт пропускает. Проверка секрета вебхука выше по коду тоже
  условная: `if settings.telegram_webhook_secret and not hmac.compare_digest(...)` — при пустом
  секрете подпись не проверяется совсем. Соседняя функция `_maybe_promote_admin` (`auth.py:32`)
  ту же проверку делает **правильно**: `bool(settings.admin_telegram_chat_id) and ...` — то есть
  паттерн в проекте есть, здесь он просто не применён.

- **Как эксплуатируется** (при выполнении обоих условий, см. ниже): анонимный HTTP-запрос,
  без токена:
  ```
  POST /telegram/webhook
  {"callback_query": {"id":"1","from":{"id":""},"data":"drv:ok:<user_id>",
                      "message":{"message_id":1,"chat":{"id":1}}}}
  ```
  `_handle_admin_callback` умеет: `drv:ok/no` — выставить `User.verified` и `docs_status`
  (одобрить себя водителем в обход модерации), `ad:ok/no` — одобрить рекламу, `pay:ok/no` —
  вызвать `_activate_payment` (зачисление без денег для не-провайдерских платежей),
  `resp:ok/no` — принять/отклонить чужой отклик на заявку.

- **Доказательство** (PoC, вывод дословно; `telegram_webhook_secret=""`,
  `admin_telegram_chat_id=""`):
  ```
  STATUS: 200 {"ok":true}
  verified after webhook = True
  ```

- **Условия (важно, поэтому P2, а не P0)**: нужны **оба** — пустой `TELEGRAM_WEBHOOK_SECRET`
  **и** пустой `ADMIN_TELEGRAM_CHAT_ID`. На проде `validate_production` (`config.py:545`)
  требует `TELEGRAM_WEBHOOK_SECRET`, но **только если задан `TELEGRAM_BOT_TOKEN`**. Конфиг
  боевого сервера я не видел (в git его нет, и это правильно), поэтому не могу утверждать, что
  прод уязвим. Классифицирую как латентную дыру / отсутствие эшелонирования: одна строка
  конфигурации отделяет её от полного захвата админских действий.

- **Как чинить**:
  1. `auth.py:314` — не сравнивать с пустым значением:
     ```python
     admin_chat = str(settings.admin_telegram_chat_id or "")
     if not admin_chat or str(frm.get("id") or "") != admin_chat:
         ...отказ...
     ```
  2. `auth.py:196` — сделать секрет обязательным для самого эндпоинта, а не условным:
     нет `telegram_webhook_secret` → 404/403 на вебхук (бот всё равно не работает без него).
  3. в `validate_production` добавить: `ADMIN_TELEGRAM_CHAT_ID` обязателен, если вебхук
     смонтирован; и обязателен `TELEGRAM_WEBHOOK_SECRET` независимо от наличия токена бота.

---

### [P2] `GET /geocode` — платный прокси Яндекса без персонального лимита

- **Файл:строка**: `backend/app/routers/discovery.py:133-171`; список лимитов —
  `backend/app/middleware.py:33-44`

- **Что не так**: на `/geocode` действует только общий IP-бюджет (300 запросов/мин,
  `config.py:404`). Персонального бюджета нет, а `_ESTIMATE_PREFIXES` его не покрывает.
  За ручкой стоит платный Яндекс.Геокодер с бесплатной квотой ~1000/день (её называет сам
  докстринг). Кэш в Redis снимает только повторы — уникальные строки запроса его обходят.
  Ровно эту атаку проект уже осознал и закрыл для `/instant/estimate` и `/courier/estimate`
  (`instant.py:269-276`, `guard_estimate_budget`, комментарий: «IP-лимит стоит в middleware,
  но IP меняется прокси — аккаунт нет, поэтому потолок и здесь»). До `/geocode` тот же вывод
  не довели.

- **Как эксплуатируется**: один аккаунт шлёт `GET /geocode?q=<случайная строка>` — каждый
  уникальный `q` промахивается мимо кэша и уходит в платный API. Дневная квота выжигается
  примерно за 4 минуты, после чего подсказки «Откуда/Куда» ложатся у всех пользователей.
  Смена IP через прокси снимает и общий лимит.

- **Как чинить**: добавить персональный бюджет тем же механизмом, что у оценки цены:
  ```python
  from ..middleware import user_over_limit
  if user_over_limit("geocode", user.id, settings.rate_limit_geocode_per_user_per_min):
      raise herr(429, "Слишком много запросов подряд. Подожди минуту.", "…")
  ```
  и завести `/geocode` в `_ESTIMATE_PREFIXES` (или отдельный бюджет) — сейчас список
  «дорогих» путей неполон. Полезно также кэшировать отрицательный/короткий результат, чтобы
  мусорные строки не били по API дважды.

---

## ПРОВЕРЕНО И ЧИСТО

**Админ-гейты — механизм и полнота.**
Единого хелпера нет: используются локальные `_require_admin` / `_guard_admin` / прямое
`user.role != UserRole.admin`. Просканированы **все 68 маршрутов** с `/admin` в пути или
`admin` в имени функции — гейт стоит на каждом (единственное «подозрение», 4 ручки
`support.py:248/290/309/337`, оказалось ложным: они используют `_guard_admin`,
`support.py:229`). Полноту дополнительно сторожит существующий тест
`backend/tests/test_admin_gate_everywhere.py` — он сам находит все `/admin`-пути и требует
проверку роли и в рантайме, и в исходнике; список исключений в нём пуст. Отдельно проверено,
что `/docs`,`/redoc`,`/openapi.json` в проде выключены (`main.py:102`).

**Эскалация роли / массовое присвоение полей.**
`role`, `verified`, `approved`, `trust_level` нигде не принимаются из тела запроса. Все
`model_dump()`/`setattr` идут по узким Pydantic-моделям с явными полями:
`auth.py:526` (`MeUpdateIn` — только name/avatar/city/language, телефон не меняется),
`requests.py:280-310` (белый список из 6 полей), `family.py:71` (`ContactIn`),
`safety.py:611` (`BlockIn`), `ads.py:589` (список полей). `User.role` пишется ровно в двух
местах: `_maybe_promote_admin` (по конфигу, не по запросу) и `_handle_admin_callback`
(см. находку P2). `/driver/verify` авто-одобряет только при `driver_autoapprove_enabled`,
а прод-валидатор требует под него ключ Vision (`config.py:567`).

**IDOR — проверенные и защищённые ручки.**
- `bookings.py` — всё через `services.booking_and_ride_for_user` (`services.py:158-168`),
  включая `boarding-code`, `pay-agreement`, `/trips/{id}/receipt`, `details`.
- `parcels.py` (1406 строк) и `courier.py` (1156) — прогнаны целиком: у каждой ручки с `{id}`
  есть сравнение с `sender_id`/`courier_id` или `_guard_courier`. Сериализаторы разделены по
  доступу: `_parcel_available` (открытый список — координаты округлены до ~1 км, телефонов
  нет), `_parcel_for_sender`, `_parcel_for_courier` (телефоны только после accept),
  `_parcel_base` (без ПДн).
- `instant.py` — все остальные ручки: `_order_for_view` (509), `transition`→`_guard_actor`
  →`_guard_owns` (`instant_service.py:658-676`), явные сравнения в `cancel`, `wait`, `rate`,
  `receipt`, `cash-received`, `lost-item`, `scheduled/*`.
- `requests.py` — торг закрыт `_bargain_role` (`requests.py:509-515`); `withdraw_response`
  пускает только автора отклика; `accept_response` дополнительно проверяет очерёдность хода.
- `wallet.py` / `payments.py` / `debt.py` — оплата брони и заказа сверяет `passenger_id`;
  баланс и ledger всегда по своему id; `/payments/{id}/status` — только плательщик;
  выплаты хранят только last4 + токен провайдера, PAN не сохраняется и не логируется.
- `coupons.py` / `promo.py` — `_own_coupon`, `_own_partner`, `promo_stats` (владелец или
  админ); `coupon_redeem` гасит код только владелец бизнеса-партнёра.
- `places.py`, `route_watch.py`, `notifications.py`, `driver_schedule.py`, `support.py`,
  `reviews.py`, `stats.py`, `referral.py`, `trust.py`, `settlements.py`, `pickup.py`,
  `medical.py`, `seasonal.py`, `sybil.py`, `events.py` — все читают/пишут строго по своему
  `user_id`, чужое отдают как 404.
- `family.py` — «поделиться поездкой» может только пассажир и только своим `TrustedContact`;
  телефон доверенного валидируется, стоит потолок `MAX_TRUSTED_CONTACTS` (защита от
  SMS-бомбинга чужими номерами).
- `incidents.py` — `respond` только respondent, `appeal`/`withdraw` только участник,
  `create_incident` требует общую сущность + почасовой лимит.

**WebSocket — все 7 роутов чисты.**
`/ws/bookings/{id}` (chat.py:197), `/ws/instant/{id}/chat` (303), `/ws/parcel/{id}/chat` (445),
`/ws/trip/{id}/location` (location.py:65), `/ws/instant/{id}/location` (153),
`/ws/parcel/{id}/location` (239), `/ws/map` (33). Во всех: токен принимается **только первым
кадром** (не через query-string — он утекает в логи прокси); `authenticate_ws`
(`security.py:98`) проверяет существование пользователя **и** ревокацию (`tokens_valid_from`),
в отличие от голого `verify_token`; после этого проверяется участие в конкретном объекте
(анти-IDOR) и активность поездки; координаты не пишутся в БД, а ретранслируются направленными
ключами (без self-эхо). **Перепроверка на долгом соединении есть**: каждые 15 кадров заново
валидируется токен, а в гео-каналах ещё и статус объекта — отозванный logout'ом токен рвёт
уже открытый сокет, завершённая поездка перестаёт течь координатами.

**Приватные файлы.**
`GET /secure/docs/{name}` (drivers.py:113) — админ или владелец документа, `os.path.basename`
против path traversal. `GET /secure/evidence/{name}` (incidents.py:404) — админ или сторона
спора, у которой этот файл в CSV доказательств. В S3-режиме редирект на подписанный URL
выдаётся **после** проверки доступа.

**Публичные ручки без auth — проверено, что ПДн не текут.**
`/rides`, `/rides/{id}`, `/rides/near` — через `public_ride_payload`; `/rides/{id}` вдобавок
прогоняется через `_hide_blocked` + `_hide_trusted_only`, поэтому прямой id не обходит
«только для своих» и блокировки. `/drivers/{id}/public` — без телефона и координат.
`/r/{ride_id}` и `/r/{ride_id}/preview` (share.py) — только активные и не-`only_trusted`
поездки (`_shareable`), иначе перебор id давал бы анонимный скрейпинг графа поездок.
`/t/{token}` (live-ссылка близкому) — capability-URL: токен `secrets.token_urlsafe(16)`,
короче 16 символов даже не ищется в БД, есть TTL, токен маскируется в access-логах и в
алертах админу (`middleware.py:216, 236`). `/medical-partners/{id}/rides` осознанно закрыт
авторизацией (факт «кто едет в клинику» — вывод о здоровье).

**Rate-limit — что есть и работает.**
`/auth/*` и `/sos` — строгий бюджет 20/мин на IP (`middleware.py:33`), плюс на уровне логики:
≤3 SMS-кода в минуту на номер, ≤5 попыток ввода OTP, constant-time сравнение кода,
одноразовое погашение кода. `/waitlist`, `/callback`, `/donate`, `/boost/create` — тоже в
строгом бюджете. Отправка сообщений — `_chat_burst_reached` (chat.py:62) по отправителю и
конкретной переписке, одинаково для REST и WS (сокет не обходит REST-потолок).
`/instant/estimate` и `/courier/estimate` — двойной бюджет: на IP и **на аккаунт**
(`guard_estimate_budget`). SOS-SMS админу капятся по часу. `X-Real-IP` берётся как
доверенный (его перезаписывает nginx), `X-Forwarded-For` — только фолбэк для dev, что верно.

---

## НЕ ДОКАЗАНО

1. **`POST /ads/{ad_id}/event` (`ads.py:102`) — накрутка статистики рекламы.** Ручка требует
   входа, но не проверяет ничего больше: любой пользователь может слать `impression`/`click`
   по любому `ad_id` в цикле. Как утечку данных не квалифицирую (ответ `{"ok": true}`).
   Станет реальной проблемой, только если по этим счётчикам будут считаться деньги партнёра —
   сейчас тарификация пакетная (`AD_PACKAGES`), не за показ, поэтому оставляю как замечание,
   а не как дыру. Разумная мера на будущее: дедуп по (ad_id, user_id, окно времени).

2. **Самодекларация пола водителя (`POST /driver/gender`, `drivers.py:85`) и режим «только
   женщина за рулём».** Поле `gender` водитель ставит себе сам, без верификации, и оно влияет
   на матчинг `women_only` заказов. Это не IDOR и, судя по комментариям, осознанный
   opt-in-компромисс, но с точки зрения безопасности пассажирки гарантия «за рулём женщина»
   ничем не подкреплена. Проверить, есть ли компенсирующая мера (например ручная модерация при
   одобрении таксиста), в рамках моего среза не смог — это стык с модерацией водителей.

3. **`_EXEMPT_PREFIXES` содержит `/auth/telegram/webhook`, а реальный путь — `/telegram/webhook`**
   (`middleware.py:47` против `auth.py:196`). То есть запись мёртвая, и вебхук на самом деле
   живёт под общим лимитом 300/мин. Как дыру не квалифицирую (исключение не срабатывает —
   поведение строже, а не слабее). Но это несоответствие конфигурации коду: если Telegram
   когда-нибудь даст всплеск апдейтов, их порежет 429, и понять почему будет тяжело.

4. **Одновременность и гонки.** Смотрел выборочно (accept такси, активация купона,
   `/auth/refresh` с `with_for_update`, идемпотентность выплат) — везде есть row-lock или
   условный UPDATE. Полноценный аудит гонок не проводил, это отдельный срез.

5. **Конфигурация прода (`.env` на `yulbash.ru`).** Не проверялась и не могла: по правилам
   секреты вне git, доступа к серверу нет. Поэтому находка P2 про Telegram-вебхук
   остаётся условной — подтвердить или снять её можно только сверкой боевого `.env`
   (нужны непустые `TELEGRAM_WEBHOOK_SECRET` и `ADMIN_TELEGRAM_CHAT_ID`).

6. **Секреты в репозитории.** Прямой поиск в рамках этого среза не проводился (это чужая
   зона), но по ходу чтения ни ключей, ни `.jks`, ни `google-services.json`, ни строк
   `.env` в `backend/app/**` не встретилось. Ключ Яндекса читается из настроек
   (`settings.yandex_geocoder_key`), а не из кода.
