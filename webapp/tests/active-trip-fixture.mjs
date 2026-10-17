// ================================================================
//  Настоящий ActiveTripScreen на стенде shallow-harness.mjs: сервер, язык, роутер подменены,
//  api/bookings и логика синхронизации — настоящие (запросы уходят в подставной apiGet/apiPost).
//  Это не набор: его импортируют наборы паритета (отмена с причиной, «Завершить?», точка водителя).
// ================================================================
import { bundleComponent, langMock, mount } from "./shallow-harness.mjs";

const CLIENT = `export class ApiError extends Error{constructor(s,m){super(m);this.status=s;}}
export const getSessionGeneration=()=>globalThis.__at.generation;
export const getToken=()=>"tok";
export const apiGet=async(p)=>globalThis.__at.get(p);
export const apiPost=async(p,b)=>globalThis.__at.post(p,b);`;

/**
 * Открыть экран поездки 42.
 * @param {{lang?:string, role?:string, status?:string, phase?:string, cancel?:(body:any)=>any}} o
 */
export async function openActiveTrip({ lang = "ru", role = "passenger", status = "confirmed", phase = "", cancel } = {}) {
  const Screen = await bundleComponent("src/screens/ActiveTripScreen.tsx", {
    real: ["/api/bookings", "/utils/tripLocationShare", "/utils/tripDetailsSync.js"],
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>globalThis.__at.nav.push(p);export const useParams=()=>({id:"42"});`,
      "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1}});`,
      "/i18n/lang": langMock(lang),
      "/api/client": CLIENT,
      "./client": CLIENT,
      "/api/chat": `export const openTripLocation=(id)=>{globalThis.__at.locOpened.push(id);return{close(){}}};`,
      "/booking/OwnBookingsProvider": `export const useRefreshOwnBookings=()=>()=>{globalThis.__at.refreshed++;};`,
      "/analytics": `export const track=(e,p)=>{globalThis.__at.tracked.push([e,p]);};`,
      "/api/driver": `export const setDriverStatus=async(id,ph)=>{globalThis.__at.phases.push([id,ph]);return{ok:true};};`,
    },
  });
  const at = (globalThis.__at = {
    generation: "A",
    nav: [],
    locOpened: [],
    refreshed: 0,
    tracked: [],
    phases: [],
    posts: [],
    details: {
      booking_id: 42, ride_id: 7, role, status, contact_unlocked: true,
      from_city: "Уфа", to_city: "Сибай", depart_at: "2030-01-01T10:00:00", seats: 1, price: 500,
      pay_method: "cash", driver_name: "Ринат", driver_verified: true, driver_phone: "+79990000000",
      driver_car: "Lada", pickup: "", from_lat: null, from_lng: null, to_lat: null, to_lng: null,
    },
    trip: { role, status, driver_phase: phase },
    async get(p) {
      if (p === "/bookings/42/details") return { ...at.details };
      if (p === "/bookings/42/role") return { ...at.trip };
      if (p === "/bookings/42/boarding-code") return { code: "1234" };
      throw new Error("нет ответа для GET " + p);
    },
    async post(p, b) {
      at.posts.push([p, b]);
      if (p === "/bookings/42/cancel") {
        const res = cancel ? await cancel(b) : { id: 42, status: "cancelled", contact_then_cancel: true };
        if (res.status) {
          at.details.status = res.status;
          at.trip.status = res.status;
        }
        return res;
      }
      return { ok: true };
    },
  });
  const h = mount(Screen);
  h.render();
  await h.settle();
  return { h, at };
}
