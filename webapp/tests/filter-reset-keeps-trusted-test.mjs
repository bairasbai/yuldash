// ================================================================
//  Фильтры по умолчанию и «Сбросить фильтры» в ленте.
//
//  1. «Только проверенные» — настройка раздела «Безопасность», а не чип ленты (в приложении в ряду
//     фильтров её нет). «Сбросить фильтры» в пустой ленте снимает условия и города, а её оставляет:
//     иначе одно нажатие молча выключило бы защиту, которую человек включил сам.
//  2. Условия — семь ключей приложения (women, child, pets, baggage, ac, nosmoke, quiet). «Город» и
//     «Цена до» из прежней версии сайта убраны: в приложении их нет, город заменён чипом «Откуда»,
//     а цену сервер не фильтрует — потолок в браузере давал бы ложное «поездок нет».
//     Сохранённое прежней версией не ломает загрузку: старые имена условий переезжают на новые
//     ключи, «город» и «цена» отбрасываются.
//
//  Исполняем настоящий filterPrefs.ts; localStorage подменён.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const { outputFiles } = await build({
  entryPoints: [root + "src/filterPrefs.ts"],
  bundle: true,
  write: false,
  platform: "node",
  format: "esm",
});
let serial = 0;
let bad = 0;

async function env() {
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  };
  const fp = await import("data:text/javascript;base64," + Buffer.from(outputFiles[0].text + `\n// ${serial++}`).toString("base64"));
  return { fp, store };
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

const KEY = "yuldash.filterPrefs";

// ---------------------------------------------------------------- хранение

await test("пусто → никаких условий, «проверенные» выключены", async ({ fp }) => {
  assert.deepEqual(fp.loadFilters(), { conditions: [], onlyTrusted: false });
});

await test("сохранили → загрузили то же; в хранилище только условия и «проверенные»", async ({ fp, store }) => {
  fp.saveFilters({ conditions: ["quiet", "women"], onlyTrusted: true });
  assert.deepEqual(fp.loadFilters(), { conditions: ["women", "quiet"], onlyTrusted: true }, "порядок — как в шторке");
  assert.deepEqual(Object.keys(JSON.parse(store.get(KEY))).sort(), ["conditions", "onlyTrusted"]);
});

await test("прежнее сохранение (город, цена, старые имена условий) не ломает загрузку", async ({ fp, store }) => {
  store.set(KEY, JSON.stringify({
    city: "Сибай", maxPrice: 700, onlyTrusted: true,
    amenities: ["women_only", "child_seat", "pets", "baggage", "air_conditioner", "non_smoking", "quiet"],
  }));
  assert.deepEqual(fp.loadFilters(), {
    conditions: ["women", "child", "pets", "baggage", "ac", "nosmoke", "quiet"],
    onlyTrusted: true,
  });
});

await test("прежнее: город и цена просто отбрасываются", async ({ fp, store }) => {
  store.set(KEY, JSON.stringify({ city: "Уфа", maxPrice: 300, amenities: [], onlyTrusted: false }));
  assert.deepEqual(fp.loadFilters(), { conditions: [], onlyTrusted: false });
  fp.saveFilters(fp.loadFilters());
  const saved = JSON.parse(store.get(KEY));
  assert.ok(!("city" in saved) && !("maxPrice" in saved), "после первого сохранения прежних полей в хранилище нет");
});

await test("незнакомые ключи условий отбрасываются, остальные остаются", async ({ fp, store }) => {
  store.set(KEY, JSON.stringify({ conditions: ["pets", "teleport", 7, null, "quiet", "pets"], onlyTrusted: false }));
  assert.deepEqual(fp.loadFilters().conditions, ["pets", "quiet"]);
});

await test("битое хранилище не роняет экран", async ({ fp, store }) => {
  for (const raw of ["{oops", "null", "[]", '"x"', '{"conditions":"women"}', '{"amenities":{"a":1}}']) {
    store.set(KEY, raw);
    assert.deepEqual(fp.loadFilters(), { conditions: [], onlyTrusted: false }, raw);
  }
});

await test("хранилище недоступно — загрузка и сохранение не падают", async ({ fp }) => {
  globalThis.localStorage = {
    getItem: () => { throw new Error("blocked"); },
    setItem: () => { throw new Error("blocked"); },
    removeItem: () => { throw new Error("blocked"); },
  };
  assert.deepEqual(fp.loadFilters(), { conditions: [], onlyTrusted: false });
  assert.doesNotThrow(() => fp.saveFilters({ conditions: ["pets"], onlyTrusted: false }));
});

await test("«Безопасность» меняет только «проверенные»: условия по умолчанию остаются", async ({ fp }) => {
  fp.saveFilters({ conditions: ["pets"], onlyTrusted: false });
  fp.saveFilters({ ...fp.loadFilters(), onlyTrusted: true });
  assert.deepEqual(fp.loadFilters(), { conditions: ["pets"], onlyTrusted: true });
});

// ---------------------------------------------------------------- вкладка «Поездки»: те же правила

await test("вкладка «Поездки» режет по тем же условиям и по «проверенным»", async ({ fp }) => {
  const rides = [
    { id: 1, driver_verified: true, pets_allowed: true },
    { id: 2, driver_verified: false, pets_allowed: true },
    { id: 3, driver_verified: true },
  ];
  assert.deepEqual(fp.applyRideFilters(rides, { conditions: [], onlyTrusted: false }).map((r) => r.id), [1, 2, 3]);
  assert.deepEqual(fp.applyRideFilters(rides, { conditions: ["pets"], onlyTrusted: false }).map((r) => r.id), [1, 2]);
  assert.deepEqual(fp.applyRideFilters(rides, { conditions: ["pets"], onlyTrusted: true }).map((r) => r.id), [1]);
  assert.deepEqual(fp.applyRideFilters(rides, { conditions: [], onlyTrusted: true }).map((r) => r.id), [1, 3]);
  assert.equal(fp.isFilterActive({ conditions: [], onlyTrusted: false }), false);
  assert.equal(fp.isFilterActive({ conditions: ["quiet"], onlyTrusted: false }), true);
  assert.equal(fp.isFilterActive({ conditions: [], onlyTrusted: true }), true);
});

// ---------------------------------------------------------------- сброс в ленте (исходник экрана)

const home = readFileSync(root + "src/screens/HomeScreen.tsx", "utf8");
const resetBody = (() => {
  const start = home.indexOf("function resetFeedFilters()");
  return home.slice(start, home.indexOf("\n  }\n", start));
})();

await test("сброс в ленте снимает условия и города, больше ничего", async () => {
  assert.ok(resetBody.length > 30, "функция сброса найдена");
  assert.match(resetBody, /setConditions\(\[\]\)/, "условия сняты");
  assert.match(resetBody, /cityFilter\.clear\(\)/, "города сняты");
});

await test("сброс не трогает «Только проверенные» и сохранённые настройки, а день снимает", async () => {
  assert.ok(!/verifiedOnly|onlyTrusted/.test(resetBody), "«проверенные» — настройка «Безопасности», сброс её не касается");
  assert.ok(!/saveFilters|localStorage/.test(resetBody), "«по умолчанию» меняют на экране «Фильтры»");
  // Пусто на выбранный день — тоже «Нет поездок с такими условиями», и сброс снимает день (как Android, приёмка PR148).
  assert.match(resetBody, /setDay\(null\)/);
});

await test("кнопка «Сбросить фильтры» в ленте подключена к этому сбросу", async () => {
  assert.match(home, /onClick=\{resetFeedFilters\}/);
});

await test("лента читает «проверенные» из настроек, а переключать её не умеет", async () => {
  assert.match(home, /const \[verifiedOnly\] = useState\(\(\) => loadFilters\(\)\.onlyTrusted\)/);
  assert.ok(!/setVerifiedOnly/.test(home));
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
