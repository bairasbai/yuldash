// ================================================================
//  Лента «Ближайшие поездки» — логика без экрана (зеркало Android: MapScreen.kt,
//  NearbyFiltersPanel.kt, NearbyCityFilter.kt, ApiClient.getNearbyRidesPaged).
//
//  Всё, что можно проверить без браузера, живёт здесь: какие параметры уходят на сервер,
//  в каком состоянии лента (загрузка / ошибка / пусто / «фильтры всё срезали» / список),
//  когда показывать «Показать ещё», как подписаны чипы и кнопка шторки, как часто
//  перечитывать список городов. Экран (HomeScreen.tsx) только рисует и зовёт эти функции;
//  тесты (tests/nearby-feed-test.mjs) исполняют их настоящими.
// ================================================================
import type { NearQuery, RideCities, RideCity } from "./api/discovery";
import type { Ride } from "./api/rides";
import { pluralRu } from "./utils/format";

/** Как `appText` из LangProvider: пара «русский, башкирский» → строка на языке человека. */
export type AppText = (ru: string, ba: string) => string;

// ---------------------------------------------------------------- условия пассажира

export type NearbyCondition = "women" | "child" | "pets" | "baggage" | "ac" | "nosmoke" | "quiet";

export interface ConditionInfo {
  /** Ключ — тот же, что уходит на сервер (`conditions=`), и тот же, что хранит приложение. */
  key: NearbyCondition;
  ru: string;
  ba: string;
  /** Подпись в «Фильтрах по умолчанию». */
  hintRu: string;
  hintBa: string;
}

/**
 * Ровно семь условий и в этом порядке — как шторка «Фильтры» и экран «Фильтры по умолчанию»
 * в приложении (`NEARBY_CONDITION_KEYS`, `FiltersScreen`). Тексты — слово в слово оттуда.
 */
export const NEARBY_CONDITIONS: readonly ConditionInfo[] = [
  { key: "women", ru: "Только женщины", ba: "Тик ҡатын-ҡыҙ", hintRu: "Показывать только поездки для женщин", hintBa: "Тик ҡатын-ҡыҙ өсөн сәфәрҙәр" },
  { key: "child", ru: "Детское кресло", ba: "Балалар ултырғысы", hintRu: "Есть детское кресло или бустер", hintBa: "Балалар ултырғысы бар" },
  { key: "pets", ru: "С животным", ba: "Хайуан менән", hintRu: "Можно ехать с питомцем", hintBa: "Хайуан менән барырға мөмкин" },
  { key: "baggage", ru: "Багаж", ba: "Йөк", hintRu: "Есть место под багаж", hintBa: "Багаж өсөн урын бар" },
  { key: "ac", ru: "Кондиционер", ba: "Кондиционер", hintRu: "Есть кондиционер в салоне", hintBa: "Салонда кондиционер бар" },
  { key: "nosmoke", ru: "Некурящий", ba: "Тартмаусы", hintRu: "Показывать поездки без курения", hintBa: "Тартыуһыҙ сәфәрҙәр генә" },
  { key: "quiet", ru: "Тихая поездка", ba: "Тыныс сәфәр", hintRu: "Едем без лишних разговоров и громкой музыки", hintBa: "Артыҡ һөйләшеүһеҙ, ҡысҡырып музыкаһыҙ" },
];

export const NEARBY_CONDITION_KEYS: readonly NearbyCondition[] = NEARBY_CONDITIONS.map((c) => c.key);

export function isNearbyCondition(key: unknown): key is NearbyCondition {
  return typeof key === "string" && (NEARBY_CONDITION_KEYS as readonly string[]).includes(key);
}

/** Поставить или снять условие. */
export function toggleCondition(list: readonly NearbyCondition[], key: NearbyCondition): NearbyCondition[] {
  return list.includes(key) ? list.filter((k) => k !== key) : [...list, key];
}

/**
 * Подстраховка на СТАРЫЙ сервер, который про условия не знает и отдаёт всё подряд
 * (`shownNearby` в MapScreen.kt). Новый сервер режет сам — до подсчёта `count` и порций, —
 * тогда этот фильтр ничего не убирает.
 */
export function matchesNearbyConditions(r: Ride, conditions: readonly string[], verifiedOnly: boolean): boolean {
  if (verifiedOnly && !r.driver_verified) return false;
  const has = (key: NearbyCondition) => conditions.includes(key);
  if (has("women") && !(r.women_only || r.driver_is_woman)) return false; // женщины за рулём тоже подходят
  if (has("child") && !r.child_seat) return false;
  if (has("pets") && !r.pets_allowed) return false;
  if (has("baggage") && !r.baggage) return false;
  if (has("ac") && !r.air_conditioner) return false;
  if (has("nosmoke") && r.smoking) return false; // некурящий = поездки, где курить нельзя
  if (has("quiet") && !r.quiet) return false;
  return true;
}

// ---------------------------------------------------------------- что и как просим у сервера

/** Радиус «Ближайших» вокруг человека — как в приложении (`NEARBY_RADIUS_KM`). */
export const NEARBY_RADIUS_KM = 50;
/** Сколько поездок добавляет «Показать ещё». */
export const NEARBY_PAGE = 30;
/** Потолок сервера на одну порцию: больше 200 он всё равно не отдаст. */
export const NEARBY_MAX = 200;

export interface NearbyFilters {
  coords: { lat: number; lng: number } | null;
  /** YYYY-MM-DD или null — все дни. */
  day: string | null;
  conditions: readonly string[];
  /** «Только проверенные» из раздела «Безопасность». */
  verifiedOnly: boolean;
  /** Русские имена выбранных городов. */
  fromCities: readonly string[];
  toCities: readonly string[];
}

/** Выбран ли хоть один город в «Откуда» или «Куда». */
export function cityFilterActive(f: Pick<NearbyFilters, "fromCities" | "toCities">): boolean {
  return f.fromCities.length > 0 || f.toCities.length > 0;
}

/**
 * Параметры запроса ленты.
 *
 * Радиус — только когда знаем место человека И не выбран город: место тогда задаёт сам
 * человек, а «Куда · Сибай» из Уфы иначе дал бы ложное «поездок нет» (поездки — из Баймака).
 * Координаты при этом уходят — по ним считается расстояние на карточках.
 */
export function nearbyQuery(f: NearbyFilters, limit: number): NearQuery {
  const query: NearQuery = { limit: Math.min(Math.max(1, Math.floor(limit)), NEARBY_MAX) };
  if (f.coords) {
    query.lat = f.coords.lat;
    query.lng = f.coords.lng;
    if (!cityFilterActive(f)) query.radius_km = NEARBY_RADIUS_KM;
  }
  if (f.day) query.date = f.day;
  if (f.conditions.length) query.conditions = f.conditions;
  if (f.verifiedOnly) query.verified_only = true;
  if (f.fromCities.length) query.from_cities = f.fromCities;
  if (f.toCities.length) query.to_cities = f.toCities;
  return query;
}

/**
 * Ключ «контекста» ленты: сменился день, место, условия или города — список начинается заново
 * (на первой порции). Лимит «Показать ещё» живёт рядом с ключом и сам обнуляется при его смене —
 * без отдельного сброса и без второго запроса на ту же смену.
 */
export function nearbyContextKey(f: NearbyFilters): string {
  return JSON.stringify([
    f.coords ? [f.coords.lat, f.coords.lng] : null,
    f.day,
    [...f.conditions].sort(),
    f.verifiedOnly,
    [...f.fromCities].sort(),
    [...f.toCities].sort(),
  ]);
}

// ---------------------------------------------------------------- состояния ленты

export type NearbyFeedState = "loading" | "error" | "empty" | "filtered" | "list";

/**
 * В каком состоянии лента (`nearbyState` в MapScreen.kt).
 *
 * «Пусто при включённом условии или городе» — это «filtered» со сбросом, а не обычная заглушка:
 * человеку надо сказать, что поездки есть, просто не такие. «Только проверенные» не в счёт — её
 * отсюда не снять, честнее обычное «пусто» с «Обновить». День тоже не в счёт: «Когда» всегда
 * на виду в ряду.
 */
export function nearbyFeedState(a: {
  loading: boolean;
  error: boolean;
  /** Сколько поездок пришло с сервера. */
  fetched: number;
  /** Сколько осталось после подстраховки для старого сервера. */
  shown: number;
  conditionsOn: boolean;
  cityActive: boolean;
  /** Выбран день («Сегодня», «Завтра», своя дата): пусто на этот день — тоже «с такими условиями». */
  dayOn?: boolean;
}): NearbyFeedState {
  // Пока грузим и показать нечего — скелетон. Приложение ждёт пустой список с сервера (`nearby.isEmpty()`);
  // здесь ещё и «старый список, который новые условия срезали целиком»: смена условия не должна
  // мигнуть ложным «Нет поездок с такими условиями», пока ответ сервера ещё в пути.
  if (a.loading && a.shown === 0) return "loading";
  if (a.fetched === 0) {
    if (a.error) return "error";
    return a.conditionsOn || a.cityActive || a.dayOn ? "filtered" : "empty";
  }
  if (a.shown === 0) return "filtered";
  return "list";
}

/**
 * Показывать ли «Показать ещё»: сервер сообщил, что подходящих больше, чем пришло (`count` уже
 * учитывает условия). Выше потолка порции просить бесполезно — сервер отдаст те же 200.
 */
export function canShowMore(fetched: number, total: number): boolean {
  return fetched < total && fetched < NEARBY_MAX;
}

/** Ряд фильтров виден, пока есть что фильтровать, или хоть один фильтр включён (путь назад). */
export function filtersRowVisible(a: {
  fetched: number;
  day: string | null;
  conditionsOn: boolean;
  citiesOffered: boolean;
  nearOnly: boolean;
}): boolean {
  return a.fetched > 0 || a.day != null || a.conditionsOn || a.citiesOffered || a.nearOnly;
}

// ---------------------------------------------------------------- «Когда»

const pad2 = (n: number) => String(n).padStart(2, "0");

/**
 * День в местной зоне, сдвиг в днях: 0 — сегодня, 1 — завтра.
 *
 * Считаем ЛОКАЛЬНО, а не через UTC: в Уфе разница пять часов, и после семи вечера
 * «сегодня» по UTC — это уже завтра. Человек, ищущий вечернюю поездку, не должен
 * получать пустой список из-за часового пояса сервера.
 */
export function localDayIso(now: Date, shift = 0): string {
  const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() + shift);
  return `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
}

/** «2026-10-18» → «18.10». */
export function dayMonth(iso: string): string {
  return iso.slice(-5).split("-").reverse().join(".");
}

/** Дата подходит для «Другой даты»: настоящий день и не раньше сегодняшнего. */
export function isPickableDay(iso: string, now: Date): boolean {
  return /^\d{4}-\d{2}-\d{2}$/.test(iso) && iso >= localDayIso(now);
}

export interface DayOption {
  /** null — все дни. */
  value: string | null;
  ru: string;
  ba: string;
}

/** Три быстрых варианта дня; «Другая дата…» рисует шторка. */
export function dayOptions(now: Date): DayOption[] {
  return [
    { value: null, ru: "Все дни", ba: "Бөтә көндәр" },
    { value: localDayIso(now), ru: "Сегодня", ba: "Бөгөн" },
    { value: localDayIso(now, 1), ru: "Завтра", ba: "Иртәгә" },
  ];
}

/** Выбран день не из быстрых трёх — значит «другая дата». */
export function isOtherDay(day: string | null, now: Date): boolean {
  return day != null && !dayOptions(now).some((o) => o.value === day);
}

/** Подпись чипа: «Когда», «Когда · Сегодня», «Когда · Завтра»; другая дата — «Когда · 18.10». */
export function whenChipLabel(day: string | null, now: Date, appText: AppText): string {
  const base = appText("Когда", "Ҡасан");
  if (day == null) return base;
  const quick = dayOptions(now).find((o) => o.value === day);
  return `${base} · ${quick ? appText(quick.ru, quick.ba) : dayMonth(day)}`;
}

/** Подпись строки «Другая дата…»: выбрана дата — «Дата · 18.10». */
export function otherDayLabel(day: string | null, now: Date, appText: AppText): string {
  return isOtherDay(day, now) && day
    ? appText(`Дата · ${dayMonth(day)}`, `Көн · ${dayMonth(day)}`)
    : appText("Другая дата…", "Башҡа көн…");
}

// ---------------------------------------------------------------- «Откуда» и «Куда»

/** Подпись чипа: «Откуда», «Откуда · Баймак», «Откуда · Баймак +1». */
export function cityChipLabel(base: string, chosen: readonly string[]): string {
  if (chosen.length === 0) return base;
  if (chosen.length === 1) return `${base} · ${chosen[0]}`;
  return `${base} · ${chosen[0]} +${chosen.length - 1}`;
}

/**
 * Выбранные места на языке человека: сначала из списка (популярные — первыми), потом найденные
 * поиском; то, чего нет ни там, ни там, остаётся русским именем.
 */
export function chosenNames(
  chosen: readonly string[],
  options: readonly RideCity[],
  found: readonly RideCity[],
  ru: boolean
): string[] {
  const known: RideCity[] = [];
  for (const c of [...options, ...found]) if (!known.some((k) => k.ru === c.ru)) known.push(c);
  return [
    ...known.filter((c) => chosen.includes(c.ru)).map((c) => (ru ? c.ru : c.ba)),
    ...chosen.filter((c) => !known.some((k) => k.ru === c)),
  ];
}

/**
 * Строки шторки: сначала выбранные, которых нет в списке сервера (найденные поиском или те, что
 * уже уехали из ленты, — иначе галочку было бы нечем снять), потом города, где поездки есть сейчас.
 */
export function cityRows(chosen: readonly string[], options: readonly RideCity[], found: readonly RideCity[]): RideCity[] {
  // Найденные поиском — до закрытия шторки, даже со снятой галочкой (как в Android, приёмка PR148):
  // иначе вернуть её можно только новым поиском.
  const foundRows = found.filter((f) => !options.some((o) => o.ru === f.ru));
  const chosenRows = chosen
    .filter((c) => !options.some((o) => o.ru === c) && !found.some((f) => f.ru === c))
    .map((c) => ({ ru: c, ba: c }));
  return [...chosenRows, ...foundRows, ...options];
}

/** Добавить найденный поиском город (по русскому имени, без повторов). */
export function addFoundCity(found: readonly RideCity[], city: RideCity): RideCity[] {
  return found.some((f) => f.ru === city.ru) ? [...found] : [...found, city];
}

/** Города меняются медленно, а ленту обновляют раз в ~25 с: список — не чаще раза в 2 минуты. */
export const CITIES_REFRESH_MS = 120_000;

/**
 * Пора ли перечитать список городов: ещё ни разу не пришёл (в том числе после сбоя сети) или
 * устарел. Часы телефона отмотали назад — тоже пора: иначе список застрял бы до «будущего».
 */
export function citiesStale(loadedAtMs: number, nowMs: number): boolean {
  return loadedAtMs === 0 || nowMs < loadedAtMs || nowMs - loadedAtMs >= CITIES_REFRESH_MS;
}

/**
 * Загрузчик списка городов: ходит на сервер не чаще раза в 2 минуты, одним запросом за раз.
 * Сбой не запоминается как «загружено» — следующее обновление ленты попробует снова, а сбой при
 * открытии карты не прячет чипы до следующего входа. `refresh` отдаёт новый список или `null`
 * («не пора» / «уже идёт» / «не вышло» — оставь прежний).
 */
export function createRideCitiesLoader(
  fetcher: (signal?: AbortSignal) => Promise<RideCities>,
  now: () => number = () => Date.now()
) {
  let loadedAt = 0;
  let inflight = false;
  return {
    async refresh(signal?: AbortSignal): Promise<RideCities | null> {
      if (inflight || !citiesStale(loadedAt, now())) return null;
      inflight = true;
      try {
        const cities = await fetcher(signal);
        if (signal?.aborted) return null;
        loadedAt = now();
        return cities;
      } catch {
        return null; // сеть или старый сервер без ручки: чипов нет, лента живёт, в следующий раз повторим
      } finally {
        inflight = false;
      }
    },
  };
}

// ---------------------------------------------------------------- кнопка шторки

/**
 * Кнопка шторки условий: сколько поездок покажет лента с этими условиями (сервер уже посчитал —
 * `count`). Русский меняет окончание, башкирский — нет. Ноль — просто «Готово».
 */
export function showRidesLabel(total: number, appText: AppText): string {
  return total > 0
    ? appText(`Показать ${total} ${pluralRu(total, "поездку", "поездки", "поездок")}`, `${total} сәфәрҙе күрһәтергә`)
    : appText("Нет поездок с такими условиями", "Был шарттар менән сәфәр юҡ");   // 0 — честно, как в Android (приёмка PR148)
}
