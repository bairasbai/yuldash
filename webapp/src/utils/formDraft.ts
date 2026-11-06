import { storageOperation, storageFenced } from "./storageHealth";
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
  if (storageFenced()) return null;
  try {
    const raw = storageOperation(() => localStorage.getItem(PREFIX + key));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Stored<T>;
    if (!parsed || typeof parsed.at !== "number") return null;
    if (parsed.session !== getSessionGeneration()) return null;
    if (Date.now() - parsed.at > MAX_AGE_MS) {
      storageOperation(() => localStorage.removeItem(PREFIX + key));
      return null;
    }
    return parsed.data;
  } catch {
    return null; // повреждённый черновик не должен мешать заполнять форму
  }
}

export function writeDraft<T>(key: string, data: T): void {
  if (storageFenced()) return;
  try {
    storageOperation(() => localStorage.setItem(PREFIX + key, JSON.stringify({ at: Date.now(), data, session: getSessionGeneration() })));
  } catch {
    /* переполнено / приватный режим — просто не сохраняем */
  }
}

export function clearDraft(key: string): void {
  try {
    storageOperation(() => localStorage.removeItem(PREFIX + key));
  } catch {
    /* нечего чистить */
  }
}

/** Стереть все черновики — при выходе из аккаунта (на общем телефоне это чужое). */
export function clearAllDrafts(): void {
  try {
    const keys: string[] = [];
    for (let i = 0; i < storageOperation(() => localStorage.length); i++) {
      const k = storageOperation(() => localStorage.key(i));
      if (k && k.startsWith(PREFIX)) keys.push(k);
    }
    keys.forEach((k) => storageOperation(() => localStorage.removeItem(k)));
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
 * При первом показе отдаёт сохранённое в `apply`, дальше пишет каждое ИЗМЕНЕНИЕ полей.
 * Порядок важен: пока не восстановили, ничего не пишем — иначе пустая форма
 * первым же кадром затрёт черновик.
 *
 * Пишем только когда поля действительно поменялись (приёмка попутки 24.10.2026). Раньше запись
 * шла после КАЖДОЙ отрисовки: экран звал `clearDraft` после успешной публикации, но следующая же
 * отрисовка («Поездка опубликована», снятие «Отправляем…») записывала те же поля обратно. Водитель
 * открывал «Опубликовать маршрут» снова — и видел прошлый рейс с его остановками; поменял маршрут,
 * а старая остановка совпала с новым концом — «Начало и конец маршрута уже указаны». Первая
 * отрисовка тоже больше не пишет пустые поля поверх сохранённого черновика.
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
  const serialized = JSON.stringify(values);
  const lastSeen = useRef<string | null>(null);

  useEffect(() => {
    if (generation !== getSessionGeneration()) return;
    const saved = readDraft<Partial<T>>(key);
    // Поля первого кадра — исходные, не ввод человека: их не пишем поверх черновика.
    lastSeen.current = serialized;
    if (saved) applyRef.current(saved);
    restored.current = true;
    // serialized первого кадра — намеренно: эффект восстановления выполняется один раз.
  }, [key, generation]);

  useEffect(() => {
    if (!restored.current || generation !== getSessionGeneration()) return;
    if (lastSeen.current === serialized) return;
    lastSeen.current = serialized;
    writeDraft(key, values);
  });
}
