const f = await import("./.bundles/fmt-bundle.mjs");
let bad = 0;
const check = (ok, label, extra = "") => {
  if (!ok) bad++;
  console.log(`${ok ? "✓" : "✗"} ${label}${extra ? "   " + extra : ""}`);
};
const iso = (s) => s;
// 5 августа 2026, 18:00 UTC
const far = "2026-08-05T13:00:00";
const ruTxt = f.formatWhen(far, true);
const baTxt = f.formatWhen(far, false);
check(/^5 авг/.test(ruTxt), "русский: короткий месяц", ruTxt);
check(/^5 авг/.test(baTxt), "башкирский: свой месяц", baTxt);
const jan = "2026-01-09T10:00:00";
check(f.formatWhen(jan, true).startsWith("9 янв"), "январь по-русски", f.formatWhen(jan, true));
check(f.formatWhen(jan, false).startsWith("9 ғин"), "январь по-башкирски", f.formatWhen(jan, false));
// время в обоих одинаковое
check(ruTxt.slice(-5) === baTxt.slice(-5), "время одинаково в обоих языках", ruTxt.slice(-5));
// никакой строки браузера с точкой («июл.»)
check(!/[а-яё]\./i.test(ruTxt), "без точки после месяца", ruTxt);
console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
