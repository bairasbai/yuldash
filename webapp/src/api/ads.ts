// ================================================================
//  Реклама — self-serve кабинет партнёра (зеркало backend/app/routers/ads.py).
//  GET  /ad-packages        — тарифы (город/маршрут/главный), прайс
//  GET  /ads/mine           — мои объявления (все статусы + оплата)
//  GET  /ads/mine/stats     — показы/клики/CTR/срок по каждому
//  GET  /ads/{id}/stats     — статистика одного
//  POST /ads                — создать (draft)
//  POST /ads/{id}           — правка (draft/rejected)
//  POST /ads/{id}/submit    — на модерацию
//  POST /ads/{id}/pay       — заявка на оплату размещения (СБП «на доверии»)
//  Появится на проде после мержа release → мягкая деградация.
// ================================================================
import { apiGet, apiPost } from "./client";

export interface AdPackage {
  code: string;
  title: string;
  title_ba: string;
  amount_kop: number;
  period_days: number;
}

export interface AdMine {
  id: string;
  title: string;
  text: string;
  button: string;
  target: string;
  erid: string;
  status:
    | "draft"
    | "pending_review"
    | "active"
    | "rejected"
    | "paused"
    | "expired"
    | "archived"
    | string;
  reject_reason: string;
  package: string;
  package_title: string;
  budget_kop: number;
  period_days: number;
  placements: string[];
  cities: string[];
  paid: boolean;
  created_at: string | null;
  submitted_at: string | null;
  starts_at: string | null;
  ends_at: string | null;
}

export interface AdStat {
  ad_id: string;
  title: string;
  status: string;
  impressions: number;
  clicks: number;
  ctr: number; // %
  starts_at: string | null;
  ends_at: string | null;
  days_left: number | null;
}

export interface AdCreateIn {
  title: string;
  text?: string;
  button?: string;
  target?: string;
  package?: string; // код тарифа из AD_PACKAGES
  cities?: string; // CSV городов таргета; пусто = все
}

/** Ответ POST /ads/{id}/pay — заявка на СБП-оплату, подтверждает админ. */
export interface AdPayResult {
  payment_id: number;
  amount_kop: number;
  status: string; // pending
}

export function fetchAdPackages(signal?: AbortSignal): Promise<AdPackage[]> {
  return apiGet<AdPackage[]>("/ad-packages", { auth: false, signal });
}

export function fetchAdsMine(signal?: AbortSignal): Promise<AdMine[]> {
  return apiGet<AdMine[]>("/ads/mine", { signal });
}

export function fetchAdsMineStats(signal?: AbortSignal): Promise<AdStat[]> {
  return apiGet<AdStat[]>("/ads/mine/stats", { signal });
}

export function createAd(body: AdCreateIn): Promise<AdMine> {
  return apiPost<AdMine>("/ads", body);
}

export function updateAd(id: string, body: AdCreateIn): Promise<AdMine> {
  return apiPost<AdMine>(`/ads/${id}`, body);
}

export function submitAd(id: string): Promise<AdMine> {
  return apiPost<AdMine>(`/ads/${id}/submit`);
}

export function payAd(id: string): Promise<AdPayResult> {
  return apiPost<AdPayResult>(`/ads/${id}/pay`);
}
