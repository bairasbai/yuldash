import { apiGet, apiPost } from "./client";

export const PAID_SWITCH = "external.paid";
export const PAID_PAUSED = "external_paid_paused";
export const OWNER_SWITCHES = [
  PAID_SWITCH, "external.sms_notices", "login.sms", "login.telegram", "login.vk", "login.whatsapp",
] as const;
export type OwnerSwitchKey = typeof OWNER_SWITCHES[number];

export interface OwnerSwitch {
  stored: boolean | null;
  effective: boolean;
  blockers: string[];
  editable?: boolean;
  revision?: number;
  in_flight?: number;
  updated_at: string | null;
  updated_by_name: string | null;
  history: {
    old_value: boolean | null;
    new_value: boolean;
    admin_name: string | null;
    created_at: string;
    note: string | null;
  }[];
}

export type OwnerSwitches = Partial<Record<OwnerSwitchKey, OwnerSwitch>>;

export function fetchOwnerSwitches(generation: string, signal: AbortSignal) {
  return apiGet<{ switches: OwnerSwitches }>("/admin/switches", { _generation: generation, signal, cache: "no-store" });
}

export function saveOwnerSwitch(key: OwnerSwitchKey, value: boolean, confirm: boolean, generation: string,
                                signal: AbortSignal, revision?: number) {
  return apiPost<OwnerSwitch>(
    `/admin/switches/${key}`, { value, confirm, expected_revision: revision }, { _generation: generation, signal },
  );
}
