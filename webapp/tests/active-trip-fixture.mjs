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
 * `details` / `trip` — поля поверх ответов сервера по умолчанию (например, `cancelled_in_transit`).
 * `intercept(path, at)` — свой ответ на GET раньше обычного (undefined — обычный): «нет сети» — бросить,
 * «ответ ещё идёт» — вечный Promise. `userId` — кто вошёл (по умолчанию 1).
 * @param {{lang?:string, role?:string, status?:string, phase?:string, cancel?:(body:any)=>any, details?:object, trip?:object,
 *   intercept?:(path:string, at:any)=>any, userId?:number}} o
 */
export async function openActiveTrip({ lang = "ru", role = "passenger", status = "confirmed", phase = "", cancel, details = {}, trip = {},
  intercept, userId = 1 } = {}) {
  const Screen = await bundleComponent("src/screens/ActiveTripScreen.tsx", {
    real: ["/api/bookings", "/utils/tripLocationShare", "/utils/tripDetailsSync.js", "/utils/inTransitCancel"],
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>globalThis.__at.nav.push(p);export const useParams=()=>({id:"42"});`,
      "/auth/AuthProvider": `export const useAuth=()=>({user:{id:globalThis.__at.userId}});`,
      "/i18n/lang": langMock(lang),
      "/api/client": CLIENT,
      "./client": CLIENT,
      "/api/chat": `export const openTripLocation=(id)=>{globalThis.__at.locOpened.push(id);return{close(){}}};`,
      "/booking/OwnBookingsProvider": `export const useRefreshOwnBookings=()=>()=>{globalThis.__at.refreshed++;};`,
      "/analytics": `export const track=(e,p)=>{globalThis.__at.tracked.push([e,p]);};`,
      // at.offline = true — «трасса без связи»: ApiError со статусом 0, отметка уходит в очередь.
      "/api/driver": `import { ApiError } from "../api/client";
        export const setDriverStatus=async(id,ph)=>{if(globalThis.__at.offline)throw new ApiError(0,"offline");globalThis.__at.phases.push([id,ph]);return{ok:true};};`,
      // Слушатель отказов очереди: экран отдаёт сюда свой обработчик, набор вызывает его сам.
      "/utils/useBookingRefusal": `export const useBookingRefusal=(id,fn)=>{globalThis.__at.refusal={id,fn};};`,
      // Д2: остались ли у рейса пассажиры после «Завершить» (at.rideRemains; по умолчанию — не знаем).
      "/utils/driverRideShare": `export const driverRideRemains=async(id)=>{globalThis.__at.remainsAsked.push(id);return globalThis.__at.rideRemains;};`,
      "/utils/rideShareStore": `export const requestRidingFocus=()=>{globalThis.__at.ridingFocus++;};
        export const rideShareHandoff=()=>{globalThis.__at.handoffs++;};`,
    },
  });
  const at = (globalThis.__at = {
    generation: "A",
    userId,
    gets: [],
    nav: [],
    locOpened: [],
    refreshed: 0,
    tracked: [],
    phases: [],
    posts: [],
    rideRemains: undefined,
    remainsAsked: [],
    ridingFocus: 0,
    handoffs: 0,
    details: {
      booking_id: 42, ride_id: 7, role, status, contact_unlocked: true,
      from_city: "Уфа", to_city: "Сибай", depart_at: "2030-01-01T10:00:00", seats: 1, price: 500,
      pay_method: "cash", driver_name: "Ринат", driver_verified: true, driver_phone: "+79990000000",
      driver_car: "Lada", pickup: "", from_lat: null, from_lng: null, to_lat: null, to_lng: null,
      ...details,
    },
    trip: { role, status, driver_phase: phase, ...trip },
    async get(p) {
      at.gets.push(p);
      const own = intercept?.(p, at);
      if (own !== undefined) return own;
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
