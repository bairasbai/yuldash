import { apiCommand } from "./client";

export const DRIVER_PAY_METHODS = ["cash", "sbp"] as const;
export type DriverPayMethod = typeof DRIVER_PAY_METHODS[number];
type Owner = { userId: number; generation: string };

export interface DriverPaySettings {
  methods: DriverPayMethod[];
  updated_at: string | null;
}

export function getDriverPaySettings(owner: Owner, signal: AbortSignal) {
  return apiCommand<DriverPaySettings>("/driver/pay-settings", owner, { method: "GET", signal });
}

export function setDriverPaySettings(
  owner: Owner, methods: DriverPayMethod[], expectedUpdatedAt: string | null, signal: AbortSignal,
) {
  return apiCommand<DriverPaySettings>("/driver/pay-settings", owner, {
    method: "PUT", signal, body: JSON.stringify({ methods, expected_updated_at: expectedUpdatedAt }),
  });
}
