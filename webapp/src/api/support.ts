// ================================================================
//  Поддержка Юлдаш (зеркало backend/app/routers/support.py).
//  Тикеты + тред «пользователь ↔ поддержка». Ответ поддержки уходит
//  в Центр уведомлений (ref_kind=support) — оттуда deep-link в тред.
// ================================================================
import { apiGet, apiPost } from "./client";

export type TicketStatus = "open" | "closed";
export type TicketSender = "user" | "admin";

export interface TicketMessage {
  id: number;
  sender: TicketSender;
  body: string;
  created_at: string;
}

export interface TicketListItem {
  id: number;
  subject: string;
  status: TicketStatus;
  last_message: string;
  last_sender: TicketSender;
  unread: boolean; // ждёт ответа пользователя (последней написала поддержка)
  created_at: string;
  updated_at: string;
}

export interface TicketsOut {
  unread: number; // сколько тредов ждут пользователя (бейдж «Поддержка»)
  items: TicketListItem[];
}

export interface TicketThread {
  id: number;
  subject: string;
  status: TicketStatus;
  created_at: string;
  updated_at: string;
  messages: TicketMessage[];
}

export function fetchTickets(signal?: AbortSignal): Promise<TicketsOut> {
  return apiGet<TicketsOut>("/support/tickets", { signal });
}

/** Только счётчик непрочитанного — для бейджа в профиле. */
export async function fetchSupportUnread(signal?: AbortSignal): Promise<number> {
  const r = await apiGet<TicketsOut>("/support/tickets", { signal });
  return r.unread;
}

export function fetchTicket(id: number, signal?: AbortSignal): Promise<TicketThread> {
  return apiGet<TicketThread>(`/support/tickets/${id}`, { signal });
}

/** Новое обращение (тема опциональна). Возвращает уже созданный тред. */
export function createTicket(subject: string, body: string): Promise<TicketThread> {
  return apiPost<TicketThread>("/support/tickets", { subject, body });
}

/** Дописать в тред. Закрытый тикет бэк переоткрывает сам. Возвращает тред. */
export function sendTicketMessage(id: number, body: string): Promise<TicketThread> {
  return apiPost<TicketThread>(`/support/tickets/${id}/messages`, { body });
}

/** Закрыть обращение («вопрос решён»). Идемпотентно. */
export function closeTicket(id: number): Promise<TicketThread> {
  return apiPost<TicketThread>(`/support/tickets/${id}/close`);
}
