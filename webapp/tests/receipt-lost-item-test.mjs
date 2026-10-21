// Actual receipt component; deferred adapters exercise owner and unknown-ACK boundaries.
// This is a component check, not a browser/TCP/database journey.
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__receiptLostItem = {};
const receipt = (id = 42, role = "passenger") => ({ booking_id: id, ride_id: 7, role,
  from_city: "Сибай", to_city: "Акъяр", depart_at: "2030-01-02T10:00:00Z", seats: 1,
  amount: 450, pay_method: "cash", paid: true, driver_name: "Синтетический водитель", driver_verified: true });
const confirmed = { ok: true, chat_open_until: "2030-01-04T10:00:00Z" };
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function reset() {
  Object.assign(h, { generation: "A", bookingId: 42, role: "passenger", posts: [], routes: [],
    receipt: async id => receipt(id, h.role), lost: async () => confirmed });
}
globalThis.window = { setTimeout, clearTimeout };
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "react-router-dom": `export const useNavigate=()=> (...a)=>globalThis.__receiptLostItem.routes.push(a),useParams=()=>({id:String(globalThis.__receiptLostItem.bookingId)});`,
  "/api/client": `export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}globalThis.__receiptLostItem.ApiError=ApiError;export const getSessionGeneration=()=>globalThis.__receiptLostItem.generation;`,
  "/api/bookings": `export const fetchReceipt=(id,signal)=>globalThis.__receiptLostItem.receipt(id,signal);
    export const reportBookingLostItem=id=>{const h=globalThis.__receiptLostItem;h.posts.push({id,generation:h.generation});return h.lost(id);};`,
  "/api/family": `export const fetchBookingTip=async()=>({already_thanked:false,money:null}),thankBooking=async()=>({});`,
  "/utils/format": `export const formatWhen=v=>v,priceLabel=v=>String(v)+' ₽',payMethodLabel=v=>v;`,
});
const lostButton = (s, lang) => button(s.tree, lang === "ru" ? "Я забыл вещь в машине" : "Машинала әйбер ҡалдырҙым");
const chatButton = (s, lang) => button(s.tree, lang === "ru" ? "Открыть чат поездки" : "Сәфәр чатын асыу");
const alert = s => all(s.tree).find(n => n.props.role === "alert");

for (const lang of ["ru", "ba"]) {
  const Screen = await bundleComponent("src/screens/TripReceiptScreen.tsx", { mocks: mocks(lang) });
  for (const role of ["passenger", "driver"]) await t.test(`${lang}/${role}: receipt reopens its own chat only after server confirmation`, async () => {
    reset(); h.role = role; const held = deferred(); h.lost = () => held.promise;
    const s = mount(Screen); await s.settle();
    assert.ok(text(s.tree).includes(lang === "ru" ? "Забыл вещь?" : "Әйбер оноттоңмо?"));
    const click = lostButton(s, lang)?.props.onClick; assert.equal(typeof click, "function", "receipt has no lost-item action");
    click(); click(); await s.settle();
    assert.deepEqual(h.posts, [{ id: 42, generation: "A" }], "two retained clicks must start one request");
    assert.equal(chatButton(s, lang), undefined, "pending request must not claim the chat is open");
    assert.ok(all(s.tree).some(n => n.type === "button" && n.props.disabled));
    held.resolve(confirmed); await s.settle();
    assert.ok(chatButton(s, lang)); assert.equal(lostButton(s, lang), undefined); assert.equal(alert(s), undefined);
    click(); await s.settle(); assert.equal(h.posts.length, 1, "retained pre-success callback reopened again");
    assert.deepEqual(h.routes, [], "opening a chat needs an explicit click");
    chatButton(s, lang).props.onClick(); assert.deepEqual(h.routes, [["/trip/42/chat"]]); s.unmount();
  });

  await t.test(`${lang}: lost acknowledgement keeps uncertainty and explicit retry confirms the same booking`, async () => {
    reset(); let attempts = 0; h.lost = async () => { if (++attempts === 1) throw Error("simulated response lost after commit"); return confirmed; };
    const s = mount(Screen); await s.settle(); lostButton(s, lang).props.onClick(); await s.settle();
    assert.ok(alert(s)); assert.ok(text(alert(s)).includes(lang === "ru" ? "Не удалось подтвердить" : "раҫлап булманы"));
    assert.equal(chatButton(s, lang), undefined); assert.equal(h.posts.length, 1); assert.deepEqual(h.routes, []);
    lostButton(s, lang).props.onClick(); await s.settle();
    assert.deepEqual(h.posts.map(p => p.id), [42, 42]); assert.ok(chatButton(s, lang)); assert.equal(alert(s), undefined); s.unmount();
  });

  await t.test(`${lang}: known server refusal remains visible without claiming reopening`, async () => {
    reset(); h.lost = async () => { throw new h.ApiError(409, "Синтетический отказ сервера"); };
    const s = mount(Screen); await s.settle(); lostButton(s, lang).props.onClick(); await s.settle();
    assert.equal(text(alert(s)), "Синтетический отказ сервера"); assert.equal(chatButton(s, lang), undefined); assert.ok(lostButton(s, lang)); s.unmount();
  });

  await t.test(`${lang}: malformed success cannot enable the chat action`, async () => {
    for (const result of [{}, { ok: false, chat_open_until: confirmed.chat_open_until }, { ok: true }, { ok: true, chat_open_until: "invalid" }]) {
      reset(); h.lost = async () => result; const s = mount(Screen); await s.settle();
      lostButton(s, lang).props.onClick(); await s.settle();
      assert.ok(alert(s)); assert.equal(chatButton(s, lang), undefined); s.unmount();
    }
  });

  for (const boundary of ["booking", "account", "same-user-login", "unmount"]) for (const outcome of ["success", "error"]) {
    await t.test(`${lang}: held ${outcome} after ${boundary} cannot mark another receipt open or show its error`, async () => {
      reset(); const held = deferred(); h.lost = () => held.promise;
      const s = mount(Screen); await s.settle(); const stale = lostButton(s, lang).props.onClick; stale();
      if (boundary === "booking") h.bookingId = 43;
      else if (boundary !== "unmount") h.generation = boundary === "account" ? "B" : "A-new-login";
      if (boundary === "unmount") s.unmount(); else { s.render(); await s.settle(); }
      stale(); assert.equal(h.posts.length, 1, "retained callback acted for a new owner");
      if (outcome === "success") held.resolve(confirmed); else held.reject(Error("stale private failure"));
      await s.settle(); assert.equal(chatButton(s, lang), undefined); assert.equal(alert(s), undefined);
      if (boundary !== "unmount") assert.ok(lostButton(s, lang)); s.unmount();
    });
  }

  await t.test(`${lang}: retained success chat callback cannot navigate after owner change or unmount`, async () => {
    for (const boundary of ["booking", "account", "same-user-login", "account-no-render", "unmount"]) {
      reset(); const s = mount(Screen); await s.settle(); lostButton(s, lang).props.onClick(); await s.settle();
      const open = chatButton(s, lang).props.onClick;
      if (boundary === "booking") h.bookingId = 43;
      else if (boundary !== "unmount") h.generation = boundary === "same-user-login" ? "A-new-login" : "B";
      if (boundary === "unmount") s.unmount(); else if (boundary !== "account-no-render") { s.render(); await s.settle(); }
      open(); assert.deepEqual(h.routes, []); s.unmount();
    }
  });

  await t.test(`${lang}: receipt refresh invalidates an older pending reopening`, async () => {
    reset(); h.receipt = async id => ({ ...receipt(id), paid: false }); const held = deferred(); h.lost = () => held.promise;
    const s = mount(Screen); await s.settle(); lostButton(s, lang).props.onClick();
    const pay = all(s.tree).find(n => n.type === "PayTripCard"); pay.props.onPaid(); await s.settle();
    held.resolve(confirmed); await s.settle(); assert.equal(chatButton(s, lang), undefined); assert.ok(lostButton(s, lang)); s.unmount();
  });

  await t.test(`${lang}: missing/unfinished receipt never offers lost-item action`, async () => {
    for (const status of [404, 409, 500]) {
      reset(); h.receipt = async () => { throw new h.ApiError(status, "fixture"); };
      const s = mount(Screen); await s.settle(); assert.equal(lostButton(s, lang), undefined); assert.equal(chatButton(s, lang), undefined); assert.equal(h.posts.length, 0); s.unmount();
    }
  });
}
t.done();
