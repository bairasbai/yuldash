// ================================================================
//  Локальные предпочтения интерфейса (уведомления, звуки).
//  У бэкенда пока нет эндпоинта пользовательских настроек уведомлений —
//  храним честно на устройстве (localStorage), без выдуманного API.
//  Когда появится /me/settings — заменим один слой. Оба флага по
//  умолчанию ВКЛючены (дефолт «получать всё», как в таксопарках).
// ================================================================

export type UiPrefKey = "notifications" | "sounds";

const KEYS: Record<UiPrefKey, string> = {
  notifications: "yuldash.pref.notifications",
  sounds: "yuldash.pref.sounds",
};

/** Значение флага (дефолт — включён: в localStorage хранится только "0" для «выкл»). */
export function getUiPref(key: UiPrefKey): boolean {
  return localStorage.getItem(KEYS[key]) !== "0";
}

/** Сохранить флаг. Включён → убираем ключ (дефолт), выключен → "0". */
export function setUiPref(key: UiPrefKey, value: boolean): void {
  if (value) localStorage.removeItem(KEYS[key]);
  else localStorage.setItem(KEYS[key], "0");
}
