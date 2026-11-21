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

/** No body, quote pair, price, token, user ID or fingerprint is persisted here. */
export function pendingTaxiMarkers(storage: Storage, generation: string, environment: string): string[] {
  const keys = Array.from({ length: storage.length }, (_, i) => storage.key(i))
    .filter((k): k is string => !!k && k.startsWith(TAXI_CONSENT_MARKER_PREFIX));
  const matching: string[] = [];
  for (const key of keys) {
    const marker = JSON.parse(storage.getItem(key) ?? "null");
    if (!marker || Object.keys(marker).sort().join() !== "e,g,v" || marker.v !== 1
        || !consentGeneration(marker.g) || typeof marker.e !== "string"
        || !UUID.test(key.slice(TAXI_CONSENT_MARKER_PREFIX.length))) throw new Error("Invalid pending marker");
    // Reads from a stale tab must never delete a newer session's UNKNOWN barrier.
    if (marker.g === generation && marker.e === environment) matching.push(key);
  }
  return matching;
}

export function markTaxiPending(storage: Storage, generation: string, environment: string): string {
  if (!consentGeneration(generation)) throw new Error("Unscoped consent session");
  const key = TAXI_CONSENT_MARKER_PREFIX + crypto.randomUUID();
  const value = JSON.stringify({ v: 1, g: generation, e: environment });
  storage.setItem(key, value);
  if (storage.getItem(key) !== value) throw new Error("Pending marker was not saved");
  return key;
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
