// ================================================================
//  Клиники-партнёры и «поездки к клинике» (зеркало
//  backend/app/routers/medical.py). Это ЛОГИСТИКА, не медуслуга:
//  клиника — публичная точка назначения, к которой можно подъехать
//  вместе. Никаких мед.данных пациента. Витрина публичная (без
//  телефона/точной точки — они открываются только участникам брони).
// ================================================================
import { apiGet } from "./client";
import type { Ride } from "./rides";

/** Клиника-партнёр (GET /medical-partners). */
export interface MedicalPartner {
  id: number;
  name: string;
  city: string;
  address: string;
  lat?: number | null;
  lng?: number | null;
  description: string;
  active: boolean;
}

/** Поездки к клинике (GET /medical-partners/{id}/rides). */
export interface ClinicRides {
  partner: MedicalPartner;
  count: number;
  items: Ride[]; // публичная витрина (public_rides_payload)
}

export function fetchMedicalPartners(
  city?: string,
  signal?: AbortSignal
): Promise<MedicalPartner[]> {
  const q = city ? `?city=${encodeURIComponent(city)}` : "";
  return apiGet<MedicalPartner[]>(`/medical-partners${q}`, { auth: false, signal });
}

export function fetchClinicRides(
  id: number,
  signal?: AbortSignal
): Promise<ClinicRides> {
  return apiGet<ClinicRides>(`/medical-partners/${id}/rides`, {
    auth: false,
    signal,
  });
}
