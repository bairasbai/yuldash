import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";

globalThis.indexedDB = new IDBFactory();
const disk = new Map();
globalThis.localStorage = {
  get length() { return disk.size; },
  key: i => [...disk.keys()][i] ?? null,
  getItem: key => disk.get(key) ?? null,
  setItem: (key, value) => disk.set(key, value),
  removeItem: key => disk.delete(key),
};
const waits = new Map();
const lock = (key, operation) => {
  const done = (waits.get(key) ?? Promise.resolve()).catch(() => {}).then(operation);
  waits.set(key, done);
  return done;
};
Object.defineProperty(globalThis, "navigator", { configurable: true, value: { locks: { request: lock } } });

const bundled = (await build({
  stdin: {
    contents: "export * from './src/utils/searchHistory'; export * from './src/api/client'; export * from './src/utils/privacy'; export * from './src/utils/storageHealth';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, platform: "node", format: "esm", define: { "import.meta.env": "{}" },
})).outputFiles[0].text;
async function tab(label) {
  const module = await import("data:text/javascript;base64," + Buffer.from(bundled + "\n// " + label).toString("base64"));
  module.activateStorageFence();
  await module.initializeSessionStorage();
  return module;
}
const a = await tab("A"), b = await tab("B");
const make = (from, to = []) => ({ from: Array.isArray(from) ? from : [from], to: Array.isArray(to) ? to : [to] });
const ufa = make("Уфа"), byStop = make(["Уфа", "Ургаза"], "Акъяр");
const add = a.updateSearchHistory;
const start = add([], ufa, 1000);
assert.equal(add(start, byStop, 120999).length, 1);
assert.equal(add(start, byStop, 121000).length, 2);
assert.equal(add(start, byStop, 999).length, 2);
console.log("✓ Уточнение заменяет последнюю запись до двух минут, с точной границей и защитой от перевода часов");

let history = add([], make(["Сибай", "Стерлитамак"], "Орёл"), 100);
history = add(history, make("Акъяр", "Ургаза"), 110);
history = add(history, make(["СТЕРЛИТАМАК", "сибай"], "орел"), 120);
assert.equal(history.length, 2);
assert.deepEqual(history[0].to, ["орел"]);
assert.equal(add(history, make(["Стерлитамак"], "Орёл"), 121).length, 3);
assert.deepEqual(add(history, make([], []), 122), history);
assert.deepEqual(add(history, make(["A", "B", "C", "D"]), 122), history);
assert.equal(Array.from({ length: 8 }).reduce((rows, _, index) => add(rows, make("Город " + index), index), []).length, 5);
console.log("✓ Повторы, порядок, ё/е, сужение, пустой маршрут и максимальный размер списка");
const partialLast = add(add([], byStop, 1000), ufa, 1100);
assert.equal(partialLast.length, 2);
assert.deepEqual(add(partialLast, byStop, 1200, false).map(row => a.searchRouteKey(row)),
  [a.searchRouteKey(byStop), a.searchRouteKey(ufa)]);
console.log("✓ Повтор из истории поднимает маршрут, сохраняя более свежий частичный поиск");
const localized = add([], { ...byStop, labels: { "Ургаза": "Урғаҙа", "Акъяр": "Аҡъяр", "Лишнее": "Чужой город" } }, 1000);
assert.deepEqual(Object.entries(localized[0].labels), [["Ургаза", "Урғаҙа"], ["Акъяр", "Аҡъяр"]]);
console.log("✓ Справочные подписи сохраняются только для выбранных городов");



const old = a.getSessionGeneration();
await a.recordSearch(old, ufa);
assert.deepEqual((await a.readSearchHistory(old))[0].from, ["Уфа"]);
assert.deepEqual((await b.readSearchHistory(old))[0].from, ["Уфа"]);
const storage = a.SEARCH_HISTORY_KEY;
assert.deepEqual(Object.keys(JSON.parse(disk.get(storage)).entries[0]).sort(), ["at", "from", "to"]);
console.log("✓ Другая вкладка того же входа видит только города и время записи");

const after = await b.setSession("synthetic-access", "synthetic-refresh", undefined, old);
b.clearPersonalSearchHistory();
assert.ok(after && after !== old && !disk.has(storage));
assert.equal(a.getSessionGeneration(), old, "старая вкладка ещё не получила сигнал");
assert.equal(await a.readSearchHistory(old), undefined);
assert.equal(await a.recordSearch(old, ufa), undefined);
assert.equal(await a.removeSearch(old, a.searchRouteKey(ufa)), undefined);
assert.equal(await a.clearSearchHistory(old), undefined);
assert.equal(disk.has(storage), false);
await b.recordSearch(after, byStop);
await a.clearSearchHistory(old);
assert.deepEqual((await b.readSearchHistory(after))[0].to, ["Акъяр"]);
console.log("✓ Устаревшая вкладка не читает, не пишет и не стирает историю нового входа");

disk.set(storage, JSON.stringify({ generation: old, entries: start }));
assert.deepEqual(await b.readSearchHistory(after), []);
disk.set(storage, "{");
assert.deepEqual(await b.readSearchHistory(after), []);
await b.recordSearch(after, byStop);
assert.deepEqual(await b.removeSearch(after, b.searchRouteKey(byStop)), []);
assert.equal(disk.has(storage), false);
console.log("✓ Чужая и повреждённая запись скрыты, удаление сохраняется сразу");
await b.recordSearch(after, byStop);
const remove = b.removeSearch(after, b.searchRouteKey(byStop));
const repeat = b.repeatSearch(after, b.searchRouteKey(byStop), () => true);
assert.deepEqual(await remove, []);
assert.equal(await repeat, undefined);
assert.equal(disk.has(storage), false);
await b.recordSearch(after, byStop);
assert.equal(await b.repeatSearch(after, b.searchRouteKey(byStop), () => false), undefined);
assert.equal((await b.readSearchHistory(after)).length, 1);
await b.clearSearchHistory(after);
console.log("✓ Повтор не восстанавливает запись, удалённую другой вкладкой, и не продолжает после закрытия шторки");


const guest = b.setSession(null, null, undefined, after);
const stale = b.recordSearch(after, byStop);
assert.ok(await guest);
assert.equal(await stale, undefined);
assert.equal(disk.has(storage), false);
Object.defineProperty(globalThis, "navigator", { configurable: true, value: {} });
assert.equal(await b.recordSearch(b.getSessionGeneration(), byStop), undefined);
assert.equal(disk.has(storage), false);
console.log("✓ Запись ждёт смену сессии и пропускается без межвкладочной блокировки");
