// ================================================================
//  Заявки пассажира (зеркало backend/app/routers/requests.py).
//  Пассажир создаёт заявку «ищу попутку», водители откликаются,
//  пассажир принимает отклик → создаётся бронь (booking_id).
// ================================================================
import { apiDelete, apiGet, apiPost } from "./client";
import type { Ride, RideCategory } from "./rides";

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
  for_relative_name?: string | null; // заявка «за близкого» — имя того, для кого едем
  voice_url?: string | null; // ссылка на голосовое (из POST /voice)
  transcript?: string | null; // расшифровка/текст голосовой заявки
  assisted?: boolean; // режим «помощь» (пожилой/голос/за близкого) — сервер сразу зовёт админа
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
  /**
   * Насколько заявка уводит водителя с его маршрута, км. 0 — фактически по пути.
   * null — считать не из чего: нет активных поездок или координат. Тогда молчим,
   * а не пишем «0 км»: выдуманная цифра хуже её отсутствия.
   */
  detour_km?: number | null;
}

/** Отклик водителя на мою заявку (GET /requests/{id}/responses).
 *  Поля торга аддитивны: сервер считает, чей сейчас ход, — клиент только рисует. */
export interface ResponseItem {
  id: number;
  driver_id: number;
  driver_name: string;
  driver_avatar: string;
  driver_rating?: number | null;
  price: number; // первая цена водителя (историческая)
  comment: string;
  status: string; // offered | accepted | declined
  // --- торг ---
  current_price: number; // что сейчас на столе — по ней и создастся поездка
  last_offer_by: string; // driver | passenger — чей ход был последним
  bargain_rounds: number;
  can_counter: boolean; // смотрящий может предложить свою цену
  can_accept: boolean; // смотрящий может принять цену на столе
  bargain_history: string; // «d:500,p:400,d:450»
}

/** Один ход торга, разобранный из bargain_history. */
export interface BargainStep {
  by: "driver" | "passenger";
  price: number;
}

/** «d:500,p:400» → [{by:'driver',price:500},{by:'passenger',price:400}]. Мусор пропускаем. */
export function parseBargainHistory(raw: string): BargainStep[] {
  if (!raw) return [];
  return raw
    .split(",")
    .map((part) => {
      const [who, value] = part.split(":");
      const price = Number(value);
      if (!Number.isFinite(price)) return null;
      return { by: who === "p" ? "passenger" : "driver", price } as BargainStep;
    })
    .filter((s): s is BargainStep => s !== null);
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

/** Мои отклики (водитель): GET /responses/mine — где я предложил цену и где ждут мой ход. */
export function fetchMyResponses(signal?: AbortSignal): Promise<ResponseItem[]> {
  return apiGet<ResponseItem[]>("/responses/mine", { signal });
}

/** Встречная цена. Ходят по очереди; 409 = не твой ход / торг закрыт / лимит ходов. */
export function counterOffer(
  responseId: number,
  price: number,
  comment = ""
): Promise<ResponseItem> {
  return apiPost<ResponseItem>(`/responses/${responseId}/counter`, { price, comment });
}

/** Отказаться от торга (обе стороны). */
export function declineResponse(responseId: number): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>(`/responses/${responseId}/decline`);
}

/** Водитель убирает свой отклик совсем. */
export function withdrawResponse(responseId: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/responses/${responseId}`);
}

/** Подходящие поездки под мою заявку (GET /match/rides?request_id=).
 *  Публичный payload RideOut (без точной точки сбора до брони).
 *  До деплоя release эндпоинт отдаёт 404 → вызывающий скрывает блок. */
export function fetchMatchRides(
  requestId: number,
  signal?: AbortSignal
): Promise<Ride[]> {
  return apiGet<Ride[]>(`/match/rides?request_id=${requestId}`, { signal });
}

/** Тело правки заявки (RequestEditIn) — все поля опциональны, меняется только присланное. */
export interface RequestEditInput {
  from_city?: string;
  to_city?: string;
  desired_at?: string | null; // ISO
  seats?: number; // 1..8
  max_price?: number | null;
  comment?: string;
}

/** Править свою АКТИВНУЮ заявку (POST /requests/{id}/edit — алиас PATCH).
 *  400 = уже не активная; 404 до деплоя release → мягкая деградация. */
export function editRequest(
  requestId: number,
  body: RequestEditInput
): Promise<RideRequestRow> {
  return apiPost<RideRequestRow>(`/requests/${requestId}/edit`, body);
}
