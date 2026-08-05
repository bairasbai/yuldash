/**
 * Короткая вибрация на телефоне. Не звук и не картинка, но именно она
 * превращает нажатие в действие: печать щёлкает под пальцем.
 *
 * На компьютере и там, где браузер этого не умеет, просто ничего
 * не происходит.
 */
export function haptic(pattern: number | number[] = 10): void {
  if (typeof navigator === "undefined") return;
  if (!("vibrate" in navigator)) return;
  try {
    navigator.vibrate(pattern);
  } catch {
    // не та ситуация, из-за которой стоит падать
  }
}

/** Печать ломается: щелчок и осыпающийся отзвук. */
export const SEAL_BREAK = [14, 40, 8];
