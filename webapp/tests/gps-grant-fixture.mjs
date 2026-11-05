// Настоящий контроллер и транспорт. Подменены только браузер, HTTP и часы.
import { build } from "esbuild";
import { ROOT } from "./shallow-harness.mjs";
const bundle = await build({ stdin: { contents: `export {createRideShare,HOLD_GRACE_MS,HANDOFF_MS,PENDING_STEP_MS,MAX_RESOLVE_ATTEMPTS} from "./src/utils/rideLocationShare.ts";
  export {pickRideCarrier,RIDE_SHARE_MAX_MS} from "./src/utils/rideCarrier.ts";
  export {startDriverLocationShare,tripLocationUrl} from "./src/utils/tripLocationShare.ts";`, resolveDir: ROOT, loader: "ts" },
  bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
export const GPS = await import("data:text/javascript;base64," + Buffer.from(bundle.outputFiles[0].text).toString("base64"));
export const row = (id, status = "onboard", extra = {}) => ({booking_id:id,ride_id:7,status,pickup_stop:"",no_show_wait_sec:0,...extra});
export const settle = async () => { for(let i=0;i<12;i++) await new Promise(resolve=>setImmediate(resolve)); };
export function deferred() { let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject}; }
export function gpsRig() {
  const r={session:"A",origin:"https://api.example.test",token:true,elapsed:1000,wall:1000000,rows:[row(101),row(102)],
    watches:[],cleared:[],sockets:[],starts:[],states:[],timers:[],fetches:0,gate:null,error:null,permission:null,ids:0};
  const clock={
    setTimer(fn,ms){const timer={fn,ms,at:r.elapsed+ms,done:false};r.timers.push(timer);return timer;},
    clearTimer(timer){if(timer)timer.done=true;}
  };
  r.geo={watchPosition(ok,fail,options){const watch={id:r.watches.length+1,ok,fail,options};r.watches.push(watch);return watch.id;},
    clearWatch(id){r.cleared.push(id);}};
  r.share=GPS.createRideShare({
    start(id,hooks){
      const record={id,hooks,handle:null};r.starts.push(record);const session=r.session;
      record.handle=GPS.startDriverLocationShare({geolocation:r.geo,openSocket(){
        const socket={url:GPS.tripLocationUrl("wss://api.example.test",id),readyState:0,sent:[],closed:false,
          send(value){this.sent.push(JSON.parse(value));},close(){this.closed=true;},onopen:null,onclose:null,onerror:null,onmessage:null};
        r.sockets.push(socket);return socket;
      },token:()=>r.token?"synthetic-token":null,now:()=>r.wall,...clock,
      onState:hooks.onState,onEnd:hooks.onEnd,onPermissionDenied:hooks.onPermissionDenied,
      allowSend:hooks.allowSend,isCurrent:()=>r.session===session,subscribeOnline:()=>()=>{}});
      return record.handle;
    },
    async fetchRows(){r.fetches++;if(r.gate)await r.gate;if(r.error)throw r.error;return structuredClone(r.rows);},
    session:()=>r.session,origin:()=>r.origin,hasToken:()=>r.token,now:()=>r.elapsed,wall:()=>r.wall,
    newId:()=>"synthetic-grant-"+(++r.ids),observePermission(fn){r.permission=fn;return ()=>{if(r.permission===fn)r.permission=null;};},...clock
  });
  r.frames=()=>r.sockets.flatMap(s=>s.sent.filter(frame=>frame.type==="loc"));
  r.fix=(lat=52.7,lng=58.6)=>r.watches.at(-1)?.ok({coords:{latitude:lat,longitude:lng,heading:null}});
  r.openSocket=()=>{const socket=r.sockets.at(-1);if(socket){socket.readyState=1;socket.onopen?.({});}return socket;};
  r.advance=async(ms,{wall=ms}={})=>{
    r.elapsed+=ms;r.wall+=wall;
    for(let n=0;n<100;n++){const timer=r.timers.find(tm=>!tm.done&&tm.at<=r.elapsed);if(!timer)break;timer.done=true;timer.fn();await settle();}
  };
  r.click=async(id=101,closing=false,rideId=7)=>{const ticket=r.share.beginClick(id,rideId,closing);await r.share.finishClick(ticket,true);await settle();return ticket;};
  r.started=()=>r.starts.at(-1);
  r.activeWatches=()=>r.watches.filter(w=>!r.cleared.includes(w.id));
  return r;
}
