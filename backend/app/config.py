from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_JWT_SECRET = "dev-secret-change-me"


class Settings(BaseSettings):
    """Настройки берутся из .env (см .env.example)."""
    env: str = "dev"
    database_url: str = "sqlite:///./yuldash.db"
    jwt_secret: str = DEFAULT_JWT_SECRET
    jwt_expire_min: int = 60 * 24 * 30  # 30 дней (legacy-дефолт; access ниже короче)
    access_expire_min: int = 60 * 12   # access-токен живёт 12ч (было 7 дней — ужали окно кражи ~14x; refresh молча обновляет)
    refresh_expire_days: int = 90          # refresh-токен живёт 90 дней (ротируется при каждом refresh)
    otp_ttl_sec: int = 300              # код жив 5 минут
    sms_provider: str = "mock"          # mock | smsru | smsdar
    sms_ru_api_id: str = ""             # api_id из кабинета sms.ru (нужен для sms_provider=smsru)
    sms_from: str = ""                  # буквенный отправитель sms.ru после модерации (напр. Yuldash)
    # --- SMSDAR (go.smsdar.ru, API api.zmtech.ru) — нужен sms_provider=smsdar ---
    smsdar_id: str = ""                 # ID из Профиль → Получить API ключ
    smsdar_password: str = ""           # API-ключ (password). НЕ в git — только в .env
    smsdar_sender: str = ""             # одобренный брендовый отправитель (из sms_senders, напр. Yulbash)
    yandex_geocoder_key: str = ""       # ключ Яндекс.Геокодера НА СЕРВЕРЕ (клиент ходит на /geocode, ключ не в APK)

    # --- Авто-проверка водителей (OCR прав) ---
    # Снижает ручную работу: при отправке документов сервер сам читает права и
    # помечает заявку. Локальные гейты (есть ли текст/срок) бесплатны; OCR — Yandex
    # Vision (~0,13 ₽/фото). Человек остаётся финальной кнопкой (autoapprove по умолч. ВЫКЛ).
    driver_autocheck_enabled: bool = True    # запускать авто-проверку при /driver/verify
    driver_autoreject_enabled: bool = True   # авто-отказ ТОЛЬКО на явный мусор (пустое/нечитаемое фото)
    driver_autoapprove_enabled: bool = False # авто-одобрение при высокой уверенности (ВЫКЛ — решает админ)
    driver_autocheck_min_score: float = 0.75 # порог уверенности для авто-одобрения (0..1)
    yandex_vision_key: str = ""              # API-ключ Yandex Vision OCR. НЕ в git — в .env. Пусто → OCR выкл, всё к человеку.
    yandex_vision_folder_id: str = ""        # ОПЦИОНАЛЬНО: нужен для ключей сервис-аккаунта; ключ AI Studio работает без него (проверено вживую)

    # --- Telegram-вход (бот) ---
    telegram_bot_token: str = ""        # токен бота от @BotFather (вебхук + sendMessage)
    telegram_webhook_secret: str = ""   # секрет: аутентификация Telegram→сервер (заголовок X-Telegram-Bot-Api-Secret-Token)
    admin_telegram_chat_id: str = ""    # chat_id админа (Александр) для уведомлений: запрос звонка и пр.
    admin_phones: str = ""              # телефоны админов через запятую (автоадмин при входе по этому номеру)

    # --- Push (FCM) ---
    firebase_credentials: str = ""      # путь к JSON сервисного аккаунта Firebase (для отправки пушей). Пусто → push выключен.

    # --- Платежи (самозанятый: монетизация СВОИХ услуг — Boost/реклама) ---
    # mock — платёж сразу «оплачен» (только dev).
    # sbp_manual — перевод по СБП на номер, активирует админ вручную (интерим до ЮKassa).
    # yookassa — авто-приём + авто-чек (нужны ключи).
    payments_provider: str = "mock"
    yookassa_shop_id: str = ""          # shopId из кабинета ЮKassa (для самозанятых). НЕ в git — в .env.
    yookassa_secret_key: str = ""       # секретный ключ ЮKassa (Basic-auth). НЕ в git — в .env.
    payment_return_url: str = "https://yulbash.ru/pay/done"  # куда ЮKassa вернёт пользователя после оплаты
    # --- СБП-перевод по номеру (интерим, payments_provider=sbp_manual). Перс.данные — НЕ в git, в .env. ---
    sbp_phone: str = ""                 # номер для перевода по СБП (получатель). Напр. +79991234567
    sbp_bank: str = ""                  # банк получателя (напр. Сбербанк)
    sbp_name: str = ""                  # имя получателя как в СБП (напр. Александр А.)

    # --- Комиссия сервиса за поездку (Фаза 3, деньги v1). УТОЧНИТ АЛЕКСАНДР ---
    # Процент, который платформа удерживает с оплаты поездки. При безналичной оплате
    # (ЮKassa) в ledger водителя пишутся earn (+вся сумма) и fee (−комиссия). Баланс = earn − fee.
    # 8% — втрое ниже Яндекса (~24–30%); меняется без пересборки (правится в .env / конфиге).
    # Финальный процент и оферту утверждает Александр (юр.шляпа). Наличные комиссией не облагаются.
    service_fee_percent: float = 8.0

    # --- Выплаты водителям (Модель Б, Фаза 3 v2). ПО УМОЛЧАНИЮ ВЫКЛЮЧЕНО ---
    # Модель Б: деньги пассажира идут ЧЕРЕЗ платформу (бизнес-ЮKassa Александра), платформа
    # берёт комиссию, остальное — выплата водителю на карту (ЮKassa Payout API).
    # Это ГОТОВНОСТЬ: код полный, но режим недоступен, пока Александр не оформит ИП +
    # бизнес-ЮKassa + ключи выплат и не выставит PAYOUTS_ENABLED=true.
    # Выключено → эндпоинт вывода отвечает «Выплаты скоро», Модель А (наличные/перевод) работает.
    payouts_enabled: bool = False
    yookassa_payout_agent_id: str = ""   # agentId «Выплат» ЮKassa (отдельный продукт). НЕ в git — в .env.
    yookassa_payout_secret_key: str = "" # секретный ключ выплат ЮKassa (Basic-auth). НЕ в git — в .env.
    payout_min_kop: int = 10000          # минимальный вывод: 100 ₽ (защита от копеечных выплат/комиссий)
    payout_max_kop: int = 15_000_000     # максимальный вывод за раз: 150 000 ₽ (защита от опечатки/фрода)

    # --- Комиссия «лесенкой» 3% → 5% → 8% (волна 2, §5 Деньги). Все цифры — конфиг ---
    # Стаж таксиста считаем от ПЕРВОГО его завершённого (done) быстрого заказа:
    # первые fee_tier1_days дней → fee_tier1_percent; до fee_tier2_days → fee_tier2_percent;
    # дальше — service_fee_percent (8%, навсегда). Ниже всех: Яндекс 22–30%.
    fee_tier1_percent: float = 3.0     # 1-й месяц таксиста
    fee_tier2_percent: float = 5.0     # 2-й месяц
    fee_tier1_days: int = 30           # граница 1-й ступени (дней стажа, включительно)
    fee_tier2_days: int = 60           # граница 2-й ступени (дней стажа, включительно)
    # Промо запуска «первым водителям — 0% на 3 месяца»: водитель, чья заявка таксиста
    # одобрена ДО launch_promo_until (ISO-дата, напр. 2026-09-01), платит launch_promo_percent
    # первые launch_promo_days дней от одобрения. Пустая дата → промо ВЫКЛЮЧЕНО (по умолчанию).
    launch_promo_percent: float = 0.0
    launch_promo_until: str = ""       # ISO-дата окончания набора в промо; "" = промо выкл
    launch_promo_days: int = 90        # сколько дней промо-процент действует для попавшего

    # --- Сурж (честная наценка в час пик, волна 2, §5). Потолок ×1.5, всё прозрачно ДО заказа ---
    # k считаем по городу заказа: спрос = searching/created заказы за surge_window_min минут
    # в радиусе surge_radius_km; предложение = живые водители рядом (Redis GEO). Ступени по
    # спрос/предложение: <1 → 1.0; ≥1 → 1.1; ≥1.5 → 1.2; ≥2 → 1.3; ≥3 → 1.5. Без Redis → 1.0.
    # На ПОПУТКЕ суржа НЕТ. Статичный Tariff.k остаётся аварийным множителем (по умолчанию 1.0).
    surge_enabled: bool = True
    surge_max_k: float = 1.5           # потолок наценки (обещание пользователям)
    surge_window_min: int = 10         # окно спроса, минут
    surge_radius_km: float = 7.0       # радиус «города заказа», км

    # --- Отмены / ожидание / «пассажир не вышел» (структура как Яндекс, Модель А = страйки) ---
    # Деньги НЕ двигаем (Модель А «на доверии»): платная отмена/no-show фиксируется на заказе
    # (cancel_fee_kop = подача) и даёт пассажиру страйк; ≥strike_limit страйков за
    # strike_window_days → пауза такси-заказов strike_pause_hours. ПОПУТКА не затрагивается.
    cancel_free_minutes: int = 3       # бесплатная отмена N минут после принятия водителем
    wait_free_minutes: int = 5         # бесплатное ожидание после «Я на месте», минут
    wait_fee_rub_per_min: int = 5      # платное ожидание, ₽/мин (Яндекс +9)
    no_show_extra_minutes: int = 3     # сверх бесплатного ожидания до кнопки «пассажир не вышел»
    strike_limit: int = 3              # страйков за окно → пауза
    strike_window_days: int = 7        # окно подсчёта страйков, дней
    strike_pause_hours: int = 24       # длительность паузы такси-заказов, часов

    # --- Качество: жалобы + лестница наказаний (волна 2, §9). Все цифры — конфиг ---
    # Честно/прозрачно/анонимно; человек в контуре (разбор у админа). Такси наказываем строго,
    # ПОПУТКА мягче (пауза — только такси), SOS/безопасность — железно (тяжёлая категория →
    # мгновенный Telegram админу + авто-пауза такси до разбора).
    quality_advice_rating: float = 4.8       # 🟡 ниже → мягкий пуш-совет (без наказания)
    quality_advice_interval_days: int = 7    # дедуп совета: не чаще раза в неделю
    matcher_low_rating: float = 4.6          # 🟠 ниже → штраф к score в matcher (реже заказы)
    matcher_penalty_low_rating: float = 1.0  # величина штрафа к score
    quality_pause_reports: int = 3           # 🔴 resolved-жалоб за окно → авто-пауза такси
    quality_window_days: int = 30            # окно подсчёта resolved-жалоб, дней
    quality_pause_hours: int = 72            # длительность авто-паузы такси, часов

    # --- Долг по комиссии за ТАКСИ (Модель А «на доверии», Фаза 3). УТОЧНИТ АЛЕКСАНДР ---
    # За завершённый такси-заказ (instant) водитель получил деньги напрямую (нал / прямой СБП),
    # а комиссию 8% ДОЛЖЕН платформе. Раз в неделю водитель переводит долг Александру по СБП и
    # жмёт «Я оплатил»; Александр подтверждает. Не оплатил в срок → режим ТАКСИ блокируется
    # (ПОПУТКА — плановые поездки — НЕ блокируется). Реквизиты СБП — из .env, НЕ хардкод.
    owner_sbp_phone: str = ""              # номер Александра для перевода долга по СБП (из .env)
    owner_sbp_name: str = ""               # имя получателя как в СБП (напр. «Александр А.»)
    debt_due_days: int = 7                 # срок оплаты долга с момента начисления (дней)
    debt_block_threshold_kop: int = 100000  # порог блокировки такси: 1000 ₽ долга (в копейках)

    # --- 8-часовой лимит + отдых водителя (волна 2, §8). Только ТАКСИ-время (попутка не считается) ---
    # На линии ≥ taxi_shift_limit_hours за местный день → такси-гейт (presence/offer/accept) до
    # разблокировки: следующий день И ≥ rest_unlock_hour местного И ≥ rest_hours от последнего
    # heartbeat дня лимита. Активный заказ НЕ рубим — даём довезти. Часовой пояс Уфы UTC+5.
    taxi_shift_limit_hours: int = 8        # лимит смены такси, часов
    rest_hours: int = 8                    # минимальный отдых от последнего heartbeat, часов
    rest_unlock_hour: int = 6              # разблокировка не раньше N:00 местного следующего дня
    local_tz_offset_hours: int = 5         # локальный пояс (Уфа = UTC+5)
    workday_step_cap_sec: int = 60         # кэп шага учёта на один heartbeat (редкие пинги не накручивают)

    # --- Гейт такси (волна 2, 580-ФЗ). ВКЛЮЧАЕТ АЛЕКСАНДР после оформления документов ---
    # Мастер-флаг режима такси. False → такси «Скоро» ВЕЗДЕ (пассажиру и водителю),
    # ПОПУТКА (плановые Ride/Booking) не затрагивается. True → доступность решает список
    # городов TaxiCity: таблица пуста → такси включено везде; есть записи → только
    # перечисленные города (enabled=True). Радиус привязки к городу — taxi_city_radius_km.
    taxi_enabled: bool = False
    taxi_city_radius_km: float = 30.0      # ближе N км до известного города → считаем «в городе»

    # --- Профиль «Курьер» (C1). Мастер-флаг режима курьера (как taxi_enabled) ---
    # False → «Курьер скоро» ВЕЗДЕ (заказ курьера/выход на линию/приём courier-заказов),
    # доставка «по пути» (M3 poputka) НЕ затрагивается. True → режим включён.
    # Тарифы/комиссия/потолок наложки правятся в коде роутера (COURIER_* в routers/courier.py).
    courier_enabled: bool = False

    # --- «Быстрый заказ» (такси-режим, Фаза 2) ---
    # Тариф считает СЕРВЕР. Клиенту не верим: haversine × road_k → дорожная дистанция.
    instant_road_k: float = 1.3            # прямая → примерная длина по дорогам
    instant_avg_speed_kmh: float = 40.0    # средняя скорость для оценки времени в пути
    instant_intercity_km: float = 40.0     # дистанция выше порога → зона «межгород»
    instant_offer_ttl_sec: int = 20        # сколько водителю думать над оффером (таймер карточки)
    presence_ttl_sec: int = 60             # heartbeat координат водителя жив N сек (Redis TTL)
    # Скоринг кандидатов matcher: чем ближе подача и выше рейтинг — тем выше в очереди.
    instant_w_dist: float = 1.0            # вес близости подачи (1/дистанция)
    instant_w_rating: float = 0.4          # вес рейтинга водителя
    instant_max_offers: int = 8            # предохранитель: максимум офферов на один заказ
    # Предзаказ «на время» (MVP): горизонт бронирования вперёд. Дальше 7 суток не принимаем.
    scheduled_max_days: int = 7            # максимум на сколько вперёд можно оформить предзаказ
    # --- Экран «Мой Юлдаш» (личная статистика попутчика) ---
    # Коэффициенты — разумная прикидка для оценки экономии и эко-эффекта.
    # ⚠️ УТОЧНИТ АЛЕКСАНДР (реальный тариф такси в Башкортостане + выброс авто).
    stats_taxi_rub_per_km: float = 22.0     # ориентир стоимости такси, ₽/км (эконом, город/трасса; уточнит Александр)
    stats_co2_grams_per_km: float = 170.0   # средний выброс легкового авто, г CO₂/км (уточнит Александр)

    # --- Force-update (B9b-1): минимальная поддерживаемая версия приложения ---
    # versionCode клиента < min_app_version_code → клиент показывает блокирующий экран
    # «Обнови Юлдаш» с кнопкой в стор. 0 = проверка ВЫКЛЮЧЕНА (по умолчанию).
    # Включается без пересборки: правится в .env на сервере.
    min_app_version_code: int = 0
    app_store_url: str = ""            # ссылка на стор (RuStore/Google Play) для кнопки «Обновить»

    # --- Дневная сводка админу в Telegram (B9b-3) ---
    # Без внешнего cron: первый запрос ПОСЛЕ daily_digest_hour местного времени (Уфа, UTC+5)
    # запускает отправку; «сегодня уже отправлено» — строка-замок в БД (UNIQUE(day) решает
    # гонку воркеров) + память процесса, чтобы не дёргать БД на каждом запросе.
    daily_digest_enabled: bool = True
    daily_digest_hour: int = 21        # местный час, после которого шлём сводку за день

    # --- Тестовый аккаунт для модерации сторов (B9b-4) ---
    # Google Play / RuStore просят «тестовый логин»: ревьюер входит review_phone + review_code
    # (реальная SMS НЕ шлётся, любой другой код для этого номера НЕ работает). Аккаунт помечен
    # is_reviewer — обычный пассажир без прав. Работает ТОЛЬКО когда заданы ОБА значения.
    # Секреты — только в .env (НЕ в git); код нигде не логируем и не отдаём в ответах.
    review_phone: str = ""
    review_code: str = ""

    # --- «Скидки по пути» (M1: партнёрский слой + купоны) ---
    # Мастер-флаг раздела. False → витрина купонов и кабинет партнёра отдают «скоро»/пусто.
    coupons_enabled: bool = True

    # --- Redis (масштаб) ---
    # Один URL на всё: общий rate-limit между воркерами + WS-чат pub/sub между процессами.
    # Пусто → rate-limit in-memory на воркер, WS — локальный режим (один воркер). Пример: redis://127.0.0.1:6379/0
    redis_url: str = ""

    # --- Наблюдаемость (Sentry + алерты) ---
    # Sentry: сбор ошибок/трейсбеков. Пусто → полный no-op (ничего не инициализируется и не шлётся).
    # DSN — секрет, только из env, НЕ в git. Пример: https://<key>@o0.ingest.sentry.io/0
    sentry_dsn: str = ""
    sentry_traces_sample_rate: float = 0.0   # доля трейсов производительности (0 → только ошибки, дёшево)
    # Алерт в Telegram при всплеске серверных ошибок (5xx). Простой счётчик в окне + порог.
    error_alert_threshold: int = 10          # сколько 5xx в окне, чтобы отправить один алерт
    error_alert_window_sec: int = 300        # окно наблюдения (сек)
    error_alert_cooldown_sec: int = 900      # не чаще одного алерта раз в N сек (анти-спам)

    # --- Прод-параметры ---
    media_base_url: str = "https://yulbash.ru"   # база для публичных URL медиа (фото/голос)
    public_base_url: str = "https://yulbash.ru"  # база публичных ссылок (live-ссылка /t/{token} в SMS близкому)
    cors_origins: str = "*"                       # список origin через запятую; в проде сузить
    seed_demo: bool = True                        # демо-поездки в пустой БД (в проде выкл.)

    # --- Анти-абуз / защита (важно перед публичным запуском) ---
    rate_limit_enabled: bool = True               # глобальный лимит запросов на IP
    rate_limit_per_min: int = 300                 # запросов/мин с одного IP (обычный клиент << этого)
    rate_limit_auth_per_min: int = 20             # отдельный, строгий лимит на /auth/* и /sos (анти-перебор/спам)
    max_upload_mb: int = 10                        # лимит размера загрузки (фото/аудио)
    max_uploads_per_day: int = 60                  # лимит загрузок на юзера в сутки (анти disk-fill / спам)
    allowed_image_ext: str = "jpg,jpeg,png,webp"  # разрешённые расширения фото
    allowed_audio_ext: str = "m4a,mp3,ogg,wav,aac"  # разрешённые расширения аудио

    # --- Хранилище медиа (фото/документы/голос): диск сервера или облако S3 ---
    # Пусто → авто: заданы бакет+ключи S3 → облако; иначе локальный диск (как раньше, полный фолбэк).
    # Явно: STORAGE_BACKEND=local|s3. Секреты S3 (ключи/бакет/endpoint) — только .env, НЕ в git.
    storage_backend: str = ""
    s3_endpoint_url: str = ""       # endpoint S3-совместимого хранилища (Timeweb/VK Cloud/Selectel). Пусто → AWS.
    s3_region: str = ""             # регион бакета (напр. ru-1). Опционально.
    s3_bucket: str = ""             # имя бакета. НЕ в git — в .env.
    s3_access_key: str = ""         # Access Key ID. НЕ в git — в .env.
    s3_secret_key: str = ""         # Secret Access Key. НЕ в git — в .env.
    s3_signed_url_ttl: int = 3600   # TTL подписанного (presigned) URL, сек (по умолч. 1 час)

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @property
    def is_prod(self) -> bool:
        return self.env.lower() in ("prod", "production")

    @property
    def payouts_ready(self) -> bool:
        """Реальные выплаты доступны? В dev достаточно флага (mock-выплата для теста/готовности);
        в проде обязателен ключ выплат ЮKassa — иначе «нажали кнопку, а денег нет»."""
        if not self.payouts_enabled:
            return False
        if self.is_prod:
            return bool(self.yookassa_payout_agent_id and self.yookassa_payout_secret_key)
        return True

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()] or ["*"]

    @property
    def storage_is_s3(self) -> bool:
        """Использовать облако S3? Явный STORAGE_BACKEND главнее; иначе авто по наличию ключей."""
        mode = (self.storage_backend or "").strip().lower()
        if mode == "s3":
            return True
        if mode in ("local", "disk", "file"):
            return False
        # авто: включаем S3, только если задан минимум для работы (бакет + оба ключа)
        return bool(self.s3_bucket and self.s3_access_key and self.s3_secret_key)

    @property
    def image_ext_set(self) -> set[str]:
        return {e.strip().lower() for e in self.allowed_image_ext.split(",") if e.strip()}

    @property
    def audio_ext_set(self) -> set[str]:
        return {e.strip().lower() for e in self.allowed_audio_ext.split(",") if e.strip()}

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024

    def validate_production(self) -> None:
        """Запрещаем запускать прод с небезопасными значениями по умолчанию."""
        if not self.is_prod:
            return
        problems: list[str] = []
        # Ловим не только точный дефолт, но и любой заведомо-dev секрет (напр. фолбэк из
        # docker-compose `dev-secret-...-1234` — он длиннее 16 и раньше проскакивал гвард).
        weak_secret = (
            self.jwt_secret == DEFAULT_JWT_SECRET
            or self.jwt_secret.startswith("dev-secret")
            or len(self.jwt_secret) < 16
        )
        if weak_secret:
            problems.append("JWT_SECRET должен быть задан, длинным (>=16) и не dev-дефолтом")
        # SMS — НЕобязателен: основной вход через мессенджеры (Telegram и т.п.).
        # SMS заморожен (sms_provider=mock) — это допустимо в проде. Оживить: SMS_PROVIDER=smsru + ключ.
        if self.sms_provider == "smsru" and not self.sms_ru_api_id:
            problems.append("SMS_RU_API_ID обязателен при SMS_PROVIDER=smsru")
        if self.sms_provider == "smsdar" and not (self.smsdar_id and self.smsdar_password and self.smsdar_sender):
            problems.append("SMSDAR_ID, SMSDAR_PASSWORD и SMSDAR_SENDER обязательны при SMS_PROVIDER=smsdar")
        # Telegram-бот включён, но вебхук без секрета → любой шлёт фейковые апдейты и выпускает себе код входа.
        if self.telegram_bot_token and not self.telegram_webhook_secret:
            problems.append("TELEGRAM_WEBHOOK_SECRET обязателен при заданном TELEGRAM_BOT_TOKEN")
        if self.cors_origins.strip() == "*":
            problems.append("CORS_ORIGINS не должен быть '*' в проде")
        # seed_demo в проде насыпает фейковых водителей (+7000000000X) как реальные аккаунты в пустую БД.
        if self.seed_demo:
            problems.append("SEED_DEMO должен быть выключен в проде (фейковые водители в реальной БД)")
        # mock-платежи в проде = «оплата» без денег. Включён реальный приём → ключи/реквизиты обязательны.
        if self.payments_provider == "yookassa" and not (self.yookassa_shop_id and self.yookassa_secret_key):
            problems.append("YOOKASSA_SHOP_ID и YOOKASSA_SECRET_KEY обязательны при PAYMENTS_PROVIDER=yookassa")
        if self.payments_provider == "sbp_manual" and not self.sbp_phone:
            problems.append("SBP_PHONE обязателен при PAYMENTS_PROVIDER=sbp_manual")
        # Выплаты включены в проде без ключей выплат ЮKassa = «нажали вывод, а денег нет».
        if self.payouts_enabled and not (self.yookassa_payout_agent_id and self.yookassa_payout_secret_key):
            problems.append("YOOKASSA_PAYOUT_AGENT_ID и YOOKASSA_PAYOUT_SECRET_KEY обязательны при PAYOUTS_ENABLED=true")
        # Авто-одобрять водителей без OCR нельзя — это пустит непроверенных. Нужен ключ Vision.
        if self.driver_autoapprove_enabled and not self.yandex_vision_key:
            problems.append("YANDEX_VISION_KEY обязателен при DRIVER_AUTOAPPROVE_ENABLED (нельзя авто-одобрять без OCR)")
        # S3 включён явно, но без бакета/ключей — медиа некуда писать. Требуем полный набор.
        if (self.storage_backend or "").strip().lower() == "s3" and not (
            self.s3_bucket and self.s3_access_key and self.s3_secret_key
        ):
            problems.append("S3_BUCKET, S3_ACCESS_KEY и S3_SECRET_KEY обязательны при STORAGE_BACKEND=s3")
        if self.database_url.startswith("sqlite"):
            problems.append("DATABASE_URL не должен быть sqlite в проде")
        media_base = self.media_base_url.lower()
        if media_base.startswith("http://") or "localhost" in media_base or "127.0.0.1" in media_base:
            problems.append("MEDIA_BASE_URL должен быть публичным HTTPS URL в проде")
        if problems:
            raise RuntimeError("Небезопасная прод-конфигурация: " + "; ".join(problems))


settings = Settings()
