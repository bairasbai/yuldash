/* Workbox imports this file to handle notification delivery and clicks. */
const PUSH_DATABASE = "yuldash-trip-passes-v1";
const PUSH_CREDENTIALS = "credentials";

async function currentPushBinding() {
  try {
    const db = await new Promise((resolve, reject) => {
      const open = indexedDB.open(PUSH_DATABASE);
      open.onupgradeneeded = () => open.transaction.abort();
      open.onerror = () => reject(open.error);
      open.onsuccess = () => resolve(open.result);
    });
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(PUSH_CREDENTIALS, "readonly");
        const store = tx.objectStore(PUSH_CREDENTIALS);
        const current = store.get("current");
        const binding = store.get("web-push");
        tx.onabort = () => reject(tx.error);
        tx.oncomplete = () => {
          const saved = current.result;
          const scope = binding.result;
          if (!scope || typeof saved?.access !== "string" || !saved.generation ||
              saved.generation !== scope.generation) return resolve(undefined);
          try {
            const payload = saved.access.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
            const sid = JSON.parse(atob(payload)).sid;
            const valid = typeof sid === "string" && /^[0-9a-f]{64}$/.test(sid) && sid === scope.sessionId &&
              typeof scope.bindingId === "string" && /^[0-9a-f]{32}$/.test(scope.bindingId);
            resolve(valid ? scope : undefined);
          } catch { resolve(undefined); }
        };
      });
    } finally { db.close(); }
  } catch { return undefined; }
}

function matchesPushBinding(data, binding) {
  return !!binding && data?.session_id === binding.sessionId && data.binding_id === binding.bindingId;
}

function localNotificationTarget(url) {
  if (typeof url !== "string") return "/";
  try {
    const target = new URL(url, self.location.origin);
    return target.origin === self.location.origin && target.href !== self.location.origin + "/" ? target.href : "/";
  } catch { return "/"; }
}

async function closeStaleNotifications() {
  const binding = await currentPushBinding();
  for (const notification of await self.registration.getNotifications()) {
    if (!matchesPushBinding(notification.data, binding)) notification.close();
  }
}

function withSessionLock(work) {
  const locks = self.navigator?.locks;
  return locks ? locks.request("yuldash-session-write", () => work(true)) : work(false);
}

self.addEventListener("push", (event) => {
  let data;
  try { data = event.data?.json(); } catch { /* Unreadable payload. */ }

  event.waitUntil(withSessionLock(async guarded => {
    const binding = guarded ? await currentPushBinding() : undefined;
    const showDetails = matchesPushBinding(data, binding);
    const tag = showDetails && data.tag ? binding.bindingId + ":" + data.tag : "yuldash";
    await self.registration.showNotification(showDetails && data.title ? data.title : "Юлдаш", {
      ...(showDetails && data.body ? { body: data.body } : {}),
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      tag,
      renotify: showDetails && Boolean(data.tag),
      data: showDetails ? {
        session_id: binding.sessionId, binding_id: binding.bindingId, url: localNotificationTarget(data.url),
      } : { url: "/" },
    });
    if (showDetails && !matchesPushBinding(data, await currentPushBinding())) {
      for (const notification of await self.registration.getNotifications({ tag })) {
        if (notification.data?.binding_id === binding.bindingId) notification.close();
      }
    }
  }));
});

self.addEventListener("notificationclick", (event) => {
  const data = event.notification.data;
  event.notification.close();
  event.waitUntil((async () => {
    const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const client = clients.find(item => "focus" in item);
    if (client) await client.focus();
    await withSessionLock(async guarded => {
      const binding = guarded ? await currentPushBinding() : undefined;
      const target = matchesPushBinding(data, binding) ? localNotificationTarget(data.url) : "/";
      if (!client) await self.clients.openWindow?.(target);
      else if (target !== "/" && "navigate" in client) await client.navigate(target).catch(() => {});
    });
  })());
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "supports-session-push") {
    const port = event.ports[0];
    if (port) { port.postMessage(true); port.close(); }
  }
  if (event.data?.type === "auth-scope-changed") {
    event.waitUntil(withSessionLock(closeStaleNotifications));
  }
});

self.addEventListener("activate", (event) => {
  event.waitUntil(withSessionLock(closeStaleNotifications));
});
