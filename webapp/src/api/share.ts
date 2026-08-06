// ================================================================
//  Публичное превью поездки (зеркало backend/app/routers/share.py).
//  GET /r/{ride_id}/preview — без авторизации, без личных данных
//  (нет телефона/точки/координат). Для тапа по поездке на витрине.
// ================================================================
import { apiGet } from "./client";

export interface RidePreview {
  id: number;
  from_city: string;
  to_city: string;
  depart_at: string; // ISO
  price: number;
  seats_left: number;
  seats_total: number;
  category: string;
  status: string;
  comment: string;
  pets_allowed: boolean;
  child_seat: boolean;
  women_only: boolean;
  baggage: boolean;
  air_conditioner: boolean;
  smoking: boolean;
  driver_name: string;
  driver_rating: number;
  driver_verified: boolean;
  driver_car: string;
  boosted: boolean;
}

export function fetchRidePreview(
  rideId: number,
  signal?: AbortSignal
): Promise<RidePreview> {
  return apiGet<RidePreview>(`/r/${rideId}/preview`, { auth: false, signal });
}
