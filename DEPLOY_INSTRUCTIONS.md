# 🚀 Развёртывание OAuth/WhatsApp на yulbash.ru

## 1️⃣ SSH на сервер

Открой PowerShell и выполни:

```powershell
ssh root@85.239.52.55
```

(Войдёшь без пароля по ключу.)

## 2️⃣ Применить миграции

На сервере выполни SQL прямо в psql:

```bash
psql -U yuldash_app -d yuldash_prod -c "
ALTER TABLE \"user\"
ADD COLUMN telegram_id VARCHAR(255) UNIQUE NULL,
ADD COLUMN vk_id VARCHAR(255) UNIQUE NULL;

CREATE INDEX idx_user_telegram_id ON \"user\"(telegram_id);
CREATE INDEX idx_user_vk_id ON \"user\"(vk_id);
"
```

Потом:

```bash
psql -U yuldash_app -d yuldash_prod -c "
ALTER TABLE \"user\"
ADD COLUMN whatsapp_verified BOOLEAN DEFAULT FALSE;

CREATE INDEX idx_user_whatsapp_verified ON \"user\"(whatsapp_verified);
"
```

## 3️⃣ Проверить схему

```bash
psql -U yuldash_app -d yuldash_prod -c "
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name='user' AND column_name IN ('telegram_id', 'vk_id', 'whatsapp_verified');
"
```

Должны увидеть 3 новые колонки.

## 4️⃣ Перезапустить API

```bash
systemctl restart yuldash-api
```

Проверь живой:

```bash
curl https://yulbash.ru/health
```

Должен ответить `{"status":"ok"}`.

---

## Что дальше

После миграций:
- ✅ БД готова для реальной авторизации
- ⏳ SMS провайдер (подключить sms.ru API)
- ⏳ Telegram webhook (настроить ботом)

Готов? Дай знать.
