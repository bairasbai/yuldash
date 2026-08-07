// ================================================================
//  Центр уведомлений (зеркало backend/app/routers/notifications.py).
//  GET /notifications → лента + счётчик непрочитанного (бейдж).
//  POST /notifications/read {id} | {all:true} → пометить прочитанным.
// ================================================================
import { apiGet, apiPost } from "./client";

/** Одно уведомление ленты. Заголовок/текст приходят парой ru/ba. */
export interface AppNotification {
  id: number;
  type: string; // booking / ride / system / message
  title_ru: string;
  title_ba: string;
  body_ru: string;
  body_ba: string;
  ref_kind: string; // booking / request / support / "" — как трактовать ref_id (deep-link)
  ref_id: number | null;
  read: boolean;
  created_at: string;
}

export interface NotificationsOut {
  unread: number;
  items: AppNotification[];
}

export function fetchNotifications(signal?: AbortSignal): Promise<NotificationsOut> {
  return apiGet<NotificationsOut>("/notifications?limit=100", { signal });
}

/** Только счётчик — для бейджа в профиле (мягко: ошибку глотает вызывающий). */
export async function fetchNotifUnread(signal?: AbortSignal): Promise<number> {
  const r = await apiGet<NotificationsOut>("/notifications?limit=1", { signal });
  return r.unread;
}

export interface ReadResult {
  ok: boolean;
  unread: number;
}

/** Пометить одно уведомление прочитанным. Возвращает актуальный unread. */
export function markRead(id: number): Promise<ReadResult> {
  return apiPost<ReadResult>("/notifications/read", { id });
}

/** Пометить все прочитанными. */
export function markAllRead(): Promise<ReadResult> {
  return apiPost<ReadResult>("/notifications/read", { all: true });
}
