import { API_BASE, apiCommand, getAccountSession, getSessionGeneration, isAccountSession } from "./client";
import type { InstantOrder, OrderInput } from "./instant";
import { consentGeneration, readTaxiQuote, readTaxiConsentResult, readTaxiCloseResult, readTaxiOrderResult, taxiConsentEnvironment, taxiConsentInput } from "../utils/taxiConsent";

export type TaxiConsentQuote = {
  schema: "taxi-initial-consent-v1"; quote_id: string; request_key: string;
  snapshot_digest: string; pricing_inputs_digest: string; expires_at: string;
  price_kop: number; promo_discount_kop: number; payable_kop: number;
  breakdown: { ride_price_kop: number; pickup_fee_kop: number; options_fee_kop: number; weather_fee_kop: number };
  distance_km: number; eta_min: number; round_trip: boolean;
};
export type TaxiConsentOwner = NonNullable<ReturnType<typeof getAccountSession>>;

export function localTaxiConsentEnvironment(): string | null {
  return taxiConsentEnvironment(import.meta.env.VITE_TAXI_QUOTE_CONSENT_LOCAL, window.location, API_BASE);
}

export function localTaxiConsentContext() {
  const flag = import.meta.env.VITE_TAXI_QUOTE_CONSENT_LOCAL as string | undefined;
  return { requested: !!flag && flag !== "0", environment: localTaxiConsentEnvironment() };
}
export function localTaxiConsentGeneration(): string { return getSessionGeneration(); }

function requireLocal(owner: TaxiConsentOwner): void {
  if (!localTaxiConsentEnvironment() || !isAccountSession(owner) || !consentGeneration(owner.generation)) throw new Error("Local consent unavailable");
}

export async function fetchTaxiConsent(body: OrderInput, owner: TaxiConsentOwner, allowAttempt: () => Promise<boolean>): Promise<TaxiConsentQuote> {
  requireLocal(owner);
  if (body.scheduled_at) throw new Error("Immediate orders only");
  const result = await apiCommand<unknown>("/instant/quotes/v1", owner, {
    method: "POST", body: JSON.stringify(taxiConsentInput(body)), oneShot: true,
    allowAttempt: async () => !!localTaxiConsentEnvironment() && isAccountSession(owner) && await allowAttempt(),
  });
  return readTaxiQuote(result);
}

export async function confirmTaxiConsent(body: string, original: TaxiConsentQuote, owner: TaxiConsentOwner,
  allowAttempt: () => Promise<boolean>): Promise<InstantOrder> {
  requireLocal(owner);
  const result = await apiCommand<{ order: InstantOrder; consent: { quote: unknown; accepted: unknown } }>(
    "/instant/orders/confirmed/v1", owner, { method: "POST", body, oneShot: true, allowAttempt });
  if (!isAccountSession(owner)) throw new Error("Consent session changed");
  return readTaxiConsentResult(result, original, owner.userId);
}

/** Account, browser session, API origin and storage ownership are checked before transmission. */
async function taxiAttemptCommand(path: "/instant/orders/result/v1" | "/instant/orders/close-attempt/v1",
  requestKey: string, owner: TaxiConsentOwner, allowAttempt: () => Promise<boolean>) {
  requireLocal(owner);
  if (!consentGeneration(requestKey)) throw new Error("Invalid attempt key");
  const environment = localTaxiConsentEnvironment();
  const result = await apiCommand<unknown>(path, owner, {
    method: "POST", body: JSON.stringify({ request_key: requestKey }), oneShot: true,
    allowAttempt: async () => localTaxiConsentEnvironment() === environment && isAccountSession(owner) && await allowAttempt(),
  });
  if (!isAccountSession(owner) || localTaxiConsentEnvironment() !== environment) throw new Error("Attempt scope changed");
  return result;
}

export async function fetchTaxiConsentResult(requestKey: string, owner: TaxiConsentOwner, allowAttempt: () => Promise<boolean>) {
  return readTaxiOrderResult(await taxiAttemptCommand("/instant/orders/result/v1", requestKey, owner, allowAttempt));
}

export async function closeTaxiConsentAttempt(requestKey: string, owner: TaxiConsentOwner, allowAttempt: () => Promise<boolean>) {
  return readTaxiCloseResult(await taxiAttemptCommand("/instant/orders/close-attempt/v1", requestKey, owner, allowAttempt));
}
