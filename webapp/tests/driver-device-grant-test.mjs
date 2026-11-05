import assert from "node:assert/strict";
import { build } from "esbuild";
import { ROOT, bundleComponent, button, checker, langMock, mount, text, ofType } from "./shallow-harness.mjs";
import { GPS, gpsRig, row, settle, deferred } from "./gps-grant-fixture.mjs";
const t=checker();
const nothing=r=>{assert.equal(r.watches.length,0,"запущен GPS");assert.equal(r.sockets.length,0,"открыт сокет");assert.equal(r.frames().length,0,"отправлен кадр");};

for(const stage of ["confirmed","onboard"]) await t.test(`серверный ${stage} и выданный OSpermission без нажатия не дают публикацию`,async()=>{
  const r=gpsRig();r.rows=[row(101,stage)];r.share.open(101);await r.share.action(101,false);r.share.retry();await settle();
  nothing(r);assert.equal(r.fetches,0);assert.equal(r.share.keep(r.rows),null);r.share.stop();
});
await t.test("ticket возникает до HTTP, pending не включает GPS, ответ активирует только этот ticket",async()=>{
  const r=gpsRig();r.share.open(101);const ticket=r.share.beginClick(101,7,false);nothing(r);
  await r.share.finishClick(Symbol("другой ответ"),true);nothing(r);
  await r.share.finishClick(ticket,true);assert.equal(r.watches.length,1);r.fix();r.openSocket();assert.equal(r.frames().length,1);r.share.stop();
});
for(const failure of ["отказ","таймаут","сеть"]) await t.test(`pending ${failure}: поздний ответ и повтор не включают GPS`,async()=>{
  const r=gpsRig();r.share.open(101);const ticket=r.share.beginClick(101,7,false);
  if(failure==="таймаут")await r.advance(GPS.PENDING_STEP_MS);else await r.share.finishClick(ticket,false);
  await r.share.finishClick(ticket,true);r.share.open(101);r.share.retry();await settle();nothing(r);r.share.stop();
});
for(const change of [r=>r.session="B",r=>r.origin="https://other.example.test",r=>r.token=false]) await t.test("запоздалый HTTP-ответ чужого владельца/origin/входа не выдаёт grant",async()=>{
  const r=gpsRig();r.share.open(101);const ticket=r.share.beginClick(101,7,false);change(r);await r.share.finishClick(ticket,true);nothing(r);assert.equal(r.fetches,0);r.share.stop();
});
for(const change of [r=>r.session="B",r=>r.origin="https://other.example.test",r=>r.token=false]) await t.test("смена владельца при поиске канала не открывает сокет после позднего ответа",async()=>{
  const r=gpsRig();r.share.open(101);const gate=deferred();r.gate=gate.promise;const ticket=r.share.beginClick(101,7,false);
  const pending=r.share.finishClick(ticket,true);change(r);gate.resolve();await pending;nothing(r);r.share.stop();
});
await t.test("опоздавший timer pending не позволяет ответу после предельного срока выдать grant",async()=>{
  const r=gpsRig();r.share.open(101);const ticket=r.share.beginClick(101,7,false);r.elapsed+=GPS.PENDING_STEP_MS;
  await r.share.finishClick(ticket,true);nothing(r);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("cold reload/новый процесс не принимает старый ticket и активный снимок",async()=>{
  const old=gpsRig();old.share.open(101);const ticket=await old.click();old.share.stop();
  const cold=gpsRig();cold.rows=structuredClone(old.rows);cold.share.open(101);await cold.share.finishClick(ticket,true);await cold.share.action(101,false);nothing(cold);assert.equal(cold.fetches,0);cold.share.stop();
});
await t.test("срок начинается до сети и не обновляется повторным HTTP/сменой брони/Settings resume",async()=>{
  const r=gpsRig();r.share.open(101);const ticket=r.share.beginClick(101,7,false);const issued=r.elapsed;
  await r.advance(30_000);await r.share.finishClick(ticket,true);await r.advance(3600_000);await r.click(102);r.share.open(102);r.share.retry();
  await r.advance(GPS.RIDE_SHARE_MAX_MS-(r.elapsed-issued));assert.equal(r.share.snapshot().ride,false);assert.equal(r.activeWatches().length,0);
  const starts=r.starts.length;await r.share.action(102,false);r.share.retry();assert.equal(r.starts.length,starts);r.share.stop();
});
await t.test("часы вперёд отзывают grant необратимо: rollback и старый ответ не оживляют",async()=>{
  const r=gpsRig();r.share.open(101);const ticket=await r.click();const initial=r.wall;r.wall+=GPS.RIDE_SHARE_MAX_MS+1;
  assert.equal(r.started().hooks.allowSend(),false);const count=r.watches.length;r.wall=initial-100000;await r.share.finishClick(ticket,true);r.share.retry();r.share.open(101);r.fix();
  assert.equal(r.watches.length,count);assert.equal(r.activeWatches().length,0);assert.equal(r.frames().length,0);r.share.stop();
});
for(const change of [r=>r.origin="https://other.example.test",r=>r.wall+=GPS.RIDE_SHARE_MAX_MS+1])await t.test("утрата права между созданием сокета и onopen не отправляет даже auth/loc кадры",async()=>{
 const r=gpsRig();r.share.open(101);await r.click();r.fix();assert.equal(r.sockets.length,1);change(r);r.openSocket();
 assert.equal(r.sockets[0].sent.length,0);assert.equal(r.activeWatches().length,0);r.share.stop();
});
await t.test("монотонные часы не могут откатиться внутри разрешённого процесса",async()=>{
  const r=gpsRig();r.share.open(101);await r.click();r.elapsed=0;assert.equal(r.started().hooks.allowSend(),false);assert.equal(r.activeWatches().length,0);r.share.stop();
});
for(const source of ["Permissions API","watchPosition PERMISSION_DENIED"]) await t.test(`${source}: отзыв закрывает всё; повторная выдача и retry не возвращают прежний grant`,async()=>{
  const r=gpsRig();r.share.open(101);await r.click();r.fix();r.openSocket();const frames=r.frames().length;
  if(source==="Permissions API")r.permission();else r.watches.at(-1).fail({code:1});
  assert.equal(r.share.snapshot().ride,false);assert.equal(r.activeWatches().length,0);assert.equal(r.sockets.at(-1).closed,true);
  const count=r.watches.length;r.share.retry();r.share.open(101);r.fix();assert.equal(r.watches.length,count);assert.equal(r.frames().length,frames);
  await r.click();assert.equal(r.watches.length,count+1,"новое живое нажатие может дать только новый grant");assert.equal(r.ids,2);r.share.stop();
});
await t.test("Settings resume без отзыва продолжает один действующий grant на остаток срока",async()=>{
  const r=gpsRig();r.share.open(101);await r.click();await r.advance(1000);r.share.open(101);r.share.retry();assert.equal(r.ids,1);
  r.fix();r.openSocket();assert.equal(r.frames().length,1);r.share.stop();
});
await t.test("API/queued replay сам не создаёт разрешение и не делает запрос списка",async()=>{
  const r=gpsRig();r.share.open(101);for(let i=0;i<3;i++){r.share.step(101,false,"start");await r.share.action(101,false);}nothing(r);assert.equal(r.ids,0);assert.equal(r.fetches,0);r.share.stop();
});

await t.test("отдельное enable на onboard без driver_phase: pending до свежего GET, без GPS до ответа",async()=>{
 const r=gpsRig();r.rows=[row(101,"onboard",{driver_phase:""})];r.share.open(101);const gate=deferred();r.gate=gate.promise;
 const issued=r.elapsed;const pending=r.share.enable(101,7);assert.equal(r.ids,1);assert.equal(r.fetches,1);nothing(r);
 assert.equal(r.share.snapshot().state,"resolving");await r.advance(30_000);gate.resolve();await pending;assert.equal(r.activeWatches().length,1);
 await r.advance(1000);r.gate=null;await r.share.enable(101,7);assert.equal(r.ids,1,"повтор enable действующего grant не выдаёт новый срок");
 await r.advance(GPS.RIDE_SHARE_MAX_MS-(r.elapsed-issued));assert.equal(r.share.snapshot().ride,false);assert.equal(r.activeWatches().length,0);r.share.stop();
});
for(const mode of ["enable","phase reply"])await t.test(`${mode}: confirmed wait=70, GET held=75 до pending=90 — ни одной GPS подписки/socket/frame`,async()=>{
 const r=gpsRig();r.rows=[row(101,"confirmed",{no_show_wait_sec:70,no_show_at:new Date(r.wall+70000).toISOString()})];r.share.open(101);
 const gate=deferred();r.gate=gate.promise;const pending=mode==="enable"?r.share.enable(101,7):r.share.finishClick(r.share.beginClick(101,7,false),true);
 assert.equal(r.fetches,1);nothing(r);await r.advance(75000);nothing(r);gate.resolve();await pending;await settle();
 nothing(r);assert.equal(r.share.snapshot().ride,false,"held response не открыл уже истёкший carrier");r.share.stop();
});
await t.test("новый confirmed carrier не получает время, проведённое в GET после закрытия прежнего onboard",async()=>{
 const r=gpsRig();r.share.open(101);await r.click();r.fix();r.openSocket();const starts=r.starts.length,sockets=r.sockets.length,frames=r.frames().length;
 r.rows=[row(101,"done"),row(102,"confirmed",{no_show_wait_sec:70,no_show_at:new Date(r.wall+70000).toISOString()})];
 const gate=deferred();r.gate=gate.promise;const pending=r.share.action(101,true);assert.equal(r.activeWatches().length,0);
 await r.advance(75000);gate.resolve();await pending;await settle();r.fix();
 assert.equal(r.starts.length,starts);assert.equal(r.sockets.length,sockets);assert.equal(r.frames().length,frames);assert.equal(r.activeWatches().length,0);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
for(const zone of ["Z","naive UTC"])await t.test(`${zone}: абсолютный no_show_at ограничивает более длинный относительный wait`,async()=>{
 const r=gpsRig();let at=new Date(r.wall+70000).toISOString();if(zone==="naive UTC")at=at.slice(0,-1);
 r.rows=[row(101,"confirmed",{no_show_wait_sec:180,no_show_at:at})];r.share.open(101);const gate=deferred();r.gate=gate.promise;
 const pending=r.share.enable(101,7);await r.advance(75000);gate.resolve();await pending;nothing(r);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("monotonic GET-start bound не продлевается wall rollback во время HTTP",async()=>{
 const r=gpsRig();r.rows=[row(101,"confirmed",{no_show_wait_sec:70,no_show_at:new Date(r.wall+70000).toISOString()})];r.share.open(101);const gate=deferred();r.gate=gate.promise;
 const pending=r.share.enable(101,7);await r.advance(75000,{wall:-1000});gate.resolve();await pending;nothing(r);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("carrier wall expiry проверяется перед кадром; rollback при held GET не возрождает старый срок",async()=>{
 const r=gpsRig(),initialWall=r.wall;r.rows=[row(101,"confirmed",{no_show_wait_sec:180,no_show_at:new Date(r.wall+70000).toISOString()})];r.share.open(101);
 await r.share.enable(101,7);r.fix();r.openSocket();assert.equal(r.frames().length,1);const starts=r.starts.length,sockets=r.sockets.length;
 const gate=deferred();r.gate=gate.promise;r.wall+=75000;r.fix();assert.equal(r.activeWatches().length,0);assert.equal(r.sockets[0].closed,true);assert.equal(r.frames().length,1);
 r.wall=initialWall;gate.resolve();await settle();r.share.retry();r.fix();assert.equal(r.starts.length,starts);assert.equal(r.sockets.length,sockets);assert.equal(r.frames().length,1);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
for(const invalid of [[],[row(101,"done")],[row(101,"onboard",{ride_id:99})],[row(102)]]) await t.test("enable отвергает отсутствующую/закрытую/чужую canonical own-row из свежего GET",async()=>{
 const r=gpsRig();r.rows=invalid;r.share.open(101);await r.share.enable(101,7);nothing(r);assert.equal(r.share.snapshot().ride,false);assert.equal(r.fetches,1);r.share.stop();
});
for(const refusal of [0,403]) await t.test(`enable GET status=${refusal}: нет автоактивации и повторной сети`,async()=>{
 const r=gpsRig();r.share.open(101);r.error={status:refusal};await r.share.enable(101,7);nothing(r);assert.equal(r.share.snapshot().ride,false);
 await r.advance(30000);r.share.retry();assert.equal(r.fetches,1);nothing(r);r.share.stop();
});
for(const change of [r=>r.session="B",r=>r.origin="https://other.example.test",r=>r.token=false,r=>r.elapsed+=GPS.PENDING_STEP_MS]) await t.test("late enable GET не обходит owner/origin/token/pending deadline",async()=>{
 const r=gpsRig();r.share.open(101);const gate=deferred();r.gate=gate.promise;const pending=r.share.enable(101,7);nothing(r);
 change(r);gate.resolve();await pending;nothing(r);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});
await t.test("опоздавший pending timer не позволяет новому клику продлить уже истёкший pending grant",async()=>{
 const r=gpsRig();r.share.open(101);const old=r.share.beginClick(101,7,false);r.elapsed+=GPS.PENDING_STEP_MS;
 const fresh=r.share.beginClick(101,7,false);assert.equal(r.ids,2,"просроченный pending отозван; новое нажатие создаёт новое разрешение");
 await r.share.finishClick(old,true);nothing(r);await r.share.finishClick(fresh,true);assert.equal(r.activeWatches().length,1);r.share.stop();
});
await t.test("authoritative terminal раньше ответа обычного шага не позволяет старому HTTP активировать pending grant",async()=>{
 const r=gpsRig();r.share.open(101);const ticket=r.share.beginClick(101,7,false);r.share.terminal(101);
 await r.share.finishClick(ticket,true);nothing(r);assert.equal(r.share.snapshot().ride,false);assert.equal(r.fetches,0);r.share.stop();
});

for(const lang of ["ru","ba"])await t.test(`${lang}: реальная кнопка enable доступна после cold onboard; pending/slow/status0 не пишут «машину видят»`,async()=>{
 const r=gpsRig();r.posts=[];r.rows=[row(101,"onboard",{driver_phase:""}),row(102)];globalThis.__enableView=r;
 const View=await bundleComponent("src/components/DriverLocationShare.tsx",{mocks:{
  "/i18n/lang":langMock(lang),"/api/client":`export const getSessionGeneration=()=>globalThis.__enableView.session;`,
  "/api/driver":`export const setDriverStatus=(...args)=>{globalThis.__enableView.posts.push(args);throw new Error("enable не меняет phase");};`,
  "/api/bookings":`export const markArriving=(...args)=>{globalThis.__enableView.posts.push(args);throw new Error("enable не меняет arriving");};`,
  "/utils/driverRideShare":`export const rideShareOpen=id=>globalThis.__enableView.share.open(id);export const rideShareRetry=()=>globalThis.__enableView.share.retry();export const rideShareEnable=(...args)=>globalThis.__enableView.share.enable(...args);`,
  "/utils/rideShareStore":`export const subscribeRideShare=fn=>globalThis.__enableView.share.subscribe(fn);`
 }});
 const h=mount(View,{bookingId:101,rideId:7,inTransit:true});h.render();await h.settle();nothing(r);
 const label=lang==="ru"?"Включить передачу места":"Урынды ебәреүҙе ҡабыҙырға";
 const paused=lang==="ru"?"Передача места пока приостановлена":"Урынды ебәреү әлегә туҡтатылған";
 const live=lang==="ru"?"Машину видят":"машинаңды күрә";
 const gate=deferred();r.gate=gate.promise;const click=button(h.tree,label).props.onClick;click();click();await h.settle();
 assert.equal(r.ids,1);assert.equal(r.fetches,1);nothing(r);assert.equal(button(h.tree,label).props.disabled,true);
 assert.ok(text(h.tree).includes(paused));assert.ok(!text(h.tree).includes(live));
 gate.resolve();await h.settle();await settle();r.gate=null;r.fix();r.openSocket();await h.settle();assert.ok(text(h.tree).includes(live));
 const delayed=deferred();r.gate=delayed.promise;r.rows=[row(101,"done"),row(102)];const switching=r.share.action(101,true);await h.settle();
 assert.equal(r.activeWatches().length,0);assert.ok(text(h.tree).includes(paused));assert.ok(!text(h.tree).includes(live));
 r.error={status:0};delayed.resolve();await switching;await h.settle();assert.ok(text(h.tree).includes(paused));assert.ok(!text(h.tree).includes(live));
 r.permission();await h.settle();assert.ok(button(h.tree,label));assert.equal(button(h.tree,lang==="ru"?"Повторить":"Ҡабатлау"),undefined);
 r.gate=null;r.error=null;r.rows=[row(101,"onboard",{driver_phase:""})];button(h.tree,label).props.onClick();await h.settle();await settle();assert.equal(r.ids,2);assert.equal(r.activeWatches().length,1);
 assert.deepEqual(r.posts,[],"кнопка включает место без изменения бизнес-этапа и уведомлений");
 h.unmount();r.share.stop();
});

await t.test("реальная enable-кнопка снова доступна после pending timeout; старый GET не активирует и не мешает новому нажатию",async()=>{
 const r=gpsRig();globalThis.__enableTimeout=r;const gate=deferred();r.gate=gate.promise;
 const View=await bundleComponent("src/components/DriverLocationShare.tsx",{mocks:{
  "/i18n/lang":langMock("ru"),"/api/client":`export const getSessionGeneration=()=>globalThis.__enableTimeout.session;`,
  "/utils/driverRideShare":`export const rideShareOpen=id=>globalThis.__enableTimeout.share.open(id);export const rideShareRetry=()=>globalThis.__enableTimeout.share.retry();export const rideShareEnable=(...args)=>globalThis.__enableTimeout.share.enable(...args);`,
  "/utils/rideShareStore":`export const subscribeRideShare=fn=>globalThis.__enableTimeout.share.subscribe(fn);`
 }});
 const h=mount(View,{bookingId:101,rideId:7,inTransit:true});h.render();button(h.tree,"Включить передачу места").props.onClick();await h.settle();nothing(r);
 await r.advance(GPS.PENDING_STEP_MS);await h.settle();assert.equal(button(h.tree,"Включить передачу места").props.disabled,false);nothing(r);
 const next=deferred();r.gate=next.promise;button(h.tree,"Включить передачу места").props.onClick();await h.settle();assert.equal(r.ids,2);assert.equal(r.fetches,2);nothing(r);
 // finally первого зависшего GET приходит во время второго pending и не снимает его singleflight.
 const count=r.starts.length;gate.resolve();await h.settle();await settle();assert.equal(r.starts.length,count);nothing(r);
 button(h.tree,"Включить передачу места").props.onClick();await h.settle();assert.equal(r.fetches,2,"старый finally не разрешил третий GET");
 next.resolve();await h.settle();await settle();assert.equal(r.activeWatches().length,1);assert.equal(r.share.snapshot().ride,true);
 h.unmount();r.share.stop();
});
await t.test("реальный DriverBoarding: mount не запускает GPS, двойной тап даёт один HTTP и один ticket до запроса",async()=>{
  const r=gpsRig();r.share.open(101);const http=deferred();const order=[];globalThis.__grantComponent={r,order,http};
  const View=await bundleComponent("src/components/DriverBoarding.tsx",{mocks:{
    "/i18n/lang":langMock("ru"),"/api/client":`export class ApiError extends Error{}`,
    "/api/bookings":`export const markBoarded=()=>{const f=globalThis.__grantComponent;f.order.push("HTTP");return f.http.promise;};export const undoBoarded=async()=>({status:"confirmed"});`,
    "/utils/driverActions":`export const beginDriverClick=(...args)=>{const f=globalThis.__grantComponent;f.order.push("CLICK");return f.r.share.beginClick(...args);};export const finishDriverClick=(...args)=>{void globalThis.__grantComponent.r.share.finishClick(...args);};`
  }});
  const h=mount(View,{bookingId:101,rideId:7,status:"confirmed",onChanged:()=>{},onError:()=>{}});h.render();nothing(r);
  const click=button(h.tree,"Сел").props.onClick;click();click();assert.deepEqual(order,["CLICK","HTTP"]);nothing(r);
  http.resolve({status:"onboard"});await h.settle();await settle();assert.equal(r.watches.length,1);r.fix();r.openSocket();assert.equal(r.frames().length,1);h.unmount();r.share.stop();
});
await t.test("уход реального контрола до ответа отменяет pending: late HTTP не включает GPS",async()=>{
 const r=gpsRig();r.share.open(101);const http=deferred();globalThis.__goneGrant={r,http};
 const View=await bundleComponent("src/components/DriverBoarding.tsx",{mocks:{
  "/i18n/lang":langMock("ru"),"/api/client":`export class ApiError extends Error{}`,
  "/api/bookings":`export const markBoarded=()=>globalThis.__goneGrant.http.promise;export const undoBoarded=async()=>({});`,
  "/utils/driverActions":`export const beginDriverClick=(...args)=>globalThis.__goneGrant.r.share.beginClick(...args);export const finishDriverClick=(...args)=>{void globalThis.__goneGrant.r.share.finishClick(...args);};`
 }});
 const h=mount(View,{bookingId:101,rideId:7,status:"confirmed",onChanged:()=>{},onError:()=>{}});h.render();button(h.tree,"Сел").props.onClick();h.unmount();
 http.resolve({status:"onboard"});await settle();nothing(r);assert.equal(r.share.snapshot().ride,false);r.share.stop();
});

for(const lang of ["ru","ba"]) await t.test(`${lang}: реальный GPS-компонент при cold active остаётся без подписки; UI не выдаёт право повтором`,async()=>{
  const r=gpsRig();globalThis.__grantView=r;
  const View=await bundleComponent("src/components/DriverLocationShare.tsx",{mocks:{
    "/i18n/lang":langMock(lang),
    "/utils/driverRideShare":`export const rideShareOpen=id=>globalThis.__grantView.share.open(id);export const rideShareRetry=()=>globalThis.__grantView.share.retry();export const rideShareEnable=(...args)=>globalThis.__grantView.share.enable(...args);`,
    "/utils/rideShareStore":`export const subscribeRideShare=fn=>globalThis.__grantView.share.subscribe(fn);`
  }});
  const h=mount(View,{bookingId:101,rideId:7,inTransit:true});h.render();await h.settle();nothing(r);
  assert.ok(text(h.tree).includes(lang==="ru"?"Передача места выключена":"Урынды ебәреү һүндерелгән"));
  await r.click();r.fix();r.openSocket();await h.settle();assert.equal(r.frames().length,1);
  r.permission();await h.settle();assert.equal(button(h.tree,lang==="ru"?"Повторить":"Ҡабатлау"),undefined);
  h.unmount();r.share.stop();
});

await t.test("настоящий кабинет: первый NoShow до GPS-страницы даёт один ticket/HTTP и продолжение через другую бронь",async()=>{
 const r=gpsRig();r.rows=[row(101,"confirmed",{passenger_name:"Айгуль",route:"Сибай → Уфа",passenger_rating:null,my_stars:0}),row(102,"onboard",{passenger_name:"Бота",route:"Сибай → Уфа",passenger_rating:null,my_stars:0})];
 const http=deferred();const order=[];globalThis.__cabGrant={r,http,order};
 const View=await bundleComponent("src/components/DriverPassengers.tsx",{mocks:{
  "/i18n/lang":langMock("ru"),"/api/client":`export class ApiError extends Error{};export const getSessionGeneration=()=>globalThis.__cabGrant.r.session;`,
  "/api/bookings":`export const fetchDriverBookings=async()=>structuredClone(globalThis.__cabGrant.r.rows);export const markNoShow=()=>{globalThis.__cabGrant.order.push("HTTP");return globalThis.__cabGrant.http.promise;};export const markArriving=async()=>({});export const rateBooking=async()=>({});export const answerMeetingPoint=async()=>({});`,
  "/utils/driverActions":`export const beginDriverClick=(...args)=>{globalThis.__cabGrant.order.push("CLICK");return globalThis.__cabGrant.r.share.beginClick(...args);};export const finishDriverClick=(...args)=>{void globalThis.__cabGrant.r.share.finishClick(...args);};`,
  "/utils/rideShareStore":`export const rideShareKeep=rows=>globalThis.__cabGrant.r.share.keep(rows);export const subscribeRideShare=fn=>globalThis.__cabGrant.r.share.subscribe(fn);export const takeRidingFocus=()=>false;`
 }});
 const h=mount(View,{onOpenTrip:()=>{}});h.render();await h.settle();nothing(r);
 assert.equal(ofType(h.tree,"DriverBoarding")[0].props.rideId,7,"canonical ride передан контролу посадки");
 const callback=ofType(h.tree,"DriverNoShow")[0].props.onConfirm;callback();callback();assert.deepEqual(order,["CLICK","HTTP"]);nothing(r);
 r.rows=[row(101,"cancelled"),row(102,"onboard")];http.resolve({status:"cancelled"});await h.settle();await settle();
 assert.equal(r.started().id,102);r.fix();r.openSocket();assert.equal(r.frames().length,1);assert.ok(r.sockets.at(-1).url.includes("/102/"));
 h.unmount();await r.advance(GPS.HOLD_GRACE_MS);assert.equal(r.activeWatches().length,0);r.share.stop();
});
for(const remains of [true,false])await t.test(`настоящий ActiveTrip: Завершить при remains=${remains} сохраняет правильный переход`,async()=>{
 const scene={owner:"A",status:"onboard",phases:[],order:[],nav:[],handoff:0,focus:0,remains};globalThis.__finishGrant=scene;
 const client=`export class ApiError extends Error{};export const getSessionGeneration=()=>globalThis.__finishGrant.owner;export const apiPost=async()=>({});
  export const apiGet=async path=>{const f=globalThis.__finishGrant;if(path.endsWith("/details"))return {booking_id:42,ride_id:7,role:"driver",status:f.status,from_city:"Сибай",to_city:"Уфа",depart_at:"2030-01-01T10:00:00",seats:1,price:700,driver_name:"Водитель",passenger_name:"Айгуль",pay_method:"cash"};
   if(path.endsWith("/role"))return {role:"driver",status:f.status,driver_phase:"departed"};if(path.endsWith("/boarding-code"))return {code:"1234"};throw new Error("synthetic absent "+path);};`;
 const View=await bundleComponent("src/screens/ActiveTripScreen.tsx",{real:["/api/bookings","/utils/boardingUndo","/utils/tripLocationShare","/utils/tripDetailsSync.js","/utils/inTransitCancel"],mocks:{
  "/i18n/lang":langMock("ru"),"react-router-dom":`export const useNavigate=()=>p=>globalThis.__finishGrant.nav.push(p);export const useParams=()=>({id:"42"});`,
  "/auth/AuthProvider":`export const useAuth=()=>({user:{id:1}});`,"/api/client":client,"./client":client,
  "/api/chat":`export const openTripLocation=()=>({close(){}});`,"/booking/OwnBookingsProvider":`export const useRefreshOwnBookings=()=>()=>{};`,"/analytics":`export const track=()=>{};`,
  "/api/driver":`export const setDriverStatus=async(id,status)=>{const f=globalThis.__finishGrant;f.order.push("HTTP");f.phases.push([id,status]);f.status="done";return {ok:true};};`,
  "/utils/driverActions":`export const emitDriverAction=()=>{};export const driverStep=(_id,_closing,request)=>request();export const beginDriverClick=()=>{globalThis.__finishGrant.order.push("CLICK");return Symbol("click");};export const finishDriverClick=()=>globalThis.__finishGrant.order.push("ACCEPT");`,
  "/utils/driverRideShare":`export const driverRideRemains=async()=>globalThis.__finishGrant.remains;export const rideShareTerminal=()=>{};`,
  "/utils/rideShareStore":`export const rideShareHandoff=()=>globalThis.__finishGrant.handoff++;export const requestRidingFocus=()=>globalThis.__finishGrant.focus++;`
 }});
 const h=mount(View);h.render();await h.settle();button(h.tree,"Завершить поездку").props.onClick();await h.settle();
 ofType(h.tree,"AlertDialog")[0].props.confirm.onClick();await h.settle();
 assert.deepEqual(scene.order,["CLICK","HTTP","ACCEPT"]);assert.deepEqual(scene.phases,[[42,"done"]]);assert.equal(scene.handoff,1);
 assert.deepEqual(scene.nav,remains?["/driver"]:[]);assert.equal(scene.focus,remains?1:0);h.unmount();
});

await t.test("настоящие API/проводка/шина: queued status не создаёт grant; только связанное UI-нажатие до HTTP",async()=>{
  const r={requests:[],gets:[],starts:[],rows:[row(101)],generation:"A",time:1000,timers:[],ticket:null,queue:[],permissionListeners:[]};globalThis.__grantWire=r;
  const lib=await build({stdin:{contents:`export {setDriverStatus} from "./src/api/driver.ts";export {markNoShow} from "./src/api/bookings.ts";
    export {beginDriverClick,finishDriverClick} from "./src/utils/driverActions.ts";export {enqueue,flushOutbox} from "./src/utils/outbox.ts";
    export {rideShareOpen,rideShareEnable} from "./src/utils/driverRideShare.ts";export {subscribeRideShare} from "./src/utils/rideShareStore.ts";`,resolveDir:ROOT,loader:"ts"},bundle:true,write:false,format:"esm",platform:"node",logLevel:"silent",
    define:{window:"globalThis.__grantWire.window",performance:"globalThis.__grantWire.performance"},plugins:[{name:"browser-http",setup(b){
      b.onResolve({filter:/\/client$/},()=>({path:"client",namespace:"fake"}));
      b.onResolve({filter:/\/api\/chat$/},()=>({path:"chat",namespace:"fake"}));
      b.onResolve({filter:/\/tripLocationShare$/},()=>({path:"transport",namespace:"fake"}));
      b.onResolve({filter:/outboxStore$/},()=>({path:"queue",namespace:"fake"}));
      b.onLoad({filter:/.*/,namespace:"fake"},a=>({loader:"js",contents:a.path==="client"?`export const API_BASE="https://api.example.test";export const getSessionGeneration=()=>globalThis.__grantWire.generation;export const getToken=()=>"tok";export const refreshRejectedToken=async()=>true;
        export const apiGet=async path=>{globalThis.__grantWire.gets.push(path);return globalThis.__grantWire.rows;};export const apiPost=async(p,b)=>{globalThis.__grantWire.requests.push([p,b]);return {ok:true};};export const apiDelete=async()=>({});export const apiUpload=async()=>({});export class ApiError extends Error{};`
        :a.path==="chat"?`export const wsBase=()=>"wss://api.example.test";export const sendMessageRest=async()=>({});`
        :a.path==="queue"?`export const insertAction=async(a,active)=>{if(active())globalThis.__grantWire.queue.push(a);};export const listActions=async(s,active)=>active()?globalThis.__grantWire.queue.filter(a=>a.session===s):[];export const removeAction=async(a,active)=>{if(active())globalThis.__grantWire.queue=globalThis.__grantWire.queue.filter(b=>b.id!==a.id);};export const clearActions=async()=>{globalThis.__grantWire.queue=[];};`
        :`export const tripLocationUrl=(base,id)=>base+"/ws/trip/"+id+"/location";export const startDriverLocationShare=deps=>{const h={deps,stopped:false,stop(){this.stopped=true;},retry(){}};globalThis.__grantWire.starts.push(h);return h;};`}));
    }}]});
  r.window={setTimeout(fn,ms){const tm={fn,ms};r.timers.push(tm);return tm;},clearTimeout(){},addEventListener(){},removeEventListener(){}};r.performance={now:()=>r.time};
  const navigatorDescriptor=Object.getOwnPropertyDescriptor(globalThis,"navigator");
  const permission={state:"granted",addEventListener(_type,fn){r.permissionListeners.push(fn);},removeEventListener(_type,fn){r.permissionListeners=r.permissionListeners.filter(value=>value!==fn);}};
  Object.defineProperty(globalThis,"navigator",{configurable:true,value:{permissions:{query:async()=>permission}}});
  const W=await import("data:text/javascript;base64,"+Buffer.from(lib.outputFiles[0].text).toString("base64"));const release=W.rideShareOpen(101);
  await W.enqueue(101,"driver_status","departed","synthetic-queued-status");assert.equal(await W.flushOutbox(),true);await settle();assert.equal(r.starts.length,0);assert.equal(r.queue.length,0);
  const ticket=W.beginDriverClick(101,7,false);assert.equal(r.starts.length,0);await W.setDriverStatus(101,"arriving");assert.equal(r.starts.length,0);W.finishDriverClick(ticket,true);await settle();assert.equal(r.starts.length,1);
  permission.state="denied";for(const fn of [...r.permissionListeners])fn();assert.equal(r.starts[0].stopped,true);assert.equal(r.permissionListeners.length,0);
  permission.state="granted";W.rideShareOpen(101);await W.setDriverStatus(101,"departed");await settle();assert.equal(r.starts.length,1,"выдача ОС и API replay не возродили отозванный grant");
  const fresh=W.beginDriverClick(101,7,false);W.finishDriverClick(fresh,true);await settle();assert.equal(r.starts.length,2,"понадобилось новое живое нажатие");
  permission.state="prompt";for(const fn of [...r.permissionListeners])fn();assert.equal(r.starts[1].stopped,true,"granted → prompt тоже является отзывом");
  permission.state="granted";W.rideShareOpen(101);await settle();assert.equal(r.starts.length,2);
  const final=W.beginDriverClick(101,7,false);W.finishDriverClick(final,true);await settle();assert.equal(r.starts.length,3);
  r.generation="B";assert.equal(r.starts[2].deps.allowSend(),false);release();
  const own=W.rideShareOpen(101),posts=r.requests.length,gets=r.gets.length;r.rows=[row(101,"onboard",{driver_phase:""})];
  await W.rideShareEnable(101,7);await settle();assert.equal(r.starts.length,4);assert.equal(r.requests.length,posts,"dedicated enable не вызывает driverStatus/markArriving и другие POST");
  assert.deepEqual(r.gets.slice(gets),["/driver/bookings"],"fresh OWN GET — единственная сеть enable");own();
  if(navigatorDescriptor)Object.defineProperty(globalThis,"navigator",navigatorDescriptor);else delete globalThis.navigator;
});
// Whole-ride terminal: настоящие API, проводка, controller и raw sender.
// Подменены только HTTP/client и браузер; серверный WS close намеренно не приходит.
let wholeRideRigSerial=0;
async function wholeRideTerminalRig(){
 const previous=globalThis.__wholeRideTerminal;
 const r={owner:"A",origin:"https://api.example.test",token:"synthetic-token-A",elapsed:1000,wall:1000000,
  rows:[],gets:[],posts:[],watches:[],sockets:[],timers:[],listeners:new Set(),releases:[],grants:0,rowsGate:null,snapshot:null};
 globalThis.__wholeRideTerminal=r;
 r.post=async path=>({id:Number(path.split("/")[2]),status:path.endsWith("/complete")?"done":"cancelled"});
 r.geo={watchPosition(ok,fail){const w={id:r.watches.length+1,ok,fail,cleared:false};r.watches.push(w);return w.id;},clearWatch(id){const w=r.watches.find(w=>w.id===id);if(w)w.cleared=true;}};
 r.permission={state:"granted",addEventListener(_type,fn){r.listeners.add(fn);},removeEventListener(_type,fn){r.listeners.delete(fn);}};
 r.navigator={geolocation:r.geo,permissions:{query:async()=>r.permission}};
 r.window={setTimeout(fn,ms){const t={fn,ms,done:false};r.timers.push(t);return t;},clearTimeout(t){if(t)t.done=true;},addEventListener(){},removeEventListener(){}};
 r.performance={now:()=>r.elapsed};r.wallNow=()=>r.wall;r.location={href:"https://pwa.example.test/driver"};
 r.crypto={getRandomValues(bytes){bytes.fill(++r.grants);return bytes;}};
 r.WebSocket=class{
  constructor(url){this.url=url;this.readyState=0;this.sent=[];this.closed=false;r.sockets.push(this);}
  send(value){this.sent.push(JSON.parse(value));}
  close(){this.closed=true;this.readyState=3;}
 };
 const built=await build({stdin:{contents:`export {completeRide,cancelRide} from "./src/api/driver.ts";
   export {fetchDriverBookings} from "./src/api/bookings.ts";
   export {rideShareOpen,rideShareEnable,rideShareRetry} from "./src/utils/driverRideShare.ts";
   export {rideShareKeep,subscribeRideShare} from "./src/utils/rideShareStore.ts";
   export {setOrigin,refreshRejectedToken} from "whole-ride-client-boundary";`,resolveDir:ROOT,loader:"ts"},
  bundle:true,write:false,format:"esm",platform:"node",logLevel:"silent",footer:{js:"// isolated whole-ride terminal tab "+(++wholeRideRigSerial)},
  define:{window:"globalThis.__wholeRideTerminal.window",navigator:"globalThis.__wholeRideTerminal.navigator",performance:"globalThis.__wholeRideTerminal.performance",
   crypto:"globalThis.__wholeRideTerminal.crypto","globalThis.crypto":"globalThis.__wholeRideTerminal.crypto",WebSocket:"globalThis.__wholeRideTerminal.WebSocket",location:"globalThis.__wholeRideTerminal.location","Date.now":"globalThis.__wholeRideTerminal.wallNow"},
  plugins:[{name:"whole-ride-browser-http",setup(b){
   b.onResolve({filter:/.*/},a=>a.path==="whole-ride-client-boundary"||/\/client$/.test(a.path)?{path:"client",namespace:"whole-ride-mock"}:/\/api\/chat$/.test(a.path)?{path:"chat",namespace:"whole-ride-mock"}:undefined);
   b.onLoad({filter:/.*/,namespace:"whole-ride-mock"},a=>({loader:"js",contents:a.path==="chat"?`export const wsBase=()=>"wss://api.example.test";`:
    `const r=globalThis.__wholeRideTerminal;
     export let API_BASE=r.origin;export const setOrigin=value=>{API_BASE=value;r.origin=value;};
     export const getSessionGeneration=()=>r.owner;export const getToken=()=>r.token;
     export const refreshRejectedToken=async()=>{r.token+="-refreshed";return true;};
     export const apiGet=async path=>{r.gets.push(path);if(path!=="/driver/bookings")throw new Error("unexpected GET "+path);if(r.rowsGate)await r.rowsGate.promise;return structuredClone(r.rows);};
     export const apiPost=async(path,body)=>{r.posts.push({path,body,owner:r.owner,origin:r.origin});return r.post(path,body);};
     export const apiDelete=async()=>({});export const apiUpload=async()=>({});export class ApiError extends Error{}` }));
  }}]});
 r.W=await import("data:text/javascript;base64,"+Buffer.from(built.outputFiles[0].text).toString("base64"));
 r.off=r.W.subscribeRideShare(s=>{r.snapshot=s;});
 r.active=()=>r.watches.filter(w=>!w.cleared);r.openSockets=()=>r.sockets.filter(s=>!s.closed);
 r.frames=()=>r.sockets.flatMap(s=>s.sent.filter(f=>f.type==="loc"));r.sent=()=>r.sockets.flatMap(s=>s.sent);
 r.fix=()=>r.watches.at(-1)?.ok({coords:{latitude:52.7,longitude:58.6,heading:null}});
 r.openSocket=()=>{const s=r.sockets.at(-1);if(s){s.readyState=1;s.onopen?.({});}return s;};
 r.advance=ms=>{r.elapsed+=ms;r.wall+=ms;};
 r.activate=async(rideId=7,bookingId=101)=>{
  r.rows=[row(bookingId,"onboard",{ride_id:rideId}),row(bookingId+1,"onboard",{ride_id:rideId})];
  const release=r.W.rideShareOpen(bookingId);r.releases.push(release);
  await r.W.rideShareEnable(bookingId,rideId);await settle();
  const cabinet=r.W.rideShareKeep(structuredClone(r.rows));assert.equal(typeof cabinet,"function","настоящий кабинет держит разрешённый рейс");r.releases.push(cabinet);release();
  r.fix();r.openSocket();await settle();assert.equal(r.active().length,1);assert.equal(r.openSockets().length,1);assert.equal(r.snapshot.ride,true);assert.equal(r.snapshot.state,"sharing");
  return r.snapshot.grantId;
 };
 r.revoke=async()=>{r.permission.state="denied";for(const fn of [...r.listeners])fn();await settle();r.permission.state="granted";};
 r.cleanup=async()=>{
  r.rowsGate?.resolve();await r.revoke();for(const release of r.releases.reverse())release();r.off();
  for(const w of r.active())r.geo.clearWatch(w.id);for(const s of r.openSockets())s.close();for(const t of r.timers)t.done=true;
  await settle();if(previous===undefined)delete globalThis.__wholeRideTerminal;else globalThis.__wholeRideTerminal=previous;
 };
 return r;
}
const terminalResult=(action,rideId=7)=>({id:rideId,status:action==="completeRide"?"done":"cancelled"});
const assertNoWholeRidePublisher=(r,why)=>{
 assert.equal(r.active().length,0,why+": GPS должен остановиться сразу");
 assert.equal(r.openSockets().length,0,why+": socket должен закрыться сразу");
 assert.equal(r.snapshot.ride,false,why+": device grant должен быть отозван");
};

for(const action of ["completeRide","cancelRide"]){
 await t.test(`${action}: реальный success немедленно отзывает весь рейс до held cabinet GET, без WS close`,async()=>{
  const r=await wholeRideTerminalRig(),post=deferred();let pending;
  try{
   await r.activate();assert.equal(r.grants,1);
   r.rowsGate=deferred();r.post=()=>post.promise;
   pending=r.W[action](7).then(()=>{r.reloadStarted=true;return r.W.fetchDriverBookings();});
   await settle();assert.equal(r.active().length,1,"pending POST ещё не terminal");assert.equal(r.openSockets().length,1);
   await r.W.refreshRejectedToken();assert.equal(r.active().length,1,"refresh во время POST не меняет owner и сам не отзывает grant");
   post.resolve(terminalResult(action));await settle();assert.equal(r.reloadStarted,true,"кабинет уже ждёт fresh GET");
   assertNoWholeRidePublisher(r,"успешный whole-ride POST при прежнем owner и обновлённом token");
   const sent=r.sent().length,frames=r.frames().length,sockets=r.sockets.length;
   r.advance(8000);r.fix();r.openSocket();r.W.rideShareRetry();await settle();
   assert.equal(r.sent().length,sent,"после terminal даже late onopen не шлёт auth/loc");assert.equal(r.frames().length,frames);assert.equal(r.sockets.length,sockets);assert.equal(r.grants,1);
   await r.W[action](7);await settle();assertNoWholeRidePublisher(r,"успешный API replay");assert.equal(r.grants,1,"повтор API не выдаёт grant");
  }finally{post.resolve(terminalResult(action));r.rows=[];r.rowsGate?.resolve();await pending?.catch(()=>{});await r.cleanup();}
 });

 await t.test(`${action}: поздний success отзывает новый grant того же canonical ride, созданный до ответа`,async()=>{
  const r=await wholeRideTerminalRig(),post=deferred();let pending;
  try{
   const first=await r.activate();r.post=()=>post.promise;pending=r.W[action](7);
   await r.revoke();assert.equal(r.active().length,0);const second=await r.activate(7,102);assert.notEqual(second,first);assert.equal(r.grants,2);
   post.resolve(terminalResult(action));await pending;await settle();assertNoWholeRidePublisher(r,"late terminal закрытого рейса относится и к новому grant того же рейса");assert.equal(r.grants,2);
  }finally{post.resolve(terminalResult(action));await pending?.catch(()=>{});await r.cleanup();}
 });

 await t.test(`${action}: другая текущая canonical ride жива, даже если её booking id равен закрытому ride id`,async()=>{
  const r=await wholeRideTerminalRig(),post=deferred();let pending;
  try{
   await r.activate();r.post=()=>post.promise;pending=r.W[action](7);await r.activate(8,7);const grant=r.snapshot.grantId;
   post.resolve(terminalResult(action));await pending;await settle();assert.equal(r.active().length,1);assert.equal(r.openSockets().length,1);assert.equal(r.snapshot.rideId,8);assert.equal(r.snapshot.grantId,grant);
   const frames=r.frames().length;r.advance(8000);r.fix();assert.equal(r.frames().length,frames+1,"другому рейсу остаётся право отправки");assert.equal(r.grants,2);
  }finally{post.resolve(terminalResult(action));await pending?.catch(()=>{});await r.cleanup();}
 });

 await t.test(`${action}: ответ прежней session/origin не отзывает текущий grant нового owner`,async()=>{
  for(const boundary of ["session","origin"]){
   const r=await wholeRideTerminalRig(),post=deferred();let pending;
   try{
    await r.activate();r.post=()=>post.promise;pending=r.W[action](7);assert.equal(r.posts[0].owner,"A");assert.equal(r.posts[0].origin,"https://api.example.test");
    if(boundary==="session"){r.owner="B";r.token="synthetic-token-B";}else r.W.setOrigin("https://other-api.example.test");
    await r.activate(7,101);const grant=r.snapshot.grantId;assert.equal(r.grants,2);
    post.resolve(terminalResult(action));await pending;await settle();assert.equal(r.snapshot.grantId,grant,boundary);assert.equal(r.active().length,1,boundary);assert.equal(r.openSockets().length,1,boundary);
    const frames=r.frames().length;r.advance(8000);r.fix();assert.equal(r.frames().length,frames+1,boundary+": право текущего owner сохранено");
   }finally{post.resolve(terminalResult(action));await pending?.catch(()=>{});await r.cleanup();}
  }
 });

 await t.test(`${action}: cold/replay не создаёт grant; unrelated success и POST 403/status0 не останавливают текущий рейс`,async()=>{
  const r=await wholeRideTerminalRig();
  try{
   await r.W[action](7);await settle();assert.equal(r.grants,0);assert.equal(r.watches.length,0);assert.equal(r.sockets.length,0);assert.equal(r.gets.length,0);assert.equal(r.snapshot.ride,false);
   await r.activate(8,201);const grant=r.snapshot.grantId;await r.W.refreshRejectedToken();await r.W[action](7);await settle();
   assert.equal(r.snapshot.grantId,grant);assert.equal(r.active().length,1,"token refresh того же owner и terminal другого рейса не отзывает grant");assert.equal(r.grants,1);
   for(const status of [403,0]){
    r.post=async()=>{throw Object.assign(new Error("synthetic POST refusal"),{status});};
    await assert.rejects(r.W[action](8),e=>e.status===status);await settle();assert.equal(r.snapshot.grantId,grant);assert.equal(r.active().length,1);assert.equal(r.openSockets().length,1);
    const frames=r.frames().length;r.advance(8000);r.fix();assert.equal(r.frames().length,frames+1,"отказ/обрыв не являются terminal");assert.equal(r.grants,1);
   }
  }finally{await r.cleanup();}
 });
}

t.done();
