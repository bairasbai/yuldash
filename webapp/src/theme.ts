import { useEffect, useState } from "react";
import { devicePreference } from "./utils/devicePreference";

export type ThemeMode = "system" | "light" | "dark";

const theme = devicePreference<ThemeMode>({
  database: "yuldash-device-theme-v1",
  key: "theme",
  legacyKey: "yuldash.theme",
  fallback: "system",
  isValue: (value): value is ThemeMode => value === "system" || value === "light" || value === "dark",
  onPublish: applyTheme,
});

export const getTheme = theme.get;
export const restoreTheme = theme.restore;
export const setTheme = theme.select;

export function applyTheme(mode: ThemeMode = getTheme()): void {
  const el = document.documentElement;
  if (mode === "system") delete el.dataset.theme;
  else el.dataset.theme = mode;
}

export function useTheme(): [ThemeMode, (mode: ThemeMode) => Promise<void>] {
  const [mode, setMode] = useState(getTheme);
  useEffect(() => {
    const update = () => setMode(getTheme());
    const unsubscribe = theme.subscribe(update);
    update();
    return unsubscribe;
  }, []);
  return [mode, setTheme];
}
