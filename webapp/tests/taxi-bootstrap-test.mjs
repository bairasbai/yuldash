// Execute the unchanged production component with controlled API/GPS and hooks.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const entry=fileURLToPath(new URL('../src/screens/InstantOrderScreen.tsx',import.meta.url));
const source=await readFile(entry,'utf8'), mocks={};
for(const [,clause,path] of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)){
 const names=(clause.match(/\{([\s\S]*?)\}/)?.[1]??'').split(',').map(x=>x.trim()).filter(x=>x&&!x.startsWith('type '));
 mocks[path]='export default function Stub(){return null;}\n'+names.map(n=>`export const ${n}=()=>null;`).join('\n');
}
mocks.react=`const h=()=>globalThis.__taxiBoot;
export const useState=initial=>{const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;return[s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;s.dirty=true;}];};
export const useRef=v=>{const s=h(),i=s.cursor++;return s.slots[i]??={current:v};};
const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
export const useCallback=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps))s.slots[i]={fn,deps};return s.slots[i].fn;};
export const useEffect=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps)){s.effects.push(()=>{old?.cleanup?.();s.slots[i]={deps,cleanup:fn()};});}};`;
mocks['react/jsx-runtime']=`export const Fragment='fragment';export const jsx=(type,props)=>({type,props});export const jsxs=jsx;`;
mocks['../i18n/lang']=`export const useLang=()=>({lang:'ru',appText:globalThis.__taxiBoot.appText});`;
mocks['react-router-dom']=`export const useNavigate=()=>()=>{};`;
mocks['../api/client']=`export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}`;
mocks['../api/instant']+=`
export const fetchMyOrders=(...args)=>{const h=globalThis.__taxiBoot;h.orderCalls++;return h.orders();};
export const fetchTaxiAvailability=()=>{const h=globalThis.__taxiBoot;h.availabilityCalls++;return h.availability();};`;
// Replace the placeholder exports for controlled APIs, leaving other imports as fixtures.
mocks['../api/instant']=mocks['../api/instant'].replace('export const fetchMyOrders=()=>null;','').replace('export const fetchTaxiAvailability=()=>null;','').replace('export const ACTIVE_PASSENGER_STATUSES=()=>null;',`export const ACTIVE_PASSENGER_STATUSES=['searching','accepted','arriving','onboard'];`);
mocks['../api/discovery']=`export const reverseGeocode=()=>globalThis.__taxiBoot.address.promise;export const geocode=()=>null;`;
const result=await build({entryPoints:[entry],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'taxi-fixtures',setup(b){b.onResolve({filter:/.*/},a=>a.path in mocks?{path:a.path,namespace:'fixture'}:undefined);b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
const {default:Screen}=await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};}
function nodes(n){return !n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];}
function screen(h,name){return nodes(h.tree).find(n=>typeof n.type==='function'&&n.type.name===name);}
function text(n){return typeof n==='string'?n:!n||typeof n!=='object'?'':[n.props?.children].flat(Infinity).map(text).join(' ');}
async function settle(h){for(let i=0;i<25;i++){if(h.dirty){h.dirty=false;h.cursor=0;h.effects=[];h.tree=Screen();for(const effect of h.effects)effect();}await Promise.resolve();}assert.equal(h.dirty,false);}
let total=0,failed=0;
async function check(name,fn){total++;const h=globalThis.__taxiBoot={slots:[],cursor:0,effects:[],dirty:true,appText:ru=>ru,address:deferred(),orderCalls:0,availabilityCalls:0,orders:async()=>[],availability:async()=>({enabled:true}),gps:null};
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{geolocation:{getCurrentPosition:(yes,no)=>{h.gps={yes,no};}}}});globalThis.window={setInterval:()=>1,clearInterval:()=>{}};
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
console.log(`Taxi bootstrap: ${total-failed}/${total} passed`);if(failed)process.exitCode=1;
