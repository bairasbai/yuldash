// Память о платеже, ради которого человек ушёл в банк.
const store = new Map();
globalThis.localStorage = {
  get length() { return store.size; },
  key: (i) => [...store.keys()][i] ?? null,
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
};

const m = await import("./.bundles/pay-bundle.mjs");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

const K = "yuldash.owner.:yuldash.pendingPayment";

// --- 1. Запомнили → вернулись → знаем, что проверять и куда вести ---
m.rememberPayment(9012, "boost", "/driver");
let p = m.readPendingPayment();
check(p?.paymentId === 9012 && p?.what === "boost" && p?.backTo === "/driver",
  "после возврата из банка знаем номер платежа и куда вернуть", JSON.stringify(p));

// --- 2. Оплата подтверждена → забываем ---
m.forgetPayment();
check(m.readPendingPayment() === null, "подтверждённый платёж забыт");

// --- 3. Ничего не платили — экран возврата не выдумывает платёж ---
check(m.readPendingPayment() === null, "без платежа → null");

// --- 4. Вчерашний платёж не всплывает через сутки ---
store.set(K, JSON.stringify({ paymentId: 1, what: "trip", backTo: "/x", at: Date.now() - 25 * 3600 * 1000 }));
check(m.readPendingPayment() === null, "платежу больше суток → не показываем");
check(!store.has(K), "и он вычищен");

// --- 5. Мусор в хранилище не роняет экран возврата ---
store.set(K, "{сломано");
check(m.readPendingPayment() === null, "повреждённая запись → null");
store.set(K, JSON.stringify({ what: "trip" }));
check(m.readPendingPayment() === null, "запись без номера платежа → не доверяем");

// --- 6. Запрет хранилища (приватный режим) не ломает оплату ---
const real = globalThis.localStorage;
globalThis.localStorage = {
  get length() { throw new Error("SecurityError"); },
  key: () => { throw new Error("SecurityError"); },
  getItem: () => { throw new Error("SecurityError"); },
  setItem: () => { throw new Error("SecurityError"); },
  removeItem: () => { throw new Error("SecurityError"); },
};
let crashed = false;
try {
  m.rememberPayment(5, "debt", "/taxi-drive");
  m.readPendingPayment();
  m.forgetPayment();
} catch { crashed = true; }
globalThis.localStorage = real;
check(!crashed, "приватный режим Safari не ломает уход в банк");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
