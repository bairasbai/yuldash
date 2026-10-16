// Actual TripChat send flow. Hook/transport/disk boundaries are controlled fixtures, not a browser run.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
const source=await readFile(new URL('../src/screens/TripChatScreen.tsx',import.meta.url),'utf8');
const mocks={};
for(const match of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)){
 const[,clause,path]=match;const names=(clause.match(/\{([\s\S]*?)\}/)?.[1]??'').split(',').map(s=>s.trim()).filter(s=>s&&!s.startsWith('type ')).map(s=>s.split(/\s+as\s+/)[0]);
 mocks[path]='export default function Stub(){return null;}\n'+names.map(n=>`export const ${n}=()=>null;`).join('\n');
}
mocks.react=`const h=()=>globalThis.intentUI;const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
 export function useState(initial){const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;return[s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;s.dirty=true;}];}
 export function useRef(initial){const s=h(),i=s.cursor++;return s.slots[i]??={current:initial};}
 export function useCallback(fn,deps){const s=h(),i=s.cursor++;if(!same(s.slots[i]?.deps,deps))s.slots[i]={fn,deps};return s.slots[i].fn;}
 export function useEffect(fn,deps){const s=h(),i=s.cursor++;if(fn.toString().includes('openBookingChat')&&!same(s.slots[i]?.deps,deps)){s.effects.push(()=>{s.slots[i]?.stop?.();s.slots[i]={deps,stop:fn()};});}}
 export function useLayoutEffect(){h().cursor++;}`;
mocks['react/jsx-runtime']=`export const Fragment='fragment';export const jsx=(type,props)=>({type,props});export const jsxs=jsx;`;
mocks['../i18n/lang']=`export const useLang=()=>({appText:ru=>ru});`;
mocks['../api/client']=`export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}globalThis.intentErrorClass=ApiError;export const getSessionGeneration=()=>globalThis.intentUI.generation;`;
mocks['../api/chat']=`import{ApiError}from'../api/client';const h=()=>globalThis.intentUI;
 export const fetchMessages=async()=>[];
 export const openBookingChat=(id,handlers)=>{h().handlers=handlers;return{send:(text,key)=>{const s=h();s.wsSends.push([id,text,key]);return s.wsBehavior(text,key);},close:()=>{h().closes++;}};};
 export const sendMessageRest=async(id,text,voice,key)=>{const s=h();s.restSends.push([id,text,key]);return s.restBehavior(text,key);};
 export const editMessage=()=>null,deleteMessage=()=>null;`;
mocks['../utils/outbox']=`import{ApiError}from'../api/client';const h=()=>globalThis.intentUI;
 export const createOutboxId=()=> 'intent-'+(++h().seq);
 export const enqueue=async(id,kind,text,key)=>{const s=h();s.enqueues.push([id,kind,text,key]);await s.saveBehavior();s.actions.set(key,{id,kind,text,key});};
 export const acknowledgeMessage=async(id,key,generation)=>{const s=h();s.removals.push([id,key,generation]);if(generation!==s.generation)return;await s.removeBehavior();s.actions.delete(key);};
 export const isPermanentRejection=e=>e instanceof ApiError&&[400,403,404,405,409,410,413,415,422].includes(e.status);
 export const outboxCount=()=>h().actions.size;
 export const watchOutbox=()=>()=>{},subscribeOutbox=()=>()=>{};`;
const {outputFiles}=await build({stdin:{contents:source+'\nexport {TripChat as TestChat};',loader:'tsx',resolveDir:fileURLToPath(new URL('../src/screens/',import.meta.url))},bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'intent-fixtures',setup(b){
 b.onResolve({filter:/.*/},a=>a.path in mocks?{path:a.path,namespace:'fixture'}:undefined);b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path],loader:'js'}));
}}]});
const {TestChat}=await import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text).toString('base64'));
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return{promise,resolve,reject};}
const nodes=n=>!n||typeof n!=='object'?[]:[n,...[n.props?.children].flat(Infinity).flatMap(nodes)];
const input=h=>nodes(h.tree).find(n=>n.type==='input'&&n.props['aria-label']==='Сообщение');
const button=h=>nodes(h.tree).find(n=>n.type==='button'&&n.props['aria-label']==='Отправить');
const text=n=>typeof n==='string'?n:[n?.props?.children].flat(Infinity).map(x=>x&&typeof x==='object'?text(x):typeof x==='string'?x:'').join(' ');
function render(h){h.cursor=0;h.dirty=false;h.tree=TestChat({bookingId:42,myId:7});for(const effect of h.effects.splice(0))effect();}
async function settle(h){for(let i=0;i<60;i++){if(h.dirty)render(h);await Promise.resolve();}}
async function type(h,value){input(h).props.onChange({target:{value}});await settle(h);}
async function send(h,value='same'){await type(h,value);button(h).props.onClick();await settle(h);}
function environment(){
 const h=globalThis.intentUI={slots:[],cursor:0,dirty:true,effects:[],generation:'A',seq:0,actions:new Map(),enqueues:[],wsSends:[],restSends:[],removals:[],closes:0,
  saveBehavior:async()=>{},removeBehavior:async()=>{},wsBehavior:async()=>null,restBehavior:async()=>{throw Error('network down');}};
 h.ack=(key,id=10)=>{const m={id,text:'same',sender_id:7,request_id:key};h.handlers.onAcknowledged(m);h.handlers.onMessage(m);return m;};
 return h;
}
let passed=0,failed=0;
async function check(name,fn){const h=environment();try{await settle(h);assert.ok(h.handlers,'WS effect must be installed');await fn(h);passed++;console.log('✓ '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.stack);}}
await check('disk commit precedes WS and REST, failed save retains draft without network',async h=>{
 const save=deferred();h.saveBehavior=()=>save.promise;await send(h);assert.equal(h.enqueues.length,1);assert.equal(h.wsSends.length,0);assert.equal(h.restSends.length,0);assert.equal(input(h).props.value,'same');
 save.reject(Error('quota'));await settle(h);assert.equal(h.actions.size,0);assert.equal(input(h).props.value,'same');assert.match(text(h.tree),/Не получилось отправить/);
});
await check('ordinary own ACK removes persisted intent and clears original draft',async h=>{
 h.wsBehavior=async(t,key)=>h.ack(key);await send(h);assert.equal(h.actions.size,0);assert.equal(h.restSends.length,0);assert.equal(input(h).props.value,'');
});
await check('WS acceptance is not confirmation; pending text stays while awaiting ACK',async h=>{
 const ack=deferred();h.wsBehavior=()=>ack.promise;await send(h);assert.equal(input(h).props.value,'same');assert.equal(h.actions.size,1);assert.equal(button(h).props.disabled,true);
 ack.resolve(h.ack('intent-1'));await settle(h);assert.equal(input(h).props.value,'');assert.equal(h.actions.size,0);
});
await check('lost WS ACK retries REST using exactly the saved UUID',async h=>{
 h.restBehavior=async(t,key)=>({id:10,text:t,sender_id:7,request_id:key});await send(h);assert.deepEqual(h.wsSends,[[42,'same','intent-1']]);assert.deepEqual(h.restSends,[[42,'same','intent-1']]);assert.equal(h.actions.size,0);assert.equal(input(h).props.value,'');
});
await check('late ACK wins over held REST; subsequent REST failure cannot restore draft',async h=>{
 const rest=deferred();h.restBehavior=()=>rest.promise;await send(h);assert.equal(h.restSends.length,1);h.ack('intent-1');await settle(h);assert.equal(input(h).props.value,'');assert.equal(h.actions.size,0);
 rest.reject(Error('late disconnect'));await settle(h);assert.equal(input(h).props.value,'');assert.doesNotMatch(text(h.tree),/Не получилось отправить/);
});
await check('confirmed WS with failed queue removal does not restore text or generate another key',async h=>{
 h.wsBehavior=async(t,key)=>h.ack(key);h.removeBehavior=async()=>{throw Error('IDB cleanup failed');};await send(h);assert.equal(h.actions.size,1);assert.equal(input(h).props.value,'');assert.equal(h.seq,1);assert.equal(h.restSends.length,0);assert.doesNotMatch(text(h.tree),/Не получилось отправить/);
});
await check('confirmed REST with failed queue removal is still a delivered success',async h=>{
 h.restBehavior=async(t,key)=>({id:10,text:t,sender_id:7,request_id:key});h.removeBehavior=async()=>{throw Error('IDB cleanup failed');};await send(h);assert.equal(h.actions.size,1);assert.equal(input(h).props.value,'');assert.equal(h.seq,1);assert.doesNotMatch(text(h.tree),/Не получилось отправить/);
});
await check('ambiguous network failure leaves the same durable intent for restart',async h=>{
 await send(h);assert.equal(h.actions.size,1);assert.equal([...h.actions.keys()][0],'intent-1');assert.equal(input(h).props.value,'');assert.match(text(h.tree),/отправим сами/);
});
await check('408, 429 and server errors retain their durable UUID for automatic retry',async h=>{
 for(const status of [408,429,500,503]){h.restBehavior=async()=>{throw new globalThis.intentErrorClass(status,'temporary');};await send(h);assert.equal(input(h).props.value,'');}
 assert.deepEqual([...h.actions.keys()],['intent-1','intent-2','intent-3','intent-4']);assert.equal(h.removals.length,0);
});
await check('permanent rejection removes auto action and manual retry preserves the UUID',async h=>{
 h.restBehavior=async()=>{throw new globalThis.intentErrorClass(409,'rejected');};await send(h);assert.equal(h.actions.size,0);assert.equal(input(h).props.value,'same');assert.match(text(h.tree),/Не получилось отправить/);
 h.restBehavior=async(t,key)=>({id:10,text:t,sender_id:7,request_id:key});button(h).props.onClick();await settle(h);
 assert.deepEqual(h.enqueues.map(x=>x[3]),['intent-1','intent-1']);assert.equal(h.seq,1);assert.equal(input(h).props.value,'');assert.equal(h.actions.size,0);
});
await check('typing another draft while ACK is pending preserves it on confirmation',async h=>{
 const ack=deferred();h.wsBehavior=()=>ack.promise;await send(h);await type(h,'next draft');ack.resolve(h.ack('intent-1'));await settle(h);assert.equal(input(h).props.value,'next draft');
});
await check('two deliberately submitted identical texts get different UUIDs',async h=>{
 h.wsBehavior=async(t,key)=>h.ack(key,key==='intent-1'?10:11);await send(h);await send(h);assert.deepEqual(h.enqueues.map(x=>x[3]),['intent-1','intent-2']);assert.equal(h.actions.size,0);
});
await check('session change during durable save prevents both WS and REST and leaves B draft',async h=>{
 const save=deferred();h.saveBehavior=()=>save.promise;await send(h);h.generation='B';await type(h,'B draft');save.resolve();await settle(h);assert.equal(h.wsSends.length,0);assert.equal(h.restSends.length,0);assert.equal(input(h).props.value,'B draft');
 button(h).props.onClick();await settle(h);assert.equal(h.enqueues.length,1,'retained A screen adopted B');
});
await check('session change while waiting for ACK never starts REST fallback',async h=>{
 const ack=deferred();h.wsBehavior=()=>ack.promise;await send(h);h.generation='B';await type(h,'B draft');ack.resolve(null);await settle(h);assert.equal(h.restSends.length,0);assert.equal(input(h).props.value,'B draft');
});
console.log(`Chat intent UI cases: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
