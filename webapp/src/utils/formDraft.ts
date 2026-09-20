// ================================================================
//  Черновик формы: заполненное не пропадает, если экран закрылся.
//
//  На айфоне вкладка в фоне выгружается, когда телефону не хватает памяти —
//  а из длинной анкеты уходить ПРИХОДИТСЯ: сфотографировать разрешение,
//  найти ОСАГО в галерее. Вернулся — форма пустая, и всё заново.
//  Приложение это переживает (`rememberSaveable`), сайт до сих пор — нет.
//
//  Анкеты содержат личные поля и ссылки на документы. Черновик доступен
//  только создавшей его сессии; при выходе удаляется общей очисткой.
// ================================================================
import { useEffect, useRef, useState } from "react";
import { getSessionGeneration } from "../api/client";

const PREFIX = "yuldash.draft.";
/** Старше недели — уже не черновик, а мусор: человек давно передумал. */
const MAX_AGE_MS = 7 * 24 * 3600 * 1000;

interface Stored<T> {
  at: number;
  data: T;
  session: string;
}

export function readDraft<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Stored<T>;
    if (!parsed || typeof parsed.at !== "number") return null;
    if (parsed.session !== getSessionGeneration()) return null;
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
    localStorage.setItem(PREFIX + key, JSON.stringify({ at: Date.now(), data, session: getSessionGeneration() }));
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
  const generation = useRef(getSessionGeneration()).current;
  const [value, setValue] = useState<T>(() => {
    const saved = readDraft<T>(key);
    // Поля берём из initial: у сохранённого черновика может не быть новых полей,
    // добавленных в форму позже.
    return saved ? { ...initial, ...saved } : initial;
  });

  useEffect(() => {
    if (generation !== getSessionGeneration()) return;
    writeDraft(key, value);
  }, [key, value, generation]);

  const patch = (p: Partial<T>) => {
    if (generation !== getSessionGeneration()) return;
    setValue((prev) => ({ ...prev, ...p }));
  };
  const forget = () => {
    if (generation !== getSessionGeneration()) return;
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
  const generation = useRef(getSessionGeneration()).current;
  const restored = useRef(false);
  const applyRef = useRef(apply);
  applyRef.current = apply;

  useEffect(() => {
    if (generation !== getSessionGeneration()) return;
    const saved = readDraft<Partial<T>>(key);
    if (saved) applyRef.current(saved);
    restored.current = true;
  }, [key, generation]);

  useEffect(() => {
    if (!restored.current || generation !== getSessionGeneration()) return;
    writeDraft(key, values);
  });
}
