import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { all, bundleComponent, checker, mount, text } from "./shallow-harness.mjs";

const h = globalThis.__fullOfflineTrip = {};
const t = checker();
const mocks = {
  "react-router-dom": `export const useParams=()=>({id:String(globalThis.__fullOfflineTrip.bookingId)}),
    useLocation=()=>({pathname:"/trip/42"}),useNavigate=()=>path=>globalThis.__fullOfflineTrip.navigation.push(path),Navigate="Navigate";`,
  "/AuthProvider": `export const useAuth=()=>({status:globalThis.__fullOfflineTrip.status,retrySession:()=>globalThis.__fullOfflineTrip.retries++});`,
  "/i18n/lang": `export const useLang=()=>({lang:globalThis.__fullOfflineTrip.lang,
    appText:(ru,ba)=>globalThis.__fullOfflineTrip.lang==="ba"?ba:ru});`,
  "/flags": `export const flags={onboarded:()=>true};`,
  "/analytics": `export const trackOnce=()=>{};`,
  "/splashTarget": `export const scheduleSplashNavigation=()=>()=>{};`,
};
const define = { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://trip.test" }) };
const real = ["/offlineTripPass", "/useStoredTripData", "/client", "/format"];
const component = (entry, exportName) => bundleComponent(entry, { mocks, real, define, exportName });
const RequireAuth = await component("src/components/RequireAuth.tsx");
const Fallback = await component("src/components/OfflineTripFallback.tsx");
const Splash = await component("src/screens/SplashScreen.tsx");
const PassCard = await component("src/components/OfflineTripPass.tsx");
const result = await build({
  stdin: {
    contents: `export * from "./src/utils/offlineTripPass"; export * from "./src/utils/tripPassStore";
      export * from "./src/api/client"; export * from "./src/api/bookings";
      export * from "./src/utils/privacy"; export * from "./src/utils/inTransitCancel";`,
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node", define,
});
const api = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));

const id = 42, owner = { userId: 7, generation: "session-A" };
const legacy = "yuldash.tripPass.42";
const snapshot = {
  bookingId: id, rideId: 4, fromCity: "Уфа", toCity: "Сибай", departAt: "2026-10-16T18:00:00+05:00",
  driverName: "QA Driver", driverCar: "QA Car", driverPlate: "QA001", driverPhone: "+79990000000",
  pickup: "QA pickup", price: 300, seats: 1,
};
const token = id => "test." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".test";
const values = new Map();
function putSession(owner) {
  values.set("yuldash.token", token(owner.userId));
  values.set("yuldash.session", owner.generation);
}
function reset() {
  values.clear();
  putSession(owner);
  Object.assign(h, { bookingId: id, status: "unavailable", lang: "ru", navigation: [], retries: 0, views: [], readFail: false, failKey: null });
  globalThis.localStorage = {
    getItem(k) { if (h.readFail) throw Error("storage read failed"); return values.get(k) ?? null; },
    setItem(k,v) { if (h.failKey === k) throw Error("storage write failed"); values.set(k,String(v)); },
    removeItem: k => values.delete(k), key: i => [...values.keys()][i] ?? null,
    get length() { return values.size; },
  };
  globalThis.sessionStorage = { getItem: () => null, removeItem() {}, length: 0 };
  globalThis.indexedDB = new IDBFactory();
  globalThis.window = new EventTarget();
  globalThis.document = new EventTarget();
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: { onLine: false } });
}
function test(name, run) {
  return t.test(name, async () => {
    reset();
    try { await run(); } finally { h.views.forEach(view => view.unmount()); }
  });
}
async function save(value = snapshot, who = owner, code = "4931") {
  const revision = await api.readTripPassRevision(who, value.bookingId);
  await api.observeTripPass(who, value.bookingId, { status: "confirmed" }, revision, value);
  await api.saveTripPassCode(who, value.bookingId, revision, code);
}
const read = async (id = 42) => (await api.readOfflineTrip(id))?.pass ?? null;
const viewOf = async (Component, props) => {
  const view = mount(Component, props);
  h.views.push(view);
  await view.settle();
  return view;
};
const button = (view, label) => all(view.tree).find(node => node.type === "button" &&
  (typeof label === "function" ? label(text(node)) : text(node) === label));
const childPass = view => all(view.tree).find(node => node.type === "OfflineTripPass")?.props.pass;
const flush = async () => { for (let i = 0; i < 30; i++) await new Promise(setImmediate); };
const tabChanged = () => window.dispatchEvent(Object.assign(new Event("storage"), { key: "yuldash.session" }));
async function changeRow(change) {
  await api.editTripRecords(owner, () => true, rows => {
    const trips = new Map(rows);
    trips.set(id, change(trips.get(id)));
    return { result: true, trips };
  }, false);
}
async function readList(reply, signal) {
  globalThis.fetch = async () => new Response(JSON.stringify(await reply()), { status: 200 });
  return api.fetchMyBookings(signal);
}

for (const lang of ["ru", "ba"]) await test("F07 private auth gate " + lang, async () => {
  h.lang = lang;
  const view = await viewOf(RequireAuth, { children: "PRIVATE SCREEN", unavailableContent: "SAVED ONLY" });
  assert(text(view.tree).includes("SAVED ONLY"));
  assert(!text(view.tree).includes("PRIVATE SCREEN"));
});

for (const status of ["cancelled", "done"]) await test("F07 list retires " + status, async () => {
  await save();
  const rows = [{ id, status }];
  assert.deepEqual(await readList(() => rows), rows);
  assert.equal(await read(), null);
  await save();
  assert.equal((await api.readOfflineTrip(id)).status, "terminal");
  assert.equal(await read(), null);
});
await test("F07 list marks terminal before first snapshot, repeated history is a no-op", async () => {
  const history = Array.from({ length: 1000 }, (_, i) => ({ id: i+1, status: "done" }));
  let mutations = 0;
  const unsubscribe = api.subscribeTripPassChanges(() => { mutations++; });
  try {
    await readList(() => history);
    await save();
    assert.equal(await read(), null);
    assert.equal(mutations, 1);
    await readList(() => history);
    assert.equal(mutations, 1);
  } finally { unsubscribe(); }
});
await test("F07 list keeps active or absent, while token rotation permits terminal result", async () => {
  await save();
  await readList(() => [{ id, status: "confirmed" }]);
  await readList(() => []);
  assert.equal((await read()).boardingCode, "4931");
  await readList(() => {
    values.set("yuldash.token", token(7) + "-rotated");
    return [{ id, status: "done" }];
  });
  assert.equal(await read(), null);
});
await test("F07 late list cannot touch a new account", async () => {
  await save();
  const b = { userId: 8, generation: "session-B" };
  await assert.rejects(readList(async () => {
    putSession(b);
    await save({ ...snapshot, driverName: "QA B" }, b);
    return [{ id, status: "cancelled" }];
  }), error => error.status === 409);
  assert.equal((await read()).driverName, "QA B");
});
for (const change of ["missing token", "aborted request"]) await test("F07 list closes on " + change, async () => {
  await save();
  const request = new AbortController();
  await assert.rejects(readList(() => {
    if (change === "missing token") values.delete("yuldash.token");
    else request.abort();
    return [{ id, status: "done" }];
  }, request.signal), error => error.name === "AbortError");
  putSession(owner);
  assert.equal((await read()).boardingCode, "4931");
});

await test("START offline selects only current route metadata", async () => {
  await save();
  assert.deepEqual(await api.listOfflineTripOptions(), { generation: owner.generation, trips: [
    { bookingId: id, fromCity: snapshot.fromCity, toCity: snapshot.toCity, departAt: snapshot.departAt },
  ] });
  const view = await viewOf(Splash);
  assert(button(view, "Открыть сохранённую поездку"));
  assert(!text(view.tree).includes(snapshot.fromCity));
  button(view, "Открыть сохранённую поездку").props.onClick();
  await flush();
  assert.deepEqual(h.navigation, ["/trip/42"]);
});
await test("START online unavailable offers retry without private options", async () => {
  await save();
  navigator.onLine = true;
  const view = await viewOf(Splash);
  assert(button(view, "Повторить"));
  assert(!button(view, "Открыть сохранённую поездку"));
});
await test("START multiple trips expand to routes and time without private fields", async () => {
  await save();
  await save({ ...snapshot, bookingId: 43, fromCity: "Кумертау" });
  const view = await viewOf(Splash);
  const toggle = button(view, "Сохранённые поездки");
  assert(toggle && !toggle.props["aria-expanded"]);
  assert(!text(view.tree).includes(snapshot.fromCity));
  toggle.props.onClick();
  await view.settle();
  const rendered = text(view.tree);
  assert(rendered.includes("Кумертау → Сибай") && rendered.includes("Уфа → Сибай"));
  for (const value of [snapshot.driverPhone, snapshot.driverPlate, snapshot.pickup, "4931"]) assert(!rendered.includes(value));
  const chosen = button(view, label => label.startsWith("Кумертау → Сибай") && /\d{2}:\d{2}/.test(label));
  assert(chosen);
  chosen.props.onClick();
  await flush();
  assert.deepEqual(h.navigation, ["/trip/43"]);
  navigator.onLine = true;
  window.dispatchEvent(new Event("online"));
  await view.settle();
  navigator.onLine = false;
  window.dispatchEvent(new Event("offline"));
  await view.settle();
  assert(!button(view, "Сохранённые поездки").props["aria-expanded"]);
});
for (const [field, value] of [["fromCity", " "], ["departAt", "broken"]]) await test("START invalid " + field, async () => {
  await save({ ...snapshot, [field]: value });
  assert.deepEqual((await api.listOfflineTripOptions()).trips, []);
});
await test("START missing time keeps its route", async () => {
  await save({ ...snapshot, departAt: "" });
  await save({ ...snapshot, bookingId: 43, fromCity: "Кумертау" });
  const view = await viewOf(Splash);
  button(view, "Сохранённые поездки").props.onClick();
  await view.settle();
  assert(text(view.tree).includes("Время уточняется"));
});
for (const change of ["cancel", "online", "new session"]) await test("START stale button closes after " + change, async () => {
  await save();
  const view = await viewOf(Splash);
  const selected = button(view, "Открыть сохранённую поездку");
  if (change === "cancel") await api.finishTripPass(owner, id);
  if (change === "online") navigator.onLine = true;
  if (change === "new session") putSession({ ...owner, generation: "session-B" });
  selected.props.onClick();
  await flush();
  assert.deepEqual(h.navigation, []);
});
await test("START ownership mismatch closes both discovery and the card", async () => {
  await save();
  putSession({ ...owner, userId: 8 });
  assert.equal(await read(), null);
  assert.equal(await api.listOfflineTripOptions(), null);
  assert(!button(await viewOf(Splash), "Открыть сохранённую поездку"));
});
await test("START terminal in-transit booking cannot be discovered", async () => {
  await save();
  await api.observeTripPass(owner, id, { status: "cancelled", role: "passenger", cancelled_in_transit: true, cancelled_in_transit_at: new Date().toISOString() }, undefined);
  assert.deepEqual((await api.listOfflineTripOptions()).trips, []);
});
await test("F07 cannot load a legacy pair or an invalid route", async () => {
  values.set(legacy, JSON.stringify({ ...snapshot, ownerId: owner.userId, sessionGeneration: owner.generation, ownershipId: "old" }));
  values.set(legacy + ".owner", JSON.stringify({ ownerId: owner.userId, generation: owner.generation, ownershipId: "old" }));
  assert.equal(await read(), null);
  await save();
  for (const id of [0, -1, NaN, Infinity, 42.5]) assert.equal(await read(id), null);
});
for (const [field, value] of [
  ["ownerId", 8], ["sessionGeneration", "session-B"], ["driverPhone", {}], ["price", "300"],
  ["savedAt", undefined], ["bookingId", 43], ["payMethod", "invalid"],
]) await test("F07 damaged snapshot " + field, async () => {
  await save();
  await changeRow(row => ({ ...row, pass: { ...row.pass, [field]: value } }));
  assert.equal(await read(), null);
});
for (const key of ["yuldash.token", "yuldash.session"]) await test("F07 absent credential " + key, async () => {
  await save();
  values.delete(key);
  assert.equal(await read(), null);
});
await test("F07 unavailable credentials and unavailable IndexedDB do not expose a pass", async () => {
  await save();
  h.readFail = true;
  assert.equal(await read(), null);
  h.readFail = false;
  indexedDB.open = () => { throw Error("disk denied"); };
  assert.equal(await read(), null);
});
for (const failKey of ["yuldash.session", "yuldash.refresh", "yuldash.token"]) await test("F07 partial publication cannot bring back prior credentials' pass " + failKey, async () => {
  await save();
  h.failKey = failKey;
  await assert.rejects(api.setSession(token(8), "refresh-B"));
  putSession(owner);
  assert.equal(await read(), null);
});
await test("F07 logout clears legacy snapshots and preserves device language", async () => {
  await save();
  values.set(legacy, "legacy");
  values.set("yuldash.lang", "ba");
  await api.setSession(null, null);
  api.clearPersonalLocal();
  assert(!values.has(legacy));
  assert.equal(values.get("yuldash.lang"), "ba");
  assert.equal(await api.readTripRecords(owner, () => true), null);
});

for (const lang of ["ru", "ba"]) await test("F07 offline card renders original code and vehicle " + lang, async () => {
  h.lang = lang;
  await save();
  const pass = childPass(await viewOf(Fallback));
  assert(pass);
  const view = await viewOf(PassCard, { pass });
  assert(text(view.tree).includes("QA001"));
  assert(text(view.tree).includes("4931"));
});
for (const status of ["guest", "loading", "authed", "online"]) await test("F07 fallback stays closed for " + status, async () => {
  await save();
  if (status === "online") navigator.onLine = true;
  else h.status = status;
  assert(!childPass(await viewOf(Fallback)));
});
for (const event of ["storage", "online"]) await test("F07 visible card rechecks after " + event, async () => {
  await save();
  const view = await viewOf(Fallback);
  assert(childPass(view));
  if (event === "storage") {
    putSession({ ...owner, generation: "session-B" });
    tabChanged();
  } else {
    navigator.onLine = true;
    window.dispatchEvent(new Event("online"));
  }
  await view.settle();
  assert(!childPass(view));
});
const payLabels = { cash: ["Наличными", "Аҡса менән"], sbp: ["Перевод (СБП)", "Күсереү (СБП)"], negotiate: ["По договорённости", "Килешеү буйынса"] };
for (const [method, labels] of Object.entries(payLabels)) for (const [index, lang] of ["ru", "ba"].entries()) await test("METHOD07 " + method + " " + lang, async () => {
  h.lang = lang;
  await save({ ...snapshot, payMethod: method });
  const pass = childPass(await viewOf(Fallback));
  assert.equal(pass.payMethod, method);
  const view = await viewOf(PassCard, { pass });
  assert(text(view.tree).includes(labels[index]));
  assert(text(view.tree).includes("300"));
});
for (const lang of ["ru", "ba"]) await test("METHOD07 missing or unknown method is not interpreted as cash " + lang, async () => {
  h.lang = lang;
  const view = await viewOf(PassCard, { pass: { ...snapshot, payMethod: "future-unknown" } });
  assert(text(view.tree).includes(lang === "ba" ? "Түләү ысулы һаҡланмаған" : "Способ оплаты не сохранён"));
  assert(!text(view.tree).includes(payLabels.cash[lang === "ba" ? 1 : 0]));
});
for (const lang of ["ru", "ba"]) await test("CANCEL08 offline help keeps only 112 " + lang, async () => {
  h.lang = lang;
  await save();
  await api.observeTripPass(owner, id, { status: "cancelled", role: "passenger", cancelled_in_transit: true, cancelled_in_transit_at: new Date().toISOString() }, undefined);
  const fallback = await viewOf(Fallback);
  assert(!childPass(fallback));
  const help = await viewOf(fallback.tree.type, fallback.tree.props);
  assert(text(help.tree).includes(lang === "ba" ? "SOS хәбәре ебәрелмәй" : "сообщение SOS не отправится"));
  assert(all(help.tree).some(node => node.type === "a" && node.props.href === "tel:112"));
  assert(!all(help.tree).some(node => node.type === "a" && node.props.href?.startsWith("/sos")));
  for (const secret of ["QA001", "4931", "QA Car", snapshot.driverPhone]) assert(!text(help.tree).includes(secret));
});
for (const kind of ["expired", "future", "other login", "no token"]) await test("CANCEL08 offline help stays closed for " + kind, async () => {
  await api.observeTripPass(owner, id, { status: "cancelled", role: "passenger", cancelled_in_transit: true, cancelled_in_transit_at: new Date().toISOString() }, undefined);
  if (kind === "expired") await changeRow(row => ({ ...row, cancelledInTransitAt: Date.now() - api.IN_TRANSIT_MARKER_TTL_MS - 1 }));
  if (kind === "future") await changeRow(row => ({ ...row, cancelledInTransitAt: Date.now() + 10000 }));
  if (kind === "other login") putSession({ ...owner, generation: "session-B" });
  if (kind === "no token") values.delete("yuldash.token");
  assert.equal((await viewOf(Fallback)).tree, null);
});

t.done();
