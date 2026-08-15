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

// ----------------------------- Админ: обращения людей -----------------------------
/**
 * Очередь поддержки. Для такси и доставки поддержка — последняя инстанция при любой
 * проблеме, и тишина в ответ читается как «им всё равно». Поэтому отвечать нужно
 * отовсюду, а не только с телефона.
 */
export interface AdminTicket {
  id: number;
  user_id: number;
  user_name: string;
  subject: string;
  status: string; // open | closed
  last_message: string;
  last_sender: string; // user | admin
  message_count: number;
  created_at: string;
  updated_at: string;
}

/** GET /admin/support/tickets?status=open|closed|all */
export function fetchAdminTickets(
  status: "open" | "closed" | "all" = "open",
  signal?: AbortSignal
): Promise<AdminTicket[]> {
  return apiGet<AdminTicket[]>(`/admin/support/tickets?status=${status}`, { signal });
}

/** GET /admin/support/tickets/{id} — весь тред глазами админа. */
export function fetchAdminTicket(id: number, signal?: AbortSignal): Promise<TicketThread> {
  return apiGet<TicketThread>(`/admin/support/tickets/${id}`, { signal });
}

/** Ответ поддержки. Закрытый тикет переоткрывается: диалог продолжается. */
export function replyAdminTicket(id: number, body: string): Promise<TicketThread> {
  return apiPost<TicketThread>(`/admin/support/tickets/${id}/reply`, { body });
}

/** Закрыть обращение со стороны админа. */
export function closeAdminTicket(id: number): Promise<TicketThread> {
  return apiPost<TicketThread>(`/admin/support/tickets/${id}/close`);
}
