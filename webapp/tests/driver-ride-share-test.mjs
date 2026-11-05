// Эквиваленты R selector/сети/переходов поверх принятого memory-grant: открытие без нажатия безопасно.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ROOT, checker } from "./shallow-harness.mjs";
import { GPS, gpsRig, row, settle, deferred } from "./gps-grant-fixture.mjs";
const t=checker();
const cases=JSON.parse(readFileSync(ROOT+"tests/fixtures/driver-ride-carrier-cases.json","utf8")).cases;
await t.test("сохранён весь общий R-набор выбора несущей",()=>assert.ok(cases.length>=10));
for(const c of cases) await t.test(`выбор несущей: ${c.name}`,()=>{
 const pick=GPS.pickRideCarrier(c.rows,c.anchor,c.closed);
 assert.equal(pick.rideId,c.expect.ride_id);assert.deepEqual(pick.live,c.expect.live);assert.equal(pick.carrier,c.expect.carrier);assert.equal(pick.carrierTtlSec,c.expect.carrier_ttl_sec);
});
async function ride(){const r=gpsRig();r.release=r.share.open(101);await r.click();assert.equal(r.fetches,1);assert.equal(r.share.snapshot().ride,true);return r;}
await t.test("первая отметка до open сохраняет grant и срок: кабинет/экран лишь держат передачу",async()=>{
 const r=gpsRig();const ticket=r.share.beginClick(101,7,false);await r.share.finishClick(ticket,true);assert.equal(r.watches.length,0,"без держателя страницы GPS не подписан");
 r.share.open(101);assert.equal(r.watches.length,1);assert.equal(r.ids,1);r.share.stop();
});
await t.test("закрытая несущая меняется на другую живую бронь того же рейса",async()=>{
 const r=await ride();r.rows=[row(101,"done"),row(102)];await r.share.action(101,true);assert.equal(r.started().id,102);assert.equal(r.cleared.includes(1),true);assert.equal(r.ids,1);r.share.stop();
});
await t.test("последняя бронь закрыта — GPS, сокет, таймеры и право публикации погашены",async()=>{
 const r=await ride();r.fix();r.openSocket();r.rows=[row(101,"done"),row(102,"cancelled")];await r.share.action(101,true);
 assert.equal(r.share.snapshot().bookingId,null);assert.equal(r.share.snapshot().ride,false);assert.equal(r.activeWatches().length,0);assert.equal(r.sockets.at(-1).closed,true);assert.equal(r.timers.some(tm=>!tm.done),false);r.share.stop();
});
await t.test("исправление посадки не закрывает бронь: новый confirmed получает собственный срок",async()=>{
 const r=await ride();r.rows=[row(101,"confirmed",{no_show_wait_sec:120}),row(102,"done")];r.started().hooks.onEnd("boarding-corrected");await settle();
 assert.equal(r.started().id,101);assert.equal(r.ids,1);await r.advance(121000);r.rows=[row(101,"confirmed",{no_show_wait_sec:0})];r.fix();await settle();
 assert.equal(r.activeWatches().length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("исправление без локального grant не ищет пассажиров",async()=>{
 const r=gpsRig();r.share.open(101);await r.share.action(101,false);assert.equal(r.fetches,0);assert.equal(r.starts.length,0);r.share.stop();
});
await t.test("ответ списка другого рейса/неизвестной исходной брони никогда не становится каналом",async()=>{
 for(const rows of [[row(101,"onboard",{ride_id:9}),row(201,"onboard",{ride_id:9})],[row(201)]]){
  const r=gpsRig();r.rows=rows;r.share.open(101);await r.click();assert.equal(r.starts.length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
 }
});
await t.test("устаревший open закрытой брони не открывает её заново",async()=>{
 const r=await ride();r.rows=[row(101,"done"),row(102)];await r.share.action(101,true);const n=r.starts.length;r.share.open(101);
 assert.equal(r.starts.length,n);assert.equal(r.share.snapshot().bookingId,102);r.share.stop();
});
await t.test("mount/keep во время свежего поиска не возвращает старый GPS-канал",async()=>{
 const r=await ride();const gate=deferred();r.gate=gate.promise;r.rows=[row(101,"done"),row(102)];const pending=r.share.action(101,true);
 const n=r.starts.length;r.share.open(101);r.share.keep(r.rows);assert.equal(r.starts.length,n);assert.equal(r.activeWatches().length,0);
 gate.resolve();await pending;assert.equal(r.started().id,102);r.share.stop();
});
await t.test("канал закрывается раньше успешного ответа на Завершить — передача переходит остальным",async()=>{
 const r=await ride();r.share.step(101,true,"start");const ticket=r.share.beginClick(101,7,true);r.started().hooks.onEnd("trip");
 assert.equal(r.activeWatches().length,0);r.share.open(101);r.share.keep(r.rows);assert.equal(r.activeWatches().length,0,"mount во время HTTP не воскресил закрытый канал");
 r.rows=[row(101,"done"),row(102)];await r.share.finishClick(ticket,true);assert.equal(r.started().id,102);r.share.stop();
});
for(const outcome of ["fail","timeout"]) await t.test(`после раннего закрытия канала ${outcome} не оставляет GPS навсегда`,async()=>{
 const r=await ride();r.share.step(101,true,"start");r.started().hooks.onEnd("trip");
 if(outcome==="fail")r.share.step(101,true,"fail");else await r.advance(GPS.PENDING_STEP_MS);
 assert.equal(r.activeWatches().length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
for(const why of ["auth","gave-up"])await t.test(`${why}: прекращение без запросов к другим пассажирам`,async()=>{
 const r=await ride();const queries=r.fetches;r.started().hooks.onEnd(why);await settle();assert.equal(r.fetches,queries);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("отказ сервера прекращает выбор, временная сеть повторяется ограниченно",async()=>{
 assert.ok(Number.isSafeInteger(GPS.MAX_RESOLVE_ATTEMPTS)&&GPS.MAX_RESOLVE_ATTEMPTS>0,"fixture экспортирует настоящий положительный предел попыток");
 const refused=await ride();refused.error={status:403};await refused.share.action(101,true);assert.equal(refused.share.snapshot().ride,false,"403 обязан отозвать grant");assert.equal(refused.activeWatches().length,0,"403 обязан снять GPS");refused.share.stop();
 const r=await ride();r.rows=[row(101,"done"),row(102)];r.error={status:0};await r.share.action(101,true);assert.equal(r.activeWatches().length,0);
 r.error=null;await r.advance(2000);assert.equal(r.started().id,102);r.share.stop();
 const limited=await ride();limited.error={status:0};await limited.share.action(101,true);
 for(let i=0;i<GPS.MAX_RESOLVE_ATTEMPTS+2;i++)await limited.advance(30000);
 assert.equal(limited.share.snapshot().ride,false,"исчерпание MAX_RESOLVE_ATTEMPTS обязано отозвать grant");assert.equal(limited.fetches,GPS.MAX_RESOLVE_ATTEMPTS+2,"сеть ограничена исходным числом попыток");assert.equal(limited.activeWatches().length,0,"после исчерпания попыток GPS остановлен");limited.share.stop();
});

await t.test("медленная и status=0 сверка публикуют resolving сразу после остановки передачи",async()=>{
 const r=await ride();r.fix();r.openSocket();assert.equal(r.share.snapshot().state,"sharing");
 const states=[];r.share.subscribe(s=>states.push(s.state));const gate=deferred();r.gate=gate.promise;
 r.rows=[row(101,"done"),row(102)];const pending=r.share.action(101,true);
 assert.equal(r.share.snapshot().state,"resolving");assert.equal(r.activeWatches().length,0);assert.equal(r.sockets[0].closed,true);
 r.error={status:0};gate.resolve();await pending;assert.equal(r.share.snapshot().state,"resolving");
 assert.equal(states.at(-1),"resolving");assert.equal(r.activeWatches().length,0);
 r.gate=null;r.error=null;await r.advance(2000);assert.equal(r.started().id,102);assert.notEqual(r.share.snapshot().state,"sharing","до первой новой точки нет ложного sharing");
 r.fix();r.openSocket();assert.equal(r.share.snapshot().state,"sharing");r.share.stop();
});

await t.test("authoritative terminal A немедленно закрывает A до GET; B сохраняет прежний grant, A не оживает из stale строки",async()=>{
 const r=await ride();r.fix();r.openSocket();const a=r.started(),grant=r.share.snapshot().grantId,gate=deferred();r.gate=gate.promise;
 r.share.terminal(101);assert.equal(r.activeWatches().length,0,"A закрыт синхронно, без grace");assert.equal(r.sockets[0].closed,true);
 assert.equal(r.share.snapshot().state,"resolving");const reads=r.fetches;r.share.terminal(101);assert.equal(r.fetches,reads,"повтор terminal не дублирует pending GET");
 // GET может ещё показать A onboard: authoritative terminal запрещает возврат к A.
 r.rows=[row(101),row(102)];gate.resolve();await settle();assert.equal(r.started().id,102);assert.equal(r.share.snapshot().grantId,grant);assert.equal(r.ids,1);
 a.hooks.onState("sharing");a.hooks.onEnd("trip");assert.equal(r.started().id,102,"старые callback A не закрывают B");
 r.gate=null;r.rows=[row(101,"cancelled"),row(102,"done")];r.share.terminal(102);assert.equal(r.activeWatches().length,0,"последний carrier закрыт до сверки");
 await settle();assert.equal(r.share.snapshot().ride,false);assert.equal(r.activeWatches().length,0);r.share.retry();assert.equal(r.started().id,102);r.share.stop();
});
await t.test("terminal вне carrier не закрывает живую B; cold terminal не создаёт grant/запрос/GPS",async()=>{
 const cold=gpsRig();cold.share.open(101);cold.share.terminal(101);assert.equal(cold.ids,0);assert.equal(cold.fetches,0);assert.equal(cold.watches.length,0);cold.share.stop();
 const r=await ride();r.rows=[row(101,"done"),row(102),row(103)];await r.share.action(101,true);assert.equal(r.started().id,102);
 const b=r.started(),reads=r.fetches;r.share.terminal(103);assert.equal(r.started(),b);assert.equal(r.activeWatches().length,1);assert.equal(r.fetches,reads);r.share.stop();
});
await t.test("первый confirmed-канал получает no-show срок; открытие и другой экран его не обнуляют",async()=>{
 const r=gpsRig();r.rows=[row(101,"confirmed",{no_show_wait_sec:120}),row(102,"confirmed",{no_show_wait_sec:0})];r.share.open(101);await r.click();
 await r.advance(60000);r.share.open(101);r.share.open(102);await settle();await r.advance(61000);r.rows=[row(101,"confirmed",{no_show_wait_sec:0}),row(102,"confirmed",{no_show_wait_sec:0})];r.fix();await settle();
 assert.equal(r.activeWatches().length,0);assert.equal(r.frames().length,0);r.share.stop();
});
await t.test("кабинет не создаёт передачу, но держит уже разрешённый рейс при уходе со страницы",async()=>{
 const r=gpsRig();assert.equal(r.share.keep(r.rows),null);assert.equal(r.starts.length,0);r.release=r.share.open(101);await r.click();
 const cabinet=r.share.keep(r.rows);assert.equal(typeof cabinet,"function");r.release();await r.advance(GPS.HOLD_GRACE_MS);assert.equal(r.activeWatches().length,1);
 cabinet();await r.advance(GPS.HOLD_GRACE_MS);assert.equal(r.activeWatches().length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("медленный переход в кабинет имеет прежний предел HANDOFF; повтор не продлевает его",async()=>{
 const r=await ride();r.share.handoff();r.release();await r.advance(GPS.HOLD_GRACE_MS);assert.equal(r.activeWatches().length,1);
 r.share.handoff();await r.advance(GPS.HANDOFF_MS);await r.advance(GPS.HOLD_GRACE_MS);assert.equal(r.activeWatches().length,0);r.share.stop();
});
await t.test("открытие другого рейса не удерживает прежнюю передачу и не отменяет grace-срок",async()=>{
 const r=await ride();r.release();r.share.open(201);await r.advance(GPS.HOLD_GRACE_MS);
 assert.equal(r.activeWatches().length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("late HTTP после pending-срока не обходит остановку даже без исполнения таймера",async()=>{
 const r=await ride();r.share.step(101,true,"start");r.started().hooks.onEnd("trip");r.elapsed+=GPS.PENDING_STEP_MS;
 r.rows=[row(101,"done"),row(102)];await r.share.action(101,true);assert.equal(r.activeWatches().length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});

await t.test("подписка сразу отдаёт состояние, поздний старый callback не влияет на новый канал",async()=>{
 const r=await ride();let state;const off=r.share.subscribe(value=>state=value);assert.equal(state.bookingId,101);const old=r.started();
 r.rows=[row(101,"done"),row(102)];await r.share.action(101,true);old.hooks.onEnd("auth");old.hooks.onState("ended");assert.equal(r.share.snapshot().bookingId,102);assert.equal(r.share.snapshot().ride,true);off();r.share.stop();
});
await t.test("координаты/grant не записываются в журнал, аналитику или постоянное хранилище",()=>{
 for(const rel of ["src/utils/rideCarrier.ts","src/utils/rideLocationShare.ts","src/utils/driverRideShare.ts","src/utils/rideShareStore.ts"]){
  const code=readFileSync(ROOT+rel,"utf8").replace(/\/\*[\s\S]*?\*\//g,"").replace(/\/\/.*$/gm,"");
  assert.ok(!/console\.|track\(|trackOnce\(|localStorage|sessionStorage|indexedDB/.test(code),rel);
 }
});
t.done();
