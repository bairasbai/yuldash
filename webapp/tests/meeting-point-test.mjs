// RS-03 А: точное место встречи/высадки на сайте — проводка от пикеров до тела брони
// (зеркало backend/tests/test_meeting_points.py и Android MeetingPointTest).
// Пусто — тела брони прежние; ориентир/булавка — meeting/alighting уходят; clear — не шлём.
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
globalThis.document ??= { body: {} };
const h = globalThis.__meetPoint = {
  states: [], refs: [], effects: [], si: 0, ri: 0, calls: [],
  reset() { this.states = []; this.refs = []; this.effects = []; this.si = this.ri = 0; this.calls = []; },
};
const modules = {
  react: `const h=globalThis.__meetPoint; export function useState(value){const i=h.si++; if(!(i in h.states))h.states[i]=typeof value==='function'?value():value; return [h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v]}; export function useRef(value){const i=h.ri++; return h.refs[i]??=( {current:value} )}; export const useEffect=(fn)=>{h.effects.push(fn)}; export const useCallback=fn=>fn;`,
  "react/jsx-runtime": `export const Fragment='fragment'; export const jsx=(type,props)=>({type,props:props??{}}); export const jsxs=jsx;`,
  "react-dom": `export const createPortal=(node)=>node;`,
  "react-router-dom": `export const Link='a'; export const useNavigate=()=> ()=>{}; export const useParams=()=>({id:'42'});`,
  lang: `export const useLang=()=>({lang:'ru',appText:(ru,ba)=>ru,t:k=>k});`,
  auth: `export const useAuth=()=>({isAuthed:true});`,
  bookings: `export const createBooking=body=>{globalThis.__meetPoint.calls.push(body);return Promise.resolve({id:73,status:'pending'})};`,
  rides: `export const fetchRide=async(id)=>null;`,
  client: `export class ApiError extends Error {} export const getSessionGeneration=()=>"same-session";`,
  ownBookings: `export const useOwnBooking=()=>null,useRefreshOwnBookings=()=>()=>{};`,
  features: `const flags={rides:true,taxi:true,courier:true,parcels:true,ads:true,business:true,ready:true};export const useFeatures=()=>flags,getFeatures=()=>flags;`,
  soon: `export default ()=>null;`,
  analytics: `export const track=()=>{};`,
  states: `export const LoadingList='loading'; export const ErrorState='retry';`,
  header: `export default 'header';`,
  ymap: `export default 'ymap';`,
  geo: `export const fetchPickupPoints=async()=>[{id:7,city:'Уфа',title_ru:'у мечети',title_ba:'мәсет янында',lat:54.7,lng:55.9,usage_count:3}]; export const fetchDistricts=async()=>[]; export const fetchPopularRoutes=async()=>[]; export const fetchSettlements=async()=>[];`,
};
const root = fileURLToPath(new URL("../", import.meta.url));
async function component(path) {
  const result = await build({entryPoints:[root+path], bundle:true, write:false, format:"esm", platform:"node", jsx:"automatic", logLevel:"silent",
    plugins:[{name:"local-ui",setup(b){
      b.onResolve({filter:/.*/}, args=>{
        const p=args.path;
        const key=Object.hasOwn(modules,p)?p:p.endsWith('/i18n/lang')?'lang':p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/api/bookings')?'bookings':p.endsWith('/api/rides')?'rides':p.endsWith('/api/geo')?'geo':p.endsWith('/api/client')?'client':p.endsWith('/booking/OwnBookingsProvider')?'ownBookings':p.endsWith('/features')?'features':p.endsWith('/ComingSoon')?'soon':p.endsWith('/analytics')?'analytics':p.endsWith('/components/States')?'states':p.endsWith('YandexMap')?'ymap':p.endsWith('/components/ScreenHeader')?'header':null;
        return key?{path:key,namespace:'mock'}:null;
      });
      b.onLoad({filter:/.*/,namespace:'mock'},args=>({contents:modules[args.path],loader:'js'}));
    }}]});
  return (await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'))).default;
}
const render=(Component,props)=>{h.si=h.ri=0;h.effects=[];return Component(props)};
function all(node){if(!node||typeof node!=='object')return [];return [node,...[node.props?.children].flat(Infinity).flatMap(all)]}
const byName=(tree,name)=>all(tree).filter(n=>n.type?.name===name);
const button=tree=>all(tree).find(n=>n.type==='button'&&n.props.className?.includes('sheet__cta'));

const Sheet=await component('src/components/RideSheet.tsx');
const ride={id:42,driver_id:7,from_city:'Уфа',to_city:'Сибай',depart_at:'2030-01-01T10:00:00',seats_left:3,seats_total:3,price:500,driver_name:'Ринат',driver_rating:5,driver_car:'Lada',category:'regular'};
const book=async()=>{ await button(render(Sheet,{ride,onClose(){}})).props.onClick(); };

let failures=0;
const test=async(name,fn)=>{ try{ await fn(); console.log('✓',name); }catch(e){ failures++; console.log('✗',name,'\n ',e.message); } };

await test('без точек тело брони прежнее — meeting/alighting нет', async () => {
  h.reset();
  await book();
  assert.ok(!('meeting' in h.calls[0]) && !('alighting' in h.calls[0]), JSON.stringify(h.calls[0]));
});

await test('ориентир + пояснение уходят как meeting, высадка не задана — alighting нет', async () => {
  h.reset();
  const tree=render(Sheet,{ride,onClose(){}});
  const pickers=byName(tree,'MeetingPointPicker');
  assert.equal(pickers.length,2,'два пикера: посадка и высадка');
  pickers[0].props.onChange({landmark_id:7,note:'синие ворота'});
  await button(render(Sheet,{ride,onClose(){}})).props.onClick();
  assert.deepEqual(h.calls[0].meeting,{landmark_id:7,note:'синие ворота'});
  assert.ok(!('alighting' in h.calls[0]));
});

await test('булавка уходит как alighting с координатами', async () => {
  h.reset();
  const tree=render(Sheet,{ride,onClose(){}});
  const pickers=byName(tree,'MeetingPointPicker');
  pickers[1].props.onChange({lat:54.7,lng:55.9,note:'у школы'});
  await button(render(Sheet,{ride,onClose(){}})).props.onClick();
  assert.deepEqual(h.calls[0].alighting,{lat:54.7,lng:55.9,note:'у школы'});
  assert.ok(!('meeting' in h.calls[0]));
});

await test('clear — ключ не шлём вовсе', async () => {
  h.reset();
  const tree=render(Sheet,{ride,onClose(){}});
  byName(tree,'MeetingPointPicker')[0].props.onChange({clear:true});
  await button(render(Sheet,{ride,onClose(){}})).props.onClick();
  assert.ok(!('meeting' in h.calls[0]));
});

if (failures) { console.log(`\nПРОВАЛЕНО: ${failures}`); process.exit(1); }
console.log('\nВСЁ СОШЛОСЬ');
