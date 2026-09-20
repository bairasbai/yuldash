// Real SosScreen, controlled component/hooks runtime; no DOM, network, SMS or geolocation service.
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const h=globalThis.__sosBoundary={};
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}};
const mocks={
 react:`const h=globalThis.__sosBoundary;
 export function useState(v){const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],v=>{if(x.live)x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
 export function useRef(v){const x=h.current,i=x.i++;return x.values[i]??={current:v};}
 export function useCallback(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||deps.some((v,j)=>v!==p.deps[j]))x.values[i]={fn,deps};return x.values[i].fn;}
 export function useEffect(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||!deps||deps.some((v,j)=>v!==p.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn()});}}
 export const useMemo=fn=>fn();`,
 'react/jsx-runtime':`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`,
 'react-router-dom':`export const useNavigate=()=> (...args)=>globalThis.__sosBoundary.nav.push(args);`,
 lang:`export const useLang=()=>({appText:ru=>ru,lang:'ru'});`,
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
function reset(){Object.assign(h,{instances:new Map(),effects:[],auth:{isAuthed:true,status:'authed',user:{id:101}},generation:'A',nav:[],tracks:[],sends:[],geo:[],deferred});Object.defineProperty(globalThis,'navigator',{configurable:true,value:{geolocation:{getCurrentPosition:(ok,error)=>h.geo.push({ok,error})}}});render();}
function loginB(){h.auth={isAuthed:true,status:'authed',user:{id:202}};h.generation='B';return render();}
function enterA(){textarea(render()).props.onChange({target:{value:'Личный текст A'}});nodes(render(),n=>n.type==='button'&&n.key==='medical')[0].props.onClick();h.geo[0].ok({coords:{latitude:54,longitude:55}});return render();}
let total=0,failed=0;
async function test(label,fn){total++;try{reset();await fn();console.log('PASS '+label)}catch(e){failed++;console.error('FAIL '+label+': '+e.message)}finally{for(const x of h.instances.values()){x.live=false;x.cleanups.forEach(fn=>fn?.());}}}
await test('account B cannot see or send A draft and coordinates',async()=>{enterA();const tree=loginB();assert.equal(textarea(tree)?.props.value,'','B must not inherit A note');const pending=byClass(tree,'btn-danger').props.onClick();const r=h.sends.at(-1);assert.equal(r.user,202);assert.equal(r.body.note,'');assert.equal(r.body.category,'other');assert.equal(r.body.lat,undefined);assert.equal(r.body.lng,undefined);r.resolve({});await pending;});
await test('late A success must not show sent screen or analytics for B',async()=>{enterA();const pending=byClass(render(),'btn-danger').props.onClick();const r=h.sends[0];loginB();r.resolve({});await pending;assert.ok(textarea(render()),'B must retain its own editable form');assert.deepEqual(h.tracks,[],'Old A continuation must not emit B analytics');});
await test('late A location callback must not supply coordinates to B',async()=>{const oldGeo=h.geo[0];loginB();oldGeo.ok({coords:{latitude:54,longitude:55}});const pending=byClass(render(),'btn-danger').props.onClick();const request=h.sends.at(-1);assert.equal(request.body.lat,undefined,'Old GPS must not populate B');assert.equal(request.body.lng,undefined);request.resolve({});await pending;});
await test('same-session rerender preserves draft and ordinary send',async()=>{enterA();const tree=render();assert.equal(textarea(tree).props.value,'Личный текст A');const pending=byClass(tree,'btn-danger').props.onClick();const r=h.sends[0];assert.equal(r.body.category,'medical');assert.equal(r.body.lat,54);r.resolve({});await pending;assert.ok(JSON.stringify(render()).includes('Сигнал отправлен'),'Successful send must show confirmation');assert.equal(h.tracks.length,1);});
await test('merged SOS copies note and coordinates and rejects stale account callback',async()=>{
 const tree=enterA(),writes=[];globalThis.navigator.clipboard={writeText:async text=>writes.push(text)};globalThis.window={setTimeout:()=>0};
 const copy=nodes(tree,n=>n.type==='button'&&JSON.stringify(n.props.children).includes('Скопировать'))[0];assert.ok(copy);
 await copy.props.onClick();assert.deepEqual(writes,['Личный текст A\n54.00000, 55.00000']);
 loginB();await copy.props.onClick();assert.equal(writes.length,1,'Stale A must not copy after B login');
});
console.log(`SOS account boundary: ${total-failed} passed, ${failed} failed`);if(failed)process.exitCode=1;
