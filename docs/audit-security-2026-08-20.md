# Аудит безопасности — 2026-08-20 (strix + ручной разбор)

**Ветка:** `claude/sweet-elbakyan-1adbc9` (worktree `remote-control-9c2189`)
**Метод:** попытка динамического пентеста **strix** (self-hosted CLI, Docker-песочница, OpenAI `gpt-5.4`) + **ручной security-разбор кода** (backend + android) через роль `yuldash-security-privacy`.
**Итог:** критичных и high-дыр НЕ найдено. Код зрелый, защита единообразная (backend и android). Внесены мелкие правки, часть — вне кода / отложено.

---

## Backend (FastAPI)

Проверено: IDOR/доступ, авторизация/сессии, секреты, инъекции, CORS/утечки/логи.

- **Критичного/high — нет.** IDOR закрыт везде (owner-check `booking_and_ride_for_user` и аналоги), JWT HS256 с `require_exp/sub`, refresh атомарный (закрыт TOCTOU), OTP с лимитами (5/код, 3/мин, IP 20/мин), секреты из env, `.gitignore` покрывает ключи, SQL только через ORM, CORS без `*`+credentials + прод-гвард `validate_production`, логи без ПДн.
- **[low] ИСПРАВЛЕНО:** `backend/.env.example` предлагал `ACCESS_EXPIRE_MIN=10080` (7 дней) при коде 12ч (`config.py:19`) → вернул на `720`.
- **Хрупкие места (следить при доработке):**
  1. Автоадмин по номеру телефона + номер СБП виден в ответе оплаты — совпадение = чужой номер как ключ к админке (сейчас прикрыто гвардом `SBP≠ADMIN` + учётом кода страны).
  2. Приватные файлы (доки водителя `secure_doc`, evidence инцидентов) — владение по префиксу имени файла; имя присваивает **только сервер** — держать инвариант.
  3. Граница истории чата при смене курьера (`parcel.accepted_at`) — был инцидент 2026-08-07; перепроверять при правках жизненного цикла посылки.

## Android (Kotlin / Jetpack Compose)

Проверено: секреты, хранение токена, TLS, логи, манифест, WebView, гео/буфер.

- **Критичного/high — нет.** Токен в `EncryptedSharedPreferences`+Keystore (AES256_GCM), HTTPS-only без обхода TLS (0 совпадений `TrustManager/HostnameVerifier/trustAll`), **0** логов с ПДн, Sentry PII-скрабинг, `allowBackup=false`, exported только лаунчер, WebView нет, GPS только при активной поездке, `FLAG_SECURE`, sensitive-clipboard.
- **[low-med] ДЕЙСТВИЕ ВНЕ КОДА (Александр):** ключ Яндекс MapKit попадает в релизный APK (BuildConfig, `app/build.gradle.kts:175`) — ограничить в кабинете Яндекса по package `com.yuldash.app` + SHA релизной подписи. Без ограничения = med (чужой жжёт квоту).
- **[low] ИСПРАВЛЕНО:** deep-link принимал `http`+`https` для `yulbash.ru/r/` → убран `http` (`AndroidManifest.xml`).
- **[low] ИСПРАВЛЕНО:** cleartext-исключение (`10.0.2.2`/`localhost`) уезжало в релиз (общий конфиг) → вынесено в `app/src/debug/res/xml/network_security_config.xml`; релизный `main`-конфиг строгий (только HTTPS).
- **[low] ПРИНЯТО (не чиним):** fallback токена в открытые prefs при недоступном Keystore — осознанный компромисс (иначе вход невозможен) + сигнал в Sentry.

## Динамический пентест strix — НЕ завершён

- **Причина:** Kaspersky резал HTTPS-соединения strix → бесконечные повторы → исчерпан баланс OpenAI (`no credits`). Плюс Docker Desktop падал из-за повреждённых сокетов (`dockerInference`, AI Model Runner) — вылечено переименованием папки `run` + пауза Kaspersky.
- **Статус:** отложено до пополнения OpenAI. Запускать **с выключенным Kaspersky**, режим `standard`/`deep`, `--max-budget`.

## Открытые задачи

- [ ] **Александр:** ограничить ключ Яндекс MapKit в кабинете (package + SHA).
- [ ] Динамический strix-пентест `backend` (при кредитах OpenAI, Kaspersky off).
- [ ] (опц.) backend: явные `*Out`-схемы вместо возврата целых ORM-моделей (`/me`, `response_model=Booking/...`) — профилактика будущих утечек полей.

## Изменённые файлы (эта сессия)

- `backend/.env.example` — токен 7д → 12ч.
- `android/app/src/main/AndroidManifest.xml` — deep-link только https.
- `android/app/src/main/res/xml/network_security_config.xml` — релиз строгий.
- `android/app/src/debug/res/xml/network_security_config.xml` — **новый**, cleartext только для debug.
