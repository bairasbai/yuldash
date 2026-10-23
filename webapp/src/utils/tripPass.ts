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
function rememberStage(id: number, generation: string | undefined, stage: "onboard" | "terminal"): void {
  if (!storageFenceEnabled() || !generation) return;
  const key = `${id}:${generation}`;
  if (memoryStages.get(key) !== "terminal") memoryStages.set(key, stage);
}
/** Separate irreversible flags avoid a stale tab overwriting a newer stage. */
export function tripPassStage(id: number, generation: string): "confirmed" | "onboard" | "terminal" {
  const memory = memoryStages.get(`${id}:${generation}`);
  if (memory === "terminal") return "terminal";
  try {
    if (storageOperation(() => localStorage.getItem(STAGE_KEY(id, generation, "terminal")))) return "terminal";
    return memory === "onboard" || storageOperation(() => localStorage.getItem(STAGE_KEY(id, generation, "onboard"))) ? "onboard" : "confirmed";
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
export function clearTripPassBoardingCode(bookingId: number, generation?: string): void {
  rememberStage(bookingId, generation, "onboard");
  try {
    const raw = storageOperation(() => localStorage.getItem(KEY(bookingId)));
    const pass = raw ? JSON.parse(raw) as TripPass : null;
    const ownerGeneration = generation || pass?.sessionGeneration;
    if (ownerGeneration) storageOperation(() => localStorage.setItem(STAGE_KEY(bookingId, ownerGeneration, "onboard"), "1"));
    if (!pass?.boardingCode) return;
    // Сохраняем время самого снимка, это не новое чтение данных с сервера.
    storageOperation(() => localStorage.setItem(KEY(bookingId), JSON.stringify({ ...pass, boardingCode: "" })));
  } catch {
    // Если обновить снимок нельзя, не оставляем в нём использованный код.
    dropTripPass(bookingId, generation);
  }
}

/**
 * Убрать паспорт: поездка завершена или отменена.
 *
 * Держать его дольше нельзя — это телефон живого человека и код посадки,
 * которые после поездки не нужны никому.
 */
export function dropTripPass(bookingId: number, generation?: string): void {
  rememberStage(bookingId, generation, "terminal");
  try {
    const ownerGeneration = generation || loadTripPass(bookingId)?.sessionGeneration;
    if (ownerGeneration) storageOperation(() => localStorage.setItem(STAGE_KEY(bookingId, ownerGeneration, "terminal"), "1"));
  } catch {
    /* Still try removing the private snapshot if a stage marker cannot be stored. */
  }
  try {
    storageOperation(() => localStorage.removeItem(KEY(bookingId)));
    storageOperation(() => localStorage.removeItem(`${KEY(bookingId)}.owner`));
  } catch {
    /* не критично */
  }
}
