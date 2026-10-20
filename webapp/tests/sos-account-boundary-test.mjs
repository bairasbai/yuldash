// Real SosScreen, controlled component/hooks runtime; no DOM, network, SMS or geolocation service.
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const h=globalThis.__sosBoundary={};
const realNow=Date.now;
Date.now=()=>h.now;
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}};
const mocks={
 react:`const h=globalThis.__sosBoundary;
 export function useState(v){const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],v=>{if(x.live)x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
 export function useRef(v){const x=h.current,i=x.i++;return x.values[i]??={current:v};}
 export function useCallback(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||deps.some((v,j)=>v!==p.deps[j]))x.values[i]={fn,deps};return x.values[i].fn;}
 export function useEffect(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||!deps||deps.some((v,j)=>v!==p.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn()});}}
 export const useMemo=fn=>fn();`,
 'react/jsx-runtime':`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`,
 'react-router-dom':`export const useNavigate=()=> (...args)=>globalThis.__sosBoundary.nav.push(args); export const useSearchParams=()=>[new URLSearchParams(globalThis.__sosBoundary.search??'')];`,
 lang:`export const useLang=()=>({appText:(ru,ba)=>globalThis.__sosBoundary.lang==='ba'?ba:ru,lang:globalThis.__sosBoundary.lang??'ru'});`,
 auth:`export const useAuth=()=>globalThis.__sosBoundary.auth;`,
 client:`export class ApiError extends Error {constructor(status){super('fixture');this.status=status;}} export const getSessionGeneration=()=>globalThis.__sosBoundary.generation;`,
 safety:`export const sendSos=body=>{const h=globalThis.__sosBoundary,r=h.deferred();h.sends.push({body,user:h.auth.user.id,...r});return r.promise;};`,
 header:`export const SubHeader=()=>null;`,
 icons:`export const IconPhone=()=>null,IconShield=()=>null,IconCheck=()=>null,IconWarn=()=>null,IconHospital=()=>null,IconHeart=()=>null,IconCar=()=>null,IconPin=()=>null,IconCopy=()=>null;`,
 analytics:`export const track=(...args)=>globalThis.__sosBoundary.tracks.push(args);`,
};
const result=await build({entryPoints:[root+'src/screens/SosScreen.tsx'],bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'boundary',setup(b){b.onResolve({filter:/.*/},a=>{const p=a.path,k=Object.hasOwn(mocks,p)?p:p.endsWith('/i18n/lang')?'lang':p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/api/client')?'client':p.endsWith('/api/safety')?'safety':p.endsWith('/ConsentsScreen')?'header':p.endsWith('/components/Icons')?'icons':p.endsWith('/analytics')?'analytics':null;return k?{path:k,namespace:'mock'}:null});b.onLoad({filter:/.*/,namespace:'mock'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
const {default:Screen}=await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
function render(){
 const seen=new Set();h.effects=[];
 function visit(n,path){if(Array.isArray(n))return n.map((v,i)=>visit(v,path+'.'+i));if(n==null||typeof n!=='object')return n;
 if(typeof n.type==='function'){const id=path+':'+(n.key??'');seen.add(id);let x=h.instances.get(id);if(!x||x.type!==n.type){x={type:n.type,values:[],cleanups:[],live:true};h.instances.set(id,x);}x.i=0;h.current=x;return visit(n.type(n.props),id+'.child');}
 return{...n,props:{...n.props,children:visit(n.props?.children,path+'.children')}};}
 const tree=visit({type:Screen,props:{}},'root');
 for(const [id,x]of h.instances)if(!seen.has(id)){x.live=false;x.cleanups.forEach(fn=>fn?.());h.instances.delete(id);}
 for(const fn of h.effects)fn();return tree;
}
function nodes(tree,predicate){if(Array.isArray(tree))return tree.flatMap(t=>nodes(t,predicate));if(!tree||typeof tree!=='object')return[];return [...(predicate(tree)?[tree]:[]),...nodes(tree.props?.children,predicate)];}
const byClass=(tree,c)=>nodes(tree,n=>n.props?.className?.split(' ').includes(c))[0];
const textarea=tree=>nodes(tree,n=>n.type==='textarea')[0];
const geoButton=tree=>nodes(byClass(tree,'sos-dictate__row'),n=>n.type==='button')[0];
const copyButton=tree=>nodes(tree,n=>n.type==='button'&&JSON.stringify(n.props.children).includes('Скопировать'))[0];
const point=(lat=54,lng=55,timestamp=h.now)=>({coords:{latitude:lat,longitude:lng},timestamp});
function unmount(){for(const x of h.instances?.values()??[]){x.live=false;x.cleanups.forEach(fn=>fn?.());}}
function reset({permissionState}={}){
 unmount();Object.assign(h,{instances:new Map(),effects:[],auth:{isAuthed:true,status:'authed',user:{id:101}},generation:'A',lang:'ru',search:'',nav:[],tracks:[],sends:[],geo:[],deferred,now:1800000000000,timers:new Map(),nextTimer:0,permission:null});
 globalThis.window={setTimeout:(fn,delay)=>{const id=++h.nextTimer;h.timers.set(id,{fn,at:h.now+delay});return id},clearTimeout:id=>h.timers.delete(id)};
 const navigator={geolocation:{getCurrentPosition:(ok,error)=>h.geo.push({ok,error})}};
 if(permissionState!==undefined){h.permission=new EventTarget();h.permission.state=permissionState;navigator.permissions={query:async()=>h.permission};}
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:navigator});render();
}
function advance(ms,{runTimers=true}={}){h.now+=ms;if(runTimers)for(const [id,timer]of [...h.timers])if(timer.at<=h.now){h.timers.delete(id);timer.fn();}}
function changePermission(state){h.permission.state=state;h.permission.dispatchEvent(new Event('change'));}
async function sendWithoutLocation(tree){assert.equal(nodes(tree,n=>n.props?.href==='tel:112').length,1,'112 stays available');const pending=byClass(tree,'btn-danger').props.onClick(),request=h.sends.at(-1);assert.ok(request,'SOS must send without waiting for GPS');request.resolve({});await pending;assert.equal(request.body.lat,undefined,'Unconfirmed location must not be sent');assert.equal(request.body.lng,undefined);}
function loginB(){h.auth={isAuthed:true,status:'authed',user:{id:202}};h.generation='B';return render();}
function enterA(){textarea(render()).props.onChange({target:{value:'Личный текст A'}});nodes(render(),n=>n.type==='button'&&n.key==='medical')[0].props.onClick();h.geo[0].ok(point());return render();}
let total=0,failed=0;
async function test(label,fn){total++;try{reset();await fn();console.log('PASS '+label)}catch(e){failed++;console.error('FAIL '+label+': '+e.message)}finally{unmount();}}
await test('account B cannot see or send A draft and coordinates',async()=>{enterA();const tree=loginB();assert.equal(textarea(tree)?.props.value,'','B must not inherit A note');const pending=byClass(tree,'btn-danger').props.onClick();const r=h.sends.at(-1);assert.equal(r.user,202);assert.equal(r.body.note,'');assert.equal(r.body.category,'other');assert.equal(r.body.lat,undefined);assert.equal(r.body.lng,undefined);r.resolve({});await pending;});
await test('late A success must not show sent screen or analytics for B',async()=>{enterA();const pending=byClass(render(),'btn-danger').props.onClick();const r=h.sends[0];loginB();r.resolve({});await pending;assert.ok(textarea(render()),'B must retain its own editable form');assert.deepEqual(h.tracks,[],'Old A continuation must not emit B analytics');});
await test('late A location callback must not supply coordinates to B',async()=>{const oldGeo=h.geo[0];loginB();oldGeo.ok(point());const pending=byClass(render(),'btn-danger').props.onClick();const request=h.sends.at(-1);assert.equal(request.body.lat,undefined,'Old GPS must not populate B');assert.equal(request.body.lng,undefined);request.resolve({});await pending;});
await test('same-session rerender preserves draft and ordinary send',async()=>{enterA();const tree=render();assert.equal(textarea(tree).props.value,'Личный текст A');const pending=byClass(tree,'btn-danger').props.onClick();const r=h.sends[0];assert.equal(r.body.category,'medical');assert.equal(r.body.lat,54);r.resolve({});await pending;assert.ok(JSON.stringify(render()).includes('Сигнал отправлен'),'Successful send must show confirmation');assert.equal(h.tracks.length,1);});
await test('merged SOS copies note and coordinates and rejects stale account callback',async()=>{
 const tree=enterA(),writes=[];globalThis.navigator.clipboard={writeText:async text=>writes.push(text)};
 const copy=nodes(tree,n=>n.type==='button'&&JSON.stringify(n.props.children).includes('Скопировать'))[0];assert.ok(copy);
 await copy.props.onClick();assert.deepEqual(writes,['Личный текст A\n54.00000, 55.00000']);
 loginB();await copy.props.onClick();assert.equal(writes.length,1,'Stale A must not copy after B login');
});
for(const lang of ['ru','ba'])for(const location of ['denied','unavailable'])await test(`${lang}: ${location} geolocation never promises transmitted coordinates`,async()=>{
 reset();h.lang=lang;h.search='?booking=73';
 if(location==='unavailable')delete globalThis.navigator.geolocation;
 render();if(location==='denied')h.geo.at(-1).error({code:1});
 const tree=render(),before=JSON.stringify(tree);
 assert.ok(!before.includes('с твоим местом и машиной')&&!before.includes('урыныңды һәм барған машинаңды'),'Missing location must not be promised before sending');
 const pending=byClass(tree,'btn-danger').props.onClick(),request=h.sends.at(-1);
 assert.equal(request.body.lat,undefined);assert.equal(request.body.lng,undefined);assert.equal(request.body.booking_id,73);
 request.resolve({hint_ru:'Сервер принял сигнал',hint_ba:'Сервер сигналды ҡабул итте'});await pending;
 const confirmation=JSON.stringify(byClass(render(),'sos-info--ok'));
 assert.ok(confirmation.includes(lang==='ba'?'Сигнал Юлдашта һаҡланды':'Сигнал сохранён в Юлдаш'));
 assert.ok(!confirmation.includes('координат'),'A successful signal without GPS must not claim coordinates were received');
 assert.ok(confirmation.includes(lang==='ba'?'Сервер сигналды ҡабул итте':'Сервер принял сигнал'),'Authoritative server hint stays visible');
 h.lang='ru';h.search='';
});
for(const outcome of ['denied','unavailable'])await test(`fresh location is removed on retry and ${outcome}, including retained handlers`,async()=>{
 const previous=enterA(),writes=[];globalThis.navigator.clipboard={writeText:async text=>writes.push(text)};
 if(outcome==='unavailable')delete globalThis.navigator.geolocation;
 geoButton(previous).props.onClick();
 const whileRetrying=byClass(render(),'sos-dictate__coords');
 if(outcome==='denied')h.geo.at(-1).error({code:1});
 const afterRetry=byClass(render(),'sos-dictate__coords');await copyButton(previous).props.onClick();
 const pending=byClass(previous,'btn-danger').props.onClick(),request=h.sends.at(-1);request.resolve({});await pending;
 assert.deepEqual({whileRetrying,afterRetry,writes,lat:request.body.lat,lng:request.body.lng},{whileRetrying:undefined,afterRetry:undefined,writes:['Личный текст A'],lat:undefined,lng:undefined},'Retry/rejection must remove old location from UI, clipboard and SOS');
});
await test('location expires from the UI at sixty seconds',async()=>{
 enterA();advance(59999);assert.ok(byClass(render(),'sos-dictate__coords'),'Confirmed fix stays available during its lifetime');
 advance(1);assert.equal(byClass(render(),'sos-dictate__coords'),undefined,'Timer must remove the expired fix');
});
for(const action of ['copy','send'])await test(`${action} rechecks age without a render or timer callback`,async()=>{
 const previous=enterA(),writes=[];globalThis.navigator.clipboard={writeText:async text=>writes.push(text)};
 advance(60000,{runTimers:false});
 if(action==='copy'){await copyButton(previous).props.onClick();assert.deepEqual(writes,['Личный текст A'],'Expired coordinates must not enter the clipboard');}
 else await sendWithoutLocation(previous);
});
await test('late callbacks from an earlier location request cannot replace a new fix',async()=>{
 const old=h.geo[0];old.error({code:2});geoButton(render()).props.onClick();h.geo.at(-1).ok(point(56,57));
 old.ok(point());old.error({code:1});
 assert.ok(JSON.stringify(byClass(render(),'sos-dictate__coords')).includes('56.00000, 57.00000'),'Newest request owns the fix');
 const pending=byClass(render(),'btn-danger').props.onClick(),request=h.sends.at(-1);assert.equal(request.body.lat,56);assert.equal(request.body.lng,57);request.resolve({});await pending;
});
for(const timestamp of ['expired','future','missing'])await test(`${timestamp} provider timestamp is rejected`,async()=>{
 const fix=point(54,55,timestamp==='expired'?h.now-60000:h.now+1);if(timestamp==='missing')delete fix.timestamp;h.geo[0].ok(fix);
 assert.equal(byClass(render(),'sos-dictate__coords'),undefined,'Only a provider fix with a fresh timestamp can be shown');await sendWithoutLocation(render());
});
for(const state of ['denied','prompt'])await test(`permission changes to ${state} clear UI and retained copy/send handlers`,async()=>{
 reset({permissionState:'granted'});await Promise.resolve();const previous=enterA(),writes=[];globalThis.navigator.clipboard={writeText:async text=>writes.push(text)};
 changePermission(state);await copyButton(previous).props.onClick();
 assert.deepEqual(writes,['Личный текст A'],'Revoked permission must clear the clipboard snapshot');
 assert.equal(byClass(render(),'sos-dictate__coords'),undefined,'Permission revocation must clear displayed location');
 await sendWithoutLocation(previous);
});
await test('permission revocation invalidates an in-flight fix; a later granted request works',async()=>{
 reset({permissionState:'granted'});await Promise.resolve();const old=h.geo[0];changePermission('denied');old.ok(point());
 assert.equal(byClass(render(),'sos-dictate__coords'),undefined,'Late fix cannot survive revocation');
 changePermission('granted');geoButton(render()).props.onClick();h.geo.at(-1).ok(point(56,57));
 assert.ok(JSON.stringify(byClass(render(),'sos-dictate__coords')).includes('56.00000, 57.00000'),'Newly permitted request must recover');
});
await test('initial permission prompt can grant the original location request',async()=>{
 reset({permissionState:'prompt'});await Promise.resolve();changePermission('granted');h.geo[0].ok(point());
 assert.ok(JSON.stringify(byClass(render(),'sos-dictate__coords')).includes('54.00000, 55.00000'),'First consent must not require another request');
});
await test('an unsupported Permissions query does not block confirmed Geolocation',async()=>{
 unmount();h.instances=new Map();globalThis.navigator.permissions={query:async()=>{throw new Error('unsupported')}};render();await Promise.resolve();await Promise.resolve();h.geo.at(-1).ok(point());
 assert.ok(JSON.stringify(byClass(render(),'sos-dictate__coords')).includes('54.00000, 55.00000'),'Successful GPS works when Permissions API cannot answer');
});
await test('unmounted form ignores pending location callbacks and removes its expiry timer',async()=>{
 const old=h.geo[0];enterA();unmount();assert.equal(h.timers.size,0,'Unmount must clear its location expiry timer');
 const before=[...h.instances.values()].map(x=>JSON.stringify(x.values));old.ok(point(56,57));old.error({code:1});
 assert.deepEqual([...h.instances.values()].map(x=>JSON.stringify(x.values)),before,'Unmounted callbacks must not mutate refs or state');
});
Date.now=realNow;
console.log(`SOS account boundary: ${total-failed} passed, ${failed} failed`);if(failed)process.exitCode=1;
