import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { bundleComponent, mount, langMock, all } from "./shallow-harness.mjs";
const root = fileURLToPath(new URL("../", import.meta.url));
const values = new Map(), local = new Map();
const h = globalThis.__guestBooking = { generation: "guest-A", routes: [], storageFails: false };
const storage = map => ({
  get length() { return map.size; }, key: i => [...map.keys()][i] ?? null,
  getItem: k => { if (h.storageFails) throw Error("unavailable"); return map.get(k) ?? null; },
  setItem: (k,v) => { if (h.storageFails) throw Error("unavailable"); map.set(k,String(v)); },
  removeItem: k => { if (h.storageFails) throw Error("unavailable"); map.delete(k); },
});
globalThis.sessionStorage = storage(values); globalThis.localStorage = storage(local);
globalThis.document = { body: {}, visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
globalThis.window = { open() {} };
const client = 'export class ApiError extends Error {constructor(status,message=""){super(message);this.status=status;}};export const getSessionGeneration=()=>globalThis.__guestBooking.generation;export const apiPost=async()=>({});';
const result = await build({entryPoints:[root+"src/utils/guestBookingDraft.ts"],bundle:true,write:false,format:"esm",platform:"node",plugins:[{name:"owner",setup(b){
  b.onResolve({filter:/api\/client$/},()=>({path:"client",namespace:"owner"}));
  b.onLoad({filter:/.*/,namespace:"owner"},()=>({contents:client}));
}}]});
const draft = await import("data:text/javascript;base64,"+Buffer.from(result.outputFiles[0].text).toString("base64"));
const pending = "yuldash.guestBooking.pending", restore = "yuldash.guestBooking.restore";
const selection = { seats:2, pickupStop:"Акъяр", dropoffStop:"Сибай", meeting:{lat:54,lng:56,note:"QA Gate A"}, alighting:{note:"QA Gate B"} };
let total=0, bad=0;
async function test(name, fn) {
  total++;values.clear();local.clear();Object.assign(h,{generation:"guest-A",routes:[],storageFails:false});
  try { await fn();console.log("PASS "+name); } catch(e){bad++;console.error("FAIL "+name+": "+e.message);}
}
const remember = (s=selection) => draft.rememberGuestBookingDraft(42,s,h.generation);
function publish(nonce) {const claim=draft.claimGuestBookingDraft(nonce,"guest-A");assert(claim);h.generation="login-B";return draft.publishGuestBookingDraft(claim,h.generation);}
await test("G10-seats-and-both-user-points-once",()=>{
  const nonce=remember();assert(nonce);
  assert.equal(publish(nonce),nonce);
  assert.deepEqual(draft.consumeGuestBookingDraft(nonce,42,"login-B"),selection);
  assert.equal(draft.consumeGuestBookingDraft(nonce,42,"login-B"),null);
  assert.equal(values.size,0);
});
await test("G10-guardian-and-arbitrary-fields-never-carried",()=>{
  const options={...selection,frontSeat:true,minor:true};
  const nonce=remember({...options,guardianName:"Fixture Adult",guardianPhone:"not-a-real-number",token:"never-copy"});
  const raw=values.get(pending);assert(!raw.includes("guardian"));assert(!raw.includes("token"));
  publish(nonce);assert.deepEqual(draft.consumeGuestBookingDraft(nonce,42,"login-B"),options);
});
for(const flags of [{frontSeat:"true"},{minor:1},{minor:null}])await test(
  "G10-invalid-option-flags-"+JSON.stringify(flags),
  ()=>assert.equal(remember({...selection,...flags}),undefined)
);
await test("G10-wrong-nonce-preserves-current-intent",()=>{
  const nonce=remember();assert.equal(draft.claimGuestBookingDraft("wrong-nonce","guest-A"),null);
  assert(values.has(pending));assert(draft.claimGuestBookingDraft(nonce,"guest-A"));
  assert.equal(draft.claimGuestBookingDraft(nonce,"guest-A"),null);
});
await test("G10-wrong-source-cannot-transfer",()=>{
  const nonce=remember();h.generation="other-login";
  assert.equal(draft.claimGuestBookingDraft(nonce,"other-login"),null);assert(!values.has(restore));
});
for(const age of [-10000,draft.GUEST_BOOKING_DRAFT_TTL_MS+1])await test("G10-time-boundary-"+age,()=>{
  const nonce=remember(), raw=JSON.parse(values.get(pending));raw.at=Date.now()-age;
  values.set(pending,JSON.stringify(raw));assert.equal(draft.claimGuestBookingDraft(nonce,"guest-A"),null);
});
await test("G10-no-rebind-to-same-or-different-current-owner",()=>{
  const nonce=remember(),claim=draft.claimGuestBookingDraft(nonce,"guest-A");
  assert.equal(draft.publishGuestBookingDraft(claim,"guest-A"),undefined);
  h.generation="login-C";assert.equal(draft.publishGuestBookingDraft(claim,"login-B"),undefined);
});
for(const [ride,owner] of [[43,"login-B"],[42,"login-C"]])await test("G10-wrong-ride-or-owner-"+ride+"-"+owner,()=>{
  const nonce=remember();publish(nonce);
  assert.equal(draft.consumeGuestBookingDraft(nonce,ride,owner),null);assert(!values.has(restore));
});
await test("G10-expired-restoration-is-not-replayed",()=>{
  const nonce=remember();publish(nonce);const raw=JSON.parse(values.get(restore));
  raw.at=Date.now()-draft.GUEST_BOOKING_DRAFT_TTL_MS-1;values.set(restore,JSON.stringify(raw));
  assert.equal(draft.consumeGuestBookingDraft(nonce,42,"login-B"),null);
});
for(const seats of [0,9,1.5,NaN])await test("G10-invalid-seat-count-"+seats,()=>assert.equal(remember({...selection,seats}),undefined));
await test("G10-invalid-coordinate-pair-closed",()=>assert.equal(remember({...selection,meeting:{lat:54}}),undefined));
await test("G10-unavailable-storage-does-not-break-navigation",()=>{h.storageFails=true;assert.equal(remember(),undefined);});
await test("G10-explicit-exit-clears-only-own-handoff",()=>{
  const nonce=remember();draft.clearPendingGuestBookingDraft("other");assert(values.has(pending));
  draft.clearPendingGuestBookingDraft(nonce);assert(!values.has(pending));
});
await test("G10-account-boundary-clears-tab-selection",async()=>{
  const nonce=remember();publish(nonce);local.set("yuldash.lang","ba");
  const clear=await bundleComponent("src/utils/privacy.ts",{exportName:"clearPersonalLocal",mocks:{"/storageHealth":"export const reportStorageFailure=()=>{};"}});
  clear();assert.equal(values.size,0);assert.equal(local.get("yuldash.lang"),"ba");
});
async function mountedLogin(nonce) {
  h.location={pathname:"/login",state:{from:"/rides/42",guestBooking:true,guestBookingNonce:nonce,
    returnState:{rideMatch:{rideId:42,owner:"guest-A",matched_stop:"Акъяр",matched_dropoff_stop:"Сибай"}}}};
  let release, entered;
  const started=new Promise(resolve=>{entered=resolve;});
  h.login=()=>new Promise(resolve=>{release=(commit=true)=>{if(commit)h.generation="login-B";resolve("login-B");};entered();});
  const api = 'export const TELEGRAM_BOT="";export const lastLoginMethods=()=>["sms"],fetchLoginMethods=async()=>["sms"],tgStart=async()=>({}),tgVerify=async()=>({access_token:"fixture-A",refresh_token:"fixture-R",user:{id:7}}),requestSmsCode=async()=>({sent:true}),verifySmsCode=tgVerify,telegramStartUrl=()=>"",telegramChatUrl=()=>"";';
  const Screen=await bundleComponent("src/screens/LoginScreen.tsx",{real:["/utils/loginFlow","/utils/guestBookingDraft"],mocks:{
    "react-router-dom":'export const useLocation=()=>globalThis.__guestBooking.location,useNavigate=()=> (...a)=>globalThis.__guestBooking.routes.push(a);',
    "/api/client":client,"/api/auth":api,"/i18n/lang":langMock("ru"),
    "/auth/AuthProvider":'export const useAuth=()=>({login:(...a)=>globalThis.__guestBooking.login(...a)});',
    "/analytics":'export const track=()=>{};',
  }});
  const view=mount(Screen);view.render();
  const flow=view.state.values.find(v=>v?.current?.smsVerify)?.current;assert(flow);
  const flight=flow.smsVerify("+70000000000","000000","QA");await started;
  return {view,flight,release:(commit=true)=>release(commit)};
}
await test("G10-deferred-login-after-back-does-not-publish-or-navigate",async()=>{
  const nonce=remember(),login=await mountedLogin(nonce);assert(!values.has(pending));
  login.view.unmount();await Promise.resolve();login.release();await login.flight;
  assert(!values.has(restore));assert.equal(h.routes.length,0);
});
await test("G10-current-successful-login-publishes-for-exact-owner",async()=>{
  const nonce=remember(),login=await mountedLogin(nonce);login.release();await login.flight;
  assert.equal(h.routes.length,1);assert.equal(h.routes[0][0],"/rides/42");
  assert.equal(h.routes[0][1].state.guestBookingNonce,nonce);
  assert.equal(h.routes[0][1].state.rideMatch,undefined,"guest history must not be restamped independently of the draft");
  assert.deepEqual(draft.consumeGuestBookingDraft(nonce,42,"login-B"),selection);
  login.view.unmount();await Promise.resolve();
});

await test("G10-logout-during-deferred-login-cannot-publish-or-navigate",async()=>{
  const nonce=remember(),login=await mountedLogin(nonce);
  h.generation="logged-out-C";login.release(false);await login.flight;
  assert(!values.has(restore));assert.equal(h.routes.length,0);
  login.view.unmount();await Promise.resolve();
});
await test("G10-expired-login-does-not-restamp-history-stops",async()=>{
  const nonce=remember(),raw=JSON.parse(values.get(pending));raw.at=Date.now()-draft.GUEST_BOOKING_DRAFT_TTL_MS-1;
  values.set(pending,JSON.stringify(raw));const login=await mountedLogin(nonce);login.release();await login.flight;
  assert(!values.has(restore));assert.equal(h.routes.length,1);
  assert.equal(h.routes[0][1].state.guestBooking,true);
  assert.equal(h.routes[0][1].state.guestBookingNonce,undefined);
  assert.equal(h.routes[0][1].state.rideMatch,undefined);
  login.view.unmount();await Promise.resolve();
});
for(const invalid of ["",123,"X".repeat(201)])await test("G10-invalid-stop-closed-"+String(invalid).length,()=>{
  assert.equal(remember({...selection,pickupStop:invalid}),undefined);
});
const freshRide={id:42,driver_id:7,from_city:"Уфа",to_city:"Баймак",waypoints:"Акъяр | Сибай",depart_at:"2030-01-01T10:00:00",seats_left:2,seats_total:3,price:500,driver_name:"QA",category:"regular"};
const historicStops=owner=>({rideMatch:{rideId:42,owner,matched_stop:"Акъяр",matched_dropoff_stop:"Сибай",matched_from_start:false,matched_to_end:false}});
async function mountedDetail(state,fetchRide=async()=>freshRide) {
  h.location={pathname:"/rides/42",state};h.fetchRide=fetchRide;
  const Screen=await bundleComponent("src/screens/RideDetailScreen.tsx",{real:["/utils/guestBookingDraft","/utils/pickupStops"],mocks:{
    "react-router-dom":'export const useLocation=()=>globalThis.__guestBooking.location,useParams=()=>({id:"42"}),useNavigate=()=> (...a)=>globalThis.__guestBooking.routes.push(a);',
    "/api/client":client,
    "/api/rides":'export const fetchRide=(...a)=>globalThis.__guestBooking.fetchRide(...a);',
    "/auth/AuthProvider":'export const useAuth=()=>({status:"authed",isAuthed:true,user:{id:globalThis.__guestBooking.generation}});',
    "/i18n/lang":langMock("ru"),
  }});
  const view=mount(Screen);view.render();return view;
}
const sheet=view=>all(view.tree).find(n=>n.type==="RideSheet");
await test("G10-fresh-read-restores-all-choice-once-and-back-cannot-replay-stops",async()=>{
  const nonce=remember();publish(nonce);
  const history={...historicStops("login-B"),guestBooking:true,guestBookingNonce:nonce};
  const first=await mountedDetail(history);await first.settle();
  assert.deepEqual(sheet(first).props.initialSelection,selection);
  assert.equal(sheet(first).props.ride.matched_stop,undefined,"guest stops are not copied to reusable ride metadata");
  assert(!values.has(restore));first.unmount();await Promise.resolve();
  const back=await mountedDetail(history);await back.settle();
  assert.equal(sheet(back).props.initialSelection,null);
  assert.equal(sheet(back).props.ride.matched_stop,undefined);
  assert.equal(sheet(back).props.ride.matched_dropoff_stop,undefined);
  back.unmount();await Promise.resolve();
});
await test("G10-expired-restoration-cannot-fall-back-to-both-history-stops",async()=>{
  const nonce=remember();publish(nonce);const raw=JSON.parse(values.get(restore));raw.at=Date.now()-draft.GUEST_BOOKING_DRAFT_TTL_MS-1;values.set(restore,JSON.stringify(raw));
  const view=await mountedDetail({...historicStops("login-B"),guestBooking:true,guestBookingNonce:nonce});await view.settle();
  assert.equal(sheet(view).props.initialSelection,null);
  assert.equal(sheet(view).props.ride.matched_stop,undefined);assert.equal(sheet(view).props.ride.matched_dropoff_stop,undefined);
  view.unmount();await Promise.resolve();
});
for(const route of ["Сибай | Акъяр","Акъяр"])await test("G10-changed-fresh-route-rejects-entire-old-span-"+route,async()=>{
  const nonce=remember({...selection,minor:true,frontSeat:true});publish(nonce);
  const view=await mountedDetail({guestBooking:true,guestBookingNonce:nonce},async()=>({...freshRide,waypoints:route}));await view.settle();
  assert.equal(sheet(view).props.initialSelection,null,"old qty/precise points must not bind to a different span");
  assert.deepEqual(sheet(view).props.initialGuestOptions,{minor:true,frontSeat:true});
  assert.equal(sheet(view).props.initialRouteChanged,true);
  assert(!values.has(restore));view.unmount();await Promise.resolve();
});
await test("G10-normal-authenticated-search-keeps-own-both-stops",async()=>{
  h.generation="login-A";const view=await mountedDetail(historicStops("login-A"));await view.settle();
  assert.equal(sheet(view).props.ride.matched_stop,"Акъяр");assert.equal(sheet(view).props.ride.matched_dropoff_stop,"Сибай");
  view.unmount();await Promise.resolve();
});
await test("G10-mounted-account-switch-reloads-without-old-both-stops",async()=>{
  h.generation="login-A";const reads=[];
  const view=await mountedDetail(historicStops("login-A"),(_id,signal)=>new Promise(resolve=>reads.push({signal,resolve})));
  reads[0].resolve(freshRide);await view.settle();assert.equal(sheet(view).props.ride.matched_stop,"Акъяр");
  h.generation="login-B";view.render();assert.equal(sheet(view),undefined,"old owner's sheet is hidden before the next request resolves");
  assert.equal(reads.length,2);reads[1].resolve(freshRide);await view.settle();
  assert.equal(sheet(view).props.ride.matched_stop,undefined);assert.equal(sheet(view).props.ride.matched_dropoff_stop,undefined);
  assert.equal(sheet(view).props.initialSelection,null);
  view.unmount();await Promise.resolve();
});
await test("G10-late-A-response-cannot-overwrite-mounted-B",async()=>{
  h.generation="login-A";const reads=[];
  const view=await mountedDetail(historicStops("login-A"),(_id,signal)=>new Promise(resolve=>reads.push({signal,resolve})));
  h.generation="login-B";view.render();assert.equal(reads.length,2);assert.equal(reads[0].signal.aborted,true);
  reads[1].resolve({...freshRide,price:600});await view.settle();
  assert.equal(sheet(view).props.ride.price,600);assert.equal(sheet(view).props.ride.matched_stop,undefined);
  // Deliberately ignore AbortSignal at this boundary to exercise the owner/version guard too.
  reads[0].resolve({...freshRide,price:999,matched_stop:"Акъяр",matched_dropoff_stop:"Сибай"});await view.settle();
  assert.equal(sheet(view).props.ride.price,600);assert.equal(sheet(view).props.ride.matched_stop,undefined);
  assert.equal(sheet(view).props.ride.matched_dropoff_stop,undefined);assert.equal(sheet(view).props.initialSelection,null);
  view.unmount();await Promise.resolve();
});
console.log("Guest booking draft: "+(total-bad)+"/"+total+" passed");if(bad)process.exitCode=1;
