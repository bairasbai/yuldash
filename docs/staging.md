# 🧪 Staging-контур — «черновой сервер» для проверки перед боем

> Простыми словами: **staging** — это второй, точно такой же сервер приложения, но «понарошку».
> Туда сначала выкатываем новый код и щупаем, что ничего не сломалось. Только когда там всё
> хорошо — катим на **прод** (боевой `yulbash.ru`, которым пользуются люди). Так мы не роняем
> живых пользователей на непроверенном коде.

Зачем это Юлдашу: сейчас код едет сразу в бой. Один плохой деплой = приложение легло у всех.
Staging убирает этот риск — ошибку ловим на «черновике».

---

## Что такое staging из чего состоит (3 кусочка)

Всё живёт на **том же сервере** `85.239.52.55`, просто рядом с боевым, со своими именами:

| Кусочек | Прод (боевой) | Staging (черновой) |
|---|---|---|
| Код | `/opt/yuldash` | `/opt/yuldash-staging` |
| Сервис (uvicorn) | `yuldash-api` на порту `8000` | `yuldash-api-staging` на порту **`8100`** |
| База данных | `yuldash` | `yuldash_staging` (отдельная, пустая копия схемы) |
| Адрес | `https://yulbash.ru` | `https://staging.yulbash.ru` |

Ключевая мысль: **staging ничего не знает про боевую БД и боевых пользователей.** Своя база,
свой порт, свой поддомен. Сломать прод из staging нельзя.

---

## Как поднять staging — по шагам (делает Александр один раз)

Всё выполняется на сервере: `ssh root@85.239.52.55`.

### 1. Отдельная база данных
```bash
sudo -u postgres createdb yuldash_staging
# пользователь БД можно взять тот же, что у прода, или завести отдельного — на выбор
```

### 2. Копия кода как git-репозиторий
Staging деплоится через `git pull` (в отличие от прода, куда пока льём файлы вручную).
```bash
cd /opt
git clone https://github.com/<owner>/<repo>.git yuldash-staging
cd yuldash-staging
python3 -m venv .venv
./.venv/bin/pip install -r backend/requirements.txt
chown -R yuldash:yuldash /opt/yuldash-staging
```
> Если репозиторий приватный — настрой deploy-key или токен для `git pull` (как для любого CI).

### 3. Свой `.env` (НЕ из git — секреты)
Скопируй боевой и поменяй базу + пометь окружение:
```bash
cp /opt/yuldash/.env /opt/yuldash-staging/.env
# в /opt/yuldash-staging/.env:
#   env=staging
#   DATABASE_URL=postgresql://.../yuldash_staging   ← своя база!
```

### 4. systemd-сервис на порту 8100
Создай `/etc/systemd/system/yuldash-api-staging.service` (копия боевого, меняем каталог и порт):
```ini
[Unit]
Description=Yuldash API (staging)
After=network.target postgresql.service

[Service]
User=yuldash
WorkingDirectory=/opt/yuldash-staging/backend
ExecStart=/opt/yuldash-staging/.venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8100
Restart=always

[Install]
WantedBy=multi-user.target
```
Включить:
```bash
systemctl daemon-reload
systemctl enable --now yuldash-api-staging
```

### 5. Первый прогон миграций и проверка
```bash
cd /opt/yuldash-staging/backend
sudo -u yuldash ../.venv/bin/alembic upgrade head
curl -s http://127.0.0.1:8100/health     # ждём {"status":"ok",...}
```

### 6. Поддомен `staging.yulbash.ru` (чтобы заходить снаружи)
- В панели регистратора (Timeweb) добавь A-запись `staging.yulbash.ru → 85.239.52.55`.
- В nginx добавь server-блок для `staging.yulbash.ru`, проксирующий на `127.0.0.1:8100`
  (по образцу боевого `@api`, только порт 8100).
- SSL: `certbot --nginx -d staging.yulbash.ru`.
- Проверка: `curl https://staging.yulbash.ru/health`.

---

## Как катить на staging (каждый раз — одна команда)

Скрипт `backend/ops/deploy.sh` уже настроен на staging по умолчанию:
```bash
ssh root@85.239.52.55
cd /opt/yuldash-staging/backend
APP_DIR=/opt/yuldash-staging SERVICE=yuldash-api-staging \
HEALTH_URL=http://127.0.0.1:8100/health BRANCH=main bash ops/deploy.sh
```
Скрипт сам: подтянет код → накатит миграции → перезапустит → проверит `/health`.
**Если не поднялось — автоматически откатит код назад** и (если настроен Telegram-бот) пришлёт алерт.

Пощупал на `https://staging.yulbash.ru`, всё ок → тогда катишь на прод (с осторожностью):
```bash
APP_DIR=/opt/yuldash SERVICE=yuldash-api \
HEALTH_URL=http://127.0.0.1:8000/health BRANCH=main bash ops/deploy.sh
```
> Перед прод-деплоем всегда делай бэкап: `ssh root@85.239.52.55 "/opt/yuldash/backup-db.sh"`.

---

## Частые вопросы

- **Staging обязателен?** Нет, приложение работает и без него. Но это дешёвая страховка от
  «уронил прод на кривом деплое». Рекомендуется до того, как пользователей станет много.
- **Данные в staging настоящие?** Нет и не должны быть. База пустая/тестовая. Реальные телефоны
  и геолокацию туда не заливаем (приватность, §8 CLAUDE.md).
- **Сколько это ест ресурсов?** Немного — второй лёгкий uvicorn + маленькая БД. На том же сервере.
