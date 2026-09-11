# 🚀 Чек-лист выкатки на прод — сентябрь 2026 (один вечер за ноутбуком)

> Для Александра. Простыми словами, по шагам. Только русский — это ops, не UI.
> Что выкатываем: **всё, что накопилось в `main` с июля** — исправления аудита 6–9 сентября
> (`b945f6f8`, `dd1523b5`), волны августа, 128 миграций схемы. На проде (`yulbash.ru`) сейчас
> код июля: там живут баги доступа и денег, которые в репозитории уже починены
> (список — [audit-main-fix-order.md](audit-main-fix-order.md)).
>
> **Важно про GitHub:** аккаунт заблокирован, поэтому «умный» `backend/ops/deploy.sh`
> (он делает `git pull` на сервере) **не сработает**. Идём по пути «скопировать файлы»
> (`deploy-backend.bat`) + два ручных шага, которых в этом bat нет: библиотеки и ops-скрипты.

---

## 0. Перед началом (10 минут)

- [ ] **VPN включён.** Российские операторы режут SSH (ТСПУ), а 22.08 сервер с ноутбука не
  отвечал вовсе. Проверка связи:
  ```
  ssh root@85.239.52.55 "uptime && systemctl is-active yuldash-api && curl -s http://127.0.0.1:8000/health"
  ```
  Ждём `active` и `{"status":"ok","env":"prod","db":"ok"}`. Нет ответа → [server.md](server.md), раздел «Не достучаться до сервера».
- [ ] **Что сейчас на проде и не правил ли кто-то файлы прямо там** (в июле так проверяли — «drift-safe»):
  ```
  ssh root@85.239.52.55 "cd /opt/yuldash && git log -1 --format='%h %ad %s' --date=short; git status --short | head"
  ```
  Пусто в `git status` — хорошо. Есть изменённые файлы — **не затирать вслепую**: скачать
  (`scp root@85.239.52.55:/opt/yuldash/app/ФАЙЛ ./prod-copy-ФАЙЛ`) и сравнить с репозиторием.
  Ответ «not a git repository» — значит, код на сервер лился копированием. Тогда сними
  контрольные суммы: `ssh root@85.239.52.55 "cd /opt/yuldash && md5sum app/*.py app/routers/*.py"`
  и пришли вывод мне — сверю с репозиторием и скажу, есть ли на проде правки, которых нет в git.
- [ ] Свободное место на сервере: `ssh root@85.239.52.55 "df -h / | tail -1"` — нужно > 2 ГБ.

## 1. Бэкап базы (обязательно, 2 минуты)

```
ssh root@85.239.52.55 "/opt/yuldash/backup-db.sh && ls -lt /opt/yuldash/backups | head -3"
```
Ждём свежий `*.sql.gz` первой строкой. **Без этого шага дальше не идём** — миграций 128,
откат по одной невозможен, откат только из дампа.

Плюс копия кода на сервере на случай отката (bat её не делает):
```
ssh root@85.239.52.55 "cp -a /opt/yuldash/app /opt/yuldash/app.bak-$(date +%Y%m%d) && cp -a /opt/yuldash/alembic /opt/yuldash/alembic.bak-$(date +%Y%m%d)"
```

## 2. Библиотеки Python (то, чего нет в bat)

`requirements.txt` менялся 16.08 и 31.08 — на проде старые версии, новый код без них упадёт.
```
scp backend/requirements.txt root@85.239.52.55:/opt/yuldash/requirements.txt
ssh root@85.239.52.55 "cd /opt/yuldash && sudo -u yuldash ./.venv/bin/pip install -r requirements.txt 2>&1 | tail -5"
```
Ждём `Successfully installed …` или «already satisfied». Ошибка сборки какой-то библиотеки —
остановиться и прислать хвост вывода мне.

## 3. Код + миграции + рестарт — двойной клик `backend\deploy-backend.bat`

Он сам: копирует `app/`, `alembic/`, `alembic.ini` → `chown` → `alembic upgrade head` →
`systemctl restart yuldash-api` → `/health`. Смотреть на экран:
- `alembic upgrade head` должен пройти **без Traceback**. Упал — **ничего не перезапускать**,
  прислать вывод; база в это время не сломана (ревизии идемпотентны, см. [deploy-migrations.md](deploy-migrations.md)).
- В конце `{"status":"ok"}` дважды: с сервера и публично.

Проверка, что схема доехала:
```
ssh root@85.239.52.55 "cd /opt/yuldash && sudo -u yuldash ./.venv/bin/alembic current"
```
Ждём `bs_merge_heads_20260903 (head)` — ровно одна голова (у alembic раньше было две, 3.09 их слили).

## 4. Скрипты и таймеры ops (то, чего нет в bat)

Бэкапы с шифрованием, восстановление, ограждение фоновых задач — лежат в `backend/ops/`,
на прод не доезжали (см. tasks.md, «Что изменится на проде после выкатки»).
```
scp -r backend/ops backend/scripts root@85.239.52.55:/opt/yuldash/
ssh root@85.239.52.55 "chown -R yuldash:yuldash /opt/yuldash && chmod +x /opt/yuldash/ops/*.sh && systemctl daemon-reload"
```
Потом по [ops/README.md](../backend/ops/README.md): ключ `BACKUP_ENCRYPT_PASSPHRASE` в
`/opt/yuldash/.backup.env` (хранить в менеджере паролей, не на сервере), прогон
`ops/backup.sh` → `ops/restore-verify.sh` = `OK`.

## 5. Разовые действия после выкатки

- [ ] **Пересчёт рейтингов водителей** (замкнутый круг: штраф → нет заказов → некому оценить):
  ```
  ssh root@85.239.52.55 "cd /opt/yuldash && sudo -u yuldash ./.venv/bin/python -m scripts.recompute_driver_ratings"
  ```
  Это примерка — печатает, у кого что станет, ничего не меняет. Устроило → то же с `--apply`.
- [ ] `ls -l /opt/yuldash/.env` → должно быть `-rw-------` (секреты не читаются чужими).

## 6. Проверка, что живое (5 минут)

- [ ] `backend\verify-deploy.bat` — публичные ручки отвечают 200/401, не 404/500.
- [ ] Журнал без ошибок за последние минуты:
  ```
  ssh root@85.239.52.55 "journalctl -u yuldash-api --since '10 min ago' | grep -ciE 'traceback|error|critical'"
  ```
  Ждём `0`. Не ноль → `journalctl -u yuldash-api --since '10 min ago' | grep -iE 'traceback|error' -A5 | head -60` и прислать мне.
- [ ] С телефона: войти в приложение, открыть ленту, создать и отменить заявку, открыть чат.
  Выйти и войти снова — старый токен **не должен** работать после выхода (это BE01, главная дыра доступа).

## 7. Если что-то пошло не так — откат (5 минут)

```
ssh root@85.239.52.55 "cd /opt/yuldash && rm -rf app alembic && mv app.bak-ДАТА app && mv alembic.bak-ДАТА alembic && systemctl restart yuldash-api && sleep 3 && curl -s http://127.0.0.1:8000/health"
```
Схему базы **не откатываем** (миграции аддитивные — старый код с новыми колонками работает).
Если база всё же повреждена — полный откат из дампа шага 1: [deploy-migrations.md](deploy-migrations.md), раздел «Откат».

## Что НЕ входит в этот вечер

- **Сайт/PWA (`webapp/`)** на проде не стоит вообще: нет поддомена, nginx-конфига, CORS и
  JS-ключа карт (`webapp/README.md`). Отдельная задача — после ключей.
- **Android** — новый APK собирается отдельно (`gradlew :app:assembleRelease` + подпись), сюда не входит.
- **Новый git-remote.** Как появится — переходим на `backend/ops/deploy.sh` (health-gate и
  авто-откат), а этот чек-лист станет историей.

## После выкатки — в мозг

`docs/tasks.md`: дата, коммит `main`, что прошло, что нет. `docs/00-INDEX.md`: снять
предупреждение «прод недоступен с ноутбука» (22.08), если связь появилась.
