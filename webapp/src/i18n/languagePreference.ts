import type { Lang } from "./dict";
import { devicePreference } from "../utils/devicePreference";

const language = devicePreference<Lang>({
  database: "yuldash-device-language-v1",
  key: "language",
  legacyKey: "yuldash.lang",
  fallback: "ru",
  isValue: (value): value is Lang => value === "ru" || value === "ba",
  onPublish: value => { document.documentElement.lang = value; },
});

export const getLanguage = language.get;
export const restoreLanguage = language.restore;
export const setDeviceLanguage = language.select;
export const subscribeLanguage = language.subscribe;

type SaveStatus = "idle" | "saving" | "failed";
let saveStatus: SaveStatus = "idle";
const saveListeners = new Set<() => void>();

function publishSaveStatus(status: SaveStatus): void {
  saveStatus = status;
  saveListeners.forEach(listener => listener());
}

export function getLanguageSaveStatus(): SaveStatus {
  return saveStatus;
}

export function subscribeLanguageSaveStatus(listener: () => void): () => void {
  saveListeners.add(listener);
  return () => { saveListeners.delete(listener); };
}

export async function saveLanguage(value: Lang): Promise<boolean> {
  if (saveStatus === "saving") return false;
  publishSaveStatus("saving");
  try {
    await setDeviceLanguage(value);
    publishSaveStatus("idle");
    return true;
  } catch {
    publishSaveStatus("failed");
    return false;
  }
}
