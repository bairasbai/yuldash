// ================================================================
//  Выключатели направлений — запуск волнами (решение Александра, 13.10.2026).
//
//  Волна 1 — только попутки. Такси, курьеры, посылки, реклама и «Скидки по пути»
//  включает СЕРВЕР (GET /config/features), сайт обновлять не нужно. Здесь — только
//  чистые правила и «хранилище» с подставными зависимостями: без React, без сети,
//  без браузера — чтобы проверять их набором `tests/features-test.mjs`.
//  Проводка к React и к настоящей сети — `src/features.ts`.
//
//  Правило безопасности одно: НЕИЗВЕСТНО → ВЫКЛЮЧЕНО. Первый запуск без сети, битый
//  ответ, незнакомое или не-булево поле — человек увидит «Скоро в Юлдаше», а не экран,
//  который упрётся в отказ сервера. Попутки (`rides`) флага не имеют и всегда включены.
//
//  Зеркало Android: FeatureFlags.kt / ComingSoon.kt.
// ================================================================

/** Ключ в localStorage. Флаги одинаковы для всех — это настройка устройства, не человека. */
export const FEATURES_KEY = "yuldash.features";

/** Сколько запомненное считается свежим. Сервер кеширует ответ на минуту — чаще незачем. */
export const FEATURES_MAX_AGE_MS = 5 * 60 * 1000;

/** После неудачной попытки не стучимся снова раньше этого срока (сеть лежит — не молотим). */
export const FEATURES_RETRY_GAP_MS = 15 * 1000;

export interface Features {
  /** Попутки: всегда включены. */
  readonly rides: true;
  readonly taxi: boolean;
  readonly courier: boolean;
  readonly parcels: boolean;
  readonly ads: boolean;
  readonly business: boolean;
}

/** Состояние для экрана. `ready` — ответ известен (из запомненного или от сервера). */
export type FeatureState = Features & { readonly ready: boolean };

/** Всё выключено, кроме попуток. */
export const FEATURES_OFF: Features = Object.freeze({
  rides: true,
  taxi: false,
  courier: false,
  parcels: false,
  ads: false,
  business: false,
});

const KEYS = ["taxi", "courier", "parcels", "ads", "business"] as const;

function isRecord(raw: unknown): raw is Record<string, unknown> {
  return typeof raw === "object" && raw !== null && !Array.isArray(raw);
}

/**
 * Любое значение → набор флагов. Не бросает исключений.
 * Включено только то, где сервер прямо сказал `true`; нет поля, строка «true», число 1,
 * null — всё это «выключено». `rides` всегда `true`, что бы ни пришло.
 */
export function parseFeatures(raw: unknown): Features {
  if (!isRecord(raw)) return FEATURES_OFF;
  const on = (key: (typeof KEYS)[number]) => raw[key] === true;
  return Object.freeze({
    rides: true,
    taxi: on("taxi"),
    courier: on("courier"),
    parcels: on("parcels"),
    ads: on("ads"),
    business: on("business"),
  });
}

/**
 * Ответ сервера → флаги, либо `null`, если это вообще не объект (HTML от упавшего прокси,
 * строка, массив). `null` считается сбоем запроса: хорошее запомненное им не затирают.
 */
export function parseFeaturesResponse(raw: unknown): Features | null {
  return isRecord(raw) ? parseFeatures(raw) : null;
}

// ---------------- запомненное ----------------

export interface CachedFeatures {
  readonly flags: Features;
  /** Когда флаги подтвердил сервер (мс, часы устройства). */
  readonly at: number;
}

/** Минимум методов хранилища, которые нам нужны (и которые легко подделать в тесте). */
export interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

export function serializeFeatures(flags: Features, at: number): string {
  return JSON.stringify({ v: 1, at, flags });
}

/** Текст из хранилища → запомненное. Битый JSON, чужая форма, нет метки времени → `null`. */
export function parseCachedFeatures(text: string | null | undefined): CachedFeatures | null {
  if (!text) return null;
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    return null; // битая запись — как будто её нет: неизвестное считается выключенным
  }
  if (!isRecord(value)) return null;
  const at = value.at;
  if (typeof at !== "number" || !Number.isFinite(at) || at <= 0) return null;
  return { flags: parseFeatures(value.flags), at };
}

export function readFeaturesCache(storage: StorageLike | null | undefined): CachedFeatures | null {
  if (!storage) return null;
  try {
    return parseCachedFeatures(storage.getItem(FEATURES_KEY));
  } catch {
    return null; // хранилище закрыто (приватный режим) — запомненного нет
  }
}

export function writeFeaturesCache(storage: StorageLike | null | undefined, flags: Features, at: number): void {
  if (!storage) return;
  try {
    storage.setItem(FEATURES_KEY, serializeFeatures(flags, at));
  } catch {
    /* хранилище закрыто или переполнено — без запомненного работаем, просто спросим снова */
  }
}

/**
 * Запомненное устарело? Нет метки — устарело; часы откатили назад — тоже (иначе будущая
 * метка держала бы старый ответ вечно).
 */
export function isStale(at: number, now: number, maxAgeMs: number = FEATURES_MAX_AGE_MS): boolean {
  if (!Number.isFinite(at) || at <= 0) return true;
  if (now < at) return true;
  return now - at >= maxAgeMs;
}

/** Пора ли спрашивать сервер: устарело, и последняя попытка была не только что. */
export function shouldRefresh(at: number, now: number, lastAttemptAt: number): boolean {
  if (!isStale(at, now)) return false;
  if (lastAttemptAt > 0 && now >= lastAttemptAt && now - lastAttemptAt < FEATURES_RETRY_GAP_MS) return false;
  return true;
}

// ---------------- решения гейтов ----------------

/** Какой вход закрывают флаги. */
export type Need = "delivery" | "courier" | "business" | "ads";

/** О чём «скоро»: у доставки две половины (посылки «по пути» и курьер), у курьера — своя. */
export type SoonKind = "delivery" | "courier" | "business" | "ads";

/**
 * Открыт ли вход.
 *  • delivery — работает хоть одна половина доставки (посылки «по пути» или курьер);
 *  • courier  — только курьерский режим (заказы, линия, заработок);
 *  • business — «Скидки по пути» (купоны и кабинет бизнеса);
 *  • ads      — реклама.
 */
export function isOpen(need: Need, f: Features): boolean {
  switch (need) {
    case "delivery":
      return f.parcels || f.courier;
    case "courier":
      return f.courier;
    case "business":
      return f.business;
    case "ads":
      return f.ads;
  }
}

/**
 * Что показать вместо закрытого входа; `null` — вход открыт.
 * Курьерский режим при включённых посылках «по пути» не должен говорить «и посылок нет»:
 * тогда «скоро» — только про курьера, а попутная доставка работает.
 */
export function soonKind(need: Need, f: Features): SoonKind | null {
  if (isOpen(need, f)) return null;
  if (need === "courier") return f.parcels ? "courier" : "delivery";
  return need;
}

/** Такси для гостя: закрыто — вместо просьбы войти показываем «Такси скоро». */
export function isTaxiOpen(f: Features): boolean {
  return f.taxi;
}

export type DeliveryType = "poputka" | "courier" | "buy_bring";

/**
 * Какие способы доставки предлагает мастер отправки.
 *  • «По пути» — только пока включены посылки (иначе сервер откажет в POST /parcels);
 *  • «Курьером» и «Купи и привези» — только пока включён курьер.
 */
export function deliveryTypes(f: Features): DeliveryType[] {
  const list: DeliveryType[] = [];
  if (f.parcels) list.push("poputka");
  if (f.courier) list.push("courier", "buy_bring");
  return list;
}

/** Способ, выбранный сразу: «по пути», если он есть, иначе курьер. */
export function defaultDeliveryType(f: Features): DeliveryType {
  return f.parcels ? "poputka" : "courier";
}

/** «Возить → Доступные» (взять посылку «по пути») работает, только пока включены посылки. */
export function isCarryOpen(f: Features): boolean {
  return f.parcels;
}

// ---------------- хранилище ----------------

export interface FeatureStoreDeps {
  /** Хранилище для запомненного; `null` — недоступно. */
  storage: () => StorageLike | null;
  now: () => number;
  /** Сырой ответ GET /config/features (без токена). Отказ = сбой запроса. */
  fetchRaw: () => Promise<unknown>;
}

export interface FeatureStore {
  getSnapshot: () => FeatureState;
  subscribe: (listener: () => void) => () => void;
  /** Спросить сервер. Без `force` — только если запомненное устарело. Не бросает. */
  refresh: (opts?: { force?: boolean }) => Promise<void>;
}

/**
 * Хранилище флагов. Объект-снимок меняется только при изменении состояния (нужно
 * `useSyncExternalStore`: новый объект на каждый вызов зациклил бы отрисовку).
 *
 *  • холодный старт: есть запомненное → `ready` сразу; нет → всё выключено, `ready: false`;
 *  • ответ сервера → новый снимок + запись в хранилище;
 *  • сбой без запомненного → `ready: true` и всё выключено (неизвестное = «скоро», а не
 *    вечный спиннер); сбой с запомненным → прежнее состояние, метка времени не обновляется,
 *    поэтому следующее открытие спросит снова;
 *  • два одновременных вопроса — один запрос.
 */
export function createFeatureStore(deps: FeatureStoreDeps): FeatureStore {
  const listeners = new Set<() => void>();
  let at = 0;
  let lastAttemptAt = 0;
  let inflight: Promise<void> | null = null;

  const cached = readFeaturesCache(deps.storage());
  let snapshot: FeatureState = cached
    ? Object.freeze({ ...cached.flags, ready: true })
    : Object.freeze({ ...FEATURES_OFF, ready: false });
  if (cached) at = cached.at;

  function commit(next: FeatureState): void {
    snapshot = next;
    listeners.forEach((listener) => listener());
  }

  /** Ответа нет: если до этого не было ни запомненного, ни ответа — «неизвестно» разрешилось в «выключено». */
  function settleUnknown(): void {
    if (!snapshot.ready) commit(Object.freeze({ ...FEATURES_OFF, ready: true }));
  }

  function refresh(opts?: { force?: boolean }): Promise<void> {
    if (inflight) return inflight;
    const now = deps.now();
    if (!opts?.force && !shouldRefresh(at, now, lastAttemptAt)) return Promise.resolve();
    lastAttemptAt = now;
    inflight = Promise.resolve()
      .then(() => deps.fetchRaw())
      .then((raw) => {
        const flags = parseFeaturesResponse(raw);
        if (!flags) {
          settleUnknown();
          return;
        }
        at = deps.now();
        writeFeaturesCache(deps.storage(), flags, at);
        commit(Object.freeze({ ...flags, ready: true }));
      })
      .catch(() => settleUnknown())
      .finally(() => {
        inflight = null;
      });
    return inflight;
  }

  return {
    getSnapshot: () => snapshot,
    subscribe(listener) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    refresh,
  };
}
