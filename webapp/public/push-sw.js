/* Workbox imports this file to handle notification delivery and clicks. */
const PUSH_DATABASE = "yuldash-trip-passes-v1";
const PUSH_CREDENTIALS = "credentials";
const RATE_DATABASE = "yuldash-rate-draft";

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
            const { sid, sub } = JSON.parse(atob(payload));
            const valid = typeof sid === "string" && /^[0-9a-f]{64}$/.test(sid) && sid === scope.sessionId &&
              typeof scope.bindingId === "string" && /^[0-9a-f]{32}$/.test(scope.bindingId);
            resolve(valid ? { ...scope, subject: sub } : undefined);
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

function notificationRating(data, binding) {
  const rate = data?.rate;
  if (!matchesPushBinding(data, binding) || !rate ||
      !Number.isSafeInteger(rate.booking_id) || rate.booking_id <= 0 ||
      !Number.isSafeInteger(rate.recipient_user_id) || rate.recipient_user_id <= 0 ||
      String(rate.recipient_user_id) !== binding.subject ||
      !Number.isSafeInteger(rate.until) || rate.until <= Date.now() / 1000 ||
      localNotificationTarget(data.url) !== self.location.origin + "/trip/" + rate.booking_id) return undefined;
  return rate;
}

async function writeRatingDraft(rate, binding) {
  const db = await new Promise((resolve, reject) => {
    const open = indexedDB.open(RATE_DATABASE, 1);
    open.onupgradeneeded = () => open.result.createObjectStore("draft");
    open.onerror = () => reject(open.error);
    open.onsuccess = () => resolve(open.result);
  });
  try {
    await new Promise((resolve, reject) => {
      const tx = db.transaction("draft", "readwrite");
      tx.onabort = () => reject(tx.error);
      tx.oncomplete = () => resolve();
      tx.objectStore("draft").put({
        bookingId: rate.booking_id, userId: rate.recipient_user_id,
        generation: binding.generation, sessionId: binding.sessionId, bindingId: binding.bindingId,
        expiresAt: Math.min(rate.until * 1000, Date.now() + 600_000),
      }, "current");
    });
  } finally { db.close(); }
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
    const rate = notificationRating(data, binding);
    const maxActions = typeof Notification === "undefined" ? 0 : Notification.maxActions;
    const payloadActions = Array.isArray(data?.actions) ? data.actions : [];
    const detailsAction = payloadActions.find(action => action?.action === "rate_details");
    const actions = rate && maxActions > 0 ? [
      ...(payloadActions.some(action => action?.action === "rate_five") ? [{ action: "rate_five", title: "5 ★" }] : []),
      ...(detailsAction?.title === "Есть замечания" || detailsAction?.title === "Иҫкәрмәләр бар" ?
        [{ action: "rate_details", title: detailsAction.title }] : []),
    ].slice(0, maxActions) : [];
    await self.registration.showNotification(showDetails && data.title ? data.title : "Юлдаш", {
      ...(showDetails && data.body ? { body: data.body } : {}),
      icon: "/icon-192.png",
      badge: "/icon-192.png",
      tag,
      renotify: showDetails && Boolean(data.tag),
      ...(actions.length ? { actions } : {}),
      data: showDetails ? {
        session_id: binding.sessionId, binding_id: binding.bindingId, url: localNotificationTarget(data.url),
        ...(actions.some(action => action.action === "rate_five") ? { rate } : {}),
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
    const target = await withSessionLock(async guarded => {
      const binding = guarded ? await currentPushBinding() : undefined;
      if (!matchesPushBinding(data, binding)) return "/";
      const rate = notificationRating(data, binding);
      if (event.action === "rate_five" && rate) {
        try { await writeRatingDraft(rate, binding); } catch { /* Open the regular trip page. */ }
      }
      return localNotificationTarget(data.url);
    });
    const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const client = clients.find(item => "focus" in item);
    if (client) await client.focus();
    let navigation;
    await withSessionLock(async guarded => {
      const binding = guarded && target !== "/" ? await currentPushBinding() : undefined;
      const currentTarget = matchesPushBinding(data, binding) ? target : "/";
      if (!client) navigation = self.clients.openWindow?.(currentTarget);
      else if (currentTarget !== "/" && "navigate" in client) navigation = client.navigate(currentTarget).catch(() => {});
    });
    await navigation;
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
