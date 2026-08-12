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

// ------------------------- «Поделиться поездкой с близким» -------------------------
/**
 * Открытая близкому ссылка на поездку (TripShare). Близкий видит живую карту в браузере,
 * приложение ему не нужно — по SMS приходит ссылка вида /t/{token}.
 *
 * Ссылку можно отозвать: строка удаляется, токен «сгорает». Срок жизни — сутки, чтобы
 * зависшая поездка не показывала гео бесконечно.
 */
export interface TripShare {
  id: number;
  booking_id: number | null;
  order_id: number | null;
  parcel_id: number | null;
  contact_id: number | null;
  token: string | null;
  last_status: string; // shared | sat | arrived | done
  created_at: string;
  expires_at: string | null;
}

/** Статус поездки для близких: «села в машину» / «доехала» / «поездка завершена». */
export type TripStatus = "sat" | "arrived" | "done";

// --- Попутка (бронь) ---
export function shareBooking(bookingId: number, contactId: number): Promise<TripShare> {
  return apiPost<TripShare>(`/bookings/${bookingId}/share`, { contact_id: contactId });
}

export function fetchBookingShares(
  bookingId: number,
  signal?: AbortSignal
): Promise<TripShare[]> {
  return apiGet<TripShare[]>(`/bookings/${bookingId}/shares`, { signal });
}

export function revokeBookingShare(
  bookingId: number,
  shareId: number
): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/bookings/${bookingId}/share/${shareId}`);
}

/** «Села / доехала / завершила» — близкие получают SMS только при реальной смене статуса. */
export function setTripStatus(bookingId: number, status: TripStatus): Promise<TripShare[]> {
  return apiPost<TripShare[]>(`/bookings/${bookingId}/trip-status`, { status });
}

// --- Такси (заказ) ---
export function shareOrder(orderId: number, contactId: number): Promise<TripShare> {
  return apiPost<TripShare>(`/instant/orders/${orderId}/share`, { contact_id: contactId });
}

export function fetchOrderShares(
  orderId: number,
  signal?: AbortSignal
): Promise<TripShare[]> {
  return apiGet<TripShare[]>(`/instant/orders/${orderId}/shares`, { signal });
}

export function revokeOrderShare(
  orderId: number,
  shareId: number
): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/instant/orders/${orderId}/share/${shareId}`);
}

// --- Посылка: ссылку получает ПОЛУЧАТЕЛЬ (следит за курьером без приложения) ---
export function createParcelTrackLink(parcelId: number): Promise<TripShare> {
  return apiPost<TripShare>(`/parcels/${parcelId}/track-link`);
}

export function revokeParcelTrackLink(parcelId: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/parcels/${parcelId}/track-link`);
}
