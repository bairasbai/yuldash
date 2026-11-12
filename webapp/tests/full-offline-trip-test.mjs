import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const h = globalThis.__fullOfflineTrip = { status: 'unavailable', lang: 'ru' };
const values = new Map();
const tokenFor=id=>"test."+Buffer.from(JSON.stringify({sub:String(id)})).toString("base64url")+".test";
globalThis.localStorage = {
  getItem(k) { h.getHook?.(k); if (h.readFail) throw Error('disk unavailable'); return values.get(k) ?? null; },
  setItem(k,v) { if (h.writeFail || k === h.failKey) throw Error('disk full'); values.set(k,String(v)); h.setHook?.(k); },
  removeItem: k => values.delete(k), key: i => [...values.keys()][i] ?? null,
  get length() { return values.size; },
};
globalThis.sessionStorage = { ...localStorage, get length() { return values.size; } };
globalThis.window = new EventTarget();
globalThis.document = new EventTarget();
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { onLine: false } });
const mocks = {
  react: `const h=globalThis.__fullOfflineTrip;export const useState=v=>{if(h.splashStateIndex==null)return[typeof v==='function'?v():v,()=>h.renders++];const i=h.splashStateIndex++;if(!(i in h.splashStates))h.splashStates[i]=typeof v==='function'?v():v;return[h.splashStates[i],next=>{h.splashStates[i]=typeof next==='function'?next(h.splashStates[i]):next;}];};export const useEffect=fn=>{h.cleanup.push(fn())};`,
  'react/jsx-runtime': `export const jsx=(type,props)=>({type,props}),jsxs=jsx,Fragment='fragment';`,
  'react-router-dom': `export const useParams=()=>({id:String(globalThis.__fullOfflineTrip.bookingId)}),useLocation=()=>({pathname:'/trip/42'}),useNavigate=()=>path=>globalThis.__fullOfflineTrip.navigation.push(path),Navigate='Navigate';`,
  auth: `export const useAuth=()=>({status:globalThis.__fullOfflineTrip.status,retrySession:()=>globalThis.__fullOfflineTrip.retries++});`,
  lang: `export const useLang=()=>({lang:globalThis.__fullOfflineTrip.lang,appText:(ru,ba)=>globalThis.__fullOfflineTrip.lang==='ba'?ba:ru});`,
  flags: `export const flags={onboarded:()=>true};`,
  analytics: `export const trackOnce=()=>{};`,
  navigation: `export const scheduleSplashNavigation=()=>()=>{};`,
};
async function compile(entry) {
 const result=await build({entryPoints:[root+entry],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',define:{'import.meta.env':'{}'},plugins:[{name:'controlled-boundaries',setup(b){
  b.onResolve({filter:/.*/},a=>{const p=a.path,k=Object.hasOwn(mocks,p)?p:p.endsWith('/auth/AuthProvider')?'auth':p.endsWith('/i18n/lang')?'lang':p.endsWith('/flags')?'flags':p.endsWith('/analytics')?'analytics':p.endsWith('/utils/splashTarget')?'navigation':null;return k?{path:k,namespace:'boundary'}:null;});
  b.onLoad({filter:/.*/,namespace:'boundary'},a=>({contents:mocks[a.path],loader:'js'}));
 }}]});return import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
}
const {default:RequireAuth}=await compile('src/components/RequireAuth.tsx');
let failed=0,total=0;
async function test(id,fn){total++;try{reset();await fn();console.log('PASS '+id);}catch(e){failed++;console.error('FAIL '+id+': '+e.message);}finally{for(const fn of h.cleanup)fn?.();}}
function reset(){values.clear();Object.assign(h,{bookingId:42,status:'unavailable',lang:'ru',readFail:false,writeFail:false,failKey:null,getHook:null,setHook:null,renders:0,retries:0,cleanup:[],splashStates:[],splashStateIndex:null,navigation:[]});navigator.onLine=false;values.set('yuldash.token',tokenFor(7));values.set('yuldash.session','session-A');}
const expand=n=>Array.isArray(n)?n.map(expand):n&&typeof n==='object'?typeof n.type==='function'?expand(n.type(n.props)):{...n,props:{...n.props,children:expand(n.props?.children)}}:n;
const text=n=>n==null||n===false?'':Array.isArray(n)?n.map(text).join(''):typeof n==='object'?text(n.props?.children):String(n);
for(const lang of ['ru','ba'])await test('F07-gate-'+lang,()=>{h.lang=lang;const t=text(expand(RequireAuth({children:'PRIVATE',unavailableContent:'SAVED ONLY'})));assert(t.includes('SAVED ONLY'));assert(!t.includes('PRIVATE'));});
// Initial RED runs just the existing auth gate; later runs exercise the new production loader/UI too.
if (!process.argv.includes('--gate-only')) {
 const {saveVerifiedTripPass,loadOfflineTripPass,listOfflineTripOptions}=await compile('src/utils/offlineTripPass.ts');
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
 const {fetchMyBookings}=await compile('src/api/bookings.ts');
 async function readList(reply, signal) {
   const originalFetch = globalThis.fetch;
   globalThis.fetch = async () => {
     const rows = await reply();
     return new Response(JSON.stringify(rows), { status: 200 });
   };
   try { return await fetchMyBookings(signal); }
   finally { globalThis.fetch = originalFetch; }
 }
 for (const status of ['cancelled','done']) await test('F07-list-retires-'+status,async()=>{
   save();
   const rows=[{id:42,status}];
   assert.deepEqual(await readList(()=>rows),rows);
   assert(!values.has(key));
   assert(!values.has(owner));
   assert.equal(loadOfflineTripPass(42),null);
   save();
   assert(!values.has(key),'Late confirmed details must not recreate a terminal pass');
 });
 await test('F07-list-terminal-before-first-save',async()=>{
   await readList(()=>[{id:42,status:'cancelled'}]);
   save();
   assert(!values.has(key));
   assert.equal(loadOfflineTripPass(42),null);
 });
 await test('F07-repeated-terminal-list-does-not-rewrite-markers',async()=>{
   const history=Array.from({length:1000},(_,i)=>({id:i+1,status:'done'}));
   save();
   await readList(()=>history);
   assert.equal(loadOfflineTripPass(42),null);
   let writes=0;
   h.setHook=()=>{writes++;};
   await readList(()=>history);
   assert.equal(writes,0);
 });
 await test('F07-list-keeps-active-and-absent',async()=>{
   save();
   await readList(()=>[{id:42,status:'confirmed'},{id:9,status:'cancelled'}]);
   assert.equal(loadOfflineTripPass(42)?.boardingCode,'4931');
   await readList(()=>[]);
   assert.equal(loadOfflineTripPass(42)?.boardingCode,'4931');
 });
 await test('F07-late-list-does-not-change-next-session',async()=>{
   save();
   const late=readList(()=>{
     values.set('yuldash.session','session-B');
     values.set('yuldash.token',tokenFor(8));
     saveVerifiedTripPass({...pass,driverName:'B'},8,'session-B');
     return [{id:42,status:'cancelled'}];
   });
   await assert.rejects(late,error=>error.status===409);
   assert.equal(loadOfflineTripPass(42)?.driverName,'B');
 });
 await test('F07-token-rotation-keeps-list-terminal-result',async()=>{
   save();
   await readList(()=>{
     values.set('yuldash.token',tokenFor(7)+'-rotated');
     return [{id:42,status:'done'}];
   });
   assert.equal(loadOfflineTripPass(42),null);
 });
 await test('F07-credentials-removed-before-list-result',async()=>{
   save();
   await assert.rejects(readList(()=>{
     values.delete('yuldash.token');
     return [{id:42,status:'done'}];
   }),error=>error.name==='AbortError');
   assert(values.has(key));
   assert.equal(loadOfflineTripPass(42),null);
 });
 await test('F07-aborted-list-leaves-pass',async()=>{
   save();
   const request=new AbortController();
   await assert.rejects(readList(()=>{
     request.abort();
     return [{id:42,status:'done'}];
   },request.signal),error=>error.name==='AbortError');
   assert.equal(loadOfflineTripPass(42)?.boardingCode,'4931');
 });

 const {default:Splash}=await compile('src/screens/SplashScreen.tsx');
 const showSplash=()=>{
   h.splashStateIndex=0;
   return expand(Splash());
 };
 const findButton=(tree,label)=>{
   if(Array.isArray(tree)) return tree.map(node=>findButton(node,label)).find(Boolean);
   if(!tree || typeof tree!=='object') return;
   if(tree.type==='button' && text(tree)===label) return tree;
   return findButton(tree.props?.children,label);
 };
 await test('START-offline-only-owned-choices',()=>{
   save();
   assert.deepEqual(listOfflineTripOptions(),{generation:'session-A',trips:[{
     bookingId:42,fromCity:pass.fromCity,toCity:pass.toCity,departAt:pass.departAt
   }]});
   const splash=showSplash();
   assert(findButton(splash,'Открыть сохранённую поездку'));
   assert(!text(splash).includes(pass.fromCity));
   assert(!text(splash).includes(pass.boardingCode));
   findButton(splash,'Открыть сохранённую поездку').props.onClick();
   assert.deepEqual(h.navigation,['/trip/42']);
 });
 await test('START-online-unavailable-has-only-retry',()=>{
   save();navigator.onLine=true;
   const splash=showSplash();
   assert(findButton(splash,'Повторить'));
   assert(!findButton(splash,'Открыть сохранённую поездку'));
 });
 await test('START-multiple-shows-route-only-after-expanding',()=>{
   save();
   saveVerifiedTripPass({...pass,bookingId:43,fromCity:'Кумертау'},7,'session-A');
   const collapsed=showSplash();
   const toggle=findButton(collapsed,'Сохранённые поездки');
   assert(toggle && !toggle.props['aria-expanded']);
   assert(!text(collapsed).includes(pass.fromCity));
   toggle.props.onClick();
   const expanded=showSplash(), rendered=text(expanded);
   assert(rendered.includes('Кумертау → Сибай') && rendered.includes('Уфа → Сибай'));
   for(const privateValue of [pass.driverPhone,pass.boardingCode,pass.pickup,pass.driverPlate]) assert(!rendered.includes(privateValue));
 });
 await test('START-stale-click-cannot-open-after-cancellation',()=>{
   save();
   const action=findButton(showSplash(),'Открыть сохранённую поездку');
   dropTripPass(42,'session-A');
   action.props.onClick();
   assert.deepEqual(h.navigation,[]);
   assert.deepEqual(listOfflineTripOptions()?.trips,[]);
 });
 await test('START-stale-click-cannot-open-after-network-recovery',()=>{
   save();
   const action=findButton(showSplash(),'Открыть сохранённую поездку');
   navigator.onLine=true;
   action.props.onClick();
   assert.deepEqual(h.navigation,[]);
 });
 await test('START-cannot-discover-another-account-even-with-paired-snapshot',()=>{
   save();
   values.set('yuldash.token',tokenFor(8));
   assert.equal(loadOfflineTripPass(42),null);
   assert.deepEqual(listOfflineTripOptions()?.trips,[]);
   assert(!findButton(showSplash(),'Открыть сохранённую поездку'));
 });
 await test('START-same-owner-new-session-does-not-reuse-old-choice',()=>{
   save();
   const action=findButton(showSplash(),'Открыть сохранённую поездку');
   values.set('yuldash.session','session-B');
   saveVerifiedTripPass(pass,7,'session-B');
   action.props.onClick();
   assert.deepEqual(h.navigation,[]);
   assert(findButton(showSplash(),'Открыть сохранённую поездку'));
 });
 await test('START-ignores-trip-with-in-transit-cancellation',()=>{
   save();
   cancellation.rememberInTransitCancel(42,7,'session-A');
   assert.deepEqual(listOfflineTripOptions()?.trips,[]);
 });
 await test('START-list-closes-if-session-changes-during-enumeration',()=>{
   save();
   h.getHook=k=>{if(k===owner){h.getHook=null;values.set('yuldash.session','session-B');}};
   assert.equal(listOfflineTripOptions(),null);
 });

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
 await test('F07-new-B-cannot-retag-A',()=>{save();values.set('yuldash.session','session-B');values.set('yuldash.token',tokenFor(8));saveVerifiedTripPass({...pass,bookingId:43},8,'session-B');assert.equal(loadOfflineTripPass(42),null);assert.equal(loadOfflineTripPass(43)?.ownerId,8);});
 await test('F07-invalid-save-owner',()=>{for(const id of [undefined,0,-1,NaN,1.5])saveVerifiedTripPass(pass,id,'session-A');assert.equal(values.has(key),false);});
 await test('F07-storage-write-failure',()=>{h.writeFail=true;assert.doesNotThrow(save);assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-marker-write-failure',()=>{h.failKey=owner;save();assert(values.has(key));assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-new-pass-old-marker-mismatch',()=>{save();h.failKey=owner;saveVerifiedTripPass({...pass,boardingCode:'NEW'},7,'session-A');assert.equal(loadOfflineTripPass(42),null);});
 await test('F07-failed-pass-write-preserves-prior-complete-pair',()=>{save();const before=[...values];h.failKey=key;saveVerifiedTripPass({...pass,boardingCode:'NEW'},7,'session-A');assert.deepEqual([...values],before);assert.equal(loadOfflineTripPass(42)?.boardingCode,'4931');});
 for(const boundary of [key,owner])await test('F07-interleaved-B-during-'+boundary,()=>{h.setHook=k=>{if(k!==boundary)return;h.setHook=null;values.set('yuldash.session','session-B');values.set('yuldash.token',tokenFor(8));saveVerifiedTripPass({...pass,driverName:'B'},8,'session-B');};save();assert.equal(loadOfflineTripPass(42)?.ownerId,8);assert.equal(loadOfflineTripPass(42)?.driverName,'B');});
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
