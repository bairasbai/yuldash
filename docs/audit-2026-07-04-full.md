# 🔬 Полный построчный аудит Юлдаша — 2026-07-04

> Метод: 9 параллельных senior-аудиторов, каждый читал свою зону ЦЕЛИКОМ построчно (Android UI ×5, backend ×2, ApiClient+контракт, инфра/web). Кросс-факты сверялись с обоими кодовыми базами.
> Объём: ~21k строк Kotlin + ~10k Python + web/инфра. **Найдено ~227 находок: P0 — 1, P1 — 17, P2 — 83, P3 — 126.**
> Легенда: **P0** краш/деньги/утечка ПДн/жизнь · **P1** сломанное поведение видное юзеру или блокер релиза · **P2** перф/поддерживаемость/полиш/безопасность-на-вырост · **P3** мелочь.

Крашеопасных `!!` не найдено нигде. Секреты в git не утекли (проверено `git ls-files`). Контракт клиент↔сервер сходится на 99% (92/92 эндпоинта, 1 полевое расхождение). База — заметно выше среднего «вайб-кода»; блокеры точечные.

---

## 🔴 P0 — критично (1)

| # | Файл | Проблема | Фикс |
|---|---|---|---|
| P0-1 | SosVerifyScreens.kt:565 + backend config.py:15 | SOS-экран обещает «доверенные получат SMS», но на проде `sms_provider="mock"` → SMS **физически не уходят**, только лог. Ложное обещание в жизненно-важном флоу. | Бэк возвращает `sms_delivered:bool`; при mock — честно «Сигнал поддержке отправлен; SMS близким недоступны — позвони сам» + кнопка звонка доверенному. |

---

## 🟠 P1 — блокеры релиза (17)

### Навигация / вход (ядро)
- **P1-2** YuldashApp.kt:499 — «Назад» на `Screen.Intro` не заблокирован → обход онбординга и логина (`onboarding_completed` не ставится). FIX: `&& screen != Screen.Intro` в BackHandler.
- **P1-3** YuldashApp.kt:361 — `Intro` не в `transient` → «Назад» из кабинета водителя возвращает на брендовое интро. FIX: добавить Intro в transient + чистить navHistory в ветке DriverCabinet.
- **P1-4** YuldashApp.kt:313/468/491 — `me()/getMyRequests()/getContacts()` грузятся один раз на Splash ДО логина → после входа «Мои заявки»/контакты/роль админа пусты до перезапуска. FIX: ключевать эффекты на флаг логина, перезагружать при входе.
- **P1-5** LoginScreen.kt:427 — тупик после протухшего кода (410)/лимита (429): кнопка ретрая открывает СТАРЫЙ `tgRequestId`. Подтверждено бэком (auth.py:192/217, attempts не сбрасывается). FIX: при 410/429 — `tgStart()` заново (новый request_id); бэк — сбрасывать attempts при перевыпуске.

### Демо-данные «протекают» при живом пустом сервере
- **P1-6** YuldashApp.kt:431 — при 0 поездок с сервера демо НЕ заменяются (`isNotEmpty()`-гард), их id `"1".."3"` числовые → `book()` создаёт реальную бронь на чужую серверную поездку id=1. FIX: onSuccess всегда заменять список; демо-id нечисловые (`demo-1`).
- **P1-7** YuldashApp.kt:335 — то же для рекламы: сервер жив, `/ads` пуст → остаётся `demoPartnerAds` с фейк-телефоном, кнопка «Позвонить» набирает `+7 927 000-12-12`. FIX: onSuccess всегда заменять.

### Live-гео / поездка
- **P1-8** YuldashApp.kt:577 (+783) — `onOpenActiveTrip` ставит `activeTrip=null` перед входом → гейт live-гео ложный → `TripLocationService` глушится для подтверждённой поездки, попутчик не видит позицию. FIX: `activeTrip=ride` когда статус позволяет.

### Чат / бронь (контракт транспортов)
- **P1-9** ApiClient — клиент нигде не зовёт `POST /bookings/{id}/confirm` → прямая бронь навсегда `pending` → телефон водителя/pickup/координаты **никогда не разблокируются**, live-гео не подключается. Работает только флоу заявка→отклик→accept. FIX: `confirmBooking()` + кнопка «Подтвердить» водителю.
- **P1-10** chat.py:98-115 — REST-отправка сообщения (голос/фото/текст-фолбэк) НЕ делает `manager.broadcast` → собеседник видит их только после переполла. FIX: тот же broadcast, что в WS-хендлере.

### Приватность / доступность
- **P1-11** RidesRequestsChatScreens.kt:1810 — микрофон (`VoiceRecorder`) не останавливается на dispose → MediaRecorder пишет звук после ухода с экрана (приватность + утечка). FIX: `DisposableEffect { onDispose { recorder.stop() } }`.
- **P1-12** AccessibilityScreens.kt:954 — телефон близкого вшивается в свободный комментарий заявки, видимый водителям ДО подтверждения (вопреки обещанию файла:784). FIX: отдельное API-поле, раскрывать после подтверждения.

### Карты — деньги/батарея
- **P1-13** MapScreen.kt:295/305 — `/rides/near` и заявки грузятся на КАЖДЫЙ GPS-фикс (эффект на сырых координатах). FIX: ключ по округлённой позиции / дебаунс ≥500м.
- **P1-14** MapScreen.kt:1346 — маршрут до партнёра пересчитывается DrivingRouter'ом на каждый фикс → квота MapKit горит + линия мигает. FIX: ключ только `adRoutePoint`, origin захватить раз.
- **P1-15** MapScreen.kt:1404 — onStart/onStop карты привязаны к композиции, не к lifecycle → свернул приложение с картой → MapKit рендерит/сеть в фоне. FIX: `LifecycleEventObserver`.

### Backend-безопасность
- **P1-16** auth.py:91-113 — SMS-OTP не гасится после входа (нет `used`, только TTL 5мин) → перехваченный код реюзабелен. FIX: `session.delete(otp)` после успеха.
- **P1-17** family.py:24-30 + safety.py — `add_contact` без лимита кол-ва и без валидации телефона → SMS-бомбинг чужих номеров за счёт платформы (каждый SOS/trip-status шлёт всем). FIX: кеп ~5 контактов + regex номера + троттл рассылок.

### Инфра
- **P1-18** deploy-backend.bat vs alembic vs migrate_*.sql — три источника истины схемы; деплой применяет 5 из 22 SQL и НЕ зовёт `alembic upgrade head` → ревизии `0002/0003` не доезжают → новая колонка = тихий 500 на проде. FIX: единый путь `alembic stamp head` (разово) + `alembic upgrade head` (всегда); migrate_*.sql → archive/.

---

## 🟡 P2 — важное (83)

### UI — класс «молчаливый fire-and-forget» (нет onFailure → действие теряется)
SecondaryScreens.kt: модерация водителей :742, оплаты :1001, приём отклика :1114, поддержка :1377 (тост «отправлено» ДО результата), уведомления :267. ProfileScreen.kt: кабинет водителя :844 (ложное «нет маршрутов» при обрыве), тарифы AdEditor :1297 (тупик флоу), реферал :306. SosVerifyScreens.kt: getDriverStatus :670. RidesRequestsChatScreens.kt: заявки в чате :1238. → FIX (шаблонно): `.onFailure { Toast/ошибка+Повторить }`.

### UI — плацебо / мёртвый UI / фейк-данные
- SecondaryScreens.kt:401 — тумблер «Скрывать телефон» пишет в prefs, но нигде не читается и серверу не уходит — ложное обещание безопасности.
- SecondaryScreens.kt:269 — табы уведомлений мёртвые (все в одну иконку → «Поездки»/«Система» всегда пусты).
- ProfileScreen.kt:480 + BookingActiveTripScreen.kt:738 — «Пассажир · Баймаҡ» захардкожено у ВСЕХ (реальные роль/город с сервера есть, но не рисуются).
- ProfileScreen.kt:279/1472/1557 — реклама фильтруется по «Баймаҡ»; impression засчитывается при каждом входе item в композицию (накрутка при скролле). FIX: город юзера; дедуп impression по Set id.
- RidesRequestsChatScreens.kt:1323 — фейк-бейдж «проверен» у ВСЕХ диалогов (в DTO поля нет) — подрыв главной фишки доверия. :1304/1322 — счётчик непрочитанных всегда 0.

### UI — state не ключёван по bookingId (утечка в другую бронь)
BookingActiveTripScreen.kt: `role` :764 (чужие кнопки роли), `draft/editingId/status` :780/782, оценка :956; MapView пересоздаётся :541 (нестабильные ключи Point) → placemark'и копятся :565.

### Карты / гео / сокеты
- MapScreen.kt:1157 — «моя точка» слушает GPS+NETWORK разом (двойной расход, фикс из сервиса сюда не дошёл); не lifecycle-aware.
- MapScreen.kt:328 — MapFeedSocket живёт пока экран в композиции (ночью ping/25с держит радио). FIX: `repeatOnLifecycle`.
- MapScreen.kt:299 — координаты в GET-query → оседают в access-логах nginx. FIX: POST-тело / грубить до ~1км.
- TripLocationService.kt:68 — если оба провайдера выключены при старте, listener присвоен но updates не заказаны → guard навсегда блокирует; :63 — `peer` не чистится при смене брони (чужие координаты).
- LocationSocket.kt:79 + MapFeedSocket.kt:54 + ChatSocket.kt:69 — нет `onClosing` → серверный graceful-close (деплой/1008) не реконнектит или долбит 10× при отказе авторизации. LocationSocket.kt:70 — `optDouble`→NaN→Point(NaN) в MapKit.

### ApiClient / контракт
- ApiClient.kt:283 — `/auth/verify` читает top-level `name`, сервер кладёт в `user.name` → серверное имя игнорируется.
- ApiClient.kt:275 — SMS-вход не регистрирует push-токен → пуши не приходят до перезапуска.
- FcmService.kt:27 — тумблеры «Уведомления/Звуки» не работают в фоне (сервер шлёт notification-only, не data). FIX: data-only payload, клиент рендерит сам.

### Backend — гонки без row-lock
- requests.py:352 `accept_response` (двойной матч → 2 Ride+Booking на одну заявку), bookings.py:227 двойная отмена (овербукинг), security.py:73 rotate_refresh (TOCTOU), ads.py:326 двойной pending-платёж.

### Backend — валидация ввода (Pydantic без границ)
- schemas.py:16 RideIn (seats/price/города/координаты), ads.py:219 AdCreateIn, drivers.py:99 DriverProfileIn, auth.py:56 PhoneIn (формат+SMS-бомба суточный кеп), safety.py:32 category, rides.py depart_at.

### Backend — прод-гварды / платежи
- config.py:8 — гварды держатся на честном `ENV=prod` (иначе dev-secret/CORS*/mock). config.py:65 `seed_demo=True` не проверяется → фейк-водители в пустом проде.
- security.py:128 — OTP на `random` (Mersenne), не `secrets`.
- payments.py:92 — `fetch_payment` вне yookassa всегда `{"status":"succeeded"}` → мина под публичным вебхуком (спасает только неугадываемость provider_id).
- auth.py:148 — секрет Telegram-вебхука опционален (fail-open): пустой секрет → поддельный callback подтверждает платёж/верифицирует водителя.
- driver_check.py:116/172 — фото авто не проверяется вообще; autoapprove обходится распечаткой (нет сверки ФИО) — риск спит (autoapprove=False).

### Backend — прочее P2
requests.py:380 match_rides течёт pickup (без public_ride_payload); family.py:83 trip-status SMS без троттла; rides.py:33 нет отмены/завершения Ride, прошедшие в выдаче; discovery.py:131 /geocode без auth (квота Яндекса); services.py:113 500 вместо 400 на не-dict JSON.

### Инфра / CI / web (P2)
- **CI**: нет `timeout-minutes` (зависший тест = 360мин лимита), нет `cache: pip`, нет `concurrency` (старые прогоны не отменяются). **Плюс `on: push`+`pull_request` = двойной прогон на каждый пуш в PR.**
- docker-compose.yml:26 — фолбэк `JWT_SECRET:-dev-secret-...` обходит validate_production (длиннее 16, не равен блэклисту).
- requirements.txt — все версии плавающие (невоспроизводимость; python-jose≥3.3 c CVE).
- deploy под root на захардкоженный IP; web/deploy.sh `StrictHostKeyChecking=no` (MITM).
- web: Метрика грузится ДО согласия (cookie-баннер декоративный) — 152-ФЗ. Нет security-заголовков (CSP/XFO/HSTS) в git.
- build.gradle.kts:46 — debug-URL cleartext http, а cleartext глобально off + нет network-security-config → debug-сборка не достучится до локального бэка.

*(полный список P2 — в отчётах аудиторов; здесь сгруппированы главные)*

---

## 🟢 P3 — мелочи (126, вынесено в план пакетами)

Классы: одноязычные строки (RU-only detail/push по всему API — системно; отдельные UI-строки :версия/инициалы/«друг»), тач-цели <48dp (звёзды/эмодзи/чипы — ~10 мест), keyless `items()` на изменяемых списках (~9), тёмная тема (CanonHairlineGreen/DangerBorder без dark-варианта, Color.White на кнопках карты), склонения («1 звёзд», «1 мест»), мёртвый код (demoTrustedContacts, fire*-обёртки ×5, мёртвые параметры composable), формат-мелочи (rating `%.1f`, координаты Locale.US), недо-полиш (YooKassa без поллинга статуса, deep-link пушей, пагинация чата). Полный перечень — в 9 отчётах, адресуется в WP-11.

---

## 📋 План исправления — по рабочим пакетам (1 пакет = 1 коммит/пуш)

> Экономия минут GitHub: пушим редко, крупными блоками. CI гоняет только backend-тесты, но триггерится на любой пуш — поэтому **WP-0 первым** (урезает расход вдвое + ставит timeout/кеш), дальше группируем.

| WP | Зона | Что входит | Приоритет | Риск |
|---|---|---|---|---|
| **WP-0** | ⚙️ CI | timeout-minutes:15, cache:pip, concurrency cancel-in-progress; `on: [pull_request]` без push-дубля | инфра | низкий, сразу экономит минуты |
| **WP-1** | 🔒 Backend security | P1-16 OTP one-time, P1-17 кеп контактов+валидация телефона, webhook fail-closed+constant-time, accept_response/refresh/cancel гонки (row-lock), seed_demo-гвард, fetch_payment→pending, OTP на secrets | P0/P1/P2 | средний — прод, тесты обяз. |
| **WP-2** | 🗄 Миграции | P1-18 alembic единый путь + deploy-скрипт; boarding_code колонка | P1 | **высокий (прод-схема)** — сверить перед прогоном |
| **WP-3** | 🧭 Навигация+данные | P1-2/3 Intro back-door, P1-4 reload после логина, P1-6/7 демо не бронируемы/звонимы при живом сервере (нечисловые id + безусловная замена), P1-8 activeTrip-гейт | P1 | средний — трогает YuldashApp |
| **WP-4** | 🆘 SOS+вход+приватность | P0-1 честный статус SMS, P1-5 тупик логина, P1-12 телефон близкого мимо публичного комментария, SOS гео-таймаут/Locale.US | P0/P1 | средний |
| **WP-5** | 💬 Бронь+чат | P1-9 confirm-флоу (клиент+кнопка водителю), P1-10 REST-чат broadcast, push после SMS-входа, onClosing в 3 сокетах | P1 | средний — клиент+бэк |
| **WP-6** | 🗺 Карты | P1-13/14/15 lifecycle-карта, дебаунс near, маршрут; P2 GPS+NETWORK, MapFeedSocket lifecycle, peer-очистка, NaN-гард | P1/P2 | средний |
| **WP-7** | 🎨 UI fire-and-forget | весь класс onFailure (модерация/оплаты/отклик/поддержка/уведомления/кабинеты/реферал) | P2 | низкий — шаблонно |
| **WP-8** | 🎨 UI плацебо/фейк | тумблер телефона (на сервер или убрать), табы уведомлений, «Пассажир·Баймаҡ»→реальные, бейдж «проверен», impression-дедуп, state по bookingId | P2 | низкий-средний |
| **WP-9** | 🔌 Backend валидация | Pydantic Field-границы (RideIn/AdCreateIn/DriverProfileIn/PhoneIn/category/depart_at); match_rides pickup; /geocode auth; rides lifecycle | P2 | низкий |
| **WP-10** | ⚙️ Инфра/web | compose JWT fail-fast, requirements пины, Метрика после согласия, security-заголовки nginx в git, deploy hardening, .gitignore края, debug cleartext-config | P2 | низкий |
| **WP-11** | 🧹 P3 полиш | одноязычные строки (черновики BA→tasks.md), тач-цели 48dp, keyless keys, тёмная тема Canon*, склонения, мёртвый код, формат-мелочи | P3 | низкий |

**Порядок:** WP-0 (сразу) → WP-1/WP-2 (безопасность+схема, до пользователей) → WP-3/4/5 (P1-блокеры продукта) → WP-6 → WP-7/8/9/10 (P2) → WP-11 (P3).
Сборка Android (`assembleDebug`) и прогон на устройстве — за Александром после каждого UI-пакета (в облаке нет SDK). Backend-тесты гоняет CI.
