// Исполняем экран и его настоящий обработчик нажатия; сеть и React заменены локально.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const h = globalThis.__notificationRoutes = { states: [], si: 0, effects: [], routes: [], read: [] };
const mocks = {
  react: `const h=globalThis.__notificationRoutes; export function useState(v){const i=h.si++;if(!(i in h.states))h.states[i]=v;return [h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v]};export const useEffect=fn=>h.effects.push(fn);export const useCallback=fn=>fn;export const useMemo=fn=>fn();`,
  'react/jsx-runtime': `export const Fragment='fragment';export const jsx=(type,props)=>({type,props:props??{}});export const jsxs=jsx;`,
  'react-router-dom': `export const useNavigate=()=>path=>globalThis.__notificationRoutes.routes.push(path);`,
  lang: `export const useLang=()=>({lang:'ru',appText:ru=>ru});`,
  notifications: `const h=globalThis.__notificationRoutes;export const fetchNotifications=async()=>({items:[h.note],unread:1});export const markRead=async id=>h.read.push(id);export const markAllRead=async()=>{};`,
  format: `export const formatRelative=()=>'';`,
  header: `export const SubHeader='header';`,
  states: `export const LoadingList='loading';`,
  icons: `export const IconChat='i',IconCar='i',IconRoute='i',IconBell='i',IconWarn='i';`,
};
const result = await build({entryPoints:[root+'src/screens/NotificationsScreen.tsx'],bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'local-notifications',setup(b){
  b.onResolve({filter:/.*/},a=>{const p=a.path;const k=Object.hasOwn(mocks,p)?p:p.endsWith('/i18n/lang')?'lang':p.endsWith('/api/notifications')?'notifications':p.endsWith('/utils/format')?'format':p.endsWith('/ConsentsScreen')?'header':p.endsWith('/components/States')?'states':p.endsWith('/components/Icons')?'icons':null;return k?{path:k,namespace:'mock'}:null;});
  b.onLoad({filter:/.*/,namespace:'mock'},a=>({contents:mocks[a.path],loader:'js'}));
}}]});
const Screen=(await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'))).default;
function render(){h.si=0;h.effects=[];return Screen();}
function all(n){return n&&typeof n==='object'?[n,...[n.props?.children].flat(Infinity).flatMap(all)]:[];}
let failed=0;
for(const [label,type,ref_kind,ref_id,path] of [
  ['Новая заявка водителю','request_watch','request_watch',42,'/requests-feed'],
  ['Старое уведомление водителю','request_watch','request',42,'/requests-feed'],
  ['Отклик автору заявки','request','request',42,'/requests/42/responses'],
  ['Подписка пассажира на поездку','route_watch','ride',42,'/rides/42'],
  ['Бронь пассажира','booking','booking',42,'/booking/42'],
  ['Оценка завершённой поездки','ride','booking_done',42,'/trip/42'],
]) {
  h.states=[];h.routes=[];h.read=[];h.note={id:7,type,ref_kind,ref_id,read:false,title_ru:label,title_ba:label,body_ru:'',body_ba:'',created_at:'2026-09-12T10:00:00Z'};
  render();h.effects[0]();await new Promise(setImmediate);
  const row=all(render()).find(n=>n.type==='button'&&n.props.className?.startsWith('notif-row'));
  await row.props.onClick();
  try{assert.deepEqual(h.routes,[path]);assert.deepEqual(h.read,[7]);console.log('✓ '+label);}catch(e){failed++;console.error(label+': '+e.message);}
}
if(failed)process.exitCode=1;
