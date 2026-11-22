// Exercise /taxi from its actual entry point with a fresh React mount and retained browser storage.
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const origin = "http://127.0.0.1:5173", api = "http://127.0.0.1:8000";
const environment = origin + "|" + api;
const owner = { userId: 11, generation: "10000000-0000-4000-8000-000000000011" };
const otherGeneration = "10000000-0000-4000-8000-000000000012";
const first = "20000000-0000-4000-8000-000000000001", second = "20000000-0000-4000-8000-000000000002";
const prefix = "yuldash.taxiConsent.pending.";
const lookup = "/instant/orders/result/v1", close = "/instant/orders/close-attempt/v1";
const history = "/instant/orders/mine?limit=5", availability = "/instant/availability";
const active = { id: 42, role: "passenger", passenger_id: owner.userId, status: "accepted", passenger_price_kop: 42000 };
const recovered = { ...active, id: 41, status: "done" };
const committed = { schema: "taxi-consent-result-v1", state: "committed", order_id: recovered.id,
  initial_payable_kop: 45000, next: "fetch_order" };
const closed = { schema: "taxi-consent-close-v1", state: "closed", history: "not_created", admission: "blocked" };

const client = `
  const state=()=>globalThis.__taxiReload;
  export const API_BASE="${api}";
  export class ApiError extends Error {}
  export const getAccountSession=()=>state().owner;
  export const getSessionGeneration=()=>state().owner.generation;
  export const isAccountSession=o=>o.userId===getAccountSession().userId && o.generation===getSessionGeneration();
  export const apiGet=path=>state().request(path);
  export const apiPost=(path,body)=>state().request(path,body);
  export const apiCommand=async(path,owner,options)=>{
    if(!isAccountSession(owner) || !await options.allowAttempt()) throw Error("attempt not authorized");
    return state().request(path,options.body && JSON.parse(options.body));
  };`;
function buildScreen(flag, lang = "ru") {
  return bundleComponent("src/screens/InstantOrderScreen.tsx", {
    define: { "import.meta.env.VITE_TAXI_QUOTE_CONSENT_LOCAL": JSON.stringify(flag) },
    real: ["../api/instant", "../api/taxiConsent", "../utils/taxiConsent", "../components/TaxiConsentAction"],
    mocks: {
      "../api/client": client, "./client": client,
      "../i18n/lang": langMock(lang),
      "../features": "export const useFeatures=()=>({taxi:true,ready:true});",
      "react-dom": "export const createPortal=child=>child;",
      "react-router-dom": 'export const Link="Link"; export const useNavigate=()=>()=>{};',
      "./AlertDialog": 'export default "AlertDialog";',
      "../utils/sessionCredentials": `export const subscribeSessionChanges=listener=>{
        const events=globalThis.__taxiReload.sessions;
        events.addEventListener("change",listener);
        return ()=>events.removeEventListener("change",listener);
      };`,
    },
  });
}
const [pilot, standard] = await Promise.all([buildScreen("1"), buildScreen("0")]);

class Storage extends Map {
  get length() { return this.size; }
  key(index) { return [...this.keys()][index] ?? null; }
  getItem(key) { return this.get(key) ?? null; }
  setItem(key, value) { this.set(key, String(value)); }
  removeItem(key) { this.delete(key); }
}
function marker(key = first, generation = owner.generation, env = environment, v = 2) {
  localStorage.setItem(prefix + key, JSON.stringify({ v, g: generation, e: env }));
}
function setup({ location = origin, trips = [], city = false } = {}) {
  globalThis.localStorage = new Storage();
  globalThis.document = Object.assign(new EventTarget(), { body: {}, activeElement: null });
  globalThis.window = Object.assign(new EventTarget(), {
    location: new URL("/taxi", location), setInterval: () => 1, clearInterval() {},
  });
  globalThis.HTMLElement = class {};
  const s = globalThis.__taxiReload = {
    owner, sessions: new EventTarget(), calls: [], bodies: [], responses: { [history]: trips, [availability]: { enabled: city },
      [close]: closed, [lookup]: committed, ["/instant/orders/" + recovered.id]: recovered },
    async request(path, body) {
      s.calls.push(path);
      if (body !== undefined) s.bodies.push(body);
      assert.ok(path in s.responses, "unexpected API call: " + path);
      return s.responses[path];
    },
  };
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: { geolocation: {
    getCurrentPosition(_ok, unavailable) { s.calls.push("GPS"); unavailable(); },
  } } });
  return s;
}
const named = (view, name) => all(view.tree).find(n => typeof n.type === "function" && n.type.name === name);
// Both production components keep their own hooks. Pass the exact props rendered by the screen.
async function settle(view) { globalThis.__sh = view.state; await view.settle(); }
async function actionFor(screen) {
  const node = named(screen, "TaxiConsentAction");
  assert.ok(node, "the /taxi entry must expose recovery before GPS, history and the city gate");
  const action = mount(node.type, node.props);
  await settle(action);
  return action;
}
function click(view, label) {
  const control = button(view.tree, label);
  assert.ok(control && !control.props.disabled, "missing enabled action: " + label);
  control.props.onClick();
}
const t = checker();
await t.test("reloaded /taxi resolves the saved key before another active trip or city availability", async () => {
  const s = setup({ trips: [active] });
  marker();
  const screen = mount(pilot);
  await settle(screen);
  assert.deepEqual(s.calls, [], "an unresolved attempt must not wait for GPS or generic order history");
  const action = await actionFor(screen);
  window.dispatchEvent(new Event("focus"));
  document.dispatchEvent(new Event("visibilitychange"));
  await settle(action);
  assert.deepEqual(s.calls, [], "focus does not read, submit, or close an order");
  click(action, "Проверить результат заказа");
  await settle(action);
  await settle(screen);
  assert.equal(named(screen, "TrackingView")?.props.order, recovered);
  assert.equal(localStorage.getItem(prefix + first), null);
  assert.deepEqual(s.bodies, [{ request_key: first }]);
  assert.deepEqual(s.calls, [lookup, "/instant/orders/41"]);
  action.unmount();
  named(screen, "TrackingView").props.onNewOrder();
  await settle(screen);
  assert.equal(named(screen, "TrackingView")?.props.order, active);
  assert.deepEqual(s.calls, [lookup, "/instant/orders/41", "GPS", history]);
  screen.unmount();
});

async function confirmClose(action, label = "Завершить попытку") {
  click(action, label);
  await settle(action);
  const dialog = ofType(action.tree, "AlertDialog")[0];
  assert.ok(dialog, "closing needs a second explicit passenger action");
  dialog.props.confirm.onClick();
  await settle(action);
}

await t.test("closing one of two attempts keeps the other accessible; continuing then rechecks city availability", async () => {
  const s = setup();
  marker(first);
  marker(second, owner.generation, environment, 3);
  const screen = mount(pilot);
  await settle(screen);
  const action = await actionFor(screen);
  assert.deepEqual(s.calls, []);
  await confirmClose(action);
  assert.equal(localStorage.getItem(prefix + first), null);
  assert.ok(localStorage.getItem(prefix + second));
  assert.equal(button(action.tree, "Продолжить"), undefined);
  assert.deepEqual(s.calls, [close]);
  await confirmClose(action);
  assert.deepEqual(s.bodies, [{ request_key: first }, { request_key: second }]);
  assert.deepEqual(s.calls, [close, close], "closing does not auto-start another order");
  click(action, "Продолжить");
  await settle(screen);
  assert.deepEqual(s.calls, [close, close, "GPS", history, availability]);
  assert.match(text(screen.tree), /Такси скоро/);
  assert.equal(named(screen, "ComposeView"), undefined);
  action.unmount(); screen.unmount();
});

await t.test("a recoverable attempt remains available when its lookup is inconclusive", async () => {
  const s = setup();
  s.responses[lookup] = { schema: "taxi-consent-result-v1", state: "unknown" };
  marker();
  const screen = mount(pilot);
  await settle(screen);
  const action = await actionFor(screen);
  click(action, "Проверить результат заказа");
  await settle(action);
  assert.ok(localStorage.getItem(prefix + first));
  assert.ok(button(action.tree, "Проверить результат заказа"));
  assert.equal(button(action.tree, "Продолжить"), undefined);
  assert.deepEqual(s.calls, [lookup]);
  action.unmount(); screen.unmount();
});

await t.test("a Bashkir passenger can check and close the third saved attempt by its displayed number", async () => {
  const s = setup();
  const third = "20000000-0000-4000-8000-000000000003";
  s.responses[lookup] = { schema: "taxi-consent-result-v1", state: "unknown" };
  for (const key of [third, first, second]) marker(key);
  const screen = mount(await buildScreen("1", "ba"));
  await settle(screen);
  const action = await actionFor(screen);
  const options = ofType(action.tree, "option");
  assert.deepEqual(options.map(text), ["Ынтылыш № 1", "Ынтылыш № 2", "Ынтылыш № 3"]);
  ofType(action.tree, "select")[0].props.onChange({ target: { value: options[2].props.value } });
  await settle(action);
  click(action, "Заказ һөҙөмтәһен тикшереү");
  await settle(action);
  await confirmClose(action, "Ынтылышты тамамлау");
  assert.deepEqual(s.calls, [lookup, close]);
  assert.deepEqual(s.bodies, [{ request_key: third }, { request_key: third }]);
  assert.equal(localStorage.getItem(prefix + third), null);
  assert.ok(localStorage.getItem(prefix + first));
  assert.ok(localStorage.getItem(prefix + second));
  action.unmount(); screen.unmount();
});

for (const [scenario, entry, seed] of [
  ["ordinary build", standard, () => marker()],
  ["empty local pilot", pilot, () => {}],
  ["another login's marker", pilot, () => marker(first, otherGeneration)],
  ["another environment's marker", pilot, () => marker(first, owner.generation, origin + "|http://127.0.0.1:9000")],
]) {
  await t.test(scenario + " preserves the normal /taxi entry", async () => {
    const s = setup({ city: true });
    seed();
    const originalStorage = [...localStorage.entries()];
    const screen = mount(entry);
    await settle(screen);
    assert.ok(named(screen, "ComposeView"));
    assert.deepEqual(s.calls, ["GPS", history, availability]);
    assert.deepEqual([...localStorage.entries()], originalStorage);
    screen.unmount();
  });
}

await t.test("invalid pilot location fails before recovery, GPS or any API call", async () => {
  const s = setup({ location: "https://outside.invalid" });
  marker();
  const screen = mount(pilot);
  await settle(screen);
  assert.match(text(screen.tree), /Локальный режим/);
  assert.equal(named(screen, "TaxiConsentAction"), undefined);
  assert.deepEqual(s.calls, []);
  assert.ok(localStorage.getItem(prefix + first));
  screen.unmount();
});

for (const [scenario, seed] of [
  ["legacy marker without a server key", () => marker(first, owner.generation, environment, 1)],
  ["unreadable storage", () => Object.defineProperty(localStorage, "length", { get() { throw Error("blocked"); } })],
  ["malformed marker", () => localStorage.setItem(prefix + first, "{")],
]) {
  await t.test(scenario + " offers only the passenger's trip history", async () => {
    const s = setup({ city: true });
    seed();
    const screen = mount(pilot);
    await settle(screen);
    const action = await actionFor(screen);
    assert.equal(button(action.tree, "Проверить результат заказа"), undefined);
    assert.equal(button(action.tree, "Завершить попытку"), undefined);
    assert.equal(button(action.tree, "Продолжить"), undefined);
    assert.equal(ofType(action.tree, "Link")[0]?.props.to, "/my-taxi");
    assert.deepEqual(s.calls, []);
    action.unmount(); screen.unmount();
  });
}
await t.test("late trip history from a previous login cannot displace the new login's saved attempt", async () => {
  let finishHistory;
  const s = setup({ trips: new Promise(resolve => { finishHistory = resolve; }) });
  marker(first, otherGeneration);
  const screen = mount(pilot);
  await settle(screen);
  assert.deepEqual(s.calls, ["GPS", history]);

  s.owner = { userId: 12, generation: otherGeneration };
  s.sessions.dispatchEvent(new Event("change"));
  await settle(screen);
  finishHistory([active]);
  await settle(screen);

  assert.equal(named(screen, "TrackingView"), undefined);
  const action = await actionFor(screen);
  assert.ok(button(action.tree, "Проверить результат заказа"));
  assert.deepEqual(s.calls, ["GPS", history]);
  action.unmount(); screen.unmount();
});
t.done();
