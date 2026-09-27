// ================================================================
//  Web Push — отправка подписки браузера на бэкенд.
//
//  Приёмник `POST /push/web/subscribe` появился 31.08.2026 (до этого
//  клиент подписывался в никуда: браузер честно давал подписку, сервер
//  отвечал 404, и человек с сайта не получал уведомлений вообще).
//  Отписка — своя, `POST /push/web/unsubscribe`: браузерная подписка
//  опознаётся по endpoint, а не по FCM-токену.
//
//  Мягкую обработку 404 оставляем: сервер могли не обновить, а падать
//  из-за уведомлений экран не должен (PushBackendMissing).
//
//  Чтобы уведомления реально дошли, нужны ключи VAPID: публичный здесь
//  (VITE_VAPID_PUBLIC_KEY) и приватный на сервере — они пара.
// ================================================================
import { apiPost, ApiError } from "./client";

/** Бэкенд ещё не принимает web-push подписки (404/405). Не ошибка клиента. */
export class PushBackendMissing extends Error {
  constructor() {
    super("web-push backend endpoint not deployed");
    this.name = "PushBackendMissing";
  }
}

/** Форма подписки для сервера (стандарт Web Push: endpoint + ключи p256dh/auth). */
export interface WebPushSubscriptionIn {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  /** Кодировка контента (обычно "aesgcm"/"aes128gcm") — полезно серверу. */
  content_encoding?: string;
}

/** PushSubscription браузера → сериализуемая форма для бэкенда. */
export function serializeSubscription(sub: PushSubscription): WebPushSubscriptionIn {
  const json = sub.toJSON();
  const keys = json.keys ?? {};
  return {
    endpoint: sub.endpoint,
    keys: { p256dh: keys.p256dh ?? "", auth: keys.auth ?? "" },
    content_encoding:
      (PushManager as unknown as { supportedContentEncodings?: string[] })
        .supportedContentEncodings?.[0] ?? "aes128gcm",
  };
}

/**
 * Отправить подписку на сервер. Требует авторизации (Bearer) — подписка
 * привязывается к аккаунту. 404/405 → PushBackendMissing (мягко).
 */
export async function sendWebPushSubscription(sub: PushSubscription, expectedGeneration?: string): Promise<void> {
  try {
    await apiPost<{ ok: boolean }>("/push/web/subscribe", serializeSubscription(sub), { expectedGeneration });
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
      throw new PushBackendMissing();
    }
    throw e;
  }
}

/**
 * Отвязать FCM-токен устройства (POST /push/unregister). Для нативного клиента.
 * Идемпотентно, ошибки глотаем — выход из аккаунта не должен падать из-за пуша.
 */
export function unregisterPush(token: string): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>("/push/unregister", { token }).catch(() => ({ ok: false }));
}

/**
 * Отписать БРАУЗЕР (POST /push/web/unsubscribe).
 *
 * Отдельная ручка, а не общая с FCM: у браузера подписка опознаётся по `endpoint`,
 * и раньше веб слал его в ручку токенов — сервер искал такую строку среди FCM-токенов,
 * не находил и отвечал «хорошо». То есть отписки не происходило вовсе: на общем телефоне
 * следующий вошедший продолжал получать чужие уведомления.
 *
 * Идемпотентно, ошибки глотаем: выход из аккаунта не должен падать из-за пуша.
 */
export function unsubscribeWebPush(endpoint: string, expectedGeneration?: string): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>("/push/web/unsubscribe", { endpoint }, { expectedGeneration }).catch(() => ({
    ok: false,
  }));
}
