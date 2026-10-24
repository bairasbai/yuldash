// ================================================================
//  «Удалить аккаунт» на сайте: отказ сервера 409 — его текст, а не «проверь связь» (решение 24.10.2026).
//
//  Было: любой отказ удаления прятался за «Не получилось удалить аккаунт. Проверь связь и попробуй ещё
//  раз». Водитель после отмены в пути (удаление держится 6 ч, а при открытом разборе — до его закрытия),
//  должник по комиссии, пассажир с живой бронью — все видели «проверь связь» и жали снова. Сервер же
//  объясняет, что закрыть и когда можно: этот текст человеку и нужен. Сбой сети — как раньше.
//
//  Исполняем настоящий экран на стенде shallow-harness.mjs.
// ================================================================
import assert from "node:assert/strict";
import { bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
globalThis.localStorage ??= { getItem: () => null, setItem() {}, removeItem() {} };

const HOLD = "Удалить аккаунт можно будет не раньше 24.10 18:40 по времени Уфы.";

async function openSettings(failure) {
  const Screen = await bundleComponent("src/screens/SettingsScreen.tsx", {
    define: { "import.meta.env": "{}" },
    mocks: {
      "react-router-dom": `export const useNavigate=()=>p=>{globalThis.__set.nav.push(p);};`,
      "/i18n/lang": langMock("ru"),
      "/auth/AuthProvider": `export const useAuth=()=>({user:{id:1},logout:async()=>{globalThis.__set.loggedOut++;}});`,
      "/theme": `export const useTheme=()=>["system",()=>{}];`,
      "/fontScale": `export const useFontScale=()=>["normal",()=>{}];`,
      "/uiPrefs": `export const getUiPref=()=>true;export const setUiPref=()=>{};`,
      "/api/client": `export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}`,
      "/api/auth": `import {ApiError} from "/api/client";
        export const deleteAccount=async()=>{const f=globalThis.__set.failure;
          if(!f) return {ok:true};
          if(f.status) throw new ApiError(f.status,f.message);
          throw new TypeError("Failed to fetch");};`,
    },
  });
  globalThis.__set = { nav: [], loggedOut: 0, failure };
  const h = mount(Screen);
  h.render();
  await h.settle();
  button(h.tree, "Удалить аккаунт")?.props.onClick();
  await h.settle();
  button(h.tree, "Да, удалить навсегда").props.onClick();
  await h.settle();
  return h;
}

await t.test("409 сервера (отмена в пути) — его текст, без «проверь связь»; аккаунт на месте", async () => {
  const h = await openSettings({ status: 409, message: HOLD });
  const s = text(h.tree);
  assert.ok(s.includes(HOLD), s);
  assert.ok(!s.includes("Проверь связь"), "отказ сервера выдан за сбой сети");
  assert.equal(globalThis.__set.loggedOut, 0, "отказ — а сессию стёрли");
  assert.deepEqual(globalThis.__set.nav, []);
});

await t.test("сбой сети — как раньше: «Проверь связь»", async () => {
  const h = await openSettings({ message: "" });
  assert.ok(text(h.tree).includes("Не получилось удалить аккаунт. Проверь связь и попробуй ещё раз."));
});

await t.test("удаление прошло — выход и экран старта", async () => {
  await openSettings(null);
  assert.equal(globalThis.__set.loggedOut, 1);
  assert.deepEqual(globalThis.__set.nav, ["/splash"]);
});

t.done();
