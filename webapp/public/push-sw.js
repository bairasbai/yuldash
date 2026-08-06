/* ================================================================
 *  Юлдаш — обработчик Web Push в service worker.
 *  Подключается к сгенерированному Workbox SW через
 *  workbox.importScripts (см. vite.config.ts) — НЕ ломает autoUpdate:
 *  Workbox по-прежнему сам управляет кешем и обновлением, а мы лишь
 *  добавляем слушатели push / notificationclick.
 *
 *  Формат payload (двуязычный, шлёт бэкенд, когда появится эндпоинт):
 *    { title, body, url, tag, lang }  — все поля необязательны.
 *  Без payload (или битый JSON) показываем спокойный дефолт.
 * ================================================================ */

self.addEventListener("push", (event) => {
  let data = {};
  try {
    if (event.data) data = event.data.json();
  } catch (_e) {
    // payload не JSON — покажем дефолтный текст, но уведомление всё равно нужно
    // (userVisibleOnly: подписка требует видимого уведомления на каждый push).
    try {
      data = { body: event.data ? event.data.text() : "" };
    } catch (_e2) {
      data = {};
    }
  }

  const title = data.title || "Юлдаш";
  const options = {
    body: data.body || "Есть новое событие",
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    tag: data.tag || undefined,
    // renotify — чтобы повтор с тем же tag снова тронул устройство
    renotify: Boolean(data.tag),
    data: { url: data.url || "/" },
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || "/";

  event.waitUntil(
    self.clients
      .matchAll({ type: "window", includeUncontrolled: true })
      .then((clients) => {
        // Уже открытая вкладка приложения — фокус + навигация внутрь.
        for (const client of clients) {
          if ("focus" in client) {
            client.focus();
            if ("navigate" in client && target !== "/") {
              try {
                client.navigate(target);
              } catch (_e) {
                /* кросс-домен/ограничение — просто фокус */
              }
            }
            return;
          }
        }
        // Иначе открываем новое окно.
        if (self.clients.openWindow) return self.clients.openWindow(target);
      })
  );
});
