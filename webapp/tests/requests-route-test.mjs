import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__requestRoute = { generation: "a", sent: [], feed: [], routes: [], done: [], closed: 0 };
const item = { id: 22, passenger_name: "Тест", from_city: "Ургаза", to_city: "Бурибай", seats: 1, prefs: [], responded: false,
  matched_ride_id: 7, matched_ride_from_city: "Сибай", matched_ride_to_city: "Акъяр", matched_ride_depart_at: "2030-01-02T10:00:00Z", matched_pickup_stop: "Ургаза", matched_dropoff_stop: "Бурибай", detour_km: 0 };
const apiMock = `export const fetchRequestsFeed=(signal,onRoute)=>{globalThis.__requestRoute.feed.push({signal,onRoute});return globalThis.__requestRoute.fetch?.()??Promise.resolve(globalThis.__requestRoute.items??[])};
  export const respondToRequest=(...args)=>{globalThis.__requestRoute.sent.push(args);return globalThis.__requestRoute.send?.()??Promise.resolve({id:82})};`;
const clientMock = `export class ApiError extends Error {constructor(status,message='fixture'){super(message);this.status=status;}} globalThis.__requestRoute.ApiError=ApiError; export const getSessionGeneration=()=>globalThis.__requestRoute.generation;`;
const mocks = lang => ({ "/i18n/lang": langMock(lang), "/api/requests": apiMock, "/api/client": clientMock,
  "react-router-dom": `export const useNavigate=()=> (...a)=>globalThis.__requestRoute.routes.push(a);export const useParams=()=>({id:'22'});`,
  "/useSheetExit": `export const useSheetExit=()=>({leaving:false,portal:n=>n,dialogProps:{},close:()=>globalThis.__requestRoute.closed++});`,
  "/utils/format": `export const formatWhen=v=>v??'',pluralRu=(n,s)=>s,priceLabel=v=>String(v);`,
});
for (const lang of ["ru", "ba"]) {
  const Feed = await bundleComponent("src/screens/RequestsFeedScreen.tsx", { mocks: mocks(lang) });
  const Respond = await bundleComponent("src/screens/RequestsFeedScreen.tsx", { mocks: mocks(lang), exportName: "RespondSheet" });
  const Note = await bundleComponent("src/components/ResponseRouteNote.tsx", { mocks: mocks(lang) });
  const createClient = `export class ApiError extends Error {};
    export const getSessionGeneration=()=>globalThis.__requestRoute.generation;
    export const apiPost=async(path,body)=>{globalThis.__requestRoute.created.push({path,body});return{id:91}};
    export const apiGet=async()=>[],apiDelete=async()=>{};`;
  const CreateRequest = await bundleComponent("src/screens/CreateRequestScreen.tsx", {
    real: ["/api/requests", "/components/StopSuggestField"],
    mocks: { ...mocks(lang), "/api/client": createClient, "./client": createClient,
      "/api/geo": "export const searchSettlements=async()=>[];" },
  });
  const towns = [
    { id: 501, name_ru: "Давлетово", name_ba: "Дәүләт", district: "Абзелиловский р-н", kind: "village", region: "РБ", lat: null, lng: null },
    { id: 502, name_ru: "Давлетово", name_ba: null, district: "Баймакский р-н", kind: "village", region: "РБ", lat: null, lng: null },
  ];
  const cityFields = tree => all(tree).filter(n => n.type === "CityField");
  const townName = town => lang === "ba" ? town.name_ba || town.name_ru : town.name_ru;
  // Execute the form callbacks in CityField.pick's real order: bare onChange, then full onPick.
  async function pickTown(form, index, town) {
    const field = cityFields(form.tree)[index];
    field.props.onChange(townName(town));
    assert.equal(typeof field.props.onPick, "function", "the form must preserve the selected settlement identity");
    field.props.onPick(town);
    await form.settle();
  }
  for (const reversed of [false, true]) await t.test(`${lang}: заявка сохраняет район обоих Давлетово, направление ${reversed ? "обратное" : "прямое"}`, async () => {
    h.created = [];
    const from = towns[reversed ? 1 : 0], to = towns[reversed ? 0 : 1];
    const form = mount(CreateRequest); form.render();
    try {
      await pickTown(form, 0, from);
      await pickTown(form, 1, to);
      const expectedFrom = `${townName(from)} (${from.district})`;
      const expectedTo = `${townName(to)} (${to.district})`;
      assert.equal(cityFields(form.tree)[0].props.value, expectedFrom);
      assert.equal(cityFields(form.tree)[1].props.value, expectedTo);
      const submit = button(form.tree, lang === "ru" ? "Найти водителя" : "Йөрөтөүсе табырға");
      assert.equal(submit.props.disabled, false);
      await submit.props.onClick(); await form.settle();
      assert.equal(h.created.length, 1);
      assert.equal(h.created[0].path, "/requests"); // actual createRequest -> captured transport
      assert.equal(h.created[0].body.from_city, expectedFrom);
      assert.equal(h.created[0].body.to_city, expectedTo);
      assert.ok(text(form.tree).includes(lang === "ru" ? "Готово! Ищем водителя" : "Әҙер! Йөрөтөүсе эҙләйбеҙ"));
    } finally { form.unmount(); }
  });
  await t.test(`${lang}: город и райцентр не получают суффикс села; отсутствие района не создаёт пустые скобки`, async () => {
    const city = { ...towns[0], id: 503, kind: "city", name_ru: "Сибай", name_ba: "Сибай" };
    const center = { ...towns[1], id: 504, kind: "district_center", name_ru: "Акъяр", name_ba: "Аҡъяр" };
    const noDistrict = { ...towns[1], id: 505, district: null };
    for (const [from, to] of [[city, center], [noDistrict, city]]) {
      h.created = [];
      const form = mount(CreateRequest); form.render();
      try {
        await pickTown(form, 0, from); await pickTown(form, 1, to);
        await button(form.tree, lang === "ru" ? "Найти водителя" : "Йөрөтөүсе табырға").props.onClick();
        assert.equal(h.created[0].body.from_city, townName(from));
        assert.equal(h.created[0].body.to_city, townName(to));
      } finally { form.unmount(); }
    }
  });
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
  await t.test(`${lang}: «Как повезёшь пассажира?» и «По моим маршрутам» — цвета бренда и подсвеченная выбранная строка`, async () => {
    h.generation = "a"; h.items = [{ ...item, matched_ride_id: null }]; h.feed = []; h.fetch = undefined;
    const f = mount(Feed); await f.settle();
    const rowOf = (tree, input) => all(tree).find(n => n.type === "label" && all(n).includes(input));
    let box = all(f.tree).find(n => n.type === "input" && n.props.type === "checkbox");
    assert.equal(box.props.className, "checkbox"); assert.ok(!rowOf(f.tree, box).props.className.includes("list-row--on"));
    box.props.onChange({ target: { checked: true } }); h.items = []; await f.settle();
    box = all(f.tree).find(n => n.type === "input" && n.props.type === "checkbox");
    assert.ok(rowOf(f.tree, box).props.className.includes("list-row--on"), "включённая галочка подсвечивает строку"); f.unmount();
    const s = mount(Respond, { item, onClose() {}, onDone() {} }); s.render();
    const radios = () => all(s.tree).filter(n => n.type === "input" && n.props.type === "radio");
    assert.ok(radios().every(r => r.props.className === "checkbox" && !rowOf(s.tree, r).props.className.includes("list-row--on")));
    radios()[1].props.onChange(); await s.settle();
    assert.deepEqual(radios().map(r => rowOf(s.tree, r).props.className.includes("list-row--on")), [false, true]);
    s.unmount();
  });
  // Любой 409 раньше подменялся «Рейс изменился…» и навсегда запирал «Мой рейс». 409 бывает и про закрытую заявку,
  // и про паузу пассажира, и про «отклик уже ушёл с другим рейсом» — человеку нужно слово сервера.
  const own409 = lang === "ru" ? "Рейс изменился" : "Сәфәр үҙгәргән";
  const closed409 = lang === "ru" ? "Заявка уже закрыта или её время прошло" : "Заявка ябылған йәки уның ваҡыты үткән инде";
  const rideRadio = tree => all(tree).find(n => n.type === "input" && n.props.type === "radio");
  const sheetCta = tree => all(tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta"));
  async function respondWith(error, choice = 0) {
    h.generation = "a"; h.sent = []; let refreshed = 0;
    h.send = () => Promise.reject(error);
    const s = mount(Respond, { item, onClose() {}, onDone() {}, onRefresh() { refreshed++; } }); s.render();
    all(s.tree).filter(n => n.type === "input" && n.props.type === "radio")[choice].props.onChange(); await s.settle();
    await sheetCta(s.tree).props.onClick(); await s.settle();
    return { s, refreshed: () => refreshed, done() { s.unmount(); h.send = undefined; } };
  }
  await t.test(`${lang}: отклик, 409 — показано слово сервера, а не «рейс изменился»; «Мой рейс» не заперт, есть «Обновить заявки»`, async () => {
    const r = await respondWith(new h.ApiError(409, closed409));
    try {
      assert.ok(text(r.s.tree).includes(closed409), "слова сервера должны дойти до человека");
      assert.ok(!text(r.s.tree).includes(own409), "наша догадка не должна подменять сервер");
      assert.equal(rideRadio(r.s.tree).props.disabled, undefined, "выбор рейса не запирается навсегда");
      assert.equal(sheetCta(r.s.tree).props.disabled, true, "после отказа выбрать рейс надо осознанно заново");
      button(r.s.tree, lang === "ru" ? "Обновить заявки" : "Заявкаларҙы яңыртыу").props.onClick(); assert.equal(r.refreshed(), 1);
      rideRadio(r.s.tree).props.onChange(); await r.s.settle();
      assert.equal(sheetCta(r.s.tree).props.disabled, false, "после нового выбора отправить можно");
    } finally { r.done(); }
  });
  await t.test(`${lang}: отклик, 409 без слов сервера — наша фраза; другой отказ (429) не запирает и не просит обновить`, async () => {
    let r = await respondWith(new h.ApiError(409, ""));
    try { assert.ok(text(r.s.tree).includes(own409)); assert.ok(button(r.s.tree, lang === "ru" ? "Обновить заявки" : "Заявкаларҙы яңыртыу")); } finally { r.done(); }
    const slow = lang === "ru" ? "Слишком часто. Подожди немного." : "Артыҡ йыш. Бер аҙ көт.";
    r = await respondWith(new h.ApiError(429, slow));
    try {
      assert.ok(text(r.s.tree).includes(slow)); assert.ok(!text(r.s.tree).includes(own409));
      assert.equal(button(r.s.tree, lang === "ru" ? "Обновить заявки" : "Заявкаларҙы яңыртыу"), undefined);
      assert.equal(rideRadio(r.s.tree).props.checked, true, "рейс остаётся выбранным");
    } finally { r.done(); }
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
  const responsesApi = `
    export const fetchRequestResponses=()=>{globalThis.__requestRoute.responseLoads=(globalThis.__requestRoute.responseLoads??0)+1;return Promise.resolve(globalThis.__requestRoute.responses)};
    export const fetchMatchRides=()=>Promise.resolve([]),parseBargainHistory=raw=>globalThis.__requestRoute.parseHistory(raw);
    export const acceptResponse=()=>{globalThis.__requestRoute.responseActions.push('accept');return globalThis.__requestRoute.acceptResult?.()??Promise.resolve({booking_id:88})};
    export const counterOffer=()=>{globalThis.__requestRoute.responseActions.push('counter');return globalThis.__requestRoute.counter()};
    export const declineResponse=()=>{globalThis.__requestRoute.responseActions.push('decline');return Promise.resolve({ok:true})};`;
  const Responses = await bundleComponent("src/screens/RequestResponsesScreen.tsx", { mocks: { ...mocks(lang), "/api/requests": responsesApi } });
  h.parseHistory = await bundleComponent("src/api/requests.ts", { exportName: "parseBargainHistory",
    mocks: { "./client": "export const apiGet=()=>{},apiPost=()=>{},apiDelete=()=>{};" } });
  const Trail = await bundleComponent("src/components/BargainTrail.tsx", { mocks: { "/i18n/lang": langMock(lang) } });
  const offer = { id: 32, driver_id: 8, driver_name: "Тест", status: "offered", price: 700, current_price: 600,
    can_accept: true, can_counter: true, bargain_rounds: 1, bargain_history: "d:700,p:600" };
  const labels = {
    accepted: lang === "ru" ? "Принят" : "Ҡабул ителгән",
    declined: lang === "ru" ? "Торг закрыт: по цене не сошлись" : "Һатыулашыу ябыҡ: хаҡ буйынса килешмәнек",
    withdrawn: lang === "ru" ? "Отклик отозван" : "Яуап кире алынды",
    expired: lang === "ru" ? "Заявка закрылась — человек уже уехал" : "Заявка ябылған — кеше киткән инде",
  };
  const assertNoResponseActions = tree => {
    for (const label of ["Поехать с ним", "Уның менән барырға", "Своя цена", "Үҙ хаҡым", "Отказаться", "Баш тартыу", "Предложить", "Тәҡдим итеү"])
      assert.equal(button(tree, label), undefined, `closed response must not offer: ${label}`);
    assert.equal(all(tree).some(n => n.type === "input"), false, "closed response must not keep the counter form");
    assert.equal(all(tree).some(n => String(n.props.className ?? "").includes("bargain-turn--mine")), false);
  };
  for (const status of ["declined", "withdrawn", "expired", "accepted"]) await t.test(`${lang}: закрытый отклик ${status} показывает итог и не предлагает принять или торговаться`, async () => {
    // withdrawn is normally excluded by GET; a cached/late item must still fail closed.
    for (const staleCapabilities of [false, true]) {
      h.generation = "a"; h.responseActions = [];
      h.responses = [{ ...offer, status, can_accept: staleCapabilities, can_counter: staleCapabilities }];
      const s = mount(Responses); await s.settle();
      try {
        assert.ok(text(s.tree).includes(labels[status]), `missing terminal result for ${status}`);
        assertNoResponseActions(s.tree);
        assert.deepEqual(h.responseActions, []);
      } finally { s.unmount(); }
    }
  });
  await t.test(`${lang}: история торга остаётся после закрытия, подсказка оставшихся ходов видна только для offered`, async () => {
    for (const status of ["offered", "declined", "withdrawn", "expired", "accepted"]) {
      h.generation = "a"; h.responseActions = []; h.responses = [{ ...offer, status }];
      const s = mount(Responses); await s.settle();
      const trailProps = all(s.tree).find(n => n.type === "BargainTrail").props;
      s.unmount();
      assert.equal(trailProps.rounds, status === "offered" ? 1 : undefined, `round hint for ${status}`);
      assert.deepEqual(trailProps.history, [{ by: "driver", price: 700 }, { by: "passenger", price: 600 }]);
      const trail = mount(Trail, trailProps); trail.render();
      try {
        assert.ok(text(trail.tree).includes(lang === "ru" ? "Как шёл торг" : "Һатыулашыу нисек барҙы"));
        assert.ok(text(trail.tree).includes("700")); assert.ok(text(trail.tree).includes("600"));
        const hints = all(trail.tree).filter(n => String(n.props.className ?? "").includes("bargain__left"));
        assert.equal(hints.length, status === "offered" ? 1 : 0, `live bargain hint for ${status}`);
        if (status === "offered") assert.ok(text(hints[0]).includes(lang === "ru"
          ? "Осталось ходов: 5. Торгуемся по очереди." : "Ҡалған сират: 5. Сиратлап һатыулашабыҙ."));
      } finally { trail.unmount(); }
    }
  });
  // «Принять» отклик: 409 бывает «водитель отозвал», «торг закрыт», «ход другой стороны» — раньше все три
  // читались как «Рейс изменился…». Слова сервера доходят; список при 409 перечитывается.
  async function acceptWith(error) {
    h.generation = "a"; h.responseActions = []; h.responseLoads = 0; h.routes = []; h.responses = [offer];
    h.acceptResult = () => Promise.reject(error);
    const s = mount(Responses); await s.settle();
    await button(s.tree, lang === "ru" ? "Поехать с ним" : "Уның менән барырға").props.onClick(); await s.settle();
    return { s, done() { s.unmount(); h.acceptResult = undefined; } };
  }
  const alertText = tree => text(all(tree).find(n => n.props?.role === "alert"));
  await t.test(`${lang}: «Принять», 409 — слова сервера, не «рейс изменился»; список перечитан; в поездку не уходим`, async () => {
    const withdrawn = lang === "ru" ? "Водитель отозвал это предложение" : "Водитель был тәҡдимен кире алды";
    const r = await acceptWith(new h.ApiError(409, withdrawn));
    try {
      assert.equal(alertText(r.s.tree), withdrawn);
      assert.ok(!text(r.s.tree).includes(lang === "ru" ? "Рейс изменился" : "Сәфәр үҙгәргән"));
      assert.equal(h.responseLoads, 2, "после 409 отклики перечитываются");
      assert.deepEqual(h.routes, []);
    } finally { r.done(); }
  });
  await t.test(`${lang}: «Принять», 409 без слов сервера — наша фраза; другая ошибка — её слова; сеть — общая фраза`, async () => {
    let r = await acceptWith(new h.ApiError(409, ""));
    try { assert.equal(alertText(r.s.tree), lang === "ru" ? "Рейс изменился или больше не подходит. Обнови отклики и выбери снова." : "Сәфәр үҙгәргән йәки бүтән тап килмәй. Яуаптарҙы яңыртып ҡабат һайла."); assert.equal(h.responseLoads, 2); } finally { r.done(); }
    const moved = lang === "ru" ? "Сейчас ход другой стороны — свою же цену принять нельзя" : "Хәҙер икенсе яҡ сираты — үҙ хаҡыңды ҡабул итеп булмай";
    r = await acceptWith(new h.ApiError(409, moved));
    try { assert.equal(alertText(r.s.tree), moved); } finally { r.done(); }
    r = await acceptWith(new h.ApiError(500, lang === "ru" ? "Ошибка на сервере. Попробуй чуть позже." : "Серверҙа хата. Аҙыраҡтан ҡабатла."));
    try { assert.ok(alertText(r.s.tree).length > 0); assert.equal(h.responseLoads, 1, "перечитывание — только на 409"); } finally { r.done(); }
    r = await acceptWith(new TypeError("Failed to fetch"));
    try { assert.equal(alertText(r.s.tree), lang === "ru" ? "Не получилось принять. Попробуй снова." : "Ҡабул итеп булманы. Ҡабат ҡара."); } finally { r.done(); }
  });
  await t.test(`${lang}: живой отклик сохраняет принятие и свою цену; отказ при обновлении закрывает уже открытую форму`, async () => {
    h.generation = "a"; h.responseActions = []; h.responses = [offer];
    h.counter = () => {
      h.responses = [{ ...offer, status: "declined", can_accept: false, can_counter: false }];
      return Promise.reject(new h.ApiError(409));
    };
    const s = mount(Responses); await s.settle();
    try {
      assert.equal(button(s.tree, lang === "ru" ? "Поехать с ним" : "Уның менән барырға").props.disabled, false);
      assert.ok(button(s.tree, lang === "ru" ? "Отказаться" : "Баш тартыу"));
      button(s.tree, lang === "ru" ? "Своя цена" : "Үҙ хаҡым").props.onClick(); await s.settle();
      assert.equal(all(s.tree).find(n => n.type === "input").props.value, "600");
      await button(s.tree, lang === "ru" ? "Предложить" : "Тәҡдим итеү").props.onClick(); await s.settle();
      assert.ok(text(s.tree).includes(labels.declined));
      assertNoResponseActions(s.tree);
      assert.deepEqual(h.responseActions, ["counter"]);
    } finally { s.unmount(); h.counter = undefined; }
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
