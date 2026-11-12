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
