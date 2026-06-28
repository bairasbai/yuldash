from pydantic_settings import BaseSettings, SettingsConfigDict

DEFAULT_JWT_SECRET = "dev-secret-change-me"


class Settings(BaseSettings):
    """Настройки берутся из .env (см .env.example)."""
    env: str = "dev"
    database_url: str = "sqlite:///./yuldash.db"
    jwt_secret: str = DEFAULT_JWT_SECRET
    jwt_expire_min: int = 60 * 24 * 30  # 30 дней (legacy-дефолт; access ниже короче)
    access_expire_min: int = 60 * 24 * 7   # access-токен живёт 7 дней (refresh обновляет)
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

    # --- Telegram-вход (бот) ---
    telegram_bot_token: str = ""        # токен бота от @BotFather (вебхук + sendMessage)
    telegram_webhook_secret: str = ""   # секрет: аутентификация Telegram→сервер (заголовок X-Telegram-Bot-Api-Secret-Token)

    # --- Push (FCM) ---
    firebase_credentials: str = ""      # путь к JSON сервисного аккаунта Firebase (для отправки пушей). Пусто → push выключен.

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
        if self.database_url.startswith("sqlite"):
            problems.append("DATABASE_URL не должен быть sqlite в проде")
        media_base = self.media_base_url.lower()
        if media_base.startswith("http://") or "localhost" in media_base or "127.0.0.1" in media_base:
            problems.append("MEDIA_BASE_URL должен быть публичным HTTPS URL в проде")
        if problems:
            raise RuntimeError("Небезопасная прод-конфигурация: " + "; ".join(problems))


settings = Settings()
