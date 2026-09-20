import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
const h=globalThis.__taxiRating={};
async function load(file){
 const entry=fileURLToPath(new URL('../src/screens/'+file,import.meta.url)),source=await readFile(entry,'utf8'),mocks={};
 for(const [,clause,path] of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)){
  const names=(clause.match(/\{([\s\S]*?)\}/)?.[1]??'').split(',').map(x=>x.trim()).filter(x=>x&&!x.startsWith('type '));
  mocks[path]='export default ()=>null;'+names.map(n=>`export const ${n}=()=>null;`).join('\n');
 }
 mocks.react=`const h=globalThis.__taxiRating;
export const useState=v=>{const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],n=>{if(x.live){x.values[i]=typeof n==='function'?n(x.values[i]):n;h.dirty=true;}}];};
export const useRef=v=>{const x=h.current,i=x.i++;return x.values[i]??={current:v};};
export const useEffect=(fn,deps)=>{const x=h.current,i=x.i++,old=x.values[i];if(!old||deps.some((v,j)=>v!==old.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn();});}};`;
 mocks['react/jsx-runtime']=`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`;
 mocks['../i18n/lang']=`export const useLang=()=>({lang:'ru',appText:globalThis.__taxiRating.appText});`;
 mocks['../api/client']=`export class ApiError extends Error{};export const getSessionGeneration=()=>globalThis.__taxiRating.generation;`;
 mocks['../api/instant']=`export const rateInstantOrder=(...args)=>{const h=globalThis.__taxiRating;h.posts.push(args);return h.send(...args);};export const fetchInstantOrder=id=>globalThis.__taxiRating.read(id);export const reportLostItem=async()=>({});`;
 mocks['../utils/format']=`export const kopExactLabel=()=>'';export const payMethodLabel=()=>'';export const pluralRu=(n,one)=>one;`;
 const result=await build({entryPoints:[entry],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'fixtures',setup(b){b.onResolve({filter:/.*/},a=>a.path.endsWith('/useTaxiRating')?undefined:a.path in mocks?{path:a.path,namespace:'fixture'}:undefined);b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
 return (await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'))).default;
}
const screens=[['passenger',await load('TaxiPassengerCompletedScreen.tsx')],['driver',await load('TaxiDriverCompletedScreen.tsx')]];
function render(){const seen=new Set();h.effects=[];function visit(n,path){if(Array.isArray(n))return n.map((v,i)=>visit(v,path+'.'+i));if(!n||typeof n!=='object')return n;if(typeof n.type==='function'){const id=path+':'+(n.key??'');seen.add(id);let x=h.instances.get(id);if(!x||x.type!==n.type){x={type:n.type,values:[],cleanups:[],live:true};h.instances.set(id,x);}h.current=x;x.i=0;return visit(n.type(n.props),id+'.child');}return{...n,props:{...n.props,children:visit(n.props?.children,path+'.children')}};}h.dirty=false;h.tree=visit({type:h.Screen,props:{order:h.order,onClose(){},onNewOrder(){},onOpenReceipt(){},onOpenChat(){},onReturnToLine(){},onShiftFinished(){}}},'root');for(const [id,x]of h.instances)if(!seen.has(id)){x.live=false;x.cleanups.forEach(f=>f?.());h.instances.delete(id);}for(const fn of h.effects)fn();}
const text=n=>Array.isArray(n)?n.map(text).join(' '):typeof n==='string'?n:!n||typeof n!=='object'?'':text(n.props?.children);
const nodes=n=>Array.isArray(n)?n.flatMap(nodes):!n||typeof n!=='object'?[]:[n,...nodes(n.props?.children)];
const radios=()=>nodes(h.tree).filter(n=>n.props.role==='radio');
const submit=()=>nodes(h.tree).find(n=>n.type==='button'&&text(n)==='Отправить оценку');
async function settle(){for(let i=0;i<25;i++){if(h.dirty)render();await Promise.resolve();}}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};}
let total=0,failed=0;
async function test(Screen,name,fn){total++;Object.assign(h,{Screen,instances:new Map(),dirty:true,generation:'A',appText:ru=>ru,posts:[],order:{id:41,status:'done',my_stars:0,can_rate:true,driver_name:'Driver',driver_car:'Car',passenger_name:'Passenger',passenger_trips:1,passenger_rating:5,price_estimate:200,promo_discount_kop:0},read:async()=>h.order,send:async()=>({})});try{await fn();console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}}
for(const [label,Screen]of screens){
 await test(Screen,label+' restores own saved rating',async()=>{h.order.my_stars=5;await settle();assert.match(text(h.tree),/Спасибо за оценку/);assert.equal(radios().length,0);});
 await test(Screen,label+' respects server rating gate',async()=>{h.order.can_rate=false;await settle();assert.equal(radios().length,0);assert.match(text(h.tree),/недоступна/);});
 await test(Screen,label+' freezes score and tags during send',async()=>{const pending=deferred();h.send=()=>pending.promise;await settle();radios()[4].props.onClick();await settle();submit().props.onClick();await settle();assert.ok(radios().every(n=>n.props.disabled));pending.resolve({});await settle();assert.match(text(h.tree),/Спасибо за оценку/);assert.equal(h.posts[0][1],5);});
 await test(Screen,label+' recovers saved score after lost response',async()=>{h.send=async()=>{throw Error('lost');};h.read=async()=>({...h.order,my_stars:5});await settle();radios()[4].props.onClick();await settle();submit().props.onClick();await settle();assert.match(text(h.tree),/Спасибо за оценку/);assert.equal(h.posts.length,1);});
 await test(Screen,label+' unknown result requires read retry before another send',async()=>{h.send=async()=>{throw Error('lost');};h.read=async()=>{throw Error('offline');};await settle();radios()[4].props.onClick();await settle();submit().props.onClick();await settle();assert.ok(!submit()||submit().props.disabled);assert.ok(nodes(h.tree).some(n=>n.type==='button'&&text(n)==='Повторить'));});
 await test(Screen,label+' does not carry confirmation into next order',async()=>{await settle();radios()[4].props.onClick();await settle();submit().props.onClick();await settle();h.order={...h.order,id:42,my_stars:0};h.dirty=true;await settle();assert.equal(radios().length,5);assert.doesNotMatch(text(h.tree),/Спасибо за оценку/);});
 await test(Screen,label+' ignores late previous-account send',async()=>{const pending=deferred();h.send=()=>pending.promise;await settle();radios()[4].props.onClick();await settle();submit().props.onClick();await settle();h.generation='B';h.order={...h.order,my_stars:2};h.dirty=true;await settle();pending.resolve({});await settle();assert.match(text(h.tree),/2 звезда/);assert.doesNotMatch(text(h.tree),/5 звезда/);});
 await test(Screen,label+' validates missing server metadata before allowing a rating',async()=>{delete h.order.my_stars;delete h.order.can_rate;h.read=async()=>{throw Error('offline');};await settle();assert.equal(radios().length,0);assert.ok(nodes(h.tree).some(n=>n.type==='button'&&text(n)==='Повторить'));});
 await test(Screen,label+' unsaved failure preserves selected score for retry',async()=>{h.send=async()=>{throw Error('offline');};await settle();radios()[3].props.onClick();await settle();submit().props.onClick();await settle();assert.equal(radios()[3].props['aria-checked'],true);assert.match(text(h.tree),/Не получилось отправить/);assert.equal(submit().props.disabled,false);});
}
console.log(`Taxi rating recovery: ${total-failed}/${total} passed`);if(failed)process.exitCode=1;
