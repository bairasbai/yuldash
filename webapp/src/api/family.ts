// ================================================================
//  Семейный контроль (зеркало backend/app/routers/family.py).
//  Доверенные контакты: список / добавить / удалить.
//  GET/POST /trusted-contacts есть на бэке; DELETE — best-effort
//  (появится на проде позже) → экран деградирует мягко на 404/405.
// ================================================================
import { apiGet, apiPost, apiDelete } from "./client";

/** Строка доверенного контакта (TrustedContact). */
export interface TrustedContact {
  id: number;
  user_id: number;
  name: string;
  relation: string;
  phone: string;
  notify_by_default: boolean;
}

/** Тело POST /trusted-contacts (ContactIn). */
export interface ContactInput {
  name: string;
  relation?: string;
  phone?: string;
  notify_by_default?: boolean;
}

export function fetchTrustedContacts(signal?: AbortSignal): Promise<TrustedContact[]> {
  return apiGet<TrustedContact[]>("/trusted-contacts", { signal });
}

export function addTrustedContact(body: ContactInput): Promise<TrustedContact> {
  return apiPost<TrustedContact>("/trusted-contacts", {
    name: body.name,
    relation: body.relation ?? "",
    phone: body.phone ?? "",
    notify_by_default: body.notify_by_default ?? true,
  });
}

/** Удалить контакт. Ручки может ещё не быть на проде (404/405) — вызывающий деградирует мягко. */
export function deleteTrustedContact(id: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/trusted-contacts/${id}`);
}
