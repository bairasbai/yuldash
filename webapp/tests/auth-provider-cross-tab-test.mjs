// Executes the real provider with a controlled hooks/API boundary (not a browser renderer).
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const h = globalThis.__authProviderCrossTab = {};
const deferred = () => { let resolve, reject; const promise = new Promise((a,b)=>{resolve=a;reject=b;}); return {promise,resolve,reject}; };
const flush = async () => { for(let i=0;i<4;i++) await new Promise(setImmediate); };
const mocks = {
  react: `const h=globalThis.__authProviderCrossTab;
    export const createContext=()=>({Provider:'provider'}),useContext=()=>null;
    export function useState(v){const i=h.si++;if(!(i in h.states))h.states[i]=typeof v==='function'?v():v;return[h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v];}
    export function useRef(v){const i=h.ri++;return h.refs[i]??={current:v};}
    export function useEffect(fn,deps){const i=h.ei++;const prev=h.effectDeps[i];if(!prev||deps.some((v,j)=>v!==prev[j])){h.effectDeps[i]=deps;h.effects.push(fn);}}
    export function useCallback(fn,deps){const i=h.ci++;const prev=h.callbacks[i];if(!prev||deps.some((v,j)=>v!==prev.deps[j]))h.callbacks[i]={fn,deps};return h.callbacks[i].fn;}
    export const useMemo=fn=>fn();`,
  'react/jsx-runtime': `export const jsx=(type,props)=>({type,props}),jsxs=jsx;`,
  client: `const h=globalThis.__authProviderCrossTab;
    export const getToken=()=>h.token,getSessionGeneration=()=>String(h.generation);
    export const setSession=(token,refresh)=>{h.token=token;h.refresh=refresh;h.generation++;};
    export const setRefreshHandler=fn=>h.refreshHandler=fn,setUnauthorizedHandler=fn=>h.unauthorizedHandler=fn;`,
  auth: `const h=globalThis.__authProviderCrossTab;
    export const fetchMe=()=>{const request=h.makeDeferred();request.token=h.token;h.requests.push(request);return request.promise;};
    export const refreshSession=async()=>true;
    export const logoutServer=()=>{h.logoutBearers.push(h.token);return h.serverLogout.promise;};`,
  push: `export const disableWebPush=()=>globalThis.__authProviderCrossTab.disable.promise;`,
  outbox: `export const clearOutbox=()=>globalThis.__authProviderCrossTab.clears.push('outbox');`,
  drafts: `export const clearAllDrafts=()=>globalThis.__authProviderCrossTab.clears.push('drafts');`,
  privacy: `export const syncPersonalSession=()=>{}; export const clearPersonalLocal=()=>globalThis.__authProviderCrossTab.clears.push('personal');`,
};
const result = await build({entryPoints:[root+'src/auth/AuthProvider.tsx'],bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'provider-boundary',setup(b){
  b.onResolve({filter:/.*/},a=>{const p=a.path;const k=Object.hasOwn(mocks,p)?p:p.endsWith('/api/client')?'client':p.endsWith('/api/auth')?'auth':p.endsWith('/push/webPush')?'push':p.endsWith('/utils/outbox')?'outbox':p.endsWith('/utils/formDraft')?'drafts':p.endsWith('/utils/privacy')?'privacy':null;return k?{path:k,namespace:'boundary'}:null;});
  b.onLoad({filter:/.*/,namespace:'boundary'},a=>({contents:mocks[a.path],loader:'js'}));
}}]});
const {AuthProvider} = await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
function reset(){delete globalThis.caches;globalThis.window=new EventTarget();globalThis.localStorage={getItem:key=>key==='yuldash.token'?h.token:key==='yuldash.session'?String(h.generation):null};Object.assign(h,{states:[],refs:[],callbacks:[],effectDeps:[],effects:[],cleanups:[],requests:[],token:'A',refresh:'refresh-A',generation:1,makeDeferred:deferred,disable:deferred(),serverLogout:deferred(),logoutBearers:[],clears:[]});}
function render(){h.si=h.ri=h.ei=h.ci=0;return AuthProvider({children:null}).props.value;}
function mount(){reset();const value=render();for(const fn of h.effects.splice(0))h.cleanups.push(fn());return value;}
const A={id:101,name:'Account A'}, B={id:202,name:'Account B'};
function loginB(){render().login('B','refresh-B',B);}
function assertB(){const value=render();assert.equal(h.token,'B','B token must survive');assert.equal(value.status,'authed','B must stay authenticated');assert.equal(value.user?.id,202,'B must keep its own profile');}
async function readyA(){mount();h.requests[0].resolve(A);await flush();assert.equal(render().user.id,101);}
let failed=0,total=0;
async function test(label,fn){total++;try{await fn();console.log('PASS '+label);}catch(e){failed++;console.error('FAIL '+label+': '+e.message);}finally{for(const fn of h.cleanups??[])fn?.();}}

// External tab has already committed shared storage before the browser emits storage.
function externalSession(token, generation) {
  const oldValue=String(h.generation);h.token=token;h.generation=generation;
  const event=new Event('storage');
  Object.assign(event,{key:'yuldash.session',oldValue,newValue:String(generation),storageArea:localStorage});
  window.dispatchEvent(event);
}
function requireRequest(token) {
  const request=h.requests.findLast(r=>r.token===token);
  assert.ok(request,`Storage transition must load profile using token ${token}`);
  return request;
}
function assertUser(id) { const v=render();assert.equal(v.user?.id,id);assert.equal(v.status,'authed'); }

await test('external login immediately hides A until B profile is checked',async()=>{
  await readyA();externalSession('B',2);
  const v=render();assert.equal(v.user,null,'Old A profile must disappear on storage event');assert.equal(v.isAuthed,false);
  requireRequest('B').resolve(B);await flush();assertUser(202);
});
await test('external logout immediately removes authenticated A UI',async()=>{
  await readyA();externalSession(null,2);
  const v=render();assert.equal(v.user,null,'External logout must remove A profile');assert.equal(v.status,'guest');assert.equal(v.isAuthed,false);
});
await test('external B then C login accepts only latest profile',async()=>{
  await readyA();externalSession('B',2);const b=requireRequest('B');
  externalSession('C',3);const c=requireRequest('C');
  c.resolve({id:303,name:'Account C'});await flush();assertUser(303);
  b.resolve(B);await flush();assertUser(303);
});
await test('late startup A failure cannot remove external login B',async()=>{
  mount();const a=h.requests[0];externalSession('B',2);const b=requireRequest('B');
  b.resolve(B);await flush();assertUser(202);
  a.reject(new Error('old A failed'));await flush();assertUser(202);
});
await test('same-session token refresh storage event preserves A UI',async()=>{
  await readyA();h.token='A-refreshed';
  const event=new Event('storage');Object.assign(event,{key:'yuldash.token',oldValue:'A',newValue:h.token,storageArea:localStorage});window.dispatchEvent(event);
  await flush();assertUser(101);assert.equal(h.requests.length,1,'Token rotation in same session must not restart profile');
});
await test('logout already waiting from A must not send logout as external B',async()=>{
  await readyA();const pending=render().logout();externalSession('B',2);
  h.disable.resolve();h.serverLogout.resolve({ok:true});await pending;
  assert.deepEqual(h.logoutBearers,[],'Old logout continuation must not act as B');assert.equal(h.token,'B');assert.deepEqual(h.clears,[]);
});
await test('storage clear event removes old authenticated UI',async()=>{
  await readyA();h.token=null;h.generation='';
  const event=new Event('storage');Object.assign(event,{key:null,newValue:null,oldValue:null,storageArea:localStorage});window.dispatchEvent(event);
  assert.equal(render().user,null);assert.equal(render().status,'guest');
});
await test('queued obsolete session event cannot reset current profile',async()=>{
  await readyA();loginB();const requestCount=h.requests.length;
  const event=new Event('storage');Object.assign(event,{key:'yuldash.session',newValue:'obsolete-session',oldValue:'older-session',storageArea:localStorage});window.dispatchEvent(event);
  await flush();assertUser(202);assert.equal(h.requests.length,requestCount,'Obsolete event must not reload current profile');
});
await test('startup outage is unavailable, then online restores account without login',async()=>{
 mount();h.requests[0].reject(new Error('offline'));await flush();assert.equal(render().status,'unavailable');assert.equal(render().user,null);assert.equal(h.token,'A');assert.deepEqual(h.clears,[]);
 window.dispatchEvent(new Event('online'));assert.equal(h.requests.length,2);assert.equal(render().status,'loading');h.requests[1].resolve(A);await flush();assertUser(101);
});
await test('retry events do not duplicate pending requests or reload verified profile',async()=>{
 mount();h.requests[0].reject(new Error('503'));await flush();window.dispatchEvent(new Event('focus'));window.dispatchEvent(new Event('online'));assert.equal(h.requests.length,2);
 h.requests[1].resolve(A);await flush();window.dispatchEvent(new Event('focus'));assert.equal(h.requests.length,2);assertUser(101);
});
await test('manual retry restores unavailable profile',async()=>{
 mount();h.requests[0].reject(new Error('offline'));await flush();render().retrySession();assert.equal(h.requests.length,2);h.requests[1].resolve(A);await flush();assertUser(101);
});
await test('old retry marker cannot reload or hide a newly logged-in account',async()=>{
 mount();h.requests[0].reject(new Error('offline'));await flush();loginB();window.dispatchEvent(new Event('online'));assert.equal(h.requests.length,1);assertB();
});
console.log(`AuthProvider cross-tab: ${total-failed} passed, ${failed} failed`);
if(failed)process.exitCode=1;
