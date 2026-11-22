// Execute the production components (controlled hooks, not a React mount) with controlled API/GPS and hooks.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const entry=fileURLToPath(new URL('../src/screens/InstantOrderScreen.tsx',import.meta.url));
const scheduledEntry=fileURLToPath(new URL('../src/screens/ScheduledOrdersScreen.tsx',import.meta.url));
const source=(await readFile(entry,'utf8'))+'\n'+(await readFile(scheduledEntry,'utf8')), mocks={};
for(const [,clause,path] of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)){
 const names=(clause.match(/\{([\s\S]*?)\}/)?.[1]??'').split(',').map(x=>x.trim()).filter(x=>x&&!x.startsWith('type '));
 mocks[path]??='export default function Stub(){return null;}\n';
 for(const n of names)if(!mocks[path].includes(`export const ${n}=`))mocks[path]+=`export const ${n}=()=>null;\n`;
}
delete mocks['../utils/scheduledTaxiContext'];
delete mocks['../utils/scheduledActivation'];
mocks['../api/taxiConsent']=`export const localTaxiConsentContext=()=>globalThis.__taxiBoot.consentContext;export const localTaxiConsentGeneration=()=>'';`;
mocks.react=`const h=()=>globalThis.__taxiBoot;
export const useState=initial=>{const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;return[s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;s.dirty=true;}];};
export const useRef=v=>{const s=h(),i=s.cursor++;return s.slots[i]??={current:v};};
const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
export const useCallback=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps))s.slots[i]={fn,deps};return s.slots[i].fn;};
export const useEffect=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps)){s.effects.push(()=>{old?.cleanup?.();s.slots[i]={deps,cleanup:fn()};});}};`;
mocks['react/jsx-runtime']=`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key});export const jsxs=jsx;`;
mocks['../i18n/lang']=`export const useLang=()=>({lang:'ru',appText:globalThis.__taxiBoot.appText});`;
mocks['../features']=`export const useFeatures=()=>({taxi:globalThis.__taxiBoot.taxi!==false,ready:true});`;
mocks['react-router-dom']=`export const useLocation=()=>globalThis.__taxiBoot.location;export const useNavigate=()=> (...args)=>{const h=globalThis.__taxiBoot;h.navigations.push(args);h.location={key:String(++h.routeKey),state:args[1]?.state??null};h.dirty=true;};`;
mocks['../api/client']=`export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}
export const getAccountSession=()=>globalThis.__taxiBoot.owner;export const isAccountSession=o=>{const x=globalThis.__taxiBoot.owner;return !!x&&o.userId===x.userId&&o.generation===x.generation;};`;
mocks['../api/instant']+=`
export const fetchInstantOrder=(id,signal)=>{const h=globalThis.__taxiBoot;h.detailCalls.push({id,signal});return h.details(id);};
export const fetchScheduled=()=>Promise.resolve({scheduled:[{id:17,role:'passenger',status:'scheduled',scheduled_at:null,from_text:'A',to_text:'B',price_estimate:0}],activated:[]});
export const activateScheduled=(id)=>globalThis.__taxiBoot.activate(id);
export const fetchMyOrders=(...args)=>{const h=globalThis.__taxiBoot;h.orderCalls++;return h.orders();};
export const fetchTaxiAvailability=()=>{const h=globalThis.__taxiBoot;h.availabilityCalls++;return h.availability();};`;
// Replace the placeholder exports for controlled APIs, leaving other imports as fixtures.
for(const name of ['fetchInstantOrder','fetchScheduled','activateScheduled'])mocks['../api/instant']=mocks['../api/instant'].replace(`export const ${name}=()=>null;`,'');
mocks['../api/instant']=mocks['../api/instant'].replace('export const fetchMyOrders=()=>null;','').replace('export const fetchTaxiAvailability=()=>null;','').replace('export const ACTIVE_PASSENGER_STATUSES=()=>null;',`export const ACTIVE_PASSENGER_STATUSES=['searching','accepted','arriving','onboard'];`);
mocks['../api/discovery']=`export const reverseGeocode=()=>globalThis.__taxiBoot.address.promise;export const geocode=()=>null;`;
async function compile(target){const result=await build({entryPoints:[target],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'taxi-fixtures',setup(b){b.onResolve({filter:/.*/},a=>a.path in mocks?{path:a.path,namespace:'fixture'}:undefined);b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
return (await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'))).default;}
const Screen=await compile(entry),ScheduledScreen=await compile(scheduledEntry);
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};}
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function screen(h,name){return nodes(h.tree).find(n=>typeof n.type==='function'&&n.type.name===name);}
function text(n){return typeof n==='string'?n:!n||typeof n!=='object'?'':[n.props?.children].flat(Infinity).map(text).join(' ');}
async function settle(h){for(let i=0;i<25;i++){if(h.dirty){h.dirty=false;h.cursor=0;h.effects=[];h.tree=(h.scheduled?ScheduledScreen:Screen)();if(h.tree.type?.name==='PassengerTaxiOrder'){if(h.innerKey!==h.tree.key){for(const x of h.slots)x?.cleanup?.();h.slots=[];h.cursor=0;h.effects=[];h.innerKey=h.tree.key;}h.tree=h.tree.type(h.tree.props);}for(const effect of h.effects)effect();}await Promise.resolve();}assert.equal(h.dirty,false);}
let total=0,failed=0;
async function check(name,fn){total++;const h=globalThis.__taxiBoot={slots:[],cursor:0,effects:[],dirty:true,appText:ru=>ru,address:deferred(),orderCalls:0,availabilityCalls:0,orders:async()=>[],availability:async()=>({enabled:true}),gps:null,owner:{userId:11,generation:'A'},location:{key:'initial',state:null},routeKey:0,navigations:[],detailCalls:[],details:async id=>({id,role:'passenger',status:'expired'}),activate:async id=>({id,role:'passenger',status:'expired'})};
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{geolocation:{getCurrentPosition:(yes,no)=>{h.gps={yes,no};}}}});globalThis.window={setInterval:fn=>{h.tick=fn;return 1;},clearInterval:()=>{}};
 try{await fn(h);console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+'\n'+e.stack);}}
async function start(h,geo=false){await settle(h);geo?h.gps.yes({coords:{latitude:54,longitude:55}}):h.gps.no();await settle(h);}
await check('orders outage does not offer a duplicate new order',async h=>{h.orders=async()=>{throw Error('offline');};await start(h);assert.equal(screen(h,'ComposeView'),undefined);assert.match(text(h.tree),/Не получилось/);assert.equal(h.availabilityCalls,0);});
await check('availability outage is a retryable error, not a closed city',async h=>{h.availability=async()=>{throw Error('offline');};await start(h);assert.doesNotMatch(text(h.tree),/Такси скоро/);assert.match(text(h.tree),/Не получилось/);});
await check('retry restores the existing trip instead of creating another',async h=>{h.orders=async()=>{throw Error('offline');};await start(h);h.orders=async()=>[{id:42,status:'accepted'}];const retry=nodes(h.tree).find(n=>n.type==='button'&&text(n)==='Повторить');assert.ok(retry);retry.props.onClick();await settle(h);h.gps.no();await settle(h);assert.equal(screen(h,'TrackingView')?.props.order.id,42);assert.equal(h.availabilityCalls,0);});
await check('an active order is restored before availability',async h=>{h.orders=async()=>[{id:42,status:'onboard'}];await start(h);assert.equal(screen(h,'TrackingView')?.props.order.id,42);assert.equal(h.availabilityCalls,0);});
await check('explicitly disabled city keeps the waitlist gate',async h=>{h.availability=async()=>({enabled:false,message:{ru:'Город пока не подключён',ba:'test'}});await start(h);assert.match(text(h.tree),/Такси скоро/);});
await check('empty orders and enabled city allow composing',async h=>{await start(h);assert.ok(screen(h,'ComposeView'));});
await check('geocoder updates the unchanged GPS pickup',async h=>{await start(h,true);h.address.resolve({title:'Адрес GPS'});await settle(h);assert.deepEqual(screen(h,'ComposeView').props.from,{lat:54,lng:55,text:'Адрес GPS'});});
await check('late geocoder cannot replace manually chosen pickup',async h=>{await start(h,true);const chosen={lat:56,lng:57,text:'Другой адрес'};screen(h,'ComposeView').props.setFrom(chosen);await settle(h);h.address.resolve({title:'Старый GPS-адрес'});await settle(h);assert.deepEqual(screen(h,'ComposeView').props.from,chosen);});
await check('global off closes a previously enabled city form',async h=>{await start(h);assert.ok(screen(h,'ComposeView'));h.taxi=false;h.dirty=true;await settle(h);assert.equal(screen(h,'ComposeView'),undefined);assert.match(text(h.tree),/Такси скоро/);});
await check('global off keeps the active trip visible',async h=>{h.taxi=false;h.orders=async()=>[{id:42,status:'onboard'}];await start(h);assert.equal(screen(h,'TrackingView')?.props.order.id,42);});


await check('invalid local pilot cannot read GPS or contact taxi API',async h=>{h.consentContext={requested:true,environment:null};await settle(h);assert.equal(h.gps,null);assert.equal(h.orderCalls,0);assert.equal(h.availabilityCalls,0);assert.match(text(h.tree),/Локальный режим/);});
const hint=(id=17,userId=11,generation='A')=>({scheduledTaxi:{id,userId,generation}});
const track=h=>screen(h,'TrackingView');
function unmount(h){for(const x of h.slots)x?.cleanup?.();h.dirty=false;}
await check('explicit same-owner expired GET keeps the selected no-driver attempt',async h=>{h.location.state=hint();await settle(h);assert.equal(track(h)?.props.order.id,17);assert.deepEqual(h.detailCalls.map(x=>x.id),[17]);assert.equal(h.orderCalls,0);});
await check('ordinary history does not restore unqueued expired orders',async h=>{h.orders=async()=>[{id:17,role:'passenger',status:'expired',wait_until:null}];await start(h);assert.ok(screen(h,'ComposeView'));assert.equal(h.detailCalls.length,0);});
for(const state of [hint(17,22),hint(17,11,'old'),hint(-1),hint(1.5)])await check('foreign/stale/malformed navigation hint is not authority '+JSON.stringify(state),async h=>{h.location.state=state;await start(h);assert.equal(h.detailCalls.length,0);assert.ok(screen(h,'ComposeView'));});
for(const value of [{id:18,role:'passenger',status:'expired'},{id:17,role:'driver',status:'expired'},{id:17,role:'passenger',status:'scheduled'},{id:17,role:'passenger',status:'future-status'},null])await check('unconfirmed requested details never enter trip UI '+JSON.stringify(value),async h=>{h.location.state=hint();h.details=async()=>value;await settle(h);assert.equal(track(h),undefined);assert.match(text(h.tree),/Не получилось/);assert.equal(h.orderCalls,0);});
await check('transient requested GET retains retry instead of starting a new order',async h=>{h.location.state=hint();h.details=async()=>{throw Error('offline');};await settle(h);assert.equal(screen(h,'ComposeView'),undefined);h.details=async id=>({id,role:'passenger',status:'expired'});nodes(h.tree).find(x=>x.type==='button'&&text(x)==='Повторить').props.onClick();await settle(h);assert.equal(track(h)?.props.order.id,17);assert.equal(h.detailCalls.length,2);});
await check('late details cannot publish after account change',async h=>{const d=deferred();h.location.state=hint();h.details=()=>d.promise;await settle(h);h.owner={userId:22,generation:'B'};h.dirty=true;await settle(h);d.resolve({id:17,role:'passenger',status:'expired'});await settle(h);h.gps.no();await settle(h);assert.equal(track(h),undefined);assert.ok(screen(h,'ComposeView'));assert.equal(h.detailCalls.length,1);});
await check('unmount aborts requested details and discards the late response',async h=>{const d=deferred();h.location.state=hint();h.details=()=>d.promise;await settle(h);const signal=h.detailCalls[0].signal;unmount(h);assert.equal(signal.aborted,true);d.resolve({id:17,role:'passenger',status:'expired'});await Promise.resolve();await Promise.resolve();assert.equal(h.dirty,false);});
await check('new order clears context; late polling cannot resurrect it',async h=>{h.location.state=hint();await settle(h);const d=deferred();h.details=()=>d.promise;h.tick();track(h).props.onNewOrder();await settle(h);assert.equal(h.location.state,null);h.gps.no();await settle(h);d.resolve({id:17,role:'passenger',status:'accepted'});await settle(h);assert.equal(track(h),undefined);assert.ok(screen(h,'ComposeView'));assert.equal(h.navigations.at(-1)[1].replace,true);});
await check('a new explicit ID wins over old refresh',async h=>{h.location.state=hint();await settle(h);const d=deferred();h.details=()=>d.promise;track(h).props.onRefresh();h.location={key:'new-context',state:hint(18)};h.details=async id=>({id,role:'passenger',status:'expired'});h.dirty=true;await settle(h);d.resolve({id:17,role:'passenger',status:'expired'});await settle(h);assert.equal(track(h)?.props.order.id,18);});
await check('late old cancellation callback cannot erase a new session context',async h=>{h.location.state=hint();await settle(h);const old=track(h).props.onCancelled;h.owner={userId:22,generation:'B'};h.location={key:'new-owner',state:hint(18,22,'B')};h.dirty=true;await settle(h);old();await settle(h);assert.equal(track(h)?.props.order.id,18);assert.equal(h.navigations.length,0);});
for(const status of ['accepted','cancelled','done'])await check('authoritative requested GET can advance to known status '+status,async h=>{h.location.state=hint();h.details=async id=>({id,role:'passenger',status});await settle(h);assert.equal(track(h)?.props.order.status,status);});
async function activateButton(h){h.scheduled=true;await settle(h);const b=nodes(h.tree).find(x=>x.type==='button'&&text(x).includes('Начать поиск сейчас'));assert.ok(b);b.props.onClick();await settle(h);}
await check('origin hands over only the same expired passenger ID',async h=>{await activateButton(h);assert.deepEqual(h.navigations.at(-1),['/taxi',{state:hint()}]);});
for(const result of [{id:18,role:'passenger',status:'expired'},{id:17,role:'driver',status:'expired'},{id:17,role:'passenger',status:'scheduled'},{id:17,role:'passenger',status:'cancelled'}])await check('unconfirmed activation cannot hand over expired context '+JSON.stringify(result),async h=>{h.activate=async()=>result;await activateButton(h);assert.equal(h.navigations.length,0);});
for(const status of ['expired','searching','scheduled','done'])for(const action of ['unmount','account'])await check('late origin '+status+' activation cannot navigate after '+action,async h=>{const d=deferred();h.activate=()=>d.promise;await activateButton(h);if(action==='unmount')unmount(h);else h.owner={userId:22,generation:'B'};d.resolve({id:17,role:'passenger',status});await Promise.resolve();await Promise.resolve();assert.equal(h.navigations.length,0);});
await check('ordinary same-path navigation preserves the current pickup draft',async h=>{await start(h);const chosen={lat:56,lng:57,text:'Chosen pickup'};screen(h,'ComposeView').props.setFrom(chosen);await settle(h);h.location={key:'same-path-again',state:null};h.dirty=true;await settle(h);if(!screen(h,'ComposeView')){h.gps.no();await settle(h);}assert.deepEqual(screen(h,'ComposeView')?.props.from,chosen);});
for(const kind of ['poll-poll','poll-refresh','refresh-poll'])await check('newer same-ID details win over delayed '+kind,async h=>{h.location.state=hint();await settle(h);const first=deferred(),second=deferred();let call=0;h.details=()=>++call===1?first.promise:second.promise;const [a,b]=kind.split('-');const refresh=track(h).props.onRefresh;(a==='poll'?h.tick:refresh)();(b==='poll'?h.tick:refresh)();second.resolve({id:17,role:'passenger',status:'cancelled'});await settle(h);assert.equal(track(h)?.props.order.status,'cancelled');first.resolve({id:17,role:'passenger',status:'expired'});await settle(h);assert.equal(track(h)?.props.order.status,'cancelled');});
console.log(`Taxi bootstrap: ${total-failed}/${total} passed`);if(failed)process.exitCode=1;
