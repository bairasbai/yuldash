import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';
const root=fileURLToPath(new URL('../',import.meta.url));
const baseline=process.argv.includes('--baseline');
const old='C:/Users/Bayra/Yuldash/test-results/wave1-storage-failure-20261016/before/';
const tokenA="test."+Buffer.from(JSON.stringify({sub:"7"})).toString("base64url")+".test";
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve};};
const flush=async()=>{for(let i=0;i<6;i++)await new Promise(setImmediate);};
let serial=0,passed=0,failed=0;
function setup(){
 const h={all:false,idb:false,cache:false,held:null,holdAt:null,started:false,local:new Map(),session:new Map()};
 const store=values=>({getItem(k){if(h.all)throw Error('storage read denied');return values.get(k)??null;},setItem(k,v){if(h.all)throw Error('storage write denied');values.set(k,String(v));},removeItem(k){if(h.all)throw Error('storage remove denied');values.delete(k);},key(i){if(h.all)throw Error('storage key denied');return [...values.keys()][i]??null;},get length(){if(h.all)throw Error('storage length denied');return values.size;}});
 globalThis.localStorage=store(h.local);globalThis.sessionStorage=store(h.session);globalThis.window=new EventTarget();window.location={origin:'http://qa.test'};globalThis.document=new EventTarget();
 let queue=Promise.resolve();const locks={request(name,fn){const run=queue.catch(()=>{}).then(()=>fn());queue=run.catch(()=>{});return run;}};
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{locks,onLine:true}});
 function heldRequest(req){return new Proxy(req,{set(target,key,value){if(key==='onsuccess'){target[key]=async e=>{h.started=true;await h.held.promise;value.call(req,e)};return true;}target[key]=value;return true;},get(target,key){return Reflect.get(target,key,target);}});}
 const factory=new IDBFactory();globalThis.indexedDB={open(...args){if(h.idb)throw Error('IDB open denied');const req=factory.open(...args);return h.holdAt==='open'&&args[0]==='yuldash-outbox-v1'?heldRequest(req):req;},deleteDatabase(...args){if(h.idb)throw Error('IDB delete denied');const req=factory.deleteDatabase(...args);return h.holdAt==='delete:'+args[0]?heldRequest(req):req;}};
 h.cacheNames=new Set(['workbox-precache-v2-http://qa.test/','legacy-private']);h.cacheEntries=new Map([['http://qa.test/index.html?__WB_REVISION__=abc',new Request('http://qa.test/index.html?__WB_REVISION__=abc')],['http://qa.test/me',new Request('http://qa.test/me')]]);
 globalThis.caches={async keys(){if(h.cache)throw Error('Cache keys denied');return [...h.cacheNames];},async delete(name){if(h.cache)throw Error('Cache delete denied');if(h.holdAt==='cache'){h.started=true;await h.held.promise;}return h.cacheNames.delete(name);},async open(){if(h.cache)throw Error('Cache open denied');return {keys:async()=>[...h.cacheEntries.values()],delete:async r=>h.cacheEntries.delete(r.url)};}};
 h.local.set('yuldash.token',tokenA);h.local.set('yuldash.refresh','refresh-A');h.local.set('yuldash.session','A');h.local.set('yuldash.lang','ba');h.local.set('yuldash.theme','dark');h.local.set('unrelated','retain');return h;
}
async function load(){
 const extra=baseline?[{name:'real-before-stage',setup(b){b.onLoad({filter:/webapp[\\/]src[\\/](api[\\/]client|utils[\\/]tripPass)\.ts$/},a=>{const relative=a.path.slice(a.path.replaceAll('\\','/').indexOf('/webapp/')+1).replaceAll('\\','/');return {contents:fs.readFileSync(old+relative,'utf8'),loader:'ts'};});}}]:[];
 const result=await build({stdin:{contents:`export * from './src/utils/storageHealth';export * from './src/api/client';export * from './src/utils/tripPass';export * from './src/utils/offlineTripPass';export * from './src/utils/tripPassStore';export * from './src/utils/storageRecovery';export * from './src/utils/outbox';export * from './src/i18n/languagePreference';export {default as Boundary} from './src/components/StorageBoundary';`,resolveDir:root,loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',define:{'import.meta.env':'{"BASE_URL":"/","VITE_API_BASE":"http://qa.api"}'},footer:{js:'// isolated-document-'+serial++},plugins:extra});
 return import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
}
const pass={bookingId:42,rideId:4,fromCity:'QA from',toCity:'QA to',departAt:'2026-10-16T18:00:00Z',driverName:'QA Driver',driverCar:'QA Car',driverPlate:'QA001',driverPhone:'+79990000000',boardingCode:'4931',pickup:'QA pickup',price:300,seats:1};
async function test(id,fn){try{const h=setup(),m=await load();m.activateStorageFence();if(!baseline)await m.initializeSessionStorage();await fn(h,m);passed++;console.log('✓ '+id);}catch(e){failed++;console.error('FAIL '+id+': '+e.message);}}
const owner={userId:7,generation:'A'};
const save=async m=>{
 if(baseline)return m.saveVerifiedTripPass(pass,7,'A');
 await m.observeTripPass(owner,42,{status:'confirmed'},null,pass);
 await m.saveTripPassCode(owner,42,null,'4931');
};
const read=async m=>baseline?m.loadOfflineTripPass(42):(await m.readOfflineTrip(42))?.pass??null;
await test('S01 normal credentials remain readable',async(h,m)=>{assert.equal(m.getToken(),tokenA);assert.equal(m.getSessionGeneration(),'A');});
await test('S02 normal owned offline pass remains available',async(h,m)=>{await save(m);assert.equal((await read(m))?.boardingCode,'4931');});
await test('S03 denied credential read closes access instead of throwing',async(h,m)=>{h.idb=true;await assert.rejects(m.reconcileSession());assert.equal(m.getToken(),null);assert(m.storageFenced());});
await test('S04 used code stays closed after the boarding transaction fails',async(h,m)=>{
 await save(m);
 if(baseline){h.all=true;m.clearTripPassBoardingCode(42,'A');h.all=false;assert.notEqual(m.tripPassStage(42,'A'),'confirmed');assert.notEqual(m.loadTripPass(42)?.boardingCode,'4931');return;}
 h.idb=true;await m.observeTripPass(owner,42,{status:'onboard',onboard_at:'2026-10-24T12:00:00Z'},undefined);h.idb=false;
 assert(m.storageFenced());assert.equal(await read(m),null);
});
await test('S05 failed terminal write closes access and recovery revokes the prior pass',async(h,m)=>{
 await save(m);
 if(baseline){h.all=true;m.dropTripPass(42,'A');h.all=false;assert.equal(m.tripPassStage(42,'A'),'terminal');assert.equal(m.loadTripPass(42),null);return;}
 h.idb=true;await m.observeTripPass(owner,42,{status:'cancelled'},undefined);h.idb=false;
 assert(m.storageFenced());assert.equal(await read(m),null);
 await m.recoverStorage();
 assert.equal(await m.readTripRecords(owner,()=>true),null);
});
await test('S06 quarantine changes runtime generation without a disk write',async(h,m)=>{h.all=true;m.probeStorage(false);assert.notEqual(m.getSessionGeneration(),'A');});
if(!baseline){
await test('S07 healed storage does not reopen a quarantined pass',async(h,m)=>{await save(m);h.all=true;m.probeStorage(false);h.all=false;assert.equal(m.getToken(),null);assert.equal(await read(m),null);});
await test('S08 committed cleanup preserves device prefs/public shell/foreign namespace',async(h,m)=>{await save(m);m.reportStorageFailure();h.local.set('yuldash.tripPass.42','legacy snapshot');h.session.set('yuldash.draft.qa','private');h.all=true;m.probeStorage(false);h.all=false;await m.recoverStorage();assert(!m.storageFenced());assert.equal(h.local.get('yuldash.token'),undefined);assert.equal(h.local.get('yuldash.tripPass.42'),undefined);assert.equal(h.local.get('yuldash.lang'),'ba');assert.equal(h.local.get('yuldash.theme'),'dark');assert.equal(h.local.get('unrelated'),'retain');assert(!h.session.has('yuldash.draft.qa'));assert(h.cacheNames.has('workbox-precache-v2-http://qa.test/'));assert(!h.cacheNames.has('legacy-private'));assert(!h.cacheEntries.has('http://qa.test/me'));});
await test('S09 retry with all storage still denied stays closed',async(h,m)=>{h.all=true;m.probeStorage(false);await assert.rejects(m.recoverStorage());assert(m.storageFenced());});
await test('S10 no Web Locks never starts destructive recovery',async(h,m)=>{m.reportStorageFailure();delete navigator.locks;await assert.rejects(m.recoverStorage());const other=await load();await other.initializeSessionStorage();assert.equal(other.getToken(),tokenA);assert(m.storageFenced());});
await test('S11 failed CacheStorage cleanup cannot complete recovery',async(h,m)=>{m.reportStorageFailure();h.cache=true;await assert.rejects(m.recoverStorage());assert(m.storageFenced());h.cache=false;await m.recoverStorage();assert(!m.storageFenced());});
await test('S12 failed IndexedDB cleanup cannot complete recovery',async(h,m)=>{m.reportStorageFailure();h.idb=true;await assert.rejects(m.recoverStorage());assert(m.storageFenced());h.idb=false;await m.recoverStorage();assert(!m.storageFenced());});
await test('S13 pending delete retains lock and duplicate Retry joins it',async(h,m)=>{m.reportStorageFailure();h.holdAt='cache';h.held=deferred();const recovery=m.recoverStorage();assert.equal(m.recoverStorage(),recovery);while(!h.started)await flush();let settled=false;const b=Promise.resolve(m.setSession('token-B','refresh-B')).then(()=>settled=true,()=>settled=true);await new Promise(r=>setTimeout(r,25));assert.equal(settled,false);assert(m.storageFenced());h.held.resolve();await recovery;await b;assert.equal(h.local.get('yuldash.token'),undefined);await m.setSession('token-B','refresh-B');assert.equal(m.getToken(),'token-B');assert.equal(h.local.get('yuldash.token'),undefined);});
await test('S14 recovery A never clears already committed B',async(h,m)=>{await save(m);m.reportStorageFailure();const other=await load();other.activateStorageFence();await other.initializeSessionStorage();await other.setSession('token-B','refresh-B');await m.recoverStorage();assert.equal(m.getToken(),'token-B');assert.equal(await read(m),null);assert(!m.offlineStorageTrusted());});
await test('S15 a cold storage failure preserves a verified durable pair',async(h)=>{const m=await load();m.activateStorageFence();h.all=true;m.probeStorage(false);h.all=false;await m.recoverStorage();assert.equal(m.getToken(),tokenA);});
await test('S16 an unconfirmed legacy notification cannot recover old credentials',async(h)=>{const m=await load();m.activateStorageFence();h.all=true;m.probeStorage(false);h.all=false;h.local.set('yuldash.session','B');h.local.set('yuldash.token','token-B');const event=new Event('storage');Object.assign(event,{key:'yuldash.session',newValue:'B'});window.dispatchEvent(event);await m.recoverStorage();assert.equal(m.getToken(),tokenA);assert(!m.offlineStorageTrusted());});
await test('S17 old refresh publication queued before quarantine cannot write later',async(h,m)=>{const held=deferred();const lock=navigator.locks.request('yuldash-session-write',()=>held.promise);await flush();const old=Promise.resolve(m.rotateSession('old-response','old-refresh','A'));m.reportStorageFailure();held.resolve();await lock;await assert.rejects(old);const other=await load();await other.initializeSessionStorage();assert.equal(other.getToken(),tokenA);});
await test('S18 a late old save cannot overwrite a newer snapshot',async(h,m)=>{await save(m);m.reportStorageFailure();const before=await m.readTripRecords(owner,()=>true);await m.observeTripPass(owner,42,{status:'confirmed'},null,{...pass,driverName:'OLD'});assert.deepEqual(await m.readTripRecords(owner,()=>true),before);});
await test('S19 pending queue open is revoked before commit and memory resets',async(h,m)=>{h.holdAt='open';h.held=deferred();const old=m.enqueue(42,'message','OLD_A','qa-old');while(!h.started)await flush();m.reportStorageFailure();m.resetOutboxMemory();h.held.resolve();await assert.rejects(old);h.holdAt=null;assert.equal(m.outboxCount(42),0);await m.recoverStorage();assert.equal(m.outboxCount(42),0);});
await test('S20 ordinary app failure is not presented as storage failure',async(h,m)=>{const b=new m.Boundary({children:'PRIVATE'}),error=Error('ordinary application failure');b.state={blocked:true,error,version:0};assert.throws(()=>b.render(),error);assert(!m.storageFenced());});
await test('S21 direct failure notification survives storage healing before global error',async(h,m)=>{const b=new m.Boundary({children:'PRIVATE'});b.setState=patch=>Object.assign(b.state,patch);h.all=true;m.probeStorage(false);h.all=false;const event=new Event('unhandledrejection',{cancelable:true});b.asyncError(event);assert(b.state.blocked);assert(event.defaultPrevented);});
for(const [i,name] of ['yuldash-outbox-v1','yuldash-refresh-intents'].entries())await test(`S${22+i} B cannot publish during pending ${name} deletion`,async(h,m)=>{m.reportStorageFailure();h.holdAt='delete:'+name;h.held=deferred();const recovery=m.recoverStorage();while(!h.started)await flush();let settled=false;const waiting=Promise.resolve(m.setSession('B','RB')).finally(()=>settled=true);const error=waiting.catch(e=>e);await flush();assert.equal(settled,false);h.held.resolve();await recovery;assert(await error instanceof Error);await m.setSession('B','RB');assert.equal(m.getToken(),'B');});
await test('S24 closing a pending queue connection retains the same recovery lock',async(h,m)=>{h.holdAt='open';h.held=deferred();const old=m.enqueue(42,'message','old','held-open').catch(e=>e);while(!h.started)await flush();m.reportStorageFailure();m.resetOutboxMemory();const recovery=m.recoverStorage();await flush();let settled=false;const waiting=Promise.resolve(m.setSession('B','RB')).finally(()=>settled=true);const error=waiting.catch(e=>e);await flush();assert.equal(settled,false);h.held.resolve();assert(await old instanceof Error);h.holdAt=null;await recovery;assert(await error instanceof Error);});
await test('S25 language write failure leaves completed recovery open',async(h,m)=>{
 const open=globalThis.indexedDB.open.bind(globalThis.indexedDB);
 globalThis.indexedDB.open=(name,...args)=>{
   if(name==='yuldash-device-language-v1')throw Error('Language database unavailable');
   return open(name,...args);
 };
 const boundary=new m.Boundary({children:null});
 boundary.setState=update=>{boundary.state={...boundary.state,...update(boundary.state)};};
 m.reportStorageFailure();
 await boundary.retry('ru');
 await flush();
 assert.equal(boundary.state.blocked,false);
 assert.equal(m.storageFenced(),false);
 assert.equal(m.getLanguage(),'ba');
 assert.equal(m.getLanguageSaveStatus(),'failed');
 assert.equal(h.local.has('yuldash.token'),false);
});
}
console.log(`Storage boundary: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
