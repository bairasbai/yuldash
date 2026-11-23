import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const owner = { userId: 12, generation: "synthetic" };
const route = { id: 44, role: "passenger", passenger_id: owner.userId, from_text: "А",
  to_text: "Старый адрес", status: "cancelled", contact_then_cancel: true, cancel_by: "system", cancel_reason: "route_resume_timeout" };
const t = checker();
const find = (view, name) => all(view.tree).find(n => typeof n.type === "function" && n.type.name === name);

for (const lang of ["ru", "ba"]) {
  const mocks = {
    "../api/client": "export class ApiError extends Error{}; export const getAccountSession=()=>globalThis.__timeout.owner; export const isAccountSession=o=>o===globalThis.__timeout.owner;",
    "../api/instant": `const state=()=>globalThis.__timeout;
      export const fetchMyOrders=async limit=>{state().calls.push(["GET mine",limit]);return state().rows};
      export const fetchInstantOrder=async id=>{state().calls.push(["GET order",id]);return state().fresh};
      export const fetchTaxiAvailability=async()=>({enabled:true});
      export const createInstantOrder=()=>{throw Error("must not create");};
      export const isUnlocked=()=>false;
      export const ACTIVE_PASSENGER_STATUSES=["created","searching","offered","accepted","arriving","onboard"];
      export const instantEstimate=()=>{throw Error("must not estimate");};
      export const createScheduledOrder=()=>{throw Error("must not create scheduled");};
      export const fetchNearbyDrivers=()=>{throw Error("must not fetch nearby");};
      export const fetchAlternatives=()=>{throw Error("must not fetch alternatives");};
      export const addAlternative=()=>{throw Error("must not add alternative");};
      export const cancelInstantOrder=()=>{throw Error("must not cancel");};
      export const cancelQueuedOrder=()=>{throw Error("must not cancel queue");};`,
    "../api/taxiConsent": "export const localTaxiConsentContext=()=>null; export const localTaxiConsentGeneration=()=>null;",
    "../utils/scheduledTaxiContext": "export const requestedScheduledTaxiId=()=>null; export const requestedTaxiOrderMatches=(o,id)=>o.id===id&&o.role==='passenger';",
    "../i18n/lang": langMock(lang),
    "../features": "export const useFeatures=()=>({taxi:true,ready:true});",
    "react-router-dom": 'export const useLocation=()=>({state:null}); export const useNavigate=()=>p=>globalThis.__timeout.navigated.push(p);',
  };
  // Stub only boundaries while running actual React screen bodies and reason/presentation logic.
  const options = { mocks, real: ["../utils/taxiRouteResumeTimeout", "../utils/format", "../utils/serverTime"] };
  const copy = lang === "ru" ? "После изменения маршрута не нашли водителя" : "Маршрут үҙгәргәндән һуң йөрөтөүсе табылманы";
  const historyCopy = lang === "ru" ? "Не нашли водителя после изменения маршрута" : copy;
  const newLabel = lang === "ru" ? "Новый заказ" : "Яңы заказ";
  const Screen = await bundleComponent("src/screens/InstantOrderScreen.tsx", options);
  const History = await bundleComponent("src/screens/MyTaxiTripsScreen.tsx", options);

  await t.test(lang + ": active-order timeout is system copy, and New Order only opens a blank draft", async () => {
    const state = globalThis.__timeout = { owner, calls: [], navigated: [], rows: [{ ...route, status: "accepted" }], fresh: route };
    const intervals = new Map();
    globalThis.window = { setInterval(f) { const i = {}; intervals.set(i, f); return i; }, clearInterval(i) { intervals.delete(i); } };
    Object.defineProperty(globalThis, "navigator", { configurable: true, value: {} });
    const view = mount(Screen);
    await view.settle();
    assert.equal(find(view, "TrackingView").props.order.status, "accepted");
    for (const f of intervals.values()) f();
    await view.settle();
    const tracking = find(view, "TrackingView");
    assert.equal(tracking.props.order, route);
    const card = mount(tracking.type, tracking.props);
    await card.settle();
    assert.ok(text(card.tree).includes(copy));
    const title = lang === "ru" ? "Не нашли водителя" : "Йөрөтөүсе табылманы";
    assert.equal(all(card.tree).find(n => n.type === "SubHeader").props.title, title);
    assert.equal(text(all(card.tree).find(n => n.type === "h2")), title);
    assert.doesNotMatch(text(card.tree), /Ты отменил|Һин заказды кире алдың|Договорились ехать|Барырға һөйләштегеҙме/);
    const action = button(card.tree, newLabel);
    assert.ok(action);
    card.unmount();
    const other = mount(tracking.type, { ...tracking.props, order: { ...route, cancel_by: "passenger", cancel_fee_kop: 4900 } });
    await other.settle();
    assert.match(text(other.tree), lang === "ru" ? /Отмена была платной.*49/ : /Кире алыу түләүле булды.*49/);
    assert.match(text(other.tree), /Договорились ехать|Барырға һөйләштегеҙме/);
    other.unmount();
    globalThis.__sh = view.state;
    action.props.onClick();
    await view.settle();
    assert.equal(find(view, "TrackingView"), undefined);
    assert.equal(find(view, "ComposeView").props.to, null);
    assert.deepEqual(state.calls, [["GET mine", 5], ["GET order", 44]]);
    assert.deepEqual(state.navigated, []);
    view.unmount();
  });

  await t.test(lang + ": history distinguishes timeout from lookalikes and doesn't open a receipt for it", async () => {
    const state = globalThis.__timeout = { owner, calls: [], navigated: [], rows: [route,
      { ...route, id: 45, cancel_by: "passenger" }, { ...route, id: 46, cancel_reason: "stuck_timeout" },
      { ...route, id: 47, status: "expired" }, { ...route, id: 48, status: "done", passenger_price_kop: 4900 }] };
    const view = mount(History);
    await view.settle();
    const cards = all(view.tree).filter(n => n.props.className === "money-row");
    assert.equal(cards.length, 5);
    assert.ok(text(cards[0]).includes(historyCopy));
    assert.deepEqual(cards.map(n => n.type), ["div", "div", "div", "div", "button"]);
    for (const unqualified of cards.slice(1)) assert.ok(!text(unqualified).includes(historyCopy));
    const cancelled = lang === "ru" ? "Отменена" : "Кире алынған";
    assert.ok(text(cards[1]).includes(cancelled));
    assert.ok(text(cards[2]).includes(cancelled));
    assert.deepEqual(state.navigated, []);
    cards[4].props.onClick();
    assert.deepEqual(state.navigated, ["/taxi-receipt/48"]);
    view.unmount();
  });
}
t.done();
