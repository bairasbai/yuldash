// Actual WS adapter + actual IndexedDB queue, deterministic wire/HTTP fixtures. No live server claim.
import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {IDBFactory,IDBObjectStore} from 'fake-indexeddb';
import {fileURLToPath} from 'node:url';
import {storedActions,until} from './outbox-helpers.mjs';
const {outputFiles}=await build({stdin:{contents:"export * from './src/api/client';export * from './src/api/chat';export * from './src/utils/outbox';",resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{"VITE_API_BASE":"http://127.0.0.1:8006"}'}});
let serial=0,passed=0,failed=0;
const realTimeout=globalThis.setTimeout,realClear=globalThis.clearTimeout;
async function environment(){
 globalThis.indexedDB=new IDBFactory();const storage=new Map(),timers=new Map();
 globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,String(v)),removeItem:k=>storage.delete(k)};
 globalThis.window={addEventListener(){},removeEventListener(){}};
 globalThis.setTimeout=(fn,ms,...args)=>{if(ms===5000){const id='ack-'+(++serial);timers.set(id,fn);return id;}return realTimeout(fn,ms,...args);};
 globalThis.clearTimeout=id=>{if(timers.has(id))timers.delete(id);else realClear(id);};
 const messages=new Map(),wire=[],http=[],sockets=[];
 const commit=(key,text)=>{if(!messages.has(key))messages.set(key,{type:'message',id:messages.size+1,sender_id:7,text,request_id:key});return messages.get(key);};
 globalThis.WebSocket=class{static OPEN=1;readyState=0;constructor(){sockets.push(this);}send(raw){const frame=JSON.parse(raw);if(frame.type==='message'){wire.push(frame);commit(frame.request_id,frame.text);}}close(){this.readyState=3;this.onclose?.();}};
 globalThis.fetch=async(url,init)=>{const key=init.headers['Idempotency-Key'];http.push({key,text:JSON.parse(init.body).text});return new Response(JSON.stringify(commit(key,JSON.parse(init.body).text)));};
 const load=()=>import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text+'\n//'+serial++).toString('base64'));
 const api=await load();api.setSession('fixture-A','fixture-refresh');
 const chat=api.openBookingChat(42,{senderId:7,onMessage(){}});const socket=sockets[0];socket.readyState=1;socket.onopen();
 return{api,chat,socket,load,messages,wire,http,timers,tick(){for(const [id,fn]of [...timers]){timers.delete(id);fn();}}};
}
async function check(name,fn){const f=await environment();try{await fn(f);passed++;console.log('✓ '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.stack);}finally{f.chat.close();assert.equal(f.timers.size,0);}}
try{
 await check('committed WS with lost echo survives module reload and startup REST uses the same key',async f=>{
  const key=f.api.createOutboxId();await f.api.enqueue(42,'message','same',key);const ack=f.chat.send('same',key);
  assert.equal(f.messages.size,1);assert.equal((await storedActions()).length,1);f.chat.close();assert.equal(await ack,null);
  const reloaded=await f.load();let done=false;const stop=reloaded.watchOutbox(()=>{done=true;});await until(()=>done);stop();
  assert.equal(f.messages.size,1);assert.deepEqual(f.http.map(x=>x.key),[key]);assert.equal((await storedActions()).length,0);
 });
 await check('lost ACK times out to REST with same identity and exact queue acknowledgment',async f=>{
  const key=f.api.createOutboxId();await f.api.enqueue(42,'message','same',key);const ack=f.chat.send('same',key);f.tick();assert.equal(await ack,null);
  const message=await f.api.sendMessageRest(42,'same',undefined,key);assert.equal(message.id,1);await f.api.acknowledgeMessage(42,key,f.api.getSessionGeneration());
  assert.equal(f.messages.size,1);assert.equal(f.wire[0].request_id,f.http[0].key);assert.equal((await storedActions()).length,0);
 });
 await check('failed removal after known commit keeps same durable key for safe replay',async f=>{
  const key=f.api.createOutboxId();await f.api.enqueue(42,'message','same',key);const ack=f.chat.send('same',key);
  f.socket.onmessage({data:JSON.stringify(f.messages.get(key))});assert.equal((await ack).id,1);
  const remove=IDBObjectStore.prototype.delete;IDBObjectStore.prototype.delete=function(){throw Error('disk cleanup failed');};
  try{await assert.rejects(f.api.acknowledgeMessage(42,key,f.api.getSessionGeneration()));}finally{IDBObjectStore.prototype.delete=remove;}
  assert.equal((await storedActions()).length,1);const reloaded=await f.load();await reloaded.flushOutbox();
  assert.equal(f.messages.size,1);assert.equal(f.http.at(-1).key,key);assert.equal((await storedActions()).length,0);
 });
 await check('late acknowledgment captured by A cannot remove account B intent',async f=>{
  const generation=f.api.getSessionGeneration(),key='same-key';await f.api.enqueue(42,'message','A',key);
  f.api.setSession('fixture-B','fixture-refresh-B');await f.api.enqueue(42,'message','B',key);await f.api.acknowledgeMessage(42,key,generation);
  assert.ok((await storedActions()).some(a=>a.payload==='B'));assert.equal(f.http.length,0);
 });
}finally{globalThis.setTimeout=realTimeout;globalThis.clearTimeout=realClear;}
console.log(`WS persistent intent cases: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
