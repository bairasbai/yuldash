import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__requestRoute = { generation: "a", sent: [], feed: [], routes: [], done: [], closed: 0 };
const item = { id: 22, passenger_name: "Тест", from_city: "Ургаза", to_city: "Бурибай", seats: 1, prefs: [], responded: false,
  matched_ride_id: 7, matched_ride_from_city: "Сибай", matched_ride_to_city: "Акъяр", matched_ride_depart_at: "2030-01-02T10:00:00Z", matched_pickup_stop: "Ургаза", matched_dropoff_stop: "Бурибай", detour_km: 0 };
const apiMock = `export const fetchRequestsFeed=(signal,onRoute)=>{globalThis.__requestRoute.feed.push({signal,onRoute});return globalThis.__requestRoute.fetch?.()??Promise.resolve(globalThis.__requestRoute.items??[])};
  export const respondToRequest=(...args)=>{globalThis.__requestRoute.sent.push(args);return globalThis.__requestRoute.send?.()??Promise.resolve({id:82})};`;
const clientMock = `export class ApiError extends Error {constructor(status){super('fixture');this.status=status;}} globalThis.__requestRoute.ApiError=ApiError; export const getSessionGeneration=()=>globalThis.__requestRoute.generation;`;
const mocks = lang => ({ "/i18n/lang": langMock(lang), "/api/requests": apiMock, "/api/client": clientMock,
  "react-router-dom": `export const useNavigate=()=> (...a)=>globalThis.__requestRoute.routes.push(a);export const useParams=()=>({id:'22'});`,
  "/useSheetExit": `export const useSheetExit=()=>({leaving:false,portal:n=>n,dialogProps:{},close:()=>globalThis.__requestRoute.closed++});`,
  "/utils/format": `export const formatWhen=v=>v??'',pluralRu=(n,s)=>s,priceLabel=v=>String(v);`,
});
for (const lang of ["ru", "ba"]) {
  const Feed = await bundleComponent("src/screens/RequestsFeedScreen.tsx", { mocks: mocks(lang) });
  const Respond = await bundleComponent("src/screens/RequestsFeedScreen.tsx", { mocks: mocks(lang), exportName: "RespondSheet" });
  const Note = await bundleComponent("src/components/ResponseRouteNote.tsx", { mocks: mocks(lang) });
  await t.test(`${lang}: фильтр запрашивается с сервера; оценка крюка не обещает совпадение маршрута`, async () => {
    h.generation = "a"; h.feed = []; h.fetch = undefined; h.items = [{ ...item, matched_ride_id: null }];
    const s = mount(Feed); await s.settle(); assert.equal(h.feed[0].onRoute, false);
    assert.ok(text(s.tree).includes(lang === "ru" ? "Крюк ≈ 0 км" : "Урау ≈ 0 км"));
    assert.ok(!text(s.tree).includes(lang === "ru" ? "По пути" : "Юл ыңғайында"));
    all(s.tree).find(n => n.type === "input" && n.props.type === "checkbox").props.onChange({ target: { checked: true } });
    h.items = []; await s.settle(); assert.equal(h.feed.at(-1).onRoute, true); assert.equal(h.feed[0].signal.aborted, true);
    assert.ok(text(s.tree).includes(lang === "ru" ? "По твоим маршрутам пока нет заявок" : "Һинең юлдарың буйынса әле заявкалар юҡ"));
    assert.ok(button(s.tree, lang === "ru" ? "Все заявки" : "Бөтә заявкалар")); s.unmount(); assert.equal(h.feed.at(-1).signal.aborted, true);
  });
  await t.test(`${lang}: без выбора рейса POST закрыт; свой рейс и отдельная поездка отправляют разные тела`, async () => {
    for (const choice of [0, 1]) {
      h.generation = "a"; h.sent = []; h.send = undefined; h.closed = 0; h.done = [];
      const s = mount(Respond, { item, onClose() {}, onDone: id => h.done.push(id) }); s.render();
      const cta = () => all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta"));
      assert.equal(cta().props.disabled, true); await cta().props.onClick(); assert.equal(h.sent.length, 0);
      assert.ok(text(s.tree).includes("Сибай")); assert.ok(text(s.tree).includes("Акъяр")); assert.ok(text(s.tree).includes("2030-01-02T10:00:00Z"));
      assert.ok(text(s.tree).includes("Ургаза")); assert.ok(text(s.tree).includes("Бурибай"));
      all(s.tree).filter(n => n.type === "input" && n.props.type === "radio")[choice].props.onChange(); await s.settle();
      await cta().props.onClick(); assert.equal(h.sent.length, 1); assert.equal(h.sent[0][3], choice === 0 ? 7 : undefined);
      assert.deepEqual(h.done, [82]); assert.equal(h.closed, 1); s.unmount();
    }
  });
  await t.test(`${lang}: старый отклик без match остаётся отдельным; два быстрых нажатия дают один POST`, async () => {
    h.sent = []; let resolve; h.send = () => new Promise(r => { resolve = r; });
    const s = mount(Respond, { item: { ...item, matched_ride_id: null }, onClose() {}, onDone() {} }); s.render();
    const submit = all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta")).props.onClick;
    const first = submit(); await submit(); assert.equal(h.sent.length, 1); assert.equal(h.sent[0][3], undefined);
    resolve({ id: 83 }); await first; s.unmount(); h.send = undefined;
  });
  await t.test(`${lang}: уход/смена аккаунта отменяют POST; новый запрос сбрасывает старый выбор`, async () => {
    h.generation = "a"; h.sent = []; h.done = []; let resolve; h.send = () => new Promise(r => { resolve = r; });
    const s = mount(Respond, { item, onClose() {}, onDone: id => h.done.push(id) }); s.render();
    all(s.tree).find(n => n.type === "input" && n.props.type === "radio").props.onChange(); await s.settle();
    const p = all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta")).props.onClick();
    s.unmount(); assert.equal(h.sent[0][4].aborted, true); h.generation = "b"; resolve({ id: 90 }); await p; assert.equal(h.done.length, 0);
    h.send = undefined;
    const fresh = mount(Respond, { item, onClose() {}, onDone() {} }); fresh.render();
    all(fresh.tree).find(n => n.type === "input" && n.props.type === "radio").props.onChange(); await fresh.settle();
    fresh.props = { ...fresh.props, item: { ...item, id: 23 } }; fresh.render(); await fresh.settle();
    assert.ok(all(fresh.tree).filter(n => n.type === "input" && n.props.type === "radio").every(n => !n.props.checked)); fresh.unmount();
  });
  await t.test(`${lang}: устаревший рейс 409 требует обновления; повтор со старой привязкой заблокирован`, async () => {
    h.generation = "a"; h.sent = []; let refreshed = 0;
    h.send = () => Promise.reject(new h.ApiError(409));
    const s = mount(Respond, { item, onClose() {}, onDone() {}, onRefresh() { refreshed++; } }); s.render();
    all(s.tree).find(n => n.type === "input" && n.props.type === "radio").props.onChange(); await s.settle();
    await all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta")).props.onClick(); await s.settle();
    assert.ok(text(s.tree).includes(lang === "ru" ? "Рейс изменился" : "Сәфәр үҙгәргән"));
    assert.equal(all(s.tree).find(n => n.type === "input" && n.props.type === "radio").props.disabled, true);
    button(s.tree, lang === "ru" ? "Обновить заявки" : "Заявкаларҙы яңыртыу").props.onClick(); assert.equal(refreshed, 1);
    s.unmount(); h.send = undefined;
  });
  await t.test(`${lang}: пассажир до приёма видит общий рейс, выезд и свой участок; legacy NULL не превращается в конечную`, () => {
    const response = { ride_id: 7, ride_from_city: "Сибай", ride_to_city: "Акъяр", ride_depart_at: "2030-01-02T10:00:00Z", pickup_stop: "Ургаза", dropoff_stop: "Бурибай" };
    const s = mount(Note, { response }); s.render();
    assert.ok(text(s.tree).includes("Сибай")); assert.ok(text(s.tree).includes("Акъяр")); assert.ok(text(s.tree).includes(response.ride_depart_at));
    const segment = all(s.tree).find(n => n.type === "BookingSegmentNote"); assert.equal(segment.props.pickupStop, "Ургаза"); assert.equal(segment.props.dropoffStop, "Бурибай"); s.unmount();
  });
  await t.test(`${lang}: ответ ленты после ухода не обновляет экран; смена аккаунта не принимает прошлые данные`, async () => {
    h.generation = "a"; h.feed = []; let deliver; h.fetch = () => new Promise(r => { deliver = r; });
    const s = mount(Feed); s.render(); s.unmount(); deliver([item]); await new Promise(setImmediate);
    assert.equal(h.feed[0].signal.aborted, true);
    const f = mount(Feed); f.render(); h.generation = "b"; deliver([item]); await f.settle();
    assert.ok(!text(f.tree).includes("Ургаза")); f.unmount(); h.fetch = undefined;
  });
  await t.test(`${lang}: экран откликов показывает участок до приёма; поздний accept не уводит новый аккаунт в поездку`, async () => {
    const response = { id: 31, driver_id: 8, driver_name: "Тест", status: "offered", price: 500, current_price: 500,
      can_accept: true, can_counter: false, bargain_rounds: 0, bargain_history: "", ride_id: 7, ride_from_city: "Сибай", ride_to_city: "Акъяр", ride_depart_at: item.matched_ride_depart_at,
      pickup_stop: "Ургаза", dropoff_stop: "Бурибай" };
    h.responses = [response]; h.generation = "a"; h.routes = []; let resolve; h.accept = () => new Promise(r => { resolve = r; });
    const Responses = await bundleComponent("src/screens/RequestResponsesScreen.tsx", { mocks: { ...mocks(lang), "/api/requests": `
      export const fetchRequestResponses=(id,signal)=>{globalThis.__requestRoute.responsesSignal=signal;return Promise.resolve(globalThis.__requestRoute.responses)};
      export const fetchMatchRides=()=>Promise.resolve([]),parseBargainHistory=()=>[];
      export const acceptResponse=(id,signal)=>{globalThis.__requestRoute.acceptSignal=signal;return globalThis.__requestRoute.accept()};
      export const counterOffer=()=>{},declineResponse=()=>{};` } });
    const s = mount(Responses); await s.settle();
    assert.deepEqual(all(s.tree).find(n => n.type === "ResponseRouteNote").props.response, response);
    const p = button(s.tree, lang === "ru" ? "Поехать с ним" : "Уның менән барырға").props.onClick();
    s.unmount(); assert.equal(h.acceptSignal.aborted, true); assert.equal(h.responsesSignal.aborted, true);
    h.generation = "b"; resolve({ booking_id: 88 }); await p; assert.equal(h.routes.length, 0);
  });
}

await t.test("API: on_route=true идёт на сервер; ride_id присутствует только при явной привязке, AbortSignal передаётся", async () => {
  h.api = [];
  const opts = { mocks: { "./client": `export const apiGet=(...a)=>{globalThis.__requestRoute.api.push(a);return Promise.resolve([])};export const apiPost=(...a)=>{globalThis.__requestRoute.api.push(a);return Promise.resolve({})};export const apiDelete=()=>{};` } };
  const feed = await bundleComponent("src/api/requests.ts", { ...opts, exportName: "fetchRequestsFeed" });
  const respond = await bundleComponent("src/api/requests.ts", { ...opts, exportName: "respondToRequest" });
  const ac = new AbortController(); await feed(ac.signal, true); assert.equal(h.api[0][0], "/requests/feed?on_route=true"); assert.equal(h.api[0][1].signal, ac.signal);
  await respond(22, 500, "", 7, ac.signal); assert.deepEqual(h.api[1][1], { price: 500, comment: "", ride_id: 7 }); assert.equal(h.api[1][2].signal, ac.signal);
  await respond(22, 500, ""); assert.deepEqual(h.api[2][1], { price: 500, comment: "" }); assert.ok(!("ride_id" in h.api[2][1]));
});
t.done();
