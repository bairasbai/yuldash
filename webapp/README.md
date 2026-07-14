# Юлдаш — PWA (webapp)

Веб-версия приложения «Юлдаш», которую можно **установить на экран «Домой»** iPhone
(Safari → «Поделиться» → «На экран „Домой“») и Android/десктопа (кнопка «Установить»).
Работает как отдельное приложение: свой значок, полноэкранный режим, офлайн-оболочка.

Это **фаза 1 — фундамент**: каркас-оболочка, установка на экран, двуязычие RU/BA,
связь с бэкендом и один живой экран — **«Поездки» (лента)**. Остальные вкладки — заглушки.

## Стек

- **Vite + React + TypeScript**
- **vite-plugin-pwa** (Workbox) — service worker, кеш app shell, автообновление
- **react-router-dom** — навигация между вкладками

Зависимостей по минимуму, всё ставится через прокси (Node 22 / npm 10).

## Запуск

```bash
cd webapp
npm install
npm run dev        # http://localhost:5173
```

Сборка прод-статики:

```bash
npm run build      # tsc (типы) + vite build → dist/
npm run preview    # локально проверить собранную версию
```

## Конфигурация

- **База API** — `VITE_API_BASE` (см. `.env.example`). По умолчанию `https://yulbash.ru`.
  Локально можно создать `.env` со своим адресом.
- **Токен** авторизации хранится в `localStorage` (`yuldash.token`), уходит как
  `Authorization: Bearer …`. Точка правды — `src/api/client.ts`.
- **Двуязычие** — точка правды `src/i18n/dict.ts` (пары `[ru, ba]`), контекст
  `useLang()` / хелпер `appText(ru, ba)` (зеркало Android).

## Деплой (app.yulbash.ru)

`npm run build` собирает статику в `dist/`. Разложить на nginx как статический сайт
под поддоменом `app.yulbash.ru`. Пример SPA-конфига (роутинг на клиенте):

```nginx
server {
  server_name app.yulbash.ru;
  root /var/www/yuldash-webapp/dist;
  index index.html;

  # SPA: все пути → index.html
  location / {
    try_files $uri $uri/ /index.html;
  }

  # service worker и манифест не кешировать агрессивно
  location = /sw.js            { add_header Cache-Control "no-cache"; }
  location = /manifest.webmanifest { add_header Cache-Control "no-cache"; }
}
```

HTTPS обязателен (PWA/service worker работают только по https или localhost).

## ⚠️ CORS на бэкенде

Фронт ходит на `https://yulbash.ru`. Чтобы запросы из браузера не блокировались,
бэкенду нужно добавить origin поддомена в `CORS_ORIGINS`:

```
CORS_ORIGINS=https://yulbash.ru,https://app.yulbash.ru
```

(см. `backend/app/main.py` — CORSMiddleware, и `backend/app/config.py`).
Пока origin не добавлен, лента поездок из dev/прод-браузера покажет состояние ошибки
(экран «Поездки» это корректно обрабатывает — кнопка «Повторить»).

## Пуши на iOS

Web Push на iOS работает **только с iOS 16.4+** и **только после установки на экран
„Домой“** (в обычном Safari-табе пуши недоступны). В этой фазе пуши не подключены —
это следующая фаза.

## Структура

```
webapp/
├─ public/
│  ├─ manifest.webmanifest      # name/short_name «Юлдаш», standalone, иконки 192/512 + maskable
│  ├─ icon-192.png / icon-512.png / apple-touch-icon.png / favicon.ico
├─ index.html                   # мета iOS (apple-mobile-web-app-*), theme-color, viewport-fit=cover
├─ vite.config.ts               # VitePWA (autoUpdate, кеш app shell + API NetworkFirst)
└─ src/
   ├─ main.tsx                  # bootstrap + регистрация SW
   ├─ App.tsx                   # router + оболочка (нижняя навигация, InstallPrompt)
   ├─ index.css                 # токены Canon* (светлая/тёмная тема)
   ├─ ui.css                    # компоненты и оболочка
   ├─ api/
   │  ├─ client.ts              # API base, токен, apiGet/apiPost, 401-хендлер  ← точка правды API
   │  └─ rides.ts               # модель Ride (зеркало RideOut) + fetchRides()
   ├─ i18n/
   │  ├─ dict.ts                # словарь пар [ru, ba]                          ← точка правды i18n
   │  └─ lang.tsx               # LangProvider / useLang / appText
   ├─ components/               # BottomNav, InstallPrompt, RideCard, States, Icons, …
   └─ screens/
      ├─ RidesScreen.tsx        # ЖИВОЙ экран — лента поездок
      └─ StubScreen.tsx         # заглушки вкладок
```

## Что дальше (следующие фазы)

- Экраны Карта / Заявка / Чат / Профиль (сейчас заглушки).
- Авторизация (SMS-код), сохранение токена, приватные эндпоинты.
- Web Push (iOS 16.4+ после установки), realtime-лента/чат по WebSocket.
- Проверка чернового башкирского носителем — см. `BASHKIR_DRAFT.md`.
