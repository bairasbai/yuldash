// ================================================================
//  Админка Юлдаша (Волна 8А — ядро модерации).
//  Зеркало admin-эндпоинтов backend:
//    requests.py  · POST /admin/request-for-phone, GET /requests/{id}/responses,
//                   POST /responses/{id}/accept
//    drivers.py   · GET /admin/drivers/pending, POST /admin/drivers/{id}/moderate,
//                   GET /secure/docs/{name} (защищённые фото — только админ/владелец)
//    safety.py    · GET /admin/reports, POST /admin/reports/{id}/resolve|reject,
//                   POST /admin/quality/{id}/pause|unpause
//    payments.py  · GET /admin/payments/pending, POST /admin/payments/{id}/confirm|reject,
//                   GET /admin/payments/summary
//  Все ручки требуют role == "admin" (иначе 403). До мержа release на прод — 404/405 (мягко).
// ================================================================
import { apiGet, apiPost, apiDelete, getToken, isOwnApiUrl } from "./client";
import type { ResponseItem } from "./requests";
import type { Parcel } from "./parcels";

// ----------------------------- 2. Заявка за юзера -----------------------------
export interface AdminRequestIn {
  phone: string;
  name?: string;
  from_city: string;
  to_city: string;
  desired_at?: string | null;
  seats?: number;
  comment?: string;
}

/** POST /admin/request-for-phone — оформить заявку ЗА пользователя по телефону. */
export function adminRequestForPhone(body: AdminRequestIn): Promise<{ id: number }> {
  return apiPost<{ id: number }>("/admin/request-for-phone", body);
}

// ----------------------------- 3. Принять отклик за юзера -----------------------------
/** GET /requests/{id}/responses — отклики на заявку (админ видит любые). */
export function adminRequestResponses(requestId: number, signal?: AbortSignal): Promise<ResponseItem[]> {
  return apiGet<ResponseItem[]>(`/requests/${requestId}/responses`, { signal });
}

/** POST /responses/{id}/accept — принять отклик ЗА пользователя (создаёт поездку+бронь). */
export function adminAcceptResponse(responseId: number): Promise<{ booking_id: number }> {
  return apiPost<{ booking_id: number }>(`/responses/${responseId}/accept`);
}

// ----------------------------- 4. Модерация водителей -----------------------------
export interface PendingDriver {
  user_id: number;
  name: string;
  phone: string;
  car: string;
  license_url: string;
  car_photo_url: string;
  autocheck_result: string; // pass / needs_human / reject / error / ""
  autocheck_score: number;
  autocheck_data: string; // JSON: распознанные поля + коды причин
}

/** GET /admin/drivers/pending — водители на проверке (docs_status=pending). */
export function fetchPendingDrivers(signal?: AbortSignal): Promise<PendingDriver[]> {
  return apiGet<PendingDriver[]>("/admin/drivers/pending", { signal });
}

/** POST /admin/drivers/{id}/moderate — подтвердить (verified) или отклонить. */
export function moderateDriver(
  userId: number,
  approve: boolean
): Promise<{ user_id: number; verified: boolean; docs_status: string }> {
  return apiPost(`/admin/drivers/${userId}/moderate`, { approve });
}

/**
 * Загрузить защищённое фото документа с Bearer-токеном и вернуть blob-URL.
 * <img src> не умеет слать заголовок Authorization, поэтому тянем сами → objectURL.
 * URL из бэка абсолютный (secure_docs_url). Вызывающий обязан URL.revokeObjectURL при размонтировании.
 */
export async function fetchSecureDoc(url: string, signal?: AbortSignal): Promise<string> {
  // Токен подставляем только своему серверу — иначе ссылка из чужой заявки увела бы
  // доступ администратора на чужой домен (см. isOwnApiUrl).
  if (!isOwnApiUrl(url)) throw new Error("doc host not allowed");
  const token = getToken();
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal,
  });
  if (!res.ok) throw new Error(`doc ${res.status}`);
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

// ----------------------------- 5. Жалобы -----------------------------
export interface AdminReport {
  id: number;
  reporter_name: string; // виден ТОЛЬКО админу
  target_name: string;
  target_phone: string;
  reason: string;
  created_at: string;
  category: string;
  status: string; // new | reviewing | resolved | rejected
  resolution?: string | null;
  order_id?: number | null;
  booking_id?: number | null;
  parcel_id?: number | null;
  target_user_id: number;
}

/** GET /admin/reports — жалобы для разбора. Фильтры ?status ?category. */
export function fetchAdminReports(
  opts?: { status?: string; category?: string; signal?: AbortSignal }
): Promise<AdminReport[]> {
  const q = new URLSearchParams();
  if (opts?.status) q.set("status", opts.status);
  if (opts?.category) q.set("category", opts.category);
  const qs = q.toString();
  return apiGet<AdminReport[]>(`/admin/reports${qs ? `?${qs}` : ""}`, { signal: opts?.signal });
}

/** POST /admin/reports/{id}/resolve — жалоба подтверждена. keep_pause держит паузу «до разбора». */
export function resolveReport(
  reportId: number,
  body?: { resolution?: string; keep_pause?: boolean }
): Promise<AdminReport> {
  return apiPost<AdminReport>(`/admin/reports/${reportId}/resolve`, body ?? {});
}

/** POST /admin/reports/{id}/reject — жалоба не подтвердилась. */
export function rejectReport(reportId: number, resolution?: string): Promise<AdminReport> {
  return apiPost<AdminReport>(`/admin/reports/${reportId}/reject`, { resolution: resolution ?? "" });
}

/** POST /admin/quality/{id}/pause — вручную поставить/продлить паузу такси (попутка работает). */
export function qualityPause(
  userId: number,
  hours: number
): Promise<{ ok: boolean; taxi_paused_until: string; reason: string }> {
  return apiPost(`/admin/quality/${userId}/pause`, { hours });
}

/** POST /admin/quality/{id}/unpause — снять паузу такси. */
export function qualityUnpause(userId: number): Promise<{ ok: boolean }> {
  return apiPost(`/admin/quality/${userId}/unpause`);
}

// ----------------------------- 6. Заявки на оплату (СБП «на доверии») -----------------------------
export interface PendingPayment {
  payment_id: number;
  purpose: string; // boost | donate | support | ad
  tier?: string | null;
  amount: number; // рубли (бэк уже делит копейки на 100)
  ride_id?: number | null;
  note: string;
  payer_name: string;
  payer_phone: string;
  created_at: string;
}

/** GET /admin/payments/pending — платежи на подтверждение (СБП). */
export function fetchPendingPayments(signal?: AbortSignal): Promise<PendingPayment[]> {
  return apiGet<PendingPayment[]>("/admin/payments/pending", { signal });
}

/** POST /admin/payments/{id}/confirm — деньги пришли → активировать. */
export function confirmPayment(paymentId: number): Promise<{ payment_id: number; status: string }> {
  return apiPost(`/admin/payments/${paymentId}/confirm`);
}

/** POST /admin/payments/{id}/reject — деньги не пришли → отменить. */
export function rejectPayment(paymentId: number): Promise<{ payment_id: number; status: string }> {
  return apiPost(`/admin/payments/${paymentId}/reject`);
}

export interface PaymentsSummary {
  donate: { count: number; sum_rub: number };
  boost: { count: number; sum_rub: number };
  support: { count: number; sum_rub: number };
}

/** GET /admin/payments/summary — сводка подтверждённых платежей. */
export function fetchPaymentsSummary(signal?: AbortSignal): Promise<PaymentsSummary> {
  return apiGet<PaymentsSummary>("/admin/payments/summary", { signal });
}

// ================================================================
//  Волна 8Б — расширение админки. Зеркало backend-роутеров:
//    reviews.py · GET /admin/reviews/pending, /admin/ratings/pending;
//                 POST /admin/{reviews|ratings}/{id}/publish {published}
//    ads.py     · GET /admin/ads, /ads/stats; POST /admin/ads/{id}/approve|reject|status; DELETE /admin/ads/{id}
//    taxi.py    · GET /admin/taxi-applications, /admin/taxi-cities, /admin/taxi/pulse;
//                 POST /admin/taxi-applications/{id}/approve|reject, /admin/taxi-cities; DELETE …
//    waitlist.py· GET /admin/waitlist; POST /admin/waitlist/invite
//  Все требуют role == "admin" (иначе 403). До мержа release на прод — 404/405 (мягко).
// ================================================================

// ----------------------------- 7. Модерация отзывов -----------------------------
/** Отзыв о приложении (для лендинга) — ждёт модерации. */
export interface PendingAppReview {
  id: number;
  user_id?: number | null;
  name: string;
  city: string;
  stars: number;
  text: string;
  published: boolean;
  created_at: string;
}

/** Текстовый отзыв о поездке (Rating.text) — ждёт модерации. */
export interface PendingRating {
  id: number;
  author: string; // кто оставил (видит только админ)
  ratee_id: number; // кому адресован
  stars: number;
  text: string;
  created_at: string;
}

/** GET /admin/reviews/pending — отзывы о приложении на модерации. */
export function fetchPendingAppReviews(signal?: AbortSignal): Promise<PendingAppReview[]> {
  return apiGet<PendingAppReview[]>("/admin/reviews/pending", { signal });
}

/** POST /admin/reviews/{id}/publish — одобрить (или снять) отзыв о приложении. */
export function publishAppReview(id: number, published: boolean): Promise<PendingAppReview> {
  return apiPost<PendingAppReview>(`/admin/reviews/${id}/publish`, { published });
}

/** GET /admin/ratings/pending — текстовые отзывы о поездках на модерации. */
export function fetchPendingRatings(signal?: AbortSignal): Promise<PendingRating[]> {
  return apiGet<PendingRating[]>("/admin/ratings/pending", { signal });
}

/** POST /admin/ratings/{id}/publish — одобрить (или снять) текст отзыва о поездке. */
export function publishRating(id: number, published: boolean): Promise<PendingRating> {
  return apiPost<PendingRating>(`/admin/ratings/${id}/publish`, { published });
}

// ----------------------------- 8. Модерация рекламы -----------------------------
export interface AdminAd {
  id: string;
  partner: string;
  partner_contact: string;
  title: string;
  text: string;
  button: string;
  target: string;
  image: string;
  erid: string;
  plan: string; // founder | standard | premium
  package: string; // код тарифа партнёра (city/route/main), '' у админских
  placements: string[];
  cities: string[];
  priority: number;
  status: string; // draft | pending_review | active | rejected | paused | expired | archived
  reject_reason: string;
  owner_id: number | null; // партнёр (self-serve) или null (админское)
  paid: boolean;
  live: boolean;
  expired: boolean;
  founder_lock: boolean;
  starts_at: string | null;
  ends_at: string | null;
  created_at: string | null;
}

export interface AdminAdsResponse {
  founder_used: number;
  founder_limit: number;
  items: AdminAd[];
}

/** Показы/клики по объявлениям: { [ad_id]: {impressions, clicks} }. */
export type AdStats = Record<string, { impressions: number; clicks: number }>;

/** GET /admin/ads — все объявления (кроме архива) + занятые founder-слоты. */
export function fetchAdminAds(signal?: AbortSignal): Promise<AdminAdsResponse> {
  return apiGet<AdminAdsResponse>("/admin/ads", { signal });
}

/** GET /ads/stats — сводка показов/кликов (только админ). */
export function fetchAdStats(signal?: AbortSignal): Promise<AdStats> {
  return apiGet<AdStats>("/ads/stats", { signal });
}

/** POST /admin/ads/{id}/approve — одобрить (pending_review → active). erid — маркировка ОРД. */
export function approveAd(id: string, erid: string): Promise<unknown> {
  return apiPost(`/admin/ads/${id}/approve`, { erid });
}

/** POST /admin/ads/{id}/reject — отклонить с причиной (→ rejected). */
export function rejectAd(id: string, reason: string): Promise<unknown> {
  return apiPost(`/admin/ads/${id}/reject`, { reason });
}

/** POST /admin/ads/{id}/status — опубликовать/пауза/архив. */
export function setAdStatus(id: string, status: string): Promise<unknown> {
  return apiPost(`/admin/ads/${id}/status`, { status });
}

/** DELETE /admin/ads/{id} — мягкое удаление (в архив). */
export function archiveAd(id: string): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/admin/ads/${id}`);
}

// ----------------------------- 9. Заявки таксистов + города -----------------------------
export interface TaxiApplication {
  id: number;
  status: string; // pending | approved | rejected
  inn: string;
  permit_number: string;
  permit_photo_url: string;
  osago_url: string;
  selfie_url: string;
  criminal_record_url: string;
  birth_date: string;
  license_since_year: number;
  comment: string;
  created_at: string;
  reviewed_at: string | null;
  user_id: number;
  name: string;
  phone: string;
  car_class: string; // economy | comfort (заявленный)
  invited_by: string | null; // кто пригласил (доверие «между своими»)
}

export interface TaxiCity {
  id: number;
  city: string;
  enabled: boolean;
  created_at: string;
}

/** GET /admin/taxi-applications?status= — очередь заявок таксистов (580-ФЗ). */
export function fetchTaxiApplications(status: string, signal?: AbortSignal): Promise<TaxiApplication[]> {
  return apiGet<TaxiApplication[]>(`/admin/taxi-applications?status=${encodeURIComponent(status)}`, { signal });
}

/** POST /admin/taxi-applications/{id}/approve — одобрить (опц. финальный класс машины). */
export function approveTaxiApp(id: number, carClass?: "economy" | "comfort"): Promise<{ id: number; status: string }> {
  return apiPost(`/admin/taxi-applications/${id}/approve`, carClass ? { car_class: carClass } : {});
}

/** POST /admin/taxi-applications/{id}/reject — отклонить с комментарием. */
export function rejectTaxiApp(id: number, comment: string): Promise<{ id: number; status: string }> {
  return apiPost(`/admin/taxi-applications/${id}/reject`, { comment });
}

/** GET /admin/taxi-cities — города, где включено такси. */
export function fetchTaxiCities(signal?: AbortSignal): Promise<TaxiCity[]> {
  return apiGet<TaxiCity[]>("/admin/taxi-cities", { signal });
}

/** POST /admin/taxi-cities — добавить город или обновить его флаг (без дублей по имени). */
export function upsertTaxiCity(city: string, enabled: boolean): Promise<TaxiCity> {
  return apiPost<TaxiCity>("/admin/taxi-cities", { city, enabled });
}

/** DELETE /admin/taxi-cities/{id} — убрать город из списка такси. */
export function deleteTaxiCity(id: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/admin/taxi-cities/${id}`);
}

// ----------------------------- 10. Пульс такси -----------------------------
export interface TaxiPulseCity {
  city: string;
  online: number;
  active: number;
}

export interface TaxiPulse {
  drivers_online: number;
  orders_active: number;
  orders_today: number;
  done_today: number;
  cancelled_today: number;
  no_show_today: number;
  gps_suspects_today: number;
  contact_then_cancel_today: number;
  avg_search_sec_today: number | null;
  by_city: TaxiPulseCity[];
}

/** GET /admin/taxi/pulse — живая сводка такси (на линии / активные заказы / по городам). */
export function fetchTaxiPulse(signal?: AbortSignal): Promise<TaxiPulse> {
  return apiGet<TaxiPulse>("/admin/taxi/pulse", { signal });
}

// ----------------------------- 11. Лист ожидания -----------------------------
export interface WaitlistEntry {
  id: number;
  phone: string;
  city: string;
  role: string; // passenger | driver
  created_at: string;
  invited_at: string | null;
}

export interface WaitlistResponse {
  total: number;
  invited: number;
  by_city: { city: string; count: number }[];
  by_role: { passenger: number; driver: number };
  items: WaitlistEntry[];
}

/** GET /admin/waitlist — счётчики набора (по всей базе) + список по фильтрам. */
export function fetchWaitlist(
  opts?: { city?: string; role?: string; invited?: boolean; limit?: number; signal?: AbortSignal }
): Promise<WaitlistResponse> {
  const q = new URLSearchParams();
  if (opts?.city) q.set("city", opts.city);
  if (opts?.role) q.set("role", opts.role);
  if (opts?.invited !== undefined) q.set("invited", String(opts.invited));
  if (opts?.limit) q.set("limit", String(opts.limit));
  const qs = q.toString();
  return apiGet<WaitlistResponse>(`/admin/waitlist${qs ? `?${qs}` : ""}`, { signal: opts?.signal });
}

/** POST /admin/waitlist/invite — пометить волну: проставить invited_at выбранным. */
export function inviteWaitlist(ids: number[]): Promise<{ ok: boolean; invited: number }> {
  return apiPost<{ ok: boolean; invited: number }>("/admin/waitlist/invite", { ids });
}

// ================================================================
//  Волна 8В — завершение админки. Зеркало backend-роутеров:
//    coupons.py · GET /admin/partners; POST /admin/partners/{id}/approve|reject
//    promo.py   · GET/POST /admin/promo; POST /admin/promo/{id}/status
//    parcels.py · GET /admin/parcels (+ statement дохода)
//    courier.py · GET /admin/courier-applications; POST …/{id}/approve|reject
//  Все требуют role == "admin" (иначе 403). До мержа release на прод — 404/405 (мягко).
// ================================================================

// ----------------------------- 12. Модерация бизнесов -----------------------------
export interface AdminPartner {
  id: number;
  owner_id: number;
  name: string;
  category: string;
  city: string;
  address: string;
  phone: string; // публичный контакт БИЗНЕСА (не пользователя)
  description: string;
  status: string; // pending | active | rejected | paused | archived
  reject_reason: string;
  subscription_plan: string;
  subscription_until: string | null;
  subscription_active: boolean;
  created_at: string | null;
  reviewed_at: string | null;
}

/** GET /admin/partners — все бизнесы (очередь модерации), pending сверху. */
export function fetchAdminPartners(
  opts?: { limit?: number; offset?: number; signal?: AbortSignal }
): Promise<AdminPartner[]> {
  const q = new URLSearchParams();
  if (opts?.limit) q.set("limit", String(opts.limit));
  if (opts?.offset) q.set("offset", String(opts.offset));
  const qs = q.toString();
  return apiGet<AdminPartner[]>(`/admin/partners${qs ? `?${qs}` : ""}`, { signal: opts?.signal });
}

/** POST /admin/partners/{id}/approve — одобрить бизнес (→ active). */
export function approvePartner(id: number): Promise<AdminPartner> {
  return apiPost<AdminPartner>(`/admin/partners/${id}/approve`);
}

/** POST /admin/partners/{id}/reject — отклонить бизнес (→ rejected) с причиной. */
export function rejectPartner(id: number, reason: string): Promise<AdminPartner> {
  return apiPost<AdminPartner>(`/admin/partners/${id}/reject`, { reason });
}

// ----------------------------- 13. Промокоды и кампании -----------------------------
export interface AdminPromo {
  id: number;
  code: string;
  title: string;
  description: string;
  owner_id: number | null; // блогер/партнёр (по owner_phone) или null (акция Юлдаша)
  campaign: string;
  kind: string; // welcome | boost
  perk_value: number; // boost → число бесплатных поднятий
  limit_total: number;
  limit_per_user: number;
  redeemed_count: number;
  applied: number; // всего применили код
  active: number; // из них РЕАЛЬНО активны («живая поездка») — по ним платят блогеру
  valid_from: string | null;
  valid_until: string | null;
  active_flag: boolean; // включена ли кампания
  created_at: string | null;
}

export interface AdminPromoIn {
  code: string;
  title?: string;
  description?: string;
  owner_phone?: string | null; // привязать код к блогеру по телефону
  campaign?: string;
  kind?: "welcome" | "boost";
  perk_value?: number;
  limit_total?: number;
  limit_per_user?: number;
  valid_from?: string | null;
  valid_until?: string | null;
}

/** GET /admin/promo — все промокоды со счётчиками applied/active, новые сверху. */
export function fetchAdminPromos(signal?: AbortSignal): Promise<AdminPromo[]> {
  return apiGet<AdminPromo[]>("/admin/promo", { signal });
}

/** POST /admin/promo — создать промокод/кампанию (code → upper, уникальность). */
export function createAdminPromo(body: AdminPromoIn): Promise<AdminPromo> {
  return apiPost<AdminPromo>("/admin/promo", body);
}

/** POST /admin/promo/{id}/status — вкл/выкл кампанию. */
export function setPromoStatus(id: number, active: boolean): Promise<AdminPromo> {
  return apiPost<AdminPromo>(`/admin/promo/${id}/status`, { active });
}

// ----------------------------- 14. Доставки посылок -----------------------------
/** Доход платформы по доставленным посылкам (сумма fee + число доставленных). */
export interface ParcelsStatement {
  delivered_count: number;
  collected_fee_kop: number;
}

export interface AdminParcelsResponse {
  parcels: Parcel[]; // с приватным (телефон получателя + код вручения) — для поддержки/споров
  statement: ParcelsStatement;
}

/** GET /admin/parcels — все заявки (контроль/поддержка) + statement дохода платформы. */
export function fetchAdminParcels(
  opts?: { limit?: number; offset?: number; signal?: AbortSignal }
): Promise<AdminParcelsResponse> {
  const q = new URLSearchParams();
  if (opts?.limit) q.set("limit", String(opts.limit));
  if (opts?.offset) q.set("offset", String(opts.offset));
  const qs = q.toString();
  return apiGet<AdminParcelsResponse>(`/admin/parcels${qs ? `?${qs}` : ""}`, { signal: opts?.signal });
}

// ----------------------------- 15. Заявки курьеров -----------------------------
export interface AdminCourierApplication {
  id: number;
  transport: string; // car | cargo
  status: string; // pending | approved | rejected
  selfie_url: string; // защищённое фото (fetchSecureDoc)
  invited_by: number | null;
  reject_reason: string;
  created_at: string | null;
  reviewed_at: string | null;
  user_id: number;
  name: string;
  phone: string;
  invited_by_name: string | null; // кто пригласил (доверие «между своими»)
}

/** GET /admin/courier-applications?status= — очередь заявок курьеров. status=pending|approved|rejected|all. */
export function fetchCourierApplications(
  status: string,
  signal?: AbortSignal
): Promise<AdminCourierApplication[]> {
  return apiGet<AdminCourierApplication[]>(
    `/admin/courier-applications?status=${encodeURIComponent(status)}`,
    { signal }
  );
}

/** POST /admin/courier-applications/{id}/approve — одобрить заявку курьера. */
export function approveCourierApp(id: number): Promise<{ id: number; status: string }> {
  return apiPost(`/admin/courier-applications/${id}/approve`);
}

/** POST /admin/courier-applications/{id}/reject — отклонить заявку с причиной. */
export function rejectCourierApp(id: number, reason: string): Promise<{ id: number; status: string }> {
  return apiPost(`/admin/courier-applications/${id}/reject`, { reason });
}
