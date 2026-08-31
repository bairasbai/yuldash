// ================================================================
//  Экран без состояний — не готовый экран.
//
//  Три правила, каждое выросло из настоящей беды:
//
//  1. Пустой экран, который ЗНАЕТ дорогу, обязан ВЕСТИ, а не подсказывать.
//     Открыл «Везу» — пусто, и написано «возьми заказ во вкладке „Заказы“».
//     Куда идти, сказали. Кнопки нет. Человек закрывает экран, ищет вкладку
//     глазами, иногда не находит и уходит совсем. Экран уже знает следующий
//     шаг — он его написал текстом; значит знание есть, а действия нет.
//     (Тот же сторож есть в приложении: EmptyStateLeadsGuardTest.)
//
//  2. Список, который грузится, должен уметь показать ошибку. Экран без
//     ветки ошибки на сельском интернете выглядит как вечная загрузка:
//     человек не понимает, сломалось у него или у нас, и жать ему нечего.
//
//  3. У ошибки должен быть повтор. «Что-то пошло не так» без кнопки —
//     тупик: единственный выход это перезагрузить страницу целиком.
//
//  Читает исходники напрямую — сборка не нужна.
// ================================================================
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, dirname, relative } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const SRC = join(HERE, "..", "src");

let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};

/** Все .tsx рекурсивно. */
function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...walk(p));
    else if (name.endsWith(".tsx")) out.push(p);
  }
  return out;
}

const files = walk(SRC).map((p) => ({
  name: relative(SRC, p).replace(/\\/g, "/"),
  code: readFileSync(p, "utf8"),
}));

const lineOf = (code, index) => code.slice(0, index).split("\n").length;

// ---------------------------------------------------------------- 1. пустое ведёт
/** Слова, которыми текст указывает дорогу. Если они есть — нужна и кнопка. */
const POINTS = ["вкладк", "Открой", "открой", "Перейди", "перейди", "бүлегендә"];

const deadEnds = [];
for (const f of files) {
  // Блок пустого состояния целиком: <div className="state…"> … </div>
  for (const m of f.code.matchAll(/<div className="state[^"]*"[\s\S]*?<\/div>\s*\)/g)) {
    const block = m[0];
    if (!POINTS.some((w) => block.includes(w))) continue;
    if (block.includes("<button") || block.includes("onClick")) continue;
    deadEnds.push(`${f.name}:${lineOf(f.code, m.index)}`);
  }
}
check(
  deadEnds.length === 0,
  "пустой экран, который называет дорогу, по ней и ведёт",
  deadEnds.slice(0, 4).join("; ")
);

// ---------------------------------------------------------------- 2. загрузка → ошибка
/**
 * ЭКРАН, где есть состояние загрузки, обязан иметь и состояние ошибки.
 *
 * Смотрим только `screens/` — с обёрток и мелких блоков спрашивать нечего:
 * `RequireAuth` при неудаче уводит на вход, а блок вроде «Мои пассажиры»
 * осознанно ПРЯЧЕТСЯ целиком (нет поездок — нет блока), и пустая карточка
 * с ошибкой в кабинете была бы хуже его отсутствия.
 *
 * Ищем по признакам, а не по именам: `LoadingList`/`"loading"` против
 * `ErrorState`/`"error"`/`setError`/`hidden`. Экраны, которые сами ничего
 * не загружают, пропускаем: спрашивать с них ветку ошибки не о чем.
 */
const loadingNoError = [];
for (const f of files) {
  if (!f.name.startsWith("screens/")) continue;
  // Экран-гейт (Splash) ничего не грузит сам: он ждёт проверку сессии и уводит
  // навигацией в любом исходе. Ветке ошибки там неоткуда взяться и незачем.
  const loadsSomething = /(fetch[A-Z]\w*|api(Get|Post|Delete|Upload))\s*\(/.test(f.code);
  if (!loadsSomething) continue;
  const hasLoading = /<LoadingList|=== "loading"|"loading"\)/.test(f.code);
  if (!hasLoading) continue;
  const hasError = /<ErrorState|=== "error"|"error"\)|setError\(|"hidden"/.test(f.code);
  if (!hasError) loadingNoError.push(f.name);
}
check(
  loadingNoError.length === 0,
  "у экрана с загрузкой есть и ветка ошибки",
  loadingNoError.slice(0, 4).join("; ")
);

// ---------------------------------------------------------------- 3. у ошибки есть повтор
/**
 * `ErrorState` без `onRetry` — тупик. Пропускаем места, где повтор передаётся
 * переменной (`onRetry={retry}`) — это тот же повтор.
 */
const noRetry = [];
for (const f of files) {
  for (const m of f.code.matchAll(/<ErrorState([^/>]*)\/>/g)) {
    if (!/onRetry/.test(m[1])) noRetry.push(`${f.name}:${lineOf(f.code, m.index)}`);
  }
}
check(
  noRetry.length === 0,
  "у состояния ошибки есть кнопка «Повторить»",
  noRetry.slice(0, 4).join("; ")
);

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
