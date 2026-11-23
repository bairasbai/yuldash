import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const flag = "import.meta.env.VITE_TAXI_DRIVER_OFFER_PRECONDITION_LOCAL";
const route = "/instant/driver/offer";
const accept = "/instant/orders/42/accept/v2";
const read = "/instant/orders/42";
const owner = { userId: 17, generation: "first-login" };
const make = (value, patch = {}) => ({ id: 42, role: "driver", status: "offered",
  driver_gross_kop: 70000, from_text: "А", to_text: "Б", stops: [], offer_card_precondition: "v1." + value.repeat(64), ...patch });
const first = make("a");
const updated = make("b", { driver_gross_kop: 85000, to_text: "В" });
const adopted = { id: first.id, role: "driver", status: "accepted", driver_id: owner.userId };
globalThis.__driverV2ApiError = class ApiError extends Error {
  constructor(status, code) { super("synthetic HTTP response"); this.status = status; this.code = code; }
};
const client = `
  const env=()=>globalThis.__driverV2;
  export const API_BASE="http://127.0.0.1:8000";
  export const ApiError=globalThis.__driverV2ApiError;
  export const getAccountSession=()=>env().owner;
  export const isAccountSession=o=>env().owner?.userId===o.userId && env().owner?.generation===o.generation;
  export const apiCommand=(path,owner,opts)=>env().request(path,owner,opts);
`;
async function build(mode, lang = "ru") {
  return bundleComponent("src/screens/InstantDriverTripScreen.tsx", {
    define: { [flag]: JSON.stringify(mode) },
    real: ["../api/taxiDriverOffer", "../utils/driverOfferExpiry", "../utils/serverTime", "../utils/format"],
    mocks: {
      "../api/client": client, "./client": client,
      "../i18n/lang": langMock(lang),
      "react-router-dom": "export const useNavigate=()=>()=>{};",
      "../utils/sessionCredentials": `export const subscribeSessionChanges=fn=>{
        const e=globalThis.__driverV2.auth; e.addEventListener("change",fn);
        return ()=>e.removeEventListener("change",fn);
      };`,
      "../api/driver": `export const fetchDriverStatus=async()=>({online:true}); export const setDriverOnline=async()=>{};`,
      "../api/instant": `
        const s=()=>globalThis.__driverV2;
        export const fetchTaxiApplication=async()=>({status:"approved"});
        export const fetchPretrip=async()=>null, fetchWorkday=async()=>null, fetchWorkZone=async()=>null;
        export const sendPresence=async()=>s().presence(), fetchDemand=async()=>({zones:[]});
        export const fetchDriverOffer=async()=>{s().legacy.push("poll");return {offer:s().card};};
        export const acceptOrder=async(id)=>{s().legacy.push("accept:"+id);return s().adopted;};
        export const fetchInstantOrder=async()=>s().adopted;
        export const declineOrder=async(id)=>{s().legacy.push("decline:"+id);};
        export const DECLINE_REASONS=[], arrivedOrder=async()=>{}, onboardOrder=async()=>{}, doneOrder=async()=>{}, cancelInstantOrder=async()=>{};`,
    },
  });
}
const Screen = await build("1");
const Legacy = await build("0");
const overlay = view => all(view.tree).find(n => typeof n.type === "function" && n.type.name === "OfferOverlay");
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { resolve, promise }; }

async function setup(ScreenType = Screen, location = "http://127.0.0.1:5173", card = first, position = false) {
  const intervals = new Map(), values = new Map(), legacy = [];
  globalThis.window = {
    location: new URL(location), setInterval(f, delay) { const id = {}; intervals.set(id, { f, delay }); return id; },
    clearInterval(id) { intervals.delete(id); },
  };
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: position ? { geolocation: {
    watchPosition(next) { next({ coords: { latitude: 0, longitude: 0 } }); return 1; }, clearWatch() {},
  } } : {} });
  globalThis.localStorage = { getItem: k => values.get(k), setItem: (k, v) => values.set(k, v) };
  const env = globalThis.__driverV2 = {
    owner, auth: new EventTarget(), card, adopted, legacy, calls: [],
    readState: () => first, acceptAction: () => adopted, presence: () => {},
    async request(path, who, opts) {
      assert.equal(who, owner);
      assert.ok(!opts.signal.aborted);
      env.calls.push({ path, opts });
      if (path === route) return { offer: env.card };
      if (path === accept) {
        assert.equal(opts.oneShot, true);
        assert.ok(await opts.allowAttempt(), "the displayed card must still be valid at dispatch");
        return env.acceptAction();
      }
      if (path === read) return env.readState();
      throw Error("unexpected protected endpoint " + path);
    },
  };
  const view = mount(ScreenType);
  await view.settle();
  const tick = async () => { for (const item of intervals.values()) if (item.delay === 3000) item.f(); await view.settle(); };
  const heartbeat = async () => { for (const item of intervals.values()) if (item.delay === 15000) item.f(); await view.settle(); };
  return { env, view, tick, values, heartbeat };
}
const paths = x => x.calls.map(y => y.path);
const t = checker();

await t.test("legacy uses original endpoint and a requested non-local mode never falls through", async () => {
  const a = await setup(Legacy);
  const old = overlay(a.view).props;
  assert.equal(await old.onAccept(old.order), "accepted");
  await a.view.settle();
  assert.deepEqual(a.env.legacy, ["poll", "accept:42"]);
  assert.deepEqual(a.env.calls, []);
  a.view.unmount();
  const b = await setup(Screen, "https://outside.invalid");
  assert.equal(overlay(b.view), undefined);
  assert.deepEqual(b.env.legacy, []);
  assert.deepEqual(b.env.calls, []);
  b.view.unmount();
});

await t.test("same-ID updated card cannot be accepted through the old action; fresh click sends exact current tag", async () => {
  const { env, view, tick } = await setup();
  const old = overlay(view).props;
  env.card = updated;
  await tick();
  assert.equal(overlay(view).props.order, updated);
  assert.equal(await old.onAccept(old.order), "closed");
  assert.deepEqual(paths(env), [route, route]);
  const next = overlay(view).props;
  assert.equal(await next.onAccept(next.order), "accepted");
  assert.deepEqual(JSON.parse(env.calls.at(-1).opts.body), { offer_card_precondition: updated.offer_card_precondition });
  assert.deepEqual(env.legacy, []);
  view.unmount();
});

await t.test("accept suspended in flight locks same ID; a transport error stays unknown until explicit owned GET", async () => {
  const { env, view, tick, values } = await setup();
  const response = deferred();
  env.acceptAction = async () => { await response.promise; throw new globalThis.__driverV2ApiError(401, "auth"); };
  const clicked = overlay(view).props;
  const running = clicked.onAccept(clicked.order);
  env.card = updated;
  await tick();
  assert.equal(overlay(view).props.order, first);
  assert.equal(await clicked.onAccept(clicked.order), "closed");
  response.resolve();
  assert.equal(await running, "closed");
  await view.settle();
  const unknown = overlay(view).props;
  assert.equal(unknown.pending, true);
  await unknown.onSkip(true);
  await unknown.onSkip(false);
  await unknown.onAccept(unknown.order);
  await tick();
  assert.deepEqual(paths(env), [route, accept]);
  assert.deepEqual(env.legacy, []);

  env.readState = () => { throw new globalThis.__driverV2ApiError(404, "not-found"); };
  await unknown.onCheck();
  await view.settle();
  assert.equal(overlay(view).props.pending, true);
  assert.equal(values.size, 0);
  env.readState = () => ({ ...adopted, driver_id: owner.userId + 1 });
  await unknown.onCheck();
  await view.settle();
  assert.equal(overlay(view).props.pending, true);
  env.readState = () => ({ ...adopted, id: adopted.id + 1 });
  await unknown.onCheck();
  await view.settle();
  assert.equal(overlay(view).props.pending, true, "same owner on a different order cannot resolve the attempted order");
  env.readState = () => adopted;
  await unknown.onCheck();
  await view.settle();
  assert.equal(overlay(view), undefined);
  assert.equal(values.get("yuldash.taxi.activeOrder"), "42");
  assert.deepEqual(paths(env), [route, accept, read, read, read, read]);
  view.unmount();
});

for (const [lang, label, busyLabel] of [
  ["ru", "Проверить результат", "Проверяем…"],
  ["ba", "Һөҙөмтәне тикшереү", "Тикшерәбеҙ…"],
]) {
  await t.test(`manual unknown-outcome check is visible and read-only after heartbeat 403 (${lang})`, async () => {
    const { env, view, tick, values, heartbeat } = await setup(
      lang === "ru" ? Screen : await build("1", "ba"), "http://127.0.0.1:5173", first, true,
    );
    const response = deferred();
    env.acceptAction = async () => { await response.promise; throw new globalThis.__driverV2ApiError(0, "unknown"); };
    const clicked = overlay(view).props;
    const submitting = clicked.onAccept(clicked.order);
    env.presence = () => { throw new globalThis.__driverV2ApiError(403, "offline"); };
    await heartbeat();
    assert.equal(overlay(view), undefined, "heartbeat transitions to the actual offline screen");
    assert.deepEqual(paths(env), [route, accept]);
    assert.equal(button(view.tree, label), undefined, "POST is still in flight, not yet a recovery attempt");
    response.resolve();
    assert.equal(await submitting, "closed");
    await view.settle();
    assert.ok(button(view.tree, label), "the only manual recheck must survive offer overlay removal");
    assert.equal(button(view.tree, "Принять"), undefined);
    assert.equal(button(view.tree, "Ҡабул итеү"), undefined);
    assert.equal(values.size, 0);

    const lookup = deferred(), check = button(view.tree, label).props.onClick;
    env.readState = () => lookup.promise;
    check();
    check(); // the existing operation ref must block a double tap before render
    await view.settle();
    assert.equal(button(view.tree, busyLabel).props.disabled, true);
    assert.deepEqual(paths(env), [route, accept, read]);
    assert.equal(env.calls.at(-1).opts.method, "GET");
    lookup.resolve({ ...adopted, driver_id: owner.userId + 1 });
    await view.settle();
    assert.ok(button(view.tree, label));
    assert.equal(values.size, 0);

    env.readState = () => { throw new globalThis.__driverV2ApiError(404, "unproven"); };
    button(view.tree, label).props.onClick();
    await view.settle();
    assert.ok(button(view.tree, label), "a failed GET is not a negative result");
    await tick();
    assert.deepEqual(paths(env), [route, accept, read, read]);

    const late = deferred();
    env.readState = () => lang === "ru" ? adopted : late.promise;
    button(view.tree, label).props.onClick();
    if (lang === "ba") {
      env.owner = { ...owner, generation: "new-login" };
      env.auth.dispatchEvent(new Event("change"));
      late.resolve(adopted);
    }
    await view.settle();
    assert.equal(button(view.tree, label), undefined);
    assert.equal(values.get("yuldash.taxi.activeOrder"), lang === "ru" ? "42" : undefined);
    assert.deepEqual(paths(env), [route, accept, read, read, read]);
    assert.deepEqual(env.legacy, []);
    view.unmount();
  });
}

await t.test("exact changed-offer code refreshes without accepting; repeated rejected tag stays hidden", async () => {
  const { env, view, tick } = await setup();
  env.acceptAction = () => { throw new globalThis.__driverV2ApiError(409, "taxi_offer_changed"); };
  const old = overlay(view).props;
  assert.equal(await old.onAccept(old.order), "closed");
  await view.settle();
  assert.equal(overlay(view), undefined);
  assert.deepEqual(paths(env), [route, accept, route]);
  env.card = updated;
  await tick();
  const refreshed = overlay(view).props;
  assert.equal(refreshed.order, updated);
  assert.match(refreshed.note, /Условия заказа изменились/);
  assert.deepEqual(paths(env), [route, accept, route, route]);
  env.acceptAction = () => adopted;
  assert.equal(await refreshed.onAccept(refreshed.order), "accepted");
  assert.deepEqual(paths(env), [route, accept, route, route, accept]);
  view.unmount();
});

for (const reason of ["logout", "unmount"]) {
  await t.test(reason + " cannot publish a late accept or use discarded offer actions", async () => {
    const { env, view, values } = await setup();
    const response = deferred();
    env.acceptAction = () => response.promise;
    const clicked = overlay(view).props;
    const running = clicked.onAccept(clicked.order);
    await view.settle();
    if (reason === "logout") {
      env.owner = { userId: owner.userId, generation: "new-login" };
      env.auth.dispatchEvent(new Event("change"));
      await view.settle();
      assert.equal(overlay(view), undefined);
    } else view.unmount();
    response.resolve(adopted);
    assert.equal(await running, "closed");
    assert.equal(await clicked.onAccept(clicked.order), "closed");
    assert.equal(values.size, 0);
    assert.deepEqual(paths(env), [route, accept]);
    view.unmount();
  });
}

await t.test("actual offer overlay passes the displayed card, has an accessible result action and never auto-declines a protected expiry", async () => {
  const { env, view } = await setup();
  const overlayNode = overlay(view);
  let selected, lookup = 0, skipped = 0;
  const order = { ...first, offer_expires_at: new Date(Date.now() + 30_000).toISOString() };
  const card = mount(overlayNode.type, { ...overlayNode.props, order, onAccept: async (shown) => { selected = shown; return "closed"; },
    onSkip: () => skipped++, onCheck: () => lookup++ });
  await card.settle();
  const take = button(card.tree, "Принять");
  assert.ok(take && !take.props.disabled);
  take.props.onClick();
  assert.equal(selected, order);
  card.props = { ...card.props, pending: true, order: { ...order, offer_expires_at: new Date(1).toISOString() } };
  card.render();
  await card.settle();
  assert.equal(button(card.tree, "Пропустить").props.disabled, true);
  assert.ok(!button(card.tree, "Принять"));
  button(card.tree, "Проверить результат").props.onClick();
  assert.equal(lookup, 1);
  assert.equal(skipped, 0);
  card.unmount();
  globalThis.__sh = view.state;
  assert.deepEqual(paths(env), [route]);
  view.unmount();
});

await t.test("untagged older-server offer cannot accept or fall back", async () => {
  const { env, view } = await setup(Screen, undefined, { ...first, offer_card_precondition: undefined });
  const card = overlay(view).props;
  assert.equal(await card.onAccept(card.order), "closed");
  assert.deepEqual(paths(env), [route]);
  assert.deepEqual(env.legacy, []);
  view.unmount();
});

for (const lang of ["ru", "ba"]) {
  await t.test(lang + ": stopped offer shows only neutral ordered stops and explicit gross with no direct map line", async () => {
    const entry = lang === "ru" ? Screen : await build("1", "ba");
    const offer = { ...first, from_lat: 54, from_lng: 56, to_lat: 55, to_lng: 57,
      from_text: "  Исходная точка ", to_text: " Конечная точка   ", driver_net_kop: 62000,
      offer_expires_at: new Date(Date.now() + 30_000).toISOString(),
      stops: [{ lat: 54.512987, lng: 56.512987, text: "Синтетический частный адрес, квартира 913" }, { lat: 54.8, lng: 56.8, text: "" }] };
    const { view } = await setup(entry, undefined, offer);
    const node = overlay(view);
    const card = mount(node.type, node.props);
    await card.settle();
    const list = all(card.tree).find(n => n.type === "ol");
    assert.equal(list.props["aria-label"], lang === "ru" ? "Маршрут по порядку" : "Маршрут тәртип буйынса");
    assert.deepEqual(all(list).filter(n => n.type === "li").map(n => text(n.props.children).trim()),
      lang === "ru" ? ["Точка АИсходная точка", "Остановка 1", "Остановка 2", "Точка БКонечная точка"]
        : ["А нөктәһеИсходная точка", "1-се туҡтау", "2-се туҡтау", "Б нөктәһеКонечная точка"]);
    const copy = text(card.tree);
    const attributes = all(card.tree).flatMap(n => Object.entries(n.props)
      .filter(([name]) => name.startsWith("aria-") || name === "title").map(([, value]) => String(value))).join(" ");
    for (const value of [copy, attributes, JSON.stringify(all(card.tree).find(n => n.type === "YandexMap").props)]) {
      assert.doesNotMatch(value, /частный адрес|квартира 913|54\.512987|56\.512987/);
    }
    assert.match(copy, lang === "ru" ? /Сумма заказа до комиссии: 700 *₽/ : /Комиссияға тиклем заказ хаҡы: 700 *₽/);
    assert.match(copy, lang === "ru" ? /Предполагаемый доход620 *₽/ : /Көтөлгән килем620 *₽/);
    assert.equal(all(card.tree).find(n => n.type === "YandexMap").props.route, false);
    card.unmount();
    globalThis.__sh = view.state;
    view.unmount();
  });
}
await t.test("legacy stopped offer keeps original view and newer malformed tagged card cannot be accepted", async () => {
  const cardFixture = { ...first, from_lat: 54, from_lng: 56, to_lat: 55, to_lng: 57,
    offer_expires_at: new Date(Date.now() + 30_000).toISOString(),
    stops: [{ lat: 54.5, lng: 56.5, text: "Остановка" }] };
  const { view } = await setup(Legacy, undefined, cardFixture);
  const node = overlay(view);
  const card = mount(node.type, node.props);
  await card.settle();
  assert.ok(all(card.tree).some(n => n.type === "RouteTimeline"));
  assert.ok(!all(card.tree).some(n => n.type === "ol"));
  assert.equal(all(card.tree).find(n => n.type === "YandexMap").props.route, true);
  assert.ok(!text(card.tree).includes("Сумма заказа до комиссии"));
  card.unmount();
  globalThis.__sh = view.state;
  view.unmount();
  for (const invalid of [{ ...first, stops: undefined }, { ...first, driver_gross_kop: undefined }]) {
    const malformed = await setup(Screen, undefined, invalid);
    const offered = overlay(malformed.view).props;
    assert.equal(await offered.onAccept(offered.order), "closed");
    assert.deepEqual(paths(malformed.env), [route]);
    malformed.view.unmount();
  }
});

await t.test("non-scoped conflict or malformed success cannot release unknown acceptance", async () => {
  for (const response of [() => { throw new globalThis.__driverV2ApiError(409, "generic"); },
      () => ({ ...adopted, driver_id: owner.userId + 1 })]) {
    const { env, view } = await setup();
    env.acceptAction = response;
    const card = overlay(view).props;
    assert.equal(await card.onAccept(card.order), "closed");
    await view.settle();
    assert.equal(overlay(view).props.pending, true);
    assert.deepEqual(paths(env), [route, accept]);
    view.unmount();
  }
});
t.done();
