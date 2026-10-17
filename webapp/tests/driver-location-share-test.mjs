// ================================================================
//  Паритет с Android, шаг 3 (17.10.2026): водитель с сайта отправляет свою точку в тот же
//  канал, что приложение, — WS /ws/trip/{id}/location (Android TripLocationService + LocationSocket).
//
//  Было: сайт водителя точку не слал вовсе — пассажир и мама по ссылке видели машину, только
//  если водитель ехал с приложением.
//
//  Личные данные — то, что этот набор держит (AGENTS.md §7, §8):
//   • место спрашиваем только в пути («Выехал» / «Подъезжаю» или пассажир сел), до этого — нет;
//   • без доступа к месту экран не падает: «закрыт доступ», что делать, «Повторить»;
//   • сокет открываем только с первой точкой; кадр — формат Android {type:"loc",lat,lng,ts};
//   • не чаще раза в 7 секунд; «поездка закончилась» (1008 Trip ended) — больше не шлём;
//   • координаты не уходят ни в console, ни в аналитику; когда показывать — решает сервер;
//   • честная подпись «только пока эта страница открыта».
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { ROOT, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";

const t = checker();
/** Логика отправки точки; на старом коде модуля нет — каждая проверка ниже честно падает. */
let S = null;
try {
  const lib = await build({ entryPoints: [ROOT + "src/utils/tripLocationShare.ts"], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
  S = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));
} catch {
  S = new Proxy({}, { get: () => { throw new Error("нет src/utils/tripLocationShare.ts — точку водителя сайт не отправляет"); } });
}

// ---------------------------------------------------------------- подставной браузер
function rig({ geo = true, current = () => true } = {}) {
  const r = { states: [], sockets: [], watches: [], cleared: [], timers: [], now: 1_000_000 };
  r.geo = geo
    ? {
        watchPosition(ok, fail, opts) {
          const id = r.watches.length + 1;
          r.watches.push({ id, ok, fail, opts });
          return id;
        },
        clearWatch(id) {
          r.cleared.push(id);
        },
      }
    : null;
  r.handle = S.startDriverLocationShare({
    geolocation: r.geo,
    openSocket() {
      const s = { readyState: 0, sent: [], closed: null, onopen: null, onclose: null, onerror: null, onmessage: null,
        send(d) { this.sent.push(JSON.parse(d)); }, close(code) { this.closed = code ?? 1000; } };
      r.sockets.push(s);
      return s;
    },
    token: () => "tok",
    now: () => r.now,
    setTimer(fn, ms) { const tm = { fn, ms, done: false }; r.timers.push(tm); return tm; },
    clearTimer(tm) { if (tm) tm.done = true; },
    onState: (s) => r.states.push(s),
    isCurrent: current,
  });
  r.fix = (lat, lng, heading = null) => r.watches.at(-1).ok({ coords: { latitude: lat, longitude: lng, heading } });
  r.open = () => { const s = r.sockets.at(-1); s.readyState = 1; s.onopen?.({}); return s; };
  r.runTimers = () => { for (const tm of r.timers.splice(0)) if (!tm.done) tm.fn(); };
  return r;
}

await t.test("в пути = водитель + «Выехал»/«Подъезжаю» или пассажир сел; иначе место не просим", () => {
  const f = S.driverInTransit;
  assert.equal(f("driver", "confirmed", ""), false, "подтверждено, но не выехал — рано");
  assert.equal(f("driver", "confirmed", "departed"), true);
  assert.equal(f("driver", "confirmed", "arriving"), true);
  assert.equal(f("driver", "onboard", ""), true, "пассажир сел — поездка идёт");
  assert.equal(f("driver", "pending", "departed"), false, "неподтверждённая бронь — нет");
  assert.equal(f("driver", "done", "departed"), false);
  assert.equal(f("driver", "cancelled", "departed"), false);
  assert.equal(f("passenger", "onboard", "departed"), false, "пассажирскую точку сайт не шлёт");
});

await t.test("канал и кадр — как у Android: /ws/trip/{id}/location, {type:loc,lat,lng,ts,bearing?}", () => {
  assert.equal(S.tripLocationUrl("wss://yulbash.ru", 42), "wss://yulbash.ru/ws/trip/42/location");
  assert.deepEqual(JSON.parse(S.locationFrame(54.7, 55.9, 90, 1_700_000_123_456)), { type: "loc", lat: 54.7, lng: 55.9, ts: 1_700_000_123, bearing: 90 });
  assert.deepEqual(JSON.parse(S.locationFrame(54.7, 55.9, NaN, 1_700_000_000_000)), { type: "loc", lat: 54.7, lng: 55.9, ts: 1_700_000_000 });
  assert.equal(S.SEND_EVERY_MS, 7000, "не чаще раза в 7 с — как TripLocationService.MIN_INTERVAL_MS");
});

await t.test("спросили место → первая точка → сокет → auth первым кадром, затем точка", () => {
  const r = rig();
  assert.equal(r.watches.length, 1, "браузер спросили один раз");
  assert.equal(r.watches[0].opts.enableHighAccuracy, true);
  assert.deepEqual(r.states, ["asking"]);
  assert.equal(r.sockets.length, 0, "без точки соединение не открываем");
  r.fix(54.73, 55.95, 45);
  assert.equal(r.sockets.length, 1);
  const s = r.open();
  assert.deepEqual(s.sent[0], { type: "auth", token: "tok" }, "токен — первым кадром, не в адресе");
  assert.deepEqual(s.sent[1], { type: "loc", lat: 54.73, lng: 55.95, ts: Math.floor(r.now / 1000), bearing: 45 });
  assert.equal(r.states.at(-1), "sharing");
});

await t.test("не чаще раза в 7 секунд — но последняя точка не теряется", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  const s = r.open();
  const locs = () => s.sent.filter((f) => f.type === "loc");
  r.now += 3000; r.fix(54.71, 55.91);
  assert.equal(locs().length, 1, "через 3 с — сразу не шлём");
  const trail = r.timers.find((tm) => !tm.done);
  assert.ok(trail && trail.ms === 4000, "точка отложена до конца паузы (ещё 4 с)");
  r.now += 4000; r.runTimers();
  assert.equal(locs().length, 2, "машина остановилась сразу после кадра — пассажир всё равно видит, где она стоит");
  assert.equal(locs().at(-1).lat, 54.71);
  r.now += 7000; r.fix(54.72, 55.92);
  assert.equal(locs().length, 3, "через 7 с — шлём сразу");
  assert.equal(locs().at(-1).lat, 54.72);
});

await t.test("доступ закрыт → «denied», слежение снято, соединения нет; «Повторить» спрашивает снова", () => {
  const r = rig();
  r.watches[0].fail({ code: 1 });
  assert.equal(r.states.at(-1), "denied");
  assert.deepEqual(r.cleared, [1]);
  assert.equal(r.sockets.length, 0);
  r.handle.retry();
  assert.equal(r.watches.length, 2, "снова спросили браузер");
  assert.equal(r.states.at(-1), "asking");
});

await t.test("место не определяется → «unavailable»; появилась точка — работаем дальше", () => {
  const r = rig();
  r.watches[0].fail({ code: 3 });
  assert.equal(r.states.at(-1), "unavailable");
  r.fix(54.7, 55.9);
  r.open();
  assert.equal(r.states.at(-1), "sharing");
});

await t.test("браузер без геолокации → «unsupported», ничего не открываем", () => {
  const r = rig({ geo: false });
  assert.deepEqual(r.states, ["unsupported"]);
  assert.equal(r.sockets.length, 0);
});

await t.test("1008 «Trip ended» — поездка закончилась: больше не шлём и не переподключаемся", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  const s = r.open();
  s.onclose({ code: 1008, reason: "Trip ended" });
  assert.equal(r.states.at(-1), "ended");
  assert.deepEqual(r.cleared, [1], "слежение за местом снято");
  r.runTimers();
  assert.equal(r.sockets.length, 1, "нового соединения нет");
});

await t.test("обрыв связи — «reconnecting» и повтор с паузой; «Trip not active» — мягкий повтор через 15 с", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  r.open().onclose({ code: 1006, reason: "" });
  assert.equal(r.states.at(-1), "reconnecting");
  assert.equal(r.timers.at(-1).ms, 1000);
  r.runTimers();
  assert.equal(r.sockets.length, 2, "переподключились");
  r.open().onclose({ code: 1008, reason: "Trip not active" });
  assert.equal(r.timers.at(-1).ms, S.SOFT_RETRY_MS);
});

await t.test("stop() — снимает слежение и закрывает соединение; сменилась сессия — точка не уходит", () => {
  const r = rig();
  r.fix(54.7, 55.9);
  const s = r.open();
  r.handle.stop();
  assert.deepEqual(r.cleared, [1]);
  assert.equal(s.closed, 1000);
  let alive = true;
  const r2 = rig({ current: () => alive });
  r2.fix(54.7, 55.9);
  const s2 = r2.open();
  alive = false;
  r2.now += 10_000; r2.fix(54.8, 56.0);
  assert.equal(s2.sent.filter((f) => f.type === "loc").length, 1, "после выхода из аккаунта кадров нет");
});

await t.test("координаты не уходят ни в журнал, ни в аналитику", () => {
  for (const rel of ["src/utils/tripLocationShare.ts", "src/components/DriverLocationShare.tsx"]) {
    const code = readFileSync(ROOT + rel, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
    assert.ok(!/console\.|track\(|trackOnce\(|localStorage|sessionStorage/.test(code), rel + ": журнал, аналитика или хранилище");
  }
});

await t.test("сервер не трогаем: канал тот же, что у Android; правила показа — серверные", () => {
  const code = readFileSync(ROOT + "src/utils/tripLocationShare.ts", "utf8");
  assert.ok(code.includes("/ws/trip/${bookingId}/location"));
  const kt = ROOT + "../android/app/src/main/java/com/yuldash/app/data/LocationSocket.kt";
  try {
    assert.ok(readFileSync(kt, "utf8").includes('"${ApiClient.wsBase()}/ws/trip/$bookingId/location"'));
  } catch (e) {
    if (e.code !== "ENOENT") throw e;
  }
});

// ---------------------------------------------------------------- экран: где и когда
/** В Node 22 navigator — только для чтения: подменяем свойство целиком. */
const fakeNavigator = (geolocation) =>
  Object.defineProperty(globalThis, "navigator", { configurable: true, writable: true, value: { geolocation } });
async function shareView(lang, inTransit, state) {
  const View = await bundleComponent("src/components/DriverLocationShare.tsx", {
    real: ["/utils/tripLocationShare"],
    mocks: {
      "/i18n/lang": langMock(lang),
      "/api/client": `export const getSessionGeneration=()=>"A";export const getToken=()=>"tok";`,
      "/api/chat": `export const wsBase=()=>"ws://x";`,
    },
  });
  const h = mount(View, { bookingId: 42, inTransit });
  h.render();
  if (state) {
    // состояние приходит из логики через onState → useState: подставляем его напрямую
    h.state.values[0] = state;
    h.render();
  }
  return h;
}

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: до «Выехал» — «зачем», место ещё не спрашиваем`, async () => {
    const geoCalls = [];
    fakeNavigator({ watchPosition: () => geoCalls.push(1), clearWatch() {} });
    const h = await shareView(lang, false);
    assert.ok(text(h.tree).includes(L("Нажмёшь «Выехал» — сайт спросит доступ к месту", "«Сыҡтым» тип баҫһаң — сайт урыныңа рөхсәт һорар")));
    assert.equal(geoCalls.length, 0, "браузер о месте не спрашивали");
  });
  await t.test(`${lang}: отправляем — честная подпись «только пока эта страница открыта»`, async () => {
    fakeNavigator({ watchPosition: () => 1, clearWatch() {} });
    const h = await shareView(lang, true, "sharing");
    assert.ok(text(h.tree).includes(L("только пока эта страница открыта", "был бит асыҡ саҡта ғына")));
  });
  await t.test(`${lang}: доступ закрыт — объясняем, что делать, и «Повторить»; экран живой`, async () => {
    fakeNavigator({ watchPosition: () => 1, clearWatch() {} });
    const h = await shareView(lang, true, "denied");
    assert.ok(text(h.tree).includes(L("Доступ к месту закрыт", "Урыныңа рөхсәт ябыҡ")));
    assert.ok(text(h.tree).includes(L("настройках браузера", "Браузер көйләүҙәрендә")));
    assert.ok(button(h.tree, L("Повторить", "Ҡабатлау")));
  });
}

// ---------------------------------------------------------------- экран поездки: кому и когда
await t.test("экран поездки: водитель до «Выехал» — блок «зачем» (inTransit=false)", async () => {
  const { h, at } = await openActiveTrip({ role: "driver", status: "confirmed", phase: "" });
  const share = ofType(h.tree, "DriverLocationShare");
  assert.equal(share.length, 1);
  assert.equal(share[0].props.inTransit, false);
  assert.equal(share[0].props.bookingId, 42);
  assert.deepEqual(at.locOpened, [], "водителю приёмный канал не открываем");
});

await t.test("экран поездки: водитель нажал «Выехал» — точка начинает уходить (inTransit=true)", async () => {
  const { h, at } = await openActiveTrip({ role: "driver", status: "confirmed", phase: "" });
  at.trip.driver_phase = "departed"; // сервер принял «Выехал»
  button(h.tree, "Выехал").props.onClick();
  await h.settle();
  assert.deepEqual(at.phases, [[42, "departed"]]);
  assert.equal(ofType(h.tree, "DriverLocationShare")[0].props.inTransit, true);
});

await t.test("экран поездки: открыл уже в пути (пассажир сел) — сразу inTransit=true", async () => {
  const { h } = await openActiveTrip({ role: "driver", status: "onboard", phase: "" });
  assert.equal(ofType(h.tree, "DriverLocationShare")[0].props.inTransit, true);
});

await t.test("экран поездки: пассажиру своей точки не шлём, а машину водителя слушаем", async () => {
  const { h, at } = await openActiveTrip({ role: "passenger", status: "confirmed", phase: "departed" });
  assert.equal(ofType(h.tree, "DriverLocationShare").length, 0);
  assert.deepEqual(at.locOpened, [42]);
});

t.done();
