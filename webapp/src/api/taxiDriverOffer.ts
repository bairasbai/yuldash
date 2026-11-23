import { API_BASE, apiCommand, getAccountSession, isAccountSession } from "./client";
import type { InstantOrder } from "./instant";
import { taxiConsentEnvironment } from "../utils/taxiConsent";

export type DriverOfferOwner = NonNullable<ReturnType<typeof getAccountSession>>;
export type DisplayedDriverOffer = { id: number; tag: string };

export const offerOwnerSnapshot = getAccountSession;
export const isDriverOfferOwner = isAccountSession;

/** Local pilot only; a requested but invalid configuration cannot select the legacy endpoint. */
export function localDriverOfferMode() {
  const flag = import.meta.env.VITE_TAXI_DRIVER_OFFER_PRECONDITION_LOCAL as string | undefined;
  if (flag !== "1") return "legacy";
  return typeof window !== "undefined" && taxiConsentEnvironment(flag, window.location, API_BASE)
    ? "protected" : "blocked";
}

export function driverOfferVersion(card: InstantOrder | null): DisplayedDriverOffer | null {
  if (!card || !Number.isSafeInteger(card.id) || card.id <= 0 ||
      card.role !== "driver" || card.status !== "offered" ||
      typeof card.driver_gross_kop !== "number" || !Number.isSafeInteger(card.driver_gross_kop) || card.driver_gross_kop < 0 ||
      !Array.isArray(card.stops) || !card.stops.every((stop) => stop && typeof stop.text === "string" &&
        Number.isFinite(stop.lat) && Number.isFinite(stop.lng))) return null;
  const tag = (card as InstantOrder & { offer_card_precondition?: unknown }).offer_card_precondition;
  return typeof tag === "string" && /^v1\.[a-f0-9]{64}$/.test(tag) ? { id: card.id, tag } : null;
}

export function sameDriverOffer(a: DisplayedDriverOffer | null, b: DisplayedDriverOffer | null) {
  return a !== null && b !== null && a.id === b.id && a.tag === b.tag;
}

export function fetchProtectedDriverOffer(owner: DriverOfferOwner, signal: AbortSignal) {
  return apiCommand<{ offer: InstantOrder | null; blocked?: string | null }>(
    "/instant/driver/offer", owner, { method: "GET", signal },
  );
}

function ownedAcceptedOrder(value: InstantOrder | null, id: number, owner: DriverOfferOwner) {
  return value?.id === id && value.role === "driver" && value.driver_id === owner.userId &&
    ["accepted", "arriving", "onboard"].includes(value.status) ? value : null;
}

export async function acceptProtectedDriverOffer(version: DisplayedDriverOffer, owner: DriverOfferOwner,
  signal: AbortSignal, allowAttempt: () => Promise<boolean>) {
  const value = await apiCommand<InstantOrder>(`/instant/orders/${version.id}/accept/v2`, owner, {
    method: "POST", signal, oneShot: true, allowAttempt,
    body: JSON.stringify({ offer_card_precondition: version.tag }),
  });
  const order = ownedAcceptedOrder(value, version.id, owner);
  if (!order) throw new Error("Unverified taxi offer result");
  return order;
}

/** Active ownership is positive evidence; an absent/offered record cannot prove non-commit. */
export async function findProtectedDriverAcceptance(id: number, owner: DriverOfferOwner, signal: AbortSignal) {
  const value = await apiCommand<InstantOrder>(`/instant/orders/${id}`, owner, { method: "GET", signal });
  return ownedAcceptedOrder(value, id, owner);
}
