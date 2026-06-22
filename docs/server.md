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
- Конфиг: `/opt/yuldash/.env` (`env=prod`, `jwt_secret` случайный, SQLite `yuldash.db`). **НЕ в git.**
- Сервис: systemd **`yuldash-api`** (uvicorn на `127.0.0.1:8000`, автозапуск при загрузке, `Restart=always`).
- nginx: проксь `:443`/`:80` → `:8000`. Конфиг `/etc/nginx/sites-available/yuldash`.
- **API публично (HTTPS): `https://85-239-52-55.sslip.io`** (HTTP → 301 на HTTPS). Проверка: `curl https://85-239-52-55.sslip.io/health` → `{"status":"ok","env":"prod"}`.
- SSL: Let's Encrypt через `certbot` (плагин nginx), имя — бесплатный `sslip.io` поверх IP. Авто-обновление: `certbot.timer`. Cert: `/etc/letsencrypt/live/85-239-52-55.sslip.io/`.

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
| Проверка здоровья | `curl https://85-239-52-55.sslip.io/health` |
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
- [x] **Подключить Android к API (CRUD-ядро)**: ✅ вход (SMS→JWT→автологин), ✅ поездки с сервера (`GET /rides`, карточки с водителем), ✅ заявки (`POST /requests` + `GET /requests/mine` — видны на вкладке «Заявка»), ✅ публикация поездки (`POST /rides` — проверено: БД 5→6), ✅ бронь (`POST /bookings` — проверено curl: booking id1, тот же fire-and-forget механизм). Клиент `data/ApiClient.kt` (HttpURLConnection, без зависимостей). **Важный фикс:** POST'ы — на долгоживущем scope `ApiClient` (fire-and-forget), иначе scope экрана отменялся при навигации и обрывал запрос. Бэкенд `/rides` дополнен `RideOut`+сид демо-поездок. Осталось на сервер: чат (сообщения по брони), семейные сценарии, SOS. Базовый URL: `https://85-239-52-55.sslip.io`.
- [ ] Реальный SMS-провайдер (сейчас OTP — мок, код пишется в лог сервера).
- [ ] БД: SQLite (Фаза 1) → Postgres при росте.
