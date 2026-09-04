// ================================================================
//  Реклама — витрина для людей + self-serve кабинет партнёра
//  (зеркало backend/app/routers/ads.py).
//
//  Витрина (то, за что партнёр заплатил):
//    GET  /ads?city=&placement=  — активные объявления под место показа
//    POST /ads/{id}/event        — показ/клик (статистика кабинета)
//
//  Кабинет:
//    GET  /ad-packages        — тарифы (город/маршрут/главный), прайс
//    GET  /ads/mine           — мои объявления (все статусы + оплата)
//    GET  /ads/mine/stats     — показы/клики/CTR/срок по каждому
//    GET  /ads/{id}/stats     — статистика одного
//    POST /ads                — создать (draft)
//    POST /ads/{id}           — правка (draft/rejected)
//    POST /ads/{id}/submit    — на модерацию
//    POST /ads/{id}/pay       — заявка на оплату размещения (СБП «на доверии»)
//  Появится на проде после мержа release → мягкая деградация.
// ================================================================
import { apiGet, apiPost } from "./client";

// ---------- Витрина ----------

/**
 * Места показа. Строки те же, что кладёт сервер в Ad.placements —
 * менять нельзя: партнёр платит за конкретное место.
 */
export type AdPlacement =
  | "nearby" // карта, «поездки рядом»
  | "route" // список поездок по маршруту
  | "ridesList" // список поездок вообще
  | "tripDetails" // карточка поездки / активная поездка
  | "profile" // профиль
  | "help"; // помощь

/** Объявление, каким его видит человек (_ad_public на сервере). */
export interface PartnerAd {
  id: string;
  partner: string;
  title: string;
  text: string;
  button: string;
  target: string; // ссылка партнёра; пусто → звоним по contact
  contact: string; // реальный телефон партнёра ("" — звонить некуда)
  image: string;
  erid: string; // маркировка рекламы, показываем всегда (закон о рекламе)
  plan: string;
  placements: string[];
  cities: string[];
  city: string;
}

/**
 * Активная реклама под место показа. Без входа — витрину видят и гости.
 * Город не задан → сервер отдаёт всё, что не привязано к городу.
 */
export function fetchAds(
  opts?: { city?: string; placement?: AdPlacement; signal?: AbortSignal }
): Promise<PartnerAd[]> {
  const p = new URLSearchParams();
  if (opts?.city) p.set("city", opts.city);
  if (opts?.placement) p.set("placement", opts.placement);
  const qs = p.toString();
  return apiGet<PartnerAd[]>(`/ads${qs ? `?${qs}` : ""}`, {
    auth: false,
    signal: opts?.signal,
  });
}

/**
 * Показ или клик по объявлению — это цифры в кабинете партнёра.
 * Сервер требует входа (иначе статистику накрутит любой) и сам режет
 * повторы; гостя не считаем вовсе. Любая ошибка тут молчит: реклама
 * не должна ломать экран человеку.
 */
export function sendAdEvent(id: string, type: "impression" | "click"): Promise<void> {
  return apiPost<{ ok: boolean }>(`/ads/${id}/event`, { type })
    .then(() => undefined)
    .catch(() => undefined);
}

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

/**
 * Продлить размещение своей рекламы ещё на период (POST /ads/{id}/renew).
 *
 * Заявку создаёт СЕРВЕР, и только потом человеку показывают QR для перевода. Иначе
 * получалось так: экран сразу рисовал QR, человек переводил деньги и жал «я перевёл»,
 * а на сервере не появлялось ничего — ни заявки, ни сообщения. Через несколько дней
 * реклама гасла по сроку, и человек был уверен, что его обманули.
 *
 * Идемпотентно: повторное нажатие вернёт уже созданную заявку, а не заведёт вторую.
 */
export function renewAd(
  id: string
): Promise<{ payment_id: number; amount_kop: number; status: string }> {
  return apiPost(`/ads/${id}/renew`);
}
