// Исполняем реальные компоненты; заменены только браузерные hooks, навигация и сеть.
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
// Шторка выносится в document.body (useSheetExit → exit.portal): браузера нет — хватит пустого body.
globalThis.document ??= { body: {} };
const h = globalThis.__ridePath = {
  states: [], refs: [], effects: [], si: 0, ri: 0, authed: true, routes: [], calls: [],
  reset() { this.states = []; this.refs = []; this.effects = []; this.si = this.ri = 0; this.routes = []; this.calls = []; },
};
const modules = {
  react: `const h=globalThis.__ridePath; export function useState(value){const i=h.si++; if(!(i in h.states))h.states[i]=typeof value==='function'?value():value; return [h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v]}; export function useRef(value){const i=h.ri++; return h.refs[i]??=( {current:value} )}; export const useEffect=(fn)=>{h.effects.push(fn)}; export const useCallback=fn=>fn;`,
  "react/jsx-runtime": `export const Fragment='fragment'; export const jsx=(type,props)=>({type,props:props??{}}); export const jsxs=jsx;`,
  // Шторка рисуется в <body> через портал (useSheetExit): в наборе портал отдаёт разметку как есть.
  "react-dom": `export const createPortal=(node)=>node;`,
  "react-router-dom": `export const Link='a'; export const useNavigate=()=> (...args)=>globalThis.__ridePath.routes.push(args); export const useParams=()=>({id:'42'}); export const useLocation=()=>({state:globalThis.__ridePath.locationState});`,
  lang: `export const useLang=()=>({lang:'ru',appText:(ru,ba)=>ru,t:k=>k});`,
  auth: `export const useAuth=()=>({isAuthed:globalThis.__ridePath.authed});`,
  bookings: `export const createBooking=body=>{globalThis.__ridePath.calls.push(body);return globalThis.__ridePath.booking(body)};`,
  rides: `export const fetchRide=(id,signal)=>globalThis.__ridePath.fetchRide(id,signal);`,
  client: `export class ApiError extends Error {} export const getSessionGeneration=()=>"same-session";`,
  ownBookings: `export const useOwnBooking=()=>null,useRefreshOwnBookings=()=>()=>{};`,
  features: `const flags={rides:true,taxi:true,courier:true,parcels:true,ads:true,business:true,ready:true};export const useFeatures=()=>flags,getFeatures=()=>flags;`,
  soon: `export default ()=>null;`,
  analytics: `export const track=()=>{};`,
  states: `export const LoadingList='loading'; export const ErrorState='retry';`,
  header: `export default 'header';`,
};
async function component(path) {
  const result = await build({entryPoints:[root+path], bundle:true, write:false, format:"esm", platform:"node", jsx:"automatic",
    plugins:[{name:"local-ui",setup(b){
      b.onResolve({filter:/.*/}, args=>{
        const p=args.path;
        const key=Object.hasOwn(modules,p)?p:p.endsWith('/i18n/lang')?'lang':p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/api/bookings')?'bookings':p.endsWith('/api/rides')?'rides':p.endsWith('/api/client')?'client':p.endsWith('/booking/OwnBookingsProvider')?'ownBookings':p.endsWith('/features')?'features':p.endsWith('/ComingSoon')?'soon':p.endsWith('/analytics')?'analytics':p.endsWith('/components/States')?'states':p.endsWith('/components/ScreenHeader')?'header':null;
        return key?{path:key,namespace:'mock'}:null;
      });
      b.onLoad({filter:/.*/,namespace:'mock'},args=>({contents:modules[args.path],loader:'js'}));
    }}]});
  return (await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'))).default;
}
const render=(Component,props)=>{h.si=h.ri=0;h.effects=[];return Component(props)};
function all(node){if(!node||typeof node!=='object')return [];return [node,...[node.props?.children].flat(Infinity).flatMap(all)]}
const button=tree=>all(tree).find(n=>n.type==='button'&&n.props.className?.includes('sheet__cta'));
const ride={id:42,driver_id:7,from_city:'Уфа',to_city:'Сибай',depart_at:'2030-01-01T10:00:00',seats_left:2,seats_total:3,price:500,driver_name:'Ринат',driver_rating:5,driver_car:'Lada',category:'regular'};
const Card=await component('src/components/RideCard.tsx');
assert.equal(render(Card,{ride,index:0,to:'/rides/42'}).props.to,'/rides/42','Карточка должна быть доступной ссылкой к поездке');
assert.match(readFileSync(root+'src/screens/RidesScreen.tsx','utf8'),/to=\{`\/rides\/\$\{ride.id\}`\}/,'Лента должна подключать переход');
assert.ok(existsSync(root+'src/screens/RideDetailScreen.tsx'),'Есть экран прямой ссылки');
assert.match(readFileSync(root+'src/App.tsx','utf8'),/path="\/rides\/:id"/,'Маршрут зарегистрирован');
const Sheet=await component('src/components/RideSheet.tsx');
h.reset(); h.authed=false;
await button(render(Sheet,{ride,onClose(){}})).props.onClick();
assert.deepEqual(h.routes,[['/login',{state:{from:'/rides/42'}}]],'После входа должна восстановиться выбранная поездка');
assert.equal(h.calls.length,0);
h.reset(); h.authed=true; let resolve;h.booking=()=>new Promise(r=>resolve=r);
let closed=0;let tree=render(Sheet,{ride,onClose(){closed++}});
const first=button(tree).props.onClick(); const second=button(tree).props.onClick();
assert.equal(h.calls.length,1,'Два быстрых тапа отправляют одну бронь');
assert.equal(button(render(Sheet,{ride,onClose(){closed++}})).props.disabled,true);
resolve({id:73});await Promise.all([first,second]);
assert.deepEqual(h.calls,[{ride_id:42,seats:1,minor_passenger:undefined,minor_guardian_name:undefined,minor_guardian_phone:undefined}]);
assert.equal(closed,1);assert.equal(h.routes[0][0],'/trip/73');
h.reset();h.booking=async()=>{throw new Error('offline')};
await button(render(Sheet,{ride,onClose(){}})).props.onClick();
assert.equal(h.routes.length,0,'При ошибке не открываем несуществующую бронь');
tree=render(Sheet,{ride,onClose(){}});
assert.ok(all(tree).some(n=>n.props.className==='auth__error'),'Ошибка видна');
assert.equal(button(tree).props.disabled,false,'После ошибки можно повторить');
h.booking=async()=>({id:74});await button(tree).props.onClick();assert.equal(h.routes[0][0],'/trip/74');
// Переднее место (2026-10-14): просьба из шторки уходит в бронь; без неё тело брони прежнее (выше).
const byName=(tree,name)=>all(tree).find(n=>n.type?.name===name);
assert.ok(byName(render(Card,{ride,index:0}),'SeatBadges'),'Карточка в ленте показывает места: переднее, «сзади двое»');
h.reset();h.booking=async()=>({id:75});
const choice=byName(render(Sheet,{ride,onClose(){}}),'FrontSeatChoice');
assert.ok(choice&&choice.props.checked===false,'Шторка предлагает переднее место, по умолчанию выключено');
choice.props.onChange(true);
tree=render(Sheet,{ride,onClose(){}});assert.equal(byName(tree,'FrontSeatChoice').props.checked,true);
await button(tree).props.onClick();
assert.equal(h.calls[0].front_seat,true,'Просьба о переднем месте доходит до сервера');
const Detail=await component('src/screens/RideDetailScreen.tsx');
h.reset();h.fetchRide=async id=>{assert.equal(id,42);return ride};
tree=render(Detail);assert.ok(all(tree).some(n=>n.type==='loading'));
h.effects[0]();await new Promise(setImmediate);
tree=render(Detail);assert.ok(all(tree).some(n=>n.props.ride?.id===42),'Прямая ссылка загружает выбранную поездку');
h.reset();h.fetchRide=async()=>{throw new Error('offline')};
render(Detail);h.effects[0]();await new Promise(setImmediate);tree=render(Detail);
const retry=all(tree).find(n=>n.type==='retry');assert.ok(retry,'Загрузка имеет видимую ошибку');
h.fetchRide=async()=>ride;retry.props.onRetry();await new Promise(setImmediate);
assert.ok(all(render(Detail)).some(n=>n.props.ride?.id===42),'Повтор восстанавливает детали');
for(const unavailable of [{...ride,seats_left:0},{...ride,status:'cancelled'}]){
  h.reset();h.fetchRide=async()=>unavailable;render(Detail);h.effects[0]();await new Promise(setImmediate);
  tree=render(Detail);assert.ok(!all(tree).some(n=>n.props.ride),'Закрытая/полная поездка не предлагает бронь');
  assert.ok(all(tree).some(n=>n.props.role==='status'));
}
h.reset();let deliver;h.fetchRide=()=>new Promise(r=>deliver=r);
render(Detail);const cleanup=h.effects[0]();cleanup();deliver(ride);await new Promise(setImmediate);
assert.equal(h.states[0],null,'Ответ после ухода со страницы не меняет выбор');
h.reset();h.fetchRide=async()=>{throw new Error('offline')};
render(Detail);const leave=h.effects[0]();await new Promise(setImmediate);
h.fetchRide=()=>new Promise(r=>deliver=r);
all(render(Detail)).find(n=>n.type==='retry').props.onRetry();leave();deliver(ride);await new Promise(setImmediate);
assert.equal(h.states[0],null,'Повтор без AbortSignal тоже не оживляет закрытый экран');
console.log('✓ Ссылка и маршрут; возврат после входа; одна бронь при двойном тапе; успех, ошибка и повтор; загрузка деталей и повтор; закрытые/полные поездки; уход до ответа');

// Место посадки: найденная остановка сохраняется при открытии подробностей; отсутствие подсказки
// не означает начало маршрута. На остановке начало можно выбрать только явно, по названию.
const withStops = { ...ride, waypoints: 'Акъяр | Сибай', to_city: 'Баймак' };
h.reset(); h.booking = async () => ({ id: 80 });
tree = render(Sheet, { ride: withStops, onClose() {} });
assert.equal(button(tree).props.disabled, true, 'Без явного выбора бронирование недоступно');
await button(tree).props.onClick();
assert.equal(h.calls.length, 0, 'Сохранённая кнопка тоже не отправит неопределённую посадку');
const pickupChoice = byName(tree, 'PickupStopChoice');
assert.equal(pickupChoice.props.picked, null);
const choices = all(pickupChoice.type(pickupChoice.props)).filter(n => n.type === 'input');
assert.deepEqual(choices.map(n => n.props.value), ['Уфа', 'Акъяр', 'Сибай']);
assert.ok(choices.every(n => !n.props.checked), 'Первый пункт не выбран автоматически');
choices[1].props.onChange();
tree = render(Sheet, { ride: withStops, onClose() {} });
assert.equal(button(tree).props.disabled, false);
await button(tree).props.onClick();
assert.equal(h.calls[0].pickup_stop, 'Акъяр');
h.reset(); h.booking = async () => ({ id: 81 });
tree = render(Sheet, { ride: withStops, onClose() {} });
byName(tree, 'PickupStopChoice').props.onPick('Уфа');
await button(render(Sheet, { ride: withStops, onClose() {} })).props.onClick();
assert.equal(h.calls[0].pickup_stop, 'Уфа', 'Начало отправляется названием, не пустой строкой');
const matchedRide = { ...withStops, matched_stop: 'Акъяр', matched_from_start: false };
h.reset(); h.booking = async () => ({ id: 82 });
tree = render(Sheet, { ride: matchedRide, onClose() {} });
assert.equal(byName(tree, 'PickupStopChoice').props.picked, 'Акъяр');
await button(tree).props.onClick();
assert.equal(h.calls[0].pickup_stop, 'Акъяр');
const state = render(Card, { ride: matchedRide, index: 0, to: '/rides/42' }).props.state;
h.reset(); h.locationState = state; h.fetchRide = async () => withStops;
render(Detail); h.effects[0](); await new Promise(setImmediate);
tree = render(Detail);
assert.equal(all(tree).find(n => n.props.ride)?.props.ride.matched_stop, 'Акъяр', 'GET без фильтра сохраняет найденную подсказку');
h.reset(); h.locationState = { rideMatch: { rideId: 999, matched_stop: 'Акъяр', matched_from_start: false } };
h.fetchRide = async () => withStops; render(Detail); h.effects[0](); await new Promise(setImmediate);
tree = render(Detail);
assert.equal(all(tree).find(n => n.props.ride)?.props.ride.matched_stop, undefined, 'Подсказка другой поездки не переносится');
h.reset(); h.authed = false; h.locationState = undefined;
await button(render(Sheet, { ride: matchedRide, onClose() {} })).props.onClick();
assert.deepEqual(h.routes[0][1].state.returnState, state, 'Переход на вход сохраняет найденную остановку');
console.log('✓ Посадка: нет умолчания, явные начало/остановка, защита отправки, найденная подсказка и переход на вход');
