import { apiCommand } from "./client";

export type RecipientRequestOwner = { userId: number; generation: string };

export interface DriverBankRecipient {
  state: "disabled" | "unconfigured" | "configured" | "requires_reconnect";
  version: string | null;
  updated_at: string | null;
  can_revoke: boolean;
}

export interface DriverBankRecipientSetup {
  setup_id: string;
  expires_at: string;
  version: string | null;
  mode: "synthetic";
}

export interface DriverBankRecipientConfirmation {
  version: string;
  confirmed_at: string;
}

const BASE_PATH = "/driver/bank-recipient";

export function readDriverBankRecipient(owner: RecipientRequestOwner, signal: AbortSignal) {
  return apiCommand<DriverBankRecipient>(BASE_PATH, owner, { method: "GET", signal });
}

export function startDriverBankRecipient(owner: RecipientRequestOwner, version: string | null, signal: AbortSignal) {
  return apiCommand<DriverBankRecipientSetup>(`${BASE_PATH}/setup`, owner, {
    method: "POST", signal, oneShot: true, body: JSON.stringify({ version }),
  });
}

export function confirmDriverBankRecipient(owner: RecipientRequestOwner, setup: DriverBankRecipientSetup,
  signal: AbortSignal) {
  return apiCommand<DriverBankRecipientConfirmation>(`${BASE_PATH}/setup/${setup.setup_id}/confirm`, owner, {
    method: "POST", signal, oneShot: true,
    body: JSON.stringify({ consent: true, token: `synthetic-${setup.setup_id}` }),
  });
}

export async function readDriverBankRecipientReceipt(owner: RecipientRequestOwner, setup: DriverBankRecipientSetup,
  signal: AbortSignal) {
  const receipt = await apiCommand<{ confirmation: DriverBankRecipientConfirmation | null }>(
    `${BASE_PATH}/setup/${setup.setup_id}/receipt`, owner, { method: "GET", signal },
  );
  return receipt.confirmation;
}

export function revokeDriverBankRecipient(owner: RecipientRequestOwner, version: string, signal: AbortSignal) {
  return apiCommand<DriverBankRecipient>(`${BASE_PATH}/revoke`, owner, {
    method: "POST", signal, oneShot: true, body: JSON.stringify({ version }),
  });
}
