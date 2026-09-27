import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
const root=fileURLToPath(new URL('../',import.meta.url));
const h=globalThis.__completion={};
const hooks=`const h=globalThis.__completion;
export const useState=v=>{const i=h.cursor++;if(!(i in h.slots))h.slots[i]=typeof v==='function'?v():v;return[h.slots[i],n=>h.slots[i]=typeof n==='function'?n(h.slots[i]):n];};
export const useCallback=fn=>fn;
export const useEffect=fn=>{const i=h.cursor++;if(!(i in h.slots)){h.slots[i]=true;h.effects.push(fn);}};
export const useRef=v=>{const i=h.cursor++;return h.slots[i]??={current:v};};`;
function fixture(name){return `function ${name}(props){return {type:'fixture',props:{...props,fixture:'${name}'}}}`;}
async function loadScreen(file,name){
 const source=await readFile(root+'src/screens/'+file,'utf8'),mocks={};
 for(const [,clause,path] of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)){
  const names=(clause.match(/\{([\s\S]*?)\}/)?.[1]??'').split(',').map(x=>x.trim()).filter(x=>x&&!x.startsWith('type '));
  const def=path.split('/').at(-1).replace(/\W/g,'');mocks[path]=`export default ${fixture(def)}\n`+names.map(n=>'export '+fixture(n)).join('\n');
 }
 if(!mocks['../components/parcelUi'].includes('function StatusPillParcel'))mocks['../components/parcelUi']+='\nexport '+fixture('StatusPillParcel');
 mocks.react=hooks;mocks['react/jsx-runtime']=`export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`;
 mocks['../i18n/lang']=`export const useLang=()=>({lang:'ru',appText:ru=>ru});`;
 mocks['react-router-dom']=`export const useNavigate=()=>()=>{};export const Link='a';`;
 mocks['../auth/AuthProvider']=`export const useAuth=()=>({user:{id:9}});`;
 mocks['../api/parcels']+=`\nexport const fetchCarrying=(signal,recent)=>{globalThis.__completion.requests.push(recent);return Promise.resolve(globalThis.__completion.rows);};
 export const setParcelStatus=async(id,status,code)=>{const h=globalThis.__completion;h.calls.push({id,status,code});if(h.reject)throw Error('wrong code');const next={...h.rows[0],status};h.rows=[next];return next;};`;
 mocks['../api/parcels']=mocks['../api/parcels'].replace('export '+fixture('fetchCarrying'),'').replace('export '+fixture('setParcelStatus'),'').replace('export '+fixture('isCarrying'),`export const isCarrying=s=>['accepted','in_transit','returning'].includes(s);`);
 mocks['../api/client']=`export class ApiError extends Error {} export const getSessionGeneration=()=>'';`;
 for(const name of ['parcelAttemptFailed','parcelDispute','parcelReturnDone','parcelReturnStart','releaseParcel'])if(!mocks['../api/parcels'].includes('function '+name))mocks['../api/parcels']+='\nexport '+fixture(name);
 for(const name of ['IconArrow','IconFlag','IconWarn'])if(!mocks['../components/Icons'].includes('function '+name))mocks['../components/Icons']+='\nexport '+fixture(name);
 const result=await build({stdin:{contents:source+`\nexport {${name}};`,resolveDir:root+'src/screens',loader:'tsx'},bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'fixtures',setup(b){b.onResolve({filter:/.*/},a=>{
  if(['/CompletedParcelCard','/ParcelProblemActions','/parcelDispute.js'].some(end=>a.path.endsWith(end)))return undefined;
  const shared=Object.keys(mocks).find(k=>a.path.startsWith('./')&&k.split('/').at(-1)===a.path.split('/').at(-1));if(shared)return{path:shared,namespace:'fixture'};
  if(a.path in mocks)return{path:a.path,namespace:'fixture'};
  if(a.path.startsWith('.')){const n=a.path.split('/').at(-1);mocks[a.path]=`export default ${fixture(n)};`;return{path:a.path,namespace:'fixture'};}
 });b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));}}]});
 return (await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64')))[name];
}
const screens=[['professional',await loadScreen('CourierScreen.tsx','CarryOrders')],['rideshare',await loadScreen('ParcelsScreen.tsx','CarryingList')]];
function render(Screen){h.cursor=0;h.effects=[];function visit(n){if(Array.isArray(n))return n.map(visit);if(!n||typeof n!=='object')return n;if(typeof n.type==='function')return visit(n.type(n.props));return{...n,props:{...n.props,children:visit(n.props?.children)}};}const tree=visit({type:Screen,props:{onGoAvailable(){}}});for(const fn of h.effects)fn();return tree;}
function nodes(n){return Array.isArray(n)?n.flatMap(nodes):!n||typeof n!=='object'?[]:[n,...nodes(n.props?.children)];}
const find=(tree,name)=>nodes(tree).find(n=>n.props.fixture===name);
async function reset(Screen,status='in_transit'){Object.assign(h,{slots:[],cursor:0,effects:[],requests:[],calls:[],reject:false,rows:[{id:31,status,sender_id:101,courier_id:9,from_city:'Уфа',to_city:'Бирск',price_kop:30000,delivery_type:'standard'}]});render(Screen);await Promise.resolve();return render(Screen);}
let total=0,failed=0;async function test(name,fn){total++;try{await fn();console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}}
for(const [label,Screen] of screens){
 await test(label+' requests recent completed orders',async()=>{await reset(Screen);assert.equal(h.requests[0],true);});
 await test(label+' delivery keeps receipt and sender rating, without active actions',async()=>{let t=await reset(Screen);find(t,'CarryParcelCard').props.onDeliver();t=render(Screen);await find(t,'CodeDialog').props.onSubmit(' 1234 ');t=render(Screen);assert.equal(h.calls[0].code,'1234');assert.equal(find(t,'ParcelReceiptCard')?.props.parcelId,31);assert.equal(find(t,'ParcelRate')?.props.role,'sender');assert.equal(find(t,'CarryParcelCard'),undefined);assert.equal(find(t,'ParcelPhoto'),undefined);});
 for(const status of ['delivered','returned','canceled'])await test(label+' reopens '+status,async()=>{const t=await reset(Screen,status);assert.equal(!!find(t,'ParcelReceiptCard'),status!=='canceled');assert.equal(!!find(t,'ParcelRate'),status==='delivered');assert.ok(nodes(t).some(n=>n.type==='button'&&JSON.stringify(n.props.children).includes('Открыть спор')),'Completed courier must have a dispute action');assert.equal(find(t,'CarryParcelCard'),undefined);});
 await test(label+' wrong delivery code keeps active order and retry dialog',async()=>{let t=await reset(Screen);find(t,'CarryParcelCard').props.onDeliver();h.reject=true;t=render(Screen);await find(t,'CodeDialog').props.onSubmit('bad');t=render(Screen);assert.ok(find(t,'CarryParcelCard'));assert.ok(find(t,'CodeDialog')?.props.error);assert.equal(find(t,'ParcelReceiptCard'),undefined);});
}
console.log(`Courier completion: ${total-failed}/${total} passed`);if(failed)process.exitCode=1;
