import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const h = globalThis.__fullOfflineTrip = { status: 'unavailable', lang: 'ru' };
const values = new Map();
globalThis.localStorage = {
  getItem(k) { h.getHook?.(k); if (h.readFail) throw Error('disk unavailable'); return values.get(k) ?? null; },
  setItem(k,v) { if (h.writeFail || k === h.failKey) throw Error('disk full'); values.set(k,String(v)); h.setHook?.(k); },
  removeItem: k => values.delete(k), key: i => [...values.keys()][i] ?? null,
  get length() { return values.size; },
};
globalThis.sessionStorage = { ...localStorage, get length() { return values.size; } };
globalThis.window = new EventTarget();
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: false } });
const mocks = {
  react: `const h=globalThis.__fullOfflineTrip;export const useState=v=>[typeof v==='function'?v():v,()=>h.renders++];export const useEffect=fn=>{h.cleanup.push(fn())};`,
  'react/jsx-runtime': `export const jsx=(type,props)=>({type,props}),jsxs=jsx,Fragment='fragment';`,
  'react-router-dom': `export const useParams=()=>({id:String(globalThis.__fullOfflineTrip.bookingId)}),useLocation=()=>({pathname:'/trip/42'}),Navigate='Navigate';`,
  auth: `export const useAuth=()=>({status:globalThis.__fullOfflineTrip.status,retrySession:()=>globalThis.__fullOfflineTrip.retries++});`,
  lang: `export const useLang=()=>({lang:globalThis.__fullOfflineTrip.lang,appText:(ru,ba)=>globalThis.__fullOfflineTrip.lang==='ba'?ba:ru});`,
  client: `export const getToken=()=>localStorage.getItem('yuldash.token'),getSessionGeneration=()=>localStorage.getItem('yuldash.session')??'';`,
};
async function compile(entry) {
 const result=await build({entryPoints:[root+entry],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',define:{'import.meta.env':'{}'},plugins:[{name:'controlled-boundaries',setup(b){
  b.onResolve({filter:/.*/},a=>{const p=a.path,k=Object.hasOwn(mocks,p)?p:p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/i18n/lang')?'lang':p.endsWith('/api/client')?'client':null;return k?{path:k,namespace:'boundary'}:null;});
  b.onLoad({filter:/.*/,namespace:'boundary'},a=>({contents:mocks[a.path],loader:'js'}));
 }}]});return import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
}
const {default:RequireAuth}=await compile('src/components/RequireAuth.tsx');
let failed=0,total=0;
async function test(id,fn){total++;try{reset();await fn();console.log('PASS '+id);}catch(e){failed++;console.error('FAIL '+id+': '+e.message);}finally{for(const fn of h.cleanup)fn?.();}}
function reset(){values.clear();Object.assign(h,{bookingId:42,status:'unavailable',lang:'ru',readFail:false,writeFail:false,failKey:null,getHook:null,setHook:null,renders:0,retries:0,cleanup:[]});navigator.onLine=false;values.set('yuldash.token','local-fixture-token');values.set('yuldash.session','session-A');}
const expand=n=>Array.isArray(n)?n.map(expand):n&&typeof n==='object'?typeof n.type==='function'?expand(n.type(n.props)):{...n,props:{...n.props,children:expand(n.props?.children)}}:n;
const text=n=>n==null||n===false?'':Array.isArray(n)?n.map(text).join(''):typeof n==='object'?text(n.props?.children):String(n);
for(const lang of ['ru','ba'])await test('F07-gate-'+lang,()=>{h.lang=lang;const t=text(expand(RequireAuth({children:'PRIVATE',unavailableContent:'SAVED ONLY'})));assert(t.includes('SAVED ONLY'));assert(!t.includes('PRIVATE'));});
// Initial RED runs just the existing auth gate; later runs exercise the new production loader/UI too.
if (!process.argv.includes('--gate-only')) {
 const {saveVerifiedTripPass,loadOfflineTripPass}=await compile('src/utils/offlineTripPass.ts');
 const {default:Fallback}=await compile('src/components/OfflineTripFallback.tsx');
 const {default:PassCard}=await compile('src/components/OfflineTripPass.tsx');
 const {clearTripPassBoardingCode,dropTripPass}=await compile('src/utils/tripPass.ts');
 const realClient=await compile('src/api/client.ts');
 const {clearPersonalLocal}=await compile('src/utils/privacy.ts');
 const cancellation=await compile('src/utils/inTransitCancel.ts');
 const pass={bookingId:42,fromCity:'Уфа',toCity:'Сибай',departAt:'2026-10-16T18:00:00+05:00',driverName:'QA Driver',driverCar:'QA Car',driverPlate:'QA001',driverPhone:'+79990000000',boardingCode:'4931',pickup:'QA pickup',price:300,seats:1};
 const save=()=>saveVerifiedTripPass(pass,7,'session-A');
 const edit=(key,fn)=>values.set(key,JSON.stringify(fn(JSON.parse(values.get(key)))));
 const key='yuldash.tripPass.42',owner='yuldash.tripPass.42.owner';
 await test('F07-store-own',()=>{save();assert.equal(loadOfflineTripPass(42)?.boardingCode,'4931');assert.equal(loadOfflineTripPass(42)?.ownerId,7);});
 for(const [id,mutate] of [
  ['legacy',()=>edit(key,p=>{delete p.ownerId;delete p.sessionGeneration;return p;})],
  ['wrong-pass-owner',()=>edit(key,p=>({...p,ownerId:8}))],
  ['wrong-pass-generation',()=>edit(key,p=>({...p,sessionGeneration:'session-B'}))],
  ['wrong-marker-owner',()=>edit(owner,p=>({...p,ownerId:8}))],
  ['wrong-marker-generation',()=>edit(owner,p=>({...p,generation:'session-B'}))],
  ['missing-marker',()=>values.delete(owner)],['broken-marker',()=>values.set(owner,'{')],
  ['empty-generation',()=>values.set('yuldash.session','')],['changed-generation',()=>values.set('yuldash.session','session-B')],
  ['no-token',()=>values.delete('yuldash.token')],['broken-pass',()=>values.set(key,'{')],
  ['phone-object',()=>edit(key,p=>({...p,driverPhone:{x:1}}))],['bad-price',()=>edit(key,p=>({...p,price:'300'}))],
  ['missing-date',()=>edit(key,p=>{delete p.savedAt;return p;})],['wrong-booking',()=>edit(key,p=>({...p,bookingId:43}))],
  ['read-failure',()=>h.readFail=true],
 ])await test('F07-reject-'+id,()=>{save();mutate();assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-no-pass',()=>assert.equal(loadOfflineTripPass(42),null));
 await test('F07-invalid-route',()=>{save();for(const id of [0,-1,NaN,Infinity,42.5])assert.equal(loadOfflineTripPass(id),null);});
 await test('F07-late-A-save-preserves-B',()=>{save();values.set('yuldash.session','session-B');const before=[...values];saveVerifiedTripPass({...pass,driverName:'LATE A'},7,'session-A');assert.deepEqual([...values],before);assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-new-B-cannot-retag-A',()=>{save();values.set('yuldash.session','session-B');saveVerifiedTripPass({...pass,bookingId:43},8,'session-B');assert.equal(loadOfflineTripPass(42),null);assert.equal(loadOfflineTripPass(43)?.ownerId,8);});
 await test('F07-invalid-save-owner',()=>{for(const id of [undefined,0,-1,NaN,1.5])saveVerifiedTripPass(pass,id,'session-A');assert.equal(values.has(key),false);});
 await test('F07-storage-write-failure',()=>{h.writeFail=true;assert.doesNotThrow(save);assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-marker-write-failure',()=>{h.failKey=owner;save();assert(values.has(key));assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-new-pass-old-marker-mismatch',()=>{save();h.failKey=owner;saveVerifiedTripPass({...pass,boardingCode:'NEW'},7,'session-A');assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-failed-pass-write-preserves-prior-complete-pair',()=>{save();const before=[...values];h.failKey=key;saveVerifiedTripPass({...pass,boardingCode:'NEW'},7,'session-A');assert.deepEqual([...values],before);assert.equal(loadOfflineTripPass(42)?.boardingCode,'4931');});
 for(const boundary of [key,owner])await test('F07-interleaved-B-during-'+boundary,()=>{h.setHook=k=>{if(k!==boundary)return;h.setHook=null;values.set('yuldash.session','session-B');saveVerifiedTripPass({...pass,driverName:'B'},8,'session-B');};save();assert.equal(loadOfflineTripPass(42)?.ownerId,8);assert.equal(loadOfflineTripPass(42)?.driverName,'B');});
 await test('F07-token-disappears-during-read',()=>{save();h.getHook=k=>{if(k===owner){h.getHook=null;values.delete('yuldash.token');}};assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-same-owner-new-login-still-closed',()=>{save();values.set('yuldash.session','new-login-of-same-user');assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-onboard-code-stays-cleared',()=>{save();clearTripPassBoardingCode(42);assert.equal(loadOfflineTripPass(42)?.boardingCode,'');assert.equal(loadOfflineTripPass(42)?.driverPlate,'QA001');});
 await test('F07-review-late-tab-save-cannot-restore-onboard-code',()=>{save();clearTripPassBoardingCode(42,'session-A');saveVerifiedTripPass(pass,7,'session-A');assert.equal(loadOfflineTripPass(42)?.boardingCode,'');});
 await test('F07-review-late-tab-save-cannot-restore-terminal-pass',()=>{save();dropTripPass(42,'session-A');saveVerifiedTripPass(pass,7,'session-A');assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-review-partial-session-write-closes-old-pass',()=>{save();h.failKey='yuldash.session';assert.throws(()=>realClient.setSession('B','refresh-B'));h.failKey=null;assert.equal(loadOfflineTripPass(42),null);});
 for(const failKey of ['yuldash.refresh','yuldash.token'])await test('F07-review-partial-'+failKey,()=>{save();h.failKey=failKey;assert.throws(()=>realClient.setSession('B','refresh-B'));h.failKey=null;assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-review-final-generation-write-failure',()=>{save();h.setHook=k=>{if(k==='yuldash.session'){h.setHook=null;h.failKey=k;}};assert.throws(()=>realClient.setSession('B','refresh-B'));h.failKey=null;assert.equal(loadOfflineTripPass(42),null);});
 for(const state of ['onboard','terminal'])await test('F07-review-stage-changes-mid-save-'+state,()=>{save();h.setHook=k=>{if(k===key){h.setHook=null;if(state==='onboard')clearTripPassBoardingCode(42,'session-A');else dropTripPass(42,'session-A');}};save();const p=loadOfflineTripPass(42);if(state==='terminal')assert.equal(p,null);else assert.equal(p?.boardingCode,'');});
 await test('F07-review-stage-flags-cleared-on-logout',()=>{save();clearTripPassBoardingCode(42,'session-A');dropTripPass(42,'session-A');clearPersonalLocal();assert.equal([...values.keys()].filter(k=>k.startsWith('yuldash.tripPass.')).length,0);});
 await test('F07-terminal-pass-stays-dropped',()=>{save();dropTripPass(42);assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-logout-cleanup',()=>{save();values.set('yuldash.lang','ba');clearPersonalLocal();assert.equal(values.has(key),false);assert.equal(values.has(owner),false);assert.equal(values.get('yuldash.lang'),'ba');});
 for(const lang of ['ru','ba'])await test('F07-offline-ui-'+lang,()=>{save();h.lang=lang;const t=text(expand(Fallback()));assert(t.includes('QA001'));assert(t.includes('4931'));assert(t.includes(lang==='ru'?'Нет связи':'Бәйләнеш юҡ'));});
 for(const status of ['guest','loading','authed'])await test('F07-ui-closed-'+status,()=>{save();h.status=status;assert.equal(text(expand(Fallback())),'');});
 await test('F07-ui-online-closed',()=>{save();navigator.onLine=true;assert.equal(text(expand(Fallback())),'');});
 await test('F07-ui-storage-change-rechecks',()=>{save();assert(text(expand(Fallback())).includes('QA001'));values.set('yuldash.session','session-B');window.dispatchEvent(new Event('storage'));assert(h.renders>0);assert.equal(text(expand(Fallback())),'');});
 await test('F07-ui-online-event-clears',()=>{save();expand(Fallback());navigator.onLine=true;window.dispatchEvent(new Event('online'));assert(h.renders>0);assert.equal(text(expand(Fallback())),'');});
 for(const method of ['cash','sbp','negotiate'])for(const lang of ['ru','ba'])await test('METHOD07-'+method+'-'+lang,()=>{
   saveVerifiedTripPass({...pass,payMethod:method},7,'session-A');h.lang=lang;
   assert.equal(loadOfflineTripPass(42)?.payMethod,method);
   const label={cash:['Наличными','Аҡса менән'],sbp:['Перевод (СБП)','Күсереү (СБП)'],negotiate:['По договорённости','Килешеү буйынса']}[method][lang==='ba'?1:0];
   assert(text(expand(Fallback())).includes(label));assert(text(expand(Fallback())).includes('300'));
 });
 await test('METHOD07-legacy-unknown-is-not-cash',()=>{save();const value=text(expand(Fallback()));assert(value.includes('Способ оплаты не сохранён'));assert(!value.includes('Наличными'));});
 for(const lang of ['ru','ba'])await test('METHOD07-renderer-invalid-method-is-unknown-'+lang,()=>{
   h.lang=lang;const value=text(expand(PassCard({pass:{...pass,payMethod:'future-unknown-method'}})));
   assert(value.includes(lang==='ba'?'Түләү ысулы һаҡланмаған':'Способ оплаты не сохранён'));
   assert(!value.includes(lang==='ba'?'Килешеү буйынса':'По договорённости'));
   assert(!value.includes(lang==='ba'?'Аҡса менән':'Наличными'));
 });
 await test('METHOD07-invalid-method-cannot-be-read',()=>{save();edit(key,p=>({...p,payMethod:'not-a-method'}));assert.equal(loadOfflineTripPass(42),null);});
 for(const lang of ['ru','ba'])await test('CANCEL08-offline-help-without-private-snapshot-'+lang,()=>{
   save();cancellation.rememberInTransitCancel(42,7,'session-A');h.lang=lang;
   const stored=JSON.parse(values.get('yuldash.inTransit.42'));assert.deepEqual(Object.keys(stored).sort(),['at','generation','owner']);
   const tree=expand(RequireAuth({children:'PRIVATE_SCREEN',unavailableContent:Fallback()}));
   const value=text(tree);assert(value.includes('SOS'));assert(value.includes(lang==='ba'?'Сәфәр юлда кире алынды':'Поездку отменили в пути'));
   const links=[];const walk=n=>{if(Array.isArray(n))n.forEach(walk);else if(n&&typeof n==='object'){if(n.type==='a')links.push(n);walk(n.props?.children);}};walk(tree);
   assert(links.some(n=>n.props.href==='tel:112'),'Emergency call must be reachable without the protected SOS route');
   assert(!links.some(n=>n.props.href?.startsWith('/sos')),'Offline help must not return the user to RequireAuth');
   assert(value.includes(lang==='ba'?'SOS хәбәре ебәрелмәй':'сообщение SOS не отправится'));
   assert(!value.includes('PRIVATE_SCREEN'));assert(value.includes(lang==='ba'?'Ҡабат':'Повтор'));
   for(const secret of ['QA001','4931','QA Car','PRIVATE','+79990000000'])assert(!value.includes(secret));
 });
 for(const kind of ['old','expired','future','other-login','no-token'])await test('CANCEL08-closed-'+kind,()=>{
   cancellation.rememberInTransitCancel(42,7,'session-A');const marker='yuldash.inTransit.42';
   if(kind==='old')values.set(marker,JSON.stringify({owner:7,car:'PRIVATE',plate:'PRIVATE'}));
   if(kind==='expired')edit(marker,m=>({...m,at:Date.now()-cancellation.IN_TRANSIT_MARKER_TTL_MS-1}));
   if(kind==='future')edit(marker,m=>({...m,at:Date.now()+10000}));
   if(kind==='other-login')values.set('yuldash.session','session-B');
   if(kind==='no-token')values.delete('yuldash.token');
   assert.equal(text(expand(Fallback())),'');
 });
}
console.log(`Full offline trip: ${total-failed}/${total} passed`);if(failed)process.exitCode=1;
