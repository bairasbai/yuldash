// Executes the real provider with a controlled hooks/API boundary (not a browser renderer).
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const h = globalThis.__authProviderSession = {};
const deferred = () => { let resolve, reject; const promise = new Promise((a,b)=>{resolve=a;reject=b;}); return {promise,resolve,reject}; };
const flush = async () => { for(let i=0;i<4;i++) await new Promise(setImmediate); };
const mocks = {
  react: `const h=globalThis.__authProviderSession;
    export const createContext=()=>({Provider:'provider'}),useContext=()=>null;
    export function useState(v){const i=h.si++;if(!(i in h.states))h.states[i]=typeof v==='function'?v():v;return[h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v];}
    export function useRef(v){const i=h.ri++;return h.refs[i]??={current:v};}
    export function useEffect(fn,deps){const i=h.ei++;const prev=h.effectDeps[i];if(!prev||deps.some((v,j)=>v!==prev[j])){h.effectDeps[i]=deps;h.effects.push(fn);}}
    export function useCallback(fn,deps){const i=h.ci++;const prev=h.callbacks[i];if(!prev||deps.some((v,j)=>v!==prev.deps[j]))h.callbacks[i]={fn,deps};return h.callbacks[i].fn;}
    export const useMemo=fn=>fn();`,
  'react/jsx-runtime': `export const jsx=(type,props)=>({type,props}),jsxs=jsx;`,
  client: `const h=globalThis.__authProviderSession;
    export const getToken=()=>h.token,getSessionGeneration=()=>String(h.generation);
    export const setSession=(token,refresh,_permit,expected)=>{if(h.sessionFailure)throw Error('session storage unavailable');if(expected!==String(h.generation))return null;h.token=token;h.refresh=refresh;h.generation++;return String(h.generation);};
    export const reconcileSession=()=>h.reconcileGate?.promise??Promise.resolve(); export const setRefreshHandler=fn=>h.refreshHandler=fn,setUnauthorizedHandler=fn=>h.unauthorizedHandler=fn;`,
  auth: `const h=globalThis.__authProviderSession;
    export const fetchMe=()=>{const request=h.makeDeferred();h.requests.push(request);return request.promise;};
    export const refreshSession=async()=>true;
    export const logoutServer=access=>{h.logoutBearers.push(access);return h.serverLogout.promise;};`,
  push: `export const disableLocalPushAfterLogout=()=>globalThis.__authProviderSession.disable.promise;`,
  credentials: `export const subscribeSessionChanges=listener=>{globalThis.__authProviderSession.sessionChanged=listener;return()=>{};};`,
  outbox: `export const clearOutbox=()=>globalThis.__authProviderSession.clears.push('outbox');`,
  drafts: `export const clearAllDrafts=()=>globalThis.__authProviderSession.clears.push('drafts');`,
  privacy: `export const syncPersonalSession=()=>{}; export const clearPersonalLocal=()=>globalThis.__authProviderSession.clears.push('personal');`,
};
const result = await build({entryPoints:[root+'src/auth/AuthProvider.tsx'],bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'provider-boundary',setup(b){
  b.onResolve({filter:/.*/},a=>{const p=a.path;const k=Object.hasOwn(mocks,p)?p:p.endsWith('/api/client')?'client':p.endsWith('/api/auth')?'auth':p.endsWith('/push/webPush')?'push':p.endsWith('/utils/sessionCredentials')?'credentials':p.endsWith('/utils/outbox')?'outbox':p.endsWith('/utils/formDraft')?'drafts':p.endsWith('/utils/privacy')?'privacy':null;return k?{path:k,namespace:'boundary'}:null;});
  b.onLoad({filter:/.*/,namespace:'boundary'},a=>({contents:mocks[a.path],loader:'js'}));
}}]});
const {AuthProvider} = await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
function reset(){delete globalThis.caches;Object.assign(h,{states:[],refs:[],callbacks:[],effectDeps:[],effects:[],cleanups:[],requests:[],token:'A',refresh:'refresh-A',generation:1,sessionFailure:false,makeDeferred:deferred,disable:deferred(),serverLogout:deferred(),logoutBearers:[],clears:[],reconcileGate:null});}
function render(){h.si=h.ri=h.ei=h.ci=0;return AuthProvider({children:null}).props.value;}
function mount(){reset();const value=render();for(const fn of h.effects.splice(0))h.cleanups.push(fn());return value;}
const A={id:101,name:'Account A'}, B={id:202,name:'Account B'};
function loginB(){return render().login('B','refresh-B',B,String(h.generation));}
function assertB(){const value=render();assert.equal(h.token,'B','B token must survive');assert.equal(value.status,'authed','B must stay authenticated');assert.equal(value.user?.id,202,'B must keep its own profile');}
async function readyA(){mount();h.requests[0].resolve(A);await flush();assert.equal(render().user.id,101);}
let failed=0,total=0;
async function test(label,fn){total++;try{await fn();console.log('PASS '+label);}catch(e){failed++;console.error('FAIL '+label+': '+e.message);}finally{for(const fn of h.cleanups??[])fn?.();}}

await test('startup success from A cannot replace login B',async()=>{
  mount();await loginB();h.requests[0].resolve(A);await flush();assertB();
});
await test('an aborted replacement cannot publish the new profile',async()=>{
  mount();h.sessionFailure=true;await assert.rejects(loginB);
  h.requests[0].resolve(A);await flush();assert.equal(render().status,'authed');assert.equal(render().user.id,101);
});
await test('failed replacement preserves the previous profile refresh',async()=>{
  await readyA();const pending=render().refresh();h.sessionFailure=true;await assert.rejects(loginB);
  h.requests[1].resolve(A);await pending;assert.equal(render().status,'authed');assert.equal(render().user.id,101);
});
await test('startup rejected request from A cannot make B a guest',async()=>{
  mount();await loginB();h.requests[0].reject(new Error('session changed / old request failed'));await flush();assertB();
});
await test('late manual profile refresh cannot replace B',async()=>{
  await readyA();const refresh=render().refresh();await loginB();h.requests[1].resolve(A);await refresh;assertB();
});
await test('logout waiting for reconciliation never signs out a newer account',async()=>{
  await readyA();h.reconcileGate=deferred();const logout=render().logout();await loginB();h.reconcileGate.resolve();await logout;
  assert.deepEqual(h.logoutBearers,[]);assertB();assert.deepEqual(h.clears,[]);
});
await test('guest is published while logout server response is still pending',async()=>{
  await readyA();await render().logout();assert.equal(render().status,'guest');assert.deepEqual(h.logoutBearers,['A']);
  await loginB();h.serverLogout.resolve({ok:true});await flush();assertB();assert.deepEqual(h.clears,['outbox','drafts','personal']);
});
await test('normal startup refresh and logout still work',async()=>{
  await readyA();const refresh=render().refresh();h.requests[1].resolve({...A,name:'Updated A'});await refresh;assert.equal(render().user.name,'Updated A');
  h.disable.resolve();h.serverLogout.resolve({ok:true});await render().logout();assert.equal(h.token,null);assert.equal(render().status,'guest');assert.equal(render().user,null);assert.deepEqual(h.clears,['outbox','drafts','personal']);
});
await test('rejected server logout does not restore the committed account',async()=>{
  await readyA();await render().logout();h.serverLogout.reject(new Error('terminal 401'));await flush();
  assert.equal(render().status,'guest');assert.equal(render().user,null);
  assert.deepEqual(h.clears,['outbox','drafts','personal']);
});
await test('terminal 401 outside explicit logout clears personal data',async()=>{
  await readyA();h.token=null;h.refresh=null;h.generation++;h.unauthorizedHandler();
  assert.equal(render().status,'guest');assert.equal(render().user,null);
  assert.deepEqual(h.clears,['outbox','drafts','personal'],'Automatic session expiry must clear personal local data');
});
await test('deferred cache enumeration from logout A cannot delete B caches',async()=>{
  await readyA();const keys=deferred(),deleted=[];
  globalThis.caches={keys:()=>keys.promise,delete:async key=>{deleted.push(key);return true;}};
  h.disable.resolve();h.serverLogout.resolve({ok:true});await render().logout();
  await loginB();keys.resolve(['runtime-personal']);await flush();
  assertB();assert.deepEqual(deleted,[],'Delayed cache cleanup must not delete the new session cache');
});
console.log(`AuthProvider: ${total-failed} passed, ${failed} failed`);
if(failed)process.exitCode=1;
