// Real storage/API/queue helpers. All fetches are synthetic; no product requests.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { storedActions } from './outbox-helpers.mjs';
const built = await build({ stdin: { contents: `
 export * from './src/api/client'; export * from './src/utils/outbox'; export {logoutServer} from './src/api/auth';
 export * from './src/utils/formDraft'; export * from './src/utils/privacy';
 export * from './src/flags'; export * from './src/filterPrefs';
 export * from './src/utils/pendingPayment'; export * from './src/utils/tripPass';
 `, resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
 bundle: true, write: false, platform: 'node', format: 'esm', define: { 'import.meta.env': '{}' } });
let serial = 0, total = 0, failed = 0;
function storage(data) { return { get length() { return data.size; }, key: i => [...data.keys()][i] ?? null,
 getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, String(v)), removeItem: k => data.delete(k) }; }
async function check(name, run) {
 total++;globalThis.indexedDB = new IDBFactory();
 const data = new Map();globalThis.localStorage = storage(data);globalThis.sessionStorage = storage(new Map());
 globalThis.fetch = async () => { throw new Error('Unexpected network'); };
 const api = await import('data:text/javascript;base64,' + Buffer.from(built.outputFiles[0].text + `\n// ${serial++}`).toString('base64'));
 try { await run(api, data);console.log('PASS '+name); } catch(e) { failed++;console.error('FAIL '+name+': '+e.message); }
}
function seedPersonal(api, label) {
 api.writeDraft('taxi', { private: label });
 api.flags.setRole(label === 'B' ? 'driver' : 'passenger');
 api.saveFilters({ city: label, maxPrice: null, amenities: [], onlyTrusted: false });
 api.rememberPayment(label === 'B' ? 202 : 101, 'trip', '/trip');
 api.saveTripPass({ bookingId: 42, driverPhone: label, boardingCode: label });
}
for (const mode of ['modern', 'v1', 'legacy', 'markerless']) for (const replacement of ['login', 'logout']) {
 await check(`${mode}: terminal401 interleaving preserves B ${replacement} and B data`, async (api, data) => {
  if (mode === 'modern') api.setSession('A', null);
  else if(mode==='v1')data.set('yuldash.session',JSON.stringify({version:1,generation:'v1-A',access:'A',refresh:null}));
  else { data.set('yuldash.token', 'A');if(mode === 'legacy')data.set('yuldash.session', 'legacy-A'); }
  const ownerA = api.getSessionGeneration(), mainA = data.get('yuldash.session'), payloadA = data.get('yuldash.session.credentials.'+api.getSessionGeneration());
  seedPersonal(api, 'A');await api.enqueue(42, 'message', 'A');
  api.setSession('B', null);const ownerB = api.getSessionGeneration();
  seedPersonal(api, 'B');await api.enqueue(99, 'message', 'B');
  const mainB = data.get('yuldash.session');
  if (mainA === undefined) data.delete('yuldash.session');else data.set('yuldash.session', mainA);
  if(payloadA!==undefined)data.set('yuldash.session.credentials.'+ownerA,payloadA);
  if(mode !== 'modern')data.set('yuldash.token','A');
  const cleanup = [];
  api.setUnauthorizedHandler(owner => { cleanup.push(api.clearOutbox(owner));api.clearAllDrafts(owner);api.clearPersonalLocal(owner); });
  const original = localStorage.getItem;let reads = 0;
  localStorage.getItem = key => {
   const value = original(key);
   if(key === 'yuldash.session' && ++reads === 3) {
    data.set('yuldash.session', mainB);
    if(replacement === 'logout')api.setSession(null,null);
   }
   return value;
  };
  globalThis.fetch = async () => new Response('{}',{status:401});
  await assert.rejects(api.apiGet('/private'));await Promise.all(cleanup);
  if(replacement === 'login') {
   assert.equal(api.getToken(),'B');assert.equal(api.getSessionGeneration(),ownerB);
   assert.deepEqual(api.readDraft('taxi'),{private:'B'});assert.equal(api.flags.role(),'driver');
   assert.equal(api.loadFilters().city,'B');assert.equal(api.readPendingPayment()?.paymentId,202);
   assert.equal(api.loadTripPass(42)?.driverPhone,'B');
   assert.equal((await storedActions()).some(a=>a.session===ownerB&&a.payload==='B'),true);
  } else assert.equal(api.getToken(),null);
  assert.equal((await storedActions()).some(a=>a.session===ownerA),false);
 });
}
await check('ordinary terminal401 invalidates owner and physically removes owned data',async(api,data)=>{
 api.setSession('A',null);const owner=api.getSessionGeneration();seedPersonal(api,'A');await api.enqueue(42,'message','A');
 const cleanup=[];api.setUnauthorizedHandler(expected=>{cleanup.push(api.clearOutbox(expected));api.clearAllDrafts(expected);api.clearPersonalLocal(expected);});
 globalThis.fetch=async()=>new Response('{}',{status:401});await assert.rejects(api.apiGet('/private'));await Promise.all(cleanup);
 assert.equal(api.getToken(),null);assert.notEqual(api.getSessionGeneration(),owner);
 assert.equal(api.readDraft('taxi'),null);assert.equal(api.loadTripPass(42),null);assert.equal(api.readPendingPayment(),null);
 assert.equal((await storedActions()).length,0);
 assert.equal([...data.values()].some(value=>value.includes('"private":"A"')),false);
});
await check('ownerless legacy personal records are not inherited by a new account',async(api,data)=>{
 data.set('yuldash.role','driver');data.set('yuldash.filterPrefs',JSON.stringify({city:'A'}));
 data.set('yuldash.pendingPayment',JSON.stringify({paymentId:101,at:Date.now()}));
 data.set('yuldash.tripPass.42',JSON.stringify({bookingId:42,driverPhone:'A',boardingCode:'private'}));
 api.setSession('B','refresh-B');
 assert.equal(api.flags.role(),'passenger');assert.equal(api.loadFilters().city,'');
 assert.equal(api.readPendingPayment(),null);assert.equal(api.loadTripPass(42),null);
 // Shared old-format keys are not deleted concurrently with old-version writers.
 assert.equal(data.has('yuldash.tripPass.42'),true);
});
await check('revocation survives a late rotation write and reload',async(api,data)=>{
 api.setSession('A','refresh-A');const owner=api.getSessionGeneration();
 data.set('yuldash.session.revoked.'+owner,'1');
 data.set('yuldash.session.rotation.'+owner,JSON.stringify({version:1,generation:owner,access:'late-A',refresh:'late-refresh-A'}));
 assert.equal(api.getToken(),null);assert.equal(api.getRefreshToken(),null);assert.notEqual(api.getSessionGeneration(),owner);
 assert.equal(api.rotateSession('later-A','later-refresh',owner),false);
});
await check('failed durable revoke reports unavailable and does not falsely log out',async(api)=>{
 api.setSession('A',null);const owner=api.getSessionGeneration(),calls=[],original=localStorage.setItem;
 api.setUnauthorizedHandler((expected,persisted)=>calls.push([expected,persisted]));
 localStorage.setItem=(key,value)=>{if(key.startsWith('yuldash.session.revoked.'))throw new Error('Quota');return original(key,value);};
 globalThis.fetch=async()=>new Response('{}',{status:401});await assert.rejects(api.apiGet('/private'));
 assert.deepEqual(calls,[[owner,false]]);assert.equal(api.getToken(),'A');assert.equal(api.getSessionGeneration(),owner);
});
await check('exact-owner legacy drafts and consents survive upgrade without being assigned to B',async(api,data)=>{
 api.setSession('A','refresh-A');const owner=api.getSessionGeneration();
 data.set('yuldash.draft.legacy',JSON.stringify({at:Date.now(),session:owner,data:{private:'A'}}));
 data.set('yuldash.consents',JSON.stringify({session:owner,offer:true}));
 assert.deepEqual(api.readDraft('legacy'),{private:'A'});assert.equal(api.flags.consents().offer,true);
 api.setSession('B','refresh-B');assert.equal(api.readDraft('legacy'),null);assert.equal(api.flags.consents().offer,false);
 api.clearAllDrafts(owner);api.clearPersonalLocal(owner);
 assert.equal(data.has('yuldash.draft.legacy'),true);assert.equal(data.has('yuldash.consents'),true);
});
await check('A namespace cleanup cannot remove B replacements during deletion',async(api,data)=>{
 api.setSession('A','refresh-A');const owner=api.getSessionGeneration();seedPersonal(api,'A');
 const original=localStorage.removeItem;let changed=false;
 localStorage.removeItem=key=>{if(!changed&&key.startsWith('yuldash.owner.')){changed=true;api.setSession('B','refresh-B');seedPersonal(api,'B');}return original(key);};
 api.clearAllDrafts(owner);api.clearPersonalLocal(owner);
 assert.equal(api.getToken(),'B');assert.deepEqual(api.readDraft('taxi'),{private:'B'});
 assert.equal(api.loadFilters().city,'B');assert.equal(api.loadTripPass(42)?.driverPhone,'B');
});
await check('late cleanup A cannot abort B enqueue with stale UI snapshot A',async(api)=>{
 api.setSession('A',null);const ownerA=api.getSessionGeneration();await api.enqueue(1,'message','A');
 api.setSession('B',null);const pending=api.enqueue(2,'message','B');pending.catch(()=>{});await api.clearOutbox(ownerA);await pending;
 assert.equal((await storedActions()).some(a=>a.payload==='B'),true);
});
await check('clear before first UI snapshot aborts same-owner pending insert',async(api)=>{
 api.setSession('A',null);const pending=api.enqueue(1,'message','A');
 const rejected=assert.rejects(pending);await api.clearOutbox();await rejected;
 assert.equal((await storedActions()).length,0);
});
await check('logout expected owner never sends B bearer after account switch',async(api)=>{
 api.setSession('A',null);const owner=api.getSessionGeneration();api.setSession('B','refresh-B');
 const calls=[];globalThis.fetch=async(...args)=>{calls.push(args);return new Response('{}');};
 await assert.rejects(api.logoutServer(owner));assert.deepEqual(calls,[]);assert.equal(api.getToken(),'B');
});
await check('logout snapshot interleaving sends A or aborts but never B credentials',async(api)=>{
 api.setSession('A','refresh-A');const owner=api.getSessionGeneration(),read=localStorage.getItem;let injected=false;
 localStorage.getItem=key=>{const value=read(key);if(!injected&&key==='yuldash.session'){injected=true;api.setSession('B','refresh-B');}return value;};
 const calls=[];globalThis.fetch=async(_url,init)=>{calls.push(init.headers.Authorization);return new Response('{}');};
 await assert.rejects(api.logoutServer(owner));assert.equal(calls.includes('Bearer B'),false);assert.equal(api.getToken(),'B');
});
for(const key of ['yuldash.draft.legacy','yuldash.consents']) await check(`legacy ${key} fallback rechecks owner after read`,async(api,data)=>{
 api.setSession('A',null);const owner=api.getSessionGeneration(),read=localStorage.getItem;
 data.set(key,JSON.stringify({session:owner,at:Date.now(),data:{private:'A'},offer:true}));
 let injected=false;localStorage.getItem=k=>{const value=read(k);if(k===key&&!injected){injected=true;api.setSession('B',null);}return value;};
 if(key.includes('draft'))assert.equal(api.readDraft('legacy',owner),null);else assert.equal(api.flags.consents(owner).offer,false);
 assert.equal(injected,true);
});
await check('outbox dispatch interleavings never send A body with B bearer',async(api,data)=>{
 api.setSession('A',null);await api.enqueue(1,'message','PRIVATE_A');const main=data.get('yuldash.session');
 const read=localStorage.getItem;let reads=0;localStorage.getItem=key=>{const value=read(key);if(key==='yuldash.session'&&++reads===7)api.setSession('B',null);return value;};
 const calls=[];globalThis.fetch=async(_url,init)=>{calls.push([init.headers.Authorization,init.body]);return new Response('{}');};
 await api.flushOutbox();assert.equal(calls.some(([bearer,body])=>bearer==='Bearer B'&&body.includes('PRIVATE_A')),false);
 assert.notEqual(data.get('yuldash.session'),main);
});
await check('second401 after successful refresh cannot revoke B at terminal boundary',async(api,data)=>{
 api.setSession('A','refresh-A');const owner=api.getSessionGeneration();seedPersonal(api,'A');
 api.setRefreshHandler(async()=>api.rotateSession('A-new','refresh-A-new',owner));
 const cleanup=[];api.setUnauthorizedHandler(expected=>{cleanup.push(api.clearOutbox(expected));api.clearAllDrafts(expected);api.clearPersonalLocal(expected);});
 let fetches=0;const bearers=[];
 globalThis.fetch=async(_url,init)=>{
  bearers.push(init.headers.Authorization);if(++fetches===1)return new Response('{}',{status:401});
  return {get status(){api.setSession('B','refresh-B');seedPersonal(api,'B');return 401;}};
 };
 await assert.rejects(api.apiGet('/private'));await Promise.all(cleanup);
 assert.deepEqual(bearers,['Bearer A','Bearer A-new']);assert.equal(api.getToken(),'B');
 assert.deepEqual(api.readDraft('taxi'),{private:'B'});assert.equal(api.loadTripPass(42)?.driverPhone,'B');
 assert.equal(data.has('yuldash.session.credentials.'+owner),false);
});
console.log(`Terminal session ownership: ${total-failed} passed, ${failed} failed`);if(failed)process.exitCode=1;
