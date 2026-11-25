import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { IDBFactory, IDBObjectStore } from "fake-indexeddb";
import { fileURLToPath } from "node:url";
import { DriverOfferExpiry } from "../src/utils/driverOfferExpiry.js";
import { classifyDriverAcceptRead } from "./.bundles/driver-accept-recovery.mjs";

const id = 81;
const owner = 17;
const accepted = { id, status: "accepted", role: "driver", driver_id: owner };

const expiry = new DriverOfferExpiry(id);
assert.equal(expiry.beginAccept(id), true);
let deliver;
const delayed = new Promise(resolve => { deliver = resolve; });
const settled = delayed.then(observed => {
  assert.equal(expiry.finishAccept(id, true), false);
  return classifyDriverAcceptRead(id, owner, observed);
});
assert.equal(expiry.expire(id), false);
deliver(accepted);
assert.equal(await settled, "active");
assert.equal(expiry.manualDecline(id), false);
console.log("✓ late owned acceptance opens the trip without a decline");

for (const other of [{ ...accepted, id: id + 1 }, { ...accepted, role: "passenger" },
  { ...accepted, driver_id: owner + 1 }, { ...accepted, status: "offered" }]) {
  assert.equal(classifyDriverAcceptRead(id, owner, other), "unknown");
}
console.log("✓ stale, passenger and unassigned views cannot open the active trip");

assert.equal(classifyDriverAcceptRead(id, owner, undefined, { status: 503, message: "" }), "unknown");
assert.equal(classifyDriverAcceptRead(id, owner, undefined, { status: 403, message: "Forbidden" }), "unknown");
assert.equal(classifyDriverAcceptRead(id, owner, undefined, { status: 403, message: "Заказға рөхсәт юҡ" }), "closed");
assert.equal(classifyDriverAcceptRead(id, owner, { ...accepted, status: "expired" }), "closed");
console.log("✓ only confirmed unavailability releases the next offer");

const screen = readFileSync(new URL("../src/screens/InstantDriverTripScreen.tsx", import.meta.url), "utf8");
assert.match(screen, /method: "POST", body: "\{\}", oneShot: true/);
assert.match(screen, /method: "GET", oneShot: true, signal: attempt.signal/);
assert.match(screen, /if \(!offer \|\| pendingAccept/);
assert.match(screen, /if \(!online \|\| active \|\| accepting \|\| pendingAccept\)/);
assert.match(screen, /!isAccountSession\(owner\)/);
console.log("✓ real screen guards polling and decline and uses a same-owner one-shot status read");

const { all, bundleComponent, mount } = await import("./shallow-harness.mjs");
let now = 0;
const offer = { id, status: "offered", role: "driver", offer_expires_at: new Date(20_000).toISOString() };
const state = globalThis.__acceptFixture = {
  currentOwner: { generation: "A", userId: owner },
  offer,
  post: null,
  reads: 0,
  offers: 0,
  declines: 0,
  writes: 0,
  note: null,
};
const timers = new Map();
let serial = 0;
globalThis.window = {
  setInterval: (fn, ms) => { timers.set(++serial, { fn, ms, interval: true }); return serial; },
  clearInterval: key => timers.delete(key),
  setTimeout: (fn, ms) => { timers.set(++serial, { fn, ms }); return serial; },
  clearTimeout: key => timers.delete(key),
};
const storage = new Map();
globalThis.localStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => {
    if (key === "yuldash.taxi.activeOrder") ++state.writes;
    storage.set(key, value);
  },
  removeItem: key => storage.delete(key),
};
const priorNavigator = Object.getOwnPropertyDescriptor(globalThis, "navigator");
Object.defineProperty(globalThis, "navigator", { configurable: true, value: {} });
const authBundle = await build({
  entryPoints: [fileURLToPath(new URL("../src/utils/sessionRecord.ts", import.meta.url))],
  bundle: true, write: false, format: "esm", platform: "node",
});
const auth = await import("data:text/javascript;base64," + Buffer.from(authBundle.outputFiles[0].text).toString("base64"));
const credentials = generation => ({ generation, access: "fixture access", refresh: "fixture refresh" });
const put = IDBObjectStore.prototype.put;
IDBObjectStore.prototype.put = function (data, key) {
  if (state.rejectIntentWrite && key?.[0] === "driver-accept") throw new DOMException("Fixture write rejected", "QuotaExceededError");
  return put.call(this, data, key);
};
const marker = generation => auth.editDriverAcceptRecord(generation ?? state.currentOwner.generation, value => value);
const savedNow = Date.now;
Date.now = () => now;
const Screen = await bundleComponent("src/screens/InstantDriverTripScreen.tsx", {
  real: ["/driverAcceptRecovery", "/driverAcceptIntent", "/driverOfferDeadline", "/driverOfferExpiry", "/driverOfferMoney"],
  mocks: {
    "i18n/lang": 'const appText=(ru,ba)=>ru; const lang={lang:"ru",appText}; export const useLang=()=>lang;',
    "react-router-dom": "export const useNavigate=()=>()=>{};",
    "api/driverCurrentOrder": `export async function readDriverCurrentOrder(owner, signal) {
      return globalThis.__acceptFixture.lookup(owner,signal);
    }`,
    "api/driver": "export const fetchDriverStatus=async()=>({online:true}); export const setDriverOnline=async()=>{};",
    "api/client": `export const API_BASE="https://fixture.invalid"; const fixture=globalThis.__acceptFixture;
      export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}
      fixture.Error=ApiError;
      export const getAccountSession=()=>fixture.currentOwner;
      export const isAccountSession=owner=>owner===fixture.currentOwner;
      export async function apiCommand(path,owner,opt) {
        fixture.lastOwner=owner;
        if (opt.method==="POST") {
          if (path.endsWith("/decline")) { fixture.declines++; return fixture.decline(); }
          if (path.endsWith("/cancel")) {
            fixture.cancels++;
            fixture.checkCancel(opt);
            return fixture.cancel();
          }
          fixture.posts++; return fixture.post;
        }
        fixture.reads++; return fixture.read(path,opt);
      }`,
    "api/instant": `const s=globalThis.__acceptFixture;
      export const fetchTaxiApplication=async()=>({status:s.application});
      export const fetchPretrip=async()=>null, fetchWorkday=async()=>null, fetchWorkZone=async()=>null;
      export const fetchDemand=async()=>({zones:[]});
      export const fetchDriverOffer=async()=>{s.offers++;return {offer:s.offer};};
      export const declineOrder=async()=>{s.declines++; return s.decline();};
      export const fetchInstantOrder=async()=>null, sendPresence=async()=>null;
      export const arrivedOrder=async()=>null, onboardOrder=async()=>null, doneOrder=async()=>null, cancelInstantOrder=async()=>null;
      export const DECLINE_REASONS=[];`,
  },
});

function fireTimeout(ms) {
  const match = [...timers].find(([, t]) => !t.interval && t.ms === ms);
  assert.ok(match, `timeout ${ms}`);
  timers.delete(match[0]);
  match[1].fn();
}

async function startFixture(read) {
  timers.clear();
  storage.clear();
  globalThis.indexedDB = new IDBFactory();
  await auth.initializeSessionRecord("guest", () => credentials(state.currentOwner.generation));
  now = 0;
  Object.assign(state, {posts: 0, reads: 0, offers: 0, declines: 0, writes: 0, offer, read, decline: async()=>({...offer,status:"expired"}), application: "approved", lookup: async () => ({state:"none"})});
  let fail;
  state.post = new Promise((_, reject) => { fail = reject; });
  return { h: mount(Screen), fail };
}

const offerNode = h => all(h.tree).find(n => n.type?.name === "OfferOverlay");
const tripNode = h => all(h.tree).find(n => n.type?.name === "DriverTrip");

try {
  const activeCase = await startFixture(async () => accepted);
  await activeCase.h.settle();
  const waiting = offerNode(activeCase.h).props.onAccept();
  await activeCase.h.settle();
  now = 21_000;
  activeCase.fail(new state.Error(503, "delayed response"));
  assert.equal(await waiting, "closed");
  await activeCase.h.settle();
  assert.equal(tripNode(activeCase.h).props.order.id, id);
  assert.deepEqual([state.posts, state.reads, state.declines, state.writes], [1, 1, 0, 1]);
  activeCase.h.unmount();
  console.log("✓ mounted driver screen recovers a committed order after a late 503 without repeating Accept");

  for (const mode of ["release", "lost-release", "no-show", "unknown", "changed-session"]) {
    const f = await startFixture(async () => accepted);
    await f.h.settle();
    state.post = Promise.resolve(accepted);
    await offerNode(f.h).props.onAccept();
    await f.h.settle();
    state.cancels = 0;
    const beforeReads = state.reads;
    let assignments = 0;
    const receipt = {...accepted, status: "cancelled", no_show: true, cancel_fee_kop: 2500};
    state.checkCancel = opts => assert.equal(opts.oneShot, true);
    let complete;
    state.cancel = () => mode === "changed-session" ? new Promise(resolve => { complete = resolve; })
      : mode === "release" ? Promise.resolve({ id, released: true })
      : Promise.reject(new state.Error(0, "response lost"));
    state.read = async () => {
      if (mode === "lost-release") throw new state.Error(403, "not assigned");
      if (mode === "no-show") return receipt;
      throw new state.Error(0, "still offline");
    };
    state.lookup = async () => { assignments++; return {state: "none"}; };
    const trip = tripNode(f.h);
    assert.ok(trip);
    const originalOwner = state.currentOwner;
    const click = mode === "no-show" ? trip.props.onNoShow : trip.props.onCancel;
    click();
    if (mode === "changed-session") {
      state.currentOwner = {...originalOwner, generation: "different-login"};
      complete({ id, released: true });
    } else {
      click(); // a second callback before React rerenders must not send another request
    }
    await f.h.settle();
    assert.equal(state.cancels, 1);
    assert.equal(state.reads - beforeReads, ["release", "changed-session"].includes(mode) ? 0 : 1);
    assert.equal(assignments, mode === "lost-release" ? 1 : 0);
    const result = tripNode(f.h);
    if (mode === "release" || mode === "lost-release") assert.equal(result, undefined);
    else if (mode === "no-show") assert.deepEqual(result.props.order, receipt);
    else {
      assert.equal(result.props.order.id, id);
      if (mode === "unknown") assert.match(result.props.note, /статус поездки пока неизвестен/i);
    }
    f.h.unmount();
    state.currentOwner = originalOwner;
    console.log("✓ driver cancellation does not replay the write: " + mode);
  }

  for (const reconnection of ["none", "replacement", "same"]) {
    const f = await startFixture(async () => accepted);
    await f.h.settle();
    state.post = Promise.resolve(accepted);
    await offerNode(f.h).props.onAccept();
    await f.h.settle();
    state.cancels = 0;
    state.checkCancel = opts => assert.equal(opts.oneShot, true);
    state.cancel = async () => { throw new state.Error(0, "reply lost"); };
    state.read = async () => { throw new state.Error(0, "first read lost"); };
    const beforeReads = state.reads;
    tripNode(f.h).props.onCancel();
    await f.h.settle();
    const waitingTrip = tripNode(f.h);
    assert.equal(waitingTrip.props.busy, true);
    waitingTrip.props.onCancel();
    await f.h.settle();
    assert.equal(state.cancels, 1, "unknown status blocks a second cancellation");

    const replacement = {...accepted, id: id + 1};
    let assignmentReads = 0;
    state.read = async () => {
      if (reconnection === "same") return accepted;
      throw new state.Error(403, "not assigned");
    };
    state.lookup = async () => {
      assignmentReads++;
      return reconnection === "replacement" ? {state: "active", order: replacement} : {state: "none"};
    };
    for (const tick of [...timers.values()].filter(t => t.interval && t.ms === 3000)) tick.fn();
    await f.h.settle();
    assert.deepEqual([state.cancels, state.reads - beforeReads, assignmentReads],
      [1, 2, reconnection === "same" ? 0 : 1]);
    if (reconnection === "same") {
      assert.equal(tripNode(f.h).props.busy, false);
      assert.equal(tripNode(f.h).props.order.id, id);
    } else {
      assert.equal(tripNode(f.h), undefined);
      if (reconnection === "replacement") {
        const button = all(f.h.tree).find(n => n.type === "button" && JSON.stringify(n.props.children).includes("Вернуться в поездку"));
        assert.ok(button);
        button.props.onClick();
        await f.h.settle();
        assert.equal(tripNode(f.h).props.order.id, replacement.id);
      }
    }
    f.h.unmount();
    console.log("✓ driver cancellation reconnect restores current assignment without a second POST: " + reconnection);
  }

  const offlineCase = await startFixture(async () => accepted);
  await offlineCase.h.settle();
  const pending = offerNode(offlineCase.h).props.onAccept();
  await offlineCase.h.settle();
  const waitScreen = all(offlineCase.h.tree).find(n => n.type?.name === "DriverWaiting");
  assert.ok(waitScreen);
  waitScreen.props.onGoOffline();
  await offlineCase.h.settle();
  assert.equal(offerNode(offlineCase.h), undefined);
  const offerReads = state.offers;
  offlineCase.fail(new state.Error(503, "delayed response"));
  assert.equal(await pending, "closed");
  await offlineCase.h.settle();
  assert.equal(tripNode(offlineCase.h).props.order.id, id);
  assert.equal(state.offers, offerReads);
  assert.deepEqual([state.posts, state.reads, state.declines, state.writes], [1, 1, 0, 1]);
  offlineCase.h.unmount();
  console.log("✓ leaving the line does not discard a committed acceptance");

  for (const message of ["У тебя уже есть активная поездка — заверши её сначала",
    "Һинең актив сәфәрең бар — башта уны тамамла"]) {
    const conflictCase = await startFixture(async () => { throw Error("unexpected reconciliation"); });
    await conflictCase.h.settle();
    const waiting = offerNode(conflictCase.h).props.onAccept();
    await conflictCase.h.settle();
    state.offer = null;
    conflictCase.fail(new state.Error(409, message));
    assert.equal(await waiting, "closed");
    await conflictCase.h.settle();
    const screen = all(conflictCase.h.tree).find(n => n.type?.name === "DriverWaiting");
    assert.equal(screen.props.acceptNote, message);
    assert.equal(tripNode(conflictCase.h), undefined);
    assert.deepEqual([state.posts, state.reads, state.declines, state.writes], [1, 0, 0, 0]);
    conflictCase.h.unmount();
  }
  console.log("✓ another active trip preserves the localized server explanation without replay");

  const closedCase = await startFixture(async () => {
    if (state.reads === 1) throw new state.Error(503, "temporarily unavailable");
    state.offer = { ...offer, id: id + 1, offer_expires_at: new Date(40_000).toISOString() };
    throw new state.Error(403, "Нет доступа к заказу");
  });
  await closedCase.h.settle();
  const next = offerNode(closedCase.h).props.onAccept();
  await closedCase.h.settle();
  now = 22_000;
  closedCase.fail(new TypeError("network response lost"));
  assert.equal(await next, "closed");
  await closedCase.h.settle();
  assert.equal(offerNode(closedCase.h).props.accepting, true);
  assert.equal(offerNode(closedCase.h).props.note, "Проверяем, принят ли заказ");
  assert.equal(state.offers, 1);
  await offerNode(closedCase.h).props.onSkip();
  fireTimeout(4_000);
  await closedCase.h.settle();
  assert.equal(offerNode(closedCase.h).props.order.id, id + 1);
  assert.deepEqual([state.posts, state.reads, state.declines, state.writes], [1, 2, 0, 0]);
  closedCase.h.unmount();
  console.log("✓ mounted screen holds an unknown result, then resumes normal polling on confirmed unavailability");
  const restart = await startFixture(async () => { throw Error("unexpected reconciliation"); });
  state.application = "pending";
  state.lookup = async () => ({state: "active", order: accepted});
  await restart.h.settle();
  const resume = all(restart.h.tree).find(n =>
    n.type === "button" && n.props.children === "Вернуться в поездку");
  assert.ok(resume, "already assigned ride can be opened even before approval or going online");
  assert.equal(tripNode(restart.h), undefined);
  resume.props.onClick();
  await restart.h.settle();
  assert.equal(tripNode(restart.h).props.order.id, id);
  assert.deepEqual([state.posts, state.reads, state.offers, state.declines], [0, 0, 0, 0]);
  restart.h.unmount();
  console.log("✓ mounted cold boot exposes return to owned ride without a new acceptance or going online");

  const unavailable = await startFixture(async () => { throw Error("unexpected reconciliation"); });
  state.application = "pending";
  state.lookup = async () => ({state: "unavailable"});
  await unavailable.h.settle();
  const retry = all(unavailable.h.tree).find(n =>
    n.type === "button" && n.props.children === "Повторить");
  assert.ok(retry, "lookup error must remain retryable, not look like absence");
  state.lookup = async () => ({state: "active", order: accepted});
  retry.props.onClick();
  await unavailable.h.settle();
  const suspendedResume = all(unavailable.h.tree).find(n =>
    n.type === "button" && n.props.children === "Вернуться в поездку");
  assert.ok(suspendedResume);
  const original = state.currentOwner;
  state.currentOwner = { ...original, generation: "new login" };
  suspendedResume.props.onClick();
  await unavailable.h.settle();
  assert.equal(tripNode(unavailable.h), undefined);
  assert.deepEqual([state.posts, state.declines], [0, 0]);
  state.currentOwner = original;
  unavailable.h.unmount();
  console.log("✓ failed lookup can be retried; a retained action cannot open a trip after re-login");
  const denied = await startFixture(async () => { throw Error("nothing to reconcile"); });
  await denied.h.settle();
  state.rejectIntentWrite = true;
  assert.equal(await offerNode(denied.h).props.onAccept(), "retry");
  assert.deepEqual([state.posts, state.reads, state.declines], [0, 0, 0]);
  state.rejectIntentWrite = false;
  denied.h.unmount();
  console.log("✓ an unsaved intent never sends an untrackable Accept");

  let committed = false;
  let assignments = 0;
  const interrupted = await startFixture(async () => committed ? accepted : offer);
  await interrupted.h.settle();
  const post = offerNode(interrupted.h).props.onAccept();
  await interrupted.h.settle();
  assert.equal(state.posts, 1);
  const saved = await marker();
  assert.deepEqual(Object.keys(saved).sort(), ["api", "closed", "pending", "user"]);
  assert.deepEqual(Object.keys(saved.pending).sort(), ["action", "attempt", "id", "until"]);
  assert.equal(saved.pending.id, id);
  interrupted.h.unmount();
  state.lookup = async () => { ++assignments; return {state: "none"}; };

  const firstRefresh = mount(Screen);
  await firstRefresh.settle();
  assert.equal(tripNode(firstRefresh), undefined);
  assert.equal(state.reads, 1);
  assert.equal(state.posts, 1);
  assert.ok(await marker());
  firstRefresh.unmount();

  const secondRefresh = mount(Screen);
  await secondRefresh.settle();
  assert.equal(state.reads, 2);
  assert.equal(assignments, 0); // pending same-order recovery takes precedence over a null assignment snapshot
  committed = true;
  fireTimeout(4_000);
  await secondRefresh.settle();
  assert.equal(tripNode(secondRefresh).props.order.id, id);
  assert.deepEqual([state.posts, state.reads, state.declines], [1, 3, 0]);
  assert.deepEqual((await marker())?.closed, [{ id, until: 20_000 }]);
  interrupted.fail(new state.Error(503, "late response"));
  assert.equal(await post, "closed");
  secondRefresh.unmount();
  console.log("✓ two fresh mounts reconcile delayed commit from one saved Accept without replay or Decline");

  const switched = await startFixture(async () => { throw Error("old order cannot be queried"); });
  await switched.h.settle();
  const oldPost = offerNode(switched.h).props.onAccept();
  await switched.h.settle();
  switched.h.unmount();
  const oldOwner = state.currentOwner;
  state.currentOwner = { ...oldOwner, generation: "new login" };
  await auth.replaceSessionRecord(oldOwner.generation, credentials(state.currentOwner.generation), () => {});
  const newLogin = mount(Screen);
  await newLogin.settle();
  assert.equal(tripNode(newLogin), undefined);
  assert.equal(state.reads, 0);
  const nextPost = offerNode(newLogin).props.onAccept();
  await newLogin.settle();
  const newMarker = await marker();
  assert.ok(newMarker);
  switched.fail(new state.Error(503, "old late response"));
  assert.equal(await oldPost, "closed");
  assert.deepEqual(await marker(), newMarker);
  newLogin.unmount();
  assert.equal(state.posts, 2);
  assert.equal(state.declines, 0);
  state.currentOwner = oldOwner;
  // The mock shares a pending Promise between its two calls; never retest that edge as a real transport.
  assert.equal(await nextPost, "closed");
  console.log("✓ same-user re-login ignores the old intent and a stale callback cannot clear the new one");
  const twoTabs = await startFixture(async () => offer);
  await twoTabs.h.settle();
  const otherTab = mount(Screen);
  await otherTab.settle();
  const firstClick = offerNode(twoTabs.h).props.onAccept();
  const secondClick = offerNode(otherTab).props.onAccept();
  globalThis.__sh = twoTabs.h.state;
  await twoTabs.h.settle();
  globalThis.__sh = otherTab.state;
  await otherTab.settle();
  assert.equal(await secondClick, "closed");
  assert.deepEqual([state.posts, state.declines], [1, 0]);
  otherTab.unmount();
  twoTabs.h.unmount();
  twoTabs.fail(new state.Error(503, "late original response"));
  assert.equal(await firstClick, "closed");
  console.log("✓ concurrent same-offer clicks reserve one IndexedDB slot even without Web Locks");

  const skipFirst = await startFixture(async () => ({...offer, status:"expired"}));
  await skipFirst.h.settle();
  const waitingTab = mount(Screen);
  await waitingTab.settle();
  const stale = offerNode(waitingTab).props;
  state.decline = async () => ({id, released: true});
  const skipping = offerNode(skipFirst.h).props.onSkip();
  const trying = stale.onAccept();
  globalThis.__sh = skipFirst.h.state;
  await skipFirst.h.settle();
  globalThis.__sh = waitingTab.state;
  await waitingTab.settle();
  assert.deepEqual([state.declines, state.posts], [1, 0], "one confirmed decision, not two contradictory requests");
  await skipping;
  assert.equal(await trying, "closed");
  await stale.onSkip();
  assert.equal(await stale.onAccept(), "closed");
  assert.deepEqual([state.declines, state.posts], [1, 0], "a stale tab cannot post again after confirmation");
  waitingTab.unmount();
  skipFirst.h.unmount();
  console.log("✓ concurrent Skip wins its atomic claim and stale post-confirmation clicks send nothing");

  const takeFirst = await startFixture(async () => accepted);
  await takeFirst.h.settle();
  const skipTab = mount(Screen);
  await skipTab.settle();
  const late = offerNode(skipTab).props;
  state.post = Promise.resolve(accepted);
  const taking = offerNode(takeFirst.h).props.onAccept();
  const secondSkip = late.onSkip();
  globalThis.__sh = takeFirst.h.state;
  await takeFirst.h.settle();
  globalThis.__sh = skipTab.state;
  await skipTab.settle();
  await secondSkip;
  assert.equal(await taking, "accepted");
  await late.onSkip();
  assert.equal(await late.onAccept(), "closed");
  assert.deepEqual([state.posts, state.declines], [1, 0]);
  skipTab.unmount();
  takeFirst.h.unmount();
  console.log("✓ Accept first produces one command and late Skip cannot change the confirmed order");

  const released = await startFixture(async () => { throw Error("unexpected read"); });
  await released.h.settle();
  state.decline = async () => ({id, released: true});
  await offerNode(released.h).props.onSkip();
  await released.h.settle();
  assert.deepEqual([state.declines, state.posts, state.reads], [1, 0, 0]);
  assert.equal(offerNode(released.h), undefined);
  assert.equal((await marker())?.pending, undefined);
  released.h.unmount();
  console.log("✓ a release acknowledgement closes the offer without a follow-up GET");

  const stillOwns = await startFixture(async () => accepted);
  await stillOwns.h.settle();
  state.decline = async () => ({id, released: false});
  await offerNode(stillOwns.h).props.onSkip();
  await stillOwns.h.settle();
  assert.deepEqual([state.declines, state.posts, state.reads], [1, 0, 1]);
  assert.equal(state.writes, 1, "only the authenticated read opens the accepted order");
  stillOwns.h.unmount();
  console.log("✓ a non-release acknowledgement reconciles without repeating Skip");

  const lostSkip = await startFixture(async () => ({...offer, status:"expired"}));
  await lostSkip.h.settle();
  state.decline = async () => { throw new state.Error(503, "response lost"); };
  await offerNode(lostSkip.h).props.onSkip();
  await lostSkip.h.settle();
  assert.deepEqual([state.declines, state.posts, state.reads], [1, 0, 1]);
  assert.equal((await marker())?.pending, undefined);
  assert.deepEqual((await marker())?.closed, [{ id, until: 20_000 }]);
  lostSkip.h.unmount();
  console.log("✓ a lost Skip response is resolved through GET and never repeated");

  const expireWhileQueued = await startFixture(async () => { throw Error("nothing to reconcile"); });
  await expireWhileQueued.h.settle();
  const queuedClick = offerNode(expireWhileQueued.h).props.onAccept();
  now = 21_000;
  assert.equal(await queuedClick, "closed");
  assert.deepEqual([state.posts, state.declines], [0, 0]);
  assert.equal(await marker(), undefined);
  expireWhileQueued.h.unmount();
  console.log("✓ expiry before the claim commits sends neither Accept nor automatic Decline");

  const accountWhileQueued = await startFixture(async () => { throw Error("nothing to reconcile"); });
  await accountWhileQueued.h.settle();
  const oldLogin = state.currentOwner;
  // Leave this tab's cached owner stale while the durable login is replaced.
  const switchLogin = auth.replaceSessionRecord(oldLogin.generation, credentials("different durable login"), () => {});
  const delayedClick = offerNode(accountWhileQueued.h).props.onAccept();
  await switchLogin;
  assert.equal(await delayedClick, "retry");
  assert.deepEqual([state.posts, state.declines], [0, 0]);
  await assert.rejects(marker(), /Driver session changed|aborted/i);
  assert.equal(await marker("different durable login"), undefined);
  state.currentOwner = oldLogin;
  accountWhileQueued.h.unmount();
  console.log("✓ an older tab cannot claim or send on replaced durable login");

  for (const recover of ["manual", "accept", "expiry"]) {
    const scenario = await startFixture(async () => ({...offer, status:"expired"}));
    await scenario.h.settle();
    const savedCard = offerNode(scenario.h);
    const actualOverlay = mount(savedCard.type, savedCard.props);
    await actualOverlay.settle();
    const button = css => all(actualOverlay.tree).find(n => n.type === "button" && n.props.className === css);
    const skipButton = () => button("btn-soft toffer__btn");
    state.rejectIntentWrite = true;
    skipButton().props.onClick();
    await actualOverlay.settle();
    await marker();
    assert.deepEqual([state.declines, state.posts], [0, 0]);
    state.rejectIntentWrite = false;
    if (recover === "manual") {
      skipButton().props.onClick();
      await actualOverlay.settle();
      await marker();
      assert.deepEqual([state.declines, state.posts], [1, 0]);
    } else if (recover === "accept") {
      state.post = Promise.resolve(accepted);
      button("btn-primary toffer__btn").props.onClick();
      await actualOverlay.settle();
      await marker();
      assert.deepEqual([state.declines, state.posts], [0, 1]);
    } else {
      now = 21_000;
      [...timers.values()].find(t => t.interval && t.ms === 1000).fn();
      await actualOverlay.settle();
      globalThis.__sh = scenario.h.state;
      await scenario.h.settle();
      assert.equal(offerNode(scenario.h), undefined, "failed local Skip cannot trap an expired card");
      assert.deepEqual([state.declines, state.posts], [0, 0]);
    }
    actualOverlay.unmount();
    scenario.h.unmount();
  }
  console.log("✓ actual Skip recovers from a definite no-send and the local timer still dismisses");

  const unsupported = await startFixture(async () => { throw Error("no pending request"); });
  await unsupported.h.settle();
  state.rejectIntentWrite = true;
  const card = offerNode(unsupported.h);
  const overlay = mount(card.type, card.props);
  await overlay.settle();
  const acceptButton = all(overlay.tree).find(n =>
    n.type === "button" && n.props.className === "btn-primary toffer__btn");
  assert.ok(acceptButton);
  acceptButton.props.onClick();
  await overlay.settle();
  assert.deepEqual([state.posts, state.declines], [0, 0]);
  assert.equal(await marker(), undefined);
  now = 21_000;
  const tick = [...timers.values()].find(t => t.interval && t.ms === 1000);
  assert.ok(tick);
  tick.fn();
  await overlay.settle();
  overlay.unmount();
  globalThis.__sh = unsupported.h.state;
  await unsupported.h.settle();
  assert.equal(offerNode(unsupported.h), undefined);
  assert.deepEqual([state.posts, state.declines], [0, 0]);
  state.rejectIntentWrite = false;
  unsupported.h.unmount();
  console.log("✓ mounted offer button and timer do not send Accept or Decline without safe coordination");
} finally {
  IDBObjectStore.prototype.put = put;
  Date.now = savedNow;
  if (priorNavigator) Object.defineProperty(globalThis, "navigator", priorNavigator);
  else delete globalThis.navigator;
}
