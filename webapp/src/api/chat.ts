// ================================================================
//  Чат брони (зеркало backend/app/routers/chat.py).
//  История — REST (GET /bookings/{id}/messages), отправка — REST
//  (POST) с живой доставкой через WebSocket /ws/bookings/{id}.
//  WS-авторизация: первым кадром {"type":"auth","token":"<jwt>"}.
// ================================================================
import { API_BASE, apiGet, apiPost, apiDelete, apiUpload, getToken, getSessionGeneration, ApiError, isOwnApiUrl, refreshRejectedToken } from "./client";
import { listenDriverLocation } from "../utils/tripLocationListen";
import { tripLocationUrl, type SocketLike } from "../utils/tripLocationShare";

/** Сообщение чата (строка Message + live-payload сокета). */
export interface ChatMessage {
  id: number;
  sender_id: number;
  text: string;
  /** Метка сервера: "" — обычное, "warn" — фишинг, "contact" — телефон, "abuse" — грубость. */
  flag?: string;
  from_admin?: boolean;
  /** Ссылка на голосовое (наш /voice). Пусто — обычное текстовое сообщение. */
  voice_url?: string;
  /** Удалено у всех: текст очищен сервером, показываем пометку вместо пузыря. */
  deleted?: boolean;
  /** Отредактировано — рядом с текстом приписка «изменено». */
  edited?: boolean;
  // REST отдаёт created_at, сокет — timestamp. Нормализуем в UI.
  created_at?: string;
  timestamp?: string;
  /** Идентичность исходящей операции; подтверждаем только собственный sender_id. */
  request_id?: string;
}

export function fetchMessages(
  bookingId: number,
  signal?: AbortSignal
): Promise<ChatMessage[]> {
  return apiGet<ChatMessage[]>(`/bookings/${bookingId}/messages?limit=500`, {
    signal,
  });
}

export function sendMessageRest(
  bookingId: number,
  text: string,
  voiceUrl?: string,
  requestId?: string,
  generation?: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/bookings/${bookingId}/messages`, {
    text,
    voice_url: voiceUrl,
  }, { idempotencyKey: requestId, _generation: generation });
}

/**
 * Поправить своё сообщение (POST /bookings/{id}/messages/{mid}/edit).
 * Сервер сам перепроверяет текст на подозрительное — «отправил безобидное,
 * потом переписал» не проходит. Голосовое править нельзя.
 * Ошибки: 403 чужое, 400 удалено / пусто / голосовое.
 */
export function editMessage(
  bookingId: number,
  messageId: number,
  text: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/bookings/${bookingId}/messages/${messageId}/edit`, { text });
}

/**
 * Удалить сообщение (DELETE /bookings/{id}/messages/{mid}).
 *   "all" — у всех: только своё, текст стирается, остаётся пометка;
 *   "me"  — скрыть у себя: собеседник по-прежнему видит.
 */
export function deleteMessage(
  bookingId: number,
  messageId: number,
  scope: "all" | "me"
): Promise<ChatMessage> {
  return apiDelete<ChatMessage>(`/bookings/${bookingId}/messages/${messageId}?scope=${scope}`);
}

/** Инбокс диалогов (GET /conversations). */
export interface Conversation {
  booking_id: number;
  peer_name: string;
  peer_avatar: string;
  peer_verified: boolean;
  route: string;
  last_message: string;
  depart_at?: string | null;
}

export function fetchConversations(signal?: AbortSignal): Promise<Conversation[]> {
  return apiGet<Conversation[]>("/conversations", { signal });
}

/** http(s)://host → ws(s)://host для WebSocket-эндпоинтов. */
export function wsBase(): string {
  return API_BASE.replace(/^http/, "ws");
}

/**
 * Открыть WebSocket чата брони. Живая доставка сообщений.
 * onMessage вызывается на каждый кадр {"type":"message", ...}.
 * Возвращает объект с send/close. При падении WS экран сам переходит на поллинг.
 */
export function openBookingChat(
  bookingId: number,
  handlers: {
    senderId: number;
    onMessage: (m: ChatMessage) => void;
    onAcknowledged?: (m: ChatMessage) => void;
    onOpen?: () => void;
    onClose?: () => void;
    onError?: () => void;
  }
): { send: (text: string, requestId: string) => Promise<ChatMessage | null>; close: () => void } {
  const token = getToken(), generation = getSessionGeneration();
  const ws = new WebSocket(`${wsBase()}/ws/bookings/${bookingId}`);
  let closed = false;
  const active = () => !closed && generation === getSessionGeneration();
  type Waiter = { resolve: (m: ChatMessage | null) => void; reject: (e: ApiError) => void; timer: ReturnType<typeof setTimeout> };
  const pending = new Map<string, Waiter>();
  // A concurrent REST flush can acknowledge an intent before send() installs its waiter.
  const acknowledged = new Map<string, ChatMessage>();
  function finish(key: string, message: ChatMessage | null, error?: ApiError) {
    const waiter = pending.get(key);
    if (!waiter) return;
    pending.delete(key); clearTimeout(waiter.timer);
    if (error) waiter.reject(error); else waiter.resolve(message);
  }
  function disconnected() { for (const key of pending.keys()) finish(key, null); }
  ws.onopen = () => {
    if (!active()) { ws.close(); disconnected(); return; }
    if (token) ws.send(JSON.stringify({ type: "auth", token }));
    handlers.onOpen?.();
  };
  ws.onmessage = (ev) => {
    if (!active()) { disconnected(); return; }
    let data;
    try { data = JSON.parse(ev.data); } catch { return; }
    if (data?.type === "message") {
      if (typeof data.request_id === "string" && data.sender_id === handlers.senderId && Number.isSafeInteger(data.id) && data.id > 0) {
        acknowledged.set(data.request_id, data as ChatMessage);
        if (acknowledged.size > 256) acknowledged.delete(acknowledged.keys().next().value!);
        finish(data.request_id, data as ChatMessage);
        handlers.onAcknowledged?.(data as ChatMessage);
      }
      handlers.onMessage(data as ChatMessage);
    } else if (data?.type === "rejected" && typeof data.request_id === "string") {
      const status = Number.isInteger(data.status_code) ? data.status_code : 503;
      finish(data.request_id, null, new ApiError(status, String(data.reason ?? "message rejected")));
    }
  };
  ws.onclose = () => { disconnected(); if (active()) handlers.onClose?.(); };
  ws.onerror = () => { disconnected(); if (active()) handlers.onError?.(); };
  return {
    send: (text, requestId) => {
      if (!active()) return Promise.resolve(null);
      const known = acknowledged.get(requestId);
      if (known) return Promise.resolve(known);
      if (ws.readyState !== WebSocket.OPEN) return Promise.resolve(null);
      // Register before send: a fast/queued acknowledgment must never be missed.
      return new Promise<ChatMessage | null>((resolve, reject) => {
        finish(requestId, null);
        const timer = setTimeout(() => finish(requestId, null), 5000);
        pending.set(requestId, { resolve, reject, timer });
        try { ws.send(JSON.stringify({ type: "message", text, request_id: requestId })); }
        catch { finish(requestId, null); }
      });
    },
    close: () => {
      closed = true; disconnected(); acknowledged.clear();
      ws.onopen = null; ws.onmessage = null; ws.onclose = null; ws.onerror = null;
      try { ws.close(); } catch { /* Уже закрыт. Очередь остаётся на диске. */ }
    },
  };
}

// ---- Чат такси-заказа (зеркало chat.py: /instant/orders/{id}/messages + /ws/instant/{id}/chat) ----
// Писать можно только в активном заказе (accepted..onboard); читать — плюс done/cancelled.

export function fetchOrderMessages(
  orderId: number,
  signal?: AbortSignal
): Promise<ChatMessage[]> {
  return apiGet<ChatMessage[]>(`/instant/orders/${orderId}/messages?limit=500`, {
    signal,
  });
}

export function sendOrderMessageRest(
  orderId: number,
  text: string,
  voiceUrl?: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/instant/orders/${orderId}/messages`, {
    text,
    voice_url: voiceUrl,
  });
}

/** WebSocket чата такси-заказа. Первым кадром {"type":"auth","token":...}. */
export function openOrderChat(
  orderId: number,
  handlers: {
    onMessage: (m: ChatMessage) => void;
    onOpen?: () => void;
    onClose?: () => void;
    onError?: () => void;
  }
): { send: (text: string) => boolean; close: () => void } {
  const token = getToken();
  const ws = new WebSocket(`${wsBase()}/ws/instant/${orderId}/chat`);

  ws.onopen = () => {
    if (token) ws.send(JSON.stringify({ type: "auth", token }));
    handlers.onOpen?.();
  };
  ws.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data);
      if (data?.type === "message") handlers.onMessage(data as ChatMessage);
    } catch {
      /* не-JSON кадр — игнор */
    }
  };
  ws.onclose = () => handlers.onClose?.();
  ws.onerror = () => handlers.onError?.();

  return {
    send: (text: string) => {
      if (ws.readyState !== WebSocket.OPEN) return false;
      ws.send(JSON.stringify({ type: "message", text }));
      return true;
    },
    close: () => {
      try {
        ws.close();
      } catch {
        /* уже закрыт */
      }
    },
  };
}

// ---- Чат посылки (chat.py: /parcels/{id}/messages + /ws/parcel/{id}/chat) ----
// До этого у посылки была только кнопка «позвонить»: договориться письменно —
// где оставить, кому отдать, когда будут дома — было нечем.
// После вручения/возврата/отмены чат остаётся на чтение, но не на запись.

export function fetchParcelMessages(
  parcelId: number,
  signal?: AbortSignal
): Promise<ChatMessage[]> {
  return apiGet<ChatMessage[]>(`/parcels/${parcelId}/messages?limit=500`, { signal });
}

export function sendParcelMessageRest(
  parcelId: number,
  text: string,
  voiceUrl?: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/parcels/${parcelId}/messages`, {
    text,
    voice_url: voiceUrl,
  });
}

/** WebSocket чата посылки. Первым кадром {"type":"auth","token":...}. */
export function openParcelChat(
  parcelId: number,
  handlers: {
    onMessage: (m: ChatMessage) => void;
    onOpen?: () => void;
    onClose?: () => void;
    onError?: () => void;
  }
): { send: (text: string) => boolean; close: () => void } {
  const token = getToken();
  const ws = new WebSocket(`${wsBase()}/ws/parcel/${parcelId}/chat`);

  ws.onopen = () => {
    if (token) ws.send(JSON.stringify({ type: "auth", token }));
    handlers.onOpen?.();
  };
  ws.onmessage = (ev) => {
    try {
      const data = JSON.parse(ev.data);
      if (data?.type === "message") handlers.onMessage(data as ChatMessage);
    } catch {
      /* не-JSON кадр — игнор */
    }
  };
  ws.onclose = () => handlers.onClose?.();
  ws.onerror = () => handlers.onError?.();

  return {
    send: (text: string) => {
      if (ws.readyState !== WebSocket.OPEN) return false;
      ws.send(JSON.stringify({ type: "message", text }));
      return true;
    },
    close: () => {
      try {
        ws.close();
      } catch {
        /* уже закрыт */
      }
    },
  };
}

/**
 * Live-позиция машины в поездке (WS /ws/trip/{id}/location).
 * Только пока бронь confirmed/onboard. onLoc — координаты водителя.
 * Переподключение, обновление истёкшего ключа и «сеть вернулась» — utils/tripLocationListen.ts.
 */
export function openTripLocation(
  bookingId: number,
  onLoc: (p: { lat: number; lng: number; bearing?: number }) => void
): { close: () => void } {
  const generation = getSessionGeneration();
  const h = listenDriverLocation({
    // Настоящий WebSocket шире SocketLike (у его обработчиков this и Event) — логике хватает подмножества.
    openSocket: () => new WebSocket(tripLocationUrl(wsBase(), bookingId)) as unknown as SocketLike,
    token: getToken,
    setTimer: (fn, ms) => setTimeout(fn, ms),
    clearTimer: (t) => clearTimeout(t as ReturnType<typeof setTimeout>),
    onLoc,
    isCurrent: () => generation === getSessionGeneration(),
    refreshToken: refreshRejectedToken,
    subscribeOnline: (fn) => {
      if (typeof window === "undefined") return () => {};
      window.addEventListener("online", fn);
      return () => window.removeEventListener("online", fn);
    },
  });
  return { close: () => h.stop() };
}

/**
 * Фото в сообщении помечается префиксом — так же, как в приложении (`ApiClient.IMG_PREFIX`).
 * Отдельного поля под картинку в сообщении нет, и заводить его только ради веба нельзя:
 * старый Android перестал бы понимать такие сообщения.
 */
export const IMG_PREFIX = "[img]";

/**
 * Текст сообщения → ссылка на картинку, если это фото. Иначе null.
 *
 * Принимаем ТОЛЬКО свой адрес. Легитимное фото всегда наше: его отдаёт
 * POST /upload/chat-photo. А вот собеседник (или мошенник, притворяющийся
 * поддержкой) может послать текст `[img]http://чужой-хост/1.png` руками —
 * браузер сам сходит по ссылке, и на том конце запишут IP человека, который
 * просто открыл чат. Чужой адрес показываем как обычный текст: сообщение
 * не теряется, но никуда не ходим.
 */
export function imageUrlOf(text: string): string | null {
  if (!text?.startsWith(IMG_PREFIX)) return null;
  const url = text.slice(IMG_PREFIX.length).trim();
  if (!url || !isOwnApiUrl(url)) return null;
  return url;
}

// ---- Фото в чат (POST /upload/chat-photo, multipart `file`) ----
// Отдельно от документов водителя: те приватны (/secure/docs), фото чата видит собеседник.
// Нужно, чтобы объяснить «вот у какого подъезда стою» без десяти сообщений текстом.
export function uploadChatPhoto(file: File, signal?: AbortSignal): Promise<{ url: string }> {
  const form = new FormData();
  form.append("file", file);
  return apiUpload<{ url: string }>("/upload/chat-photo", form, { signal });
}
