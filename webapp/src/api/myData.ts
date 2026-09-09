import { apiGet, apiPost } from "./client";

/** Живая выписка о данных пользователя — контракт GET /me/data. */
export interface MyData {
  rides: number;
  rides_days: number;
  bookings: number;
  messages: number;
  messages_days: number;
  voices: number;
  voices_days: number;
  notifications: number;
  notifications_days: number;
  driver_docs: number;
  driver_docs_removable: boolean;
  location_stored: boolean;
  card_stored: boolean;
}

export interface MyDataExport {
  filename: string;
  text: string;
}

export function fetchMyData(signal?: AbortSignal): Promise<MyData> {
  return apiGet<MyData>("/me/data", { signal });
}

export function exportMyData(lang: "ru" | "ba"): Promise<MyDataExport> {
  return apiGet<MyDataExport>(`/me/export?lang=${lang}`);
}

export function deleteDriverDocs(): Promise<void> {
  return apiPost<void>("/me/driver-docs/delete", {});
}
