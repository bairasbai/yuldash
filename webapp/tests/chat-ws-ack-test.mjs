// Actual booking WS adapter; deterministic browser WebSocket and clock, no real server.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const { outputFiles } = await build({ entryPoints: [fileURLToPath(new URL('../src/api/chat.ts', import.meta.url))], bundle: true, write: false, format: 'esm', platform: 'node', plugins: [{name:'client',setup(b){
  b.onResolve({filter:/^\.\/client$/},()=>({path:'client',namespace:'fixture'}));
  b.onLoad({filter:/.*/,namespace:'fixture'},()=>({contents:`export const API_BASE='http://local';export const getToken=()=>globalThis.wsFixture.token;export const getSessionGeneration=()=>globalThis.wsFixture.generation;
    export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}
    export const apiGet=()=>{},apiPost=async()=>globalThis.wsFixture.receipt,apiDelete=()=>{},apiUpload=()=>{},isOwnApiUrl=()=>true,refreshRejectedToken=async()=>true;`}));
} }] });
const api = await import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text).toString('base64'));
const realTimeout=globalThis.setTimeout,realClear=globalThis.clearTimeout;
let serial=0,passed=0,failed=0;
function environment(){
 const f=globalThis.wsFixture={generation:'A',token:'fixture-token',timers:new Map(),frames:[],acknowledged:[],messages:[],sockets:[],historyChanges:0};
 globalThis.setTimeout=(fn,ms)=>{const id=++serial;f.timers.set(id,{fn,ms});return id;};globalThis.clearTimeout=id=>f.timers.delete(id);
 globalThis.WebSocket=class {
  static OPEN=1;
  readyState=0;
  constructor(url){this.url=url;f.sockets.push(this);}
  send(raw){const frame=JSON.parse(raw);f.frames.push(frame);f.onSend?.(frame,this);}
  open(){this.readyState=1;this.onopen?.();}
  emit(data){this.onmessage?.({data:JSON.stringify(data)});}
  close(){this.readyState=3;this.onclose?.();}
 };
 const chat=api.openBookingChat(42,{senderId:7,onMessage:m=>f.messages.push(m),onAcknowledged:m=>f.acknowledged.push(m),onHistoryChanged:()=>f.historyChanges++});
 const socket=f.sockets[0];f.chat=chat;f.socket=socket;
 f.ack=(key,id=10,sender=7,text='same')=>socket.emit({type:'message',id,sender_id:sender,text,request_id:key});
 f.tick=()=>{for(const [id,t]of [...f.timers]){assert.equal(t.ms,5000);f.timers.delete(id);t.fn();}};
 return f;
}
async function check(name,fn){const f=environment();try{await fn(f);passed++;console.log('✓ '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.stack);}finally{f.chat.close();assert.equal(f.timers.size,0);}}
try{
 await check('hidden ACK confirms exactly one owned intent without content delivery or another send',async f=>{
  f.socket.open();const pending=f.chat.send('private','hidden');
  f.socket.emit({type:'hidden_ack',id:9,sender_id:7,request_id:'hidden',hidden_for_me:true});
  const receipt=await pending;assert.equal(receipt.hidden_for_me,true);assert.equal(receipt.request_id,'hidden');
  assert.equal(f.acknowledged.length,1);assert.equal(f.messages.length,1);assert.equal(f.messages[0].text,undefined);
  assert.equal((await f.chat.send('private','hidden')).id,9);assert.equal(f.frames.filter(x=>x.type==='message').length,1);
 });
 await check('malformed hidden ACK never confirms or publishes another owners receipt',async f=>{
  f.socket.open();let resolved=false;const pending=f.chat.send('private','hidden').then(m=>{resolved=true;return m;});
  for(const bad of [{id:0},{id:1.5},{sender_id:8},{request_id:''},{hidden_for_me:false}])
   f.socket.emit({type:'hidden_ack',id:9,sender_id:7,request_id:'hidden',hidden_for_me:true,...bad});
  await Promise.resolve();assert.equal(resolved,false);assert.equal(f.messages.length,0);assert.equal(f.acknowledged.length,0);
  f.tick();assert.equal(await pending,null);
 });
 await check('REST hidden receipt requires exact original key, owner and positive integer id',async f=>{
  f.token='header.'+Buffer.from(JSON.stringify({sub:'7'})).toString('base64url')+'.signature';
  const good={id:9,sender_id:7,request_id:'hidden',hidden_for_me:true};
  for(const bad of [{id:0},{id:1.5},{sender_id:8},{request_id:'wrong'}]){
   f.receipt={...good,...bad};await assert.rejects(api.sendMessageRest(42,'private',undefined,'hidden'),/Unconfirmed hidden receipt/);
  }
  f.receipt=good;assert.deepEqual(await api.sendMessageRest(42,'private',undefined,'hidden'),good);
 });
 await check('unopened transport falls back without sending text',async f=>{assert.equal(await f.chat.send('same','key'),null);assert.deepEqual(f.frames,[]);});
 await check('sender and exact request ID confirm, equal text and another sender do not',async f=>{
  f.socket.open();let done=false;const pending=f.chat.send('same','intent-a').then(m=>{done=true;return m;});
  assert.deepEqual(f.frames.at(-1),{type:'message',text:'same',request_id:'intent-a'});
  f.ack('intent-b');f.ack('intent-a',11,8);await Promise.resolve();assert.equal(done,false);
  f.ack('intent-a',12);assert.equal((await pending).id,12);assert.deepEqual(f.acknowledged.map(m=>m.id),[10,12]);
 });
 await check('synchronous ACK inside send is received after waiter registration',async f=>{
  f.socket.open();f.onSend=(frame)=>{if(frame.type==='message')f.ack(frame.request_id);};assert.equal((await f.chat.send('same','fast')).id,10);
 });
 await check('ACK before waiter avoids sending an already committed intent again',async f=>{
  f.socket.open();f.ack('early');assert.equal((await f.chat.send('same','early')).id,10);assert.equal(f.frames.filter(x=>x.type==='message').length,0);
 });
 await check('lost ACK has a bounded five-second fallback and late ACK is still published',async f=>{
  f.socket.open();const pending=f.chat.send('same','lost');assert.equal(f.timers.size,1);f.tick();assert.equal(await pending,null);
  f.ack('lost');assert.equal(f.acknowledged.at(-1).request_id,'lost');assert.equal((await f.chat.send('same','lost')).id,10);
 });
 await check('permanent and rate-limit rejections preserve server status',async f=>{
  f.socket.open();for(const status of [409,429]){const key='reject-'+status;const pending=f.chat.send('same',key);
   f.socket.emit({type:'rejected',request_id:key,status_code:status,reason:'fixture'});await assert.rejects(pending,e=>e.status===status);}
 });
 await check('transport send exception clears waiter and allows REST fallback',async f=>{
  f.socket.open();f.onSend=()=>{throw Error('transport closed');};assert.equal(await f.chat.send('same','throw'),null);assert.equal(f.timers.size,0);
 });
 await check('close settles waiters, clears timers and ignores retained late callback',async f=>{
  f.socket.open();const pending=f.chat.send('same','closed');const late=f.socket.onmessage;f.chat.close();assert.equal(await pending,null);
  late({data:JSON.stringify({type:'message',id:10,sender_id:7,text:'same',request_id:'closed'})});assert.equal(f.acknowledged.length,0);assert.equal(f.messages.length,0);
 });
 await check('retained session A cannot send or acknowledge after B logs in',async f=>{
  f.socket.open();const pending=f.chat.send('same','old');f.generation='B';f.ack('old');assert.equal(await pending,null);assert.equal(f.acknowledged.length,0);
  assert.equal(await f.chat.send('same','new-B'),null);assert.equal(f.frames.filter(x=>x.type==='message').length,1);
 });
 await check('separate identical words have distinct intents with out-of-order confirmation',async f=>{
  f.socket.open();const a=f.chat.send('same','a'),b=f.chat.send('same','b');f.ack('b',12);f.ack('a',11);
  assert.equal((await a).id,11);assert.equal((await b).id,12);assert.equal(f.acknowledged.length,2);
 });
 await check('history invalidation is separate from message delivery and cannot confirm a pending send',async f=>{
  f.socket.open();let resolved=false;const pending=f.chat.send('same','pending').then(m=>{resolved=true;return m;});
  f.socket.emit({type:'history_changed',id:900,sender_id:7,text:'not a message',request_id:'pending'});
  await Promise.resolve();assert.equal(f.historyChanges,1);assert.equal(resolved,false);
  assert.equal(f.messages.length,0);assert.equal(f.acknowledged.length,0);assert.equal(f.timers.size,1);
  f.ack('pending');assert.equal((await pending).id,10);
 });
 await check('closed or replaced owner ignores retained history invalidation callbacks',async f=>{
  f.socket.open();const retained=f.socket.onmessage;f.generation='B';retained({data:JSON.stringify({type:'history_changed'})});
  assert.equal(f.historyChanges,0);f.chat.close();retained({data:JSON.stringify({type:'history_changed'})});assert.equal(f.historyChanges,0);
 });
}finally{globalThis.setTimeout=realTimeout;globalThis.clearTimeout=realClear;}
console.log(`Booking WS ACK cases: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
