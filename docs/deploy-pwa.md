# 🌐 Выкатка сайта/PWA на app.yulbash.ru — один раз настроить, дальше одна команда

> Для Александра, простыми словами. Сайт (`webapp/`) сейчас **нигде не стоит** — всё, что
> сделано в нём с июля, живёт только в репозитории. Код и nginx-конфиг готовы; не хватает
> четырёх вещей вне кода: поддомен, HTTPS, два ключа и одна строка в настройках сервера.
> После этого выкатка = `bash webapp/deploy.sh`.

## Что нужно достать (до вечера за ноутбуком)

| Что | Где взять | Куда положить |
|---|---|---|
| **JS-ключ Яндекс Карт** для домена `app.yulbash.ru` | Кабинет разработчика Яндекса → «JavaScript API и HTTP Геокодер» → новый ключ, в ограничениях указать домен. Это **другой** ключ, не тот, что у Android (MapKit) | `webapp/.env.production` → `VITE_YANDEX_MAPS_JS_KEY` |
| **Пара VAPID-ключей** для пушей в браузере | На ноутбуке, из папки `backend/`: `.venv\Scripts\python.exe tools/vapid_keys.py` — печатает две строки: публичный (87 знаков) и приватный (43 знака). Проверено локально. Альтернатива: `npx web-push generate-vapid-keys` | публичный → `webapp/.env.production` → `VITE_VAPID_PUBLIC_KEY`; приватный → `/opt/yuldash/.env` → `VAPID_PRIVATE_KEY` (+ `VAPID_PUBLIC_KEY` тот же публичный, `VAPID_SUBJECT=mailto:почта`). **Приватный — никогда в git и не в чат** |
| Имя Telegram-бота входа | тот же бот, что в Android | `VITE_TELEGRAM_BOT` |

Без ключа карт сайт работает — карта показывает спокойную заглушку «подключится с ключом».
Без VAPID — тоже работает, кнопка пушей честно говорит «после настройки на сервере».
То есть выкатывать можно и раньше, ключи доложить потом (пересобрать + `deploy.sh`).

## Один раз на сервере (30 минут)

1. **DNS.** У регистратора домена `yulbash.ru` добавить запись `A  app  85.239.52.55`.
   Проверка через 5–30 минут: `nslookup app.yulbash.ru` → `85.239.52.55`.
2. **Папка и nginx.**
   ```
   ssh root@85.239.52.55 "mkdir -p /var/www/yuldash-webapp/dist"
   scp webapp/nginx.conf.example root@85.239.52.55:/etc/nginx/sites-available/app.yulbash.ru
   ssh root@85.239.52.55 "ln -sf /etc/nginx/sites-available/app.yulbash.ru /etc/nginx/sites-enabled/ && nginx -t"
   ```
   `nginx -t` сейчас **ругнётся на сертификаты** — это ожидаемо, они появятся на шаге 3.
3. **HTTPS (обязателен: без него не работают установка на экран, service worker, пуши).**
   ```
   ssh root@85.239.52.55 "certbot --nginx -d app.yulbash.ru --redirect -m ПОЧТА --agree-tos -n && nginx -t && systemctl reload nginx"
   ```
   Если certbot не установлен: `apt install -y certbot python3-certbot-nginx`. Продление — само (таймер certbot).
4. **CORS на бэкенде** — иначе браузер с `app.yulbash.ru` не сможет ходить на API `yulbash.ru`:
   в `/opt/yuldash/.env` строка
   ```
   CORS_ORIGINS=https://yulbash.ru,https://app.yulbash.ru
   ```
   затем `systemctl restart yuldash-api` и `curl -s http://127.0.0.1:8000/health`.
   Пока строки нет, приватные запросы с сайта падают — экраны показывают «Повторить», не белый экран.
5. **`webapp/.env.production`** (файл в git не попадает):
   ```
   VITE_API_BASE=https://yulbash.ru
   VITE_TELEGRAM_BOT=<бот>
   VITE_YANDEX_MAPS_JS_KEY=<ключ или пусто>
   VITE_VAPID_PUBLIC_KEY=<публичный ключ или пусто>
   ```

## Каждая выкатка (2 минуты)

```
bash webapp/deploy.sh
```
Скрипт: собирает → заливает → атомарно подменяет папку, **не стирая старые assets два дня**
(старый service worker у людей может их ещё просить — иначе белый экран, как ловили на
лендинге в июле) → `nginx -t && reload` → проверяет главную, manifest и что `sw.js` не кэшируется.
Ждём в конце `DEPLOY_DONE` и `sw.js cache-control: no-cache ✓`.

## Проверка руками с телефона (5 минут)

- Открыть `https://app.yulbash.ru` — лента поездок, переключатель RU/БА.
- Войти по SMS-коду, открыть «Мои заявки», «Профиль → Мои данные».
- Android/Chrome: должна появиться кнопка «Установить»; iPhone: Safari → Поделиться → «На экран Домой».
- Если ключ карт задан: на экране такси рисуются тайлы Уфы, а не заглушка.
- Если VAPID задан: Настройки → «Включить пуши» → разрешить → тестовое уведомление из
  админки (`/admin`) доходит.

## Если сломалось

- Белый экран после выкатки → `ssh root@85.239.52.55 "ls /var/www/yuldash-webapp/dist/assets | wc -l"` —
  должно быть больше нуля; `curl -sI https://app.yulbash.ru/sw.js` — `Cache-Control: no-cache`.
- API-ошибки на сайте при живом `yulbash.ru/health` → почти всегда CORS (шаг 4) — смотреть в
  консоли браузера слово `CORS`.
- Откат: предыдущей папки нет (заменяется атомарно), но откатить = собрать прошлый коммит
  и снова `deploy.sh`. Старые assets двое суток лежат рядом, так что старые вкладки не ломаются.
