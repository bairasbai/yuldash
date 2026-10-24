// ================================================================
//  Отмена в пути, которую эта вкладка уже видела (приёмка PR207, №3). Зеркало Android
//  InTransitCancels (CancelledInTransit.kt).
//
//  Человек на трассе со слабой связью ушёл на SOS и вернулся, перезагрузил страницу, открыл
//  уведомление ещё раз — а сервер не отвечает. Офлайн-паспорт к этому моменту уже удалён
//  (в нём телефон водителя). Без этой записи экран поездки показывал бы только «Не удалось
//  загрузить» — ни SOS, ни «Застрял на трассе», ни госномера.
//
//  Храним в sessionStorage только номер брони (в ключе), владельца и машину с госномером —
//  НИКОГДА телефон. Префикс `yuldash.inTransit.` стирается при выходе и смене входа
//  (utils/privacy.ts). Хранилище недоступно (приватный режим) — молча без записи.
// ================================================================

const KEY = (bookingId: number) => `yuldash.inTransit.${bookingId}`;

export interface InTransitSnapshot {
  car: string;
  plate: string;
}

/** Запись этой вкладки для брони — только своего входа. Нет, чужая или битая — null. */
export function loadInTransitCancel(bookingId: number, owner: number | undefined): InTransitSnapshot | null {
  if (!bookingId || owner == null) return null;
  try {
    const raw = sessionStorage.getItem(KEY(bookingId));
    if (!raw) return null;
    const v = JSON.parse(raw) as { owner?: unknown; car?: unknown; plate?: unknown } | null;
    if (!v || v.owner !== owner) return null;
    return { car: typeof v.car === "string" ? v.car : "", plate: typeof v.plate === "string" ? v.plate : "" };
  } catch {
    return null;
  }
}

/** Запомнить отмену в пути. Пустая машина не стирает уже известную (сервер мог её не прислать). */
export function rememberInTransitCancel(bookingId: number, owner: number | undefined, car: string, plate: string): void {
  if (!bookingId || owner == null) return;
  const old = loadInTransitCancel(bookingId, owner);
  try {
    sessionStorage.setItem(KEY(bookingId), JSON.stringify({ owner, car: car || old?.car || "", plate: plate || old?.plate || "" }));
  } catch {
    /* Приватный режим: экран работает и без памяти вкладки. */
  }
}
