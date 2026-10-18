import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const bundled = await build({ entryPoints: [fileURLToPath(new URL("../src/utils/pickupStops.ts", import.meta.url))], bundle: true, write: false, format: "esm", platform: "node" });
const rules = await import("data:text/javascript;base64," + Buffer.from(bundled.outputFiles[0].text).toString("base64"));
const ride = { id: 7, driver_id: 8, from_city: "Баймак", to_city: "Уфа", waypoints: "Акъяр | Сибай", depart_at: "2030-01-02T10:00:00Z", seats_left: 3, price: 500, driver_name: "Тест", driver_rating: 5, category: "regular" };
await t.test("Участок: без подсказки обе точки не выбраны; прямой рейс имеет известные начало и конец", () => {
  assert.deepEqual(rules.initialSegment(ride), { pickup: null, dropoff: null });
  assert.deepEqual(rules.initialSegment({ ...ride, waypoints: "" }), { pickup: "Баймак", dropoff: "Уфа" });
  assert.equal(rules.segmentReady(ride, "Сибай", "Акъяр"), false);
  assert.equal(rules.segmentReady(ride, "Акъяр", "Акъяр"), false);
  assert.equal(rules.segmentReady(ride, "Акъяр", null), false);
  assert.equal(rules.segmentReady(ride, "Акъяр", "Сибай"), true);
  assert.equal(rules.keepDropoffAfterPickup(ride, "Сибай", "Акъяр"), null);
  assert.equal(rules.keepDropoffAfterPickup(ride, "Баймак", "Сибай"), "Сибай");
});
await t.test("Подсказки сервера сохраняют обе точки через карточку/перезагрузку; чужой рейс и обратный порядок не проходят", () => {
  const matched = { ...ride, matched_stop: "Акъяр", matched_dropoff_stop: "Сибай" };
  const restored = rules.preserveRideMatch(ride, rules.rideMatchState(matched));
  assert.deepEqual(rules.initialSegment(restored), { pickup: "Акъяр", dropoff: "Сибай" });
  assert.deepEqual(rules.initialSegment(rules.preserveRideMatch({ ...ride, id: 9 }, rules.rideMatchState(matched))), { pickup: null, dropoff: null });
  assert.equal(rules.initialSegment({ ...matched, matched_stop: "Сибай", matched_dropoff_stop: "Акъяр" }).dropoff, null);
  assert.deepEqual(rules.initialSegment({ ...ride, matched_from_start: true, matched_to_end: true }), { pickup: "Баймак", dropoff: "Уфа" });
});

const h = globalThis.__segment = { generation: "a", sent: [], routes: [], done: [], closed: 0, feedCalls: [] };
const clientMock = `export class ApiError extends Error { constructor(status,message=''){super(message);this.status=status;} } export const getSessionGeneration=()=>globalThis.__segment.generation;`;
const sheetMock = `export const useSheetExit=()=>({leaving:false,portal:n=>n,dialogProps:{},close:()=>globalThis.__segment.closed++});`;
const mockFor = lang => ({
  "/i18n/lang": langMock(lang), "/api/client": clientMock,
  "/useSheetExit": sheetMock,
  "react-router-dom": `export const Link='a';export const useNavigate=()=> (...a)=>globalThis.__segment.routes.push(a);`,
  "/features": `export const useFeatures=()=>({parcels:true}),getFeatures=()=>({parcels:true});`,
  "/AuthProvider": `export const useAuth=()=>({isAuthed:true});`,
  "/OwnBookingsProvider": `export const useOwnBooking=()=>globalThis.__segment.ownBooking,useRefreshOwnBookings=()=>()=>{};`,
  "/utils/format": `export const pluralRu=(n,s)=>s,dayMonthShort=()=>'',hhmm=()=>'',formatWhen=()=>'',priceLabel=v=>String(v);`,
  "/api/bookings": `export const createBooking=(body,signal)=>{globalThis.__segment.sent.push({body,signal});return globalThis.__segment.send?.()??Promise.resolve({id:55});};`,
});
for (const lang of ["ru", "ba"]) {
  const Sheet = await bundleComponent("src/components/RideSheet.tsx", { real: ["/utils/pickupStops"], mocks: mockFor(lang) });
  const Choice = await bundleComponent("src/components/PickupStopChoice.tsx", { exportName: "DropoffStopChoice", real: ["/utils/pickupStops"], mocks: { "/i18n/lang": langMock(lang) } });
  const Note = await bundleComponent("src/components/PickupStopChoice.tsx", { exportName: "BookingSegmentNote", real: ["/utils/pickupStops"], mocks: { "/i18n/lang": langMock(lang) } });
  await t.test(`${lang}: высадка предлагает только последующие остановки и конечную; legacy NULL честный`, () => {
    let s = mount(Choice, { ride, pickup: "Акъяр", picked: null, onPick() {} }); s.render();
    assert.deepEqual(all(s.tree).filter(n => n.type === "input").map(n => n.props.value), ["Сибай", "Уфа"]);
    s.unmount(); s = mount(Note, { pickupStop: "Акъяр", dropoffStop: null, from: ride.from_city, to: ride.to_city }); s.render();
    assert.ok(text(s.tree).includes(lang === "ru" ? "Место высадки не указано — уточни в чате" : "Төшөү урыны күрһәтелмәгән — чатта асыҡла"));
    assert.ok(!text(s.tree).includes("Уфа")); s.unmount();
  });
  await t.test(`${lang}: одна посадка не отправляет бронь; обе точки уходят; смена посадки сбрасывает недопустимую высадку`, async () => {
    h.generation = "a"; h.sent = []; h.ownBooking = null; h.send = undefined;
    const s = mount(Sheet, { ride, onClose() {} }); s.render();
    const cta = () => all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta"));
    const leaf = name => all(s.tree).find(n => n.type === name);
    leaf("PickupStopChoice").props.onPick("Акъяр"); await s.settle();
    assert.equal(cta().props.disabled, true); await cta().props.onClick(); assert.equal(h.sent.length, 0);
    leaf("DropoffStopChoice").props.onPick("Сибай"); await s.settle(); assert.equal(cta().props.disabled, false);
    leaf("PickupStopChoice").props.onPick("Сибай"); await s.settle(); assert.equal(leaf("DropoffStopChoice").props.picked, null);
    await cta().props.onClick(); assert.equal(h.sent.length, 0);
    leaf("DropoffStopChoice").props.onPick("Уфа"); await s.settle(); await cta().props.onClick();
    assert.equal(h.sent.length, 1); assert.equal(h.sent[0].body.pickup_stop, "Сибай"); assert.equal(h.sent[0].body.dropoff_stop, "Уфа");
    s.unmount();
  });
  await t.test(`${lang}: прямой рейс отправляет названия обеих точек; уход/смена аккаунта не открывает чужую бронь`, async () => {
    h.generation = "a"; h.sent = []; h.routes = []; let resolve;
    h.send = () => new Promise(r => { resolve = r; });
    const s = mount(Sheet, { ride: { ...ride, waypoints: "" }, onClose() {} }); s.render();
    const cta = all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta"));
    const pending = cta.props.onClick(); assert.equal(h.sent[0].body.pickup_stop, "Баймак"); assert.equal(h.sent[0].body.dropoff_stop, "Уфа");
    s.unmount(); assert.equal(h.sent[0].signal.aborted, true); h.generation = "b"; resolve({ id: 90 }); await pending;
    assert.equal(h.routes.length, 0); h.send = undefined;
  });
  await t.test(`${lang}: личная карточка передаёт свою высадку; публичная не получает чужие точки`, async () => {
    const Card = await bundleComponent("src/components/RideCard.tsx", { real: ["/utils/pickupStops"], mocks: mockFor(lang) });
    h.ownBooking = { id: 41, status: "pending", pickup_stop: "Акъяр", dropoff_stop: "Сибай", ride_has_stops: true };
    let s = mount(Card, { ride, index: 0 }); s.render();
    assert.equal(all(s.tree).find(n => n.type === "BookingSegmentNote").props.dropoffStop, "Сибай"); s.unmount();
    h.ownBooking = null; s = mount(Card, { ride: { ...ride, pickup_stop: "private", dropoff_stop: "private" }, index: 0 }); s.render();
    assert.ok(!all(s.tree).some(n => n.type === "BookingSegmentNote")); assert.ok(!text(s.tree).includes("private")); s.unmount();
  });
}
t.done();
