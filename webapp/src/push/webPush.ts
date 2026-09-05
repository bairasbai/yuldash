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
  return localStorage.getItem(ENABLED_KEY) === "1";
}
function setWebPushEnabled(v: boolean): void {
  if (v) localStorage.setItem(ENABLED_KEY, "1");
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

/**
 * Включить Web Push: разрешение → подписка PushManager → отправка на бэк.
 * Честная мягкая деградация на каждом шаге (см. EnableResult).
 */
export async function enableWebPush(): Promise<EnableResult> {
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
    if (perm !== "granted") {
      setWebPushEnabled(false);
      return "denied";
    }

    const reg = await navigator.serviceWorker.ready;
    // Переиспользуем существующую подписку, если она уже есть.
    let sub = await reg.pushManager.getSubscription();
    if (!sub) {
      sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(key),
      });
    }

    try {
      await sendWebPushSubscription(sub);
      setWebPushEnabled(true);
      return "ok";
    } catch (e) {
      if (e instanceof PushBackendMissing) {
        // Клиент подписался честно, но приёмника на сервере ещё нет.
        // Флаг включаем: подписка в браузере реальна, останется дослать позже.
        setWebPushEnabled(true);
        return "ok-pending-server";
      }
      throw e;
    }
  } catch {
    setWebPushEnabled(false);
    return "error";
  }
}

/** Отписаться на этом устройстве (снять подписку браузера + флаг). */
export async function disableWebPush(): Promise<void> {
  setWebPushEnabled(false);
  if (!pushSupported()) return;
  try {
    const reg = await navigator.serviceWorker.ready;
    const sub = await reg.pushManager.getSubscription();
    if (sub) {
      // Сначала говорим серверу, потом гасим подписку в браузере. Иначе на общем телефоне
      // следующий вошедший продолжал бы получать чужие уведомления: локального удаления мало.
      await unsubscribeWebPush(sub.endpoint);
      await sub.unsubscribe();
    }
  } catch {
    /* мягко: флаг уже снят, подписка протухнет сама */
  }
}
