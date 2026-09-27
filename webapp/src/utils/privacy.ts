// ================================================================
//  Что стирается с телефона при выходе из аккаунта.
//
//  В сёлах телефон часто один на семью: вышла мать — заходит сын. Всё, что
//  привязано к ЧЕЛОВЕКУ, обязано уйти вместе с ним. Всё, что привязано
//  к УСТРОЙСТВУ (язык, тема, размер шрифта), остаётся — это удобство, а не
//  чужие данные, и стирать его — только раздражать.
//
//  Приложение чистит то же самое (`clearLocalSession` в `ApiClient.kt`).
//  Токены, очередь исходящих и черновики анкет чистятся отдельно, там же,
//  где заводятся.
// ================================================================

import { clearOwnedStorage } from "./ownedStorage";

/** Legacy keys are never adopted without an owner. Kept for migration inventory. */
const PERSONAL_KEYS = [
  "yuldash.outbox.changed", // только сигнал обновления очереди, без текста сообщений
  "yuldash.consents", // согласия 152-ФЗ: их даёт человек, а не телефон
  "yuldash.role", // пассажир/водитель — роль этого человека
  "yuldash.filterPrefs", // маршруты, которые он ищет: куда ездит — личное
  "yuldash.taxi.activeOrder", // номер его заказа такси
  "yuldash.push.web.enabled", // привязка уведомлений к его подписке
  "yuldash.cid", // его идентификатор в аналитике: иначе двое склеятся в одного
  "yuldash.pendingPayment", // платёж, ради которого он уходил в банк
  "yuldash.payMethod", // чем он платит водителю: наличные это привычка человека, не телефона
];

/**
 * Ключи вида «префикс + номер». Живут в sessionStorage — он умирает вместе
 * с вкладкой, но на телефоне вкладка не закрывается неделями, а «выйти и войти
 * другим» занимает минуту. Поэтому чистим и его.
 */
const PERSONAL_PREFIXES = [
  "yuldash.winterAsk.", // «доехал?» по конкретным броням
  "yuldash.winterAskOrder.", // то же по такси-заказам: чей это был заказ — личное
  "yuldash.tracked.", // какие события у него уже отправлены
  "yuldash.scroll.", // где он остановился в ленте — след его просмотра
  // Офлайн-паспорт поездки: телефон водителя, госномер, код посадки. После выхода
  // из аккаунта это чужие персональные данные на общем телефоне — стираем целиком.
  "yuldash.tripPass.",
];

/**
 * Стереть личное, оставить настройки устройства.
 *
 * Язык, тема, размер шрифта, простой режим и отметки «видел онбординг»
 * НЕ трогаем: следующему человеку с тем же телефоном они, скорее всего,
 * подходят, а вреда от них нет.
 */
function clearByPrefix(store: Storage): void {
  const doomed: string[] = [];
  for (let i = 0; i < store.length; i++) {
    const k = store.key(i);
    if (k && PERSONAL_PREFIXES.some((p) => k.startsWith(p))) doomed.push(k);
  }
  doomed.forEach((k) => store.removeItem(k));
}

/** Вкладка хранит своего владельца отдельно от общего localStorage. */
export function syncPersonalSession(generation: string): void {
  try {
    if (sessionStorage.getItem("yuldash.session") === generation) return;
    clearByPrefix(sessionStorage);
    sessionStorage.setItem("yuldash.session", generation);
  } catch {
    /* Хранилище может быть недоступно в приватном режиме. */
  }
}

export function clearPersonalLocal(owner: string): void {
  try {
    clearOwnedStorage(owner);
  } catch {
    /* хранилище недоступно (приватный режим) — стирать нечего */
  }
  try {
    if (sessionStorage.getItem("yuldash.session") === owner) clearByPrefix(sessionStorage);
  } catch {
    /* то же самое для сессионного */
  }
}

// Referenced by privacy classification tooling; legacy values cannot be assigned
// to A or B safely and are deliberately not removed alongside modern namespaces.
export const legacyPersonalKeys = PERSONAL_KEYS;
