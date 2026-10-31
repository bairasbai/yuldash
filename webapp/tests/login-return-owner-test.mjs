// Выбор посадки гостя («Где садишься?») едет через вход в состоянии истории вкладки. После входа
// выбор принадлежит тому, кто вошёл, а не гостю: LoginScreen ставит ему новую метку владельца.
// Без этого выбор либо терялся (метка гостя не совпадает с новым входом), либо не имел владельца.
import assert from "node:assert/strict";
import { bundleComponent, checker, langMock, mount } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__loginReturn = { generation: "guest", routes: [], state: undefined, deps: null };
globalThis.document = { addEventListener() {}, removeEventListener() {} };
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "/auth/AuthProvider": `export const useAuth=()=>({login(){}});`,
  "/api/client": `export class ApiError extends Error {} export const apiPost=()=>{}; export const getSessionGeneration=()=>globalThis.__loginReturn.generation;`,
  "/api/auth": `export const TELEGRAM_BOT='fixture',lastLoginMethods=()=>['telegram'],fetchLoginMethods=async()=>['telegram'];
    export const tgStart=async()=>({}),tgVerify=async()=>({}),requestSmsCode=async()=>({}),verifySmsCode=async()=>({}),telegramChatUrl=()=>'',telegramStartUrl=()=>'';`,
  "/utils/loginFlow": `export const createLoginFlow=deps=>{globalThis.__loginReturn.deps=deps;return{revive(){},dispose(){},start(){},verify(){},smsRequest(){},smsVerify(){}}};
    export const filterLoginCode=x=>x,nextNeedPhone=c=>c;`,
  "react-router-dom": `export const useLocation=()=>({state:globalThis.__loginReturn.state});export const useNavigate=()=>(...a)=>globalThis.__loginReturn.routes.push(a);`,
});
const guestState = { rideMatch: { rideId: 42, owner: "guest", matched_stop: "Акъяр", matched_from_start: false, matched_dropoff_stop: "Сибай", matched_to_end: false } };

for (const lang of ["ru", "ba"]) {
  const Login = await bundleComponent("src/screens/LoginScreen.tsx", { real: ["/utils/pickupStops"], mocks: mocks(lang) });
  await t.test(`${lang}: после входа выбор посадки гостя переезжает к вошедшему (метка владельца — новый вход)`, () => {
    h.routes = []; h.generation = "guest"; h.state = { from: "/rides/42", returnState: guestState };
    const s = mount(Login); s.render();
    h.generation = "user-1"; // вход состоялся — поколение сменилось
    h.deps.navigate();
    assert.equal(h.routes.length, 1);
    const [to, options] = h.routes[0];
    assert.equal(to, "/rides/42"); assert.equal(options.replace, true);
    assert.equal(options.state.rideMatch.owner, "user-1");
    assert.equal(options.state.rideMatch.matched_stop, "Акъяр"); assert.equal(options.state.rideMatch.matched_dropoff_stop, "Сибай");
    assert.equal(guestState.rideMatch.owner, "guest", "исходная запись истории не переписана");
    s.unmount();
  });
  await t.test(`${lang}: вход без выбора посадки уходит без состояния`, () => {
    h.routes = []; h.generation = "guest"; h.state = { from: "/map" };
    const s = mount(Login); s.render(); h.generation = "user-2"; h.deps.navigate();
    assert.deepEqual(h.routes[0], ["/map", { replace: true }]);
    s.unmount();
  });
}
t.done();
