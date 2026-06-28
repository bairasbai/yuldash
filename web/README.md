# Юлдаш — лендинг для скачивания

Премиум-лендинг с анимациями для скачивания Android-приложения Юлдаш напрямую (APK).
Двуязычный: русский + башкирский (переключатель RU/БА в шапке).

**Стек:** Next.js 14 (App Router) · framer-motion · Tailwind CSS · статический экспорт.
Цвета синхронизированы с приложением (`android/.../ui/theme/Theme.kt`).

---

## Запуск локально

```bash
cd web
npm install
npm run dev      # http://localhost:3000
```

## Сборка статики (для сервера)

```bash
npm run build    # → создаёт папку web/out со статикой
```

`output: "export"` в `next.config.mjs` делает чистую статику — никакого Node на сервере не нужно,
раздаёт обычный Nginx.

---

## Куда положить APK

Файл сборки положи как **`web/public/yuldash.apk`** (он в `.gitignore`, в git не попадёт).
После `npm run build` он окажется в `out/yuldash.apk` и будет качаться по
`https://yulbash.ru/yuldash.apk`. Размер для подписи под кнопкой правится в
`components/config.ts` (`APK_SIZE`).

---

## Деплой на yulbash.ru (Nginx)

1. Собрать: `npm run build` → папка `out/`.
2. Скопировать содержимое `out/` на сервер, например в `/var/www/yuldash-landing`.
3. Скопировать APK: `/var/www/yuldash-landing/yuldash.apk`.
4. Конфиг Nginx (лендинг на корне домена, бэкенд FastAPI остаётся на `/api`):

```nginx
server {
    listen 443 ssl;
    server_name yulbash.ru;

    # ... ssl_certificate как уже настроено ...

    root /var/www/yuldash-landing;
    index index.html;

    # лендинг (статика)
    location / {
        try_files $uri $uri/ /index.html;
    }

    # APK — отдаём с правильным типом и как вложение
    location = /yuldash.apk {
        types { application/vnd.android.package-archive apk; }
        default_type application/vnd.android.package-archive;
        add_header Content-Disposition 'attachment; filename="yuldash.apk"';
    }

    # бэкенд (если он на этом же домене)
    location /api/ {
        proxy_pass http://127.0.0.1:8000/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

5. `sudo nginx -t && sudo systemctl reload nginx`.

> Если бэкенд занимает корень домена — подними лендинг на поддомене (`get.yulbash.ru`)
> и используй тот же блок, поменяв `server_name`.
