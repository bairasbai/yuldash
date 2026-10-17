// ================================================================
//  Лента поездок идёт С токеном (как Android: ApiClient auth = true).
//
//  Было: fetchRides / fetchRide / fetchRidesNear слали `auth: false` — сервер не знал, кто
//  смотрит. Заблокированный водитель оставался в ленте, а поездка «только для своих» не
//  показывалась своим. Теперь токен (если есть) уходит с запросом; гость без токена получает
//  публичную ленту, как раньше, и его «сессия» от этого не страдает.
//
//  Исполняем настоящий клиент и настоящие функции API; сеть подменена.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const bundle = await build({
  stdin: {
    contents:
      "export * from './src/api/client'; export { fetchRides, fetchRide } from './src/api/rides'; export { fetchRidesNear } from './src/api/discovery';",
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
const json = (status, body = []) => new Response(JSON.stringify(body), { status });

async function env() {
  globalThis.indexedDB = new IDBFactory();
  const storage = new Map([["yuldash.lang", "ru"]]);
  globalThis.localStorage = {
    getItem: (k) => storage.get(k) ?? null,
    setItem: (k, v) => storage.set(k, String(v)),
    removeItem: (k) => storage.delete(k),
  };
  const api = await import("data:text/javascript;base64," + Buffer.from(bundle.outputFiles[0].text + `\n// ${serial++}`).toString("base64"));
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), headers: init?.headers ?? {} });
    return json(200, String(url).includes("/rides/near") ? { count: 0, items: [] } : String(url).match(/\/rides\/\d+$/) ? { id: 5 } : []);
  };
  return { api, calls };
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

const feeds = [
  ["fetchRides — общая лента", (api) => api.fetchRides(), "/rides"],
  ["fetchRides — с маршрутом", (api) => api.fetchRides(undefined, { from_city: "Уфа", to_city: "Сибай" }), "/rides?from_city="],
  ["fetchRide — карточка поездки", (api) => api.fetchRide(5), "/rides/5"],
  ["fetchRidesNear — «Ближайшие»", (api) => api.fetchRidesNear({ lat: 54.7, lng: 56 }), "/rides/near?"],
];

for (const [label, call, path] of feeds) {
  await test(`${label}: с токеном уходит Authorization`, async ({ api, calls }) => {
    api.setSession("tok-A", "refresh-A");
    await call(api);
    assert.equal(calls.length, 1);
    assert.ok(calls[0].url.includes(path), calls[0].url);
    assert.equal(calls[0].headers.Authorization, "Bearer tok-A", "сервер должен знать, кто смотрит ленту");
  });
  await test(`${label}: гость без токена — без заголовка, ответ приходит, сессия цела`, async ({ api, calls }) => {
    let unauthorized = 0;
    api.setUnauthorizedHandler(() => unauthorized++);
    const result = await call(api);
    assert.ok(result !== undefined);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].headers.Authorization, undefined, "гостю токен не подставляется");
    assert.equal(unauthorized, 0);
    assert.equal(api.getToken(), null);
  });
}

await test("гость, у которого сервер ответил 200, не вызывает «нужно войти заново»", async ({ api }) => {
  let unauthorized = 0;
  api.setUnauthorizedHandler(() => unauthorized++);
  await api.fetchRidesNear({});
  await api.fetchRides();
  assert.equal(unauthorized, 0);
});

// Исходник: `auth: false` в трёх лентах не должен вернуться.
for (const [file, fn] of [
  ["src/api/rides.ts", "fetchRides"],
  ["src/api/rides.ts", "fetchRide"],
  ["src/api/discovery.ts", "fetchRidesNear"],
]) {
  const src = readFileSync(root + file, "utf8");
  const start = src.indexOf(`export function ${fn}(`);
  const body = src.slice(start, src.indexOf("\n}\n", start));
  await test(`${file}: ${fn} без «auth: false»`, async () => {
    assert.ok(start > 0 && body.length > 20, "функция найдена");
    assert.ok(!/auth:\s*false/.test(body), "в ленте стоит auth: false — сервер снова не знает, кто смотрит");
  });
}

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
