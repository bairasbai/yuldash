// ================================================================
//  «Откуда» и «Куда»: список городов с сервера, как его читают и как часто перечитывают
//  (зеркало NearbyCityFilter.kt, data/RideCities.kt и NearbyCityFilterTest в приложении).
//
//  Исполняем настоящие nearbyFeed.ts и api/discovery.ts; сеть и часы подменены.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const bundle = await build({
  stdin: {
    contents:
      "export * from './src/api/client'; export * from './src/nearbyFeed'; export { fetchRideCities, parseRideCities } from './src/api/discovery';",
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
  let reply = () => new Response(JSON.stringify({ from: [{ ru: "Баймак", ba: "Баймаҡ" }], to: [] }), { status: 200 });
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), headers: init?.headers ?? {} });
    return reply();
  };
  return { m, calls, setReply: (fn) => (reply = fn) };
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

const ru = (a) => a;
const ba = (_a, b) => b;
const MIN = 60_000;

// ---------------------------------------------------------------- разбор /rides/cities

await test("разбор: оба написания на месте", async ({ m }) => {
  const r = m.parseRideCities({ from: [{ ru: "Баймак", ba: "Баймаҡ" }, { ru: "Сибай", ba: "Сибай" }], to: [{ ru: "Уфа", ba: "Өфө" }] });
  assert.deepEqual(r.from, [{ ru: "Баймак", ba: "Баймаҡ" }, { ru: "Сибай", ba: "Сибай" }]);
  assert.deepEqual(r.to, [{ ru: "Уфа", ba: "Өфө" }]);
});

await test("разбор: без русского имени город пропускаем, без башкирского — берём русское", async ({ m }) => {
  const r = m.parseRideCities({
    from: [{ ru: "", ba: "Өфө" }, { ba: "Сибай" }, { ru: "  Уфа  ", ba: "" }, { ru: "Белорецк" }, { ru: "Кумертау", ba: "   " }],
    to: [],
  });
  assert.deepEqual(r.from, [{ ru: "Уфа", ba: "Уфа" }, { ru: "Белорецк", ba: "Белорецк" }, { ru: "Кумертау", ba: "Кумертау" }]);
});

await test("разбор: мусор в ответе не роняет ленту", async ({ m }) => {
  assert.deepEqual(m.parseRideCities(null), { from: [], to: [] });
  assert.deepEqual(m.parseRideCities("oops"), { from: [], to: [] });
  assert.deepEqual(m.parseRideCities({ from: "нет", to: 5 }), { from: [], to: [] });
  assert.deepEqual(m.parseRideCities({ from: [null, 7, "Уфа", { ru: 3 }], to: [] }), { from: [], to: [] });
  assert.deepEqual(m.parseRideCities({}), { from: [], to: [] });
});

await test("fetchRideCities ходит на /rides/cities с токеном и отдаёт разобранный ответ", async ({ m, calls }) => {
  m.setSession("tok-A", "refresh-A");
  const r = await m.fetchRideCities();
  assert.equal(calls.length, 1);
  assert.equal(new URL(calls[0].url).pathname, "/rides/cities");
  assert.equal(calls[0].headers.Authorization, "Bearer tok-A", "сервер прячет города заблокированных, как в ленте");
  assert.deepEqual(r, { from: [{ ru: "Баймак", ba: "Баймаҡ" }], to: [] });
});

await test("гость получает города без токена", async ({ m, calls }) => {
  await m.fetchRideCities();
  assert.equal(calls[0].headers.Authorization, undefined);
});

await test("старый сервер без ручки (404) — ошибка наверх; лента её глотает и чипов не рисует", async ({ m, setReply }) => {
  setReply(() => new Response(JSON.stringify({ detail: "Not Found" }), { status: 404 }));
  await assert.rejects(() => m.fetchRideCities());
});

// ---------------------------------------------------------------- как часто перечитываем

await test("пора перечитывать: ни разу не приходил, устарел (≥ 2 мин) или часы отмотали назад", async ({ m }) => {
  assert.equal(m.CITIES_REFRESH_MS, 2 * MIN);
  assert.equal(m.citiesStale(0, 5), true, "ещё не приходил — в том числе после сбоя сети");
  assert.equal(m.citiesStale(1000, 1000 + 2 * MIN - 1), false);
  assert.equal(m.citiesStale(1000, 1000 + 2 * MIN), true);
  assert.equal(m.citiesStale(5000, 1000), true, "часы телефона ушли назад — не ждём «будущего»");
});

function fakeClock(start = 1_000_000) {
  let t = start;
  return { now: () => t, advance: (ms) => (t += ms) };
}

await test("троттлинг: за две минуты — один запрос, дальше — снова", async ({ m }) => {
  const clock = fakeClock();
  let hits = 0;
  const loader = m.createRideCitiesLoader(async () => (hits++, { from: [{ ru: "Баймак", ba: "Баймаҡ" }], to: [] }), clock.now);

  assert.deepEqual((await loader.refresh())?.from, [{ ru: "Баймак", ba: "Баймаҡ" }]);
  assert.equal(hits, 1);

  // Лента обновляется раз в ~25 с — список за это время не перечитываем.
  for (let i = 0; i < 4; i++) {
    clock.advance(25_000);
    assert.equal(await loader.refresh(), null, `через ${(i + 1) * 25} с`);
  }
  assert.equal(hits, 1, "сто секунд — всё ещё один запрос");

  clock.advance(25_000); // 125 секунд
  assert.ok(await loader.refresh(), "две минуты прошли — читаем заново");
  assert.equal(hits, 2);
});

await test("после сбоя сети — повтор при ближайшем обновлении, не через две минуты", async ({ m }) => {
  const clock = fakeClock();
  let hits = 0;
  let down = true;
  const loader = m.createRideCitiesLoader(async () => {
    hits++;
    if (down) throw new Error("network");
    return { from: [{ ru: "Сибай", ba: "Сибай" }], to: [] };
  }, clock.now);

  assert.equal(await loader.refresh(), null, "сбой не роняет ленту");
  clock.advance(25_000);
  down = false;
  const second = await loader.refresh();
  assert.deepEqual(second?.from, [{ ru: "Сибай", ba: "Сибай" }], "через 25 с, а не через 2 мин");
  assert.equal(hits, 2);
  clock.advance(25_000);
  assert.equal(await loader.refresh(), null, "теперь снова ждём две минуты");
  assert.equal(hits, 2);
});

await test("пока запрос идёт, второй не отправляется", async ({ m }) => {
  const clock = fakeClock();
  let hits = 0;
  let release;
  const loader = m.createRideCitiesLoader(
    () => (hits++, new Promise((res) => (release = () => res({ from: [{ ru: "Уфа", ba: "Өфө" }], to: [] })))),
    clock.now
  );
  const first = loader.refresh();
  assert.equal(await loader.refresh(), null, "обновление ленты не плодит запросы");
  assert.equal(hits, 1);
  release();
  assert.ok(await first);
});

await test("запрос отменили (ушли с экрана) — это не «загружено»: в следующий раз прочитаем", async ({ m }) => {
  const clock = fakeClock();
  let hits = 0;
  const ac = new AbortController();
  const loader = m.createRideCitiesLoader(async () => {
    hits++;
    ac.abort();
    return { from: [{ ru: "Уфа", ba: "Өфө" }], to: [] };
  }, clock.now);
  assert.equal(await loader.refresh(ac.signal), null);
  assert.ok(await loader.refresh(), "список не считается полученным");
  assert.equal(hits, 2);
});

// ---------------------------------------------------------------- подпись чипа и строки шторки

await test("подпись чипа: «Откуда», «Откуда · Баймак», «Откуда · Баймак +1»", async ({ m }) => {
  assert.equal(m.cityChipLabel("Откуда", []), "Откуда");
  assert.equal(m.cityChipLabel("Откуда", ["Баймак"]), "Откуда · Баймак");
  assert.equal(m.cityChipLabel("Откуда", ["Баймак", "Сибай"]), "Откуда · Баймак +1");
  assert.equal(m.cityChipLabel("Куда", ["Уфа", "Сибай", "Мелеуз"]), "Куда · Уфа +2");
});

const options = [{ ru: "Баймак", ba: "Баймаҡ" }, { ru: "Сибай", ba: "Сибай" }];
const found = [{ ru: "Темясово", ba: "Темәс" }];

await test("подпись чипа — на языке человека; популярные первыми, потом найденные поиском", async ({ m }) => {
  assert.deepEqual(m.chosenNames(["Сибай", "Баймак"], options, found, true), ["Баймак", "Сибай"]);
  assert.deepEqual(m.chosenNames(["Сибай", "Баймак"], options, found, false), ["Баймаҡ", "Сибай"]);
  assert.deepEqual(m.chosenNames(["Темясово", "Сибай"], options, found, false), ["Сибай", "Темәс"]);
  assert.equal(m.cityChipLabel(ru("Откуда"), m.chosenNames(["Баймак", "Сибай"], options, found, false)), "Откуда · Баймаҡ +1");
  assert.equal(m.cityChipLabel(ba("Откуда", "Ҡайҙан"), m.chosenNames(["Баймак"], options, found, false)), "Ҡайҙан · Баймаҡ");
});

await test("выбранный город, которого нет ни в списке, ни среди найденных, остаётся русским именем", async ({ m }) => {
  assert.deepEqual(m.chosenNames(["Мелеуз"], options, found, false), ["Мелеуз"]);
});

await test("строки шторки: сначала найденные и выбранные вне списка, потом города с поездками", async ({ m }) => {
  const foundOutside = found.filter((f) => !options.some((o) => o.ru === f.ru)).map((f) => f.ru);
  assert.deepEqual(m.cityRows([], options, found).map((c) => c.ru), [...foundOutside, "Баймак", "Сибай"],
    "найденные поиском остаются до закрытия шторки, даже со снятой галочкой (как в Android)");
  assert.ok(m.cityRows(["Темясово"], options, found).map((c) => c.ru).includes("Темясово"));
  assert.deepEqual(m.cityRows(["Сибай"], options, []).map((c) => c.ru), ["Баймак", "Сибай"], "из списка — не дублируем");
});

await test("выбранный город уехал из списка сервера — галочку всё равно можно снять", async ({ m }) => {
  const rows = m.cityRows(["Мелеуз"], options, []);
  assert.deepEqual(rows[0], { ru: "Мелеуз", ba: "Мелеуз" }, "строка есть, иначе выбор застрял бы до «Все города»");
});

await test("найденный поиском город добавляется один раз", async ({ m }) => {
  const once = m.addFoundCity([], { ru: "Темясово", ba: "Темәс" });
  assert.deepEqual(once, [{ ru: "Темясово", ba: "Темәс" }]);
  assert.deepEqual(m.addFoundCity(once, { ru: "Темясово", ba: "Темәс" }), once);
  assert.equal(m.addFoundCity(once, { ru: "Верхний Лач", ba: "Үрге Лач" }).length, 2);
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
