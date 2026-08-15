// ================================================================
//  Черновик формы: заполненное не пропадает, если экран закрылся.
//
//  На айфоне вкладка в фоне выгружается, когда телефону не хватает памяти —
//  а из длинной анкеты уходить ПРИХОДИТСЯ: сфотографировать разрешение,
//  найти ОСАГО в галерее. Вернулся — форма пустая, и всё заново.
//  Приложение это переживает (`rememberSaveable`), сайт до сих пор — нет.
//
//  Храним только текст, который человек ввёл сам. Ничего чувствительного:
//  ни телефонов, ни кодов, ни токенов — эти поля просто не отдаём сюда.
// ================================================================
import { useEffect, useRef, useState } from "react";

const PREFIX = "yuldash.draft.";
/** Старше недели — уже не черновик, а мусор: человек давно передумал. */
const MAX_AGE_MS = 7 * 24 * 3600 * 1000;

interface Stored<T> {
  at: number;
  data: T;
}

export function readDraft<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Stored<T>;
    if (!parsed || typeof parsed.at !== "number") return null;
    if (Date.now() - parsed.at > MAX_AGE_MS) {
      localStorage.removeItem(PREFIX + key);
      return null;
    }
    return parsed.data;
  } catch {
    return null; // повреждённый черновик не должен мешать заполнять форму
  }
}

export function writeDraft<T>(key: string, data: T): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify({ at: Date.now(), data }));
  } catch {
    /* переполнено / приватный режим — просто не сохраняем */
  }
}

export function clearDraft(key: string): void {
  try {
    localStorage.removeItem(PREFIX + key);
  } catch {
    /* нечего чистить */
  }
}

/** Стереть все черновики — при выходе из аккаунта (на общем телефоне это чужое). */
export function clearAllDrafts(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith(PREFIX)) keys.push(k);
    }
    keys.forEach((k) => localStorage.removeItem(k));
  } catch {
    /* нечего чистить */
  }
}

/**
 * Как `useState`, но переживает закрытие вкладки.
 *
 * Возвращает `[значение, изменить, забыть]`. «Забыть» зовут после успешной
 * отправки — иначе следующий заход начнётся с уже отправленных данных.
 */
export function useFormDraft<T extends object>(
  key: string,
  initial: T
): [T, (patch: Partial<T>) => void, () => void] {
  const [value, setValue] = useState<T>(() => {
    const saved = readDraft<T>(key);
    // Поля берём из initial: у сохранённого черновика может не быть новых полей,
    // добавленных в форму позже.
    return saved ? { ...initial, ...saved } : initial;
  });

  useEffect(() => {
    writeDraft(key, value);
  }, [key, value]);

  const patch = (p: Partial<T>) => setValue((prev) => ({ ...prev, ...p }));
  const forget = () => {
    clearDraft(key);
    setValue(initial);
  };

  return [value, patch, forget];
}

/**
 * Черновик для формы, которая уже написана на обычных `useState` — чтобы не
 * переписывать рабочий экран ради сохранения.
 *
 * При первом показе отдаёт сохранённое в `apply`, дальше пишет каждое изменение.
 * Порядок важен: пока не восстановили, ничего не пишем — иначе пустая форма
 * первым же кадром затрёт черновик.
 */
export function useDraftSync<T extends object>(
  key: string,
  values: T,
  apply: (saved: Partial<T>) => void
): void {
  const restored = useRef(false);
  const applyRef = useRef(apply);
  applyRef.current = apply;

  useEffect(() => {
    const saved = readDraft<Partial<T>>(key);
    if (saved) applyRef.current(saved);
    restored.current = true;
  }, [key]);

  useEffect(() => {
    if (!restored.current) return;
    writeDraft(key, values);
  });
}
