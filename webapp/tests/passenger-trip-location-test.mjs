// ================================================================
//  Машина водителя на карте у пассажира — приём из канала поездки (приёмка PR155, 17.10.2026).
//
//  Было: канал открывался один раз. Первый же обрыв связи (туннель, смена вышки) или истечение
//  ключа входа (12 ч) — и машина на карте замирала до перезагрузки страницы. Замер проверяющего
//  с ключом на 1 минуту: водитель отправил 21 точку, пассажир получил 9.
//
//  Стало как у водителя (tripLocationShare.ts) и как Android LocationSocket: лестница 1…30 с,
//  «сеть вернулась» — сразу, истёкший ключ — тихое обновление, конец поездки — конец.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { ROOT, bundleComponent, checker } from "./shallow-harness.mjs";

const t = checker();
const lib = await build({ entryPoints: [ROOT + "src/utils/tripLocationListen.ts"], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
const L = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));
const tick = () => new Promise((ok) => setImmediate(ok));

function rig({ token = "tok", current = () => true, refresh, online = false } = {}) {
  const tokenFn = typeof token === "function" ? token : () => token;
  const r = { sockets: [], timers: [], points: [], refreshed: [], onlineFns: [] };
  r.handle = L.listenDriverLocation({
    openSocket() {
      const s = { readyState: 0, sent: [], closed: null, onopen: null, onclose: null, onerror: null, onmessage: null,
        send(d) { this.sent.push(JSON.parse(d)); }, close(code) { this.closed = code ?? 1000; } };
      r.sockets.push(s);
      return s;
    },
    token: tokenFn,
    setTimer(fn, ms) { const tm = { fn, ms, done: false }; r.timers.push(tm); return tm; },
    clearTimer(tm) { if (tm) tm.done = true; },
    onLoc: (p) => r.points.push(p),
    isCurrent: current,
    ...(refresh ? { refreshToken: (k) => (r.refreshed.push(k), refresh(k)) } : {}),
    ...(online ? { subscribeOnline: (fn) => (r.onlineFns.push(fn), () => { r.onlineFns = r.onlineFns.filter((f) => f !== fn); }) } : {}),
  });
  r.open = () => { const s = r.sockets.at(-1); s.readyState = 1; s.onopen?.({}); return s; };
  r.msg = (d) => r.sockets.at(-1).onmessage?.({ data: typeof d === "string" ? d : JSON.stringify(d) });
  r.drop = (code = 1006, reason = "") => r.sockets.at(-1).onclose?.({ code, reason });
  r.pending = () => r.timers.filter((x) => !x.done);
  r.runTimers = () => { for (const tm of r.timers.splice(0)) if (!tm.done) tm.fn(); };
  return r;
}

await t.test("вход первым кадром, на карту — только точка водителя; свои и битые кадры не рисуем", () => {
  const r = rig();
  assert.deepEqual(r.open().sent, [{ type: "auth", token: "tok" }]);
  r.msg({ type: "loc", role: "driver", lat: 54.7, lng: 55.9, bearing: 90 });
  r.msg({ type: "loc", role: "passenger", lat: 1, lng: 2 });
  r.msg("не json");
  r.msg({ type: "loc", role: "driver", lat: "54", lng: 55 });
  assert.deepEqual(r.points, [{ lat: 54.7, lng: 55.9, bearing: 90 }]);
});

await t.test("без ключа входа соединение не открываем", () => {
  const r = rig({ token: null });
  assert.equal(r.sockets.length, 0);
});

await t.test("обрыв связи — не конец: повтор по лестнице 1, 2, 4, 8, 16, 30, 30 с, по одной попытке на паузу", () => {
  const r = rig();
  r.open();
  r.drop();
  const pauses = [];
  for (let i = 0; i < 7; i++) {
    pauses.push(r.pending().at(-1).ms);
    r.runTimers();
    r.drop();
  }
  assert.deepEqual(pauses, [1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  assert.equal(r.sockets.length, 8);
  r.runTimers();
  r.open();
  r.msg({ type: "loc", role: "driver", lat: 54.71, lng: 55.91 });
  assert.equal(r.points.length, 1, "связь вернулась — машина снова двигается на карте");
});

await t.test("сеть вернулась («online») — подключаемся сразу; мягкий повтор так не ускоряется", () => {
  const r = rig({ online: true });
  r.open();
  for (let i = 0; i < 5; i++) { r.drop(); r.runTimers(); }
  r.drop();
  assert.equal(r.pending().at(-1).ms, 30000);
  r.onlineFns.forEach((f) => f());
  assert.equal(r.sockets.length, 7, "по «online» — новое соединение сразу");
  assert.equal(r.pending().length, 0);
  r.open();
  r.drop(1008, "Trip not active");
  r.onlineFns.forEach((f) => f());
  assert.equal(r.sockets.length, 7, "«бронь ещё не подтверждена» ждёт свои 15 с");
});

await t.test("«поездка ещё не активна» — мягкий повтор раз в 15 с, не больше 40 раз", () => {
  const r = rig();
  for (let i = 0; i < 41; i++) {
    r.open();
    r.drop(1008, "Trip not active");
    if (i < 40) {
      assert.equal(r.pending().at(-1).ms, 15000);
      r.runTimers();
    }
  }
  assert.equal(r.sockets.length, 41);
  assert.equal(r.pending().length, 0, "дальше не долбим сервер");
});

await t.test("ключ входа истёк посреди поездки — тихое обновление, снова с новым ключом, машина снова видна", async () => {
  let key = "old";
  const r = rig({ token: () => key, refresh: async () => { key = "fresh"; return true; } });
  r.open();
  r.drop(1008, "Token revoked");
  assert.deepEqual(r.refreshed, ["old"]);
  await tick();
  r.runTimers();
  assert.deepEqual(r.open().sent[0], { type: "auth", token: "fresh" });
  r.msg({ type: "loc", role: "driver", lat: 54.7, lng: 55.9 });
  assert.equal(r.points.length, 1);
});

await t.test("ключ ещё не обновился — со старым не подключаемся; обновление отказано — конец", async () => {
  let net = false;
  const r = rig({ token: "old", refresh: async () => { if (!net) throw new Error("offline"); return false; } });
  r.open();
  r.drop(1008, "Invalid token");
  for (let i = 0; i < 3; i++) { await tick(); r.runTimers(); }
  assert.equal(r.sockets.length, 1, "отвергнутый ключ на сервер больше не уходит");
  net = true;
  await tick();
  r.runTimers();
  await tick();
  assert.equal(r.pending().length, 0, "вход отозван — повторов нет");
  r.runTimers();
  assert.equal(r.sockets.length, 1);
});

for (const reason of ["Trip ended", "Forbidden", "Access ended"]) {
  await t.test(`1008 «${reason}» — конец: повторов нет`, () => {
    const r = rig({ online: true });
    r.open();
    r.drop(1008, reason);
    r.runTimers();
    r.onlineFns.forEach((f) => f());
    assert.equal(r.sockets.length, 1);
    assert.equal(r.onlineFns.length, 0, "от «online» отписались");
  });
}

await t.test("вышли или сменили аккаунт — следующий повтор не подключается", () => {
  let alive = true;
  const r = rig({ current: () => alive });
  r.open();
  r.drop();
  alive = false;
  r.runTimers();
  assert.equal(r.sockets.length, 1);
});

await t.test("stop() — соединение закрыто, таймер снят, от «online» отписались, поздние события ничего не делают", () => {
  const r = rig({ online: true });
  const s = r.open();
  r.handle.stop();
  assert.equal(s.closed, 1000);
  assert.equal(r.onlineFns.length, 0);
  s.onclose?.({ code: 1006, reason: "" });
  r.runTimers();
  assert.equal(r.sockets.length, 1);
});

await t.test("openTripLocation: адрес без ключа, вход первым кадром, «online» окна, close() останавливает", async () => {
  const made = [];
  globalThis.WebSocket = class {
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; made.push(this); }
    send(d) { this.sent.push(JSON.parse(d)); }
    close() { this.closed = true; }
  };
  const listeners = [];
  globalThis.window = {
    addEventListener: (type, fn) => listeners.push([type, fn]),
    removeEventListener: (type, fn) => { const i = listeners.findIndex(([a, f]) => a === type && f === fn); if (i >= 0) listeners.splice(i, 1); },
    location: { origin: "https://app.yulbash.ru", protocol: "https:", host: "app.yulbash.ru" },
  };
  const openTripLocation = await bundleComponent("src/api/chat.ts", {
    exportName: "openTripLocation",
    real: ["/utils/tripLocationListen", "/utils/tripLocationShare"],
    mocks: {
      "./client": `export const API_BASE="https://yulbash.ru";export const getToken=()=>"SECRET";export const getSessionGeneration=()=>"A";
        export const refreshRejectedToken=async()=>true;export const apiGet=()=>{};export const apiPost=()=>{};export const apiDelete=()=>{};
        export const apiUpload=()=>{};export const isOwnApiUrl=()=>true;export class ApiError extends Error{}`,
    },
  });
  const points = [];
  const conn = openTripLocation(42, (p) => points.push(p));
  assert.equal(made.length, 1);
  assert.match(made[0].url, /\/ws\/trip\/42\/location$/);
  assert.ok(!/SECRET|token/i.test(made[0].url), "ключ входа в адресе канала");
  made[0].readyState = 1;
  made[0].onopen({});
  assert.deepEqual(made[0].sent[0], { type: "auth", token: "SECRET" });
  made[0].onmessage({ data: JSON.stringify({ type: "loc", role: "driver", lat: 54.7, lng: 55.9 }) });
  assert.equal(points.length, 1);
  assert.equal(listeners.length, 1, "пассажир не узнает, что сеть вернулась");
  conn.close();
  assert.equal(made[0].closed, true);
  assert.equal(listeners.length, 0);
});

t.done();
