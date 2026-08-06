// ================================================================
//  Тема оформления — ЕДИНАЯ точка правды (светлая / тёмная / системная).
//  До этого тема шла ТОЛЬКО от системы (prefers-color-scheme). Здесь
//  добавляем ручной оверрайд, переживающий перезагрузку (localStorage):
//   • "system" — снимаем data-theme, дизайн-токены берутся из
//     @media (prefers-color-scheme) в index.css (как раньше);
//   • "light"/"dark" — ставим data-theme на <html>, CSS-переменные
//     жёстко переопределяются (см. index.css → :root[data-theme=...]).
//  Значение и применение определяются ТОЛЬКО здесь — переиспользуй
//  хук useTheme в Настройках, без дублей (как fontScale.ts).
// ================================================================
import { useEffect, useState } from "react";

export type ThemeMode = "system" | "light" | "dark";

const KEY = "yuldash.theme";

function isMode(v: unknown): v is ThemeMode {
  return v === "system" || v === "light" || v === "dark";
}

/** Текущий режим темы из localStorage (дефолт — системный). */
export function getTheme(): ThemeMode {
  const v = localStorage.getItem(KEY);
  return isMode(v) ? v : "system";
}

/** Применить тему к документу. Вызывается на старте (main.tsx) и при смене. */
export function applyTheme(mode: ThemeMode = getTheme()): void {
  const el = document.documentElement;
  if (mode === "system") {
    // Возврат к системной теме: убираем ручной оверрайд, работает @media.
    delete el.dataset.theme;
  } else {
    el.dataset.theme = mode; // "light" | "dark" — жёстко (см. index.css)
  }
}

// Подписчики (компоненты через useTheme) — чтобы переключатель мгновенно
// обновлял индикатор везде, где он показан.
const listeners = new Set<() => void>();

/** Сменить тему: сохранить + применить + оповестить подписчиков. */
export function setTheme(mode: ThemeMode): void {
  localStorage.setItem(KEY, mode);
  applyTheme(mode);
  listeners.forEach((l) => l());
}

/** Хук для UI: [текущая тема, сменить]. Переиспользуй в Настройках. */
export function useTheme(): [ThemeMode, (m: ThemeMode) => void] {
  const [mode, setMode] = useState<ThemeMode>(getTheme);
  useEffect(() => {
    const l = () => setMode(getTheme());
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  }, []);
  return [mode, setTheme];
}
