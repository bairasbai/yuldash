import type { BoardingUndoTerms } from "../api/bookings";
import { compareBoardingTimestamps, isConfirmedBoardingUndo, sameBoardingTimestamp } from "./boardingUndo";
import { storageOperation, storageFenceEnabled, storageFenced } from "./storageHealth";
// ================================================================
//  Офлайн-паспорт поездки. Зеркало Android `data/TripPass.kt` (F11).
//
//  Зачем. Машину сверяют ровно там, где связи может не быть: ночная
//  трасса, перевал, обочина у выезда из села. Без сети веб-версия
//  не могла показать НИЧЕГО — ни госномера, ни кода посадки, ни
//  телефона водителя. Человек стоял у машины и не мог проверить,
//  та ли это машина (сверка клиентов, слой офлайна, 2026-08-31).
//
//  Что храним: снимок подтверждённой брони — маршрут, водитель,
//  госномер, код посадки, точка встречи, цена. Пишется, когда бронь
//  подтверждена и данные уже открыты; стирается, когда поездка
//  закончилась или отменена.
//
//  ПРИВАТНОСТЬ. Телефон водителя — персональные данные. В браузере
//  «секретного хранилища» нет: localStorage читает любой скрипт на
//  этом домене, а на общем телефоне — и следующий человек. Поэтому:
//   • телефон храним, потому что без него офлайн-паспорт бесполезен
//     (позвонить водителю с обочины — это и есть его смысл);
//   • но живёт он ровно столько, сколько живёт поездка: при завершении
//     брони его стирает этот файл, при выходе из аккаунта — общая чистка
//     (`utils/privacy.ts`, префикс `yuldash.tripPass.`). Двух своих путей
//     чистки нарочно не заводим: они расходятся при первой же правке;
//   • в аналитику и логи ничего из паспорта не уходит.
// ================================================================

/** Ключ на бронь: паспортов может быть несколько (две поездки в один день). */
const KEY = (bookingId: number) => `yuldash.tripPass.${bookingId}`;
const STAGE_KEY = (id: number, generation: string, stage: string) => `${KEY(id)}.${stage}.${generation}`;

const memoryStages = new Map<string, "onboard" | "terminal">();
const memoryBoardings = new Map<string, Set<string>>();
const memoryUndos = new Map<string, Set<string>>();
const boardingKey = (id: number, generation: string, stamp: string) => `${STAGE_KEY(id, generation, "onboard")}.${encodeURIComponent(stamp)}`;
const undoKey = (id: number, generation: string, stamp: string) => `${STAGE_KEY(id, generation, "undo")}.${encodeURIComponent(stamp)}`;
function storedBoardings(id: number, generation: string): string[] {
  const prefix = `${STAGE_KEY(id, generation, "onboard")}.`, stamps: string[] = [];
  const size = storageOperation(() => localStorage.length);
  for (let i = 0; i < size; i++) {
    const key = storageOperation(() => localStorage.key(i));
    if (key?.startsWith(prefix) && storageOperation(() => localStorage.getItem(key))) {
      try { stamps.push(decodeURIComponent(key.slice(prefix.length))); } catch { stamps.push(""); /* broken marker keeps the code closed */ }
    }
  }
  return stamps;
}
function rememberStamp(map: Map<string, Set<string>>, id: number, generation: string, stamp: string): void {
  if (!storageFenceEnabled()) return;
  const key = `${id}:${generation}`, stamps = map.get(key) ?? new Set<string>();
  stamps.add(stamp); map.set(key, stamps);
}
function rememberStage(id: number, generation: string | undefined, stage: "onboard" | "terminal"): void {
  if (!storageFenceEnabled() || !generation) return;
  const key = `${id}:${generation}`;
  if (memoryStages.get(key) !== "terminal") memoryStages.set(key, stage);
}
/** Новая серверная посадка замещает историю; квитанция снимает только последнюю эпоху. */
export function tripPassStage(id: number, generation: string): "confirmed" | "onboard" | "terminal" {
  const memory = memoryStages.get(`${id}:${generation}`);
  if (memory === "terminal") return "terminal";
  try {
    if (storageOperation(() => localStorage.getItem(STAGE_KEY(id, generation, "terminal")))) return "terminal";
    const undos = memoryUndos.get(`${id}:${generation}`);
    const legacyUndo = undos?.has("legacy") || storageOperation(() => localStorage.getItem(undoKey(id, generation, "legacy")));
    if (!legacyUndo && (memory === "onboard" || storageOperation(() => localStorage.getItem(STAGE_KEY(id, generation, "onboard"))))) return "onboard";
    const stamps = new Set([...storedBoardings(id, generation), ...(memoryBoardings.get(`${id}:${generation}`) ?? [])]);
    let latest: string | null = null, latestUndone = false;
    for (const stamp of stamps) {
      if (!sameBoardingTimestamp(stamp, stamp)) return "onboard";
      const order = latest === null ? 1 : compareBoardingTimestamps(stamp, latest);
      const undone = !!(undos?.has(stamp) || storageOperation(() => localStorage.getItem(undoKey(id, generation, stamp))));
      if (order === 1) { latest = stamp; latestUndone = undone; }
      else if (order === 0) latestUndone ||= undone;
    }
    if (latest !== null && !latestUndone) return "onboard";
    return "confirmed";
  } catch (error) { if (memory === "onboard") return "onboard"; throw error; }
}

/** Снимок брони для показа без сети. Только то, что нужно у машины. */
export interface TripPass {
  /** Only a fresh server-confirmed snapshot may be read before /me is reachable. */
  ownerId?: number;
  sessionGeneration?: string;
  ownershipId?: string;
  bookingId: number;
  fromCity: string;
  toCity: string;
  departAt: string;
  driverName: string;
  driverCar: string;
  /** Госномер — по нему и сверяют машину у обочины. */
  driverPlate: string;
  driverPhone: string;
  boardingCode: string;
  pickup: string;
  pickupStop?: string | null;
  dropoffStop?: string | null;
  rideHasStops?: boolean;
  price: number;
  seats: number;
  /** Missing in legacy snapshots: never interpret it as cash. */
  payMethod?: "cash" | "sbp" | "negotiate";
  savedAt: number;
}

/**
 * Сохранить паспорт. Зовётся, когда бронь подтверждена и контакты открыты:
 * до этого сохранять нечего, телефона и точки ещё нет.
 *
 * Пустой код посадки не мешает: у части поездок его нет, а госномер и телефон
 * нужны всё равно.
 */
export function saveTripPass(p: Omit<TripPass, "savedAt">): void {
  if (storageFenced()) return;
  try {
    storageOperation(() => localStorage.setItem(KEY(p.bookingId), JSON.stringify({ ...p, savedAt: Date.now() })));
  } catch {
    /* приватный режим или переполнение — офлайн-паспорта просто не будет */
  }
}

/** Достать паспорт. Нет или битый — null, экран просто не покажет карточку. */
export function loadTripPass(bookingId: number): TripPass | null {
  if (storageFenced()) return null;
  try {
    const raw = storageOperation(() => localStorage.getItem(KEY(bookingId)));
    if (!raw) return null;
    const v = JSON.parse(raw) as TripPass;
    if (!v || typeof v !== "object" || v.bookingId !== bookingId) return null;
    if ([v.pickupStop, v.dropoffStop].some(value => value != null && typeof value !== "string") ||
      (v.rideHasStops !== undefined && typeof v.rideHasStops !== "boolean")) return null;
    if (v.sessionGeneration) {
      const stage = tripPassStage(bookingId, v.sessionGeneration);
      if (stage === "terminal") return null;
      if (stage === "onboard") return { ...v, boardingCode: "" };
    }
    return v;
  } catch {
    return null;
  }
}

/** Посадка подтверждена: код больше не нужен, остальные данные ещё нужны в пути. */
export function clearTripPassBoardingCode(bookingId: number, generation?: string, onboardAt?: string | null): void {
  if (!onboardAt) rememberStage(bookingId, generation, "onboard");
  else if (generation) rememberStamp(memoryBoardings, bookingId, generation, onboardAt);
  try {
    const raw = storageOperation(() => localStorage.getItem(KEY(bookingId)));
    const pass = raw ? JSON.parse(raw) as TripPass : null;
    const ownerGeneration = generation || pass?.sessionGeneration;
    if (ownerGeneration) {
      if (onboardAt) storageOperation(() => localStorage.setItem(boardingKey(bookingId, ownerGeneration, onboardAt), "1"));
      else {
        storageOperation(() => localStorage.removeItem(undoKey(bookingId, ownerGeneration, "legacy")));
        memoryUndos.get(`${bookingId}:${ownerGeneration}`)?.delete("legacy");
        storageOperation(() => localStorage.setItem(STAGE_KEY(bookingId, ownerGeneration, "onboard"), "1"));
      }
    }
    if (!pass?.boardingCode) return;
    // Сохраняем время самого снимка, это не новое чтение данных с сервера.
    storageOperation(() => localStorage.setItem(KEY(bookingId), JSON.stringify({ ...pass, boardingCode: "" })));
  } catch {
    // Если обновить снимок нельзя, не оставляем в нём использованный код.
    dropTripPass(bookingId, generation);
  }
}

/** Only an authenticated server receipt can reopen this boarding's code; terminal is permanent. */
export function restoreConfirmedTripPass(bookingId: number, generation: string,
  current: (BoardingUndoTerms & { status?: string }) | null | undefined,
  receipt: BoardingUndoTerms & { status?: string },
  currentGeneration: () => string = () => storageOperation(() => localStorage.getItem("yuldash.session") ?? ""),
): boolean {
  const currentOwner = () => !!generation && currentGeneration() === generation && !storageFenced();
  try {
    if (!currentOwner() || receipt.status !== "confirmed" || current?.status === "done" || current?.status === "cancelled" ||
      tripPassStage(bookingId, generation) === "terminal") return false;
    // A fresh /details after reload carries the receipt; saved per-boarding flags retain its source.
    const source = current?.onboard_at ? current : { onboard_at: receipt.boarding_undo_onboard_at };
    if (!isConfirmedBoardingUndo(source, receipt)) return false;
    const stamps = new Set([...storedBoardings(bookingId, generation), ...(memoryBoardings.get(`${bookingId}:${generation}`) ?? [])]);
    if ([...stamps].some(stamp => {
      const order = compareBoardingTimestamps(stamp, receipt.boarding_undo_onboard_at);
      return order === null || order > 0;
    })) return false;
    for (const stamp of stamps) {
      if (!sameBoardingTimestamp(stamp, receipt.boarding_undo_onboard_at)) continue;
      if (!currentOwner() || tripPassStage(bookingId, generation) === "terminal") return false;
      storageOperation(() => localStorage.setItem(undoKey(bookingId, generation, stamp), receipt.boarding_undo_confirmed_id!));
      rememberStamp(memoryUndos, bookingId, generation, stamp);
    }
    if (storageOperation(() => localStorage.getItem(STAGE_KEY(bookingId, generation, "onboard"))) || memoryStages.get(`${bookingId}:${generation}`) === "onboard") {
      if (!currentOwner() || tripPassStage(bookingId, generation) === "terminal") return false;
      storageOperation(() => localStorage.setItem(undoKey(bookingId, generation, "legacy"), receipt.boarding_undo_confirmed_id!));
      rememberStamp(memoryUndos, bookingId, generation, "legacy");
    }
    return currentOwner() && tripPassStage(bookingId, generation) === "confirmed";
  } catch { return false; }
}

/**
 * Убрать паспорт: поездка завершена или отменена.
 *
 * Держать его дольше нельзя — это телефон живого человека и код посадки,
 * которые после поездки не нужны никому.
 */
export function dropTripPass(bookingId: number, generation?: string): boolean {
  rememberStage(bookingId, generation, "terminal");
  let marked = false;
  try {
    const ownerGeneration = generation || loadTripPass(bookingId)?.sessionGeneration;
    if (ownerGeneration) {
      storageOperation(() => localStorage.setItem(STAGE_KEY(bookingId, ownerGeneration, "terminal"), "1"));
      marked = true;
    }
  } catch {
    /* Still try removing the private snapshot if a stage marker cannot be stored. */
  }
  try {
    storageOperation(() => localStorage.removeItem(KEY(bookingId)));
    storageOperation(() => localStorage.removeItem(`${KEY(bookingId)}.owner`));
  } catch {
    /* не критично */
  }
  return marked;
}
