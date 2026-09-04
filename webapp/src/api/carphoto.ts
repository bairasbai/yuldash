// ================================================================
//  Фотоконтроль машины (580-ФЗ) — GET /carphoto, POST /carphoto/photo,
//  POST /carphoto/submit. Зеркало Android CarPhotoScreen.kt.
//
//  Раз в две недели человек показывает, на чём он возит: такси —
//  четыре стороны кузова и салон, курьер — две стороны и багажник.
//  До этого машину видели ОДИН раз, на фото при регистрации.
//
//  Кадры лежат в приватной области (/secure/carphoto/…): видны только
//  владельцу и админу, и через 90 дней удаляются сами.
// ================================================================
import { apiGet, apiPost, apiUpload } from "./client";

/** Режим: у такси спрашиваем салон, у курьера — багажник. */
export type CarPhotoMode = "taxi" | "courier";

/**
 * Плановый обход или требование по жалобе «грязная машина».
 *
 * Разделены намеренно: плановый — обязанность с лестницей и паузой линии, требование —
 * просьба показать то, о чём написал пассажир. Требование работу не ограничивает вообще.
 */
export type CarPhotoKind = "periodic" | "complaint";

/** Один кадр: что снять, подсказка и что уже прислано. */
export interface CarPhotoSlot {
  code: string; // front | back | left | right | salon | trunk
  ru: string;
  ba: string;
  hint_ru: string;
  hint_ba: string;
  url: string | null;
  /** "ok" или код причины: too_small | screenshot | stale | duplicate. */
  verdict: string;
}

/** Требование фото по жалобе: сутки на снимок салона (или багажника у курьера). */
export interface CarPhotoDemand {
  id: number;
  status: string; // waiting | review
  due_at: string;
  hours_left: number;
  overdue: boolean;
  slots: CarPhotoSlot[];
  missing: string[];
}

/** Двуязычное правило: сервер шлёт оба языка, экран берёт нужный. */
export interface BiText {
  ru: string;
  ba: string;
}

/**
 * Состояние контроля.
 *
 * `stage` — ступень мягкой лестницы: ok (срок не вышел) → remind (1–3 дня, только
 * напоминание) → slow (4–7 дней, приоритет вниз) → blocked (пауза до фото). Экран
 * называет последствие ЗАРАНЕЕ: человек должен узнать о нём до того, как оно наступит.
 */
export interface CarPhotoState {
  mode: CarPhotoMode;
  enabled: boolean;
  required: boolean;
  status?: string; // waiting | review | passed | failed
  id?: number;
  seq?: number;
  due_at?: string;
  days_left?: number; // отрицательное = просрочен
  stage?: "ok" | "remind" | "slow" | "blocked";
  late_days?: number;
  winter?: boolean; // зимой чистый кузов не требуем
  check_signs?: boolean; // первый контроль такси: фонарь и «шашечки»
  manual?: boolean;
  reject_reason?: string;
  slots?: CarPhotoSlot[];
  missing?: string[];
  last_passed_at?: string | null;
  keep_days?: number;
  /** Открытое требование по жалобе — если оно сейчас есть. */
  demand?: CarPhotoDemand | null;
  /** По каким пунктам смотрят салон. Все три видны на фото; запаха среди них нет. */
  clean_rules?: BiText[];
}

/** Ответ на один присланный кадр: подошёл или что переснять. */
export interface CarPhotoShot {
  url: string;
  slot: string;
  ok: boolean;
  reason: string;
  message: { ru: string; ba: string };
  missing: string[];
}

export function fetchCarPhoto(
  mode: CarPhotoMode = "taxi",
  signal?: AbortSignal
): Promise<CarPhotoState> {
  return apiGet<CarPhotoState>(`/carphoto?mode=${mode}`, { signal });
}

/** Прислать ОДИН кадр. Вердикт приходит сразу — человек стоит у машины и может переснять. */
export function uploadCarPhoto(
  mode: CarPhotoMode,
  slot: string,
  file: File,
  kind: CarPhotoKind = "periodic",
  signal?: AbortSignal
): Promise<CarPhotoShot> {
  const form = new FormData();
  form.append("file", file);
  return apiUpload<CarPhotoShot>(
    `/carphoto/photo?mode=${mode}&slot=${slot}&kind=${kind}`,
    form,
    { signal }
  );
}

/** Отправить набор кадров. Принято сразу или ушло человеку на просмотр. */
export function submitCarPhoto(
  mode: CarPhotoMode = "taxi",
  kind: CarPhotoKind = "periodic",
  signal?: AbortSignal
): Promise<CarPhotoState> {
  return apiPost<CarPhotoState>("/carphoto/submit", { mode, kind }, { signal });
}
