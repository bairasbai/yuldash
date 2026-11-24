// Actual stop views and API wrappers; only session and HTTP transport replaced.
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

const revision = "10000000-0000-4000-8000-000000000001";
const renewed = "10000000-0000-4000-8000-000000000002";
const later = "10000000-0000-4000-8000-000000000003";
const A = "20000000-0000-4000-8000-000000000001";
const B = "20000000-0000-4000-8000-000000000002";
const C = "20000000-0000-4000-8000-000000000003";
const D = "20000000-0000-4000-8000-000000000004";
const waitId = "30000000-0000-4000-8000-000000000001";
const root = "/instant/orders/71";
const first = { lat: 1, lng: 2, text: "Аптека", done: false, waypoint_id: A };
const duplicate = { ...first, waypoint_id: B };
const other = { lat: 3, lng: 4, text: "Магазин", done: false, waypoint_id: B };
const base = { id: 71, role: "driver", driver_id: 11, passenger_id: 12, status: "onboard", standing: false };
const ready = (stops = [first, duplicate]) => ({ ...base, stops,
  stop_control: { admission_enabled: true, route_revision: revision, wait_revision: null, active_wait: null },
});
const planned = (order = ready(), waypoint_id = B, enabled = true) => ({ ...order, standing: true,
  stop_control: { ...order.stop_control, admission_enabled: enabled, wait_revision: waitId,
    active_wait: { kind: "planned", waypoint_id, wait_id: waitId } },
});
const reply = order => ({ ok: true, standing: order.standing, order });
const state = () => globalThis.__stopControl;
globalThis.__StopApiError = class extends Error {
  constructor(code, status = 409, message = "synthetic stale response") { super(message); this.status = status; this.code = code; }
};
const client = `
  export const ApiError = globalThis.__StopApiError;
  export const getAccountSession = () => globalThis.__stopControl.owner;
  export const isAccountSession = o => o.userId === getAccountSession().userId && o.generation === getAccountSession().generation;
  export const apiGet = () => { throw new Error("unscoped GET"); };
  export const apiPost = (path, body) => globalThis.__stopControl.send(path, "legacy", body);
  export const apiCommand = async (path, owner, opts) => {
    if (!isAccountSession(owner) || !opts.oneShot || !(await opts.allowAttempt())) throw Error("not owned");
    return globalThis.__stopControl.send(path, opts.method, opts.body === undefined ? undefined : JSON.parse(opts.body));
  };
`;
function build(path, lang) {
  return bundleComponent("src/components/" + path + ".tsx", {
    real: ["../api/instant", "../utils/taxiStopControl"],
    mocks: { "../api/client": client, "./client": client, "../i18n/lang": langMock(lang),
      "./PayMethodPicker": "export const PAY_METHODS_OPEN=[];",
    },
  });
}
const [driver, baDriver, passenger] = await Promise.all([
  build("TaxiDriverTripActions", "ru"), build("TaxiDriverTripActions", "ba"), build("TaxiTripActions", "ru"),
]);
async function show(order, handler, Component = driver) {
  const s = globalThis.__stopControl = { requests: [], owner: {
    userId: order.role === "driver" ? order.driver_id : 12, generation: "session-1",
  }, async send(path, method, body) {
    s.requests.push([path, method, body]);
    return handler(path, method, body);
  }};
  const top = mount(Component, { order, onChanged() {} });
  await top.settle();
  const name = Component === passenger ? "Stops" : "StopControlActions";
  const node = all(top.tree).find(x => x.type?.name === name);
  assert.ok(node, name);
  top.unmount();
  const view = mount(node.type, node.props);
  await view.settle();
  return view;
}
async function press(view, label) {
  const item = button(view.tree, label);
  assert.ok(item && !item.props.disabled, label);
  item.props.onClick();
  await view.settle();
}
const t = checker();
await t.test("preparation preserves selection: duplicate labels post the explicit second occurrence", async () => {
  const original = ready();
  original.stop_control.route_revision = null;
  original.stops = [first, duplicate].map(({ waypoint_id, ...stop }) => stop);
  const page = await show(original, path => path.endsWith("/prepare") ? reply(ready()) : reply(planned()));
  await press(page, "Отметить остановку");
  assert.equal(button(page.tree, "Остановка №"), undefined);
  await press(page, "Загрузить остановки");
  assert.deepEqual(state().requests, [[root + "/waypoints/v1/prepare", "POST", undefined]]);
  const delayed = button(page.tree, "Остановка № 2").props.onClick;
  delayed();
  delayed(); // Same render, before disabled state has reached the browser.
  await page.settle();
  assert.deepEqual(state().requests[1], [root + "/stop/v1", "POST",
    { action: "start_planned", route_revision: revision, wait_revision: null, waypoint_id: B }]);
  assert.equal(state().requests.length, 2);
  assert.match(text(page.tree), /Ожидание на остановке № 2/);
  page.unmount();
});

await t.test("BA unplanned pause without a waypoint, then exact resume after admission is off", async () => {
  const idle = ready();
  const paused = { ...planned(idle, B, false), stop_control: {
    ...planned(idle, B, false).stop_control, active_wait: { kind: "unplanned", waypoint_id: null, wait_id: waitId },
  }};
  const page = await show(idle, (_path, _method, body) => reply(body.action === "resume" ? base : paused), baDriver);
  await press(page, "Туҡталышты билдәләү");
  await press(page, "Пландан тыш туҡтау");
  await press(page, "Киттек");
  assert.deepEqual(state().requests, [[root + "/stop/v1", "POST", { action: "start_unplanned", wait_revision: null }],
    [root + "/stop/v1", "POST", { action: "resume", wait_id: waitId }]]);
  page.unmount();
});

for (const kind of ["missing", "missing generation", "old generation", "fresh generation after GET", "fresh generation in ACK"]) {
  await t.test(`captured ON resume requires verified idle generation: ${kind}`, async () => {
    const initial = planned(ready(), B, true);
    const fresh = ready();
    fresh.stop_control.wait_revision = renewed;
    const idle = ready();
    if (kind === "old generation") idle.stop_control.wait_revision = waitId;
    const ack = kind === "fresh generation in ACK" ? fresh
      : kind === "missing" || kind === "fresh generation after GET" ? base : idle;
    const page = await show(initial, (_path, method) =>
      method === "POST" ? reply(ack) : kind === "fresh generation after GET" ? fresh
        : kind === "old generation" || kind === "missing generation" ? idle : base);
    await press(page, "Поехали");
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [
      [root + "/stop/v1", "POST"], ...(kind === "fresh generation in ACK" ? [] : [[root, "GET"]]),
    ]);
    assert.ok(!all(page.tree).some(x => x.type?.name === "StandingToggle"));
    const verified = kind.startsWith("fresh");
    assert.equal(!!button(page.tree, "Отметить остановку"), verified);
    assert.equal(!!button(page.tree, "Обновить поездку"), !verified);
    page.unmount();
  });
}

await t.test("unknown write only reads the same order; absent capability never falls back", async () => {
  const page = await show(ready(), (_path, method) => {
    if (method === "POST") throw Error("unknown");
    return base; // An older response does not prove the new command never ran.
  });
  await press(page, "Отметить остановку");
  await press(page, "Внеплановая пауза");
  assert.equal(button(page.tree, "Стоим на остановке"), undefined);
  assert.equal(button(page.tree, "Отметить остановку"), undefined);
  await press(page, "Обновить поездку");
  assert.deepEqual(state().requests.map(x => x.slice(0, 2)), [[root + "/stop/v1", "POST"], [root, "GET"], [root, "GET"]]);
  page.unmount();
});

const retryLabel = "Повторить то же действие";
for (const action of ["start_planned", "start_unplanned", "resume"]) {
  await t.test(`driver may deliberately retry one exact ${action} only after two unchanged authorized reads`, async () => {
    const resume = action === "resume";
    const baseline = resume ? planned(ready(), B, false) : ready();
    const result = action === "start_planned" ? planned()
      : resume ? base
      : { ...planned(), stop_control: { ...planned().stop_control,
        active_wait: { kind: "unplanned", wait_id: waitId, waypoint_id: null },
      }};
    let deliveries = 0;
    const page = await show(baseline, (_path, method) => {
      if (method === "GET") return baseline;
      if (state().requests.length === 1) throw Error("connection failed before server delivery");
      ++deliveries;
      return reply(result);
    });
    if (!resume) await press(page, "Отметить остановку");
    await press(page, resume ? "Поехали" : action === "start_planned" ? "Остановка № 2" : "Внеплановая пауза");
    assert.equal(button(page.tree, retryLabel), undefined);
    await press(page, "Обновить поездку");
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)),
      [[root + "/stop/v1", "POST"], [root, "GET"], [root, "GET"]]);
    const click = button(page.tree, retryLabel).props.onClick;
    click();
    click();
    await page.settle();
    assert.equal(deliveries, 1);
    const expected = resume ? { action, wait_id: waitId }
      : action === "start_planned" ? { action, route_revision: revision, wait_revision: null, waypoint_id: B }
      : { action, wait_revision: null };
    assert.deepEqual(state().requests.filter(r => r[1] === "POST"),
      [[root + "/stop/v1", "POST", expected], [root + "/stop/v1", "POST", expected]]);
    page.unmount();
  });
}

await t.test("manual exact retry after first delayed commit observes existing wait, not a second wait", async () => {
  const baseline = ready();
  const active = planned();
  let order = baseline;
  let committed = 0;
  const page = await show(baseline, (_path, method, body) => {
    if (method === "GET") return order;
    if (state().requests.length === 1) throw Error("reply lost while the first server request remains in flight");
    assert.equal(body.wait_revision, null);
    assert.equal(order.stop_control.wait_revision, waitId);
    throw new globalThis.__StopApiError("taxi_wait_stale"); // new request lost exact write guard.
  });
  await press(page, "Отметить остановку");
  await press(page, "Остановка № 2");
  await press(page, "Обновить поездку");
  // The first request wins after the two observations, before the deliberate retry.
  order = active;
  ++committed;
  await press(page, retryLabel);
  assert.equal(committed, 1);
  assert.match(text(page.tree), /Ожидание на остановке № 2/);
  assert.equal(button(page.tree, retryLabel), undefined);
  assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [
    [root + "/stop/v1", "POST"], [root, "GET"], [root, "GET"], [root + "/stop/v1", "POST"], [root, "GET"],
  ]);
  page.unmount();
});

await t.test("unknown driver write cannot replay or fall back after legacy-shaped reads", async () => {
  const prior = ready();
  const legacy = { ...prior, standing: true,
    stop_control: { ...prior.stop_control, active_wait: { kind: "legacy_unknown", wait_id: null, waypoint_id: null } },
  };
  const page = await show(prior, (_path, method) => {
    if (method === "POST") throw Error("no response");
    return legacy;
  });
  await press(page, "Отметить остановку");
  await press(page, "Внеплановая пауза");
  await press(page, "Обновить поездку");
  assert.equal(button(page.tree, retryLabel), undefined);
  assert.ok(!all(page.tree).some(x => x.type?.name === "StandingToggle"));
  assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/stop/v1", "POST"], [root, "GET"], [root, "GET"]]);
  page.unmount();
});

for (const delayedFirstWins of [false, true]) {
  await t.test(`explicit prepare can recover without guessing or starting a pause: delayed first wins=${delayedFirstWins}`, async () => {
    const baseline = ready();
    baseline.stop_control.route_revision = null;
    baseline.stops = [first, duplicate].map(({ waypoint_id, ...stop }) => stop);
    let server = baseline;
    let assigned = 0;
    const page = await show(baseline, (_path, method) => {
      if (method === "GET") return server;
      if (state().requests.length === 1) throw Error("unconfirmed prepare");
      if (server.stop_control.route_revision === null) {
        ++assigned;
        server = ready();
      }
      return reply(server);
    });
    await press(page, "Отметить остановку");
    await press(page, "Загрузить остановки");
    assert.equal(button(page.tree, retryLabel), undefined);
    await press(page, "Обновить поездку");
    if (delayedFirstWins) { ++assigned; server = ready(); }
    await press(page, retryLabel);
    assert.equal(assigned, 1);
    assert.ok(button(page.tree, "Остановка № 2"));
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [
      [root + "/waypoints/v1/prepare", "POST"], [root, "GET"], [root, "GET"], [root + "/waypoints/v1/prepare", "POST"],
    ]);
    page.unmount();
  });
}

await t.test("known 409 reads the current revision and requires a new deliberate selection", async () => {
  const next = ready();
  next.stop_control.route_revision = renewed;
  const page = await show(ready(), (_path, method) => {
    if (method === "POST") throw new globalThis.__StopApiError("taxi_waypoints_stale");
    return next;
  });
  await press(page, "Отметить остановку");
  await press(page, "Остановка № 1");
  assert.equal(button(page.tree, "Остановка № 1"), undefined);
  assert.match(text(page.tree), /Поездка обновлена/);
  assert.deepEqual(state().requests.map(x => x.slice(0, 2)), [[root + "/stop/v1", "POST"], [root, "GET"]]);
  page.unmount();
});

await t.test("old order and genuinely unbound standing keep legacy controls; malformed capability fails closed", async () => {
  let page = await show(base, () => Promise.resolve());
  let nested = all(page.tree).find(x => x.type?.name === "StandingToggle");
  assert.ok(nested);
  let legacy = mount(nested.type, nested.props);
  await legacy.settle();
  await press(legacy, "Стоим на остановке");
  legacy.unmount();
  assert.deepEqual(state().requests, [[root + "/stop", "legacy", undefined]]);
  page.unmount();
  const old = ready();
  old.standing = true;
  old.stop_control.active_wait = { kind: "legacy_unknown", wait_id: null, waypoint_id: null };
  page = await show(old, () => Promise.resolve());
  nested = all(page.tree).find(x => x.type?.name === "StandingToggle");
  assert.ok(nested);
  legacy = mount(nested.type, nested.props);
  await legacy.settle();
  await press(legacy, "Поехали");
  legacy.unmount();
  assert.deepEqual(state().requests, [[root + "/stop", "legacy", undefined]]);
  page.unmount();
  page = await show({ ...base, stop_control: null }, () => assert.fail());
  assert.ok(button(page.tree, "Обновить поездку"));
  assert.equal(button(page.tree, "Стоим на остановке"), undefined);
  assert.deepEqual(state().requests, []);
  page.unmount();
});

await t.test("passenger locks only the bound row and can remove a distinct future stop", async () => {
  const ordered = { ...planned(ready([first, other]), A, false), role: "passenger", passenger_id: null };
  const page = await show(ordered, (path, method, payload) => {
    assert.equal(path, root + "/waypoints");
    assert.equal(method, "POST");
    assert.deepEqual(payload, { waypoints: [{ lat: first.lat, lng: first.lng, text: first.text }] });
    return { applied: true, price: 600, order: { ...ordered, stops: [first] } };
  }, passenger);
  await press(page, "Остановки");
  const rows = ofType(page.tree, "li");
  assert.ok(text(rows[0]).includes("Водитель ждёт здесь") && !button(rows[0], "Убрать"));
  assert.ok(button(rows[1], "Убрать"));
  await press(page, "Убрать");
  assert.equal(state().requests.length, 1);
  page.unmount();
});

await t.test("passenger duplicate ambiguity reloads only and does not use the price in an error", async () => {
  const order = { ...planned(ready(), A), role: "passenger", passenger_id: null };
  const page = await show(order, (_path, method) => {
    if (method === "POST") throw new globalThis.__StopApiError("taxi_active_waypoint_protected");
    return order;
  }, passenger);
  await press(page, "Остановки");
  assert.equal(ofType(page.tree, "li").filter(row => !!button(row, "Убрать")).length, 1);
  await press(page, "Убрать");
  assert.match(text(page.tree), /Не удалось безопасно сохранить/);
  assert.doesNotMatch(text(page.tree), /Готово. Цена/);
  assert.deepEqual(state().requests.map(x => x.slice(0, 2)), [[root + "/waypoints", "POST"], [root, "GET"]]);
  page.unmount();
});

for (const replacing of [false, true]) {
  await t.test(`V3 ${replacing ? "replaces" : "removes"} only the second literal duplicate with both revisions`, async () => {
    const order = { ...planned(ready(), A, false), role: "passenger", passenger_id: null };
    order.stop_control.passenger_edit_v1 = true; // A draining planned wait also advertises V3.
    const label = replacing ? "Заменить" : "Убрать";
    const next = { ...order, stops: replacing ? [first, { ...other, waypoint_id: C }] : [first],
      stop_control: { ...order.stop_control, route_revision: renewed },
    };
    const page = await show(order, (path, method) => {
      assert.equal(path, root + "/waypoints/v1");
      assert.equal(method, "POST");
      return { applied: true, price: 600, order: next };
    }, passenger);
    await press(page, "Остановки");
    const rows = ofType(page.tree, "li");
    assert.ok(!button(rows[0], label));
    assert.ok(button(rows[1], label));
    await press(page, label);
    if (replacing) {
      assert.match(text(page.tree), /Замена остановки № 2/);
      const search = all(page.tree).find(x => x.type?.name === "AddressSearch");
      assert.ok(search);
      search.props.onPick({ lat: 3, lng: 4, text: "Магазин" });
      await page.settle();
    }
    assert.deepEqual(state().requests, [[root + "/waypoints/v1", "POST", {
      route_revision: revision, wait_revision: waitId, waypoints: [
        { kind: "keep", waypoint_id: A },
        ...(replacing ? [{ kind: "new", lat: 3, lng: 4, text: "Магазин" }] : []),
      ],
    }]]);
    assert.match(text(page.tree), /Водитель ждёт здесь/);
    page.unmount();
  });
}

await t.test("V3 unknown must not become a V2 write after same-order but absent capability read", async () => {
  const order = { ...planned(ready(), A), role: "passenger", passenger_id: null };
  order.stop_control.passenger_edit_v1 = true;
  const v2 = { ...order, stop_control: { ...order.stop_control, route_revision: renewed } };
  delete v2.stop_control.passenger_edit_v1;
  const page = await show(order, (_path, method) => {
    if (method === "POST") throw Error("no response");
    return v2;
  }, passenger);
  await press(page, "Остановки");
  await press(page, "Убрать");
  assert.ok(button(page.tree, "Убрать").props.disabled);
  await press(page, "Обновить поездку");
  assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/waypoints/v1", "POST"], [root, "GET"], [root, "GET"]]);
  page.unmount();
});

for (const malformed of ["V2 shape", "same route revision", "wrong retained ID", "missing price", "truthy applied"]) {
  await t.test(`V3 blocks a 200 with ${malformed} instead of reporting success`, async () => {
    const order = { ...planned(ready(), A), role: "passenger", passenger_id: null };
    order.stop_control.passenger_edit_v1 = true;
    const response = { ...order, stops: [first], stop_control: { ...order.stop_control, route_revision: renewed } };
    const payload = { applied: true, price: 600, order: response };
    if (malformed === "V2 shape") delete response.stop_control.passenger_edit_v1;
    if (malformed === "same route revision") response.stop_control.route_revision = revision;
    if (malformed === "wrong retained ID") response.stops = [{ ...first, waypoint_id: B }];
    if (malformed === "missing price") delete payload.price;
    if (malformed === "truthy applied") payload.applied = "true";
    const page = await show(order, (_path, method) => method === "POST" ? payload : order, passenger);
    await press(page, "Остановки");
    await press(page, "Убрать");
    assert.doesNotMatch(text(page.tree), /Готово. Цена/);
    assert.ok(button(page.tree, "Убрать").props.disabled);
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/waypoints/v1", "POST"], [root, "GET"]]);
    page.unmount();
  });
}

const completed = { lat: 5, lng: 6, text: "Парк", done: true, waypoint_id: D };
const checkedV3Trip = () => {
  const order = { ...planned(ready([completed, first, duplicate]), A), role: "passenger", passenger_id: null };
  order.stop_control.passenger_edit_v1 = true;
  return order;
};
for (const mutation of ["active name", "active coordinates", "completed name", "completed moved outside prefix"]) {
  await t.test(`V3 requires the same owner-visible stop identity: ${mutation}`, async () => {
    const order = checkedV3Trip();
    const stops = [{ ...completed }, { ...first }];
    if (mutation === "active name") stops[1].text = "Другой адрес";
    if (mutation === "active coordinates") stops[1].lat = 3;
    if (mutation === "completed name") stops[0].text = "Другой адрес";
    if (mutation === "completed moved outside prefix") stops.reverse();
    const result = { ...order, stops, stop_control: { ...order.stop_control, route_revision: renewed } };
    const page = await show(order, (_path, method) =>
      method === "POST" ? { applied: true, price: 600, order: result } : order, passenger);
    await press(page, "Остановки");
    await press(page, "Убрать");
    assert.doesNotMatch(text(page.tree), /Готово. Цена|Другой адрес/);
    assert.ok(button(page.tree, "Убрать").props.disabled);
    await press(page, "Обновить поездку");
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)),
      [[root + "/waypoints/v1", "POST"], [root, "GET"], [root, "GET"]]);
    page.unmount();
  });
}

await t.test("V3 unchanged completed prefix and active point admit legitimate ancillary updates", async () => {
  const order = { ...checkedV3Trip(), waiting_fee_kop: 0, eta_min: 10 };
  const result = { ...order, waiting_fee_kop: 300, eta_min: 13, stops: [completed, first],
    stop_control: { ...order.stop_control, route_revision: renewed } };
  const page = await show(order, () => ({ applied: true, price: 600, order: result }), passenger);
  await press(page, "Остановки");
  await press(page, "Убрать");
  assert.match(text(page.tree), /Готово. Цена: 600 ₽/);
  assert.match(text(page.tree), /Парк/);
  assert.match(text(page.tree), /Водитель ждёт здесь/);
  assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/waypoints/v1", "POST"]]);
  page.unmount();
});

for (const [code, copy, changed, enabled] of [
  ["taxi_waypoint_edit_cooldown", "Слишком часто. Подожди пару секунд", false, true],
  ["taxi_waypoint_edit_cooldown", "Бик йыш. Бер-ике секунд көт", false, true],
  ["", "Too Many Requests", false, false],
  ["", "Слишком часто. Подожди пару секунд", false, false],
  ["taxi_waypoint_edit_cooldown", "Слишком часто. Подожди пару секунд", true, false],
]) {
  await t.test(`V3 429 ${code || "untagged"}/${changed ? "changed" : "same"}: ${copy}`, async () => {
    const order = checkedV3Trip();
    const acknowledged = { ...order, stops: [completed, first],
      stop_control: { ...order.stop_control, route_revision: renewed } };
    let posts = 0;
    const page = await show(order, (_path, method) => {
      if (method === "GET") return changed ? {
        ...order, stop_control: { ...order.stop_control, route_revision: renewed },
      } : { ...order, eta_min: 19 };
      if (++posts === 1) throw new globalThis.__StopApiError(code, 429, copy);
      return { applied: true, price: 600, order: acknowledged };
    }, passenger);
    await press(page, "Остановки");
    await press(page, "Убрать");
    assert.doesNotMatch(text(page.tree), /Готово. Цена/);
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/waypoints/v1", "POST"], [root, "GET"]]);
    assert.equal(button(page.tree, "Убрать").props.disabled, !enabled);
    if (enabled) {
      await press(page, "Убрать");
      assert.match(text(page.tree), /Готово. Цена: 600 ₽/);
      assert.deepEqual(state().requests.map(r => r.slice(0, 2)),
        [[root + "/waypoints/v1", "POST"], [root, "GET"], [root + "/waypoints/v1", "POST"]]);
    }
    page.unmount();
  });
}

for (const change of ["route", "price"]) {
  await t.test(`a later authoritative ${change} never inherits the old acknowledged fare banner`, async () => {
    const order = checkedV3Trip();
    const acknowledged = { ...order, price_estimate: 600, stops: [completed, first],
      stop_control: { ...order.stop_control, route_revision: renewed } };
    const page = await show(order, () => ({ applied: true, price: 600, order: acknowledged }), passenger);
    await press(page, "Остановки");
    await press(page, "Убрать");
    assert.match(text(page.tree), /Готово. Цена: 600 ₽/);
    page.props.initial = change === "route"
      ? { ...acknowledged, stops: [completed, first, { ...other, waypoint_id: C }],
          stop_control: { ...order.stop_control, route_revision: later } }
      : { ...acknowledged, price_estimate: 700 };
    page.render();
    await page.settle();
    if (change === "route") assert.match(text(page.tree), /Магазин/);
    assert.doesNotMatch(text(page.tree), /Готово. Цена/);
    assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/waypoints/v1", "POST"]]);
    page.unmount();
  });
}

await t.test("a foreign order returned from reread cannot open a new action", async () => {
  const page = await show(ready(), (_path, method) => {
    if (method === "POST") throw new globalThis.__StopApiError("taxi_wait_stale");
    return { ...ready(), id: 99 };
  });
  await press(page, "Отметить остановку");
  await press(page, "Внеплановая пауза");
  assert.ok(button(page.tree, "Обновить поездку"));
  assert.equal(button(page.tree, "Отметить остановку"), undefined);
  assert.deepEqual(state().requests.map(r => r.slice(0, 2)), [[root + "/stop/v1", "POST"], [root, "GET"]]);
  page.unmount();
});

await t.test("a lost response under a changed account never reloads or falls back", async () => {
  let fail;
  const page = await show(ready(), () => new Promise((_, reject) => { fail = reject; }));
  await press(page, "Отметить остановку");
  await press(page, "Внеплановая пауза");
  state().owner = { ...state().owner, generation: "session-2" };
  fail(Error("loss"));
  await page.settle();
  assert.deepEqual(state().requests.map(x => x.slice(0, 2)), [[root + "/stop/v1", "POST"]]);
  page.unmount();
});
t.done();
