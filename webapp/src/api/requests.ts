// ================================================================
//  Заявки пассажира (зеркало backend/app/routers/requests.py).
//  Пассажир создаёт заявку «ищу попутку», водители откликаются,
//  пассажир принимает отклик → создаётся бронь (booking_id).
// ================================================================
import { apiGet, apiPost } from "./client";
import type { RideCategory } from "./rides";

/** Тело создания заявки. Совпадает 1:1 с RequestIn (только нужные поля). */
export interface RequestInput {
  from_city: string;
  to_city: string;
  desired_at?: string | null; // ISO
  seats?: number; // 1..8
  max_price?: number | null; // ₽, 0 = не важно
  category?: RideCategory;
  with_kids?: boolean;
  baggage?: boolean;
  women_only?: boolean;
  child_seat?: boolean;
  pets?: boolean;
  wheelchair?: boolean;
  non_smoking?: boolean;
  air_conditioner?: boolean;
  only_trusted?: boolean;
  comment?: string;
}

/** Полная строка заявки (ответ POST /requests, GET /requests/mine). */
export interface RideRequestRow {
  id: number;
  passenger_id: number;
  from_city: string;
  to_city: string;
  from_lat?: number | null;
  from_lng?: number | null;
  to_lat?: number | null;
  to_lng?: number | null;
  desired_at?: string | null;
  seats: number;
  max_price?: number | null;
  category: RideCategory;
  women_only: boolean;
  baggage: boolean;
  child_seat: boolean;
  pets: boolean;
  only_trusted: boolean;
  comment: string;
  status: string; // active | matched | cancelled
  created_at: string;
}

/** Элемент ленты заявок для водителя (GET /requests/feed). */
export interface RequestFeedItem {
  id: number;
  passenger_name: string;
  passenger_avatar: string;
  from_city: string;
  to_city: string;
  desired_at?: string | null;
  seats: number;
  comment: string;
  responded: boolean;
  my_response_id?: number | null;
  prefs: string[]; // women|child|pets|wheelchair|baggage|nosmoke|ac
}

/** Отклик водителя на мою заявку (GET /requests/{id}/responses). */
export interface ResponseItem {
  id: number;
  driver_id: number;
  driver_name: string;
  driver_avatar: string;
  driver_rating?: number | null;
  price: number;
  comment: string;
  status: string; // offered | accepted | declined
}

export function createRequest(body: RequestInput): Promise<RideRequestRow> {
  return apiPost<RideRequestRow>("/requests", body);
}

export function fetchMyRequests(signal?: AbortSignal): Promise<RideRequestRow[]> {
  return apiGet<RideRequestRow[]>("/requests/mine", { signal });
}

export function cancelRequest(id: number): Promise<RideRequestRow> {
  return apiPost<RideRequestRow>(`/requests/${id}/cancel`);
}

export function fetchRequestsFeed(signal?: AbortSignal): Promise<RequestFeedItem[]> {
  return apiGet<RequestFeedItem[]>("/requests/feed", { signal });
}

export function respondToRequest(
  requestId: number,
  price: number,
  comment: string
): Promise<{ ok: boolean; id: number }> {
  return apiPost<{ ok: boolean; id: number }>(`/requests/${requestId}/respond`, {
    price,
    comment,
  });
}

export function fetchRequestResponses(
  requestId: number,
  signal?: AbortSignal
): Promise<ResponseItem[]> {
  return apiGet<ResponseItem[]>(`/requests/${requestId}/responses`, { signal });
}

/** Принять отклик → создаётся бронь. Возвращает booking_id. */
export function acceptResponse(responseId: number): Promise<{ booking_id: number }> {
  return apiPost<{ booking_id: number }>(`/responses/${responseId}/accept`);
}
