// ================================================================
//  Какие направления включены — запуск волнами (13.10.2026).
//
//  GET /config/features публичный: гость тоже видит главный экран, а в ответе только
//  флаги. Правила (неизвестное = выключено, свежесть, запомненное) — в featureRules.ts,
//  здесь только проводка: настоящая сеть, настоящий localStorage, хук для экранов.
//
//  Обновляем: при старте приложения, когда вкладку открыли снова, и при входе на любой
//  экран с флагами — но только если запомненному больше пяти минут.
// ================================================================
import { useEffect, useSyncExternalStore } from "react";
import { apiGet } from "./api/client";
import { createFeatureStore, type FeatureState } from "./featureRules";

export type { FeatureState } from "./featureRules";

function deviceStorage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null; // доступ к хранилищу закрыт (приватный режим) — живём без запомненного
  }
}

const store = createFeatureStore({
  storage: deviceStorage,
  now: Date.now,
  fetchRaw: (signal) => apiGet<unknown>("/config/features", { auth: false, signal }),
  online: () => navigator.onLine,
});

/** Текущий снимок для сохранённого callback до следующего React render. */
export function getFeatures(): FeatureState { return store.getSnapshot(); }

/** Мгновенно закрыть новые действия при потере сети. */
export function disableFeatures(): void { store.disable(); }

/** Спросить сервер, если подтверждение устарело. Ошибок наружу не бросает. */
export function refreshFeatures(opts?: { force?: boolean }): Promise<void> {
  return store.refresh(opts);
}

/**
 * Флаги для экрана: `{ rides: true, taxi, courier, parcels, ads, business, ready }`.
 * Пока ответ неизвестен (`ready: false`) всё, кроме попуток, выключено. Экран
 * перерисуется сам, когда ответ придёт; при монтировании тихо проверяем свежесть.
 */
export function useFeatures(): FeatureState {
  const state = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot);
  useEffect(() => {
    void store.refresh();
  }, []);
  return state;
}
