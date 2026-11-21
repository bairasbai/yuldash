import { API_BASE, authSessionId, isAccountSession } from "../api/client";
import type { DriverBankRecipientSetup, RecipientRequestOwner } from "../api/driverBankRecipient";

const VERSION = /^[a-f0-9]{32}$/;
const STORAGE = "yuldash.driver.bank.recipient.setup.";

export function currentRecipientSetupOwner(owner: RecipientRequestOwner) {
  const sessionId = authSessionId();
  if (!sessionId || !isAccountSession(owner)) return null;
  return { ...owner, sessionId, apiBase: API_BASE };
}

type SetupOwner = NonNullable<ReturnType<typeof currentRecipientSetupOwner>>;

export function recipientSetupOwnerActive(owner: SetupOwner) {
  return owner.apiBase === API_BASE && isAccountSession(owner) && authSessionId() === owner.sessionId;
}

function slot(owner: SetupOwner) {
  return `${STORAGE}${owner.apiBase}:${owner.userId}:${owner.sessionId}`;
}

function validSetup(setup: unknown): setup is DriverBankRecipientSetup {
  if (!setup || typeof setup !== "object") return false;
  const row = setup as Partial<DriverBankRecipientSetup>;
  return row.mode === "synthetic" && typeof row.setup_id === "string" && VERSION.test(row.setup_id)
    && (row.version === null || (typeof row.version === "string" && VERSION.test(row.version)))
    && typeof row.expires_at === "string" && Number.isFinite(Date.parse(row.expires_at));
}

export function readRecipientSetup(owner: SetupOwner): DriverBankRecipientSetup | null {
  if (!recipientSetupOwnerActive(owner)) return null;
  const raw = localStorage.getItem(slot(owner));
  if (raw === null) return null;
  const data: unknown = JSON.parse(raw);
  if (!validSetup(data)) throw new Error("Invalid recipient setup");
  return data;
}

export function keepRecipientSetup(owner: SetupOwner, setup: DriverBankRecipientSetup) {
  if (!recipientSetupOwnerActive(owner) || !validSetup(setup)) throw new Error("Recipient setup is unavailable");
  localStorage.setItem(slot(owner), JSON.stringify({
    mode: setup.mode, setup_id: setup.setup_id, expires_at: setup.expires_at, version: setup.version,
  }));
}

export function clearRecipientSetup(owner: SetupOwner, setup: DriverBankRecipientSetup) {
  if (readRecipientSetup(owner)?.setup_id === setup.setup_id) {
    localStorage.removeItem(slot(owner));
  }
}
