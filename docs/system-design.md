# 🏗️ Юлдаш: Production System Design (архитектура стартапа на миллионы)

> **Цель:** описать ПОЛНУЮ систему Юлдаша как она сейчас стоит и КАК ОНА МАСШТАБИРУЕТСЯ до миллионов пользователей, попуток в день и инвестиций. Это блюпринт инженера, готовый к инвесторам и новым разработчикам.

> **Статус:** ✅ Ядро working (вход через **Telegram**, поездки, заявки, бронь, чат+WebSocket, карта, проверка водителя). Приложение живёт на `yulbash.ru` (FastAPI + PostgreSQL). Android подключён.

> ⚠️ **ВАЖНО (честно, обновлено 2026-06-28):** ниже описана **ЦЕЛЕВАЯ** архитектура (как масштабировать). **Сделан большой шаг к ней — бэкенд разрезан (2026-06-28, Opus):** монолит `app/main.py` (~1400 строк) → тонкая фабрика `create_app()` + домены в `app/routers/*` (health, auth, rides, requests, bookings, drivers, chat, discovery, family, safety) + общий `app/services.py` + схемы `app/schemas.py`. **Появилось:** версионирование API — каждый роут доступен и на корне (живой клиент), и под `/api/v1` (алиас); `Dockerfile` + `docker-compose.yml` (опц., прод пока systemd); pytest (`tests/test_api.py`, 11 тестов) + Alembic (`alembic/versions/`). **Поведение 1:1** (pytest 11/11, smoke OK, security OK, паритет роутов root↔/api/v1 проверен). **Ещё целевое, НЕ сделано:** PostGIS, 20+ нормализованных таблиц, middleware-слой (rate-limit), Redis-кеш, реальная контейнеризация прода. Вход — Telegram (не SMS/VK/WhatsApp). Читать структуру `api/services/...` ниже как ориентир — фактические имена: `routers/`, `services.py`, `schemas.py`.

---

## I. Архитектура системы (макро-уровень)

```
┌─────────────────────────────────────────────────────────┐
│                    ЮЛДАШ (Plarform)                      │
├─────────────────┬──────────────────┬────────────────────┤
│   📱 MOBILE      │  🌐 BACKEND      │  🔧 OPS / INFRA    │
│  (Kotlin/Compose) │ (FastAPI/Postgres) │ (VPS/Postgres)   │
│                  │                    │                    │
│ • Screen-layer  │ • REST API        │ • 2х сервер VPS   │
│ • Location Svcs │ • WebSocket (chat)│ • PostgreSQL + wal │
│ • Yandex MapKit │ • Auth (Telegram) │ • Backup (S3-like) │
│ • Telegram/VK   │ • Matching engine │ • Monitoring       │
│ • Voice         │ • Ratings DB      │ • Certificate mgmt │
│ • Boost (платно)│ • Moderation queue│ • Firewall/DDoS    │
└─────────────────┴──────────────────┴────────────────────┘
```

### A. 3-слойная архитектура мобильного приложения

```kotlin
// PRESENTATION LAYER (экраны)
├─ MainActivity.kt (состояние + навигация)
├─ screens/ 
│  ├─ HomeScreen.kt (5 вкладок)
│  ├─ MapScreen.kt (маршруты + ближайшие)
│  ├─ RidesScreen.kt
│  ├─ RequestScreen.kt (заявка)
│  ├─ ChatScreen.kt
│  ├─ ProfileScreen.kt
│  ├─ ActiveTripScreen.kt (live + чат)
│  └─ ... 15+ вторичных

// DATA LAYER (сеть + локальное состояние)
├─ data/
│  ├─ ApiClient.kt (REST + WebSocket)
│  ├─ DTO.kt (модели с сервера)
│  ├─ SharedPrefs.kt (JWT токены, юзер)
│  └─ LocationManager.kt (GPS + сеть)

// SERVICE LAYER (бизнес-логика)
├─ services/
│  ├─ MatchingService (локальное ранжирование)
│  ├─ LocationService (отправка гео в фон)
│  ├─ PushNotificationService (сценарии)
│  └─ VoiceRecognitionService (локально + облако)

// DESIGN SYSTEM
└─ ui/theme/
   ├─ Colors.kt (Canon* палитра)
   ├─ Typography.kt
   └─ Shapes.kt
```

### B. Backend: 3-слойная архитектура (FastAPI)

```python
# backend/
├─ main.py (app, CORS, middleware, error-handler)
├─ config.py (env, DB URL, Telegram/VK API ключи)
│
├─ api/
│  ├─ auth.py (SMS → JWT)
│  ├─ rides.py (CRUD поездок + листинг)
│  ├─ requests.py (заявки пассажиров)
│  ├─ bookings.py (брони + матчинг)
│  ├─ messages.py (чат + WebSocket)
│  ├─ driver.py (кабинет водителя)
│  ├─ user.py (профиль, оценки)
│  ├─ safety.py (SOS, жалобы, блокировки)
│  ├─ admin.py (модерация)
│  ├─ payments.py (платежи)
│  ├─ feed.py (статистика, популярные маршруты)
│  └─ uploads.py (фото прав, авто)
│
├─ models/
│  ├─ __init__.py (все DTO + таблицы SQLModel)
│  ├─ user.py
│  ├─ ride.py
│  ├─ booking.py
│  ├─ message.py
│  └─ ... (14+ моделей)
│
├─ db/
│  ├─ connection.py (SessionLocal, engine)
│  ├─ migrations/ (Alembic)
│  │  ├─ versions/
│  │  │  ├─ 001_initial.py
│  │  │  ├─ 002_premium_rides.py
│  │  │  └─ ...
│  │  └─ env.py
│  └─ seeding.py (demo-данные)
│
├─ services/ (бизнес-логика)
│  ├─ auth_service.py (JWT + OTP)
│  ├─ matching_service.py (матчинг request ↔ ride)
│  ├─ rating_service.py
│  ├─ sms_service.py (провайдер)
│  ├─ payment_service.py
│  ├─ moderation_service.py (рецензия документов)
│  └─ notification_service.py (push)
│
├─ middleware/
│  ├─ auth_middleware.py (JWT verify)
│  ├─ rate_limit.py (DDoS-защита)
│  └─ error_handler.py (логирование)
│
├─ tests/
│  ├─ conftest.py
│  ├─ test_auth.py
│  ├─ test_rides.py
│  ├─ test_matching.py
│  └─ ... (15+ модулей)
│
├─ docker/
│  ├─ Dockerfile
│  └─ docker-compose.yml
│
├─ requirements.txt (FastAPI, SQLModel, Pydantic, asyncio, etc.)
├─ .env.example (конфиг-шаблон)
├─ README.md (инструкция запуска)
└─ smoke.py (интеграционный тест)
```

---

## II. Схема базы данных (PostgreSQL)

### Core таблицы (нормализованные, индексированы)

```sql
-- USERS & AUTH
users (
  id: UUID PRIMARY KEY
  phone: CHAR(11) UNIQUE NOT NULL -- +7XXXXXXXXXX (индекс)
  phone_verified_at: TIMESTAMP
  name: TEXT
  avatar_url: TEXT
  role: ENUM(passenger, driver, admin) DEFAULT passenger
  language: ENUM(ru, ba) DEFAULT ru
  device_token: TEXT (для push-уведомлений)
  last_activity_at: TIMESTAMP (для онлайн-статуса)
  created_at, updated_at: TIMESTAMP
  deleted_at: TIMESTAMP NULL (мягкое удаление)
  consent_version: INT (согласие на ПД)
);
CREATE INDEX idx_users_phone ON users(phone);
CREATE INDEX idx_users_role ON users(role) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_last_activity ON users(last_activity_at DESC);

-- JWT токены (для выхода + проверки)
tokens (
  id: UUID PRIMARY KEY
  user_id: FK users
  token_hash: CHAR(64) (sha256, НЕ самим токен!)
  token_type: ENUM(access, refresh)
  issued_at, expires_at: TIMESTAMP
  revoked_at: TIMESTAMP NULL (выход = revoke)
);
CREATE INDEX idx_tokens_user_id_active ON tokens(user_id, expires_at DESC);

-- ВОДИТЕЛИ
driver_profiles (
  id: UUID PRIMARY KEY
  user_id: FK users UNIQUE NOT NULL
  status: ENUM(offline, online, on_trip) DEFAULT offline
  is_verified: BOOL DEFAULT FALSE (прошёл проверку документов)
  rating: NUMERIC(3,2) DEFAULT 5.0
  trips_count: INT DEFAULT 0
  about: TEXT (описание)
  bank_details: JSONB (реквизиты для выплат, зашифровано)
  created_at, updated_at: TIMESTAMP
);
CREATE INDEX idx_driver_profiles_user_id ON driver_profiles(user_id);
CREATE INDEX idx_driver_profiles_status ON driver_profiles(status);
CREATE INDEX idx_driver_profiles_rating ON driver_profiles(rating DESC);

-- ДОКУМЕНТЫ ВОДИТЕЛЕЙ
driver_documents (
  id: UUID PRIMARY KEY
  driver_id: FK driver_profiles
  document_type: ENUM(license, vehicle_registration, photo)
  file_url: TEXT (ссылка на S3)
  file_hash: CHAR(64) (для дедупликации + антифрода)
  status: ENUM(pending, approved, rejected) DEFAULT pending
  reviewed_by: FK users NULL (админ)
  reviewed_at: TIMESTAMP NULL
  rejection_reason: TEXT NULL
  uploaded_at: TIMESTAMP
);
CREATE INDEX idx_documents_driver_id_status ON driver_documents(driver_id, status);

-- АВТОМОБИЛИ
vehicles (
  id: UUID PRIMARY KEY
  driver_id: FK driver_profiles
  make: TEXT (марка)
  model: TEXT
  year: INT
  color: TEXT
  license_plate: CHAR(8) UNIQUE (индекс для ГИБДД)
  vehicle_registration_number: TEXT UNIQUE
  photo_url: TEXT
  seats: INT DEFAULT 4
  is_active: BOOL DEFAULT TRUE
  created_at, updated_at: TIMESTAMP
);
CREATE INDEX idx_vehicles_driver_id ON vehicles(driver_id);
CREATE INDEX idx_vehicles_license_plate ON vehicles(license_plate);

-- ПОЕЗДКИ (предложение водителя, ОСНОВНАЯ ТАБЛИЦА)
rides (
  id: UUID PRIMARY KEY
  driver_id: FK driver_profiles NOT NULL
  vehicle_id: FK vehicles NULL
  
  -- маршрут (геоданные)
  from_point: POINT (индекс GiST для радиус-поиска)
  from_address: TEXT
  to_point: POINT
  to_address: TEXT
  waypoints: JSONB (массив промежуточных точек)
  
  -- время
  depart_at: TIMESTAMP NOT NULL (индекс DESC — ближайшие)
  estimated_duration_minutes: INT
  
  -- места и цена
  seats_total: INT DEFAULT 4
  seats_available: INT NOT NULL
  price: INT NOT NULL (копейки, 100 = 1₽)
  currency: ENUM(rub, eur, usd) DEFAULT rub
  
  -- предпочтения пассажира (NEW 2026-06-24)
  pets_allowed: BOOL DEFAULT FALSE
  child_seat_available: BOOL DEFAULT FALSE
  women_only: BOOL DEFAULT FALSE
  smoking_allowed: BOOL DEFAULT FALSE
  baggage_allowed: BOOL DEFAULT FALSE
  air_conditioner: BOOL DEFAULT TRUE
  
  -- статус
  status: ENUM(active, completed, cancelled) DEFAULT active
  notes: TEXT
  is_boosted: BOOL DEFAULT FALSE
  boosted_until: TIMESTAMP NULL
  
  created_at, updated_at, completed_at: TIMESTAMP
);
CREATE INDEX idx_rides_driver_id ON rides(driver_id);
CREATE INDEX idx_rides_status_depart ON rides(status, depart_at DESC);
CREATE INDEX idx_rides_from_point ON rides USING GIST(from_point);
CREATE INDEX idx_rides_to_point ON rides USING GIST(to_point);
CREATE INDEX idx_rides_depart_at_active ON rides(depart_at DESC) WHERE status='active';

-- ЗАЯВКИ (нужда пассажира)
requests (
  id: UUID PRIMARY KEY
  passenger_id: FK users NOT NULL
  
  from_point: POINT
  from_address: TEXT
  to_point: POINT
  to_address: TEXT
  
  desired_depart_at: TIMESTAMP NOT NULL
  seats_needed: INT DEFAULT 1
  max_price: INT DEFAULT NULL (если есть бюджет)
  
  -- предпочтения
  pets: BOOL DEFAULT FALSE
  child_seat: BOOL DEFAULT FALSE
  only_women_drivers: BOOL DEFAULT FALSE
  no_smoking: BOOL DEFAULT FALSE
  baggage: BOOL DEFAULT FALSE
  
  -- контекст
  for_relative_id: FK users NULL (если за близкого)
  notes: TEXT
  
  status: ENUM(pending, matched, booked, completed, cancelled) DEFAULT pending
  created_at, updated_at: TIMESTAMP
);
CREATE INDEX idx_requests_passenger_id_status ON requests(passenger_id, status);
CREATE INDEX idx_requests_status_desired_at ON requests(status, desired_depart_at DESC);
CREATE INDEX idx_requests_from_point ON requests USING GIST(from_point);

-- БРОНИ (матчинг request ↔ ride)
bookings (
  id: UUID PRIMARY KEY
  ride_id: FK rides NOT NULL
  passenger_id: FK users NOT NULL (пассажир ИЛИ by_admin_for)
  request_id: FK requests NULL (если создана по заявке)
  driver_id: FK driver_profiles NOT NULL (денормализация для быстроты)
  
  seats_booked: INT NOT NULL
  price_total: INT NOT NULL (копейки, могут отличаться от ride.price)
  
  status: ENUM(
    pending,        -- пассажир забронировал, ждёт подтверждения водителя
    confirmed,      -- водитель подтвердил
    on_trip,        -- водитель/пассажир отметил «в дороге»
    completed,      -- доехал
    cancelled,      -- отмена
    no_show         -- не явился
  ) DEFAULT pending
  
  boarding_code: CHAR(4) (код посадки, 4 цифры)
  boarding_verified_at: TIMESTAMP NULL
  
  confirmed_at: TIMESTAMP NULL
  started_at: TIMESTAMP NULL
  completed_at: TIMESTAMP NULL
  cancelled_at: TIMESTAMP NULL
  cancelled_by: ENUM(driver, passenger, admin) NULL
  cancellation_reason: TEXT NULL
  
  created_at, updated_at: TIMESTAMP
);
CREATE INDEX idx_bookings_ride_id ON bookings(ride_id);
CREATE INDEX idx_bookings_passenger_id_status ON bookings(passenger_id, status);
CREATE INDEX idx_bookings_driver_id_status ON bookings(driver_id, status);
CREATE INDEX idx_bookings_created_at ON bookings(created_at DESC);

-- СООБЩЕНИЯ (чат в рамках брони / заявки)
messages (
  id: UUID PRIMARY KEY
  booking_id: FK bookings NOT NULL
  sender_id: FK users NOT NULL
  
  -- текст И/ИЛИ голос
  text_content: TEXT NULL
  voice_url: TEXT NULL (ссылка на S3)
  voice_duration_seconds: INT NULL
  voice_transcript: TEXT NULL (результат речевого движка)
  
  -- статусы
  sent_at: TIMESTAMP NOT NULL
  read_at: TIMESTAMP NULL
  edited_at: TIMESTAMP NULL
  deleted_at: TIMESTAMP NULL (мягкое удаление)
  
  -- для фильтра
  is_automated: BOOL DEFAULT FALSE (сообщение от системы)
);
CREATE INDEX idx_messages_booking_id_sent_at ON messages(booking_id, sent_at DESC);
CREATE INDEX idx_messages_sender_id ON messages(sender_id);

-- ОЦЕНКИ И ОТЗЫВЫ
ratings (
  id: UUID PRIMARY KEY
  booking_id: FK bookings NOT NULL
  from_user_id: FK users NOT NULL
  to_user_id: FK users NOT NULL
  
  rating_value: INT NOT NULL (1-5 звёзд)
  comment: TEXT NULL
  
  -- детальные метрики
  professionalism: INT NULL (1-5)
  cleanliness: INT NULL (1-5)
  communication: INT NULL (1-5)
  safety: INT NULL (1-5)
  
  created_at: TIMESTAMP
);
CREATE INDEX idx_ratings_to_user_id ON ratings(to_user_id);
CREATE INDEX idx_ratings_booking_id ON ratings(booking_id);

-- ДОВЕРЕННЫЕ КОНТАКТЫ (семейный контроль)
trusted_contacts (
  id: UUID PRIMARY KEY
  user_id: FK users NOT NULL
  contact_name: TEXT NOT NULL
  contact_phone: CHAR(11)
  relation: ENUM(spouse, parent, child, friend, other)
  notify_by_default: BOOL DEFAULT TRUE
  created_at: TIMESTAMP
);
CREATE INDEX idx_trusted_contacts_user_id ON trusted_contacts(user_id);

-- ШАРИНГ ПОЕЗДКИ (отправляет статус близким)
trip_shares (
  id: UUID PRIMARY KEY
  booking_id: FK bookings NOT NULL
  trusted_contact_id: FK trusted_contacts NOT NULL
  
  shared_at: TIMESTAMP
  shared_until: TIMESTAMP NULL (по умолчанию после завершения)
  
  -- события
  passenger_boarded_notified: BOOL DEFAULT FALSE
  passenger_arrived_notified: BOOL DEFAULT FALSE
  
  created_at: TIMESTAMP
);
CREATE INDEX idx_trip_shares_booking_id ON trip_shares(booking_id);

-- SOS СОБЫТИЯ (экстренная безопасность)
sos_events (
  id: UUID PRIMARY KEY
  user_id: FK users NOT NULL
  booking_id: FK bookings NULL (если в поездке)
  
  triggered_at: TIMESTAMP NOT NULL
  location_point: POINT (где произошло)
  
  status: ENUM(reported, acknowledged, resolved, false_alarm)
  description: TEXT
  
  -- ответ диспетчера
  responded_by: FK users NULL (диспетчер)
  response_at: TIMESTAMP NULL
  
  created_at: TIMESTAMP
);
CREATE INDEX idx_sos_events_user_id_triggered_at ON sos_events(user_id, triggered_at DESC);

-- ЖАЛОБЫ И БЛОКИРОВКИ
reports (
  id: UUID PRIMARY KEY
  reporter_id: FK users NOT NULL
  reported_user_id: FK users NOT NULL
  booking_id: FK bookings NULL
  
  reason: ENUM(
    rude, unsafe, fraud, unwanted_contact, other
  )
  description: TEXT
  
  status: ENUM(pending, investigating, resolved, dismissed) DEFAULT pending
  
  reviewed_by: FK users NULL (админ)
  reviewed_at: TIMESTAMP NULL
  action_taken: ENUM(none, warning, suspension, permanent_ban) NULL
  
  created_at: TIMESTAMP
);
CREATE INDEX idx_reports_reported_user_id_status ON reports(reported_user_id, status);

-- ЧЁРНЫЙ СПИСОК
blocks (
  id: UUID PRIMARY KEY
  user_id: FK users NOT NULL
  blocked_user_id: FK users NOT NULL
  reason: TEXT NULL
  created_at: TIMESTAMP
  UNIQUE(user_id, blocked_user_id)
);
CREATE INDEX idx_blocks_user_id ON blocks(user_id);

-- ПЛАТЕЖИ (ТОЛЬКО донаты пассажира + boost)
-- Сама поездка платится наличными/переводом между юзерами, без приложения
payments (
  id: UUID PRIMARY KEY
  from_user_id: FK users NOT NULL (плательщик: пассажир)
  
  payment_type: ENUM(donate, boost_ride) DEFAULT donate
  
  -- для donate: кому идут деньги
  to_user_id: FK users NULL (водитель, если благодарность)
  
  -- для boost: какую поездку поднимаем
  ride_id: FK rides NULL (если тип = boost_ride)
  
  amount: INT NOT NULL (копейки)
  currency: ENUM(rub, eur, usd) DEFAULT rub
  
  payment_method: ENUM(yandex_kassa, yoomoney, paypal) DEFAULT yandex_kassa
  
  -- для интеграции с платёжной системой
  external_payment_id: TEXT NULL (id от Яндекс.Касса или другого)
  external_status: TEXT NULL (pending, succeeded, failed)
  
  status: ENUM(pending, completed, failed, refunded) DEFAULT pending
  
  created_at, completed_at: TIMESTAMP
);
CREATE INDEX idx_payments_from_user_id ON payments(from_user_id);
CREATE INDEX idx_payments_ride_id ON payments(ride_id) WHERE payment_type='boost_ride';

-- СТАТИСТИКА & МЕТРИКИ (денормализованно для быстроты)
user_stats (
  user_id: FK users PRIMARY KEY
  total_trips: INT DEFAULT 0
  total_distance_km: INT DEFAULT 0
  average_rating: NUMERIC(3,2) DEFAULT 5.0
  response_rate: NUMERIC(3,2) DEFAULT 100.0
  last_trip_at: TIMESTAMP NULL
  updated_at: TIMESTAMP
);

-- РЕКЛАМА (партнёры)
partner_ads (
  id: UUID PRIMARY KEY
  advertiser_id: FK users (обычно admin/модератор, создаёт от бренда)
  
  title: TEXT
  description: TEXT
  image_url: TEXT
  cta_text: TEXT
  cta_url: TEXT
  
  erid: TEXT (Яндекс erid для отчётности)
  
  -- географический & маршрутный тиргет
  city: TEXT (город или NULL для всех)
  route_from: POINT NULL
  route_to: POINT NULL
  
  placement: ENUM(
    map_feed,
    rides_list,
    booking_screen,
    profile_block,
    help_section
  )
  
  -- бюджет и статистика
  budget_kopeks: INT (общий бюджет)
  spent_kopeks: INT DEFAULT 0
  
  impressions: INT DEFAULT 0 (показы)
  clicks: INT DEFAULT 0 (клики)
  ctr: NUMERIC(4,2) DEFAULT 0 (вычисляется)
  
  start_date, end_date: DATE
  status: ENUM(draft, pending, approved, active, paused, completed) DEFAULT draft
  
  created_at, updated_at: TIMESTAMP
);
CREATE INDEX idx_partner_ads_advertiser_id ON partner_ads(advertiser_id);
CREATE INDEX idx_partner_ads_status_placement ON partner_ads(status, placement);

-- АУДИТ (для отслеживания действий)
audit_log (
  id: BIGSERIAL PRIMARY KEY
  user_id: FK users NOT NULL
  action: TEXT (создал заявку, отменил бронь)
  entity_type: TEXT (request, booking, driver_profile)
  entity_id: UUID
  changes: JSONB (что именно изменилось)
  created_at: TIMESTAMP
);
CREATE INDEX idx_audit_log_user_id_created_at ON audit_log(user_id, created_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);
```

### Индексы для масштабирования

```sql
-- Гео-поиск (PostGIS)
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE INDEX idx_rides_from_location_gist ON rides USING GIST(ST_GeographyFromText(ST_AsText(from_point)));

-- Полнотекстовый поиск (отзывы, адреса)
CREATE INDEX idx_rides_address_fts ON rides USING GIN(to_tsvector('russian', from_address));
CREATE INDEX idx_requests_address_fts ON requests USING GIN(to_tsvector('russian', from_address));

-- Партиционирование по времени (для больших таблиц)
-- Поездки по месяцам
CREATE TABLE rides_2026_06 PARTITION OF rides
  FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
-- (аналогично для messages, bookings, audit_log)
```

---

## III. REST API (полный спецификация)

### Authentication Endpoints (Telegram / VK / WhatsApp)

> SMS заменена на социальные сети (физ. лицо не может получить договор для SMS). Юзер выбирает: Telegram, VK или WhatsApp.

```http
GET /api/v1/auth/telegram-login-url
Response 200:
{
  "login_url": "https://t.me/yuldash_bot?start=...",
  "request_id": "uuid-xxxx"
}

---

POST /api/v1/auth/telegram-callback
Content-Type: application/json
{
  "request_id": "uuid-xxxx",
  "telegram_user_id": "123456789",
  "telegram_username": "vasya_123"
}

Response 200:
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "user": {
    "id": "uuid",
    "telegram_id": "123456789",
    "name": "Василий",
    "role": "passenger"
  }
}

---

POST /api/v1/auth/vk-login
Content-Type: application/json
{
  "vk_access_token": "vk_token_xxx",
  "vk_user_id": "12345678"
}

Response 200:
{
  "access_token": "eyJ...",
  "refresh_token": "eyJ...",
  "user": {
    "id": "uuid",
    "vk_id": "12345678",
    "name": "Иван Петров",
    "role": "passenger"
  }
}

---

POST /api/v1/auth/whatsapp-login
Content-Type: application/json
{
  "phone": "+79991234567",
  "whatsapp_verified": true
}

Response 200:
{
  "access_token": "eyJ...",
  "user": {...}
}

---

POST /api/v1/auth/refresh
Authorization: Bearer <refresh_token>

Response 200:
{
  "access_token": "eyJ..."
}

---

POST /api/v1/auth/logout
Authorization: Bearer <access_token>

Response 204: No Content
```

### Rides Endpoints

```http
GET /api/v1/rides
Authorization: Bearer <token>
Query: from_lat=54.73&from_lon=55.95&radius_km=30&limit=50&offset=0
       &date_from=2026-06-27T08:00Z&date_to=2026-06-27T23:00Z
       &pets_allowed=true&women_only=false

Response 200:
{
  "data": [
    {
      "id": "uuid",
      "driver": {
        "id": "uuid",
        "name": "Рустам",
        "rating": 4.8,
        "trips_count": 342,
        "is_verified": true
      },
      "vehicle": {
        "make": "Lada",
        "model": "Vesta",
        "color": "белая",
        "license_plate": "РТ123АБ"
      },
      "from": {
        "address": "ул. Ленина, Баймаҡ",
        "lat": 54.73,
        "lon": 55.95
      },
      "to": {
        "address": "ул. Комсомольная, Сибай",
        "lat": 52.88,
        "lon": 58.5
      },
      "depart_at": "2026-06-27T10:30:00Z",
      "estimated_duration_minutes": 45,
      "seats_available": 2,
      "price": 500,
      "currency": "rub",
      "pets_allowed": true,
      "women_only": false,
      "child_seat_available": true,
      "is_boosted": false,
      "created_at": "2026-06-27T07:15:00Z"
    }
  ],
  "pagination": {
    "total": 142,
    "limit": 50,
    "offset": 0
  }
}

---

POST /api/v1/rides
Authorization: Bearer <token>
Content-Type: application/json
{
  "from": {
    "address": "ул. Пушкина, Баймаҡ",
    "lat": 54.73,
    "lon": 55.95
  },
  "to": {
    "address": "аэропорт Уфа",
    "lat": 54.27,
    "lon": 55.72
  },
  "depart_at": "2026-06-27T14:00:00Z",
  "seats_total": 4,
  "price": 800,
  "pets_allowed": false,
  "women_only": true,
  "child_seat_available": true,
  "smoking_allowed": false,
  "notes": "Спешу, прямой маршрут"
}

Response 201:
{
  "id": "ride-uuid",
  "driver_id": "user-uuid",
  "status": "active",
  "created_at": "2026-06-27T12:35:00Z"
}

---

GET /api/v1/rides/{ride_id}
Authorization: Bearer <token>

Response 200:
{
  "id": "ride-uuid",
  "driver": {...},
  "vehicle": {...},
  ... (полные данные поездки)
}

---

PATCH /api/v1/rides/{ride_id}
Authorization: Bearer <token>
Content-Type: application/json
{
  "status": "cancelled",
  "seats_available": 3
}

Response 200:
{
  "id": "ride-uuid",
  "status": "cancelled",
  "updated_at": "2026-06-27T12:40:00Z"
}
```

### Requests (заявки пассажира)

```http
POST /api/v1/requests
Authorization: Bearer <token>
Content-Type: application/json
{
  "from": {
    "address": "Сибай, Мира 10",
    "lat": 52.88,
    "lon": 58.5
  },
  "to": {
    "address": "Уфа, ТЦ Караван",
    "lat": 54.77,
    "lon": 56.08
  },
  "desired_depart_at": "2026-06-27T15:00:00Z",
  "seats_needed": 1,
  "max_price": 600,
  "pets": true,
  "no_smoking": true,
  "notes": ""
}

Response 201:
{
  "id": "request-uuid",
  "status": "pending",
  "created_at": "2026-06-27T14:50:00Z"
}

---

GET /api/v1/requests/my
Authorization: Bearer <token>
Query: status=pending&limit=20

Response 200:
{
  "data": [
    {
      "id": "request-uuid",
      "from": {...},
      "to": {...},
      "status": "pending",
      "matching_rides": [
        { "id": "ride-uuid", "price": 520, "depart_at": "...", ... }
      ]
    }
  ]
}

---

GET /api/v1/requests/{request_id}/matches
Authorization: Bearer <token>

Response 200:
{
  "data": [
    { ride matching the request }
  ]
}
```

### Bookings (бронирование)

```http
POST /api/v1/bookings
Authorization: Bearer <token>
Content-Type: application/json
{
  "ride_id": "ride-uuid",
  "seats": 1,
  "trusted_contact_id": "contact-uuid" (optional, для семьи)
}

Response 201:
{
  "id": "booking-uuid",
  "status": "pending",
  "price_total": 500,
  "boarding_code": "1234",
  "created_at": "2026-06-27T14:55:00Z"
}

---

POST /api/v1/bookings/{booking_id}/confirm
Authorization: Bearer <token>

Response 200:
{
  "id": "booking-uuid",
  "status": "confirmed",
  "confirmed_at": "2026-06-27T15:00:00Z"
}

---

POST /api/v1/bookings/{booking_id}/status
Authorization: Bearer <token>
Content-Type: application/json
{
  "new_status": "on_trip"
}

Response 200:
{
  "status": "on_trip",
  "started_at": "2026-06-27T15:15:00Z"
}

---

POST /api/v1/bookings/{booking_id}/cancel
Authorization: Bearer <token>
Content-Type: application/json
{
  "reason": "я передумал"
}

Response 200:
{
  "status": "cancelled",
  "cancelled_at": "2026-06-27T15:05:00Z"
}
```

### Messages (чат внутри поездки)

```http
GET /api/v1/bookings/{booking_id}/messages
Authorization: Bearer <token>
Query: limit=50&offset=0

Response 200:
{
  "data": [
    {
      "id": "msg-uuid",
      "sender": { "id": "user-uuid", "name": "Иван" },
      "text": "Я готов",
      "sent_at": "2026-06-27T15:10:00Z"
    }
  ]
}

---

POST /api/v1/bookings/{booking_id}/messages
Authorization: Bearer <token>
Content-Type: application/json
{
  "text": "Жду на углу Ленина и Комсомольной"
}

Response 201:
{
  "id": "msg-uuid",
  "text": "Жду на углу Ленина и Комсомольной",
  "sent_at": "2026-06-27T15:10:30Z"
}

---

POST /api/v1/bookings/{booking_id}/messages/voice
Authorization: Bearer <token>
Content-Type: multipart/form-data
{
  "voice_file": <audio.m4a>,
  "duration_seconds": 5
}

Response 201:
{
  "id": "msg-uuid",
  "voice_url": "https://s3.yandex.cloud/yuldash/voice/msg-uuid.m4a",
  "voice_duration_seconds": 5,
  "voice_transcript": "я еду уже"
}
```

### Ratings & Safety

```http
POST /api/v1/bookings/{booking_id}/rate
Authorization: Bearer <token>
Content-Type: application/json
{
  "to_user_id": "driver-user-uuid",
  "rating_value": 5,
  "comment": "Вежлив, аккуратно водит",
  "professionalism": 5,
  "cleanliness": 4,
  "communication": 5
}

Response 201:
{
  "id": "rating-uuid",
  "rating_value": 5
}

---

POST /api/v1/sos
Authorization: Bearer <token>
Content-Type: application/json
{
  "booking_id": "booking-uuid",
  "location": { "lat": 54.73, "lon": 55.95 },
  "description": "водитель ведёт опасно"
}

Response 201:
{
  "id": "sos-uuid",
  "status": "reported",
  "created_at": "2026-06-27T15:20:00Z"
}

---

POST /api/v1/reports
Authorization: Bearer <token>
Content-Type: application/json
{
  "reported_user_id": "driver-uuid",
  "reason": "rude",
  "description": "Оскорблял во время поездки"
}

Response 201:
{
  "id": "report-uuid",
  "status": "pending"
}
```

### Driver Profile & Verification

```http
POST /api/v1/driver/profile
Authorization: Bearer <token>
Content-Type: application/json
{
  "vehicle": {
    "make": "Lada",
    "model": "Vesta",
    "year": 2022,
    "color": "белая",
    "license_plate": "РТ123АБ",
    "seats": 4
  },
  "bank_details": {
    "account": "40817810xxxxxxxx",
    "bic": "044525225"
  }
}

Response 201:
{
  "id": "profile-uuid",
  "status": "offline"
}

---

POST /api/v1/driver/documents/upload
Authorization: Bearer <token>
Content-Type: multipart/form-data
{
  "document_type": "license",
  "file": <photo.jpg>
}

Response 201:
{
  "id": "doc-uuid",
  "status": "pending",
  "uploaded_at": "2026-06-27T12:00:00Z"
}

---

POST /api/v1/driver/verify
Authorization: Bearer <token>
Content-Type: application/json
{
  "license_doc_id": "doc-uuid-1",
  "vehicle_reg_doc_id": "doc-uuid-2",
  "vehicle_photo_doc_id": "doc-uuid-3"
}

Response 201:
{
  "verification_submitted_at": "2026-06-27T12:05:00Z",
  "status": "pending"
}

---

GET /api/v1/driver/status
Authorization: Bearer <token>

Response 200:
{
  "is_verified": false,
  "verification_status": "pending",
  "documents": [
    {
      "type": "license",
      "status": "pending",
      "reviewed_at": null
    }
  ]
}
```

### Admin Endpoints

```http
GET /api/v1/admin/drivers
Authorization: Bearer <admin_token>
Query: status=pending_verification&limit=50

Response 200:
{
  "data": [
    {
      "id": "driver-uuid",
      "user": { "name": "Рустам", "phone": "+7999..." },
      "documents": [
        {
          "type": "license",
          "file_url": "...",
          "status": "pending"
        }
      ]
    }
  ]
}

---

POST /api/v1/admin/drivers/{driver_id}/moderate
Authorization: Bearer <admin_token>
Content-Type: application/json
{
  "action": "approve",
  "comment": "документы в порядке"
}

Response 200:
{
  "is_verified": true,
  "reviewed_at": "2026-06-27T12:30:00Z"
}

---

GET /api/v1/admin/reports
Authorization: Bearer <admin_token>
Query: status=pending

Response 200:
{
  "data": [
    {
      "id": "report-uuid",
      "reported_user": {...},
      "reason": "rude",
      "description": "...",
      "status": "pending"
    }
  ]
}

---

POST /api/v1/admin/reports/{report_id}/resolve
Authorization: Bearer <admin_token>
Content-Type: application/json
{
  "action": "warning",
  "comment": "первое нарушение"
}

Response 200:
{
  "status": "resolved",
  "action_taken": "warning"
}
```

---

## IV. WebSocket для real-time (Чат & лайв-статусы)

```json
// подключение
ws://yulbash.ru/api/v1/ws?token=<JWT>&booking_id=<booking_id>

// отправить сообщение (клиент → сервер)
{
  "type": "message",
  "text": "я уже в пути"
}

// получить сообщение (сервер → клиент)
{
  "type": "message",
  "id": "msg-uuid",
  "sender_id": "user-uuid",
  "sender_name": "Иван",
  "text": "я уже в пути",
  "timestamp": "2026-06-27T15:10:30Z"
}

// лайв-статус («едем», «доехали»)
{
  "type": "status",
  "new_status": "on_trip",
  "updated_by_role": "driver",
  "timestamp": "2026-06-27T15:15:00Z"
}

// гео обновление (если пассажир поделился)
{
  "type": "location_update",
  "user_id": "driver-uuid",
  "lat": 54.73,
  "lon": 55.95,
  "timestamp": "2026-06-27T15:15:10Z"
}

// отключение
{
  "type": "disconnect"
}
```

---

## V. Архитектура Android приложения (детали)

### Состояние и навигация (простая, но мощная)

```kotlin
// MainActivity.kt — чистая архитектура состояния
@Composable
fun YuldashApp() {
  // 1. ГЛОБАЛЬНОЕ СОСТОЯНИЕ (помним между экранами)
  val screen = remember { mutableStateOf<Screen>(Screen.Splash) }
  val currentUser = remember { mutableStateOf<User?>(null) }
  val appLanguage = remember { mutableStateOf(AppLanguage.Ru) }
  
  // 2. ЗАГРУЗКА ПРИ СТАРТЕ (SMS-вход → поездки → профиль)
  LaunchedEffect(Unit) {
    // проверить токен в SharedPrefs
    val token = SharedPrefs.getToken()
    if (token != null) {
      currentUser.value = ApiClient.getMe()
      screen.value = Screen.Home
    } else {
      // проверить прошедший ли онбординг
      val onboarded = SharedPrefs.isOnboarded()
      screen.value = if (onboarded) Screen.Login else Screen.Onboarding
    }
  }
  
  // 3. ЗАГРУЗКА ДАННЫХ В ФОН (раз в минуту обновляем поездки)
  LaunchedEffect(Unit) {
    while (true) {
      delay(60.seconds)
      if (screen.value == Screen.Home) {
        rides.value = ApiClient.getRides()
      }
    }
  }
  
  // 4. RENDER (навигация через when)
  when (screen.value) {
    is Screen.Splash -> SplashScreen(
      onSplashDone = {
        // определяем, куда идти
        screen.value = /* ... */
      }
    )
    is Screen.Onboarding -> OnboardingScreen(
      onComplete = {
        screen.value = Screen.Login
      }
    )
    is Screen.Login -> LoginScreen(
      onLoginSuccess = { user ->
        currentUser.value = user
        screen.value = Screen.Home
      }
    )
    is Screen.Home -> HomeScreen(
      currentUser = currentUser.value,
      onNavigateTo = { newScreen ->
        screen.value = newScreen
      }
    )
    // ... другие экраны
  }
}

// Модели экранов
enum class Screen {
  Splash,
  Onboarding,
  Login,
  Home,
  ActiveTrip,
  CreateRide,
  Booking,
  VerifyDriver,
  // ... 15+ экранов
}

// Состояние вкладок в Home
enum class HomeTab {
  Map, Rides, Request, Chat, Profile
}

// Хранилище локального состояния
object LocalAppState {
  var selectedRide: Ride? = null
  var currentUser: User? = null
  var rides: List<Ride> = emptyList()
  var activeTrip: Booking? = null
  // ...
}
```

### Слой данных (ApiClient)

```kotlin
// data/ApiClient.kt — все API-вызовы в одном месте
object ApiClient {
  private val baseUrl = BuildConfig.YULDASH_API_BASE_URL // https://yulbash.ru
  private var jwtToken: String? = null
  private val backgroundScope = CoroutineScope(Dispatchers.IO + Job())
  
  fun init(context: Context) {
    // загрузить токен из SharedPrefs
    jwtToken = SharedPreferences(context, "yuldash")
      .getString("jwt_token", null)
  }
  
  // АУТЕНТИФИКАЦИЯ
  suspend fun requestCode(phone: String): String {
    val response = httpPost<RequestCodeResponse>(
      "/auth/request-code",
      mapOf("phone" to phone)
    )
    return response.request_id
  }
  
  suspend fun verifyCode(requestId: String, code: String): AuthResponse {
    val response = httpPost<AuthResponse>(
      "/auth/verify-code",
      mapOf("request_id" to requestId, "code" to code)
    )
    jwtToken = response.access_token
    // сохранить токен в SharedPrefs
    SharedPreferences.saveToken(response.access_token)
    return response
  }
  
  // ПОЕЗДКИ
  suspend fun getRides(
    lat: Double = 54.73,
    lon: Double = 55.95,
    radiusKm: Int = 30,
    petsAllowed: Boolean? = null,
    womenOnly: Boolean? = null
  ): List<Ride> {
    return try {
      httpGet<RidesResponse>(
        "/rides?from_lat=$lat&from_lon=$lon&radius_km=$radiusKm" +
        (if (petsAllowed != null) "&pets_allowed=$petsAllowed" else "") +
        (if (womenOnly != null) "&women_only=$womenOnly" else "")
      ).data
    } catch (e: Exception) {
      // фолбэк на моки
      demoRides
    }
  }
  
  suspend fun createRide(ride: RideIn): String {
    val response = httpPost<RideResponse>(
      "/rides",
      ride
    )
    return response.id
  }
  
  // ЗАЯВКИ
  suspend fun createRequest(req: RequestIn): String {
    val response = httpPost<RequestResponse>(
      "/requests",
      req
    )
    return response.id
  }
  
  suspend fun getMyRequests(): List<RequestDto> {
    return try {
      httpGet<RequestsResponse>("/requests/my").data
    } catch (e: Exception) {
      emptyList()
    }
  }
  
  // БРОНИРОВАНИЕ
  suspend fun book(rideId: String, seats: Int = 1): BookingResponse {
    return httpPost(
      "/bookings",
      mapOf("ride_id" to rideId, "seats" to seats)
    )
  }
  
  suspend fun confirmBooking(bookingId: String): BookingResponse {
    return httpPost(
      "/bookings/$bookingId/confirm",
      emptyMap()
    )
  }
  
  // СООБЩЕНИЯ
  suspend fun sendMessage(bookingId: String, text: String) {
    fireAndForget {
      httpPost(
        "/bookings/$bookingId/messages",
        mapOf("text" to text)
      )
    }
  }
  
  // ДОКУМЕНТЫ (водитель)
  suspend fun uploadPhoto(file: ByteArray, docType: String): String {
    val boundary = "----WebKitFormBoundary${System.currentTimeMillis()}"
    val body = buildMultipartBody(
      "document_type" to docType,
      "file" to file
    )
    return httpPostRaw("/driver/documents/upload", body, boundary)
  }
  
  suspend fun verifyDriver(licenseDocId: String, vehicleRegDocId: String): String {
    return httpPost<VerifyResponse>(
      "/driver/verify",
      mapOf(
        "license_doc_id" to licenseDocId,
        "vehicle_reg_doc_id" to vehicleRegDocId
      )
    ).verification_submitted_at
  }
  
  // БЕЗОПАСНОСТЬ
  suspend fun triggerSOS(bookingId: String?, description: String) {
    fireAndForget {
      httpPost(
        "/sos",
        mapOf(
          "booking_id" to bookingId,
          "description" to description
        )
      )
    }
  }
  
  // РЕЙТИНГИ
  suspend fun rateUser(bookingId: String, toUserId: String, rating: Int, comment: String) {
    fireAndForget {
      httpPost(
        "/bookings/$bookingId/rate",
        mapOf(
          "to_user_id" to toUserId,
          "rating_value" to rating,
          "comment" to comment
        )
      )
    }
  }
  
  // ============ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ============
  
  private suspend inline fun <reified T> httpGet(path: String): T {
    return withContext(Dispatchers.IO) {
      val url = URL("$baseUrl/api/v1$path")
      (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $jwtToken")
        setRequestProperty("Accept", "application/json")
      }.use { conn ->
        if (conn.responseCode != 200) {
          throw Exception("HTTP ${conn.responseCode}")
        }
        conn.inputStream.bufferedReader().readText()
          .let { json -> Json.decodeFromString<T>(it) }
      }
    }
  }
  
  private suspend inline fun <reified T> httpPost(path: String, body: Any): T {
    return withContext(Dispatchers.IO) {
      val url = URL("$baseUrl/api/v1$path")
      val jsonBody = Json.encodeToString(body)
      (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Authorization", "Bearer $jwtToken")
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
        outputStream.write(jsonBody.toByteArray())
      }.use { conn ->
        if (conn.responseCode !in 200..299) {
          throw Exception("HTTP ${conn.responseCode}")
        }
        conn.inputStream.bufferedReader().readText()
          .let { json -> Json.decodeFromString<T>(json) }
      }
    }
  }
  
  // пустить в фон (не блокирует экран)
  private fun fireAndForget(block: suspend () -> Unit) {
    backgroundScope.launch {
      try {
        block()
      } catch (e: Exception) {
        Log.e("ApiClient", "Background request failed", e)
      }
    }
  }
  
  // демо-данные (фолбэк при нет сети)
  val demoRides = listOf(
    Ride(
      id = "ride-1",
      driverName = "Рустам",
      driverRating = 4.8,
      from = "ул. Ленина, Баймаҡ",
      to = "ул. Мира, Сибай",
      departAt = System.currentTimeMillis() + 30.minutes,
      price = 450,
      seatsAvailable = 2,
      petsAllowed = true
    ),
    // ...
  )
}
```

### DTO (модели данных)

```kotlin
// data/DTO.kt — всё, что приходит с сервера

@Serializable
data class RideDto(
  val id: String,
  val driver: DriverDto,
  val vehicle: VehicleDto?,
  val from: LocationDto,
  val to: LocationDto,
  val depart_at: String, // ISO 8601
  val seats_available: Int,
  val price: Int, // копейки
  val pets_allowed: Boolean = false,
  val women_only: Boolean = false,
  val child_seat_available: Boolean = false,
  val is_boosted: Boolean = false,
  val created_at: String
)

@Serializable
data class DriverDto(
  val id: String,
  val name: String,
  val rating: Double,
  val trips_count: Int,
  val is_verified: Boolean,
  val avatar_url: String?
)

@Serializable
data class BookingDto(
  val id: String,
  val ride: RideDto,
  val status: String, // pending, confirmed, on_trip, completed
  val price_total: Int,
  val boarding_code: String,
  val confirmed_at: String?,
  val created_at: String
)

@Serializable
data class MessageDto(
  val id: String,
  val sender: UserDto,
  val text: String?,
  val voice_url: String?,
  val voice_transcript: String?,
  val sent_at: String,
  val read_at: String?
)

@Serializable
data class RequestDto(
  val id: String,
  val from: LocationDto,
  val to: LocationDto,
  val desired_depart_at: String,
  val status: String,
  val seats_needed: Int,
  val max_price: Int?,
  val created_at: String
)
```

---

## VI. Безопасность & Compliance

### На уровне приложения

```kotlin
// ✅ Никогда не логируем чувствительные данные
Log.d("MyApp", "User logged in") // ✓ OK
Log.d("MyApp", "Token: $jwtToken") // ✗ FORBIDDEN

// ✅ Маскируем телефоны в UI
fun maskPhone(phone: String) = "+7 *** ** ${phone.takeLast(2)}"

// ✅ Запрашиваем пермишены в нужный момент (при активной поездке)
if (ContextCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION)
    != PackageManager.PERMISSION_GRANTED) {
  ActivityCompat.requestPermissions(this, arrayOf(ACCESS_FINE_LOCATION), 101)
}

// ✅ Отправляем гео только после подтверждения
if (locationSharingEnabled) {
  sendLocationUpdates() // WebSocket в фон
}
```

### На уровне API

```python
# backend/middleware/auth_middleware.py

@app.middleware("http")
async def verify_jwt(request: Request, call_next):
    auth_header = request.headers.get("Authorization", "")
    if not auth_header.startswith("Bearer "):
        return JSONResponse({"error": "Unauthorized"}, status_code=401)
    
    token = auth_header.split(" ")[1]
    try:
        payload = jwt.decode(token, SECRET_KEY, algorithms=["HS256"])
        request.state.user_id = payload["sub"]
    except jwt.ExpiredSignatureError:
        return JSONResponse({"error": "Token expired"}, status_code=401)
    
    return await call_next(request)

# backend/middleware/rate_limit.py
# max 100 req/min per IP → DDoS protection
```

### Соответствие 152-ФЗ (России)

```markdown
- ✅ Хранение данных в РФ (PostgreSQL на сервере в РФ)
- ✅ Согласие на обработку ПД (галка при регистрации + версия оферты)
- ✅ Политика конфиденциальности (на сайте, ссылка в приложении)
- ✅ Возможность удалить профиль (GDPR-like / право забвения)
- ✅ Не передаём данные третьим лицам без согласия
- ✅ Шифруем в передаче (HTTPS + TLS 1.2+)
- ✅ Аудит-логи (кто когда что действил → для споров)
```

---

## VII. DevOps & Масштабирование

### Текущий стек (июнь 2026)

```
Приложение:   Kotlin + Jetpack Compose (1 APK, ~117 МБ)
Бэкенд:       FastAPI (Python, ~50 роутов)
БД:           PostgreSQL (одна инстанция, ~500 МБ)
Сервер:       VPS 2 ядра / 4 ГБ (ОЦ Tangra, $10/месяц)
Хранилище:    локальный диск (фотографии в `media/`)
HTTPS:        Let's Encrypt (certbot)
Домен:        yulbash.ru (DNS, MX записи)
```

### План масштабирования (для 1М пользователей)

```
ФАЗА 1 (текущая, ~10k DAU):
├─ 1 PostgreSQL (SSD)
├─ 2-3 FastAPI worker (gunicorn)
├─ nginx + Let's Encrypt
├─ простой мониторинг (systemd + alerting)

ФАЗА 2 (100k DAU):
├─ PostgreSQL + read replica (репликация)
├─ Redis cache (сессии + рейтинги)
├─ 5-10 FastAPI worker (load-balancer)
├─ S3-compatible storage (фото)
├─ docker-compose (управляемые контейнеры)

ФАЗА 3 (1M DAU):
├─ PostgreSQL кластер (Patroni + etcd)
├─ Redis cluster (3+ инстанции)
├─ Kubernetes (микросервисы)
├─ CDN (Cloudflare / Yandex.Cloud)
├─ Prometheus + Grafana (метрики)
├─ ELK stack (логи)
├─ отдельный чат-сервер (Socket.IO)
```

### CI/CD Pipeline (GitHub Actions)

```yaml
# .github/workflows/deploy.yml
name: Deploy to Production

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      
      - name: Run backend tests
        run: cd backend && python -m pytest tests/ -v
      
      - name: Build APK
        run: cd android && ./gradlew assembleRelease
      
      - name: Upload APK to S3
        run: aws s3 cp android/app/build/outputs/apk/release/app-release.apk s3://yuldash-releases/
      
      - name: Deploy backend (SSH)
        env:
          DEPLOY_KEY: ${{ secrets.DEPLOY_KEY }}
        run: |
          mkdir -p ~/.ssh
          echo "$DEPLOY_KEY" > ~/.ssh/id_rsa
          chmod 600 ~/.ssh/id_rsa
          ssh -i ~/.ssh/id_rsa deploy@yulbash.ru \
            "cd /home/yuldash && git pull && docker-compose up -d"
```

---

## VIII. Метрики & Аналитика (что мерить)

```kotlin
// analytics/EventTracker.kt

object EventTracker {
  fun trackRideSearch(filters: Map<String, Any>) {
    // что ищет пользователь → улучшение матчинга
  }
  
  fun trackRideBooked(rideId: String, price: Int) {
    // конверсия поиск → бронь
  }
  
  fun trackTripCompleted(bookingId: String, durationMin: Int, price: Int) {
    // метрики поездки → выручка, среднее время
  }
  
  fun trackDriverOnboarding(step: String) {
    // фанел онбординга водителя → где отсеиваемся
  }
  
  fun trackSOSTriggered(reason: String) {
    // безопасность → какие проблемы
  }
}

// Dashboard (что показываем инвесторам)
// DAU / MAU (активные пользователи)
// ARPU = общая выручка / активные пользователи
// Retention (какой % вернулись через неделю)
// Conversion: поиск → бронь
// NPS (Net Promoter Score, опрос)
// Driver supply (сколько водителей онлайн)
// Avg rating (качество сервиса)
```

---

## IX. Дорожная карта (Roadmap)

```markdown
### Q3 2026 (текущий квартал)
- ✅ MVP (Telegram/VK вход, поездки, чат, проверка водителя)
- 🔄 Платежи для донатов и буста (Яндекс.Касса)
- 🔄 Telegram/VK/WhatsApp вход (вместо SMS)
- [ ] Расширение на Сибай + Уфу

### Q4 2026
- [ ] Платформа для партнёров маршрута (аптеки, кафе на маршруте)
- [ ] Push-уведомления (Firebase Cloud Messaging)
- [ ] Мобильное приложение для админа (модерация водителей, жалобы)
- [ ] Реальные платежи (опционально, для корпоративных заказов позже)

### Q1 2027
- [ ] Web-версия (для водителей из браузера)
- [ ] API для партнёров (открытый маркетплейс)
- [ ] Интеграция с системой 1С (для корпоративных заказов)
- [ ] iOS приложение (Swift + SwiftUI) — пока на бэклоге, фокус на Android

### Q2 2027+
- [ ] Корпоративный сегмент (большие работодатели)
- [ ] Экспорт в другие регионы (Оренбургская область, Татарстан)
- [ ] AI-матчинг (ML модель предсказывает best match)
- [ ] Автоматическая оплата через переводы (без касс)
```

---

## X. Security Checklist (перед продакшеном)

- [ ] Все API требуют JWT токен
- [ ] Пароли NE хранятся (только JWT)
- [ ] HTTPS везде (А+ rating on SSL Labs)
- [ ] SQL-injection защита (SQLModel ORM, parameterized queries)
- [ ] XSS защита (Android нативный, не WebView)
- [ ] CSRF protection (если есть веб-форма)
- [ ] Rate limiting (max запросов в минуту)
- [ ] Sensitive logs удалены (не логируем токены/телефоны)
- [ ] Бэкапы БД (ежедневно, на отдельном сервере)
- [ ] Мониторинг ошибок (alerting когда падает)
- [ ] Penetration testing (перед инвест-раундом)

---

## XI. Deployment (как разворачивается на сервер)

```bash
# На сервере yulbash.ru (Ubuntu 22.04)

# 1. Клонируем репо
git clone https://github.com/your-org/yuldash.git /home/yuldash
cd /home/yuldash

# 2. Окружение
cp backend/.env.example backend/.env
# вписать: DB_URL, STRIPE_KEY, SMS_PROVIDER_KEY и т.д.

# 3. Docker-compose для целого стека
docker-compose up -d

# 4. Инициализация БД
docker-compose exec backend python -m alembic upgrade head

# 5. Проверяем живо
curl https://yulbash.ru/api/v1/health

# 6. Мониторим логи
docker-compose logs -f backend
```

---

## XII. Итого: Production-ready система

| Компонент | Статус | Масштаб |
|-----------|--------|---------|
| **Telegram/VK вход** | 🔄 | 10k DAU |
| **Поездки & заявки** | ✅ | 10k DAU |
| **Матчинг** | ✅ базовый | 100k DAU (ML позже) |
| **Чат** | ✅ REST (polling) | 1M DAU (WebSocket позже) |
| **Донаты & Boost (платежи)** | 🔄 | Яндекс.Касса готова |
| **Проверка водителя** | ✅ | 100% |
| **Карта (Яндекс)** | ✅ | реальные тайлы, маркеры |
| **Админ-панель** | 🔄 API ready | только через API |
| **Мобильное приложение** | ✅ | Google Play |
| **iOS** | ❌ | на Q1 2027 |

---

**Это ваша система. Растёт пошагово, сохраняя quality.**
