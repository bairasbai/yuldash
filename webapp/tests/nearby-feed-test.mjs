// ================================================================
//  Лента «Ближайшие поездки» как в приложении (PR #147 сервер, PR #148 приложение):
//  что уходит на сервер, когда «Показать ещё», в каком состоянии лента, как подписаны чипы и кнопка.
//
//  Исполняем настоящие nearbyFeed.ts, api/discovery.ts и api/client.ts; сеть подменена.
//  Эталон — MapScreen.kt, NearbyFiltersPanel.kt, NearbyCityFilter.kt, ApiClient.getNearbyRidesPaged.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const bundle = await build({
  stdin: {
    contents:
      "export * from './src/api/client'; export * from './src/nearbyFeed'; export { fetchRidesNear, nearQueryString } from './src/api/discovery';",
    resolveDir: root,
    loader: "ts",
  },
  bundle: true,
  write: false,
  platform: "node",
  format: "esm",
  define: { "import.meta.env": "{}" },
});

let serial = 0;
let bad = 0;

async function env() {
  globalThis.indexedDB = new IDBFactory();
  const storage = new Map([["yuldash.lang", "ru"]]);
  globalThis.localStorage = {
    getItem: (k) => storage.get(k) ?? null,
    setItem: (k, v) => storage.set(k, String(v)),
    removeItem: (k) => storage.delete(k),
  };
  const m = await import("data:text/javascript;base64," + Buffer.from(bundle.outputFiles[0].text + `\n// ${serial++}`).toString("base64"));
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), headers: init?.headers ?? {} });
    return new Response(JSON.stringify({ count: 0, items: [] }), { status: 200 });
  };
  return { m, calls };
}

async function test(name, run) {
  try {
    await run(await env());
    console.log("✓ " + name);
  } catch (e) {
    bad++;
    console.log("✗ " + name + "\n    " + e.message);
  }
}

/** appText как в LangProvider. */
const ru = (a) => a;
const ba = (_a, b) => b;
const NO_FILTERS = { coords: null, day: null, conditions: [], verifiedOnly: false, fromCities: [], toCities: [] };

// ---------------------------------------------------------------- параметры запроса ленты

await test("условия уходят через запятую и по алфавиту — порядок нажатий не важен", async ({ m }) => {
  const q = new URLSearchParams(m.nearQueryString({ conditions: ["quiet", "women", "ac"], limit: 30 }));
  assert.equal(q.get("conditions"), "ac,quiet,women");
  const other = new URLSearchParams(m.nearQueryString({ conditions: ["ac", "women", "quiet"], limit: 30 }));
  assert.equal(other.get("conditions"), "ac,quiet,women");
});

await test("«только проверенные» → verified_only=true; выключена — параметра нет совсем", async ({ m }) => {
  assert.equal(new URLSearchParams(m.nearQueryString({ verified_only: true })).get("verified_only"), "true");
  assert.ok(!m.nearQueryString({ verified_only: false }).includes("verified_only"));
  assert.ok(!m.nearQueryString({}).includes("verified_only"));
});

await test("города «Откуда» и «Куда» — from_cities / to_cities через запятую, по алфавиту, без повторов", async ({ m }) => {
  const q = new URLSearchParams(m.nearQueryString({ from_cities: ["Сибай", "Баймак", "Баймак"], to_cities: ["Уфа"] }));
  assert.equal(q.get("from_cities"), "Баймак,Сибай");
  assert.equal(q.get("to_cities"), "Уфа");
  assert.ok(m.nearQueryString({ from_cities: ["Баймак"] }).includes("from_cities=%D0%91"), "кириллица кодируется");
});

await test("день → date=YYYY-MM-DD; без дня параметра нет", async ({ m }) => {
  assert.equal(new URLSearchParams(m.nearQueryString({ date: "2026-10-18" })).get("date"), "2026-10-18");
  assert.ok(!m.nearQueryString({}).includes("date="));
});

await test("пустые списки и пустые имена параметров не создают", async ({ m }) => {
  const s = m.nearQueryString({ conditions: [], from_cities: [" ", ""], to_cities: [] });
  assert.equal(s, "limit=30");
});

await test("порядок ключей — как в приложении: гео, день, условия, проверенные, города, limit", async ({ m }) => {
  const s = m.nearQueryString({
    to_cities: ["Уфа"], from_cities: ["Баймак"], verified_only: true, conditions: ["pets"], date: "2026-10-18",
    radius_km: 50, lng: 56, lat: 54, limit: 60,
  });
  const keys = [...new URLSearchParams(s).keys()];
  assert.deepEqual(keys, ["lat", "lng", "radius_km", "date", "conditions", "verified_only", "from_cities", "to_cities", "limit"]);
});

await test("nearbyQuery: радиус 50 км — только с местом и без выбранного города", async ({ m }) => {
  const here = { lat: 54.7, lng: 56.0 };
  assert.equal(m.nearbyQuery({ ...NO_FILTERS, coords: here }, 30).radius_km, 50);
  assert.equal(m.nearbyQuery(NO_FILTERS, 30).radius_km, undefined, "места нет — радиуса нет");
  for (const city of [{ fromCities: ["Баймак"] }, { toCities: ["Сибай"] }]) {
    const q = m.nearbyQuery({ ...NO_FILTERS, coords: here, ...city }, 30);
    assert.equal(q.radius_km, undefined, "выбран город — радиус снят (иначе «Куда · Сибай» из Уфы даёт ложное «пусто»)");
    assert.equal(q.lat, 54.7, "а координаты уходят: по ним считается расстояние на карточках");
    assert.equal(q.lng, 56.0);
  }
});

await test("nearbyQuery собирает всё: день, условия, проверенные, города, порцию", async ({ m }) => {
  const q = m.nearbyQuery(
    { coords: null, day: "2026-10-18", conditions: ["women", "quiet"], verifiedOnly: true, fromCities: ["Баймак"], toCities: ["Уфа"] },
    60
  );
  assert.deepEqual(q, {
    limit: 60, date: "2026-10-18", conditions: ["women", "quiet"], verified_only: true, from_cities: ["Баймак"], to_cities: ["Уфа"],
  });
});

await test("порция не больше потолка сервера (200) и не меньше 1", async ({ m }) => {
  assert.equal(m.nearbyQuery(NO_FILTERS, 1000).limit, 200);
  assert.equal(m.nearbyQuery(NO_FILTERS, 0).limit, 1);
  assert.equal(m.nearbyQuery(NO_FILTERS, 30).limit, 30);
});

await test("fetchRidesNear шлёт эти параметры настоящим запросом, с токеном", async ({ m, calls }) => {
  m.setSession("tok-A", "refresh-A");
  await m.fetchRidesNear({ conditions: ["pets", "child"], verified_only: true, from_cities: ["Сибай"], to_cities: ["Уфа"], date: "2026-10-17", limit: 30 });
  assert.equal(calls.length, 1);
  const url = new URL(calls[0].url);
  assert.equal(url.pathname, "/rides/near");
  assert.equal(url.searchParams.get("conditions"), "child,pets");
  assert.equal(url.searchParams.get("verified_only"), "true");
  assert.equal(url.searchParams.get("from_cities"), "Сибай");
  assert.equal(url.searchParams.get("to_cities"), "Уфа");
  assert.equal(url.searchParams.get("date"), "2026-10-17");
  assert.equal(calls[0].headers.Authorization, "Bearer tok-A");
});

await test("смена выбора = новый контекст ленты; порядок нажатий контекст не меняет", async ({ m }) => {
  const a = m.nearbyContextKey({ ...NO_FILTERS, conditions: ["pets", "women"] });
  assert.equal(a, m.nearbyContextKey({ ...NO_FILTERS, conditions: ["women", "pets"] }));
  assert.notEqual(a, m.nearbyContextKey({ ...NO_FILTERS, conditions: ["pets"] }));
  assert.notEqual(a, m.nearbyContextKey({ ...NO_FILTERS, conditions: ["pets", "women"], day: "2026-10-17" }));
  assert.notEqual(a, m.nearbyContextKey({ ...NO_FILTERS, conditions: ["pets", "women"], fromCities: ["Баймак"] }));
  assert.notEqual(a, m.nearbyContextKey({ ...NO_FILTERS, conditions: ["pets", "women"], coords: { lat: 1, lng: 2 } }));
  assert.notEqual(m.nearbyContextKey(NO_FILTERS), m.nearbyContextKey({ ...NO_FILTERS, verifiedOnly: true }));
});

// ---------------------------------------------------------------- «Показать ещё» по count

await test("«Показать ещё» — пока пришло меньше, чем подходит (count сервера)", async ({ m }) => {
  assert.equal(m.canShowMore(30, 75), true);
  assert.equal(m.canShowMore(75, 75), false, "пришло всё");
  assert.equal(m.canShowMore(0, 0), false);
  assert.equal(m.canShowMore(4, 4), false);
});

await test("«Показать ещё» не зависит от того, равна ли порция лимиту: count уже учёл условия", async ({ m }) => {
  // Раньше кнопка пряталась, как только включали условие, и считалась по `rides.length >= limit`.
  assert.equal(m.canShowMore(12, 40), true, "условие включено, пришло 12 из 40 подходящих — кнопка нужна");
});

await test("выше потолка сервера (200) кнопка не нужна: он отдаст те же 200", async ({ m }) => {
  assert.equal(m.canShowMore(200, 5000), false);
  assert.equal(m.canShowMore(199, 5000), true);
});

// ---------------------------------------------------------------- состояния ленты

const S = { loading: false, error: false, fetched: 0, shown: 0, conditionsOn: false, cityActive: false };

await test("состояния: загрузка → скелетон, пока показывать нечего", async ({ m }) => {
  assert.equal(m.nearbyFeedState({ ...S, loading: true }), "loading");
  assert.equal(m.nearbyFeedState({ ...S, loading: true, fetched: 5, shown: 5 }), "list", "список есть — не прячем его скелетоном");
});

await test("смена условия не мигает ложным «Нет поездок…»: пока грузим, это скелетон", async ({ m }) => {
  // старый список срезан новыми условиями целиком, а ответ сервера ещё в пути
  assert.equal(m.nearbyFeedState({ ...S, loading: true, fetched: 5, shown: 0, conditionsOn: true }), "loading");
  assert.equal(m.nearbyFeedState({ ...S, loading: false, fetched: 5, shown: 0, conditionsOn: true }), "filtered");
});

await test("состояния: нет сети → error, обычная пустота → empty", async ({ m }) => {
  assert.equal(m.nearbyFeedState({ ...S, error: true }), "error");
  assert.equal(m.nearbyFeedState(S), "empty");
});

await test("пусто при включённом условии или городе → filtered со сбросом", async ({ m }) => {
  assert.equal(m.nearbyFeedState({ ...S, conditionsOn: true }), "filtered");
  assert.equal(m.nearbyFeedState({ ...S, cityActive: true }), "filtered");
  assert.equal(m.nearbyFeedState({ ...S, conditionsOn: true, cityActive: true }), "filtered");
});

await test("ошибка важнее «filtered»: нет сети — не «сними фильтры»", async ({ m }) => {
  assert.equal(m.nearbyFeedState({ ...S, error: true, conditionsOn: true }), "error");
});

await test("сервер прислал поездки, а подстраховка срезала все → filtered", async ({ m }) => {
  assert.equal(m.nearbyFeedState({ ...S, fetched: 7, shown: 0 }), "filtered");
  assert.equal(m.nearbyFeedState({ ...S, fetched: 7, shown: 3 }), "list");
});

await test("ряд фильтров виден, пока есть что фильтровать или снимать", async ({ m }) => {
  const off = { fetched: 0, day: null, conditionsOn: false, citiesOffered: false, nearOnly: false };
  assert.equal(m.filtersRowVisible(off), false, "нечего ни фильтровать, ни снимать");
  assert.equal(m.filtersRowVisible({ ...off, fetched: 1 }), true);
  assert.equal(m.filtersRowVisible({ ...off, day: "2026-10-17" }), true, "выбран день, поездок нет — путь назад на «Все дни»");
  assert.equal(m.filtersRowVisible({ ...off, conditionsOn: true }), true);
  assert.equal(m.filtersRowVisible({ ...off, citiesOffered: true }), true, "есть города — «Откуда» ведёт к ним, даже если рядом пусто");
  assert.equal(m.filtersRowVisible({ ...off, nearOnly: true }), true);
});

// ---------------------------------------------------------------- подстраховка на старый сервер

const ride = (over = {}) => ({ id: 1, driver_verified: false, ...over });

await test("подстраховка: каждое из семи условий режет по своему полю", async ({ m }) => {
  const cases = [
    ["women", { women_only: true }, {}],
    ["child", { child_seat: true }, {}],
    ["pets", { pets_allowed: true }, {}],
    ["baggage", { baggage: true }, {}],
    ["ac", { air_conditioner: true }, {}],
    ["quiet", { quiet: true }, {}],
  ];
  for (const [key, has, lacks] of cases) {
    assert.equal(m.matchesNearbyConditions(ride(has), [key], false), true, key + ": есть");
    assert.equal(m.matchesNearbyConditions(ride(lacks), [key], false), false, key + ": нет");
  }
  assert.equal(m.matchesNearbyConditions(ride({ smoking: false }), ["nosmoke"], false), true);
  assert.equal(m.matchesNearbyConditions(ride({}), ["nosmoke"], false), true, "поля нет = курить нельзя (как читает приложение)");
  assert.equal(m.matchesNearbyConditions(ride({ smoking: true }), ["nosmoke"], false), false);
});

await test("подстраховка: «Только женщины» — и женщина за рулём подходит", async ({ m }) => {
  assert.equal(m.matchesNearbyConditions(ride({ driver_is_woman: true }), ["women"], false), true);
  assert.equal(m.matchesNearbyConditions(ride({ women_only: true }), ["women"], false), true);
  assert.equal(m.matchesNearbyConditions(ride({}), ["women"], false), false);
});

await test("подстраховка: условия складываются через И, «проверенные» отдельно", async ({ m }) => {
  const r = ride({ pets_allowed: true, quiet: false, driver_verified: true });
  assert.equal(m.matchesNearbyConditions(r, ["pets", "quiet"], false), false);
  assert.equal(m.matchesNearbyConditions(r, ["pets"], true), true);
  assert.equal(m.matchesNearbyConditions(ride({ pets_allowed: true }), ["pets"], true), false, "водитель не проверен");
  assert.equal(m.matchesNearbyConditions(ride({}), [], false), true, "ничего не выбрано — пропускаем всех");
});

await test("условий ровно семь, ключи и порядок — как в приложении", async ({ m }) => {
  assert.deepEqual([...m.NEARBY_CONDITION_KEYS], ["women", "child", "pets", "baggage", "ac", "nosmoke", "quiet"]);
  assert.equal(m.NEARBY_CONDITIONS.length, 7);
  assert.deepEqual(
    m.NEARBY_CONDITIONS.map((c) => c.ru),
    ["Только женщины", "Детское кресло", "С животным", "Багаж", "Кондиционер", "Некурящий", "Тихая поездка"]
  );
  assert.deepEqual(
    m.NEARBY_CONDITIONS.map((c) => c.ba),
    ["Тик ҡатын-ҡыҙ", "Балалар ултырғысы", "Хайуан менән", "Йөк", "Кондиционер", "Тартмаусы", "Тыныс сәфәр"]
  );
  for (const c of m.NEARBY_CONDITIONS) assert.ok(c.hintRu && c.hintBa, c.key + ": у каждого есть подпись на двух языках");
});

await test("toggleCondition ставит и снимает, исходный список не трогает", async ({ m }) => {
  const a = ["pets"];
  assert.deepEqual(m.toggleCondition(a, "quiet"), ["pets", "quiet"]);
  assert.deepEqual(m.toggleCondition(a, "pets"), []);
  assert.deepEqual(a, ["pets"]);
});

// ---------------------------------------------------------------- кнопка шторки: «Показать N поездок»

await test("«Показать N поездок»: русский склоняет", async ({ m }) => {
  const label = (n) => m.showRidesLabel(n, ru);
  assert.equal(label(1), "Показать 1 поездку");
  assert.equal(label(2), "Показать 2 поездки");
  assert.equal(label(4), "Показать 4 поездки");
  assert.equal(label(5), "Показать 5 поездок");
  assert.equal(label(11), "Показать 11 поездок");
  assert.equal(label(12), "Показать 12 поездок");
  assert.equal(label(21), "Показать 21 поездку");
  assert.equal(label(22), "Показать 22 поездки");
  assert.equal(label(25), "Показать 25 поездок");
  assert.equal(label(111), "Показать 111 поездок");
  assert.equal(label(101), "Показать 101 поездку");
});

await test("«Показать N поездок»: башкирский не склоняется", async ({ m }) => {
  for (const n of [1, 2, 5, 11, 21, 100]) assert.equal(m.showRidesLabel(n, ba), `${n} сәфәрҙе күрһәтергә`);
});

await test("ноль поездок — честное «Нет поездок с такими условиями», как в Android", async ({ m }) => {
  assert.equal(m.showRidesLabel(0, ru), "Нет поездок с такими условиями");
  assert.equal(m.showRidesLabel(0, ba), "Был шарттар менән сәфәр юҡ");
});

await test("пусто на выбранный день — «filtered» со сбросом, а не «поездок нет»", async ({ m }) => {
  assert.equal(m.nearbyFeedState({ ...S, dayOn: true }), "filtered");
});

// ---------------------------------------------------------------- «Когда»

// 16 октября 2026, 23:30 по местному времени человека — «сегодня» не должно уехать на завтра.
const lateEvening = new Date(2026, 9, 16, 23, 30);

await test("«сегодня» считается по местному времени, а не по UTC (вечером в Уфе)", async ({ m }) => {
  assert.equal(m.localDayIso(lateEvening), "2026-10-16");
  assert.equal(m.localDayIso(lateEvening, 1), "2026-10-17");
  assert.equal(m.localDayIso(new Date(2026, 11, 31, 12), 1), "2027-01-01", "переход через год");
  assert.equal(m.localDayIso(new Date(2026, 1, 28, 12), 1), "2026-03-01", "конец февраля");
});

await test("варианты дня: Все дни / Сегодня / Завтра", async ({ m }) => {
  const o = m.dayOptions(lateEvening);
  assert.deepEqual(o.map((x) => x.value), [null, "2026-10-16", "2026-10-17"]);
  assert.deepEqual(o.map((x) => x.ru), ["Все дни", "Сегодня", "Завтра"]);
  assert.deepEqual(o.map((x) => x.ba), ["Бөтә көндәр", "Бөгөн", "Иртәгә"]);
});

await test("подпись чипа «Когда»: база, Сегодня, Завтра, другая дата", async ({ m }) => {
  assert.equal(m.whenChipLabel(null, lateEvening, ru), "Когда");
  assert.equal(m.whenChipLabel("2026-10-16", lateEvening, ru), "Когда · Сегодня");
  assert.equal(m.whenChipLabel("2026-10-17", lateEvening, ru), "Когда · Завтра");
  assert.equal(m.whenChipLabel("2026-10-18", lateEvening, ru), "Когда · 18.10");
  assert.equal(m.whenChipLabel(null, lateEvening, ba), "Ҡасан");
  assert.equal(m.whenChipLabel("2026-10-17", lateEvening, ba), "Ҡасан · Иртәгә");
  assert.equal(m.whenChipLabel("2026-12-03", lateEvening, ba), "Ҡасан · 03.12");
});

await test("«Другая дата…» → «Дата · 18.10» после выбора", async ({ m }) => {
  assert.equal(m.otherDayLabel(null, lateEvening, ru), "Другая дата…");
  assert.equal(m.otherDayLabel("2026-10-17", lateEvening, ru), "Другая дата…", "Завтра — не «другая»");
  assert.equal(m.otherDayLabel("2026-10-18", lateEvening, ru), "Дата · 18.10");
  assert.equal(m.otherDayLabel(null, lateEvening, ba), "Башҡа көн…");
  assert.equal(m.otherDayLabel("2026-10-18", lateEvening, ba), "Көн · 18.10");
  assert.equal(m.isOtherDay("2026-10-18", lateEvening), true);
  assert.equal(m.isOtherDay("2026-10-16", lateEvening), false);
  assert.equal(m.isOtherDay(null, lateEvening), false);
});

await test("другую дату можно выбрать только от сегодняшнего дня", async ({ m }) => {
  assert.equal(m.isPickableDay("2026-10-16", lateEvening), true, "сегодня");
  assert.equal(m.isPickableDay("2026-10-20", lateEvening), true);
  assert.equal(m.isPickableDay("2026-10-15", lateEvening), false, "вчера");
  assert.equal(m.isPickableDay("", lateEvening), false, "поле стёрли");
  assert.equal(m.isPickableDay("18.10.2026", lateEvening), false, "не тот формат");
});

await test("dayMonth: 2026-10-18 → 18.10", async ({ m }) => {
  assert.equal(m.dayMonth("2026-10-18"), "18.10");
  assert.equal(m.dayMonth("2027-01-05"), "05.01");
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
