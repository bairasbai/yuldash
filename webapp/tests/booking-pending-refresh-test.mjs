import assert from "node:assert/strict";
import { all, bundleComponent, checker, langMock, mount, ofType } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__pendingBooking = { id: 42, calls: [], refreshed: 0, timers: new Map(), listeners: new Set(), nextTimer: 0 };
globalThis.document = {
  visibilityState: "visible",
  addEventListener: (event, fn) => { if (event === "visibilitychange") h.listeners.add(fn); },
  removeEventListener: (event, fn) => { if (event === "visibilitychange") h.listeners.delete(fn); },
};
globalThis.window = {
  setInterval: fn => { const id = ++h.nextTimer; h.timers.set(id, fn); return id; },
  clearInterval: id => h.timers.delete(id),
};
const Screen = await bundleComponent("src/screens/BookingScreen.tsx", {
  real: ["/utils/useVisibleInterval"],
  mocks: {
    "react-router-dom": `export const Navigate="Navigate",useParams=()=>({id:String(globalThis.__pendingBooking.id)}),useNavigate=()=>()=>{};`,
    "/i18n/lang": langMock(),
    "/api/bookings": `export const fetchBookingDetails=(id,signal)=>{const h=globalThis.__pendingBooking;h.calls.push({id,signal});return h.load(id,signal);};export const cancelBooking=()=>globalThis.__pendingBooking.cancel();`,
    "/api/client": `export class ApiError extends Error{}`,
    "/analytics": `export const track=()=>{};`,
    "/booking/OwnBookingsProvider": `const refresh=()=>globalThis.__pendingBooking.refreshed++;export const useRefreshOwnBookings=()=>refresh;`,
    "/utils/bookingLink": `export const bookingLinkPath=()=>null;`,
  },
});
const details = status => ({ booking_id: h.id, ride_id: 7, role: "passenger", status, contact_unlocked: status === "confirmed", from_city: "Уфа", to_city: "Баймак", depart_at: "2030-01-01T10:00:00", seats: 1, price: 0, driver_name: "Ринат", driver_car: "Lada", driver_phone: status === "confirmed" ? "synthetic-phone" : "", pickup: status === "confirmed" ? "Акъяр" : "" });
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
function visibility(value) { document.visibilityState = value; [...h.listeners].forEach(fn => fn()); }
async function open(status = "pending") {
  h.id = 42; h.calls = []; h.refreshed = 0; h.timers.clear(); h.listeners.clear(); document.visibilityState = "visible";
  h.load = async () => details(status);
  const s = mount(Screen); await s.settle(); return s;
}
const tick = () => [...h.timers.values()].forEach(fn => fn());

await t.test("pending quietly becomes confirmed and exposes contacts without page reload", async () => {
  const s = await open();
  assert.equal(h.timers.size, 1);
  const response = deferred(); h.load = () => response.promise;
  tick();
  assert.equal(h.calls.length, 2);
  assert.equal(ofType(s.tree, "LoadingList").length, 0, "Тихое обновление не скрывает готовые детали");
  response.resolve(details("confirmed")); await s.settle();
  assert.ok(all(s.tree).some(n => n.type === "a" && n.props.href === "tel:synthetic-phone"));
  assert.equal(h.timers.size, 0, "После ответа водителя pending-опрос прекращается");
  assert.equal(h.refreshed, 1);
  s.unmount();
});
await t.test("hidden tab aborts a pending request and return refreshes once", async () => {
  const s = await open(); const late = deferred(); h.load = () => late.promise;
  tick(); const request = h.calls.at(-1);
  visibility("hidden"); assert.equal(request.signal.aborted, true); assert.equal(h.timers.size, 0);
  late.resolve(details("confirmed")); await s.settle();
  assert.ok(!all(s.tree).some(n => n.type === "a" && n.props.href === "tel:synthetic-phone"));
  h.load = async () => details("confirmed"); visibility("visible"); await s.settle();
  assert.equal(h.calls.length, 3); assert.equal(h.timers.size, 0);
  s.unmount();
});
await t.test("departure from the screen cancels its request and ignores a late answer", async () => {
  const s = await open(); const late = deferred(); h.load = () => late.promise; tick();
  const request = h.calls.at(-1); s.unmount();
  assert.equal(request.signal.aborted, true); assert.equal(h.timers.size, 0); assert.equal(h.listeners.size, 0);
  late.resolve(details("confirmed")); await s.settle(); assert.equal(h.refreshed, 0);
});
await t.test("a failure keeps pending details and the next visible tick can retry", async () => {
  const s = await open(); h.load = async () => { throw Error("offline"); }; tick(); await s.settle();
  assert.equal(ofType(s.tree, "ErrorState").length, 0); assert.equal(h.timers.size, 1);
  h.load = async () => details("cancelled"); tick(); await s.settle();
  assert.equal(h.timers.size, 0); s.unmount();
});
for (const status of ["confirmed", "onboard", "done", "cancelled"]) {
  await t.test(status + " never starts pending refresh", async () => {
    const s = await open(status); assert.equal(h.timers.size, 0); assert.equal(h.calls.length, 1); s.unmount();
  });
}
t.done();
