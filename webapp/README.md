# Юлдаш — PWA (webapp)

Веб-версия приложения «Юлдаш»: открываешь в браузере и **устанавливаешь на экран «Домой»**
iPhone (Safari → «Поделиться» → «На экран „Домой“») или Android/десктопа (кнопка «Установить»).
Работает как отдельное приложение: свой значок, полноэкранный режим, офлайн-оболочка, пуши.

Тот же бэкенд `yulbash.ru`, дизайн Canon 1:1 с Android, двуязычие RU/BA.

## Стек

- **Vite + React + TypeScript**
- **vite-plugin-pwa** (Workbox) — service worker, кеш app shell, автообновление, Web Push
- **react-router-dom** — навигация

Зависимостей по минимуму, всё ставится через прокси (Node 22 / npm 10).

## Запуск (локально)

```bash
cd webapp
npm ci             # чистая установка по package-lock (для CI/деплоя)
npm run dev        # http://localhost:5173
```

Сборка прод-статики:

```bash
npm ci
npm run build      # tsc (типы) + vite build → dist/
npm run preview    # локально проверить собранную версию
```

Результат сборки — статика в **`dist/`** (index.html + assets + `sw.js` + `manifest.webmanifest`).
Ничего серверного не нужно — это статический сайт.

## Конфигурация окружения

Переменные читаются на этапе сборки (`import.meta.env.VITE_*`). Локально — `.env`,
для прод-сборки — `.env.production` (или переменные окружения CI). Шаблон — `.env.example`.

### `.env.production` (пример для прод-сборки)

```bash
# База API (бэкенд). Прод:
VITE_API_BASE=https://yulbash.ru

# Telegram-бот входа по коду (без @). Тот же, что в мобильном приложении.
VITE_TELEGRAM_BOT=<имя_бота>

# Ключ Яндекс Карт (JS API) — ОТДЕЛЬНЫЙ ключ под веб-домен app.yulbash.ru
# (не тот, что нативный MapKit в Android). Пусто → карта покажет плейсхолдер.
VITE_YANDEX_MAPS_JS_KEY=<js_api_ключ>

# Публичный VAPID-ключ для Web Push (base64url). Приватный ключ — только на сервере.
# Пусто → «Включить пуши» честно скажет «включится после настройки на сервере».
VITE_VAPID_PUBLIC_KEY=<public_vapid_ключ>
```

Токен авторизации хранится в `localStorage` (`yuldash.token`), уходит как
`Authorization: Bearer …`. Точка правды — `src/api/client.ts`.
Двуязычие — `src/i18n/dict.ts` (пары `[ru, ba]`) + `useLang()` / `appText(ru, ba)`.

## Деплой на nginx (app.yulbash.ru)

> Короткий путь: один раз настроить по [docs/deploy-pwa.md](../docs/deploy-pwa.md), дальше
> каждая выкатка — `bash webapp/deploy.sh` (сборка → заливка → атомарная подмена → проверка).

1. Собери статику: `npm ci && npm run build` → каталог `dist/`.
2. Разложи `dist/` на сервер, напр. `/var/www/yuldash-webapp/dist`.
3. Настрой nginx как статический SPA-сайт с fallback на `index.html`
   (готовый пример — **`nginx.conf.example`** в этой папке).
4. Выпусти TLS-сертификат (Let’s Encrypt / certbot) — **HTTPS обязателен**:
   service worker, установка на «Домой» и Web Push работают только по https (или localhost).

Ключевое в конфиге (детали — в `nginx.conf.example`):
- `try_files $uri /index.html;` — все клиентские маршруты отдаёт SPA;
- `assets/*` (хешированные имена) — кешируем агрессивно (`immutable`, год);
- `sw.js`, `push-sw.js`, `manifest.webmanifest`, `index.html` — **не кешировать**
  (`no-cache`), иначе пользователи застрянут на старой версии.

## ⚙️ Что остаётся за Александром (операционка на сервере)

Фронт собран и готов, но эти шаги — вне кода, их нужно сделать один раз на инфраструктуре:

**(а) Поддомен + nginx.** Завести `app.yulbash.ru` (DNS A-запись на тот же сервер),
поднять nginx-статику из `dist/` с SPA-fallback (см. `nginx.conf.example`) и HTTPS.

**(б) CORS на бэкенде.** Браузер ходит с `https://app.yulbash.ru` на `https://yulbash.ru`,
поэтому origin нужно добавить в `CORS_ORIGINS` бэка:
```
CORS_ORIGINS=https://yulbash.ru,https://app.yulbash.ru
```
(см. `backend/app/main.py` — CORSMiddleware, и `backend/app/config.py`).
Пока origin не добавлен, приватные запросы из браузера будут падать (экраны это
корректно показывают — состояние ошибки + «Повторить»).

**(в) Ключ Яндекс Карт (JS API).** Выпустить в кабинете Яндекса **отдельный** JS-API-ключ,
привязанный к домену `app.yulbash.ru`, и положить в `VITE_YANDEX_MAPS_JS_KEY` при сборке.

**(г) Web Push — VAPID + приёмник на бэкенде.** ⚠️ Нужно доделать на сервере:
  1. Сгенерировать пару VAPID-ключей (напр. `npx web-push generate-vapid-keys`,
     или через `pywebpush`/`py-vapid`). Публичный → `VITE_VAPID_PUBLIC_KEY` (сборка фронта),
     приватный → в `.env` бэкенда (**не в git**).
  2. Добавить на бэкенд эндпоинт приёма web-push подписки:
     **`POST /push/web/subscribe`** — принимает `{ endpoint, keys: { p256dh, auth }, content_encoding }`
     (тело формирует `src/api/push.ts`), требует Bearer-токен, сохраняет подписку рядом с
     существующими device-токенами (`DeviceToken`) и привязывает к пользователю.
  3. Научить рассыльщик (`services.send_push`) отправлять и на web-push подписки
     (payload JSON `{ title, body, url, tag, lang }` — его разбирает `public/push-sw.js`).

Пока эндпоинта нет, клиент честно ведёт себя мягко: подписка в браузере создаётся,
`POST /push/web/subscribe` → 404 глотается как `PushBackendMissing`, а в Настройках
показывается «Пуши заработают, когда мы настроим отправку на сервере». **Никаких обещаний,
что пуши уже работают — они включатся только после шагов выше.**

## Web Push на iOS — важно

Web Push на iOS работает **только с iOS 16.4+** и **только после установки на экран „Домой“**
(в обычном табе Safari `PushManager` недоступен). Клиент это проверяет
(`navigator.standalone` / `display-mode: standalone`) и, если приложение не установлено,
показывает подсказку «Сначала добавь на экран „Домой“» вместо кнопки включения.

## Офлайн

App shell (оболочка) кешируется service worker’ом (Workbox precache + `navigateFallback`),
поэтому без сети приложение открывается и показывает баннер «Нет сети», а не белый экран.
Лента поездок (`GET /rides`, `/feed`) кешируется по стратегии NetworkFirst
(свежее из сети, кеш — фолбэк). Индикатор офлайна — `src/components/OfflineBanner.tsx`
(слушает `navigator.onLine` + события `online`/`offline`).

## CI

Сборка фронта проверяется в GitHub Actions job’ой **`webapp-build`** (`.github/workflows/ci.yml`):
`setup-node 22` → `npm ci` → `npm run build`. Ветка защищена: PR не пройдёт, если фронт не собрался.

## Структура

```
webapp/
├─ public/
│  ├─ manifest.webmanifest      # «Юлдаш», standalone, иконки 192/512 + maskable
│  ├─ push-sw.js                # обработчик Web Push (push / notificationclick)
│  ├─ icon-192.png / icon-512.png / apple-touch-icon.png / favicon.ico
├─ index.html                   # мета iOS, theme-color, viewport-fit=cover
├─ nginx.conf.example           # пример прод-конфига nginx (SPA + кеш + HTTPS)
├─ vite.config.ts               # VitePWA (autoUpdate, кеш app shell, importScripts push-sw)
└─ src/
   ├─ main.tsx                  # bootstrap + регистрация SW
   ├─ App.tsx                   # router + оболочка (нижняя навигация, OfflineBanner, InstallPrompt)
   ├─ api/
   │  ├─ client.ts              # API base, токен, apiGet/apiPost, 401  ← точка правды API
   │  └─ push.ts                # отправка web-push подписки на бэк (мягкий 404)
   ├─ push/webPush.ts           # подписка Web Push (поддержка/standalone/permission/subscribe)
   ├─ components/
   │  ├─ OfflineBanner.tsx      # индикатор «Нет сети»
   │  └─ PushToggle.tsx         # секция «Пуш-уведомления» в Настройках
   ├─ i18n/                     # dict.ts (пары [ru, ba]) + lang.tsx (useLang/appText)
   └─ screens/…                 # экраны (см. src/App.tsx — маршруты)
```
