// ================================================================
//  Web Push — КЛИЕНТСКАЯ часть (iOS 16.4+, Android, десктоп).
//  Честная реализация с мягкой деградацией:
//   • поддержка Web Push есть ТОЛЬКО когда есть Notification +
//     Service Worker + PushManager;
//   • на iOS пуши работают ТОЛЬКО после установки на экран «Домой»
//     (standalone) — в обычном табе Safari PushManager недоступен;
//   • без VITE_VAPID_PUBLIC_KEY подписаться нельзя — говорим честно
//     «включится после настройки на сервере».
//  Отправка подписки на бэкенд — в api/push.ts (мягко глотает 404,
//  пока эндпоинт не развёрнут). Точка правды флага «включено на этом
//  устройстве» — localStorage (webPushEnabled).
// ================================================================
import { sendWebPushSubscription, PushBackendMissing, unsubscribeWebPush } from "../api/push";
import {
  ApiError, authSessionId, getAccountSession, getSessionGeneration, getToken, reconcileSession, refreshRejectedToken,
} from "../api/client";
import { notifyPushScopeChanged, readWebPushBinding, writeWebPushBinding } from "../utils/sessionRecord";
import { sessionMutation } from "../utils/storageHealth";

const ENABLED_KEY = "yuldash.push.web.enabled";

/** Публичный VAPID-ключ из окружения (base64url). Пусто → подписка невозможна. */
export function vapidKey(): string {
  return (import.meta.env.VITE_VAPID_PUBLIC_KEY ?? "").trim();
}

/** Есть ли в браузере всё нужное для Web Push. */
export function pushSupported(): boolean {
  return (
    "Notification" in window &&
    "serviceWorker" in navigator &&
    "PushManager" in window
  );
}

/** Установлено ли приложение на экран «Домой» (нужно для пушей на iOS). */
export function isStandalone(): boolean {
  return (
    window.matchMedia("(display-mode: standalone)").matches ||
    // iOS Safari
    (window.navigator as unknown as { standalone?: boolean }).standalone === true
  );
}

/** iOS / iPadOS (в т.ч. iPad, маскирующийся под Mac). */
export function isIos(): boolean {
  const ua = window.navigator.userAgent;
  const iOS = /iPad|iPhone|iPod/.test(ua);
  const iPadOS = navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  return iOS || iPadOS;
}

/** Текущее разрешение на уведомления (default / granted / denied). */
export function permission(): NotificationPermission {
  return pushSupported() ? Notification.permission : "denied";
}

/** Отмечено ли, что пуши включены на этом устройстве (для индикатора). */
export function isWebPushEnabled(): boolean {
  const enabled = localStorage.getItem(ENABLED_KEY);
  return enabled === "1" || enabled === getSessionGeneration();
}
function setWebPushEnabled(v: boolean): void {
  if (v) localStorage.setItem(ENABLED_KEY, getSessionGeneration());
  else localStorage.removeItem(ENABLED_KEY);
}

/** Итог попытки включить пуши — экран решает, что показать. */
export type EnableResult =
  | "ok" // подписались и отправили на сервер
  | "ok-pending-server" // подписались, но бэкенд-эндпоинта ещё нет (мягкий 404)
  | "unsupported" // браузер не умеет Web Push
  | "need-standalone" // iOS без установки на «Домой»
  | "no-key" // нет VITE_VAPID_PUBLIC_KEY
  | "denied" // пользователь запретил уведомления
  | "error"; // прочая ошибка (сеть/SW)

/** base64url публичного VAPID-ключа → Uint8Array для applicationServerKey. */
function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64.length % 4)) % 4);
  const normalized = (base64 + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(normalized);
  // Явно на ArrayBuffer (не ArrayBufferLike) — так тип подходит под BufferSource
  // applicationServerKey в strict TS.
  const buffer = new ArrayBuffer(raw.length);
  const out = new Uint8Array(buffer);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}

function supportsScopedPush(worker: ServiceWorker | null): Promise<boolean> {
  if (!worker) return Promise.resolve(false);
  const { port1, port2 } = new MessageChannel();
  return new Promise(resolve => {
    const finish = (supported: boolean) => {
      clearTimeout(timeout);
      port1.close();
      resolve(supported);
    };
    const timeout = setTimeout(() => finish(false), 1000);
    port1.onmessage = event => finish(event.data === true);
    try { worker.postMessage({ type: "supports-session-push" }, [port2]); }
    catch { port2.close(); finish(false); }
  });
}

/**
 * Включить Web Push: разрешение → подписка PushManager → отправка на бэк.
 * Честная мягкая деградация на каждом шаге (см. EnableResult).
 */
export async function enableWebPush(): Promise<EnableResult> {
  const generation = getSessionGeneration();
  const currentSession = () => generation === getSessionGeneration();
  if (!pushSupported()) {
    // iOS в обычном табе Safari: PushManager появляется только в standalone.
    if (isIos() && !isStandalone()) return "need-standalone";
    return "unsupported";
  }
  if (isIos() && !isStandalone()) return "need-standalone";

  const key = vapidKey();
  if (!key) return "no-key";

  try {
    const perm = await Notification.requestPermission();
    if (!currentSession()) return "error";
    if (perm !== "granted") {
      setWebPushEnabled(false);
      return "denied";
    }

    const reg = await navigator.serviceWorker.ready;
    if (!currentSession()) return "error";
    let sessionId = authSessionId();
    const access = getToken();
    if (!sessionId && access && getAccountSession()) {
      await refreshRejectedToken(access);
      if (!currentSession()) return "error";
      sessionId = authSessionId();
    }
    if (sessionId && !await supportsScopedPush(reg.active)) {
      if (currentSession()) setWebPushEnabled(false);
      return "error";
    }
    if (!currentSession()) return "error";
    // Переиспользуем существующую подписку, если она уже есть.
    let sub = await reg.pushManager.getSubscription();
    if (!currentSession()) return "error";
    if (!sub) {
      sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(key),
      });
      if (!currentSession()) return "error";
    }

    try {
      if (sessionId) {
        const previous = await readWebPushBinding();
        if (!currentSession()) return "error";
        const expectedBindingId = previous?.endpoint === sub.endpoint ? previous.bindingId : undefined;
        let bindingId: string | undefined;
        try {
          bindingId = await sendWebPushSubscription(sub, { expectedBindingId });
        } catch (error) {
          // A completed logout may already have removed the binding kept by this device.
          if (!(error instanceof ApiError && error.status === 409 && expectedBindingId && currentSession())) throw error;
          bindingId = await sendWebPushSubscription(sub, {});
        }
        if (!currentSession() || !bindingId || !await writeWebPushBinding(generation, {
          generation, endpoint: sub.endpoint, sessionId, bindingId,
        })) return "error";
      } else {
        await sendWebPushSubscription(sub);
      }
      if (!currentSession()) return "error";
      setWebPushEnabled(true);
      return "ok";
    } catch (e) {
      if (!currentSession()) return "error";
      if (e instanceof PushBackendMissing) {
        // Клиент подписался честно, но приёмника на сервере ещё нет.
        // Флаг включаем: подписка в браузере реальна, останется дослать позже.
        setWebPushEnabled(true);
        return "ok-pending-server";
      }
      throw e;
    }
  } catch {
    if (!currentSession()) return "error";
    setWebPushEnabled(false);
    return "error";
  }
}

/** Отписаться на этом устройстве (снять подписку браузера + флаг). */
export async function disableWebPush(): Promise<void> {
  const generation = getSessionGeneration();
  const currentSession = () => generation === getSessionGeneration();
  setWebPushEnabled(false);
  if (!pushSupported()) return;
  try {
    // При выходе нужна только уже существующая регистрация. ready может ждать вечно,
    // если worker не установлен (обычная вкладка/dev или неудачная установка PWA).
    const reg = await navigator.serviceWorker.getRegistration();
    if (!currentSession()) return;
    if (!reg) return;
    const sub = await reg.pushManager.getSubscription();
    if (!currentSession()) return;
    if (sub) {
      // Сначала говорим серверу, потом гасим подписку в браузере. Иначе на общем телефоне
      // следующий вошедший продолжал бы получать чужие уведомления: локального удаления мало.
      const sessionId = authSessionId();
      if (sessionId) {
        const binding = await readWebPushBinding();
        if (!currentSession()) return;
        if (binding?.generation === generation && binding.sessionId === sessionId && binding.endpoint === sub.endpoint) {
          if (!await writeWebPushBinding(generation, undefined, binding.bindingId)) return;
          notifyPushScopeChanged();
          await unsubscribeWebPush(sub.endpoint, binding.bindingId);
        }
      } else {
        await unsubscribeWebPush(sub.endpoint);
      }
      await unsubscribeBrowserIfCurrent(generation, sub);
    }
  } catch {
    /* мягко: флаг уже снят, подписка протухнет сама */
  }
}

async function unsubscribeBrowserIfCurrent(generation: string, subscription: PushSubscription): Promise<void> {
  await sessionMutation(async () => {
    await reconcileSession();
    if (generation === getSessionGeneration() && !isWebPushEnabled()) await subscription.unsubscribe();
  });
}

/** Drop the browser subscription if it still belongs to the signed-out device. */
export async function disableLocalPushAfterLogout(guestGeneration: string): Promise<void> {
  setWebPushEnabled(false);
  if (!pushSupported()) return;
  try {
    const registration = await navigator.serviceWorker.getRegistration();
    const subscription = await registration?.pushManager.getSubscription();
    if (subscription) await unsubscribeBrowserIfCurrent(guestGeneration, subscription);
  } catch { /* Local sign-out is already saved. */ }
}
