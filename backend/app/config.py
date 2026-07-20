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

    # --- Авто-подбор водителя для «помощь»-заявок (пассажир БЕЗ приложения) ---
    # Обычную заявку пассажир закрывает сам в приложении. Заявку от человека без приложения
    # (по звонку/за пожилого) раньше принимал админ вручную — теперь система сама берёт ЛУЧШИЙ
    # отклик (рейтинг→цена) спустя короткую паузу. Работает через systemd-таймер (app/automatch.py).
    automatch_enabled: bool = True           # включить авто-подбор (заявки с приложением НЕ трогает)
    automatch_grace_sec: int = 120           # пауза после первого отклика — дать откликнуться другим, выбрать лучшего
    # Напоминание оценить поездку: фоновая задача (app/rate_reminder.py, systemd-таймер ~раз в 30 мин)
    # шлёт двуязычный push «оцените поездку» участникам завершённой брони, кто ещё не оценил (один раз).
    rate_reminder_enabled: bool = True

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

    # --- Redis (масштаб) ---
    # Один URL на всё: общий rate-limit между воркерами + WS-чат pub/sub между процессами.
    # Пусто → rate-limit in-memory на воркер, WS — локальный режим (один воркер). Пример: redis://127.0.0.1:6379/0
    redis_url: str = ""

    # --- Прод-параметры ---
    media_base_url: str = "https://yulbash.ru"   # база для публичных URL медиа (фото/голос)
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

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @property
    def is_prod(self) -> bool:
        return self.env.lower() in ("prod", "production")

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()] or ["*"]

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
        if self.jwt_secret == DEFAULT_JWT_SECRET or len(self.jwt_secret) < 16:
            problems.append("JWT_SECRET должен быть задан и быть длинным (>=16 символов)")
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
        # mock-платежи в проде = «оплата» без денег. Включён реальный приём → ключи/реквизиты обязательны.
        if self.payments_provider == "yookassa" and not (self.yookassa_shop_id and self.yookassa_secret_key):
            problems.append("YOOKASSA_SHOP_ID и YOOKASSA_SECRET_KEY обязательны при PAYMENTS_PROVIDER=yookassa")
        if self.payments_provider == "sbp_manual" and not self.sbp_phone:
            problems.append("SBP_PHONE обязателен при PAYMENTS_PROVIDER=sbp_manual")
        # Авто-одобрять водителей без OCR нельзя — это пустит непроверенных. Нужен ключ Vision.
        if self.driver_autoapprove_enabled and not self.yandex_vision_key:
            problems.append("YANDEX_VISION_KEY обязателен при DRIVER_AUTOAPPROVE_ENABLED (нельзя авто-одобрять без OCR)")
        if self.database_url.startswith("sqlite"):
            problems.append("DATABASE_URL не должен быть sqlite в проде")
        media_base = self.media_base_url.lower()
        if media_base.startswith("http://") or "localhost" in media_base or "127.0.0.1" in media_base:
            problems.append("MEDIA_BASE_URL должен быть публичным HTTPS URL в проде")
        if problems:
            raise RuntimeError("Небезопасная прод-конфигурация: " + "; ".join(problems))


settings = Settings()
