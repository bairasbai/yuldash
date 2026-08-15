// Проверка черновиков форм на собранном модуле.
const store = new Map();
globalThis.localStorage = {
  get length() { return store.size; },
  key: (i) => [...store.keys()][i] ?? null,
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};

const d = await import("./.bundles/draft-bundle.mjs");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

const KEY = "taxi-application";
const P = "yuldash.draft." + KEY;

// --- 1. Записал → прочитал ---
d.writeDraft(KEY, { inn: "027812345678", permit: "АА-123", carClass: "comfort" });
let got = d.readDraft(KEY);
check(got?.inn === "027812345678" && got?.permit === "АА-123", "анкета вернулась целиком", JSON.stringify(got));

// --- 2. Пустого черновика нет ---
check(d.readDraft("никогда-не-было") === null, "пустой ключ → null");

// --- 3. Протухший (старше недели) не отдаётся и вычищается ---
store.set(P, JSON.stringify({ at: Date.now() - 8 * 24 * 3600 * 1000, data: { inn: "старое" } }));
check(d.readDraft(KEY) === null, "черновику больше недели → не подставляем");
check(!store.has(P), "и он сразу вычищен из хранилища");

// --- 4. Повреждённый черновик не роняет форму ---
store.set(P, "{это не json");
check(d.readDraft(KEY) === null, "мусор в хранилище → null, без падения");
store.set(P, JSON.stringify({ data: { inn: "без метки времени" } }));
check(d.readDraft(KEY) === null, "запись без метки времени → не доверяем");

// --- 5. clearDraft стирает своё и только своё ---
d.writeDraft(KEY, { inn: "1" });
d.writeDraft("create-ride", { from: "Уфа" });
store.set("yuldash.token", "не-трогать");
d.clearDraft(KEY);
check(d.readDraft(KEY) === null, "clearDraft стёр свой черновик");
check(d.readDraft("create-ride") !== null, "чужой черновик уцелел");
check(store.get("yuldash.token") === "не-трогать", "токен не тронут");

// --- 6. Выход из аккаунта стирает ВСЕ черновики, но не токен ---
d.writeDraft(KEY, { inn: "чужой инн" });
d.writeDraft("courier-application", { fullName: "чужое имя" });
d.clearAllDrafts();
check(
  d.readDraft(KEY) === null && d.readDraft("create-ride") === null && d.readDraft("courier-application") === null,
  "выход стёр все черновики"
);
check(store.get("yuldash.token") === "не-трогать", "токен и прочее уцелели");

// --- 7. Хранилище недоступно (приватный режим Safari) — не падаем ---
const broken = {
  get length() { throw new Error("SecurityError"); },
  key: () => { throw new Error("SecurityError"); },
  getItem: () => { throw new Error("SecurityError"); },
  setItem: () => { throw new Error("SecurityError"); },
  removeItem: () => { throw new Error("SecurityError"); },
};
const real = globalThis.localStorage;
globalThis.localStorage = broken;
let crashed = false;
try {
  d.writeDraft(KEY, { inn: "1" });
  d.readDraft(KEY);
  d.clearDraft(KEY);
  d.clearAllDrafts();
} catch {
  crashed = true;
}
globalThis.localStorage = real;
check(!crashed, "запрет хранилища не роняет экран");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
