// Executes actual CarryOrders and CarryParcelCard with controlled hooks/API; no live services.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
const root=fileURLToPath(new URL('../',import.meta.url));
const h=globalThis.__courierGoods={};
const mocks={
'react':`const h=globalThis.__courierGoods;
export function useState(v){const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],v=>{if(x.live)x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
export function useCallback(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||deps.some((v,j)=>v!==p.deps[j]))x.values[i]={fn,deps};return x.values[i].fn;}
export function useEffect(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||!deps||deps.some((v,j)=>v!==p.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn()});}}`,
'react/jsx-runtime':`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`,
'react-router-dom':`export const useNavigate=()=>()=>{};export const Link='a';`,
lang:`export const useLang=()=>({lang:globalThis.__courierGoods.ba?'ba':'ru',appText:(ru,ba)=>globalThis.__courierGoods.ba?ba:ru});`,
client:`export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}} globalThis.__courierGoods.ApiError=ApiError;`,
courier:`export const setGoodsCost=(id,kop)=>globalThis.__courierGoods.save(id,kop);export const fetchCourierMe=courierOnline=courierOffline=fetchCourierAvailable=payCourierCommission=()=>{};`,
parcels:`export const fetchCarrying=async()=>globalThis.__courierGoods.rows;export const isCarrying=s=>s==='accepted'||s==='in_transit';export const acceptParcel=parcelArrived=setParcelStatus=()=>{};`,
format:`export const rubLabel=n=>String(n/100);export const formatWhen=dayMonthLong=()=>'';`,
header:`export const SubHeader=()=>null;`,
states:`export const LoadingList=ErrorState=()=>null;`,
icons:`export const IconStar=IconCheck=IconCopy=IconBox=IconCamera=IconClock=IconTrend=IconRoute=IconWallet=IconArrow=IconPhone=IconChat=IconProfile=()=>null;`,
brand:`export const YuCourierWalk=YuModeCourier=YuRoute=()=>null;`,
time:`export const serverMs=()=>0;`,
payment:`export const rememberPayment=()=>{};`,
analytics:`export const track=()=>{};`,
form:`export const ParcelAddressBlock=ParcelDeadlineNote=ParcelReturnNotice=ParcelRouteRow=()=>null;export const cargoTypeEmoji=cargoTypeLabel=sizeLabel=()=>'';`,
progress:`export const CourierDeliveryProgress=()=>null;`,
navigator:`export const openNavigator=()=>{};`,
empty:`export default ()=>null;`,
};
// Chained export declarations require independently declared bindings in ES modules.
for(const key of Object.keys(mocks))mocks[key]=mocks[key].replace(/export const ([A-Za-z][\w]*(?:=[A-Za-z][\w]*)+)=(?=\()/g,(_,names)=>'export const '+names.split('=').join('=()=>null,')+'=');
const mapping={'/i18n/lang':'lang','/api/client':'client','/api/courier':'courier','/api/parcels':'parcels','/utils/format':'format','/ConsentsScreen':'header','/components/States':'states','/Icons':'icons','/BrandIcons':'brand','/utils/serverTime':'time','/utils/pendingPayment':'payment','/analytics':'analytics','/parcelForm':'form','/TaxiTripProgress':'progress','/utils/navigator':'navigator'};
const b=await build({stdin:{contents:(await readFile(root+'src/screens/CourierScreen.tsx','utf8'))+'\nexport { CarryOrders };',resolveDir:root+'src/screens',loader:'tsx'},bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'controlled',setup(b){b.onResolve({filter:/.*/},a=>{if((a.path.endsWith('/parcelUi')||a.path.endsWith('/CompletedParcelCard')))return null;let k=Object.hasOwn(mocks,a.path)?a.path:Object.entries(mapping).find(([suffix])=>a.path.endsWith(suffix))?.[1];if(!k&&a.path.startsWith('.'))k='empty';return k?{path:k,namespace:'mock'}:null;});b.onLoad({filter:/.*/,namespace:'mock'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
const {CarryOrders}=await import('data:text/javascript;base64,'+Buffer.from(b.outputFiles[0].text).toString('base64'));
function render(){const seen=new Set();h.effects=[];function visit(n,path){if(Array.isArray(n))return n.map((v,i)=>visit(v,path+'.'+i));if(n==null||typeof n!=='object')return n;if(typeof n.type==='function'){const id=path+':'+(n.key??'');seen.add(id);let x=h.instances.get(id);if(!x||x.type!==n.type){x={type:n.type,values:[],cleanups:[],live:true};h.instances.set(id,x);}x.i=0;h.current=x;return visit(n.type(n.props),id+'.child');}return {...n,props:{...n.props,children:visit(n.props?.children,path+'.children')}};}const tree=visit({type:CarryOrders,props:{onGoAvailable(){}}},'root');for(const[id,x]of h.instances)if(!seen.has(id)){x.live=false;x.cleanups.forEach(f=>f?.());h.instances.delete(id);}for(const fn of h.effects)fn();return tree;}
function nodes(t,p){if(Array.isArray(t))return t.flatMap(v=>nodes(v,p));if(!t||typeof t!=='object')return [];return [...(p(t)?[t]:[]),...nodes(t.props?.children,p)];}
const text=t=>JSON.stringify(t);
const button=(t,label)=>nodes(t,n=>n.type==='button'&&text(n.props.children).includes(label))[0];
const input=t=>nodes(t,n=>n.type==='input'&&n.props.inputMode==='numeric')[0];
const flush=async()=>{await Promise.resolve();await Promise.resolve();};
async function reset(ba=false){Object.assign(h,{ba,instances:new Map(),effects:[],calls:[],rows:[{id:11,status:'in_transit',delivery_type:'buy_bring',cod_amount_kop:10000,settlement:null,price_kop:50000,size:'small',from_city:'Уфа',to_city:'Уфа'}]});render();await flush();render();}
let passed=0,failed=0;
async function test(name,fn){try{await reset();await fn();passed++;console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}}
const budgetMessage='Заказчик согласился на 100 ₽ (с запасом — до 115 ₽). Свяжись с ним: он поднимет сумму в заказе';
await test('422 budget rejection is visible beside order; entered cost survives and retry succeeds',async()=>{
 let approved=false;h.save=async(id,kop)=>{h.calls.push({id,kop});if(!approved){throw new h.ApiError(422,budgetMessage);}return {settlement:{goods_actual_kop:kop,total_due_kop:kop+50000}};};
 input(render()).props.onChange({target:{value:'200'}});await button(render(),'Сохранить').props.onClick();await flush();
 assert.ok(text(render()).includes(budgetMessage),'Server budget explanation must be visible');assert.equal(input(render()).props.value,'200');assert.ok(!button(render(),'Доставлено'));
 approved=true;await button(render(),'Сохранить').props.onClick();await flush();assert.ok(!text(render()).includes(budgetMessage));assert.ok(button(render(),'Доставлено'));assert.deepEqual(h.calls,[{id:11,kop:20000},{id:11,kop:20000}]);
});
await test('ordinary success enables delivery',async()=>{h.save=async(id,kop)=>({settlement:{goods_actual_kop:kop,total_due_kop:kop+50000}});input(render()).props.onChange({target:{value:'100'}});await button(render(),'Сохранить').props.onClick();await flush();assert.ok(button(render(),'Доставлено'));});
await test('network failure has readable retry guidance in RU and BA',async()=>{for(const ba of [false,true]){await reset(ba);h.save=async()=>{throw new Error('')};input(render()).props.onChange({target:{value:'100'}});await button(render(),ba?'Һаҡлау':'Сохранить').props.onClick();await flush();const alerts=nodes(render(),n=>n.props.role==='alert');assert.equal(alerts.length,1);assert.ok(text(alerts[0]).includes(ba?'Ҡабатлап':'Повтори'));assert.equal(input(render()).props.value,'100');}});
console.log(`Courier goods: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
