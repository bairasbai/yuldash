// Что остаётся на общем телефоне после выхода из аккаунта.
const store = new Map();
globalThis.localStorage = {
  get length() { return store.size; },
  key: (i) => [...store.keys()][i] ?? null,
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};

const sess = new Map();
globalThis.sessionStorage = {
  get length() { return sess.size; },
  key: (i) => [...sess.keys()][i] ?? null,
  getItem: (k) => (sess.has(k) ? sess.get(k) : null),
  setItem: (k, v) => sess.set(k, String(v)),
  removeItem: (k) => sess.delete(k),
};

const p = await import("./.bundles/privacy-bundle.mjs");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

// Телефон матери: она пользовалась сайтом.
const personal = {
  "yuldash.consents": '{"offer":true,"privacy":true,"geo":true}',
  "yuldash.role": "driver",
  "yuldash.filterPrefs": '{"from":"Баймак","to":"Сибай"}',
  "yuldash.taxi.activeOrder": "8123",
  "yuldash.push.web.enabled": "1",
  "yuldash.cid": "cid-матери",
};
const device = {
  "yuldash.lang": "ba",
  "yuldash.theme": "dark",
  "yuldash.fontScale": "large",
  "yuldash.simpleMode": "1",
  "yuldash.introSeen": "1",
  "yuldash.onboarded": "1",
  "yuldash.install.dismissed": "1",
  "yuldash.chatSafetySeen": "1",
  "yuldash.pref.sounds": "1",
};
const owner = "account-A", prefix = "yuldash.owner.account-A:";
Object.entries(personal).forEach(([k, v]) => store.set(prefix + k, v));
Object.entries(device).forEach(([k, v]) => store.set(k, v));
sess.set("yuldash.session", owner);
// Эти живут в сессионном хранилище вкладки.
sess.set("yuldash.winterAsk.4471", "1");
sess.set("yuldash.winterAsk.4472", "1");
sess.set("yuldash.tracked.booking_created", "1");
sess.set("yuldash.scroll.rides", "1200");
sess.set("yuldash.chunkReload", "1");

p.clearPersonalLocal(owner);

for (const k of Object.keys(personal)) {
  check(!store.has(prefix + k), `стёрто личное: ${k}`);
}
for (const [k, v] of Object.entries(device)) {
  check(store.get(k) === v, `осталась настройка телефона: ${k}`, store.get(k) ?? "—");
}

check(!sess.has("yuldash.winterAsk.4471") && !sess.has("yuldash.winterAsk.4472"), "стёрты отметки «доехал?» по чужим броням");
check(!sess.has("yuldash.tracked.booking_created"), "стёрты отметки отправленных событий");
check(!sess.has("yuldash.scroll.rides"), "стёрта память прокрутки — след чужого просмотра");
check(sess.get("yuldash.chunkReload") === "1", "техническая метка в сессии не тронута");

// Согласия — отдельно подчеркнём: без них новый человек считался бы согласившимся.
check(!store.has(prefix + "yuldash.consents"), "согласие с офертой не наследуется новым человеком");

// Приватный режим Safari: хранилище кидает ошибку — не падаем.
const real = globalThis.localStorage;
const realSess = globalThis.sessionStorage;
globalThis.sessionStorage = {
  get length() { throw new Error("SecurityError"); },
  key: () => { throw new Error("SecurityError"); },
  getItem: () => { throw new Error("SecurityError"); },
  setItem: () => { throw new Error("SecurityError"); },
  removeItem: () => { throw new Error("SecurityError"); },
};
globalThis.localStorage = {
  get length() { throw new Error("SecurityError"); },
  key: () => { throw new Error("SecurityError"); },
  getItem: () => { throw new Error("SecurityError"); },
  setItem: () => { throw new Error("SecurityError"); },
  removeItem: () => { throw new Error("SecurityError"); },
};
let crashed = false;
try { p.clearPersonalLocal(owner); } catch { crashed = true; }
globalThis.localStorage = real;
globalThis.sessionStorage = realSess;
check(!crashed, "запрет хранилища не роняет выход из аккаунта");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
