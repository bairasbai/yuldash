import type { InstantOrder, OrderInput } from "../api/instant";
import type { TaxiConsentQuote } from "../api/taxiConsent";
import { serverMs } from "./serverTime";

export const TAXI_CONSENT_MARKER_PREFIX = "yuldash.taxiConsent.pending.";
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const HEX = /^[0-9a-f]{64}$/;
const PARTS = ["options_fee_kop", "pickup_fee_kop", "ride_price_kop", "weather_fee_kop"];

/** Local experiment only. An invalid requested configuration must never fall back. */
export function taxiConsentEnvironment(flag: string | undefined,
  location: { hostname: string; origin: string; protocol: string }, apiBase: string): string | null {
  const local = (name: string) => ["localhost", "127.0.0.1", "[::1]"].includes(name);
  if (flag !== "1" || !local(location.hostname) || !["http:", "https:"].includes(location.protocol)) return null;
  try {
    const api = new URL(apiBase);
    if (!local(api.hostname) || !["http:", "https:"].includes(api.protocol) || api.username || api.password
        || api.search || api.hash) return null;
    return `${location.origin}|${api.origin}${api.pathname.replace(/\/+$/, "")}`;
  } catch { return null; }
}

export function consentGeneration(generation: string): boolean { return UUID.test(generation); }

/** Same validated pricing inputs as create; operational fields never go to quote. */
export function taxiConsentInput(body: OrderInput) {
  return {
    from_lat: body.from_lat, from_lng: body.from_lng, to_lat: body.to_lat, to_lng: body.to_lng,
    from_text: body.from_text, to_text: body.to_text, category: body.category,
    options: body.options, waypoints: body.waypoints, round_trip: body.round_trip,
    return_wait_min: body.return_wait_min,
  };
}

export function readTaxiQuote(raw: unknown): TaxiConsentQuote {
  const q = raw as TaxiConsentQuote;
  const money = (n: unknown) => typeof n === "number" && Number.isSafeInteger(n) && n >= 0;
  if (!q || q.schema !== "taxi-initial-consent-v1" || !UUID.test(q.quote_id) || !UUID.test(q.request_key)
      || !HEX.test(q.snapshot_digest) || !HEX.test(q.pricing_inputs_digest)
      || !serverMs(q.expires_at) || !money(q.price_kop) || !money(q.promo_discount_kop)
      || !money(q.payable_kop) || q.price_kop - q.promo_discount_kop !== q.payable_kop
      || !q.breakdown || Object.keys(q.breakdown).sort().join() !== PARTS.join()
      || !PARTS.every(k => money(q.breakdown[k as keyof typeof q.breakdown]))
      || Object.values(q.breakdown).reduce((a, b) => a + b, 0) !== q.price_kop
      || !Number.isFinite(q.distance_km) || q.distance_km < 0
      || !Number.isFinite(q.eta_min) || q.eta_min < 0 || typeof q.round_trip !== "boolean") {
    throw new Error("Invalid taxi consent quote");
  }
  return q;
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  if (value && typeof value === "object") return `{${Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${JSON.stringify(k)}:${canonical(v)}`).join(",")}}`;
  return JSON.stringify(value);
}

export function sameTaxiQuote(a: TaxiConsentQuote, b: TaxiConsentQuote): boolean {
  return canonical(a) === canonical(b);
}

export type ConsentStamp = { wall: number; monotonic: number; validForMs: number };
/** TF09-B recency principle: count from START, include RTT/background, reject rollback. */
export function taxiConsentFresh(stamp: ConsentStamp, wall = Date.now(), mono = performance.now()): boolean {
  const a = wall - stamp.wall, b = mono - stamp.monotonic;
  return Number.isFinite(a) && Number.isFinite(b) && a >= 0 && b >= 0
    && Math.max(a, b) < stamp.validForMs;
}

/** V1 suffixes were local UUIDs, never server correlators. V2 stores only the real key. */
export type TaxiPendingMarker = { key: string; version: 1 | 2 | 3; requestKey: string | null };
export function pendingTaxiResults(storage: Storage, generation: string, environment: string): TaxiPendingMarker[] {
  const keys = Array.from({ length: storage.length }, (_, i) => storage.key(i))
    .filter((k): k is string => !!k && k.startsWith(TAXI_CONSENT_MARKER_PREFIX));
  const matching: TaxiPendingMarker[] = [];
  for (const key of keys) {
    const marker = JSON.parse(storage.getItem(key) ?? "null");
    const suffix = key.slice(TAXI_CONSENT_MARKER_PREFIX.length);
    if (!marker || Object.keys(marker).sort().join() !== "e,g,v" || ![1, 2, 3].includes(marker.v)
        || !consentGeneration(marker.g) || typeof marker.e !== "string" || !UUID.test(suffix)) {
      throw new Error("Invalid pending marker");
    }
    // A stale reader must not delete or adopt another login/environment's barrier.
    if (marker.g === generation && marker.e === environment) {
      matching.push({ key, version: marker.v, requestKey: marker.v === 1 ? null : suffix });
    }
  }
  return matching;
}
export function pendingTaxiMarkers(storage: Storage, generation: string, environment: string): string[] {
  return pendingTaxiResults(storage, generation, environment).map(marker => marker.key);
}

export function markTaxiPending(storage: Storage, generation: string, environment: string, requestKey: string,
  version: 2 | 3 = 2): string {
  if (!consentGeneration(generation) || !UUID.test(requestKey)) throw new Error("Unscoped consent request");
  const key = TAXI_CONSENT_MARKER_PREFIX + requestKey;
  if (storage.getItem(key) !== null) throw new Error("Pending marker already exists");
  const value = JSON.stringify({ v: version, g: generation, e: environment });
  storage.setItem(key, value);
  if (storage.getItem(key) !== value) throw new Error("Pending marker was not saved");
  return key;
}

export function ownsTaxiPending(storage: Storage, marker: TaxiPendingMarker, generation: string, environment: string): boolean {
  return marker.version !== 1 && !!marker.requestKey && pendingTaxiResults(storage, generation, environment)
    .some(found => found.version === marker.version && found.key === marker.key && found.requestKey === marker.requestKey);
}
/** V3 records the passenger's intent to stop; no tab may send the old confirmation again. */
export function beginTaxiClose(storage: Storage, marker: TaxiPendingMarker, generation: string, environment: string): TaxiPendingMarker {
  if (!ownsTaxiPending(storage, marker, generation, environment)) throw new Error("Pending marker changed");
  if (marker.version === 3) return marker;
  const closing: TaxiPendingMarker = { ...marker, version: 3 };
  storage.setItem(marker.key, JSON.stringify({ v: 3, g: generation, e: environment }));
  if (!ownsTaxiPending(storage, closing, generation, environment)) throw new Error("Could not save close intent");
  return closing;
}
export function clearOwnedTaxiPending(storage: Storage, marker: TaxiPendingMarker, generation: string, environment: string): void {
  if (!ownsTaxiPending(storage, marker, generation, environment)) throw new Error("Pending marker changed");
  clearTaxiPending(storage, marker.key);
}

export type TaxiOrderResult = { schema: "taxi-consent-result-v1"; state: "unknown" }
  | { schema: "taxi-consent-result-v1"; state: "committed"; order_id: number; initial_payable_kop: number; next: "fetch_order" };
export function readTaxiOrderResult(raw: unknown): TaxiOrderResult {
  const r = raw as TaxiOrderResult;
  if (r?.schema === "taxi-consent-result-v1") {
    if (r.state === "unknown" && Object.keys(r).sort().join() === "schema,state") return r;
    if (r.state === "committed" && Object.keys(r).sort().join() === "initial_payable_kop,next,order_id,schema,state"
        && Number.isSafeInteger(r.order_id) && r.order_id > 0 && r.next === "fetch_order"
        && Number.isSafeInteger(r.initial_payable_kop) && r.initial_payable_kop >= 0) return r;
  }
  throw new Error("Invalid taxi order result");
}
export type TaxiCloseResult = Extract<TaxiOrderResult, { state: "committed" }> |
  ({ schema: "taxi-consent-close-v1"; admission: "blocked" } & (
    { state: "closed"; history: "not_created" } |
    { state: "retired"; history: "created_unavailable" } |
    { state: "unknown"; history: "unverified" }
  ));

export function readTaxiCloseResult(raw: unknown): TaxiCloseResult {
  const result = raw as TaxiCloseResult;
  if (result?.schema === "taxi-consent-result-v1") {
    const committed = readTaxiOrderResult(result);
    if (committed.state === "committed") return committed;
  } else if (result?.schema === "taxi-consent-close-v1" && result.admission === "blocked"
      && Object.keys(result).sort().join() === "admission,history,schema,state"
      && ((result.state === "closed" && result.history === "not_created")
          || (result.state === "retired" && result.history === "created_unavailable")
          || (result.state === "unknown" && result.history === "unverified"))) return result;
  throw new Error("Invalid taxi close result");
}
export function readRecoveredTaxiOrder(raw: unknown, id: number, userId: number): InstantOrder {
  const order = raw as InstantOrder;
  if (!order || order.id !== id || !Number.isSafeInteger(id) || id <= 0 || order.role !== "passenger"
      || !["created", "searching", "offered", "accepted", "arriving", "onboard", "done", "cancelled", "expired"].includes(order.status)
      || (order.passenger_id != null && order.passenger_id !== userId)
      || !Number.isSafeInteger(order.passenger_price_kop) || order.passenger_price_kop < 0) {
    throw new Error("Unconfirmed current order");
  }
  return order;
}

export function clearTaxiPending(storage: Storage, key: string): void {
  if (!key.startsWith(TAXI_CONSENT_MARKER_PREFIX)) throw new Error("Wrong marker scope");
  storage.removeItem(key);
  if (storage.getItem(key) !== null) throw new Error("Pending marker was not removed");
}

/** A coherent but unrelated response does not resolve an unknown attempt. */
export function readTaxiConsentResult(raw: unknown, original: TaxiConsentQuote, userId: number): InstantOrder {
  const result = raw as { order: InstantOrder; consent: { quote: unknown; accepted: unknown } };
  const quoted = readTaxiQuote(result?.consent?.quote);
  const accepted = readTaxiQuote(result?.consent?.accepted);
  const order = result.order;
  if (!sameTaxiQuote(quoted, original) || accepted.quote_id !== original.quote_id
      || accepted.request_key !== original.request_key || accepted.payable_kop > original.payable_kop
      || accepted.expires_at !== original.expires_at || accepted.promo_discount_kop !== original.promo_discount_kop
      || !order || !Number.isSafeInteger(order.id) || order.id <= 0 || order.role !== "passenger"
      || !["created", "searching", "offered", "accepted", "arriving", "onboard", "done", "cancelled", "expired"].includes(order.status)
      || (order.passenger_id != null && order.passenger_id !== userId)) throw new Error("Unconfirmed taxi result");
  return order;
}
