// Повтор, который идёт только пока экран виден.
// Подделываем React-хуки и document.

globalThis.window = { setInterval, clearInterval };

let visibility = "visible";
const listeners = new Map();
globalThis.document = {
  get visibilityState() { return visibility; },
  addEventListener: (ev, fn) => listeners.set(ev, [...(listeners.get(ev) ?? []), fn]),
  removeEventListener: (ev, fn) =>
    listeners.set(ev, (listeners.get(ev) ?? []).filter((f) => f !== fn)),
};
const fire = (ev) => (listeners.get(ev) ?? []).forEach((f) => f());

// Мини-реализация useEffect/useRef, достаточная для одного хука.
const effects = [];
const cleanups = [];
globalThis.__react = {
  useRef: (v) => ({ current: v }),
  useEffect: (fn) => effects.push(fn),
};

const src = await import("node:fs").then((fs) =>
  fs.readFileSync(new URL("./.bundles/vis-bundle.mjs", import.meta.url), "utf8")
);
// Подменяем импорт react на нашу заглушку
const patched = src.replace(/import\s*\{[^}]*\}\s*from\s*"react";?/, "const { useRef, useEffect } = globalThis.__react;");
const url = "data:text/javascript;base64," + Buffer.from(patched).toString("base64");
const { useVisibleInterval } = await import(url);

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

let calls = 0;
useVisibleInterval(50, () => calls++, true);
// прогоняем накопленные эффекты
effects.forEach((e) => {
  const c = e();
  if (typeof c === "function") cleanups.push(c);
});

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

await wait(130);
const whileVisible = calls;
check(whileVisible >= 2, "пока экран виден — повтор идёт", `тиков: ${whileVisible}`);

// Ушли в фон
visibility = "hidden";
fire("visibilitychange");
const atHide = calls;
await wait(150);
check(calls === atHide, "в фоне повтор молчит — батарея и трафик целы", `тиков: ${calls - atHide}`);

// Вернулись
visibility = "visible";
fire("visibilitychange");
check(calls === atHide + 1, "на возвращении обновляем сразу, не ждём круга", `тиков: ${calls - atHide}`);

await wait(130);
check(calls > atHide + 1, "и повтор снова пошёл", `тиков всего: ${calls}`);

// Экран закрыли
cleanups.forEach((c) => c());
const atStop = calls;
await wait(150);
check(calls === atStop, "экран закрыт — таймер снят, ничего не тикает");

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
