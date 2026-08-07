// ================================================================
//  Крупный шрифт — ЕДИНАЯ точка правды (доступность, простой режим).
//  Хранится в localStorage; применяется глобальным множителем ко всему
//  интерфейсу. Дизайн-система Юлдаша основана на px (не rem), поэтому
//  честно масштабируем весь UI через CSS `zoom` на <html> — так растёт
//  ВЕСЬ текст (и заодно тач-цели, что для пожилых даже лучше). Значение
//  и множитель определяются ТОЛЬКО здесь — переиспользуй хук/функции в
//  Простом режиме и Настройках, без дублей.
// ================================================================
import { useEffect, useState } from "react";

export type FontScale = "normal" | "large" | "xlarge";

const KEY = "yuldash.fontScale";

/** Множитель к размеру интерфейса. Точка правды — здесь. */
const MULT: Record<FontScale, number> = {
  normal: 1,
  large: 1.15,
  xlarge: 1.3,
};

function isScale(v: unknown): v is FontScale {
  return v === "normal" || v === "large" || v === "xlarge";
}

/** Текущий масштаб из localStorage (дефолт — обычный). */
export function getFontScale(): FontScale {
  const v = localStorage.getItem(KEY);
  return isScale(v) ? v : "normal";
}

/** Применить масштаб к документу. Вызывается на старте (main.tsx) и при смене. */
export function applyFontScale(scale: FontScale = getFontScale()): void {
  const el = document.documentElement;
  const mult = MULT[scale] ?? 1;
  // px-дизайн → глобальный множитель zoom (растит весь текст и отступы).
  // Пустая строка = сбросить в 1 (не оставляем zoom:1, чтобы не влиять на fixed-элементы зря).
  (el.style as unknown as { zoom: string }).zoom = mult === 1 ? "" : String(mult);
  el.dataset.fontScale = scale;
}

// Подписчики (компоненты через useFontScale) — чтобы переключатель в одном месте
// мгновенно обновлял индикатор в другом (Простой режим ↔ Настройки).
const listeners = new Set<() => void>();

/** Сменить масштаб: сохранить + применить + оповестить подписчиков. */
export function setFontScale(scale: FontScale): void {
  localStorage.setItem(KEY, scale);
  applyFontScale(scale);
  listeners.forEach((l) => l());
}

/** Хук для UI: [текущий масштаб, сменить]. Переиспользуй в Настройках/Простом режиме. */
export function useFontScale(): [FontScale, (s: FontScale) => void] {
  const [scale, setScale] = useState<FontScale>(getFontScale);
  useEffect(() => {
    const l = () => setScale(getFontScale());
    listeners.add(l);
    return () => {
      listeners.delete(l);
    };
  }, []);
  return [scale, setFontScale];
}
