// Real provider, cards, sheet and trip/booking callers. Browser, hooks and HTTP boundaries are controlled.
import assert from "node:assert/strict";
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return { promise, resolve, reject }; };
const mocks = {
  react: `const h=()=>globalThis.__ownBookingsHarness, active=()=>h().active;
    const eq=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
    export const createContext=value=>{const ctx={value};ctx.Provider={ctx};return ctx;};
    export const useContext=ctx=>h().contexts.get(ctx)??ctx.value;
    export function useState(initial){const f=active(),i=f.cursor++;if(!(i in f.slots))f.slots[i]=typeof initial==="function"?initial():initial;
      return[f.slots[i],v=>{if(!f.mounted)return;const old=f.slots[i];f.slots[i]=typeof v==="function"?v(old):v;if(!Object.is(old,f.slots[i]))h().dirty=true;}];}
    export function useRef(v){const f=active(),i=f.cursor++;return f.slots[i]??={current:v};}
    export function useMemo(fn,deps){const f=active(),i=f.cursor++;if(!eq(f.deps[i],deps)){f.deps[i]=deps;f.slots[i]=fn();}return f.slots[i];}
    export const useCallback=(fn,deps)=>useMemo(()=>fn,deps);
    export function useEffect(fn,deps){const f=active(),i=f.cursor++;if(!eq(f.deps[i],deps)){f.deps[i]=deps;f.effects.push(()=>{f.cleanups[i]?.();f.cleanups[i]=fn();});}}`,
  "react/jsx-runtime": `export const Fragment="fragment";export const jsx=(type,props,key)=>({type,props:props??{},key});export const jsxs=jsx;`,
  router: `const h=()=>globalThis.__ownBookingsHarness;export const Link="link",Navigate="navigate";
    export const useLocation=()=>({pathname:h().pathname}),useParams=()=>({id:String(h().bookingId)}),useNavigate=()=>(...args)=>h().routes.push(args);`,
  auth: `export const useAuth=()=>globalThis.__ownBookingsHarness.auth;`,
  lang: `export const useLang=()=>({lang:globalThis.__ownBookingsHarness.lang,appText:(ru,ba)=>globalThis.__ownBookingsHarness.lang==="ba"?ba:ru});`,
  client: `export const getSessionGeneration=()=>globalThis.__ownBookingsHarness.generation;export class ApiError extends Error{}`,
  bookings: `const h=()=>globalThis.__ownBookingsHarness;
    export const fetchMyBookings=signal=>{const req={...h().makeDeferred(),signal,generation:h().generation,userId:h().auth.user?.id};h().requests.push(req);return req.promise;};
    export const createBooking=body=>{h().posts.push(body);return h().book(body);};
    export const cancelBooking=id=>{h().cancels.push(id);return h().cancel(id);};
    export const CANCEL_REASONS=[];
    export const fetchBookingDetails=(id,signal)=>h().details(id,signal);
    export const fetchTripState=async()=>({role:h().role,status:"confirmed",driver_phase:"departed"});
    export const fetchBoardingCode=async()=>({code:"1234"});
    export const rateBooking=async()=>({}),ratingTags=()=>[],setPayAgreement=async()=>({});`,
  medical: `export const fetchMedicalPartners=async()=>[{id:8,name:"Клиника",city:"Уфа"}];export const fetchClinicRides=async()=>({items:[globalThis.__ownBookingsHarness.ride,{...globalThis.__ownBookingsHarness.ride,id:43,front_seat_taken:false}]});`,
  cabinet: `export const SettingSwitchRow="switch";`,
  features: `const flags={parcels:true,ready:true};export const useFeatures=()=>flags,getFeatures=()=>flags;`,
  soon: `export default ()=>null;`,
  analytics: `export const track=()=>{};`,
  states: `export const LoadingList="loading",ErrorState="retry";`,
  header: `export const SubHeader="header";`,
  map: `export default "map";`,
  status: `export const StatusPill="status";`,
  partner: `export const PartnerAdSlot="ad";`,
  pass: `export const loadTripPass=()=>null,loadOfflineTripPass=()=>null,saveTripPass=()=>false,saveVerifiedTripPass=()=>{},dropTripPass=()=>{},clearTripPassBoardingCode=()=>{},tripPassStage=()=>'confirmed';`,
  driver: `export const setDriverStatus=async()=>({});`,
  chat: `export const openTripLocation=()=>({close(){}});`,
  safety: `export const winterCheck=async()=>({state:"ok"}),winterCheckOk=async()=>({});`,
  winter: `export const forgetWinterCheck=()=>{},rememberWinterCheck=()=>{},wasWinterCheckAsked=()=>false,winterCheckDelay=()=>Infinity,winterCheckNeedsAnswer=()=>false;`,
  interval: `export const useVisibleInterval=()=>{};`,
  weather: `export default "weather";export const useRouteWeather=()=>null;`,
  roadside: `export default "roadside";`,
  share: `export default "share";`,
  tripchat: `export default "tripchat";`,
  offline: `export default "offline";`,
  driverloc: `export default "driverloc";`,
  driverboarding: `export default "driverboarding";`,
  boardingwait: `export default "boardingwait";`,
  driverboardeddispute: `export default "driverboardeddispute";`,
  outbox: `export const enqueue=async()=>{};`,
};
const icons = [...readFileSync(root+"src/components/Icons.tsx","utf8").matchAll(/export (?:const|function) (Icon\w+)/g)].map(m=>m[1]);
mocks.icons=icons.map(name=>`export const ${name}="icon";`).join("\n");
const suffixes = {
  "/api/client":"client", "/api/bookings":"bookings", "/api/medical":"medical", "/auth/AuthProvider":"auth", "/i18n/lang":"lang",
  "/features":"features", "/ComingSoon":"soon",
  "/analytics":"analytics", "/cabinetUi":"cabinet", "/Icons":"icons", "/components/States":"states",
  "/ConsentsScreen":"header", "/components/YandexMap":"map", "/components/StatusPill":"status",
  "/components/PartnerAd":"partner", "/utils/tripPass":"pass", "/utils/offlineTripPass":"pass", "/api/driver":"driver", "/api/chat":"chat",
  "/api/safety":"safety", "/utils/winterCheck.js":"winter", "/utils/useVisibleInterval":"interval",
  "/components/WeatherWarningCard":"weather", "/components/RoadsideHelp":"roadside",
  "/components/ShareTripCard":"share", "/components/TripChatCard":"tripchat",
  "/components/OfflineTripPass":"offline", "/utils/outbox":"outbox", "/components/DriverLocationShare":"driverloc",
  "/components/DriverBoarding":"driverboarding", "/components/BoardingWait":"boardingwait", "/components/DriverBoardedDispute":"driverboardeddispute",
};
const result=await build({
  stdin:{contents:`export {OwnBookingsProvider,useOwnBooking,useRefreshOwnBookings} from "./src/booking/OwnBookingsProvider.tsx";
    export {default as Card} from "./src/components/RideCard.tsx";export {default as Sheet} from "./src/components/RideSheet.tsx";
    export {default as Booking} from "./src/screens/BookingScreen.tsx";export {default as ActiveTrip} from "./src/screens/ActiveTripScreen.tsx";
    export {default as Clinics} from "./src/screens/ClinicRidesScreen.tsx";`,
    resolveDir:root,loader:"tsx"},
  bundle:true,write:false,platform:"node",format:"esm",jsx:"automatic",
  plugins:[{name:"private-seat-boundaries",setup(b){
    b.onResolve({filter:/.*/},a=>{
      const key=a.path in mocks?a.path:a.path==="react-router-dom"?"router":Object.entries(suffixes).find(([suffix])=>a.path.endsWith(suffix))?.[1];
      return key?{path:key,namespace:"fixture"}:undefined;
    });
    b.onLoad({filter:/(?:RideSheet|BookingScreen|OwnBookingsProvider)\.tsx$/},a=>{
      const mutant=process.env.OWN_BOOKINGS_TEST_MUTANT;
      if(!mutant)return undefined;
      let content=readFileSync(a.path,"utf8");
      if(mutant==="skip-refresh"&&/(?:RideSheet|BookingScreen)\.tsx$/.test(a.path))
        content=content.replace("      refreshOwnBookings();","      /* mutation control: skip successful invalidation */");
      if(mutant==="late-read"&&/OwnBookingsProvider\.tsx$/.test(a.path))
        content=content.replace("requestVersion.current === version &&","true &&");
      return{contents:content,loader:"tsx"};
    });
    b.onLoad({filter:/.*/,namespace:"fixture"},a=>({contents:mocks[a.path],loader:"js"}));
  }}],
});
const api=await import("data:text/javascript;base64,"+Buffer.from(result.outputFiles[0].text).toString("base64"));
const own={id:73,ride_id:42,status:"confirmed",front_seat:true};
const label=lang=>lang==="ba"?"Алғы урын — һинеке":"Переднее место — твоё";
const all=n=>!n||typeof n!=="object"?[]:Array.isArray(n)?n.flatMap(all):[n,...all(n.props?.children)];
function text(n){if(n==null||typeof n==="boolean")return "";if(typeof n!=="object")return String(n);return Array.isArray(n)?n.map(text).join(" "):text(n.props?.children);}
function Probe(){return{type:"probe",props:{booking:api.useOwnBooking(42),refresh:api.useRefreshOwnBookings()}};}
function Root() {
  const h=globalThis.__ownBookingsHarness;
  const children=h.mode==="active"?[{type:api.ActiveTrip,props:{}}]:h.mode==="clinic"?[{type:api.Clinics,props:{}}]:
    [...Array.from({length:h.cards},(_,index)=>({type:api.Card,props:{ride:h.ride,index,key:index}})),
      {type:h.mode==="cancel"?api.Booking:api.Sheet,props:{ride:h.ride,onClose:()=>h.closes++}},{type:Probe,props:{}}];
  return{type:api.OwnBookingsProvider,props:{children}};
}
function fresh(){
  const h=globalThis.__ownBookingsHarness={fibers:new Map(),contexts:new Map(),active:null,dirty:true,mounted:true,
    auth:{isAuthed:true,user:{id:1},status:"authed"},generation:"A",pathname:"/rides/feed",lang:"ru",role:"passenger",mode:"feed",cards:1,
    bookingId:73,requests:[],posts:[],cancels:[],routes:[],closes:0,makeDeferred:deferred,listeners:new Map(),
    ride:{id:42,driver_id:7,from_city:"Уфа",to_city:"Сибай",depart_at:"2030-01-01T10:00:00",seats_left:2,seats_total:3,
      price:500,driver_name:"Ринат",driver_rating:5,driver_car:"Lada",category:"regular",front_seat_available:true,front_seat_taken:true},
    book:async()=>({id:73}),cancel:async()=>({id:73}),details:async()=>({booking_id:73,ride_id:42,role:h.role,status:"confirmed",
      from_city:"Уфа",to_city:"Сибай",depart_at:"2030-01-01T10:00:00",seats:1,price:500,pay_method:"cash",front_seat:true,driver_name:"Ринат"}),
  };
  globalThis.HTMLElement=class {};
  globalThis.document={activeElement:null};
  globalThis.window={confirm:()=>true,
    addEventListener:(event,fn)=>{if(!h.listeners.has(event))h.listeners.set(event,new Set());h.listeners.get(event).add(fn);},
    removeEventListener:(event,fn)=>h.listeners.get(event)?.delete(fn)};
  return h;
}
function render(h,commit=true){
  h.dirty=false;const visited=new Set(),effects=[];
  function walk(n,path){
    if(Array.isArray(n))return n.map((child,i)=>walk(child,path+"/"+i));
    if(!n||typeof n!=="object")return n;
    if(n.type?.ctx){h.contexts.set(n.type.ctx,n.props.value);return walk(n.props.children,path+"/provider");}
    if(typeof n.type==="function"){
      const id=path+":"+n.type.name+":"+String(n.key??"");let f=h.fibers.get(id);
      if(!f){f={slots:[],deps:[],cleanups:[],effects:[],cursor:0,mounted:true};h.fibers.set(id,f);}
      visited.add(id);f.cursor=0;f.effects=[];h.active=f;const output=n.type(n.props??{});effects.push(...f.effects);
      return walk(output,id);
    }
    return{...n,props:{...n.props,children:walk(n.props?.children,path+"/children")}};
  }
  h.tree=h.mounted?walk({type:Root,props:{}},"root"):null;
  for(const [id,f]of h.fibers)if(!visited.has(id)){f.mounted=false;f.cleanups.forEach(fn=>fn?.());h.fibers.delete(id);}
  h.pendingEffects=effects;if(commit)effects.forEach(fn=>fn());
}
async function settle(h){for(let i=0;i<24;i++){if(h.dirty)render(h);await Promise.resolve();}assert.equal(h.dirty,false,"Hooks did not settle");}
const probe=h=>all(h.tree).find(n=>n.type==="probe").props;
const card=h=>all(h.tree).find(n=>n.type==="article"&&n.props.className==="ride-card");
const cta=h=>all(h.tree).find(n=>n.type==="button"&&n.props.className?.includes("sheet__cta"));
const cancel=h=>all(h.tree).find(n=>n.type==="button"&&text(n)==="Отменить бронь");
// Отмена на «Брони» — только ждущей брони и через своё окно «Отменить бронь?» (паритет с Android, 17.10.2026).
const yes=h=>all(h.tree).find(n=>n.type==="button"&&text(n)==="Да, отменить");
const pendingDetails=h=>{const details=h.details;h.details=async(...a)=>({...await details(...a),status:"pending"});};
function asB(h){h.auth={isAuthed:true,user:{id:2},status:"authed"};h.generation="B";h.dirty=true;}
async function ready(h,rows=[own]){render(h);await settle(h);assert.equal(h.requests.length,1);h.requests[0].resolve(rows);await settle(h);}
let cases=0,failures=0;
async function check(name,fn){const h=fresh();cases++;try{await fn(h);console.log("✓ "+name);}catch(e){failures++;console.error("FAIL "+name+"\n"+e.stack);}finally{h.mounted=false;render(h);}}
await check("one private GET serves 30 real cards and the sheet",async h=>{
  h.cards=30;await ready(h);assert.equal(h.requests.length,1);
  assert.ok(text(card(h)).includes(label(h.lang)));assert.ok(text(cta(h)).includes("Открыть поездку"));
  assert.equal(probe(h).booking.id,73);
});
for(const lang of ["ru","ba"])await check(lang+": own front identity reaches Card and Sheet",async h=>{
  h.lang=lang;await ready(h);assert.ok(text(card(h)).includes(label(lang)));
  const sheet=all(h.tree).find(n=>n.props.className==="sheet");assert.ok(text(sheet).includes(label(lang)));
  assert.ok(!text(sheet).includes(lang==="ru"?"другой пассажир":"башҡа юлсы"));
});
for(const status of ["pending","confirmed","onboard"])await check("existing "+status+" booking opens its identity without POST",async h=>{
  await ready(h,[{...own,status}]);await cta(h).props.onClick();
  assert.deepEqual(h.posts,[]);assert.equal(h.routes[0][0],status==="pending"?"/booking/73":"/trip/73");
});
await check("guest feed has no private read or own label",async h=>{
  h.auth={isAuthed:false,user:null,status:"guest"};render(h);await settle(h);assert.equal(h.requests.length,0);
  assert.equal(probe(h).booking,null);assert.ok(!text(h.tree).includes(label(h.lang)));
});
await check("logout clears displayed ownership before effect commit and retires late response",async h=>{
  await ready(h);const refresh=probe(h).refresh;refresh();await settle(h);const pending=h.requests[1];
  h.auth={isAuthed:false,user:null,status:"guest"};h.generation="guest";render(h,false);
  assert.ok(!text(h.tree).includes(label(h.lang)));h.pendingEffects.forEach(fn=>fn());await settle(h);
  pending.resolve([own]);await settle(h);assert.equal(probe(h).booking,null);
});
await check("switch A to B clears A even when B fails",async h=>{
  await ready(h);asB(h);render(h,false);assert.ok(!text(h.tree).includes(label(h.lang)));
  h.pendingEffects.forEach(fn=>fn());await settle(h);h.requests[1].reject(new Error("offline"));await settle(h);
  assert.equal(probe(h).booking,null);assert.ok(!text(h.tree).includes(label(h.lang)));
});
await check("late A success cannot replace B response",async h=>{
  render(h);asB(h);await settle(h);assert.equal(h.requests.length,2);
  h.requests[1].resolve([{...own,id:88,front_seat:false}]);await settle(h);
  h.requests[0].resolve([own]);await settle(h);assert.equal(probe(h).booking.id,88);
  assert.ok(!text(h.tree).includes(label(h.lang)));
});
await check("same user new login still rejects previous generation",async h=>{
  render(h);h.generation="A-new";h.dirty=true;await settle(h);
  h.requests[1].resolve([]);await settle(h);h.requests[0].resolve([own]);await settle(h);assert.equal(probe(h).booking,null);
});
await check("old mutation refresh cannot invalidate B",async h=>{
  await ready(h);const oldRefresh=probe(h).refresh;asB(h);await settle(h);h.requests[1].resolve([{...own,id:88}]);await settle(h);
  oldRefresh();await settle(h);assert.equal(h.requests.length,2);assert.equal(probe(h).booking.id,88);
});
await check("refresh retires earlier same-owner request and accepts authoritative empty result",async h=>{
  render(h);probe(h).refresh();await settle(h);assert.equal(h.requests.length,2);
  h.requests[1].resolve([]);await settle(h);h.requests[0].resolve([own]);await settle(h);assert.equal(probe(h).booking,null);
});
await check("failed fresh read does not retain a previous own badge",async h=>{
  await ready(h);probe(h).refresh();await settle(h);assert.equal(probe(h).booking,null);
  h.requests[1].reject(new Error("offline"));await settle(h);assert.equal(probe(h).booking,null);
});
await check("route change rereads once, terminal/rear/malformed rows cannot own front",async h=>{
  await ready(h);h.pathname="/rides/42";h.dirty=true;await settle(h);assert.equal(h.requests.length,2);
  h.requests[1].resolve([{...own,status:"done"},{...own,id:0},{...own,id:89,status:"cancelled"}]);await settle(h);
  assert.equal(probe(h).booking,null);assert.ok(!text(h.tree).includes(label(h.lang)));
});
await check("successful booking clears snapshot and rereads own identity",async h=>{
  h.ride.front_seat_taken=false;await ready(h,[]);const post=deferred();h.book=()=>post.promise;
  const switchNode=all(h.tree).find(n=>n.type==="switch"&&n.props.title==="Хочу переднее место");
  switchNode.props.onChange(true);await settle(h);const action=cta(h).props.onClick();
  assert.equal(h.posts[0].front_seat,true);post.resolve({id:75});await action;await settle(h);
  assert.equal(h.requests.length,2);h.requests[1].resolve([{...own,id:75}]);await settle(h);
  assert.equal(probe(h).booking.id,75);assert.equal(h.routes[0][0],"/trip/75");
});
await check("failed booking does not invalidate proven own list",async h=>{
  h.ride.front_seat_taken=false;await ready(h,[]);h.book=async()=>{throw new Error("offline");};
  await cta(h).props.onClick();await settle(h);assert.equal(h.requests.length,1);assert.equal(h.routes.length,0);
});
await check("late booking success from A cannot refresh or navigate B",async h=>{
  h.ride.front_seat_taken=false;await ready(h,[]);const post=deferred();h.book=()=>post.promise;
  const action=cta(h).props.onClick();asB(h);await settle(h);h.requests[1].resolve([]);await settle(h);
  post.resolve({id:75});await action;await settle(h);assert.equal(h.requests.length,2);assert.equal(h.routes.length,0);
});
await check("successful cancellation invalidates own front reservation",async h=>{
  h.mode="cancel";pendingDetails(h);await ready(h);const post=deferred();h.cancel=()=>post.promise;
  cancel(h).props.onClick();await settle(h);assert.deepEqual(h.cancels,[],"окно сначала, запрос — после «Да, отменить»");
  const action=yes(h).props.onClick();assert.deepEqual(h.cancels,[73]);post.resolve({id:73,status:"cancelled"});
  await action;await settle(h);assert.equal(h.requests.length,2);h.requests[1].resolve([]);await settle(h);
  assert.equal(probe(h).booking,null);assert.ok(!text(card(h)).includes(label(h.lang)));
});
await check("failed cancellation retains own list and uses existing error",async h=>{
  h.mode="cancel";pendingDetails(h);await ready(h);h.cancel=async()=>{throw new Error("offline");};cancel(h).props.onClick();await settle(h);
  yes(h).props.onClick();await settle(h);
  assert.equal(h.requests.length,1);assert.equal(probe(h).booking.id,73);assert.ok(text(h.tree).includes("Не получилось отменить"));
});
await check("focus retries once; unmount aborts and ignores held response",async h=>{
  await ready(h);for(const fn of h.listeners.get("focus"))fn();await settle(h);assert.equal(h.requests.length,2);
  const pending=h.requests[1];h.mounted=false;render(h);assert.equal(pending.signal.aborted,true);
  pending.resolve([own]);await settle(h);assert.equal(h.tree,null);assert.equal(h.listeners.get("focus").size,0);
});
for(const lang of ["ru","ba"])for(const role of ["passenger","driver"])await check(lang+": active trip shows front seat for "+role,async h=>{
  h.mode="active";h.pathname="/trip/73";h.lang=lang;h.role=role;render(h);await settle(h);
  const expected=role==="driver"?(lang==="ru"?"Пассажир просит переднее место":"Юлсы алғы урынды һорай"):(lang==="ru"?"Переднее место":"Алғы урын");
  assert.ok(text(h.tree).includes(expected),text(h.tree));
  assert.equal(h.requests.length,0,"Trip details need no /bookings/mine request");
});
await check("response after refresh cannot restore own badge before the next effect",async h=>{
  render(h);probe(h).refresh();h.requests[0].resolve([own]);await Promise.resolve();await Promise.resolve();
  render(h,false);assert.equal(probe(h).booking,null);assert.ok(!text(card(h)).includes(label(h.lang)));
  h.pendingEffects.forEach(fn=>fn());await settle(h);h.requests[1].resolve([]);await settle(h);
});
await check("malformed private response leaves ownership unknown",async h=>{
  render(h);h.requests[0].resolve(null);await settle(h);assert.equal(probe(h).booking,null);
});
await check("late cancellation from A cannot clear B own front reservation",async h=>{
  h.mode="cancel";pendingDetails(h);await ready(h);const post=deferred();h.cancel=()=>post.promise;cancel(h).props.onClick();await settle(h);
  const action=yes(h).props.onClick();
  asB(h);await settle(h);h.requests[1].resolve([{...own,id:88}]);await settle(h);
  post.resolve({id:73,status:"cancelled"});await action;await settle(h);
  assert.equal(h.requests.length,2);assert.equal(probe(h).booking.id,88);
});
for(const lang of ["ru","ba"])for(const front of [false,undefined])await check(lang+": active trip without front request omits seat row ("+String(front)+")",async h=>{
  h.mode="active";h.pathname="/trip/73";h.lang=lang;const details=h.details;h.details=async()=>({...await details(),front_seat:front});
  render(h);await settle(h);
  const row=all(h.tree).find(n=>n.props.className==="info-row__k"&&text(n)===(lang==="ru"?"Место":"Урын"));
  assert.equal(row,undefined);
});
await check("actual clinic list and its sheet share the same own-bookings read",async h=>{
  h.mode="clinic";h.pathname="/clinics";render(h);await settle(h);
  assert.equal(h.requests.length,1,"The clinic route needs one shared private read");
  h.requests[0].resolve([own]);await settle(h);
  all(h.tree).find(n=>n.type==="button"&&n.props.className==="list-row list-row--link").props.onClick();await settle(h);
  assert.ok(text(card(h)).includes(label(h.lang)));
  all(h.tree).find(n=>n.type==="button"&&n.props.className==="ride-card-btn").props.onClick();await settle(h);
  assert.ok(text(all(h.tree).find(n=>n.props.className==="sheet")).includes(label(h.lang)));
  assert.equal(h.requests.length,1,"Opening the clinic sheet must not create a read per card");
});
for(const pathname of ["/map","/rides/feed","/rides/42","/clinics","/my-requests","/requests/19/responses"])await check(pathname+": shared Card/Sheet consumers receive own identity",async h=>{
  h.pathname=pathname;await ready(h);assert.ok(text(card(h)).includes(label(h.lang)));
  assert.equal(h.requests.length,1);
});
assert.match(readFileSync(root+"src/main.tsx","utf8"),/<OwnBookingsProvider>[\s\S]*<App\s*\/>[\s\S]*<\/OwnBookingsProvider>/,"Provider must wrap actual routes");
console.log(`${cases} cases, ${failures} failures`);process.exitCode=failures?1:0;
