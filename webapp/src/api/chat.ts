// ================================================================
//  Чат брони (зеркало backend/app/routers/chat.py).
//  История — REST (GET /bookings/{id}/messages), отправка — REST
//  (POST) с живой доставкой через WebSocket /ws/bookings/{id}.
//  WS-авторизация: первым кадром {"type":"auth","token":"<jwt>"}.
// ================================================================
import { API_BASE, apiGet, apiPost, getToken } from "./client";

/** Сообщение чата (строка Message + live-payload сокета). */
export interface ChatMessage {
  id: number;
  sender_id: number;
  text: string;
  flag?: string; // "" | "warn"
  from_admin?: boolean;
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
  text: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/bookings/${bookingId}/messages`, { text });
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
  text: string
): Promise<ChatMessage> {
  return apiPost<ChatMessage>(`/instant/orders/${orderId}/messages`, { text });
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
