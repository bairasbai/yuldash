// Production CreateRide/RideSheet: saved draft and retained click, API replaced only by spies.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
const entries = ["../src/screens/CreateRideScreen.tsx", "../src/components/RideSheet.tsx"].map(p=>fileURLToPath(new URL(p,import.meta.url)));
const mocks = {};
for (const entry of entries) {
  const source = await readFile(entry,"utf8");
  for (const [,clause,path] of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)) {
    const names=(clause.match(/\{([\s\S]*?)\}/)?.[1]??"").split(",").map(n=>n.trim()).filter(n=>n&&!n.startsWith("type "));
    mocks[path]="export default function Stub(){return null;}\n"+names.map(n=>`export const ${n}=()=>null;`).join("\n");
  }
}
mocks.react=`const h=()=>globalThis.__parcelRide;export const useState=v=>{const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof v==='function'?v():v;return[s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;s.dirty=true;}];};export const useRef=v=>{const s=h(),i=s.cursor++;return s.slots[i]??={current:v};};export const useEffect=()=>{};`;
mocks["react/jsx-runtime"]="export const Fragment='fragment';export const jsx=(type,props)=>({type,props});export const jsxs=jsx;";
mocks["react-router-dom"]="export const useNavigate=()=> (...args)=>globalThis.__parcelRide.routes.push(args);";
mocks["../i18n/lang"]="export const useLang=()=>({lang:globalThis.__parcelRide.lang,appText:(ru,ba)=>globalThis.__parcelRide.lang==='ba'?ba:ru});";
mocks["../auth/AuthProvider"]="export const useAuth=()=>({isAuthed:true});";
mocks["../features"]="export const getFeatures=()=>({parcels:globalThis.__parcelRide.parcels,ready:true});export const useFeatures=getFeatures;";
mocks["../utils/formDraft"]="export const clearDraft=()=>{};export const useDraftSync=(key,state,apply)=>{const h=globalThis.__parcelRide;if(!h.hydrated){h.hydrated=true;apply(h.draft);}};";
mocks["../api/driver"]="export const fetchPriceHint=async()=>({});export const publishRide=async body=>{globalThis.__parcelRide.published.push(body);return{id:42};};";
mocks["../api/bookings"]="export const createBooking=async body=>{globalThis.__parcelRide.booked.push(body);return{id:73};};";
mocks["../api/client"]="export class ApiError extends Error{}export const getSessionGeneration=()=> 'same-owner';";
mocks["../booking/OwnBookingsProvider"]="export const useOwnBooking=()=>globalThis.__parcelRide.ownBooking??null;export const useRefreshOwnBookings=()=>()=>{};";
mocks["../utils/format"]="export const formatWhen=()=>'';export const priceLabel=()=>'';export const rubLabel=()=>'';";
mocks["../components/WeatherWarningCard"]="export default function Weather(){return null;}export const useRouteWeather=()=>null;";
mocks["../utils/seatOptions"]="export const SEATS_DEFAULT=4;export const clampSeats=n=>n;export const seatCap=()=>4;";
mocks["../components/ComingSoon"]="export default function ComingSoon(){return null;}";
mocks["./ComingSoon"]=mocks["../components/ComingSoon"];
// Уход шторки — настоящий (useSheetExit): ему хватает useState/useRef/useEffect подменённого React.
delete mocks["./useSheetExit"];
async function compile(entry){const out=await build({entryPoints:[entry],bundle:true,write:false,format:"esm",platform:"node",jsx:"automatic",plugins:[{name:"parcel-fixtures",setup(b){b.onResolve({filter:/.*/},a=>a.path in mocks?{path:a.path,namespace:"fixture"}:undefined);b.onLoad({filter:/.*/,namespace:"fixture"},a=>({contents:mocks[a.path],loader:"js"}));}}]});return(await import("data:text/javascript;base64,"+Buffer.from(out.outputFiles[0].text).toString("base64"))).default;}
const [Create,Sheet]=await Promise.all(entries.map(compile));
function fresh(parcels,category,lang="ru"){return globalThis.__parcelRide={parcels,lang,cursor:0,slots:[],dirty:true,hydrated:false,published:[],booked:[],routes:[],draft:{category,from:"Уфа",to:"Сибай",when:"2030-01-01T10:00",price:"300"}};}
function nodes(n){return !n||typeof n!=="object"?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function text(n){return typeof n==="string"?n:!n||typeof n!=="object"?"": [n.props?.children].flat(Infinity).map(text).join(" ");}
function render(h,C,props){let tree;for(let i=0;i<10;i++){h.dirty=false;h.cursor=0;tree=C(props);if(!h.dirty)return tree;}throw Error("render did not settle");}
const soon=tree=>nodes(tree).some(n=>n.type?.name==="ComingSoon"&&n.props.kind==="delivery");
const submit=tree=>nodes(tree).find(n=>n.type==="button"&&text(n)==="Опубликовать");
const book=tree=>nodes(tree).find(n=>n.type==="button"&&n.props.className?.includes("sheet__cta"));
let total=0;
for(const category of ["parcel","cargo"]){
  for(const lang of ["ru","ba"]){const h=fresh(false,category,lang);assert.ok(soon(render(h,Create)),`saved ${category}/${lang} is Soon`);assert.equal(h.published.length,0);total++;}
  {const h=fresh(true,category),tree=render(h,Create);const held=submit(tree)?.props.onClick;assert.ok(held);h.parcels=false;await held();assert.equal(h.published.length,0,"OFF before React render blocks saved submit");assert.ok(soon(render(h,Create)));total++;}
  for(const lang of ["ru","ba"]){const h=fresh(false,category,lang),ride={id:42,category};assert.ok(soon(render(h,Sheet,{ride,onClose(){}})));assert.equal(h.booked.length,0);total++;}
  {const h=fresh(true,category),ride={id:42,category},held=book(render(h,Sheet,{ride,onClose(){}}))?.props.onClick;assert.ok(held);h.parcels=false;await held();assert.equal(h.booked.length,0,"OFF before React render blocks saved book");assert.ok(soon(render(h,Sheet,{ride,onClose(){}})));total++;}
}
for(const category of ["regular","urgent","hospital"]){
  const h=fresh(false,category);let tree=render(h,Create);assert.ok(!soon(tree));assert.ok(submit(tree));await submit(tree).props.onClick();assert.equal(h.published[0].category,category);total++;
  const s=fresh(false,category),ride={id:42,category};tree=render(s,Sheet,{ride,onClose(){}});assert.ok(!soon(tree));await book(tree).props.onClick();assert.equal(s.booked.length,1);assert.equal(s.routes[0][0],"/trip/73");total++;
}
for(const category of ["parcel","cargo"])for(const status of ["pending","confirmed","onboard"])for(const lang of ["ru","ba"]){
  const h=fresh(false,category,lang),ride={id:42,category};
  h.ownBooking={id:89,ride_id:42,status,front_seat:true};
  let closed=0;const tree=render(h,Sheet,{ride,onClose(){closed++;}});
  assert.ok(!soon(tree),`existing ${category}/${status}/${lang} stays accessible when OFF`);
  assert.equal(text(book(tree)),lang==="ru"?"Открыть поездку":"Сәфәрҙе асыу");
  await book(tree).props.onClick();
  assert.equal(closed,1);
  assert.deepEqual(h.booked,[],"Opening an existing booking must not POST a new one");
  assert.equal(h.routes[0][0],status==="pending"?"/booking/89":"/trip/89");
  total++;
}
console.log(`Parcel ride gates: ${total}/${total} passed`);
