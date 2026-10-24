// ================================================================
//  Точка водителя с сайта — сторожа, найденные приёмкой PR155 (17.10.2026). Набор проверяющего,
//  перенесён как есть и дополнен. Ловит то, что пропускал driver-location-share-test:
//
//   1. Шторм соединений. Сервер лежит, а каждая новая точка GPS открывала соединение мимо паузы
//      (замер: 29 попыток за 30 с). Теперь повтор назначает только обрыв — по лестнице 1, 2, 4 … 30 с,
//      как Android LocationSocket.
//   2. Отказ в ключе. «Invalid token» / «Token revoked» уходили в вечные повторы (66 соединений
//      за 90 с) с тем же ключом. Потом их сделали концом — и сломали обычный случай: ключ живёт
//      12 ч, сайт обновляет его тихо, а точка гасла до конца поездки (второй проход приёмки).
//      Теперь как Android ChatSocket: отвергнутый ключ не повторяем, ждём обновлённый (тихое
//      обновление тем же полётом, что у запросов); обновление отказано — конец. Прочие 1008,
//      кроме «Trip not active», — конец.
//   3. Без ключа входа соединение не открываем вовсе (Android: currentToken() ?: return).
//   4. Сменилась сессия — при следующей точке снимаем слежение и закрываем соединение.
//   5. Проводка экрана: уход со страницы и «в пути → нет» останавливают отправку; ключа нет
//      в адресе канала; выход из аккаунта виден логике (isCurrent).
//   6. Сеть вернулась («online») — подключаемся сразу, не ждём шага лестницы до 30 с.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { ROOT, bundleComponent, checker, langMock, mount } from "./shallow-harness.mjs";

const t = checker();
const lib = await build({ entryPoints: [ROOT + "src/utils/tripLocationShare.ts"], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
const S = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));

function rig({ token = "tok", current = () => true, refresh, online = false } = {}) {
  const tokenFn = typeof token === "function" ? token : () => token;
  const r = { states: [], sockets: [], watches: [], cleared: [], timers: [], now: 1_000_000, refreshed: [], onlineFns: [] };
  r.geo = {
    watchPosition(ok, fail, opts) { const id = r.watches.length + 1; r.watches.push({ id, ok, fail, opts }); return id; },
    clearWatch(id) { r.cleared.push(id); },
  };
  r.handle = S.startDriverLocationShare({
    geolocation: r.geo,
    openSocket() {
      const s = { readyState: 0, sent: [], closed: null, onopen: null, onclose: null, onerror: null, onmessage: null,
        send(d) { this.sent.push(JSON.parse(d)); }, close(code) { this.closed = code ?? 1000; } };
      r.sockets.push(s);
      return s;
    },
    token: tokenFn,
    now: () => r.now,
    setTimer(fn, ms) { const tm = { fn, ms, done: false }; r.timers.push(tm); return tm; },
    clearTimer(tm) { if (tm) tm.done = true; },
    onState: (s) => r.states.push(s),
    isCurrent: current,
    ...(refresh ? { refreshToken: (k) => (r.refreshed.push(k), refresh(k)) } : {}),
    ...(online ? { subscribeOnline: (fn) => (r.onlineFns.push(fn), () => { r.onlineFns = r.onlineFns.filter((f) => f !== fn); }) } : {}),
  });
  r.fix = (lat, lng) => r.watches.at(-1).ok({ coords: { latitude: lat, longitude: lng, heading: null } });
  r.open = () => { const s = r.sockets.at(-1); s.readyState = 1; s.onopen?.({}); return s; };
  r.runTimers = () => { for (const tm of r.timers.splice(0)) if (!tm.done) tm.fn(); };
  r.pending = () => r.timers.filter((x) => !x.done);
  return r;
}
const tick = () => new Promise((ok) => setImmediate(ok));

await t.test("обрыв связи: пауза 1-2-4…30 с не обходится новой точкой GPS (нет шторма соединений)", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1006, reason: "" });
  // Сети нет: каждое новое соединение тут же обрывается (1006), GPS шлёт точку раз в секунду.
  for (let i = 0; i < 10; i++) {
    r.now += 1000; r.fix(54.7 + i * 0.0001, 55.9);
    const s = r.sockets.at(-1); if (s.closed === null && s.readyState === 0 && s.onclose) { s.closed = 1006; s.onclose({ code: 1006, reason: "" }); }
  }
  assert.equal(r.sockets.length, 1, `за 10 с без связи открыто ${r.sockets.length - 1} новых соединений — должно ждать паузу`);
});

await t.test("повторы идут по лестнице 1, 2, 4, 8, 16, 30, 30 с — и только по таймеру", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1006, reason: "" });
  const pauses = [];
  for (let i = 0; i < 7; i++) {
    const tm = r.timers.filter((x) => !x.done).at(-1);
    pauses.push(tm.ms);
    r.runTimers(); // пауза прошла — одна новая попытка
    r.sockets.at(-1).onclose({ code: 1006, reason: "" });
  }
  assert.deepEqual(pauses, [1000, 2000, 4000, 8000, 16000, 30000, 30000]);
  assert.equal(r.sockets.length, 8, "одна попытка на одну паузу");
  r.runTimers();
  r.open();
  assert.equal(r.states.at(-1), "sharing", "сеть вернулась — точка снова уходит");
});

for (const reason of ["Forbidden", "Trip ended", "Access ended"]) {
  await t.test(`1008 «${reason}» — конец, как в Android LocationSocket (терминал), а не вечный повтор`, () => {
    const r = rig();
    r.fix(54.7, 55.9);
    r.open().onclose({ code: 1008, reason });
    r.runTimers();
    r.now += 10_000; r.fix(54.71, 55.91);
    r.runTimers();
    assert.equal(r.sockets.length, 1, "после отказа соединение больше не открываем");
    assert.deepEqual(r.cleared, [1], "слежение за местом снято");
    assert.ok(!r.states.includes("reconnecting"), "на экране нет ложного «Нет связи с сервером»");
    assert.equal(r.states.at(-1), "ended");
  });
}

await t.test("ключ входа истёк посреди поездки, сайт тихо взял новый — точка снова уходит с новым ключом", async () => {
  let key = "old";
  const r = rig({ token: () => key, refresh: async () => { key = "fresh"; return true; } });
  r.fix(54.7, 55.9);
  r.open();
  r.sockets.at(-1).onclose({ code: 1008, reason: "Token revoked" }); // 12 ч прошли — сервер закрыл канал
  assert.equal(r.states.at(-1), "reconnecting", "на экране «Нет связи…», а не пустота");
  assert.deepEqual(r.refreshed, ["old"], "попросили тихо обновить именно отвергнутый ключ");
  assert.deepEqual(r.cleared, [], "слежение за местом не снято");
  await tick();
  r.runTimers();
  const s = r.open();
  assert.equal(r.sockets.length, 2);
  assert.deepEqual(s.sent[0], { type: "auth", token: "fresh" }, "второе соединение — с новым ключом");
  assert.equal(s.sent[1].type, "loc", "и точка сразу уходит");
  assert.equal(r.states.at(-1), "sharing");
});

await t.test("ключ ещё не обновился (нет сети для обновления) — со старым ключом не подключаемся, ждём по лестнице", async () => {
  let key = "old";
  let net = false;
  const r = rig({ token: () => key, refresh: async () => { if (!net) throw new Error("offline"); key = "fresh"; return true; } });
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1008, reason: "Invalid token" });
  const pauses = [];
  for (let i = 0; i < 4; i++) {
    await tick();
    pauses.push(r.pending().at(-1).ms);
    r.runTimers();
  }
  assert.equal(r.sockets.length, 1, "отвергнутый ключ на сервер больше не шлём");
  assert.deepEqual(pauses, [1000, 2000, 4000, 8000], "обновление повторяется по лестнице, без шторма");
  assert.equal(r.refreshed.length, 5);
  assert.equal(r.states.at(-1), "reconnecting");
  net = true;
  await tick();
  r.runTimers();
  await tick();
  r.runTimers();
  assert.equal(r.sockets.length, 2, "сеть вернулась, ключ обновился — подключились");
  assert.deepEqual(r.open().sent[0], { type: "auth", token: "fresh" });
});

await t.test("вход отозван («Выйти со всех устройств»): обновление отказано — конец, слежение снято", async () => {
  const r = rig({ token: "old", refresh: async () => false });
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1008, reason: "Token revoked" });
  await tick();
  r.runTimers();
  r.now += 10_000; r.fix(54.71, 55.91);
  r.runTimers();
  assert.equal(r.sockets.length, 1, "после отказа в обновлении соединение больше не открываем");
  assert.deepEqual(r.cleared, [1], "слежение за местом снято");
  assert.equal(r.states.at(-1), "ended");
});

await t.test("обновление отказано, но другая вкладка уже сменила ключ — не конец, подключаемся с новым", async () => {
  let key = "old";
  const r = rig({ token: () => key, refresh: async () => { key = "from-other-tab"; return false; } });
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1008, reason: "Token revoked" });
  await tick();
  assert.notEqual(r.states.at(-1), "ended", "вход жив — другая вкладка уже обновила ключ");
  assert.deepEqual(r.cleared, []);
  r.runTimers();
  assert.deepEqual(r.open().sent[0], { type: "auth", token: "from-other-tab" });
});

await t.test("новый ключ тоже отвергли сразу после входа — лестница растёт, а не долбит раз в секунду", async () => {
  let n = 0;
  const r = rig({ token: () => "k" + n, refresh: async () => { n++; return true; } });
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1008, reason: "Invalid token" });
  const pauses = [r.pending().at(-1).ms];
  for (let i = 0; i < 4; i++) {
    await tick();
    r.runTimers();
    r.open().onclose({ code: 1008, reason: "Invalid token" }); // сервер отказал и этому
    pauses.push(r.pending().at(-1).ms);
  }
  assert.deepEqual(pauses, [1000, 2000, 4000, 8000, 16000]);
});

await t.test("сеть вернулась («online») — подключаемся сразу, не ждём шага лестницы", () => {
  const r = rig({ online: true });
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1006, reason: "" });
  for (let i = 0; i < 4; i++) { r.runTimers(); r.sockets.at(-1).onclose({ code: 1006, reason: "" }); }
  assert.equal(r.pending().at(-1).ms, 16000, "сейчас ждали бы 16 с");
  r.onlineFns.forEach((f) => f());
  assert.equal(r.sockets.length, 6, "по «online» — новое соединение сразу");
  assert.equal(r.pending().length, 0, "назначенный повтор снят");
  r.open();
  assert.equal(r.states.at(-1), "sharing");
  r.handle.stop();
  assert.equal(r.onlineFns.length, 0, "остановили — от «online» отписались");
});

await t.test("«online» не ускоряет мягкий повтор «бронь ещё не подтверждена»", () => {
  const r = rig({ online: true });
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1008, reason: "Trip not active" });
  r.onlineFns.forEach((f) => f());
  assert.equal(r.sockets.length, 1);
});

await t.test("1008 «Trip not active» — не конец: бронь подтвердят, мягкий повтор через 15 с", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1008, reason: "Trip not active" });
  assert.equal(r.timers.filter((x) => !x.done).at(-1).ms, S.SOFT_RETRY_MS);
  r.runTimers();
  assert.equal(r.sockets.length, 2);
});

await t.test("нет ключа входа — соединение не открываем, точка не уходит (Android: currentToken() ?: return)", () => {
  const r = rig({ token: null });
  r.fix(54.7, 55.9);
  r.runTimers();
  r.now += 10_000; r.fix(54.71, 55.91);
  assert.equal(r.sockets.length, 0, "без ключа — ни одного соединения");
  assert.deepEqual(r.cleared, [1], "и слежение за местом снято");
});

await t.test("сессия сменилась — слежение за местом снимается при следующей точке", () => {
  let alive = true;
  const r = rig({ current: () => alive });
  r.fix(54.7, 55.9);
  const s = r.open();
  alive = false;
  r.now += 8000; r.fix(54.8, 56.0);
  assert.deepEqual(r.cleared, [1], "браузер продолжает следить за местом после выхода");
  assert.equal(s.closed, 1000, "соединение закрыто");
  assert.equal(s.sent.filter((f) => f.type === "loc").length, 1, "после выхода кадров нет");
});

// ---------------------------------------------------------------- проводка экрана
// С Д2 (24.10.2026) экран берёт отправку у общей на вкладку (utils/driverRideShare.ts → rideLocationShare.ts):
// проводка к браузеру живёт там, поэтому собираем её по-настоящему, подменяя только сеть и саму отправку.
async function view() {
  return bundleComponent("src/components/DriverLocationShare.tsx", {
    real: ["/utils/driverRideShare", "/utils/rideShareStore"],
    mocks: {
      "/i18n/lang": langMock("ru"),
      "/api/client": `export const getSessionGeneration=()=>globalThis.__gen;export const getToken=()=>"SECRET";export const refreshRejectedToken=async()=>true;`,
      "/api/chat": `export const wsBase=()=>"ws://x";`,
      "/api/bookings": `export const fetchDriverBookings=async()=>[];`,
      "/tripLocationShare": `export const tripLocationUrl=(b,id)=>b+"/ws/trip/"+id+"/location";
        export function startDriverLocationShare(deps){const rec={deps,stops:0};(globalThis.__starts??=[]).push(rec);return{stop(){rec.stops++;},retry(){}};}`,
    },
  });
}
/** Ушли со страницы: отправка гаснет после короткой паузы на смену маршрута (rideLocationShare.HOLD_GRACE_MS). */
const afterGrace = () => new Promise((r) => setTimeout(r, 1600));

await t.test("ушёл со страницы / «Завершить» сняло «в пути» — отправка останавливается (stop)", async () => {
  globalThis.__starts = [];
  globalThis.__gen = "A";
  globalThis.window = { setTimeout, clearTimeout };
  const V = await view();
  const h = mount(V, { bookingId: 42, inTransit: true });
  h.render();
  assert.equal(globalThis.__starts.length, 1);
  h.props = { bookingId: 42, inTransit: false };
  h.render();
  await afterGrace();
  assert.equal(globalThis.__starts[0].stops, 1, "смена «в пути» → нет: stop не вызван");
  h.props = { bookingId: 42, inTransit: true };
  h.render();
  h.unmount();
  await afterGrace();
  assert.equal(globalThis.__starts[1].stops, 1, "уход со страницы: stop не вызван");
});

await t.test("канал открывается без ключа в адресе; выход из аккаунта виден логике (isCurrent)", async () => {
  globalThis.__starts = [];
  globalThis.__gen = "A";
  const made = [];
  globalThis.WebSocket = class { constructor(url) { made.push(url); } };
  const V = await view();
  const h = mount(V, { bookingId: 42, inTransit: true });
  h.render();
  const d = globalThis.__starts[0].deps;
  d.openSocket();
  assert.equal(made[0], "ws://x/ws/trip/42/location");
  assert.ok(!/token|SECRET/i.test(made[0]), "ключ входа в адресе канала");
  assert.equal(d.isCurrent(), true);
  globalThis.__gen = "B";
  assert.equal(d.isCurrent(), false, "смена сессии не останавливает отправку");
  assert.equal(typeof d.refreshToken, "function", "истёкший ключ экран не обновляет — точка погаснет до конца поездки");
  assert.equal(typeof d.subscribeOnline, "function", "«сеть вернулась» не подключено");
  assert.equal(typeof d.onEnd, "function", "конец отправки не доходит до отправки на весь рейс");
  globalThis.__gen = "A";
  h.unmount();
  await afterGrace();
});

await t.test("экран подписывается на «online» окна и отписывается", async () => {
  globalThis.__starts = [];
  globalThis.__gen = "A";
  const listeners = [];
  globalThis.window = {
    setTimeout,
    clearTimeout,
    addEventListener: (type, fn) => listeners.push([type, fn]),
    removeEventListener: (type, fn) => { const i = listeners.findIndex(([t2, f]) => t2 === type && f === fn); if (i >= 0) listeners.splice(i, 1); },
  };
  const V = await view();
  const h = mount(V, { bookingId: 42, inTransit: true });
  h.render();
  const fn = () => {};
  const off = globalThis.__starts[0].deps.subscribeOnline(fn);
  assert.deepEqual(listeners, [["online", fn]], "логика не узнает, что сеть вернулась");
  off();
  assert.equal(listeners.length, 0);
  h.unmount();
  await afterGrace();
});

t.done();
