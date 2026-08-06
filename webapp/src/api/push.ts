// ================================================================
//  Web Push — отправка подписки браузера на бэкенд.
//  Эндпоинт `POST /push/web/subscribe` на проде ПОКА НЕТ (есть только
//  FCM-регистрация `/push/register` для нативного Android). Поэтому 404
//  трактуем мягко (PushBackendMissing) — клиент подписался честно, а
//  приёмник появится позже (см. webapp/README.md → раздел про VAPID).
//  Когда бэкенд добавит эндпоинт — здесь менять НИЧЕГО не нужно.
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
export async function sendWebPushSubscription(sub: PushSubscription): Promise<void> {
  try {
    await apiPost<{ ok: boolean }>("/push/web/subscribe", serializeSubscription(sub));
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 405)) {
      throw new PushBackendMissing();
    }
    throw e;
  }
}
