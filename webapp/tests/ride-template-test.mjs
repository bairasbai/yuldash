import assert from "node:assert/strict";
import { bundleComponent, checker, mount } from "./shallow-harness.mjs";

const t = checker();
const mapDraft = await bundleComponent("src/utils/rideTemplate.ts", { exportName: "returnRideDraft" });
const route = {
  from_city: "Акъяр", to_city: "Сибай", waypoints: "Баймак | Ургаза",
  seats_total: 2, price: 500, stop_prices: [90, 300], category: "regular",
  pets_allowed: true, child_seat: true, women_only: true, smoking: true, baggage: true,
  air_conditioner: true, only_trusted: true, quiet: true, no_minors: true,
  back_max_two: true, front_seat_available: false, dropped: ["depart_at", "pickup", "comment"],
  suggested_date: null,
};

await t.test("сохраняет порядок обратного маршрута, места и накопленные цены", () => {
  const draft = mapDraft(route);
  assert.deepEqual([draft.from, draft.to, draft.waypoints], ["Акъяр", "Сибай", ["Баймак", "Ургаза"]]);
  assert.deepEqual([draft.seats, draft.price, draft.stopPricesOn, draft.stopPriceValues], [2, "500", true, ["90", "300"]]);
  assert.ok(draft.onlyTrusted && draft.amen.women_only && draft.backMaxTwo && !draft.frontSeatAvailable);
  assert.ok(!draft.amen.non_smoking && draft.returnTemplate.smoking);
});

await t.test("заполняет новый черновик без старого времени, комментария и места встречи", () => {
  const draft = mapDraft({ ...route, comment: "старый комментарий", pickup: "старый адрес", depart_at: "2030-01-01" });
  assert.deepEqual([draft.comment, draft.pickup, draft.pickupPointId, draft.when, draft.recurrence],
    ["", "", null, "", "none"]);
  assert.deepEqual(draft.returnTemplate.dropped, route.dropped);
});

await t.test("сохраняет договорную цену и пустые остановки", () => {
  const draft = mapDraft({ ...route, price: 0, stop_prices: [], waypoints: "", smoking: false });
  assert.deepEqual([draft.price, draft.stopPricesOn, draft.stopPriceValues, draft.waypoints], ["0", false, [], []]);
  assert.ok(draft.amen.non_smoking && !draft.returnTemplate.smoking);
});

const fixture = globalThis.__returnRide = {};
globalThis.__returnRideMapper = mapDraft;
const controls = await bundleComponent("src/components/ReturnRideAction.tsx", {
  exportName: "useReturnRideAction",
  mocks: {
    "react-router-dom": `export const useLocation=()=>({pathname:'/driver'});
      export const useNavigate=()=>path=>globalThis.__returnRide.navigation.push(path);`,
    "/api/client": "export const getSessionGeneration=()=>globalThis.__returnRide.generation;",
    "/auth/AuthProvider": "export const useAuth=()=>({user:{id:7,created_at:'fixture'}});",
    "/api/driver": "export const fetchReturnTemplate=id=>globalThis.__returnRide.fetch(id);",
    "/utils/bookingCommandStore": `export const bookingCommandOwner=(user,generation)=>({generation});
      export const isCurrentBookingOwner=owner=>owner.generation===globalThis.__returnRide.generation;
      export const readPublicationSlot=async()=>globalThis.__returnRide.slot;
      export const releaseRidePublication=async(owner,command)=>globalThis.__returnRide.released.push(command.key)>0;`,
    "/utils/formDraft": `export const readDraft=()=>globalThis.__returnRide.draft;
      export const writeDraft=(key,draft)=>{const f=globalThis.__returnRide;if(!f.denyWrite)f.draft=draft;};`,
    "/utils/rideTemplate": `export const RIDE_DRAFT='create-ride';
      export const returnRideDraft=globalThis.__returnRideMapper;`,
  },
});

function ready({ state, denyWrite = false } = {}) {
  Object.assign(fixture, {
    generation: "A", navigation: [], reads: [], released: [], draft: { from: "Старый черновик" }, denyWrite,
    slot: { revision: state ? "key" : null, command: state ? { key: "key", state } : undefined },
    fetch: async (id) => { fixture.reads.push(id); return route; },
  });
  const h = mount(controls);
  h.render();
  return h;
}

await t.test("предыдущая неопределённая публикация сохраняет свой черновик", async () => {
  const h = ready({ state: "unknown" });
  try {
    await h.tree.open(17);
    await h.settle();
    assert.equal(h.tree.failure.kind, "previous");
    assert.deepEqual([fixture.draft.from, fixture.reads, fixture.released, fixture.navigation],
      ["Старый черновик", [], [], []]);
  } finally { h.unmount(); }
});

await t.test("старый запрос от другого входа не трогает черновик", async () => {
  const h = ready();
  try {
    fixture.fetch = async () => { fixture.generation = "B"; return route; };
    await h.tree.open(17);
    assert.equal(fixture.draft.from, "Старый черновик");
    assert.deepEqual(fixture.navigation, []);
  } finally { h.unmount(); }
});

await t.test("два быстрых нажатия дают один шаблон и одно открытие формы", async () => {
  const h = ready({ state: "applied" });
  try {
    const open = h.tree.open;
    await Promise.all([open(17), open(18)]);
    assert.deepEqual(fixture.reads, [17]);
    assert.deepEqual(fixture.released, ["key"]);
    assert.deepEqual(fixture.navigation, ["/create-ride"]);
    assert.deepEqual(fixture.draft, mapDraft(route));
  } finally { h.unmount(); }
});

await t.test("отказ сохранения оставляет видимую ошибку и прежний черновик", async () => {
  const h = ready({ denyWrite: true });
  try {
    await h.tree.open(17);
    await h.settle();
    assert.equal(h.tree.failure.kind, "draft");
    assert.equal(fixture.draft.from, "Старый черновик");
    assert.deepEqual(fixture.navigation, []);
  } finally { h.unmount(); }
});

t.done();
