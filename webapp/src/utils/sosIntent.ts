import { ApiError, getSessionGeneration } from "../api/client";
import type { SosInput } from "../api/safety";

const PENDING_KEY = "yuldash.sos.pending";
const MAX_AGE_MS = 15 * 60_000;

export interface SosIntent {
  owner: string;
  key: string;
  fingerprint: string;
  createdAt: number;
}

// SOS has a protected slot. Each booking roadside request has its own slot.
const MAX_ROADSIDE_SLOTS = 32;
const MAX_MEMORY_SLOTS = 1 + MAX_ROADSIDE_SLOTS * 2;
const memory = new Map<string, SosIntent>();
let memoryOwner: string | null = null;
const acknowledged = new Set<string>();
const identity = (intent: SosIntent, scope: string) => `${scope}:${intent.key}:${intent.fingerprint}`;
const same = (a: SosIntent | null | undefined, b: SosIntent) =>
  a?.owner === b.owner && a.key === b.key && a.fingerprint === b.fingerprint;
const scopeAllowed = (scope: string) => scope === "sos" || /^roadside-booking-[1-9]\d*$/.test(scope);

export function assertSosOwner(owner: string): void {
  const current = getSessionGeneration(); // Auth storage keeps its existing fail-closed policy.
  if (memoryOwner !== current) {
    memoryOwner = current;
    memory.clear();
    acknowledged.clear();
  }
  if (owner !== current) throw new ApiError(409, "");
}

/** Sorted keys and explicit nulls: this exact object also goes to apiPost. */
export function normalizeSosBody(body: SosInput) {
  return {
    booking_id: body.booking_id ?? null,
    category: body.category,
    lat: body.lat ?? null,
    lng: body.lng ?? null,
    note: body.note ?? "",
    order_id: body.order_id ?? null,
  };
}

function valid(value: unknown, owner: string, scope: string): value is SosIntent {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const v = value as SosIntent;
  const age = Date.now() - v.createdAt;
  return Object.keys(v).sort().join(",") === "createdAt,fingerprint,key,owner"
    && v.owner === owner && typeof v.key === "string" && /^[A-Za-z0-9_-]{16,128}$/.test(v.key)
    && typeof v.fingerprint === "string" && /^[a-f0-9]{64}$/.test(v.fingerprint)
    && Number.isFinite(v.createdAt) && age >= 0 && age <= MAX_AGE_MS
    && !acknowledged.has(identity(v, scope));
}

function read(owner: string): Record<string, SosIntent> | null {
  // Optional metadata must not quarantine auth or prevent an emergency request.
  try {
    const value: unknown = JSON.parse(localStorage.getItem(PENDING_KEY) ?? "null");
    // Retain a pending SOS written by the first single-slot version.
    if (valid(value, owner, "sos")) return { sos: value };
    const slots: Record<string, SosIntent> = {};
    if (value && typeof value === "object" && !Array.isArray(value)) {
      let roadside = 0;
      for (const [scope, intent] of Object.entries(value)) {
        if (!scopeAllowed(scope) || !valid(intent, owner, scope)) continue;
        if (scope === "sos" || roadside++ < MAX_ROADSIDE_SLOTS) slots[scope] = intent;
      }
    }
    return slots;
  } catch { return null; }
}

function persist(slots: Record<string, SosIntent>): void {
  try {
    if (Object.keys(slots).length) localStorage.setItem(PENDING_KEY, JSON.stringify(slots));
    else localStorage.removeItem(PENDING_KEY);
  } catch { /* Optional store; current-process keys remain in memory. */ }
}

function remember(scope: string, intent: SosIntent, stored: Record<string, SosIntent>): void {
  memory.set(scope, intent);
  for (const [key, value] of memory) if (!valid(value, intent.owner, key)) memory.delete(key);
  const overflow = [...memory.keys()].filter(key => key !== "sos" && !stored[key]);
  let overflowCount = overflow.length;
  for (const key of overflow) {
    if (overflowCount <= MAX_ROADSIDE_SLOTS) break;
    if (key !== scope) { memory.delete(key); overflowCount--; }
  }
  // Drop cached durable copies first: their key remains recoverable from storage.
  for (const key of memory.keys()) {
    if (memory.size <= MAX_MEMORY_SLOTS) break;
    if (key !== "sos" && key !== scope && stored[key]) memory.delete(key);
  }
  for (const key of memory.keys()) {
    if (memory.size <= MAX_MEMORY_SLOTS) break;
    if (key !== "sos" && key !== scope) memory.delete(key);
  }
}

function saveSlot(scope: string, intent: SosIntent, slots: Record<string, SosIntent> | null): void {
  remember(scope, intent, slots ?? {});
  if (!slots) return;
  // Never evict an active pending slot to make room: overflow is memory-only.
  const roadside = Object.keys(slots).filter(key => key !== "sos").length;
  if (scope === "sos" || slots[scope] || roadside < MAX_ROADSIDE_SLOTS) slots[scope] = intent;
  persist(slots);
}

/** No note/coordinates/token on disk. Reload restores only an explicit same-body retry. */
export async function prepareSosIntent(body: unknown, owner: string, scope = "sos"): Promise<SosIntent> {
  assertSosOwner(owner);
  if (!scopeAllowed(scope)) throw new ApiError(409, "");
  const bytes = new TextEncoder().encode(JSON.stringify(scope === "sos" ? normalizeSosBody(body as SosInput) : body));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  assertSosOwner(owner);
  const fingerprint = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
  const slots = read(owner);
  const cached = memory.get(scope);
  const previous = [cached, slots?.[scope]].find(value => valid(value, owner, scope) && value.fingerprint === fingerprint);
  if (previous) {
    saveSlot(scope, previous, slots);
    return previous; // Do not extend the original fifteen-minute lifetime.
  }
  const key = typeof crypto.randomUUID === "function" ? crypto.randomUUID()
    : Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, "0")).join("");
  const intent = { owner, key, fingerprint, createdAt: Date.now() };
  assertSosOwner(owner);
  saveSlot(scope, intent, slots);
  return intent;
}

export function acknowledgeSosIntent(intent: SosIntent, scope = "sos"): void {
  if (intent.owner !== getSessionGeneration()) return;
  assertSosOwner(intent.owner);
  const slots = read(intent.owner);
  acknowledged.add(identity(intent, scope)); // Failed cleanup cannot revive an acknowledged intent in memory.
  if (same(memory.get(scope), intent)) memory.delete(scope);
  if (slots && same(slots[scope], intent)) {
    delete slots[scope];
    persist(slots);
  }
}
