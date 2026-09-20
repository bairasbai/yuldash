import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const h=globalThis.__parcelRating={};
const mocks={
react:`const h=globalThis.__parcelRating;
 export const useState=v=>{const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],n=>{if(x.live){x.values[i]=typeof n==='function'?n(x.values[i]):n;h.dirty=true;}}];};
 export const useRef=v=>{const x=h.current,i=x.i++;return x.values[i]??={current:v};};
 export const useEffect=(fn,deps)=>{const x=h.current,i=x.i++,old=x.values[i];if(!old||deps.some((v,j)=>v!==old.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn();});}};`,
'react/jsx-runtime':`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`,
'../i18n/lang':`export const useLang=()=>({appText:(ru,ba)=>globalThis.__parcelRating.ba?ba:ru});`,
'../api/client':`export const getSessionGeneration=()=>globalThis.__parcelRating.generation;`,
'../api/parcels':`export const fetchParcelMyRating=(...args)=>globalThis.__parcelRating.read(...args);export const rateParcel=(...args)=>{const h=globalThis.__parcelRating;h.posts.push(args);return h.send(...args);};`,
'./Icons':`export const IconStar=()=>null;`,
'../analytics':`export const track=()=>{};`,
};
const bundle=await build({entryPoints:[fileURLToPath(new URL('../src/components/ParcelRate.tsx',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'rating-fixtures',setup(b){b.onResolve({filter:/.*/},a=>a.path in mocks?{path:a.path,namespace:'fixture'}:undefined);b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
const {default:Screen}=await import('data:text/javascript;base64,'+Buffer.from(bundle.outputFiles[0].text).toString('base64'));
function render(){const seen=new Set();h.effects=[];function visit(n,path){if(Array.isArray(n))return n.map((v,i)=>visit(v,path+'.'+i));if(!n||typeof n!=='object')return n;if(typeof n.type==='function'){const id=path+':'+(n.key??'');seen.add(id);let x=h.instances.get(id);if(!x||x.type!==n.type){x={type:n.type,values:[],cleanups:[],live:true};h.instances.set(id,x);}h.current=x;x.i=0;return visit(n.type(n.props),id+'.child');}return {...n,props:{...n.props,children:visit(n.props?.children,path+'.children')}};}h.dirty=false;h.tree=visit({type:Screen,props:{parcelId:h.parcelId,role:'sender'}},'root');for(const [id,x] of h.instances)if(!seen.has(id)){x.live=false;x.cleanups.forEach(f=>f?.());h.instances.delete(id);}for(const fn of h.effects)fn();}
const text=n=>Array.isArray(n)?n.map(text).join(' '):typeof n==='string'?n:!n||typeof n!=='object'?'':text(n.props?.children);
const nodes=n=>Array.isArray(n)?n.flatMap(nodes):!n||typeof n!=='object'?[]:[n,...nodes(n.props?.children)];
const stars=()=>nodes(h.tree).filter(n=>n.type==='button'&&n.props['aria-label']?.startsWith('Поставить'));
const retry=()=>nodes(h.tree).find(n=>n.type==='button'&&text(n)==='Повторить');
async function settle(){for(let i=0;i<20;i++){if(h.dirty)render();await Promise.resolve();}}
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};}
let total=0,failed=0;
async function test(name,fn){total++;Object.assign(h,{instances:new Map(),dirty:true,parcelId:31,generation:'A',ba:false,posts:[],read:async()=>({stars:null}),send:async()=>({})});try{await fn();console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}}
await test('reopened parcel displays own saved score and no send buttons',async()=>{h.read=async()=>({stars:5});await settle();assert.match(text(h.tree),/5 из 5/);assert.equal(stars().length,0);});
await test('unrated parcel sends once and shows confirmed score',async()=>{await settle();stars()[3].props.onClick();await settle();assert.equal(h.posts.length,1);assert.match(text(h.tree),/4 из 5/);assert.equal(stars().length,0);});
await test('failed initial read blocks sending until successful retry',async()=>{h.read=async()=>{throw Error('offline');};await settle();assert.equal(stars().length,0);assert.ok(retry());h.read=async()=>({stars:null});retry().props.onClick();await settle();assert.equal(stars().length,5);});
await test('lost response after commit recovers saved score without second POST',async()=>{let saved=false;h.read=async()=>({stars:saved?5:null});h.send=async()=>{saved=true;throw Error('lost response');};await settle();stars()[4].props.onClick();await settle();assert.match(text(h.tree),/5 из 5/);assert.equal(stars().length,0);assert.equal(h.posts.length,1);});
await test('unsaved failure keeps comment and permits another attempt',async()=>{h.send=async()=>{throw Error('offline');};await settle();nodes(h.tree).find(n=>n.type==='input').props.onChange({target:{value:'Мой отзыв'}});await settle();stars()[4].props.onClick();await settle();assert.equal(nodes(h.tree).find(n=>n.type==='input').props.value,'Мой отзыв');assert.equal(stars().length,5);assert.match(text(h.tree),/Не получилось/);});
await test('unknown result blocks duplicate until the server can be read',async()=>{let sent=false;h.send=async()=>{sent=true;throw Error('offline');};h.read=async()=>{if(sent)throw Error('offline');return{stars:null};};await settle();stars()[4].props.onClick();await settle();assert.equal(stars().length,0);assert.ok(retry());h.read=async()=>({stars:5});retry().props.onClick();await settle();assert.match(text(h.tree),/5 из 5/);assert.equal(h.posts.length,1);});
await test('late old-account read cannot replace the new account state',async()=>{const old=deferred();h.read=()=>old.promise;await settle();h.generation='B';h.read=async()=>({stars:2});h.dirty=true;await settle();old.resolve({stars:5});await settle();assert.match(text(h.tree),/2 из 5/);assert.doesNotMatch(text(h.tree),/5 из 5/);});
await test('switching parcel resets saved score before fetching new parcel',async()=>{h.read=async()=>({stars:5});await settle();h.parcelId=32;h.read=async()=>({stars:null});h.dirty=true;await settle();assert.equal(stars().length,5);assert.doesNotMatch(text(h.tree),/5 из 5/);});
await test('late send result from old account does not replace new score',async()=>{const old=deferred();h.send=()=>old.promise;await settle();stars()[4].props.onClick();await settle();h.generation='B';h.read=async()=>({stars:2});h.dirty=true;await settle();old.resolve({});await settle();assert.match(text(h.tree),/2 из 5/);assert.doesNotMatch(text(h.tree),/5 из 5/);});
await test('Bashkir saved score and read failure have localized text',async()=>{h.ba=true;h.read=async()=>({stars:4});await settle();assert.match(text(h.tree),/Һинең баһаң: 5-тән 4/);h.parcelId=32;h.read=async()=>{throw Error('offline');};h.dirty=true;await settle();assert.match(text(h.tree),/Баһаны тикшереп булманы/);assert.match(text(h.tree),/Ҡабатлау/);});
await test('API adapter rejects malformed scores instead of treating them as unrated',async()=>{
 const api=await build({entryPoints:[fileURLToPath(new URL('../src/api/parcels.ts',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm',plugins:[{name:'client',setup(b){b.onResolve({filter:/^\.\/client$/},()=>({path:'client',namespace:'fixture'}));b.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:`export const apiGet=async path=>{globalThis.__parcelRating.path=path;return globalThis.__parcelRating.response;};export const apiPost=()=>null,apiUpload=()=>null;`,loader:'js'}));}}]});
 const {fetchParcelMyRating}=await import('data:text/javascript;base64,'+Buffer.from(api.outputFiles[0].text).toString('base64'));
 for(const response of [null,{},[],{stars:0},{stars:6},{stars:'5'}]){h.response=response;await assert.rejects(()=>fetchParcelMyRating(31));}
 for(const score of [null,1,5]){h.response={stars:score};assert.deepEqual(await fetchParcelMyRating(31),{stars:score});assert.equal(h.path,'/parcels/31/my-rating');}
});
console.log(`Parcel rating recovery: ${total-failed}/${total} passed`);if(failed)process.exitCode=1;
