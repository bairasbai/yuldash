// Execute the passenger action, storage rules and API parsers. Replace only HTTP and browser services.
import assert from "node:assert/strict";
import { bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const origin = "http://127.0.0.1:5173";
const api = "http://127.0.0.1:8000";
const environment = origin + "|" + api;
const account = { userId: 11, generation: "10000000-0000-4000-8000-000000000011" };
const other = { userId: 12, generation: "10000000-0000-4000-8000-000000000012" };
const first = "20000000-0000-4000-8000-000000000001";
const second = "20000000-0000-4000-8000-000000000002";
const prefix = "yuldash.taxiConsent.pending.";
const close = "/instant/orders/close-attempt/v1";
const lookup = "/instant/orders/result/v1";
const quote = "/instant/quotes/v1";
const confirmOrder = "/instant/orders/confirmed/v1";
const closed = { schema: "taxi-consent-close-v1", state: "closed", history: "not_created", admission: "blocked" };
const committed = { schema: "taxi-consent-result-v1", state: "committed", order_id: 41, initial_payable_kop: 45000, next: "fetch_order" };

const clientMock = `
  export const API_BASE = "${api}";
  export class ApiError extends Error {}
  export const getAccountSession = () => globalThis.__taxiRecovery.owner;
  export const getSessionGeneration = () => getAccountSession().generation;
  export const isAccountSession = owner => owner.userId === getAccountSession().userId && owner.generation === getSessionGeneration();
  export const apiCommand = (...args) => globalThis.__taxiRecovery.command(...args);
`;
const Component = await bundleComponent("src/components/TaxiConsentAction.tsx", {
  real: ["../api/taxiConsent", "../utils/taxiConsent", "../utils/serverTime", "../utils/format"],
  define: { "import.meta.env.VITE_TAXI_QUOTE_CONSENT_LOCAL": '"1"' },
  mocks: {
    "../api/client": clientMock, "./client": clientMock,
    "../api/instant": "export const fetchInstantOrder=(...args)=>globalThis.__taxiRecovery.card(...args);",
    "../utils/sessionCredentials": "export const subscribeSessionChanges=()=>()=>{};",
    "../i18n/lang": langMock(),
    "react-dom": "export const createPortal = child => child;",
  },
});

class MemoryStorage extends Map {
  get length() { return this.size; }
  key(i) { return [...this.keys()][i] ?? null; }
  getItem(k) { return this.get(k) ?? null; }
  setItem(k, v) { this.set(k, String(v)); }
  removeItem(k) { this.delete(k); }
}
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
function addMarker(key, owner = account) {
  localStorage.setItem(prefix + key, JSON.stringify({ v: 2, g: owner.generation, e: environment }));
}
function version(key) { return JSON.parse(localStorage.getItem(prefix + key))?.v; }

async function setup(respond, { existing = true } = {}) {
  globalThis.localStorage = new MemoryStorage();
  globalThis.window = Object.assign(new EventTarget(), { location: new URL(origin), setInterval: () => 1, clearInterval() {} });
  globalThis.document = Object.assign(new EventTarget(), { body: {}, activeElement: null });
  globalThis.HTMLElement = class {};
  if (existing) addMarker(first);
  const state = globalThis.__taxiRecovery = {
    owner: account, paths: [], opened: [], cards: [],
    async command(path, owner, options) {
      assert.equal(owner, state.owner);
      assert.equal(options.method, "POST");
      assert.equal(options.oneShot, true);
      assert.equal(await options.allowAttempt(), true);
      state.paths.push(path);
      return respond(path, JSON.parse(options.body));
    },
    async card(id, _signal, options) {
      assert.equal(options.owner, state.owner);
      assert.equal(await options.allowAttempt(), true);
      state.cards.push(id);
      return state.currentCard;
    },
  };
  const props = { body: { category: "standard" }, environment, onOrdered: order => state.opened.push(order),
    onQuote() {}, onLockChange() {}, onSessionReset() {} };
  const view = mount(Component, props);
  await view.settle();
  return { state, view };
}
async function openClose(view) {
  const trigger = button(view.tree, "Завершить попытку");
  assert.ok(trigger && !trigger.props.disabled);
  trigger.props.onClick();
  await view.settle();
  const dialog = ofType(view.tree, "AlertDialog")[0];
  assert.ok(dialog, "closing requires a separate dialog");
  return dialog.props;
}
async function confirmClose(view) {
  const dialog = await openClose(view);
  dialog.confirm.onClick();
  await view.settle();
}

const t = checker();
await t.test("only explicit confirmation closes an attempt; loss and reload keep the barrier", async () => {
  let loseReply = true;
  const { state, view } = await setup((path, body) => {
    if (path === quote) throw Error("end of synthetic quote flow");
    assert.equal(path, close);
    assert.deepEqual(body, { request_key: first });
    if (loseReply) throw Error("synthetic response loss");
    return closed;
  });
  addMarker(second, other);
  window.dispatchEvent(new Event("focus"));
  document.dispatchEvent(new Event("visibilitychange"));
  await view.settle();
  const dialog = await openClose(view);
  dialog.dismiss.onClick();
  await view.settle();
  assert.deepEqual(state.paths, []);
  const confirm = (await openClose(view)).confirm;
  confirm.onClick();
  confirm.onClick();
  await view.settle();
  assert.deepEqual(state.paths, [close]);
  assert.equal(version(first), 3, "the intent to stop survives a lost close response");
  view.unmount();

  const reload = mount(Component, view.props);
  await reload.settle();
  assert.deepEqual(state.paths, [close], "reload may not retry a close or create command");
  assert.equal(button(reload.tree, "Рассчитать новую цену"), undefined);
  loseReply = false;
  await confirmClose(reload);
  assert.equal(version(first), undefined);
  assert.equal(version(second), 2, "another login's marker must survive");
  assert.deepEqual(state.paths, [close, close]);
  const next = button(reload.tree, "Рассчитать новую цену");
  assert.ok(next && !next.props.disabled);
  next.props.onClick();
  await reload.settle();
  assert.deepEqual(state.paths, [close, close, quote]);
  reload.unmount();
});

await t.test("one close proof releases only its key even within the same session", async () => {
  const { state, view } = await setup((path, body) => {
    assert.equal(path, close);
    assert.deepEqual(body, { request_key: first });
    return closed;
  });
  addMarker(second);
  window.dispatchEvent(new Event("focus"));
  await view.settle();
  await confirmClose(view);
  assert.equal(version(first), undefined);
  assert.equal(version(second), 2);
  assert.deepEqual(state.paths, [close]);
  assert.ok(button(view.tree, "Проверить результат заказа"));
  assert.equal(button(view.tree, "Рассчитать новую цену"), undefined);
  view.unmount();
});

for (const [outcome, history, message] of [
  ["retired", "created_unavailable", /Заказ был создан/],
  ["unknown", "unverified", /Неизвестно, был ли заказ создан/],
]) {
  await t.test(outcome + " explains history and retains the barrier after another lookup", async () => {
    const { state, view } = await setup(path => path === lookup
      ? { schema: "taxi-consent-result-v1", state: "unknown" }
      : { schema: "taxi-consent-close-v1", state: outcome, history, admission: "blocked" });
    await confirmClose(view);
    assert.match(text(view.tree), message);
    button(view.tree, "Проверить результат заказа").props.onClick();
    await view.settle();
    assert.match(text(view.tree), message);
    assert.equal(ofType(view.tree, "Link")[0]?.props.to, "/my-taxi");
    assert.equal(version(first), 3);
    assert.equal(button(view.tree, "Рассчитать новую цену"), undefined);
    assert.deepEqual(state.paths, [close, lookup]);
    view.unmount();
  });
}

await t.test("committed requires an authorized current card before navigating and clearing", async () => {
  const { state, view } = await setup(() => committed);
  const card = { id: 41, role: "passenger", passenger_id: account.userId, status: "done", passenger_price_kop: 42000 };
  state.currentCard = { ...card, passenger_id: other.userId };
  await confirmClose(view);
  assert.deepEqual(state.cards, [41]);
  assert.deepEqual(state.opened, []);
  assert.equal(version(first), 3);
  state.currentCard = card;
  button(view.tree, "Проверить результат заказа").props.onClick();
  await view.settle();
  assert.deepEqual(state.cards, [41, 41]);
  assert.deepEqual(state.opened, [card], "historical initial payable cannot replace the current fare");
  assert.equal(version(first), undefined);
  assert.deepEqual(state.paths, [close, lookup]);
  view.unmount();
});

async function startUnknownConfirmation(onRecovery) {
  const { state, view } = await setup((path, payload) => {
    if (path === quote) return {
      schema: "taxi-initial-consent-v1", quote_id: second, request_key: first,
      expires_at: new Date(Date.now() + 120000).toISOString(),
      snapshot_digest: "a".repeat(64), pricing_inputs_digest: "b".repeat(64),
      price_kop: 45000, promo_discount_kop: 0, payable_kop: 45000,
      breakdown: { ride_price_kop: 45000, pickup_fee_kop: 0, options_fee_kop: 0, weather_fee_kop: 0 },
      distance_km: 8, eta_min: 12, round_trip: false,
    };
    if (path === confirmOrder) throw Error("synthetic lost confirmation");
    return onRecovery(path, payload);
  }, { existing: false });
  button(view.tree, "Заказать за").props.onClick();
  await view.settle();
  ofType(view.tree, "AlertDialog")[0].props.confirm.onClick();
  await view.settle();
  assert.deepEqual(state.paths, [quote, confirmOrder]);
  assert.equal(version(first), 2);
  return { state, view };
}

await t.test("a stale retry dialog cannot confirm once another tab has begun closing the attempt", async () => {
  const { state, view } = await startUnknownConfirmation(path => assert.fail("unexpected request: " + path));
  button(view.tree, "Повторить подтверждение").props.onClick();
  await view.settle();
  const staleRetry = ofType(view.tree, "AlertDialog")[0].props.confirm;

  const marker = JSON.parse(localStorage.getItem(prefix + first));
  localStorage.setItem(prefix + first, JSON.stringify({ ...marker, v: 3 }));
  // No storage event yet: the tab can still receive a click on the old dialog.
  staleRetry.onClick();
  window.dispatchEvent(new Event("focus"));
  await view.settle();
  assert.deepEqual(state.paths, [quote, confirmOrder]);
  assert.equal(version(first), 3);
  assert.equal(button(view.tree, "Повторить подтверждение"), undefined);
  view.unmount();
});

for (const outcome of ["closed", "committed"]) {
  await t.test("an open tab can verify " + outcome + " after another tab removes its marker", async () => {
    const { state, view } = await startUnknownConfirmation((path, body) => {
      assert.deepEqual(body, { request_key: first });
      if (path === lookup) return outcome === "committed" ? committed : { schema: "taxi-consent-result-v1", state: "unknown" };
      assert.equal(path, close);
      return closed;
    });
    const card = { id: 41, role: "passenger", passenger_id: account.userId, status: "done", passenger_price_kop: 42000 };
    state.currentCard = card;
    localStorage.removeItem(prefix + first); // Another tab already verified an authoritative server result.
    window.dispatchEvent(new Event("focus"));
    await view.settle();
    assert.deepEqual(state.paths, [quote, confirmOrder], "storage removal alone proves no server outcome");
    assert.equal(button(view.tree, "Повторить подтверждение"), undefined);
    assert.equal(button(view.tree, "Рассчитать новую цену"), undefined);
    const read = button(view.tree, "Проверить результат заказа");
    assert.ok(read && !read.props.disabled, "same-tab recovery must remain available without reload");
    read.props.onClick();
    await view.settle();
    if (outcome === "committed") {
      assert.deepEqual(state.opened, [card]);
      assert.deepEqual(state.paths, [quote, confirmOrder, lookup]);
    } else {
      assert.deepEqual(state.opened, []);
      assert.equal(button(view.tree, "Рассчитать новую цену"), undefined);
      await confirmClose(view);
      assert.ok(button(view.tree, "Рассчитать новую цену"));
      assert.deepEqual(state.paths, [quote, confirmOrder, lookup, close]);
    }
    view.unmount();
  });
}

await t.test("malformed close and a late previous-account response cannot clear a marker", async () => {
  const late = deferred();
  let calls = 0;
  const { state, view } = await setup(() => ++calls === 1 ? { ...closed, history: "unverified" } : late.promise);
  await confirmClose(view);
  assert.equal(version(first), 3);
  await confirmClose(view);
  state.owner = other;
  view.render();
  late.resolve(closed);
  await view.settle();
  assert.equal(version(first), 3);
  assert.deepEqual(state.paths, [close, close]);
  assert.deepEqual(state.opened, []);
  view.unmount();
});
t.done();
