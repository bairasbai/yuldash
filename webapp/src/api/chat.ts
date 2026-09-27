// ================================================================
//  Чат брони (зеркало backend/app/routers/chat.py).
//  История — REST (GET /bookings/{id}/messages), отправка — REST
//  (POST) с живой доставкой через WebSocket /ws/bookings/{id}.
//  WS-авторизация: первым кадром {"type":"auth","token":"<jwt>"}.
// ================================================================
import { API_BASE, apiGet, apiPost, apiDelete, apiUpload, getToken, isOwnApiUrl } from "./client";

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
  expectedGeneration?: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/bookings/${bookingId}/messages`, {
    text,
    voice_url: voiceUrl,
  }, { idempotencyKey: requestId, expectedGeneration });
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
    onMessage: (m: ChatMessage) => void;
    onOpen?: () => void;
    onClose?: () => void;
    onError?: () => void;
  }
): { send: (text: string) => boolean; close: () => void } {
  const token = getToken();
  const ws = new WebSocket(`${wsBase()}/ws/bookings/${bookingId}`);

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
 */
export function openTripLocation(
  bookingId: number,
  onLoc: (p: { lat: number; lng: number; bearing?: number }) => void
): { close: () => void } {
  const token = getToken();
  let ws: WebSocket | null = null;
  try {
    ws = new WebSocket(`${wsBase()}/ws/trip/${bookingId}/location`);
    ws.onopen = () => {
      if (token) ws?.send(JSON.stringify({ type: "auth", token }));
    };
    ws.onmessage = (ev) => {
      try {
        const d = JSON.parse(ev.data);
        if (d?.type === "loc" && d.role === "driver" && typeof d.lat === "number")
          onLoc({ lat: d.lat, lng: d.lng, bearing: d.bearing });
      } catch {
        /* игнор */
      }
    };
  } catch {
    /* WS недоступен — не критично, экран работает без live-точки */
  }
  return {
    close: () => {
      try {
        ws?.close();
      } catch {
        /* уже закрыт */
      }
    },
  };
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
