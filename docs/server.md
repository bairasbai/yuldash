# 🖥 Сервер и деплой бэкенда

> Инфраструктура Юлдаша. **Секреты (root-пароль, `jwt_secret`) тут НЕ хранятся** — только на сервере.

## Сервер
- Провайдер: **Timeweb Cloud**. ОС: **Ubuntu 24.04 LTS**. 2×3.3 ГГц.
- IPv4: `85.239.52.55` · IPv6: `2a03:6f00:a::2:aefd`
- Хостнейм: `msk-1-vm-3azu`

## Доступ — только по SSH-ключу
- Вход: `ssh root@85.239.52.55` (ключ `~/.ssh/id_ed25519` на ноуте Александра).
- Пароль **отключён** (`PasswordAuthentication no`), root только по ключу. Конфиг: `/etc/ssh/sshd_config.d/00-yuldash-hardening.conf`.
- Потеря ключа → доступ через панель Timeweb → вкладка **«Консоль»**.
- **Firewall (ufw):** открыты `22`, `80`, `443`; остальное закрыто.
- ⚠️ Российские операторы (ТСПУ) периодически режут SSH → таймауты подключения. Лечится повтором/VPN. Это провайдер, не сервер.

## Бэкенд (FastAPI)
- Код: `/opt/yuldash` (залит из `backend/`). venv: `/opt/yuldash/.venv`.
- Конфиг: `/opt/yuldash/.env` (`env=prod`, `jwt_secret`, `DATABASE_URL` → PostgreSQL, `SMS_PROVIDER`). **НЕ в git.**
- Сервис: systemd **`yuldash-api`** (uvicorn на `127.0.0.1:8000`, автозапуск при загрузке, `Restart=always`).
- nginx: проксь `:443`/`:80` → `:8000`. Конфиг `/etc/nginx/sites-available/yuldash`.
- **API публично (HTTPS): `https://yulbash.ru`** (HTTP → 301 на HTTPS). Проверка: `curl https://yulbash.ru/health` → `{"status":"ok","env":"prod"}`.
- Домен: **`yulbash.ru`** (A-запись → `85.239.52.55`, регистратор Timeweb). SSL: Let's Encrypt (`certbot`, плагин nginx) для `yulbash.ru` + `www.yulbash.ru`. Авто-обновление: `certbot.timer`. Cert: `/etc/letsencrypt/live/yulbash.ru/`. (Старый `sslip.io`-cert тоже остался, не мешает.)

## 🚪 Как подключиться и управлять (шпаргалка для Александра)

> Всё — с ноутбука. Открыть **PowerShell** (Пуск → набрать `PowerShell` → Enter).

### Зайти на сервер
```powershell
ssh root@85.239.52.55
```
Пустит **без пароля** (по ключу). Увидишь `root@msk-1-vm-3azu:~#` — ты внутри. Выйти обратно: `exit`.

⚠️ Если висит `Connection timed out` — это оператор связи (ТСПУ) режет SSH, **не сервер**. Что делать: повтори 2–3 раза, или включи VPN, или зайди через панель Timeweb → вкладка **«Консоль»** (вход в браузере, ключ не нужен).

### Частые команды (после входа на сервер)
| Зачем | Команда (выход) |
|---|---|
| API живой? | `systemctl status yuldash-api` (выход — `q`) |
| Перезапустить API | `systemctl restart yuldash-api` |
| Логи живьём | `journalctl -u yuldash-api -f` (выход — `Ctrl+C`) |
| Проверка здоровья | `curl https://yulbash.ru/health` |
| Место на диске | `df -h` |
| Память | `free -h` |

- **Посмотреть код входа (OTP)** — пока SMS мок, код пишется в лог. Команда: `journalctl -u yuldash-api | grep OTP` → строка вида `[OTP] +7999... -> 1234`.

### Команда без захода (одной строкой с ноута)
```powershell
ssh root@85.239.52.55 "systemctl restart yuldash-api"
```

### Передеплой бэкенда (после обновления кода)
Обычно это делает Claude. Суть: залить свежий `backend/` в `/opt/yuldash` → `cd /opt/yuldash && ./.venv/bin/pip install -r requirements.txt` → `chown -R yuldash:yuldash /opt/yuldash` → `systemctl restart yuldash-api`.

### Потерял ключ/ноут — как вернуть доступ
Панель Timeweb → сервер «Юлдаш» → вкладка **«Консоль»**: вход в систему прямо в браузере, ключ не нужен. Оттуда можно всё починить (например, добавить новый ключ).

## Не сделано (следующие шаги)
- [x] **HTTPS/SSL** — включён бесплатно через `sslip.io` + Let's Encrypt (без покупки домена). Захочешь красивый адрес — купить домен, привязать A-запись на `85.239.52.55`, `certbot --nginx -d домен` (1 команда), сменить базовый URL в приложении.
- [x] **Подключить Android к API (CRUD-ядро)**: ✅ вход (SMS→JWT→автологин), ✅ поездки с сервера (`GET /rides`, карточки с водителем), ✅ заявки (`POST /requests` + `GET /requests/mine` — видны на вкладке «Заявка»), ✅ публикация поездки (`POST /rides` — проверено: БД 5→6), ✅ бронь (`POST /bookings` — проверено curl: booking id1, тот же fire-and-forget механизм). Клиент `data/ApiClient.kt` (HttpURLConnection, без зависимостей). **Важный фикс:** POST'ы — на долгоживущем scope `ApiClient` (fire-and-forget), иначе scope экрана отменялся при навигации и обрывал запрос. Бэкенд `/rides` дополнен `RideOut`+сид демо-поездок. ✅ **SOS** (`POST /sos` — проверено: БД 1→2), ✅ **доверенные контакты** (`POST`/`GET /trusted-contacts` — curl id1, загрузка в экран). ✅ **чат — минимальный шов**: composer с реальным вводом → `POST /bookings/{id}/messages` в последнюю бронь юзера (бэкенд проверен curl: message id1). Осталось: реальный SMS-провайдер (одобрение отправителя sms.ru — действие Александра). ✓ **Сделано 2026-06-23:** список диалогов (`/conversations`), уведомления (`/notifications`), популярные (`/popular-routes`), частые (`/my-routes`), реклама (`/ads`), share/trip-status — все сервер-управляемые с демо-фоллбэком. Базовый URL: `https://yulbash.ru`.
- [~] **SMS-провайдер (`sms.ru`)** — ключ валиден (баланс 10 ₽, `sms_provider=smsru`, ключ в `.env`, **НЕ в git**), код шлёт реальную SMS. **НО** sms.ru требует **буквенного отправителя** (ошибка 221) → пока не доставляет. ⚠️ **Действие Александра:** sms.ru → «Отправители» (https://sms.ru/?panel=senders) → создать отправителя (бренд, напр. `Yulbash`/`Yuldash`) → модерация (часы). После одобрения: `sms_from=ИМЯ` в `/opt/yuldash/.env` → `systemctl restart yuldash-api` → реальные SMS пойдут. **Вход НЕ сломан:** при ошибке отправки код падает в лог (фоллбэк) — `journalctl -u yuldash-api | grep OTP`.
- [x] **БД: PostgreSQL** — переключено с SQLite (`DATABASE_URL=postgresql+psycopg2://yuldash@localhost:5432/yuldash`). PG слушает только localhost + firewall закрывает 5432 снаружи. Схема+сид пересозданы (реальных данных не было). Драйвер `psycopg2-binary` в requirements.
- [x] **`/driver/bookings` — водитель оценивает пассажиров** (2026-06-24): `GET /driver/bookings` → брони на поездки текущего водителя (`booking_id`, имя/рейтинг пассажира, маршрут, статус). Android: `getDriverBookings`/`DriverBookingDto`, секция «Пассажиры — оцените» в кабинете водителя → `rateBooking`. Закрывает вторую сторону `/rate`. Развёрнут.
- [x] **`/bookings/{id}/rate` — реальные рейтинги** (2026-06-24): таблица `Rating` (booking, rater, ratee, stars). `POST /bookings/{id}/rate {stars}` — пассажир оценивает водителя, водитель — пассажира (1 на бронь, перезапись). Рейтинг = среднее реальных оценок (`_user_rating`), до отзывов — сид из `DriverProfile.rating`. `RideOut.driver_rating` живой, `/me` отдаёт свой рейтинг. Android: `rateBooking`, звёзды в ActiveTrip, рейтинг в Профиле. Проверено: 4★→4.0, +5★→4.5 (БД). **Авто-миграция:** `init_db` создаёт таблицу на старте.
- [x] **`/rides/near` — ближайшие по маршруту + гео** (2026-06-24): `GET /rides/near?from_city&to_city&lat&lng&radius_km` — активные поездки (есть места) по маршруту клиента, **сортировка по времени выезда ↑**, haversine-дистанция клиент→точка выезда (таблица `CITY_COORDS` городов БашРТ), опц. фильтр радиуса. Ответ `{count, items:[RideOut+distance_km]}`. Без авторизации. Android: `getNearbyRides`/`RideDto.distanceKm` → секция «Ближайшие поездки».
- [x] **`/feed` — живая лента карты** (2026-06-24): `GET /feed` отдаёт счётчики поездок за день/неделю/месяц/год (из `Booking.created_at`) + топ-маршрут недели (из `Ride`) + число водителей. Без авторизации. Android: `ApiClient.getFeed()`/`FeedDto`, карусель `QuickSearchCard` показывает реальные числа (офлайн → демо). Развёрнут (curl: `{"today":2,"week":3,...,"top_route":{"Темясово→Уфа"}}`).
- [x] **Экран активной поездки** (`ActiveTripScreen`) — закрывает чат-треды + семейный share: после брони открывается «Моя поездка» с чатом по брони (история `GET` + отправка), «поделиться с близким» (`POST /bookings/{id}/share`), статусом поездки (сел/доехал/завершить → `POST /bookings/{id}/trip-status`), SOS. Все эндпоинты curl-проверены (share→TripShare id1, trip-status→sat, messages→id1). book возвращает booking_id для контекста.
- [x] **Проверка водителя + фото + премиум-предпочтения** (2026-06-24, Cowork, ✅ ЗАДЕПЛОЕНО). Эндпоинты: `POST /upload/photo` (base64→`media/docs`, лимит `MAX_UPLOAD_MB`, белый список расширений), `POST /driver/profile` (реальное авто вместо хардкода), `POST /driver/verify` (→`docs_status=pending`+`license_url`/`car_photo_url`), `GET /driver/status`, `POST /admin/drivers/{id}/moderate` (роль admin → `User.verified=True`). `DriverProfile` +`docs_status`(none/pending/verified/rejected)/`license_url`/`car_photo_url`/`verify_submitted_at`. **Премиум-поля** `Ride`/`RideIn`/`RideOut`: `pets_allowed`/`child_seat`/`women_only`/`smoking`/`baggage`/`air_conditioner` (дефолт False, `GET /rides` принимает как фильтры). Проверено server-side: `/driver/status`,`/upload/photo`→401; `/rides/near` отдаёт премиум-поля. Деплой: `backend/deploy-backend.bat` (scp+ssh+chown+restart) + `backend/migrate_premium.sql` (9× ALTER TABLE — `SQLModel.create_all` НЕ добавляет колонки в существующие таблицы PG). Проверка: `backend/verify-server.bat`/`verify-deploy.bat`.
- [x] **Прод-харднинг конфига** (`config.py`): `env=dev|prod`; `validate_production()` (вызов на старте при prod) РУГАЕТСЯ и падает, если: `jwt_secret`=dev/<16 симв., `sms_provider=mock`, `media_base_url` не публичный HTTPS, `cors_origins=*`. Новые ключи `.env`: `MEDIA_BASE_URL`, `CORS_ORIGINS`, `SEED_DEMO`, `MAX_UPLOAD_MB`, `ALLOWED_IMAGE_EXT`, `ALLOWED_AUDIO_EXT`. Шаблон — `backend/.env.example` (секреты НЕ в git).
- [x] **Базовый URL API — из конфигурации сборки** (Android): `build.gradle.kts` читает `local.properties` → `BuildConfig.YULDASH_API_BASE_URL`. Debug → `http://10.0.2.2:8000` (локальный бэк с эмулятора), release → `https://yulbash.ru`. `ApiClient.BASE` = `BuildConfig.YULDASH_API_BASE_URL`. Переопределяется `YULDASH_DEBUG_API_BASE_URL`/`YULDASH_RELEASE_API_BASE_URL` в `local.properties`.
- ⚠️ **Windows-curl к `yulbash.ru` = `000`** (ТСПУ/клиентская сеть режет curl.exe с ноута) — сервер и приложение/эмулятор API видят. Прод-API проверять server-side: `ssh root@85.239.52.55 "curl -s http://127.0.0.1:8000/<path>"`.
