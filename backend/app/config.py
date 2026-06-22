from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Настройки берутся из .env (см .env.example)."""
    env: str = "dev"
    database_url: str = "sqlite:///./yuldash.db"
    jwt_secret: str = "dev-secret-change-me"
    jwt_expire_min: int = 60 * 24 * 30  # 30 дней
    otp_ttl_sec: int = 300              # код жив 5 минут
    sms_provider: str = "mock"          # mock | smsru
    sms_ru_api_id: str = ""             # api_id из кабинета sms.ru (нужен для sms_provider=smsru)

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
