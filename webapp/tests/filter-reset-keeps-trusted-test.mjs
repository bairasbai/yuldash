// ================================================================
//  «Сбросить» в ленте не выключает «Только проверенные».
//
//  «Только проверенные» — настройка раздела «Безопасность», а не чип ленты (в приложении в ряду
//  фильтров её нет). Раньше «Сбросить · N» стирал и её: одно нажатие молча выключало защиту,
//  которую человек включил сам. Теперь сброс чистит город, цену и удобства, а её оставляет;
//  в счётчике «Сбросить · N» её тоже нет — иначе кнопка «сбросила», а число не изменилось.
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

const full = { city: "Сибай", maxPrice: 700, amenities: ["women_only", "pets"], onlyTrusted: true };

await test("сброс ленты стирает город, цену и удобства, но оставляет «Только проверенные»", async ({ fp }) => {
  fp.saveFilters(full);
  const left = fp.clearFilters();
  assert.deepEqual(left, { city: "", maxPrice: null, amenities: [], onlyTrusted: true });
  assert.deepEqual(fp.loadFilters(), { city: "", maxPrice: null, amenities: [], onlyTrusted: true }, "в хранилище тоже");
});

await test("«Только проверенные» не считается в «Сбросить · N»", async ({ fp }) => {
  assert.equal(fp.feedFilterCount({ city: "", maxPrice: null, amenities: [], onlyTrusted: true }), 0);
  assert.equal(fp.feedFilterCount(full), 4, "город + цена + 2 удобства");
  assert.equal(fp.feedFilterCount({ ...full, onlyTrusted: false }), 4, "от неё число не зависит");
});

await test("после сброса счётчик 0, а лента всё ещё только от проверенных", async ({ fp }) => {
  fp.saveFilters(full);
  const left = fp.clearFilters();
  assert.equal(fp.feedFilterCount(left), 0, "кнопки «Сбросить» больше нет");
  const rides = [
    { from_city: "Уфа", to_city: "Сибай", price: 500, driver_verified: true },
    { from_city: "Уфа", to_city: "Сибай", price: 500, driver_verified: false },
  ];
  assert.deepEqual(fp.applyRideFilters(rides, left).map((r) => r.driver_verified), [true]);
});

await test("без «Только проверенные» сброс убирает запись целиком", async ({ fp, store }) => {
  fp.saveFilters({ ...full, onlyTrusted: false });
  const left = fp.clearFilters();
  assert.equal(left.onlyTrusted, false);
  assert.equal(store.size, 0, "в хранилище ничего не осталось");
});

await test("полный сброс (экран «Фильтры», переключатель на виду) выключает и её", async ({ fp, store }) => {
  fp.saveFilters(full);
  const left = fp.clearFilters(false);
  assert.equal(left.onlyTrusted, false);
  assert.equal(store.size, 0);
});

await test("хранилище недоступно — сброс не падает", async ({ fp }) => {
  fp.saveFilters(full);
  globalThis.localStorage = {
    getItem: () => { throw new Error("blocked"); },
    setItem: () => { throw new Error("blocked"); },
    removeItem: () => { throw new Error("blocked"); },
  };
  assert.doesNotThrow(() => fp.clearFilters());
});

// Экран: счётчик и сброс идут через эти функции, а не через свою арифметику.
const home = readFileSync(root + "src/screens/HomeScreen.tsx", "utf8");
await test("HomeScreen считает «Сбросить · N» через feedFilterCount и сбрасывает через clearFilters()", async () => {
  assert.match(home, /const filterCount = feedFilterCount\(prefs\);/);
  assert.match(home, /setPrefs\(clearFilters\(\)\);/);
  assert.ok(!/prefs\.onlyTrusted \? 1 : 0/.test(home), "«Только проверенные» снова попала в счётчик");
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
