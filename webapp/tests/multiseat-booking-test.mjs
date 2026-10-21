import assert from "node:assert/strict";
import { all, bundleComponent, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__multi = { generation: "a", sent: [], routes: [], own: null };
const ride = { id: 7, driver_id: 8, from_city: "Баймак", to_city: "Уфа", waypoints: "", depart_at: "2030-01-02T10:00:00Z", seats_left: 3, price: 500, driver_name: "Тест", category: "regular" };
for (const lang of ["ru", "ba"]) {
  const Sheet = await bundleComponent("src/components/RideSheet.tsx", { real: ["/utils/pickupStops"], mocks: {
    "/i18n/lang": langMock(lang),
    "/api/client": `export class ApiError extends Error {} export const getSessionGeneration=()=>globalThis.__multi.generation;`,
    "/useSheetExit": `export const useSheetExit=()=>({leaving:false,portal:n=>n,close:()=>{}});`,
    "react-router-dom": `export const useNavigate=()=> (...args)=>globalThis.__multi.routes.push(args);`,
    "/features": `export const useFeatures=()=>({parcels:true}),getFeatures=()=>({parcels:true});`,
    "/AuthProvider": `export const useAuth=()=>({isAuthed:true});`,
    "/OwnBookingsProvider": `export const useOwnBooking=()=>globalThis.__multi.own,useRefreshOwnBookings=()=>()=>{};`,
    "/utils/format": `export const formatWhen=()=>'',priceLabel=v=>String(v);`,
    "/api/bookings": `export const createBooking=(body,signal)=>{globalThis.__multi.sent.push({body,signal});return globalThis.__multi.send?.()??Promise.resolve({id:55});};`,
  } });
  const reset = () => { h.generation = "a"; h.sent = []; h.routes = []; h.own = null; h.send = undefined; };
  const selector = s => all(s.tree).find(n => n.type === "select");
  const cta = s => all(s.tree).find(n => n.type === "button" && n.props.className?.includes("sheet__cta"));
  await t.test(`${lang}: диапазон 1…свободные, сумма и выбранные места в запросе; двойной тап`, async () => {
    reset(); let resolve; h.send = () => new Promise(r => { resolve = r; });
    const s = mount(Sheet, { ride, onClose() {} }); s.render();
    assert.deepEqual(all(selector(s)).filter(n => n.type === "option").map(n => n.props.value), [1, 2, 3]);
    selector(s).props.onChange({ target: { value: "3" } }); await s.settle();
    assert.ok(text(s.tree).includes((lang === "ru" ? "Итого: " : "Бөтәһе: ") + "1500"));
    const pending = cta(s).props.onClick(); await s.settle();
    assert.equal(selector(s).props.disabled, true); assert.equal(cta(s).props.disabled, true);
    await cta(s).props.onClick(); assert.equal(h.sent.length, 1); assert.equal(h.sent[0].body.seats, 3);
    assert.equal(h.sent[0].body.pickup_stop, "Баймак"); assert.equal(h.sent[0].body.dropoff_stop, "Уфа");
    resolve({ id: 55 }); await pending; s.unmount();
  });
  await t.test(`${lang}: потерянный ответ сохраняет число мест для явного повтора`, async () => {
    reset(); h.send = () => Promise.reject(new Error("synthetic lost reply"));
    const s = mount(Sheet, { ride, onClose() {} }); s.render();
    selector(s).props.onChange({ target: { value: "2" } }); await s.settle();
    await cta(s).props.onClick(); await s.settle();
    assert.equal(selector(s).props.value, 2); assert.equal(cta(s).props.disabled, false);
    assert.ok(all(s.tree).some(n => n.props.className === "auth__error"));
    h.send = undefined; await cta(s).props.onClick();
    assert.deepEqual(h.sent[0].body, h.sent[1].body); assert.equal(h.sent[1].body.seats, 2);
    s.unmount();
  });
  await t.test(`${lang}: ноль мест запрещает запрос, известная бронь открывается без нового выбора`, async () => {
    reset(); const s = mount(Sheet, { ride: { ...ride, seats_left: 0 }, onClose() {} }); s.render();
    assert.equal(cta(s).props.disabled, true); await cta(s).props.onClick(); assert.equal(h.sent.length, 0); s.unmount();
    h.own = { id: 90, status: "pending", seats: 2 };
    const own = mount(Sheet, { ride, onClose() {} }); own.render();
    assert.equal(selector(own), undefined); await cta(own).props.onClick(); assert.equal(h.sent.length, 0);
    assert.ok(h.routes[0][0].includes("90")); own.unmount();
  });
  await t.test(`${lang}: смена аккаунта сбрасывает количество мест`, async () => {
    reset(); const s = mount(Sheet, { ride, onClose() {} }); s.render();
    selector(s).props.onChange({ target: { value: "3" } }); await s.settle();
    h.generation = "b"; s.render(); await s.settle(); assert.equal(selector(s).props.value, 1); s.unmount();
  });
}
t.done();
