import { apiGet, apiPost } from "./client";

export const LOGIN_SWITCHES = ["login.sms", "login.telegram", "login.vk", "login.whatsapp"] as const;
export type LoginSwitch = typeof LOGIN_SWITCHES[number];

export interface OwnerSwitch {
  stored: boolean | null;
  effective: boolean;
  blockers: string[];
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

export type OwnerSwitches = Partial<Record<LoginSwitch, OwnerSwitch>>;

export function fetchOwnerSwitches(generation: string, signal: AbortSignal) {
  return apiGet<{ switches: OwnerSwitches }>("/admin/switches", { _generation: generation, signal, cache: "no-store" });
}

export function saveOwnerSwitch(key: LoginSwitch, value: boolean, confirm: boolean, generation: string, signal: AbortSignal) {
  return apiPost<OwnerSwitch>(`/admin/switches/${key}`, { value, confirm }, { _generation: generation, signal });
}
